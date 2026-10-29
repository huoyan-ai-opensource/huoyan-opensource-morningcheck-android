package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.widget.TextView;

import cc.huoyan.opensource.morningcheck.sdk.SdkDetailsFormatter;

/**
 * 系统设置：独立 Activity 直接绑定对应 XML。
 */
public final class SystemSettingsActivity extends BaseDeviceSettingsActivity {
    @Override
    protected int settingsSection() {
        return 7;
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_system_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_system_design;
    }

    @Override
    protected void bindRegular(View form) {
        content.setPadding(0, 0, 0, 0);
        settingsAction(form, R.id.settings_business_cloud, this::showCloudAddress);
        form.findViewById(R.id.settings_business_cloud).setOnLongClickListener(v -> {
            navigate("settings", 1);
            return true;
        });
        bindCloudAddress(form);
        settingsAction(form, R.id.settings_system_sdk, () -> showDetails("SDK 状态", statusDetails(), "status"));
        TextView shopName = form.findViewById(R.id.settings_shop_name);
        shopName.setText(currentShopName());
        settingsAction(form, R.id.settings_shop_name, () -> {
            if (navigating) return;
            navigating = true;
            startActivity(new Intent(this, PlaceSettingsActivity.class));
        });
        bindVersion(form);
        bindFontSize(form);
        bindSecurity(form);
        settingsToggle(form, R.id.settings_voice, "voice", true);
        android.widget.Switch voice = form.findViewById(R.id.settings_voice);
        TextView voiceState = form.findViewById(R.id.settings_voice_state);
        voiceState.setText(voice.isChecked() ? "已开启" : "已关闭");
        voice.setOnCheckedChangeListener((button, checked) -> {
            voiceState.setText(checked ? "已开启" : "已关闭");
            if (uiReady()) prefs.edit().putBoolean("voice", checked).apply();
        });
        settingsAction(form, R.id.settings_capabilities, this::showCapabilities);
    }

    @Override
    protected void bindCompact(View form) {
        bindRegular(form);
    }

    private void bindVersion(View form) {
        ((TextView) form.findViewById(R.id.settings_version)).setText(
                getString(R.string.settings_version_value, BuildConfig.VERSION_NAME));
    }

    private void bindSecurity(View form) {
        settingsAction(form, R.id.settings_security_password, () -> {
            dismissFormDialog();
            formDialog = SecurityPasswordDialog.showChange(this, null);
        });
        android.widget.Switch automatic = form.findViewById(R.id.settings_auto_check);
        TextView state = form.findViewById(R.id.settings_auto_check_state);
        automatic.setChecked(SecuritySettings.isAutoCheckEnabled(this));
        state.setText(automatic.isChecked() ? "已开启" : "已关闭");
        automatic.setOnCheckedChangeListener((button, checked) -> {
            state.setText(checked ? "已开启" : "已关闭");
            if (uiReady()) SecuritySettings.setAutoCheckEnabled(this, checked);
        });
    }

    private void bindFontSize(View form) {
        int selected = AppFontSettings.readIndex(this);
        ((TextView) form.findViewById(R.id.settings_font_label)).setText(
                getResources().getStringArray(R.array.app_font_size_labels)[selected]);
        ((TextView) form.findViewById(R.id.settings_font_value)).setText(getString(R.string.app_font_size_value,
                getResources().getStringArray(R.array.app_font_size_percentages)[selected]));
        settingsAction(form, R.id.settings_font_current, () -> {
            dismissFormDialog();
            formDialog = ImmersiveUi.show(new AppFontSizeDialog(this, AppFontSettings.readIndex(this), which -> {
                if (AppFontSettings.save(this, which)) {
                    toast(getString(R.string.app_font_size_saved));
                    recreate();
                }
            }));
        });
    }

    static float previewTextSize(Context context, int index) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.fontScale = AppFontSettings.scaleFor(index);
        Resources resources = context.createConfigurationContext(configuration).getResources();
        // 预览沿用选择弹窗的可读字号，直接按目标档换算，避免叠加当前应用的缩放。
        return resources.getDimension(R.dimen.mc_choice_option_text_size);
    }

    private void showCapabilities() {
        showDetails("App " + BuildConfig.VERSION_NAME + " · SDK 能力",
                SdkDetailsFormatter.capabilities(sdk.getCapabilities()), "capabilities");
    }

    @Override
    protected void refreshStatus() {
        super.refreshStatus();
        if (formView != null) {
            ((TextView) formView.findViewById(R.id.settings_system_sdk_version))
                    .setText(sdkSession.certificate().optString("sdk_version", sdkSession.viewState().title));
            ((TextView) formView.findViewById(R.id.settings_shop_name)).setText(currentShopName());
        }
    }

    private void bindCloudAddress(View form) {
        String address = setting("cloud_url", "");
        ((TextView) form.findViewById(R.id.settings_business_cloud_value)).setText(
                address.isEmpty() ? "未配置" : address);
    }

    private void showCloudAddress() {
        dismissFormDialog();
        View form = getLayoutInflater().inflate(R.layout.dialog_business_cloud_design, null);
        android.widget.EditText address = form.findViewById(R.id.settings_cloud_url);
        address.setText(setting("cloud_url", ""));
        androidx.appcompat.app.AlertDialog dialog = new ProjectDialog.Builder(this)
                .setView(form).create();
        formDialog = dialog;
        form.findViewById(R.id.cloud_address_cancel).setOnClickListener(v -> dialog.dismiss());
        form.findViewById(R.id.settings_save_cloud).setOnClickListener(v -> {
            String value = address.getText().toString().trim();
            if (!validHttp(value)) {
                notice("请输入 http:// 或 https:// 地址");
                return;
            }
            prefs.edit().putString("cloud_url", value).apply();
            if (formView != null) bindCloudAddress(formView);
            dialog.dismiss();
            toast("配置已保存");
        });
        ImmersiveUi.show(dialog);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels
                        - getResources().getDimensionPixelSize(R.dimen.dp_24),
                getResources().getDimensionPixelSize(R.dimen.dp_260)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
