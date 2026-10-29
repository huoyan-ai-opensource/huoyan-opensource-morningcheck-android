package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;

import java.util.ArrayList;
import java.util.List;

/**
 * 保留选中值控件的绑定能力，所有触摸和键盘选择均打开项目列表弹窗。
 */
public class ChoiceSpinner extends AppCompatSpinner {
    private AlertDialog choiceDialog;
    private float downX, downY;

    public ChoiceSpinner(Context context) {
        super(context);
    }

    public ChoiceSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ChoiceSpinner(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    @Override
    public boolean performClick() {
        if (!isEnabled() || getWindowToken() == null) return false;
        if (choiceDialog != null && choiceDialog.isShowing()) return true;
        List<String> labels = new ArrayList<>();
        if (getAdapter() != null) for (int i = 0; i < getAdapter().getCount(); i++)
            labels.add(String.valueOf(getAdapter().getItem(i)));
        CharSequence title = getPrompt();
        choiceDialog = DesignChoiceDialog.create(DepartmentFilterDialog.activityOf(getContext()),
                title == null ? "请选择" : title.toString(), "点选后关闭", labels, getSelectedItemPosition(),
                position -> setSelection(position));
        ImmersiveUi.show(choiceDialog);
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                setPressed(true);
                break;
            case MotionEvent.ACTION_MOVE:
                int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)
                    setPressed(false);
                break;
            case MotionEvent.ACTION_UP:
                boolean click = isPressed();
                setPressed(false);
                if (click) performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (choiceDialog != null) choiceDialog.dismiss();
        choiceDialog = null;
        super.onDetachedFromWindow();
    }
}
