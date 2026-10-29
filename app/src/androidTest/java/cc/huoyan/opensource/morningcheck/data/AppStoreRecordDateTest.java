package cc.huoyan.opensource.morningcheck.data;

import static org.junit.Assert.*;

import android.content.ContentValues;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;

/** 日期、结果、部门、搜索与分页使用隔离数据库，并验证结构化时间边界。 */
@RunWith(AndroidJUnit4.class)
public final class AppStoreRecordDateTest {
    private Context context;
    private String databaseName;
    private AppStore store;

    @Before public void createStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "record-date-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void removeStore() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void dateResultDepartmentAndLiteralSearchFilterBeforeCountingAndPaging() {
        long root = store.saveDepartment(0, "门店");
        long child = store.saveDepartment(0, "后厨", root, "", 0);
        long outside = store.saveDepartment(0, "其他门店");
        for (int i = 0; i < 43; i++) save("matched-" + i, 1_000_000 + i, 0, child, "比例%_员工");
        save("other-department", 1_000_010, 0, outside, "比例%_员工");
        save("normal", 1_000_010, 1, child, "比例%_员工");
        save("other-name", 1_000_010, 0, child, "其他员工");
        save("earlier", 999_999, 0, child, "比例%_员工");
        save("later", 1_000_100, 0, child, "比例%_员工");
        RecordDateRange.Bounds dates = RecordDateRange.Bounds.between(1_000_000, 1_000_100);
        Set<String> visited = new HashSet<>();
        for (int page = 1; page <= 3; page++) {
            AppStore.DataPage result = store.recordsPage("%_", 2, root, dates, page, 20);
            assertEquals(43L, result.total);
            assertEquals(48L, result.totalRows);
            assertEquals(3, result.totalPages);
            assertEquals(page == 3 ? 3 : 20, result.rows.size());
            for (JSONObject value : result.rows) assertTrue(visited.add(value.optString("id")));
        }
        assertEquals(43, visited.size());
        assertEquals(3, store.recordsPage("%_", 2, root, dates, 99, 20).page);
        assertEquals(0, store.recordsPage("不存在", 2, root, dates, 1, 20).rows.size());
        assertEquals(1L, store.recordsPage("%_", 1, root, dates, 1, 20).total);
    }

    @Test public void rangeIncludesStartAndEndingMinuteSecondsButExcludesFollowingMinute() {
        long start = 1_000_020_000L;
        RecordDateRange range = RecordDateRange.custom(start, start, false);
        RecordDateRange.Bounds dates = range.resolvedAt(start, TimeZone.getTimeZone("Asia/Shanghai"));
        save("before", dates.startInclusive - 1, 0, 0, "员工");
        save("start", dates.startInclusive, 0, 0, "员工");
        save("last-millisecond", dates.endExclusive - 1, 0, 0, "员工");
        save("next-minute", dates.endExclusive, 0, 0, "员工");
        AppStore.DataPage result = store.recordsPage("", 2, 0, dates, 1, 20);
        assertEquals(2L, result.total);
        assertEquals("last-millisecond", result.rows.get(0).optString("id"));
        assertEquals("start", result.rows.get(1).optString("id"));
    }

    @Test public void timestampColumnsAvoidParsingExcludedJsonAndAllPreservesOldQueryContract() {
        save("match", 1000, 0, 0, "员工");
        ContentValues damaged = new ContentValues();
        damaged.put("id", "old-damaged");
        damaged.put("data", "not-json");
        damaged.put("time", 500);
        damaged.put("status", 0);
        store.getWritableDatabase().insertOrThrow("records", null, damaged);
        AppStore.DataPage result = store.recordsPage("", 2, 0, RecordDateRange.Bounds.between(1000, 2000), 1, 20);
        assertEquals(1L, result.total);
        assertEquals(2L, result.totalRows);
        assertEquals("match", result.rows.get(0).optString("id"));
        store.getWritableDatabase().delete("records", "id=?", new String[]{"old-damaged"});
        AppStore.DataPage original = store.recordsPage("", 0, 0, 1, 20);
        AppStore.DataPage all = store.recordsPage("", 0, 0, RecordDateRange.Bounds.all(), 1, 20);
        assertEquals(original.total, all.total);
        assertEquals(original.rows.get(0).toString(), all.rows.get(0).toString());
    }

    private void save(String id, long time, int result, long department, String name) {
        store.saveRecord(Jsons.obj("id", id, "created_at", time, "status", result, "staff_id", 1,
                "staff_name", name, "staff_no", "A001", "department_id", department));
    }
}
