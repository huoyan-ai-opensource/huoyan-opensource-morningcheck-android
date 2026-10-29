package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 场所资料独立编辑；扫码登录复用进程内绑定流程，成功后返回本页。
 */
public final class PlaceSettingsActivity extends BaseMorningActivity {
    private static final int LOGIN_REQUEST = 3102;
    private static final String STATE_NAME = "place.name";
    private static final String STATE_PHONE = "place.phone";
    private static final String STATE_ADDRESS = "place.address";
    private String nameDraft, phoneDraft, addressDraft;

    @Override
    protected String pageKey() {
        return "place_settings";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_place_settings;
    }

    @Override
    protected void setup(Bundle state) {
        nameDraft = state == null ? currentShopName() : state.getString(STATE_NAME, currentShopName());
        phoneDraft = state == null ? setting("shop_phone", "") : state.getString(STATE_PHONE, "");
        addressDraft = state == null ? setting("shop_address", "") : state.getString(STATE_ADDRESS, "");
    }

    @Override
    protected void bindPage() {
        ((TextView) findViewById(R.id.header_page_title)).setText(getString(R.string.i18n_b_place));
        ((TextView) findViewById(R.id.header_back_label)).setText(getString(R.string.i18n_b_back_settings));
        findViewById(R.id.header_home).setContentDescription(getString(R.string.i18n_b_back_system_settings));
        findViewById(R.id.header_home).setOnClickListener(view -> finish());
        ((EditText) content.findViewById(R.id.place_name)).setText(nameDraft);
        ((EditText) content.findViewById(R.id.place_phone)).setText(phoneDraft);
        ((EditText) content.findViewById(R.id.place_address)).setText(addressDraft);
        content.findViewById(R.id.place_save).setOnClickListener(view -> savePlace());
        content.findViewById(R.id.place_login).setOnClickListener(view -> {
            if (destroyed || !foreground || isFinishing() || navigating) return;
            captureForm();
            navigating = true;
            startActivityForResult(new Intent(this, LoginActivity.class)
                    .putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true), LOGIN_REQUEST);
        });
    }

    @Override
    protected void beforeRender() {
        captureForm();
    }

    @Override
    protected void onDataLoaded() {
        // SDK 状态和后台数据刷新不能覆盖操作员尚未保存的场所资料。
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        captureForm();
        state.putString(STATE_NAME, nameDraft);
        state.putString(STATE_PHONE, phoneDraft);
        state.putString(STATE_ADDRESS, addressDraft);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != LOGIN_REQUEST || resultCode != RESULT_OK) return;
        nameDraft = currentShopName();
        EditText name = content.findViewById(R.id.place_name);
        name.setText(nameDraft);
    }

    private void captureForm() {
        if (content == null) return;
        EditText name = content.findViewById(R.id.place_name);
        if (name == null) return;
        nameDraft = name.getText().toString();
        phoneDraft = ((EditText) content.findViewById(R.id.place_phone)).getText().toString();
        addressDraft = ((EditText) content.findViewById(R.id.place_address)).getText().toString();
    }

    private void savePlace() {
        if (destroyed || !foreground || isFinishing()) return;
        captureForm();
        String name = nameDraft.trim();
        if (name.isEmpty()) {
            notice(getString(R.string.i18n_b_place_required));
            return;
        }
        prefs.edit().putString("shop_name", name).putString("shop_phone", phoneDraft.trim())
                .putString("shop_address", addressDraft.trim()).apply();
        toast(getString(R.string.i18n_b_place_saved));
        finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
