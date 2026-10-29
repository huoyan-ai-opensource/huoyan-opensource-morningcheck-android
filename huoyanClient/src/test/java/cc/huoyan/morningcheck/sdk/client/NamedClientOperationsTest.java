package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** 验证公共入口确实走同一传输与请求生命周期，不依赖 Android 页面或真实云。 */
public class NamedClientOperationsTest {
    @Test public void lifecycleQueriesAndSettingsDispatchOnBothChannels() throws Exception {
        for (int api : new int[]{21, 30}) {
            Fixture f = new Fixture(api);
            List<Integer> codes = new ArrayList<>();
            HuoyanClient.Callback callback = (code, message, data) -> codes.add(code);
            f.client.init(callback); f.finish("init", 0);
            f.client.refreshStatus(callback); f.finish("getStatus", 0);
            f.client.refreshCapabilities(callback); f.finish("getCapabilities", 0);
            f.client.getCertInfo(callback); f.finish("getCertInfo", 0);
            f.client.getDownloadProgress(callback); f.finish("getDownloadProgress", 0);
            f.client.refreshClassTable(callback); f.finish("getClassTable", 0);
            f.client.getCloudStatus(callback); f.finish("getCloudStatus", 0);
            f.client.userCount(callback); f.finish("userCount", 0);
            f.client.activate("HY2-abcd-1234-abcd", "morningcheck", callback);
            assertEquals("HY2-abcd-1234-abcd", f.last().params.getString("activation_code"));
            f.finish("activate", -42);
            f.client.setBaseUrl("https://sdk.example.test/v1", callback);
            assertEquals("https://sdk.example.test/v1", f.last().params.getString("url"));
            f.finish("setBaseUrl", 0);
            f.client.setCloudStatus(true, callback);
            assertTrue(f.last().params.getBoolean("enabled"));
            f.finish("setCloudStatus", 0);
            f.client.setCloudStatus(false, callback);
            assertFalse(f.last().params.getBoolean("enabled"));
            f.finish("setCloudStatus", 40010);
            assertEquals(12, codes.size());
            assertEquals(Integer.valueOf(-42), codes.get(8));
            assertEquals(Integer.valueOf(40010), codes.get(11));
            assertEquals(api < 27 ? SdkConfig.Channel.WS : SdkConfig.Channel.AIDL, f.selected);
            f.client.destroy();
        }
    }

    @Test public void licenseModelsAndActivationUseNewProtocolOnBothChannels() throws Exception {
        for (int api : new int[]{21, 30}) {
            Fixture f = new Fixture(api);
            List<JSONObject> replies = new ArrayList<>();
            f.client.queryDeviceModels((code, reason, data) -> replies.add(data));
            assertEquals("queryDeviceModels", f.last().command);
            assertEquals(0, f.last().params.length());
            JSONObject models = new JSONObject().put("models", new JSONArray().put(new JSONObject()
                    .put("model_code", "mc-rk3576").put("model_name", "晨检")
                    .put("chip", "rk3576").put("current", false)));
            f.last().callback.onResult(0, "", models);
            assertEquals(models.toString(), replies.get(0).toString());
            f.client.queryDeviceModels(null);
            assertFalse("当前设备查询必须省略字段", f.last().params.has("activation_code"));
            f.client.activate("SERIAL", "mc-rk3576", null);
            assertEquals("mc-rk3576", f.last().params.getString("model_code"));
            assertThrows(IllegalArgumentException.class, () -> f.client.activate("SERIAL", " ", null));
            assertThrows(IllegalArgumentException.class, () -> f.client.activate("SERIAL", null, null));
            assertEquals(3, f.calls.size());
            f.client.destroy();
        }
    }

    @Test public void licenseQueryRequiresCodeAndDeviceQueryHasNoParameters() throws Exception {
        for (int api : new int[]{21, 30}) {
            Fixture f = new Fixture(api);
            f.client.queryLicenseModels("INPUT-CODE", null);
            assertEquals("queryLicenseModels", f.last().command);
            assertEquals("INPUT-CODE", f.last().params.getString("activation_code"));
            assertEquals(1, f.last().params.length());
            for (String code : new String[]{null, "", " "})
                assertThrows(IllegalArgumentException.class, () -> f.client.queryLicenseModels(code, null));
            assertEquals(1, f.calls.size());
            f.client.queryDeviceModels(null);
            assertEquals("queryDeviceModels", f.last().command);
            assertEquals(0, f.last().params.length());
            f.client.destroy();
        }
    }

