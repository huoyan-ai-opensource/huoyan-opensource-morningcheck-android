package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;

public final class ModelSelectionSessionTest {
    @Test
    public void firstActivationWaitsForExplicitSelectionAndPreservesSerialFormat() throws Exception {
        Fixture data = new Fixture();
        data.session.open(false);
        assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
        assertEquals(0, data.queries);
        data.session.input(" SERIAL-A ");
        data.session.input("SERIAL-B");
        data.session.open(true);
        assertEquals(1, data.queries);
        assertEquals("SERIAL-A", data.queried);
        assertFalse(data.queriedDevice);
        data.queryReply.onResult(0, "", models());
        assertEquals(ModelSelectionSession.Stage.CHOOSING, data.session.stage);
        assertEquals(0, data.activations);
        assertEquals(0, data.certificates);
        assertTrue(data.session.models.get(0).current);
        assertEquals("同名模型", data.session.models.get(1).name);
        assertFalse(data.session.select(-1));
        assertFalse(data.session.select(2));
        assertTrue(data.session.select(1));
        assertFalse(data.session.select(1));
        assertEquals(ModelSelectionSession.Stage.ACTIVATING, data.session.stage);
        assertTrue(data.session.submitted);
        assertEquals(1, data.activations);
        assertEquals("SERIAL-A", data.code);
        assertEquals("model-b", data.model);
        data.activationReply.onResult(0, "", new JSONObject());
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(0, data.session.resultCode);
        assertEquals("设备激活成功", data.session.resultMessage);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertEquals("", data.session.resultMessage);
    }

    @Test
    public void firstActivationMaySelectModelMarkedCurrent() throws Exception {
        Fixture data = new Fixture();
        data.choose(false);
        assertTrue(data.session.select(0));
        assertEquals(1, data.activations);
        assertEquals("model-a", data.model);
    }

    @Test
    public void switchingReadsCurrentAuthorizationAndItsCertificateBeforeChoosing() throws Exception {
        Fixture data = new Fixture();
        data.session.open(true);
        assertTrue(data.session.switching);
        assertNull(data.queried);
        data.queryReply.onResult(0, "", models());
        assertEquals(ModelSelectionSession.Stage.QUERYING, data.session.stage);
        assertEquals(1, data.certificates);
        assertFalse(data.session.select(1));
        data.certificateReply.onResult(0, "", certificate(" DEVICE-LICENSE "));
        assertEquals(ModelSelectionSession.Stage.CHOOSING, data.session.stage);
        assertTrue(data.session.select(1));
        assertEquals("DEVICE-LICENSE", data.code);
        assertEquals("model-b", data.model);
        data.activationReply.onResult(0, "", new JSONObject());
        assertEquals("模型切换成功", data.session.resultMessage);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
    }

    @Test
    public void switchingCurrentModelAndCancellationNeverSubmit() throws Exception {
        Fixture data = new Fixture();
        data.choose(true);
        assertTrue(data.session.select(0));
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertFalse(data.session.submitted);
        data.choose(true);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertEquals(0, data.activations);
    }

    @Test
    public void cancellingFirstSelectionReturnsToInputWithoutWriting() throws Exception {
        Fixture data = new Fixture();
        data.choose(false);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
        assertFalse(data.session.select(0));
        data.session.input("NEW");
        assertEquals("NEW", data.queried);
        assertEquals(2, data.queries);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertEquals(0, data.activations);
    }

    @Test
    public void cancelledQueriesAndCertificatesCannotReplaceNewSelection() throws Exception {
        Fixture data = new Fixture();
        data.session.open(true);
        HuoyanClient.Callback oldQuery = data.queryReply;
        oldQuery.onResult(0, "", models());
        HuoyanClient.Callback oldCertificate = data.certificateReply;
        data.session.cancel();
        data.session.open(false);
        data.session.input("NEW");
        oldQuery.onResult(-44, "过期查询", null);
        oldCertificate.onResult(0, "", certificate("OLD"));
        assertEquals(ModelSelectionSession.Stage.QUERYING, data.session.stage);
        data.queryReply.onResult(0, "", models());
        assertTrue(data.session.select(1));
        assertEquals("NEW", data.code);
        assertEquals(1, data.certificates);
    }

