package cc.huoyan.opensource.morningcheck;

import android.view.View;
import android.widget.EditText;

/**
 * 业务云设置：独立 Activity 直接绑定对应 XML。
 */
public final class BusinessCloudSettingsActivity extends BaseDeviceSettingsActivity {
    @Override
    protected int settingsSection() {
        return 1;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.settings_cloud;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.settings_cloud_compact;
    }

    @Override
    protected void bindRegular(View form) {
        EditText url = settingsInput(form, R.id.settings_cloud_url, "cloud_url", "");
        EditText token = settingsInput(form, R.id.settings_cloud_token, "cloud_token", "");
        settingsAction(form, R.id.settings_save_cloud, () -> {
            String value = url.getText().toString().trim();
            if (!validHttp(value)) {
                notice(getString(R.string.i18n_a_enter_an_http_or_https_address));
                return;
            }
            prefs.edit().putString("cloud_url", value).putString("cloud_token", token.getText().toString().trim()).apply();
            toast(getString(R.string.i18n_a_configuration_saved));
        });
    }

    @Override
    protected void bindCompact(View form) {
        settingsAction(form, R.id.settings_edit_cloud_url, () -> editSetting(getString(R.string.i18n_a_business_cloud_url), "cloud_url", "", false));
        settingsAction(form, R.id.settings_edit_cloud_token, () -> editSetting("X-USER-TOKEN", "cloud_token", "", true));
    }

}
