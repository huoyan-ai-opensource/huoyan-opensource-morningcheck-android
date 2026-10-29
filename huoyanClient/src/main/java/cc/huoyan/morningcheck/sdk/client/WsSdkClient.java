package cc.huoyan.morningcheck.sdk.client;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient.Callback;
import cc.huoyan.morningcheck.sdk.client.HuoyanClient.Listener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 晨检壳的 WS v2 客户端。发送、超时和重连共用一个串行调度器，回调统一回到主线程。
 * 网络连接不等于 SDK 就绪；本地业务可用性由最近一次 getStatus 的机器态、证书和模型共同确认。
 */
final class WsSdkClient implements ClientTransport {
    public static final int ERROR_NOT_CONNECTED = -20001;
    public static final int ERROR_CANCELLED = -20002;
    public static final int ERROR_INVALID_REQUEST = -20003;
    public static final int ERROR_RESULT_UNKNOWN = 40009;
    public static final int ERROR_BUSY = 40010;

    interface Connector {
        WebSocket open(Request request, WebSocketListener listener);

        default void destroy() {
        }
    }

    private final Listener listener;
    private final Executor deliveries;
    private final ScheduledExecutorService io;
    private final Connector connector;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    /** 宿主 APK 版本；首次、重连、自动重试与显式 init 使用同一来源。 */
    private final String appVersion;
    private volatile boolean connected;
    private volatile boolean destroyed;
    private volatile JSONObject status = new JSONObject();
    private volatile JSONObject capabilities = new JSONObject();
    private volatile JSONObject classTable = new JSONObject();
    // 以下字段只在 io 线程读写，避免文本声明和对应 binary 被其他请求插入。
    private boolean wanted;
    private long generation;
    private int reconnectAttempt;
    private String endpoint;
    private String activeImageId;
    private WebSocket socket;
    private ScheduledFuture<?> reconnect;
    private ScheduledFuture<?> openingTimeout;
    private ScheduledFuture<?> initRetry;
    private int initRetryAttempt;
    private Integer lastInitCode;
    private Runnable initializationListener;
    private boolean initializationReported;
    // 连接初始化由传输层退避；显式初始化由调用方按真实回执决定是否重试。
    private boolean automaticInitRetryAllowed = true;
    // 同连接快照可由服务端并行响应；只让较新的成功查询更新共享状态。
    private long nextStatusSequence;
    private long acceptedStatusSequence;

    /**
     * 创建面向 Android 主线程回调的客户端；appVersion 取宿主 APK 的 versionName。
     */
    public WsSdkClient(String appVersion, Listener listener) {
        this(appVersion, listener, mainExecutor(), createIo(), defaultConnector());
    }

    WsSdkClient(String appVersion, Listener listener, Executor deliveries,
                ScheduledExecutorService io, Connector connector) {
        this.appVersion = appVersion;
        this.listener = listener;
        this.deliveries = deliveries;
        this.io = io;
        this.connector = connector;
    }

    @Override
    public void setInitializationListener(Runnable listener) {
        initializationListener = listener;
    }

    private static Executor mainExecutor() {
        return new MainDeliveries();
    }

