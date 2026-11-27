package com.sunr1se.byd.autowallpaper.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.sunr1se.byd.autowallpaper.R;
import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.monitor.VehicleStateMonitor;
import com.sunr1se.byd.autowallpaper.service.AutoDarkMonitorService;
import com.sunr1se.byd.autowallpaper.service.AutoDarkWallpaperService;
import com.sunr1se.byd.autowallpaper.util.AppLogger;
import com.sunr1se.byd.autowallpaper.wallpaper.CropRect;
import com.sunr1se.byd.autowallpaper.wallpaper.VideoSpecEvaluator;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperController;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车机原生风格主界面：左侧导航栏（主页 / 壁纸 / 关于）+ 右侧内容区。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainUI";
    private static final String STATE_TAB = "state_selected_tab";
    private static final String STATE_EDITING_NIGHT = "state_editing_night";

    private static final int TAB_HOME = 0;
    private static final int TAB_WALLPAPER = 1;
    private static final int TAB_ABOUT = 2;

    private static final int PENDING_NONE = 0;
    private static final int PENDING_ENABLE_STATIC = 1;
    private static final int PENDING_SWITCH_TO_STATIC = 2;

    private static final int REQ_PICK_WALLPAPERS = 1001;
    private static final int REQ_PICK_LOG_TREE = 1003;
    private static final int REQ_CREATE_LOG_FILE = 1004;
    private static final int REQ_READ_STORAGE_FOR_BACKUP = 2001;
    private static final int REQ_POST_NOTIFICATIONS = 2002;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private AppPrefs prefs;
    private WallpaperGridAdapter gridAdapter;

    private int currentTab = TAB_HOME;
    private boolean editingNight = false;
    private boolean suppressControlEvents = false;
    private int pendingActionAfterPermission = PENDING_NONE;
    private boolean logListenerRegistered = false;
    private String lastApplyStatusText = "尚未切换壁纸";
    private boolean lastApplyIsError = false;

    // 左边栏控件
    private View navHome;
    private View navWallpaper;
    private View navAbout;
    private ImageView ivNavHome;
    private ImageView ivNavWallpaper;
    private ImageView ivNavAbout;
    private TextView tvNavHome;
    private TextView tvNavWallpaper;
    private TextView tvNavAbout;
    private View viewSidebarStatusDot;
    private TextView tvSidebarStatus;

    // 页面容器
    private View pageHome;
    private View pageWallpaper;
    private View pageAbout;

    // 集中说明菜单触发按钮
    private View btnHomeInfo;
    private View btnWallpaperInfo;
    private View btnAboutInfo;
    private View rowAboutHelp;

    // 主页控件
    private ImageView ivSystemModeIcon;
    private TextView tvSystemModePill;
    private View boxMasterIcon;
    private ImageView ivMasterPowerIcon;
    private View viewMasterStatusDot;
    private TextView tvMasterStatusBadge;
    private View chipBackupState;
    private View chipApplyWarning;
    private TextView tvApplyWarningBadge;
    private Switch switchMasterEnable;
    private View cardModeStatic;
    private View cardModeLive;
    private View boxModeStaticIcon;
    private View boxModeLiveIcon;
    private ImageView ivModeStaticIcon;
    private ImageView ivModeLiveIcon;
    private ImageView ivModeStaticCheck;
    private ImageView ivModeLiveCheck;
    private View layoutLiveWallpaperAction;
    private ImageView ivLiveWallpaperStateIcon;
    private TextView tvLiveWallpaperState;
    private View btnActivateLiveWallpaper;
    private View cardHomeDayPreview;
    private View cardHomeNightPreview;
    private View frameHomeDaySlot;
    private View frameHomeNightSlot;
    private ImageView ivHomeDayPreview;
    private ImageView ivHomeNightPreview;
    private View badgeHomeDayPolicy;
    private ImageView ivHomeDayPolicyIcon;
    private TextView tvHomeDayPolicyCount;
    private View badgeHomeNightPolicy;
    private ImageView ivHomeNightPolicyIcon;
    private TextView tvHomeNightPolicyCount;
    private View badgeHomeDayVideo;
    private TextView tvHomeDayVideoDuration;
    private View badgeHomeNightVideo;
    private TextView tvHomeNightVideoDuration;
    private View layoutHomeDayEmpty;
    private ImageView ivHomeDayEmptyIcon;
    private TextView tvHomeDayEmptyLabel;
    private View layoutHomeNightEmpty;
    private ImageView ivHomeNightEmptyIcon;
    private TextView tvHomeNightEmptyLabel;
    private TextView tvHomeDayName;
    private TextView tvHomeNightName;

    // 壁纸页控件
    private View tabWallpaperDay;
    private ImageView ivTabWallpaperDay;
    private TextView tvTabWallpaperDay;
    private View tabWallpaperNight;
    private ImageView ivTabWallpaperNight;
    private TextView tvTabWallpaperNight;
    private View btnAddWallpaper;
    private View tabStrategyFixed;
    private ImageView ivTabStrategyFixed;
    private TextView tvTabStrategyFixed;
    private View tabStrategyRandom;
    private ImageView ivTabStrategyRandom;
    private TextView tvTabStrategyRandom;
    private GridView gridWallpapers;
    private View layoutWallpaperEmpty;
    private TextView tvWallpaperEmptyTitle;
    private View btnEmptyAddWallpaper;
    private TextView tvEmptyAddWallpaper;

    // 关于页控件
    private TextView tvAboutVersion;
    private TextView tvAboutDeviceInfo;
    private View boxDebugIcon;
    private ImageView ivDebugIcon;
    private Switch switchDebugMode;
    private View cardDebugConsole;
    private View rowLogFilePath;
    private TextView tvLogFilePath;
    private View btnSetLogPath;
    private View btnCopyLogs;
    private View btnExportLogs;
    private View btnClearLogs;
    private ScrollView scrollLog;
    private TextView tvLogConsole;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Boolean appliedUiDarkMode = null;
    private ContentObserver bydUiThemeObserver;
    private ContentObserver bydCarSettingsUiObserver;

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
            (running, snapshot, evaluation) -> renderHomeStatus();

    private final WallpaperController.ApplyStatusListener applyStatusListener =
            (statusText, isError) -> {
                lastApplyStatusText = statusText;
                lastApplyIsError = isError;
                renderHomeStatus();
                if (currentTab == TAB_WALLPAPER) {
                    refreshWallpaperPage();
                }
            };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        Boolean bydDark = VehicleStateMonitor.queryBydDarkStateOrNull(newBase);
        if (bydDark != null) {
            Configuration baseConfig = newBase.getResources().getConfiguration();
            Configuration overrideConfig = new Configuration();
            overrideConfig.uiMode = (baseConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (bydDark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            applyOverrideConfiguration(overrideConfig);
            appliedUiDarkMode = bydDark;
        } else {
            appliedUiDarkMode = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this);
        setContentView(R.layout.activity_main);
        prefs = new AppPrefs(this);

        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_HOME);
            editingNight = savedInstanceState.getBoolean(STATE_EDITING_NIGHT, false);
        }

        bindViews();
        setupListeners();
        populateAboutInfo();

        selectTab(currentTab);
        renderHomeStatus();
        refreshHomeWallpaperOverviewAsync();
        refreshWallpaperPage();
        syncDebugConsoleState();

        if (prefs.isEnabled() && !AutoDarkMonitorService.isRunning()) {
            AppLogger.i(TAG, "检测到开关为开启状态，自动拉起 AutoDarkMonitorService");
            AutoDarkMonitorService.startMonitor(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
        outState.putBoolean(STATE_EDITING_NIGHT, editingNight);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AutoDarkMonitorService.addObserver(monitorObserver);
        WallpaperController.addApplyStatusListener(applyStatusListener);
        registerBydUiThemeObserver();
        syncDebugConsoleState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (syncThemeWithBydNightModeIfNeeded()) {
            return;
        }
        renderHomeStatus();
        refreshHomeWallpaperOverviewAsync();
        if (currentTab == TAB_WALLPAPER) {
            refreshWallpaperPage();
        }
        handlePendingActionAfterPermission();
    }

    @Override
    protected void onStop() {
        unregisterBydUiThemeObserver();
        WallpaperController.removeApplyStatusListener(applyStatusListener);
        AutoDarkMonitorService.removeObserver(monitorObserver);
        unregisterLogListenerIfNeeded();
        super.onStop();
    }

    private void registerBydUiThemeObserver() {
        if (bydUiThemeObserver == null) {
            bydUiThemeObserver = new ContentObserver(uiHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange(selfChange, null);
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (!syncThemeWithBydNightModeIfNeeded()) {
                        renderHomeStatus();
                    }
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(VehicleStateMonitor.SETTINGS_GLOBAL_BYD_NIGHT_MODE),
                        false,
                        bydUiThemeObserver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "MainActivity 注册 key_byd_night_mode 主题监听失败", t);
                bydUiThemeObserver = null;
            }
        }
        if (bydCarSettingsUiObserver == null) {
            bydCarSettingsUiObserver = new ContentObserver(uiHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    renderHomeStatus();
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        VehicleStateMonitor.CARSETTINGS_GLOBAL_URI,
                        true,
                        bydCarSettingsUiObserver);
            } catch (Throwable t) {
                AppLogger.w(TAG, "MainActivity 注册 carsettings 档位监听失败: " + t.getMessage());
                bydCarSettingsUiObserver = null;
            }
        }
    }

    private void unregisterBydUiThemeObserver() {
        if (bydUiThemeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(bydUiThemeObserver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "MainActivity 注销 key_byd_night_mode 主题监听失败", t);
            }
            bydUiThemeObserver = null;
        }
        if (bydCarSettingsUiObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(bydCarSettingsUiObserver);
            } catch (Throwable t) {
                AppLogger.e(TAG, "MainActivity 注销 carsettings 档位监听失败", t);
            }
            bydCarSettingsUiObserver = null;
        }
    }

    private boolean syncThemeWithBydNightModeIfNeeded() {
        Boolean latestDark = VehicleStateMonitor.queryBydDarkStateOrNull(this);
        if (latestDark != null && !latestDark.equals(appliedUiDarkMode) && !isFinishing() && !isDestroyed()) {
            AppLogger.i(TAG, "检测到比亚迪日夜模式变化 (" + appliedUiDarkMode + " -> " + latestDark + ")，重建界面以同步浅色/深色主题");
            recreate();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (gridAdapter != null) {
            gridAdapter.release();
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        navHome = findViewById(R.id.navHome);
        navWallpaper = findViewById(R.id.navWallpaper);
        navAbout = findViewById(R.id.navAbout);
        ivNavHome = findViewById(R.id.ivNavHome);
        ivNavWallpaper = findViewById(R.id.ivNavWallpaper);
        ivNavAbout = findViewById(R.id.ivNavAbout);
        tvNavHome = findViewById(R.id.tvNavHome);
        tvNavWallpaper = findViewById(R.id.tvNavWallpaper);
        tvNavAbout = findViewById(R.id.tvNavAbout);
        viewSidebarStatusDot = findViewById(R.id.viewSidebarStatusDot);
        tvSidebarStatus = findViewById(R.id.tvSidebarStatus);

        pageHome = findViewById(R.id.pageHome);
        pageWallpaper = findViewById(R.id.pageWallpaper);
        pageAbout = findViewById(R.id.pageAbout);

        btnHomeInfo = findViewById(R.id.btnHomeInfo);
        btnWallpaperInfo = findViewById(R.id.btnWallpaperInfo);
        btnAboutInfo = findViewById(R.id.btnAboutInfo);
        rowAboutHelp = findViewById(R.id.rowAboutHelp);

        ivSystemModeIcon = findViewById(R.id.ivSystemModeIcon);
        tvSystemModePill = findViewById(R.id.tvSystemModePill);
        boxMasterIcon = findViewById(R.id.boxMasterIcon);
        ivMasterPowerIcon = findViewById(R.id.ivMasterPowerIcon);
        viewMasterStatusDot = findViewById(R.id.viewMasterStatusDot);
        tvMasterStatusBadge = findViewById(R.id.tvMasterStatusBadge);
        chipBackupState = findViewById(R.id.chipBackupState);
        chipApplyWarning = findViewById(R.id.chipApplyWarning);
        tvApplyWarningBadge = findViewById(R.id.tvApplyWarningBadge);
        switchMasterEnable = findViewById(R.id.switchMasterEnable);
        cardModeStatic = findViewById(R.id.cardModeStatic);
        cardModeLive = findViewById(R.id.cardModeLive);
        boxModeStaticIcon = findViewById(R.id.boxModeStaticIcon);
        boxModeLiveIcon = findViewById(R.id.boxModeLiveIcon);
        ivModeStaticIcon = findViewById(R.id.ivModeStaticIcon);
        ivModeLiveIcon = findViewById(R.id.ivModeLiveIcon);
        ivModeStaticCheck = findViewById(R.id.ivModeStaticCheck);
        ivModeLiveCheck = findViewById(R.id.ivModeLiveCheck);
        layoutLiveWallpaperAction = findViewById(R.id.layoutLiveWallpaperAction);
        ivLiveWallpaperStateIcon = findViewById(R.id.ivLiveWallpaperStateIcon);
        tvLiveWallpaperState = findViewById(R.id.tvLiveWallpaperState);
        btnActivateLiveWallpaper = findViewById(R.id.btnActivateLiveWallpaper);
        cardHomeDayPreview = findViewById(R.id.cardHomeDayPreview);
        cardHomeNightPreview = findViewById(R.id.cardHomeNightPreview);
        frameHomeDaySlot = findViewById(R.id.frameHomeDaySlot);
        frameHomeNightSlot = findViewById(R.id.frameHomeNightSlot);
        ivHomeDayPreview = findViewById(R.id.ivHomeDayPreview);
        ivHomeNightPreview = findViewById(R.id.ivHomeNightPreview);
        badgeHomeDayPolicy = findViewById(R.id.badgeHomeDayPolicy);
        ivHomeDayPolicyIcon = findViewById(R.id.ivHomeDayPolicyIcon);
        tvHomeDayPolicyCount = findViewById(R.id.tvHomeDayPolicyCount);
        badgeHomeNightPolicy = findViewById(R.id.badgeHomeNightPolicy);
        ivHomeNightPolicyIcon = findViewById(R.id.ivHomeNightPolicyIcon);
        tvHomeNightPolicyCount = findViewById(R.id.tvHomeNightPolicyCount);
        badgeHomeDayVideo = findViewById(R.id.badgeHomeDayVideo);
        tvHomeDayVideoDuration = findViewById(R.id.tvHomeDayVideoDuration);
        badgeHomeNightVideo = findViewById(R.id.badgeHomeNightVideo);
        tvHomeNightVideoDuration = findViewById(R.id.tvHomeNightVideoDuration);
        layoutHomeDayEmpty = findViewById(R.id.layoutHomeDayEmpty);
        ivHomeDayEmptyIcon = findViewById(R.id.ivHomeDayEmptyIcon);
        tvHomeDayEmptyLabel = findViewById(R.id.tvHomeDayEmptyLabel);
        layoutHomeNightEmpty = findViewById(R.id.layoutHomeNightEmpty);
        ivHomeNightEmptyIcon = findViewById(R.id.ivHomeNightEmptyIcon);
        tvHomeNightEmptyLabel = findViewById(R.id.tvHomeNightEmptyLabel);
        tvHomeDayName = findViewById(R.id.tvHomeDayName);
        tvHomeNightName = findViewById(R.id.tvHomeNightName);

        applyRoundClip(ivHomeDayPreview, 12f);
        applyRoundClip(ivHomeNightPreview, 12f);

        tabWallpaperDay = findViewById(R.id.tabWallpaperDay);
        ivTabWallpaperDay = findViewById(R.id.ivTabWallpaperDay);
        tvTabWallpaperDay = findViewById(R.id.tvTabWallpaperDay);
        tabWallpaperNight = findViewById(R.id.tabWallpaperNight);
        ivTabWallpaperNight = findViewById(R.id.ivTabWallpaperNight);
        tvTabWallpaperNight = findViewById(R.id.tvTabWallpaperNight);
        btnAddWallpaper = findViewById(R.id.btnAddWallpaper);
        tabStrategyFixed = findViewById(R.id.tabStrategyFixed);
        ivTabStrategyFixed = findViewById(R.id.ivTabStrategyFixed);
        tvTabStrategyFixed = findViewById(R.id.tvTabStrategyFixed);
        tabStrategyRandom = findViewById(R.id.tabStrategyRandom);
        ivTabStrategyRandom = findViewById(R.id.ivTabStrategyRandom);
        tvTabStrategyRandom = findViewById(R.id.tvTabStrategyRandom);
        gridWallpapers = findViewById(R.id.gridWallpapers);
        layoutWallpaperEmpty = findViewById(R.id.layoutWallpaperEmpty);
        tvWallpaperEmptyTitle = findViewById(R.id.tvWallpaperEmptyTitle);
        btnEmptyAddWallpaper = findViewById(R.id.btnEmptyAddWallpaper);
        tvEmptyAddWallpaper = findViewById(R.id.tvEmptyAddWallpaper);

        gridAdapter = new WallpaperGridAdapter(this, new WallpaperGridAdapter.ItemActionListener() {
            @Override
            public void onItemClicked(WallpaperRepository.WallpaperItem item) {
                handleWallpaperItemClicked(item);
            }

            @Override
            public void onItemLongClicked(WallpaperRepository.WallpaperItem item) {
                handleWallpaperItemLongClicked(item);
            }

            @Override
            public void onCropClicked(WallpaperRepository.WallpaperItem item) {
                if (!item.isVideo()) {
                    showWallpaperCropDialog(item, editingNight);
                }
            }

            @Override
            public void onDeleteClicked(WallpaperRepository.WallpaperItem item) {
                confirmDeleteWallpaper(item);
            }
        });
        gridWallpapers.setAdapter(gridAdapter);

        tvAboutVersion = findViewById(R.id.tvAboutVersion);
        tvAboutDeviceInfo = findViewById(R.id.tvAboutDeviceInfo);
        boxDebugIcon = findViewById(R.id.boxDebugIcon);
        ivDebugIcon = findViewById(R.id.ivDebugIcon);
        switchDebugMode = findViewById(R.id.switchDebugMode);
        cardDebugConsole = findViewById(R.id.cardDebugConsole);
        rowLogFilePath = findViewById(R.id.rowLogFilePath);
        tvLogFilePath = findViewById(R.id.tvLogFilePath);
        btnSetLogPath = findViewById(R.id.btnSetLogPath);
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnExportLogs = findViewById(R.id.btnExportLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        scrollLog = findViewById(R.id.scrollLog);
        tvLogConsole = findViewById(R.id.tvLogConsole);
    }

    private void applyRoundClip(ImageView imageView, float radiusDp) {
        final float radiusPx = radiusDp * getResources().getDisplayMetrics().density;
        imageView.setClipToOutline(true);
        imageView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
    }

    private void setupListeners() {
        navHome.setOnClickListener(v -> selectTab(TAB_HOME));
        navWallpaper.setOnClickListener(v -> selectTab(TAB_WALLPAPER));
        navAbout.setOnClickListener(v -> selectTab(TAB_ABOUT));

        View.OnClickListener infoMenuListener = v -> showInfoMenuDialog();
        btnHomeInfo.setOnClickListener(infoMenuListener);
        btnWallpaperInfo.setOnClickListener(infoMenuListener);
        btnAboutInfo.setOnClickListener(infoMenuListener);
        rowAboutHelp.setOnClickListener(infoMenuListener);
        chipApplyWarning.setOnClickListener(infoMenuListener);

        switchMasterEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            if (isChecked) {
                handleMasterEnableRequested();
            } else {
                handleMasterDisableRequested();
            }
        });

        cardModeStatic.setOnClickListener(v -> handleModeSwitchRequested(AppPrefs.MODE_STATIC_MANAGER));
        cardModeLive.setOnClickListener(v -> handleModeSwitchRequested(AppPrefs.MODE_LIVE_SERVICE));
        btnActivateLiveWallpaper.setOnClickListener(v -> openLiveWallpaperChooser());

        cardHomeDayPreview.setOnClickListener(v -> {
            editingNight = false;
            selectTab(TAB_WALLPAPER);
        });
        cardHomeNightPreview.setOnClickListener(v -> {
            editingNight = true;
            selectTab(TAB_WALLPAPER);
        });

        tabWallpaperDay.setOnClickListener(v -> {
            if (editingNight) {
                editingNight = false;
                refreshWallpaperPage();
            }
        });
        tabWallpaperNight.setOnClickListener(v -> {
            if (!editingNight) {
                editingNight = true;
                refreshWallpaperPage();
            }
        });

        View.OnClickListener addListener = v -> launchWallpaperPicker();
        btnAddWallpaper.setOnClickListener(addListener);
        btnEmptyAddWallpaper.setOnClickListener(addListener);

        tabStrategyFixed.setOnClickListener(v -> updateSelectionMode(AppPrefs.SELECT_FIXED));
        tabStrategyRandom.setOnClickListener(v -> updateSelectionMode(AppPrefs.SELECT_RANDOM));

        switchDebugMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppressControlEvents) {
                return;
            }
            prefs.setDebugModeEnabled(isChecked);
            AppLogger.i(TAG, "调试模式 -> " + (isChecked ? "已开启" : "已关闭"));
            syncDebugConsoleState();
        });

        btnSetLogPath.setOnClickListener(v -> showLogPathConfigDialog());
        rowLogFilePath.setOnClickListener(v -> showLogPathConfigDialog());

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

    private void selectTab(int tab) {
        currentTab = tab;
        pageHome.setVisibility(tab == TAB_HOME ? View.VISIBLE : View.GONE);
        pageWallpaper.setVisibility(tab == TAB_WALLPAPER ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(tab == TAB_ABOUT ? View.VISIBLE : View.GONE);

        updateSidebarItemStyle(navHome, ivNavHome, tvNavHome, tab == TAB_HOME);
        updateSidebarItemStyle(navWallpaper, ivNavWallpaper, tvNavWallpaper, tab == TAB_WALLPAPER);
        updateSidebarItemStyle(navAbout, ivNavAbout, tvNavAbout, tab == TAB_ABOUT);

        if (tab == TAB_HOME) {
            renderHomeStatus();
            refreshHomeWallpaperOverviewAsync();
        } else if (tab == TAB_WALLPAPER) {
            refreshWallpaperPage();
        } else if (tab == TAB_ABOUT) {
            syncDebugConsoleState();
        }
    }

    private void updateSidebarItemStyle(View container, ImageView icon, TextView label, boolean selected) {
        container.setSelected(selected);
        int accentColor = getColor(R.color.accent_primary);
        int primaryColor = getColor(R.color.text_primary);
        int secondaryColor = getColor(R.color.text_secondary);

        icon.setColorFilter(selected ? accentColor : secondaryColor);
        label.setTextColor(selected ? accentColor : primaryColor);
        label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private boolean isSystemNightNow() {
        Boolean bydDark = VehicleStateMonitor.queryBydDarkStateOrNull(this);
        return Boolean.TRUE.equals(bydDark);
    }

    private void renderHomeStatus() {
        VehicleStateMonitor.BydDayNightReading bydReading = VehicleStateMonitor.readBydNightModeNow(this);
        boolean modeValid = bydReading.isValid();
        boolean systemDark = bydReading.isDark();
        String modeHint = VehicleStateMonitor.formatBydWallpaperModeShort(bydReading.wallpaperModeCode);

        if (modeValid) {
            ivSystemModeIcon.setImageResource(systemDark ? R.drawable.ic_moon : R.drawable.ic_sun);
            ivSystemModeIcon.setColorFilter(getColor(R.color.accent_primary));
            String pillText = systemDark ? "夜间" : "日间";
            if (modeHint != null) {
                pillText += " · " + modeHint;
            }
            tvSystemModePill.setText(pillText);
        } else {
            ivSystemModeIcon.setImageResource(R.drawable.ic_alert_triangle);
            ivSystemModeIcon.setColorFilter(getColor(R.color.status_warning));
            tvSystemModePill.setText("未检测");
        }

        boolean enabled = prefs.isEnabled();
        boolean running = AutoDarkMonitorService.isRunning();
        int mode = prefs.getWallpaperMode();

        suppressControlEvents = true;
        try {
            switchMasterEnable.setChecked(enabled);
        } finally {
            suppressControlEvents = false;
        }

        int dotColor;
        if (enabled && running) {
            boxMasterIcon.setSelected(true);
            if (modeValid) {
                dotColor = getColor(R.color.status_success);
                ivMasterPowerIcon.setColorFilter(getColor(R.color.accent_primary));
                tvMasterStatusBadge.setText("运行中");
                tvMasterStatusBadge.setTextColor(dotColor);
                tvSidebarStatus.setText("运行中 · " + (systemDark ? "夜间" : "日间"));
            } else {
                dotColor = getColor(R.color.status_warning);
                ivMasterPowerIcon.setColorFilter(dotColor);
                tvMasterStatusBadge.setText("信号异常");
                tvMasterStatusBadge.setTextColor(dotColor);
                tvSidebarStatus.setText("信号异常");
            }
        } else if (enabled) {
            boxMasterIcon.setSelected(true);
            dotColor = getColor(R.color.status_warning);
            ivMasterPowerIcon.setColorFilter(dotColor);
            tvMasterStatusBadge.setText("启动中");
            tvMasterStatusBadge.setTextColor(dotColor);
            tvSidebarStatus.setText("启动中");
        } else {
            boxMasterIcon.setSelected(false);
            dotColor = getColor(R.color.status_idle);
            ivMasterPowerIcon.setColorFilter(getColor(R.color.text_secondary));
            tvMasterStatusBadge.setText("已关闭");
            tvMasterStatusBadge.setTextColor(getColor(R.color.text_secondary));
            tvSidebarStatus.setText("未开启");
        }
        viewMasterStatusDot.getBackground().mutate().setTint(dotColor);
        viewSidebarStatusDot.getBackground().mutate().setTint(dotColor);

        chipBackupState.setVisibility(prefs.hasOriginalBackup() ? View.VISIBLE : View.GONE);
        chipApplyWarning.setVisibility(enabled && lastApplyIsError ? View.VISIBLE : View.GONE);

        // 主页预览卡片高亮当前系统正在生效的一侧（日间或夜间）
        cardHomeDayPreview.setSelected(enabled && modeValid && !systemDark);
        cardHomeNightPreview.setSelected(enabled && modeValid && systemDark);

        boolean isStatic = (mode == AppPrefs.MODE_STATIC_MANAGER);
        cardModeStatic.setSelected(isStatic);
        cardModeLive.setSelected(!isStatic);
        boxModeStaticIcon.setSelected(isStatic);
        boxModeLiveIcon.setSelected(!isStatic);
        ivModeStaticCheck.setVisibility(isStatic ? View.VISIBLE : View.GONE);
        ivModeLiveCheck.setVisibility(!isStatic ? View.VISIBLE : View.GONE);
        ivModeStaticIcon.setColorFilter(getColor(isStatic ? R.color.accent_primary : R.color.text_secondary));
        ivModeLiveIcon.setColorFilter(getColor(!isStatic ? R.color.accent_primary : R.color.text_secondary));

        if (!isStatic) {
            layoutLiveWallpaperAction.setVisibility(View.VISIBLE);
            boolean active = WallpaperController.isOurLiveWallpaperActive(this);
            int stateColor = getColor(active ? R.color.status_success : R.color.status_warning);
            ivLiveWallpaperStateIcon.setImageResource(
                    active ? R.drawable.ic_check_circle : R.drawable.ic_alert_triangle);
            ivLiveWallpaperStateIcon.setColorFilter(stateColor);
            tvLiveWallpaperState.setText(active ? "动态壁纸已激活" : "未激活系统动态壁纸");
            tvLiveWallpaperState.setTextColor(stateColor);
            btnActivateLiveWallpaper.setVisibility(active ? View.GONE : View.VISIBLE);
        } else {
            layoutLiveWallpaperAction.setVisibility(View.GONE);
        }
    }

    private void showInfoMenuDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_info_menu);
        dialog.setCanceledOnTouchOutside(true);

        TextView tvSummary = dialog.findViewById(R.id.tvInfoRuntimeSummary);
        if (tvSummary != null) {
            tvSummary.setText(buildRuntimeStatusSummary());
        }
        View btnClose = dialog.findViewById(R.id.btnCloseInfoDialog);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int maxDialogWidth = dpToPx(560);
            int targetWidth = Math.min((int) (screenWidth * 0.88f), maxDialogWidth);
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private String buildRuntimeStatusSummary() {
        VehicleStateMonitor.BydDayNightReading reading = VehicleStateMonitor.readBydNightModeNow(this);
        StringBuilder sb = new StringBuilder();
        sb.append("当前状态：");
        if (prefs.isEnabled() && AutoDarkMonitorService.isRunning()) {
            sb.append(reading.isValid()
                    ? ("运行中 · " + (reading.isDark() ? "夜间" : "日间"))
                    : "运行中 · 信号异常");
        } else if (prefs.isEnabled()) {
            sb.append("启动中");
        } else {
            sb.append("已关闭");
        }
        if (prefs.hasOriginalBackup()) {
            String meta = prefs.getOriginalBackupMeta();
            sb.append("\n原壁纸备份：已备份").append(meta != null ? " (" + meta + ")" : "");
        } else if (prefs.getWallpaperMode() == AppPrefs.MODE_STATIC_MANAGER) {
            sb.append("\n原壁纸备份：未备份");
        }
        if (lastApplyStatusText != null && !lastApplyStatusText.isEmpty()) {
            sb.append("\n最近执行：").append(lastApplyStatusText);
        }
        return sb.toString();
    }

    private void refreshHomeWallpaperOverviewAsync() {
        final boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        ioExecutor.execute(() -> {
            final List<WallpaperRepository.WallpaperItem> dayAll =
                    WallpaperRepository.listWallpapers(MainActivity.this, false, true);
            final List<WallpaperRepository.WallpaperItem> nightAll =
                    WallpaperRepository.listWallpapers(MainActivity.this, true, true);
            final List<WallpaperRepository.WallpaperItem> dayEligible =
                    WallpaperRepository.listWallpapers(MainActivity.this, false, isLiveMode);
            final List<WallpaperRepository.WallpaperItem> nightEligible =
                    WallpaperRepository.listWallpapers(MainActivity.this, true, isLiveMode);
            final WallpaperRepository.WallpaperItem dayActive =
                    WallpaperRepository.getActiveItem(MainActivity.this, false, isLiveMode);
            final WallpaperRepository.WallpaperItem nightActive =
                    WallpaperRepository.getActiveItem(MainActivity.this, true, isLiveMode);

            final Bitmap dayBmp = dayActive != null
                    ? WallpaperController.decodeSampledCroppedBitmap(
                    WallpaperRepository.getThumbnailFile(MainActivity.this, false, dayActive),
                    dayActive.isVideo() ? null : dayActive.cropRect, 480, 300)
                    : null;
            final Bitmap nightBmp = nightActive != null
                    ? WallpaperController.decodeSampledCroppedBitmap(
                    WallpaperRepository.getThumbnailFile(MainActivity.this, true, nightActive),
                    nightActive.isVideo() ? null : nightActive.cropRect, 480, 300)
                    : null;

            final int dayMode = prefs.getSelectionMode(false);
            final int nightMode = prefs.getSelectionMode(true);

            runOnUiThread(() -> {
                bindHomeOverviewCard(
                        false, isLiveMode, dayAll.size(), dayEligible.size(), dayMode, dayActive, dayBmp,
                        frameHomeDaySlot, ivHomeDayPreview,
                        badgeHomeDayPolicy, ivHomeDayPolicyIcon, tvHomeDayPolicyCount,
                        badgeHomeDayVideo, tvHomeDayVideoDuration,
                        layoutHomeDayEmpty, ivHomeDayEmptyIcon, tvHomeDayEmptyLabel,
                        tvHomeDayName);
                bindHomeOverviewCard(
                        true, isLiveMode, nightAll.size(), nightEligible.size(), nightMode, nightActive, nightBmp,
                        frameHomeNightSlot, ivHomeNightPreview,
                        badgeHomeNightPolicy, ivHomeNightPolicyIcon, tvHomeNightPolicyCount,
                        badgeHomeNightVideo, tvHomeNightVideoDuration,
                        layoutHomeNightEmpty, ivHomeNightEmptyIcon, tvHomeNightEmptyLabel,
                        tvHomeNightName);
            });
        });
    }

    private void bindHomeOverviewCard(boolean isNight, boolean isLiveMode,
                                      int totalCount, int eligibleCount, int selectionMode,
                                      WallpaperRepository.WallpaperItem activeItem, Bitmap bmp,
                                      View slotFrame, ImageView iv,
                                      View badgePolicy, ImageView ivPolicyIcon, TextView tvPolicyCount,
                                      View badgeVideo, TextView tvVideoDuration,
                                      View layoutEmpty, ImageView ivEmptyIcon, TextView tvEmptyLabel,
                                      TextView tvName) {
        if (eligibleCount == 0 || activeItem == null) {
            slotFrame.setBackgroundResource(R.drawable.bg_empty_slot);
            iv.setImageDrawable(null);
            badgePolicy.setVisibility(View.GONE);
            badgeVideo.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.VISIBLE);
            if (!isLiveMode && totalCount > 0) {
                ivEmptyIcon.setImageResource(R.drawable.ic_lock);
                tvEmptyLabel.setText("仅动态模式");
                tvName.setText(totalCount + " 个视频");
            } else {
                ivEmptyIcon.setImageResource(R.drawable.ic_add_photo);
                tvEmptyLabel.setText(isNight ? "添加夜间" : "添加日间");
                tvName.setText("未设置");
            }
            return;
        }
        slotFrame.setBackgroundResource(R.drawable.bg_preview_placeholder);
        layoutEmpty.setVisibility(View.GONE);
        iv.setImageBitmap(bmp);

        badgePolicy.setVisibility(View.VISIBLE);
        ivPolicyIcon.setImageResource(selectionMode == AppPrefs.SELECT_RANDOM
                ? R.drawable.ic_strategy_random
                : R.drawable.ic_strategy_fixed);
        tvPolicyCount.setText(String.valueOf(eligibleCount));

        if (activeItem.isVideo()) {
            badgeVideo.setVisibility(View.VISIBLE);
            tvVideoDuration.setText(activeItem.durationMs > 0L
                    ? VideoSpecEvaluator.formatDuration(activeItem.durationMs)
                    : "视频");
        } else {
            badgeVideo.setVisibility(View.GONE);
        }
        tvName.setText(activeItem.displayName);
    }

    private void setMasterSwitchCheckedSilently(boolean checked) {
        suppressControlEvents = true;
        try {
            switchMasterEnable.setChecked(checked);
        } finally {
            suppressControlEvents = false;
        }
    }

    private void handleMasterEnableRequested() {
        boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        List<WallpaperRepository.WallpaperItem> dayAll = WallpaperRepository.listWallpapers(this, false, true);
        List<WallpaperRepository.WallpaperItem> nightAll = WallpaperRepository.listWallpapers(this, true, true);
        List<WallpaperRepository.WallpaperItem> dayEligible = WallpaperRepository.listWallpapers(this, false, isLiveMode);
        List<WallpaperRepository.WallpaperItem> nightEligible = WallpaperRepository.listWallpapers(this, true, isLiveMode);

        if (dayEligible.isEmpty() && nightEligible.isEmpty()) {
            setMasterSwitchCheckedSilently(false);
            if (!isLiveMode && (!dayAll.isEmpty() || !nightAll.isEmpty())) {
                new AlertDialog.Builder(this)
                        .setTitle("当前仅有视频壁纸")
                        .setMessage("“直接修改壁纸”模式仅支持静态图片，视频壁纸仅在“动态壁纸模式”下可用。\n\n请添加图片壁纸，或先切换为动态壁纸模式。")
                        .setPositiveButton("切换为动态模式", (d, w) -> {
                            prefs.setWallpaperMode(AppPrefs.MODE_LIVE_SERVICE);
                            renderHomeStatus();
                            refreshHomeWallpaperOverviewAsync();
                            proceedEnableWithModeCheck();
                        })
                        .setNeutralButton("去添加图片", (d, w) -> selectTab(TAB_WALLPAPER))
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("尚未添加壁纸")
                    .setMessage("请先至少添加日间或夜间壁纸，再开启自动切换。")
                    .setPositiveButton("去添加壁纸", (d, w) -> selectTab(TAB_WALLPAPER))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        if (dayEligible.isEmpty() || nightEligible.isEmpty()) {
            final boolean missingNight = nightEligible.isEmpty();
            String missingLabel = missingNight ? "夜间" : "日间";
            boolean onlyHasVideo = !isLiveMode && !(missingNight ? nightAll : dayAll).isEmpty();
            String detail = onlyHasVideo
                    ? ("您的" + missingLabel + "壁纸库目前仅有视频，直接修改模式下切换到" + missingLabel + "模式时将保持原样。是否继续开启？")
                    : ("您尚未添加" + missingLabel + "壁纸，切换到" + missingLabel + "模式时将保持原样。是否继续开启？");
            new AlertDialog.Builder(this)
                    .setTitle("缺少可用" + missingLabel + "壁纸")
                    .setMessage(detail)
                    .setPositiveButton("仍然开启", (d, w) -> proceedEnableWithModeCheck())
                    .setNeutralButton("去补充", (d, w) -> {
                        setMasterSwitchCheckedSilently(false);
                        editingNight = missingNight;
                        selectTab(TAB_WALLPAPER);
                    })
                    .setNegativeButton("取消", (d, w) -> setMasterSwitchCheckedSilently(false))
                    .setOnCancelListener(d -> setMasterSwitchCheckedSilently(false))
                    .show();
            return;
        }

        proceedEnableWithModeCheck();
    }

    private void proceedEnableWithModeCheck() {
        int mode = prefs.getWallpaperMode();
        if (mode == AppPrefs.MODE_STATIC_MANAGER && !prefs.hasOriginalBackup()) {
            setMasterSwitchCheckedSilently(false);
            startStaticBackupThenRun(PENDING_ENABLE_STATIC);
            return;
        }

        enableServiceAndApply(mode == AppPrefs.MODE_LIVE_SERVICE);
    }

    private void enableServiceAndApply(boolean checkLiveHint) {
        requestNotificationPermissionIfNeeded();
        prefs.setEnabled(true);
        WallpaperController.resetDedupState();
        AutoDarkMonitorService.startMonitor(this);
        AppLogger.i(TAG, "用户开启自动壁纸切换 | 模式=" + AppPrefs.formatWallpaperMode(prefs.getWallpaperMode()));
        renderHomeStatus();

        if (checkLiveHint && !WallpaperController.isOurLiveWallpaperActive(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("请激活系统动态壁纸")
                    .setMessage("自动切换已开启。使用“动态壁纸模式”需要将本应用设为系统动态壁纸才能在桌面生效。")
                    .setPositiveButton("前往系统激活", (d, w) -> openLiveWallpaperChooser())
                    .setNegativeButton("稍后再说", null)
                    .show();
        }
    }

    private void handleMasterDisableRequested() {
        prefs.setEnabled(false);
        AutoDarkMonitorService.stopMonitor(this);
        AppLogger.i(TAG, "用户关闭自动壁纸切换");

        if (prefs.hasOriginalBackup()) {
            ioExecutor.execute(() -> {
                try {
                    WallpaperRepository.restoreOriginalWallpaper(MainActivity.this);
                    runOnUiThread(() -> {
                        renderHomeStatus();
                        Toast.makeText(MainActivity.this, "已关闭并复原原有壁纸", Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable t) {
                    AppLogger.e(TAG, "关闭开关时复原原壁纸失败", t);
                    runOnUiThread(() -> {
                        renderHomeStatus();
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("复原原壁纸失败")
                                .setMessage("已关闭自动切换，但复原原有壁纸时出错：" + t.getMessage()
                                        + "\n备份文件仍已保留，可在调试日志中查看详情。")
                                .setPositiveButton("知道了", null)
                                .show();
                    });
                }
            });
        } else {
            renderHomeStatus();
            if (prefs.getWallpaperMode() == AppPrefs.MODE_STATIC_MANAGER) {
                Toast.makeText(this, "已关闭自动切换（未备份原壁纸）", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已关闭自动切换", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleModeSwitchRequested(final int targetMode) {
        int currentMode = prefs.getWallpaperMode();
        if (currentMode == targetMode) {
            return;
        }

        if (!prefs.isEnabled()) {
            prefs.setWallpaperMode(targetMode);
            AppLogger.i(TAG, "切换壁纸模式 -> " + AppPrefs.formatWallpaperMode(targetMode) + " (当前未开启)");
            renderHomeStatus();
            refreshHomeWallpaperOverviewAsync();
            refreshWallpaperPage();
            return;
        }

        if (targetMode == AppPrefs.MODE_STATIC_MANAGER) {
            if (!prefs.hasOriginalBackup()) {
                startStaticBackupThenRun(PENDING_SWITCH_TO_STATIC);
                return;
            }
            applySwitchToStaticMode();
            return;
        }

        // 从直接修改壁纸切换为动态壁纸模式：先复原原有静态壁纸，再启用动态模式
        if (prefs.hasOriginalBackup()) {
            ioExecutor.execute(() -> {
                try {
                    WallpaperRepository.restoreOriginalWallpaper(MainActivity.this);
                    runOnUiThread(() -> applySwitchToLiveMode("已复原原有静态壁纸，并切换为动态壁纸模式"));
                } catch (Throwable t) {
                    AppLogger.e(TAG, "切换为动态模式前复原原壁纸失败", t);
                    runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                            .setTitle("复原原壁纸失败")
                            .setMessage("切换模式前尝试复原原壁纸失败：" + t.getMessage()
                                    + "\n是否仍要切换为动态壁纸模式？")
                            .setPositiveButton("仍然切换", (d, w) -> applySwitchToLiveMode("已切换为动态壁纸模式"))
                            .setNegativeButton("取消", null)
                            .show());
                }
            });
        } else {
            applySwitchToLiveMode("已切换为动态壁纸模式");
        }
    }

    private void applySwitchToStaticMode() {
        prefs.setWallpaperMode(AppPrefs.MODE_STATIC_MANAGER);
        AppLogger.i(TAG, "切换壁纸模式 -> 直接修改壁纸");
        renderHomeStatus();
        refreshHomeWallpaperOverviewAsync();
        refreshWallpaperPage();
        AutoDarkMonitorService.notifyConfigUpdated(this);
        Toast.makeText(this, "已切换为直接修改壁纸模式", Toast.LENGTH_SHORT).show();
    }

    private void applySwitchToLiveMode(String toastMsg) {
        prefs.setWallpaperMode(AppPrefs.MODE_LIVE_SERVICE);
        AppLogger.i(TAG, "切换壁纸模式 -> 动态壁纸模式");
        renderHomeStatus();
        refreshHomeWallpaperOverviewAsync();
        refreshWallpaperPage();
        AutoDarkMonitorService.notifyConfigUpdated(this);
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
    }

    private void startStaticBackupThenRun(final int actionType) {
        Toast.makeText(this, "正在备份原有壁纸...", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            final WallpaperRepository.BackupResult result =
                    WallpaperRepository.backupCurrentSystemWallpaper(MainActivity.this);
            runOnUiThread(() -> onBackupResult(actionType, result));
        });
    }

    private void onBackupResult(int actionType, WallpaperRepository.BackupResult result) {
        if (result.status == WallpaperRepository.BackupResult.Status.SUCCESS) {
            completePendingAction(actionType);
            return;
        }

        if (result.status == WallpaperRepository.BackupResult.Status.PERMISSION_REQUIRED) {
            new AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限以备份原壁纸")
                    .setMessage("为了在关闭自动切换后帮您复原原有桌面壁纸，需要先授予文件访问权限以读取系统当前壁纸。\n\n您也可以选择不备份直接继续。")
                    .setPositiveButton("前往授权", (d, w) -> {
                        pendingActionAfterPermission = actionType;
                        requestReadWallpaperPermission();
                    })
                    .setNeutralButton("不备份继续", (d, w) -> completePendingAction(actionType))
                    .setNegativeButton("取消", (d, w) -> {
                        pendingActionAfterPermission = PENDING_NONE;
                        renderHomeStatus();
                    })
                    .setOnCancelListener(d -> {
                        pendingActionAfterPermission = PENDING_NONE;
                        renderHomeStatus();
                    })
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("无法备份原有壁纸")
                .setMessage(result.message + "\n\n因此关闭开关后将无法自动复原当前壁纸，是否仍要继续？")
                .setPositiveButton("不备份继续", (d, w) -> completePendingAction(actionType))
                .setNegativeButton("取消", (d, w) -> renderHomeStatus())
                .setOnCancelListener(d -> renderHomeStatus())
                .show();
    }

    private void completePendingAction(int actionType) {
        pendingActionAfterPermission = PENDING_NONE;
        if (actionType == PENDING_ENABLE_STATIC) {
            enableServiceAndApply(false);
        } else if (actionType == PENDING_SWITCH_TO_STATIC) {
            applySwitchToStaticMode();
        }
    }

    private void handlePendingActionAfterPermission() {
        if (pendingActionAfterPermission == PENDING_NONE) {
            return;
        }
        if (WallpaperRepository.hasReadWallpaperPermission(this)) {
            int action = pendingActionAfterPermission;
            pendingActionAfterPermission = PENDING_NONE;
            startStaticBackupThenRun(action);
        }
    }

    private void requestReadWallpaperPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAllFilesAccessSettings();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_READ_STORAGE_FOR_BACKUP);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
            }
        }
    }

    private void refreshWallpaperPage() {
        boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        List<WallpaperRepository.WallpaperItem> dayItems = WallpaperRepository.listWallpapers(this, false, true);
        List<WallpaperRepository.WallpaperItem> nightItems = WallpaperRepository.listWallpapers(this, true, true);

        tvTabWallpaperDay.setText("日间 " + dayItems.size());
        tvTabWallpaperNight.setText("夜间 " + nightItems.size());
        styleSegmentedTab(tabWallpaperDay, ivTabWallpaperDay, tvTabWallpaperDay, !editingNight);
        styleSegmentedTab(tabWallpaperNight, ivTabWallpaperNight, tvTabWallpaperNight, editingNight);

        int selectionMode = prefs.getSelectionMode(editingNight);
        boolean isRandom = (selectionMode == AppPrefs.SELECT_RANDOM);
        styleSegmentedTab(tabStrategyFixed, ivTabStrategyFixed, tvTabStrategyFixed, !isRandom);
        styleSegmentedTab(tabStrategyRandom, ivTabStrategyRandom, tvTabStrategyRandom, isRandom);

        tvEmptyAddWallpaper.setText("添加图片或视频");

        List<WallpaperRepository.WallpaperItem> activeList = editingNight ? nightItems : dayItems;
        if (activeList.isEmpty()) {
            gridWallpapers.setVisibility(View.GONE);
            layoutWallpaperEmpty.setVisibility(View.VISIBLE);
            tvWallpaperEmptyTitle.setText(editingNight ? "暂无夜间壁纸" : "暂无日间壁纸");
        } else {
            layoutWallpaperEmpty.setVisibility(View.GONE);
            gridWallpapers.setVisibility(View.VISIBLE);
        }

        String selectedId = prefs.getSelectedWallpaperId(editingNight);
        String activeId = prefs.getActiveWallpaperId(editingNight);
        gridAdapter.updateData(activeList, editingNight, selectionMode, selectedId, activeId, isLiveMode);
    }

    private void styleSegmentedTab(View container, ImageView icon, TextView label, boolean selected) {
        container.setSelected(selected);
        int color = getColor(selected ? R.color.accent_primary : R.color.text_secondary);
        icon.setColorFilter(color);
        label.setTextColor(color);
        label.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateSelectionMode(int mode) {
        int current = prefs.getSelectionMode(editingNight);
        if (current == mode) {
            return;
        }
        prefs.setSelectionMode(editingNight, mode);
        String modeLabel = editingNight ? "夜间" : "日间";
        AppLogger.i(TAG, modeLabel + "壁纸策略切换 -> " + (mode == AppPrefs.SELECT_RANDOM ? "随机轮换" : "使用指定壁纸"));
        refreshWallpaperPage();
        refreshHomeWallpaperOverviewAsync();

        if (prefs.isEnabled() && isSystemNightNow() == editingNight) {
            WallpaperController.resetDedupState();
            AutoDarkMonitorService.notifyConfigUpdated(this);
        } else {
            WallpaperController.notifyLibraryChanged(this);
        }
    }

    private void handleWallpaperItemClicked(final WallpaperRepository.WallpaperItem item) {
        boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        if (!isLiveMode && item.isVideo()) {
            new AlertDialog.Builder(this)
                    .setTitle("仅动态壁纸模式可用")
                    .setMessage("“" + item.displayName + "”是视频壁纸，当前“直接修改壁纸”模式仅支持静态图片。\n\n您可以切换为“动态壁纸模式”后使用，或查看详情/删除。")
                    .setPositiveButton("切换为动态模式", (d, w) -> handleModeSwitchRequested(AppPrefs.MODE_LIVE_SERVICE))
                    .setNeutralButton("查看详情", (d, w) -> showWallpaperPreviewDialog(item))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        int selectionMode = prefs.getSelectionMode(editingNight);
        String currentSelected = prefs.getSelectedWallpaperId(editingNight);

        if (selectionMode == AppPrefs.SELECT_FIXED && !item.id.equals(currentSelected)) {
            prefs.setSelectedWallpaperId(editingNight, item.id);
            prefs.setActiveWallpaperId(editingNight, item.id);
            AppLogger.i(TAG, "指定" + (editingNight ? "夜间" : "日间") + "壁纸 -> " + item.displayName);
            refreshWallpaperPage();
            refreshHomeWallpaperOverviewAsync();

            if (prefs.isEnabled() && isSystemNightNow() == editingNight) {
                WallpaperController.resetDedupState();
                AutoDarkMonitorService.notifyConfigUpdated(this);
            } else {
                WallpaperController.notifyLibraryChanged(this);
            }
            Toast.makeText(this, "已设为" + (editingNight ? "夜间" : "日间") + "壁纸", Toast.LENGTH_SHORT).show();
            return;
        }

        showWallpaperPreviewDialog(item);
    }

    private void handleWallpaperItemLongClicked(final WallpaperRepository.WallpaperItem item) {
        final boolean targetNight = editingNight;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        int selectionMode = prefs.getSelectionMode(targetNight);
        String currentSelected = prefs.getSelectedWallpaperId(targetNight);
        if ((isLiveMode || !item.isVideo())
                && !(selectionMode == AppPrefs.SELECT_FIXED && item.id.equals(currentSelected))) {
            labels.add(selectionMode == AppPrefs.SELECT_RANDOM
                    ? "改用指定此壁纸"
                    : ("设为" + (targetNight ? "夜间" : "日间") + "壁纸"));
            actions.add(() -> {
                prefs.setSelectionMode(targetNight, AppPrefs.SELECT_FIXED);
                prefs.setSelectedWallpaperId(targetNight, item.id);
                prefs.setActiveWallpaperId(targetNight, item.id);
                refreshWallpaperPage();
                refreshHomeWallpaperOverviewAsync();
                notifyWallpaperAppliedIfNeeded(targetNight);
                Toast.makeText(this, "已设为" + (targetNight ? "夜间" : "日间") + "壁纸", Toast.LENGTH_SHORT).show();
            });
        }

        if (!item.isVideo()) {
            labels.add("调整取景");
            actions.add(() -> showWallpaperCropDialog(item, targetNight));
            if (item.hasCustomCrop()) {
                labels.add("恢复默认居中");
                actions.add(() -> resetWallpaperCropToDefault(item, targetNight));
            }
        }

        labels.add("查看预览");
        actions.add(() -> showWallpaperPreviewDialog(item));

        labels.add("删除壁纸");
        actions.add(() -> confirmDeleteWallpaper(item));

        new AlertDialog.Builder(this)
                .setTitle(item.displayName)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showWallpaperPreviewDialog(final WallpaperRepository.WallpaperItem item) {
        final boolean targetNight = editingNight;
        File thumbFile = WallpaperRepository.getThumbnailFile(this, targetNight, item);
        final Bitmap preview = WallpaperController.decodeSampledBitmap(thumbFile, 1280, 800);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16);
        container.setPadding(pad, pad, pad, pad / 2);

        if (item.isVideo()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(240)));
            if (preview != null) {
                iv.setImageBitmap(preview);
            }
            container.addView(iv);
        } else {
            WallpaperCropView previewCropView = new WallpaperCropView(this);
            previewCropView.setInteractive(false);
            previewCropView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(240)));
            float aspect = WallpaperController.resolveScreenAspectRatio(this);
            if (preview != null) {
                previewCropView.setImageAndCrop(preview, item.width, item.height, aspect, item.cropRect);
            }
            container.addView(previewCropView);
        }

        TextView tvInfo = new TextView(this);
        tvInfo.setPadding(0, dpToPx(10), 0, 0);
        StringBuilder info = new StringBuilder();
        info.append(item.displayName);
        if (item.isVideo()) {
            info.append("\n类型：视频壁纸（仅动态壁纸模式可用）");
            info.append("\n分辨率：").append(item.width).append(" × ").append(item.height);
            if (item.durationMs > 0L) {
                info.append("\n时长：").append(VideoSpecEvaluator.formatDuration(item.durationMs));
            }
            if (item.fps > 0f) {
                info.append("\n帧率：").append(VideoSpecEvaluator.formatFps(item.fps));
            }
            info.append("\n大小：").append(VideoSpecEvaluator.formatSize(item.sizeBytes));
        } else {
            info.append("\n类型：静态图片");
            info.append("\n分辨率：").append(item.width).append(" × ").append(item.height);
            info.append("\n大小：").append(VideoSpecEvaluator.formatSize(item.sizeBytes));
            info.append("\n取景：").append(item.hasCustomCrop() ? "已自定义剪裁（高亮区为桌面实际显示范围）" : "默认居中裁剪（高亮区为桌面实际显示范围）");
        }
        tvInfo.setText(info.toString());
        tvInfo.setTextSize(13f);
        container.addView(tvInfo);

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        if (!item.isVideo()) {
            Button btnEditCrop = new Button(this);
            btnEditCrop.setAllCaps(false);
            btnEditCrop.setText("调整桌面取景...");
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.topMargin = dpToPx(8);
            btnEditCrop.setLayoutParams(btnParams);
            btnEditCrop.setOnClickListener(v -> {
                if (dialogHolder[0] != null) {
                    dialogHolder[0].dismiss();
                }
                showWallpaperCropDialog(item, targetNight);
            });
            container.addView(btnEditCrop);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((targetNight ? "夜间壁纸预览" : "日间壁纸预览"))
                .setView(container)
                .setPositiveButton("关闭", null)
                .setNegativeButton("删除此壁纸", (d, w) -> confirmDeleteWallpaper(item));

        boolean isLiveMode = (prefs.getWallpaperMode() == AppPrefs.MODE_LIVE_SERVICE);
        if (prefs.getSelectionMode(targetNight) == AppPrefs.SELECT_RANDOM && (isLiveMode || !item.isVideo())) {
            builder.setNeutralButton("改用指定此壁纸", (d, w) -> {
                prefs.setSelectionMode(targetNight, AppPrefs.SELECT_FIXED);
                prefs.setSelectedWallpaperId(targetNight, item.id);
                prefs.setActiveWallpaperId(targetNight, item.id);
                refreshWallpaperPage();
                refreshHomeWallpaperOverviewAsync();
                notifyWallpaperAppliedIfNeeded(targetNight);
            });
        }
        AlertDialog dialog = builder.create();
        dialogHolder[0] = dialog;
        dialog.setOnDismissListener(d -> {
            if (preview != null && !preview.isRecycled()) {
                preview.recycle();
            }
        });
        dialog.show();
    }

    private void showWallpaperCropDialog(final WallpaperRepository.WallpaperItem item, final boolean targetNight) {
        if (item == null || item.isVideo()) {
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_wallpaper_crop);
        dialog.setCanceledOnTouchOutside(false);

        final WallpaperCropView cropView = dialog.findViewById(R.id.cropPreviewView);
        final TextView tvLoading = dialog.findViewById(R.id.tvCropLoading);
        final TextView tvZoomValue = dialog.findViewById(R.id.tvCropZoomValue);
        final SeekBar seekZoom = dialog.findViewById(R.id.seekCropZoom);
        final TextView btnReset = dialog.findViewById(R.id.btnCropReset);
        final TextView btnCancel = dialog.findViewById(R.id.btnCropCancel);
        final TextView btnSave = dialog.findViewById(R.id.btnCropSave);
        final View btnClose = dialog.findViewById(R.id.btnCloseCropDialog);

        // 动态限高，保证车机横屏下整个弹窗无需纵向滚动即可完整展示
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int targetViewH = Math.max(dpToPx(180), Math.min(dpToPx(340), Math.round(screenH * 0.54f)));
        ViewGroup.LayoutParams cropLp = cropView.getLayoutParams();
        cropLp.height = targetViewH;
        cropView.setLayoutParams(cropLp);

        final AtomicBoolean dismissed = new AtomicBoolean(false);
        final AtomicBoolean updatingSlider = new AtomicBoolean(false);
        final Bitmap[] loadedHolder = new Bitmap[1];

        btnSave.setEnabled(false);
        btnReset.setEnabled(false);
        seekZoom.setEnabled(false);

        cropView.setOnCropChangeListener((cropRect, zoom, maxZoom, isDefaultCenter) -> {
            tvZoomValue.setText(String.format(Locale.US, "%.1f×", zoom));
            btnReset.setEnabled(!isDefaultCenter);
            btnReset.setAlpha(isDefaultCenter ? 0.5f : 1.0f);
            if (!updatingSlider.get()) {
                updatingSlider.set(true);
                try {
                    if (maxZoom <= 1.001f) {
                        seekZoom.setEnabled(false);
                        seekZoom.setProgress(0);
                    } else {
                        seekZoom.setEnabled(true);
                        int progress = Math.round(((zoom - 1f) / (maxZoom - 1f)) * seekZoom.getMax());
                        seekZoom.setProgress(Math.max(0, Math.min(seekZoom.getMax(), progress)));
                    }
                } finally {
                    updatingSlider.set(false);
                }
            }
        });

        seekZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingSlider.get()) {
                    return;
                }
                float maxZoom = cropView.getMaxZoom();
                if (maxZoom <= 1.001f) {
                    return;
                }
                updatingSlider.set(true);
                try {
                    float zoom = 1f + ((float) progress / (float) seekBar.getMax()) * (maxZoom - 1f);
                    cropView.setZoomFromSlider(zoom);
                } finally {
                    updatingSlider.set(false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnReset.setOnClickListener(v -> cropView.resetToDefaultCenter());
        btnSave.setOnClickListener(v -> {
            final CropRect toSave = cropView.isAtDefaultCenter() ? null : cropView.getCurrentCropRect();
            btnSave.setEnabled(false);
            ioExecutor.execute(() -> {
                try {
                    WallpaperRepository.updateWallpaperCrop(MainActivity.this, targetNight, item.id, toSave);
                    runOnUiThread(() -> {
                        if (!dismissed.get()) {
                            dialog.dismiss();
                        }
                        onWallpaperCropSaved(targetNight);
                        Toast.makeText(MainActivity.this,
                                toSave == null ? "已恢复默认居中取景" : "已保存取景裁剪区域",
                                Toast.LENGTH_SHORT).show();
                    });
                } catch (Throwable t) {
                    AppLogger.e(TAG, "保存壁纸取景失败: id=" + item.id, t);
                    runOnUiThread(() -> {
                        if (!dismissed.get()) {
                            btnSave.setEnabled(true);
                        }
                        Toast.makeText(MainActivity.this,
                                "保存失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        dialog.setOnDismissListener(d -> {
            dismissed.set(true);
            if (loadedHolder[0] != null && !loadedHolder[0].isRecycled()) {
                loadedHolder[0].recycle();
                loadedHolder[0] = null;
            }
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int maxDialogWidth = dpToPx(760);
            int targetWidth = Math.min((int) (screenW * 0.88f), maxDialogWidth);
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();

        final float screenAspect = WallpaperController.resolveScreenAspectRatio(this);
        ioExecutor.execute(() -> {
            File file = WallpaperRepository.getWallpaperFile(MainActivity.this, targetNight, item);
            Bitmap bmp = WallpaperController.decodeSampledBitmap(file, 2048, 2048);
            runOnUiThread(() -> {
                if (dismissed.get() || isFinishing() || isDestroyed()) {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                    return;
                }
                if (bmp == null) {
                    tvLoading.setText("读取图片失败，无法调整取景");
                    return;
                }
                loadedHolder[0] = bmp;
                tvLoading.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                cropView.setImageAndCrop(bmp, item.width, item.height, screenAspect, item.cropRect);
            });
        });
    }

    private void resetWallpaperCropToDefault(final WallpaperRepository.WallpaperItem item, final boolean targetNight) {
        ioExecutor.execute(() -> {
            try {
                WallpaperRepository.updateWallpaperCrop(MainActivity.this, targetNight, item.id, null);
                runOnUiThread(() -> {
                    onWallpaperCropSaved(targetNight);
                    Toast.makeText(MainActivity.this, "已恢复默认居中取景", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "恢复默认取景失败: id=" + item.id, t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "恢复失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void onWallpaperCropSaved(boolean targetNight) {
        refreshWallpaperPage();
        refreshHomeWallpaperOverviewAsync();
        notifyWallpaperAppliedIfNeeded(targetNight);
    }

    private void notifyWallpaperAppliedIfNeeded(boolean targetNight) {
        if (prefs.isEnabled() && isSystemNightNow() == targetNight) {
            WallpaperController.resetDedupState();
            AutoDarkMonitorService.notifyConfigUpdated(this);
        } else {
            WallpaperController.notifyLibraryChanged(this);
        }
    }

    private void confirmDeleteWallpaper(final WallpaperRepository.WallpaperItem item) {
        new AlertDialog.Builder(this)
                .setTitle("删除壁纸")
                .setMessage("确定要从" + (editingNight ? "夜间" : "日间") + "壁纸中移除“" + item.displayName + "”吗？")
                .setPositiveButton("删除", (d, w) -> ioExecutor.execute(() -> {
                    try {
                        WallpaperRepository.deleteWallpaper(MainActivity.this, editingNight, item.id);
                        runOnUiThread(() -> {
                            refreshWallpaperPage();
                            refreshHomeWallpaperOverviewAsync();
                            if (prefs.isEnabled() && isSystemNightNow() == editingNight) {
                                WallpaperController.resetDedupState();
                                AutoDarkMonitorService.notifyConfigUpdated(MainActivity.this);
                            } else {
                                WallpaperController.notifyLibraryChanged(MainActivity.this);
                            }
                            Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "删除壁纸失败: id=" + item.id, t);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "删除失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchWallpaperPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_PICK_WALLPAPERS);
        } catch (Throwable t) {
            AppLogger.e(TAG, "打开系统文件选择器失败", t);
            Toast.makeText(this, "无法打开文件选择器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openLiveWallpaperChooser() {
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, AutoDarkWallpaperService.class));
            AppLogger.i(TAG, "打开系统动态壁纸激活界面");
            startActivity(intent);
        } catch (Throwable t) {
            AppLogger.e(TAG, "打开系统动态壁纸选择页失败", t);
            Toast.makeText(this,
                    "无法直接打开动态壁纸设置页，请尝试在车机系统壁纸设置中选取，或切换为“直接修改壁纸”模式",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void populateAboutInfo() {
        String versionName = "2.0.0";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = pi.versionName;
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取应用版本失败", t);
        }
        tvAboutVersion.setText("v" + versionName);
        tvAboutDeviceInfo.setText(Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
    }

    private void syncDebugConsoleState() {
        boolean debugEnabled = prefs.isDebugModeEnabled();
        suppressControlEvents = true;
        try {
            switchDebugMode.setChecked(debugEnabled);
        } finally {
            suppressControlEvents = false;
        }

        boxDebugIcon.setSelected(debugEnabled);
        ivDebugIcon.setColorFilter(getColor(debugEnabled ? R.color.accent_primary : R.color.text_secondary));
        cardDebugConsole.setVisibility(debugEnabled ? View.VISIBLE : View.GONE);
        if (debugEnabled) {
            refreshLogLocationText();
            registerLogListenerIfNeeded();
        } else {
            unregisterLogListenerIfNeeded();
        }
    }

    private void registerLogListenerIfNeeded() {
        if (!logListenerRegistered) {
            AppLogger.addListener(logListener);
            logListenerRegistered = true;
        }
    }

    private void unregisterLogListenerIfNeeded() {
        if (logListenerRegistered) {
            AppLogger.removeListener(logListener);
            logListenerRegistered = false;
        }
    }

    private void refreshLogLocationText() {
        if (tvLogFilePath != null) {
            tvLogFilePath.setText(AppLogger.getLogLocationSummary(this));
        }
    }

    private void showLogPathConfigDialog() {
        int pad = dpToPx(16);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad / 2);
        sv.addView(root);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("当前保存文件：" + AppLogger.getLogFilePath()
                + "\n\n可点击下方预设目录，或通过系统文件选择器导出到 U 盘/任意目录：");
        tvDesc.setTextSize(12f);
        root.addView(tvDesc);

        final EditText etPath = new EditText(this);
        etPath.setSingleLine(true);
        etPath.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etPath.setText(AppLogger.getCurrentLogDirectoryPath(this));
        etPath.setSelection(etPath.getText().length());
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etParams.topMargin = dpToPx(8);
        etParams.bottomMargin = dpToPx(8);
        etPath.setLayoutParams(etParams);
        root.addView(etPath);

        List<File> presets = AppLogger.getPresetLogDirectories(this);
        String[] presetLabels = new String[]{
                "推荐 · 公共下载目录 (Download/BYDAutoDarkLogs)",
                "公共文档目录 (Documents/BYDAutoDarkLogs)",
                "外部存储根目录 (BYDAutoDarkLogs)",
                "应用私有目录 (Android/data/.../files/logs)"
        };
        for (int i = 0; i < presets.size(); i++) {
            final File dir = presets.get(i);
            String label = (i < presetLabels.length ? presetLabels[i] : "预设目录") + "\n" + dir.getAbsolutePath();
            Button btnPreset = new Button(this);
            btnPreset.setAllCaps(false);
            btnPreset.setText(label);
            btnPreset.setTextSize(11f);
            btnPreset.setOnClickListener(v -> {
                etPath.setText(dir.getAbsolutePath());
                etPath.setSelection(etPath.getText().length());
            });
            root.addView(btnPreset);
        }

        String safTree = prefs.getLogSafTreeUri();
        if (safTree != null && !safTree.trim().isEmpty()) {
            TextView tvSafStatus = new TextView(this);
            tvSafStatus.setText("\n已绑定 SAF 导出目录：" + safTree);
            tvSafStatus.setTextSize(11f);
            root.addView(tvSafStatus);
        }

        Button btnPickSafTree = new Button(this);
        btnPickSafTree.setAllCaps(false);
        btnPickSafTree.setText("用系统文件选择器绑定导出文件夹（支持 U 盘）");
        btnPickSafTree.setOnClickListener(v -> launchSafTreePicker());
        root.addView(btnPickSafTree);

        Button btnSaveAsFile = new Button(this);
        btnSaveAsFile.setAllCaps(false);
        btnSaveAsFile.setText("立即另存当前日志为文件...");
        btnSaveAsFile.setOnClickListener(v -> launchCreateLogDocument());
        root.addView(btnSaveAsFile);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Button btnAllFilesPerm = new Button(this);
            btnAllFilesPerm.setAllCaps(false);
            btnAllFilesPerm.setText("前往授权“所有文件访问权限”（写入自定义/根目录需要）");
            btnAllFilesPerm.setOnClickListener(v -> openAllFilesAccessSettings());
            root.addView(btnAllFilesPerm);
        }

        new AlertDialog.Builder(this)
                .setTitle("设置日志保存位置")
                .setView(sv)
                .setPositiveButton("应用此路径", (dialog, which) -> {
                    final String input = etPath.getText().toString().trim();
                    ioExecutor.execute(() -> {
                        try {
                            File applied = AppLogger.setCustomLogDirectory(MainActivity.this, input);
                            runOnUiThread(() -> {
                                refreshLogLocationText();
                                Toast.makeText(MainActivity.this,
                                        "实时日志已切换至: " + applied.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            });
                        } catch (Throwable t) {
                            AppLogger.e(TAG, "切换自定义日志目录失败: " + input, t);
                            runOnUiThread(() -> showLogDirectoryErrorDialog(input, t));
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showLogDirectoryErrorDialog(String path, Throwable error) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("自定义日志目录不可用")
                .setMessage("路径: " + path + "\n错误原因: " + error.getMessage()
                        + "\n\n建议：使用系统文件选择器导出，或开启所有文件访问权限。")
                .setNegativeButton("关闭", null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            builder.setPositiveButton("开启所有文件访问权限", (d, w) -> openAllFilesAccessSettings());
        }
        builder.show();
    }

    private void launchSafTreePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_LOG_TREE);
        } catch (Throwable t) {
            AppLogger.e(TAG, "拉起系统目录选择器失败", t);
            Toast.makeText(this, "无法打开目录选择器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchCreateLogDocument() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE,
                    "autodark_export_" + AppLogger.formatExportTimestamp() + ".log");
            startActivityForResult(intent, REQ_CREATE_LOG_FILE);
        } catch (Throwable t) {
            AppLogger.e(TAG, "拉起系统文件另存界面失败", t);
            Toast.makeText(this, "无法打开另存界面: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            AppLogger.w(TAG, "打开应用级所有文件权限设置失败，尝试通用设置: " + t.getMessage());
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable t2) {
                AppLogger.e(TAG, "打开所有文件访问权限设置失败", t2);
                Toast.makeText(this, "系统未提供所有文件访问设置页", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    @SuppressLint("WrongConstant")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQ_PICK_WALLPAPERS) {
            final List<Uri> uris = extractSelectedUris(data);
            if (uris.isEmpty()) {
                return;
            }
            final boolean targetNight = editingNight;
            ioExecutor.execute(() -> {
                final List<WallpaperRepository.VideoInspection> highSpecWarnings =
                        WallpaperRepository.inspectHighSpecVideos(MainActivity.this, uris);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (highSpecWarnings.isEmpty()) {
                        performImportUris(uris, targetNight);
                    } else {
                        showHighSpecVideoWarningDialog(uris, highSpecWarnings, targetNight);
                    }
                });
            });
            return;
        }

        final Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        if (requestCode == REQ_PICK_LOG_TREE) {
            try {
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Throwable t) {
                AppLogger.w(TAG, "持久化 SAF 目录权限失败: " + t.getMessage());
            }
            prefs.setLogSafTreeUri(uri.toString());
            AppLogger.i(TAG, "已绑定 SAF 日志导出目录 URI=" + uri);
            refreshLogLocationText();

            ioExecutor.execute(() -> {
                try {
                    Uri exported = AppLogger.exportLogsToSafTree(MainActivity.this, uri);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "SAF 目录绑定成功，已导出验证文件: " + exported, Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    AppLogger.e(TAG, "导出日志到 SAF 目录失败: uri=" + uri, t);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "SAF 目录导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
            return;
        }

        if (requestCode == REQ_CREATE_LOG_FILE) {
            ioExecutor.execute(() -> {
                try {
                    AppLogger.writeLogsToUri(MainActivity.this, uri);
                    AppLogger.i(TAG, "日志已另存为文件 URI=" + uri);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "日志另存成功: " + uri, Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    AppLogger.e(TAG, "另存日志文件失败: uri=" + uri, t);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "另存失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private void showHighSpecVideoWarningDialog(final List<Uri> allUris,
                                                final List<WallpaperRepository.VideoInspection> warnings,
                                                final boolean targetNight) {
        StringBuilder msg = new StringBuilder();
        msg.append("检测到以下视频满足 2 项及以上高规格条件（分辨率 ≥2K、帧率 >60fps、时长 ≥60秒、体积 >100MB），在车机长期桌面循环播放可能增加发热或资源占用：\n\n");
        Set<Uri> warnedUris = new HashSet<>();
        for (WallpaperRepository.VideoInspection item : warnings) {
            warnedUris.add(item.uri);
            msg.append("• ").append(item.displayName).append("\n");
            for (String reason : item.specResult.triggeredReasons) {
                msg.append("   - ").append(reason).append("\n");
            }
            msg.append("\n");
        }

        final List<Uri> normalUris = new ArrayList<>();
        for (Uri uri : allUris) {
            if (uri != null && !warnedUris.contains(uri)) {
                normalUris.add(uri);
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("高规格视频提示")
                .setMessage(msg.toString().trim())
                .setPositiveButton("仍然导入", (d, w) -> performImportUris(allUris, targetNight))
                .setNegativeButton("取消", null);
        if (!normalUris.isEmpty()) {
            builder.setNeutralButton("跳过高规格视频", (d, w) -> performImportUris(normalUris, targetNight));
        }
        builder.show();
    }

    private void performImportUris(final List<Uri> uris, final boolean targetNight) {
        Toast.makeText(this, "正在导入 " + uris.size() + " 个壁纸文件...", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            try {
                WallpaperRepository.ImportResult res =
                        WallpaperRepository.importWallpapersFromUris(MainActivity.this, uris, targetNight);
                runOnUiThread(() -> {
                    refreshWallpaperPage();
                    refreshHomeWallpaperOverviewAsync();
                    if (prefs.isEnabled() && isSystemNightNow() == targetNight) {
                        WallpaperController.resetDedupState();
                        AutoDarkMonitorService.notifyConfigUpdated(MainActivity.this);
                    } else {
                        WallpaperController.notifyLibraryChanged(MainActivity.this);
                    }
                    String msg = "已导入 " + res.importedItems.size() + " 项" + (targetNight ? "夜间" : "日间") + "壁纸";
                    if (!res.errorMessages.isEmpty()) {
                        msg += "（" + res.errorMessages.size() + " 项导入失败）";
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "导入壁纸失败", t);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "导入失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private List<Uri> extractSelectedUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri itemUri = clip.getItemAt(i).getUri();
                if (itemUri != null) {
                    uris.add(itemUri);
                }
            }
        }
        if (uris.isEmpty() && data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_STORAGE_FOR_BACKUP) {
            int action = pendingActionAfterPermission;
            pendingActionAfterPermission = PENDING_NONE;
            if (action == PENDING_NONE) {
                return;
            }
            if (WallpaperRepository.hasReadWallpaperPermission(this)) {
                startStaticBackupThenRun(action);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("未获得文件读取权限")
                        .setMessage("无法读取并备份您当前的系统壁纸，关闭开关后将无法自动复原原壁纸。是否继续？")
                        .setPositiveButton("不备份继续", (d, w) -> completePendingAction(action))
                        .setNegativeButton("取消", (d, w) -> renderHomeStatus())
                        .show();
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
