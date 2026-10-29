package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;

/**
 * 四段显示框上方的透明编辑层；保留真实选区、物理键盘和无障碍编辑。
 */
public final class SdkActivationCodeEditText extends AppCompatEditText {
    private TextView[] segments;
    private Runnable selectionListener;
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] editorLocation = new int[2];
    private final int[] boxLocation = new int[2];

    public SdkActivationCodeEditText(Context context, AttributeSet attributes) {
        super(context, attributes);
        setShowSoftInputOnFocus(false);
        setTextColor(Color.TRANSPARENT);
        setHighlightColor(Color.TRANSPARENT);
        setCursorVisible(false);
        setLongClickable(false);
        setBackgroundColor(Color.TRANSPARENT);
        cursor.setColor(ContextCompat.getColor(context, R.color.mc_blue));
        cursor.setStrokeWidth(getResources().getDimension(R.dimen.dp_1));
    }

    /**
     * 仅绑定当前弹窗的视图；销毁时传 null 释放引用。
     */
    public void bindSegments(TextView[] boxes, Runnable listener) {
        segments = boxes;
        selectionListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_UP && segments != null) {
            requestFocus();
            getLocationInWindow(editorLocation);
            int group = 0;
            float nearest = Float.MAX_VALUE;
            for (int i = 0; i < segments.length; i++) {
                TextView box = segments[i];
                box.getLocationInWindow(boxLocation);
                float left = boxLocation[0] - editorLocation[0];
                float top = boxLocation[1] - editorLocation[1];
                float dx = Math.max(left - event.getX(), Math.max(0, event.getX() - left - box.getWidth()));
                float dy = Math.max(top - event.getY(), Math.max(0, event.getY() - top - box.getHeight()));
                float distance = dx * dx + dy * dy;
                if (distance < nearest) {
                    nearest = distance;
                    group = i;
                }
            }
            TextView box = segments[group];
            box.getLocationInWindow(boxLocation);
            float left = boxLocation[0] - editorLocation[0];
            int count = Math.max(0, Math.min(4, length() - group * 4));
            float unit = Math.max(1, box.getPaint().measureText("M"));
            float start = left + (box.getWidth() - count * unit) / 2f;
            int offset = Math.max(0, Math.min(count, Math.round((event.getX() - start) / unit)));
            setSelection(Math.min(length(), group * 4 + offset));
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionListener != null) selectionListener.run();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int position = getSelectionStart();
        if (!hasFocus() || segments == null || position < 0 || length() == 16 && position == 16)
            return;
        int group = Math.min(3, position / 4);
        TextView box = segments[group];
        getLocationInWindow(editorLocation);
        box.getLocationInWindow(boxLocation);
        int count = Math.max(0, Math.min(4, length() - group * 4));
        float unit = box.getPaint().measureText("M");
        float x = boxLocation[0] - editorLocation[0] + (box.getWidth() - count * unit) / 2f
                + Math.min(4, position - group * 4) * unit;
        float y = boxLocation[1] - editorLocation[1] + box.getHeight() / 2f;
        float half = box.getTextSize() * 0.6f;
        canvas.drawLine(x, y - half, x, y + half, cursor);
    }
}
