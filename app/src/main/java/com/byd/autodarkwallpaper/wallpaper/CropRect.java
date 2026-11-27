package com.sunr1se.byd.autowallpaper.wallpaper;

import android.graphics.Rect;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 归一化无损裁剪区域（坐标系以原图左上角为 (0,0)、右下角为 (1,1)）。
 */
public final class CropRect {

    public static final float MIN_SPAN = 0.05f;
    public static final CropRect FULL = new CropRect(0f, 0f, 1f, 1f);

    private static final float CLAMP_EPS = 1e-5f;
    private static final float MIN_JSON_SPAN = 0.01f;

    public final float left;
    public final float top;
    public final float right;
    public final float bottom;

    public CropRect(float left, float top, float right, float bottom) {
        if (!isFinite(left) || !isFinite(top) || !isFinite(right) || !isFinite(bottom)) {
            throw new IllegalArgumentException("CropRect coordinates must be finite");
        }
        float l = clampUnitWithEps(left);
        float t = clampUnitWithEps(top);
        float r = clampUnitWithEps(right);
        float b = clampUnitWithEps(bottom);
        if (l < 0f || l > 1f || t < 0f || t > 1f || r < 0f || r > 1f || b < 0f || b > 1f) {
            throw new IllegalArgumentException("CropRect coordinates out of [0,1]: "
                    + left + "," + top + "," + right + "," + bottom);
        }
        if (l >= r || t >= b) {
            throw new IllegalArgumentException("CropRect must satisfy left < right and top < bottom");
        }
        this.left = l;
        this.top = t;
        this.right = r;
        this.bottom = b;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }

    public float centerX() {
        return (left + right) * 0.5f;
    }

    public float centerY() {
        return (top + bottom) * 0.5f;
    }

    /**
     * 计算指定图片在目标宽高比下的默认居中裁剪区域。
     */
    public static CropRect computeDefaultCenterCrop(int imageW, int imageH, float targetAspect) {
        if (imageW <= 0 || imageH <= 0 || !isFinite(targetAspect) || targetAspect <= 0f) {
            return FULL;
        }
        float imageAspect = (float) imageW / (float) imageH;
        if (Math.abs(imageAspect - targetAspect) <= 1e-4f) {
            return FULL;
        }
        if (imageAspect > targetAspect) {
            float normW = clampUnitWithEps(targetAspect / imageAspect);
            float left = (1f - normW) * 0.5f;
            return new CropRect(left, 0f, left + normW, 1f);
        } else {
            float normH = clampUnitWithEps(imageAspect / targetAspect);
            float top = (1f - normH) * 0.5f;
            return new CropRect(0f, top, 1f, top + normH);
        }
    }

    public boolean isApproximatelyEqual(CropRect other, float eps) {
        if (other == null) {
            return false;
        }
        return Math.abs(left - other.left) <= eps
                && Math.abs(top - other.top) <= eps
                && Math.abs(right - other.right) <= eps
                && Math.abs(bottom - other.bottom) <= eps;
    }

    public boolean isDefaultCenterCrop(int imageW, int imageH, float targetAspect, float eps) {
        CropRect def = computeDefaultCenterCrop(imageW, imageH, targetAspect);
        return isApproximatelyEqual(def, eps);
    }

    /**
     * 映射为指定原图尺寸下的像素裁剪矩形（严格夹紧在 [0, imageW] × [0, imageH]，且宽高至少为 1）。
     */
    public Rect toPixelRect(int imageW, int imageH) {
        if (imageW <= 0 || imageH <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        int pxLeft = Math.max(0, Math.min(imageW - 1, Math.round(left * imageW)));
        int pxTop = Math.max(0, Math.min(imageH - 1, Math.round(top * imageH)));
        int pxRight = Math.max(pxLeft + 1, Math.min(imageW, Math.round(right * imageW)));
        int pxBottom = Math.max(pxTop + 1, Math.min(imageH, Math.round(bottom * imageH)));
        return new Rect(pxLeft, pxTop, pxRight, pxBottom);
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("left", (double) left);
        obj.put("top", (double) top);
        obj.put("right", (double) right);
        obj.put("bottom", (double) bottom);
        return obj;
    }

    public static CropRect fromJsonObject(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        if (!obj.has("left") || !obj.has("top") || !obj.has("right") || !obj.has("bottom")) {
            return null;
        }
        double l = obj.optDouble("left", Double.NaN);
        double t = obj.optDouble("top", Double.NaN);
        double r = obj.optDouble("right", Double.NaN);
        double b = obj.optDouble("bottom", Double.NaN);
        if (Double.isNaN(l) || Double.isNaN(t) || Double.isNaN(r) || Double.isNaN(b)
                || Double.isInfinite(l) || Double.isInfinite(t)
                || Double.isInfinite(r) || Double.isInfinite(b)) {
            return null;
        }
        if (l < -CLAMP_EPS || t < -CLAMP_EPS || r > 1.0 + CLAMP_EPS || b > 1.0 + CLAMP_EPS) {
            return null;
        }
        float fl = clampUnitWithEps((float) l);
        float ft = clampUnitWithEps((float) t);
        float fr = clampUnitWithEps((float) r);
        float fb = clampUnitWithEps((float) b);
        if (fr - fl < MIN_JSON_SPAN || fb - ft < MIN_JSON_SPAN) {
            return null;
        }
        return new CropRect(fl, ft, fr, fb);
    }

    public String toCacheKey() {
        return String.format(Locale.US, "%.4f_%.4f_%.4f_%.4f", left, top, right, bottom);
    }

    public String toShortString() {
        return String.format(Locale.US, "[%.3f,%.3f,%.3f,%.3f]", left, top, right, bottom);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CropRect)) {
            return false;
        }
        CropRect other = (CropRect) o;
        return Float.compare(other.left, left) == 0
                && Float.compare(other.top, top) == 0
                && Float.compare(other.right, right) == 0
                && Float.compare(other.bottom, bottom) == 0;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(left);
        result = 31 * result + Float.floatToIntBits(top);
        result = 31 * result + Float.floatToIntBits(right);
        result = 31 * result + Float.floatToIntBits(bottom);
        return result;
    }

    @Override
    public String toString() {
        return "CropRect" + toShortString();
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clampUnitWithEps(float v) {
        if (v < 0f && v >= -CLAMP_EPS) {
            return 0f;
        }
        if (v > 1f && v <= 1f + CLAMP_EPS) {
            return 1f;
        }
        return v;
    }
}
