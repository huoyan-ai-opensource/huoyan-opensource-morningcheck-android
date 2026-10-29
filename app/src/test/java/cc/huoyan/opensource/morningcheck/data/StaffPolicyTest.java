package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class StaffPolicyTest {
    @Test public void staffNumberTrimsWhitespaceAndPreservesLeadingZeroes() {
        assertEquals("0012", StaffPolicy.requireStaffNumber(" 0012\t"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankStaffNumberIsRejected() { StaffPolicy.requireStaffNumber(" \t "); }

    @Test(expected = IllegalArgumentException.class)
    public void missingStaffNumberIsRejected() { StaffPolicy.requireStaffNumber(null); }

    private JSONObject remote() {
        return Jsons.obj("id", "7", "name", "张三", "department", "后厨", "staff_no", "A07",
                "status", 1, "avatar", "https://example.test/7.jpg", "health_cert_expire_time", "2026-09-29");
    }

    @Test public void cloudImportNeverPretendsSdkFaceIsRegistered() {
        JSONObject result = StaffPolicy.fromCloud(null, remote(), 7, 10);
        assertFalse(result.optBoolean("face_registered"));
        assertEquals("A07", result.optString("staff_no"));
        assertEquals("2026-09-29", result.optString("health_expiry"));
    }

    @Test public void cloudRefreshPreservesLocalDisableAndFaceRegistration() {
        JSONObject existing = Jsons.obj("id", 7, "enabled", false, "face_registered", true, "face_uid", 91);
        JSONObject result = StaffPolicy.fromCloud(existing, remote(), 7, 10);
        assertFalse(result.optBoolean("enabled"));
        assertTrue(result.optBoolean("face_registered"));
        assertEquals(91, result.optLong("face_uid"));
    }

    @Test public void cloudDepartmentRefreshDropsPreviousLocalDepartmentId() {
        JSONObject existing = Jsons.obj("id", 7, "department", "旧部门", "department_id", 88);
        JSONObject result = StaffPolicy.fromCloud(existing, remote(), 7, 10);
        assertEquals("后厨", result.optString("department"));
        assertFalse("归属应重新按云端部门解析，不能被旧本地ID覆盖", result.has("department_id"));
    }

    @Test public void removedOrMalformedCertificateClearsOldValidDate() {
        for (Object value : new Object[]{JSONObject.NULL, "", "not-a-date", "2026-02-30"}) {
            JSONObject existing = Jsons.obj("id", 7, "health_expiry", "2099-01-01");
            JSONObject changed = remote();
            Jsons.put(changed, "health_cert_expire_time", value);
            assertEquals("", StaffPolicy.fromCloud(existing, changed, 7, 10).optString("health_expiry"));
        }
        JSONObject omitted = remote();omitted.remove("health_cert_expire_time");
        assertEquals("", StaffPolicy.fromCloud(Jsons.obj("id", 7, "health_expiry", "2099-01-01"), omitted, 7, 10).optString("health_expiry"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void detailMustMatchRequestedPerson() {
        StaffPolicy.fromCloud(null, remote(), 8, 10);
    }

    @Test public void paginationUsesOriginalRecordsAndPagesContract() {
        JSONObject response = Jsons.obj("code", 0, "data", Jsons.obj("pages", 2, "current", 1,
                "records", new JSONArray().put(remote())));
        assertEquals(2, StaffPolicy.pageCount(response));
        assertEquals(1, StaffPolicy.pageRecords(response).length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownPaginationShapeIsNotAssumedToBeACompleteImport() {
        StaffPolicy.pageRecords(Jsons.obj("code", 0, "data", new JSONArray().put(remote())));
    }

    @Test public void lateFaceCallbackOnlyPatchesFaceFields() {
        JSONObject latest = Jsons.obj("id", 7, "name", "已修改姓名", "health_expiry", "2027-01-01", "enabled", false);
        JSONObject updated = Jsons.patch(latest, Jsons.obj("face_registered", true, "face_uid", 22));
        assertEquals("已修改姓名", updated.optString("name"));
        assertEquals("2027-01-01", updated.optString("health_expiry"));
        assertFalse(updated.optBoolean("enabled"));
    }

    @Test(expected = IllegalStateException.class)
    public void lateCallbackCannotRecreateDeletedStaff() {
        Jsons.patch(null, Jsons.obj("face_registered", true));
    }
}
