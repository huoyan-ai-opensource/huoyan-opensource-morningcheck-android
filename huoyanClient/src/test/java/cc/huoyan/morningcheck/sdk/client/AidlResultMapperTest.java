package cc.huoyan.morningcheck.sdk.client;

import cc.huoyan.morningcheck.app.aidl.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Binder 与 WS 数据语义一致性；保留未知状态、长 ID、时间单位和真正业务错误。 */
public class AidlResultMapperTest {
    @Test public void licenseModelsPreserveWireFieldsAndFailureCode() throws Exception {
        cc.huoyan.morningcheck.app.aidl.LicenseModelParcel model =
                new cc.huoyan.morningcheck.app.aidl.LicenseModelParcel("mc", "晨检", "rk3576", true);
        AidlResultMapper.Reply reply = AidlResultMapper.map(
                new cc.huoyan.morningcheck.app.aidl.LicenseModelsParcel(0, "", java.util.List.of(model)));
        JSONObject item = reply.data.getJSONArray("models").getJSONObject(0);
        assertEquals("mc", item.getString("model_code"));
        assertEquals("晨检", item.getString("model_name"));
        assertEquals("rk3576", item.getString("chip"));
        assertTrue(item.getBoolean("current"));
        assertEquals(4, item.length());
        AidlResultMapper.Reply failure = AidlResultMapper.map(
                new cc.huoyan.morningcheck.app.aidl.LicenseModelsParcel(-44, "无授权", java.util.List.of()));
        assertEquals(-44, failure.code);
        assertEquals("无授权", failure.message);
    }

    @Test public void nullableStatusRemainsUnknown() throws Exception {
        StatusSnapshotParcel p = new StatusSnapshotParcel(0, null, "dev", null, null, null,
                null, false, "NOT_ACTIVATED", false, false, null, 1234567890123L, null, "未激活");
        JSONObject data = AidlResultMapper.map(p).data;
        assertTrue(data.has("key_lost")); assertTrue(data.isNull("key_lost"));
        assertTrue(data.isNull("certificate_valid"));
        assertFalse(data.getBoolean("backend_connected"));
        assertEquals(1234567890123L, data.getLong("state_updated_at"));
        assertEquals("未激活", data.getString("recent_error"));
    }

    @Test public void offlineStatusPreservesLocalReadinessAndUncalibratedState() throws Exception {
        StatusSnapshotParcel p = new StatusSnapshotParcel(0, null, "dev", null, null, "cached-model",
                false, false, "OFFLINE", true, false, null, 1234567890123L, true, null);
        AidlResultMapper.Reply reply = AidlResultMapper.map(p);
        assertEquals(0, reply.code);
        assertEquals("OFFLINE", reply.data.getString("sdk_state"));
        assertTrue(reply.data.getBoolean("model_ready"));
        assertTrue(reply.data.getBoolean("certificate_valid"));
        assertFalse(reply.data.getBoolean("key_lost"));
        assertFalse(reply.data.getBoolean("backend_connected"));
        assertEquals("cached-model", reply.data.getString("model_version"));
    }

    @Test public void unmatchedIdentityAndLongIdKeepOriginalEvidence() throws Exception {
        JSONObject data = AidlResultMapper.map(IdentityResultParcel.ok(
                new McIdentityParcel(9_007_199_254_740_999L, -0.5f, false))).data;
        assertEquals(9_007_199_254_740_999L, data.getLong("person_id"));
        assertFalse(data.getBoolean("matched"));
        assertEquals(-0.5, data.getDouble("similarity"), 0.000001);
    }

    @Test public void emptyDetectionIsSuccessfulAndErrorStaysError() throws Exception {
        AidlResultMapper.Reply empty = AidlResultMapper.map(DetectionListParcel.emptyOk());
        assertEquals(0, empty.code); assertEquals(0, empty.data.getJSONArray("boxes").length());
        AidlResultMapper.Reply error = AidlResultMapper.map(DetectionListParcel.err(-3, "证书过期"));
        assertEquals(-3, error.code); assertEquals("证书过期", error.message);
        assertFalse(error.data.has("boxes"));
    }

    @Test public void boxesUseNormalizedCoordinatesAndSnakeCaseClassId() throws Exception {
        JSONObject box = AidlResultMapper.map(new DetectionListParcel(0, null, 1,
                new float[]{0.1f}, new float[]{0.2f}, new float[]{0.3f}, new float[]{0.4f},
                new int[]{6}, new float[]{0.9f})).data.getJSONArray("boxes").getJSONObject(0);
        assertEquals(6, box.getInt("class_id"));
        assertEquals(0.3, box.getDouble("w"), 0.000001);
        assertEquals(0.4, box.getDouble("h"), 0.000001);
    }

