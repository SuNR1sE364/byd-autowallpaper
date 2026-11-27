package com.sunr1se.byd.autowallpaper.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.sunr1se.byd.autowallpaper.R;
import com.sunr1se.byd.autowallpaper.wallpaper.CropRect;
import com.sunr1se.byd.autowallpaper.wallpaper.WallpaperCropMath;

/**
 * 静态壁纸无损取景控件：
 * - interactive = true：锁定车机屏幕宽高比取景框，支持拖动与双指/滑块缩放。
 * - interactive = false：只读预览模式，展示完整原图并高亮实际桌面可见区域。
 */
public class WallpaperCropView extends View {

    public interface OnCropChangeListener {
        void onCropChanged(CropRect cropRect, float zoom, float maxZoom, boolean isDefaultCenter);
    }

    private static final float DEFAULT_CHECK_EPS = 0.005f;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint canvasBgPaint = new Paint();

    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RectF viewportRect = new RectF();
    private final RectF highlightRect = new RectF();
    private final float[] baseWH = new float[]{1f, 1f};
    private final float[] stateBuf = new float[]{0.5f, 0.5f, 1f};

    private final int padPx;
    private final float cornerLenPx;

    private Bitmap bitmap;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private float targetAspect = 16f / 9f;
    private CropRect currentCrop = CropRect.FULL;
    private boolean interactive = true;
    private OnCropChangeListener changeListener;

    private ScaleGestureDetector scaleDetector;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float lastTouchX;
    private float lastTouchY;
    private boolean isScaling = false;

    public WallpaperCropView(Context context) {
        this(context, null);
    }

    public WallpaperCropView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WallpaperCropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        padPx = Math.round(12f * density);
        cornerLenPx = 16f * density;

