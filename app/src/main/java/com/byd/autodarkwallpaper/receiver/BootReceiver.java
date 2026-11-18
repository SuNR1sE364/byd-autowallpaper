package com.byd.autodarkwallpaper.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.service.AutoDarkMonitorService;
import com.byd.autodarkwallpaper.util.AppLogger;

/**
 * 车机开机/上电广播接收器：按用户配置自动拉起后台监控服务。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        AppLogger.init(context);
        String action = intent != null ? intent.getAction() : "null";
        AppLogger.i(TAG, "收到系统开机/上电广播: action=" + action);

        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.isBootAutoStart() || !prefs.isEnabled()) {
            AppLogger.i(TAG, "开机自启或总开关未开启，跳过拉起服务 (bootAutoStart="
                    + prefs.isBootAutoStart() + ", enabled=" + prefs.isEnabled() + ")");
            return;
        }

        try {
            AutoDarkMonitorService.startMonitor(context);
            AppLogger.i(TAG, "已请求启动 AutoDarkMonitorService");
        } catch (Throwable t) {
            AppLogger.e(TAG, "开机自启拉起 AutoDarkMonitorService 失败", t);
        }
    }
}