    @Test
    public void duplicateQueryAndCertificateCallbacksAreIgnored() throws Exception {
        Fixture data = new Fixture();
        data.session.open(true);
        data.queryReply.onResult(0, "", models());
        data.queryReply.onResult(0, "", models());
        assertEquals(1, data.certificates);
        data.certificateReply.onResult(0, "", certificate("CURRENT"));
        data.certificateReply.onResult(-44, "重复回执", null);
        assertEquals(ModelSelectionSession.Stage.CHOOSING, data.session.stage);
        assertEquals("", data.session.resultMessage);
    }

    @Test
    public void malformedOrEmptyListsFinishAndFirstActivationCanReturnToInput() throws Exception {
        for (JSONObject payload : new JSONObject[]{null, new JSONObject(),
                new JSONObject().put("models", new JSONArray()),
                new JSONObject().put("models", new JSONArray().put(new JSONObject().put("model_code", "a"))),
                new JSONObject().put("models", new JSONArray().put(new JSONObject()
                        .put("model_code", 12).put("model_name", "invalid")))}) {
            Fixture data = new Fixture();
            data.session.open(false);
            data.session.input("SERIAL");
            data.queryReply.onResult(0, "", payload);
            assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
            assertNotEquals(0, data.session.resultCode);
            assertFalse(data.session.resultMessage.isEmpty());
            assertFalse(data.session.submitted);
            data.session.consumed();
            assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
            assertEquals("", data.session.resultMessage);
            assertEquals(0, data.certificates);
            assertEquals(0, data.activations);
        }
    }

    @Test
    public void failedSwitchQueryDoesNotReadCertificateAndEndsAfterConsumption() {
        Fixture data = new Fixture();
        data.session.open(true);
        data.queryReply.onResult(-44, "授权无效", null);
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(-44, data.session.resultCode);
        assertEquals("授权无效", data.session.resultMessage);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertEquals(0, data.certificates);
        assertEquals(0, data.activations);
    }

    @Test
    public void malformedCertificateNeverReusesPreviousInput() throws Exception {
        for (JSONObject payload : new JSONObject[]{null, certificate(123), certificate(true),
                certificate(new JSONObject())}) {
            Fixture data = new Fixture();
            data.choose(false);
            data.session.cancel();
            data.session.cancel();
            data.session.open(true);
            data.queryReply.onResult(0, "", models());
            data.certificateReply.onResult(0, "", payload);
            assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
            assertFalse(data.session.select(1));
            assertNotEquals(0, data.session.resultCode);
            assertFalse(data.session.submitted);
            assertEquals(0, data.activations);
            data.session.consumed();
            assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        }
    }

    @Test
    public void restoredLicenseSelectsBeforeRequestingCodeAndActivatesWithoutRequery() throws Exception {
        for (JSONObject payload : new JSONObject[]{new JSONObject(), certificate(" "), certificate(JSONObject.NULL)}) {
            Fixture data = new Fixture();
            data.session.open(true);
            assertTrue(data.queriedDevice);
            data.queryReply.onResult(0, "", models());
            data.certificateReply.onResult(0, "", payload);
            assertEquals(ModelSelectionSession.Stage.CHOOSING, data.session.stage);
            assertTrue(data.session.needsActivationCode);
            assertEquals(0, data.activations);
            assertTrue(data.session.select(1));
            assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
            assertFalse(data.session.submitted);
            data.session.detach();
            data.session.attach(() -> { });
            data.session.input(" RECOVERED-CODE ");
            data.session.input("DUPLICATE");
            assertEquals(1, data.queries);
            assertEquals(1, data.certificates);
            assertEquals(ModelSelectionSession.Stage.ACTIVATING, data.session.stage);
            assertEquals("RECOVERED-CODE", data.code);
            assertEquals("model-b", data.model);
            assertEquals(1, data.activations);
            data.activationReply.onResult(0, "", new JSONObject());
            assertEquals("模型切换成功", data.session.resultMessage);
            data.session.consumed();
            assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
            assertFalse(data.session.needsActivationCode);
        }
    }

    @Test
    public void missingCodeCancelCurrentAndBlankInputNeverWrite() throws Exception {
        Fixture data = new Fixture();
        data.requestMissingCode();
        assertTrue(data.session.select(0));
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        data.requestMissingCode();
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        data.requestMissingCode();
        data.session.select(1);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        data.session.input("LATE");
        data.requestMissingCode();
        data.session.select(1);
        data.session.input(" ");
        assertEquals(HuoyanClient.ERROR_INVALID_REQUEST, data.session.resultCode);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
        assertEquals(0, data.activations);
    }

