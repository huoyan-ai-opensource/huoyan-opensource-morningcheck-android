package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * 部门选择始终通过统一列表弹窗回传稳定 ID。
 */
public final class DepartmentFilterSpinner extends ChoiceSpinner {
    private Runnable chooser, detach;
    private float downX, downY;

    public DepartmentFilterSpinner(Context context) {
        super(context);
    }

    public DepartmentFilterSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DepartmentFilterSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setDepartmentChooser(Runnable chooser, Runnable detach) {
        this.chooser = chooser;
        this.detach = detach;
    }

    @Override
    public boolean performClick() {
        if (chooser == null) return super.performClick();
        if (!isEnabled() || getWindowToken() == null) return false;
        chooser.run();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (chooser == null) return super.onTouchEvent(event);
        if (!isEnabled()) return false;
        // AppCompat 的拖动转发会绕过 performClick 直接开下拉，弹窗模式需接管这条触摸路径。
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
        if (detach != null) detach.run();
        super.onDetachedFromWindow();
    }
}
