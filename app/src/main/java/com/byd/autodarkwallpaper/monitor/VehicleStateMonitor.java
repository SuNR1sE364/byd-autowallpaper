package com.byd.autodarkwallpaper.monitor;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Configuration;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;
import android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice;
import android.hardware.bydauto.light.AbsBYDAutoLightListener;
import android.hardware.bydauto.light.BYDAutoLightDevice;
import android.hardware.bydauto.sensor.AbsBYDAutoSensorListener;
import android.hardware.bydauto.sensor.BYDAutoSensorDevice;

import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.util.AppLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机系统状态与比亚迪硬件信号采集器。
 * <p>
 * 采集三类信号：
 * 1. Android 系统深浅色模式 (Configuration.uiMode / UiModeManager)
 * 2. 比亚迪车灯接口 (BYDAutoLightDevice)
 * 3. 比亚迪环境光传感器 (BYDAutoSensorDevice)
 * 外加整车电源档位监听 (BYDAutoBodyworkDevice) 用于上电瞬间强制校准。
 * <p>
 * 遵循“无静默 Fallback”原则：任意接口初始化、监听注册或数据读取失败均记录完整堆栈与原始返回码。
 * 注意：实车 DiLink 150 framework 中 AbsBYDAuto*Listener.onDataChanged(IBYDAutoEvent) 为 final 方法，
 * 子类禁止重写 onDataChanged，只能重写具体业务回调方法（如 onLightOn/onLightIntensityChanged 等）。
 */
public class VehicleStateMonitor {

    private static final String TAG = "VehicleMonitor";
    public static final int UNSET_VALUE = Integer.MIN_VALUE;

    public static final String[] BYD_HARDWARE_PERMISSIONS = new String[]{
            "android.permission.BYDAUTO_LIGHT_GET",
            "android.permission.BYDAUTO_LIGHT_COMMON",
            "android.permission.BYDAUTO_LIGHT_SET",
            "android.permission.BYDAUTO_SENSOR_GET",
            "android.permission.BYDAUTO_SENSOR_COMMON",
            "android.permission.BYDAUTO_SENSOR_SET",
            "android.permission.BYDAUTO_BODYWORK_GET",
            "android.permission.BYDAUTO_BODYWORK_COMMON",
            "android.permission.BYDAUTO_BODYWORK_SET",
            "android.permission.BYDAUTO_SETTING_GET",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_INSTRUMENT_GET",
            "android.permission.BYDAUTO_INSTRUMENT_COMMON"
    };

    public interface OnStateChangedListener {
        void onStateUpdated(TelemetrySnapshot snapshot);
    }

    public static final class TelemetrySnapshot {
        public int systemUiModeNightMask = Configuration.UI_MODE_NIGHT_UNDEFINED;
        public int uiModeManagerNightMode = UNSET_VALUE;
        public String systemError = null;

        public boolean lightDeviceConnected = false;
        public String lightDeviceError = "尚未初始化";
        public int sideLightStatus = UNSET_VALUE;
        public int lowBeamStatus = UNSET_VALUE;
        public int highBeamStatus = UNSET_VALUE;
        public int autoLightStatus = UNSET_VALUE;

        public boolean sensorDeviceConnected = false;
        public String sensorDeviceError = "尚未初始化";
        public int lightIntensityLevel = UNSET_VALUE;

        public boolean bodyworkDeviceConnected = false;
        public String bodyworkDeviceError = "尚未初始化";
        public int powerLevel = UNSET_VALUE;

        public long lastUpdatedTimestamp = 0L;

