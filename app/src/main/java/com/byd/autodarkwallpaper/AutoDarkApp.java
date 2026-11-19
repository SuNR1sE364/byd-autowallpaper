package com.byd.autodarkwallpaper;

import android.app.Application;
import android.os.Build;

import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperRepository;

/**
 * 应用入口：初始化日志并迁移历史自定义壁纸（若有）。
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
            WallpaperRepository.migrateLegacyCustomWallpapersIfNeeded(this);
        } catch (Throwable t) {
            AppLogger.e(TAG, "迁移历史壁纸库失败", t);
        }
    }
}
