package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;

/**
 * 模块内部的通道边界，宿主只持有 HuoyanClient。
 */
interface ClientTransport extends AutoCloseable {
    /**
     * 仅表示本连接显式 init 及有界重试已结束，不表示模型或证书可用。
     */
    void setInitializationListener(Runnable listener);

    void connect(String endpoint);

    String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback);

    void cancel(String requestId);

    @Override
    void close();

    void destroy();

    JSONObject getStatus();

    JSONObject getCapabilities();

    JSONObject getClassTable();

    boolean isConnected();

    boolean isReady();
}
