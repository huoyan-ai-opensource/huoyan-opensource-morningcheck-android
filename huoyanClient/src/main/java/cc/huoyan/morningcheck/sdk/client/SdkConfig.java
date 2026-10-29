package cc.huoyan.morningcheck.sdk.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * 晨检壳的连接配置。自动模式只按地址与系统版本选择通道，不因请求失败切换通道。
 */
public final class SdkConfig {
    public static final String DEFAULT_WS_ENDPOINT = "ws://127.0.0.1:18087/";
    public static final String SDK_PACKAGE = "cc.huoyan.morningcheck";
    public static final String SDK_SERVICE = "cc.huoyan.morningcheck.app.comm.SdkCommService";
    public static final String SDK_BIND_ACTION = "cc.huoyan.morningcheck.SdkCommService";

    public enum Channel {AUTO, AIDL, WS}

    private final Channel channel;
    private final String wsEndpoint;

    private SdkConfig(Channel channel, String endpoint) {
        this.channel = channel;
        String address = endpoint == null ? "" : endpoint.trim();
        try {
            URI uri = new URI(address);
            if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("SDK 地址须为有效的 ws:// 或 wss:// 地址");
            }
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("SDK 地址格式无效", invalid);
        }
        this.wsEndpoint = address;
    }

    /**
     * 同机 Android 8.1 及以上采用 AIDL，其余采用指定 WebSocket 地址。
     */
    public static SdkConfig automatic(String wsEndpoint) {
        return new SdkConfig(Channel.AUTO, wsEndpoint);
    }

    /**
     * 显式连接本机晨检壳，要求 Android 8.1 / API 27 及以上。
     */
    public static SdkConfig aidl() {
        return new SdkConfig(Channel.AIDL, DEFAULT_WS_ENDPOINT);
    }

    /**
     * 显式采用 WebSocket，可连接本机或局域网晨检壳。
     */
    public static SdkConfig webSocket(String endpoint) {
        return new SdkConfig(Channel.WS, endpoint);
    }

    public Channel getChannel() {
        return channel;
    }

    public String getWsEndpoint() {
        return wsEndpoint;
    }

    /**
     * 根据系统版本解析自动模式；显式 AIDL 在旧系统上报错，不静默改变用户选择。
     */
    public Channel getResolvedChannel(int apiLevel) {
        if (channel == Channel.AIDL && apiLevel < 27) {
            throw new IllegalArgumentException("本机 AIDL 通道需要 Android 8.1 及以上，请选择自动或 WebSocket");
        }
        if (channel != Channel.AUTO) return channel;
        // 仅回环地址代表本机；不能把局域网目标误绑到本机另一个 SDK。
        return apiLevel >= 27 && isLoopback(wsEndpoint) ? Channel.AIDL : Channel.WS;
    }

    static boolean isLoopback(String endpoint) {
        String host = URI.create(endpoint).getHost().toLowerCase(Locale.ROOT);
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SdkConfig)) return false;
        SdkConfig config = (SdkConfig) other;
        return channel == config.channel && wsEndpoint.equals(config.wsEndpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel, wsEndpoint);
    }
}
