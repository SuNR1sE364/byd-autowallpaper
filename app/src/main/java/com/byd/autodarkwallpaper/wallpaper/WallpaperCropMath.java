package com.sunr1se.byd.autowallpaper.wallpaper;

import android.graphics.RectF;

/**
 * 静态壁纸取景裁剪纯数学计算器（平移、焦点缩放、视口适配）。
 */
public final class WallpaperCropMath {

    private WallpaperCropMath() {
    }

    /**
     * 计算 1.0x 缩放时匹配 targetAspect 的最大归一化宽高 [baseNormW, baseNormH]。
     */
    public static void computeBaseNormalizedSize(int imgW, int imgH, float targetAspect, float[] outBaseWH) {
        if (outBaseWH == null || outBaseWH.length < 2) {
            throw new IllegalArgumentException("outBaseWH length must be >= 2");
        }
        CropRect base = CropRect.computeDefaultCenterCrop(imgW, imgH, targetAspect);
        outBaseWH[0] = base.width();
        outBaseWH[1] = base.height();
    }

    /**
     * 计算允许的最大缩放倍率（至多 4.0x，且保证裁剪跨度不低于 CropRect.MIN_SPAN）。
     */
    public static float computeMaxZoom(float baseNormW, float baseNormH) {
        float minBase = Math.min(baseNormW, baseNormH);
        if (minBase <= 0f || Float.isNaN(minBase) || Float.isInfinite(minBase)) {
            return 1f;
        }
        return Math.max(1f, Math.min(4f, minBase / CropRect.MIN_SPAN));
    }

    /**
     * 由归一化中心点与缩放倍率构建并夹紧边界后的 CropRect。
     */
    public static CropRect buildCropRectFromCenterAndZoom(float cx,
                                                         float cy,
                                                         float zoom,
                                                         float baseNormW,
                                                         float baseNormH) {
        float maxZoom = computeMaxZoom(baseNormW, baseNormH);
        float safeZoom = clamp(zoom, 1f, maxZoom);
        float normW = clamp(baseNormW / safeZoom, CropRect.MIN_SPAN, 1f);
        float normH = clamp(baseNormH / safeZoom, CropRect.MIN_SPAN, 1f);

        float halfW = normW * 0.5f;
        float halfH = normH * 0.5f;
        float safeCx = clamp(cx, halfW, 1f - halfW);
        float safeCy = clamp(cy, halfH, 1f - halfH);

        float left = clamp(safeCx - halfW, 0f, 1f - normW);
        float top = clamp(safeCy - halfH, 0f, 1f - normH);
        return new CropRect(left, top, left + normW, top + normH);
    }

    /**
     * 从已有 CropRect 反推 [cx, cy, zoom]；rect 为 null 时返回默认居中 [0.5, 0.5, 1.0]。
     */
    public static void extractCenterAndZoom(CropRect rect,
                                            float baseNormW,
                                            float baseNormH,
                                            float[] outCxCyZoom) {
        if (outCxCyZoom == null || outCxCyZoom.length < 3) {
            throw new IllegalArgumentException("outCxCyZoom length must be >= 3");
        }
        if (rect == null || baseNormW <= 0f || baseNormH <= 0f) {
            outCxCyZoom[0] = 0.5f;
            outCxCyZoom[1] = 0.5f;
            outCxCyZoom[2] = 1f;
            return;
        }
        float maxZoom = computeMaxZoom(baseNormW, baseNormH);
        float zoomW = baseNormW / Math.max(1e-5f, rect.width());
        float zoomH = baseNormH / Math.max(1e-5f, rect.height());
        float zoom = clamp((zoomW + zoomH) * 0.5f, 1f, maxZoom);
        outCxCyZoom[0] = rect.centerX();
        outCxCyZoom[1] = rect.centerY();
        outCxCyZoom[2] = zoom;
    }

