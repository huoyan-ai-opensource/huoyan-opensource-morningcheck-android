package cc.huoyan.opensource.morningcheck.login;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;

import static org.junit.Assert.*;

public final class ShopLoginCoordinatorTest {
    private static final SdkConfig CONFIG = SdkConfig.webSocket("ws://192.168.1.2:18087/");

    @Test public void blankQrIsRejectedWithoutConnecting() {
        Fixture fixture = new Fixture();
        fixture.coordinator.login(" \n\t", CONFIG);
        assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
        assertTrue(fixture.clients.isEmpty());
        assertTrue(fixture.saved.isEmpty());
    }

    @Test public void qrIsPassedUnmodifiedAndRepeatedScansOrConnectionEventsNeverReplayBinding() {
        Fixture fixture = new Fixture();
        fixture.coordinator.login("  0007/erp?token=raw  ", CONFIG);
        fixture.coordinator.login("other", SdkConfig.aidl());
        FakeClient client = fixture.latest();
        assertEquals(CONFIG, client.config);
        client.connected();
        client.connected();
        fixture.coordinator.login("third", CONFIG);
        fixture.coordinator.clearResult();
        assertEquals(1, fixture.clients.size());
        assertEquals(1, client.bindCount);
        assertEquals("  0007/erp?token=raw  ", client.raw);
        assertEquals(ShopLoginCoordinator.Phase.BINDING, fixture.coordinator.getState().phase);
        assertTrue(fixture.timerCancelled);
    }

    @Test public void connectionTimeoutDestroysClientAndLateConnectionDoesNotSubmit() {
        Fixture fixture = new Fixture();
        fixture.coordinator.login("external", CONFIG);
        FakeClient client = fixture.latest();
        assertEquals(5_000, fixture.timeoutDelay);
        fixture.timeout.run();
        client.connected();
        assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
        assertEquals(0, client.bindCount);
        assertEquals(1, client.destroyCount);
        assertTrue(fixture.saved.isEmpty());
    }

