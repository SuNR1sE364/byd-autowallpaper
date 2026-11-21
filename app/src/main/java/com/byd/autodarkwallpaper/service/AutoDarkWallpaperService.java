package com.byd.autodarkwallpaper.service;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.byd.autodarkwallpaper.config.AppPrefs;
import com.byd.autodarkwallpaper.monitor.VehicleStateMonitor;
import com.byd.autodarkwallpaper.util.AppLogger;
import com.byd.autodarkwallpaper.wallpaper.WallpaperController;
import com.byd.autodarkwallpaper.wallpaper.WallpaperRepository;

import java.io.File;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态壁纸引擎服务：基于 OpenGL ES 2.0 + Media3 ExoPlayer 统一渲染图片与视频壁纸，
 * 支持日间与夜间多壁纸管理与 400ms 平滑淡入淡出切换。
 */
public class AutoDarkWallpaperService extends WallpaperService {

    private static final String TAG = "LiveWallpaperSvc";
    private static final long TRANSITION_DURATION_MS = 400L;
    private static final long IDLE_PLAYER_RELEASE_DELAY_MS = 30_000L;
    private static final long VIDEO_RETRY_DELAY_MS = 1500L;
    private static final int MAX_VIDEO_RETRY = 3;

    private static final Set<AutoDarkEngine> ACTIVE_ENGINES = new CopyOnWriteArraySet<>();

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
            engine.reloadAssetsAsync();
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

    private static final class SideAsset {
        String itemId;
        boolean isVideo;
        File videoFile;
        Bitmap bitmap;
        int videoWidth;
        int videoHeight;

        void clear() {
            itemId = null;
            isVideo = false;
            videoFile = null;
            bitmap = null;
            videoWidth = 0;
            videoHeight = 0;
        }
    }

    private final class AutoDarkEngine extends Engine {

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
        private final WallpaperGlRenderer glRenderer = new WallpaperGlRenderer();
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final AtomicInteger dayLoadSeq = new AtomicInteger(0);
        private final AtomicInteger nightLoadSeq = new AtomicInteger(0);
        private final AtomicInteger targetApplySeq = new AtomicInteger(0);

        private final SideAsset dayAsset = new SideAsset();
        private final SideAsset nightAsset = new SideAsset();

        private Bitmap activeLayerBitmap;
        private Bitmap retainedFromBitmap;
        private boolean currentSceneIsVideo = false;
        private File currentSceneVideoFile = null;
        private String currentSceneItemId = null;

        private int surfaceWidth = 0;
        private int surfaceHeight = 0;
        private boolean visible = false;
        private boolean targetDark = false;
        private Surface videoSurface;

        private ExoPlayer exoPlayer;
        private File currentPlayingVideoFile;
        private int videoRetryCount = 0;

        private ValueAnimator blendAnimator;
        private BroadcastReceiver stateReceiver;

        private final Runnable releaseIdlePlayerRunnable = () -> {
            if (!visible && exoPlayer != null) {
                AppLogger.i(TAG, "壁纸已连续隐藏超过 30 秒，释放 ExoPlayer 归还车机解码资源");
                releaseExoPlayer();
                glRenderer.setVideoFirstFrameRendered(false);
            }
        };

        private final Runnable retryVideoRunnable = () -> {
            if (!visible || videoSurface == null || !currentSceneIsVideo || currentSceneVideoFile == null) {
                return;
            }
            AppLogger.i(TAG, "正在重建 ExoPlayer 重试播放视频: " + currentSceneVideoFile.getName());
            releaseExoPlayer();
            startVideoPlayback(currentSceneVideoFile);
        };

