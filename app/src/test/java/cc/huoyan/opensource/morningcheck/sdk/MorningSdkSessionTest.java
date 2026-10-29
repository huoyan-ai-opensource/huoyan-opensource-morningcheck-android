package cc.huoyan.opensource.morningcheck.sdk;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;

import static org.junit.Assert.*;

/** 验证页面订阅、状态查询及写操作的所有权，不依赖 Android 主线程或真实云端。 */
public class MorningSdkSessionTest {
    private static final SdkConfig FIRST = SdkConfig.webSocket("ws://127.0.0.1:18087/");
    private static final SdkConfig SECOND = SdkConfig.webSocket("ws://127.0.0.1:18088/");

    @Test public void certificatePageImmediatelyReadsAndSharesOneFiveSecondReservation() {
        Fixture f = new Fixture();
        f.port.immediate = true;
        MorningSdkSession.Listener first = certificateObserver(), second = certificateObserver();
        f.session.attach(first); f.session.connect(FIRST);
        int reads = f.port.count("getCertInfo");
        f.timer.advance(4_999);
        assertEquals(reads, f.port.count("getCertInfo"));
        f.timer.advance(1);
        assertEquals(reads + 1, f.port.count("getCertInfo"));
        f.session.attach(second);
        reads = f.port.count("getCertInfo");
        int statuses = f.port.count("getStatus"), progresses = f.port.count("getDownloadProgress");
        f.timer.advance(10_000);
        assertEquals(reads + 2, f.port.count("getCertInfo"));
        assertEquals(statuses + 2, f.port.count("getStatus"));
        assertEquals(progresses + 2, f.port.count("getDownloadProgress"));
        f.session.detach(first);
        reads = f.port.count("getCertInfo");
        f.timer.advance(5_000);
        assertEquals(reads + 1, f.port.count("getCertInfo"));
        f.session.detach(second);
        assertFalse(f.timer.hasActive());
        reads = f.port.count("getCertInfo");
        f.timer.advance(20_000);
        assertEquals(reads, f.port.count("getCertInfo"));
        assertEquals(0, f.port.closes);
    }

    @Test public void leavingCertificatePageRestoresStatusPollingAndReturningReadsImmediately() {
        Fixture f = new Fixture(); f.port.immediate = true;
        MorningSdkSession.Listener home = observer(), license = certificateObserver();
        f.session.attach(home); f.session.connect(FIRST);
        f.session.attach(license);
        int reads = f.port.count("getCertInfo"), statuses = f.port.count("getStatus");
        f.timer.advance(1_500);
        assertEquals(statuses, f.port.count("getStatus"));
        f.session.detach(license);
        f.timer.advance(1_499);
        assertEquals(statuses, f.port.count("getStatus"));
        f.timer.advance(1);
        assertEquals(statuses + 1, f.port.count("getStatus"));
        f.timer.advance(6_000);
        assertEquals(reads, f.port.count("getCertInfo"));
        f.session.attach(license);
        assertEquals(reads + 1, f.port.count("getCertInfo"));
    }

    @Test public void slowCertificateDetailsMergeAcrossFiveSecondCyclesAndRetryAfterFailure() {
        Fixture f = new Fixture(); f.session.attach(certificateObserver()); f.session.connect(FIRST);
        f.timer.advance(20_000);
        for (String command : new String[]{"getStatus", "getCertInfo", "getDownloadProgress"})
            assertEquals(command, 1, f.port.count(command));
        f.port.complete("getCertInfo", -3, new JSONObject());
        f.timer.advance(5_000);
        assertEquals(2, f.port.count("getCertInfo"));
        assertEquals(1, f.port.count("getStatus"));
        assertEquals(1, f.port.count("getDownloadProgress"));
        f.port.complete("getCertInfo", 0, json("activation_serial", "SAMPLE-ONLY"));
        assertEquals("SAMPLE-ONLY", f.session.certificate().optString("activation_serial"));
    }

