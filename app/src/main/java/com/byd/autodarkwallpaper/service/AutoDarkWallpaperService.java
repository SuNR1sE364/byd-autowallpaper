package com.byd.autodarkwallpaper.service;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import android.view.animation.DecelerateInterpolator;

import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperController;
import com.byd.autodarkwallpaper.wallpaper.WallpaperRepository;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 动态壁纸引擎服务：支持日间与夜间多壁纸管理与 400ms 平滑淡入淡出切换。
 */
public class AutoDarkWallpaperService extends WallpaperService {

    private static final String TAG = "LiveWallpaperSvc";
    private static final long TRANSITION_DURATION_MS = 400L;

    private static final Set<AutoDarkEngine> ACTIVE_ENGINES = new CopyOnWriteArraySet<>();

    public static void notifyTargetStateChanged(boolean isDark, String reason) {
        notifyTargetStateChanged(isDark, null, reason);
    }

    public static void notifyTargetStateChanged(boolean isDark, String itemId, String reason) {
        AppLogger.i(TAG, "通知动态壁纸 Engine (" + ACTIVE_ENGINES.size()
                + " 个) 切换为 isDark=" + isDark + ", itemId=" + itemId + ", reason=" + reason);
        for (AutoDarkEngine engine : ACTIVE_ENGINES) {
            engine.applyTarget(isDark, itemId, true);
        }
    }

