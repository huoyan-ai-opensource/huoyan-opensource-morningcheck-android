package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import cc.huoyan.opensource.morningcheck.camera.CameraImageOptions;

/**
 * 按真实完整画面的等比留白定位四个角点，拖动期间阻止父层抢占手势。
 */
public final class CameraCropView extends View {
    interface Listener {
        void changed(float left, float top, float right, float bottom);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF image = new RectF();
    private float left, top, right = 1, bottom = 1;
    private int imageWidth, imageHeight, corner = -1;
    private Listener listener;

    public CameraCropView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setContentDescription(getContext().getString(R.string.i18n_a_drag_the_corners_to_adjust_the_capture_area));
    }

    void configure(CameraImageOptions options, Listener listener) {
        left = options.left;
        top = options.top;
        right = options.right;
        bottom = options.bottom;
        this.listener = listener;
        invalidate();
    }

    void imageSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        invalidate();
    }

    private void measureImage() {
        if (imageWidth <= 0 || imageHeight <= 0) {
            image.setEmpty();
            return;
        }
        float scale = Math.min(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float width = imageWidth * scale, height = imageHeight * scale;
        image.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        measureImage();
        if (image.isEmpty()) return;
        float x0 = image.left + left * image.width(), y0 = image.top + top * image.height();
        float x1 = image.left + right * image.width(), y1 = image.top + bottom * image.height();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawRect(image.left, image.top, image.right, y0, paint);
        canvas.drawRect(image.left, y1, image.right, image.bottom, paint);
        canvas.drawRect(image.left, y0, x0, y1, paint);
        canvas.drawRect(x1, y0, image.right, y1, paint);
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDimension(R.dimen.dp_1));
        canvas.drawRect(x0, y0, x1, y1, paint);
        paint.setStyle(Paint.Style.FILL);
        float handle = getResources().getDimension(R.dimen.dp_3);
        for (float x : new float[]{x0, x1})
            for (float y : new float[]{y0, y1})
                canvas.drawRect(Math.max(image.left, x - handle), Math.max(image.top, y - handle),
                        Math.min(image.right, x + handle), Math.min(image.bottom, y + handle), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        measureImage();
        if (image.isEmpty()) return false;
        float x = (event.getX() - image.left) / image.width(), y = (event.getY() - image.top) / image.height();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float hit = getResources().getDimension(R.dimen.dp_16);
            float[] xs = {left, right, right, left}, ys = {top, top, bottom, bottom};
            for (int i = 0; i < 4; i++)
                if (Math.abs(x - xs[i]) * image.width() <= hit
                        && Math.abs(y - ys[i]) * image.height() <= hit) {
                    corner = i;
                    break;
                }
            if (corner < 0) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (corner < 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (corner == 0 || corner == 3) left = Math.max(0, Math.min(right - .05f, x));
            else right = Math.min(1, Math.max(left + .05f, x));
            if (corner < 2) top = Math.max(0, Math.min(bottom - .05f, y));
            else bottom = Math.min(1, Math.max(top + .05f, y));
            if (listener != null) listener.changed(left, top, right, bottom);
            invalidate();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            corner = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
