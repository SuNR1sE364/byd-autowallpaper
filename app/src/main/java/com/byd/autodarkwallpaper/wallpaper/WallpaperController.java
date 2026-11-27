package com.sunr1se.byd.autowallpaper.wallpaper;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.sunr1se.byd.autowallpaper.config.AppPrefs;
import com.sunr1se.byd.autowallpaper.service.AutoDarkWallpaperService;
import com.sunr1se.byd.autowallpaper.util.AppLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸切换控制器：支持“直接修改壁纸”与“动态壁纸模式”。
 */
public final class WallpaperController {

    private static final String TAG = "WallpaperCtrl";

    public static final String ACTION_WALLPAPER_STATE_CHANGED =
            "com.sunr1se.byd.autowallpaper.ACTION_WALLPAPER_STATE_CHANGED";
    public static final String ACTION_WALLPAPER_IMAGES_RELOADED =
            "com.sunr1se.byd.autowallpaper.ACTION_WALLPAPER_IMAGES_RELOADED";
    public static final String EXTRA_IS_DARK = "is_dark";
    public static final String EXTRA_ITEM_ID = "item_id";

    public interface ApplyStatusListener {
        void onApplyStatusChanged(String statusText, boolean isError);
    }

    private static final ExecutorService STATIC_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final List<ApplyStatusListener> STATUS_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile String lastStatusText = "尚未切换壁纸";
    private static volatile boolean lastStatusIsError = false;
    private static volatile Boolean lastAppliedTargetDark = null;
    private static volatile int lastAppliedWallpaperMode = -1;
    private static volatile String lastAppliedItemId = null;

    private WallpaperController() {
    }

    public static void addApplyStatusListener(ApplyStatusListener listener) {
        if (listener != null && !STATUS_LISTENERS.contains(listener)) {
            STATUS_LISTENERS.add(listener);
            listener.onApplyStatusChanged(lastStatusText, lastStatusIsError);
        }
    }

    public static void removeApplyStatusListener(ApplyStatusListener listener) {
        STATUS_LISTENERS.remove(listener);
    }

    public static void notifyLibraryChanged(Context context) {
        Context appCtx = context.getApplicationContext();
        AutoDarkWallpaperService.notifyImagesReloaded();
        Intent intent = new Intent(ACTION_WALLPAPER_IMAGES_RELOADED);
        intent.setPackage(appCtx.getPackageName());
        appCtx.sendBroadcast(intent);
    }

