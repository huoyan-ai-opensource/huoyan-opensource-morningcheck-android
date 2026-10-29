package cc.huoyan.opensource.morningcheck.login;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.MorningApplication;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/**
 * 单次扫码登录由 Application 持有。页面离开只解绑监听，不撤回已经提交的云端绑定。
 * 所有状态和客户端调用在主线程收口；仅归属的磁盘提交在短生命周期后台线程执行。
 */
public final class ShopLoginCoordinator {
    private static final long CONNECTION_TIMEOUT_MS = 5_000;

    public enum Phase {IDLE, WAITING_CONNECTION, BINDING, SAVING, SUCCESS, ERROR}

    public static final class State {
        public final Phase phase;
        public final long attempt;
        public final String message;

        private State(Phase phase, long attempt, String message) {
            this.phase = phase;
            this.attempt = attempt;
            this.message = message;
        }

        public boolean isRunning() {
            return phase == Phase.WAITING_CONNECTION || phase == Phase.BINDING || phase == Phase.SAVING;
        }
    }

    public interface Listener {
        void onState(State state);
    }

    interface ConnectionListener {
        void onConnection(boolean connected, String message);
    }

    interface ResultListener {
        void onResult(int code, String message, JSONObject data);
    }

    interface Client {
        void connect(SdkConfig config);

        void bind(String raw, ResultListener listener);

        void destroy();
    }

    interface ClientFactory {
        Client create(ConnectionListener listener);
    }

    interface Timer {
        Runnable after(long delayMs, Runnable action);
    }

    interface Storage {
        boolean save(Binding binding);
    }

    static final class Binding {
        final String accountId;
        final String shopId;
        final String externalShopId;
        final SdkConfig config;
        final String accountName;
        final String shopName;
        final String shopNo;

        Binding(String accountId, String shopId, String externalShopId, SdkConfig config) {
            this(accountId, shopId, externalShopId, config, "", "", "");
        }

        Binding(String accountId, String shopId, String externalShopId, SdkConfig config,
                String accountName, String shopName, String shopNo) {
            this.accountId = accountId;
            this.shopId = shopId;
            this.externalShopId = externalShopId;
            this.config = config;
            this.accountName = accountName;
            this.shopName = shopName;
            this.shopNo = shopNo;
        }
    }

    private final ClientFactory factory;
    private final Timer timer;
    private final Storage storage;
    private final Executor worker;
    private final Executor ui;
    private final Runnable checkThread;
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private State state = new State(Phase.IDLE, 0, "请扫描门店二维码");
    private Client client;
    private Runnable cancelConnectionTimeout;

