package cc.huoyan.opensource.morningcheck;

import android.os.Bundle;

/**
 * 员工新增与列表编辑弹窗，共用现有资料编辑器。
 */
public final class StaffEditActivity extends BaseMorningActivity {
    public static final String EXTRA_STAFF_ID = "staff_id";
    public static final String EXTRA_DIALOG = "dialog_editor";
    private StaffProfileEditor editor;
    private long existingStaffId;

    @Override
    protected String pageKey() {
        return "staff_edit";
    }

    @Override
    protected int activityLayout() {
        return isDialogEditor() ? R.layout.activity_staff_editor_dialog : R.layout.activity_staff_edit;
    }

    boolean isDialogEditor() {
        return getIntent().getBooleanExtra(EXTRA_DIALOG, false);
    }

    @Override
    public void onCreate(Bundle state) {
        setTheme(isDialogEditor() ? R.style.McStaffEditorTheme : R.style.AppTheme);
        super.onCreate(state);
    }

    @Override
    protected void setup(Bundle state) {
        existingStaffId = getIntent().getLongExtra(EXTRA_STAFF_ID, 0);
        editor = new StaffProfileEditor(this, existingStaffId, state, savedId -> {
            finish();
        }, this::finish);
    }

    @Override
    protected void bindPage() {
        editor.bind(content);
    }

    @Override
    protected void beforeRender() {
        if (editor != null) editor.beforeRender();
    }

    @Override
    protected void onDataLoaded() {
        editor.updateUi();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        editor.reload(recover, done);
    }

    @Override
    protected void notice(String value) {
        editor.notice(value);
    }

    @Override
    public void onBackPressed() {
        editor.requestCancel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        editor.resume();
    }

    @Override
    protected void onStop() {
        editor.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        editor.close();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        editor.saveState(state);
        super.onSaveInstanceState(state);
    }
}
