package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** 统一裁切人脸卡片的照片背景、图片和编号，保留整张卡片的圆角。 */
public final class FaceSampleCardLayout extends LinearLayout {
    private final Path cardClip = new Path();
    private final float cornerRadius;

    public FaceSampleCardLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        cornerRadius = getResources().getDimension(R.dimen.dp_4);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float radius = Math.min(cornerRadius, Math.min(width, height) / 2f);
        cardClip.reset();
        cardClip.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // 直接裁切所有子内容，软件绘制时也不能让照片背景盖住卡片的顶部圆角。
        int saved = canvas.save();
        try {
            canvas.clipPath(cardClip);
            super.dispatchDraw(canvas);
        } finally {
            canvas.restoreToCount(saved);
        }
    }
}
