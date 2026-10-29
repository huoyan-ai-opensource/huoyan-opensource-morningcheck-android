package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Arrays;

/**
 * 项目统一确认与表单窗口。视觉复用零售 AlertDialog，正文允许滚动。
 */
final class ProjectDialog extends AlertDialog {
    private final Builder spec;
    private View panel;
    private View hostDecor;
    private int lastInputBottom;
    private final ViewTreeObserver.OnGlobalLayoutListener inputResized = () -> {
        if (hostDecor != null && inputBottom(hostDecor) != lastInputBottom) resizeToHost();
    };

    private ProjectDialog(Builder spec) {
        super(spec.context);
        this.spec = spec;
    }

    @Override
    protected void onCreate(Bundle state) {
        panel = LayoutInflater.from(getContext()).inflate(R.layout.dialog_project_alert, null);
        TextView title = panel.findViewById(R.id.confirmation_title);
        title.setText(spec.title);
        title.setVisibility(spec.title == null ? View.GONE : View.VISIBLE);
        TextView message = panel.findViewById(R.id.confirmation_message);
        message.setText(spec.message);
        message.setVisibility(spec.message == null ? View.GONE : View.VISIBLE);
        if (spec.body != null)
            ((LinearLayout) panel.findViewById(R.id.confirmation_content)).addView(spec.body);
        bindButton(BUTTON_NEGATIVE, spec.negative, spec.onNegative);
        bindButton(BUTTON_NEUTRAL, spec.neutral, spec.onNeutral);
        bindButton(BUTTON_POSITIVE, spec.positive, spec.onPositive);
        if (spec.neutral != null) {
            ((LinearLayout) panel.findViewById(R.id.confirmation_buttons)).setOrientation(LinearLayout.VERTICAL);
            for (int which : new int[]{BUTTON_NEGATIVE, BUTTON_NEUTRAL, BUTTON_POSITIVE}) {
                Button button = getButton(which);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.topMargin = getContext().getResources().getDimensionPixelSize(R.dimen.dp_4);
                button.setLayoutParams(params);
            }
        }
        setView(panel, 0, 0, 0, 0);
        super.onCreate(state);
        setCancelable(spec.cancelable);
        setCanceledOnTouchOutside(spec.cancelable);
    }

    private void bindButton(int which, CharSequence label, DialogInterface.OnClickListener action) {
        Button button = getButton(which);
        button.setText(label);
        button.setVisibility(label == null ? View.GONE : View.VISIBLE);
        button.setOnClickListener(v -> {
            if (action != null) action.onClick(this, which);
            dismiss();
        });
    }

    @Override
    public Button getButton(int which) {
        if (panel == null) return null;
        return panel.findViewById(which == BUTTON_POSITIVE ? android.R.id.button1
                : which == BUTTON_NEGATIVE ? android.R.id.button2 : android.R.id.button3);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hostDecor = DepartmentFilterDialog.activityOf(spec.context).getWindow().getDecorView();
        hostDecor.getViewTreeObserver().addOnGlobalLayoutListener(inputResized);
        resizeToHost();
    }

    @Override
    protected void onStop() {
        if (hostDecor != null) {
            hostDecor.getViewTreeObserver().removeOnGlobalLayoutListener(inputResized);
            hostDecor = null;
        }
        super.onStop();
    }

    private static int inputBottom(View host) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(host);
        return insets == null ? 0
                : insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
    }

    void resizeToHost() {
        Activity host = DepartmentFilterDialog.activityOf(spec.context);
        int margin = getContext().getResources().getDimensionPixelSize(R.dimen.dp_12);
        View hostDecor = host.getWindow().getDecorView();
        int hostWidth = hostDecor.getWidth() > 0 ? hostDecor.getWidth() : host.getResources().getDisplayMetrics().widthPixels;
        int hostHeight = hostDecor.getHeight() > 0 ? hostDecor.getHeight() : host.getResources().getDisplayMetrics().heightPixels;
        // 浮动窗口自己的 IME inset 已被系统消耗，按宿主的键盘高度限制正文，保留底部按钮。
        lastInputBottom = inputBottom(hostDecor);
        hostHeight = Math.max(1, hostHeight - lastInputBottom);
        int width = Math.max(1, Math.min(hostWidth - margin * 2,
                getContext().getResources().getDimensionPixelSize(R.dimen.dp_220)));
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        View scroll = panel.findViewById(R.id.confirmation_scroll);
        scroll.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.forceLayout();
        panel.forceLayout();
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int excess = panel.getMeasuredHeight() - (int) (hostHeight * .85f);
        if (excess > 0) {
            scroll.getLayoutParams().height = Math.max(margin * 2, scroll.getMeasuredHeight() - excess);
            scroll.requestLayout();
        }
    }

    static final class Builder {
        private final Context context;
        private CharSequence title, message, positive, negative, neutral;
        private DialogInterface.OnClickListener onPositive, onNegative, onNeutral, onItem;
        private View body;
        private String[] items;
        private int selected = -1;
        private boolean cancelable = true;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(CharSequence value) {
            title = value;
            return this;
        }

        Builder setTitle(int value) {
            return setTitle(context.getText(value));
        }

        Builder setMessage(CharSequence value) {
            message = value;
            return this;
        }

        Builder setMessage(int value) {
            return setMessage(context.getText(value));
        }

        Builder setView(View value) {
            body = value;
            return this;
        }

        Builder setCancelable(boolean value) {
            cancelable = value;
            return this;
        }

        Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener action) {
            positive = text;
            onPositive = action;
            return this;
        }

        Builder setPositiveButton(int text, DialogInterface.OnClickListener action) {
            return setPositiveButton(context.getText(text), action);
        }

        Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener action) {
            negative = text;
            onNegative = action;
            return this;
        }

        Builder setNegativeButton(int text, DialogInterface.OnClickListener action) {
            return setNegativeButton(context.getText(text), action);
        }

        Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener action) {
            neutral = text;
            onNeutral = action;
            return this;
        }

        Builder setItems(String[] values, DialogInterface.OnClickListener action) {
            items = values.clone();
            onItem = action;
            return this;
        }

        Builder setItems(int values, DialogInterface.OnClickListener action) {
            return setItems(context.getResources().getStringArray(values), action);
        }

        Builder setSingleChoiceItems(int values, int current, DialogInterface.OnClickListener action) {
            selected = current;
            return setItems(values, action);
        }

        AlertDialog create() {
            if (items != null) {
                final AlertDialog[] dialog = new AlertDialog[1];
                dialog[0] = DesignChoiceDialog.create(DepartmentFilterDialog.activityOf(context),
                        String.valueOf(title), "点选后关闭", Arrays.asList(items), selected,
                        position -> {
                            if (onItem != null) onItem.onClick(dialog[0], position);
                        });
                return dialog[0];
            }
            return new ProjectDialog(this);
        }
    }
}
