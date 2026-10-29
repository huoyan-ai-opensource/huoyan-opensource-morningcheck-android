package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 扫码弹窗保持设计中的 16:9 预览，异常操作区在有限高度内滚动。
 */
public final class ScanDialogLayout extends FrameLayout {
    public ScanDialogLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int margin = getResources().getDimensionPixelSize(R.dimen.dp_12);
        int width = Math.max(1, Math.min(MeasureSpec.getSize(widthSpec) - margin * 2,
                getResources().getDimensionPixelSize(R.dimen.dp_260)));
        View panel = findViewById(R.id.scan_dialog_panel);
        LayoutParams params = (LayoutParams) panel.getLayoutParams();
        params.width = width;
        params.topMargin = params.bottomMargin = margin;
        View preview = findViewById(R.id.scan_dialog_preview_frame);
        preview.getLayoutParams().height = Math.max(1, (width
                - getResources().getDimensionPixelSize(R.dimen.dp_10) * 2) * 9 / 16);
        super.onMeasure(widthSpec, heightSpec);
    }
}
