package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static cc.huoyan.opensource.morningcheck.domain.FaceResultPolicy.Registration.*;
import static org.junit.Assert.*;

public class FaceResultPolicyTest {
    @Test public void actualUidAndNoFaceHaveDifferentOutcomes() throws Exception {
        assertEquals(SAVED, FaceResultPolicy.registration(0, new JSONObject().put("uid", 2097500510197125121L)));
        assertEquals(NO_FACE, FaceResultPolicy.registration(0, new JSONObject().put("uid", 0)));
    }

    @Test public void missingMalformedSuccessCannotBeReplayedAsKnownFailure() throws Exception {
        for (Object uid : new Object[]{"123", -1, 1.5, 0x1.0p63, JSONObject.NULL})
            assertEquals(UNKNOWN, FaceResultPolicy.registration(0, new JSONObject().put("uid", uid)));
        assertEquals(UNKNOWN, FaceResultPolicy.registration(0, new JSONObject()));
    }

    @Test public void onlyUnknownRequiresReconciliationBeforeAnotherAttempt() throws Exception {
        assertEquals(UNKNOWN, FaceResultPolicy.registration(40009, new JSONObject().put("uid", 12)));
        assertEquals(UNKNOWN, FaceResultPolicy.registration(-20002, new JSONObject()));
        for (int code : new int[]{40010, -3, -9, -20001, -20003})
            assertEquals(REJECTED, FaceResultPolicy.registration(code, new JSONObject()));
    }

    @Test public void missingPersonCanConvergeLocalFaceAndDeletionState() {
        assertTrue(FaceResultPolicy.isAbsentOrDeleted(-6));
        assertTrue(FaceResultPolicy.isAbsentOrDeleted(0));
        assertEquals(0, FaceResultPolicy.verifiedUids(-6, null).length());
        assertFalse(FaceResultPolicy.isAbsentOrDeleted(40009));
        assertFalse(FaceResultPolicy.isAbsentOrDeleted(-3));
    }

    @Test public void verifiedUidsRetainLongIdentity() throws Exception {
        long uid = 2097500510197125121L;
        JSONArray result = FaceResultPolicy.verifiedUids(0, new JSONObject().put("uids", new JSONArray().put(uid)));
        assertEquals(uid, result.getLong(0));
    }

    @Test public void itemsKeepExactInt64AndOverrideUnrelatedLegacyFields() throws Exception {
        long uid = 2097500510197125121L;
        JSONObject data = new JSONObject("{\"items\":[{\"uid\":" + uid
                + ",\"image_available\":true,\"image_url\":\"http://localhost/actual.jpg\",\"mime_type\":\"image/jpeg\"}]}");
        data.put("uids", new JSONArray().put(88));
        data.put("samples", new JSONArray().put(image(88, true, "http://localhost/wrong.jpg")));
        FaceResultPolicy.VerifiedSamples samples = FaceResultPolicy.verifiedSamples(0, data);
        assertEquals(1, samples.uids.length());
        assertEquals(uid, samples.uids.getLong(0));
        assertEquals("http://localhost/actual.jpg", samples.urls.get(uid));
        assertFalse(samples.urls.containsKey(88L));
    }

    @Test public void unavailableItemKeepsUidWithoutLoadingResidualUrl() throws Exception {
        JSONObject data = new JSONObject().put("items", new JSONArray()
                .put(image(5, false, "http://localhost/must-not-load.jpg"))
                .put(image(90, true, "http://localhost/90.jpg")));
        FaceResultPolicy.VerifiedSamples samples = FaceResultPolicy.verifiedSamples(0, data);
        assertEquals(5, samples.uids.getLong(0));
        assertEquals(90, samples.uids.getLong(1));
        assertEquals("", samples.urls.get(5L));
        assertEquals("http://localhost/90.jpg", samples.urls.get(90L));
    }

