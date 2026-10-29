package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;

import java.util.List;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;

/**
 * 设置页共用表单草稿和 SDK 会话；每个子类直接承载自己的 XML。
 */
public abstract class BaseDeviceSettingsActivity extends BaseMorningActivity {
    public static final String EXTRA_SECTION = "section";
    private static final String STATE_FORM = "settings.form";
    protected Bundle formState = new Bundle();
    protected View formView;
    protected AlertDialog formDialog;
    protected int viewGeneration;

    protected abstract int settingsSection();

    protected abstract int regularFormLayout();

    protected abstract int compactFormLayout();

    protected abstract void bindRegular(View form);

    protected abstract void bindCompact(View form);

    @Override
    protected final String pageKey() {
        return "settings";
    }

    @Override
    protected final int activityLayout() {
        return compact ? R.layout.activity_devices_compact : R.layout.activity_devices;
    }

    @Override
    protected final void setup(@Nullable Bundle state) {
        if (state != null && state.getBundle(STATE_FORM) != null)
            formState = new Bundle(state.getBundle(STATE_FORM));
        setupSettings(state);
    }

    protected void setupSettings(@Nullable Bundle state) {
    }

    protected void releaseSettingsView() {
    }

    protected void onSettingsResumed() {
    }

    protected boolean designPanels() {
        return false;
    }

    @Override
    protected final void beforeRender() {
        saveForm(formView);
        viewGeneration++;
        releaseSettingsView();
        // 密码窗口独立持有输入和键盘，随同一宿主旋转；离页仍由 onStop 销毁输入。
        if (!SecurityPasswordDialog.retainsInputOnReflow(formDialog)) dismissFormDialog();
        formView = null;
    }

