package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Arrays;

/**
 * 密码验证和修改共用内置数字键盘，离页销毁输入，不恢复密码草稿。
 */
public final class SecurityPasswordDialog {
    private SecurityPasswordDialog() {
    }

    public static AlertDialog showChange(BaseMorningActivity host, Runnable onChanged) {
        return new Session(host, true, onChanged, null).show();
    }

    public static AlertDialog showVerify(BaseMorningActivity host, Runnable onVerified) {
        return showVerify(host, onVerified, null);
    }

    /**
     * 先清理弹窗，再交付验证成功，最后通知关闭；调用方无需替换关闭监听器。
     */
    public static AlertDialog showVerify(BaseMorningActivity host, Runnable onVerified, Runnable onClosed) {
        return new Session(host, false, onVerified, onClosed).show();
    }

    static boolean retainsInputOnReflow(AlertDialog dialog) {
        return dialog instanceof PasswordWindow && dialog.isShowing();
    }

    private static final class Session implements DefaultLifecycleObserver {
        private final BaseMorningActivity host;
        private final boolean change;
        private final Runnable success, closed;
        private final PasswordWindow dialog;
        private final LoadingDialog loading;
        private final EditText original, replacement, confirmation;
        private EditText selected;
        private final View hostDecor;
        private final View.OnLayoutChangeListener resized;
        private volatile boolean active = true;
        private boolean submitting, accepted;

        Session(BaseMorningActivity host, boolean change, Runnable success, Runnable closed) {
            this.host = host;
            this.change = change;
            this.success = success;
            this.closed = closed;
            View body = LayoutInflater.from(host).inflate(R.layout.dialog_security_password, null);
            original = body.findViewById(R.id.security_password_original);
            replacement = body.findViewById(R.id.security_password_new);
            confirmation = body.findViewById(R.id.security_password_repeat);
            ((TextView) body.findViewById(R.id.security_password_title)).setText(change
                    ? R.string.security_password_change_title : R.string.security_password_verify_title);
            ((TextView) body.findViewById(R.id.security_password_description)).setText(change
                    ? R.string.security_password_change_description : R.string.security_password_verify_description);
            ((TextView) body.findViewById(R.id.security_password_original_label)).setText(change
                    ? R.string.security_password_original : R.string.security_password_current);
            body.findViewById(R.id.security_password_new_group).setVisibility(change ? View.VISIBLE : View.GONE);
            dialog = new PasswordWindow(host, body);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(R.string.security_password_cancel);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(view -> dialog.cancel());
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(change
                    ? R.string.security_password_save : R.string.security_password_verify);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> submit());
            for (EditText field : new EditText[]{original, replacement, confirmation}) {
                field.setOnClickListener(view -> select(field));
                field.setOnFocusChangeListener((view, focused) -> {
                    if (focused) select(field);
                });
            }
            int[] digits = {R.id.security_password_key_0, R.id.security_password_key_1,
                    R.id.security_password_key_2, R.id.security_password_key_3, R.id.security_password_key_4,
                    R.id.security_password_key_5, R.id.security_password_key_6, R.id.security_password_key_7,
                    R.id.security_password_key_8, R.id.security_password_key_9};
            for (int digit = 0; digit < digits.length; digit++) {
                final char value = (char) ('0' + digit);
                body.findViewById(digits[digit]).setOnClickListener(view -> {
                    if (current() && !submitting && selected.length() < SecuritySettings.MAX_PASSWORD_LENGTH)
                        selected.getText().append(value);
                });
            }
            body.findViewById(R.id.security_password_key_clear).setOnClickListener(view -> {
                if (current() && !submitting) selected.getText().clear();
            });
            body.findViewById(R.id.security_password_key_delete).setOnClickListener(view -> {
                if (current() && !submitting && selected.length() > 0)
                    selected.getText().delete(selected.length() - 1, selected.length());
            });
            body.setOnClickListener(view -> {
                if (current() && !submitting) dialog.cancel();
            });
            body.findViewById(R.id.security_password_card).setClickable(true);
            select(original);
            loading = new LoadingDialog(host);
            hostDecor = host.getWindow().getDecorView();
            resized = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (active && dialog.isShowing() && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
                    dialog.resizeToHost();
            };
            dialog.setOnShowListener(shown -> select(original));
            dialog.setOnDismissListener(dismissed -> release());
        }