        canvasBgPaint.setColor(0xFF0B0F16);
        scrimPaint.setColor(0x99000000);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);
        borderPaint.setColor(0xE6FFFFFF);

        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(3f * density);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setColor(context.getColor(R.color.accent_primary));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(0x4DFFFFFF);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!interactive || bitmap == null || viewportRect.width() <= 0f || viewportRect.height() <= 0f) {
                    return false;
                }
                float focusU = (detector.getFocusX() - viewportRect.left) / viewportRect.width();
                float focusV = (detector.getFocusY() - viewportRect.top) / viewportRect.height();
                currentCrop = WallpaperCropMath.zoomCropRect(
                        currentCrop, detector.getScaleFactor(), focusU, focusV, baseWH[0], baseWH[1]);
                invalidate();
                notifyChanged();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
            }
        });
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
        invalidate();
    }

    public void setOnCropChangeListener(OnCropChangeListener listener) {
        this.changeListener = listener;
        if (listener != null && hasValidImage()) {
            notifyChanged();
        }
    }

    public void setImageAndCrop(Bitmap bitmap, float targetAspect, CropRect initialCrop) {
        int bw = bitmap != null ? bitmap.getWidth() : 0;
        int bh = bitmap != null ? bitmap.getHeight() : 0;
        setImageAndCrop(bitmap, bw, bh, targetAspect, initialCrop);
    }

    public void setImageAndCrop(Bitmap bitmap,
                                int sourceWidth,
                                int sourceHeight,
                                float targetAspect,
                                CropRect initialCrop) {
        this.bitmap = bitmap;
        this.sourceWidth = sourceWidth > 0 ? sourceWidth : (bitmap != null ? bitmap.getWidth() : 0);
        this.sourceHeight = sourceHeight > 0 ? sourceHeight : (bitmap != null ? bitmap.getHeight() : 0);
        this.targetAspect = (!Float.isNaN(targetAspect) && !Float.isInfinite(targetAspect) && targetAspect > 0f)
                ? targetAspect : (16f / 9f);

        if (this.sourceWidth > 0 && this.sourceHeight > 0) {
            WallpaperCropMath.computeBaseNormalizedSize(
                    this.sourceWidth, this.sourceHeight, this.targetAspect, baseWH);
            if (initialCrop != null) {
                WallpaperCropMath.extractCenterAndZoom(initialCrop, baseWH[0], baseWH[1], stateBuf);
                currentCrop = WallpaperCropMath.buildCropRectFromCenterAndZoom(
                        stateBuf[0], stateBuf[1], stateBuf[2], baseWH[0], baseWH[1]);
            } else {
                currentCrop = CropRect.computeDefaultCenterCrop(
                        this.sourceWidth, this.sourceHeight, this.targetAspect);
            }
        } else {
            baseWH[0] = 1f;
            baseWH[1] = 1f;
            currentCrop = CropRect.FULL;
        }
        invalidate();
        notifyChanged();
    }

    public CropRect getCurrentCropRect() {
        return currentCrop;
    }

    public boolean isAtDefaultCenter() {
        if (!hasValidImage()) {
            return true;
        }
        return currentCrop.isDefaultCenterCrop(sourceWidth, sourceHeight, targetAspect, DEFAULT_CHECK_EPS);
    }

    public void resetToDefaultCenter() {
        if (!hasValidImage()) {
            return;
        }
        currentCrop = CropRect.computeDefaultCenterCrop(sourceWidth, sourceHeight, targetAspect);
        invalidate();
        notifyChanged();
    }

    public float getZoom() {
        if (!hasValidImage()) {
            return 1f;
        }
        WallpaperCropMath.extractCenterAndZoom(currentCrop, baseWH[0], baseWH[1], stateBuf);
        return stateBuf[2];
    }

    public float getMaxZoom() {
        return WallpaperCropMath.computeMaxZoom(baseWH[0], baseWH[1]);
    }

    public void setZoomFromSlider(float zoom) {
        if (!interactive || !hasValidImage()) {
            return;
        }
        WallpaperCropMath.extractCenterAndZoom(currentCrop, baseWH[0], baseWH[1], stateBuf);
        currentCrop = WallpaperCropMath.buildCropRectFromCenterAndZoom(
                stateBuf[0], stateBuf[1], zoom, baseWH[0], baseWH[1]);
        invalidate();
        notifyChanged();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewportRect(w, h);
    }

    private void updateViewportRect(int w, int h) {
        if (interactive) {
            WallpaperCropMath.computeViewportRect(w, h, padPx, padPx, targetAspect, viewportRect);
        } else {
            viewportRect.set(0f, 0f, w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        canvas.drawRect(0f, 0f, viewW, viewH, canvasBgPaint);

        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return;
        }

        if (interactive) {
            drawInteractiveMode(canvas, viewW, viewH);
        } else {
            drawReadOnlyPreviewMode(canvas, viewW, viewH);
        }
    }

    private void drawInteractiveMode(Canvas canvas, int viewW, int viewH) {
        WallpaperCropMath.computeViewportRect(viewW, viewH, padPx, padPx, targetAspect, viewportRect);
        float vpW = viewportRect.width();
        float vpH = viewportRect.height();
        if (vpW <= 0f || vpH <= 0f) {
            return;
        }

        float drawW = vpW / Math.max(CropRect.MIN_SPAN, currentCrop.width());
        float drawH = vpH / Math.max(CropRect.MIN_SPAN, currentCrop.height());
        float drawLeft = viewportRect.left - currentCrop.left * drawW;
        float drawTop = viewportRect.top - currentCrop.top * drawH;

        srcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        dstRect.set(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH);
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint);

        drawScrimOutsideRect(canvas, 0f, 0f, viewW, viewH, viewportRect);
        drawGridLines(canvas, viewportRect);
        drawFrameAndCorners(canvas, viewportRect);
    }

    private void drawReadOnlyPreviewMode(Canvas canvas, int viewW, int viewH) {
        int margin = Math.round(padPx * 0.35f);
        float availW = Math.max(1f, viewW - 2f * margin);
        float availH = Math.max(1f, viewH - 2f * margin);
        float imgAspect = (float) bitmap.getWidth() / (float) bitmap.getHeight();
        float drawW;
        float drawH;
        if (availW / availH > imgAspect) {
            drawH = availH;
            drawW = drawH * imgAspect;
        } else {
            drawW = availW;
            drawH = drawW / imgAspect;
        }
        float drawLeft = (viewW - drawW) * 0.5f;
        float drawTop = (viewH - drawH) * 0.5f;

        srcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        dstRect.set(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH);
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint);

        highlightRect.set(
                drawLeft + currentCrop.left * drawW,
                drawTop + currentCrop.top * drawH,
                drawLeft + currentCrop.right * drawW,
                drawTop + currentCrop.bottom * drawH);
        drawScrimOutsideRect(canvas, dstRect.left, dstRect.top, dstRect.right, dstRect.bottom, highlightRect);
        drawFrameAndCorners(canvas, highlightRect);
    }

    private void drawScrimOutsideRect(Canvas canvas,
                                      float outerLeft,
                                      float outerTop,
                                      float outerRight,
                                      float outerBottom,
                                      RectF inner) {
        if (inner.top > outerTop) {
            canvas.drawRect(outerLeft, outerTop, outerRight, inner.top, scrimPaint);
        }
        if (inner.bottom < outerBottom) {
            canvas.drawRect(outerLeft, inner.bottom, outerRight, outerBottom, scrimPaint);
        }
        if (inner.left > outerLeft) {
            canvas.drawRect(outerLeft, inner.top, inner.left, inner.bottom, scrimPaint);
        }
        if (inner.right < outerRight) {
            canvas.drawRect(inner.right, inner.top, outerRight, inner.bottom, scrimPaint);
        }
    }

    private void drawGridLines(Canvas canvas, RectF rect) {
        float w = rect.width();
        float h = rect.height();
        float x1 = rect.left + w / 3f;
        float x2 = rect.left + w * 2f / 3f;
        float y1 = rect.top + h / 3f;
        float y2 = rect.top + h * 2f / 3f;
        canvas.drawLine(x1, rect.top, x1, rect.bottom, gridPaint);
        canvas.drawLine(x2, rect.top, x2, rect.bottom, gridPaint);
        canvas.drawLine(rect.left, y1, rect.right, y1, gridPaint);
        canvas.drawLine(rect.left, y2, rect.right, y2, gridPaint);
    }

    private void drawFrameAndCorners(Canvas canvas, RectF rect) {
        canvas.drawRect(rect, borderPaint);
        float len = Math.min(cornerLenPx, Math.min(rect.width(), rect.height()) * 0.35f);
        // 左上角
        canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, cornerPaint);
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, cornerPaint);
        // 右上角
        canvas.drawLine(rect.right - len, rect.top, rect.right, rect.top, cornerPaint);
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, cornerPaint);
        // 左下角
        canvas.drawLine(rect.left, rect.bottom - len, rect.left, rect.bottom, cornerPaint);
        canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, cornerPaint);
        // 右下角
        canvas.drawLine(rect.right - len, rect.bottom, rect.right, rect.bottom, cornerPaint);
        canvas.drawLine(rect.right, rect.bottom - len, rect.right, rect.bottom, cornerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactive || !hasValidImage()) {
            return super.onTouchEvent(event);
        }
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isScaling && !scaleDetector.isInProgress() && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    int idx = event.findPointerIndex(activePointerId);
                    if (idx >= 0) {
                        float x = event.getX(idx);
                        float y = event.getY(idx);
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        lastTouchX = x;
                        lastTouchY = y;
                        if (viewportRect.width() > 0f && viewportRect.height() > 0f) {
                            currentCrop = WallpaperCropMath.panCropRect(
                                    currentCrop, dx, dy, viewportRect.width(), viewportRect.height(),
                                    baseWH[0], baseWH[1]);
                            invalidate();
                            notifyChanged();
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newIndex = pointerIndex == 0 ? 1 : 0;
                    if (newIndex < event.getPointerCount()) {
                        activePointerId = event.getPointerId(newIndex);
                        lastTouchX = event.getX(newIndex);
                        lastTouchY = event.getY(newIndex);
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID;
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                if (!isScaling) {
                    performClick();
                }
                isScaling = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isScaling = false;
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private boolean hasValidImage() {
        return bitmap != null && !bitmap.isRecycled() && sourceWidth > 0 && sourceHeight > 0;
    }

    private void notifyChanged() {
        if (changeListener != null && hasValidImage()) {
            changeListener.onCropChanged(currentCrop, getZoom(), getMaxZoom(), isAtDefaultCenter());
        }
    }
}
