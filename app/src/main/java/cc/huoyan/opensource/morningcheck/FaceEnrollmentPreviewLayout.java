package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** 人脸录入预览等比铺满容器，画面与检测框共同缩放并裁切超出的区域。 */
public final class FaceEnrollmentPreviewLayout extends FrameLayout {
    private final Path previewClip = new Path();
    private final float cornerRadius;
    private int frameWidth, frameHeight;

    public FaceEnrollmentPreviewLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        cornerRadius = getResources().getDimension(R.dimen.dp_6);
    }

    public void setFrameSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        frameWidth = width;
        frameHeight = height;
        updatePreviewScale();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float radius = Math.min(cornerRadius, Math.min(width, height) / 2f);
        previewClip.reset();
        previewClip.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int saved = canvas.save();
        try {
            canvas.clipPath(previewClip);
            super.dispatchDraw(canvas);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updatePreviewScale();
    }

    private void updatePreviewScale() {
        if (getChildCount() == 0 || frameWidth <= 0 || frameHeight <= 0) return;
        View preview = getChildAt(0);
        int width = preview.getWidth(), height = preview.getHeight();
        if (width <= 0 || height <= 0) return;
        float widthScale = width / (float) frameWidth, heightScale = height / (float) frameHeight;
        float scale = Math.max(widthScale, heightScale) / Math.min(widthScale, heightScale);
        // 相机内部仍完整预览；统一放大其宿主，让画面和检测框一起裁切，避免只缩放纹理造成错位。
        preview.setScaleX(scale);
        preview.setScaleY(scale);
    }
}