    @Test public void certificateObservationPausesForActivationAndResumesWithoutRepeatingWrite() {
        Fixture f = new Fixture(); f.session.attach(certificateObserver()); f.session.connect(FIRST);
        Call oldCertificate = f.port.last("getCertInfo");
        int certificateReads = f.port.count("getCertInfo");
        f.session.activate("SAMPLE-ONLY", "morningcheck");
        oldCertificate.reply(0, json("activation_serial", "OLD-SAMPLE"));
        f.timer.advance(10_000);
        assertEquals(certificateReads, f.port.count("getCertInfo"));
        assertFalse(f.session.certificate().has("activation_serial"));
        f.port.complete("activate", 0, new JSONObject());
        f.port.last("getStatus").reply(0, ready());
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertEquals(certificateReads + 1, f.port.count("getCertInfo"));
        f.port.last("getCertInfo").reply(0, json("activation_serial", "SAMPLE-ONLY"));
        f.timer.advance(4_999);
        assertEquals(certificateReads + 1, f.port.count("getCertInfo"));
        f.timer.advance(1);
        assertEquals(certificateReads + 2, f.port.count("getCertInfo"));
        assertEquals(1, f.port.count("activate"));
        assertEquals(0, f.port.count("init"));
    }

    @Test public void pagesShareOneConnectionAndLastDetachStopsPollingOnly() {
        Fixture f = new Fixture();
        MorningSdkSession.Listener home = observer(), staff = observer();
        f.session.attach(home);
        f.session.connect(FIRST);
        f.session.attach(staff);
        f.session.detach(home);
        f.session.connect(FIRST);
        f.session.refreshDetails();
        f.session.onStatus(json("sdk_state", "READY", "model_version", "测试版本",
                "certificate_valid_until", "2099-12-31"));
        assertEquals(1, f.port.connects);
        assertEquals(0, f.port.closes);
        assertEquals("普通连接、页面订阅、状态更新和详情刷新不能查询暂停的云端托管状态",
                0, f.port.count("getCloudStatus"));
        f.session.detach(staff);
        assertFalse(f.timer.hasActive());
        assertEquals(0, f.port.closes);
    }

    @Test public void pollingObservesOfflineReadinessAndOnlineRecoveryWithoutReconnectingOrReinitializing() {
        Fixture f = new Fixture();
        f.session.attach(observer());
        f.session.connect(FIRST);
        f.port.complete("getStatus", 0, json("sdk_state", "MODEL_PREPARING", "model_ready", false,
                "certificate_valid", true, "key_lost", false));
        assertFalse(f.session.viewState().ready);
        assertTrue(f.session.viewState().recovering);
        f.timer.advance(1_500);
        f.port.complete("getStatus", 0, json("sdk_state", "OFFLINE", "model_ready", true,
                "certificate_valid", true, "key_lost", false));
        assertEquals(MorningSdkState.Phase.OFFLINE, f.session.viewState().phase);
        assertTrue(f.session.viewState().ready);
        f.timer.advance(1_500);
        f.port.complete("getStatus", 0, ready());
        assertEquals(MorningSdkState.Phase.READY, f.session.viewState().phase);
        assertTrue(f.session.viewState().ready);
        assertEquals(1, f.port.connects);
        assertEquals(0, f.port.count("init"));
        assertEquals(0, f.port.closes);
    }

    @Test public void slowStatusReadDoesNotPileUpPollingRequests() {
        Fixture f = new Fixture();
        f.session.attach(observer());
        f.session.connect(FIRST);
        f.timer.fire(); f.timer.fire(); f.timer.fire();
        assertEquals(1, f.port.count("getStatus"));
        assertEquals(1, f.port.count("getDownloadProgress"));
        f.port.complete("getStatus", 0, json("sdk_state", "NOT_ACTIVATED"));
        f.timer.fire();
        assertEquals(2, f.port.count("getStatus"));
        assertEquals("前台预约只查询现有 SDK 状态，不查询云端托管", 0, f.port.count("getCloudStatus"));
    }

    @Test public void explicitExitAllowsSameConfigurationToStartANewClientConnection() {
        Fixture f = new Fixture();
        MorningSdkSession.Listener home = observer();
        f.session.attach(home);
        f.session.connect(FIRST);
        long previous = f.session.generation();
        f.session.disconnect();
        f.session.detach(home);
        assertEquals(1, f.port.closes);
        assertFalse(f.timer.hasActive());

        f.session.attach(observer());
        f.session.connect(FIRST);
        assertEquals("退出后进程仍在，重开也必须重新连接并触发客户端初始化", 2, f.port.connects);
        assertTrue(f.session.generation() > previous);
        f.session.connect(FIRST);
        assertEquals("后续普通页面继续共享这一条新连接", 2, f.port.connects);
    }

