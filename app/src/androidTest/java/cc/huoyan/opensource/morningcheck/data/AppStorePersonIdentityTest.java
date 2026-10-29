package cc.huoyan.opensource.morningcheck.data;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

/** 人员身份与软删除仅使用隔离数据库，不调用 SDK 或修改设备业务名册。 */
@RunWith(AndroidJUnit4.class)
public final class AppStorePersonIdentityTest {
    private Context context;
    private AppStore store;
    private String databaseName;

    @Before public void openStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "person-identity-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void closeStore() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void personIdsAdvanceIndependentlyOfLocalIdsAndSurviveReopenAndNumberChanges() {
        JSONObject imported = Jsons.obj("id", 8000L, "name", "外部员工", "staff_no", "A001", "person_id", 70000L);
        store.saveStaff(imported);
        JSONObject first = store.staff(8000);
        assertEquals(1L, first.optLong("person_id"));
        assertEquals("分配不得修改调用者快照", 70000L, imported.optLong("person_id"));
        store.patchStaffProfile(8000, first, Jsons.obj("staff_no", "NEW001", "name", "新姓名"));
        store.saveStaff(Jsons.obj("id", 8000L, "name", "重建资料快照", "staff_no", "NEW001"));
        reopen();
        long second = store.createStaffProfile(profile("A002"));
        assertEquals(8001L, second);
        assertEquals(2L, store.staff(second).optLong("person_id"));
        assertEquals(1L, store.staff(8000).optLong("person_id"));
        assertEquals(8000L, store.staffByPersonId(1).optLong("id"));
        assertNull("不能把本地主键误当成 SDK 标识回查", store.staffByPersonId(8000));
    }

    @Test public void cloudUpdatesPreserveAssignedPersonIdAndExplicitReassignmentIsRejected() {
        long localId = store.createStaffProfile(profile("S001"));
        long personId = store.staff(localId).optLong("person_id");
        JSONObject remote = Jsons.obj("id", localId, "name", "云端姓名", "staff_no", "C001",
                "person_id", 9000L, "health_cert_expire_time", "2030-10-10");
        store.saveStaff(StaffPolicy.fromCloud(store.staff(localId), remote, localId, 100L));
        assertEquals(personId, store.staff(localId).optLong("person_id"));
        assertEquals("C001", store.staff(localId).optString("staff_no"));
        JSONObject replaced = Jsons.copy(store.staff(localId));
        Jsons.put(replaced, "person_id", personId + 100);
        expectFailure("人员标识不可修改", () -> store.saveStaff(replaced));
        expectFailure("人员标识不可修改", () -> store.patchStaff(localId, Jsons.obj("person_id", personId + 100)));
        expectFailure("字段无效", () -> store.patchStaffProfile(localId, store.staff(localId), Jsons.obj("person_id", personId + 100)));
        assertEquals(personId, store.staff(localId).optLong("person_id"));
    }

    @Test public void deletedStaffRetainIdentityButLeaveEveryBusinessRosterQuery() {
        long department = store.saveDepartment(0, "已删除人员部门");
        long deleted = store.createStaffProfile(Jsons.obj("name", "待删除员工", "staff_no", "OLD001",
                "department_id", department, "enabled", false));
        long retained = store.createStaffProfile(profile("KEEP001"));
        long personId = store.staff(deleted).optLong("person_id");
        store.patchStaff(deleted, Jsons.obj("face_registered", true, "face_uids", new JSONArray().put(901L)));
        store.saveRecord(record("old", deleted, 0));
        store.saveRecord(record("retained", retained, 1));
        store.removeStaff(deleted);
        assertNull(store.staff(deleted));
        assertNull(store.staffByPersonId(personId));
        assertEquals(1L, store.staffCount());
        assertEquals(1, store.staff().size());
        assertEquals(0L, store.staffPage("OLD001", 1, 20).total);
        assertEquals(1L, store.staffPage("", 1, 20).totalRows);
        assertEquals(0L, store.staffPage("", 2, department, 1, 20).total);
        assertEquals(1L, store.staffPage("", 0, 0, 1, 20).totalRows);
        assertEquals(0L, store.department(department).optLong("member_count"));
        assertEquals(0L, store.departmentsPage("已删除人员部门", 1, 20).rows.get(0).optLong("member_count"));
        AppStore.HomeSummary summary = store.homeSummary(100, 200);
        assertEquals(1L, summary.staffCount);
        assertEquals(1L, summary.checkedTodayCount);
        assertEquals(0L, summary.abnormalTodayCount);
        assertNotNull("删除员工不能删除历史记录", store.record("old"));
        assertEquals(2L, store.recordsPage(0, 1, 20).total);
        assertEquals(1L, rawLong("deleted", deleted));
        assertEquals(personId, rawLong("person_id", deleted));
        reopen();
        long replacement = store.createStaffProfile(profile("OLD001"));
        assertTrue(replacement > deleted);
        assertTrue(store.staff(replacement).optLong("person_id") > personId);
        assertNull(store.staffByPersonId(personId));
    }

    @Test public void tombstoneCannotBeRevivedByLateFaceCallbacksCloudImportOrRecreatedProfile() {
        long id = store.createStaffProfile(profile("S001"));
        JSONObject stale = store.staff(id);
        store.removeStaff(id);
        expectFailure("已删除", () -> store.patchStaff(id, Jsons.obj("face_registered", true, "face_uid", 901L)));
        expectFailure("已删除", () -> store.patchStaffProfile(id, stale, Jsons.obj("name", "迟到编辑")));
        expectFailure("已删除", () -> store.saveStaff(stale));
        expectFailure("已删除", () -> store.createStaff(Jsons.copy(stale)));
        JSONObject remote = Jsons.obj("id", id, "name", "旧云端行", "staff_no", "S001");
        expectFailure("已删除", () -> store.saveStaff(StaffPolicy.fromCloud(null, remote, id, 100L)));
        store.removeStaff(id);
        assertEquals(0L, store.staffCount());
        assertEquals(1L, rawLong("deleted", id));
        assertEquals(stale.optLong("person_id"), rawLong("person_id", id));
    }

    @Test public void deletingOnlyMemberAllowsDepartmentRemovalWithoutTouchingTombstone() {
        long department = store.saveDepartment(0, "可删除部门");
        long id = store.createStaffProfile(Jsons.obj("name", "旧员工", "staff_no", "S001", "department_id", department));
        long personId = store.staff(id).optLong("person_id");
        store.removeStaff(id);
        store.removeDepartment(department);
        assertNull(store.department(department));
        assertEquals(personId, rawLong("person_id", id));
        assertNull(store.staffByPersonId(personId));
    }

    @Test public void ambiguousPersonIdNeverSelectsFirstLiveOrDeletedEmployee() {
        long first = store.createStaffProfile(profile("A001"));
        long second = store.createStaffProfile(profile("A002"));
        long personId = store.staff(first).optLong("person_id");
        ContentValues corrupt = new ContentValues();
        corrupt.put("person_id", personId);
        store.getWritableDatabase().update("staff", corrupt, "id=?", new String[]{String.valueOf(second)});
        expectFailure("对应多个员工", () -> store.staffByPersonId(personId));
        store.removeStaff(second);
        expectFailure("对应多个员工", () -> store.staffByPersonId(personId));
        assertNull(store.staffByPersonId(0));
        assertNull(store.staffByPersonId(-1));
    }

    @Test public void failedWriteRollsBackBothIdentityCountersAndExhaustionDoesNotWrap() {
        long first = store.createStaffProfile(profile("A001"));
        long firstPerson = store.staff(first).optLong("person_id");
        expectFailure("工号", () -> store.createStaffProfile(profile("A001")));
        long second = store.createStaffProfile(profile("A002"));
        assertEquals(first + 1, second);
        assertEquals(firstPerson + 1, store.staff(second).optLong("person_id"));
        store.getWritableDatabase().execSQL("UPDATE staff_person_sequence SET value=? WHERE singleton=1", new Object[]{Long.MAX_VALUE});
        expectFailure("达到上限", () -> store.createStaffProfile(profile("A003")));
        assertNull(store.staff(second + 1));
        assertEquals(2L, store.staffCount());
    }

    @Test public void versionFiveMigrationKeepsOriginalSdkIdsAndReservesPreviouslyAllocatedRange() {
        JSONObject original = Jsons.obj("id", 42L, "person_id", 9999L, "name", "旧员工", "staff_no", "OLD042",
                "face_registered", true, "face_uids", new JSONArray().put(905L), "created_at", 100L);
        store.close();
        try (SQLiteDatabase previous = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            createVersionFiveTables(previous);
            insertLegacy(previous, original);
            previous.execSQL("INSERT INTO staff_id_sequence(singleton,value) VALUES(1,500)");
            previous.setVersion(5);
        }
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(42L, store.staff(42).optLong("person_id"));
        assertEquals(42L, store.staffByPersonId(42).optLong("id"));
        assertEquals(905L, store.staff(42).optJSONArray("face_uids").optLong(0));
        assertEquals("迁移不得重写原始业务 JSON", original.toString(), rawData(42));
        assertFalse(store.staff(42).optBoolean("deleted"));
        long created = store.createStaffProfile(profile("NEW501"));
        assertEquals(501L, created);
        assertEquals(501L, store.staff(created).optLong("person_id"));
        reopen();
        assertEquals(42L, store.staff(42).optLong("person_id"));
        assertEquals(502L, store.staff(store.createStaffProfile(profile("NEW502"))).optLong("person_id"));
    }

    @Test public void migratedInt64PersonIdRemainsExactAboveDoubleIntegerPrecision() {
        long exact = 9_007_199_254_740_993L;
        store.close();
        try (SQLiteDatabase previous = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            createVersionFiveTables(previous);
            insertLegacy(previous, Jsons.obj("id", exact, "name", "大编号员工", "staff_no", "BIG"));
            previous.setVersion(5);
        }
        store = new AppStore(context, databaseName);
        assertEquals(exact, store.staff(exact).optLong("person_id"));
        assertEquals(exact, store.staffByPersonId(exact).optLong("id"));
        long created = store.createStaffProfile(profile("NEXT"));
        assertEquals(exact + 1, created);
        assertEquals(exact + 1, store.staff(created).optLong("person_id"));
    }

    private void reopen() {
        store.close();
        store = new AppStore(context, databaseName);
    }

    private static JSONObject profile(String number) {
        return Jsons.obj("name", "测试员工", "staff_no", number, "department_id", 0);
    }

    private static JSONObject record(String id, long staff, int status) {
        return Jsons.obj("id", id, "staff_id", staff, "staff_name", "历史快照", "created_at", 150L, "status", status);
    }

    private long rawLong(String column, long id) {
        try (Cursor row = store.getReadableDatabase().query("staff", new String[]{column}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            assertTrue(row.moveToFirst());
            return row.getLong(0);
        }
    }

    private String rawData(long id) {
        try (Cursor row = store.getReadableDatabase().query("staff", new String[]{"data"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            assertTrue(row.moveToFirst());
            return row.getString(0);
        }
    }

    private static void createVersionFiveTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE staff (id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,name TEXT NOT NULL DEFAULT '',department TEXT NOT NULL DEFAULT '',staff_no TEXT NOT NULL DEFAULT '',enabled INTEGER NOT NULL DEFAULT 1,department_id INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,status INTEGER NOT NULL DEFAULT -1,upload TEXT NOT NULL DEFAULT '',staff_id TEXT NOT NULL DEFAULT '',staff_name TEXT NOT NULL DEFAULT '',staff_no TEXT NOT NULL DEFAULT '',department_id INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE departments (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,time INTEGER NOT NULL,parent_id INTEGER NOT NULL DEFAULT 0,path TEXT NOT NULL DEFAULT '',UNIQUE(parent_id,name))");
        db.execSQL("CREATE TABLE staff_id_sequence (singleton INTEGER PRIMARY KEY CHECK(singleton=1),value INTEGER NOT NULL)");
    }

    private static void insertLegacy(SQLiteDatabase db, JSONObject person) {
        ContentValues values = new ContentValues();
        values.put("id", person.optString("id"));
        values.put("data", person.toString());
        values.put("name", person.optString("name"));
        values.put("staff_no", person.optString("staff_no"));
        values.put("time", person.optLong("created_at", 100L));
        db.insertOrThrow("staff", null, values);
    }

    private static void expectFailure(String message, Runnable operation) {
        try { operation.run(); fail("操作应被拒绝：" + message); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