    @Override
    protected final void bindPage() {
        TextView title = findViewById(R.id.header_page_title);
        title.setText(getResources().getStringArray(R.array.settings_sections)[settingsSection()]);
        ViewGroup body = content.findViewById(R.id.settings_body);
        if (designPanels()) {
            int inset = getResources().getDimensionPixelSize(R.dimen.dp_12);
            content.setPadding(inset, inset, inset, inset);
            body.setPadding(0, 0, 0, 0);
            body.setBackground(null);
        }
        formView = getLayoutInflater().inflate(compact ? compactFormLayout() : regularFormLayout(), body, false);
        body.addView(formView);
        if (compact) bindCompact(formView);
        else bindRegular(formView);
        restoreForm(formView);
        refreshStatus();
        // 手动横竖屏重排时 Activity 仍处于前台，新表单挂载后再恢复镜头等资源。
        int expectedView = viewGeneration;
        main.post(() -> {
            if (expectedView == viewGeneration && uiReady()) onSettingsResumed();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        navigating = false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        saveForm(formView);
        state.putBundle(STATE_FORM, new Bundle(formState));
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        int expectedView = viewGeneration;
        main.post(() -> {
            if (expectedView == viewGeneration && uiReady()) {
                refreshStatus();
                onSettingsResumed();
            }
        });
    }

    @Override
    protected void onPause() {
        saveForm(formView);
        super.onPause();
    }

    @Override
    protected void onStop() {
        dismissFormDialog();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        viewGeneration++;
        releaseSettingsView();
        dismissFormDialog();
        formView = null;
        super.onDestroy();
    }

    @Override
    protected void onDataLoaded() {
        refreshStatus();
    }

    @Override
    protected void onSdkStateChanged() {
        if (!destroyed && foreground) refreshStatus();
    }

    /**
     * 状态回执只更新状态文字，保留尚未提交的表单输入。
     */
    protected void refreshStatus() {
        View form = formView;
        if (destroyed || form == null) return;
        TextView connectionText = form.findViewById(R.id.settings_connection);
        if (connectionText != null) connectionText.setText(UiMessages.text(this, connection));
        TextView transport = form.findViewById(R.id.settings_sdk_transport);
        if (transport != null) transport.setText(getString(R.string.settings_sdk_transport_value,
                getResources().getStringArray(R.array.settings_sdk_transports)[MorningSdkSettings.transportIndex(prefs)]));
        EditText sdkUrl = form.findViewById(R.id.settings_sdk_url);
        if (sdkUrl != null) sdkUrl.setEnabled(MorningSdkSettings.transportIndex(prefs) != 1);
        MorningSdkSession.Operation operation = sdkSession.operation();
        View activate = form.findViewById(R.id.settings_activate);
        if (activate != null) {
            activate.setEnabled(sdk.isConnected() && !operation.running);
            activate.setAlpha(activate.isEnabled() ? 1f : .5f);
        }
    }

    /**
     * 重排与系统重建恢复输入草稿，即时保存的开关仍以配置存储为准。
     */
    protected void saveForm(@Nullable View view) {
        if (view == null) return;
        if (view instanceof EditText && view.getId() != View.NO_ID) {
            formState.putString("text:" + view.getId(), ((EditText) view).getText().toString());
        } else if (view instanceof Spinner && view.getId() != View.NO_ID) {
            formState.putInt("selection:" + view.getId(), ((Spinner) view).getSelectedItemPosition());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                saveForm(group.getChildAt(index));
        }
    }

    protected void restoreForm(View view) {
        String textKey = "text:" + view.getId();
        String selectionKey = "selection:" + view.getId();
        if (view instanceof EditText && formState.containsKey(textKey)) {
            ((EditText) view).setText(formState.getString(textKey, ""));
        } else if (view instanceof Spinner && formState.containsKey(selectionKey)) {
            Spinner spinner = (Spinner) view;
            int selected = formState.getInt(selectionKey, 0);
            if (selected >= 0 && selected < spinner.getCount()) spinner.setSelection(selected);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                restoreForm(group.getChildAt(index));
        }
    }

    protected String statusDetails() {
        MorningSdkState state = sdkSession.viewState();
        MorningSdkSession.Operation operation = sdkSession.operation();
        return UiMessages.text(this, state.title) + "\n" + UiMessages.text(this, state.detail)
                + (operationSummary(operation).isEmpty() ? "" : "\n\n" + UiMessages.text(this, operationSummary(operation)));
    }

    protected static String operationSummary(MorningSdkSession.Operation operation) {
        if (operation.running || operation.code != 0) return operation.message;
        return operation.label.isEmpty() ? "" : "上次" + operation.label + "请求已处理";
    }

    protected void showDetails(String title, String message, String kind) {
        if (!uiReady()) return;
        dismissFormDialog();
        if (SdkDetailsActivity.isDetailKind(kind)) {
            if (navigating || isFinishing() || !foreground) return;
            navigating = true;
            startActivity(new Intent(this, SdkDetailsActivity.class)
                    .putExtra(SdkDetailsActivity.EXTRA_KIND, kind));
            return;
        }
        View body = getLayoutInflater().inflate(R.layout.dialog_sdk_details, null);
        ((TextView) body.findViewById(R.id.sdk_details_text)).setText(UiMessages.text(this, message));
        formDialog = ImmersiveUi.show(new ProjectDialog.Builder(this).setTitle(title).setView(body)
                .setPositiveButton(getString(R.string.i18n_a_ok), null).create());
        AlertDialog shown = formDialog;
        formDialog.setOnDismissListener(dialog -> {
            if (formDialog == shown) formDialog = null;
        });
    }

    protected void onUi(int expectedView, Runnable action) {
        main.post(() -> {
            if (viewGeneration == expectedView && uiReady()) action.run();
        });
    }

    protected void editSetting(String label, String key, String fallback, boolean secret) {
        if (key.equals("sdk_url") && !allowConnectionChange()) return;
        typedPrompt(label, label, setting(key, fallback), settingInputType(key, secret), value -> {
            try {
                if (key.equals("sdk_url") && !allowConnectionChange()) return;
                if (key.equals("cloud_url") && !validHttp(value))
                    throw new IllegalArgumentException(getString(R.string.i18n_a_invalid_address));
                if (key.equals("sdk_url")) SdkConfig.webSocket(value);
                if ((key.equals("shop_id") || key.equals("device_id") || key.equals("baud")) && Long.parseLong(value) <= 0)
                    throw new IllegalArgumentException(getString(R.string.i18n_a_enter_a_positive_whole_number));
                if (key.equals("temperature_limit")) {
                    double n = Double.parseDouble(value);
                    if (!Double.isFinite(n) || n < 36.1 || n > 43)
                        throw new IllegalArgumentException(getString(R.string.i18n_a_valid_range_36_1_43_c));
                }
                if (key.equals("serial_path") && !value.startsWith("/dev/"))
                    throw new IllegalArgumentException(getString(R.string.i18n_a_serial_port_path_must_start_with_dev));
                prefs.edit().putString(key, value).apply();
                updateSavedValue(key, value);
                toast(getString(R.string.i18n_a_settings_saved));
                refreshStatus();
            } catch (Exception error) {
                notice(failure(error));
            }
        });
    }

    private static int settingInputType(String key, boolean secret) {
        if (secret) return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        if (key.equals("shop_id") || key.equals("device_id") || key.equals("baud"))
            return InputType.TYPE_CLASS_NUMBER;
        if (key.equals("temperature_limit"))
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (key.equals("sdk_url") || key.equals("cloud_url") || key.equals("serial_path"))
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        return InputType.TYPE_CLASS_TEXT;
    }

    protected EditText settingsInput(View form, int id, String key, String fallback) {
        EditText input = form.findViewById(id);
        input.setText(setting(key, fallback));
        return input;
    }

    protected Spinner settingsSelect(View form, int id, List<String> choices, int position) {
        Spinner spinner = form.findViewById(id);
        spinner.setAdapter(Ui.spinnerAdapter(this, choices));
        spinner.setSelection(Math.max(0, position));
        return spinner;
    }

    protected void settingsToggle(View form, int id, String key, boolean fallback) {
        Switch toggle = form.findViewById(id);
        toggle.setChecked(rule(key, fallback));
        toggle.setOnCheckedChangeListener((view, checked) -> {
            if (uiReady()) prefs.edit().putBoolean(key, checked).apply();
        });
    }

    protected void settingsAction(View form, int id, Runnable action) {
        form.findViewById(id).setOnClickListener(view -> {
            if (uiReady()) action.run();
        });
    }

    @Override
    protected void notice(String message) {
        super.notice(message);
    }

    /**
     * 配置保存和重连同属一次切机操作；管理请求在途时也不能提前保存连接草稿。
     */
    protected boolean allowConnectionChange() {
        if (!uiReady()) return false;
        MorningSdkSession.Operation operation = sdkSession.operation();
        if (!operation.running) return true;
        notice(getString(R.string.i18n_a_operation_wait, UiMessages.text(this, operation.label)));
        return false;
    }

    private void typedPrompt(String title, String hint, String value, int inputType,
                             BaseMorningActivity.TextAction action) {
        if (!uiReady()) return;
        dismissFormDialog();
        View body = getLayoutInflater().inflate(R.layout.dialog_text_prompt, null);
        EditText input = body.findViewById(R.id.prompt_value);
        input.setHint(hint);
        input.setInputType(inputType);
        if ((inputType & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
            input.setSaveEnabled(false);
        input.setText(value);
        formDialog = ImmersiveUi.show(new ProjectDialog.Builder(this).setTitle(title).setView(body)
                .setNegativeButton(getString(R.string.i18n_a_cancel), null).setPositiveButton(getString(R.string.i18n_a_save), (dialog, which) -> {
                    if (uiReady()) {
                        action.accept(input.getText().toString().trim());
                    }
                }).create());
    }

    protected void dismissFormDialog() {
        if (formDialog != null) {
            formDialog.dismiss();
            formDialog = null;
        }
    }

    protected void updateSavedValue(String key, String value) {
        String[] keys = {"sdk_url", "cloud_url", "cloud_token", "shop_name",
                "temperature_limit", "serial_path", "baud"};
        int[] ids = {R.id.settings_sdk_url, R.id.settings_cloud_url, R.id.settings_cloud_token,
                R.id.settings_shop_name,
                R.id.settings_temperature_limit, R.id.settings_serial_path, R.id.settings_baud};
        for (int index = 0; index < keys.length; index++) {
            if (keys[index].equals(key)) formState.putString("text:" + ids[index], value);
        }
    }

    protected final boolean uiReady() {
        return formView != null && !destroyed && !isFinishing() && foreground
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }
}
