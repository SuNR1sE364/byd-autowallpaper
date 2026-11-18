package com.byd.autodarkwallpaper;

import android.app.Application;
import android.os.Build;

import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperController;

/**
 * 应用入口：初始化诊断日志并确保内置日间/夜间测试壁纸就绪。
 */
public class AutoDarkApp extends Application {

    private static final String TAG = "AutoDarkApp";

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.init(this);
        AppLogger.i(TAG, "应用启动 | 设备=" + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + " | Build=" + Build.DISPLAY);
        try {
            WallpaperController.ensureDefaultWallpapers(this);
        } catch (Throwable t) {
            AppLogger.e(TAG, "启动时初始化内置测试壁纸失败", t);
        }
    }
}
