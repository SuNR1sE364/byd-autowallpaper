package com.byd.autodarkwallpaper.util;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机日/夜模式底层信号探测器（调试专用）。
 * <p>
 * 用于实车定位比亚迪 DiLink 切换深浅/日夜模式时真实发生变化的系统字段，覆盖：
 * 1. Configuration.uiMode / UiModeManager / PowerManager
 * 2. BYD carsettings ContentProvider (global / system / secure) 及动态发现的车机相关 Provider
 * 3. Android Settings.Global / Settings.System / Settings.Secure 全量扫描与 Diff
 * 4. SystemProperties (getprop) 全量扫描与 Diff
 * 5. 标准 Android 光线传感器 (Sensor.TYPE_LIGHT) 检查
 * 6. 实时 ContentObserver (notifyForDescendants=true) + 候选广播实时监控
 * <p>
 * 遵循“无静默降级”原则：所有无权限、查询异常或命令失败均完整记录异常类型与原始信息。
 */
public final class DayNightProbeHelper {

    private static final String TAG = "DayNightProbe";

    private static final String[] CARSETTINGS_URIS = new String[]{
            "content://carsettings/global",
            "content://carsettings/system",
            "content://carsettings/secure",
            "content://com.byd.carsettings/global",
            "content://com.byd.carsettings/system",
            "content://com.byd.carsettings/secure"
    };

    private static final String[] PROBE_KNOWN_KEYS = new String[]{
            "sys_screen_mode",
            "support_change_system_uimode",
            "cur_screen_mode",
            "current_screen_mode",
            "screen_mode",
            "day_night_mode",
            "daynight_mode",
            "night_mode",
            "dark_mode",
            "ui_night_mode",
            "theme_mode",
            "skin_mode",
            "byd_screen_mode",
            "byd_day_night",
            "byd_day_night_mode",
            "byd_night_mode",
            "auto_night_mode"
    };

    private static final String[] CANDIDATE_BROADCAST_ACTIONS = new String[]{
            Intent.ACTION_CONFIGURATION_CHANGED,
            "AUTONAVI_STANDARD_BROADCAST_RECV",
            "android.car.intent.action.DAY_NIGHT_MODE_CHANGED",
            "android.app.action.ENTER_CAR_MODE",
            "android.app.action.EXIT_CAR_MODE",
            "com.byd.intent.action.DAY_NIGHT_MODE",
            "com.byd.action.DAY_NIGHT_MODE",
            "com.byd.action.DAY_NIGHT_MODE_CHANGED",
            "com.byd.action.SCREEN_MODE_CHANGED",
            "com.byd.action.THEME_CHANGED",
            "com.byd.action.SKIN_CHANGED",
            "com.byd.action.NIGHT_MODE_CHANGED",
            "com.byd.systemui.ACTION_THEME_CHANGE"
    };

    private static final Pattern INTERESTING_KEY_PATTERN = Pattern.compile(
            "(night|dark|day|light|theme|skin|screen_mode|uimode|ui_mode|color_mode|"
                    + "brightness|illum|lamp|headlight|ambient|display_mode|byd|carsetting|car_mode)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PROVIDER_FILTER_PATTERN = Pattern.compile(
            "(byd|car|setting|theme|skin|mode|display|screen|status|systemui|launcher|auto|dilink)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GETPROP_LINE_PATTERN = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$");

    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    public static final class TableDump {
        public final String sourceName;
        public final Map<String, String> values = new TreeMap<>();
        public String error;
        public String extraNote;

        TableDump(String sourceName) {
            this.sourceName = sourceName;
        }
    }

    public static final class ProbeSnapshot {
        public final String label;
        public final long capturedAtMs;
        public final String capturedTimeText;

        public final Map<String, String> summaryFields = new LinkedHashMap<>();
        public final Map<String, TableDump> tables = new LinkedHashMap<>();
        public final List<String> discoveredProviders = new ArrayList<>();
        public String providerScanError;
        public String lightSensorSummary;

