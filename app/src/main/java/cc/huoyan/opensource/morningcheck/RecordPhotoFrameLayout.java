package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 照片区域随两列网格的实际宽度保持 4:3，加载前后尺寸一致。 */
public final class RecordPhotoFrameLayout extends FrameLayout {
    public RecordPhotoFrameLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = Math.round(width * 3f / 4f);
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