    @Test public void oldConnectionDetailsNeverOverwriteNewDevice() {
        Fixture f = new Fixture();
        f.session.attach(observer());
        f.session.connect(FIRST);
        assertEquals(0, f.port.count("getCloudStatus"));
        // 云端托管暂停自动读取，但显式读接口仍保留兼容性及切机迟到回执保护。
        f.session.read("getCloudStatus", null);
        Call old = f.port.last("getCloudStatus");
        long generation = f.session.generation();
        f.session.connect(SECOND);
        assertTrue(f.session.generation() > generation);
        old.reply(0, json("enabled", true));
        assertFalse(f.session.cloudStatus().has("enabled"));
        assertEquals("切换设备不能隐式恢复云端托管查询", 1, f.port.count("getCloudStatus"));
        f.session.read("getCloudStatus", null);
        f.port.last("getCloudStatus").reply(0, json("enabled", false));
        assertEquals(Boolean.FALSE, f.session.cloudStatus().opt("enabled"));
        assertEquals(2, f.port.count("getCloudStatus"));
    }

    @Test
    public void activationReceiptImmediatelyCompletesWithoutDownloadOrReadyVerification() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(-1), callbacks = new AtomicInteger();
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicReference<JSONObject> receipt = new AtomicReference<>();
        assertTrue(f.session.activate("fixture", "morningcheck", (code, message, data) -> {
            result.set(code);
            reason.set(message);
            receipt.set(data);
            callbacks.incrementAndGet();
            assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        }));
        long id = f.session.activation().id;
        assertEquals(MorningSdkSession.ActivationPhase.ACTIVATING, f.session.activation().phase);
        assertTrue(f.session.operation().running);
        assertEquals(0, f.port.count("getDownloadProgress"));
        assertEquals(0, f.port.count("getStatus"));
        assertEquals(-1, result.get());
        f.port.complete("activate", 0, json("receipt", "accepted"));
        assertEquals(id, f.session.activation().id);
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertFalse(f.session.activation().running);
        assertFalse(f.session.operation().running);
        assertEquals(0, result.get());
        assertEquals("测试回执", reason.get());
        assertEquals("accepted", receipt.get().optString("receipt"));
        assertEquals(1, callbacks.get());
        assertFalse("成功回执不能冒充模型已就绪", f.session.viewState().ready);
        assertFalse(f.timer.hasActive());
        assertEquals(1, f.port.count("activate"));
        assertEquals(0, f.port.count("init"));
    }

    @Test
    public void explicitActivationFailureImmediatelyPreservesActualReceipt() {
        for (int failure : new int[]{-109, HuoyanClient.ERROR_RESULT_UNKNOWN}) {
            Fixture f = new Fixture();
            f.session.connect(FIRST);
            AtomicInteger result = new AtomicInteger(), callbacks = new AtomicInteger();
            AtomicReference<String> reason = new AtomicReference<>();
            assertTrue(f.session.activate("fixture", "morningcheck", (code, message, data) -> {
                result.set(code);
                reason.set(message);
                assertEquals("actual", data.optString("receipt"));
                callbacks.incrementAndGet();
            }));
            f.port.complete("activate", failure, json("receipt", "actual"));
            long id = f.session.activation().id;
            assertEquals(MorningSdkSession.ActivationPhase.FAILED, f.session.activation().phase);
            assertEquals(failure, f.session.activation().code);
            assertEquals(failure, result.get());
            assertEquals("测试回执", reason.get());
            assertFalse(f.session.operation().running);
            f.timer.advance(200_000);
            assertEquals(1, callbacks.get());
            assertEquals(1, f.port.count("activate"));
            assertEquals(0, f.port.count("init"));
            assertFalse(f.timer.hasActive());
            assertTrue(f.session.activate("another-fixture", "another-model"));
            assertTrue(f.session.activation().id > id);
        }
    }

    @Test
    public void postActivationReadFailuresAndTerminalStatesCannotRewriteSuccessfulReceipt() {
        for (int readCode : new int[]{HuoyanClient.ERROR_BUSY, -3, -101, 0}) {
            Fixture f = new Fixture();
            f.session.attach(certificateObserver());
            f.session.connect(FIRST);
            AtomicInteger callbacks = new AtomicInteger();
            f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
                assertEquals(0, code);
                callbacks.incrementAndGet();
            });
            f.port.complete("activate", 0, new JSONObject());
            assertEquals(1, callbacks.get());
            assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
            // 激活结束只读取真实状态；证书失效或查询失败不能反写先前的激活回执。
            f.port.last("getStatus").reply(readCode, json("sdk_state", "CERTIFICATE_INVALID",
                    "model_ready", false, "certificate_valid", false));
            f.port.last("getCertInfo").reply(-101, new JSONObject());
            f.port.last("getDownloadProgress").reply(0, json("downloaded", 0, "total", 0));
            assertFalse(f.session.viewState().ready);
            assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
            assertEquals(0, f.session.activation().code);
            assertEquals(1, callbacks.get());
            assertEquals(1, f.port.count("activate"));
            assertEquals(0, f.port.count("init"));
        }
    }

    @Test
    public void readyStateRefreshCannotRewriteFailedActivationReceipt() {
        Fixture f = new Fixture();
        f.session.attach(certificateObserver());
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(), callbacks = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
            result.set(code);
            callbacks.incrementAndGet();
        });
        f.port.complete("activate", -109, new JSONObject());
        f.port.last("getStatus").reply(0, ready());
        assertTrue("真实状态可以独立恢复识别入口", f.session.viewState().ready);
        assertEquals(MorningSdkSession.ActivationPhase.FAILED, f.session.activation().phase);
        assertEquals(-109, f.session.activation().code);
        assertEquals(-109, result.get());
        assertEquals(1, callbacks.get());
    }

    @Test
    public void cachedReadyAndLatePreActivationReadsCannotCompleteOrRevertActivation() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.onStatus(ready());
        f.session.read("getDownloadProgress", null);
        Call oldProgress = f.port.last("getDownloadProgress");
        AtomicInteger oldReadResult = new AtomicInteger(-1);
        f.session.read("getCertInfo", (code, message, data) -> oldReadResult.set(code));
        Call oldCertificate = f.port.last("getCertInfo");
        f.session.activate("fixture", "morningcheck");
        oldProgress.reply(0, json("downloaded", 100, "total", 100));
        oldCertificate.reply(0, json("valid", true));
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, oldReadResult.get());
        assertEquals(-1, f.session.activation().percent);
        assertEquals(0, f.session.download().length());
        assertFalse(f.session.viewState().ready);
        f.session.onStatus(ready());
        assertTrue(f.session.activation().running);
        assertFalse(f.session.viewState().ready);
        f.port.complete("activate", 0, new JSONObject());
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertFalse(f.session.viewState().ready);
        f.session.read("getStatus", null);
        f.port.last("getStatus").reply(0, ready());
        assertTrue(f.session.viewState().ready);
        assertEquals(0, f.port.count("init"));
    }

    @Test
    public void waitingForActivationReceiptDoesNotScheduleDownloadOrReadyRequests() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.activate("fixture", "morningcheck");
        f.timer.advance(100_000);
        assertTrue(f.session.activation().running);
        assertEquals(MorningSdkSession.ActivationPhase.ACTIVATING, f.session.activation().phase);
        assertEquals(0, f.port.count("getDownloadProgress"));
        assertEquals(0, f.port.count("getStatus"));
        assertEquals(0, f.port.count("init"));
        f.port.complete("activate", 0, new JSONObject());
        f.timer.advance(200_000);
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertEquals(1, f.port.count("activate"));
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void manualStatusQueriesResumeAsIndependentReadsImmediatelyAfterReceipt() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.activate("fixture", "morningcheck");
        AtomicInteger result = new AtomicInteger(-1);
        f.session.read("getStatus", (code, reason, data) -> result.set(code));
        assertEquals(HuoyanClient.ERROR_BUSY, result.get());
        assertEquals(0, f.port.count("getStatus"));
        f.port.complete("activate", 0, new JSONObject());
        f.session.read("getStatus", (code, reason, data) -> result.set(code));
        assertEquals(1, f.port.count("getStatus"));
        f.port.last("getStatus").reply(-101, new JSONObject());
        assertEquals(-101, result.get());
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
    }

    @Test
    public void failedOrUnknownActivationIgnoresRawReadyUntilNewStatusReadSucceeds() {
        for (int code : new int[]{-109, HuoyanClient.ERROR_RESULT_UNKNOWN}) {
            Fixture f = new Fixture();
            f.session.connect(FIRST);
            f.session.onStatus(ready());
            assertTrue(f.session.viewState().ready);
            f.session.activate("fixture", "morningcheck");
            f.session.onStatus(ready());
            assertEquals("激活期间无请求代次的旧状态必须隔离", 0, f.session.status().length());
            f.port.complete("activate", code, new JSONObject());
            f.session.onStatus(ready());
            assertFalse(f.session.viewState().ready);
            f.session.read("getStatus", null);
            f.port.complete("getStatus", -3, new JSONObject());
            f.session.onStatus(ready());
            assertFalse("查询失败不能解除旧广播隔离", f.session.viewState().ready);
            f.session.read("getStatus", null);
            f.port.complete("getStatus", 0, ready());
            assertTrue("本次失败不妨碍独立查询确认 SDK 可用", f.session.viewState().ready);
            assertEquals(MorningSdkSession.ActivationPhase.FAILED, f.session.activation().phase);
        }
    }

    @Test
    public void preActivationStatusReadIsInvalidatedWhenWriteBegins() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.onStatus(ready());
        AtomicInteger result = new AtomicInteger(-1);
        f.session.read("getStatus", (code, message, data) -> result.set(code));
        Call old = f.port.last("getStatus");
        f.session.activate("fixture", "morningcheck");
        f.port.complete("activate", -109, new JSONObject());
        f.session.onStatus(ready());
        old.reply(0, ready());
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        assertFalse(f.session.viewState().ready);
        assertEquals(0, f.session.status().length());
        assertEquals(1, f.port.count("activate"));
    }

    @Test
    public void ordinaryOldQueryCallbackCannotOverwriteNewerRawStatusAfterIsolationEnds() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.activate("fixture", "morningcheck");
        f.port.complete("activate", 0, new JSONObject());
        f.session.read("getStatus", null);
        f.port.complete("getStatus", 0, ready());
        f.session.read("getStatus", (code, message, data) -> { });
        Call old = f.port.last("getStatus");
        f.session.onStatus(ready());
        old.reply(0, json("sdk_state", "NOT_ACTIVATED", "model_ready", false));
        assertTrue("已通过传输层顺序保护的新事实不能被旧回调撤回", f.session.viewState().ready);
        f.session.read("getStatus", (code, message, data) -> { });
        Call oldReady = f.port.last("getStatus");
        f.session.onStatus(json("sdk_state", "NOT_ACTIVATED", "model_ready", false));
        oldReady.reply(0, ready());
        assertFalse("旧成功查询不能重新开放新状态已禁用的晨检", f.session.viewState().ready);
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
    }

    @Test
    public void activationSurvivesPageLeavingAndNewPageNeverResubmits() {
        Fixture f = new Fixture();
        MorningSdkSession.Listener page = observer();
        f.session.attach(page);
        f.session.connect(FIRST);
        f.session.activate("fixture", "morningcheck");
        long id = f.session.activation().id;
        AtomicInteger duplicate = new AtomicInteger();
        assertFalse(f.session.execute("init", new JSONObject(), "初始化", (code, reason, data) -> duplicate.set(code)));
        assertEquals(HuoyanClient.ERROR_BUSY, duplicate.get());
        assertFalse(f.session.activate("second-fixture", "another-model", (code, reason, data) -> duplicate.set(code)));
        assertEquals(HuoyanClient.ERROR_BUSY, duplicate.get());
        assertEquals(id, f.session.activation().id);
        int progressReads = f.port.count("getDownloadProgress");
        f.session.detach(page);
        f.timer.advance(10_000);
        assertTrue("离页后仅保留写回执截止预约", f.timer.hasActive());
        assertEquals(progressReads, f.port.count("getDownloadProgress"));
        MorningSdkSession.Listener restored = observer();
        f.session.attach(restored);
        assertEquals(1, f.port.count("activate"));
        f.session.detach(restored);
        f.port.complete("activate", 0, new JSONObject());
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertFalse(f.timer.hasActive());
        assertEquals(0, f.port.closes);
    }

    @Test
    public void switchingDeviceAbortsPendingActivationAndIgnoresLateReceipt() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(-1), callbacks = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
            result.set(code);
            callbacks.incrementAndGet();
        });
        Call activate = f.port.last("activate");
        f.session.connect(SECOND);
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        assertEquals(MorningSdkSession.ActivationPhase.FAILED, f.session.activation().phase);
        activate.reply(0, new JSONObject());
        f.timer.advance(200_000);
        assertEquals(1, callbacks.get());
        assertEquals(1, f.port.count("activate"));
        assertEquals(0, f.port.count("init"));
        assertFalse(f.session.viewState().ready);
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void disconnectBeforeReceiptCompletesUnknownOnlyOnce() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(), callbacks = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
            result.set(code);
            callbacks.incrementAndGet();
        });
        Call activate = f.port.last("activate");
        f.session.disconnect();
        activate.reply(0, new JSONObject());
        f.timer.advance(200_000);
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        assertEquals(1, callbacks.get());
        assertEquals(1, f.port.count("activate"));
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void disconnectAfterReceiptCannotRewriteSuccessOrAcceptOldDeviceRead() {
        Fixture f = new Fixture();
        f.session.attach(certificateObserver());
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(-1), callbacks = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
            result.set(code);
            callbacks.incrementAndGet();
        });
        f.port.complete("activate", 0, new JSONObject());
        Call oldStatus = f.port.last("getStatus");
        f.session.disconnect();
        oldStatus.reply(0, ready());
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertEquals(0, result.get());
        assertEquals(1, callbacks.get());
        assertFalse(f.session.viewState().ready);
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void receiptTimeoutNeverReplaysWriteOrWaitsForReady() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(), callbacks = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> {
            result.set(code);
            callbacks.incrementAndGet();
        });
        Call activate = f.port.last("activate");
        f.timer.advance(149_999);
        assertTrue(f.session.activation().running);
        f.timer.advance(1);
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        activate.reply(0, new JSONObject());
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, f.session.activation().code);
        assertEquals(1, callbacks.get());
        assertEquals(1, f.port.count("activate"));
        assertEquals(0, f.port.count("getStatus"));
        assertEquals(0, f.port.count("getDownloadProgress"));
        assertEquals(0, f.port.count("init"));
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void synchronousActivationReceiptCompletesWithoutAdditionalRequests() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.port.immediate = true;
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue(f.session.activate("fixture", "morningcheck", (code, reason, data) -> callbacks.incrementAndGet()));
        assertEquals("activate", f.port.calls.get(0).name);
        assertEquals("fixture", f.port.calls.get(0).params.optString("activation_code"));
        assertEquals("morningcheck", f.port.calls.get(0).params.optString("model_code"));
        assertEquals(MorningSdkSession.ActivationPhase.SUCCEEDED, f.session.activation().phase);
        assertEquals(1, callbacks.get());
        assertEquals(1, f.port.calls.size());
        assertFalse(f.timer.hasActive());
    }

    @Test
    public void listenerDisconnectDuringActivationNotificationCannotSendToAnotherDevice() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.attach(new MorningSdkSession.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) { }
            @Override public void onSdkDetailsChanged() {
                if (f.session.activation().running) f.session.disconnect();
            }
        });
        AtomicInteger result = new AtomicInteger();
        f.session.activate("fixture", "morningcheck", (code, reason, data) -> result.set(code));
        assertEquals(0, f.port.count("activate"));
        assertEquals(MorningSdkSession.ActivationPhase.FAILED, f.session.activation().phase);
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        assertEquals(0, f.port.count("init"));
    }

    @Test public void managementWriteSurvivesPageLeavingAndDuplicateIsRejected() {
        Fixture f = new Fixture();
        MorningSdkSession.Listener page = observer();
        f.session.attach(page); f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(-1), duplicate = new AtomicInteger(-1);
        f.session.execute("setCloudStatus", json("enabled", true), "开启云服务",
                (code, message, data) -> result.set(code));
        assertFalse(f.session.execute("init", new JSONObject(), "初始化",
                (code, message, data) -> duplicate.set(code)));
        assertEquals(HuoyanClient.ERROR_BUSY, duplicate.get());
        f.session.detach(page);
        assertFalse(f.timer.hasActive());
        f.port.complete("setCloudStatus", 0, new JSONObject());
        assertEquals(0, result.get());
        assertEquals(0, f.port.closes);
        assertFalse(f.timer.hasActive());
        assertFalse("没有真实读回不能乐观显示已启用", f.session.cloudStatus().has("enabled"));
    }

    @Test public void cloudWriteInvalidatesEarlierReadsAndAlwaysConfirmsActualSwitch() {
        Fixture f = new Fixture();
        f.session.attach(observer()); f.session.connect(FIRST);
        f.session.read("getCloudStatus", null);
        Call before = f.port.last("getCloudStatus");
        f.session.execute("setCloudStatus", json("enabled", true), "开启云服务", null);
        f.port.complete("setCloudStatus", 0, new JSONObject());
        assertEquals(2, f.port.count("getCloudStatus"));
        Call after = f.port.last("getCloudStatus");
        after.reply(0, json("enabled", true));
        before.reply(0, json("enabled", false));
        assertEquals(Boolean.TRUE, f.session.cloudStatus().opt("enabled"));
        f.session.execute("setCloudStatus", json("enabled", false), "关闭云服务", null);
        f.port.complete("setCloudStatus", 40009, new JSONObject());
        assertEquals(3, f.port.count("getCloudStatus"));
        assertFalse(f.session.cloudStatus().has("enabled"));
        f.port.last("getCloudStatus").reply(0, json("enabled", true));
        assertEquals(Boolean.TRUE, f.session.cloudStatus().opt("enabled"));
    }

    @Test public void switchingDeviceMakesOldWriteUnknownWithoutReplay() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        AtomicInteger result = new AtomicInteger(-1);
        f.session.execute("setBaseUrl", json("url", "https://example.invalid"), "设置 SDK 云地址",
                (code, message, data) -> result.set(code));
        f.session.connect(SECOND);
        f.port.complete("setBaseUrl", 0, new JSONObject());
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, result.get());
        assertEquals(1, f.port.count("setBaseUrl"));
    }

    @Test public void disconnectedManagementDoesNotSendAndExplicitDisconnectClearsFacts() {
        Fixture f = new Fixture();
        AtomicInteger result = new AtomicInteger();
        assertFalse(f.session.execute("activate", json("activation_code", "fixture", "model_code", "morningcheck"), "激活",
                (code, message, data) -> result.set(code)));
        assertEquals(HuoyanClient.ERROR_NOT_CONNECTED, result.get());
        assertEquals(0, f.port.count("activate"));
        f.session.connect(FIRST);
        f.session.onStatus(json("sdk_state", "READY", "model_ready", true, "certificate_valid", true));
        f.session.disconnect();
        assertFalse(f.session.viewState().ready);
        assertEquals(0, f.session.status().length());
    }

    @Test public void listenerCanDetachItselfDuringImmediateConnectionWithoutMoreCallbacks() {
        Fixture f = new Fixture();
        AtomicInteger updates = new AtomicInteger();
        f.session.attach(new MorningSdkSession.Listener() {
            @Override public void onConnection(boolean connected, String message) { f.session.detach(this); }
            @Override public void onStatus(JSONObject status) { updates.incrementAndGet(); }
            @Override public void onSdkDetailsChanged() { updates.incrementAndGet(); }
        });
        assertEquals(0, updates.get());
        assertFalse(f.timer.hasActive());
    }

    @Test public void observerRemovedDuringBroadcastDoesNotReceiveOldDeviceStatus() {
        Fixture f = new Fixture();
        AtomicInteger updates = new AtomicInteger();
        MorningSdkSession.Listener second = new MorningSdkSession.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) { updates.incrementAndGet(); }
        };
        f.session.attach(new MorningSdkSession.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) { if (status.has("sdk_state")) f.session.detach(second); }
        });
        f.session.attach(second);
        updates.set(0);
        f.session.onStatus(json("sdk_state", "NOT_ACTIVATED"));
        assertEquals(0, updates.get());
    }

    @Test public void failedConnectionDoesNotRememberInvalidConfigOrKeepOldReadyFacts() {
        Fixture f = new Fixture();
        f.session.connect(FIRST);
        f.session.onStatus(json("sdk_state", "READY", "model_ready", true, "certificate_valid", true));
        f.port.rejectConnect = true;
        try { f.session.connect(SECOND); fail("应拒绝配置"); }
        catch (IllegalArgumentException expected) { }
        assertFalse(f.session.viewState().ready);
        assertFalse(f.session.matchesConfiguration(SECOND));
        f.port.rejectConnect = false;
        f.session.connect(SECOND);
        assertEquals(3, f.port.connects);
        assertTrue(f.session.matchesConfiguration(SECOND));
    }

    private static MorningSdkSession.Listener observer() {
        return new MorningSdkSession.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) { }
        };
    }
    private static MorningSdkSession.Listener certificateObserver() {
        return new MorningSdkSession.Listener() {
            @Override public boolean observesCertificateDetails() { return true; }
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) { }
        };
    }
    private static JSONObject json(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
        return result;
    }
    private static JSONObject ready() {
        return json("sdk_state", "READY", "model_ready", true, "certificate_valid", true, "key_lost", false);
    }
    private static final class Fixture {
        final FakeTimer timer = new FakeTimer();
        final FakePort port = new FakePort();
        final MorningSdkSession session = new MorningSdkSession(listener -> {
            port.listener = listener; return port;
        }, timer);
    }
    private static final class FakePort implements MorningSdkSession.Port {
        HuoyanClient.Listener listener;
        int connects, closes;
        boolean connected;
        boolean rejectConnect;
        boolean immediate;
        final List<Call> calls = new ArrayList<>();
        @Override public void connect(SdkConfig config) {
            connects++;
            if (rejectConnect) throw new IllegalArgumentException("连接配置不可用");
            connected = true; listener.onConnection(true, "已连接");
        }
        @Override public void close() { closes++; connected = false; listener.onConnection(false, "已断开"); }
        @Override public boolean isConnected() { return connected; }
        @Override public HuoyanClient client() { return null; }
        @Override public String request(String name, JSONObject params, HuoyanClient.Callback callback) {
            Call call = new Call(name, params, callback); calls.add(call);
            if (immediate) call.reply(0, "getStatus".equals(name) ? ready() : new JSONObject());
            return String.valueOf(calls.size());
        }
        int count(String name) { int n = 0; for (Call c : calls) if (c.name.equals(name)) n++; return n; }
        Call last(String name) {
            for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).name.equals(name)) return calls.get(i);
            throw new AssertionError(name);
        }
        void complete(String name, int code, JSONObject data) {
            for (Call c : calls) if (c.name.equals(name) && !c.completed) {
                if ("getStatus".equals(name) && code == 0) listener.onStatus(data);
                c.reply(code, data); return;
            }
            throw new AssertionError(name);
        }
    }
    private static final class Call {
        final String name;
        final HuoyanClient.Callback callback;
        final JSONObject params;
        boolean completed;
        Call(String name, JSONObject params, HuoyanClient.Callback callback) {
            this.name = name; this.params = params; this.callback = callback;
        }
        void reply(int code, JSONObject data) { completed = true; callback.onResult(code, "测试回执", data); }
    }
    private static final class FakeTimer implements MorningSdkSession.Timer {
        final List<Task> tasks = new ArrayList<>();
        long now;
        @Override public Runnable after(long delay, Runnable action) {
            Task task = new Task(now + delay, action); tasks.add(task); return () -> task.cancelled = true;
        }
        boolean hasActive() { for (Task t : tasks) if (!t.cancelled) return true; return false; }
        void fire() {
            Task task = earliest();
            if (task == null) throw new AssertionError("没有待执行预约");
            now = Math.max(now, task.due);
            task.cancelled = true; task.action.run();
        }
        void advance(long millis) {
            long target = now + millis;
            Task task;
            while ((task = earliest()) != null && task.due <= target) fire();
            now = target;
        }
        private Task earliest() {
            Task first = null;
            for (Task task : tasks) if (!task.cancelled && (first == null || task.due < first.due)) first = task;
            return first;
        }
    }
    private static final class Task {
        final long due;
        final Runnable action; boolean cancelled;
        Task(long due, Runnable action) { this.due = due; this.action = action; }
    }
}