        ProbeSnapshot(String label) {
            this.label = label;
            this.capturedAtMs = System.currentTimeMillis();
            synchronized (TIME_FMT) {
                this.capturedTimeText = TIME_FMT.format(new Date(this.capturedAtMs));
            }
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean liveWatching = false;
    private final List<ContentObserver> activeObservers = new ArrayList<>();
    private BroadcastReceiver liveBroadcastReceiver;

    public DayNightProbeHelper(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isLiveWatching() {
        return liveWatching;
    }

    /**
     * 抓取一次全量车机状态快照（请在后台线程调用），并自动将核心摘要写入 AppLogger。
     */
    public ProbeSnapshot captureSnapshot(String label, boolean logKeywordHighlights) {
        long startNs = System.nanoTime();
        ProbeSnapshot snap = new ProbeSnapshot(label);

        collectUiAndPowerSummary(snap);
        collectLightSensorInfo(snap);
        collectDiscoveredProviders(snap);

        // 1. 比亚迪 carsettings 候选 URI
        for (String uriStr : CARSETTINGS_URIS) {
            snap.tables.put(uriStr, dumpCarSettingsUri(uriStr));
        }

        // 2. Android 标准 Settings 三张表
        snap.tables.put("Settings.Global", dumpSettingsTable("Settings.Global", Settings.Global.CONTENT_URI));
        snap.tables.put("Settings.System", dumpSettingsTable("Settings.System", Settings.System.CONTENT_URI));
        snap.tables.put("Settings.Secure", dumpSettingsTable("Settings.Secure", Settings.Secure.CONTENT_URI));

        // 3. SystemProperties (getprop)
        snap.tables.put("getprop", dumpGetprop());

        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        logSnapshotReport(snap, costMs, logKeywordHighlights);
        return snap;
    }

    /**
     * 对比两次快照并输出完整 Diff 到 AppLogger，同时返回简要摘要字符串用于界面提示。
     */
    public String diffAndLog(ProbeSnapshot before, ProbeSnapshot after) {
        if (before == null || after == null) {
            return "缺少对比快照";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("================ 快照 Diff 对比 ================\n");
        sb.append("基线: [").append(before.label).append(" @ ").append(before.capturedTimeText)
                .append("]  --->  当前: [").append(after.label).append(" @ ").append(after.capturedTimeText)
                .append("]\n");

        int totalDiffCount = 0;

        // 1. 对比核心 UI / Power 摘要
        List<String> summaryDiffs = diffMaps(before.summaryFields, after.summaryFields);
        sb.append("\n[1] UIMode / 电源状态变化 (").append(summaryDiffs.size()).append(" 项):\n");
        if (summaryDiffs.isEmpty()) {
            sb.append("  (无变化)\n");
        } else {
            totalDiffCount += summaryDiffs.size();
            for (String line : summaryDiffs) {
                sb.append("  ").append(line).append('\n');
            }
        }

        // 2. 对比各表 (carsettings / Settings.* / getprop)
        Set<String> allTableNames = new TreeSet<>();
        allTableNames.addAll(before.tables.keySet());
        allTableNames.addAll(after.tables.keySet());

        int sectionIndex = 2;
        for (String tableName : allTableNames) {
            TableDump t1 = before.tables.get(tableName);
            TableDump t2 = after.tables.get(tableName);
            Map<String, String> m1 = (t1 != null) ? t1.values : Collections.emptyMap();
            Map<String, String> m2 = (t2 != null) ? t2.values : Collections.emptyMap();

            List<String> diffs = diffMaps(m1, m2);
            boolean hasErrors = (t1 != null && t1.error != null) || (t2 != null && t2.error != null);

            // 对于 carsettings 中本就不存在/无数据的备用 URI，若两次均空且错误相同则简化展示
            if (diffs.isEmpty() && m1.isEmpty() && m2.isEmpty() && hasErrors) {
                continue;
            }

            sb.append("\n[").append(sectionIndex++).append("] ").append(tableName)
                    .append(" 变化 (").append(diffs.size()).append(" 项, 样本数 ")
                    .append(m1.size()).append(" -> ").append(m2.size()).append("):\n");
            if (diffs.isEmpty()) {
                sb.append("  (无变化)\n");
            } else {
                totalDiffCount += diffs.size();
                int limit = Math.min(diffs.size(), 150);
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(diffs.get(i)).append('\n');
                }
                if (diffs.size() > limit) {
                    sb.append("  ... 其余 ").append(diffs.size() - limit).append(" 项已省略\n");
                }
            }
        }

        sb.append("\n================ Diff 结束 (总变化 ").append(totalDiffCount).append(" 项) ================");
        AppLogger.i(TAG, sb.toString());

        if (totalDiffCount == 0) {
            return "未检测到任何键值变化（请查看日志中 carsettings/Provider 权限报错）";
        }
        return "共发现 " + totalDiffCount + " 项变化，已完整写入日志";
    }

    /**
     * 开启实时 URI 变动与广播监听。
     */
    public synchronized void startLiveWatch() {
        if (liveWatching) {
            AppLogger.i(TAG, "实时变动监听已在运行中");
            return;
        }
        liveWatching = true;
        AppLogger.i(TAG, "========== 开始实时变动监听 ==========");

        List<Uri> watchUris = new ArrayList<>();
        watchUris.add(Settings.Global.CONTENT_URI);
        watchUris.add(Settings.System.CONTENT_URI);
        watchUris.add(Settings.Secure.CONTENT_URI);
        for (String uriStr : CARSETTINGS_URIS) {
            watchUris.add(Uri.parse(uriStr));
        }

        ContentResolver cr = appContext.getContentResolver();
        for (final Uri baseUri : watchUris) {
            ContentObserver observer = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange(selfChange, null);
                }

                @Override
                public void onChange(boolean selfChange, Uri changedUri) {
                    String uriDesc = (changedUri != null) ? changedUri.toString() : (baseUri + " (未附带子URI)");
                    String valueDesc = tryReadSingleChangedUriValue(changedUri);
                    AppLogger.i(TAG, "[实时Observer] onChange: uri=" + uriDesc
                            + " | selfChange=" + selfChange
                            + (valueDesc != null ? (" | 当前值=" + valueDesc) : ""));
                }
            };
            try {
                cr.registerContentObserver(baseUri, true, observer);
                activeObservers.add(observer);
                AppLogger.i(TAG, "[实时Observer] 注册成功: " + baseUri);
            } catch (Throwable t) {
                AppLogger.w(TAG, "[实时Observer] 注册失败: " + baseUri
                        + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        liveBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                StringBuilder extraSb = new StringBuilder();
                Bundle extras = intent.getExtras();
                if (extras != null && !extras.isEmpty()) {
                    for (String key : extras.keySet()) {
                        if (extraSb.length() > 0) {
                            extraSb.append(", ");
                        }
                        Object val = extras.get(key);
                        extraSb.append(key).append('=').append(val);
                    }
                }
                int uiMode = appContext.getResources().getConfiguration().uiMode;
                AppLogger.i(TAG, "[实时广播] action=" + intent.getAction()
                        + " | extras={" + extraSb + "}"
                        + " | 当前uiMode=0x" + Integer.toHexString(uiMode));
            }
        };

        IntentFilter filter = new IntentFilter();
        for (String action : CANDIDATE_BROADCAST_ACTIONS) {
            filter.addAction(action);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(liveBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(liveBroadcastReceiver, filter);
            }
            AppLogger.i(TAG, "[实时广播] 已注册 " + CANDIDATE_BROADCAST_ACTIONS.length + " 个候选 Action");
        } catch (Throwable t) {
            AppLogger.e(TAG, "[实时广播] 注册失败", t);
            liveBroadcastReceiver = null;
        }
    }

    /**
     * 停止实时变动监听。
     */
    public synchronized void stopLiveWatch() {
        if (!liveWatching) {
            return;
        }
        liveWatching = false;
        ContentResolver cr = appContext.getContentResolver();
        for (ContentObserver obs : activeObservers) {
            try {
                cr.unregisterContentObserver(obs);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销 ContentObserver 失败", t);
            }
        }
        activeObservers.clear();

        if (liveBroadcastReceiver != null) {
            try {
                appContext.unregisterReceiver(liveBroadcastReceiver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销实时广播接收器失败", t);
            }
            liveBroadcastReceiver = null;
        }
        AppLogger.i(TAG, "========== 已停止实时变动监听 ==========");
    }

    private void collectUiAndPowerSummary(ProbeSnapshot snap) {
        try {
            Configuration cfg = appContext.getResources().getConfiguration();
            int uiMode = cfg.uiMode;
            int nightMask = uiMode & Configuration.UI_MODE_NIGHT_MASK;
            int typeMask = uiMode & Configuration.UI_MODE_TYPE_MASK;
            snap.summaryFields.put("Configuration.uiMode(hex)", "0x" + Integer.toHexString(uiMode));
            snap.summaryFields.put("Configuration.nightMask", formatNightMask(nightMask));
            snap.summaryFields.put("Configuration.typeMask", formatUiTypeMask(typeMask));
        } catch (Throwable t) {
            snap.summaryFields.put("Configuration.error", formatThrowable(t));
        }

        try {
            UiModeManager umm = (UiModeManager) appContext.getSystemService(Context.UI_MODE_SERVICE);
            if (umm == null) {
                snap.summaryFields.put("UiModeManager", "null");
            } else {
                snap.summaryFields.put("UiModeManager.getNightMode()", formatManagerNightMode(umm.getNightMode()));
                snap.summaryFields.put("UiModeManager.getCurrentModeType()", formatUiTypeMask(umm.getCurrentModeType()));
            }
        } catch (Throwable t) {
            snap.summaryFields.put("UiModeManager.error", formatThrowable(t));
        }

        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                snap.summaryFields.put("PowerManager.isPowerSaveMode()", String.valueOf(pm.isPowerSaveMode()));
            }
        } catch (Throwable t) {
            snap.summaryFields.put("PowerManager.error", formatThrowable(t));
        }

        try {
            int brightness = Settings.System.getInt(appContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            snap.summaryFields.put("Settings.System.screen_brightness", String.valueOf(brightness));
        } catch (Throwable t) {
            snap.summaryFields.put("Settings.System.screen_brightness", "读取失败: " + formatThrowable(t));
        }

        try {
            int bMode = Settings.System.getInt(appContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
            snap.summaryFields.put("Settings.System.screen_brightness_mode", String.valueOf(bMode));
        } catch (Throwable t) {
            snap.summaryFields.put("Settings.System.screen_brightness_mode", "读取失败: " + formatThrowable(t));
        }
    }

    private void collectLightSensorInfo(ProbeSnapshot snap) {
        try {
            SensorManager sm = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                snap.lightSensorSummary = "SensorManager 为 null";
                return;
            }
            List<Sensor> lightSensors = sm.getSensorList(Sensor.TYPE_LIGHT);
            if (lightSensors == null || lightSensors.isEmpty()) {
                snap.lightSensorSummary = "系统未暴露标准 Android Sensor.TYPE_LIGHT (数量=0)";
                return;
            }
            Sensor sensor = lightSensors.get(0);
            final float[] reading = new float[]{Float.NaN};
            final CountDownLatch latch = new CountDownLatch(1);
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event != null && event.values != null && event.values.length > 0) {
                        reading[0] = event.values[0];
                        latch.countDown();
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            boolean registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
            try {
                if (registered) {
                    latch.await(600, TimeUnit.MILLISECONDS);
                }
            } finally {
                sm.unregisterListener(listener);
            }
            snap.lightSensorSummary = "发现 " + lightSensors.size() + " 个 TYPE_LIGHT: name=" + sensor.getName()
                    + ", vendor=" + sensor.getVendor()
                    + ", lux=" + (Float.isNaN(reading[0]) ? "600ms内无上报" : String.valueOf(reading[0]));
            snap.summaryFields.put("Sensor.TYPE_LIGHT(lux)",
                    Float.isNaN(reading[0]) ? "无上报" : String.valueOf(reading[0]));
        } catch (Throwable t) {
            snap.lightSensorSummary = "TYPE_LIGHT 检查异常: " + formatThrowable(t);
        }
    }

    private void collectDiscoveredProviders(ProbeSnapshot snap) {
        try {
            PackageManager pm = appContext.getPackageManager();
            List<ProviderInfo> providers = pm.queryContentProviders(null, 0, 0);
            if (providers == null) {
                snap.providerScanError = "queryContentProviders 返回 null";
                return;
            }
            for (ProviderInfo pi : providers) {
                String auth = pi.authority != null ? pi.authority : "";
                String pkg = pi.packageName != null ? pi.packageName : "";
                if (PROVIDER_FILTER_PATTERN.matcher(auth).find() || PROVIDER_FILTER_PATTERN.matcher(pkg).find()) {
                    snap.discoveredProviders.add(
                            "authority=" + auth
                                    + " | pkg=" + pkg
                                    + " | exported=" + pi.exported
                                    + " | readPerm=" + pi.readPermission
                                    + " | writePerm=" + pi.writePermission);
                }
            }
            Collections.sort(snap.discoveredProviders);
        } catch (Throwable t) {
            snap.providerScanError = formatThrowable(t);
        }
    }

    private TableDump dumpCarSettingsUri(String uriStr) {
        TableDump dump = new TableDump(uriStr);
        Uri baseUri = Uri.parse(uriStr);
        ContentResolver cr = appContext.getContentResolver();

        try (Cursor cursor = cr.query(baseUri, null, null, null, null)) {
            if (cursor == null) {
                dump.error = "query 返回 null（Provider 不存在或不支持直接全表查询）";
            } else {
                String[] cols = cursor.getColumnNames();
                dump.extraNote = "columns=" + Arrays.toString(cols) + ", rows=" + cursor.getCount();
                int keyIdx = findColumnIndex(cols, "name", "key", "_id");
                int valIdx = findColumnIndex(cols, "value", "val");
                int rowIndex = 0;
                while (cursor.moveToNext()) {
                    rowIndex++;
                    if (keyIdx >= 0 && valIdx >= 0) {
                        String k = readCursorCell(cursor, keyIdx);
                        String v = readCursorCell(cursor, valIdx);
                        dump.values.put(k != null ? k : ("row#" + rowIndex), v != null ? v : "<null>");
                    } else {
                        StringBuilder rowSb = new StringBuilder();
                        for (int c = 0; c < cols.length; c++) {
                            if (c > 0) {
                                rowSb.append(" | ");
                            }
                            rowSb.append(cols[c]).append('=').append(readCursorCell(cursor, c));
                        }
                        dump.values.put("row#" + rowIndex, rowSb.toString());
                    }
                }
            }
        } catch (Throwable t) {
            dump.error = formatThrowable(t);
        }

        // 若全表查询返回空或 null，再尝试逐个键路径查询与 ContentProvider.call(GET_*)（兼容仿 SettingsProvider 实现）
        if (dump.values.isEmpty()) {
            List<String> keyProbeNotes = new ArrayList<>();
            for (String key : PROBE_KNOWN_KEYS) {
                Uri keyUri = Uri.withAppendedPath(baseUri, key);
                try (Cursor c = cr.query(keyUri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        String[] cols = c.getColumnNames();
                        StringBuilder rowSb = new StringBuilder();
                        for (int i = 0; i < cols.length; i++) {
                            if (i > 0) {
                                rowSb.append(", ");
                            }
                            rowSb.append(cols[i]).append('=').append(readCursorCell(c, i));
                        }
                        dump.values.put(key, rowSb.toString());
                    }
                } catch (Throwable t) {
                    if (keyProbeNotes.isEmpty()) {
                        keyProbeNotes.add("单键query(" + key + ")失败: " + formatThrowable(t));
                    }
                }
            }

            if (dump.values.isEmpty()) {
                String seg = baseUri.getLastPathSegment();
                String callMethod = (seg != null && !seg.isEmpty()) ? ("GET_" + seg) : "GET_global";
                boolean loggedCallErr = false;
                for (String key : PROBE_KNOWN_KEYS) {
                    try {
                        Bundle res = cr.call(baseUri, callMethod, key, null);
                        if (res != null && !res.isEmpty()) {
                            Object v = res.get("value");
                            dump.values.put(key + " (via " + callMethod + ")",
                                    v != null ? String.valueOf(v) : res.toString());
                        }
                    } catch (Throwable t) {
                        if (!loggedCallErr) {
                            loggedCallErr = true;
                            keyProbeNotes.add("call(" + callMethod + "," + key + ")失败: " + formatThrowable(t));
                        }
                    }
                }
            }

            if (!keyProbeNotes.isEmpty()) {
                dump.extraNote = (dump.extraNote == null ? "" : dump.extraNote + " | ")
                        + String.join("; ", keyProbeNotes);
            }
        }
        return dump;
    }

    private TableDump dumpSettingsTable(String name, Uri contentUri) {
        TableDump dump = new TableDump(name);
        ContentResolver cr = appContext.getContentResolver();
        try (Cursor cursor = cr.query(contentUri, new String[]{"name", "value"}, null, null, null)) {
            if (cursor == null) {
                dump.error = "query 返回 null";
                return dump;
            }
            while (cursor.moveToNext()) {
                String k = cursor.getString(0);
                String v = cursor.getString(1);
                if (k != null) {
                    dump.values.put(k, v != null ? v : "<null>");
                }
            }
        } catch (Throwable t) {
            dump.error = formatThrowable(t);
        }
        return dump;
    }

    private TableDump dumpGetprop() {
        TableDump dump = new TableDump("getprop");
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                String pendingKey = null;
                StringBuilder pendingVal = null;
                while ((line = br.readLine()) != null) {
                    Matcher m = GETPROP_LINE_PATTERN.matcher(line);
                    if (m.matches()) {
                        if (pendingKey != null) {
                            dump.values.put(pendingKey, pendingVal.toString());
                            pendingKey = null;
                            pendingVal = null;
                        }
                        dump.values.put(m.group(1), m.group(2));
                    } else if (line.startsWith("[") && line.contains("]: [")) {
                        int sep = line.indexOf("]: [");
                        pendingKey = line.substring(1, sep);
                        pendingVal = new StringBuilder(line.substring(sep + 4));
                    } else if (pendingKey != null) {
                        pendingVal.append('\n');
                        if (line.endsWith("]")) {
                            pendingVal.append(line, 0, line.length() - 1);
                            dump.values.put(pendingKey, pendingVal.toString());
                            pendingKey = null;
                            pendingVal = null;
                        } else {
                            pendingVal.append(line);
                        }
                    }
                }
                if (pendingKey != null) {
                    dump.values.put(pendingKey, pendingVal.toString());
                }
            }
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                dump.error = "getprop 执行超时 (>5s)";
            } else if (process.exitValue() != 0) {
                dump.error = "getprop exitCode=" + process.exitValue();
            }
        } catch (Throwable t) {
            dump.error = formatThrowable(t);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return dump;
    }