    @Test public void emptyItemsAreAuthoritativeEvenWhenLegacyStillHasSamples() throws Exception {
        JSONObject data = new JSONObject().put("items", new JSONArray())
                .put("uids", new JSONArray().put(12));
        FaceResultPolicy.VerifiedSamples samples = FaceResultPolicy.verifiedSamples(0, data);
        assertEquals(0, samples.uids.length());
        assertTrue(samples.urls.isEmpty());
    }

    @Test public void malformedItemsCannotFallBackToValidLegacyUids() throws Exception {
        Object[] bad = {JSONObject.NULL, new JSONObject(), "[]", new JSONArray().put(5),
                new JSONArray().put(JSONObject.NULL), new JSONArray().put(image(5, true, "http://localhost/5.jpg"))
                        .put(image(5, true, "http://localhost/other.jpg"))};
        for (Object items : bad) {
            JSONObject data = new JSONObject().put("items", items).put("uids", new JSONArray().put(99));
            assertThrows(IllegalArgumentException.class, () -> FaceResultPolicy.verifiedSamples(0, data));
        }
    }

    @Test public void malformedItemFieldsCannotModifyConfirmedSamples() throws Exception {
        for (Object uid : new Object[]{"12", 0, -1, 1.5, 2097500510197125121d,
                Long.toUnsignedString(Long.MIN_VALUE), JSONObject.NULL}) {
            JSONObject data = new JSONObject().put("items", new JSONArray().put(image(uid, true, "http://localhost/5.jpg")));
            assertThrows(IllegalArgumentException.class, () -> FaceResultPolicy.verifiedSamples(0, data));
        }
        JSONObject[] badImages = {image(5, true, ""), image(5, true, "http://localhost/5.jpg").put("image_available", "true"),
                image(5, true, "http://localhost/5.jpg").put("mime_type", 2),
                image(5, false, "").put("image_url", JSONObject.NULL), new JSONObject().put("uid", 5)};
        for (JSONObject item : badImages) {
            JSONObject data = new JSONObject().put("items", new JSONArray().put(item)).put("uids", new JSONArray().put(99));
            assertThrows(IllegalArgumentException.class, () -> FaceResultPolicy.verifiedSamples(0, data));
        }
    }

    @Test public void legacyPhotosStillJoinByUidOnlyWhenItemsAreAbsent() throws Exception {
        JSONObject data = new JSONObject().put("uids", new JSONArray().put(5).put(90))
                .put("samples", new JSONArray().put(image(90, true, "http://localhost/90.jpg"))
                        .put(image(300, true, "http://localhost/unrelated.jpg")));
        FaceResultPolicy.VerifiedSamples samples = FaceResultPolicy.verifiedSamples(0, data);
        assertEquals(2, samples.uids.length());
        assertEquals("", samples.urls.get(5L));
        assertEquals("http://localhost/90.jpg", samples.urls.get(90L));
        assertFalse(samples.urls.containsKey(300L));
    }

    @Test public void longMaxItemsKeepExactIdentityWhileOverflowIsRejected() throws Exception {
        JSONObject data = new JSONObject("{\"items\":[{\"uid\":9223372036854775807,\"image_available\":false,\"image_url\":\"\",\"mime_type\":\"\"}]}");
        assertEquals(Long.MAX_VALUE, FaceResultPolicy.verifiedSamples(0, data).uids.getLong(0));
        JSONObject overflow = new JSONObject("{\"items\":[{\"uid\":9223372036854775808,\"image_available\":false,\"image_url\":\"\",\"mime_type\":\"\"}]}");
        assertThrows(IllegalArgumentException.class, () -> FaceResultPolicy.verifiedSamples(0, overflow));
    }

    private static JSONObject image(Object uid, boolean available, String url) throws Exception {
        return new JSONObject().put("uid", uid).put("image_available", available)
                .put("image_url", url).put("mime_type", available ? "image/jpeg" : "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void transportFailureCannotClearExistingFaceState() {
        FaceResultPolicy.verifiedUids(-20001, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedUidsCannotClearExistingFaceState() throws Exception {
        FaceResultPolicy.verifiedUids(0, new JSONObject().put("uids", new JSONArray().put("12")));
    }
}