        AlertDialog show() {
            if (host.destroyed || !host.foreground || host.isFinishing()) {
                release();
                return dialog;
            }
            // 生命周期注册可能立即回调；先挂尺寸监听，确保同步关闭时也能完整清理。
            hostDecor.addOnLayoutChangeListener(resized);
            host.getLifecycle().addObserver(this);
            if (!active) return dialog;
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            ImmersiveUi.show(dialog);
            return dialog;
        }

        private void submit() {
            if (!current() || submitting) return;
            char[] oldValue = characters(original), newValue = characters(replacement), repeated = characters(confirmation);
            if (!SecuritySettings.validPassword(oldValue) || change && !SecuritySettings.validPassword(newValue)) {
                Arrays.fill(oldValue, '\0');
                Arrays.fill(newValue, '\0');
                Arrays.fill(repeated, '\0');
                host.notice(host.getString(R.string.security_password_invalid));
                return;
            }
            boolean matches = Arrays.equals(newValue, repeated);
            Arrays.fill(repeated, '\0');
            if (change && !matches) {
                Arrays.fill(oldValue, '\0');
                Arrays.fill(newValue, '\0');
                host.notice(host.getString(R.string.security_password_mismatch));
                select(confirmation);
                return;
            }
            submitting = true;
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
            dismissKeyboard();
            loading.show(host.getString(change ? R.string.security_password_saving : R.string.security_password_verifying));
            try {
                host.runIo(() -> {
                    boolean verified = false;
                    String failure = null;
                    try {
                        if (!active) return;
                        verified = change ? SecuritySettings.changePassword(host, oldValue, newValue)
                                : SecuritySettings.verify(host, oldValue);
                    } catch (RuntimeException error) {
                        failure = host.getString(R.string.security_password_failed);
                    } finally {
                        Arrays.fill(oldValue, '\0');
                        Arrays.fill(newValue, '\0');
                    }
                    boolean result = verified;
                    String error = failure;
                    host.main.post(() -> complete(result, error));
                });
            } catch (RuntimeException rejected) {
                Arrays.fill(oldValue, '\0');
                Arrays.fill(newValue, '\0');
                complete(false, host.getString(R.string.security_password_failed));
            }
        }

