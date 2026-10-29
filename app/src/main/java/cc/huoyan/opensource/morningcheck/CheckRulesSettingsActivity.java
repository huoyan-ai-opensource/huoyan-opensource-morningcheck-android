package cc.huoyan.opensource.morningcheck;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 晨检规则设置：独立 Activity 直接绑定对应 XML。
 */
public final class CheckRulesSettingsActivity extends BaseDeviceSettingsActivity {
    private EditText temperatureInput;
    private TextWatcher temperatureWatcher;
    private boolean restoringForm;

    @Override
    protected int settingsSection() {
        return 5;
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_rules_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_rules_design;
    }

    @Override
    protected void bindRegular(View form) {
        bindSettingsRules(form);
    }

    @Override
    protected void bindCompact(View form) {
        bindSettingsRules(form);
    }

    private void bindSettingsRules(View form) {
        bindRule(form, R.id.settings_head, R.id.rules_head_row, "head");
        bindRule(form, R.id.settings_hat, R.id.rules_hat_row, "hat");
        bindRule(form, R.id.settings_mask, R.id.rules_mask_row, "mask");
        bindRule(form, R.id.settings_temperature, R.id.rules_temperature_row, "temperature");
        temperatureInput = settingsInput(form, R.id.settings_temperature_limit, "temperature_limit", "37.3");
        EditText input = temperatureInput;
        input.setFocusable(false);
        input.setOnClickListener(view -> showTemperatureEditor());
        temperatureWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (restoringForm || form != formView || input != temperatureInput || !uiReady())
                    return;
                updateTemperatureStatus(form, true);
            }
        };
        input.addTextChangedListener(temperatureWatcher);
        refreshRuleStates(form);
        updateTemperatureStatus(form, false);
    }

    private void bindRule(View form, int switchId, int rowId, String key) {
        Switch toggle = form.findViewById(switchId);
        toggle.setChecked(rule(key, true));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (form != formView || !uiReady()) return;
            prefs.edit().putBoolean(key, checked).apply();
            refreshRuleStates(form);
        });
        form.findViewById(rowId).setOnClickListener(view -> {
            if (form == formView && uiReady() && toggle.isEnabled()) toggle.toggle();
        });
    }

    private void refreshRuleStates(View form) {
        boolean head = ((Switch) form.findViewById(R.id.settings_head)).isChecked();
        showRuleState(form, R.id.settings_head, R.id.rules_head_state,
                R.string.settings_head_full_label, head ? R.string.rules_enabled : R.string.rules_disabled);
        for (int[] row : new int[][]{
                {R.id.settings_hat, R.id.rules_hat_state, R.id.rules_hat_row, R.string.rules_hat},
                {R.id.settings_mask, R.id.rules_mask_state, R.id.rules_mask_row, R.string.rules_mask}}) {
            Switch toggle = form.findViewById(row[0]);
            toggle.setEnabled(head);
            form.findViewById(row[2]).setAlpha(head ? 1f : .5f);
            showRuleState(form, row[0], row[1], row[3], toggle.isChecked()
                    ? R.string.rules_wear : R.string.rules_do_not_wear);
        }
        showRuleState(form, R.id.settings_temperature, R.id.rules_temperature_state,
                R.string.settings_temperature_label,
                ((Switch) form.findViewById(R.id.settings_temperature)).isChecked()
                        ? R.string.rules_enabled : R.string.rules_disabled);
    }

    private void showRuleState(View form, int switchId, int stateId, int labelId, int stateTextId) {
        ((TextView) form.findViewById(stateId)).setText(stateTextId);
        form.findViewById(switchId).setContentDescription(getString(labelId) + "，" + getString(stateTextId));
    }

    private void updateTemperatureStatus(View form, boolean save) {
        String text = temperatureInput.getText().toString().trim();
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException invalid) {
            value = Double.NaN;
        }
        boolean valid = text.matches("\\d+(\\.\\d+)?") && Double.isFinite(value) && value >= 36.1 && value <= 43;
        TextView status = form.findViewById(R.id.settings_temperature_limit_status);
        status.setVisibility(valid ? View.GONE : View.VISIBLE);
        if (valid) {
            String saved = String.valueOf(value);
            if (save && !saved.equals(setting("temperature_limit", "37.3")))
                prefs.edit().putString("temperature_limit", saved).apply();
            status.setText(R.string.rules_saved);
            status.setTextColor(getColorCompat(R.color.mc_muted));
        } else {
            // 空串和输入到一半的小数只是草稿，不得覆盖现场晨检使用的有效上限。
            status.setText(getString(R.string.rules_invalid_temperature, setting("temperature_limit", "37.3")));
            status.setTextColor(getColorCompat(R.color.mc_orange));
        }
    }

    private void showTemperatureEditor() {
        if (!uiReady() || temperatureInput == null) return;
        dismissFormDialog();
        View body = getLayoutInflater().inflate(R.layout.dialog_temperature_threshold, null);
        EditText draft = body.findViewById(R.id.threshold_draft);
        draft.setShowSoftInputOnFocus(false);
        draft.setText(temperatureInput.getText());
        // 数字编辑由本弹窗的按键承接。
        draft.setFocusable(false);
        final boolean[] replace = {true};
        for (String key : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "删除"}) {
            body.findViewWithTag("threshold:" + key).setOnClickListener(view -> {
                String value = draft.getText().toString();
                if ("删除".equals(key))
                    value = replace[0] ? "" : value.isEmpty() ? "" : value.substring(0, value.length() - 1);
                else {
                    if (replace[0]) value = "";
                    if (".".equals(key) && value.contains(".")) return;
                    value += ".".equals(key) && value.isEmpty() ? "0." : key;
                }
                replace[0] = false;
                draft.setText(value);
                body.findViewById(R.id.threshold_error).setVisibility(View.GONE);
            });
        }
        formDialog = new androidx.appcompat.app.AlertDialog.Builder(this).create();
        formDialog.setView(body, 0, 0, 0, 0);
        androidx.appcompat.app.AlertDialog shown = formDialog;
        body.findViewById(android.R.id.button2).setOnClickListener(view -> shown.cancel());
        body.findViewById(android.R.id.button1).setOnClickListener(view -> {
            if (!uiReady() || shown != formDialog || temperatureInput == null) return;
            String text = draft.getText().toString();
            double value;
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException error) {
                value = Double.NaN;
            }
            if (!text.matches("\\d+(\\.\\d+)?") || !Double.isFinite(value) || value < 36.1 || value > 43) {
                body.findViewById(R.id.threshold_error).setVisibility(View.VISIBLE);
                return;
            }
            temperatureInput.setText(text);
            shown.dismiss();
        });
        shown.setOnShowListener(ignored -> {
            if (shown.getWindow() == null) return;
            int width = Math.min(getWindow().getDecorView().getWidth() - getResources().getDimensionPixelSize(R.dimen.dp_20) * 2,
                    getResources().getDimensionPixelSize(R.dimen.dp_260));
            shown.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            shown.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        });
        ImmersiveUi.show(shown);
    }

    private int getColorCompat(int id) {
        return androidx.core.content.ContextCompat.getColor(this, id);
    }

    @Override
    protected void restoreForm(View view) {
        // 重排和系统恢复只还原草稿，不能被输入监听器当作新修改再次保存。
        boolean wasRestoring = restoringForm;
        restoringForm = true;
        super.restoreForm(view);
        restoringForm = wasRestoring;
        if (view == formView && temperatureInput != null) updateTemperatureStatus(view, false);
    }

    @Override
    protected void releaseSettingsView() {
        if (temperatureInput != null && temperatureWatcher != null)
            temperatureInput.removeTextChangedListener(temperatureWatcher);
        temperatureInput = null;
        temperatureWatcher = null;
    }
}
