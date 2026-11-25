package com.sunr1se.byd.autowallpaper;

import android.app.Application;
import android.os.Build;

import com.sunr1se.byd.autowallpaper.util.AppLogger;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperRepository;

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
