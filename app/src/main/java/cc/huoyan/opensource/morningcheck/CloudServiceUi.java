package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.lang.ref.WeakReference;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/**
 * 首页与授权页共享实际云托管状态；写操作由进程会话持有，重建不重复提交。
 */
final class CloudServiceUi {
    private final BaseMorningActivity activity;
    private final MorningSdkSession session;
    private long queriedGeneration = -1;
    private AlertDialog progress, confirmation;
    private boolean renderingToggle;

    CloudServiceUi(BaseMorningActivity activity) {
        this.activity = activity;
        session = activity.sdkSession;
    }

    void bind() {
        View badge = activity.findViewById(R.id.home_cloud_pending);
        if (badge != null) badge.setOnClickListener(v -> activity.navigate("settings", 3));
        Switch toggle = activity.findViewById(R.id.license_cloud_enabled);
        if (toggle != null) toggle.setOnCheckedChangeListener((button, checked) -> {
            if (renderingToggle) return;
            refresh();
            confirm(checked);
        });
        View retry = activity.findViewById(R.id.license_cloud_state);
        if (retry != null) retry.setOnClickListener(v -> {
            queriedGeneration = -1;
            refresh();
        });
        refresh();
    }

    void refresh() {
        if (!active(activity)) return;
        boolean connected = activity.sdk.isConnected();
        MorningSdkSession.Operation operation = session.operation();
        boolean writing = operation.running && "setCloudStatus".equals(operation.command);
        Object value = session.cloudStatus().opt("enabled");
        boolean known = connected && value instanceof Boolean;
        boolean enabled = known && Boolean.TRUE.equals(value);
        TextView badge = activity.findViewById(R.id.home_cloud_pending);
        if (badge != null) {
            badge.setText((enabled ? "● " : "○ ") + "云端服务");
            badge.setTextColor(enabled ? Ui.GREEN : Ui.MUTED);
            badge.setContentDescription("云端服务：" + (known ? enabled ? "已开启" : "已关闭" : "状态未知"));
        }
        Switch toggle = activity.findViewById(R.id.license_cloud_enabled);
        if (toggle != null) {
            renderingToggle = true;
            toggle.setChecked(enabled);
            renderingToggle = false;
            toggle.setEnabled(known && !operation.running);
        }
        TextView state = activity.findViewById(R.id.license_cloud_state);
        if (state != null) state.setText(known ? enabled ? "已开启" : "已关闭"
                : connected ? "点击查询" : "未连接");
        if (writing && toggle != null) showProgress(operation.label);
        else dismissProgress();
        if (connected && !writing && queriedGeneration != session.generation()) {
            queriedGeneration = session.generation();
            session.read("getCloudStatus", null);
        }
    }

    private void confirm(boolean enabled) {
        Object current = session.cloudStatus().opt("enabled");
        if (!(current instanceof Boolean) || enabled == (Boolean) current || session.operation().running)
            return;
        if (confirmation != null) confirmation.dismiss();
        String action = enabled ? "开启云端服务" : "关闭云端服务";
        confirmation = DesignConfirmationDialog.create(activity, action + "？",
                enabled ? "将开启当前 SDK 的云端托管服务。" : "将关闭当前 SDK 的云端托管服务。", action,
                (dialog, which) -> submit(enabled));
        ImmersiveUi.show(confirmation);
    }

    private void submit(boolean enabled) {
        if (!active(activity)) return;
        long expected = session.generation();
        WeakReference<BaseMorningActivity> host = new WeakReference<>(activity);
        session.execute("setCloudStatus", obj("enabled", enabled), enabled ? "开启云端服务" : "关闭云端服务",
                (code, message, data) -> {
                    BaseMorningActivity page = host.get();
                    if (page == null || !active(page) || page.sdkSession.generation() != expected)
                        return;
                    if (code == 0) page.toast("云端服务设置已提交");
                    else page.notice(message);
                });
        refresh();
    }

    private void showProgress(String label) {
        if (progress != null) return;
        View body = activity.getLayoutInflater().inflate(R.layout.dialog_design_loading, null);
        ((TextView) body.findViewById(R.id.loading_message)).setText("正在" + label + "…");
        body.findViewById(R.id.loading_cancel).setVisibility(View.GONE);
        progress = new AlertDialog.Builder(activity).create();
        progress.setView(body, 0, 0, 0, 0);
        progress.setCancelable(false);
        ImmersiveUi.show(progress);
        progress.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        progress.getWindow().setLayout(Math.min(activity.getWindow().getDecorView().getWidth()
                        - activity.getResources().getDimensionPixelSize(R.dimen.dp_24),
                activity.getResources().getDimensionPixelSize(R.dimen.dp_239)), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void dismissProgress() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
    }

    private static boolean active(BaseMorningActivity page) {
        return page.foreground && !page.destroyed && !page.isFinishing() && page.content != null;
    }

    void stop() {
        dismissProgress();
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        queriedGeneration = -1;
    }
}
