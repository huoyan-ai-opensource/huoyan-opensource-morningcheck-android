package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 普通照片交由外层列表翻页；全屏下可先移动照片，再围绕目标部位缩放。
 */
public final class PhotoViewerImageView extends AppCompatImageView {
    private final Matrix transform = new Matrix();
    private final RectF imageBounds = new RectF();
    private final GestureDetector taps;
    private final ScaleGestureDetector scaling;
    private final int touchSlop;
    private float zoom = 1f, downX, downY, lastX, lastY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean zoomEnabled = true, multiplePointers, tracking, dragging;

    public PhotoViewerImageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setScaleType(ScaleType.MATRIX);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                return !zoomEnabled && performClick();
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                return zoomEnabled && !multiplePointers && !dragging && performClick();
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (!zoomEnabled || getDrawable() == null) return false;
                if (zoom > 1f) fitImage();
                else scaleTo(2.5f, event.getX(), event.getY());
                return true;
            }
        });
        taps.setIsLongpressEnabled(false);
        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return zoomEnabled && getDrawable() != null;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleTo(zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
    }

    void setZoomEnabled(boolean enabled) {
        zoomEnabled = enabled;
        releaseTouch();
        fitImage();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        // ImageView 构造期间也可能设置 drawable，此时自定义矩阵还未初始化。
        if (transform != null) fitImage();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitImage();
    }

    private void fitImage() {
        zoom = 1f;
        transform.reset();
        Drawable drawable = getDrawable();
        if (drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0
                && getWidth() > 0 && getHeight() > 0) {
            RectF source = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            RectF target = new RectF(0, 0, getWidth() - getPaddingLeft() - getPaddingRight(),
                    getHeight() - getPaddingTop() - getPaddingBottom());
            transform.setRectToRect(source, target, Matrix.ScaleToFit.CENTER);
        }
        setImageMatrix(transform);
    }

    private void scaleTo(float next, float focusX, float focusY) {
        next = Math.max(1f, Math.min(5f, next));
        transform.postScale(next / zoom, next / zoom,
                focusX - getPaddingLeft(), focusY - getPaddingTop());
        zoom = next;
        constrainImage();
    }

    private void constrainImage() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        imageBounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        transform.mapRect(imageBounds);
        float width = getWidth() - getPaddingLeft() - getPaddingRight();
        float height = getHeight() - getPaddingTop() - getPaddingBottom();
        // 保证图片仍覆盖视口中心，同时允许边缘移到中心，便于先定位再放大；松手不回中。
        float centerX = width / 2f, centerY = height / 2f;
        float dx = imageBounds.right < centerX ? centerX - imageBounds.right
                : imageBounds.left > centerX ? centerX - imageBounds.left : 0;
        float dy = imageBounds.bottom < centerY ? centerY - imageBounds.bottom
                : imageBounds.top > centerY ? centerY - imageBounds.top : 0;
        transform.postTranslate(dx, dy);
        setImageMatrix(transform);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        // 列表拖动由 RecyclerView 接管；检测轻点，避免未被列表接管的纵向拖动误开大图。
        if (!zoomEnabled) {
            taps.onTouchEvent(event);
            return true;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            tracking = true;
            multiplePointers = false;
            dragging = false;
            activePointerId = event.getPointerId(0);
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiplePointers = true;
        }
        taps.onTouchEvent(event);
        scaling.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE && tracking) {
            int pointerIndex = event.findPointerIndex(activePointerId);
            if (!scaling.isInProgress() && event.getPointerCount() == 1 && pointerIndex >= 0) {
                float x = event.getX(pointerIndex), y = event.getY(pointerIndex);
                if (!dragging && Math.hypot(x - downX, y - downY) > touchSlop) dragging = true;
                if (dragging) {
                    transform.postTranslate(x - lastX, y - lastY);
                    constrainImage();
                    lastX = x;
                    lastY = y;
                }
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int remaining = event.findPointerIndex(activePointerId);
            if (remaining == event.getActionIndex() || remaining < 0)
                remaining = event.getActionIndex() == 0 ? 1 : 0;
            activePointerId = event.getPointerId(remaining);
            // 缩放结束后以剩余手指的位置继续拖动，不能沿用已抬起手指的坐标导致图片跳动。
            downX = lastX = event.getX(remaining);
            downY = lastY = event.getY(remaining);
            dragging = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            releaseTouch();
        }
        return true;
    }

    private void releaseTouch() {
        tracking = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        taps.onTouchEvent(cancel);
        scaling.onTouchEvent(cancel);
        cancel.recycle();
        releaseTouch();
        super.onDetachedFromWindow();
    }
}
