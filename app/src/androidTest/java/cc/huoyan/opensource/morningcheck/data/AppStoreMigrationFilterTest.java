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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 迁移和层级筛选仅使用独立数据库，不触碰真机业务库或云端接口。 */
@RunWith(AndroidJUnit4.class)
public final class AppStoreMigrationFilterTest {
    private Context context;
    private String databaseName;
    private AppStore store;

    @Before public void createSeparateDatabase() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "department-filter-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void removeOwnDatabase() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void versionFourMigrationPreservesRawRowsAndUsesRecordDepartmentBeforeCurrentStaff() {
        JSONObject oldStaff = Jsons.obj("id", 42, "name", "新姓名", "staff_no", "N42", "department", "前厅",
                "enabled", false, "face_uids", new JSONArray().put(90042L), "created_at", 123L);
        JSONObject snapshot = Jsons.obj("id", "snapshot", "staff_id", 42, "staff_name", "旧姓名", "staff_no", "OLD42",
                "department", "后厨", "status", 0, "upload", "已上传", "created_at", 321L);
        JSONObject fallback = Jsons.obj("id", "fallback", "staff_id", 42, "status", 1, "created_at", 322L);
        store.close();
        try (SQLiteDatabase old = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            createVersionFourTables(old);
            old.execSQL("INSERT INTO departments(id,name,time) VALUES(10,'后厨',100),(20,'前厅',100),(90,'已删除部门',100)");
            old.execSQL("DELETE FROM departments WHERE id=90");
            insertRaw(old, "staff", "42", oldStaff.toString(), 456L);
            insertRaw(old, "staff", "99", "not-json", 789L);
            insertRaw(old, "records", "snapshot", snapshot.toString(), 654L);
            insertRaw(old, "records", "fallback", fallback.toString(), 655L);
            old.setVersion(4);
        }
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(oldStaff.toString(), rawData("staff", "42"));
        assertEquals(snapshot.toString(), rawData("records", "snapshot"));
        assertEquals("not-json", rawData("staff", "99"));
        assertEquals(456L, queryLong("staff", "time", "42"));
        assertEquals(654L, queryLong("records", "time", "snapshot"));
        assertEquals(90042L, store.staff(42).optJSONArray("face_uids").optLong(0));
        assertEquals(20L, store.staff(42).optLong("department_id"));
        assertEquals(1L, store.staffPage("N42", 2, 20, 1, 20).total);
        assertEquals(1L, store.recordsPage("旧姓名", 2, 10, 1, 20).total);
        assertEquals(0L, store.recordsPage("旧姓名", 2, 20, 1, 20).total);
        assertEquals(1L, store.recordsPage("新姓名", 1, 20, 1, 20).total);
        assertTrue(store.saveDepartment(0, "新部门") > 90L);
        assertEquals(100L, store.createStaffProfile(Jsons.obj("name", "新员工", "staff_no", "NEW100", "department_id", 0)));
        store.close();
        store = new AppStore(context, databaseName);
        assertEquals(3L, store.staffCount());
        assertEquals(snapshot.toString(), rawData("records", "snapshot"));
    }

    @Test public void hierarchyAllowsSiblingNamesAcrossParentsAndRejectsCyclesAndOrphans() {
        long first = store.saveDepartment(0, "第一门店");
        long second = store.saveDepartment(0, "第二门店");
        long kitchen1 = saveDepartment("后厨", first);
        long kitchen2 = saveDepartment("后厨", second);
        long child = saveDepartment("热厨", kitchen1);
        assertEquals("第一门店 / 后厨 / 热厨", store.department(child).optString("path"));
        assertEquals(1L, store.departmentsPage("", kitchen1, 1, 20).total);
        // 全树路径搜索也命中“第一门店 / 后厨 / 热厨”，不能只数名字叫后厨的两项。
        assertEquals(3L, store.departmentsPage("后厨", 0, 1, 20).total);
        expectFailure("已存在", () -> saveDepartment("后厨", first));
        expectFailure("自身或下级", () -> store.saveDepartment(first, "第一门店", child, "第一门店", 0));
        expectFailure("不存在", () -> saveDepartment("无上级", 9999));
        expectFailure("下级部门", () -> store.removeDepartment(kitchen1));
        Set<Long> excluded = new HashSet<>();
        for (JSONObject option : store.departmentOptions(kitchen1)) excluded.add(option.optLong("id"));
        assertFalse(excluded.contains(kitchen1));
        assertFalse(excluded.contains(child));
        assertTrue(excluded.contains(kitchen2));
        store.saveStaff(person(1, child, true));
        expectFailure("1 名员工", () -> store.removeDepartment(child));
        store.saveRecord(record("kept", store.staff(1), 1, 100));
        store.patchStaff(1, Jsons.obj("department_id", 0, "department", ""));
        expectFailure("历史晨检记录", () -> store.removeDepartment(child));
        store.saveDepartment(kitchen1, "厨房", second, "后厨", first);
        assertEquals("第二门店 / 厨房 / 热厨", store.department(child).optString("path"));
        expectFailure("上级部门已变更", () -> store.saveDepartment(kitchen1, "旧草稿", second, "厨房", first));
        expectFailure("上级部门已变更", () -> store.removeDepartment(kitchen1, "厨房", first));
    }

    @Test public void versionFourMigrationDoesNotAssignRemovedDepartmentSnapshotToCurrentDepartment() {
        JSONObject currentStaff = Jsons.obj("id", 42, "name", "已调部门员工", "staff_no", "N42", "department", "B部门");
        JSONObject former = Jsons.obj("id", "former", "staff_id", 42, "staff_name", "历史姓名", "staff_no", "OLD42",
                "department", "已不存在的A部门", "status", 0, "created_at", 100L);
        JSONObject missing = Jsons.obj("id", "missing", "staff_id", 42, "status", 1, "created_at", 101L);
        JSONObject nullable = Jsons.obj("id", "nullable", "staff_id", 42, "department", JSONObject.NULL, "status", 1, "created_at", 102L);
        JSONObject blank = Jsons.obj("id", "blank", "staff_id", 42, "department", "", "status", 1, "created_at", 103L);
        store.close();
        try (SQLiteDatabase old = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            createVersionFourTables(old);
            old.execSQL("INSERT INTO departments(id,name,time) VALUES(20,'B部门',100)");
            insertRaw(old, "staff", "42", currentStaff.toString(), 100L);
            for (JSONObject record : new JSONObject[]{former, missing, nullable, blank})
                insertRaw(old, "records", record.optString("id"), record.toString(), record.optLong("created_at"));
            old.setVersion(4);
        }
        store = new AppStore(context, databaseName);
        assertEquals(20L, store.staff(42).optLong("department_id"));
        assertEquals(0L, queryLong("records", "department_id", "former"));
        assertEquals(0L, queryLong("records", "department_id", "blank"));
        assertEquals(20L, queryLong("records", "department_id", "missing"));
        assertEquals(20L, queryLong("records", "department_id", "nullable"));
        assertEquals(2L, store.recordsPage("", 0, 20, 1, 20).total);
        assertEquals(2L, store.recordsPage("", 0, -1, 1, 20).total);
        assertEquals(0L, store.recordsPage("历史姓名", 2, 20, 1, 20).total);
        assertEquals(former.toString(), rawData("records", "former"));
        assertEquals(blank.toString(), rawData("records", "blank"));
        store.patchRecord("former", Jsons.obj("upload", "已上传"));
        assertEquals(0L, queryLong("records", "department_id", "former"));
        assertEquals("已不存在的A部门", store.record("former").optString("department"));
    }

    @Test public void combinedFiltersCountAndPageSubtreesWithoutDuplicateRows() {
        long root = store.saveDepartment(0, "门店");
        long child = saveDepartment("后厨", root);
        long grandchild = saveDepartment("热厨", child);
        long other = store.saveDepartment(0, "前厅");
        for (int i = 1; i <= 67; i++) {
            long department = i <= 63 ? (i % 3 == 0 ? root : i % 3 == 1 ? child : grandchild) : other;
            store.saveStaff(person(i, department, i % 2 == 0));
            store.saveRecord(record("R" + i, store.staff(i), i % 2 == 0 ? 1 : 0, i));
        }
        assertEquals(31L, store.staffPage("筛选", 1, root, 1, 20).total);
        assertEquals(32L, store.recordsPage("筛选", 2, root, 1, 20).total);
        assertEquals(67L, store.recordsPage("筛选", 0, 0, 1, 20).totalRows);
        Set<String> ids = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            AppStore.DataPage result = store.recordsPage("筛选", 2, root, page, 20);
            for (JSONObject row : result.rows) assertTrue(ids.add(row.optString("id")));
        }
        assertEquals(32, ids.size());
        assertEquals(2, store.recordsPage("筛选", 2, root, 99, 20).page);
        assertEquals(1L, store.staffPage("S005", 0, root, 1, 20).total);
        assertEquals(0L, store.staffPage("门店", 0, 0, 1, 20).total);
        store.saveStaff(Jsons.obj("id", 100, "name", "%_字面", "staff_no", "P100", "department_id", 0));
        assertEquals(1L, store.staffPage("%_", 0, -1, 1, 20).total);
        assertEquals(0L, store.staffPage("%_", 0, root, 1, 20).total);
    }

    @Test public void renameAndMoveUpdateStaffPathsButKeepRecordSnapshotsAndStableDepartmentFilter() {
        long original = store.saveDepartment(0, "原门店");
        long destination = store.saveDepartment(0, "新门店");
        long kitchen = saveDepartment("后厨", original);
        store.saveStaff(person(1, kitchen, true));
        JSONObject before = store.staff(1);
        JSONObject explicit = record("explicit", before, 1, 100);
        JSONObject legacy = record("legacy", before, 0, 101);
        legacy.remove("department_id");
        store.saveRecord(explicit);
        store.saveRecord(legacy);
        store.saveDepartment(kitchen, "热厨", destination, "后厨", original);
        assertEquals("新门店 / 热厨", store.staff(1).optString("department"));
        assertEquals(explicit.toString(), store.record("explicit").toString());
        assertEquals(legacy.toString(), store.record("legacy").toString());
        store.patchRecord("legacy", Jsons.obj("upload", "已上传"));
        assertEquals(2L, store.recordsPage("", 0, kitchen, 1, 20).total);
        assertEquals(0L, store.recordsPage("", 0, original, 1, 20).total);
        assertEquals(2L, store.recordsPage("", 0, destination, 1, 20).total);
        expectFailure("已被更新", () -> store.patchStaffProfile(1, before, Jsons.obj("department", "原门店 / 后厨", "department_id", kitchen)));
    }

    @Test public void ambiguousCloudNamesStayUnassignedAndExplicitUnassignedRecordsNeverFollowStaff() {
        long root1 = store.saveDepartment(0, "一店"), root2 = store.saveDepartment(0, "二店");
        long child1 = saveDepartment("后厨", root1), child2 = saveDepartment("后厨", root2);
        store.saveStaff(Jsons.obj("id", 1, "name", "云员工", "staff_no", "C001", "department", "后厨"));
        assertEquals(0L, store.staff(1).optLong("department_id"));
        assertEquals("后厨", store.staff(1).optString("department"));
        assertEquals(0L, store.staffPage("", 0, child1, 1, 20).total);
        assertEquals(0L, store.staffPage("", 0, child2, 1, 20).total);
        store.patchStaff(1, Jsons.obj("face_registered", true));
        assertEquals("后厨", store.staff(1).optString("department"));
        JSONObject unassigned = record("unassigned", store.staff(1), 1, 100);
        store.saveRecord(unassigned);
        store.patchStaff(1, Jsons.obj("department_id", child1, "department", "一店 / 后厨"));
        store.patchRecord("unassigned", Jsons.obj("upload", "已上传"));
        assertEquals(1L, store.recordsPage("", 0, -1, 1, 20).total);
        assertEquals(0L, store.recordsPage("", 0, child1, 1, 20).total);
        JSONObject legacyBlank = record("legacy-blank", store.staff(1), 1, 101);
        legacyBlank.remove("department_id");
        Jsons.put(legacyBlank, "department", "");
        store.saveRecord(legacyBlank);
        assertEquals(2L, store.recordsPage("", 0, -1, 1, 20).total);
        store.patchStaff(1, Jsons.obj("department_id", child2, "department", "二店 / 后厨"));
        store.patchRecord("legacy-blank", Jsons.obj("upload", "已上传"));
        assertEquals(2L, store.recordsPage("", 0, -1, 1, 20).total);
        assertEquals(0L, store.recordsPage("", 0, child2, 1, 20).total);
        assertEquals("", store.record("legacy-blank").optString("department"));
        store.saveStaff(Jsons.obj("id", 2, "name", "完整路径", "staff_no", "C002", "department", "二店 / 后厨"));
        assertEquals(child2, store.staff(2).optLong("department_id"));
    }

    @Test public void subtreeFilterSupportsMoreThanSqliteBindLimit() {
        long root = store.saveDepartment(0, "大部门");
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < 1001; i++) {
                ContentValues department = new ContentValues();
                department.put("name", "子部门" + i); department.put("parent_id", root);
                department.put("path", "大部门 / 子部门" + i); department.put("time", i);
                db.insertOrThrow("departments", null, department);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        long last = store.departmentOptions(0).get(1000).optLong("id");
        store.saveStaff(person(1, last, true));
        store.saveRecord(record("one", store.staff(1), 1, 100));
        assertEquals(1L, store.staffPage("", 1, root, 1, 20).total);
        assertEquals(1L, store.recordsPage("", 1, root, 1, 20).total);
    }

    private long saveDepartment(String name, long parent) { return store.saveDepartment(0, name, parent, null, 0); }

    private static JSONObject person(long id, long department, boolean enabled) {
        return Jsons.obj("id", id, "name", "筛选员工" + id, "staff_no", String.format(java.util.Locale.US, "S%03d", id),
                "department_id", department, "enabled", enabled, "created_at", id);
    }

    private static JSONObject record(String id, JSONObject person, int result, long time) {
        return Jsons.obj("id", id, "staff_id", person.optLong("id"), "staff_name", person.optString("name"),
                "staff_no", person.optString("staff_no"), "department_id", person.optLong("department_id"),
                "department", person.optString("department"), "status", result, "upload", "未上传", "created_at", time);
    }

    private static void createVersionFourTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE staff(id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,name TEXT NOT NULL DEFAULT '',department TEXT NOT NULL DEFAULT '',staff_no TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,status INTEGER NOT NULL DEFAULT -1,upload TEXT NOT NULL DEFAULT '',staff_id TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE departments(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,time INTEGER NOT NULL)");
    }

    private static void insertRaw(SQLiteDatabase db, String table, String id, String raw, long time) {
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("data", raw); values.put("time", time);
        db.insertOrThrow(table, null, values);
    }

    private String rawData(String table, String id) {
        try (Cursor row = store.getReadableDatabase().query(table, new String[]{"data"}, "id=?", new String[]{id}, null, null, null)) {
            assertTrue(row.moveToFirst());
            return row.getString(0);
        }
    }

    private long queryLong(String table, String column, String id) {
        try (Cursor row = store.getReadableDatabase().query(table, new String[]{column}, "id=?", new String[]{id}, null, null, null)) {
            assertTrue(row.moveToFirst());
            return row.getLong(0);
        }
    }

    private static void expectFailure(String message, Runnable action) {
        try { action.run(); fail("应拒绝：" + message); }
        catch (IllegalArgumentException | IllegalStateException error) { assertTrue(error.getMessage(), error.getMessage().contains(message)); }
    }
}