    @Test
    public void missingCodeInputIsBoundToDeviceAndLateCertificateCannotReplaceIt() throws Exception {
        Fixture data = new Fixture();
        data.requestMissingCode();
        HuoyanClient.Callback oldCertificate = data.certificateReply;
        data.session.select(1);
        oldCertificate.onResult(0, "", certificate("LATE-CODE"));
        assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
        data.session.input("EXPLICIT-CODE");
        assertEquals("EXPLICIT-CODE", data.code);
        assertEquals(1, data.queries);

        data = new Fixture();
        data.requestMissingCode();
        data.session.select(1);
        data.session.detach();
        data.session.attach(() -> { });
        assertEquals(1, data.queries);
        assertEquals(1, data.certificates);
        data.generation++;
        data.session.input("OTHER-DEVICE-CODE");
        assertEquals(HuoyanClient.ERROR_NOT_CONNECTED, data.session.resultCode);
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(0, data.activations);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
    }

    @Test
    public void failedCertificatePreservesItsErrorInsteadOfChoosing() throws Exception {
        Fixture data = new Fixture();
        data.session.open(true);
        data.queryReply.onResult(0, "", models());
        data.certificateReply.onResult(-55, "证书读取失败", certificate("IGNORED"));
        assertEquals(-55, data.session.resultCode);
        assertEquals("证书读取失败", data.session.resultMessage);
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(0, data.activations);
    }

