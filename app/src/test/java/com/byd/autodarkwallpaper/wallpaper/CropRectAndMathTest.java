package com.sunr1se.byd.autowallpaper.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.graphics.RectF;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CropRectAndMathTest {

    private static final float EPS = 1e-4f;

    @Test
    public void computeDefaultCenterCrop_portraitImageOn16x9_cropsVerticallyCentered() {
        CropRect rect = CropRect.computeDefaultCenterCrop(1080, 1920, 16f / 9f);
        assertEquals(0f, rect.left, EPS);
        assertEquals(1f, rect.right, EPS);
        float expectedH = (9f / 16f) / (16f / 9f);
        assertEquals(expectedH, rect.height(), EPS);
        assertEquals(0.5f, rect.centerY(), EPS);
    }

    @Test
    public void computeDefaultCenterCrop_ultraWideImageOn16x9_cropsHorizontallyCentered() {
        CropRect rect = CropRect.computeDefaultCenterCrop(3840, 1080, 16f / 9f);
        assertEquals(0f, rect.top, EPS);
        assertEquals(1f, rect.bottom, EPS);
        assertEquals(0.5f, rect.width(), EPS);
        assertEquals(0.5f, rect.centerX(), EPS);
    }

    @Test
    public void computeDefaultCenterCrop_matchingAspect_returnsFull() {
        CropRect rect = CropRect.computeDefaultCenterCrop(1920, 1080, 16f / 9f);
        assertEquals(CropRect.FULL, rect);
        assertTrue(rect.isDefaultCenterCrop(1920, 1080, 16f / 9f, 1e-3f));
    }

    @Test
    public void jsonSerialization_roundTripsAndRejectsInvalidValues() throws Exception {
        CropRect original = new CropRect(0.1f, 0.2f, 0.8f, 0.7f);
        JSONObject json = original.toJsonObject();
        CropRect restored = CropRect.fromJsonObject(json);
        assertNotNull(restored);
        assertTrue(original.isApproximatelyEqual(restored, 1e-5f));

        assertNull(CropRect.fromJsonObject(null));
        JSONObject outOfRange = new JSONObject("{\"left\":-0.2,\"top\":0,\"right\":0.5,\"bottom\":0.5}");
        assertNull(CropRect.fromJsonObject(outOfRange));

        JSONObject tooSmall = new JSONObject("{\"left\":0.4,\"top\":0.4,\"right\":0.405,\"bottom\":0.5}");
        assertNull(CropRect.fromJsonObject(tooSmall));

        JSONObject inverted = new JSONObject("{\"left\":0.6,\"top\":0.2,\"right\":0.3,\"bottom\":0.8}");
        assertNull(CropRect.fromJsonObject(inverted));
    }

    @Test
    public void toPixelRect_clampsToImageBounds() {
        CropRect edge = new CropRect(0.9999f, 0.9999f, 1f, 1f);
        Rect px = edge.toPixelRect(100, 100);
        assertTrue(px.left >= 0 && px.left < 100);
        assertTrue(px.top >= 0 && px.top < 100);
        assertEquals(100, px.right);
        assertEquals(100, px.bottom);
        assertTrue(px.width() >= 1);
        assertTrue(px.height() >= 1);
    }

    @Test
    public void panCropRect_clampsWithinImageBounds() {
        float[] baseWH = new float[2];
        WallpaperCropMath.computeBaseNormalizedSize(1080, 1920, 16f / 9f, baseWH);
        CropRect initial = CropRect.computeDefaultCenterCrop(1080, 1920, 16f / 9f);

        // 向下拖动视图（dyView > 0）应使取景框上移到图片顶部（top = 0）
        CropRect movedUp = WallpaperCropMath.panCropRect(
                initial, 0f, 5000f, 800f, 450f, baseWH[0], baseWH[1]);
        assertEquals(0f, movedUp.top, EPS);
        assertEquals(baseWH[1], movedUp.height(), EPS);

        // 向上拖动视图（dyView < 0）应使取景框下移到底部（bottom = 1）
        CropRect movedDown = WallpaperCropMath.panCropRect(
                initial, 0f, -5000f, 800f, 450f, baseWH[0], baseWH[1]);
        assertEquals(1f, movedDown.bottom, EPS);
        assertEquals(baseWH[1], movedDown.height(), EPS);
    }

    @Test
    public void zoomCropRect_preservesFocusPointAndRespectsMaxZoom() {
        float[] baseWH = new float[2];
        WallpaperCropMath.computeBaseNormalizedSize(1920, 1080, 16f / 9f, baseWH);
        CropRect initial = CropRect.FULL;

        float focusU = 0.25f;
        float focusV = 0.75f;
        float imgFocusXBefore = initial.left + focusU * initial.width();
        float imgFocusYBefore = initial.top + focusV * initial.height();

        CropRect zoomed = WallpaperCropMath.zoomCropRect(
                initial, 2f, focusU, focusV, baseWH[0], baseWH[1]);
        assertEquals(0.5f, zoomed.width(), EPS);
        assertEquals(0.5f, zoomed.height(), EPS);

        float imgFocusXAfter = zoomed.left + focusU * zoomed.width();
        float imgFocusYAfter = zoomed.top + focusV * zoomed.height();
        assertEquals(imgFocusXBefore, imgFocusXAfter, 1e-3f);
        assertEquals(imgFocusYBefore, imgFocusYAfter, 1e-3f);

        // 超过最大缩放倍率时应被限制在 maxZoom 内
        float maxZoom = WallpaperCropMath.computeMaxZoom(baseWH[0], baseWH[1]);
        CropRect overZoomed = WallpaperCropMath.zoomCropRect(
                initial, 100f, 0.5f, 0.5f, baseWH[0], baseWH[1]);
        float[] state = new float[3];
        WallpaperCropMath.extractCenterAndZoom(overZoomed, baseWH[0], baseWH[1], state);
        assertEquals(maxZoom, state[2], 1e-3f);
    }

    @Test
    public void extractCenterAndZoom_roundTripsWithBuildCropRect() {
        float[] baseWH = new float[2];
        WallpaperCropMath.computeBaseNormalizedSize(1080, 1920, 16f / 9f, baseWH);

        CropRect built = WallpaperCropMath.buildCropRectFromCenterAndZoom(
                0.35f, 0.62f, 1.75f, baseWH[0], baseWH[1]);
        float[] extracted = new float[3];
        WallpaperCropMath.extractCenterAndZoom(built, baseWH[0], baseWH[1], extracted);

        assertEquals(built.centerX(), extracted[0], 1e-4f);
        assertEquals(built.centerY(), extracted[1], 1e-4f);
        assertEquals(1.75f, extracted[2], 1e-3f);
        assertFalse(built.isDefaultCenterCrop(1080, 1920, 16f / 9f, 1e-3f));

        RectF vp = new RectF();
        WallpaperCropMath.computeViewportRect(800, 400, 16, 16, 16f / 9f, vp);
        assertEquals(16f / 9f, vp.width() / vp.height(), 1e-3f);
    }
}