    @Test public void faceDetectionKeepsEachBoxAndItsLandmarksWithoutIdentity() throws Exception {
        float[] points = new float[20];
        for (int i = 0; i < points.length; i++) points[i] = i / 25f;
        FaceListParcel reply = new FaceListParcel(0, null, 2,
                new float[]{0.1f, 0.5f}, new float[]{0.2f, 0.3f},
                new float[]{0.3f, 0.2f}, new float[]{0.4f, 0.5f},
                new float[]{0.99f, 0.91f}, points);
        points[10] = 999;
        JSONObject data = AidlResultMapper.map(reply).data;
        JSONArray faces = data.getJSONArray("faces");
        assertEquals(2, faces.length());
        assertEquals(0.1, faces.getJSONObject(0).getDouble("x"), 0.000001);
        assertEquals(0.5, faces.getJSONObject(1).getDouble("x"), 0.000001);
        assertEquals(0.91, faces.getJSONObject(1).getDouble("score"), 0.000001);
        assertEquals(10, faces.getJSONObject(1).getJSONArray("landmarks").length());
        assertEquals(0.4, faces.getJSONObject(1).getJSONArray("landmarks").getDouble(0), 0.000001);
        assertFalse(data.has("matched"));
        assertFalse(faces.getJSONObject(0).has("person_id"));
        assertFalse(data.has("boxes"));
    }

    @Test public void emptyFaceDetectionSucceedsButErrorsNeverLookLikeNoFace() throws Exception {
        AidlResultMapper.Reply empty = AidlResultMapper.map(FaceListParcel.emptyOk());
        assertEquals(0, empty.code);
        assertEquals(0, empty.data.getJSONArray("faces").length());
        AidlResultMapper.Reply error = AidlResultMapper.map(FaceListParcel.err(-3, "证书过期"));
        assertEquals(-3, error.code);
        assertEquals("证书过期", error.message);
        assertFalse(error.data.has("faces"));
    }

    @Test public void capabilityIncludesFaceDetectionAndPreservesShellVersionTail() throws Exception {
        JSONObject data = AidlResultMapper.map(new CapabilityParcel(0, null, 3,
                true, true, true, true, true, true, "1.2.3", 123, true)).data;
        assertTrue(data.getBoolean("detect_face"));
        assertEquals("1.2.3", data.getString("sdk_version"));
        assertEquals(123, data.getInt("sdk_version_code"));
        JSONObject legacy = AidlResultMapper.map(new CapabilityParcel(0, null, 3,
                true, true, true, true, true, true)).data;
        assertFalse(legacy.getBoolean("detect_face"));
        assertEquals("unknown", legacy.getString("sdk_version"));
        assertEquals(-1, legacy.getInt("sdk_version_code"));
    }

    @Test public void classTableGroupsModelsWithoutConfusingClassIds() throws Exception {
        JSONObject data = AidlResultMapper.map(new ClassTableParcel(0,
                new String[]{"head", "hand", "head"}, new int[]{0, 0, 1},
                new String[]{"帽子", "手背", "口罩"}, new String[]{"hat", "back", "mask"})).data;
        assertEquals(2, data.getJSONArray("head").length());
        assertEquals("back", data.getJSONArray("hand").getJSONObject(0).getString("nameEn"));
        assertEquals(1, data.getJSONArray("head").getJSONObject(1).getInt("classId"));
    }

    @Test public void certificateUsesSecondsAndWsVersionKey() throws Exception {
        JSONObject data = AidlResultMapper.map(new CertInfoParcel(0, null, "morning", "stable", "daily",
                1_700_000_001L, 1_700_000_002L, 0, false, 1_700_100_000L, true,
                1_700_200_000L, "0.9.0", 9, "code")).data;
        assertEquals(1_700_200_000L, data.getLong("expires_at"));
        assertFalse(data.getBoolean("has_renewed_at"));
        assertTrue(data.getBoolean("has_next_renew_at"));
        assertEquals("0.9.0", data.getString("sdk_version"));
        assertFalse(data.has("sdk_version_name"));
    }

    @Test public void bindingSeparatesExternalRequestAndInternalResponse() throws Exception {
        JSONObject data = AidlResultMapper.map(new BindingResultParcel(0, null,
                new BindDeviceParcel(123, "erp-007", null, null),
                "123", "account", "999", "一店", "001")).data;
        assertEquals("erp-007", data.getJSONObject("request").getString("shop_id"));
        assertEquals("999", data.getJSONObject("response").getString("shop_id"));
    }

    @Test public void missingReplyIsUnknownNeverSuccess() throws Exception {
        assertEquals(HuoyanClient.ERROR_RESULT_UNKNOWN, AidlResultMapper.map(null).code);
        assertEquals(-110, AidlResultMapper.map(new BaseResultParcel(-110, "等待审核")).code);
    }