    @Test public void imageMethodsPreserveFrameAndExactPersonId() throws Exception {
        Fixture f = new Fixture(30);
        byte[] rgb = new byte[]{1, 2, 3, 4, 5, 6};
        f.client.identify(rgb, 2, 1, null);
        f.assertImage("identify", rgb, 2, 1);
        f.client.checkHead(rgb, 2, 1, 8, null);
        f.assertImage("checkHead", rgb, 2, 1);
        assertEquals(8, f.last().params.getInt("max_detections"));
        f.client.checkHand(rgb, 2, 1, 64, null);
        f.assertImage("checkHand", rgb, 2, 1);
        assertEquals(64, f.last().params.getInt("max_detections"));
        List<JSONObject> result = new ArrayList<>();
        f.client.register(rgb, 2, 1, Long.MAX_VALUE, (code, message, data) -> result.add(data));
        f.assertImage("register", rgb, 2, 1);
        assertEquals(Long.MAX_VALUE, f.last().params.getLong("person_id"));
        f.last().callback.onResult(0, "登记成功", new JSONObject().put("uid", 53));
        assertEquals(53, result.get(0).getLong("uid"));
        byte[] photo = new byte[]{(byte) 0xff, (byte) 0xd8, 0, (byte) 0xff, (byte) 0xd9};
        f.client.uploadPhoto(photo, "jpeg", null);
        assertEquals("uploadPhoto", f.last().command);
        assertSame(photo, f.last().image);
        assertEquals("jpeg", f.last().params.getString("format"));
        f.client.destroy();
    }

    @Test public void personnelManagementStaysAvailableWithoutModelReady() throws Exception {
        Fixture f = new Fixture(30);
        assertFalse(f.client.isReady());
        f.client.userDetail(9007199254740993L, null);
        assertEquals("userDetail", f.last().command);
        assertEquals(9007199254740993L, f.last().params.getLong("person_id"));
        f.client.forget(9007199254740993L, null);
        assertEquals("forget", f.last().command);
        assertEquals(9007199254740993L, f.last().params.getLong("person_id"));
        f.client.forgetByUid(Long.MAX_VALUE, null);
        assertEquals("forgetByUid", f.last().command);
        assertEquals(Long.MAX_VALUE, f.last().params.getLong("uid"));
        f.client.bindDevice("007", null);
        assertEquals("bindDevice", f.last().command);
        assertEquals("007", f.last().params.getString("shop_id"));
        assertEquals(2097500510197125120L, f.last().params.getLong("account_id"));
        f.client.destroy();
    }

    @Test public void faceDetectionPreservesFrameLimitAndReplyOnBothChannels() throws Exception {
        for (int api : new int[]{21, 30}) {
            Fixture f = new Fixture(api);
            byte[] rgb = new byte[]{1, 2, 3, 4, 5, 6};
            List<JSONObject> results = new ArrayList<>();
            f.client.detectFace(rgb, 2, 1, 3, (code, message, data) -> results.add(data));
            f.assertImage("detectFace", rgb, 2, 1);
            assertEquals(3, f.last().params.getInt("max_faces"));
            f.last().callback.onResult(0, "", new JSONObject().put("faces", new JSONArray()));
            assertEquals(0, results.get(0).getJSONArray("faces").length());
            assertFalse(results.get(0).has("matched"));
            // 与壳保持一致，上限超过 64 的有效 int 留给壳截断。
            f.client.detectFace(rgb, 2, 1, 65, null);
            assertEquals(65, f.last().params.getInt("max_faces"));
            assertThrows(IllegalArgumentException.class, () -> f.client.detectFace(rgb, 2, 1, 0, null));
            assertThrows(IllegalArgumentException.class, () -> f.client.detectFace(rgb, 2, 1, -1, null));
            assertThrows(IllegalArgumentException.class, () -> f.client.detectFace(new byte[3], 2, 1, 3, null));
            assertEquals(2, f.calls.size());
            f.client.destroy();
        }
    }