    @Test
    public void connectionChangeInvalidatesQueryCertificateAndChoice() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            Fixture data = new Fixture();
            data.session.open(true);
            if (phase > 0) data.queryReply.onResult(0, "", models());
            if (phase > 1) data.certificateReply.onResult(0, "", certificate("CURRENT"));
            data.generation++;
            if (phase == 0) data.queryReply.onResult(0, "", models());
            else if (phase == 1) data.certificateReply.onResult(0, "", certificate("CURRENT"));
            else assertFalse(data.session.select(1));
            assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
            assertEquals(HuoyanClient.ERROR_NOT_CONNECTED, data.session.resultCode);
            assertEquals(0, data.activations);
        }
    }

    @Test
    public void changedConnectionAfterSubmissionKeepsUnknownAndDropsOldReceipt() throws Exception {
        Fixture data = new Fixture();
        data.choose(false);
        data.session.select(1);
        HuoyanClient.Callback old = data.activationReply;
        data.generation++;
        assertFalse(data.session.validateConnection());
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, data.session.resultCode);
        assertTrue(data.session.submitted);
        data.session.consumed();
        data.session.open(false);
        data.session.input("NEW");
        old.onResult(0, "", new JSONObject());
        assertEquals(ModelSelectionSession.Stage.QUERYING, data.session.stage);
        assertEquals(1, data.activations);
    }

    @Test
    public void cancellationCannotPretendSubmittedActivationWasCancelled() throws Exception {
        Fixture data = new Fixture();
        data.choose(false);
        data.session.select(1);
        data.session.cancel();
        assertEquals(ModelSelectionSession.Stage.ACTIVATING, data.session.stage);
        data.activationReply.onResult(-109, "激活被拒绝", new JSONObject());
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(-109, data.session.resultCode);
        assertEquals("激活被拒绝", data.session.resultMessage);
        data.activationReply.onResult(0, "迟到成功", new JSONObject());
        assertEquals(-109, data.session.resultCode);
        assertEquals(1, data.activations);
    }

    @Test
    public void rejectedActivationAndSynchronousErrorCannotGetStuckOrBeOverwritten() throws Exception {
        Fixture data = new Fixture();
        data.accepted = false;
        data.choose(false);
        assertFalse(data.session.select(1));
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(HuoyanClient.ERROR_BUSY, data.session.resultCode);
        data = new Fixture();
        data.accepted = false;
        data.synchronousCode = HuoyanClient.ERROR_NOT_CONNECTED;
        data.choose(false);
        assertFalse(data.session.select(1));
        assertEquals(HuoyanClient.ERROR_NOT_CONNECTED, data.session.resultCode);
        assertEquals("同步拒绝", data.session.resultMessage);
    }

    @Test
    public void thrownReadsAreErrorsAndThrownWriteRemainsUnknown() throws Exception {
        Fixture data = new Fixture();
        data.queryThrows = true;
        data.session.open(true);
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertFalse(data.session.submitted);
        data = new Fixture();
        data.certificateThrows = true;
        data.session.open(true);
        data.queryReply.onResult(0, "", models());
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertFalse(data.session.submitted);
        data = new Fixture();
        data.activationThrows = true;
        data.choose(false);
        assertFalse(data.session.select(1));
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, data.session.resultCode);
        assertEquals(1, data.activations);
    }

    @Test
    public void detachedPagesRestoreQueryAndActivationResultsWithoutReplay() throws Exception {
        Fixture data = new Fixture();
        int[] notifications = {0};
        data.session.attach(() -> notifications[0]++);
        data.session.open(false);
        data.session.input("SERIAL");
        data.session.detach();
        int before = notifications[0];
        data.queryReply.onResult(0, "", models());
        assertEquals(before, notifications[0]);
        data.session.attach(() -> notifications[0]++);
        assertEquals(ModelSelectionSession.Stage.CHOOSING, data.session.stage);
        assertEquals(1, data.queries);
        data.session.select(1);
        data.session.detach();
        before = notifications[0];
        data.activationReply.onResult(0, "", new JSONObject());
        assertEquals(before, notifications[0]);
        data.session.attach(() -> notifications[0]++);
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(0, data.session.resultCode);
        assertEquals(1, data.activations);
    }

    @Test
    public void destructionDropsAllLateCallbacksAndCannotReopen() throws Exception {
        for (boolean writing : new boolean[]{false, true}) {
            Fixture data = new Fixture();
            if (writing) {
                data.choose(false);
                data.session.select(1);
            } else {
                data.session.open(true);
                data.queryReply.onResult(0, "", models());
            }
            int[] notifications = {0};
            data.session.attach(() -> notifications[0]++);
            data.session.destroy();
            if (writing) data.activationReply.onResult(0, "", new JSONObject());
            else data.certificateReply.onResult(0, "", certificate("CURRENT"));
            data.session.attach(() -> notifications[0]++);
            data.session.open(false);
            assertEquals(ModelSelectionSession.Stage.IDLE, data.session.stage);
            assertEquals(1, notifications[0]);
            assertEquals(writing ? 1 : 0, data.activations);
        }
    }

    @Test
    public void blankInputNeverQueriesAndMayBeRetriedAfterResultConsumption() {
        Fixture data = new Fixture();
        data.session.open(false);
        data.session.input(" ");
        assertEquals(ModelSelectionSession.Stage.FINISHED, data.session.stage);
        assertEquals(HuoyanClient.ERROR_INVALID_REQUEST, data.session.resultCode);
        data.session.consumed();
        assertEquals(ModelSelectionSession.Stage.INPUT, data.session.stage);
        assertEquals(0, data.queries);
    }

    private static JSONObject models() throws Exception {
        return new JSONObject().put("models", new JSONArray()
                .put(new JSONObject().put("model_code", "model-a").put("model_name", "同名模型").put("current", true))
                .put(new JSONObject().put("model_code", "model-b").put("model_name", "同名模型").put("current", false)));
    }

    private static JSONObject certificate(Object serial) throws Exception {
        return new JSONObject().put("activation_serial", serial);
    }

    private static final class Fixture implements ModelSelectionSession.Data {
        final ModelSelectionSession session = new ModelSelectionSession(this);
        long generation;
        int queries, certificates, activations;
        String queried, code, model;
        boolean queriedDevice;
        HuoyanClient.Callback queryReply, certificateReply, activationReply;
        boolean accepted = true, queryThrows, certificateThrows, activationThrows;
        Integer synchronousCode;

        void choose(boolean switching) throws Exception {
            session.open(switching);
            if (!switching) session.input("SERIAL");
            queryReply.onResult(0, "", models());
            if (switching) certificateReply.onResult(0, "", ModelSelectionSessionTest.certificate("CURRENT"));
        }

        void requestMissingCode() throws Exception {
            session.open(true);
            queryReply.onResult(0, "", models());
            certificateReply.onResult(0, "", new JSONObject());
        }

        @Override
        public long generation() {
            return generation;
        }

        @Override
        public void query(String code, boolean switching, HuoyanClient.Callback callback) {
            queries++;
            queried = code;
            queriedDevice = switching;
            queryReply = callback;
            if (queryThrows) throw new IllegalStateException("读取失败");
        }

        @Override
        public void certificate(HuoyanClient.Callback callback) {
            certificates++;
            certificateReply = callback;
            if (certificateThrows) throw new IllegalStateException("读取失败");
        }

        @Override
        public boolean activate(String code, String model, HuoyanClient.Callback callback) {
            activations++;
            this.code = code;
            this.model = model;
            activationReply = callback;
            if (activationThrows) throw new IllegalStateException("回执丢失");
            if (synchronousCode != null) callback.onResult(synchronousCode, "同步拒绝", new JSONObject());
            return accepted;
        }
    }
}
