package com.byd.autodarkwallpaper.monitor;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.byd.autodarkwallpaper.util.AppLogger;

/**
 * 比亚迪车机日/夜模式状态监听器。
 * <p>
 * 监听比亚迪车机真实日夜键 {@code Settings.Global.key_byd_night_mode}（1 = 夜间/深色，2 = 日间/浅色）
 * 以及辅助档位键 {@code content://carsettings/global} 中的 {@code time_wallpaper_mode}（1 = 手动夜间，2 = 手动日间，3 = 自动）。
 */
public class VehicleStateMonitor {

    private static final String TAG = "VehicleMonitor";
    public static final int UNSET_VALUE = Integer.MIN_VALUE;

    public static final String SETTINGS_GLOBAL_BYD_NIGHT_MODE = "key_byd_night_mode";
    public static final int BYD_NIGHT_MODE_DARK = 1;
    public static final int BYD_NIGHT_MODE_LIGHT = 2;

    public static final Uri CARSETTINGS_GLOBAL_URI = Uri.parse("content://carsettings/global");
    public static final String CARSETTINGS_KEY_WALLPAPER_MODE = "time_wallpaper_mode";
    public static final int BYD_WALLPAPER_MODE_NIGHT = 1;
    public static final int BYD_WALLPAPER_MODE_DAY = 2;
    public static final int BYD_WALLPAPER_MODE_AUTO = 3;

    public static final class BydDayNightReading {
        public final int nightModeCode;
        public final String nightModeRaw;
        public final int wallpaperModeCode;
        public final String wallpaperModeRaw;
        public final String error;

        public BydDayNightReading(int nightModeCode, String nightModeRaw,
                                  int wallpaperModeCode, String wallpaperModeRaw,
                                  String error) {
            this.nightModeCode = nightModeCode;
            this.nightModeRaw = nightModeRaw;
            this.wallpaperModeCode = wallpaperModeCode;
            this.wallpaperModeRaw = wallpaperModeRaw;
            this.error = error;
        }

        public boolean isValid() {
            return error == null
                    && (nightModeCode == BYD_NIGHT_MODE_DARK || nightModeCode == BYD_NIGHT_MODE_LIGHT);
        }

        public boolean isDark() {
            return nightModeCode == BYD_NIGHT_MODE_DARK;
        }
    }

    public interface OnStateChangedListener {
        void onStateUpdated(TelemetrySnapshot snapshot);
    }

    public static final class TelemetrySnapshot {
        public int bydNightMode = UNSET_VALUE;
        public String bydNightModeRaw = null;
        public int bydWallpaperMode = UNSET_VALUE;
        public String bydNightModeError = null;

        public int systemUiModeNightMask = Configuration.UI_MODE_NIGHT_UNDEFINED;
        public int uiModeManagerNightMode = UNSET_VALUE;
        public String systemError = null;

        public long lastUpdatedTimestamp = 0L;

        public TelemetrySnapshot copy() {
            TelemetrySnapshot c = new TelemetrySnapshot();
            c.bydNightMode = this.bydNightMode;
            c.bydNightModeRaw = this.bydNightModeRaw;
            c.bydWallpaperMode = this.bydWallpaperMode;
            c.bydNightModeError = this.bydNightModeError;
            c.systemUiModeNightMask = this.systemUiModeNightMask;
            c.uiModeManagerNightMode = this.uiModeManagerNightMode;
            c.systemError = this.systemError;
            c.lastUpdatedTimestamp = this.lastUpdatedTimestamp;
            return c;
        }
    }

    public static final class EvaluationResult {
        public final boolean valid;
        public final boolean targetDark;
        public final String reason;
        public final String errorDetail;

        public EvaluationResult(boolean valid, boolean targetDark, String reason, String errorDetail) {
            this.valid = valid;
            this.targetDark = targetDark;
            this.reason = reason;
            this.errorDetail = errorDetail;
        }
    }

    private final Context appContext;
    private final Object stateLock = new Object();
    private final TelemetrySnapshot snapshot = new TelemetrySnapshot();

    private volatile boolean started = false;
    private OnStateChangedListener listener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ComponentCallbacks componentCallbacks;
    private BroadcastReceiver configReceiver;
    private ContentObserver bydNightModeObserver;
    private ContentObserver carSettingsObserver;

    public VehicleStateMonitor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void setOnStateChangedListener(OnStateChangedListener listener) {
        this.listener = listener;
    }

    public TelemetrySnapshot getSnapshot() {
        synchronized (stateLock) {
            return snapshot.copy();
        }
    }