    @Test public void feedbackUsesOriginalFieldsAndUnknownResultIsNeverReplayed() throws Exception {
        Fixture f = new Fixture(21);
        JSONObject feedback = new JSONObject().put("request_id", "result-1")
                .put("photo_file_url", "https://photo.example.test/check.jpg").put("feedback_type", 1)
                .put("goods", new JSONArray().put(new JSONObject().put("goods_id", "staff-001")
                        .put("selected", 1).put("distance", 0.123).put("source", "manual")));
        List<Integer> codes = new ArrayList<>();
        f.client.submitFeedback(feedback, (code, message, data) -> codes.add(code));
        assertEquals("submitFeedback", f.last().command);
        assertEquals("result-1", f.last().params.getString("request_id"));
        assertEquals("staff-001", f.last().params.getJSONArray("goods").getJSONObject(0).getString("goods_id"));
        f.last().callback.onResult(40009, "云端回执未知", new JSONObject());
        f.last().callback.onResult(0, "迟到成功", new JSONObject());
        assertEquals(List.of(40009), codes);
        assertEquals(1, f.calls.size());
        f.client.destroy();
    }

    @Test public void invalidNamedParametersNeverReachTransport() {
        Fixture f = new Fixture(21);
        byte[] rgb = new byte[3];
        assertThrows(IllegalArgumentException.class, () -> f.client.activate("  ", "morningcheck", null));
        assertThrows(IllegalArgumentException.class, () -> f.client.setBaseUrl(null, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.identify(rgb, 2, 1, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.checkHead(rgb, 1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.checkHand(rgb, 1, 1, 65, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.register(rgb, 1, 1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.forget(-1, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.forgetByUid(0, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.userDetail(-2, null));
        assertThrows(IllegalArgumentException.class, () -> f.client.uploadPhoto(rgb, "rgb", null));
        assertThrows(IllegalArgumentException.class, () -> f.client.submitFeedback(new JSONObject(), null));
        assertTrue(f.calls.isEmpty());
        f.client.destroy();
    }

    @Test public void namedRequestCancellationKeepsOriginalRequestIdentity() {
        Fixture f = new Fixture(30);
        List<Integer> codes = new ArrayList<>();
        String id = f.client.forgetByUid(17, (code, message, data) -> codes.add(code));
        f.client.cancel(id);
        assertEquals("wire-1", f.cancelled);
        f.last().callback.onResult(HuoyanClient.ERROR_CANCELLED, "停止等待", new JSONObject());
        f.client.close();
        f.last().callback.onResult(0, "迟到成功", new JSONObject());
        assertEquals(List.of(HuoyanClient.ERROR_CANCELLED), codes);
        f.client.destroy();
    }

    private static final class Call {
        final String command;
        final JSONObject params;
        final byte[] image;
        final HuoyanClient.Callback callback;
        Call(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
            this.command = command; this.params = params; this.image = image; this.callback = callback;
        }
    }

    private static final class Fixture implements ClientTransport {
        final List<Call> calls = new ArrayList<>();
        final HuoyanClient client;
        SdkConfig.Channel selected;
        Runnable initialized;
        String cancelled;
        boolean connected;

        Fixture(int api) {
            client = new HuoyanClient(api, null, Runnable::run, (channel, listener) -> {
                selected = channel; return this;
            }, (delay, action) -> { throw new AssertionError("本用例初始化已经完成，不应再预约等待"); });
            client.connect(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT));
        }
        Call last() { return calls.get(calls.size() - 1); }
        void finish(String command, int code) {
            assertEquals(command, last().command);
            assertNull(last().image);
            last().callback.onResult(code, "测试回执", new JSONObject());
        }
        void assertImage(String command, byte[] image, int width, int height) throws Exception {
            assertEquals(command, last().command);
            assertSame(image, last().image);
            assertEquals(width, last().params.getInt("width"));
            assertEquals(height, last().params.getInt("height"));
            assertEquals("rgb", last().params.getString("format"));
        }
        @Override public void setInitializationListener(Runnable listener) { initialized = listener; }
        @Override public void connect(String endpoint) { connected = true; initialized.run(); }
        @Override public String request(String command, JSONObject params, byte[] image, HuoyanClient.Callback callback) {
            calls.add(new Call(command, params, image, callback)); return "wire-" + calls.size();
        }
        @Override public void cancel(String id) { cancelled = id; }
        @Override public void close() { connected = false; }
        @Override public void destroy() { connected = false; }
        @Override public JSONObject getStatus() { return new JSONObject(); }
        @Override public JSONObject getCapabilities() { return new JSONObject(); }
        @Override public JSONObject getClassTable() { return new JSONObject(); }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isReady() { return false; }
    }
}
