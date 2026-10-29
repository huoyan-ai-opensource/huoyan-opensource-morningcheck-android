package cc.huoyan.morningcheck.sdk.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.json.JSONObject;

/**
 * 本机 WS 使用绑定租约拉起壳；远程 WS 完全不接触本机 SDK 服务。
 */
final class LocalWsTransport implements ClientTransport {
    private final Context context;
    private final WsSdkClient client;
    private boolean bound;
    private boolean wanted;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        // API 21–26 壳的 AIDL 不可用，但绑定租约仍使服务存活，实际命令仅走 WS。
        @Override
        public void onNullBinding(ComponentName name) {
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (LocalWsTransport.this) {
                releaseBinding();
                if (wanted) bindLocal();
            }
        }
    };

    LocalWsTransport(Context context, HuoyanClient.Listener listener) {
        this.context = context;
        this.client = new WsSdkClient(ClientAppVersion.read(context), listener);
    }

    @Override
    public void setInitializationListener(Runnable listener) {
        client.setInitializationListener(listener);
    }

    @Override
    public synchronized void connect(String endpoint) {
        wanted = SdkConfig.isLoopback(endpoint);
        if (wanted) bindLocal();
        else releaseBinding();
        client.connect(endpoint);
    }

    private void bindLocal() {
        if (bound) return;
        Intent intent = new Intent(SdkConfig.SDK_BIND_ACTION)
                .setComponent(new ComponentName(SdkConfig.SDK_PACKAGE, SdkConfig.SDK_SERVICE));
        // Android 可能先登记 ServiceConnection 再返回 false，失败也要配对 unbind。
        bound = true;
        try {
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
                releaseBinding();
        } catch (SecurityException | IllegalArgumentException unavailable) {
            // WS 通道自行报告连接结果；设备也可能由系统策略常驻壳进程。
            releaseBinding();
        }
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException alreadyUnbound) { /* 系统已回收绑定。 */ }
    }

    @Override
    public String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
        return client.request(command, params, image, callback);
    }

    @Override
    public void cancel(String id) {
        client.cancel(id);
    }

    @Override
    public JSONObject getStatus() {
        return client.getStatus();
    }

    @Override
    public JSONObject getCapabilities() {
        return client.getCapabilities();
    }

    @Override
    public JSONObject getClassTable() {
        return client.getClassTable();
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public boolean isReady() {
        return client.isReady();
    }

    @Override
    public synchronized void close() {
        wanted = false;
        releaseBinding();
        client.close();
    }

    @Override
    public synchronized void destroy() {
        wanted = false;
        releaseBinding();
        client.destroy();
    }
}