        public TelemetrySnapshot copy() {
            TelemetrySnapshot c = new TelemetrySnapshot();
            c.systemUiModeNightMask = this.systemUiModeNightMask;
            c.uiModeManagerNightMode = this.uiModeManagerNightMode;
            c.systemError = this.systemError;
            c.lightDeviceConnected = this.lightDeviceConnected;
            c.lightDeviceError = this.lightDeviceError;
            c.sideLightStatus = this.sideLightStatus;
            c.lowBeamStatus = this.lowBeamStatus;
            c.highBeamStatus = this.highBeamStatus;
            c.autoLightStatus = this.autoLightStatus;
            c.sensorDeviceConnected = this.sensorDeviceConnected;
            c.sensorDeviceError = this.sensorDeviceError;
            c.lightIntensityLevel = this.lightIntensityLevel;
            c.bodyworkDeviceConnected = this.bodyworkDeviceConnected;
            c.bodyworkDeviceError = this.bodyworkDeviceError;
            c.powerLevel = this.powerLevel;
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

    private ComponentCallbacks componentCallbacks;
    private BroadcastReceiver configReceiver;

    private LightDeviceBridge lightBridge;
    private SensorDeviceBridge sensorBridge;
    private BodyworkDeviceBridge bodyworkBridge;

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

    /**
     * 检查全部比亚迪硬件权限在当前设备上的授予状态及系统 protectionLevel，
     * 返回所有已声明且当前未授予（DENIED）的权限列表以供动态申请。
     */
    public static List<String> checkAndLogBydPermissions(Context context) {
        List<String> denied = new ArrayList<>();
        String report = buildBydPermissionReport(context, denied);
        AppLogger.i(TAG, "比亚迪硬件权限诊断报告:\n" + report);
        return denied;
    }

    public static String buildBydPermissionReport(Context context, List<String> deniedOut) {
        Context appCtx = context.getApplicationContext();
        PackageManager pm = appCtx.getPackageManager();
        StringBuilder sb = new StringBuilder();
        for (String perm : BYD_HARDWARE_PERMISSIONS) {
            String shortName = perm.replace("android.permission.", "");
            boolean definedInSystem = true;
            String levelDesc;
            try {
                PermissionInfo info = pm.getPermissionInfo(perm, 0);
                levelDesc = formatProtectionLevel(info);
            } catch (PackageManager.NameNotFoundException e) {
                definedInSystem = false;
                levelDesc = "系统未定义";
            } catch (Throwable t) {
                levelDesc = "查询异常(" + t.getClass().getSimpleName() + ")";
            }

            boolean granted = (appCtx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED);
            if (!granted && definedInSystem && deniedOut != null) {
                deniedOut.add(perm);
            }
            sb.append(" - ").append(shortName)
                    .append(": ").append(granted ? "GRANTED" : "DENIED")
                    .append(" [protection=").append(levelDesc).append("]\n");
        }
        return sb.toString().trim();
    }

    private static String formatProtectionLevel(PermissionInfo info) {
        int base = info.getProtection();
        String baseName;
        switch (base) {
            case PermissionInfo.PROTECTION_NORMAL:
                baseName = "normal";
                break;
            case PermissionInfo.PROTECTION_DANGEROUS:
                baseName = "dangerous";
                break;
            case PermissionInfo.PROTECTION_SIGNATURE:
                baseName = "signature";
                break;
            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM:
                baseName = "signatureOrSystem";
                break;
            case PermissionInfo.PROTECTION_INTERNAL:
                baseName = "internal";
                break;
            default:
                baseName = "unknown(" + base + ")";
                break;
        }
        int flags = info.getProtectionFlags();
        StringBuilder sb = new StringBuilder(baseName);
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            sb.append("|privileged");
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            sb.append("|development");
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0) {
            sb.append("|preinstalled");
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
            sb.append("|appop");
        }
        return sb.toString();
    }

    public synchronized void start() {
        if (started) {
            AppLogger.i(TAG, "VehicleStateMonitor 已处于启动状态，执行一次全量轮询与重连检查");
            pollAllStatesNow();
            return;
        }
        started = true;
        AppLogger.i(TAG, "正在启动车机状态与比亚迪硬件监听器...");

        checkAndLogBydPermissions(appContext);
        registerSystemUiModeListeners();
        initLightDevice();
        initSensorDevice();
        initBodyworkDevice();

        pollAllStatesNow();
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        AppLogger.i(TAG, "正在停止车机状态与比亚迪硬件监听器...");

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

        releaseLightDevice();
        releaseSensorDevice();
        releaseBodyworkDevice();
    }

