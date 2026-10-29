package cc.huoyan.opensource.morningcheck.sdk;

import org.json.JSONObject;
import org.junit.Test;

import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;
import static org.junit.Assert.*;

public class MorningSdkStateTest {
    private JSONObject ready() {
        return obj("sdk_state", "READY", "model_ready", true, "certificate_valid", true, "key_lost", false);
    }

    @Test public void disconnectedNeverReusesOldReadySnapshot() {
        MorningSdkState state = MorningSdkState.from(false, ready(), null);
        assertEquals(MorningSdkState.Phase.DISCONNECTED, state.phase);
        assertFalse(state.ready);
    }

    @Test public void connectedWithoutSnapshotDoesNotRequestActivation() {
        MorningSdkState state = MorningSdkState.from(true, null, null);
        assertEquals(MorningSdkState.Phase.STATUS_UNKNOWN, state.phase);
        assertFalse(state.ready);
        assertFalse(state.needsActivation);
    }

    @Test public void successfulInitializationMayStillNeedActivation() {
        MorningSdkState state = MorningSdkState.from(true, obj("sdk_state", "NOT_ACTIVATED", "device_id", "device-1"), null);
        assertEquals(MorningSdkState.Phase.NOT_ACTIVATED, state.phase);
        assertTrue(state.needsActivation);
        assertFalse(state.ready);
        assertFalse(state.recovering);
    }

    @Test public void readyRequiresActualBooleanEvidence() {
        for (Object evidence : new Object[] {JSONObject.NULL, false, "true", 1}) {
            MorningSdkState state = MorningSdkState.from(true,
                    obj("sdk_state", "READY", "certificate_valid", evidence, "model_ready", true), null);
            assertFalse(state.ready);
            assertFalse("未知或无效证据不能猜成过期", state.needsActivation);
            assertEquals(MorningSdkState.Phase.STATUS_UNKNOWN, state.phase);
        }
        assertFalse(MorningSdkState.from(true, obj("sdk_state", "READY", "certificate_valid", true), null).ready);
        assertTrue(MorningSdkState.from(true, ready(), null).ready);
    }