    private String tryReadSingleChangedUriValue(Uri uri) {
        if (uri == null) {
            return null;
        }
        String lastSeg = uri.getLastPathSegment();
        String uriStr = uri.toString();
        ContentResolver cr = appContext.getContentResolver();
        try {
            if (uriStr.startsWith(Settings.Global.CONTENT_URI.toString() + "/") && lastSeg != null) {
                return Settings.Global.getString(cr, lastSeg);
            }
            if (uriStr.startsWith(Settings.System.CONTENT_URI.toString() + "/") && lastSeg != null) {
                return Settings.System.getString(cr, lastSeg);
            }
            if (uriStr.startsWith(Settings.Secure.CONTENT_URI.toString() + "/") && lastSeg != null) {
                return Settings.Secure.getString(cr, lastSeg);
            }
            try (Cursor c = cr.query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String[] cols = c.getColumnNames();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cols.length; i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(cols[i]).append('=').append(readCursorCell(c, i));
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable t) {
            return "<读取失败: " + t.getClass().getSimpleName() + ": " + t.getMessage() + ">";
        }
        return null;
    }

    private void logSnapshotReport(ProbeSnapshot snap, long costMs, boolean includeHighlights) {
        StringBuilder sb = new StringBuilder();
        sb.append("================ 车机日/夜信号快照 [").append(snap.label).append("] ================\n");
        sb.append("抓取时间: ").append(snap.capturedTimeText).append(" | 耗时: ").append(costMs).append("ms\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(") | Build=").append(Build.DISPLAY).append('\n');

        sb.append("\n[核心 UI / 亮度 / 光感]\n");
        for (Map.Entry<String, String> e : snap.summaryFields.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        sb.append("  TYPE_LIGHT 详情 = ").append(snap.lightSensorSummary).append('\n');

        sb.append("\n[各数据源采集结果]\n");
        for (TableDump dump : snap.tables.values()) {
            sb.append("  - ").append(dump.sourceName).append(": ").append(dump.values.size()).append(" 条");
            if (dump.extraNote != null) {
                sb.append(" (").append(dump.extraNote).append(')');
            }
            if (dump.error != null) {
                sb.append(" [异常: ").append(dump.error).append(']');
            }
            sb.append('\n');
        }

        if (includeHighlights) {
            // 1. 若 carsettings 有数据，全量列出（或前 120 条）
            for (String uriStr : CARSETTINGS_URIS) {
                TableDump dump = snap.tables.get(uriStr);
                if (dump != null && !dump.values.isEmpty()) {
                    sb.append("\n[").append(uriStr).append(" 键值列表 (共 ").append(dump.values.size()).append(" 项)]\n");
                    int count = 0;
                    for (Map.Entry<String, String> e : dump.values.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(" = ").append(trimForLog(e.getValue())).append('\n');
                        if (++count >= 120) {
                            sb.append("  ... 其余 ").append(dump.values.size() - count).append(" 项已省略（全量仍参与 Diff）\n");
                            break;
                        }
                    }
                }
            }

            // 2. Settings.* 和 getprop 中命中日夜/主题/车机关键词的条目
            String[] highlightTables = new String[]{"Settings.Global", "Settings.System", "Settings.Secure", "getprop"};
            for (String tName : highlightTables) {
                TableDump dump = snap.tables.get(tName);
                if (dump == null || dump.values.isEmpty()) {
                    continue;
                }
                List<String> matched = new ArrayList<>();
                for (Map.Entry<String, String> e : dump.values.entrySet()) {
                    if (INTERESTING_KEY_PATTERN.matcher(e.getKey()).find()) {
                        matched.add(e.getKey() + " = " + trimForLog(e.getValue()));
                    }
                }
                sb.append("\n[").append(tName).append(" 关键词命中 (").append(matched.size()).append(" / ")
                        .append(dump.values.size()).append(")]\n");
                int limit = Math.min(matched.size(), 80);
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(matched.get(i)).append('\n');
                }
                if (matched.size() > limit) {
                    sb.append("  ... 其余 ").append(matched.size() - limit).append(" 项已省略（全量仍参与 Diff）\n");
                }
            }

            // 3. 发现的车机相关 ContentProvider
            sb.append("\n[车机相关 ContentProvider 扫描 (命中 ").append(snap.discoveredProviders.size()).append(" 个)]\n");
            if (snap.providerScanError != null) {
                sb.append("  扫描异常: ").append(snap.providerScanError).append('\n');
            } else if (snap.discoveredProviders.isEmpty()) {
                sb.append("  (未发现匹配项)\n");
            } else {
                int limit = Math.min(snap.discoveredProviders.size(), 80);
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(snap.discoveredProviders.get(i)).append('\n');
                }
                if (snap.discoveredProviders.size() > limit) {
                    sb.append("  ... 其余 ").append(snap.discoveredProviders.size() - limit).append(" 个已省略\n");
                }
            }
        }

        sb.append("================ 快照 [").append(snap.label).append("] 结束 ================");
        AppLogger.i(TAG, sb.toString());
    }

