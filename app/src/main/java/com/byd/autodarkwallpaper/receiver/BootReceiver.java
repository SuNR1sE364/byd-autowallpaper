package com.sunr1se.byd.autowallpaper.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.service.AutoDarkMonitorService;
import com.sunr1se.byd.autowallpaper.util.AppLogger;

/**
 * 车机开机/上电广播接收器：按主页总开关状态自动拉起后台监控服务。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        AppLogger.init(context);
        String action = intent != null ? intent.getAction() : "null";
        AppLogger.i(TAG, "收到系统开机/上电广播: action=" + action);

        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.isEnabled()) {
            AppLogger.i(TAG, "主页总开关未开启，跳过开机自启");
            return;
        }

        try {
            AutoDarkMonitorService.startMonitorForBoot(context);
            AppLogger.i(TAG, "已请求开机自启 AutoDarkMonitorService");
        } catch (Throwable t) {
            AppLogger.e(TAG, "开机自启拉起 AutoDarkMonitorService 失败", t);
        }
    }
}