    @Test public void offlineDoesNotDisableReadyLocalInference() {
        JSONObject snapshot = ready();
        snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("backend_connected", false));
        MorningSdkState state = MorningSdkState.from(true, snapshot, null);
        assertTrue(state.ready);
        assertTrue(state.detail.contains("本地晨检"));
        assertTrue(state.detail.contains("云端当前未连接"));
    }

    @Test public void offlineStateAllowsLocalInferenceWithoutRequestingActivationOrRecovery() {
        JSONObject snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(),
                obj("sdk_state", "OFFLINE", "backend_connected", false));
        MorningSdkState state = MorningSdkState.from(true, snapshot, null);
        assertEquals(MorningSdkState.Phase.OFFLINE, state.phase);
        assertTrue(state.ready);
        assertFalse(state.needsActivation);
        assertFalse(state.recovering);
        assertTrue(state.detail.contains("本地晨检"));
        assertTrue(state.detail.contains("云端功能暂不可用"));
        assertEquals(-1, state.downloadPercent);
        snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("busy", true, "busy_operation", "register"));
        assertEquals(MorningSdkState.Phase.BUSY, MorningSdkState.from(true, snapshot, null).phase);
        assertTrue(MorningSdkState.from(true, snapshot, null).ready);
    }

    @Test public void offlineStateCannotReplaceCertificateModelAndKeyEvidence() {
        JSONObject snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(), obj("sdk_state", "OFFLINE"));
        for (Object value : new Object[]{JSONObject.NULL, false, "true", 1}) {
            assertFalse(MorningSdkState.from(true,
                    cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("certificate_valid", value)), null).ready);
            assertFalse(MorningSdkState.from(true,
                    cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("model_ready", value)), null).ready);
        }
        MorningSdkState lost = MorningSdkState.from(true,
                cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("key_lost", true)), null);
        assertEquals(MorningSdkState.Phase.KEY_LOST, lost.phase);
        assertFalse(lost.ready);
        assertFalse(MorningSdkState.from(false, snapshot, null).ready);
    }

    @Test public void laterRestrictionOverridesPreviouslyUsableOfflineSnapshot() {
        JSONObject snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(), obj("sdk_state", "OFFLINE"));
        assertTrue(MorningSdkState.from(true, snapshot, null).ready);
        for (String name : new String[]{"CERTIFICATE_EXPIRED", "CERTIFICATE_INVALID", "MODEL_PREPARING",
                "MODEL_NOT_READY", "KEY_ROTATION_PENDING", "KEY_ROTATION_RESTORING", "DISABLED", "STOPPED", "UNKNOWN"}) {
            MorningSdkState state = MorningSdkState.from(true,
                    cc.huoyan.opensource.morningcheck.data.Jsons.patch(snapshot, obj("sdk_state", name)), null);
            assertFalse(name, state.ready);
        }
    }

    @Test public void temporaryBusyPreservesReadyAndIdentifiesOperation() {
        JSONObject snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(), obj("busy", true, "busy_operation", "register"));
        MorningSdkState state = MorningSdkState.from(true, snapshot, null);
        assertEquals(MorningSdkState.Phase.BUSY, state.phase);
        assertTrue(state.ready);
        assertTrue(state.detail.contains("登记人员"));
    }

    @Test public void keyLossAndRetirementOverrideOldReadyFacts() {
        JSONObject lost = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(), obj("key_lost", true));
        MorningSdkState state = MorningSdkState.from(true, lost, null);
        assertEquals(MorningSdkState.Phase.KEY_LOST, state.phase);
        assertFalse(state.ready);
        state = MorningSdkState.from(true, obj("sdk_state", "STOPPED", "key_lost", true), null);
        assertEquals(MorningSdkState.Phase.STOPPED, state.phase);
    }

    @Test public void rotationReasonIsNotFlattenedIntoKeyLoss() {
        MorningSdkState rejected = MorningSdkState.from(true, obj("sdk_state", "KEY_ROTATION_REJECTED", "key_lost", true), null);
        MorningSdkState limited = MorningSdkState.from(true, obj("sdk_state", "KEY_ROTATION_RATE_LIMITED", "key_lost", true), null);
        MorningSdkState pending = MorningSdkState.from(true, obj("sdk_state", "KEY_ROTATION_PENDING", "key_lost", true), null);
        assertEquals(MorningSdkState.Phase.KEY_ROTATION_REJECTED, rejected.phase);
        assertEquals(MorningSdkState.Phase.KEY_ROTATION_RATE_LIMITED, limited.phase);
        assertFalse(limited.detail.contains("秒"));
        assertFalse(rejected.recovering);
        assertTrue(pending.recovering);
        assertFalse(pending.ready);
    }

    @Test public void expiredCertificateExplainsLimitedFunctions() {
        MorningSdkState state = MorningSdkState.from(true, obj("sdk_state", "CERTIFICATE_EXPIRED", "model_ready", true), null);
        assertFalse(state.ready);
        assertTrue(state.needsActivation);
        assertTrue(state.detail.contains("人员维护与其他功能仍可使用"));
    }

    @Test public void completedDownloadDoesNotAnnounceReady() {
        MorningSdkState state = MorningSdkState.from(true, obj("sdk_state", "MODEL_PREPARING"), obj("downloaded", 100L, "total", 100L));
        assertEquals(100, state.downloadPercent);
        assertFalse(state.ready);
        assertTrue(state.recovering);
        assertTrue(state.detail.contains("正在完成加载"));
    }

    @Test public void downloadRejectsUnknownAndInconsistentTotals() {
        for (JSONObject download : new JSONObject[] {null, obj(), obj("received", 2, "total", 3),
                obj("downloaded", 2, "total", 0), obj("downloaded", -1, "total", 10),
                obj("downloaded", 11, "total", 10), obj("downloaded", "2", "total", 10),
                obj("downloaded", 2.5, "total", 10)}) {
            MorningSdkState state = MorningSdkState.from(true, obj("sdk_state", "MODEL_PREPARING"), download);
            assertEquals(-1, state.downloadPercent);
            assertFalse(state.detail.contains("%"));
        }
    }

    @Test public void progressNeverOverflowsOrRoundsUpToComplete() {
        MorningSdkState state = MorningSdkState.from(true, obj("sdk_state", "MODEL_PREPARING"),
                obj("downloaded", Long.MAX_VALUE - 1, "total", Long.MAX_VALUE));
        assertEquals(99, state.downloadPercent);
        assertEquals(-1, MorningSdkState.from(true, ready(), obj("downloaded", 50, "total", 100)).downloadPercent);
    }

    @Test public void configurationWarningIsExplicitAndPreservesReadyEvidence() {
        MorningSdkState state = MorningSdkState.from(true, ready(), null).withConfigurationPending("setBaseUrl");
        assertEquals(MorningSdkState.Phase.CONFIGURATION_PENDING, state.phase);
        assertTrue(state.ready);
        assertTrue(state.detail.contains("同一地址"));
        MorningSdkState lost = MorningSdkState.from(false, ready(), null).withConfigurationPending("setBaseUrl");
        assertEquals(MorningSdkState.Phase.DISCONNECTED, lost.phase);
    }

    @Test public void arbitraryRecentErrorCannotChangeMachineState() {
        JSONObject snapshot = cc.huoyan.opensource.morningcheck.data.Jsons.patch(ready(), obj("recent_error", "证书已到期，配置尚未确认落盘"));
        MorningSdkState state = MorningSdkState.from(true, snapshot, null);
        assertEquals(MorningSdkState.Phase.READY, state.phase);
        assertTrue(state.ready);
        assertFalse(state.needsActivation);
    }
}