    @Test public void registrationUsesSdkImageUrlAndKeepsLongUid() throws Exception {
        long uid = 9_007_199_254_740_999L;
        String url = "http://127.0.0.1:18087/face-images/9/" + uid;
        JSONObject data = AidlResultMapper.map(RegisterResultParcel.ok(uid, url)).data;
        assertEquals(uid, data.getLong("uid"));
        assertEquals(url, data.getString("image_url"));
        assertEquals("", AidlResultMapper.map(RegisterResultParcel.ok(uid)).data.getString("image_url"));
        assertFalse(AidlResultMapper.map(RegisterResultParcel.err(-3, "授权未完成")).data.has("image_url"));
    }

    @Test public void userDetailItemsBindUidToImageAndKeepLegacyProjection() throws Exception {
        long[] uids = {77101L, 9_007_199_254_740_999L};
        String firstUrl = "http://127.0.0.1:18087/face-images/9/77101";
        JSONObject data = AidlResultMapper.map(UserDetailParcel.ok(uids, new String[]{firstUrl, ""})).data;
        JSONArray items = data.getJSONArray("items");
        assertEquals(2, items.length());
        assertEquals(uids[0], items.getJSONObject(0).getLong("uid"));
        assertEquals(firstUrl, items.getJSONObject(0).getString("image_url"));
        assertTrue(items.getJSONObject(0).getBoolean("image_available"));
        assertEquals("image/jpeg", items.getJSONObject(0).getString("mime_type"));
        assertEquals(uids[1], items.getJSONObject(1).getLong("uid"));
        assertFalse(items.getJSONObject(1).getBoolean("image_available"));
        assertEquals("", items.getJSONObject(1).getString("image_url"));
        assertEquals("", items.getJSONObject(1).getString("mime_type"));
        JSONArray samples = data.getJSONArray("samples");
        assertEquals(2, samples.length());
        assertEquals(77101L, samples.getJSONObject(0).getLong("uid"));
        assertEquals(firstUrl, samples.getJSONObject(0).getString("image_url"));
        assertEquals(uids[1], samples.getJSONObject(1).getLong("uid"));
        assertEquals("", samples.getJSONObject(1).getString("image_url"));
        assertEquals(uids[1], data.getJSONArray("uids").getLong(1));
    }

    @Test public void oldUserDetailAndAbsentEmployeeDoNotFabricateImages() throws Exception {
        JSONArray legacy = AidlResultMapper.map(UserDetailParcel.ok(new long[]{42L})).data.getJSONArray("items");
        assertEquals(42L, legacy.getJSONObject(0).getLong("uid"));
        assertEquals("", legacy.getJSONObject(0).getString("image_url"));
        assertFalse(legacy.getJSONObject(0).getBoolean("image_available"));
        assertEquals("", legacy.getJSONObject(0).getString("mime_type"));
        assertEquals(0, AidlResultMapper.map(UserDetailParcel.ok(new long[0])).data.getJSONArray("items").length());
        AidlResultMapper.Reply absent = AidlResultMapper.map(UserDetailParcel.err(-6, "未找到员工"));
        assertEquals(-6, absent.code);
        assertFalse(absent.data.has("samples"));
        assertFalse(absent.data.has("items"));
    }

    @Test public void optionalFeedbackTypeDefaultsToZero() throws Exception {
        JSONObject input = feedback();
        assertEquals(0, AidlResultMapper.feedback(input).getFeedbackType());
        input.put("feedback_type", JSONObject.NULL);
        assertEquals(0, AidlResultMapper.feedback(input).getFeedbackType());
        assertFalse(AidlResultMapper.feedback(input).getGoods().get(0).hasDistance());
    }

    @Test public void feedbackRejectsFractionalOrStringInteger() throws Exception {
        for (Object invalid : new Object[]{1.5, "1"}) {
            JSONObject input = feedback().put("feedback_type", invalid);
            try { AidlResultMapper.feedback(input); fail("应拒绝不符合线契约的反馈类型"); }
            catch (IllegalArgumentException expected) { }
        }
        JSONObject input = feedback();
        input.getJSONArray("goods").getJSONObject(0).put("distance", "0.2");
        try { AidlResultMapper.feedback(input); fail("不能把文本数值转换为反馈距离"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void feedbackAllowsNoGoodsButRejectsCoercedIdentityFields() throws Exception {
        JSONObject input = feedback();
        input.remove("goods");
        assertTrue(AidlResultMapper.feedback(input).getGoods().isEmpty());
        input.put("goods", JSONObject.NULL);
        assertTrue(AidlResultMapper.feedback(input).getGoods().isEmpty());
        input.put("request_id", 7);
        try { AidlResultMapper.feedback(input); fail("请求标识不能强转字符串"); }
        catch (IllegalArgumentException expected) { }
        try { AidlResultMapper.optionalString(new JSONObject().put("shop_name", 7), "shop_name"); fail("门店名不能强转字符串"); }
        catch (IllegalArgumentException expected) { }
    }

    private static JSONObject feedback() throws Exception {
        return new JSONObject().put("request_id", "one").put("photo_file_url", "https://example.test/photo.jpg")
                .put("goods", new JSONArray().put(new JSONObject().put("goods_id", "123")));
    }
}