    public static void notifyImagesReloaded() {
        AppLogger.i(TAG, "通知动态壁纸 Engine 重新加载壁纸库 (" + ACTIVE_ENGINES.size() + " 个)");
        for (AutoDarkEngine engine : ACTIVE_ENGINES) {
            engine.reloadBitmapsAsync();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.init(this);
        AppLogger.i(TAG, "AutoDarkWallpaperService.onCreate()");
    }

    @Override
    public Engine onCreateEngine() {
        AppLogger.i(TAG, "AutoDarkWallpaperService.onCreateEngine()");
        return new AutoDarkEngine();
    }

    private final class AutoDarkEngine extends Engine {

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect srcRect = new Rect();
        private final Rect dstRect = new Rect();

        private Bitmap lightBitmap;
        private Bitmap darkBitmap;
        private String loadedLightId;
        private String loadedDarkId;
        private int surfaceWidth = 0;
        private int surfaceHeight = 0;
        private boolean visible = false;
        private boolean targetDark = false;
        private float darkBlend = 0f; // 0.0f = 纯日间，1.0f = 纯夜间
        private ValueAnimator blendAnimator;
        private BroadcastReceiver stateReceiver;

        AutoDarkEngine() {
            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setTextSize(34f);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            ACTIVE_ENGINES.add(this);
            targetDark = resolveInitialDarkState();
            darkBlend = targetDark ? 1f : 0f;
            AppLogger.i(TAG, "Engine.onCreate() | 初始 darkBlend=" + darkBlend);
            registerStateReceiver();
            reloadBitmapsAsync();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            AppLogger.i(TAG, "Engine.onSurfaceCreated()");
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            this.surfaceWidth = width;
            this.surfaceHeight = height;
            AppLogger.i(TAG, "Engine.onSurfaceChanged(" + width + "x" + height + ")");
            reloadBitmapsAsync();
            drawFrame();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            AppLogger.i(TAG, "Engine.onVisibilityChanged(visible=" + visible + ")");
            if (visible) {
                boolean latestDark = resolveInitialDarkState();
                if (latestDark != targetDark) {
                    applyTarget(latestDark, null, false);
                } else {
                    drawFrame();
                }
            }
        }

        @Override
        public void onDestroy() {
            ACTIVE_ENGINES.remove(this);
            if (blendAnimator != null) {
                blendAnimator.cancel();
                blendAnimator = null;
            }
            if (stateReceiver != null) {
                try {
                    unregisterReceiver(stateReceiver);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "注销动态壁纸广播接收器失败", t);
                }
                stateReceiver = null;
            }
            decodeExecutor.shutdownNow();
            recycleBitmaps();
            AppLogger.i(TAG, "Engine.onDestroy()");
            super.onDestroy();
        }

        private boolean resolveInitialDarkState() {
            AppPrefs prefs = new AppPrefs(AutoDarkWallpaperService.this);
            int saved = prefs.getCurrentTargetState();
            if (saved == AppPrefs.TARGET_STATE_DARK) {
                return true;
            }
            if (saved == AppPrefs.TARGET_STATE_LIGHT) {
                return false;
            }
            int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mask == Configuration.UI_MODE_NIGHT_YES;
        }

        private void registerStateReceiver() {
            stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (WallpaperController.ACTION_WALLPAPER_STATE_CHANGED.equals(action)) {
                        boolean isDark = intent.getBooleanExtra(WallpaperController.EXTRA_IS_DARK, false);
                        String itemId = intent.getStringExtra(WallpaperController.EXTRA_ITEM_ID);
                        applyTarget(isDark, itemId, true);
                    } else if (WallpaperController.ACTION_WALLPAPER_IMAGES_RELOADED.equals(action)) {
                        reloadBitmapsAsync();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(WallpaperController.ACTION_WALLPAPER_STATE_CHANGED);
            filter.addAction(WallpaperController.ACTION_WALLPAPER_IMAGES_RELOADED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
        }

        void applyTarget(final boolean isDark, final String itemId, final boolean animate) {
            final int reqW = surfaceWidth > 0 ? surfaceWidth : 1920;
            final int reqH = surfaceHeight > 0 ? surfaceHeight : 1200;

            decodeExecutor.execute(() -> {
                WallpaperRepository.WallpaperItem resolved = null;
                File targetFile = null;
                if (itemId != null && !itemId.isEmpty()) {
                    targetFile = WallpaperRepository.getWallpaperFile(
                            AutoDarkWallpaperService.this, isDark, itemId);
                }
                if (targetFile == null || !targetFile.exists()) {
                    resolved = WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, isDark);
                    if (resolved != null) {
                        targetFile = WallpaperRepository.getWallpaperFile(
                                AutoDarkWallpaperService.this, isDark, resolved.id);
                    }
                }
                final String finalId = (itemId != null && !itemId.isEmpty())
                        ? itemId
                        : (resolved != null ? resolved.id : null);

                String currentlyLoadedId = isDark ? loadedDarkId : loadedLightId;
                Bitmap currentBmp = isDark ? darkBitmap : lightBitmap;
                boolean needReload = (finalId == null && currentBmp != null)
                        || (finalId != null && (!finalId.equals(currentlyLoadedId) || currentBmp == null));

                Bitmap decoded = null;
                if (needReload && targetFile != null && targetFile.exists()) {
                    decoded = WallpaperController.decodeSampledBitmap(targetFile, reqW, reqH);
                }

                final boolean shouldUpdateBitmap = needReload;
                final Bitmap newBitmap = decoded;
                mainHandler.post(() -> {
                    if (shouldUpdateBitmap) {
                        if (isDark) {
                            if (darkBitmap != null && !darkBitmap.isRecycled()) {
                                darkBitmap.recycle();
                            }
                            darkBitmap = newBitmap;
                            loadedDarkId = finalId;
                        } else {
                            if (lightBitmap != null && !lightBitmap.isRecycled()) {
                                lightBitmap.recycle();
                            }
                            lightBitmap = newBitmap;
                            loadedLightId = finalId;
                        }
                    }
                    animateToTargetDark(isDark, animate);
                });
            });
        }

        private void animateToTargetDark(boolean isDark, boolean animate) {
            targetDark = isDark;
            float targetBlend = isDark ? 1f : 0f;
            if (blendAnimator != null) {
                blendAnimator.cancel();
                blendAnimator = null;
            }
            if (!animate || !visible || Math.abs(darkBlend - targetBlend) < 0.001f) {
                darkBlend = targetBlend;
                drawFrame();
                return;
            }
            blendAnimator = ValueAnimator.ofFloat(darkBlend, targetBlend);
            blendAnimator.setDuration(TRANSITION_DURATION_MS);
            blendAnimator.setInterpolator(new DecelerateInterpolator());
            blendAnimator.addUpdateListener(anim -> {
                darkBlend = (float) anim.getAnimatedValue();
                drawFrame();
            });
            blendAnimator.start();
        }

        void reloadBitmapsAsync() {
            final int reqW = surfaceWidth > 0 ? surfaceWidth : 1920;
            final int reqH = surfaceHeight > 0 ? surfaceHeight : 1200;
            decodeExecutor.execute(() -> {
                try {
                    WallpaperRepository.WallpaperItem dayItem =
                            WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, false);
                    WallpaperRepository.WallpaperItem nightItem =
                            WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, true);

                    File dayFile = dayItem != null
                            ? WallpaperRepository.getWallpaperFile(AutoDarkWallpaperService.this, false, dayItem.id)
                            : null;
                    File nightFile = nightItem != null
                            ? WallpaperRepository.getWallpaperFile(AutoDarkWallpaperService.this, true, nightItem.id)
                            : null;

                    final Bitmap newLight = WallpaperController.decodeSampledBitmap(dayFile, reqW, reqH);
                    final Bitmap newDark = WallpaperController.decodeSampledBitmap(nightFile, reqW, reqH);
                    final String newLightId = dayItem != null ? dayItem.id : null;
                    final String newDarkId = nightItem != null ? nightItem.id : null;

                    mainHandler.post(() -> {
                        recycleBitmaps();
                        lightBitmap = newLight;
                        darkBitmap = newDark;
                        loadedLightId = newLightId;
                        loadedDarkId = newDarkId;
                        drawFrame();
                    });
                } catch (Throwable t) {
                    AppLogger.e(TAG, "动态壁纸加载图片失败", t);
                }
            });
        }

