package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;
import static cc.huoyan.opensource.morningcheck.data.Jsons.put;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;

import cc.huoyan.opensource.morningcheck.data.Jsons;
import cc.huoyan.opensource.morningcheck.data.StaffPolicy;
import cc.huoyan.opensource.morningcheck.domain.HealthDateSelection;

/**
 * 员工新增与编辑弹窗共用表单；仅写资料字段，保留旋转草稿及并发校验。
 */
final class StaffProfileEditor {
    private final LoadingDialog loading;
    private DepartmentFilterDialog departmentDialog;
    private final BaseMorningActivity host;
    private final Saved saved;
    private final Runnable cancelled;
    private final long staffId;
    private View content;
    private boolean active = true;
    private long pendingSavedId;
    private JSONObject savingProfile;
    private boolean recoveringSave;

    interface Saved {
        void accept(long staffId);
    }

    StaffProfileEditor(BaseMorningActivity host, long staffId, Bundle state, Saved saved, Runnable cancelled) {
        this.host = host;
        this.loading = new LoadingDialog(host);
        this.staffId = staffId;
        this.saved = saved;
        this.cancelled = cancelled;
        restore(state);
    }

    HealthExpiryDialog dateDialog() {
        return dateDialog;
    }

    boolean isSaving() {
        return saving;
    }

    void resume() {
        if (!active || host.destroyed || !host.foreground) return;
        if (pendingSavedId > 0) deliverSaved();
        else updateUi();
    }

    private void deliverSaved() {
        if (!active || host.destroyed || !host.foreground || pendingSavedId <= 0) return;
        long value = pendingSavedId;
        pendingSavedId = 0;
        host.toast("员工资料已保存");
        saved.accept(value);
    }

    void close() {
        active = false;
        loading.dismiss();
        if (departmentDialog != null) departmentDialog.dismiss();
        readVersion++;
        if (dateDialog != null) {
            dateDialog.setOnDismissListener(null);
            dateDialog.dismiss();
            dateDialog = null;
        }
        content = null;
        nameInput = null;
        numberInput = null;
        departmentInput = null;
        healthInput = null;
    }

