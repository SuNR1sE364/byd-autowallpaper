package com.byd.autodarkwallpaper.monitor;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.IBYDAutoEvent;
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
 * 遵循“无静默 Fallback”原则：任意接口初始化或调用失败均记录完整堆栈与原始返回码。
 */
public class VehicleStateMonitor {

    private static final String TAG = "VehicleMonitor";
    public static final int UNSET_VALUE = Integer.MIN_VALUE;

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

    public synchronized void start() {
        if (started) {
            AppLogger.i(TAG, "VehicleStateMonitor 已处于启动状态，执行一次全量轮询");
            pollAllStatesNow();
            return;
        }
        started = true;
        AppLogger.i(TAG, "正在启动车机状态与比亚迪硬件监听器...");

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

        if (lightBridge != null) {
            lightBridge.release();
            lightBridge = null;
        }
        if (sensorBridge != null) {
            sensorBridge.release();
            sensorBridge = null;
        }
        if (bodyworkBridge != null) {
            bodyworkBridge.release();
            bodyworkBridge = null;
        }
    }

    public void pollAllStatesNow() {
        AppLogger.i(TAG, "开始主动轮询所有信号源...");
        readSystemUiMode();
        if (lightBridge != null) {
            lightBridge.poll();
        }
        if (sensorBridge != null) {
            sensorBridge.poll();
        }
        if (bodyworkBridge != null) {
            bodyworkBridge.poll();
        }
        synchronized (stateLock) {
            snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
        }
        notifyListener();
    }

    private void registerSystemUiModeListeners() {
        componentCallbacks = new ComponentCallbacks() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                int nightMask = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
                AppLogger.i(TAG, "收到 ComponentCallbacks.onConfigurationChanged，uiMode nightMask="
                        + formatSystemNightMask(nightMask));
                readSystemUiMode();
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
                readSystemUiMode();
                notifyListener();
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        appContext.registerReceiver(configReceiver, filter);
    }

