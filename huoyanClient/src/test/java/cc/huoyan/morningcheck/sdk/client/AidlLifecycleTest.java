package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** 验证取消/超时与实际提交、回调跨连接交付之间的确定性边界。 */
public class AidlLifecycleTest {
    @Test public void explicitInitializationNeverRetriesBusyNotReadyOrUnknownBehindTheHost() {
        for (int code : new int[]{HuoyanClient.ERROR_BUSY, -3, HuoyanClient.ERROR_RESULT_UNKNOWN, 0}) {
            assertFalse(AidlSdkClient.shouldRetryInitialization(false, code, 0, false));
        }
    }

    @Test public void connectionInitializationKeepsBoundedBusyRetryAndStopsOnUnknownOrCancellation() {
        assertTrue(AidlSdkClient.shouldRetryInitialization(true, HuoyanClient.ERROR_BUSY, 0, false));
        assertTrue(AidlSdkClient.shouldRetryInitialization(true, HuoyanClient.ERROR_BUSY, 5, false));
        assertFalse(AidlSdkClient.shouldRetryInitialization(true, HuoyanClient.ERROR_BUSY, 6, false));
        assertFalse(AidlSdkClient.shouldRetryInitialization(true, HuoyanClient.ERROR_BUSY, 0, true));
        assertFalse(AidlSdkClient.shouldRetryInitialization(true, HuoyanClient.ERROR_RESULT_UNKNOWN, 0, false));
        assertFalse(AidlSdkClient.shouldRetryInitialization(true, -3, 0, false));
    }

    @Test public void timedOutQueueCannotLaterSend() {
        AidlCallState state = new AidlCallState();
        assertTrue(state.finish());
        assertFalse(state.begin());
        assertFalse(state.hasStarted());
        assertFalse(state.finish());
    }

    @Test public void sentTimeoutKeepsUnknownOutcomeAndOnlyOneCompletion() {
        AidlCallState state = new AidlCallState();
        assertTrue(state.begin());
        assertFalse(state.begin());
        assertTrue(state.finish());
        assertTrue(state.hasStarted());
        assertFalse(state.finish());
    }

    @Test public void reconnectBeforeMainDeliveryCompletesOnceAsUnknown() {
        List<Runnable> queued = new ArrayList<>();
        List<Integer> results = new ArrayList<>();
        AtomicLong generation = new AtomicLong(1);
        AidlResultDelivery.post(queued::add, generation::get, () -> false,
                (code, reason, data) -> results.add(code), 0, "成功", new JSONObject());
        generation.incrementAndGet();
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(HuoyanClient.ERROR_RESULT_UNKNOWN), results.get(0));
    }

    @Test public void destroyDropsPendingUiDelivery() {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean destroyed = new AtomicBoolean();
        List<Integer> results = new ArrayList<>();
        AidlResultDelivery.post(queued::add, () -> 1, destroyed::get,
                (code, reason, data) -> results.add(code), 0, "成功", new JSONObject());
        destroyed.set(true);
        queued.get(0).run();
        assertTrue(results.isEmpty());
    }

    @Test public void currentGenerationPreservesServiceError() {
        List<Integer> results = new ArrayList<>();
        AidlResultDelivery.post(Runnable::run, () -> 1, () -> false,
                (code, reason, data) -> results.add(code), -110, "等待审批", new JSONObject());
        assertEquals(Integer.valueOf(-110), results.get(0));
    }

    @Test public void personIdNeverRoundsOrSaturatesFloatingPointInput() throws Exception {
        AidlSdkClient.validateParams("forget", new JSONObject().put("person_id", Long.MAX_VALUE));
        for (Object invalid : new Object[]{Math.scalb(1.0, 63), 1.5, "123", 0, -1L}) {
            try {
                AidlSdkClient.validateParams("forget", new JSONObject().put("person_id", invalid));
                fail("不应允许精度损失/溢出后删除其他人员");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
