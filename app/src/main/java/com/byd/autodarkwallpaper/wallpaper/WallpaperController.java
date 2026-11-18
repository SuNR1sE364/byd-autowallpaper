package com.byd.autodarkwallpaper.wallpaper;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.service.AutoDarkWallpaperService;
import com.byd.autodarkwallpaper.util.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸资源管理与双模式切换控制器（支持 WallpaperService 动态引擎 + WallpaperManager 静态替换）。
 */
public final class WallpaperController {

    private static final String TAG = "WallpaperCtrl";

    public static final String ACTION_WALLPAPER_STATE_CHANGED =
            "com.byd.autodarkwallpaper.ACTION_WALLPAPER_STATE_CHANGED";
    public static final String ACTION_WALLPAPER_IMAGES_RELOADED =
            "com.byd.autodarkwallpaper.ACTION_WALLPAPER_IMAGES_RELOADED";
    public static final String EXTRA_IS_DARK = "is_dark";
    public static final String EXTRA_REASON = "reason";

    private static final int DEFAULT_WIDTH = 2560;
    private static final int DEFAULT_HEIGHT = 1600;
    private static final int MAX_IMPORT_EDGE = 3840;

    public interface ApplyStatusListener {
        void onApplyStatusChanged(String statusText, boolean isError);
    }

    private static final ExecutorService STATIC_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final List<ApplyStatusListener> STATUS_LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile String lastStatusText = "尚未执行壁纸切换";
    private static volatile boolean lastStatusIsError = false;
    private static volatile Boolean lastAppliedTargetDark = null;
    private static volatile int lastAppliedWallpaperMode = -1;

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

    public static File getWallpapersDir(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "wallpapers");
        if (!dir.exists() && !dir.mkdirs()) {
            AppLogger.e(TAG, "创建壁纸目录失败: " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static File getLightWallpaperFile(Context context) {
        return new File(getWallpapersDir(context), "light.jpg");
    }

    public static File getDarkWallpaperFile(Context context) {
        return new File(getWallpapersDir(context), "dark.jpg");
    }

    public static synchronized void ensureDefaultWallpapers(Context context) throws IOException {
        File lightFile = getLightWallpaperFile(context);
        File darkFile = getDarkWallpaperFile(context);
        if (!lightFile.exists() || lightFile.length() == 0) {
            generateTestWallpaper(lightFile, false);
            new AppPrefs(context).setLightCustom(false);
            AppLogger.i(TAG, "已生成内置日间测试壁纸: " + lightFile.getAbsolutePath());
        }
        if (!darkFile.exists() || darkFile.length() == 0) {
            generateTestWallpaper(darkFile, true);
            new AppPrefs(context).setDarkCustom(false);
            AppLogger.i(TAG, "已生成内置夜间测试壁纸: " + darkFile.getAbsolutePath());
        }
    }

    public static synchronized void resetToDefaultWallpapers(Context context) throws IOException {
        File lightFile = getLightWallpaperFile(context);
        File darkFile = getDarkWallpaperFile(context);
        generateTestWallpaper(lightFile, false);
        generateTestWallpaper(darkFile, true);
        AppPrefs prefs = new AppPrefs(context);
        prefs.setLightCustom(false);
        prefs.setDarkCustom(false);
        AppLogger.i(TAG, "已重置为内置日间/夜间测试壁纸");
        notifyImagesReloaded(context);
    }

    public static void importWallpaperFromUri(Context context, Uri uri, boolean isDark) throws IOException {
        Context appCtx = context.getApplicationContext();
        File targetFile = isDark ? getDarkWallpaperFile(appCtx) : getLightWallpaperFile(appCtx);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = appCtx.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("ContentResolver.openInputStream 返回 null: " + uri);
            }
            BitmapFactory.decodeStream(is, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法解析图片尺寸，可能不是有效图片格式: " + uri);
        }

        int sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMPORT_EDGE, MAX_IMPORT_EDGE);
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap bitmap;
        try (InputStream is = appCtx.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("二次读取图片流失败: " + uri);
            }
            bitmap = BitmapFactory.decodeStream(is, null, decodeOpts);
        }

        if (bitmap == null) {
            throw new IOException("BitmapFactory.decodeStream 返回 null: " + uri);
        }

