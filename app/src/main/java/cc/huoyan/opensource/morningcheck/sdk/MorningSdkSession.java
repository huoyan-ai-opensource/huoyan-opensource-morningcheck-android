package cc.huoyan.opensource.morningcheck.sdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/**
 * 进程共享 SDK 会话。页面只订阅状态，不在切页时断开连接或中断已经提交的写操作。
 * 前台观察使用主线程预约查询，IPC、图片与网络工作仍由 huoyanClient 在后台执行。
 */
public final class MorningSdkSession implements HuoyanClient.Listener {
    public interface Listener extends HuoyanClient.Listener {
        default void onSdkDetailsChanged() {
        }

        default boolean observesCertificateDetails() {
            return false;
        }
    }

    interface Port {
        void connect(SdkConfig config);

        void close();

        boolean isConnected();

        String request(String command, JSONObject params, HuoyanClient.Callback callback);

        HuoyanClient client();
    }

    interface Factory {
        Port create(HuoyanClient.Listener listener);
    }

    interface Timer {
        Runnable after(long delay, Runnable action);
    }

    public enum ActivationPhase {
        IDLE, ACTIVATING, SUCCEEDED, FAILED
    }

    public static final class Activation {
        public final long id, generation;
        public final ActivationPhase phase;
        public final boolean running;
        public final int percent, code;
        public final String message;

        private Activation(long id, long generation, ActivationPhase phase, boolean running, int percent,
                           int code, String message) {
            this.id = id;
            this.generation = generation;
            this.phase = phase;
            this.running = running;
            this.percent = percent;
            this.code = code;
            this.message = message;
        }
    }

    // 客户端的 activate 请求超时为 130 秒；兜底只等待本次写回执，不追加模型就绪等待。
    private static final long ACTIVATION_RECEIPT_TIMEOUT_MS = 150_000;

    public static final class Operation {
        public final String command, label, message;
        public final boolean running;
        public final int code;

        Operation(String command, String label, boolean running, int code, String message) {
            this.command = command;
            this.label = label;
            this.running = running;
            this.code = code;
            this.message = message;
        }
    }

    private final Port port;
    private final Timer timer;
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final Set<String> queries = new LinkedHashSet<>();
    private SdkConfig config;
    private boolean connected;
    private volatile long generation;
    private String connectionMessage = "正在连接晨检 SDK", detailsSignature = "";
    private JSONObject status = new JSONObject(), certificate = new JSONObject();
    private JSONObject download = new JSONObject(), cloud = new JSONObject();
    private Operation operation = new Operation("", "", false, 0, "");
    private Runnable cancelPoll;
    private long readEpoch, activationSequence, cloudReadVersion;
    private boolean quarantineStatus;
    private Activation activation = new Activation(0, 0, ActivationPhase.IDLE, false, -1, 0, "");
    private ActivationRun activationRun;

    public MorningSdkSession(Context context) {
        this(listener -> {
            HuoyanClient sdk = new HuoyanClient(context.getApplicationContext(), listener);
            return new Port() {
                @Override
                public void connect(SdkConfig config) {
                    sdk.connect(config);
                }

                @Override
                public void close() {
                    sdk.close();
                }

                @Override
                public boolean isConnected() {
                    return sdk.isConnected();
                }

                @Override
                public String request(String name, JSONObject params, HuoyanClient.Callback cb) {
                    return sdk.request(name, params, null, cb);
                }

                @Override
                public HuoyanClient client() {
                    return sdk;
                }
            };
        }, mainTimer());
    }

    MorningSdkSession(Factory factory, Timer timer) {
        this.timer = timer;
        port = factory.create(this);
    }

    private static Timer mainTimer() {
        Handler main = new Handler(Looper.getMainLooper());
        return (delay, action) -> {
            main.postDelayed(action, delay);
            return () -> main.removeCallbacks(action);
        };
    }

    public HuoyanClient client() {
        return port.client();
    }

    public long generation() {
        return generation;
    }

    public boolean matchesConfiguration(SdkConfig value) {
        return value != null && value.equals(config);
    }

    public String connectionMessage() {
        return connectionMessage;
    }

    public JSONObject status() {
        return copy(status);
    }

    public JSONObject certificate() {
        return copy(certificate);
    }

    public JSONObject download() {
        return copy(download);
    }

    public JSONObject cloudStatus() {
        return copy(cloud);
    }

    public Operation operation() {
        return operation;
    }

    public Activation activation() {
        return activation;
    }

    public MorningSdkState viewState() {
        MorningSdkState current = MorningSdkState.from(connected, status, download);
        return activation.running ? current.withActivationProgress(activation.message,
                activation.percent, false) : current;
    }

