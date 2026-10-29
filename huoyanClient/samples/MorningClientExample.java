package sample;

import android.content.Context;
import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/** 仅依赖分发 JAR 的最小宿主示例；不包含相机或晨检 App 业务。 */
public final class MorningClientExample implements AutoCloseable {
    private final HuoyanClient client;

    public MorningClientExample(Context context, HuoyanClient.Listener listener) {
        client = new HuoyanClient(context, listener);
    }

    public void connectLocal() { client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT)); }
    public void connectRemote(String endpoint) { client.connect(SdkConfig.webSocket(endpoint)); }

    /** RGB 数组在回调前保持不变，未就绪时由业务层展示初始化或授权状态。 */
    public String identify(byte[] rgb, int width, int height, HuoyanClient.Callback callback) {
        return client.identify(rgb, width, height, callback);
    }

    /** 关闭等待不等于撤销已提交的 SDK 操作。 */
    public void stop() { client.close(); }
    @Override public void close() { client.destroy(); }
}