    @Test public void unknownResultDoesNotSaveOrRetryAndAllowsOnlyExplicitNewAttempt() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loginAndConnect();
        FakeClient old = fixture.latest();
        old.result(HuoyanClient.ERROR_RESULT_UNKNOWN, "连接中断", validReceipt());
        old.connected();
        assertEquals(1, old.bindCount);
        assertEquals(1, old.destroyCount);
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.coordinator.getState().message.contains("结果未知"));
        fixture.coordinator.clearResult();
        assertEquals(ShopLoginCoordinator.Phase.IDLE, fixture.coordinator.getState().phase);
        fixture.coordinator.login("new", CONFIG);
        fixture.latest().connected();
        old.result(0, "", validReceipt());
        assertTrue(fixture.saved.isEmpty());
        assertEquals(2, fixture.coordinator.getState().attempt);
        assertEquals(ShopLoginCoordinator.Phase.BINDING, fixture.coordinator.getState().phase);
    }

    @Test public void failureCannotBeMaskedByCompleteResponse() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loginAndConnect();
        fixture.latest().result(-3, "设备尚未初始化", validReceipt());
        assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
        assertTrue(fixture.coordinator.getState().message.contains("设备尚未初始化"));
        assertTrue(fixture.saved.isEmpty());
    }

    @Test public void successRequiresActualNonemptyStringScopeAndNeverUsesRequestEcho() throws Exception {
        JSONObject[] malformed = {
                null,
                new JSONObject(),
                new JSONObject().put("request", new JSONObject().put("account_id", "a").put("shop_id", "s")),
                new JSONObject().put("response", new JSONObject().put("account_id", "a")),
                new JSONObject().put("response", new JSONObject().put("account_id", " ").put("shop_id", "s")),
                new JSONObject().put("response", new JSONObject().put("account_id", 2097500510197125120L).put("shop_id", "s")),
                new JSONObject().put("response", new JSONObject().put("account_id", "a").put("shop_id", JSONObject.NULL))
        };
        for (JSONObject response : malformed) {
            Fixture fixture = new Fixture();
            fixture.loginAndConnect();
            fixture.latest().result(0, "ok", response);
            assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
            assertTrue(fixture.saved.isEmpty());
            assertEquals(1, fixture.latest().destroyCount);
        }
    }

    @Test public void detachedScreenReceivesNoLateUiButBindingStillSavesAndNewScreenGetsFinalState() throws Exception {
        Fixture fixture = new Fixture();
        List<ShopLoginCoordinator.State> oldUi = new ArrayList<>();
        ShopLoginCoordinator.Listener oldListener = oldUi::add;
        fixture.coordinator.attach(oldListener);
        fixture.loginAndConnect();
        fixture.coordinator.detach(oldListener);
        int detachedSize = oldUi.size();
        fixture.latest().result(0, "ok", validReceipt());
        assertEquals(ShopLoginCoordinator.Phase.SAVING, fixture.coordinator.getState().phase);
        assertTrue(fixture.saved.isEmpty());
        assertEquals(0, fixture.latest().destroyCount);
        fixture.worker.runNext();
        assertEquals(1, fixture.saved.size());
        assertEquals(ShopLoginCoordinator.Phase.SAVING, fixture.coordinator.getState().phase);
        fixture.ui.runNext();
        assertEquals(detachedSize, oldUi.size());
        assertEquals(1, fixture.latest().destroyCount);
        assertEquals(ShopLoginCoordinator.Phase.SUCCESS, fixture.coordinator.getState().phase);
        List<ShopLoginCoordinator.State> newUi = new ArrayList<>();
        fixture.coordinator.attach(newUi::add);
        assertEquals(ShopLoginCoordinator.Phase.SUCCESS, newUi.get(0).phase);
        ShopLoginCoordinator.Binding saved = fixture.saved.get(0);
        assertEquals("internal-account", saved.accountId);
        assertEquals("internal-shop", saved.shopId);
        assertEquals("external", saved.externalShopId);
        assertSame(CONFIG, saved.config);
        assertEquals("云端门店", saved.shopName);
        assertEquals("S001", saved.shopNo);
    }

    @Test public void duplicateSuccessWhileSavingDoesNotScheduleAnotherCommit() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loginAndConnect();
        fixture.latest().result(0, "ok", validReceipt());
        fixture.latest().result(0, "ok", validReceipt());
        fixture.coordinator.login("second", CONFIG);
        fixture.timeout.run();
        assertEquals(1, fixture.worker.tasks.size());
        assertEquals(1, fixture.clients.size());
        assertEquals(ShopLoginCoordinator.Phase.SAVING, fixture.coordinator.getState().phase);
        fixture.worker.runNext();
        fixture.ui.runNext();
        assertEquals(1, fixture.saved.size());
    }

    @Test public void failedCommitNeverReportsSuccessOrReturnsHome() throws Exception {
        Fixture fixture = new Fixture();
        fixture.saveResult = false;
        fixture.loginAndConnect();
        fixture.latest().result(0, "ok", validReceipt());
        fixture.worker.runNext();
        fixture.ui.runNext();
        assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
        assertTrue(fixture.coordinator.getState().message.contains("云端可能已绑定"));
        assertEquals(1, fixture.latest().destroyCount);
    }

    @Test public void clientIsDestroyedBeforeSuccessListenerCanStartAnotherLogin() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loginAndConnect();
        FakeClient first = fixture.latest();
        fixture.coordinator.attach(state -> {
            if (state.phase == ShopLoginCoordinator.Phase.SUCCESS) {
                assertEquals(1, first.destroyCount);
                fixture.coordinator.login("next", CONFIG);
            }
        });
        first.result(0, "ok", validReceipt());
        fixture.worker.runNext();
        fixture.ui.runNext();
        assertEquals(2, fixture.clients.size());
        assertEquals(2, fixture.coordinator.getState().attempt);
        assertEquals(ShopLoginCoordinator.Phase.WAITING_CONNECTION, fixture.coordinator.getState().phase);
        assertEquals(0, fixture.latest().destroyCount);
    }

    @Test public void throwingStorageStillFinishesAsUncertainErrorAndDestroysClient() throws Exception {
        Fixture fixture = new Fixture();
        fixture.throwOnSave = true;
        fixture.loginAndConnect();
        fixture.latest().result(0, "ok", validReceipt());
        fixture.worker.runNext();
        fixture.ui.runNext();
        assertEquals(ShopLoginCoordinator.Phase.ERROR, fixture.coordinator.getState().phase);
        assertEquals(1, fixture.latest().destroyCount);
    }

    @Test public void scopeMatchesOnlyTheSameConnectionConfiguration() throws Exception {
        JSONObject stored = new JSONObject().put("endpoint", CONFIG.getWsEndpoint()).put("channel", "WS");
        assertTrue(ShopBindingStore.matchesConnection(stored, CONFIG));
        assertFalse(ShopBindingStore.matchesConnection(stored, SdkConfig.automatic(CONFIG.getWsEndpoint())));
        assertFalse(ShopBindingStore.matchesConnection(stored, SdkConfig.webSocket("ws://192.168.1.3:18087/")));
        assertFalse(ShopBindingStore.matchesConnection(null, CONFIG));
    }

    private static JSONObject validReceipt() throws Exception {
        return new JSONObject()
                .put("response", new JSONObject().put("account_id", "internal-account").put("shop_id", "internal-shop")
                        .put("shop_name", "云端门店").put("shop_no", "S001"))
                .put("request", new JSONObject().put("account_id", "request-account").put("shop_id", "external"));
    }

    private static final class QueueExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.remove(0).run(); }
    }

    private static final class FakeClient implements ShopLoginCoordinator.Client {
        final ShopLoginCoordinator.ConnectionListener listener;
        ShopLoginCoordinator.ResultListener callback;
        SdkConfig config;
        String raw;
        int bindCount;
        int destroyCount;
        FakeClient(ShopLoginCoordinator.ConnectionListener listener) { this.listener = listener; }
        @Override public void connect(SdkConfig config) { this.config = config; }
        @Override public void bind(String raw, ShopLoginCoordinator.ResultListener callback) {
            this.raw = raw;
            this.callback = callback;
            bindCount++;
        }
        @Override public void destroy() { destroyCount++; }
        void connected() { listener.onConnection(true, "已连接"); }
        void result(int code, String message, JSONObject data) { callback.onResult(code, message, data); }
    }

    private static final class Fixture {
        final List<FakeClient> clients = new ArrayList<>();
        final List<ShopLoginCoordinator.Binding> saved = new ArrayList<>();
        final QueueExecutor worker = new QueueExecutor();
        final QueueExecutor ui = new QueueExecutor();
        boolean saveResult = true;
        boolean throwOnSave;
        boolean timerCancelled;
        long timeoutDelay;
        Runnable timeout;
        final ShopLoginCoordinator coordinator = new ShopLoginCoordinator(listener -> {
            FakeClient client = new FakeClient(listener);
            clients.add(client);
            return client;
        }, (delay, action) -> {
            timeoutDelay = delay;
            timeout = action;
            return () -> timerCancelled = true;
        }, binding -> {
            if (throwOnSave) throw new IllegalStateException("磁盘写入失败");
            saved.add(binding);
            return saveResult;
        }, worker, ui);
        FakeClient latest() { return clients.get(clients.size() - 1); }
        void loginAndConnect() {
            coordinator.login("external", CONFIG);
            latest().connected();
        }
    }
}
