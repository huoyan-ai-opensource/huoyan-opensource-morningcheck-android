package cc.huoyan.opensource.morningcheck.sdk;

import android.content.SharedPreferences;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/**
 * App 只持久化连接选项；通道选择、通信及重连由 huoyanClient 模块负责。
 */
public final class MorningSdkSettings {
    public static final String DEFAULT_WS_ENDPOINT = "ws://127.0.0.1:18087/";
    public static final String TRANSPORT_KEY = "sdk_transport";
    public static final String ENDPOINT_KEY = "sdk_url";

    private MorningSdkSettings() {
    }

    public static SdkConfig read(SharedPreferences prefs) {
        return config(prefs.getString(TRANSPORT_KEY, "auto"),
                prefs.getString(ENDPOINT_KEY, DEFAULT_WS_ENDPOINT));
    }

    public static SdkConfig config(String transport, String endpoint) {
        if ("aidl".equals(transport)) return SdkConfig.aidl();
        if ("ws".equals(transport)) return SdkConfig.webSocket(endpoint);
        // 旧版只保存地址，升级后本机自动使用 AIDL，远端地址继续使用 WS。
        return SdkConfig.automatic(endpoint);
    }

    public static int transportIndex(SharedPreferences prefs) {
        String transport = prefs.getString(TRANSPORT_KEY, "auto");
        return "aidl".equals(transport) ? 1 : "ws".equals(transport) ? 2 : 0;
    }

    public static String transportValue(int index) {
        return index == 1 ? "aidl" : index == 2 ? "ws" : "auto";
    }
}
