package com.byd.autodarkwallpaper.ui;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.byd.autodarkwallpaper.R;
import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.monitor.VehicleStateMonitor;
import com.byd.autodarkwallpaper.service.AutoDarkMonitorService;
import com.byd.autodarkwallpaper.service.AutoDarkWallpaperService;
import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperController;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BYD / DiLink 150 双模式自动亮/暗壁纸主界面。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainUI";
    private static final int REQ_PICK_LIGHT = 1001;
    private static final int REQ_PICK_DARK = 1002;
    private static final int REQ_POST_NOTIFICATIONS = 2001;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private AppPrefs prefs;
    private boolean suppressControlEvents = false;

    // 顶部与状态
    private TextView tvHeaderVersion;
    private TextView tvServiceStatus;
    private TextView tvEvaluatedMode;
    private TextView tvLiveWallpaperStatus;
    private TextView tvLastApplyResult;
    private Button btnToggleService;
    private Button btnActivateLiveWallpaper;
    private Button btnManualLight;
    private Button btnManualDark;

    // 壁纸预览与选择
    private ImageView ivPreviewLight;
    private TextView tvLightMeta;
    private ImageView ivPreviewDark;
    private TextView tvDarkMeta;
    private Button btnPickLight;
    private Button btnPickDark;
    private Button btnResetWallpapers;

    // 实现模式
    private RadioGroup rgWallpaperMode;
    private RadioButton rbModeDual;
    private RadioButton rbModeLive;
    private RadioButton rbModeStatic;
    private CheckBox cbStaticSyncLockScreen;

    // 触发源与阈值
    private RadioGroup rgTriggerSource;
    private RadioButton rbTriggerSystem;
    private RadioButton rbTriggerLight;
    private RadioButton rbTriggerSensor;
    private RadioButton rbTriggerAnyDark;
    private CheckBox cbLightSide;
    private CheckBox cbLightLowBeam;
    private CheckBox cbLightHighBeam;
    private RadioGroup rgSensorThreshold;
    private RadioButton rbLevel1;
    private RadioButton rbLevel2;
    private RadioButton rbLevel3;
    private RadioButton rbLevel4;
    private RadioButton rbLevel5;
    private RadioGroup rgSensorDirection;
    private RadioButton rbSensorLte;
    private RadioButton rbSensorGte;
    private RadioGroup rgDebounce;
    private RadioButton rbDebounce0;
    private RadioButton rbDebounce1500;
    private RadioButton rbDebounce3000;
    private RadioButton rbDebounce5000;
    private CheckBox cbBootAutoStart;

    // 遥测与日志
    private Button btnPollNow;
    private TextView tvTelemetrySystem;
    private TextView tvTelemetryLight;
    private TextView tvTelemetrySensor;
    private TextView tvTelemetryBodywork;
    private TextView tvTelemetryDecision;
    private TextView tvLogFilePath;
    private Button btnCopyLogs;
    private Button btnExportLogs;
    private Button btnClearLogs;
    private ScrollView scrollLog;
    private TextView tvLogConsole;

    private final AppLogger.LogListener logListener = fullLogText -> {
        if (tvLogConsole == null) {
            return;
        }
        tvLogConsole.setText(fullLogText);
        if (scrollLog != null) {
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        }
    };

    private final AutoDarkMonitorService.MonitorObserver monitorObserver =
            (running, snapshot, evaluation) -> renderTelemetry(running, snapshot, evaluation);

    private final WallpaperController.ApplyStatusListener applyStatusListener =
            (statusText, isError) -> {
                if (tvLastApplyResult != null) {
                    tvLastApplyResult.setText("最近应用状态：" + statusText);
                    tvLastApplyResult.setTextColor(Color.parseColor(isError ? "#F87171" : "#34D399"));
                }
                refreshLiveWallpaperStatusText();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this);
        setContentView(R.layout.activity_main);
        prefs = new AppPrefs(this);

        bindViews();
        populateHeaderVersion();
        syncControlsFromPrefs();
        setupControlListeners();
        refreshWallpaperPreviewsAsync();
        refreshLiveWallpaperStatusText();

        tvLogFilePath.setText("日志文件：" + AppLogger.getLogFilePath());
        requestNotificationPermissionIfNeeded();

        if (prefs.isEnabled() && !AutoDarkMonitorService.isRunning()) {
            AppLogger.i(TAG, "首次进入主界面，自动拉起 AutoDarkMonitorService");
            AutoDarkMonitorService.startMonitor(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.addListener(logListener);
        AutoDarkMonitorService.addObserver(monitorObserver);
        WallpaperController.addApplyStatusListener(applyStatusListener);
        refreshLiveWallpaperStatusText();
    }

    @Override
    protected void onStop() {
        WallpaperController.removeApplyStatusListener(applyStatusListener);
        AutoDarkMonitorService.removeObserver(monitorObserver);
        AppLogger.removeListener(logListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int nightMask = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        AppLogger.i(TAG, "MainActivity.onConfigurationChanged | uiMode="
                + VehicleStateMonitor.formatSystemNightMask(nightMask));
        if (AutoDarkMonitorService.isRunning()) {
            AutoDarkMonitorService.requestPollNow(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode != REQ_PICK_LIGHT && requestCode != REQ_PICK_DARK) {
            return;
        }
        final Uri uri = data.getData();
        final boolean isDark = (requestCode == REQ_PICK_DARK);
        AppLogger.i(TAG, "用户选择了" + (isDark ? "夜间" : "日间") + "壁纸 URI=" + uri);

        ioExecutor.execute(() -> {
            try {
                WallpaperController.importWallpaperFromUri(MainActivity.this, uri, isDark);
                runOnUiThread(() -> {
                    refreshWallpaperPreviewsAsync();
                    Toast.makeText(MainActivity.this,
                            (isDark ? "夜间" : "日间") + "壁纸导入成功", Toast.LENGTH_SHORT).show();
                    if (AutoDarkMonitorService.isRunning()) {
                        AutoDarkMonitorService.notifyConfigUpdated(MainActivity.this);
                    }
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "导入壁纸失败: uri=" + uri, t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "导入失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void bindViews() {
        tvHeaderVersion = findViewById(R.id.tvHeaderVersion);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvEvaluatedMode = findViewById(R.id.tvEvaluatedMode);
        tvLiveWallpaperStatus = findViewById(R.id.tvLiveWallpaperStatus);
        tvLastApplyResult = findViewById(R.id.tvLastApplyResult);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnActivateLiveWallpaper = findViewById(R.id.btnActivateLiveWallpaper);
        btnManualLight = findViewById(R.id.btnManualLight);
        btnManualDark = findViewById(R.id.btnManualDark);

        ivPreviewLight = findViewById(R.id.ivPreviewLight);
        tvLightMeta = findViewById(R.id.tvLightMeta);
        ivPreviewDark = findViewById(R.id.ivPreviewDark);
        tvDarkMeta = findViewById(R.id.tvDarkMeta);
        btnPickLight = findViewById(R.id.btnPickLight);
        btnPickDark = findViewById(R.id.btnPickDark);
        btnResetWallpapers = findViewById(R.id.btnResetWallpapers);

        rgWallpaperMode = findViewById(R.id.rgWallpaperMode);
        rbModeDual = findViewById(R.id.rbModeDual);
        rbModeLive = findViewById(R.id.rbModeLive);
        rbModeStatic = findViewById(R.id.rbModeStatic);
        cbStaticSyncLockScreen = findViewById(R.id.cbStaticSyncLockScreen);

        rgTriggerSource = findViewById(R.id.rgTriggerSource);
        rbTriggerSystem = findViewById(R.id.rbTriggerSystem);
        rbTriggerLight = findViewById(R.id.rbTriggerLight);
        rbTriggerSensor = findViewById(R.id.rbTriggerSensor);
        rbTriggerAnyDark = findViewById(R.id.rbTriggerAnyDark);
        cbLightSide = findViewById(R.id.cbLightSide);
        cbLightLowBeam = findViewById(R.id.cbLightLowBeam);
        cbLightHighBeam = findViewById(R.id.cbLightHighBeam);
        rgSensorThreshold = findViewById(R.id.rgSensorThreshold);
        rbLevel1 = findViewById(R.id.rbLevel1);
        rbLevel2 = findViewById(R.id.rbLevel2);
        rbLevel3 = findViewById(R.id.rbLevel3);
        rbLevel4 = findViewById(R.id.rbLevel4);
        rbLevel5 = findViewById(R.id.rbLevel5);
        rgSensorDirection = findViewById(R.id.rgSensorDirection);
        rbSensorLte = findViewById(R.id.rbSensorLte);
        rbSensorGte = findViewById(R.id.rbSensorGte);
        rgDebounce = findViewById(R.id.rgDebounce);
        rbDebounce0 = findViewById(R.id.rbDebounce0);
        rbDebounce1500 = findViewById(R.id.rbDebounce1500);
        rbDebounce3000 = findViewById(R.id.rbDebounce3000);
        rbDebounce5000 = findViewById(R.id.rbDebounce5000);
        cbBootAutoStart = findViewById(R.id.cbBootAutoStart);

        btnPollNow = findViewById(R.id.btnPollNow);
        tvTelemetrySystem = findViewById(R.id.tvTelemetrySystem);
        tvTelemetryLight = findViewById(R.id.tvTelemetryLight);
        tvTelemetrySensor = findViewById(R.id.tvTelemetrySensor);
        tvTelemetryBodywork = findViewById(R.id.tvTelemetryBodywork);
        tvTelemetryDecision = findViewById(R.id.tvTelemetryDecision);

        tvLogFilePath = findViewById(R.id.tvLogFilePath);
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnExportLogs = findViewById(R.id.btnExportLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        scrollLog = findViewById(R.id.scrollLog);
        tvLogConsole = findViewById(R.id.tvLogConsole);
    }

    private void populateHeaderVersion() {
        String versionName = "unknown";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName;
        } catch (Throwable t) {
            AppLogger.e(TAG, "获取应用版本信息失败", t);
        }
        tvHeaderVersion.setText("v" + versionName + " · Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ")");
    }

    private void syncControlsFromPrefs() {
        suppressControlEvents = true;
        try {
            int mode = prefs.getWallpaperMode();
            if (mode == AppPrefs.MODE_STATIC_MANAGER) {
                rbModeStatic.setChecked(true);
            } else if (mode == AppPrefs.MODE_LIVE_SERVICE) {
                rbModeLive.setChecked(true);
            } else {
                rbModeDual.setChecked(true);
            }
            cbStaticSyncLockScreen.setChecked(prefs.isStaticSyncLockScreen());

            int trigger = prefs.getActiveTriggerSource();
            if (trigger == AppPrefs.TRIGGER_BYD_LIGHT) {
                rbTriggerLight.setChecked(true);
            } else if (trigger == AppPrefs.TRIGGER_BYD_SENSOR) {
                rbTriggerSensor.setChecked(true);
            } else if (trigger == AppPrefs.TRIGGER_ANY_DARK) {
                rbTriggerAnyDark.setChecked(true);
            } else {
                rbTriggerSystem.setChecked(true);
            }

            cbLightSide.setChecked(prefs.isLightUseSide());
            cbLightLowBeam.setChecked(prefs.isLightUseLowBeam());
            cbLightHighBeam.setChecked(prefs.isLightUseHighBeam());

            int threshold = prefs.getSensorThresholdLevel();
            if (threshold == 1) {
                rbLevel1.setChecked(true);
            } else if (threshold == 3) {
                rbLevel3.setChecked(true);
            } else if (threshold == 4) {
                rbLevel4.setChecked(true);
            } else if (threshold == 5) {
                rbLevel5.setChecked(true);
            } else {
                rbLevel2.setChecked(true);
            }

            if (prefs.isSensorDarkWhenLte()) {
                rbSensorLte.setChecked(true);
            } else {
                rbSensorGte.setChecked(true);
            }

            long debounce = prefs.getDebounceMs();
            if (debounce == 0L) {
                rbDebounce0.setChecked(true);
            } else if (debounce == 3000L) {
                rbDebounce3000.setChecked(true);
            } else if (debounce == 5000L) {
                rbDebounce5000.setChecked(true);
            } else {
                rbDebounce1500.setChecked(true);
            }

            cbBootAutoStart.setChecked(prefs.isBootAutoStart());
        } finally {
            suppressControlEvents = false;
        }
    }

    private void setupControlListeners() {
        btnToggleService.setOnClickListener(v -> {
            if (AutoDarkMonitorService.isRunning()) {
                prefs.setEnabled(false);
                AutoDarkMonitorService.stopMonitor(this);
                AppLogger.i(TAG, "用户手动停止监控服务");
            } else {
                prefs.setEnabled(true);
                AutoDarkMonitorService.startMonitor(this);
                AppLogger.i(TAG, "用户手动启动监控服务");
            }
        });

        btnActivateLiveWallpaper.setOnClickListener(v -> openLiveWallpaperChooser());

        btnManualLight.setOnClickListener(v -> {
            AppLogger.i(TAG, "点击【立即切亮色(测试)】");
            AutoDarkMonitorService.requestManualApply(this, false);
        });

        btnManualDark.setOnClickListener(v -> {
            AppLogger.i(TAG, "点击【立即切暗色(测试)】");
            AutoDarkMonitorService.requestManualApply(this, true);
        });

        btnPickLight.setOnClickListener(v -> launchImagePicker(REQ_PICK_LIGHT));
        btnPickDark.setOnClickListener(v -> launchImagePicker(REQ_PICK_DARK));
        btnResetWallpapers.setOnClickListener(v -> ioExecutor.execute(() -> {
            try {
                WallpaperController.resetToDefaultWallpapers(MainActivity.this);
                runOnUiThread(() -> {
                    refreshWallpaperPreviewsAsync();
                    Toast.makeText(MainActivity.this, "已恢复内置测试壁纸", Toast.LENGTH_SHORT).show();
                    if (AutoDarkMonitorService.isRunning()) {
                        AutoDarkMonitorService.notifyConfigUpdated(MainActivity.this);
                    }
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "重置内置测试壁纸失败", t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "重置失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));

        rgWallpaperMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressControlEvents) {
                return;
            }
            int mode = AppPrefs.MODE_DUAL;
            if (checkedId == R.id.rbModeLive) {
                mode = AppPrefs.MODE_LIVE_SERVICE;
            } else if (checkedId == R.id.rbModeStatic) {
                mode = AppPrefs.MODE_STATIC_MANAGER;
            }
            prefs.setWallpaperMode(mode);
            AppLogger.i(TAG, "切换壁纸实现模式 -> " + AppPrefs.formatWallpaperMode(mode));
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        cbStaticSyncLockScreen.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setStaticSyncLockScreen(isChecked);
            AppLogger.i(TAG, "静态模式同步锁屏壁纸 -> " + isChecked);
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        rgTriggerSource.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressControlEvents) {
                return;
            }
            int source = AppPrefs.TRIGGER_SYSTEM_UIMODE;
            if (checkedId == R.id.rbTriggerLight) {
                source = AppPrefs.TRIGGER_BYD_LIGHT;
            } else if (checkedId == R.id.rbTriggerSensor) {
                source = AppPrefs.TRIGGER_BYD_SENSOR;
            } else if (checkedId == R.id.rbTriggerAnyDark) {
                source = AppPrefs.TRIGGER_ANY_DARK;
            }
            prefs.setActiveTriggerSource(source);
            AppLogger.i(TAG, "切换主触发源 -> " + AppPrefs.formatTriggerSource(source));
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        cbLightSide.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setLightUseSide(isChecked);
            AppLogger.i(TAG, "车灯触发项[示宽灯/小灯] -> " + isChecked);
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        cbLightLowBeam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setLightUseLowBeam(isChecked);
            AppLogger.i(TAG, "车灯触发项[近光灯] -> " + isChecked);
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        cbLightHighBeam.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setLightUseHighBeam(isChecked);
            AppLogger.i(TAG, "车灯触发项[远光灯] -> " + isChecked);
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        rgSensorThreshold.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressControlEvents) {
                return;
            }
            int level = 2;
            if (checkedId == R.id.rbLevel1) {
                level = 1;
            } else if (checkedId == R.id.rbLevel3) {
                level = 3;
            } else if (checkedId == R.id.rbLevel4) {
                level = 4;
            } else if (checkedId == R.id.rbLevel5) {
                level = 5;
            }
            prefs.setSensorThresholdLevel(level);
            AppLogger.i(TAG, "光感阈值档位 -> L" + level);
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        rgSensorDirection.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressControlEvents) {
                return;
            }
            boolean lte = (checkedId == R.id.rbSensorLte);
            prefs.setSensorDarkWhenLte(lte);
            AppLogger.i(TAG, "光感夜间方向 -> " + (lte ? "<= 阈值视为夜间" : ">= 阈值视为夜间"));
            AutoDarkMonitorService.notifyConfigUpdated(this);
        });

        rgDebounce.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressControlEvents) {
                return;
            }
            long ms = 1500L;
            if (checkedId == R.id.rbDebounce0) {
                ms = 0L;
            } else if (checkedId == R.id.rbDebounce3000) {
                ms = 3000L;
            } else if (checkedId == R.id.rbDebounce5000) {
                ms = 5000L;
            }
            prefs.setDebounceMs(ms);
            AppLogger.i(TAG, "防抖延迟 -> " + ms + "ms");
        });

        cbBootAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setBootAutoStart(isChecked);
            AppLogger.i(TAG, "开机自启开关 -> " + isChecked);
        });

        btnPollNow.setOnClickListener(v -> {
            AppLogger.i(TAG, "用户点击【主动轮询刷新硬件状态】");
            AutoDarkMonitorService.requestPollNow(this);
        });

        btnCopyLogs.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) {
                    throw new IllegalStateException("ClipboardManager 为 null");
                }
                cm.setPrimaryClip(ClipData.newPlainText("BYDAutoDarkLogs", AppLogger.getAllLogsText()));
                Toast.makeText(this, "已复制全部日志到剪贴板", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                AppLogger.e(TAG, "复制日志失败", t);
                Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnExportLogs.setOnClickListener(v -> ioExecutor.execute(() -> {
            try {
                File out = AppLogger.exportLogsToTimestampedFile(MainActivity.this);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "已导出到: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                AppLogger.e(TAG, "导出日志文件失败", t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }));

        btnClearLogs.setOnClickListener(v -> AppLogger.clear());
    }

    private void openLiveWallpaperChooser() {
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, AutoDarkWallpaperService.class));
            AppLogger.i(TAG, "打开系统动态壁纸预览与激活界面...");
            startActivity(intent);
        } catch (Throwable t) {
            AppLogger.e(TAG, "打开 ACTION_CHANGE_LIVE_WALLPAPER 失败（车机系统可能精简了原生动态壁纸预览器）", t);
            Toast.makeText(this,
                    "无法直接跳转动态壁纸激活页面（详见日志），请尝试使用静态壁纸模式或从系统壁纸设置中选取",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchImagePicker(int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, requestCode);
        } catch (Throwable t) {
            AppLogger.e(TAG, "拉起系统图片选择器失败", t);
            Toast.makeText(this, "无法打开图片选择器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshWallpaperPreviewsAsync() {
        ioExecutor.execute(() -> {
            try {
                WallpaperController.ensureDefaultWallpapers(MainActivity.this);
                File lightFile = WallpaperController.getLightWallpaperFile(MainActivity.this);
                File darkFile = WallpaperController.getDarkWallpaperFile(MainActivity.this);
                final Bitmap lightBmp = WallpaperController.decodeSampledBitmap(lightFile, 640, 400);
                final Bitmap darkBmp = WallpaperController.decodeSampledBitmap(darkFile, 640, 400);
                final String lightMeta = (prefs.isLightCustom() ? "自定义 · " : "内置测试 · ")
                        + WallpaperController.getImageDimensionsText(lightFile);
                final String darkMeta = (prefs.isDarkCustom() ? "自定义 · " : "内置测试 · ")
                        + WallpaperController.getImageDimensionsText(darkFile);
                runOnUiThread(() -> {
                    if (lightBmp != null) {
                        ivPreviewLight.setImageBitmap(lightBmp);
                    }
                    if (darkBmp != null) {
                        ivPreviewDark.setImageBitmap(darkBmp);
                    }
                    tvLightMeta.setText(lightMeta);
                    tvDarkMeta.setText(darkMeta);
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "加载壁纸缩略图失败", t);
            }
        });
    }

    private void refreshLiveWallpaperStatusText() {
        boolean active = WallpaperController.isOurLiveWallpaperActive(this);
        String desc = WallpaperController.describeActiveSystemWallpaper(this);
        tvLiveWallpaperStatus.setText("动态壁纸服务：" + desc);
        tvLiveWallpaperStatus.setTextColor(Color.parseColor(active ? "#34D399" : "#FBBF24"));
    }

    private void renderTelemetry(boolean running,
                                 VehicleStateMonitor.TelemetrySnapshot s,
                                 VehicleStateMonitor.EvaluationResult eval) {
        if (tvServiceStatus == null) {
            return;
        }
        tvServiceStatus.setText("监控服务状态：" + (running ? "运行中（前台保活）" : "已停止"));
        tvServiceStatus.setTextColor(Color.parseColor(running ? "#34D399" : "#F87171"));
        btnToggleService.setText(running ? getString(R.string.btn_stop_service) : getString(R.string.btn_start_service));

        if (eval != null && eval.valid) {
            tvEvaluatedMode.setText("当前判定模式：" + (eval.targetDark ? "夜间暗色 (DARK)" : "日间亮色 (LIGHT)")
                    + " · " + eval.reason);
            tvEvaluatedMode.setTextColor(Color.parseColor(eval.targetDark ? "#818CF8" : "#38BDF8"));
        } else {
            String err = (eval != null) ? (eval.reason + " | " + eval.errorDetail) : "尚未评估";
            tvEvaluatedMode.setText("当前判定模式：未能判定 (" + err + ")");
            tvEvaluatedMode.setTextColor(Color.parseColor("#FBBF24"));
        }

        if (s != null) {
            if (s.systemError != null) {
                tvTelemetrySystem.setText("系统 uiMode：读取失败 -> " + s.systemError);
                tvTelemetrySystem.setTextColor(Color.parseColor("#F87171"));
            } else {
                tvTelemetrySystem.setText("系统 uiMode："
                        + VehicleStateMonitor.formatSystemNightMask(s.systemUiModeNightMask)
                        + " | UiModeManager=" + s.uiModeManagerNightMode);
                tvTelemetrySystem.setTextColor(Color.parseColor("#F1F5F9"));
            }

            if (s.lightDeviceConnected) {
                tvTelemetryLight.setText("车灯 BYDAutoLightDevice：[已连接] 小灯="
                        + VehicleStateMonitor.formatLightState(s.sideLightStatus)
                        + " | 近光=" + VehicleStateMonitor.formatLightState(s.lowBeamStatus)
                        + " | 远光=" + VehicleStateMonitor.formatLightState(s.highBeamStatus)
                        + " | AUTO=" + VehicleStateMonitor.formatLightState(s.autoLightStatus));
                tvTelemetryLight.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvTelemetryLight.setText("车灯 BYDAutoLightDevice：[未连接] " + s.lightDeviceError);
                tvTelemetryLight.setTextColor(Color.parseColor("#F87171"));
            }

            if (s.sensorDeviceConnected) {
                tvTelemetrySensor.setText("光感 BYDAutoSensorDevice：[已连接] getLightIntensity()="
                        + AppLogger.formatBydReturnCode(s.lightIntensityLevel));
                tvTelemetrySensor.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvTelemetrySensor.setText("光感 BYDAutoSensorDevice：[未连接] " + s.sensorDeviceError);
                tvTelemetrySensor.setTextColor(Color.parseColor("#F87171"));
            }

            if (s.bodyworkDeviceConnected) {
                tvTelemetryBodywork.setText("电源 BYDAutoBodyworkDevice：[已连接] powerLevel="
                        + VehicleStateMonitor.formatPowerLevel(s.powerLevel));
                tvTelemetryBodywork.setTextColor(Color.parseColor("#34D399"));
            } else {
                tvTelemetryBodywork.setText("电源 BYDAutoBodyworkDevice：[未连接] " + s.bodyworkDeviceError);
                tvTelemetryBodywork.setTextColor(Color.parseColor("#FBBF24"));
            }
        }

        if (eval != null) {
            String warnPart = (eval.errorDetail != null && !eval.errorDetail.isEmpty())
                    ? (" | 细节/错误: " + eval.errorDetail) : "";
            tvTelemetryDecision.setText("触发判定：[" + AppPrefs.formatTriggerSource(prefs.getActiveTriggerSource())
                    + "] -> " + (eval.valid ? (eval.targetDark ? "DARK" : "LIGHT") : "INVALID")
                    + " (" + eval.reason + ")" + warnPart);
            tvTelemetryDecision.setTextColor(Color.parseColor(eval.valid ? "#14B8A6" : "#F87171"));
        }

        refreshLiveWallpaperStatusText();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                AppLogger.i(TAG, "请求 Android 13+ 通知权限 POST_NOTIFICATIONS");
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS && grantResults.length > 0) {
            AppLogger.i(TAG, "POST_NOTIFICATIONS 授权结果=" + grantResults[0]);
        }
    }
}
