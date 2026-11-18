package com.byd.autodarkwallpaper.service;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 动态壁纸引擎服务（模式 B / 双模式兼容）。
 * 系统激活本服务作为壁纸后，支持日间与夜间壁纸之间 400ms 平滑淡入淡出过渡，且无磁盘重复写入开销。
 */
public class AutoDarkWallpaperService extends WallpaperService {

    private static final String TAG = "LiveWallpaperSvc";
    private static final long TRANSITION_DURATION_MS = 400L;

    private static final Set<AutoDarkEngine> ACTIVE_ENGINES = new CopyOnWriteArraySet<>();

    public static void notifyTargetStateChanged(boolean isDark, String reason) {
        AppLogger.i(TAG, "通知存活的动态壁纸 Engine (" + ACTIVE_ENGINES.size()
                + " 个) 切换为 isDark=" + isDark + ", reason=" + reason);
        for (AutoDarkEngine engine : ACTIVE_ENGINES) {
            engine.setTargetDark(isDark, true);
        }
    }

    public static void notifyImagesReloaded() {
        AppLogger.i(TAG, "通知动态壁纸 Engine 重新加载壁纸图片 (" + ACTIVE_ENGINES.size() + " 个)");
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
        private final Paint errorTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect srcRect = new Rect();
        private final Rect dstRect = new Rect();

        private Bitmap lightBitmap;
        private Bitmap darkBitmap;
        private int surfaceWidth = 0;
        private int surfaceHeight = 0;
        private boolean visible = false;
        private boolean targetDark = false;
        private float darkBlend = 0f; // 0.0f = 纯亮色，1.0f = 纯暗色
        private ValueAnimator blendAnimator;
        private BroadcastReceiver stateReceiver;

        AutoDarkEngine() {
            errorTextPaint.setColor(Color.WHITE);
            errorTextPaint.setTextSize(32f);
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
                    setTargetDark(latestDark, false);
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
            int mask = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
                        setTargetDark(isDark, true);
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

        void setTargetDark(boolean isDark, boolean animate) {
            mainHandler.post(() -> {
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
            });
        }

        void reloadBitmapsAsync() {
            final int reqW = surfaceWidth > 0 ? surfaceWidth : 1920;
            final int reqH = surfaceHeight > 0 ? surfaceHeight : 1200;
            decodeExecutor.execute(() -> {
                try {
                    WallpaperController.ensureDefaultWallpapers(AutoDarkWallpaperService.this);
                    final Bitmap newLight = WallpaperController.decodeSampledBitmap(
                            WallpaperController.getLightWallpaperFile(AutoDarkWallpaperService.this), reqW, reqH);
                    final Bitmap newDark = WallpaperController.decodeSampledBitmap(
                            WallpaperController.getDarkWallpaperFile(AutoDarkWallpaperService.this), reqW, reqH);
                    mainHandler.post(() -> {
                        recycleBitmaps();
                        lightBitmap = newLight;
                        darkBitmap = newDark;
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
            if (darkBitmap != null && !darkBitmap.isRecycled()) {
                darkBitmap.recycle();
            }
            darkBitmap = null;
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
                canvas.drawColor(darkBlend >= 0.5f ? Color.parseColor("#020617") : Color.parseColor("#0EA5E9"));

                if (lightBitmap != null && !lightBitmap.isRecycled() && darkBlend < 1f) {
                    bitmapPaint.setAlpha(255);
                    drawCenterCrop(canvas, lightBitmap, w, h, bitmapPaint);
                }
                if (darkBitmap != null && !darkBitmap.isRecycled() && darkBlend > 0f) {
                    int alpha = Math.max(0, Math.min(255, Math.round(darkBlend * 255f)));
                    bitmapPaint.setAlpha(alpha);
                    drawCenterCrop(canvas, darkBitmap, w, h, bitmapPaint);
                }
                if (lightBitmap == null && darkBitmap == null) {
                    canvas.drawText("正在加载壁纸...", 48f, h / 2f, errorTextPaint);
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
