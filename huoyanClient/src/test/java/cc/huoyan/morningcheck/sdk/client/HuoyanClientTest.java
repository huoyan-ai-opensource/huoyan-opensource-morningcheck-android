package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 用可控回调队列验证跨通道切换，不需要真实壳或云端。 */
public class HuoyanClientTest {
    @Test public void switchingFailsOldWriteExactlyOnceAndDoesNotReplay() {
        List<FakeTransport> transports = new ArrayList<>();
        HuoyanClient client = client(transports, Runnable::run, null);
        client.connect(SdkConfig.aidl());
        List<Integer> codes = new ArrayList<>();
        client.request("register", null, new byte[3], (code, message, data) -> codes.add(code));
        client.connect(SdkConfig.webSocket("ws://192.168.1.99:18087/"));
        assertTrue(transports.get(0).destroyed);
        assertEquals(0, transports.get(1).calls);
        assertEquals(List.of(HuoyanClient.ERROR_RESULT_UNKNOWN), codes);
        transports.get(0).reply(0);
        assertEquals(1, codes.size());
        assertEquals(SdkConfig.Channel.WS, client.getActiveChannel());
        client.destroy();
    }

    @Test public void queuedOldSuccessCannotUpdateNewMachine() {
        List<FakeTransport> transports = new ArrayList<>();
        List<Runnable> deliveries = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        HuoyanClient client = client(transports, deliveries::add, null);
        client.connect(SdkConfig.aidl());
        client.request("forget", null, null, (code, message, data) -> codes.add(code));
        transports.get(0).reply(0);
        client.connect(SdkConfig.webSocket("ws://192.168.1.99:18087/"));
        drain(deliveries);
        assertEquals(List.of(HuoyanClient.ERROR_RESULT_UNKNOWN), codes);
        client.destroy();
    }

    @Test public void configurationReuseCloseReopenAndDestroyHaveSeparateLifetimes() {
        List<FakeTransport> transports = new ArrayList<>();
        HuoyanClient client = client(transports, Runnable::run, null);
        client.connect(SdkConfig.aidl());
        client.connect(SdkConfig.aidl());
        assertEquals(1, transports.size());
        client.close();
        assertFalse(client.isConnected());
        assertNull(client.getActiveChannel());
        assertTrue(transports.get(0).destroyed);
        client.connect(SdkConfig.aidl());
        assertEquals(2, transports.size());
        client.destroy();
        assertTrue(transports.get(1).destroyed);
        assertThrows(IllegalStateException.class, () -> client.connect(SdkConfig.aidl()));
    }

    @Test public void cancelledRequestTargetsOriginalTransportAndDeliversOnce() {
        List<FakeTransport> transports = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        HuoyanClient client = client(transports, Runnable::run, null);
        client.connect(SdkConfig.aidl());
        String id = client.request("identify", null, null, (code, message, data) -> codes.add(code));
        client.cancel(id);
        assertEquals("transport-request", transports.get(0).cancelled);
        transports.get(0).reply(HuoyanClient.ERROR_CANCELLED);
        client.close();
        transports.get(0).reply(0);
        assertEquals(List.of(HuoyanClient.ERROR_CANCELLED), codes);
        client.destroy();
    }

    @Test public void destroyDropsQueuedResultsAndLateStatus() {
        List<FakeTransport> transports = new ArrayList<>();
        List<Runnable> deliveries = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        List<Boolean> states = new ArrayList<>();
        HuoyanClient client = client(transports, deliveries::add, new HuoyanClient.Listener() {
            @Override public void onConnection(boolean connected, String message) { states.add(connected); }
            @Override public void onStatus(JSONObject status) { fail("销毁后不得交付状态"); }
        });
        client.connect(SdkConfig.aidl());
        client.request("userCount", null, null, (code, message, data) -> codes.add(code));
        transports.get(0).reply(0);
        transports.get(0).listener.onConnection(true, "已连接");
        client.destroy();
        transports.get(0).listener.onStatus(new JSONObject());
        drain(deliveries);
        assertTrue(codes.isEmpty());
        assertTrue(states.isEmpty());
    }

    private static HuoyanClient client(List<FakeTransport> transports, java.util.concurrent.Executor deliveries,
                                       HuoyanClient.Listener listener) {
        return new HuoyanClient(30, listener, deliveries, (channel, observer) -> {
            FakeTransport transport = new FakeTransport(observer);
            transports.add(transport);
            return transport;
        }, (delay, action) -> { throw new AssertionError("本用例不应等待初始化"); });
    }

    private static void drain(List<Runnable> deliveries) {
        while (!deliveries.isEmpty()) deliveries.remove(0).run();
    }

    private static final class FakeTransport implements ClientTransport {
        final HuoyanClient.Listener listener;
        HuoyanClient.Callback callback;
        String cancelled;
        int calls;
        boolean destroyed;
        FakeTransport(HuoyanClient.Listener listener) { this.listener = listener; }
        @Override public void setInitializationListener(Runnable listener) { }
        void reply(int code) { callback.onResult(code, "测试回执", new JSONObject()); }
        @Override public void connect(String endpoint) { }
        @Override public String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
            calls++;
            this.callback = callback;
            return "transport-request";
        }
        @Override public void cancel(String id) { cancelled = id; }
        @Override public void close() { }
        @Override public void destroy() { destroyed = true; }
        @Override public JSONObject getStatus() { return new JSONObject(); }
        @Override public JSONObject getCapabilities() { return new JSONObject(); }
        @Override public JSONObject getClassTable() { return new JSONObject(); }
        @Override public boolean isConnected() { return !destroyed; }
        @Override public boolean isReady() { return false; }
    }
}
