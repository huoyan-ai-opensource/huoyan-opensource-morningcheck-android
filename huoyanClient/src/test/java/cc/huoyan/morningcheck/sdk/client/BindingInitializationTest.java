package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 两种通道共用同一待绑定门禁；不依赖真实设备或云端。 */
public class BindingInitializationTest {
    @Test public void bothChannelsWaitForInitializationThenSendOnlyOnceWithoutReadyRequirement() {
        for (SdkConfig config : new SdkConfig[]{SdkConfig.aidl(), SdkConfig.webSocket("ws://192.168.1.2:18087/")}) {
            Fixture fixture = new Fixture(config);
            fixture.client.bindDevice("0007", fixture::result);
            assertEquals(0, fixture.latest().calls);
            assertEquals(130_000, fixture.timers.get(0).delay);
            assertFalse(fixture.client.isReady());
            fixture.latest().initialized.run();
            fixture.latest().initialized.run();
            assertEquals(1, fixture.latest().calls);
            assertEquals("bindDevice", fixture.latest().command);
            assertEquals("0007", fixture.latest().params.optString("shop_id"));
            assertTrue(fixture.timers.get(0).cancelled);
            // 已被调度器取出的旧等待超时，不能结束已经进入绑定阶段的请求。
            fixture.timers.get(0).action.run();
            assertTrue(fixture.codes.isEmpty());
            fixture.latest().reply(-3);
            assertEquals(List.of(-3), fixture.codes);
            fixture.client.destroy();
        }
    }

    @Test public void parametersAreSnapshottedWhileWaitingAndLaterBindingBypassesTheWait() throws Exception {
        Fixture fixture = new Fixture(SdkConfig.aidl());
        JSONObject params = ShopBindingRequest.create("0007");
        fixture.client.request("bindDevice", params, null, fixture::result);
        params.put("shop_id", "0008");
        fixture.latest().initialized.run();
        assertEquals("0007", fixture.latest().params.getString("shop_id"));
        fixture.latest().reply(0);
        fixture.client.bindDevice("0009", fixture::result);
        assertEquals(2, fixture.latest().calls);
        assertEquals(1, fixture.timers.size());
        fixture.client.destroy();
    }

    @Test public void cancellingBeforeInitializationNeverReachesEitherTransport() {
        Fixture fixture = new Fixture(SdkConfig.aidl());
        String id = fixture.client.bindDevice("0007", fixture::result);
        fixture.client.cancel(id);
        fixture.latest().initialized.run();
        fixture.timers.get(0).action.run();
        assertEquals(0, fixture.latest().calls);
        assertEquals(0, fixture.latest().cancels);
        assertEquals(List.of(HuoyanClient.ERROR_CANCELLED), fixture.codes);
        assertTrue(fixture.messages.get(0).contains("尚未发送"));
        fixture.client.destroy();
    }

    @Test public void waitingTimeoutPreventsLateInitializationFromSending() {
        Fixture fixture = new Fixture(SdkConfig.aidl());
        fixture.client.bindDevice("0007", fixture::result);
        fixture.timers.get(0).action.run();
        fixture.latest().initialized.run();
        assertEquals(0, fixture.latest().calls);
        assertEquals(List.of(HuoyanClient.ERROR_NOT_CONNECTED), fixture.codes);
        assertTrue(fixture.messages.get(0).contains("尚未发送"));
        fixture.client.destroy();
    }

    @Test public void disconnectEndsWaitingRequestAndReconnectDoesNotReplayIt() {
        Fixture fixture = new Fixture(SdkConfig.aidl());
        fixture.client.bindDevice("0007", fixture::result);
        FakeTransport transport = fixture.latest();
        transport.connected = false;
        transport.listener.onConnection(false, "断开");
        transport.connected = true;
        transport.listener.onConnection(true, "重连");
        transport.initialized.run();
        assertEquals(0, transport.calls);
        assertEquals(List.of(HuoyanClient.ERROR_NOT_CONNECTED), fixture.codes);
        fixture.client.destroy();
    }

    @Test public void switchingRejectsOldUnsentRequestAndOldInitCannotReleaseNewConnection() {
        Fixture fixture = new Fixture(SdkConfig.aidl());
        fixture.client.bindDevice("0007", fixture::result);
        FakeTransport old = fixture.latest();
        fixture.client.connect(SdkConfig.webSocket("ws://192.168.1.2:18087/"));
        fixture.client.bindDevice("0008", fixture::result);
        old.initialized.run();
        assertEquals(0, old.calls);
        assertEquals(0, fixture.latest().calls);
        assertEquals(List.of(HuoyanClient.ERROR_NOT_CONNECTED), fixture.codes);
        fixture.latest().initialized.run();
        assertEquals(1, fixture.latest().calls);
        fixture.client.destroy();
    }

    @Test public void queuedUnsentCancellationKeepsItsMeaningAfterConnectionChanges() {
        List<Runnable> deliveries = new ArrayList<>();
        Fixture fixture = new Fixture(SdkConfig.aidl(), deliveries::add);
        String id = fixture.client.bindDevice("0007", fixture::result);
        fixture.client.cancel(id);
        fixture.client.connect(SdkConfig.webSocket("ws://192.168.1.2:18087/"));
        while (!deliveries.isEmpty()) deliveries.remove(0).run();
        assertEquals(List.of(HuoyanClient.ERROR_CANCELLED), fixture.codes);
        fixture.client.destroy();
    }

    private static final class Timer {
        final long delay;
        final Runnable action;
        boolean cancelled;
        Timer(long delay, Runnable action) { this.delay = delay; this.action = action; }
    }

    private static final class Fixture {
        final List<FakeTransport> transports = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final List<Integer> codes = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final HuoyanClient client;
        Fixture(SdkConfig config) { this(config, Runnable::run); }
        Fixture(SdkConfig config, java.util.concurrent.Executor deliveries) {
            client = new HuoyanClient(30, null, deliveries, (channel, listener) -> {
                FakeTransport transport = new FakeTransport(listener);
                transports.add(transport);
                return transport;
            }, (delay, action) -> {
                Timer timer = new Timer(delay, action);
                timers.add(timer);
                return () -> timer.cancelled = true;
            });
            client.connect(config);
        }
        FakeTransport latest() { return transports.get(transports.size() - 1); }
        void result(int code, String message, JSONObject data) { codes.add(code); messages.add(message); }
    }

    private static final class FakeTransport implements ClientTransport {
        final HuoyanClient.Listener listener;
        Runnable initialized;
        boolean connected = true;
        int calls;
        int cancels;
        String command;
        JSONObject params;
        HuoyanClient.Callback callback;
        FakeTransport(HuoyanClient.Listener listener) { this.listener = listener; }
        @Override public void setInitializationListener(Runnable listener) { initialized = listener; }
        @Override public void connect(String endpoint) { }
        @Override public String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
            this.command = command; this.params = params; this.callback = callback;
            return "request-" + ++calls;
        }
        void reply(int code) { callback.onResult(code, "壳真实回执", new JSONObject()); }
        @Override public void cancel(String requestId) { cancels++; }
        @Override public void close() { connected = false; }
        @Override public void destroy() { connected = false; }
        @Override public JSONObject getStatus() { return new JSONObject(); }
        @Override public JSONObject getCapabilities() { return new JSONObject(); }
        @Override public JSONObject getClassTable() { return new JSONObject(); }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isReady() { return false; }
    }
}