    private static ScheduledExecutorService createIo() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "晨检-SDK-连接");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(30, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static Connector defaultConnector() {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
        return new Connector() {
            @Override
            public WebSocket open(Request request, WebSocketListener listener) {
                return client.newWebSocket(request, listener);
            }

            @Override
            public void destroy() {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdown();
            }
        };
    }

    /**
     * 异步连接；相同地址已连接或正在连接时复用当前连接。
     */
    public void connect(String endpoint) {
        final String address = endpoint == null ? "" : endpoint.trim();
        executeIo(() -> {
            Request request;
            try {
                if (!address.startsWith("ws://") && !address.startsWith("wss://")) {
                    throw new IllegalArgumentException("地址应以 ws:// 或 wss:// 开头");
                }
                request = new Request.Builder().url(address).build();
            } catch (IllegalArgumentException invalid) {
                notifyConnection(connected, "连接地址无效：" + invalid.getMessage());
                return;
            }
            if (wanted && address.equals(this.endpoint)) return;
            stopCurrent("连接已切换，原请求结果未知");
            this.endpoint = address;
            wanted = true;
            reconnectAttempt = 0;
            open(request);
        });
    }

    private void open(Request request) {
        if (!wanted || destroyed) return;
        long attemptGeneration = ++generation;
        notifyConnection(false, "正在连接晨检 SDK");
        openingTimeout = io.schedule(() -> connectionLost(attemptGeneration,
                "连接晨检 SDK 超时"), 15, TimeUnit.SECONDS);
        try {
            socket = connector.open(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    executeIo(() -> opened(attemptGeneration, webSocket));
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    executeIo(() -> receive(attemptGeneration, text));
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(code, null);
                    executeIo(() -> connectionLost(attemptGeneration, "SDK 连接关闭：" + reason));
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    executeIo(() -> connectionLost(attemptGeneration, "SDK 连接已断开"));
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
                    executeIo(() -> connectionLost(attemptGeneration,
                            "无法连接晨检 SDK：" + failure.getClass().getSimpleName()));
                }
            });
        } catch (RuntimeException failure) {
            connectionLost(attemptGeneration, "连接失败：" + failure.getClass().getSimpleName());
        }
    }

    private void opened(long current, WebSocket opened) {
        if (!wanted || current != generation) {
            opened.cancel();
            return;
        }
        cancelOpeningTimeout();
        socket = opened;
        connected = true;
        nextStatusSequence = 0;
        acceptedStatusSequence = 0;
        reconnectAttempt = 0;
        resetInitRetry();
        initializationReported = false;
        notifyConnection(true, "SDK 已连接，正在初始化");
        // 先声明显式 init。快照按服务端旁路读取，不需要等模型加载结束才显示状态。
        send(UUID.randomUUID().toString(), "init", new JSONObject(), null, null);
        send(UUID.randomUUID().toString(), "getStatus", new JSONObject(), null, null);
        send(UUID.randomUUID().toString(), "getCapabilities", new JSONObject(), null, null);
        send(UUID.randomUUID().toString(), "get_class_table", new JSONObject(), null, null);
    }

    /**
     * 发送一次请求并返回用于取消的 requestId；本客户端不会自动重放业务请求。
     * image 的所有权保留给调用方，但在回调前不得修改其内容。
     */
    public String request(String command, JSONObject params, byte[] image, Callback callback) {
        String id = UUID.randomUUID().toString();
        JSONObject snapshot;
        try {
            snapshot = MorningWsProtocol.copy(params);
        } catch (IllegalArgumentException invalid) {
            deliver(callback, ERROR_INVALID_REQUEST, invalid.getMessage(), new JSONObject());
            return id;
        }
        executeIo(() -> {
            if ("init".equals(command)) {
                resetInitRetry();
                automaticInitRetryAllowed = callback == null;
            }
            send(id, MorningWsProtocol.command(command), snapshot, image, callback);
        });
        return id;
    }

    private void send(String id, String command, JSONObject params, byte[] image, Callback callback) {
        try {
            MorningWsProtocol.validate(command, params, image);
        } catch (IllegalArgumentException invalid) {
            deliver(callback, ERROR_INVALID_REQUEST, invalid.getMessage(), new JSONObject());
            return;
        }
        if (!connected || socket == null) {
            deliver(callback, ERROR_NOT_CONNECTED, "晨检 SDK 尚未连接", new JSONObject());
            return;
        }
        if (image != null && activeImageId != null) {
            deliver(callback, ERROR_BUSY, "上一帧尚未返回，本帧未发送", new JSONObject());
            return;
        }
        if (pending.size() >= 32) {
            deliver(callback, ERROR_BUSY, "请求过多，本次请求未发送", new JSONObject());
            return;
        }
        Pending operation = new Pending(command, callback,
                "getStatus".equals(command) ? ++nextStatusSequence : 0);
        try {
            // 大块图像编码在 io 线程完成，声明与 binary 连续加入同一 WebSocket 队列。
            ByteString binary = image == null ? null : ByteString.of(MorningWsProtocol.binary(id, image));
            JSONObject wireParams = params;
            if ("init".equals(command)) {
                wireParams = MorningWsProtocol.copy(params);
                wireParams.put("app_version", appVersion);
            }
            String text = MorningWsProtocol.text(id, command, wireParams);
            pending.put(id, operation);
            if (image != null) activeImageId = id;
            if (!socket.send(text)) {
                complete(id, ERROR_NOT_CONNECTED, "发送失败，请求未进入 SDK", new JSONObject());
                connectionLost(generation, "SDK 发送队列已关闭");
                return;
            }
            if (binary != null && !socket.send(binary)) {
                connectionLost(generation, "图片发送中断，请求结果未知");
                return;
            }
            operation.timeout = io.schedule(() -> complete(id, ERROR_RESULT_UNKNOWN,
                            "等待 SDK 回执超时，操作可能仍在执行；请核对结果后重试", new JSONObject()),
                    MorningWsProtocol.timeoutMs(command), TimeUnit.MILLISECONDS);
        } catch (JSONException | RuntimeException invalid) {
            if (pending.containsKey(id)) {
                connectionLost(generation, "请求发送异常，结果未知");
            } else {
                deliver(callback, ERROR_INVALID_REQUEST, "请求编码失败", new JSONObject());
            }
        }
    }

    private void receive(long current, String text) {
        if (!connected || current != generation) return;
        try {
            JSONObject envelope = new JSONObject(text);
            // SDK 的事件仍沿用 v1 envelope。收到事件时重新读取唯一权威状态快照。
            if ("event".equals(envelope.optString("type"))) {
                refreshStatusIfIdle();
                return;
            }
            String id = envelope.optString("requestId", "");
            Pending operation = pending.get(id);
            if (operation == null) return;
            Object rawCode = envelope.opt("code");
            if (!(rawCode instanceof Number)
                    || ((Number) rawCode).doubleValue() != ((Number) rawCode).intValue()) {
                connectionLost(current, "SDK 回执缺少有效错误码，结果未知");
                return;
            }
            int code = ((Number) rawCode).intValue();
            JSONObject data = envelope.optJSONObject("data");
            if (data == null) data = new JSONObject();
            if (code == 0) {
                if ("getStatus".equals(operation.command)) {
                    if (operation.statusSequence >= acceptedStatusSequence) {
                        acceptedStatusSequence = operation.statusSequence;
                        status = MorningWsProtocol.copy(data);
                        notifyStatus();
                        scheduleInitRetry();
                    }
                } else if ("getCapabilities".equals(operation.command)) {
                    capabilities = MorningWsProtocol.copy(data);
                } else if ("get_class_table".equals(operation.command)) {
                    classTable = MorningWsProtocol.copy(data);
                }
            }
            complete(id, code, envelope.optString("reason", code == 0 ? "成功" : "SDK 请求失败"), data);
        } catch (JSONException invalid) {
            connectionLost(current, "SDK 回执无法解析，结果未知");
        }
    }

    private void refreshStatusIfIdle() {
        for (Pending operation : pending.values()) {
            if ("getStatus".equals(operation.command)) return;
        }
        send(UUID.randomUUID().toString(), "getStatus", new JSONObject(), null, null);
    }

    private void resetInitRetry() {
        cancelInitRetry();
        initializationReported = false;
        initRetryAttempt = 0;
        lastInitCode = null;
        automaticInitRetryAllowed = true;
    }

    private void cancelInitRetry() {
        if (initRetry != null) initRetry.cancel(false);
        initRetry = null;
    }

    private boolean mayRetryInit() {
        // 后续状态快照也不能重新打开已交给显式调用方的初始化重试。
        if (!automaticInitRetryAllowed) return false;
        if (lastInitCode == null || (lastInitCode != ERROR_BUSY && lastInitCode != -3))
            return false;
        String state = status.optString("sdk_state", "");
        // -3 也可能是证书过期，必须结合机器态；不解析中文 reason 猜测是否暂态。
        switch (state) {
            case "NOT_INIT":
            case "INITIALIZING":
            case "REGISTERED":
            case "MODEL_PREPARING":
            case "MODEL_NOT_READY":
            case "BACKEND_UNAVAILABLE":
                return true;
            default:
                return state.isEmpty() && lastInitCode == ERROR_BUSY;
        }
    }

    private void scheduleInitRetry() {
        if (initializationReported) return;
        if (!wanted || !connected || destroyed || !mayRetryInit()) {
            cancelInitRetry();
            return;
        }
        if (initRetry != null || initRetryAttempt >= 6) return;
        for (Pending operation : pending.values()) {
            if ("init".equals(operation.command)) return;
        }
        long current = generation;
        long delay = MorningWsProtocol.reconnectDelayMs(initRetryAttempt++);
        initRetry = io.schedule(() -> {
            initRetry = null;
            if (current != generation || !wanted || !connected || destroyed) return;
            if (!mayRetryInit()) {
                reportInitializationIfFinished();
                return;
            }
            for (Pending operation : pending.values()) {
                if ("init".equals(operation.command)) return;
            }
            send(UUID.randomUUID().toString(), "init", new JSONObject(), null, null);
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void complete(String id, int code, String message, JSONObject data) {
        Pending operation = pending.remove(id);
        if (operation == null) return;
        if (operation.timeout != null) operation.timeout.cancel(false);
        if (id.equals(activeImageId)) activeImageId = null;
        deliver(operation.callback, code, message, data);
        if ("init".equals(operation.command) && connected) {
            lastInitCode = operation.cancelled ? ERROR_CANCELLED : code;
            refreshStatusIfIdle();
            if (!operation.cancelled && operation.automaticInitialization) scheduleInitRetry();
        } else if ("activate".equals(operation.command) && connected) {
            refreshStatusIfIdle();
        }
        reportInitializationIfFinished();
    }

    private void reportInitializationIfFinished() {
        if (initializationReported || !connected || !wanted || destroyed || lastInitCode == null
                || lastInitCode == ERROR_RESULT_UNKNOWN || initRetry != null) return;
        for (Pending operation : pending.values()) {
            if ("init".equals(operation.command)) return;
            // -3/BUSY 的重试判定依赖新快照；不能先放行 bind，下一刻又补发 init。
            if ((lastInitCode == -3 || lastInitCode == ERROR_BUSY) && initRetryAttempt < 6
                    && "getStatus".equals(operation.command)) return;
        }
        if (mayRetryInit() && initRetryAttempt < 6) return;
        initializationReported = true;
        long current = generation;
        postDelivery(() -> {
            if (current == generation && connected && initializationListener != null) {
                initializationListener.run();
            }
        });
    }

    /**
     * 停止等待一个请求；已发出的 native 操作无法撤回，不能将取消理解为未执行。
     */
    public void cancel(String requestId) {
        if (requestId == null) return;
        executeIo(() -> {
            Pending operation = pending.get(requestId);
            if (operation == null) return;
            Callback callback = operation.callback;
            operation.callback = null;
            operation.cancelled = true;
            if ("init".equals(operation.command)) {
                resetInitRetry();
                automaticInitRetryAllowed = false;
            }
            // 协议没有撤回操作。保留在途槽位至真实回执/超时，避免取消后立刻再灌入一帧。
            deliver(callback, ERROR_CANCELLED,
                    "已停止等待；SDK 操作可能继续执行，结果需核对", new JSONObject());
        });
    }

    private void connectionLost(long current, String message) {
        if (current != generation) return;
        generation++;
        resetInitRetry();
        cancelOpeningTimeout();
        if (socket != null) socket.cancel();
        socket = null;
        connected = false;
        status = new JSONObject();
        capabilities = new JSONObject();
        classTable = new JSONObject();
        clearPending("SDK 连接中断，已发送请求结果未知；写操作请核对后重试");
        notifyStatus();
        if (!wanted) return;
        long delay = MorningWsProtocol.reconnectDelayMs(reconnectAttempt++);
        notifyConnection(false, message + "，" + (delay / 1000.0) + " 秒后重连");
        reconnect = io.schedule(() -> {
            reconnect = null;
            if (wanted) open(new Request.Builder().url(endpoint).build());
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void clearPending(String message) {
        for (String id : new ArrayList<>(pending.keySet())) {
            complete(id, ERROR_RESULT_UNKNOWN, message, new JSONObject());
        }
        activeImageId = null;
    }

    private void cancelOpeningTimeout() {
        if (openingTimeout != null) openingTimeout.cancel(false);
        openingTimeout = null;
    }

    private void stopCurrent(String reason) {
        wanted = false;
        generation++;
        resetInitRetry();
        if (reconnect != null) reconnect.cancel(false);
        reconnect = null;
        cancelOpeningTimeout();
        if (socket != null) socket.cancel();
        socket = null;
        connected = false;
        status = new JSONObject();
        capabilities = new JSONObject();
        classTable = new JSONObject();
        clearPending(reason);
    }

    /**
     * 关闭连接并取消全部重连预约；之后只有显式 connect 才会重新连接。
     */
    @Override
    public void close() {
        executeIo(() -> {
            stopCurrent("连接已关闭，已发送请求结果未知");
            notifyConnection(false, "SDK 连接已关闭");
            notifyStatus();
        });
    }

    /**
     * 最终释放连接、调度线程与主线程待交付回调；Activity.onDestroy 调用，之后不可恢复。
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        connected = false;
        status = new JSONObject();
        if (deliveries instanceof MainDeliveries) ((MainDeliveries) deliveries).clear();
        try {
            io.execute(() -> {
                try {
                    stopCurrent("客户端已销毁");
                    connector.destroy();
                } finally {
                    io.shutdownNow();
                }
            });
        } catch (RejectedExecutionException alreadyClosed) {
            connector.destroy();
        }
    }

    /**
     * 返回最近成功读取的 SDK 状态副本；断连后为空对象。
     */
    public JSONObject getStatus() {
        return MorningWsProtocol.copy(status);
    }

    /**
     * 返回当前连接读取到的能力表副本。
     */
    public JSONObject getCapabilities() {
        return MorningWsProtocol.copy(capabilities);
    }

    /**
     * 返回当前 SDK 的头部和手部类别表副本。
     */
    public JSONObject getClassTable() {
        return MorningWsProtocol.copy(classTable);
    }

    /**
     * 连接成功仅代表传输可用。
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 同时确认机器态、模型与证书；缺失字段属于未知，不能当成可识别。
     */
    public boolean isReady() {
        JSONObject snapshot = status;
        String state = snapshot.optString("sdk_state");
        return connected && !destroyed && ("READY".equals(state) || "OFFLINE".equals(state))
                && Boolean.TRUE.equals(snapshot.opt("model_ready"))
                && Boolean.TRUE.equals(snapshot.opt("certificate_valid"))
                && !Boolean.TRUE.equals(snapshot.opt("key_lost"));
    }

    private void executeIo(Runnable task) {
        if (destroyed) return;
        try {
            io.execute(() -> {
                if (!destroyed) task.run();
            });
        } catch (RejectedExecutionException stopped) {
            // 最终释放和网络末尾回调竞态时，不再向已结束的 Activity 投递任何内容。
            if (!destroyed) throw stopped;
        }
    }

    private void postDelivery(Runnable callback) {
        if (!destroyed) deliveries.execute(() -> {
            if (!destroyed) callback.run();
        });
    }

    private void notifyConnection(boolean value, String message) {
        if (listener != null) postDelivery(() -> listener.onConnection(value, message));
    }

    private void notifyStatus() {
        JSONObject snapshot = getStatus();
        if (listener != null) postDelivery(() -> listener.onStatus(snapshot));
    }

    private void deliver(Callback callback, int code, String message, JSONObject data) {
        if (callback == null) return;
        JSONObject snapshot = MorningWsProtocol.copy(data);
        postDelivery(() -> callback.onResult(code, message, snapshot));
    }

    private static final class Pending {
        final String command;
        // 回调在取消时会清空，连接初始化归属只在提交时确定。
        final boolean automaticInitialization;
        // 仍向原调用方交付迟到回执，但它不能倒退全局就绪状态或触发初始化。
        final long statusSequence;
        Callback callback;
        boolean cancelled;
        ScheduledFuture<?> timeout;

        Pending(String command, Callback callback, long statusSequence) {
            this.command = command;
            this.callback = callback;
            this.automaticInitialization = "init".equals(command) && callback == null;
            this.statusSequence = statusSequence;
        }
    }

    private static final class MainDeliveries implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean cleared;

        @Override
        public synchronized void execute(Runnable command) {
            if (!cleared) handler.post(command);
        }

        synchronized void clear() {
            cleared = true;
            handler.removeCallbacksAndMessages(null);
        }
    }
}