    public static boolean isOurLiveWallpaperActive(Context context) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context.getApplicationContext());
            WallpaperInfo info = wm.getWallpaperInfo();
            if (info == null) {
                return false;
            }
            ComponentName ours = new ComponentName(context, AutoDarkWallpaperService.class);
            return ours.equals(info.getComponent());
        } catch (Throwable t) {
            AppLogger.e(TAG, "查询当前系统 WallpaperInfo 失败", t);
            return false;
        }
    }

    public static String describeActiveSystemWallpaper(Context context) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context.getApplicationContext());
            WallpaperInfo info = wm.getWallpaperInfo();
            if (info == null) {
                return "尚未设为系统动态壁纸";
            }
            ComponentName ours = new ComponentName(context, AutoDarkWallpaperService.class);
            if (ours.equals(info.getComponent())) {
                return "已设为当前系统动态壁纸";
            }
            return "当前系统壁纸为其他动态壁纸，请点击前往激活";
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取系统当前壁纸信息异常", t);
            return "读取动态壁纸状态失败 (" + t.getClass().getSimpleName() + ")";
        }
    }

    public static synchronized void resetDedupState() {
        lastAppliedTargetDark = null;
        lastAppliedWallpaperMode = -1;
        lastAppliedItemId = null;
    }

    public static void applyWallpaper(Context context, boolean isDark, String reason,
                                      boolean force, boolean allowRandomRotate) {
        Context appCtx = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(appCtx);
        int mode = prefs.getWallpaperMode();
        String label = isDark ? "夜间" : "日间";

        boolean allowVideo = (mode == AppPrefs.MODE_LIVE_SERVICE);
        WallpaperRepository.WallpaperItem item =
                WallpaperRepository.resolveItemForApply(appCtx, isDark, allowRandomRotate, allowVideo);
        if (item == null) {
            if (!allowVideo && !WallpaperRepository.listWallpapers(appCtx, isDark, true).isEmpty()) {
                String warnMsg = "当前" + label + "壁纸库仅有视频，直接修改模式仅支持图片（请添加图片或切换为动态壁纸模式）";
                AppLogger.w(TAG, warnMsg + " (reason=" + reason + ")");
                notifyApplyStatus(warnMsg, true);
            } else {
                AppLogger.w(TAG, "尚未添加" + label + "壁纸，跳过切换 (reason=" + reason + ")");
                notifyApplyStatus("尚未添加" + label + "壁纸，请先前往“壁纸”页上传", true);
            }
            return;
        }

        synchronized (WallpaperController.class) {
            if (!force && !allowRandomRotate
                    && lastAppliedTargetDark != null
                    && lastAppliedTargetDark == isDark
                    && lastAppliedWallpaperMode == mode
                    && item.id.equals(lastAppliedItemId)) {
                AppLogger.i(TAG, "壁纸状态未变化，跳过重复刷写 (isDark=" + isDark
                        + ", itemId=" + item.id + ", reason=" + reason + ")");
                return;
            }
            lastAppliedTargetDark = isDark;
            lastAppliedWallpaperMode = mode;
            lastAppliedItemId = item.id;
        }

        prefs.setCurrentTargetState(isDark);
        AppLogger.i(TAG, ">>> 应用" + label + "壁纸 | 模式=" + AppPrefs.formatWallpaperMode(mode)
                + " | 壁纸=" + item.displayName + " (" + item.id + ") | 原因=" + reason);

        if (mode == AppPrefs.MODE_LIVE_SERVICE) {
            boolean active = isOurLiveWallpaperActive(appCtx);
            if (!active) {
                AppLogger.w(TAG, "[动态壁纸模式] 系统尚未激活本动态壁纸服务");
            }
            AutoDarkWallpaperService.notifyTargetStateChanged(isDark, item.id, reason);
            Intent intent = new Intent(ACTION_WALLPAPER_STATE_CHANGED);
            intent.setPackage(appCtx.getPackageName());
            intent.putExtra(EXTRA_IS_DARK, isDark);
            intent.putExtra(EXTRA_ITEM_ID, item.id);
            appCtx.sendBroadcast(intent);
            if (active) {
                notifyApplyStatus("动态壁纸已切换至" + label + "：" + item.displayName, false);
            } else {
                notifyApplyStatus("已在系统待命（请先在系统壁纸中激活本动态壁纸）", true);
            }
            return;
        }

        if (mode != AppPrefs.MODE_STATIC_MANAGER) {
            AppLogger.e(TAG, "遇到未知壁纸模式: " + mode);
            notifyApplyStatus("壁纸模式无效: " + mode, true);
            return;
        }

        if (item.isVideo()) {
            AppLogger.e(TAG, "[直接修改壁纸] 尝试将视频应用为静态壁纸，已拦截: " + item.id);
            notifyApplyStatus("直接修改模式不支持视频壁纸，请切换为动态壁纸模式", true);
            return;
        }

        final File targetFile = WallpaperRepository.getWallpaperFile(appCtx, isDark, item);
        final CropRect cropRect = item.cropRect;
        STATIC_EXECUTOR.execute(() -> {
            long start = System.currentTimeMillis();
            Bitmap bitmap = null;
            try {
                bitmap = decodeStaticWallpaperBitmap(targetFile, cropRect);
                WallpaperManager wm = WallpaperManager.getInstance(appCtx);
                int resultId = wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
                long elapsed = System.currentTimeMillis() - start;
                AppLogger.i(TAG, "[直接修改壁纸] WallpaperManager.setBitmap 完成: resultId="
                        + resultId + ", 耗时=" + elapsed + "ms, 分辨率="
                        + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + ", 裁剪=" + (cropRect != null ? cropRect.toShortString() : "默认居中"));
                if (resultId <= 0) {
                    String warnMsg = "WallpaperManager.setBitmap 返回异常 ID=" + resultId + "（可能被车机桌面拦截）";
                    AppLogger.w(TAG, warnMsg);
                    notifyApplyStatus(warnMsg, true);
                } else {
                    notifyApplyStatus("已切换" + label + "壁纸：" + item.displayName, false);
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "[直接修改壁纸] WallpaperManager.setBitmap 执行失败", t);
                notifyApplyStatus("壁纸切换失败: " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        });
    }

    /**
     * 读取当前设备物理屏幕宽高比（横屏车机通常为 16:9 或 16:10），失败时回退 16:9。
     */
    public static float resolveScreenAspectRatio(Context context) {
        if (context == null) {
            return 16f / 9f;
        }
        try {
            WindowManager wm = (WindowManager) context.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowMetrics metrics = wm.getMaximumWindowMetrics();
                    if (metrics != null) {
                        Rect bounds = metrics.getBounds();
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            return (float) bounds.width() / (float) bounds.height();
                        }
                    }
                } else {
                    DisplayMetrics dm = readRealMetricsLegacy(wm);
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return (float) dm.widthPixels / (float) dm.heightPixels;
                    }
                }
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取 WindowManager 屏幕比例失败，回退 DisplayMetrics: " + t.getMessage());
        }
        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return (float) dm.widthPixels / (float) dm.heightPixels;
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "读取资源 DisplayMetrics 失败，使用默认 16:9: " + t.getMessage());
        }
        return 16f / 9f;
    }

    @SuppressWarnings("deprecation")
    private static DisplayMetrics readRealMetricsLegacy(WindowManager wm) {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    static Bitmap decodeStaticWallpaperBitmap(File file, CropRect cropRect) throws IOException {
        if (file == null || !file.exists() || file.length() == 0L) {
            throw new IOException("壁纸文件不存在或为空: "
                    + (file != null ? file.getAbsolutePath() : "null"));
        }
        if (cropRect == null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) {
                throw new IOException("BitmapFactory.decodeFile 返回 null: " + file.getAbsolutePath());
            }
            return bitmap;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法解析壁纸尺寸: " + file.getAbsolutePath());
        }

        Rect region = cropRect.toPixelRect(bounds.outWidth, bounds.outHeight);
        BitmapRegionDecoder decoder = null;
        try {
            decoder = openRegionDecoder(file);
            if (decoder == null) {
                throw new IOException("BitmapRegionDecoder.newInstance 返回 null: " + file.getAbsolutePath());
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap cropped = decoder.decodeRegion(region, decodeOpts);
            if (cropped == null) {
                throw new IOException("BitmapRegionDecoder.decodeRegion 返回 null: " + file.getAbsolutePath());
            }
            return cropped;
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    public static Bitmap decodeSampledCroppedBitmap(File file, CropRect cropRect, int reqWidth, int reqHeight) {
        if (cropRect == null) {
            return decodeSampledBitmap(file, reqWidth, reqHeight);
        }
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        Rect region = cropRect.toPixelRect(bounds.outWidth, bounds.outHeight);
        BitmapRegionDecoder decoder = null;
        try {
            decoder = openRegionDecoder(file);
            if (decoder == null) {
                throw new IOException("BitmapRegionDecoder.newInstance 返回 null: " + file.getAbsolutePath());
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateInSampleSize(region.width(), region.height(), reqWidth, reqHeight);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return decoder.decodeRegion(region, opts);
        } catch (Throwable t) {
            AppLogger.e(TAG, "区域采样解码缩略图失败: " + file.getAbsolutePath(), t);
            return null;
        } finally {
            if (decoder != null) {
                decoder.recycle();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static BitmapRegionDecoder openRegionDecoder(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return BitmapRegionDecoder.newInstance(fis, false);
        }
    }

    public static Bitmap decodeSampledBitmap(File file, int reqWidth, int reqHeight) {
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return null;
        }
        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, reqWidth, reqHeight);
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static void notifyApplyStatus(final String text, final boolean isError) {
        lastStatusText = text;
        lastStatusIsError = isError;
        MAIN_HANDLER.post(() -> {
            for (ApplyStatusListener listener : STATUS_LISTENERS) {
                listener.onApplyStatusChanged(text, isError);
            }
        });
    }
}