        private void complete(boolean verified, String error) {
            loading.dismiss();
            if (!current()) return;
            submitting = false;
            if (!verified) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                host.notice(error == null ? host.getString(R.string.security_password_wrong) : error);
                return;
            }
            accepted = true;
            dialog.dismiss();
        }

        private boolean current() {
            return active && dialog.isShowing() && host.foreground && !host.destroyed && !host.isFinishing();
        }

        private void select(EditText field) {
            selected = field;
            for (EditText item : new EditText[]{original, replacement, confirmation}) item.setSelected(item == field);
            field.requestFocus();
            field.setSelection(field.length());
        }

        private void dismissKeyboard() {
            InputMethodManager input = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (input != null && original.getWindowToken() != null)
                input.hideSoftInputFromWindow(original.getWindowToken(), 0);
        }

        private void release() {
            if (!active) return;
            // onDismiss 时窗口已不可见，成功状态还需单独检查宿主而不依赖 isShowing。
            boolean deliver = accepted && host.foreground && !host.destroyed && !host.isFinishing();
            active = false;
            submitting = false;
            dismissKeyboard();
            loading.dismiss();
            for (EditText field : new EditText[]{original, replacement, confirmation}) {
                field.getText().clear();
                field.setOnFocusChangeListener(null);
                field.clearFocus();
                field.setOnClickListener(null);
                field.setOnTouchListener(null);
            }
            hostDecor.removeOnLayoutChangeListener(resized);
            host.getLifecycle().removeObserver(this);
            try {
                if (deliver) {
                    host.toast(host.getString(change ? R.string.security_password_changed : R.string.security_password_verified));
                    if (success != null) success.run();
                }
            } finally {
                if (closed != null) closed.run();
            }
        }

        @Override
        public void onStop(LifecycleOwner owner) {
            if (dialog.isShowing()) dialog.dismiss();
            else release();
        }

        @Override
        public void onDestroy(LifecycleOwner owner) {
            if (dialog.isShowing()) dialog.dismiss();
            else release();
        }

        private static char[] characters(EditText field) {
            char[] result = new char[field.length()];
            field.getText().getChars(0, field.length(), result, 0);
            return result;
        }
    }

    /**
     * 保留 AlertDialog 的按钮契约；只限制正文高度，窄横屏和大字号下操作按钮始终可见。
     */
    private static final class PasswordWindow extends AlertDialog {
        private final BaseMorningActivity host;
        private final View body;

        PasswordWindow(BaseMorningActivity host, View body) {
            super(host);
            this.host = host;
            this.body = body;
        }

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            // AlertDialog 的 customPanel 会按正文收缩，直接安装全屏根布局以铺满遮罩并居中卡片。
            setContentView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onStart() {
            super.onStart();
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            resizeToHost();
        }

        @Override
        public Button getButton(int which) {
            return body.findViewById(which == BUTTON_POSITIVE ? android.R.id.button1 : android.R.id.button2);
        }

        void resizeToHost() {
            View decor = host.getWindow().getDecorView();
            int hostWidth = decor.getWidth() > 0 ? decor.getWidth() : host.getResources().getDisplayMetrics().widthPixels;
            int hostHeight = decor.getHeight() > 0 ? decor.getHeight() : host.getResources().getDisplayMetrics().heightPixels;
            int margin = host.getResources().getDimensionPixelSize(R.dimen.security_password_outer_padding);
            int width = Math.max(1, Math.min(hostWidth - margin * 2,
                    host.getResources().getDimensionPixelSize(R.dimen.security_password_card_width)));
            View card = body.findViewById(R.id.security_password_card);
            View scroll = body.findViewById(R.id.security_password_scroll);
            // 宿主处理 configChanges，旋转不会重建弹窗，按当前窗口尺寸切换同一组输入控件。
            boolean landscape = hostWidth > hostHeight;
            LinearLayout content = body.findViewById(R.id.security_password_content);
            content.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            View form = body.findViewById(R.id.security_password_form);
            View keypad = body.findViewById(R.id.security_password_keypad);
            LinearLayout.LayoutParams formParams = new LinearLayout.LayoutParams(landscape ? 0
                    : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, landscape ? 1 : 0);
            LinearLayout.LayoutParams keypadParams = new LinearLayout.LayoutParams(landscape ? 0
                    : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, landscape ? 1 : 0);
            if (landscape)
                keypadParams.setMarginStart(host.getResources().getDimensionPixelSize(R.dimen.security_password_column_gap));
            else
                keypadParams.topMargin = host.getResources().getDimensionPixelSize(R.dimen.security_password_section_gap);
            form.setLayoutParams(formParams);
            keypad.setLayoutParams(keypadParams);
            card.getLayoutParams().width = width;
            scroll.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            scroll.forceLayout();
            card.forceLayout();
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int excess = card.getMeasuredHeight() - Math.max(1, hostHeight - margin * 2);
            if (excess > 0) scroll.getLayoutParams().height = Math.max(1, scroll.getMeasuredHeight() - excess);
            card.requestLayout();
        }
    }
}