    private void readSystemUiMode() {
        try {
            Configuration cfg = appContext.getResources().getConfiguration();
            int nightMask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            int managerNightMode = UNSET_VALUE;
            UiModeManager uiModeManager = (UiModeManager) appContext.getSystemService(Context.UI_MODE_SERVICE);
            if (uiModeManager != null) {
                managerNightMode = uiModeManager.getNightMode();
            }
            synchronized (stateLock) {
                snapshot.systemUiModeNightMask = nightMask;
                snapshot.uiModeManagerNightMode = managerNightMode;
                snapshot.systemError = null;
                snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
            }
            AppLogger.i(TAG, "系统 uiMode 轮询: mask=" + formatSystemNightMask(nightMask)
                    + ", UiModeManager.getNightMode()=" + managerNightMode);
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取系统 uiMode 失败", t);
            synchronized (stateLock) {
                snapshot.systemError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }

    private void initLightDevice() {
        try {
            lightBridge = new LightDeviceBridge(appContext, new LightDeviceBridge.Callback() {
                @Override
                public void onLightValuesUpdated(boolean connected, String error,
                                                 int side, int lowBeam, int highBeam, int autoStatus) {
                    synchronized (stateLock) {
                        snapshot.lightDeviceConnected = connected;
                        snapshot.lightDeviceError = error;
                        snapshot.sideLightStatus = side;
                        snapshot.lowBeamStatus = lowBeam;
                        snapshot.highBeamStatus = highBeam;
                        snapshot.autoLightStatus = autoStatus;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    notifyListener();
                }
            });
            lightBridge.init();
        } catch (Throwable t) {
            String err = t.getClass().getSimpleName() + ": " + t.getMessage();
            AppLogger.e(TAG, "初始化 BYDAutoLightDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.lightDeviceConnected = false;
                snapshot.lightDeviceError = err;
            }
            lightBridge = null;
        }
    }

    private void initSensorDevice() {
        try {
            sensorBridge = new SensorDeviceBridge(appContext, new SensorDeviceBridge.Callback() {
                @Override
                public void onSensorUpdated(boolean connected, String error, int level) {
                    synchronized (stateLock) {
                        snapshot.sensorDeviceConnected = connected;
                        snapshot.sensorDeviceError = error;
                        snapshot.lightIntensityLevel = level;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    notifyListener();
                }
            });
            sensorBridge.init();
        } catch (Throwable t) {
            String err = t.getClass().getSimpleName() + ": " + t.getMessage();
            AppLogger.e(TAG, "初始化 BYDAutoSensorDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.sensorDeviceConnected = false;
                snapshot.sensorDeviceError = err;
            }
            sensorBridge = null;
        }
    }

    private void initBodyworkDevice() {
        try {
            bodyworkBridge = new BodyworkDeviceBridge(appContext, new BodyworkDeviceBridge.Callback() {
                @Override
                public void onPowerUpdated(boolean connected, String error, int powerLevel, boolean justPoweredOn) {
                    synchronized (stateLock) {
                        snapshot.bodyworkDeviceConnected = connected;
                        snapshot.bodyworkDeviceError = error;
                        snapshot.powerLevel = powerLevel;
                        snapshot.lastUpdatedTimestamp = System.currentTimeMillis();
                    }
                    if (justPoweredOn) {
                        AppLogger.i(TAG, "检测到车辆电源切换为 ON，重新轮询全部传感器与车灯状态");
                        pollAllStatesNow();
                    } else {
                        notifyListener();
                    }
                }
            });
            bodyworkBridge.init();
        } catch (Throwable t) {
            String err = t.getClass().getSimpleName() + ": " + t.getMessage();
            AppLogger.e(TAG, "初始化 BYDAutoBodyworkDevice 桥接类失败", t);
            synchronized (stateLock) {
                snapshot.bodyworkDeviceConnected = false;
                snapshot.bodyworkDeviceError = err;
            }
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
                    "车灯触发暗色 (" + String.join(", ", onLights) + ")", null);
        }
        return new EvaluationResult(true, false, "已选车灯均关闭 -> 日间亮色", null);
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
                        + " " + cmp + " L" + threshold + ")", null);
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

    /**
     * 隔离引用 BYDAutoLightDevice，防止非 BYD 环境验证类时直接崩溃，同时在 BYD 车机上原生强类型调用。
     */
    private static final class LightDeviceBridge {
        interface Callback {
            void onLightValuesUpdated(boolean connected, String error,
                                      int side, int lowBeam, int highBeam, int autoStatus);
        }

        private final Context context;
        private final Callback callback;
        private BYDAutoLightDevice device;
        private AbsBYDAutoLightListener lightListener;

        LightDeviceBridge(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoLightDevice.getInstance()...");
            device = BYDAutoLightDevice.getInstance(context);
            if (device == null) {
                String msg = "BYDAutoLightDevice.getInstance(context) 返回 null（可能缺少 BYDAUTO_LIGHT_GET 权限或服务未就绪）";
                AppLogger.e(TAG, msg);
                callback.onLightValuesUpdated(false, msg,
                        UNSET_VALUE, UNSET_VALUE, UNSET_VALUE, UNSET_VALUE);
                return;
            }
            lightListener = new AbsBYDAutoLightListener() {
                @Override
                public void onLightOn(int area) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightOn(area=" + area + ")");
                    poll();
                }

                @Override
                public void onLightOff(int area) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightOff(area=" + area + ")");
                    poll();
                }

                @Override
                public void onLightAutoSwitchOn() {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightAutoSwitchOn()");
                    poll();
                }

                @Override
                public void onLightAutoSwitchOff() {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onLightAutoSwitchOff()");
                    poll();
                }

                @Override
                public void onAFSSwitchStateChange(int state) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onAFSSwitchStateChange(state=" + state + ")");
                }

                @Override
                public void onDataChanged(IBYDAutoEvent event) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoLightListener.onDataChanged: " + formatEvent(event));
                    try {
                        super.onDataChanged(event);
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "super.onDataChanged(BYDAutoLight) 执行异常", t);
                    }
                    poll();
                }
            };
            device.registerListener(lightListener);
            AppLogger.i(TAG, "BYDAutoLightDevice 初始化并注册监听成功");
            poll();
        }

        void poll() {
            if (device == null) {
                return;
            }
            try {
                int side = device.getLightStatus(BYDAutoLightDevice.LIGHT_SIDE);
                int low = device.getLightStatus(BYDAutoLightDevice.LIGHT_LOW_BEAM);
                int high = device.getLightStatus(BYDAutoLightDevice.LIGHT_HIGH_BEAM);
                int auto = device.getLightAutoStatus();
                AppLogger.i(TAG, "车灯轮询: SIDE=" + formatLightState(side)
                        + ", LOW_BEAM=" + formatLightState(low)
                        + ", HIGH_BEAM=" + formatLightState(high)
                        + ", AUTO=" + formatLightState(auto));
                callback.onLightValuesUpdated(true, null, side, low, high, auto);
            } catch (Throwable t) {
                AppLogger.e(TAG, "轮询 BYDAutoLightDevice 状态异常", t);
                callback.onLightValuesUpdated(false,
                        t.getClass().getSimpleName() + ": " + t.getMessage(),
                        UNSET_VALUE, UNSET_VALUE, UNSET_VALUE, UNSET_VALUE);
            }
        }

        void release() {
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
            void onSensorUpdated(boolean connected, String error, int level);
        }

        private final Context context;
        private final Callback callback;
        private BYDAutoSensorDevice device;
        private AbsBYDAutoSensorListener sensorListener;

        SensorDeviceBridge(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoSensorDevice.getInstance()...");
            device = BYDAutoSensorDevice.getInstance(context);
            if (device == null) {
                String msg = "BYDAutoSensorDevice.getInstance(context) 返回 null（可能缺少 BYDAUTO_SENSOR_GET 权限或服务未就绪）";
                AppLogger.e(TAG, msg);
                callback.onSensorUpdated(false, msg, UNSET_VALUE);
                return;
            }
            sensorListener = new AbsBYDAutoSensorListener() {
                @Override
                public void onLightIntensityChanged(int level) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoSensorListener.onLightIntensityChanged(level="
                            + AppLogger.formatBydReturnCode(level) + ")");
                    callback.onSensorUpdated(true, null, level);
                }

                @Override
                public void onDataChanged(IBYDAutoEvent event) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoSensorListener.onDataChanged: " + formatEvent(event));
                    try {
                        super.onDataChanged(event);
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "super.onDataChanged(BYDAutoSensor) 执行异常", t);
                    }
                    poll();
                }
            };
            device.registerListener(sensorListener);
            AppLogger.i(TAG, "BYDAutoSensorDevice 初始化并注册监听成功");
            poll();
        }

        void poll() {
            if (device == null) {
                return;
            }
            try {
                int level = device.getLightIntensity();
                AppLogger.i(TAG, "光感轮询: getLightIntensity()=" + AppLogger.formatBydReturnCode(level));
                callback.onSensorUpdated(true, null, level);
            } catch (Throwable t) {
                AppLogger.e(TAG, "轮询 BYDAutoSensorDevice.getLightIntensity() 异常", t);
                callback.onSensorUpdated(false,
                        t.getClass().getSimpleName() + ": " + t.getMessage(), UNSET_VALUE);
            }
        }

        void release() {
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
            void onPowerUpdated(boolean connected, String error, int powerLevel, boolean justPoweredOn);
        }

        private final Context context;
        private final Callback callback;
        private BYDAutoBodyworkDevice device;
        private AbsBYDAutoBodyworkListener bodyworkListener;
        private int lastPowerLevel = UNSET_VALUE;

        BodyworkDeviceBridge(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        void init() {
            AppLogger.i(TAG, "正在获取 BYDAutoBodyworkDevice.getInstance()...");
            device = BYDAutoBodyworkDevice.getInstance(context);
            if (device == null) {
                String msg = "BYDAutoBodyworkDevice.getInstance(context) 返回 null";
                AppLogger.e(TAG, msg);
                callback.onPowerUpdated(false, msg, UNSET_VALUE, false);
                return;
            }
            bodyworkListener = new AbsBYDAutoBodyworkListener() {
                @Override
                public void onPowerLevelChanged(int level) {
                    AppLogger.i(TAG, "回调 AbsBYDAutoBodyworkListener.onPowerLevelChanged("
                            + formatPowerLevel(level) + ")");
                    boolean poweredOn = (level == BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_ON
                            && lastPowerLevel != BYDAutoBodyworkDevice.BODYWORK_POWER_LEVEL_ON);
                    lastPowerLevel = level;
                    callback.onPowerUpdated(true, null, level, poweredOn);
                }

                @Override
                public void onDataChanged(IBYDAutoEvent event) {
                    try {
                        super.onDataChanged(event);
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "super.onDataChanged(BYDAutoBodywork) 执行异常", t);
                    }
                }
            };
            device.registerListener(bodyworkListener);
            AppLogger.i(TAG, "BYDAutoBodyworkDevice 初始化并注册监听成功");
            poll();
        }

        void poll() {
            if (device == null) {
                return;
            }
            try {
                int level = device.getPowerLevel();
                lastPowerLevel = level;
                AppLogger.i(TAG, "电源档位轮询: getPowerLevel()=" + formatPowerLevel(level));
                callback.onPowerUpdated(true, null, level, false);
            } catch (Throwable t) {
                AppLogger.e(TAG, "轮询 BYDAutoBodyworkDevice.getPowerLevel() 异常", t);
                callback.onPowerUpdated(false,
                        t.getClass().getSimpleName() + ": " + t.getMessage(), UNSET_VALUE, false);
            }
        }

        void release() {
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

    private static String formatEvent(IBYDAutoEvent event) {
        if (event == null) {
            return "null";
        }
        return "deviceType=" + event.getDeviceType()
                + ", eventType=" + event.getEventType()
                + ", value=" + event.getValue()
                + ", doubleValue=" + event.getDoubleValue()
                + ", data=" + event.getData();
    }
}
