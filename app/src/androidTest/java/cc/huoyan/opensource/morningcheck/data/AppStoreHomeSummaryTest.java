package cc.huoyan.opensource.morningcheck.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 首页按员工去重统计最新有效结果，复检不会重复增加人数。 */
@RunWith(AndroidJUnit4.class)
public final class AppStoreHomeSummaryTest {
    private Context context;
    private String databaseName;
    private AppStore store;

    @Before public void createStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "home-summary-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
        for (int i = 1; i <= 4; i++) store.saveStaff(Jsons.obj("id", i, "name", "员工" + i,
                "staff_no", "S" + i, "enabled", i != 4, "created_at", i));
    }

    @After public void removeStore() {
        store.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void latestValidResultPartitionsCheckedPeopleAndResolvesTimestampTies() {
        save("first-normal", 1, 100, 1);
        save("later-abnormal", 1, 150, 0);
        save("same-a", 2, 120, 0);
        save("same-z", 2, 120, 1);
        save("invalid-latest", 2, 160, -1);
        save("before", 3, 99, 1);
        save("next-day", 3, 200, 1);
        save("disabled-checked", 4, 199, 1);
        save("unknown-employee", 99, 180, 0);
        AppStore.HomeSummary summary = store.homeSummary(100, 200);
        assertEquals(4L, summary.staffCount);
        assertEquals(3L, summary.checkedTodayCount);
        assertEquals(2L, summary.normalTodayCount);
        assertEquals(1L, summary.abnormalTodayCount);
        assertEquals(summary.checkedTodayCount, summary.normalTodayCount + summary.abnormalTodayCount);
        store.removeStaff(1);
        assertEquals(0L, store.homeSummary(100, 200).abnormalTodayCount);
    }

    @Test public void emptyDayHasZeroCountersAndAbnormalRecordsRemainAvailableAfterNormalRetest() {
        AppStore.HomeSummary empty = store.homeSummary(100, 200);
        assertEquals(0L, empty.checkedTodayCount);
        assertEquals(0L, empty.normalTodayCount);
        assertEquals(0L, empty.abnormalTodayCount);
        save("abnormal", 1, 100, 0);
        save("normal-retest", 1, 101, 1);
        AppStore.HomeSummary latest = store.homeSummary(100, 200);
        assertEquals(1L, latest.checkedTodayCount);
        assertEquals(1L, latest.normalTodayCount);
        assertEquals(0L, latest.abnormalTodayCount);
        // 主页统计展示当前结果，异常入口仍能查到当日每次异常的原始记录。
        assertEquals(1L, store.recordsPage("", 2, 0,
                cc.huoyan.opensource.morningcheck.domain.RecordDateRange.Bounds.between(100, 200), 1, 20).total);
    }

    @Test public void employeeChangesCannotMixDifferentSnapshotsOfRosterAndCheckedCount() throws Exception {
        for (int id = 1; id <= 4; id++) save("checked-" + id, id, 100 + id, id % 2);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch begin = new CountDownLatch(1);
        Future<?> changes = writer.submit(() -> {
            try {
                if (!begin.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发统计测试未开始");
                SQLiteDatabase db = store.getWritableDatabase();
                for (int round = 0; round < 80; round++) {
                    db.beginTransaction();
                    try {
                        // 名册在完整四人和空名册之间原子切换，历史记录始终保留。
                        db.delete("staff", null, null);
                        if (round % 2 == 1) for (int id = 1; id <= 4; id++)
                            store.saveStaff(Jsons.obj("id", id, "name", "员工" + id, "staff_no", "S" + id,
                                    "created_at", id));
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    Thread.yield();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("并发名册测试被中断", interrupted);
            }
        });
        try {
            begin.countDown();
            for (int sample = 0; sample < 500; sample++) {
                AppStore.HomeSummary summary = store.homeSummary(100, 200);
                assertEquals("全员当日已检时，总数与已检必须来自同一名册快照",
                        summary.staffCount, summary.checkedTodayCount);
                assertEquals(summary.checkedTodayCount, summary.normalTodayCount + summary.abnormalTodayCount);
            }
        } finally {
            try { changes.get(15, TimeUnit.SECONDS); }
            finally { writer.shutdownNow(); assertTrue(writer.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    private void save(String id, int staffId, long time, int result) {
        store.saveRecord(Jsons.obj("id", id, "staff_id", staffId, "created_at", time, "status", result));
    }
}