        private void recycleBitmaps() {
            if (lightBitmap != null && !lightBitmap.isRecycled()) {
                lightBitmap.recycle();
            }
            lightBitmap = null;
            loadedLightId = null;
            if (darkBitmap != null && !darkBitmap.isRecycled()) {
                darkBitmap.recycle();
            }
            darkBitmap = null;
            loadedDarkId = null;
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            if (holder == null) {
                return;
            }
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) {
                    return;
                }
                int w = canvas.getWidth();
                int h = canvas.getHeight();
                boolean showingDarkSide = darkBlend >= 0.5f;
                canvas.drawColor(showingDarkSide ? Color.parseColor("#0D1117") : Color.parseColor("#EEF2F7"));

                boolean drewAny = false;
                if (lightBitmap != null && !lightBitmap.isRecycled() && darkBlend < 1f) {
                    bitmapPaint.setAlpha(255);
                    drawCenterCrop(canvas, lightBitmap, w, h, bitmapPaint);
                    if (!showingDarkSide) {
                        drewAny = true;
                    }
                }
                if (darkBitmap != null && !darkBitmap.isRecycled() && darkBlend > 0f) {
                    int alpha = Math.max(0, Math.min(255, Math.round(darkBlend * 255f)));
                    bitmapPaint.setAlpha(alpha);
                    drawCenterCrop(canvas, darkBitmap, w, h, bitmapPaint);
                    if (showingDarkSide) {
                        drewAny = true;
                    }
                }

                if (!drewAny) {
                    hintPaint.setColor(showingDarkSide ? Color.parseColor("#9BA6B8") : Color.parseColor("#5B6577"));
                    String msg = "请先在应用中添加" + (showingDarkSide ? "夜间" : "日间") + "壁纸";
                    canvas.drawText(msg, w / 2f, h / 2f, hintPaint);
                    AppLogger.w(TAG, "当前模式尚未添加壁纸，绘制提示占位画面: showingDarkSide=" + showingDarkSide);
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "Engine.drawFrame() 绘制异常", t);
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Throwable t) {
                        AppLogger.e(TAG, "unlockCanvasAndPost 异常", t);
                    }
                }
            }
        }

        private void drawCenterCrop(Canvas canvas, Bitmap bmp, int viewW, int viewH, Paint paint) {
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0 || viewW <= 0 || viewH <= 0) {
                return;
            }
            float viewRatio = (float) viewW / (float) viewH;
            float bmpRatio = (float) bw / (float) bh;
            if (bmpRatio > viewRatio) {
                int cropW = Math.round(bh * viewRatio);
                int left = Math.max(0, (bw - cropW) / 2);
                srcRect.set(left, 0, Math.min(bw, left + cropW), bh);
            } else {
                int cropH = Math.round(bw / viewRatio);
                int top = Math.max(0, (bh - cropH) / 2);
                srcRect.set(0, top, bw, Math.min(bh, top + cropH));
            }
            dstRect.set(0, 0, viewW, viewH);
            canvas.drawBitmap(bmp, srcRect, dstRect, paint);
        }
    }
}
