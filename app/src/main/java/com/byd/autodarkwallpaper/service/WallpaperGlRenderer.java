package com.sunr1se.byd.autowallpaper.service;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.sunr1se.byd.autowallpaper.util.AppLogger;
import com.sunr1se.byd.autowallpaper.wallpaper.CropRect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 动态壁纸 OpenGL ES 2.0 统一渲染引擎：
 * 在独立 GL 线程内维护 EGL 环境，统一渲染静态图片（GL_TEXTURE_2D）与 ExoPlayer 视频帧（GL_TEXTURE_EXTERNAL_OES），
 * 支持双层 400ms 平滑淡入淡出过渡与车机横竖屏自适应 CenterCrop 裁剪。
 */
public final class WallpaperGlRenderer {

    private static final String TAG = "WallpaperGL";
    private static final long DETACH_TIMEOUT_MS = 2000L;

    public enum LayerKind {
        NONE,
        BITMAP,
        VIDEO
    }

    public interface SurfaceReadyCallback {
        void onVideoSurfaceReady(Surface videoSurface);
    }

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvpMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMvpMatrix * aPosition;\n" +
            "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform float uAlpha;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uAlpha;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    // 全屏四边形顶点（Triangle Strip：左下 -> 右下 -> 左上 -> 右上）
    private static final float[] QUAD_POSITIONS = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };

    private static final float[] QUAD_TEX_COORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread glThread;
    private final Handler glHandler;

    private final FloatBuffer posBuffer;
    private final FloatBuffer texBuffer;
    private final float[] bitmapTexMatrix = new float[16];
    private final float[] videoTexMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];
    private final float[] tempScale = new float[2];
    private final float[] tempTexMatrix = new float[16];

    // EGL 与 GL 资源（仅在 glThread 上读写）
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLConfig eglConfig;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private boolean surfaceReady = false;
    private boolean renderingEnabled = true;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;

    private GlProgram program2D;
    private GlProgram programOes;
    private int videoTextureId = 0;
    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;

    private int fromTexId = 0;
    private int fromTexW = 0;
    private int fromTexH = 0;
    private Bitmap lastFromBitmap;

    private int toTexId = 0;
    private int toTexW = 0;
    private int toTexH = 0;
    private Bitmap lastToBitmap;

    private int videoThumbTexId = 0;
    private int videoThumbW = 0;
    private int videoThumbH = 0;
    private Bitmap lastVideoThumbBitmap;

    // 逻辑场景状态（仅在 glThread 上读写）
    private LayerKind fromKind = LayerKind.NONE;
    private LayerKind toKind = LayerKind.NONE;
    private CropRect fromCropRect = null;
    private CropRect toCropRect = null;
    private float blend = 0f;
    private boolean darkBackground = false;
    private int videoWidth = 0;
    private int videoHeight = 0;
    private float videoPixelAspectRatio = 1f;
    private int videoUnappliedRotation = 0;
    private boolean videoFirstFrameRendered = false;

    private volatile boolean released = false;

    public WallpaperGlRenderer() {
        glThread = new HandlerThread("WallpaperGLThread");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        posBuffer = ByteBuffer.allocateDirect(QUAD_POSITIONS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        posBuffer.put(QUAD_POSITIONS).position(0);

        texBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texBuffer.put(QUAD_TEX_COORDS).position(0);

        // Bitmap 像素原点在左上角，GL 纹理原点在左下角：使用 V' = 1 - V 翻转 2D 纹理坐标
        Matrix.setIdentityM(bitmapTexMatrix, 0);
        Matrix.translateM(bitmapTexMatrix, 0, 0f, 1f, 0f);
        Matrix.scaleM(bitmapTexMatrix, 0, 1f, -1f, 1f);

        Matrix.setIdentityM(videoTexMatrix, 0);
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /**
     * 计算 CenterCrop 缩放系数，供 GL 绘制与单元测试复用。
     *
     * @param viewW             视口宽度
     * @param viewH             视口高度
     * @param contentW          内容原始宽度
     * @param contentH          内容原始高度
     * @param pixelAspectRatio  像素宽高比（通常为 1.0）
     * @param unappliedRotation 尚未应用到帧像素的顺时针旋转角度（0/90/180/270）
     * @param outScale          输出长度至少为 2 的数组：[scaleX, scaleY]
     */
    public static void computeCenterCropScale(int viewW,
                                              int viewH,
                                              int contentW,
                                              int contentH,
                                              float pixelAspectRatio,
                                              int unappliedRotation,
                                              float[] outScale) {
        if (outScale == null || outScale.length < 2) {
            throw new IllegalArgumentException("outScale length must be >= 2");
        }
        if (viewW <= 0 || viewH <= 0 || contentW <= 0 || contentH <= 0) {
            outScale[0] = 1f;
            outScale[1] = 1f;
            return;
        }
        float par = (pixelAspectRatio > 0f && !Float.isNaN(pixelAspectRatio) && !Float.isInfinite(pixelAspectRatio))
                ? pixelAspectRatio : 1f;
        float effectiveW = contentW * par;
        float effectiveH = (float) contentH;
        int rot = ((unappliedRotation % 360) + 360) % 360;
        if (rot == 90 || rot == 270) {
            float tmp = effectiveW;
            effectiveW = effectiveH;
            effectiveH = tmp;
        }

        float viewAspect = (float) viewW / (float) viewH;
        float contentAspect = effectiveW / effectiveH;
        if (contentAspect > viewAspect) {
            outScale[0] = contentAspect / viewAspect;
            outScale[1] = 1f;
        } else {
            outScale[0] = 1f;
            outScale[1] = viewAspect / contentAspect;
        }
    }

    /**
     * 计算带归一化裁剪区域的 2D Bitmap 列主序纹理矩阵（纯浮点运算，兼容纯 JVM 单元测试）。
     * Bitmap 上传到 GL 后，顶部对应 t=0，底部对应 t=1；全屏顶点在底边 q=0、顶边 q=1。
     */
    public static void computeBitmapTexMatrix(CropRect cropRect, float[] outTexMatrix) {
        if (outTexMatrix == null || outTexMatrix.length < 16) {
            throw new IllegalArgumentException("outTexMatrix length must be >= 16");
        }
        for (int i = 0; i < 16; i++) {
            outTexMatrix[i] = 0f;
        }
        CropRect r = cropRect != null ? cropRect : CropRect.FULL;
        outTexMatrix[0] = r.right - r.left;
        outTexMatrix[5] = -(r.bottom - r.top);
        outTexMatrix[10] = 1f;
        outTexMatrix[12] = r.left;
        outTexMatrix[13] = r.bottom;
        outTexMatrix[15] = 1f;
    }

    /**
     * 基于裁剪区域的有效子图尺寸计算 CenterCrop 缩放系数（避免裁剪区域本身被非等比拉伸）。
     */
    public static void computeCroppedCenterCropScale(int viewW,
                                                     int viewH,
                                                     int contentW,
                                                     int contentH,
                                                     CropRect cropRect,
                                                     float[] outScale) {
        if (outScale == null || outScale.length < 2) {
            throw new IllegalArgumentException("outScale length must be >= 2");
        }
        if (viewW <= 0 || viewH <= 0 || contentW <= 0 || contentH <= 0) {
            outScale[0] = 1f;
            outScale[1] = 1f;
            return;
        }
        if (cropRect == null) {
            computeCenterCropScale(viewW, viewH, contentW, contentH, 1f, 0, outScale);
            return;
        }
        float subW = Math.max(1f, contentW * cropRect.width());
        float subH = Math.max(1f, contentH * cropRect.height());
        float viewAspect = (float) viewW / (float) viewH;
        float contentAspect = subW / subH;
        if (contentAspect > viewAspect) {
            outScale[0] = contentAspect / viewAspect;
            outScale[1] = 1f;
        } else {
            outScale[0] = 1f;
            outScale[1] = viewAspect / contentAspect;
        }
    }

    public void attachSurface(final SurfaceHolder holder,
                              final int width,
                              final int height,
                              final SurfaceReadyCallback callback) {
        if (released || holder == null) {
            return;
        }
        postToGl(() -> {
            detachSurfaceOnGlThread();
            try {
                initEglOnGlThread(holder);
                this.surfaceWidth = width > 0 ? width : 1920;
                this.surfaceHeight = height > 0 ? height : 1200;
                initProgramsAndVideoSurfaceOnGlThread();
                this.surfaceReady = true;

                // 若在 Surface 就绪前已有待渲染的场景，立即补上传纹理
                syncBitmapTexturesOnGlThread(lastFromBitmap, lastToBitmap, lastVideoThumbBitmap);
                renderFrameOnGlThread();

                final Surface createdSurface = this.videoSurface;
                if (callback != null && createdSurface != null) {
                    mainHandler.post(() -> callback.onVideoSurfaceReady(createdSurface));
                }
            } catch (Throwable t) {
                AppLogger.e(TAG, "初始化 EGL / OpenGL 环境失败", t);
                detachSurfaceOnGlThread();
            }
        });
    }

    public void resize(final int width, final int height) {
        if (released) {
            return;
        }
        postToGl(() -> {
            if (width > 0 && height > 0) {
                this.surfaceWidth = width;
                this.surfaceHeight = height;
                renderFrameOnGlThread();
            }
        });
    }

    public void setRenderingEnabled(final boolean enabled) {
        if (released) {
            return;
        }
        postToGl(() -> {
            this.renderingEnabled = enabled;
            if (enabled) {
                renderFrameOnGlThread();
            }
        });
    }

    public void setVideoMetadata(final int width,
                                 final int height,
                                 final float pixelAspectRatio,
                                 final int unappliedRotation) {
        if (released) {
            return;
        }
        postToGl(() -> {
            this.videoWidth = width;
            this.videoHeight = height;
            this.videoPixelAspectRatio = pixelAspectRatio;
            this.videoUnappliedRotation = unappliedRotation;
            renderFrameOnGlThread();
        });
    }

    public void setVideoFirstFrameRendered(final boolean rendered) {
        if (released) {
            return;
        }
        postToGl(() -> {
            this.videoFirstFrameRendered = rendered;
            renderFrameOnGlThread();
        });
    }

    public void updateScene(final LayerKind fromKind,
                            final Bitmap fromBitmap,
                            final LayerKind toKind,
                            final Bitmap toBitmap,
                            final Bitmap videoThumbBitmap,
                            final float blend,
                            final boolean darkBackground) {
        updateScene(fromKind, fromBitmap, null, toKind, toBitmap, null, videoThumbBitmap, blend, darkBackground);
    }

    public void updateScene(final LayerKind fromKind,
                            final Bitmap fromBitmap,
                            final CropRect fromCropRect,
                            final LayerKind toKind,
                            final Bitmap toBitmap,
                            final CropRect toCropRect,
                            final Bitmap videoThumbBitmap,
                            final float blend,
                            final boolean darkBackground) {
        if (released) {
            return;
        }
        postToGl(() -> {
            this.fromKind = fromKind != null ? fromKind : LayerKind.NONE;
            this.toKind = toKind != null ? toKind : LayerKind.NONE;
            this.fromCropRect = fromCropRect;
            this.toCropRect = toCropRect;
            this.blend = clamp01(blend);
            this.darkBackground = darkBackground;

            if (surfaceReady) {
                syncBitmapTexturesOnGlThread(fromBitmap, toBitmap, videoThumbBitmap);
                renderFrameOnGlThread();
            } else {
                this.lastFromBitmap = fromBitmap;
                this.lastToBitmap = toBitmap;
                this.lastVideoThumbBitmap = videoThumbBitmap;
            }
        });
    }

    public void setBlend(final float blend, final boolean darkBackground) {
        if (released) {
            return;
        }
        postToGl(() -> {
            this.blend = clamp01(blend);
            this.darkBackground = darkBackground;
            renderFrameOnGlThread();
        });
    }

    public void requestRender() {
        if (released) {
            return;
        }
        postToGl(this::renderFrameOnGlThread);
    }

    public void recycleBitmapSafely(final Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        if (released || !glThread.isAlive()) {
            bitmap.recycle();
            return;
        }
        glHandler.post(() -> {
            if (lastFromBitmap == bitmap) {
                lastFromBitmap = null;
            }
            if (lastToBitmap == bitmap) {
                lastToBitmap = null;
            }
            if (lastVideoThumbBitmap == bitmap) {
                lastVideoThumbBitmap = null;
            }
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        });
    }

    public void detachSurface() {
        if (released || !glThread.isAlive()) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = glHandler.post(() -> {
            try {
                detachSurfaceOnGlThread();
            } finally {
                latch.countDown();
            }
        });
        if (posted) {
            try {
                if (!latch.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    AppLogger.w(TAG, "等待 GL 线程销毁 Surface 超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = glHandler.post(() -> {
            try {
                detachSurfaceOnGlThread();
                lastFromBitmap = null;
                lastToBitmap = null;
                lastVideoThumbBitmap = null;
            } finally {
                latch.countDown();
            }
        });
        if (posted) {
            try {
                latch.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        glThread.quitSafely();
    }

    private void postToGl(Runnable task) {
        if (!released && glThread.isAlive()) {
            glHandler.post(task);
        }
    }

    private void initEglOnGlThread(SurfaceHolder holder) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay 失败");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }

        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new IllegalStateException("eglChooseConfig 失败");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }

        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, holder, surfaceAttribs, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("eglMakeCurrent 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    private void initProgramsAndVideoSurfaceOnGlThread() {
        program2D = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D);
        programOes = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        videoTextureId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        Matrix.setIdentityM(videoTexMatrix, 0);
        surfaceTexture = new SurfaceTexture(videoTextureId);
        surfaceTexture.setOnFrameAvailableListener(st -> {
            if (!surfaceReady || surfaceTexture == null) {
                return;
            }
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(videoTexMatrix);
            } catch (Throwable t) {
                AppLogger.e(TAG, "SurfaceTexture 更新视频帧异常", t);
                return;
            }
            if (renderingEnabled) {
                renderFrameOnGlThread();
            }
        }, glHandler);
        videoSurface = new Surface(surfaceTexture);
    }

    private void detachSurfaceOnGlThread() {
        surfaceReady = false;
        if (videoSurface != null) {
            try {
                videoSurface.release();
            } catch (Throwable ignored) {
            }
            videoSurface = null;
        }
        if (surfaceTexture != null) {
            try {
                surfaceTexture.setOnFrameAvailableListener(null);
                surfaceTexture.release();
            } catch (Throwable ignored) {
            }
            surfaceTexture = null;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            deleteTextureIfValid(videoTextureId);
            deleteTextureIfValid(fromTexId);
            deleteTextureIfValid(toTexId);
            deleteTextureIfValid(videoThumbTexId);
            if (program2D != null) {
                program2D.release();
                program2D = null;
            }
            if (programOes != null) {
                programOes.release();
                programOes = null;
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }

        videoTextureId = 0;
        fromTexId = 0;
        fromTexW = 0;
        fromTexH = 0;
        lastFromBitmap = null;

        toTexId = 0;
        toTexW = 0;
        toTexH = 0;
        lastToBitmap = null;

        videoThumbTexId = 0;
        videoThumbW = 0;
        videoThumbH = 0;
        lastVideoThumbBitmap = null;

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        eglConfig = null;
    }

    private void syncBitmapTexturesOnGlThread(Bitmap fromBitmap, Bitmap toBitmap, Bitmap videoThumbBitmap) {
        if (fromBitmap != lastFromBitmap) {
            if (fromBitmap != null && !fromBitmap.isRecycled()) {
                fromTexId = uploadBitmapTexture(fromTexId, fromBitmap);
                fromTexW = fromBitmap.getWidth();
                fromTexH = fromBitmap.getHeight();
                lastFromBitmap = fromBitmap;
            } else {
                deleteTextureIfValid(fromTexId);
                fromTexId = 0;
                fromTexW = 0;
                fromTexH = 0;
                lastFromBitmap = null;
            }
        }
        if (toBitmap != lastToBitmap) {
            if (toBitmap != null && !toBitmap.isRecycled()) {
                toTexId = uploadBitmapTexture(toTexId, toBitmap);
                toTexW = toBitmap.getWidth();
                toTexH = toBitmap.getHeight();
                lastToBitmap = toBitmap;
            } else {
                deleteTextureIfValid(toTexId);
                toTexId = 0;
                toTexW = 0;
                toTexH = 0;
                lastToBitmap = null;
            }
        }
        if (videoThumbBitmap != lastVideoThumbBitmap) {
            if (videoThumbBitmap != null && !videoThumbBitmap.isRecycled()) {
                videoThumbTexId = uploadBitmapTexture(videoThumbTexId, videoThumbBitmap);
                videoThumbW = videoThumbBitmap.getWidth();
                videoThumbH = videoThumbBitmap.getHeight();
                lastVideoThumbBitmap = videoThumbBitmap;
            } else {
                deleteTextureIfValid(videoThumbTexId);
                videoThumbTexId = 0;
                videoThumbW = 0;
                videoThumbH = 0;
                lastVideoThumbBitmap = null;
            }
        }
    }

    private void renderFrameOnGlThread() {
        if (!surfaceReady || !renderingEnabled || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            AppLogger.e(TAG, "eglMakeCurrent 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return;
        }

        int viewW = Math.max(1, surfaceWidth);
        int viewH = Math.max(1, surfaceHeight);
        GLES20.glViewport(0, 0, viewW, viewH);
        if (darkBackground) {
            GLES20.glClearColor(0x0D / 255f, 0x11 / 255f, 0x17 / 255f, 1f);
        } else {
            GLES20.glClearColor(0xEE / 255f, 0xF2 / 255f, 0xF7 / 255f, 1f);
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        if (fromKind != LayerKind.NONE && blend < 1f) {
            drawLayerOnGlThread(fromKind, fromTexId, fromTexW, fromTexH, fromCropRect, 1f, viewW, viewH);
        }
        if (toKind != LayerKind.NONE && blend > 0f) {
            drawLayerOnGlThread(toKind, toTexId, toTexW, toTexH, toCropRect, blend, viewW, viewH);
        }

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            int err = EGL14.eglGetError();
            if (err != EGL14.EGL_SUCCESS) {
                AppLogger.e(TAG, "eglSwapBuffers 失败: 0x" + Integer.toHexString(err));
            }
        }
    }

    private void drawLayerOnGlThread(LayerKind kind,
                                     int bitmapTexId,
                                     int bitmapW,
                                     int bitmapH,
                                     CropRect cropRect,
                                     float alpha,
                                     int viewW,
                                     int viewH) {
        if (alpha <= 0.001f) {
            return;
        }
        if (kind == LayerKind.BITMAP) {
            if (bitmapTexId != 0 && bitmapW > 0 && bitmapH > 0) {
                draw2DTexture(bitmapTexId, bitmapW, bitmapH, cropRect, alpha, viewW, viewH);
            }
            return;
        }
        if (kind == LayerKind.VIDEO) {
            if (videoFirstFrameRendered && videoTextureId != 0 && videoWidth > 0 && videoHeight > 0) {
                drawOesTexture(videoTextureId, videoWidth, videoHeight,
                        videoPixelAspectRatio, videoUnappliedRotation, alpha, viewW, viewH);
            } else if (videoThumbTexId != 0 && videoThumbW > 0 && videoThumbH > 0) {
                draw2DTexture(videoThumbTexId, videoThumbW, videoThumbH, null, alpha, viewW, viewH);
            }
        }
    }

    private void draw2DTexture(int texId,
                               int contentW,
                               int contentH,
                               CropRect cropRect,
                               float alpha,
                               int viewW,
                               int viewH) {
        if (program2D == null) {
            return;
        }
        computeCroppedCenterCropScale(viewW, viewH, contentW, contentH, cropRect, tempScale);
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.scaleM(mvpMatrix, 0, tempScale[0], tempScale[1], 1f);
        float[] texMat;
        if (cropRect != null) {
            computeBitmapTexMatrix(cropRect, tempTexMatrix);
            texMat = tempTexMatrix;
        } else {
            texMat = bitmapTexMatrix;
        }
        drawQuad(program2D, GLES20.GL_TEXTURE_2D, texId, mvpMatrix, texMat, alpha);
    }

    private void drawOesTexture(int texId,
                                int contentW,
                                int contentH,
                                float pixelAspectRatio,
                                int unappliedRotation,
                                float alpha,
                                int viewW,
                                int viewH) {
        if (programOes == null) {
            return;
        }
        computeCenterCropScale(viewW, viewH, contentW, contentH, pixelAspectRatio, unappliedRotation, tempScale);
        Matrix.setIdentityM(mvpMatrix, 0);
        Matrix.scaleM(mvpMatrix, 0, tempScale[0], tempScale[1], 1f);
        int rot = ((unappliedRotation % 360) + 360) % 360;
        if (rot != 0) {
            Matrix.rotateM(mvpMatrix, 0, -rot, 0f, 0f, 1f);
        }
        drawQuad(programOes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId, mvpMatrix, videoTexMatrix, alpha);
    }

    private void drawQuad(GlProgram program,
                          int texTarget,
                          int texId,
                          float[] mvp,
                          float[] texMat,
                          float alpha) {
        GLES20.glUseProgram(program.programId);

        GLES20.glUniformMatrix4fv(program.uMvpMatrix, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(program.uTexMatrix, 1, false, texMat, 0);
        GLES20.glUniform1f(program.uAlpha, alpha);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(texTarget, texId);
        GLES20.glUniform1i(program.uTexture, 0);

        posBuffer.position(0);
        GLES20.glEnableVertexAttribArray(program.aPosition);
        GLES20.glVertexAttribPointer(program.aPosition, 2, GLES20.GL_FLOAT, false, 0, posBuffer);

        texBuffer.position(0);
        GLES20.glEnableVertexAttribArray(program.aTexCoord);
        GLES20.glVertexAttribPointer(program.aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(program.aPosition);
        GLES20.glDisableVertexAttribArray(program.aTexCoord);
        GLES20.glBindTexture(texTarget, 0);
        GLES20.glUseProgram(0);
    }

    private static int uploadBitmapTexture(int existingTexId, Bitmap bitmap) {
        int texId = existingTexId;
        if (texId == 0) {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            texId = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return texId;
    }

    private static void deleteTextureIfValid(int texId) {
        if (texId != 0) {
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
        }
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    private static final class GlProgram {
        final int programId;
        final int aPosition;
        final int aTexCoord;
        final int uMvpMatrix;
        final int uTexMatrix;
        final int uTexture;
        final int uAlpha;

        GlProgram(String vertexSrc, String fragmentSrc) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
            int prog = GLES20.glCreateProgram();
            if (prog == 0) {
                GLES20.glDeleteShader(vs);
                GLES20.glDeleteShader(fs);
                throw new IllegalStateException("glCreateProgram 失败");
            }
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (linkStatus[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(prog);
                GLES20.glDeleteProgram(prog);
                throw new IllegalStateException("GL Program 链接失败: " + log);
            }
            this.programId = prog;
            this.aPosition = GLES20.glGetAttribLocation(prog, "aPosition");
            this.aTexCoord = GLES20.glGetAttribLocation(prog, "aTexCoord");
            this.uMvpMatrix = GLES20.glGetUniformLocation(prog, "uMvpMatrix");
            this.uTexMatrix = GLES20.glGetUniformLocation(prog, "uTexMatrix");
            this.uTexture = GLES20.glGetUniformLocation(prog, "uTexture");
            this.uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha");
        }

        void release() {
            if (programId != 0) {
                GLES20.glDeleteProgram(programId);
            }
        }

        private static int compileShader(int type, String src) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                throw new IllegalStateException("glCreateShader 失败: type=" + type);
            }
            GLES20.glShaderSource(shader, src);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Shader 编译失败: " + log);
            }
            return shader;
        }
    }
}