    void requestCancel() {
        if (!active || host.destroyed || !host.foreground) return;
        if (saving || recoveringSave) {
            loading.show("正在保存员工资料");
            return;
        }
        captureDraft();
        boolean changed = expectedProfile == null
                ? !nameDraft.isEmpty() || !numberDraft.isEmpty() || !departmentDraft.isEmpty() || !healthDraft.isEmpty()
                : !nameDraft.equals(expectedProfile.optString("name"))
                  || !numberDraft.equals(expectedProfile.optString("staff_no"))
                  || !departmentDraft.equals(expectedProfile.optString("department"))
                  || departmentIdDraft != expectedProfile.optLong("department_id", 0)
                  || !healthDraft.equals(expectedProfile.optString("health_expiry"));
        if (!changed) {
            cancelled.run();
            return;
        }
        ImmersiveUi.show(new ProjectDialog.Builder(host)
                .setTitle("放弃本次修改？").setMessage("未保存的员工资料将丢弃。")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃修改", (dialog, which) -> cancelled.run()).create());
    }

    void setInitialProfile(JSONObject current) {
        if (current == null || draftReady) return;
        expectedProfile = obj("name", current.optString("name"), "department", current.optString("department"),
                "department_id", current.optLong("department_id", 0),
                "staff_no", current.optString("staff_no"), "health_expiry", current.optString("health_expiry"));
        nameDraft = current.optString("name");
        numberDraft = current.optString("staff_no");
        departmentDraft = current.optString("department");
        departmentIdDraft = current.optLong("department_id", 0);
        healthDraft = current.optString("health_expiry");
        draftReady = true;
    }

    private JSONObject expectedProfile;
    private String nameDraft = "", numberDraft = "", departmentDraft = "", healthDraft = "", feedback = "";
    private long departmentIdDraft;
    private boolean draftReady, formLoading, saving, missingEmployee;
    private int readVersion;
    private EditText nameInput, numberInput;
    private TextView departmentInput, healthInput;
    private HealthExpiryDialog dateDialog;
    private HealthDateSelection dateSelection;
    private boolean dateOpen;
    private int selectedField;
    private int restoredFocus = -1, restoredSelection;
    private static final String[] FIELD_NAMES = {"姓名", "工号", "部门", "健康证"};
    private static final int[] FIELD_GROUPS = {R.id.staff_edit_group_name,
            R.id.staff_edit_group_number, R.id.staff_edit_group_department, R.id.staff_edit_group_health};

    private void restore(Bundle state) {
        if (state == null) return;
        draftReady = state.getBoolean("draft_ready");
        nameDraft = state.getString("draft_name", "");
        numberDraft = state.getString("draft_number", "");
        departmentDraft = state.getString("draft_department", "");
        departmentIdDraft = state.getLong("draft_department_id", 0);
        healthDraft = state.getString("draft_health", "");
        feedback = state.getString("feedback", "");
        selectedField = Math.max(0, Math.min(3, state.getInt("selected_field", 0)));
        dateOpen = state.getBoolean("date_open");
        if (dateOpen) dateSelection = new HealthDateSelection(state.getString("date_selected", ""),
                state.getString("date_month", ""), LocalDate.now());
        restoredFocus = state.getInt("focused_field", -1);
        restoredSelection = state.getInt("field_selection", 0);
        if (staffId > 0) pendingSavedId = state.getLong("pending_saved_id", 0);
        try {
            String pending = state.getString("saving_profile", "");
            if (staffId > 0 && !pending.isEmpty()) {
                savingProfile = new JSONObject(pending);
                recoveringSave = true;
            }
            String raw = state.getString("expected_profile", "");
            if (!raw.isEmpty()) expectedProfile = new JSONObject(raw);
        } catch (Exception invalid) {
            draftReady = false;
        }
    }

    void beforeRender() {
        captureDraft();
        captureFocus();
        if (dateDialog != null) {
            // 原地旋转重建 XML 时保留日历草稿，随后按新方向重新打开。
            dateDialog.setOnDismissListener(null);
            dateDialog.dismiss();
            dateDialog = null;
        }
    }

    private void captureFocus() {
        if (nameInput == null) return;
        restoredFocus = -1;
        TextView[] values = fieldValues();
        for (int i = 0; i < values.length; i++)
            if (values[i].hasFocus()) {
                restoredFocus = i;
                // 平板完整表单切成手机分项表单时，保留正在编辑的资料项。
                selectedField = i;
                if (values[i] instanceof EditText)
                    restoredSelection = values[i].getSelectionStart();
            }
    }

    private TextView[] fieldValues() {
        return new TextView[]{nameInput, numberInput, departmentInput, healthInput};
    }

    private void captureDraft() {
        if (nameInput == null) return;
        nameDraft = nameInput.getText().toString();
        numberDraft = numberInput.getText().toString();
    }

    void saveState(Bundle state) {
        captureDraft();
        captureFocus();
        state.putBoolean("draft_ready", draftReady);
        state.putString("draft_name", nameDraft);
        state.putString("draft_number", numberDraft);
        state.putString("draft_department", departmentDraft);
        state.putLong("draft_department_id", departmentIdDraft);
        state.putString("draft_health", healthDraft);
        state.putBoolean("date_open", dateOpen);
        if (dateSelection != null) {
            state.putString("date_selected", dateSelection.selected().toString());
            state.putString("date_month", dateSelection.month().toString());
        }
        state.putString("feedback", feedback);
        state.putInt("selected_field", selectedField);
        state.putInt("focused_field", restoredFocus);
        state.putInt("field_selection", restoredSelection);
        if (expectedProfile != null)
            state.putString("expected_profile", expectedProfile.toString());
        state.putLong("pending_saved_id", pendingSavedId);
        if (savingProfile != null) state.putString("saving_profile", savingProfile.toString());
    }

    void bind(View root) {
        content = root;
        {
            ((TextView) host.findViewById(R.id.header_page_title)).setText(staffId > 0 ? "编辑员工" : "新增员工");
            TextView dialogTitle = host.findViewById(R.id.staff_edit_dialog_title);
            if (dialogTitle != null) dialogTitle.setText(staffId > 0 ? "编辑员工" : "新增员工");
            host.findViewById(R.id.header_home).setOnClickListener(view -> requestCancel());
            ((TextView) host.findViewById(R.id.header_back_label)).setText("返回员工");
            host.findViewById(R.id.header_home).setContentDescription("返回员工");
        }
        nameInput = content.findViewById(R.id.staff_edit_name);
        numberInput = content.findViewById(R.id.staff_edit_number);
        healthInput = content.findViewById(R.id.staff_edit_health);
        departmentInput = content.findViewById(R.id.staff_edit_department_value);
        showDraft();
        healthInput.setOnClickListener(view -> openDatePicker());
        departmentInput.setOnClickListener(view -> {
            if (!host.foreground || host.navigating || host.isFinishing() || saving || formLoading || missingEmployee)
                return;
            captureDraft();
            loading.show("正在读取部门");
            host.runIo(() -> {
                try {
                    java.util.List<JSONObject> options = host.store.departmentOptions(0);
                    host.main.post(() -> {
                        loading.dismiss();
                        if (!active || !host.foreground || host.destroyed) return;
                        java.util.List<Long> ids = new java.util.ArrayList<>();
                        java.util.List<String> names = new java.util.ArrayList<>();
                        ids.add(0L);
                        names.add("不分配部门");
                        for (JSONObject option : options) {
                            ids.add(option.optLong("id"));
                            names.add(option.optString("path", option.optString("name")));
                        }
                        departmentDialog = DepartmentFilterDialog.show(host, ids, names, departmentIdDraft, id -> {
                            departmentIdDraft = id;
                            departmentDraft = id == 0 ? "" : names.get(ids.indexOf(id));
                            showDepartment();
                        });
                    });
                } catch (Exception error) {
                    host.main.post(() -> {
                        loading.dismiss();
                        notice(host.failure(error));
                    });
                }
            });
        });
        content.findViewById(R.id.staff_edit_clear_department).setOnClickListener(view -> {
            departmentDraft = "";
            departmentIdDraft = 0;
            showDepartment();
        });
        content.findViewById(R.id.staff_edit_save).setOnClickListener(view -> saveProfile());
        content.findViewById(R.id.staff_edit_cancel).setOnClickListener(view -> requestCancel());
        content.findViewById(R.id.staff_edit_reload).setOnClickListener(view -> {
            draftReady = false;
            expectedProfile = null;
            feedback = "";
            reload(false, null);
        });
        content.findViewById(R.id.staff_edit_previous_field).setOnClickListener(view -> selectField(selectedField - 1, true));
        content.findViewById(R.id.staff_edit_next_field).setOnClickListener(view -> selectField(selectedField + 1, true));
        showField();
        updateUi();
        if (dateOpen) content.post(this::openDatePicker);
        if (restoredFocus >= 0 && restoredFocus < fieldValues().length) {
            TextView restored = fieldValues()[restoredFocus];
            if (restored.isEnabled() && restored.isShown()) {
                restored.requestFocus();
                if (restored instanceof EditText) ((EditText) restored).setSelection(Math.max(0,
                        Math.min(restoredSelection, restored.getText().length())));
            }
        }
    }

    private void selectField(int index, boolean focus) {
        if (!steppedForm() || index < 0 || index >= FIELD_GROUPS.length || saving || formLoading)
            return;
        captureDraft();
        selectedField = index;
        showField();
        TextView selected = fieldValues()[index];
        if (focus && selected.isEnabled()) {
            selected.requestFocus();
        }
        updateUi();
    }

    private void showField() {
        boolean phone = steppedForm();
        content.findViewById(R.id.staff_edit_field_navigation).setVisibility(phone ? View.VISIBLE : View.GONE);
        for (int i = 0; i < FIELD_GROUPS.length; i++)
            content.findViewById(FIELD_GROUPS[i]).setVisibility(!phone || selectedField == i ? View.VISIBLE : View.GONE);
        ((TextView) content.findViewById(R.id.staff_edit_field_position)).setText((selectedField + 1) + "/4 · " + FIELD_NAMES[selectedField]);
    }

    private boolean steppedForm() {
        if (host instanceof StaffEditActivity && ((StaffEditActivity) host).isDialogEditor())
            return false;
        return host.getResources().getBoolean(R.bool.mc_phone);
    }

    private void invalidField(int index, EditText input, String message) {
        if (steppedForm()) {
            selectedField = index;
            showField();
            updateUi();
        }
        input.requestFocus();
        ToastEx.showFailureToast(host, message);
    }

    private void showDraft() {
        nameInput.setText(nameDraft);
        numberInput.setText(numberDraft);
        showHealthDate();
        showDepartment();
    }

    private void showHealthDate() {
        healthInput.setText(healthDraft.isEmpty() ? "选择有效期（可留空）" : healthDraft);
    }

    private void openDatePicker() {
        if (!active || host.destroyed || !host.foreground || saving || formLoading || missingEmployee || !draftReady || dateDialog != null)
            return;
        if (!dateOpen || dateSelection == null)
            dateSelection = new HealthDateSelection(healthDraft, "", LocalDate.now());
        dateOpen = true;
        dateDialog = new HealthExpiryDialog(host, dateSelection, chosen -> {
            healthDraft = chosen;
            showHealthDate();
        });
        dateDialog.setOnDismissListener(dialog -> {
            dateOpen = false;
            dateDialog = null;
            dateSelection = null;
        });
        ImmersiveUi.show(dateDialog);
    }

    private void showDepartment() {
        departmentInput.setText(departmentDraft.isEmpty() ? "选择部门（可不分配）" : departmentDraft);
    }


    void stop() {
        loading.dismiss();
        if (departmentDialog != null) departmentDialog.dismiss();
        readVersion++;
        formLoading = false;
        if (dateDialog != null) {
            dateDialog.dismiss();
            dateDialog = null;
        }
    }

    void reload(boolean recover, Runnable done) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.main.post(() -> reload(recover, done));
            return;
        }
        if (!active || host.destroyed || !host.foreground || saving) return;
        if (staffId <= 0) {
            draftReady = true;
            host.loaded = true;
            formLoading = false;
            updateUi();
            if (done != null) done.run();
            return;
        }
        int version = ++readVersion;
        formLoading = true;
        updateUi();
        host.runIo(() -> {
            try {
                JSONObject current = host.store.staff(staffId);
                host.main.post(() -> {
                    if (!active || host.destroyed || !host.foreground || version != readVersion)
                        return;
                    if (recoveringSave) {
                        // 业务存储为单线程队列；恢复读取排在已提交保存之后，确认结果而不再次提交旧草稿。
                        boolean persisted = current != null && profileMatches(current, savingProfile);
                        recoveringSave = false;
                        savingProfile = null;
                        if (persisted) {
                            formLoading = false;
                            pendingSavedId = staffId;
                            deliverSaved();
                            return;
                        }
                        notice("上次保存未完成，草稿已保留，请检查后重试。");
                    }
                    missingEmployee = current == null;
                    if (missingEmployee) notice("员工档案已删除，请返回员工管理。");
                    else if (!draftReady) {
                        expectedProfile = obj("name", current.optString("name"), "department", current.optString("department"),
                                "department_id", current.optLong("department_id", 0),
                                "staff_no", current.optString("staff_no"), "health_expiry", current.optString("health_expiry"));
                        nameDraft = current.optString("name");
                        numberDraft = current.optString("staff_no");
                        departmentDraft = current.optString("department");
                        departmentIdDraft = current.optLong("department_id", 0);
                        healthDraft = current.optString("health_expiry");
                        draftReady = true;
                        showDraft();
                    }
                    formLoading = false;
                    host.loaded = true;
                    updateUi();
                    if (done != null) done.run();
                });
            } catch (Exception error) {
                host.main.post(() -> {
                    if (!active || host.destroyed || !host.foreground || version != readVersion)
                        return;
                    formLoading = false;
                    missingEmployee = true;
                    notice("员工读取失败：" + host.failure(error));
                });
            }
        });
    }

    void notice(String value) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.main.post(() -> notice(value));
            return;
        }
        if (!active || host.destroyed || !host.foreground) return;
        feedback = value == null ? "保存失败，请重试" : value;
        ToastEx.showFailureToast(host, feedback);
        updateUi();
    }

    void updateUi() {
        if (!active || content == null || nameInput == null || host.destroyed) return;
        boolean enabled = draftReady && !formLoading && !saving && !recoveringSave && !missingEmployee;
        if (enabled && dateOpen && dateDialog == null && host.foreground)
            content.post(this::openDatePicker);
        nameInput.setEnabled(enabled);
        boolean designDialog = host instanceof StaffEditActivity && ((StaffEditActivity) host).isDialogEditor();
        numberInput.setEnabled(enabled && (!designDialog || staffId <= 0));
        healthInput.setEnabled(enabled);
        departmentInput.setEnabled(enabled);
        content.findViewById(R.id.staff_edit_clear_department).setEnabled(enabled);
        content.findViewById(R.id.staff_edit_previous_field).setEnabled(enabled && selectedField > 0);
        content.findViewById(R.id.staff_edit_next_field).setEnabled(enabled && selectedField < 3);
        View save = content.findViewById(R.id.staff_edit_save);
        save.setEnabled(enabled);
        save.setAlpha(enabled ? 1f : 0.45f);
        ((TextView) save).setText(designDialog ? "确定" : "保存员工");
        content.findViewById(R.id.staff_edit_feedback).setVisibility(View.GONE);
        content.findViewById(R.id.staff_edit_reload).setVisibility(View.GONE);
        if (host.foreground && (formLoading || saving || recoveringSave))
            loading.show(formLoading ? "正在读取员工资料" : "正在保存员工资料");
        else loading.dismiss();
    }

    private static boolean profileMatches(JSONObject current, JSONObject expected) {
        if (expected == null) return false;
        if (current.optLong("department_id", 0) != expected.optLong("department_id", 0))
            return false;
        for (String key : new String[]{"name", "staff_no", "health_expiry"})
            if (!current.optString(key).equals(expected.optString(key))) return false;
        // 已选部门的路径由存储按 ID 更新；改名或调整上级不能把已落库的保存误判为失败。
        return expected.optLong("department_id", 0) > 0
                || current.optString("department").equals(expected.optString("department"));
    }

    private void saveProfile() {
        if (!active || host.destroyed || !host.foreground || saving || formLoading || recoveringSave || missingEmployee || !draftReady)
            return;
        captureDraft();
        if (nameDraft.trim().isEmpty()) {
            invalidField(0, nameInput, "请输入姓名");
            return;
        }
        final String staffNumber;
        try {
            staffNumber = StaffPolicy.requireStaffNumber(numberDraft);
        } catch (IllegalArgumentException invalid) {
            invalidField(1, numberInput, invalid.getMessage());
            return;
        }
        String expiry = healthDraft.trim();
        if (!expiry.isEmpty()) {
            try {
                expiry = LocalDate.parse(expiry).toString();
            } catch (Exception invalid) {
                notice("健康证日期无效，请重新选择日期");
                return;
            }
        }
        final JSONObject changes = obj("name", nameDraft.trim(), "department", departmentDraft.trim(),
                "department_id", departmentIdDraft,
                "staff_no", staffNumber, "health_expiry", expiry);
        final JSONObject expected = expectedProfile == null ? null : Jsons.copy(expectedProfile);
        saving = true;
        savingProfile = Jsons.copy(changes);
        feedback = "";
        updateUi();
        host.runIo(() -> {
            try {
                final long savedId;
                if (staffId <= 0) {
                    JSONObject value = Jsons.copy(changes);
                    put(value, "created_at", System.currentTimeMillis());
                    put(value, "enabled", true);
                    put(value, "face_registered", false);
                    savedId = host.store.createStaffProfile(value);
                } else {
                    if (expected == null)
                        throw new IllegalStateException("请重新读取员工资料后保存");
                    // 仅比较并修改本页资料字段；人脸回执及启停按钮不会被旧表单覆盖。
                    host.store.patchStaffProfile(staffId, expected, changes);
                    savedId = staffId;
                }
                host.main.post(() -> {
                    saving = false;
                    savingProfile = null;
                    host.application.notifyStaffChanged();
                    if (!active || host.destroyed) return;
                    pendingSavedId = savedId;
                    deliverSaved();
                });
            } catch (Exception error) {
                host.main.post(() -> {
                    saving = false;
                    savingProfile = null;
                    if (!active || host.destroyed) return;
                    String message = host.failure(error);
                    feedback = message;
                    if (!host.foreground) return;
                    if (message.contains("工号")) invalidField(1, numberInput, message);
                    else notice(message);
                    updateUi();
                });
            }
        });
    }
}
