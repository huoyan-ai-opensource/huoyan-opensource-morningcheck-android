package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * 所有部门选择共用列表弹窗，以打开时的 ID 快照回传。
 */
final class DepartmentFilterDialog {
    private final AlertDialog dialog;

    private DepartmentFilterDialog(AlertDialog dialog) {
        this.dialog = dialog;
    }

    static DepartmentFilterDialog show(Context context, List<Long> ids, List<String> names,
                                       long selectedId, LongConsumer selected) {
        List<Long> snapshot = new ArrayList<>(ids);
        AlertDialog dialog = DesignChoiceDialog.create(activityOf(context), "选择部门", "点选后关闭",
                names, snapshot.indexOf(selectedId), index -> selected.accept(snapshot.get(index)));
        ImmersiveUi.show(dialog);
        return new DepartmentFilterDialog(dialog);
    }

    static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        throw new IllegalArgumentException("列表弹窗需要 Activity");
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void dismiss() {
        dialog.dismiss();
    }
}
