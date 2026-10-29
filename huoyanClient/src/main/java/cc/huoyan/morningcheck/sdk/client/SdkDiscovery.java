package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 参考零售客户端的局域网发现流程，只读验证晨检 WS 协议，不初始化或切换业务连接。
 */
public final class SdkDiscovery {
    public static final int DEFAULT_PORT = 18087;

    private SdkDiscovery() {
    }

    /**
     * 设计页只输入 IPv4；晨检壳使用 18087 根路径，不能沿用零售壳的端口。
     */
    public static String endpointForIp(String input) {
        String value = input == null ? "" : input.trim();
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
            throw new IllegalArgumentException("请输入有效的 IPv4 地址");
        StringBuilder normalized = new StringBuilder();
        for (String part : value.split("\\.")) {
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new IllegalArgumentException("请输入有效的 IPv4 地址");
            if (normalized.length() > 0) normalized.append('.');
            normalized.append(octet);
        }
        return "ws://" + normalized + ":" + DEFAULT_PORT + "/";
    }

    /**
     * 已存连接提取地址供编辑；不支持的主机名留空，不把错误配置当成本机。
     */
    public static String ipFromEndpoint(String endpoint) {
        try {
            return URI.create(endpointForIp(URI.create(endpoint).getHost())).getHost();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    /**
     * 只发 getStatus；WebSocket 握手成功或其他服务的消息均不足以确认晨检 SDK。
     */
    public static boolean probe(String endpoint) {
        OkHttpClient client = client();
        try {
            return probe(client, endpoint);
        } finally {
            close(client);
        }
    }

    private static OkHttpClient client() {
        return new OkHttpClient.Builder().connectTimeout(600, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build();
    }

    private static boolean probe(OkHttpClient client, String endpoint) {
        String id = UUID.randomUUID().toString();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean found = new AtomicBoolean();
        WebSocket socket = client.newWebSocket(new Request.Builder()
                .url(SdkConfig.webSocket(endpoint).getWsEndpoint()).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                try {
                    socket.send(MorningWsProtocol.text(id, "getStatus", null));
                } catch (Exception invalid) {
                    completed.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket socket, String message) {
                try {
                    JSONObject root = new JSONObject(message);
                    if (!id.equals(root.optString("requestId"))) return;
                    JSONObject data = root.optJSONObject("data");
                    found.set(root.opt("code") instanceof Number && root.optInt("code", -1) == 0
                            && data != null && data.opt("sdk_state") instanceof String
                            && !data.optString("sdk_state").isEmpty()
                            && data.opt("device_id") instanceof String
                            && !data.optString("device_id").isEmpty());
                    completed.countDown();
                } catch (Exception invalid) { /* 无关消息不能确认协议，继续等到超时。 */ }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error, Response response) {
                completed.countDown();
            }

            @Override
            public void onClosing(WebSocket socket, int code, String reason) {
                completed.countDown();
            }
        });
        try {
            completed.await(1500, TimeUnit.MILLISECONDS);
            return found.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            socket.cancel();
        }
    }

    /**
     * 在活动以太网/Wi-Fi 的 IPv4 网段探测；有并发和总时限，取消会释放所有套接字。
     */
    public static List<String> discover(String preferredIp) throws SocketException {
        Set<String> candidates = new LinkedHashSet<>();
        if (preferredIp != null && !preferredIp.trim().isEmpty())
            candidates.add(ipFromEndpoint(endpointForIp(preferredIp)));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()
                    || !(iface.getName().startsWith("wlan") || iface.getName().startsWith("eth")))
                continue;
            for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress())
                    continue;
                String ip = address.getHostAddress();
                String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                for (int host = 1; host < 255; host++) candidates.add(prefix + host);
            }
        }
        OkHttpClient client = client();
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CompletionService<String> results = new ExecutorCompletionService<>(pool);
        List<String> found = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            for (String ip : candidates)
                results.submit(() -> probe(client, endpointForIp(ip)) ? ip : null);
            for (int count = 0; count < candidates.size(); count++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<String> result = results.poll(remaining, TimeUnit.NANOSECONDS);
                if (result == null) break;
                try {
                    String ip = result.get();
                    if (ip != null) found.add(ip);
                } catch (
                        java.util.concurrent.ExecutionException failed) { /* 单个地址失败不影响其余地址。 */ }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
            close(client);
        }
        Collections.sort(found);
        return found;
    }

    private static void close(OkHttpClient client) {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