    public void attach(Listener listener) {
        boolean observedCertificate = hasCertificateObserver();
        if (!listeners.add(listener)) return;
        if (observedCertificate != hasCertificateObserver()) stopPolling();
        long expected = generation;
        listener.onConnection(connected, connectionMessage);
        if (!listeners.contains(listener) || expected != generation) return;
        listener.onStatus(copy(status));
        if (!listeners.contains(listener) || expected != generation) return;
        listener.onSdkDetailsChanged();
        if (connected) refreshDetails();
        schedulePoll();
    }

    public void detach(Listener listener) {
        boolean observedCertificate = hasCertificateObserver();
        listeners.remove(listener);
        if (listeners.isEmpty() || observedCertificate != hasCertificateObserver()) stopPolling();
        schedulePoll();
    }

    public void connect(SdkConfig next) {
        if (next.equals(config)) return;
        generation++;
        config = next;
        clearSnapshots();
        try {
            port.connect(next);
        } catch (RuntimeException invalid) {
            configurationError("SDK 连接配置无效：" + invalid.getMessage());
            throw invalid;
        }
        schedulePoll();
    }

    /**
     * 仅用于操作员明确重连；普通 Activity 切换复用当前通道。
     */
    public void reconnect(SdkConfig next) {
        config = null;
        port.close();
        connect(next);
    }

    /**
     * 明确结束当前连接；常规页面停止只 detach，不调用此入口。
     */
    public void disconnect() {
        config = null;
        generation++;
        port.close();
        clearSnapshots();
        stopPolling();
    }

    public void configurationError(String message) {
        disconnect();
        onConnection(false, message);
    }

    @Override
    public void onConnection(boolean value, String message) {
        if (connected != value) generation++;
        connected = value;
        connectionMessage = message == null ? "SDK 连接状态未知" : message;
        if (!value) clearSnapshots();
        long expected = generation;
        for (Listener listener : snapshotListeners()) {
            if (expected != generation) return;
            if (listeners.contains(listener)) listener.onConnection(value, connectionMessage);
        }
        if (value && !listeners.isEmpty()) refreshDetails();
        schedulePoll();
    }

    @Override
    public void onStatus(JSONObject value) {
        // 传输层的状态广播没有请求代次，激活前的迟到读回不能覆盖本次链的事实。
        if (quarantineStatus) return;
        acceptStatus(value);
    }

    private void acceptStatus(JSONObject value) {
        status = copy(value);
        JSONObject delivered = status;
        long expected = generation;
        for (Listener listener : snapshotListeners()) {
            if (expected != generation || delivered != status) return;
            if (listeners.contains(listener)) listener.onStatus(copy(delivered));
        }
        String signature = status.optString("sdk_state") + ":" + status.optString("model_version")
                + ":" + status.optString("certificate_valid_until");
        if (connected && !activation.running && !listeners.isEmpty()
                && !signature.equals(detailsSignature)) {
            detailsSignature = signature;
            // 授权页由同一预约完整读取，避免每次状态变化另外发起一组详情请求。
            if (!hasCertificateObserver()) {
                read("getCertInfo", null);
                read("getDownloadProgress", null);
            }
        }
    }

    private void clearSnapshots() {
        connected = false;
        status = new JSONObject();
        certificate = new JSONObject();
        download = new JSONObject();
        cloud = new JSONObject();
        detailsSignature = "";
        readEpoch++;
        quarantineStatus = false;
        queries.clear();
        if (activationRun != null)
            finishActivation(activationRun, HuoyanClient.ERROR_RESULT_UNKNOWN,
                    "SDK 连接已变更，激活结果未知；请核对当前设备状态后再操作", new JSONObject());
    }

    public void refreshDetails() {
        if (!connected || activation.running) return;
        read("getStatus", null);
        read("getCertInfo", null);
        read("getDownloadProgress", null);
    }