    private static List<String> diffMaps(Map<String, String> before, Map<String, String> after) {
        List<String> diffs = new ArrayList<>();
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(before.keySet());
        allKeys.addAll(after.keySet());
        for (String key : allKeys) {
            boolean hasBefore = before.containsKey(key);
            boolean hasAfter = after.containsKey(key);
            String v1 = before.get(key);
            String v2 = after.get(key);
            if (!hasBefore && hasAfter) {
                diffs.add("[新增] " + key + " = " + trimForLog(v2));
            } else if (hasBefore && !hasAfter) {
                diffs.add("[移除] " + key + " (原值=" + trimForLog(v1) + ")");
            } else if (!equalsNullable(v1, v2)) {
                diffs.add("[变更] " + key + " : " + trimForLog(v1) + "  --->  " + trimForLog(v2));
            }
        }
        return diffs;
    }

    private static int findColumnIndex(String[] cols, String... candidates) {
        if (cols == null) {
            return -1;
        }
        for (String cand : candidates) {
            for (int i = 0; i < cols.length; i++) {
                if (cand.equalsIgnoreCase(cols[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String readCursorCell(Cursor c, int idx) {
        try {
            int type = c.getType(idx);
            switch (type) {
                case Cursor.FIELD_TYPE_NULL:
                    return "<null>";
                case Cursor.FIELD_TYPE_INTEGER:
                    return String.valueOf(c.getLong(idx));
                case Cursor.FIELD_TYPE_FLOAT:
                    return String.valueOf(c.getDouble(idx));
                case Cursor.FIELD_TYPE_STRING:
                    return c.getString(idx);
                case Cursor.FIELD_TYPE_BLOB:
                    byte[] blob = c.getBlob(idx);
                    return "<blob:" + (blob == null ? 0 : blob.length) + "B>";
                default:
                    return c.getString(idx);
            }
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String trimForLog(String val) {
        if (val == null) {
            return "<null>";
        }
        String singleLine = val.replace("\r", "\\r").replace("\n", "\\n");
        if (singleLine.length() > 180) {
            return singleLine.substring(0, 180) + "...(len=" + singleLine.length() + ")";
        }
        return singleLine;
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String formatThrowable(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String formatNightMask(int mask) {
        switch (mask) {
            case Configuration.UI_MODE_NIGHT_YES:
                return "UI_MODE_NIGHT_YES (0x20)";
            case Configuration.UI_MODE_NIGHT_NO:
                return "UI_MODE_NIGHT_NO (0x10)";
            case Configuration.UI_MODE_NIGHT_UNDEFINED:
                return "UI_MODE_NIGHT_UNDEFINED (0x00)";
            default:
                return "0x" + Integer.toHexString(mask);
        }
    }

    private static String formatUiTypeMask(int typeMask) {
        switch (typeMask) {
            case Configuration.UI_MODE_TYPE_NORMAL:
                return "TYPE_NORMAL (1)";
            case Configuration.UI_MODE_TYPE_DESK:
                return "TYPE_DESK (2)";
            case Configuration.UI_MODE_TYPE_CAR:
                return "TYPE_CAR (3)";
            case Configuration.UI_MODE_TYPE_TELEVISION:
                return "TYPE_TV (4)";
            case Configuration.UI_MODE_TYPE_APPLIANCE:
                return "TYPE_APPLIANCE (5)";
            case Configuration.UI_MODE_TYPE_WATCH:
                return "TYPE_WATCH (6)";
            case Configuration.UI_MODE_TYPE_VR_HEADSET:
                return "TYPE_VR (7)";
            case Configuration.UI_MODE_TYPE_UNDEFINED:
                return "TYPE_UNDEFINED (0)";
            default:
                return "TYPE_UNKNOWN(" + typeMask + ")";
        }
    }

    private static String formatManagerNightMode(int mode) {
        switch (mode) {
            case UiModeManager.MODE_NIGHT_AUTO:
                return "MODE_NIGHT_AUTO (0)";
            case UiModeManager.MODE_NIGHT_NO:
                return "MODE_NIGHT_NO (1)";
            case UiModeManager.MODE_NIGHT_YES:
                return "MODE_NIGHT_YES (2)";
            case UiModeManager.MODE_NIGHT_CUSTOM:
                return "MODE_NIGHT_CUSTOM (3)";
            default:
                return "UNKNOWN(" + mode + ")";
        }
    }
}
