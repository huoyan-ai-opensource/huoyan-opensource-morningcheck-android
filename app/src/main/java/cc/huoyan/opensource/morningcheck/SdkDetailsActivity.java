package cc.huoyan.opensource.morningcheck;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;
import cc.huoyan.opensource.morningcheck.sdk.SdkDetailsFormatter;

/**
 * SDK 只读详情使用独立页面。
 */
public final class SdkDetailsActivity extends BaseMorningActivity {
    public static final String EXTRA_KIND = "sdk_detail_kind";
    private String kind = "status", readError = "";
    private String rowsSignature = "";
    private JSONObject capabilities = new JSONObject();
    private int readVersion;
    private long observedConnection;
    private boolean reading;
    private LoadingDialog readingDialog;
    private int firstRow, rowOffset;

    static boolean isDetailKind(String value) {
        return "certificate".equals(value) || "model".equals(value) || "status".equals(value)
                || "capabilities".equals(value);
    }

    @Override
    protected String pageKey() {
        return "sdk_details";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_sdk_details;
    }

    @Override
    protected void setup(Bundle state) {
        String requested = getIntent().getStringExtra(EXTRA_KIND);
        // 托管功能暂停后，旧 Intent 或页面重建都回退到设备状态，不能恢复开启入口。
        if (isDetailKind(requested)) kind = requested;
        observedConnection = sdkSession.generation();
        if (state != null) {
            firstRow = state.getInt("sdk_detail.row", 0);
            rowOffset = state.getInt("sdk_detail.offset", 0);
        }
    }

