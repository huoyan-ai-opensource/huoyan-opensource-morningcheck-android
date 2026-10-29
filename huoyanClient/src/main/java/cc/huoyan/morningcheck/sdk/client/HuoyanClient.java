package cc.huoyan.morningcheck.sdk.client;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 晨检 SDK 客户端模块的公共入口。连接、协议和重连归模块，业务数据与相机归宿主。
 * 默认将回调交付主线程；一个实例同时只连接一个壳，切换配置不会重放业务请求。
 */
public final class HuoyanClient implements AutoCloseable {
    public static final int ERROR_NOT_CONNECTED = -20001;
    public static final int ERROR_CANCELLED = -20002;
    public static final int ERROR_INVALID_REQUEST = -20003;
    public static final int ERROR_RESULT_UNKNOWN = 40009;
    public static final int ERROR_BUSY = 40010;

    public interface Listener {
        void onConnection(boolean connected, String message);

        void onStatus(JSONObject status);
    }

    public interface Callback {
        void onResult(int code, String message, JSONObject data);
    }

    interface TransportFactory {
        ClientTransport create(SdkConfig.Channel channel, Listener listener);
    }

    interface Timer {
        Runnable after(long delayMs, Runnable action);
    }

    private final int apiLevel;
    private final Listener listener;
    private final Executor deliveries;
    private final TransportFactory factory;
    private final Timer timer;
    private final Map<String, RequestState> requests = new LinkedHashMap<>();
    // 通道切换与请求登记受同一把锁保护，防止回执误投给下一台设备。
    private ClientTransport transport;
    private SdkConfig config;
    private long generation;
    private boolean destroyed;
    private boolean initializationPending;

    /**
     * 创建客户端，仅持有应用 Context；宿主销毁时必须调用 destroy。
     */
    public HuoyanClient(Context context, Listener listener) {
        this(Build.VERSION.SDK_INT, listener, mainExecutor(), defaultFactory(context), mainTimer());
    }

    HuoyanClient(int apiLevel, Listener listener, Executor deliveries, TransportFactory factory, Timer timer) {
        this.apiLevel = apiLevel;
        this.listener = listener;
        this.deliveries = Objects.requireNonNull(deliveries);
        this.factory = Objects.requireNonNull(factory);
        this.timer = Objects.requireNonNull(timer);
    }

