package cc.huoyan.opensource.morningcheck.data;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 分页、筛选和升级使用单独的测试库，不连接 SDK、相机或云端。 */
@RunWith(AndroidJUnit4.class)
public final class AppStorePaginationTest {
    private Context context;
    private String databaseName;
    private AppStore store;

    @Before public void createSeparateStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "pagination-test-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void removeOwnDatabase() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void staffPagesHaveStableOrderAndClampAfterLastPageDeletion() {
        for (int i = 1; i <= 43; i++) store.saveStaff(person(i, "员工 " + i, "厨房"));
        AppStore.DataPage first = store.staffPage("", 1, 20);
        AppStore.DataPage second = store.staffPage("", 2, 20);
        AppStore.DataPage last = store.staffPage("", 3, 20);
        assertEquals(43L, first.total);
        assertEquals(3, first.totalPages);
        assertEquals(20, first.rows.size());
        assertEquals(20, second.rows.size());
        assertEquals(3, last.rows.size());
        Set<Long> ids = new HashSet<>();
        for (AppStore.DataPage page : new AppStore.DataPage[]{first, second, last})
            for (JSONObject row : page.rows) assertTrue(ids.add(row.optLong("id")));
        assertEquals(43, ids.size());
        assertEquals(first.rows.get(0).optLong("id"), store.staffPage("", 1, 20).rows.get(0).optLong("id"));
        for (JSONObject row : last.rows) store.removeStaff(row.optLong("id"));
        AppStore.DataPage clamped = store.staffPage("", 3, 20);
        assertEquals(2, clamped.page);
        assertEquals(20, clamped.rows.size());
        assertEquals(40L, clamped.total);
        assertEquals(40L, store.staffCount());
    }

    @Test public void staffSearchFiltersBeforePagingAndTreatsWildcardsAsText() {
        for (int i = 1; i <= 45; i++)
            store.saveStaff(person(i, "员工 " + i, i <= 25 ? "后厨" : "前厅"));
        store.saveStaff(person(46, "比例%_员工", "测试"));
        AppStore.DataPage result = store.staffPage("后厨", 2, 20);
        assertEquals(25L, result.total);
        assertEquals(46L, result.totalRows);
        assertEquals(5, result.rows.size());
        for (JSONObject row : result.rows) assertEquals("后厨", row.optString("department"));
        assertEquals(1L, store.staffPage("%_", 1, 20).total);
        assertEquals(0L, store.staffPage("不存在", 1, 20).total);
        assertEquals(1, store.staffPage("不存在", 99, 20).page);
        store.patchStaff(46, Jsons.obj("name", "改名员工", "department", "后厨"));
        assertEquals(0L, store.staffPage("%_", 1, 20).total);
        assertEquals(26L, store.staffPage("后厨", 1, 20).total);
    }

    @Test public void recordFiltersCountOnlyMatchesAndUpdateAfterUploadResult() {
        for (int i = 1; i <= 47; i++)
            store.saveRecord(record("record-" + i, i, 1, i <= 27 ? 0 : 1,
                    i <= 22 ? "结果待核实" : i <= 32 ? "上传失败" : "已上传"));
        assertEquals(47L, store.recordsPage(0, 1, 20).total);
        assertEquals(27L, store.recordsPage(1, 1, 20).total);
        assertEquals(7, store.recordsPage(1, 2, 20).rows.size());
        assertEquals(32L, store.recordsPage(2, 1, 20).total);
        AppStore.DataPage pending = store.recordsPage(3, 2, 20);
        assertEquals(22L, pending.total);
        assertEquals(2, pending.rows.size());
        store.patchRecord("record-1", Jsons.obj("upload", "已上传"));
        assertEquals(21L, store.recordsPage(3, 1, 20).total);
        assertEquals(31L, store.recordsPage(2, 1, 20).total);
        store.patchRecord("record-2", Jsons.obj("status", 1));
        assertEquals(26L, store.recordsPage(1, 1, 20).total);
    }

    @Test public void homeSummaryCountsDistinctCurrentStaffWithinDayOnly() {
        store.saveStaff(person(1, "甲", ""));
        store.saveStaff(person(2, "乙", ""));
        store.saveStaff(person(3, "丙", ""));
        store.saveRecord(record("a", 100, 1, 1, "已上传"));
        store.saveRecord(record("b", 150, 1, 1, "已上传"));
        store.saveRecord(record("c", 199, 2, 1, "已上传"));
        store.saveRecord(record("orphan", 160, 4, 1, "已上传"));
        store.saveRecord(record("before", 99, 3, 1, "已上传"));
        store.saveRecord(record("after", 200, 3, 1, "已上传"));
        AppStore.HomeSummary summary = store.homeSummary(100, 200);
        assertEquals(3L, summary.staffCount);
        assertEquals(2L, summary.checkedTodayCount);
        store.removeStaff(2);
        assertEquals(1L, store.homeSummary(100, 200).checkedTodayCount);
    }

    @Test public void interruptedUploadsRecoverEveryBatchWithoutChangingOtherResults() {
        for (int i = 1; i <= 51; i++)
            store.saveRecord(record("upload-" + i, i, 1, 1, i <= 45 ? "上传中" : "已上传"));
        store.recoverInterruptedUploads();
        assertEquals(45L, store.recordsPage(3, 1, 20).total);
        assertEquals("结果待核实", store.record("upload-1").optString("upload"));
        assertEquals("结果待核实", store.record("upload-45").optString("upload"));
        assertEquals("已上传", store.record("upload-51").optString("upload"));
        store.recoverInterruptedUploads();
        assertEquals(45L, store.recordsPage(3, 1, 20).total);
    }

    @Test public void versionOneDatabaseIsMigratedAndAllRecordsSurviveReopen() {
        store.close();
        try (SQLiteDatabase old = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            old.execSQL("CREATE TABLE staff (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, data TEXT NOT NULL, time INTEGER NOT NULL)");
            insertOld(old, "staff", person(1, "旧员工", "旧部门"));
            insertOld(old, "records", record("legacy-record", 100, 1, 0, "结果待核实"));
            old.setVersion(1);
        }
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(1L, store.staffCount());
        assertEquals(1L, store.recordsPage(0, 1, 20).total);
        assertEquals(1L, store.departmentsPage("", 1, 20).total);
        assertEquals("旧员工", store.staff(1).optString("name"));
        assertEquals(1L, store.staffPage("S1", 0, 0, 1, 20).total);
        assertEquals(1L, store.recordsPage("旧员工", 2, 0, 1, 20).total);
        assertEquals(0, store.record("legacy-record").optInt("status"));
        store.saveStaff(person(2, "新员工", "新部门"));
        store.saveRecord(record("new-record", 200, 2, 1, "未上传"));
        store.close();
        store = new AppStore(context, databaseName);
        assertEquals(6, store.getReadableDatabase().getVersion());
        assertEquals(2L, store.staffCount());
        assertEquals(2L, store.recordsPage(0, 1, 20).total);
        assertNotNull(store.record("legacy-record"));
        assertEquals(1L, store.departmentsPage("新部门", 1, 20).total);
        assertEquals(1, store.record("new-record").optInt("status"));
        assertEquals("未上传", store.record("new-record").optString("upload"));
    }

    private static JSONObject person(long id, String name, String department) {
        return Jsons.obj("id", id, "name", name, "staff_no", "S" + id, "department", department, "created_at", 100);
    }

    private static JSONObject record(String id, long createdAt, long staffId, int status, String upload) {
        return Jsons.obj("id", id, "created_at", createdAt, "staff_id", staffId, "status", status, "upload", upload);
    }

    private static void insertOld(SQLiteDatabase database, String table, JSONObject value) {
        ContentValues fields = new ContentValues();
        fields.put("id", value.optString("id"));
        fields.put("data", value.toString());
        fields.put("time", value.optLong("created_at"));
        assertTrue(database.insertOrThrow(table, null, fields) >= 0);
    }
}
