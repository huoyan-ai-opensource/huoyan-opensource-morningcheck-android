package cc.huoyan.opensource.morningcheck;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * 在晨检信息卡中循环播放完整流程；仅作示意，不代表识别成功。
 */
@SuppressWarnings("deprecation")
public final class FaceAlignmentGuideView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Movie animation;
    private Bitmap still;
    private long started;

    public FaceAlignmentGuideView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setContentDescription(context.getString(R.string.check_tutorial_guide));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.round(width * 420f / 648f);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loadGuide();
    }

    private void loadGuide() {
        started = SystemClock.uptimeMillis();
        try (InputStream source = getResources().openRawResource(R.raw.mc_check_tutorial)) {
            animation = Movie.decodeStream(source);
        } catch (IOException ignored) {
            animation = null;
        }
        still = BitmapFactory.decodeResource(getResources(), R.drawable.mc_check_tutorial_preview);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean animate = animation != null
                && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
        int width = animate ? animation.width() : still == null ? 0 : still.getWidth();
        int height = animate ? animation.height() : still == null ? 0 : still.getHeight();
        if (width <= 0 || height <= 0) return;
        float scale = Math.min((float) getWidth() / width, (float) getHeight() / height);
        int saved = canvas.save();
        canvas.translate((getWidth() - width * scale) / 2, (getHeight() - height * scale) / 2);
        canvas.scale(scale, scale);
        if (animate) {
            int duration = animation.duration() > 0 ? animation.duration() : 4800;
            animation.setTime((int) ((SystemClock.uptimeMillis() - started) % duration));
            animation.draw(canvas, 0, 0, bitmapPaint);
        } else canvas.drawBitmap(still, 0, 0, bitmapPaint);
        canvas.restoreToCount(saved);
        if (animate && isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(50);
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) {
            started = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            started = SystemClock.uptimeMillis();
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animation = null;
        still = null;
        super.onDetachedFromWindow();
    }
}