    /**
     * 将视图手势位移（像素）转换为裁剪区域平移并夹紧边界。
     */
    public static CropRect panCropRect(CropRect current,
                                       float dxView,
                                       float dyView,
                                       float vpWidth,
                                       float vpHeight,
                                       float baseNormW,
                                       float baseNormH) {
        CropRect active = current != null ? current : buildCropRectFromCenterAndZoom(0.5f, 0.5f, 1f, baseNormW, baseNormH);
        if (vpWidth <= 0f || vpHeight <= 0f) {
            return active;
        }
        float[] state = new float[3];
        extractCenterAndZoom(active, baseNormW, baseNormH, state);
        float zoom = state[2];
        float normW = baseNormW / zoom;
        float normH = baseNormH / zoom;
        float deltaCx = -(dxView / vpWidth) * normW;
        float deltaCy = -(dyView / vpHeight) * normH;
        return buildCropRectFromCenterAndZoom(state[0] + deltaCx, state[1] + deltaCy, zoom, baseNormW, baseNormH);
    }

    /**
     * 围绕视口归一化焦点 (focusU, focusV) ∈ [0,1]² 执行缩放，保持焦点下图像不漂移。
     */
    public static CropRect zoomCropRect(CropRect current,
                                        float scaleFactor,
                                        float focusU,
                                        float focusV,
                                        float baseNormW,
                                        float baseNormH) {
        CropRect active = current != null ? current : buildCropRectFromCenterAndZoom(0.5f, 0.5f, 1f, baseNormW, baseNormH);
        if (Float.isNaN(scaleFactor) || Float.isInfinite(scaleFactor) || scaleFactor <= 0f) {
            return active;
        }
        float u = clamp(focusU, 0f, 1f);
        float v = clamp(focusV, 0f, 1f);

        float[] state = new float[3];
        extractCenterAndZoom(active, baseNormW, baseNormH, state);
        float oldZoom = state[2];
        float maxZoom = computeMaxZoom(baseNormW, baseNormH);
        float newZoom = clamp(oldZoom * scaleFactor, 1f, maxZoom);

        float oldW = baseNormW / oldZoom;
        float oldH = baseNormH / oldZoom;
        float imgFocusX = active.left + u * oldW;
        float imgFocusY = active.top + v * oldH;

        float newW = baseNormW / newZoom;
        float newH = baseNormH / newZoom;
        float newLeft = imgFocusX - u * newW;
        float newTop = imgFocusY - v * newH;
        return buildCropRectFromCenterAndZoom(newLeft + newW * 0.5f, newTop + newH * 0.5f, newZoom, baseNormW, baseNormH);
    }

    /**
     * 在给定控件宽高和内边距内，计算居中且锁定 targetAspect 宽高比的取景视口矩形。
     */
    public static void computeViewportRect(int viewW,
                                           int viewH,
                                           int padX,
                                           int padY,
                                           float targetAspect,
                                           RectF outViewport) {
        if (outViewport == null) {
            return;
        }
        float availW = Math.max(1f, viewW - 2f * Math.max(0, padX));
        float availH = Math.max(1f, viewH - 2f * Math.max(0, padY));
        float aspect = (!Float.isNaN(targetAspect) && !Float.isInfinite(targetAspect) && targetAspect > 0f)
                ? targetAspect : (16f / 9f);

        float vpW;
        float vpH;
        if (availW / availH > aspect) {
            vpH = availH;
            vpW = vpH * aspect;
        } else {
            vpW = availW;
            vpH = vpW / aspect;
        }
        float left = (viewW - vpW) * 0.5f;
        float top = (viewH - vpH) * 0.5f;
        outViewport.set(left, top, left + vpW, top + vpH);
    }

    private static float clamp(float val, float min, float max) {
        if (Float.isNaN(val)) {
            return min;
        }
        if (min > max) {
            return min;
        }
        return Math.max(min, Math.min(max, val));
    }
}