    public synchronized void start() {
        if (started) {
            AppLogger.i(TAG, "VehicleStateMonitor 已处于启动状态，执行一次全量轮询");
            pollAllStatesNow();
            return;
        }
        started = true;
        AppLogger.i(TAG, "正在启动车机日夜模式监听器...");
        registerSystemUiModeListeners();
        pollAllStatesNow();
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        AppLogger.i(TAG, "正在停止车机日夜模式监听器...");

        if (componentCallbacks != null) {
            try {
                appContext.unregisterComponentCallbacks(componentCallbacks);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销 ComponentCallbacks 失败", t);
            }
            componentCallbacks = null;
        }

        if (configReceiver != null) {
            try {
                appContext.unregisterReceiver(configReceiver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销 ACTION_CONFIGURATION_CHANGED 接收器失败", t);
            }
            configReceiver = null;
        }

        if (bydNightModeObserver != null) {
            try {
                appContext.getContentResolver().unregisterContentObserver(bydNightModeObserver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销 key_byd_night_mode ContentObserver 失败", t);
            }
            bydNightModeObserver = null;
        }

        if (carSettingsObserver != null) {
            try {
                appContext.getContentResolver().unregisterContentObserver(carSettingsObserver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "注销 carsettings/global ContentObserver 失败", t);
            }
            carSettingsObserver = null;
        }
    }

    public synchronized void pollAllStatesNow() {
        AppLogger.i(TAG, "开始主动读取车机日夜模式状态...");
        readSystemUiMode(true);
        synchronized (stateLock) {
            snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
        }
        notifyListener();
    }

    /**
     * 后台轻量级定时轮询（仅在数值变化时打日志与通知，防止 ContentObserver 漏报）。
     */
    public void pollQuietly() {
        if (!started) {
            return;
        }
        if (readSystemUiMode(false)) {
            synchronized (stateLock) {
                snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
            }
            notifyListener();
        }
    }

    private void registerSystemUiModeListeners() {
        ContentResolver cr = appContext.getContentResolver();

        bydNightModeObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                AppLogger.i(TAG, "收到 Settings.Global.key_byd_night_mode 变化回调: uri=" + uri);
                readSystemUiMode(true);
                notifyListener();
            }
        };
        try {
            cr.registerContentObserver(
                    Settings.Global.getUriFor(SETTINGS_GLOBAL_BYD_NIGHT_MODE),
                    false,
                    bydNightModeObserver);
            AppLogger.i(TAG, "已注册 Settings.Global.key_byd_night_mode 实时 ContentObserver");
        } catch (Throwable t) {
            AppLogger.e(TAG, "注册 Settings.Global.key_byd_night_mode 监听器失败", t);
            bydNightModeObserver = null;
        }

        carSettingsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onChange(selfChange, null);
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                AppLogger.i(TAG, "收到 content://carsettings/global 变化回调: uri=" + uri);
                if (readSystemUiMode(true)) {
                    notifyListener();
                }
            }
        };
        try {
            cr.registerContentObserver(CARSETTINGS_GLOBAL_URI, true, carSettingsObserver);
            AppLogger.i(TAG, "已注册 content://carsettings/global 实时 ContentObserver");
        } catch (Throwable t) {
            AppLogger.w(TAG, "注册 content://carsettings/global 监听器失败（不影响主监听）: " + formatThrowable(t));
            carSettingsObserver = null;
        }

        componentCallbacks = new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                int nightMask = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                AppLogger.i(TAG, "收到 ComponentCallbacks.onConfigurationChanged，uiMode nightMask="
                        + formatSystemNightMask(nightMask));
                readSystemUiMode(true);
                notifyListener();
            }

            @Override
            public void onLowMemory() {
                AppLogger.w(TAG, "收到系统 onLowMemory 回调");
            }
        };
        appContext.registerComponentCallbacks(componentCallbacks);

        configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AppLogger.i(TAG, "收到系统广播 ACTION_CONFIGURATION_CHANGED");
                readSystemUiMode(true);
                notifyListener();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        appContext.registerReceiver(configReceiver, filter);
    }

    /**
     * 快速同步查询比亚迪车机当前是否处于夜间模式（仅读 Settings.Global.key_byd_night_mode，适合 attachBaseContext 调用）。
     *
     * @return Boolean.TRUE = 夜间 (1), Boolean.FALSE = 日间 (2), null = 字段不存在或取值未识别
     */
    public static Boolean queryBydDarkStateOrNull(Context context) {
        if (context == null) {
            return null;
        }
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), SETTINGS_GLOBAL_BYD_NIGHT_MODE);
            if (raw == null) {
                return null;
            }
            int code = Integer.parseInt(raw.trim());
            if (code == BYD_NIGHT_MODE_DARK) {
                return Boolean.TRUE;
            }
            if (code == BYD_NIGHT_MODE_LIGHT) {
                return Boolean.FALSE;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 读取比亚迪车机真实日/夜模式（Settings.Global.key_byd_night_mode）以及档位模式（carsettings/global time_wallpaper_mode）。
     */
    public static BydDayNightReading readBydNightModeNow(Context context) {
        ContentResolver cr = context.getApplicationContext().getContentResolver();
        String nightRaw = null;
        int nightCode = UNSET_VALUE;
        String nightError = null;
        try {
            nightRaw = Settings.Global.getString(cr, SETTINGS_GLOBAL_BYD_NIGHT_MODE);
            if (nightRaw == null || nightRaw.trim().isEmpty()) {
                nightError = "Settings.Global 中不存在 " + SETTINGS_GLOBAL_BYD_NIGHT_MODE + " 字段";
            } else {
                nightCode = Integer.parseInt(nightRaw.trim());
                if (nightCode != BYD_NIGHT_MODE_DARK && nightCode != BYD_NIGHT_MODE_LIGHT) {
                    nightError = "未识别的 " + SETTINGS_GLOBAL_BYD_NIGHT_MODE + " 取值: " + nightRaw
                            + "（预期 1=夜间, 2=日间）";
                }
            }
        } catch (Throwable t) {
            nightError = "读取 Settings.Global." + SETTINGS_GLOBAL_BYD_NIGHT_MODE + " 失败: " + formatThrowable(t);
        }

        String wpRaw = null;
        int wpCode = UNSET_VALUE;
        try (Cursor c = cr.query(
                CARSETTINGS_GLOBAL_URI,
                new String[]{"key", "value"},
                "key=?",
                new String[]{CARSETTINGS_KEY_WALLPAPER_MODE},
                null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    String k = c.getString(0);
                    if (CARSETTINGS_KEY_WALLPAPER_MODE.equals(k)) {
                        wpRaw = c.getString(1);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // 某些版本可能不支持 selection 过滤，回退全表扫描一次
            try (Cursor c = cr.query(CARSETTINGS_GLOBAL_URI, null, null, null, null)) {
                if (c != null) {
                    int keyIdx = c.getColumnIndex("key");
                    int valIdx = c.getColumnIndex("value");
                    if (keyIdx >= 0 && valIdx >= 0) {
                        while (c.moveToNext()) {
                            if (CARSETTINGS_KEY_WALLPAPER_MODE.equals(c.getString(keyIdx))) {
                                wpRaw = c.getString(valIdx);
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t2) {
                AppLogger.w(TAG, "读取 carsettings/global." + CARSETTINGS_KEY_WALLPAPER_MODE
                        + " 失败（辅助信息不影响主判断）: " + formatThrowable(t2));
            }
        }
        if (wpRaw != null && !wpRaw.trim().isEmpty()) {
            try {
                wpCode = Integer.parseInt(wpRaw.trim());
            } catch (NumberFormatException ignored) {
                wpCode = UNSET_VALUE;
            }
        }

        return new BydDayNightReading(nightCode, nightRaw, wpCode, wpRaw, nightError);
    }

    private boolean readSystemUiMode(boolean verboseLog) {
        BydDayNightReading bydReading = readBydNightModeNow(appContext);

        int nightMask = Configuration.UI_MODE_NIGHT_UNDEFINED;
        int managerNightMode = UNSET_VALUE;
        String sysErr = null;
        try {
            Configuration cfg = appContext.getResources().getConfiguration();
            nightMask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            UiModeManager uiModeManager = (UiModeManager) appContext.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null) {
                managerNightMode = uiModeManager.getNightMode();
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取原生系统 uiMode 失败", t);
            sysErr = formatThrowable(t);
        }

        boolean changed;
        synchronized (stateLock) {
            changed = (snapshot.bydNightMode != bydReading.nightModeCode
                    || !equalsNullable(snapshot.bydNightModeRaw, bydReading.nightModeRaw)
                    || snapshot.bydWallpaperMode != bydReading.wallpaperModeCode
                    || !equalsNullable(snapshot.bydNightModeError, bydReading.error)
                    || snapshot.systemUiModeNightMask != nightMask
                    || snapshot.uiModeManagerNightMode != managerNightMode
                    || !equalsNullable(snapshot.systemError, sysErr));
            snapshot.bydNightMode = bydReading.nightModeCode;
            snapshot.bydNightModeRaw = bydReading.nightModeRaw;
            snapshot.bydWallpaperMode = bydReading.wallpaperModeCode;
            snapshot.bydNightModeError = bydReading.error;
            snapshot.systemUiModeNightMask = nightMask;
            snapshot.uiModeManagerNightMode = managerNightMode;
            snapshot.systemError = sysErr;
            snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
        }
        if (verboseLog || changed) {
            String msg = "比亚迪日夜状态读取: key_byd_night_mode=" + formatBydNightMode(bydReading.nightModeCode, bydReading.nightModeRaw)
                    + ", time_wallpaper_mode=" + formatBydWallpaperMode(bydReading.wallpaperModeCode)
                    + " | 原生uiMode=" + formatSystemNightMask(nightMask);
            if (bydReading.error != null) {
                AppLogger.w(TAG, msg + " | 异常=" + bydReading.error);
            } else {
                AppLogger.i(TAG, msg);
            }
        }
        return changed;
    }

    private void notifyListener() {
        OnStateChangedListener currentListener = this.listener;
        if (currentListener != null) {
            currentListener.onStateUpdated(getSnapshot());
        }
    }

    /**
     * 评估当前应当切换为日间还是夜间壁纸。
     */
    public EvaluationResult evaluateTargetDarkState() {
        return evaluateSnapshot(getSnapshot());
    }

    public static EvaluationResult evaluateSnapshot(TelemetrySnapshot s) {
        if (s == null) {
            return new EvaluationResult(false, false, "比亚迪日夜模式未就绪", "TelemetrySnapshot 为 null");
        }
        if (s.bydNightModeError != null) {
            return new EvaluationResult(false, false, "比亚迪日夜模式读取异常", s.bydNightModeError);
        }
        String modeHint = formatBydWallpaperModeShort(s.bydWallpaperMode);
        if (s.bydNightMode == BYD_NIGHT_MODE_DARK) {
            return new EvaluationResult(true, true,
                    "比亚迪车机夜间模式 (key_byd_night_mode=1"
                            + (modeHint != null ? ", 档位=" + modeHint : "") + ")",
                    null);
        }
        if (s.bydNightMode == BYD_NIGHT_MODE_LIGHT) {
            return new EvaluationResult(true, false,
                    "比亚迪车机日间模式 (key_byd_night_mode=2"
                            + (modeHint != null ? ", 档位=" + modeHint : "") + ")",
                    null);
        }
        return new EvaluationResult(false, false, "比亚迪日夜模式未就绪",
                "key_byd_night_mode=" + s.bydNightMode + " (raw=" + s.bydNightModeRaw + ")");
    }

    public static String formatBydNightMode(int code, String raw) {
        if (code == BYD_NIGHT_MODE_DARK) {
            return "1 (夜间/深色)";
        }
        if (code == BYD_NIGHT_MODE_LIGHT) {
            return "2 (日间/浅色)";
        }
        if (raw == null) {
            return "未读取/不存在";
        }
        return raw + " (未识别)";
    }

    public static String formatBydWallpaperMode(int code) {
        switch (code) {
            case BYD_WALLPAPER_MODE_NIGHT:
                return "1 (手动夜间)";
            case BYD_WALLPAPER_MODE_DAY:
                return "2 (手动日间)";
            case BYD_WALLPAPER_MODE_AUTO:
                return "3 (自动)";
            case UNSET_VALUE:
                return "未读取";
            default:
                return String.valueOf(code);
        }
    }

    public static String formatBydWallpaperModeShort(int code) {
        switch (code) {
            case BYD_WALLPAPER_MODE_NIGHT:
                return "手动夜间";
            case BYD_WALLPAPER_MODE_DAY:
                return "手动日间";
            case BYD_WALLPAPER_MODE_AUTO:
                return "自动";
            default:
                return null;
        }
    }

    public static String formatSystemNightMask(int mask) {
        switch (mask) {
            case Configuration.UI_MODE_NIGHT_YES:
                return "NIGHT_YES (深色模式)";
            case Configuration.UI_MODE_NIGHT_NO:
                return "NIGHT_NO (浅色模式)";
            case Configuration.UI_MODE_NIGHT_UNDEFINED:
                return "NIGHT_UNDEFINED (未定义)";
            default:
                return "UNKNOWN(" + mask + ")";
        }
    }

    private static String formatThrowable(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
