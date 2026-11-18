package com.byd.autodarkwallpaper.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.byd.autodarkwallpaper.R;
import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.monitor.VehicleStateMonitor;
import com.byd.autodarkwallpaper.ui.MainActivity;
import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperController;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 后台常驻监控前台服务：持续监听系统 uiMode、比亚迪车灯与环境光感，执行防抖并触发双模式壁纸切换。
 */
public class AutoDarkMonitorService extends Service {

    private static final String TAG = "MonitorSvc";
    private static final String CHANNEL_ID = "byd_autodark_monitor_channel";
    private static final int NOTIFICATION_ID = 1008;
    private static final long QUIET_POLL_INTERVAL_MS = 2000L;

    public static final String ACTION_START_MONITOR = "com.byd.autodarkwallpaper.ACTION_START_MONITOR";
    public static final String ACTION_POLL_NOW = "com.byd.autodarkwallpaper.ACTION_POLL_NOW";
    public static final String ACTION_CONFIG_UPDATED = "com.byd.autodarkwallpaper.ACTION_CONFIG_UPDATED";
    public static final String ACTION_MANUAL_APPLY = "com.byd.autodarkwallpaper.ACTION_MANUAL_APPLY";
    public static final String EXTRA_MANUAL_IS_DARK = "manual_is_dark";

    public interface MonitorObserver {
        void onMonitorStateChanged(boolean running,
                                   VehicleStateMonitor.TelemetrySnapshot snapshot,
                                   VehicleStateMonitor.EvaluationResult evaluation);
    }

    private static final List<MonitorObserver> OBSERVERS = new CopyOnWriteArrayList<>();
    private static volatile AutoDarkMonitorService activeInstance = null;
    private static volatile VehicleStateMonitor.TelemetrySnapshot latestSnapshot =
            new VehicleStateMonitor.TelemetrySnapshot();
    private static volatile VehicleStateMonitor.EvaluationResult latestEvaluation =
            new VehicleStateMonitor.EvaluationResult(false, false, "监控服务未启动", "请先启动监控服务");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VehicleStateMonitor stateMonitor;
    private Runnable pendingApplyRunnable;
    private Boolean pendingTargetDark = null;
    private boolean hasAppliedOnce = false;

