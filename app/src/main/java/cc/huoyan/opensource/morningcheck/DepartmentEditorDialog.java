package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.opensource.morningcheck.data.AppStore;

/**
 * 部门表单固定显示在大号弹窗内，写入任务和草稿由 Fragment 的 ViewModel 持有。
 */
public final class DepartmentEditorDialog extends DialogFragment {
    public static final String TAG = "department_editor";
    private static final String ID = "department_id";
    private static final String PARENT_ID = "department_parent_id";
    private EditorState editor;
    private View panel;
    private EditText name;
    private Spinner parent;
    private List<JSONObject> displayedOptions;
    private TextView title, members, hint;
    private View save, cancel;
    private AlertDialog confirmation;
    private boolean binding;
    private LoadingDialog loading;
    private String shownMessage = "";

    public static void open(FragmentManager manager, long id) {
        open(manager, id, 0);
    }

    public static void open(FragmentManager manager, long id, long parentId) {
        if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null) return;
        DepartmentEditorDialog dialog = new DepartmentEditorDialog();
        Bundle arguments = new Bundle();
        arguments.putLong(ID, id);
        arguments.putLong(PARENT_ID, parentId);
        dialog.setArguments(arguments);
        dialog.showNow(manager, TAG);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setCancelable(false);
        editor = new ViewModelProvider(this).get(EditorState.class);
        editor.initialize(requireArguments().getLong(ID), requireArguments().getLong(PARENT_ID), state);
        editor.changed.observe(this, ignored -> showState());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle state) {
        AppCompatDialog dialog = new AppCompatDialog(requireContext()) {
            @Override
            public boolean onTouchEvent(@NonNull MotionEvent event) {
                Window window = getWindow();
                View decor = window == null ? null : window.getDecorView();
                boolean outside = event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                        || event.getActionMasked() == MotionEvent.ACTION_DOWN && decor != null
                        && (event.getX() < 0 || event.getY() < 0
                        || event.getX() >= decor.getWidth() || event.getY() >= decor.getHeight());
                if (outside) {
                    requestClose();
                    return true;
                }
                return super.onTouchEvent(event);
            }
        };
        dialog.getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requestClose();
            }
        });
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((window, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) requestClose();
            return true;
        });
        panel = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_department_editor, null);
        dialog.setContentView(panel);
        bindForm();
        return dialog;
    }

    private void bindForm() {
        name = panel.findViewById(R.id.department_edit_name);
        parent = panel.findViewById(R.id.department_edit_parent);
        title = panel.findViewById(R.id.department_edit_title);
        members = panel.findViewById(R.id.department_edit_members);
        hint = panel.findViewById(R.id.department_edit_hint);
        save = panel.findViewById(R.id.department_edit_save);
        cancel = panel.findViewById(R.id.department_edit_cancel);
        name.setText(editor.draft);
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!binding) editor.draft = text.toString();
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        parent.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapter, View view, int position, long id) {
                if (!binding && !editor.loading && !editor.missing && displayedOptions != null
                        && position >= 0 && position <= displayedOptions.size()) {
                    editor.parentId = position == 0 ? 0 : displayedOptions.get(position - 1).optLong("id");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapter) {
            }
        });
        save.setOnClickListener(view -> editor.save());
        cancel.setOnClickListener(view -> requestClose());
        hint.setOnClickListener(view -> {
            if (!editor.busy()) editor.load();
        });
        showState();
    }

    @Override
    public void onStart() {
        super.onStart();
        resizeWindow();
        Dialog dialog = getDialog();
        if (dialog != null) {
            ImmersiveUi.hideSystemBars(dialog.getWindow());
        }
        showState();
    }

    @Override
    public void onResume() {
        super.onResume();
        showState();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // 宿主自行处理横竖屏变化；保留输入目标实例，只重排窗口和留白。
        resizeWindow();
        if (panel != null) panel.requestLayout();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        editor.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        if (loading != null) loading.dismiss();
        panel = null;
        name = null;
        parent = null;
        displayedOptions = null;
        title = members = hint = null;
        save = cancel = null;
        super.onDestroyView();
    }

    private void showState() {
        if (panel == null || !isAdded()) return;
        if (editor.completed) {
            if (loading != null) loading.dismiss();
            if (isResumed()) ToastEx.showSuccessToast(requireContext(), getString(R.string.i18n_a_department_saved));
            if (isResumed() && !getParentFragmentManager().isStateSaved()) dismiss();
            return;
        }
        title.setText(editor.id == 0 ? R.string.department_create_title : R.string.department_edit_title);
        if (!name.getText().toString().equals(editor.draft)) {
            binding = true;
            name.setText(editor.draft);
            name.setSelection(name.length());
            binding = false;
        }
        if (displayedOptions != editor.options) {
            binding = true;
            displayedOptions = editor.options;
            ArrayList<String> labels = new ArrayList<>();
            labels.add(getString(R.string.department_parent_root));
            for (JSONObject option : displayedOptions)
                labels.add(option.optString("path", option.optString("name")));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.item_spinner_value, labels);
            adapter.setDropDownViewResource(R.layout.item_spinner_option);
            parent.setAdapter(adapter);
            int position = 0;
            for (int index = 0; index < displayedOptions.size(); ++index) {
                if (displayedOptions.get(index).optLong("id") == editor.parentId)
                    position = index + 1;
            }
            parent.setSelection(position);
            binding = false;
        }
        members.setText(editor.id == 0 ? getString(R.string.department_local_hint)
                : getString(R.string.department_counts, editor.memberCount, editor.childCount));
        if (loading == null) loading = new LoadingDialog(requireActivity());
        if (isResumed() && editor.busy())
            loading.show(editor.saving ? getString(R.string.i18n_a_saving_department) : getString(R.string.i18n_a_loading_departments));
        else loading.dismiss();
        if (isResumed() && !editor.message.isEmpty() && !editor.message.equals(shownMessage)) {
            ToastEx.showFailureToast(requireContext(), UiMessages.text(requireContext(), editor.message));
            shownMessage = editor.message;
        }
        if (editor.message.isEmpty()) shownMessage = "";
        boolean enabled = editor.draftReady && editor.optionsReady && !editor.busy() && !editor.missing;
        name.setEnabled(enabled);
        parent.setEnabled(enabled);
        save.setEnabled(enabled);
        save.setAlpha(enabled ? 1f : 0.45f);
        members.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
        cancel.setEnabled(!editor.busy());
        cancel.setAlpha(editor.busy() ? 0.45f : 1f);
        if (isResumed()) resizeWindow();
    }

    private boolean canShowConfirmation() {
        return isResumed() && !editor.busy() && !editor.completed
                && !getParentFragmentManager().isStateSaved()
                && (confirmation == null || !confirmation.isShowing());
    }

    private void requestClose() {
        if (!canShowConfirmation()) return;
        if (!editor.dirty()) {
            dismiss();
            return;
        }
        showConfirmation(new ProjectDialog.Builder(requireContext())
                .setTitle(R.string.department_dialog_discard_title)
                .setMessage(R.string.department_dialog_discard_message)
                .setNegativeButton(R.string.department_dialog_keep_editing, null)
                .setPositiveButton(R.string.department_dialog_discard_confirm, (dialog, which) -> {
                    if (!editor.busy() && !getParentFragmentManager().isStateSaved()) dismiss();
                }).create());
    }


    private void showConfirmation(AlertDialog dialog) {
        confirmation = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (confirmation == dialog) confirmation = null;
        });
        ImmersiveUi.show(dialog);
    }

    private void resizeWindow() {
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null || panel == null) return;
        Window window = dialog.getWindow();
        Rect bounds = availableWindowBounds();
        LinearLayout fields = panel.findViewById(R.id.department_edit_fields);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.dp_12);
        panel.setPadding(padding, padding, padding, padding);
        int width = Math.min(bounds.width() - padding * 2,
                getResources().getDimensionPixelSize(R.dimen.dp_260)
                        + getResources().getDimensionPixelSize(R.dimen.dp_20));
        int height = Math.min(bounds.height() - padding * 2,
                getResources().getDimensionPixelSize(R.dimen.dp_180));
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setLayout(width, Math.max(1, height));
        ImmersiveUi.enable(window);
    }

    private Rect availableWindowBounds() {
        Activity host = requireActivity();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = host.getWindowManager().getCurrentWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            android.graphics.Insets fixed = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.captionBar());
            bounds.left += fixed.left;
            bounds.top += fixed.top;
            bounds.right -= fixed.right;
            bounds.bottom -= fixed.bottom;
            return bounds;
        }
        View decor = host.getWindow().getDecorView();
        if (decor.getWidth() > 0 && decor.getHeight() > 0) {
            Rect bounds = new Rect(0, 0, decor.getWidth(), decor.getHeight());
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
            if (insets != null) {
                Insets fixed = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()
                        | WindowInsetsCompat.Type.captionBar());
                bounds.left += fixed.left;
                bounds.top += fixed.top;
                bounds.right -= fixed.right;
                bounds.bottom -= fixed.bottom;
            }
            return bounds;
        }
        Configuration configuration = getResources().getConfiguration();
        float density = getResources().getDisplayMetrics().density;
        return new Rect(0, 0, Math.max(1, Math.round(configuration.screenWidthDp * density)),
                Math.max(1, Math.round(configuration.screenHeightDp * density)));
    }

    /**
     * 不持有宿主或 View；配置重建继续观察同一个写入任务，进程重启只恢复表单。
     */
    public static final class EditorState extends AndroidViewModel {
        final MutableLiveData<Integer> changed = new MutableLiveData<>(0);
        private final MorningApplication app;
        private final Handler main = new Handler(Looper.getMainLooper());
        long id, memberCount, childCount, parentId, originalParentId;
        List<JSONObject> options = new ArrayList<>();
        String draft = "", originalName = "", message = "";
        boolean initialized, draftReady, optionsReady, loading, saving, missing, completed, cleared;
        private int readVersion, revision;

        public EditorState(@NonNull Application application) {
            super(application);
            app = (MorningApplication) application;
        }

        void initialize(long id, long parentId, Bundle state) {
            if (initialized) return;
            initialized = true;
            this.id = state == null ? id : state.getLong(ID, id);
            this.parentId = parentId;
            this.originalParentId = parentId;
            draftReady = this.id == 0;
            if (state != null) {
                draft = state.getString("department_draft", "");
                originalName = state.getString("department_original", "");
                this.parentId = state.getLong(PARENT_ID, parentId);
                originalParentId = state.getLong("department_original_parent_id", parentId);
                draftReady = state.getBoolean("department_draft_ready", draftReady);
                message = state.getString("department_message", "");
            }
            if (this.id < 0) {
                missing = true;
                message = app.getString(R.string.department_dialog_invalid_id);
            } else load();
        }

        void saveState(Bundle state) {
            state.putLong(ID, id);
            state.putString("department_draft", draft);
            state.putString("department_original", originalName);
            state.putLong(PARENT_ID, parentId);
            state.putLong("department_original_parent_id", originalParentId);
            state.putBoolean("department_draft_ready", draftReady);
            state.putString("department_message", message);
        }

        boolean busy() {
            return loading || saving;
        }

        boolean dirty() {
            return draftReady && (!draft.equals(originalName) || parentId != originalParentId);
        }

        void publish() {
            if (!cleared) changed.setValue(++revision);
        }

        void load() {
            if (cleared || busy() || completed || id < 0) return;
            int version = ++readVersion;
            loading = true;
            publish();
            AppStore store = app.store;
            try {
                app.io.execute(() -> {
                    try {
                        JSONObject item = id == 0 ? null : store.department(id);
                        List<JSONObject> parents = store.departmentOptions(id);
                        main.post(() -> {
                            if (cleared || version != readVersion) return;
                            loading = false;
                            options = parents;
                            optionsReady = true;
                            missing = id > 0 && item == null;
                            if (missing)
                                message = app.getString(R.string.department_dialog_missing);
                            else if (id > 0) {
                                memberCount = item.optLong("member_count");
                                childCount = item.optLong("child_count");
                                if (!draftReady) {
                                    originalName = item.optString("name");
                                    originalParentId = item.optLong("parent_id");
                                    parentId = originalParentId;
                                    draft = originalName;
                                    draftReady = true;
                                    message = "";
                                } else if (!originalName.equals(item.optString("name"))
                                        || originalParentId != item.optLong("parent_id")) {
                                    missing = true;
                                    message = app.getString(R.string.department_dialog_changed);
                                } else message = "";
                            } else message = "";
                            boolean parentAvailable = parentId == 0;
                            for (JSONObject option : parents)
                                if (option.optLong("id") == parentId) parentAvailable = true;
                            if (!missing && !parentAvailable) {
                                missing = true;
                                message = app.getString(R.string.department_dialog_parent_missing);
                            }
                            publish();
                        });
                    } catch (Exception error) {
                        main.post(() -> readFailed(version, error));
                    }
                });
            } catch (RuntimeException error) {
                readFailed(version, error);
            }
        }

        private void readFailed(int version, Exception error) {
            if (cleared || version != readVersion) return;
            loading = false;
            message = app.getString(R.string.department_dialog_read_failed, failure(error));
            publish();
        }

        void save() {
            if (cleared || busy() || completed || !draftReady || !optionsReady || missing) return;
            String cleanName = draft.trim();
            if (cleanName.isEmpty()) {
                message = app.getString(R.string.department_name_hint);
                publish();
                return;
            }
            write(cleanName);
        }


        private void write(String newName) {
            saving = true;
            ++readVersion;
            publish();
            AppStore store = app.store;
            long target = id;
            String expectedName = originalName;
            long selectedParent = parentId;
            long expectedParent = originalParentId;
            try {
                app.io.execute(() -> {
                    try {
                        store.saveDepartment(target, newName, selectedParent,
                                target == 0 ? null : expectedName, expectedParent);
                        main.post(() -> {
                            // 写入即使在宿主退出后完成，也要通知当前前台列表；不重新提交该事务。
                            app.notifyStaffChanged();
                            app.notifyRecordsChanged();
                            saving = false;
                            completed = true;
                            publish();
                        });
                    } catch (Exception error) {
                        main.post(() -> writeFailed(error));
                    }
                });
            } catch (RuntimeException error) {
                writeFailed(error);
            }
        }

        private void writeFailed(Exception error) {
            saving = false;
            message = failure(error);
            publish();
        }

        private String failure(Exception error) {
            String detail = error.getMessage();
            return detail == null || detail.trim().isEmpty()
                    ? app.getString(R.string.department_dialog_error_fallback) : detail;
        }

        @Override
        protected void onCleared() {
            cleared = true;
            ++readVersion;
        }
    }
}
