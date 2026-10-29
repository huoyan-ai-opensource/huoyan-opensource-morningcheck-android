package cc.huoyan.opensource.morningcheck;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import cc.huoyan.opensource.morningcheck.sdk.SdkDetailsFormatter;

/**
 * 设备授权信息与激活入口；激活和模型切换统一由共享弹窗会话处理。
 */
public final class LicenseSettingsActivity extends BaseDeviceSettingsActivity {
    private static final String STATE_ROW = "license.detail_row";
    private static final String STATE_OFFSET = "license.detail_offset";
    private CloudServiceUi cloudService;
    private String rowsSignature = "";
    private int firstRow, rowOffset;
    private boolean showingCertificateDetails;
    private long modelRequest;
    private long modelConnection = -1;
    private boolean modelQueryPending;
    private String currentModelName = "—";

    @Override
    protected int settingsSection() {
        return 3;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_license_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_license_design;
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    public boolean observesCertificateDetails() {
        return true;
    }

    @Override
    protected void setupSettings(@Nullable Bundle state) {
        cloudService = new CloudServiceUi(this);
        getSupportFragmentManager().setFragmentResultListener(ActivationFlowFragment.RESULT, this,
                (key, result) -> {
                    sdkSession.refreshDetails();
                    refreshCurrentModel(true);
                });
        if (state == null) return;
        firstRow = state.getInt(STATE_ROW, 0);
        rowOffset = state.getInt(STATE_OFFSET, 0);
    }

    @Override
    protected void bindRegular(View form) {
        bindLicense(form);
    }

    @Override
    protected void bindCompact(View form) {
        bindLicense(form);
    }

    private void bindLicense(View form) {
        ((TextView) findViewById(R.id.header_page_title)).setText("授权与密钥");
        ((TextView) form.findViewById(R.id.license_client_version)).setText(BuildConfig.VERSION_NAME);
        ((TextView) form.findViewById(R.id.license_device_model)).setText(android.os.Build.MODEL);
        ((TextView) form.findViewById(R.id.license_device_serial)).setText(DeviceIdentity.hardwareSerial(this));
        cloudService.bind();
        form.findViewById(R.id.settings_license_fields).setOnLongClickListener(v -> {
            showDetails("授权详情", SdkDetailsFormatter.certificate(sdkSession.certificate()), "");
            return true;
        });
        View activate = form.findViewById(R.id.settings_activate);
        // 授权信息读取不依赖操作入口，布局调整期间缺少按钮也不能让整页崩溃。
        if (activate != null)
            activate.setOnClickListener(view -> {
                if (uiReady()) ActivationFlowFragment.open(this);
            });
        form.findViewById(R.id.settings_switch_model).setOnClickListener(view -> {
            if (uiReady()) ActivationFlowFragment.switchModel(this);
        });
    }

    @Override
    protected void onSettingsResumed() {
        refreshCurrentModel(false);
    }

    @Override
    public void onConnection(boolean connected, String message) {
        super.onConnection(connected, message);
        if (connected) refreshCurrentModel(false);
        else {
            modelRequest++;
            modelConnection = -1;
            modelQueryPending = false;
            currentModelName = "—";
            showCurrentModel();
        }
    }

    /**
     * 模型列表只在进入、重连和激活回执后读取，证书每五秒刷新不重复请求云端列表。
     */
    private void refreshCurrentModel(boolean force) {
        if (!uiReady() || !sdk.isConnected()) return;
        long connection = sdkSession.generation();
        if (!force && modelConnection == connection) return;
        View form = formView;
        long request = ++modelRequest;
        modelConnection = connection;
        modelQueryPending = true;
        currentModelName = "—";
        showCurrentModel();
        sdk.queryDeviceModels((code, message, data) -> {
            if (!uiReady() || form != formView || request != modelRequest
                    || connection != sdkSession.generation()) return;
            modelQueryPending = false;
            if (code != 0) return;
            JSONArray models = data == null ? null : data.optJSONArray("models");
            for (int i = 0; models != null && i < models.length(); i++) {
                JSONObject model = models.optJSONObject(i);
                if (model != null && Boolean.TRUE.equals(model.opt("current"))
                        && model.opt("model_name") instanceof String
                        && !model.optString("model_name").trim().isEmpty()) {
                    currentModelName = model.optString("model_name");
                    break;
                }
            }
            showCurrentModel();
        });
    }

    private void showCurrentModel() {
        if (formView == null) return;
        Object version = sdkSession.status().opt("model_version");
        ((TextView) formView.findViewById(R.id.settings_current_model)).setText(currentModelName);
        ((TextView) formView.findViewById(R.id.settings_model_version)).setText(
                version instanceof String && !((String) version).trim().isEmpty() ? (String) version : "—");
    }

    @Override
    protected void refreshStatus() {
        super.refreshStatus();
        if (formView == null || destroyed) return;
        if (cloudService != null) cloudService.refresh();
        ((TextView) formView.findViewById(R.id.license_sdk_version)).setText(
                sdkSession.certificate().optString("sdk_version", "待确认"));
        updateCertificate();
        showCurrentModel();
        View switchModel = formView.findViewById(R.id.settings_switch_model);
        switchModel.setEnabled(sdk.isConnected() && !sdkSession.operation().running);
        switchModel.setAlpha(switchModel.isEnabled() ? 1f : .5f);
    }

    @Override
    protected void releaseSettingsView() {
        if (cloudService != null) cloudService.stop();
        saveRowPosition();
        rowsSignature = "";
        showingCertificateDetails = false;
        modelRequest++;
        if (modelQueryPending) modelConnection = -1;
        modelQueryPending = false;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        saveRowPosition();
        state.putInt(STATE_ROW, firstRow);
        state.putInt(STATE_OFFSET, rowOffset);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        saveRowPosition();
        super.onPause();
    }

    @Override
    protected void onStop() {
        cloudService.stop();
        modelRequest++;
        modelConnection = -1;
        modelQueryPending = false;
        super.onStop();
    }

    private void saveRowPosition() {
        // 进程重建后的等待占位行不能覆盖上次真实证书列表的位置。
        if (!showingCertificateDetails) return;
        ListView list = formView == null ? null : formView.findViewById(R.id.settings_license_fields);
        if (list == null || list.getChildCount() == 0) return;
        firstRow = list.getFirstVisiblePosition();
        rowOffset = list.getChildAt(0).getTop();
    }

    private void updateCertificate() {
        ListView list = formView.findViewById(R.id.settings_license_fields);
        org.json.JSONObject certificate = sdkSession.certificate();
        String text = "激活时间：" + SdkDetailsFormatter.seconds(certificate.opt("activated_at"))
                + "\n过期时间：" + SdkDetailsFormatter.seconds(certificate.opt("expires_at"))
                + "\n授权版本：" + certificate.optString("license_type", "待确认");
        if (text.equals(rowsSignature) && list.getAdapter() != null) return;
        // 自动刷新只替换变化的详情，保留操作员正在查看的行；重排时也恢复列表位置。
        saveRowPosition();
        rowsSignature = text;
        showingCertificateDetails = sdkSession.certificate().length() > 0;
        CertificateRows rows = new CertificateRows(text);
        list.setAdapter(rows);
        list.setSelectionFromTop(Math.min(firstRow, Math.max(0, rows.getCount() - 1)), rowOffset);
    }

    private final class CertificateRows extends ArrayAdapter<String> {
        CertificateRows(String text) {
            super(LicenseSettingsActivity.this, R.layout.item_license_design, text.split("\\n"));
        }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            View row = reusable == null
                    ? getLayoutInflater().inflate(R.layout.item_license_design, parent, false) : reusable;
            String line = getItem(position);
            int divider = line.indexOf('：');
            TextView label = row.findViewById(R.id.record_detail_field_label);
            label.setText(divider < 0 ? "" : line.substring(0, divider));
            label.setVisibility(divider < 0 ? View.GONE : View.VISIBLE);
            ((TextView) row.findViewById(R.id.record_detail_field_value)).setText(
                    divider < 0 ? line : line.substring(divider + 1));
            return row;
        }
    }
}