    private static Executor mainExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return handler::post;
    }

    private static Timer mainTimer() {
        Handler handler = new Handler(Looper.getMainLooper());
        return (delay, action) -> {
            handler.postDelayed(action, delay);
            return () -> handler.removeCallbacks(action);
        };
    }

    private static TransportFactory defaultFactory(Context context) {
        Context app = Objects.requireNonNull(context, "Context 不能为空").getApplicationContext();
        return (channel, listener) -> {
            if (channel == SdkConfig.Channel.AIDL) {
                if (Build.VERSION.SDK_INT < 27)
                    throw new IllegalArgumentException("AIDL 需要 Android 8.1 及以上");
                return Api27Factory.create(app, listener);
            }
            return new LocalWsTransport(app, listener);
        };
    }

    /**
     * 异步连接并显式初始化。相同配置复用连接；配置改变会关闭旧连接并结束旧请求。
     * 参数错误同步抛 IllegalArgumentException，不影响当前有效连接。
     */
    public synchronized void connect(SdkConfig config) {
        Objects.requireNonNull(config, "连接配置不能为空");
        SdkConfig.Channel channel = config.getResolvedChannel(apiLevel);
        if (destroyed) throw new IllegalStateException("客户端已经销毁");
        if (transport != null && config.equals(this.config)) return;
        stopTransport("连接已切换，旧请求结果需核对");
        this.config = config;
        initializationPending = true;
        long current = generation;
        transport = factory.create(channel, new Listener() {
            @Override
            public void onConnection(boolean connected, String message) {
                if (!connected) disconnected(current);
                postState(current, () -> {
                    if (listener != null) listener.onConnection(connected, message);
                });
            }

            @Override
            public void onStatus(JSONObject status) {
                JSONObject snapshot = MorningWsProtocol.copy(status);
                postState(current, () -> {
                    if (listener != null) listener.onStatus(snapshot);
                });
            }
        });
        transport.setInitializationListener(() -> initialized(current));
        transport.connect(config.getWsEndpoint());
    }

    private synchronized void disconnected(long current) {
        if (destroyed || generation != current) return;
        initializationPending = true;
        for (Map.Entry<String, RequestState> entry : new ArrayList<>(requests.entrySet())) {
            RequestState state = entry.getValue();
            if (state.waitingInitialization) {
                requests.remove(entry.getKey());
                state.stopWaiting();
                postUnsentResult(state.callback, ERROR_NOT_CONNECTED, "SDK 连接中断，门店绑定尚未发送");
            }
        }
    }

    private synchronized void initialized(long current) {
        if (destroyed || generation != current || transport == null || !transport.isConnected())
            return;
        initializationPending = false;
        for (Map.Entry<String, RequestState> entry : new ArrayList<>(requests.entrySet())) {
            RequestState state = entry.getValue();
            if (requests.get(entry.getKey()) == state && state.waitingInitialization) {
                state.stopWaiting();
                dispatch(entry.getKey(), state, "bindDevice", state.params, state.image);
            }
        }
    }

    /**
     * 当前实际采用的通道；尚未连接或 close 后返回 null。
     */
    public synchronized SdkConfig.Channel getActiveChannel() {
        return transport == null ? null : config.getResolvedChannel(apiLevel);
    }

    /**
     * 按发行账号绑定门店，沿用零售客户端的店铺登录约定。
     * 外部门店标识非空且原样传递；成功后的真实归属位于回执 data.response，不能用 request 回显代替。
     * 当前连接的 init 尚未结束时最多等 130 秒，之后才提交一次绑定；不要求 READY 或有效证书。
     * 发行账号配置缺失或无效时回调参数错误，明确本次未发送，不将其报告为云端结果未知。
     */
    public String bindDevice(String externalShopId, Callback callback) {
        JSONObject binding;
        try {
            binding = ShopBindingRequest.create(externalShopId);
        } catch (IllegalStateException invalidConfiguration) {
            String requestId = UUID.randomUUID().toString();
            postUnsentResult(callback, ERROR_INVALID_REQUEST,
                    invalidConfiguration.getMessage() + "；门店绑定尚未发送");
            return requestId;
        }
        return request("bindDevice", binding, null, callback);
    }

    /**
     * 显式初始化一次；连接自身已自动初始化，本方法仅供用户主动恢复时使用。
     */
    public String init(Callback callback) {
        return submit("init", null, null, callback);
    }

    /**
     * 按输入激活码查询支持模型，适用于正常激活。
     * 成功回执 data.models 含 model_code、model_name、chip、current。
     */
    public String queryLicenseModels(String activationCode, Callback callback) {
        return submit("queryLicenseModels", params("activation_code", activationCode), null, callback);
    }

    /** 无参查询设备现有授权的模型。 */
    public String queryDeviceModels(Callback callback) {
        return submit("queryDeviceModels", null, null, callback);
    }

    /**
     * 使用查询返回的 model_code 激活，不自动重放；回调交付本次激活回执，状态刷新单独读取。
     */
    public String activate(String activationCode, String modelCode, Callback callback) {
        return submit("activate", params("activation_code", activationCode, "model_code", modelCode), null, callback);
    }

    /**
     * 查询壳的最新状态，并由原通道更新 getStatus 缓存。
     */
    public String refreshStatus(Callback callback) {
        return submit("getStatus", null, null, callback);
    }

    /**
     * 查询当前通道能力；结果更新 getCapabilities 缓存。
     */
    public String refreshCapabilities(Callback callback) {
        return submit("getCapabilities", null, null, callback);
    }

    /**
     * 查询证书与 SDK 版本；证书有效期字段单位为秒。
     */
    public String getCertInfo(Callback callback) {
        return submit("getCertInfo", null, null, callback);
    }

    /**
     * 查询模型下载字节进度；total=0 表示总量未知，进度不代表激活已经成功。
     */
    public String getDownloadProgress(Callback callback) {
        return submit("getDownloadProgress", null, null, callback);
    }

    /**
     * 比对紧凑 RGB888 帧；图像须为 width×height×3 字节，回调前不得复用缓冲。
     */
    public String identify(byte[] rgb, int width, int height, Callback callback) {
        return submit("identify", frameParams(width, height), rgb, callback);
    }

    /**
     * 检测人脸框及关键点，不判断身份；maxFaces 为正整数，超过 64 由壳截断。
     */
    public String detectFace(byte[] rgb, int width, int height, int maxFaces, Callback callback) {
        return submit("detectFace", frameParams(width, height, "max_faces", maxFaces), rgb, callback);
    }

    /**
     * 检查头部，最多返回 1 至 64 个框；图像所有权约束与 identify 相同。
     */
    public String checkHead(byte[] rgb, int width, int height, int maxDetections, Callback callback) {
        return submit("checkHead", frameParams(width, height, "max_detections", maxDetections), rgb, callback);
    }

    /**
     * 检查手部，最多返回 1 至 64 个框；图像所有权约束与 identify 相同。
     */
    public String checkHand(byte[] rgb, int width, int height, int maxDetections, Callback callback) {
        return submit("checkHand", frameParams(width, height, "max_detections", maxDetections), rgb, callback);
    }

    /**
     * 向正整数人员 ID 登记一张 RGB888 人脸；保存成功回执 uid，未知结果不得自动重试。
     */
    public String register(byte[] rgb, int width, int height, long personId, Callback callback) {
        return submit("register", frameParams(width, height, "person_id", personId), rgb, callback);
    }

    /**
     * 删除正整数人员 ID 的全部样本；证书过期不在客户端拦截，准入由壳决定。
     */
    public String forget(long personId, Callback callback) {
        return submit("forget", params("person_id", personId), null, callback);
    }

    /**
     * 按登记成功回执的正整数 uid 删除一个样本，不自动重试。
     */
    public String forgetByUid(long uid, Callback callback) {
        return submit("forgetByUid", params("uid", uid), null, callback);
    }

    /**
     * 查询壳内已登记人员数量，不读取宿主员工数据库。
     */
    public String userCount(Callback callback) {
        return submit("userCount", null, null, callback);
    }

    /**
     * 查询指定人员的样本 uid 列表；查询成功且列表为空才表示没有样本。
     */
    public String userDetail(long personId, Callback callback) {
        return submit("userDetail", params("person_id", personId), null, callback);
    }

    /**
     * 查询当前模型的头部与手部类别表，结果更新 getClassTable 缓存。
     */
    public String refreshClassTable(Callback callback) {
        return submit("getClassTable", null, null, callback);
    }

    /**
     * 修改 SDK 壳的云服务基址；与宿主业务云地址独立，URL 语义由壳校验。
     */
    public String setBaseUrl(String url, Callback callback) {
        return submit("setBaseUrl", params("url", url), null, callback);
    }

    /**
     * 提交云托管开关，不乐观更新状态；失败后应查询实际开关。
     */
    public String setCloudStatus(boolean enabled, Callback callback) {
        return submit("setCloudStatus", params("enabled", enabled), null, callback);
    }

    /**
     * 查询真实云托管开关，不以本地门店登录状态代替。
     */
    public String getCloudStatus(Callback callback) {
        return submit("getCloudStatus", null, null, callback);
    }

    /**
     * 上传非空 JPEG/PNG 字节，format 只接受 jpeg/png；回调前不得复用缓冲。
     */
    public String uploadPhoto(byte[] encoded, String format, Callback callback) {
        return submit("uploadPhoto", params("format", format), encoded, callback);
    }

    /**
     * 提交包含 request_id、photo_file_url 和可选 goods 数组的反馈；不自动重放写入。
     */
    public String submitFeedback(JSONObject feedback, Callback callback) {
        return submit("submitFeedback", feedback, null, callback);
    }

    /**
     * 具名入口同步拒绝无效参数；统一进入原请求登记，保留取消、初始化等待与代次语义。
     */
    private String submit(String command, JSONObject params, byte[] image, Callback callback) {
        JSONObject value = params == null ? new JSONObject() : params;
        String wireCommand = MorningWsProtocol.command(command);
        try {
            MorningWsProtocol.validate(wireCommand, value, image);
            RequestParameters.validate(wireCommand, value);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("请求参数无效：" + invalid.getMessage(), invalid);
        }
        return request(command, value, image, callback);
    }

    /**
     * 小型参数由入口构造，图像保持原引用交给通道按既有所有权规则处理。
     */
    private static JSONObject params(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2)
                result.put((String) values[i], values[i + 1]);
            return result;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("请求参数无法编码", invalid);
        }
    }

    private static JSONObject frameParams(int width, int height, Object... extra) {
        JSONObject result = params("width", width, "height", height, "format", "rgb");
        try {
            for (int i = 0; i < extra.length; i += 2) result.put((String) extra[i], extra[i + 1]);
            return result;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("图像参数无法编码", invalid);
        }
    }

    /**
     * 发出一次晨检命令，返回可取消的请求标识。params 会快照；图片在回调前不得修改。
     * 40009 表示结果未知，尤其登记、删除、反馈等写操作须核对后再决定是否重试。
     */
    public synchronized String request(String command, JSONObject params, byte[] image, Callback callback) {
        String id = UUID.randomUUID().toString();
        if (destroyed) throw new IllegalStateException("客户端已经销毁");
        if (transport == null) {
            postResult(generation, callback, ERROR_NOT_CONNECTED, "晨检 SDK 尚未连接", new JSONObject());
            return id;
        }
        RequestState state = new RequestState(generation, callback);
        requests.put(id, state);
        if ("bindDevice".equals(command) && initializationPending && transport.isConnected()) {
            if (requests.size() > 16) {
                requests.remove(id);
                postUnsentResult(callback, ERROR_BUSY, "等待请求过多，门店绑定尚未发送");
                return id;
            }
            try {
                state.params = MorningWsProtocol.copy(params);
            } catch (IllegalArgumentException invalid) {
                requests.remove(id);
                postUnsentResult(callback, ERROR_INVALID_REQUEST, invalid.getMessage());
                return id;
            }
            state.image = image == null ? null : image.clone();
            state.waitingInitialization = true;
            // 只取消预约还不够：旧定时回调可能已出队，须再次核对请求身份及等待阶段。
            state.cancelWait = timer.after(130_000, () -> initializationTimeout(id, state));
            return id;
        }
        dispatch(id, state, command, params, image);
        return id;
    }

    private void dispatch(String id, RequestState state, String command, JSONObject params, byte[] image) {
        state.transportId = transport.request(command, params, image,
                (code, message, data) -> complete(id, state, code, message, data));
    }

    private synchronized void initializationTimeout(String id, RequestState state) {
        if (requests.get(id) != state || !state.waitingInitialization) return;
        requests.remove(id);
        state.stopWaiting();
        postUnsentResult(state.callback, ERROR_NOT_CONNECTED, "等待 SDK 初始化超时，门店绑定尚未发送");
    }

    private synchronized void complete(String id, RequestState state, int code, String message, JSONObject data) {
        if (requests.get(id) != state) return;
        requests.remove(id);
        postResult(state.generation, state.callback, code, message, data);
    }

    /**
     * 停止等待指定请求；无法撤回 SDK 已开始的操作，也不会立刻释放底层图片在途槽位。
     */
    public synchronized void cancel(String requestId) {
        RequestState state = requests.get(requestId);
        if (state != null && state.waitingInitialization) {
            requests.remove(requestId);
            state.stopWaiting();
            postUnsentResult(state.callback, ERROR_CANCELLED, "已取消等待，门店绑定尚未发送");
            return;
        }
        if (state != null && transport != null) transport.cancel(state.transportId);
    }

    /**
     * 读取当前连接的状态副本。空对象表示尚未读到快照。
     */
    public synchronized JSONObject getStatus() {
        return transport == null ? new JSONObject() : transport.getStatus();
    }

    /**
     * 读取当前通道的能力表，AIDL 与 WS 的 api_version 可以不同。
     */
    public synchronized JSONObject getCapabilities() {
        return transport == null ? new JSONObject() : transport.getCapabilities();
    }

    /**
     * 读取头部、手部类别表。
     */
    public synchronized JSONObject getClassTable() {
        return transport == null ? new JSONObject() : transport.getClassTable();
    }

    public synchronized boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    /**
     * 确认本地识别、检测与登记可用；READY 或 OFFLINE 均须证书有效、模型就绪且未丢失密钥。
     * 云端操作是否可用仍以各接口的真实回执为准。
     */
    public synchronized boolean isReady() {
        return transport != null && transport.isReady();
    }

    /**
     * 关闭连接和所有重连预约，之后可以显式 connect。
     */
    @Override
    public synchronized void close() {
        if (destroyed) return;
        stopTransport("连接已关闭，已提交请求结果需核对");
        long current = generation;
        postState(current, () -> {
            if (listener != null) {
                listener.onConnection(false, "SDK 连接已关闭");
                listener.onStatus(new JSONObject());
            }
        });
    }

    /**
     * 最终释放客户端及待交付回调，之后不得复用。
     */
    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        stopTransport("客户端已销毁");
    }

    private void stopTransport(String reason) {
        generation++;
        ClientTransport previous = transport;
        transport = null;
        config = null;
        initializationPending = false;
        if (previous != null) previous.destroy();
        ArrayList<RequestState> outstanding = new ArrayList<>(requests.values());
        requests.clear();
        for (RequestState state : outstanding) {
            if (state.waitingInitialization) {
                state.stopWaiting();
                postUnsentResult(state.callback, ERROR_NOT_CONNECTED, reason + "；门店绑定尚未发送");
                continue;
            }
            // 配置切换时无法证明旧通道尚未发送，按未知处理且绝不转发到新通道。
            postResult(generation, state.callback, ERROR_RESULT_UNKNOWN, reason, new JSONObject());
        }
    }

    /**
     * 未进入传输层的请求，即使随后切换连接也仍能证明未发送。
     */
    private void postUnsentResult(Callback callback, int code, String message) {
        if (callback == null || destroyed) return;
        deliveries.execute(() -> {
            synchronized (HuoyanClient.this) {
                if (!destroyed) callback.onResult(code, message, new JSONObject());
            }
        });
    }

    private void postState(long current, Runnable callback) {
        deliveries.execute(() -> {
            synchronized (HuoyanClient.this) {
                if (!destroyed && generation == current) callback.run();
            }
        });
    }

    private void postResult(long current, Callback callback, int code, String message, JSONObject data) {
        if (callback == null || destroyed) return;
        JSONObject snapshot = MorningWsProtocol.copy(data);
        deliveries.execute(() -> {
            synchronized (HuoyanClient.this) {
                if (destroyed) return;
                // 即使旧成功回执已排到主线程，也不能在切换机器后写入新会话业务数据。
                if (generation != current) {
                    callback.onResult(ERROR_RESULT_UNKNOWN, "连接已变更，旧请求结果需核对", new JSONObject());
                } else {
                    callback.onResult(code, message, snapshot);
                }
            }
        });
    }

    private static final class RequestState {
        final long generation;
        final Callback callback;
        String transportId;
        boolean waitingInitialization;
        JSONObject params;
        byte[] image;
        Runnable cancelWait;

        RequestState(long generation, Callback callback) {
            this.generation = generation;
            this.callback = callback;
        }

        void stopWaiting() {
            waitingInitialization = false;
            Runnable cancel = cancelWait;
            cancelWait = null;
            if (cancel != null) cancel.run();
        }
    }

    /**
     * 旧系统仅加载 WS，隔离会引用 SharedMemory 的 AIDL 实现。
     */
    @RequiresApi(27)
    private static final class Api27Factory {
        static ClientTransport create(Context context, Listener listener) {
            return new AidlSdkClient(context, listener);
        }
    }
}
