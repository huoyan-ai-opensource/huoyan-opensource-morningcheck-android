package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

/**
 * 风险确认和普通确认共享项目 AlertDialog 的内容与按钮样式。
 */
final class DesignConfirmationDialog {
    private DesignConfirmationDialog() {
    }

    static AlertDialog create(Activity activity, CharSequence title, CharSequence message,
                              CharSequence confirm, DialogInterface.OnClickListener action) {
        return new ProjectDialog.Builder(activity).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton(confirm, action).create();
    }
}
