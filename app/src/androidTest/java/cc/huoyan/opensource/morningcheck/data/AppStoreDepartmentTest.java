package cc.huoyan.opensource.morningcheck.data;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
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

/** 部门管理、旧库迁移、成员保护与表单冲突全部使用独立数据库，不触碰设备名册或云端。 */
@RunWith(AndroidJUnit4.class)
public final class AppStoreDepartmentTest {
    private Context context;
    private String databaseName;
    private AppStore store;

    @Before public void createSeparateStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "department-test-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void removeOwnDatabase() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void staffImportCollectsUniqueDepartmentsAndCountsMembers() {
        store.saveStaff(person(1, "  后厨  "));
        store.saveStaff(person(2, "后厨"));
        store.saveStaff(person(3, "   "));
        assertEquals("后厨", store.staff(1).optString("department"));
        assertEquals("", store.staff(3).optString("department"));
        AppStore.DataPage page = store.departmentsPage("", 1, 20);
        assertEquals(1, page.rows.size());
        assertEquals(2L, page.rows.get(0).optLong("member_count"));
        assertEquals(3L, store.staffCount());
        store.patchStaff(2, Jsons.obj("department", "前厅"));
        assertEquals(1L, store.departmentsPage("后厨", 1, 20).rows.get(0).optLong("member_count"));
        assertEquals(1L, store.departmentsPage("前厅", 1, 20).rows.get(0).optLong("member_count"));
    }

    @Test public void departmentSearchPaginatesBeforeRenderingAndUsesLiteralKeywords() {
        for (int i = 0; i < 45; ++i) store.saveDepartment(0, "测试部门" + i);
        store.saveDepartment(0, "比例%_部门");
        AppStore.DataPage first = store.departmentsPage("测试", 1, 20);
        AppStore.DataPage second = store.departmentsPage("测试", 2, 20);
        AppStore.DataPage last = store.departmentsPage("测试", 3, 20);
        assertEquals(45L, first.total);
        assertEquals(46L, first.totalRows);
        assertEquals(20, first.rows.size());
        assertEquals(20, second.rows.size());
        assertEquals(5, last.rows.size());
        Set<Long> ids = new HashSet<>();
        for (AppStore.DataPage page : new AppStore.DataPage[]{first, second, last})
            for (JSONObject item : page.rows) assertTrue(ids.add(item.optLong("id")));
        assertEquals(45, ids.size());
        assertEquals(1L, store.departmentsPage("%_", 1, 20).total);
        for (JSONObject item : last.rows) store.removeDepartment(item.optLong("id"));
        assertEquals(2, store.departmentsPage("测试", 3, 20).page);
        assertEquals(20, store.departmentsPage("测试", 3, 20).rows.size());
        assertEquals(1, store.departmentsPage("不存在", 99, 20).page);
    }

    @Test public void renameUpdatesAllCurrentStaffAndPreservesFaceAndHistoricalSnapshots() {
        for (int i = 1; i <= 51; ++i) {
            JSONObject person = person(i, "后厨");
            Jsons.put(person, "face_registered", true);
            Jsons.put(person, "face_uids", new JSONArray().put(i * 10L));
            Jsons.put(person, "enabled", i % 2 == 0);
            store.saveStaff(person);
        }
        store.saveStaff(person(52, "前厅"));
        JSONObject historical = Jsons.obj("id", "old-record", "department", "后厨", "staff_id", 1L,
                "created_at", 100L, "upload", "已上传", "status", 1);
        store.saveRecord(historical);
        long id = store.departmentsPage("后厨", 1, 20).rows.get(0).optLong("id");
        store.saveDepartment(id, "热厨", "后厨");
        assertEquals(0L, store.staffPage("后厨", 1, 20).total);
        assertEquals(51L, store.staffPage("热厨", 1, 20).total);
        assertEquals(51L, store.department(id).optLong("member_count"));
        assertEquals("热厨", store.department(id).optString("name"));
        assertEquals("后厨", store.record("old-record").optString("department"));
        assertEquals(historical.toString(), store.record("old-record").toString());
        for (int i = 1; i <= 51; ++i) {
            JSONObject person = store.staff(i);
            assertEquals("热厨", person.optString("department"));
            assertEquals(true, person.optBoolean("face_registered"));
            assertEquals(i * 10L, person.optJSONArray("face_uids").optLong(0));
            assertEquals(i % 2 == 0, person.optBoolean("enabled"));
        }
        assertEquals("前厅", store.staff(52).optString("department"));
    }

    @Test public void departmentDeletionChecksLiveMembersAndNamesAreTrimUnique() {
        long occupied = store.saveDepartment(0, "厨房");
        long empty = store.saveDepartment(0, "空部门");
        expectFailure("已存在", () -> store.saveDepartment(0, "  厨房  "));
        expectFailure("请输入部门", () -> store.saveDepartment(0, " \t "));
        store.saveStaff(person(1, "厨房"));
        expectFailure("1 名员工", () -> store.removeDepartment(occupied));
        assertNotNull(store.department(occupied));
        assertNotNull(store.staff(1));
        store.removeDepartment(empty);
        assertNull(store.department(empty));
        store.patchStaff(1, Jsons.obj("department", ""));
        store.removeDepartment(occupied);
        assertNull(store.department(occupied));
        assertNotNull(store.staff(1));
    }

    @Test public void editingStaffMergesNewFaceResultsButRejectsChangedProfile() {
        store.saveStaff(person(1, "后厨"));
        JSONObject expected = store.staff(1);
        store.patchStaff(1, Jsons.obj("face_registered", true, "face_uids", new JSONArray().put(901L), "enabled", false));
        store.patchStaffProfile(1, expected, Jsons.obj("name", "新姓名"));
        assertEquals("新姓名", store.staff(1).optString("name"));
        assertTrue(store.staff(1).optBoolean("face_registered"));
        assertEquals(901L, store.staff(1).optJSONArray("face_uids").optLong(0));
        assertFalse(store.staff(1).optBoolean("enabled"));
        expectFailure("已被更新", () -> store.patchStaffProfile(1, expected, Jsons.obj("name", "旧表单姓名")));
        assertEquals("新姓名", store.staff(1).optString("name"));
        long department = store.departmentsPage("后厨", 1, 20).rows.get(0).optLong("id");
        JSONObject beforeRename = store.staff(1);
        store.saveDepartment(department, "热厨");
        expectFailure("已被更新", () -> store.patchStaffProfile(1, beforeRename, Jsons.obj("department", "后厨")));
        assertEquals("热厨", store.staff(1).optString("department"));
        expectFailure("字段无效", () -> store.patchStaffProfile(1, store.staff(1), Jsons.obj("face_registered", false)));
    }

    @Test public void staleDepartmentSelectionCannotRecreateDeletedOrRenamedNames() {
        long id = store.saveDepartment(0, "已选择部门");
        store.saveStaff(person(1, ""));
        JSONObject expected = store.staff(1);
        store.removeDepartment(id);
        expectFailure("重新选择部门", () -> store.patchStaffProfile(1, expected, Jsons.obj("department", "已选择部门")));
        expectFailure("重新选择部门", () -> store.createStaffProfile(person(2, "已选择部门")));
        assertNull(store.staff(2));
        assertEquals(0L, store.departmentsPage("", 1, 20).total);
        store.createStaffProfile(person(2, ""));
        assertNotNull(store.staff(2));
        long renamed = store.saveDepartment(0, "原部门");
        store.saveDepartment(renamed, "新部门", "原部门");
        expectFailure("名称已变更", () -> store.saveDepartment(renamed, "旧草稿", "原部门"));
        expectFailure("名称已变更", () -> store.removeDepartment(renamed, "原部门"));
        assertEquals("新部门", store.department(renamed).optString("name"));
    }

    @Test public void versionTwoDatabaseKeepsDepartmentsAndBusinessRows() {
        store.close();
        try (SQLiteDatabase old = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            old.execSQL("CREATE TABLE staff (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL, name TEXT NOT NULL DEFAULT '', department TEXT NOT NULL DEFAULT '')");
            old.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT -1, upload TEXT NOT NULL DEFAULT '', staff_id TEXT NOT NULL DEFAULT '')");
            ContentValues legacy = new ContentValues();
            legacy.put("id", "1");
            legacy.put("data", person(1, "旧部门").toString());
            legacy.put("time", 100L);
            legacy.put("name", "旧员工");
            legacy.put("department", "旧部门");
            old.insertOrThrow("staff", null, legacy);
            ContentValues record = new ContentValues();
            record.put("id", "legacy-record");
            record.put("data", Jsons.obj("id", "legacy-record", "review", "已复核").toString());
            record.put("time", 100L);
            old.insertOrThrow("records", null, record);
            old.setVersion(2);
        }
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(1L, store.staffCount());
        assertEquals(1L, store.recordsPage(0, 1, 20).total);
        assertEquals(1L, store.departmentsPage("", 1, 20).total);
        assertEquals("旧部门", store.staff(1).optString("department"));
        assertEquals("已复核", store.record("legacy-record").optString("review"));
        long department = store.saveDepartment(0, "新部门");
        assertEquals("新部门", store.department(department).optString("name"));
    }

    @Test public void renameRollsBackDepartmentAndEarlierBatchIfAnEmployeeIsCorrupt() {
        for (int i = 1; i <= 45; ++i) store.saveStaff(person(i, "旧部门"));
        long id = store.departmentsPage("旧部门", 1, 20).rows.get(0).optLong("id");
        ContentValues broken = new ContentValues();
        broken.put("data", "not-json");
        store.getWritableDatabase().update("staff", broken, "id=?", new String[]{"9"});
        expectFailure("数据损坏", () -> store.saveDepartment(id, "新部门"));
        assertEquals("旧部门", store.department(id).optString("name"));
        assertEquals(45L, store.department(id).optLong("member_count"));
        assertEquals("旧部门", store.staff(1).optString("department"));
        assertEquals(0L, store.departmentsPage("新部门", 1, 20).total);
    }

    @Test public void versionThreeDatabaseKeepsRawReviewAndCorruptRowsAndDoesNotClearAfterReopen() {
        store.saveStaff(person(1, "旧部门"));
        store.saveRecord(Jsons.obj("id", "reviewed-record", "created_at", 100L, "staff_id", 1,
                "status", 0, "upload", "已上传", "review", "已复核",
                "review_note", "旧复核说明", "review_at", 200L));
        ContentValues broken = new ContentValues();
        broken.put("data", "damaged-json");
        store.getWritableDatabase().update("staff", broken, "id=?", new String[]{"1"});
        // 版本 3 与新库的表结构相同，但 JSON 中保存过复核数据。
        store.getWritableDatabase().setVersion(3);
        store.close();
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(1L, store.staffCount());
        assertEquals(1L, store.recordsPage(0, 1, 20).total);
        assertEquals(1L, store.departmentsPage("", 1, 20).total);
        expectFailure("数据损坏", () -> store.staff(1));
        assertEquals("旧复核说明", store.record("reviewed-record").optString("review_note"));
        store.saveStaff(person(2, "新部门"));
        JSONObject fresh = RecordPolicy.create("fresh-record", store.staff(2), 36.5,
                java.util.Collections.emptyList(), 300L, java.time.LocalDate.of(2026, 10, 9), true);
        store.saveRecord(fresh);
        store.close();
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(2L, store.staffCount());
        assertEquals(2L, store.recordsPage(0, 1, 20).total);
        assertEquals("旧复核说明", store.record("reviewed-record").optString("review_note"));
        assertEquals(1L, store.departmentsPage("新部门", 1, 20).total);
        JSONObject restored = store.record("fresh-record");
        assertEquals(fresh.toString(), restored.toString());
        assertFalse(restored.has("review"));
        assertFalse(restored.has("review_note"));
        assertFalse(restored.has("review_at"));
        assertEquals("未上传", restored.optString("upload"));
    }

    private static JSONObject person(long id, String department) {
        return Jsons.obj("id", id, "name", "员工 " + id, "department", department, "created_at", 100L,
                "staff_no", "S" + id, "health_expiry", "", "enabled", true);
    }

    private static void expectFailure(String contains, Runnable operation) {
        try {
            operation.run();
            fail("操作应被拒绝：" + contains);
        } catch (IllegalArgumentException | IllegalStateException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(contains));
        }
    }
}