    private final Runnable quietPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeInstance == AutoDarkMonitorService.this && stateMonitor != null) {
                stateMonitor.pollConnectedHardwareQuietly();
                mainHandler.postDelayed(this, QUIET_POLL_INTERVAL_MS);
            }
        }
    };

    public static boolean isRunning() {
        return activeInstance != null;
    }

    public static VehicleStateMonitor.TelemetrySnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public static VehicleStateMonitor.EvaluationResult getLatestEvaluation() {
        return latestEvaluation;
    }

    public static void addObserver(MonitorObserver observer) {
        if (observer != null && !OBSERVERS.contains(observer)) {
            OBSERVERS.add(observer);
            observer.onMonitorStateChanged(isRunning(), latestSnapshot, latestEvaluation);
        }
    }

    public static void removeObserver(MonitorObserver observer) {
        OBSERVERS.remove(observer);
    }

    public static void startMonitor(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), AutoDarkMonitorService.class);
        intent.setAction(ACTION_START_MONITOR);
        startForegroundSafely(context.getApplicationContext(), intent);
    }

    public static void stopMonitor(Context context) {
        Intent intent = new Intent(context.getApplicationContext(), AutoDarkMonitorService.class);
        context.getApplicationContext().stopService(intent);
    }

    public static void requestPollNow(Context context) {
        if (!isRunning()) {
            startMonitor(context);
            return;
        }
        Intent intent = new Intent(context.getApplicationContext(), AutoDarkMonitorService.class);
        intent.setAction(ACTION_POLL_NOW);
        startForegroundSafely(context.getApplicationContext(), intent);
    }

    public static void notifyConfigUpdated(Context context) {
        if (!isRunning()) {
            return;
        }
        Intent intent = new Intent(context.getApplicationContext(), AutoDarkMonitorService.class);
        intent.setAction(ACTION_CONFIG_UPDATED);
        startForegroundSafely(context.getApplicationContext(), intent);
    }

    public static void requestManualApply(Context context, boolean isDark) {
        WallpaperController.applyWallpaper(context, isDark, "用户手动点击测试按钮", true);
    }

    private static void startForegroundSafely(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.init(this);
        activeInstance = this;
        createNotificationChannel();
        promoteToForeground("正在初始化比亚迪车机硬件监听...");
        AppLogger.i(TAG, "AutoDarkMonitorService.onCreate()");

        stateMonitor = new VehicleStateMonitor(this);
        stateMonitor.setOnStateChangedListener(snapshot -> {
            latestSnapshot = snapshot;
            boolean firstApply = !hasAppliedOnce;
            evaluateAndSchedule(firstApply, false);
        });
        stateMonitor.start();
        mainHandler.postDelayed(quietPollRunnable, QUIET_POLL_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground(buildNotificationSummary());
        String action = intent != null ? intent.getAction() : null;
        AppLogger.i(TAG, "onStartCommand: action=" + action);

        if (ACTION_POLL_NOW.equals(action)) {
            if (stateMonitor != null) {
                stateMonitor.pollAllStatesNow();
            }
            evaluateAndSchedule(true, true);
        } else if (ACTION_CONFIG_UPDATED.equals(action)) {
            WallpaperController.resetDedupState();
            evaluateAndSchedule(true, true);
        } else if (ACTION_MANUAL_APPLY.equals(action) && intent != null) {
            boolean isDark = intent.getBooleanExtra(EXTRA_MANUAL_IS_DARK, false);
            WallpaperController.applyWallpaper(this, isDark, "服务指令手动切换", true);
        } else {
            evaluateAndSchedule(!hasAppliedOnce, false);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        activeInstance = null;
        mainHandler.removeCallbacks(quietPollRunnable);
        cancelPendingApply();
        if (stateMonitor != null) {
            stateMonitor.stop();
            stateMonitor = null;
        }
        latestEvaluation = new VehicleStateMonitor.EvaluationResult(
                false, false, "监控服务已停止", "用户手动停止或系统回收");
        AppLogger.i(TAG, "AutoDarkMonitorService.onDestroy()");
        notifyObservers();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void evaluateAndSchedule(boolean immediate, boolean forceApply) {
        if (stateMonitor == null) {
            return;
        }
        AppPrefs prefs = new AppPrefs(this);
        VehicleStateMonitor.EvaluationResult result = stateMonitor.evaluateTargetDarkState(prefs);
        latestSnapshot = stateMonitor.getSnapshot();
        latestEvaluation = result;
        notifyObservers();
        updateNotification(buildNotificationSummary());

        if (!prefs.isEnabled()) {
            cancelPendingApply();
            AppLogger.i(TAG, "监控开关已关闭，仅更新遥测，不触发切换");
            return;
        }

        if (!result.valid) {
            cancelPendingApply();
            AppLogger.w(TAG, "触发条件评估失败（不执行静默降级）: " + result.reason + " | " + result.errorDetail);
            return;
        }

        if (result.errorDetail != null) {
            AppLogger.w(TAG, "触发评估附加警告: " + result.errorDetail);
        }

        long debounceMs = immediate ? 0L : prefs.getDebounceMs();
        final boolean targetDark = result.targetDark;
        final String reason = result.reason;

        if (debounceMs <= 0L) {
            cancelPendingApply();
            hasAppliedOnce = true;
            WallpaperController.applyWallpaper(this, targetDark, reason, forceApply);
            return;
        }

        if (pendingApplyRunnable != null && pendingTargetDark != null && pendingTargetDark == targetDark) {
            return;
        }

        cancelPendingApply();
        pendingTargetDark = targetDark;
        AppLogger.i(TAG, "已安排防抖切换任务: targetDark=" + targetDark + ", 延迟=" + debounceMs + "ms, 原因=" + reason);
        pendingApplyRunnable = () -> {
            pendingApplyRunnable = null;
            pendingTargetDark = null;
            hasAppliedOnce = true;
            WallpaperController.applyWallpaper(
                    AutoDarkMonitorService.this, targetDark, reason + " (防抖完成)", forceApply);
            updateNotification(buildNotificationSummary());
        };
        mainHandler.postDelayed(pendingApplyRunnable, debounceMs);
    }

    private void cancelPendingApply() {
        if (pendingApplyRunnable != null) {
            mainHandler.removeCallbacks(pendingApplyRunnable);
            pendingApplyRunnable = null;
        }
        pendingTargetDark = null;
    }

    private String buildNotificationSummary() {
        AppPrefs prefs = new AppPrefs(this);
        String sourceText = AppPrefs.formatTriggerSource(prefs.getActiveTriggerSource());
        VehicleStateMonitor.EvaluationResult eval = latestEvaluation;
        if (eval == null || !eval.valid) {
            String err = eval != null ? eval.reason : "评估中";
            return "状态异常(" + err + ") | 触发源: " + sourceText;
        }
        return "当前: " + (eval.targetDark ? "夜间暗色" : "日间亮色") + " | 触发源: " + sourceText;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "比亚迪自动深浅壁纸监控",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持后台监听车机日夜模式、车灯和环境光感变化");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent, flags);

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void promoteToForeground(String contentText) {
        Notification notification = buildNotification(contentText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String contentText) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification(contentText));
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "更新前台服务通知失败", t);
        }
    }

    private static void notifyObservers() {
        final boolean running = isRunning();
        final VehicleStateMonitor.TelemetrySnapshot snap = latestSnapshot;
        final VehicleStateMonitor.EvaluationResult eval = latestEvaluation;
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            for (MonitorObserver observer : OBSERVERS) {
                observer.onMonitorStateChanged(running, snap, eval);
            }
        });
    }
}