    public synchronized void pollAllStatesNow() {
        AppLogger.i(TAG, "开始主动轮询并检查所有信号源...");
        readSystemUiMode(true);

        boolean lightConnected;
        boolean sensorConnected;
        boolean bodyworkConnected;
        synchronized (stateLock) {
            lightConnected = snapshot.lightDeviceConnected;
            sensorConnected = snapshot.sensorDeviceConnected;
            bodyworkConnected = snapshot.bodyworkDeviceConnected;
        }

        if (lightBridge == null || !lightConnected) {
            releaseLightDevice();
            initLightDevice();
        } else {
            lightBridge.poll(true);
        }

        if (sensorBridge == null || !sensorConnected) {
            releaseSensorDevice();
            initSensorDevice();
        } else {
            sensorBridge.poll(true);
        }

        if (bodyworkBridge == null || !bodyworkConnected) {
            releaseBodyworkDevice();
            initBodyworkDevice();
        } else {
            bodyworkBridge.poll(true);
        }

        synchronized (stateLock) {
            snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
        }
        notifyListener();
    }

    /**
     * 后台轻量级定时轮询（仅对已连接硬件轮询，且仅在数值变化时打日志与通知，作为回调监听失效时的兜底同步）。
     */
    public void pollConnectedHardwareQuietly() {
        if (!started) {
            return;
        }
        boolean anyChanged = readSystemUiMode(false);
        if (lightBridge != null && lightBridge.isConnected()) {
            anyChanged |= lightBridge.poll(false);
        }
        if (sensorBridge != null && sensorBridge.isConnected()) {
            anyChanged |= sensorBridge.poll(false);
        }
        if (bodyworkBridge != null && bodyworkBridge.isConnected()) {
            anyChanged |= bodyworkBridge.poll(false);
        }
        if (anyChanged) {
            synchronized (stateLock) {
                snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
            }
            notifyListener();
        }
    }

    private void registerSystemUiModeListeners() {
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

    private boolean readSystemUiMode(boolean verboseLog) {
        try {
            Configuration cfg = appContext.getResources().getConfiguration();
            int nightMask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            int managerNightMode = UNSET_VALUE;
            UiModeManager uiModeManager = (UiModeManager) appContext.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null) {
                managerNightMode = uiModeManager.getNightMode();
            }
            boolean changed;
            synchronized (stateLock) {
                changed = (snapshot.systemUiModeNightMask != nightMask
                        || snapshot.uiModeManagerNightMode != managerNightMode
                        || snapshot.systemError != null);
                snapshot.systemUiModeNightMask = nightMask;
                snapshot.uiModeManagerNightMode = managerNightMode;
                snapshot.systemError = null;
                snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
            }
            if (verboseLog || changed) {
                AppLogger.i(TAG, "系统 uiMode 轮询: mask=" + formatSystemNightMask(nightMask)
                        + ", UiModeManager.getNightMode()=" + managerNightMode);
            }
            return changed;
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取系统 uiMode 失败", t);
            synchronized (stateLock) {
                snapshot.systemError = formatThrowable(t);
            }
            return true;
        }
    }