    public ShopLoginCoordinator(Context applicationContext) {
        Context app = Objects.requireNonNull(applicationContext, "应用 Context 不能为空").getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        factory = listener -> {
            MorningSdkSession session = ((MorningApplication) app).sdkSession();
            HuoyanClient sdk = session.client();
            MorningSdkSession.Listener observer = new MorningSdkSession.Listener() {
                @Override
                public void onConnection(boolean connected, String message) {
                    listener.onConnection(connected, message);
                }

                @Override
                public void onStatus(JSONObject status) {
                }
            };
            return new Client() {
                @Override
                public void connect(SdkConfig config) {
                    session.connect(config);
                    session.attach(observer);
                }

                @Override
                public void bind(String raw, ResultListener callback) {
                    sdk.bindDevice(raw, callback::onResult);
                }

                @Override
                public void destroy() {
                    session.detach(observer);
                }
            };
        };
        timer = (delay, action) -> {
            main.postDelayed(action, delay);
            return () -> main.removeCallbacks(action);
        };
        storage = new ShopBindingStore(app)::save;
        worker = action -> new Thread(action, "晨检登录保存").start();
        ui = main::post;
        checkThread = () -> {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("门店登录必须在主线程调用");
            }
        };
    }

    /**
     * 注入边界以验证迟到回执和页面生命周期，不需要启动真实服务或写业务云端。
     */
    ShopLoginCoordinator(ClientFactory factory, Timer timer, Storage storage,
                         Executor worker, Executor ui) {
        this.factory = Objects.requireNonNull(factory);
        this.timer = Objects.requireNonNull(timer);
        this.storage = Objects.requireNonNull(storage);
        this.worker = Objects.requireNonNull(worker);
        this.ui = Objects.requireNonNull(ui);
        this.checkThread = () -> {
        };
    }

    public void attach(Listener listener) {
        checkThread.run();
        listeners.add(Objects.requireNonNull(listener));
        listener.onState(state);
    }

    public void detach(Listener listener) {
        checkThread.run();
        listeners.remove(listener);
    }

    public State getState() {
        checkThread.run();
        return state;
    }

    /**
     * 空白码拒绝；有效内容原样传给 module，不解析链接、Token 或自行裁剪外部门店标识。
     */
    public void login(String raw, SdkConfig config) {
        checkThread.run();
        if (state.isRunning()) return;
        long attempt = state.attempt + 1;
        if (raw == null || raw.trim().isEmpty()) {
            publish(new State(Phase.ERROR, attempt, "门店二维码不能为空"));
            return;
        }
        if (config == null) {
            publish(new State(Phase.ERROR, attempt, "SDK 连接配置无效，请先检查设备设置"));
            return;
        }
        publish(new State(Phase.WAITING_CONNECTION, attempt, "正在连接晨检 SDK"));
        try {
            client = factory.create((connected, message) -> {
                if (connected) connected(attempt, raw, config);
            });
            cancelConnectionTimeout = timer.after(CONNECTION_TIMEOUT_MS, () -> {
                if (state.attempt == attempt && state.phase == Phase.WAITING_CONNECTION) {
                    finish(attempt, false, "晨检 SDK 连接超时，尚未提交门店绑定");
                }
            });
            client.connect(config);
        } catch (RuntimeException failure) {
            if (state.attempt == attempt && state.phase == Phase.WAITING_CONNECTION) {
                finish(attempt, false, "无法连接晨检 SDK，请检查连接配置和服务");
            }
        }
    }

    private void connected(long attempt, String raw, SdkConfig config) {
        if (state.attempt != attempt || state.phase != Phase.WAITING_CONNECTION) return;
        cancelTimer();
        // 绑定不以模型就绪或证书为前提；初始化排序交给 module，本次只调用一次。
        publish(new State(Phase.BINDING, attempt, "正在绑定门店，请稍候"));
        try {
            client.bind(raw, (code, message, data) -> received(attempt, raw, config, code, message, data));
        } catch (RuntimeException failure) {
            if (state.attempt == attempt && state.phase == Phase.BINDING) {
                finish(attempt, false, "绑定结果未知，请核对门店归属后再操作");
            }
        }
    }

    private void received(long attempt, String raw, SdkConfig config,
                          int code, String message, JSONObject data) {
        if (state.attempt != attempt || state.phase != Phase.BINDING) return;
        if (code != 0) {
            String reason = code == HuoyanClient.ERROR_RESULT_UNKNOWN
                    ? "绑定结果未知，请核对门店归属后再操作"
                    : "门店绑定失败：" + (message == null || message.trim().isEmpty() ? code : message);
            finish(attempt, false, reason);
            return;
        }
        JSONObject response = data == null ? null : data.optJSONObject("response");
        String accountId = nonEmptyString(response, "account_id");
        String shopId = nonEmptyString(response, "shop_id");
        if (accountId == null || shopId == null) {
            finish(attempt, false, "云端返回的门店归属不完整，请核对绑定结果");
            return;
        }
        Binding binding = new Binding(accountId, shopId, raw, config,
                displayString(response, "account_name"), displayString(response, "shop_name"),
                displayString(response, "shop_no"));
        publish(new State(Phase.SAVING, attempt, "绑定成功，正在保存门店归属"));
        try {
            worker.execute(() -> {
                boolean saved;
                try {
                    saved = storage.save(binding);
                } catch (RuntimeException failure) {
                    saved = false;
                }
                final boolean result = saved;
                ui.execute(() -> {
                    if (state.attempt == attempt && state.phase == Phase.SAVING) {
                        finish(attempt, result, result ? "门店登录成功"
                                : "门店归属保存失败，云端可能已绑定，请核对");
                    }
                });
            });
        } catch (RuntimeException failure) {
            finish(attempt, false, "门店归属保存失败，云端可能已绑定，请核对");
        }
    }

    private static String nonEmptyString(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null;
    }

    private static String displayString(JSONObject object, String key) {
        String value = nonEmptyString(object, key);
        return value == null ? "" : value;
    }

    public void clearResult() {
        checkThread.run();
        if (!state.isRunning()) publish(new State(Phase.IDLE, state.attempt, "请扫描门店二维码"));
    }

    private void finish(long attempt, boolean success, String message) {
        if (state.attempt != attempt || !state.isRunning()) return;
        cancelTimer();
        Client completed = client;
        client = null;
        State terminal = new State(success ? Phase.SUCCESS : Phase.ERROR, attempt, message);
        state = terminal;
        // 先结束本次所有权，再通知界面返回首页；释放连接不能影响新页面刚建立的会话。
        try {
            if (completed != null) completed.destroy();
        } catch (RuntimeException ignored) {
            // 资源释放异常不能把已经完成或已判未知的绑定变为可重发状态。
        }
        publish(terminal);
    }

    private void cancelTimer() {
        Runnable cancel = cancelConnectionTimeout;
        cancelConnectionTimeout = null;
        if (cancel != null) cancel.run();
    }

    private void publish(State next) {
        state = next;
        for (Listener listener : new ArrayList<>(listeners)) {
            // 监听回调可能导致 Activity 停止或移除另一个监听者。
            if (state != next) break;
            if (listeners.contains(listener)) listener.onState(next);
        }
    }
}
