package com.byd.autodarkwallpaper.service;

import static org.junit.Assert.assertEquals;

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
}