        try {
            saveBitmapAsJpeg(bitmap, targetFile, 94);
            AppPrefs prefs = new AppPrefs(appCtx);
            if (isDark) {
                prefs.setDarkCustom(true);
            } else {
                prefs.setLightCustom(true);
            }
            AppLogger.i(TAG, "导入" + (isDark ? "夜间" : "日间") + "壁纸成功: "
                    + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " (原始 " + bounds.outWidth + "x" + bounds.outHeight
                    + ", inSampleSize=" + sampleSize + ") -> " + targetFile.getAbsolutePath());
            notifyImagesReloaded(appCtx);
        } finally {
            bitmap.recycle();
        }
    }

    public static void notifyImagesReloaded(Context context) {
        AutoDarkWallpaperService.notifyImagesReloaded();
        Intent intent = new Intent(ACTION_WALLPAPER_IMAGES_RELOADED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
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
                return "当前为静态壁纸（或第三方主题未暴露 WallpaperInfo），本动态壁纸未激活";
            }
            ComponentName ours = new ComponentName(context, AutoDarkWallpaperService.class);
            if (ours.equals(info.getComponent())) {
                return "已激活：" + info.getServiceName();
            }
            return "未激活本应用，当前动态壁纸为：" + info.getPackageName() + "/" + info.getServiceName();
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取系统当前壁纸信息异常", t);
            return "查询失败 (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    public static synchronized void resetDedupState() {
        lastAppliedTargetDark = null;
        lastAppliedWallpaperMode = -1;
    }

    public static void applyWallpaper(Context context, boolean isDark, String reason, boolean force) {
        Context appCtx = context.getApplicationContext();
        AppPrefs prefs = new AppPrefs(appCtx);
        int mode = prefs.getWallpaperMode();

        synchronized (WallpaperController.class) {
            if (!force && lastAppliedTargetDark != null
                    && lastAppliedTargetDark == isDark
                    && lastAppliedWallpaperMode == mode) {
                AppLogger.i(TAG, "目标状态与模式未变化，跳过重复刷写 (isDark=" + isDark + ", reason=" + reason + ")");
                return;
            }
            lastAppliedTargetDark = isDark;
            lastAppliedWallpaperMode = mode;
        }

        prefs.setCurrentTargetState(isDark);
        String label = isDark ? "夜间暗色" : "日间亮色";
        AppLogger.i(TAG, ">>> 开始应用壁纸 [" + label + "] | 模式="
                + AppPrefs.formatWallpaperMode(mode) + " | 原因=" + reason);

        try {
            ensureDefaultWallpapers(appCtx);
        } catch (Throwable t) {
            AppLogger.e(TAG, "确保默认测试壁纸存在时失败", t);
            notifyApplyStatus("初始化壁纸文件失败: " + t.getMessage(), true);
            return;
        }

        boolean runLive = (mode == AppPrefs.MODE_LIVE_SERVICE || mode == AppPrefs.MODE_DUAL);
        boolean runStatic = (mode == AppPrefs.MODE_STATIC_MANAGER || mode == AppPrefs.MODE_DUAL);

        if (runLive) {
            boolean active = isOurLiveWallpaperActive(appCtx);
            if (!active) {
                AppLogger.w(TAG, "[动态壁纸模式] 注意：" + describeActiveSystemWallpaper(appCtx));
            }
            AutoDarkWallpaperService.notifyTargetStateChanged(isDark, reason);
            Intent intent = new Intent(ACTION_WALLPAPER_STATE_CHANGED);
            intent.setPackage(appCtx.getPackageName());
            intent.putExtra(EXTRA_IS_DARK, isDark);
            intent.putExtra(EXTRA_REASON, reason);
            appCtx.sendBroadcast(intent);
            if (!runStatic) {
                if (active) {
                    notifyApplyStatus("已通知动态壁纸引擎切换为[" + label + "] (" + reason + ")", false);
                } else {
                    notifyApplyStatus("已发送动态壁纸切换，但系统尚未激活本动态壁纸服务", true);
                }
            }
        }

        if (runStatic) {
            final File targetFile = isDark ? getDarkWallpaperFile(appCtx) : getLightWallpaperFile(appCtx);
            final boolean syncLock = prefs.isStaticSyncLockScreen();
            STATIC_EXECUTOR.execute(() -> {
                long start = System.currentTimeMillis();
                Bitmap bitmap = null;
                try {
                    if (!targetFile.exists() || targetFile.length() == 0) {
                        throw new IOException("目标壁纸文件不存在或为空: " + targetFile.getAbsolutePath());
                    }
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    bitmap = BitmapFactory.decodeFile(targetFile.getAbsolutePath(), opts);
                    if (bitmap == null) {
                        throw new IOException("BitmapFactory.decodeFile 返回 null: " + targetFile.getAbsolutePath());
                    }
                    WallpaperManager wm = WallpaperManager.getInstance(appCtx);
                    if (!wm.isWallpaperSupported()) {
                        AppLogger.w(TAG, "[静态壁纸模式] WallpaperManager.isWallpaperSupported() 返回 false，仍尝试调用 setBitmap");
                    }
                    if (!wm.isSetWallpaperAllowed()) {
                        AppLogger.w(TAG, "[静态壁纸模式] WallpaperManager.isSetWallpaperAllowed() 返回 false，仍尝试调用 setBitmap");
                    }
                    int flags = WallpaperManager.FLAG_SYSTEM;
                    if (syncLock) {
                        flags |= WallpaperManager.FLAG_LOCK;
                    }
                    int resultId = wm.setBitmap(bitmap, null, true, flags);
                    long elapsed = System.currentTimeMillis() - start;
                    AppLogger.i(TAG, "[静态壁纸模式] WallpaperManager.setBitmap 完成: resultId="
                            + resultId + ", flags=" + flags + ", 耗时=" + elapsed + "ms, 分辨率="
                            + bitmap.getWidth() + "x" + bitmap.getHeight());
                    if (resultId <= 0) {
                        String warnMsg = "WallpaperManager.setBitmap 返回非正数 ID=" + resultId + " (可能被车机桌面拦截)";
                        AppLogger.w(TAG, warnMsg);
                        notifyApplyStatus(warnMsg, true);
                    } else {
                        String suffix = runLive ? "（双模式已触发）" : "";
                        notifyApplyStatus("已切换[" + label + "]壁纸 id=" + resultId + " (" + elapsed + "ms)" + suffix, false);
                    }
                } catch (Throwable t) {
                    AppLogger.e(TAG, "[静态壁纸模式] WallpaperManager.setBitmap 执行失败", t);
                    notifyApplyStatus("静态壁纸设置失败: " + t.getClass().getSimpleName() + ": " + t.getMessage(), true);
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            });
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

    public static String getImageDimensionsText(File file) {
        if (file == null || !file.exists()) {
            return "文件不存在";
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return "无法解析尺寸";
        }
        long kb = Math.max(1L, file.length() / 1024L);
        return opts.outWidth + "x" + opts.outHeight + " (" + kb + " KB)";
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

    private static void saveBitmapAsJpeg(Bitmap bitmap, File targetFile, int quality) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建壁纸目录: " + parent.getAbsolutePath());
        }
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                throw new IOException("Bitmap.compress 返回 false: " + targetFile.getAbsolutePath());
            }
            fos.flush();
        }
        if (targetFile.exists() && !targetFile.delete()) {
            throw new IOException("无法覆盖旧壁纸文件: " + targetFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(targetFile)) {
            throw new IOException("无法重命名临时壁纸文件到: " + targetFile.getAbsolutePath());
        }
    }

    private static void generateTestWallpaper(File targetFile, boolean isDark) throws IOException {
        Bitmap bmp = Bitmap.createBitmap(DEFAULT_WIDTH, DEFAULT_HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bmp);
            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int[] colors = isDark
                    ? new int[]{Color.parseColor("#020617"), Color.parseColor("#0B162C"), Color.parseColor("#0F172A")}
                    : new int[]{Color.parseColor("#E0F2FE"), Color.parseColor("#7DD3FC"), Color.parseColor("#0284C7")};
            bgPaint.setShader(new LinearGradient(
                    0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT,
                    colors, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, bgPaint);

            // 海洋网格纹理
            Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridPaint.setColor(isDark ? Color.argb(28, 56, 189, 248) : Color.argb(36, 255, 255, 255));
            gridPaint.setStrokeWidth(2f);
            for (int x = 0; x <= DEFAULT_WIDTH; x += 160) {
                canvas.drawLine(x, 0, x, DEFAULT_HEIGHT, gridPaint);
            }
            for (int y = 0; y <= DEFAULT_HEIGHT; y += 160) {
                canvas.drawLine(0, y, DEFAULT_WIDTH, y, gridPaint);
            }

            // 中心测试标识卡片
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(isDark ? Color.argb(175, 15, 23, 42) : Color.argb(175, 255, 255, 255));
            RectF cardRect = new RectF(
                    DEFAULT_WIDTH / 2f - 540f, DEFAULT_HEIGHT / 2f - 210f,
                    DEFAULT_WIDTH / 2f + 540f, DEFAULT_HEIGHT / 2f + 210f);
            canvas.drawRoundRect(cardRect, 40f, 40f, cardPaint);

            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(4f);
            borderPaint.setColor(isDark ? Color.parseColor("#38BDF8") : Color.parseColor("#0369A1"));
            canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint);

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setTextSize(68f);
            titlePaint.setFakeBoldText(true);
            titlePaint.setColor(isDark ? Color.parseColor("#E0F2FE") : Color.parseColor("#0C4A6E"));
            canvas.drawText(
                    isDark ? "BYD DiLink · NIGHT / DARK MODE" : "BYD DiLink · DAY / LIGHT MODE",
                    DEFAULT_WIDTH / 2f, DEFAULT_HEIGHT / 2f - 25f, titlePaint);

            Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTextSize(42f);
            subPaint.setColor(isDark ? Color.parseColor("#94A3B8") : Color.parseColor("#0369A1"));
            canvas.drawText(
                    isDark ? "夜间暗色测试壁纸 (2560x1600)" : "日间亮色测试壁纸 (2560x1600)",
                    DEFAULT_WIDTH / 2f, DEFAULT_HEIGHT / 2f + 75f, subPaint);

            saveBitmapAsJpeg(bmp, targetFile, 92);
        } finally {
            bmp.recycle();
        }
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
