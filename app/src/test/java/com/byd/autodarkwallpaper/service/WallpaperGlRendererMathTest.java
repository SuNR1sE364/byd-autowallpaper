package com.sunr1se.byd.autowallpaper.service;

import static org.junit.Assert.assertEquals;

import com.sunr1se.byd.autowallpaper.wallpaper.CropRect;

import org.junit.Test;

public class WallpaperGlRendererMathTest {

    private static final float EPS = 1e-4f;

    @Test
    public void matchingAspectRatio_returnsUnitScale() {
        float[] scale = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 1920, 1080, 1f, 0, scale);
        assertEquals(1f, scale[0], EPS);
        assertEquals(1f, scale[1], EPS);
    }

    @Test
    public void widerContent_cropsHorizontally() {
        float[] scale = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 3840, 1080, 1f, 0, scale);
        assertEquals(2f, scale[0], EPS);
        assertEquals(1f, scale[1], EPS);
    }

    @Test
    public void tallerContent_cropsVertically() {
        float[] scale = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 1080, 1920, 1f, 0, scale);
        assertEquals(1f, scale[0], EPS);
        assertEquals((16f / 9f) / (9f / 16f), scale[1], EPS);
    }

    @Test
    public void unappliedRotation90And270_swapsEffectiveDimensions() {
        float[] scale90 = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 1080, 1920, 1f, 90, scale90);
        assertEquals(1f, scale90[0], EPS);
        assertEquals(1f, scale90[1], EPS);

        float[] scale270 = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 1080, 2560, 1f, 270, scale270);
        assertEquals(2560f / 1920f, scale270[0], EPS);
        assertEquals(1f, scale270[1], EPS);
    }

    @Test
    public void pixelAspectRatio_isRespected() {
        float[] scale = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 1440, 1080, 4f / 3f, 0, scale);
        assertEquals(1f, scale[0], EPS);
        assertEquals(1f, scale[1], EPS);
    }

    @Test
    public void invalidDimensions_fallBackToUnitScale() {
        float[] scale = new float[2];
        WallpaperGlRenderer.computeCenterCropScale(0, 1080, 1920, 1080, 1f, 0, scale);
        assertEquals(1f, scale[0], EPS);
        assertEquals(1f, scale[1], EPS);

        WallpaperGlRenderer.computeCenterCropScale(1920, 1080, 0, 0, 1f, 0, scale);
        assertEquals(1f, scale[0], EPS);
        assertEquals(1f, scale[1], EPS);
    }

    @Test
    public void computeBitmapTexMatrix_nullAndCustomCropMapCornersCorrectly() {
        float[] fullMat = new float[16];
        WallpaperGlRenderer.computeBitmapTexMatrix(null, fullMat);
        // 底边 q=0 -> t=1, 顶边 q=1 -> t=0
        assertTexTransform(fullMat, 0f, 0f, 0f, 1f);
        assertTexTransform(fullMat, 1f, 1f, 1f, 0f);

        float[] cropMat = new float[16];
        CropRect crop = new CropRect(0.2f, 0.1f, 0.8f, 0.5f);
        WallpaperGlRenderer.computeBitmapTexMatrix(crop, cropMat);
        // 全屏左下角 (0, 0) -> (left, bottom); 全屏右上角 (1, 1) -> (right, top)
        assertTexTransform(cropMat, 0f, 0f, 0.2f, 0.5f);
        assertTexTransform(cropMat, 1f, 0f, 0.8f, 0.5f);
        assertTexTransform(cropMat, 0f, 1f, 0.2f, 0.1f);
        assertTexTransform(cropMat, 1f, 1f, 0.8f, 0.1f);
    }

    @Test
    public void computeCroppedCenterCropScale_usesSubImageAspectRatio() {
        float[] scale = new float[2];
        // 1080x1920 竖图按 16:9 裁剪后子图宽高为 1080 x 607.5，与 1920x1080 屏幕匹配，应返回接近 [1, 1]
        CropRect portrait16x9 = CropRect.computeDefaultCenterCrop(1080, 1920, 16f / 9f);
        WallpaperGlRenderer.computeCroppedCenterCropScale(1920, 1080, 1080, 1920, portrait16x9, scale);
        assertEquals(1f, scale[0], 1e-3f);
        assertEquals(1f, scale[1], 1e-3f);
    }

    private static void assertTexTransform(float[] m, float u, float v, float expectedS, float expectedT) {
        // 列主序 4x4 矩阵乘法: out = M * [u, v, 0, 1]^T
        float s = m[0] * u + m[4] * v + m[12];
        float t = m[1] * u + m[5] * v + m[13];
        assertEquals(expectedS, s, EPS);
        assertEquals(expectedT, t, EPS);
    }
}