        private final Player.Listener playerListener = new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    glRenderer.setVideoMetadata(
                            videoSize.width,
                            videoSize.height,
                            videoSize.pixelWidthHeightRatio,
                            videoSize.unappliedRotationDegrees);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                videoRetryCount = 0;
                glRenderer.setVideoFirstFrameRendered(true);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLogger.e(TAG, "ExoPlayer 播放视频壁纸出错: file="
                        + (currentPlayingVideoFile != null ? currentPlayingVideoFile.getAbsolutePath() : "null")
                        + ", errorCode=" + error.errorCode, error);
                glRenderer.setVideoFirstFrameRendered(false);
                if (visible && currentSceneIsVideo && currentSceneVideoFile != null
                        && videoRetryCount < MAX_VIDEO_RETRY) {
                    videoRetryCount++;
                    mainHandler.removeCallbacks(retryVideoRunnable);
                    mainHandler.postDelayed(retryVideoRunnable, VIDEO_RETRY_DELAY_MS);
                    AppLogger.w(TAG, "将在 " + VIDEO_RETRY_DELAY_MS + "ms 后尝试第 " + videoRetryCount + " 次恢复视频播放");
                } else {
                    AppLogger.e(TAG, "视频播放器重试次数耗尽或当前不可见，已回退展示缩略图");
                    releaseExoPlayer();
                }
            }
        };

        AutoDarkEngine() {
            hintPaint.setTextAlign(Paint.Align.CENTER);
            hintPaint.setTextSize(34f);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            ACTIVE_ENGINES.add(this);
            targetDark = resolveInitialDarkState();
            AppLogger.i(TAG, "Engine.onCreate() | 初始 targetDark=" + targetDark);
            registerStateReceiver();
            reloadAssetsAsync();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            Rect frame = holder != null ? holder.getSurfaceFrame() : null;
            int w = (frame != null && frame.width() > 0) ? frame.width() : (surfaceWidth > 0 ? surfaceWidth : 1920);
            int h = (frame != null && frame.height() > 0) ? frame.height() : (surfaceHeight > 0 ? surfaceHeight : 1200);
            AppLogger.i(TAG, "Engine.onSurfaceCreated(" + w + "x" + h + ")");
            glRenderer.attachSurface(holder, w, h, createdSurface -> {
                this.videoSurface = createdSurface;
                if (currentSceneIsVideo && currentSceneVideoFile != null) {
                    if (exoPlayer != null && currentSceneVideoFile.equals(currentPlayingVideoFile)) {
                        exoPlayer.setVideoSurface(createdSurface);
                        if (visible) {
                            exoPlayer.play();
                        }
                    } else if (visible) {
                        startVideoPlayback(currentSceneVideoFile);
                    }
                }
                glRenderer.requestRender();
            });
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            boolean sizeChanged = (this.surfaceWidth != width || this.surfaceHeight != height);
            this.surfaceWidth = width;
            this.surfaceHeight = height;
            AppLogger.i(TAG, "Engine.onSurfaceChanged(" + width + "x" + height + ")");
            glRenderer.resize(width, height);
            if (sizeChanged && !currentSceneIsVideo) {
                reloadAssetsAsync();
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            AppLogger.i(TAG, "Engine.onSurfaceDestroyed()");
            cancelTransitionAnimator();
            mainHandler.removeCallbacks(retryVideoRunnable);
            mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
            releaseExoPlayer();
            videoSurface = null;
            glRenderer.setVideoFirstFrameRendered(false);
            glRenderer.detachSurface();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            AppLogger.i(TAG, "Engine.onVisibilityChanged(visible=" + visible + ")");
            if (visible) {
                mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
                glRenderer.setRenderingEnabled(true);
                boolean latestDark = resolveInitialDarkState();
                if (latestDark != targetDark) {
                    applyTarget(latestDark, null, false);
                } else if (currentSceneIsVideo && currentSceneVideoFile != null) {
                    if (exoPlayer != null && currentSceneVideoFile.equals(currentPlayingVideoFile)) {
                        exoPlayer.play();
                    } else if (videoSurface != null) {
                        startVideoPlayback(currentSceneVideoFile);
                    }
                    glRenderer.requestRender();
                } else {
                    glRenderer.requestRender();
                }
            } else {
                glRenderer.setRenderingEnabled(false);
                mainHandler.removeCallbacks(retryVideoRunnable);
                if (exoPlayer != null) {
                    exoPlayer.pause();
                    mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
                    mainHandler.postDelayed(releaseIdlePlayerRunnable, IDLE_PLAYER_RELEASE_DELAY_MS);
                }
            }
        }

        @Override
        public void onDestroy() {
            ACTIVE_ENGINES.remove(this);
            cancelTransitionAnimator();
            mainHandler.removeCallbacks(retryVideoRunnable);
            mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
            releaseExoPlayer();
            if (stateReceiver != null) {
                try {
                    unregisterReceiver(stateReceiver);
                } catch (Throwable t) {
                    AppLogger.e(TAG, "注销动态壁纸广播接收器失败", t);
                }
                stateReceiver = null;
            }
            decodeExecutor.shutdownNow();
            releaseAllHeldBitmaps();
            glRenderer.release();
            AppLogger.i(TAG, "Engine.onDestroy()");
            super.onDestroy();
        }

        private boolean resolveInitialDarkState() {
            Boolean bydDark = VehicleStateMonitor.queryBydDarkStateOrNull(AutoDarkWallpaperService.this);
            if (bydDark != null) {
                return bydDark;
            }
            AppPrefs prefs = new AppPrefs(AutoDarkWallpaperService.this);
            int saved = prefs.getCurrentTargetState();
            if (saved == AppPrefs.TARGET_STATE_DARK) {
                return true;
            }
            if (saved == AppPrefs.TARGET_STATE_LIGHT) {
                return false;
            }
            AppLogger.w(TAG, "动态壁纸初始化无法读取 key_byd_night_mode 且无历史状态，默认先按日间(false)渲染");
            return false;
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
                        reloadAssetsAsync();
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
            final int sideSeq = (isDark ? nightLoadSeq : dayLoadSeq).incrementAndGet();
            final int applySeq = targetApplySeq.incrementAndGet();

            decodeExecutor.execute(() -> {
                try {
                    WallpaperRepository.WallpaperItem resolved = resolveTargetItem(isDark, itemId);
                    SideAsset existingSide = isDark ? nightAsset : dayAsset;
                    boolean sameItemAlreadyLoaded = resolved != null
                            && resolved.id.equals(existingSide.itemId)
                            && existingSide.isVideo == resolved.isVideo()
                            && existingSide.bitmap != null
                            && !existingSide.bitmap.isRecycled()
                            && (!resolved.isVideo() || (existingSide.videoFile != null && existingSide.videoFile.exists()));

                    LoadedSidePayload payload = sameItemAlreadyLoaded
                            ? null
                            : loadPayloadForItem(isDark, resolved, reqW, reqH);

                    mainHandler.post(() -> {
                        int latestSideSeq = (isDark ? nightLoadSeq : dayLoadSeq).get();
                        if (sideSeq == latestSideSeq && payload != null) {
                            applyPayloadToSide(isDark, payload);
                        } else if (payload != null && payload.bitmap != null) {
                            safeRecycleIfUnused(payload.bitmap);
                        }

                        if (applySeq == targetApplySeq.get()) {
                            transitionToSide(isDark, animate);
                        }
                    });
                } catch (Throwable t) {
                    AppLogger.e(TAG, "应用目标壁纸加载失败: isDark=" + isDark + ", itemId=" + itemId, t);
                }
            });
        }

        void reloadAssetsAsync() {
            final int reqW = surfaceWidth > 0 ? surfaceWidth : 1920;
            final int reqH = surfaceHeight > 0 ? surfaceHeight : 1200;
            final int dSeq = dayLoadSeq.incrementAndGet();
            final int nSeq = nightLoadSeq.incrementAndGet();

            decodeExecutor.execute(() -> {
                try {
                    WallpaperRepository.WallpaperItem dayItem =
                            WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, false, true);
                    WallpaperRepository.WallpaperItem nightItem =
                            WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, true, true);

                    final LoadedSidePayload dayPayload = loadPayloadForItem(false, dayItem, reqW, reqH);
                    final LoadedSidePayload nightPayload = loadPayloadForItem(true, nightItem, reqW, reqH);

                    mainHandler.post(() -> {
                        if (dSeq == dayLoadSeq.get()) {
                            applyPayloadToSide(false, dayPayload);
                        } else if (dayPayload.bitmap != null) {
                            safeRecycleIfUnused(dayPayload.bitmap);
                        }
                        if (nSeq == nightLoadSeq.get()) {
                            applyPayloadToSide(true, nightPayload);
                        } else if (nightPayload.bitmap != null) {
                            safeRecycleIfUnused(nightPayload.bitmap);
                        }
                        transitionToSide(targetDark, false);
                    });
                } catch (Throwable t) {
                    AppLogger.e(TAG, "动态壁纸加载壁纸库资源失败", t);
                }
            });
        }

        private WallpaperRepository.WallpaperItem resolveTargetItem(boolean isDark, String itemId) {
            if (itemId != null && !itemId.isEmpty()) {
                WallpaperRepository.WallpaperItem byId =
                        WallpaperRepository.findItemById(AutoDarkWallpaperService.this, isDark, itemId);
                if (byId != null) {
                    File f = WallpaperRepository.getWallpaperFile(AutoDarkWallpaperService.this, isDark, byId);
                    if (f != null && f.exists() && f.length() > 0L) {
                        return byId;
                    }
                }
            }
            return WallpaperRepository.getActiveItem(AutoDarkWallpaperService.this, isDark, true);
        }

        private LoadedSidePayload loadPayloadForItem(boolean isDark,
                                                     WallpaperRepository.WallpaperItem item,
                                                     int reqW,
                                                     int reqH) {
            if (item == null) {
                return new LoadedSidePayload(null, false, null, createHintBitmap(isDark, reqW, reqH), 0, 0);
            }
            File mainFile = WallpaperRepository.getWallpaperFile(AutoDarkWallpaperService.this, isDark, item);
            if (mainFile == null || !mainFile.exists() || mainFile.length() == 0L) {
                AppLogger.w(TAG, "壁纸文件缺失，使用占位提示画面: id=" + item.id);
                return new LoadedSidePayload(null, false, null, createHintBitmap(isDark, reqW, reqH), 0, 0);
            }
            if (item.isVideo()) {
                File thumbFile = WallpaperRepository.getThumbnailFile(AutoDarkWallpaperService.this, isDark, item);
                Bitmap thumb = WallpaperController.decodeSampledBitmap(thumbFile, reqW, reqH);
                if (thumb == null) {
                    AppLogger.w(TAG, "视频缩略图缺失，尝试现场抽取首帧: id=" + item.id);
                    thumb = extractFirstVideoFrameFallback(mainFile);
                }
                return new LoadedSidePayload(
                        item.id, true, mainFile, thumb, item.width, item.height);
            }
            Bitmap bmp = WallpaperController.decodeSampledBitmap(mainFile, reqW, reqH);
            if (bmp == null) {
                AppLogger.w(TAG, "图片解码失败，使用占位提示画面: " + mainFile.getAbsolutePath());
                bmp = createHintBitmap(isDark, reqW, reqH);
            }
            return new LoadedSidePayload(item.id, false, null, bmp, 0, 0);
        }

        private Bitmap extractFirstVideoFrameFallback(File videoFile) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(videoFile.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                return frame != null ? frame : retriever.getFrameAtTime(-1L);
            } catch (Throwable t) {
                AppLogger.e(TAG, "现场抽取视频首帧失败: " + videoFile.getAbsolutePath(), t);
                return null;
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
        }

        private Bitmap createHintBitmap(boolean isDark, int reqW, int reqH) {
            int w = Math.max(640, Math.min(1280, reqW));
            int h = Math.max(360, Math.min(800, reqH));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(isDark ? Color.parseColor("#0D1117") : Color.parseColor("#EEF2F7"));
            hintPaint.setColor(isDark ? Color.parseColor("#9BA6B8") : Color.parseColor("#5B6577"));
            String msg = "请先在应用中添加" + (isDark ? "夜间" : "日间") + "壁纸";
            canvas.drawText(msg, w / 2f, h / 2f, hintPaint);
            AppLogger.w(TAG, "当前模式尚未添加壁纸，生成提示占位画面: isDark=" + isDark);
            return bmp;
        }

        private void applyPayloadToSide(boolean isDark, LoadedSidePayload payload) {
            SideAsset targetSide = isDark ? nightAsset : dayAsset;
            Bitmap oldBmp = targetSide.bitmap;
            targetSide.itemId = payload.itemId;
            targetSide.isVideo = payload.isVideo;
            targetSide.videoFile = payload.videoFile;
            targetSide.bitmap = payload.bitmap;
            targetSide.videoWidth = payload.videoWidth;
            targetSide.videoHeight = payload.videoHeight;
            safeRecycleIfUnused(oldBmp);
        }

        private void transitionToSide(boolean isDark, boolean animate) {
            targetDark = isDark;
            SideAsset target = isDark ? nightAsset : dayAsset;
            boolean doAnimate = animate && visible;

            // 若目标与当前正在播放的是同一个视频文件，无需重启解码器
            if (target.isVideo && currentSceneIsVideo
                    && target.videoFile != null && target.videoFile.equals(currentSceneVideoFile)) {
                Bitmap oldActive = activeLayerBitmap;
                activeLayerBitmap = target.bitmap;
                currentSceneItemId = target.itemId;
                safeRecycleIfUnused(oldActive);

                glRenderer.updateScene(
                        WallpaperGlRenderer.LayerKind.NONE, null,
                        WallpaperGlRenderer.LayerKind.VIDEO, null,
                        target.bitmap, 1f, isDark);
                if (visible && videoSurface != null) {
                    if (exoPlayer == null) {
                        startVideoPlayback(target.videoFile);
                    } else if (!exoPlayer.isPlaying()) {
                        exoPlayer.play();
                    }
                }
                return;
            }

            // 若目标与当前是同一张未变化的静态图，直接更新渲染即可
            if (!target.isVideo && !currentSceneIsVideo
                    && target.itemId != null && target.itemId.equals(currentSceneItemId)
                    && target.bitmap == activeLayerBitmap && activeLayerBitmap != null) {
                glRenderer.updateScene(
                        WallpaperGlRenderer.LayerKind.NONE, null,
                        WallpaperGlRenderer.LayerKind.BITMAP, activeLayerBitmap,
                        null, 1f, isDark);
                return;
            }

            cancelTransitionAnimator();
            mainHandler.removeCallbacks(retryVideoRunnable);

            final boolean wasVideo = currentSceneIsVideo;
            final Bitmap previousBitmap = activeLayerBitmap;

            if (!target.isVideo) {
                // 目标为静态图片（或空状态提示图）
                final Bitmap targetBmp = target.bitmap;
                if (wasVideo && doAnimate && exoPlayer != null) {
                    // 视频 -> 图：先暂停旧播放器保留 OES 最后一帧，400ms 过渡结束后再释放播放器
                    exoPlayer.pause();
                    Bitmap oldFrom = retainedFromBitmap;
                    retainedFromBitmap = null;
                    safeRecycleIfUnused(oldFrom);

                    Bitmap oldActive = activeLayerBitmap;
                    activeLayerBitmap = targetBmp;
                    currentSceneIsVideo = false;
                    currentSceneVideoFile = null;
                    currentSceneItemId = target.itemId;
                    safeRecycleIfUnused(oldActive);

                    glRenderer.updateScene(
                            WallpaperGlRenderer.LayerKind.VIDEO, null,
                            WallpaperGlRenderer.LayerKind.BITMAP, targetBmp,
                            previousBitmap, 0f, isDark);

                    startBlendAnimation(
                            WallpaperGlRenderer.LayerKind.BITMAP, targetBmp, null,
                            isDark, true);
                } else {
                    releaseExoPlayer();
                    glRenderer.setVideoFirstFrameRendered(false);

                    boolean crossfadeImages = doAnimate && previousBitmap != null && !previousBitmap.isRecycled();
                    Bitmap oldFrom = retainedFromBitmap;
                    retainedFromBitmap = crossfadeImages ? previousBitmap : null;
                    safeRecycleIfUnused(oldFrom);

                    activeLayerBitmap = targetBmp;
                    currentSceneIsVideo = false;
                    currentSceneVideoFile = null;
                    currentSceneItemId = target.itemId;
                    if (!crossfadeImages) {
                        safeRecycleIfUnused(previousBitmap);
                    }

                    glRenderer.updateScene(
                            crossfadeImages ? WallpaperGlRenderer.LayerKind.BITMAP : WallpaperGlRenderer.LayerKind.NONE,
                            crossfadeImages ? previousBitmap : null,
                            WallpaperGlRenderer.LayerKind.BITMAP,
                            targetBmp,
                            null,
                            crossfadeImages ? 0f : 1f,
                            isDark);

                    if (crossfadeImages) {
                        startBlendAnimation(
                                WallpaperGlRenderer.LayerKind.BITMAP, targetBmp, null,
                                isDark, false);
                    }
                }
                return;
            }

            // 目标为视频壁纸（图片 -> 视频 或 视频 A -> 视频 B）
            releaseExoPlayer();
            glRenderer.setVideoFirstFrameRendered(false);
            if (target.videoWidth > 0 && target.videoHeight > 0) {
                glRenderer.setVideoMetadata(target.videoWidth, target.videoHeight, 1f, 0);
            }

            boolean crossfadeFromPrev = doAnimate && previousBitmap != null && !previousBitmap.isRecycled();
            Bitmap oldFrom = retainedFromBitmap;
            retainedFromBitmap = crossfadeFromPrev ? previousBitmap : null;
            safeRecycleIfUnused(oldFrom);

            activeLayerBitmap = target.bitmap;
            currentSceneIsVideo = true;
            currentSceneVideoFile = target.videoFile;
            currentSceneItemId = target.itemId;
            if (!crossfadeFromPrev) {
                safeRecycleIfUnused(previousBitmap);
            }

            glRenderer.updateScene(
                    crossfadeFromPrev ? WallpaperGlRenderer.LayerKind.BITMAP : WallpaperGlRenderer.LayerKind.NONE,
                    crossfadeFromPrev ? previousBitmap : null,
                    WallpaperGlRenderer.LayerKind.VIDEO,
                    null,
                    target.bitmap,
                    crossfadeFromPrev ? 0f : 1f,
                    isDark);

            if (visible && videoSurface != null && target.videoFile != null) {
                videoRetryCount = 0;
                startVideoPlayback(target.videoFile);
            }

            if (crossfadeFromPrev) {
                startBlendAnimation(
                        WallpaperGlRenderer.LayerKind.VIDEO, null, target.bitmap,
                        isDark, false);
            }
        }

        private void startBlendAnimation(final WallpaperGlRenderer.LayerKind finalToKind,
                                         final Bitmap finalToBitmap,
                                         final Bitmap finalVideoThumb,
                                         final boolean darkBg,
                                         final boolean releasePlayerOnEnd) {
            blendAnimator = ValueAnimator.ofFloat(0f, 1f);
            blendAnimator.setDuration(TRANSITION_DURATION_MS);
            blendAnimator.setInterpolator(new DecelerateInterpolator());
            blendAnimator.addUpdateListener(anim -> {
                float progress = (float) anim.getAnimatedValue();
                glRenderer.setBlend(progress, darkBg);
            });
            blendAnimator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled = false;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    blendAnimator = null;
                    if (cancelled) {
                        return;
                    }
                    if (releasePlayerOnEnd) {
                        releaseExoPlayer();
                        glRenderer.setVideoFirstFrameRendered(false);
                    }
                    glRenderer.updateScene(
                            WallpaperGlRenderer.LayerKind.NONE, null,
                            finalToKind, finalToBitmap,
                            finalVideoThumb, 1f, darkBg);
                    Bitmap oldFrom = retainedFromBitmap;
                    retainedFromBitmap = null;
                    safeRecycleIfUnused(oldFrom);
                }
            });
            blendAnimator.start();
        }

        private void cancelTransitionAnimator() {
            if (blendAnimator != null) {
                blendAnimator.cancel();
                blendAnimator = null;
            }
        }

        @OptIn(markerClass = UnstableApi.class)
        private void startVideoPlayback(File videoFile) {
            if (videoFile == null || !videoFile.exists() || videoSurface == null) {
                return;
            }
            mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
            try {
                if (exoPlayer == null) {
                    exoPlayer = new ExoPlayer.Builder(AutoDarkWallpaperService.this)
                            .setHandleAudioBecomingNoisy(false)
                            .build();
                    TrackSelectionParameters trackParams = exoPlayer.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build();
                    exoPlayer.setTrackSelectionParameters(trackParams);
                    exoPlayer.setVolume(0f);
                    exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
                    exoPlayer.addListener(playerListener);
                }
                currentPlayingVideoFile = videoFile;
                exoPlayer.setVideoSurface(videoSurface);
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)));
                exoPlayer.prepare();
                if (visible) {
                    exoPlayer.play();
                } else {
                    exoPlayer.pause();
                }
                AppLogger.i(TAG, "开始播放视频壁纸: " + videoFile.getName());
            } catch (Throwable t) {
                AppLogger.e(TAG, "启动 ExoPlayer 播放视频壁纸失败: " + videoFile.getAbsolutePath(), t);
                releaseExoPlayer();
            }
        }

        private void releaseExoPlayer() {
            mainHandler.removeCallbacks(releaseIdlePlayerRunnable);
            if (exoPlayer != null) {
                try {
                    exoPlayer.removeListener(playerListener);
                    exoPlayer.clearVideoSurface();
                    exoPlayer.stop();
                    exoPlayer.release();
                } catch (Throwable t) {
                    AppLogger.e(TAG, "释放 ExoPlayer 资源异常", t);
                }
                exoPlayer = null;
            }
            currentPlayingVideoFile = null;
        }

        private void safeRecycleIfUnused(Bitmap candidate) {
            if (candidate == null || candidate.isRecycled()) {
                return;
            }
            if (candidate == dayAsset.bitmap
                    || candidate == nightAsset.bitmap
                    || candidate == activeLayerBitmap
                    || candidate == retainedFromBitmap) {
                return;
            }
            glRenderer.recycleBitmapSafely(candidate);
        }

        private void releaseAllHeldBitmaps() {
            Bitmap b1 = dayAsset.bitmap;
            Bitmap b2 = nightAsset.bitmap;
            Bitmap b3 = activeLayerBitmap;
            Bitmap b4 = retainedFromBitmap;
            dayAsset.clear();
            nightAsset.clear();
            activeLayerBitmap = null;
            retainedFromBitmap = null;
            glRenderer.recycleBitmapSafely(b1);
            if (b2 != b1) {
                glRenderer.recycleBitmapSafely(b2);
            }
            if (b3 != b1 && b3 != b2) {
                glRenderer.recycleBitmapSafely(b3);
            }
            if (b4 != b1 && b4 != b2 && b4 != b3) {
                glRenderer.recycleBitmapSafely(b4);
            }
        }
    }

    private static final class LoadedSidePayload {
        final String itemId;
        final boolean isVideo;
        final File videoFile;
        final Bitmap bitmap;
        final int videoWidth;
        final int videoHeight;

        LoadedSidePayload(String itemId,
                          boolean isVideo,
                          File videoFile,
                          Bitmap bitmap,
                          int videoWidth,
                          int videoHeight) {
            this.itemId = itemId;
            this.isVideo = isVideo;
            this.videoFile = videoFile;
            this.bitmap = bitmap;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
        }
    }
}