    private void initLightDevice() {
        try {
            lightBridge = new LightDeviceBridge(appContext, new LightDeviceBridge.Callback() {
                @Override
                public boolean onLightValuesUpdated(boolean connected, String error,
                                                    int side, int lowBeam, int highBeam, int autoStatus) {
                    boolean changed;
                    synchronized (stateLock) {
                        changed = (snapshot.lightDeviceConnected != connected
                                || !equalsNullable(snapshot.lightDeviceError, error)
                                || snapshot.sideLightStatus != side
                                || snapshot.lowBeamStatus != lowBeam
                                || snapshot.highBeamStatus != highBeam
                                || snapshot.autoLightStatus != autoStatus);
                        snapshot.lightDeviceConnected = connected;
                        snapshot.lightDeviceError = error;
                        snapshot.sideLightStatus = side;
                        snapshot.lowBeamStatus = lowBeam;
                        snapshot.highBeamStatus = highBeam;
                        snapshot.autoLightStatus = autoStatus;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    return changed;
                }
            }, this::notifyListener);
            lightBridge.init();
        } catch (Throwable t) {
            String err = formatThrowable(t);
            AppLogger.e(TAG, "初始化 BYDAutoLightDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.lightDeviceConnected = false;
                snapshot.lightDeviceError = err;
            }
            lightBridge = null;
        }
    }

    private void releaseLightDevice() {
        if (lightBridge != null) {
            lightBridge.release();
            lightBridge = null;
        }
    }

    private void initSensorDevice() {
        try {
            sensorBridge = new SensorDeviceBridge(appContext, new SensorDeviceBridge.Callback() {
                @Override
                public boolean onSensorUpdated(boolean connected, String error, int level) {
                    boolean changed;
                    synchronized (stateLock) {
                        changed = (snapshot.sensorDeviceConnected != connected
                                || !equalsNullable(snapshot.sensorDeviceError, error)
                                || snapshot.lightIntensityLevel != level);
                        snapshot.sensorDeviceConnected = connected;
                        snapshot.sensorDeviceError = error;
                        snapshot.lightIntensityLevel = level;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    return changed;
                }
            }, this::notifyListener);
            sensorBridge.init();
        } catch (Throwable t) {
            String err = formatThrowable(t);
            AppLogger.e(TAG, "初始化 BYDAutoSensorDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.sensorDeviceConnected = false;
                snapshot.sensorDeviceError = err;
            }
            sensorBridge = null;
        }
    }

    private void releaseSensorDevice() {
        if (sensorBridge != null) {
            sensorBridge.release();
            sensorBridge = null;
        }
    }

    private void initBodyworkDevice() {
        try {
            bodyworkBridge = new BodyworkDeviceBridge(appContext, new BodyworkDeviceBridge.Callback() {
                @Override
                public boolean onPowerUpdated(boolean connected, String error, int powerLevel, boolean justPoweredOn) {
                    boolean changed;
                    synchronized (stateLock) {
                        changed = (snapshot.bodyworkDeviceConnected != connected
                                || !equalsNullable(snapshot.bodyworkDeviceError, error)
                                || snapshot.powerLevel != powerLevel);
                        snapshot.bodyworkDeviceConnected = connected;
                        snapshot.bodyworkDeviceError = error;
                        snapshot.powerLevel = powerLevel;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    if (justPoweredOn) {
                        AppLogger.i(TAG, "检测到车辆电源切换为 ON，重新轮询全部传感器与车灯状态");
                        pollAllStatesNow();
                    }
                    return changed;
                }
            }, this::notifyListener);
            bodyworkBridge.init();
        } catch (Throwable t) {
            String err = formatThrowable(t);
            AppLogger.e(TAG, "初始化 BYDAutoBodyworkDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.bodyworkDeviceConnected = false;
                snapshot.bodyworkDeviceError = err;
            }
            bodyworkBridge = null;
        }
    }

    private void releaseBodyworkDevice() {
        if (bodyworkBridge != null) {
            bodyworkBridge.release();
            bodyworkBridge = null;
        }
    }

    private void notifyListener() {
        OnStateChangedListener currentListener = this.listener;
        if (currentListener != null) {
            currentListener.onStateUpdated(getSnapshot());
        }
    }

    /**
     * 根据当前用户选择的触发条件评估应当切换为亮色还是暗色壁纸。
     * 若所选硬件源未连接或返回异常错误码，明确返回 valid=false 并携带错误原因，不做静默降级。
     */
    public EvaluationResult evaluateTargetDarkState(AppPrefs prefs) {
        TelemetrySnapshot s = getSnapshot();
        int source = prefs.getActiveTriggerSource();

        switch (source) {
            case AppPrefs.TRIGGER_SYSTEM_UIMODE:
                return evaluateSystemUiMode(s);
            case AppPrefs.TRIGGER_BYD_LIGHT:
                return evaluateBydLight(s, prefs);
            case AppPrefs.TRIGGER_BYD_SENSOR:
                return evaluateBydSensor(s, prefs);
            case AppPrefs.TRIGGER_ANY_DARK:
                return evaluateAnyDark(s, prefs);
            default:
                return new EvaluationResult(false, false, "未知触发源",
                        "未知 activeTriggerSource=" + source);
        }
    }

    private EvaluationResult evaluateSystemUiMode(TelemetrySnapshot s) {
        if (s.systemError != null) {
            return new EvaluationResult(false, false, "系统 uiMode 读取失败", s.systemError);
        }
        if (s.systemUiModeNightMask == Configuration.UI_MODE_NIGHT_YES) {
            return new EvaluationResult(true, true, "系统 uiMode = NIGHT_YES (暗色)", null);
        }
        if (s.systemUiModeNightMask == Configuration.UI_MODE_NIGHT_NO) {
            return new EvaluationResult(true, false, "系统 uiMode = NIGHT_NO (亮色)", null);
        }
        return new EvaluationResult(false, false, "系统 uiMode 未定义",
                "uiMode mask=" + s.systemUiModeNightMask);
    }

    private EvaluationResult evaluateBydLight(TelemetrySnapshot s, AppPrefs prefs) {
        if (!s.lightDeviceConnected) {
            return new EvaluationResult(false, false, "车灯设备未连接",
                    "BYDAutoLightDevice 不可用: " + s.lightDeviceError);
        }
        boolean useSide = prefs.isLightUseSide();
        boolean useLow = prefs.isLightUseLowBeam();
        boolean useHigh = prefs.isLightUseHighBeam();
        if (!useSide && !useLow && !useHigh) {
            return new EvaluationResult(false, false, "未选择车灯触发项",
                    "示宽灯/近光灯/远光灯均未勾选");
        }

        List<String> onLights = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (useSide) {
            if (s.sideLightStatus == 1) {
                onLights.add("示宽灯=ON");
            } else if (s.sideLightStatus != 0) {
                errors.add("示宽灯返回值异常=" + AppLogger.formatBydReturnCode(s.sideLightStatus));
            }
        }
        if (useLow) {
            if (s.lowBeamStatus == 1) {
                onLights.add("近光灯=ON");
            } else if (s.lowBeamStatus != 0) {
                errors.add("近光灯返回值异常=" + AppLogger.formatBydReturnCode(s.lowBeamStatus));
            }
        }
        if (useHigh) {
            if (s.highBeamStatus == 1) {
                onLights.add("远光灯=ON");
            } else if (s.highBeamStatus != 0) {
                errors.add("远光灯返回值异常=" + AppLogger.formatBydReturnCode(s.highBeamStatus));
            }
        }

        if (!errors.isEmpty()) {
            return new EvaluationResult(false, false, "车灯信号异常", String.join("; ", errors));
        }

        if (!onLights.isEmpty()) {
            return new EvaluationResult(true, true,
                    "车灯触发暗色 (" + String.join(", ", onLights) + ")", s.lightDeviceError);
        }
        return new EvaluationResult(true, false, "已选车灯均关闭 -> 日间亮色", s.lightDeviceError);
    }

    private EvaluationResult evaluateBydSensor(TelemetrySnapshot s, AppPrefs prefs) {
        if (!s.sensorDeviceConnected) {
            return new EvaluationResult(false, false, "光感设备未连接",
                    "BYDAutoSensorDevice 不可用: " + s.sensorDeviceError);
        }
        int level = s.lightIntensityLevel;
        if (level < 1 || level > 5) {
            return new EvaluationResult(false, false, "光感档位无效",
                    "getLightIntensity() 返回=" + AppLogger.formatBydReturnCode(level));
        }
        int threshold = prefs.getSensorThresholdLevel();
        boolean darkWhenLte = prefs.isSensorDarkWhenLte();
        boolean isDark = darkWhenLte ? (level <= threshold) : (level >= threshold);
        String cmp = darkWhenLte ? "<=" : ">=";
        return new EvaluationResult(true, isDark,
                "光感 Level=" + level + " (" + (isDark ? "满足" : "不满足")
                        + " " + cmp + " L" + threshold + ")", s.sensorDeviceError);
    }

    private EvaluationResult evaluateAnyDark(TelemetrySnapshot s, AppPrefs prefs) {
        EvaluationResult sysRes = evaluateSystemUiMode(s);
        EvaluationResult lightRes = evaluateBydLight(s, prefs);
        EvaluationResult sensorRes = evaluateBydSensor(s, prefs);

        List<String> darkReasons = new ArrayList<>();
        List<String> validReasons = new ArrayList<>();
        List<String> failedSources = new ArrayList<>();

        checkAndCollect(sysRes, "系统uiMode", darkReasons, validReasons, failedSources);
        checkAndCollect(lightRes, "车灯", darkReasons, validReasons, failedSources);
        checkAndCollect(sensorRes, "光感", darkReasons, validReasons, failedSources);

        if (validReasons.isEmpty()) {
            return new EvaluationResult(false, false, "联合触发所有信号均不可用",
                    String.join(" | ", failedSources));
        }

        String warning = failedSources.isEmpty() ? null : ("部分信号异常: " + String.join(" | ", failedSources));
        if (!darkReasons.isEmpty()) {
            return new EvaluationResult(true, true,
                    "联合触发暗色: " + String.join(" + ", darkReasons), warning);
        }
        return new EvaluationResult(true, false,
                "联合触发亮色 (全部有效源均判定亮色: " + String.join(", ", validReasons) + ")",
                warning);
    }

    private static void checkAndCollect(EvaluationResult res, String name,
                                        List<String> darkReasons, List<String> validReasons,
                                        List<String> failedSources) {
        if (!res.valid) {
            failedSources.add(name + "[" + res.errorDetail + "]");
            return;
        }
        validReasons.add(name);
        if (res.targetDark) {
            darkReasons.add(res.reason);
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

    public static String formatLightState(int val) {
        if (val == UNSET_VALUE) {
            return "未读取";
        }
        if (val == 0) {
            return "0 (OFF)";
        }
        if (val == 1) {
            return "1 (ON)";
        }
        return AppLogger.formatBydReturnCode(val);
    }

    public static String formatPowerLevel(int val) {
        if (val == UNSET_VALUE) {
            return "未读取";
        }
        switch (val) {
            case 0:
                return "0 (OFF)";
            case 1:
                return "1 (ACC)";
            case 2:
                return "2 (ON)";
            case 255:
                return "255 (INVALID)";
            default:
                return AppLogger.formatBydReturnCode(val);
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

    /**
     * 隔离引用 BYDAutoLightDevice：
     * 1. 不重写 final 方法 onDataChanged(IBYDAutoEvent)，避免 ART 抛出 LinkageError；
     * 2. registerListener 与 poll() 解耦，即使监听注册失败也会继续尝试主动读取硬件状态。
     */
    private static final class LightDeviceBridge {
        interface Callback {
            boolean onLightValuesUpdated(boolean connected, String error,
                                         int side, int lowBeam, int highBeam, int autoStatus);
        }

        private final Context context;
        private final Callback callback;
        private final Runnable asyncNotify;
        private BYDAutoLightDevice device;
        private AbsBYDAutoLightListener lightListener;
        private String listenerWarning;
        private volatile boolean connected;

        LightDeviceBridge(Context context, Callback callback, Runnable asyncNotify) {
            this.context = context;
            this.callback = callback;
            this.asyncNotify = asyncNotify;
        }

        boolean isConnected() {
            return connected && device != null;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoLightDevice.getInstance()...");
            device = BYDAutoLightDevice.getInstance(context);
            if (device == null) {
                connected = false;
                String msg = "BYDAutoLightDevice.getInstance(context) 返回 null（可能缺少 BYDAUTO_LIGHT_GET 权限或服务未就绪）";
                AppLogger.e(TAG, msg);
                callback.onLightValuesUpdated(false, msg,
                        UNSET_VALUE, UNSET_VALUE, UNSET_VALUE, UNSET_VALUE);
                return;
            }

            listenerWarning = null;
            try {
                lightListener = new AbsBYDAutoLightListener() {
                    @Override
                    public void onLightOn(int area) {
                        AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightOn(area=" + area + ")");
                        if (poll(true)) {
                            asyncNotify.run();
                        }
                    }

                    @Override
                    public void onLightOff(int area) {
                        AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightOff(area=" + area + ")");
                        if (poll(true)) {
                            asyncNotify.run();
                        }
                    }

                    @Override
                    public void onLightAutoSwitchOn() {
                        AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightAutoSwitchOn()");
                        if (poll(true)) {
                            asyncNotify.run();
                        }
                    }

                    @Override
                    public void onLightAutoSwitchOff() {
                        AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightAutoSwitchOff()");
                        if (poll(true)) {
                            asyncNotify.run();
                        }
                    }

                    @Override
                    public void onAFSSwitchStateChange(int state) {
                        AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onAFSSwitchStateChange(state=" + state + ")");
                    }
                };
                device.registerListener(lightListener);
                AppLogger.i(TAG, "BYDAutoLightDevice 初始化并注册监听成功");
            } catch (Throwable t) {
                lightListener = null;
                listenerWarning = "监听注册异常(" + formatThrowable(t) + ")";
                AppLogger.e(TAG, "BYDAutoLightDevice 注册监听失败（将继续尝试直接轮询读取）", t);
            }

            poll(true);
        }

        boolean poll(boolean verboseLog) {
            if (device == null) {
                return false;
            }
            try {
                int side = device.getLightStatus(BYDAutoLightDevice.LIGHT_SIDE);
                int low = device.getLightStatus(BYDAutoLightDevice.LIGHT_LOW_BEAM);
                int high = device.getLightStatus(BYDAutoLightDevice.LIGHT_HIGH_BEAM);
                int auto = device.getLightAutoStatus();
                connected = true;
                boolean changed = callback.onLightValuesUpdated(true, listenerWarning, side, low, high, auto);
                if (verboseLog || changed) {
                    AppLogger.i(TAG, "车灯轮询: SIDE=" + formatLightState(side)
                            + ", LOW_BEAM=" + formatLightState(low)
                            + ", HIGH_BEAM=" + formatLightState(high)
                            + ", AUTO=" + formatLightState(auto));
                }
                return changed;
            } catch (Throwable t) {
                connected = false;
                String err = formatThrowable(t);
                if (listenerWarning != null) {
                    err = err + " | " + listenerWarning;
                }
                AppLogger.e(TAG, "轮询 BYDAutoLightDevice 状态异常", t);
                return callback.onLightValuesUpdated(false, err,
                        UNSET_VALUE, UNSET_VALUE, UNSET_VALUE, UNSET_VALUE);
            }
        }

        void release() {
            connected = false;
            if (device != null && lightListener != null) {
                try {
                    device.unregisterListener(lightListener);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "注销 BYDAutoLightDevice 监听失败", t);
                }
            }
            lightListener = null;
            device = null;
        }
    }

    /**
     * 隔离引用 BYDAutoSensorDevice。
     */
    private static final class SensorDeviceBridge {
        interface Callback {
            boolean onSensorUpdated(boolean connected, String error, int level);
        }

        private final Context context;
        private final Callback callback;
        private final Runnable asyncNotify;
        private BYDAutoSensorDevice device;
        private AbsBYDAutoSensorListener sensorListener;
        private String listenerWarning;
        private volatile boolean connected;

        SensorDeviceBridge(Context context, Callback callback, Runnable asyncNotify) {
            this.context = context;
            this.callback = callback;
            this.asyncNotify = asyncNotify;
        }

        boolean isConnected() {
            return connected && device != null;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoSensorDevice.getInstance()...");
            device = BYDAutoSensorDevice.getInstance(context);
            if (device == null) {
                connected = false;
                String msg = "BYDAutoSensorDevice.getInstance(context) 返回 null（可能缺少 BYDAUTO_SENSOR_GET 权限或服务未就绪）";
                AppLogger.e(TAG, msg);
                callback.onSensorUpdated(false, msg, UNSET_VALUE);
                return;
            }

            listenerWarning = null;
            try {
                sensorListener = new AbsBYDAutoSensorListener() {
                    @Override
                    public void onLightIntensityChanged(int level) {
                        AppLogger.i(TAG, "回调 AbsBYDAutoSensorListener.onLightIntensityChanged(level="
                                + AppLogger.formatBydReturnCode(level) + ")");
                        connected = true;
                        if (callback.onSensorUpdated(true, listenerWarning, level)) {
                            asyncNotify.run();
                        }
                    }
                };
                device.registerListener(sensorListener);
                AppLogger.i(TAG, "BYDAutoSensorDevice 初始化并注册监听成功");
            } catch (Throwable t) {
                sensorListener = null;
                listenerWarning = "监听注册异常(" + formatThrowable(t) + ")";
                AppLogger.e(TAG, "BYDAutoSensorDevice 注册监听失败（将继续尝试直接轮询读取）", t);
            }

            poll(true);
        }

        boolean poll(boolean verboseLog) {
            if (device == null) {
                return false;
            }
            try {
                int level = device.getLightIntensity();
                connected = true;
                boolean changed = callback.onSensorUpdated(true, listenerWarning, level);
                if (verboseLog || changed) {
                    AppLogger.i(TAG, "光感轮询: getLightIntensity()=" + AppLogger.formatBydReturnCode(level));
                }
                return changed;
            } catch (Throwable t) {
                connected = false;
                String err = formatThrowable(t);
                if (listenerWarning != null) {
                    err = err + " | " + listenerWarning;
                }
                AppLogger.e(TAG, "轮询 BYDAutoSensorDevice.getLightIntensity() 异常", t);
                return callback.onSensorUpdated(false, err, UNSET_VALUE);
            }
        }

        void release() {
            connected = false;
            if (device != null && sensorListener != null) {
                try {
                    device.unregisterListener(sensorListener);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "注销 BYDAutoSensorDevice 监听失败", t);
                }
            }
            sensorListener = null;
            device = null;
        }
    }

    /**
     * 隔离引用 BYDAutoBodyworkDevice，监听车辆电源档位。
     */
    private static final class BodyworkDeviceBridge {
        interface Callback {
            boolean onPowerUpdated(boolean connected, String error, int powerLevel, boolean justPoweredOn);
        }

        private final Context context;
        private final Callback callback;
        private final Runnable asyncNotify;
        private BYDAutoBodyworkDevice device;
        private AbsBYDAutoBodyworkListener bodyworkListener;
        private int lastPowerLevel = UNSET_VALUE;
        private String listenerWarning;
        private volatile boolean connected;

        BodyworkDeviceBridge(Context context, Callback callback, Runnable asyncNotify) {
            this.context = context;
            this.callback = callback;
            this.asyncNotify = asyncNotify;
        }

        boolean isConnected() {
            return connected && device != null;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoBodyworkDevice.getInstance()...");
            device = BYDAutoBodyworkDevice.getInstance(context);
            if (device == null) {
                connected = false;
                String msg = "BYDAutoBodyworkDevice.getInstance(context) 返回 null";
                AppLogger.e(TAG, msg);
                callback.onPowerUpdated(false, msg, UNSET_VALUE, false);
                return;
            }

            listenerWarning = null;
            try {
                bodyworkListener = new AbsBYDAutoBodyworkListener() {
                    @Override
                    public void onPowerLevelChanged(int level) {
                        AppLogger.i(TAG, "回调 AbsBYDAutoBodyworkListener.onPowerLevelChanged("
                                + formatPowerLevel(level) + ")");
                        boolean poweredOn = (level == BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_ON
                                && lastPowerLevel != BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_ON);
                        lastPowerLevel = level;
                        connected = true;
                        if (callback.onPowerUpdated(true, listenerWarning, level, poweredOn) && !poweredOn) {
                            asyncNotify.run();
                        }
                    }
                };
                device.registerListener(bodyworkListener);
                AppLogger.i(TAG, "BYDAutoBodyworkDevice 初始化并注册监听成功");
            } catch (Throwable t) {
                bodyworkListener = null;
                listenerWarning = "监听注册异常(" + formatThrowable(t) + ")";
                AppLogger.e(TAG, "BYDAutoBodyworkDevice 注册监听失败（将继续尝试直接轮询读取）", t);
            }

            poll(true);
        }

        boolean poll(boolean verboseLog) {
            if (device == null) {
                return false;
            }
            try {
                int level = device.getPowerLevel();
                lastPowerLevel = level;
                connected = true;
                boolean changed = callback.onPowerUpdated(true, listenerWarning, level, false);
                if (verboseLog || changed) {
                    AppLogger.i(TAG, "电源档位轮询: getPowerLevel()=" + formatPowerLevel(level));
                }
                return changed;
            } catch (Throwable t) {
                connected = false;
                String err = formatThrowable(t);
                if (listenerWarning != null) {
                    err = err + " | " + listenerWarning;
                }
                AppLogger.e(TAG, "轮询 BYDAutoBodyworkDevice.getPowerLevel() 异常", t);
                return callback.onPowerUpdated(false, err, UNSET_VALUE, false);
            }
        }

        void release() {
            connected = false;
            if (device != null && bodyworkListener != null) {
                try {
                    device.unregisterListener(bodyworkListener);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "注销 BYDAutoBodyworkDevice 监听失败", t);
                }
            }
            bodyworkListener = null;
            device = null;
        }
    }
}