    /**
     * 读接口合并同类在途请求；带回调的主动查询保留自己的真实回执。
     */
    public void read(String name, HuoyanClient.Callback callback) {
        if (!connected) {
            if (callback != null) callback.onResult(HuoyanClient.ERROR_NOT_CONNECTED,
                    "SDK 尚未连接", new JSONObject());
            return;
        }
        // 等待写回执期间不把查询快照误当成激活结果；结束后恢复独立的状态读取。
        if (activation.running && "getStatus".equals(name)) {
            if (callback != null) callback.onResult(HuoyanClient.ERROR_BUSY,
                    "正在激活，请等待本次操作结束后再查询状态", new JSONObject());
            return;
        }
        boolean tracked = callback == null;
        if (tracked && !queries.add(name)) return;
        long expected = generation;
        long expectedRead = readEpoch;
        long expectedCloud = "getCloudStatus".equals(name) ? ++cloudReadVersion : cloudReadVersion;
        port.request(name, new JSONObject(), (code, reason, data) -> {
            if (expected != generation) return;
            if (expectedRead != readEpoch) {
                if (callback != null) callback.onResult(HuoyanClient.ERROR_RESULT_UNKNOWN,
                        "激活流程已开始，旧查询结果已作废，请重新查询", new JSONObject());
                return;
            }
            if ("getCloudStatus".equals(name) && expectedCloud != cloudReadVersion) return;
            if (tracked) queries.remove(name);
            if (code == 0) {
                switch (name) {
                    case "getStatus":
                        // 常规状态由传输层按查询顺序发布；仅隔离期间用关联回执恢复事实，
                        // 避免被传输层已拒绝的旧查询回调再次倒退状态。
                        if (quarantineStatus) {
                            if (!activation.running) quarantineStatus = false;
                            acceptStatus(data);
                        }
                        break;
                    case "getCertInfo":
                        certificate = copy(data);
                        break;
                    case "getDownloadProgress":
                        download = copy(data);
                        break;
                    case "getCloudStatus":
                        cloud = copy(data);
                        break;
                    default:
                        break;
                }
                notifyDetails();
            }
            if (expected != generation) return;
            if (expectedRead != readEpoch) {
                if (callback != null) callback.onResult(HuoyanClient.ERROR_RESULT_UNKNOWN,
                        "激活流程已变更，旧查询结果已作废，请重新查询", new JSONObject());
                return;
            }
            if (callback != null) callback.onResult(code, reason, data);
        });
    }

    /**
     * 管理动作只提交一次；写回执与后续只读状态分别保存，不把激活成功等同于可识别。
     */
    public boolean execute(String name, JSONObject params, String label, HuoyanClient.Callback callback) {
        if (operation.running) {
            if (callback != null) callback.onResult(HuoyanClient.ERROR_BUSY,
                    "正在" + operation.label + "，请等待本次操作结束", new JSONObject());
            return false;
        }
        if (!connected) {
            if (callback != null) callback.onResult(HuoyanClient.ERROR_NOT_CONNECTED,
                    "SDK 尚未连接，本次请求未发送", new JSONObject());
            return false;
        }
        if ("activate".equals(name)) return beginActivation(params, label, callback);
        Operation submitted = new Operation(name, label, true, 0, "正在" + label + "，请稍候");
        operation = submitted;
        if ("setCloudStatus".equals(name)) {
            cloudReadVersion++;
            queries.remove("getCloudStatus");
            cloud = new JSONObject();
        }
        notifyDetails();
        schedulePoll();
        long expected = generation;
        port.request(name, params, (code, reason, data) -> {
            if (operation != submitted) return;
            int result = expected == generation ? code : HuoyanClient.ERROR_RESULT_UNKNOWN;
            String message = result == HuoyanClient.ERROR_RESULT_UNKNOWN
                    ? label + "结果未知，请核对 SDK 当前状态后再操作"
                    : result == 0 ? label + "请求已处理，正在确认 SDK 状态"
                      : label + "失败：" + reason + "（" + result + "）";
            operation = new Operation(name, label, false, result, message);
            // 开关和配置即使失败也重新读取；不乐观显示已经启用，更不重放未知写入。
            if (!listeners.isEmpty()) refreshDetails();
            if ("setCloudStatus".equals(name) && expected == generation)
                read("getCloudStatus", null);
            notifyDetails();
            if (listeners.isEmpty()) stopPolling();
            if (callback != null) callback.onResult(result, message, data);
        });
        return true;
    }

    /**
     * 页面不持有激活回调；离页、旋转和后台停留均由进程会话继续确认本次请求。
     */
    public boolean activate(String activationCode, String modelCode) {
        return activate(activationCode, modelCode, null);
    }

    /**
     * 提交一次激活并交付真实终态；后续状态刷新不延后或改写本次激活回执。
     */
    public boolean activate(String activationCode, String modelCode, HuoyanClient.Callback callback) {
        if (activationCode == null || activationCode.trim().isEmpty()
                || modelCode == null || modelCode.trim().isEmpty())
            throw new IllegalArgumentException("激活码与模型编码不能为空");
        JSONObject params = new JSONObject();
        try {
            params.put("activation_code", activationCode.trim());
            params.put("model_code", modelCode);
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException("激活参数无效", invalid);
        }
        return execute("activate", params, "激活授权", callback);
    }

