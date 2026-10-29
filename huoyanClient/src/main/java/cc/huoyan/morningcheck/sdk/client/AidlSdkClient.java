package cc.huoyan.morningcheck.sdk.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import cc.huoyan.morningcheck.app.aidl.BindDeviceParcel;
import cc.huoyan.morningcheck.app.aidl.FeedbackParcel;
import cc.huoyan.morningcheck.app.aidl.ISdkCommService;
import cc.huoyan.morningcheck.app.aidl.ImageRequestParcel;

/**
 * 晨检 Binder 通道；生命周期串行调度，状态读取与可能等待模型的业务调用分别执行。
 */
@RequiresApi(27)
final class AidlSdkClient implements ClientTransport {
    private static final String SDK_PACKAGE = "cc.huoyan.morningcheck";
    private static final String SDK_SERVICE = "cc.huoyan.morningcheck.app.comm.SdkCommService";
    private final Context context;
    /** 宿主 APK 版本，自动初始化与显式重试共用。 */
    private final String appVersion;
    private final HuoyanClient.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            task -> new Thread(task, "晨检-AIDL-调度"));
    // 有界队列避免 Binder 卡住时不断堆积帧；不因超时反复新建工作线程。
    private final ThreadPoolExecutor actions = worker("晨检-AIDL-调用");
    private final ThreadPoolExecutor queries = worker("晨检-AIDL-状态");
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private volatile boolean destroyed;
    private volatile boolean connected;
    private volatile long generation;
    private volatile JSONObject status = new JSONObject();
    private volatile JSONObject capabilities = new JSONObject();
    private volatile JSONObject classTable = new JSONObject();
    // 仅 control 访问；Binding 自带代次，过期 Android 回调不能替换新连接。
    private boolean wanted;
    private ISdkCommService service;
    private Binding binding;
    private int reconnectAttempt;
    private int initAttempt;
    // 连接初始化可退避；显式初始化的回执及重试由宿主流程接管。
    private boolean automaticInitRetryAllowed = true;
    private ScheduledFuture<?> reconnect;
    private ScheduledFuture<?> openingTimeout;
    private ScheduledFuture<?> initRetry;
    private String activeImageId;
    private Runnable initializationListener;

    AidlSdkClient(Context context, HuoyanClient.Listener listener) {
        if (android.os.Build.VERSION.SDK_INT < 27)
            throw new IllegalArgumentException("AIDL 需要 Android 8.1 及以上，请使用 WS");
        this.context = context.getApplicationContext();
        this.appVersion = ClientAppVersion.read(this.context);
        this.listener = listener;
        control.setRemoveOnCancelPolicy(true);
        control.setKeepAliveTime(30, TimeUnit.SECONDS);
        control.allowCoreThreadTimeOut(true);
    }

    @Override
    public void setInitializationListener(Runnable listener) {
        initializationListener = listener;
    }

    private static ThreadPoolExecutor worker(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), task -> new Thread(task, name));
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Override
    public void connect(String ignoredEndpoint) {
        execute(() -> {
            if (wanted) return;
            wanted = true;
            reconnectAttempt = 0;
            bind();
        });
    }

    private void bind() {
        if (!wanted || destroyed) return;
        Binding attempt = new Binding(++generation);
        binding = attempt;
        notifyConnection("正在绑定晨检 SDK");
        try {
            Intent intent = new Intent("cc.huoyan.morningcheck.SdkCommService")
                    .setComponent(new ComponentName(SDK_PACKAGE, SDK_SERVICE));
            // Android 可能先登记 ServiceDispatcher，再返回 false 或抛出异常；这些路径同样必须尝试解绑。
            attempt.registered = true;
            boolean accepted = context.bindService(intent, attempt, Context.BIND_AUTO_CREATE);
            if (!accepted) {
                lost(attempt.epoch, "未找到可绑定的晨检 SDK");
                return;
            }
            openingTimeout = control.schedule(() -> lost(attempt.epoch, "绑定晨检 SDK 超时"), 15, TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
            lost(attempt.epoch, "绑定晨检 SDK 失败：" + failure.getClass().getSimpleName());
        }
    }

    private final class Binding implements ServiceConnection {
        final long epoch;
        boolean registered;

        Binding(long epoch) {
            this.epoch = epoch;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            execute(() -> {
                if (epoch != generation || !wanted) return;
                cancel(openingTimeout);
                openingTimeout = null;
                service = ISdkCommService.Stub.asInterface(binder);
                if (service == null) {
                    lost(epoch, "SDK 返回空 Binder");
                    return;
                }
                connected = true;
                reconnectAttempt = 0;
                initAttempt = 0;
                automaticInitRetryAllowed = true;
                notifyConnection("SDK 已连接，正在初始化");
                // 显式 init 是动作；状态旁路不必等其 120 秒预算耗尽。
                enqueue(UUID.randomUUID().toString(), "init", new JSONObject(), null, null);
                refresh("getStatus");
                refresh("getCapabilities");
                refresh("get_class_table");
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            execute(() -> lost(epoch, "晨检 SDK 进程已断开"));
        }

        @Override
        public void onBindingDied(ComponentName name) {
            execute(() -> lost(epoch, "晨检 SDK 绑定已失效"));
        }

        @Override
        public void onNullBinding(ComponentName name) {
            execute(() -> lost(epoch, "晨检 SDK 拒绝本次绑定，请检查版本和准入配置"));
        }
    }

    @Override
    public String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
        String id = UUID.randomUUID().toString();
        final JSONObject copy;
        try {
            copy = MorningWsProtocol.copy(params);
        } catch (IllegalArgumentException invalid) {
            deliver(callback, HuoyanClient.ERROR_INVALID_REQUEST, invalid.getMessage(), new JSONObject());
            return id;
        }
        execute(() -> {
            if ("init".equals(command)) {
                cancel(initRetry);
                initRetry = null;
                initAttempt = 0;
                automaticInitRetryAllowed = callback == null;
            }
            enqueue(id, MorningWsProtocol.command(command), copy, image, callback);
        });
        return id;
    }

    private void enqueue(String id, String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
        try {
            MorningWsProtocol.validate(command, params, image);
            validateParams(command, params);
        } catch (JSONException | IllegalArgumentException invalid) {
            deliver(callback, HuoyanClient.ERROR_INVALID_REQUEST, invalid.getMessage(), new JSONObject());
            return;
        }
        if (!connected || service == null) {
            deliver(callback, HuoyanClient.ERROR_NOT_CONNECTED, "晨检 SDK 尚未连接，请求未发送", new JSONObject());
            return;
        }
        if (pending.size() >= 16 || (image != null && activeImageId != null)) {
            deliver(callback, HuoyanClient.ERROR_BUSY, "已有请求尚未完成，本次请求未发送", new JSONObject());
            return;
        }
        // 文件准备在独立工作线程，取消回调可能先到；持有副本避免调用方回收/复用原帧造成错图。
        Pending operation = new Pending(id, command, params, image == null ? null : image.clone(), callback, generation, service);
        pending.put(id, operation);
        if (image != null) activeImageId = id;
        operation.timeout = control.schedule(() -> timeout(operation),
                MorningWsProtocol.timeoutMs(command), TimeUnit.MILLISECONDS);
        try {
            (isQuery(command) ? queries : actions).execute(() -> invoke(operation));
        } catch (RejectedExecutionException busy) {
            if (id.equals(activeImageId)) activeImageId = null;
            complete(operation, new AidlResultMapper.Reply(HuoyanClient.ERROR_BUSY, "调用队列已满，本次请求未发送", new JSONObject()));
        }
    }

    private static boolean isQuery(String command) {
        return "getStatus".equals(command) || "getCapabilities".equals(command)
                || "get_class_table".equals(command) || "getCertInfo".equals(command)
                || "getDownloadProgress".equals(command);
    }

    /**
     * 文件写入、共享内存及 Binder 调用均在后台；调用超时只停止交付，不能中断远端写入。
     */
    private void invoke(Pending operation) {
        AidlImage carrier = null;
        Runnable completion = null;
        try {
            if (operation.state.isFinished()) return;
            if (operation.image != null) {
                carrier = "uploadPhoto".equals(operation.command)
                        ? AidlImage.photo(operation.image, "png".equals(operation.params.optString("format"))
                                                           ? ImageRequestParcel.FORMAT_PNG : ImageRequestParcel.FORMAT_JPEG)
                        : AidlImage.rgb(context.getCacheDir(), operation.image,
                        operation.params.optInt("width"), operation.params.optInt("height"));
            }
            FeedbackParcel feedback = "submitFeedback".equals(operation.command)
                    ? AidlResultMapper.feedback(operation.params) : null;
            if (destroyed || operation.epoch != generation || !operation.state.begin()) return;
            Object result = call(operation, carrier, feedback);
            AidlResultMapper.Reply reply = AidlResultMapper.map(result);
            completion = () -> complete(operation, reply);
        } catch (RemoteException failure) {
            completion = () -> lost(operation.epoch, "SDK Binder 已断开，已发送请求结果未知");
        } catch (Exception failure) {
            int code = operation.state.hasStarted() ? HuoyanClient.ERROR_RESULT_UNKNOWN : HuoyanClient.ERROR_INVALID_REQUEST;
            completion = () -> complete(operation, new AidlResultMapper.Reply(code,
                    operation.state.hasStarted() ? "SDK 调用未取得完整回执，结果未知" : "请求未发送：" + failure.getMessage(), new JSONObject()));
        } finally {
            if (carrier != null) carrier.close();
            Runnable finished = completion;
            // 真实调用和载体清理结束后，先在同一个调度任务中释放槽位再交付结果。
            // 这样回调内串接的 detectFace→checkHead/register 不会撞上上一帧的槽位；
            // 超时/取消仍不提前释放，旧连接的迟到调用也不能清掉新请求的槽位。
            execute(() -> {
                if (operation.id.equals(activeImageId)) activeImageId = null;
                if (finished != null) finished.run();
            });
        }
    }

    private Object call(Pending op, AidlImage carrier, FeedbackParcel feedback) throws RemoteException, JSONException {
        ISdkCommService sdk = op.service;
        JSONObject p = op.params;
        switch (op.command) {
            case "getCapabilities":
                return sdk.getCapabilities();
            case "getStatus":
                return sdk.getStatus();
            case "getCertInfo":
                return sdk.getCertInfo();
            case "getDownloadProgress":
                return sdk.getDownloadProgress();
            case "activate":
                return sdk.activate(p.getString("activation_code"), p.getString("model_code"));
            case "queryLicenseModels":
                return sdk.queryLicenseModels(p.getString("activation_code"));
            case "queryDeviceModels":
                return sdk.queryDeviceModels();
            case "setBaseUrl":
                return sdk.setBaseUrl(p.getString("url"));
            case "identify":
                return sdk.identify(carrier.frame);
            case "detectFace":
                return sdk.detectFace(carrier.frame, RequestParameters.faceLimit(p));
            case "checkHead":
                return sdk.checkHead(carrier.frame, p.getInt("max_detections"));
            case "checkHand":
                return sdk.checkHand(carrier.frame, p.getInt("max_detections"));
            case "register":
                return sdk.register(carrier.frame, p.getLong("person_id"));
            case "forget":
                return sdk.forget(p.getLong("person_id"));
            case "forgetByUid":
                return sdk.forgetByUid(p.getLong("uid"));
            case "userCount":
                return sdk.userCount();
            case "userDetail":
                return sdk.userDetail(p.getLong("person_id"));
            case "submitFeedback":
                return sdk.submitFeedback(feedback);
            case "uploadPhoto":
                return sdk.uploadPhoto(carrier.photo);
            case "init":
                return sdk.init(appVersion);
            case "bindDevice":
                return sdk.bindDevice(new BindDeviceParcel(p.getLong("account_id"),
                        p.getString("shop_id"), AidlResultMapper.optionalString(p, "shop_name"), AidlResultMapper.optionalString(p, "shop_no")));
            case "setCloudStatus":
                return sdk.setCloudStatus(p.getBoolean("enabled"));
            case "getCloudStatus":
                return sdk.getCloudStatus();
            case "get_class_table":
                return sdk.getClassTable();
            default:
                throw new IllegalArgumentException("晨检 AIDL 不支持命令：" + op.command);
        }
    }

    /**
     * 在提交 Binder 之前检查参数；格式错误必须归为未发送，不能冒充结果未知。
     */
    static void validateParams(String command, JSONObject p) throws JSONException {
        RequestParameters.validate(command, p);
    }

    private void complete(Pending operation, AidlResultMapper.Reply reply) {
        if (!operation.state.finish()) return;
        completed(operation, reply);
    }

    /**
     * 先关闭提交门再判断是否发送，防止判定未发送与工作线程开始 Binder 之间的竞态。
     */
    private void timeout(Pending operation) {
        if (!operation.state.finish()) return;
        boolean sent = operation.state.hasStarted();
        completed(operation, new AidlResultMapper.Reply(sent ? HuoyanClient.ERROR_RESULT_UNKNOWN : HuoyanClient.ERROR_NOT_CONNECTED,
                sent ? "SDK 回执超时，结果未知；写操作请核对后重试" : "请求排队超时，未发送到 SDK", new JSONObject()));
    }

    private void completed(Pending operation, AidlResultMapper.Reply reply) {
        pending.remove(operation.id);
        cancel(operation.timeout);
        if (operation.epoch != generation || destroyed) return;
        if (reply.code == 0) {
            switch (operation.command) {
                case "getStatus":
                    status = MorningWsProtocol.copy(reply.data);
                    notifyStatus();
                    break;
                case "getCapabilities":
                    capabilities = MorningWsProtocol.copy(reply.data);
                    break;
                case "get_class_table":
                    classTable = MorningWsProtocol.copy(reply.data);
                    break;
                default:
                    break;
            }
        }
        deliver(operation.callback, reply.code, reply.message, reply.data);
        if ("init".equals(operation.command) || "activate".equals(operation.command))
            refresh("getStatus");
        if ("init".equals(operation.command)
                && shouldRetryInitialization(automaticInitRetryAllowed && operation.automaticInitialization,
                reply.code, initAttempt, operation.cancelled) && wanted && connected) {
            long epoch = generation;
            initRetry = control.schedule(() -> {
                initRetry = null;
                if (epoch == generation && connected && automaticInitRetryAllowed) refresh("init");
            }, MorningWsProtocol.reconnectDelayMs(initAttempt++), TimeUnit.MILLISECONDS);
        } else if ("init".equals(operation.command) && reply.code != HuoyanClient.ERROR_RESULT_UNKNOWN) {
            // init 已得终态才释放待绑定请求；失败不等于禁止绑定，由壳按真实能力准入。
            post(() -> {
                if (initializationListener != null) initializationListener.run();
            });
        }
    }

    /**
     * 显式调用已有回调负责收口；只有连接初始化的确定繁忙才允许有限重试。
     */
    static boolean shouldRetryInitialization(boolean automatic, int code, int attempts, boolean cancelled) {
        return automatic && code == HuoyanClient.ERROR_BUSY && attempts < 6 && !cancelled;
    }

    private void refresh(String command) {
        if (!connected) return;
        for (Pending p : pending.values()) if (command.equals(p.command)) return;
        enqueue(UUID.randomUUID().toString(), command, new JSONObject(), null, null);
    }

    @Override
    public void cancel(String id) {
        execute(() -> {
            Pending p = pending.get(id);
            if (p == null || p.cancelled) return;
            p.cancelled = true;
            HuoyanClient.Callback callback = p.callback;
            p.callback = null;
            if ("init".equals(p.command)) {
                cancel(initRetry);
                initRetry = null;
            }
            // 已发出的 Binder 不可撤回；队列内取消则通过提交门阻止其随后执行。
            if (!p.state.hasStarted())
                complete(p, new AidlResultMapper.Reply(HuoyanClient.ERROR_CANCELLED, "已取消排队请求", new JSONObject()));
            deliver(callback, HuoyanClient.ERROR_CANCELLED, "已停止等待；已发送操作可能继续执行，请核对结果", new JSONObject());
        });
    }

    private void lost(long epoch, String message) {
        if (epoch != generation || !wanted) return;
        stopCurrent(message);
        wanted = true;
        long delay = MorningWsProtocol.reconnectDelayMs(reconnectAttempt++);
        notifyConnection(message + "，稍后自动重连");
        reconnect = control.schedule(this::bind, delay, TimeUnit.MILLISECONDS);
    }

    private void stopCurrent(String message) {
        wanted = false;
        generation++;
        cancel(reconnect);
        cancel(openingTimeout);
        cancel(initRetry);
        reconnect = null;
        openingTimeout = null;
        initRetry = null;
        Binding old = binding;
        binding = null;
        if (old != null && old.registered) {
            old.registered = false;
            try {
                context.unbindService(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
        connected = false;
        service = null;
        status = new JSONObject();
        capabilities = new JSONObject();
        classTable = new JSONObject();
        for (Pending p : new ArrayList<>(pending.values())) {
            if (p.state.finish()) {
                cancel(p.timeout);
                deliver(p.callback, p.state.hasStarted() ? HuoyanClient.ERROR_RESULT_UNKNOWN : HuoyanClient.ERROR_NOT_CONNECTED,
                        message + (p.state.hasStarted() ? "；已发送请求结果未知" : "；请求未发送"), new JSONObject());
            }
        }
        pending.clear();
        notifyStatus();
    }

    @Override
    public void close() {
        execute(() -> {
            stopCurrent("SDK 连接已关闭");
            notifyConnection("SDK 连接已关闭");
        });
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        connected = false;
        main.removeCallbacksAndMessages(null);
        try {
            control.execute(() -> {
                stopCurrent("客户端已销毁");
                actions.shutdown();
                queries.shutdown();
                control.shutdownNow();
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    @Override
    public JSONObject getStatus() {
        return MorningWsProtocol.copy(status);
    }

    @Override
    public JSONObject getCapabilities() {
        return MorningWsProtocol.copy(capabilities);
    }

    @Override
    public JSONObject getClassTable() {
        return MorningWsProtocol.copy(classTable);
    }

    @Override
    public boolean isConnected() {
        return connected && !destroyed;
    }

    @Override
    public boolean isReady() {
        JSONObject snapshot = status;
        String state = snapshot.optString("sdk_state");
        // OFFLINE 仅限制云端外发，本地识别、检测与登记仍需有效证书和就绪模型。
        return isConnected() && ("READY".equals(state) || "OFFLINE".equals(state))
                && Boolean.TRUE.equals(snapshot.opt("model_ready"))
                && Boolean.TRUE.equals(snapshot.opt("certificate_valid"))
                && !Boolean.TRUE.equals(snapshot.opt("key_lost"));
    }

    private void execute(Runnable task) {
        if (destroyed) return;
        try {
            control.execute(() -> {
                if (!destroyed) task.run();
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void notifyConnection(String message) {
        boolean current = connected;
        post(() -> {
            if (listener != null) listener.onConnection(current, message);
        });
    }

    private void notifyStatus() {
        JSONObject snapshot = getStatus();
        post(() -> {
            if (listener != null) listener.onStatus(snapshot);
        });
    }

    private void deliver(HuoyanClient.Callback callback, int code, String message, JSONObject data) {
        AidlResultDelivery.post(main::post, () -> generation, () -> destroyed,
                callback, code, message, data);
    }

    private void post(Runnable callback) {
        long epoch = generation;
        if (!destroyed) main.post(() -> {
            if (!destroyed && epoch == generation) callback.run();
        });
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) task.cancel(false);
    }

    /**
     * 请求只在所属连接执行一次；图像由调用方保留到回调，不被客户端回收。
     */
    private static final class Pending {
        final String id, command;
        final JSONObject params;
        final byte[] image;
        HuoyanClient.Callback callback;
        final long epoch;
        final ISdkCommService service;
        final AidlCallState state = new AidlCallState();
        ScheduledFuture<?> timeout;
        boolean cancelled;
        // 取消会清空 callback，因此须保存提交时是否属于连接初始化。
        final boolean automaticInitialization;

        Pending(String id, String command, JSONObject params, byte[] image,
                HuoyanClient.Callback callback, long epoch, ISdkCommService service) {
            this.id = id;
            this.command = command;
            this.params = params;
            this.image = image;
            this.callback = callback;
            this.epoch = epoch;
            this.service = service;
            this.automaticInitialization = "init".equals(command) && callback == null;
        }
    }
}
