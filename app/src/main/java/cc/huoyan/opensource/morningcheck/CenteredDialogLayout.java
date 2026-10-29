package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 全屏输入面板之上的表单容器，键盘出现时只缩小可滚动的编辑区。
 */
public final class CenteredDialogLayout extends FrameLayout {
    public CenteredDialogLayout(Context context, AttributeSet attributes) {
        super(context, attributes);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        View panel = getChildAt(0);
        // 系统输入法通过根容器留白避让，表单尺寸必须以扣除留白后的空间为准。
        int availableWidth = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int availableHeight = Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
        boolean shortWindow = availableHeight < getResources().getDimensionPixelSize(R.dimen.dp_260);
        int margin = getResources().getDimensionPixelSize(shortWindow ? R.dimen.dp_4 : R.dimen.dp_16);
        View header = panel.findViewById(R.id.staff_edit_dialog_title);
        if (header != null) header.setVisibility(shortWindow ? GONE : VISIBLE);
        LayoutParams layout = (LayoutParams) panel.getLayoutParams();
        boolean employeeSelection = panel.findViewById(R.id.enrollment_search) != null;
        boolean portraitSelection = employeeSelection && getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        layout.width = Math.max(0, Math.min(availableWidth - margin * 2,
                getResources().getDimensionPixelSize(employeeSelection ? R.dimen.dp_400 : R.dimen.dp_260)
                        + getResources().getDimensionPixelSize(employeeSelection ? R.dimen.dp_50 : R.dimen.dp_40)));
        layout.height = Math.max(0, Math.min(availableHeight - margin * 2,
                getResources().getDimensionPixelSize(portraitSelection ? R.dimen.dp_400 : employeeSelection ? R.dimen.dp_260 : R.dimen.dp_239)
                        + getResources().getDimensionPixelSize(portraitSelection ? R.dimen.dp_60 : employeeSelection ? R.dimen.dp_50 : R.dimen.dp_14)));
        super.onMeasure(widthSpec, heightSpec);
    }
}
