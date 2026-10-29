package cc.huoyan.opensource.morningcheck.sdk;

import java.net.URI;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/**
 * 安装提示只针对本机服务；配置错误仍交给连接入口解释，不干扰远端 WS。
 */
public final class SdkInstallationPolicy {
    private SdkInstallationPolicy() {
    }

    public static boolean requiresLocalShell(SdkConfig config, int apiLevel) {
        if (config.getResolvedChannel(apiLevel) == SdkConfig.Channel.AIDL) return true;
        String host = URI.create(config.getWsEndpoint()).getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
