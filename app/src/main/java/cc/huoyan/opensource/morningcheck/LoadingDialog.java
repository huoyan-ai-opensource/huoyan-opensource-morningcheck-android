package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 仅承载进行中的操作，由所属页面在完成及离页时关闭。
 */
final class LoadingDialog {
    private final Activity activity;
    private Dialog dialog;

    LoadingDialog(Activity activity) {
        this.activity = activity;
        if (activity instanceof androidx.lifecycle.LifecycleOwner) {
            ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().addObserver(
                    new androidx.lifecycle.DefaultLifecycleObserver() {
                        @Override
                        public void onStop(androidx.lifecycle.LifecycleOwner owner) {
                            dismiss();
                        }

                        @Override
                        public void onDestroy(androidx.lifecycle.LifecycleOwner owner) {
                            dismiss();
                            owner.getLifecycle().removeObserver(this);
                        }
                    });
        }
    }

    void show(String message) {
        show(message, false);
    }

    void showReadOnly(String message) {
        show(message, activity.getCurrentFocus() instanceof EditText);
    }

    private void show(String message, boolean keepInputFocus) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog == null) {
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            dialog.setContentView(R.layout.dialog_design_loading);
            dialog.findViewById(R.id.loading_cancel).setVisibility(View.GONE);
            dialog.setCancelable(false);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        // 搜索会随输入反复读库，读取提示不能抢走系统键盘焦点；写入仍使用阻塞窗口。
        int inputFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        dialog.getWindow().setFlags(keepInputFocus ? inputFlags : 0, inputFlags);
        ((TextView) dialog.findViewById(R.id.loading_message)).setText(message);
        int margin = activity.getResources().getDimensionPixelSize(R.dimen.dp_20);
        int hostWidth = activity.getWindow().getDecorView().getWidth();
        if (hostWidth <= 0) {
            android.content.res.Resources resources = activity.getResources();
            hostWidth = Math.round(resources.getConfiguration().screenWidthDp * resources.getDisplayMetrics().density);
            if (hostWidth <= 0) hostWidth = resources.getDisplayMetrics().widthPixels;
        }
        int width = Math.max(1, Math.min(hostWidth - margin * 2,
                activity.getResources().getDimensionPixelSize(R.dimen.dp_180)));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!dialog.isShowing()) ImmersiveUi.show(dialog);
    }

    void show(String message, Runnable cancel) {
        show(message);
        if (dialog == null) return;
        View action = dialog.findViewById(R.id.loading_cancel);
        action.setVisibility(View.VISIBLE);
        action.setOnClickListener(view -> {
            dismiss();
            cancel.run();
        });
    }

    void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