    private boolean beginActivation(JSONObject params, String label, HuoyanClient.Callback callback) {
        ActivationRun run = new ActivationRun(++activationSequence, generation, label, callback);
        activationRun = run;
        // 旧查询可仍在传输层收尾，但它们的快照不能冒充本次激活进度或就绪证据。
        readEpoch++;
        queries.clear();
        quarantineStatus = true;
        status = new JSONObject();
        detailsSignature = "";
        stopPolling();
        download = new JSONObject();
        updateActivation(run, ActivationPhase.ACTIVATING, -1, "正在激活授权，请稍候");
        if (!current(run)) return true;
        acceptStatus(status);
        if (!current(run)) return true;
        deadline(run, ACTIVATION_RECEIPT_TIMEOUT_MS);
        port.request("activate", params, (code, reason, data) -> {
            if (!current(run)) return;
            finishActivation(run, code, reason, data);
        });
        return true;
    }

    private void updateActivation(ActivationRun run, ActivationPhase phase, int percent, String message) {
        if (!current(run)) return;
        activation = new Activation(run.id, run.generation, phase, true, percent, 0, message);
        operation = new Operation("activate", run.label, true, 0, message);
        notifyDetails();
    }

    private void finishActivation(ActivationRun run, int code, String reason, JSONObject data) {
        if (activationRun != run) return;
        if (run.cancelDeadline != null) run.cancelDeadline.run();
        run.cancelDeadline = null;
        // 链期间主动查询的回执也已过时。失败后保留未知状态，直到新查询给出真实事实。
        readEpoch++;
        queries.clear();
        quarantineStatus = connected;
        if (code != 0) {
            status = new JSONObject();
            detailsSignature = "";
        }
        activationRun = null;
        String message = code == 0 ? "激活成功" : failure("激活", code, reason);
        activation = new Activation(run.id, run.generation,
                code == 0 ? ActivationPhase.SUCCEEDED : ActivationPhase.FAILED,
                false, activation.percent, code, message);
        operation = new Operation("activate", run.label, false, code, message);
        if (code != 0) acceptStatus(status);
        notifyDetails();
        if (!listeners.isEmpty()) refreshDetails();
        schedulePoll();
        if (run.callback != null) run.callback.onResult(code, reason, copy(data));
    }

    private boolean current(ActivationRun run) {
        return activationRun == run && generation == run.generation && connected;
    }

    private void deadline(ActivationRun run, long timeout) {
        if (run.cancelDeadline != null) run.cancelDeadline.run();
        run.cancelDeadline = timer.after(timeout, () -> {
            run.cancelDeadline = null;
            if (current(run)) finishActivation(run, HuoyanClient.ERROR_RESULT_UNKNOWN,
                    "激活确认超时，结果未知；请核对 SDK 当前状态后再操作", new JSONObject());
        });
    }

    private static String failure(String action, int code, String reason) {
        return code == HuoyanClient.ERROR_RESULT_UNKNOWN
                ? action + "结果未知，请核对 SDK 当前状态后再操作"
                : action + "失败：" + (reason == null ? "未返回原因" : reason) + "（" + code + "）";
    }

    private static final class ActivationRun {
        final long id, generation;
        final String label;
        final HuoyanClient.Callback callback;
        Runnable cancelDeadline;

        ActivationRun(long id, long generation, String label, HuoyanClient.Callback callback) {
            this.id = id;
            this.generation = generation;
            this.label = label;
            this.callback = callback;
        }
    }

    private void schedulePoll() {
        if (cancelPoll != null || listeners.isEmpty() || activation.running) return;
        // 页面共享一条预约；授权页可见时每五秒一起读状态、证书和模型，离页恢复普通观察。
        cancelPoll = timer.after(hasCertificateObserver() ? 5_000 : 1_500, () -> {
            cancelPoll = null;
            if (connected) {
                if (hasCertificateObserver()) refreshDetails();
                else {
                    read("getStatus", null);
                    if (!Boolean.TRUE.equals(status.opt("model_ready")) || operation.running)
                        read("getDownloadProgress", null);
                }
            }
            schedulePoll();
        });
    }

    private void stopPolling() {
        if (cancelPoll != null) cancelPoll.run();
        cancelPoll = null;
    }

    private boolean hasCertificateObserver() {
        for (Listener listener : listeners)
            if (listener.observesCertificateDetails()) return true;
        return false;
    }

    private ArrayList<Listener> snapshotListeners() {
        return new ArrayList<>(listeners);
    }

    private void notifyDetails() {
        long expected = generation;
        for (Listener listener : snapshotListeners()) {
            if (expected != generation) return;
            if (listeners.contains(listener)) listener.onSdkDetailsChanged();
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return value == null ? new JSONObject() : new JSONObject(value.toString());
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException("SDK 快照无效", invalid);
        }
    }
}