    @Override
    protected void bindPage() {
        ((TextView) findViewById(R.id.header_page_title)).setText(title());
        ((TextView) findViewById(R.id.header_back_label)).setText("返回设置");
        findViewById(R.id.header_home).setContentDescription("返回上一页设置");
        findViewById(R.id.header_home).setOnClickListener(view -> finish());
        View refresh = content.findViewById(R.id.sdk_detail_refresh);
        boolean manualRefresh = !"status".equals(kind);
        refresh.setVisibility(manualRefresh ? View.VISIBLE : View.GONE);
        refresh.setOnClickListener(manualRefresh ? view -> requestDetails() : null);
        updateDetails();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void beforeRender() {
        saveRowPosition();
        rowsSignature = "";
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        saveRowPosition();
        state.putInt("sdk_detail.row", firstRow);
        state.putInt("sdk_detail.offset", rowOffset);
        super.onSaveInstanceState(state);
    }

    private void saveRowPosition() {
        if (content == null) return;
        ListView list = content.findViewById(R.id.sdk_detail_fields);
        if (list == null || list.getChildCount() == 0) return;
        firstRow = list.getFirstVisiblePosition();
        rowOffset = list.getChildAt(0).getTop();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        if (destroyed || !foreground) return;
        loaded = true;
        requestDetails();
        if (done != null) done.run();
    }

    @Override
    protected void onSdkStateChanged() {
        if (destroyed || !foreground) return;
        if (observedConnection != sdkSession.generation()) {
            observedConnection = sdkSession.generation();
            readVersion++;
            reading = false;
            readError = "";
            capabilities = new JSONObject();
            if (sdk.isConnected()) requestDetails();
        }
        updateDetails();
    }

    @Override
    protected void onStop() {
        readVersion++;
        reading = false;
        super.onStop();
    }

    private void requestDetails() {
        if (destroyed || !foreground || isFinishing()) return;
        if (reading) return;
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        readingDialog.show("正在读取详情");
        int version = ++readVersion;
        long connection = sdkSession.generation();
        reading = true;
        readError = "";
        updateDetails();
        String command;
        switch (kind) {
            case "certificate":
                command = "getCertInfo";
                break;
            case "model":
                command = "getDownloadProgress";
                break;
            case "capabilities":
                command = "getCapabilities";
                break;
            default:
                command = "getStatus";
        }
        sdkSession.read(command, (code, message, data) -> main.post(() -> {
            // 切机、离页或后续刷新后，旧回执不能覆盖当前设备的详情。
            if (destroyed || !foreground || version != readVersion || connection != sdkSession.generation())
                return;
            reading = false;
            readingDialog.dismiss();
            if (code != 0) notice(message == null ? "详情读取失败" : message);
            readError = code == 0 ? "" : (message == null ? "详情读取失败" : message) + "（" + code + "）";
            if (code == 0 && "capabilities".equals(kind))
                capabilities = data == null ? new JSONObject() : data;
            updateDetails();
        }));
    }

    private String title() {
        switch (kind) {
            case "certificate":
                return "设备授权";
            case "model":
                return "模型准备进度";
            case "capabilities":
                return "SDK 能力";
            default:
                return "SDK 状态";
        }
    }

    private void updateDetails() {
        if (content == null || destroyed) return;
        MorningSdkState current = sdkSession.viewState();
        ((TextView) content.findViewById(R.id.sdk_detail_title)).setText(current.title);
        ((TextView) content.findViewById(R.id.sdk_detail_subtitle)).setText(current.detail);
        String text;
        switch (kind) {
            case "certificate":
                text = SdkDetailsFormatter.certificate(sdkSession.certificate());
                break;
            case "model":
                text = modelDetails();
                break;
            case "capabilities":
                text = "App 版本：" + BuildConfig.VERSION_NAME + "\n" + SdkDetailsFormatter.capabilities(capabilities);
                break;
            default:
                text = statusDetails();
        }
        String detail = text;
        if (!detail.equals(rowsSignature)) {
            saveRowPosition();
            rowsSignature = detail;
            ListView list = content.findViewById(R.id.sdk_detail_fields);
            DetailRows rows = new DetailRows(detail);
            list.setAdapter(rows);
            list.setSelectionFromTop(Math.min(firstRow, Math.max(0, rows.getCount() - 1)), rowOffset);
        }
        TextView result = content.findViewById(R.id.sdk_detail_feedback);
        result.setVisibility(View.GONE);
        View refresh = content.findViewById(R.id.sdk_detail_refresh);
        refresh.setEnabled(!"status".equals(kind) && !reading);
        refresh.setAlpha(refresh.isEnabled() ? 1f : .45f);
    }

    private String modelDetails() {
        MorningSdkState state = sdkSession.viewState();
        if (!state.ready)
            return state.title + "\n" + state.detail + "\n" + SdkDetailsFormatter.download(sdkSession.download());
        Object version = sdkSession.status().opt("model_version");
        return state.title + "\n" + state.detail + "\n当前模型已加载。\n模型版本："
                + (version instanceof String && !((String) version).trim().isEmpty() ? version : "暂未返回");
    }

    private String statusDetails() {
        MorningSdkState state = sdkSession.viewState();

        JSONObject status = sdkSession.status();
        String channel = sdk.getActiveChannel() == null ? "未连接" : sdk.getActiveChannel().toString();
        Object valid = status.opt("certificate_valid"), ready = status.opt("model_ready");
        return "连接通道：" + channel
                + "\n服务连接：" + (sdk.isConnected() ? "已连接" : "未连接")
                + "\n授权状态：" + (Boolean.TRUE.equals(valid) ? "有效" : Boolean.FALSE.equals(valid) ? "无效" : "待确认")
                + "\n模型状态：" + (Boolean.TRUE.equals(ready) ? "READY" : Boolean.FALSE.equals(ready) ? "未就绪" : "待确认")
                + "\n人员数量：" + (status.has("user_count") && !status.isNull("user_count") ? status.optString("user_count") : "待确认")
                + "\n客户端版本：v" + BuildConfig.VERSION_NAME;
    }


    private final class DetailRows extends BaseAdapter {
        private final List<String[]> rows = new ArrayList<>();

        DetailRows(String text) {
            for (String line : text.split("\\n")) {
                if (line.isEmpty()) continue;
                int divider = line.indexOf('：');
                rows.add(divider < 0 ? new String[]{"", line}
                        : new String[]{line.substring(0, divider), line.substring(divider + 1)});
            }
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            View row = reusable == null ? getLayoutInflater().inflate(R.layout.item_record_design_field, parent, false) : reusable;
            String[] value = rows.get(position);
            TextView label = row.findViewById(R.id.record_detail_field_label);
            label.setText(value[0]);
            label.setVisibility(value[0].isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) row.findViewById(R.id.record_detail_field_value)).setText(value[1]);
            return row;
        }
    }
}
