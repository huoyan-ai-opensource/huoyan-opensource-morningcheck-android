package cc.huoyan.opensource.morningcheck.data;

import android.content.Context;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

/** 只使用独立测试库验证自动主键、工号唯一和旧库保留，不触碰设备名册。 */
@RunWith(AndroidJUnit4.class)
public final class AppStoreStaffNumberTest {
    private Context context;
    private AppStore store;
    private String databaseName;

    @Before public void openStore() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "staff-number-test-" + UUID.randomUUID() + ".db";
        store = new AppStore(context, databaseName);
    }

    @After public void closeStore() { store.close(); context.deleteDatabase(databaseName); }

    @Test public void automaticIdsAdvancePastExistingIdsAndNeverReuseDeletedIds() {
        store.saveStaff(Jsons.obj("id", 80, "name", "已有员工", "staff_no", "S080"));
        long first = store.createStaffProfile(profile("0001"));
        assertEquals(81, first);
        store.removeStaff(first);
        store.close();
        store = new AppStore(context, databaseName);
        long second = store.createStaffProfile(profile("0002"));
        assertEquals(82, second);
        assertNotNull(store.staff(80));
        assertEquals("0002", store.staff(second).optString("staff_no"));
    }

    @Test public void blankAndDuplicateStaffNumbersAreRejectedButOwnNumberCanBeSaved() {
        expectFailure(() -> store.createStaffProfile(profile("  \t ")));
        long first = store.createStaffProfile(profile("  A01  "));
        assertEquals("A01", store.staff(first).optString("staff_no"));
        expectFailure(() -> store.createStaffProfile(profile("A01")));
        long second = store.createStaffProfile(profile("B01"));
        store.patchStaffProfile(first, store.staff(first), Jsons.obj("staff_no", " A01 ", "name", "修改姓名"));
        assertEquals("修改姓名", store.staff(first).optString("name"));
        expectFailure(() -> store.patchStaffProfile(second, store.staff(second), Jsons.obj("staff_no", " A01 ")));
        expectFailure(() -> store.patchStaffProfile(second, store.staff(second), Jsons.obj("staff_no", " ")));
        assertEquals("B01", store.staff(second).optString("staff_no"));
        assertEquals(1, store.staffPage("A01", 1, 20).total);
    }

    @Test public void failedSaveDoesNotConsumeEmployeeId() {
        long first = store.createStaffProfile(profile("A01"));
        expectFailure(() -> store.createStaffProfile(profile("A01")));
        assertEquals(first + 1, store.createStaffProfile(profile("B01")));
    }

    @Test public void supplementalSchemaKeepsRecordsDepartmentsAndFaceMapping() {
        long id = store.createStaffProfile(profile("A01"));
        store.patchStaff(id, Jsons.obj("face_registered", true, "face_uid", 901L));
        long department = store.saveDepartment(0, "后厨");
        store.saveRecord(Jsons.obj("id", "retained", "staff_id", id, "staff_name", "旧姓名", "created_at", 20));
        store.close();
        store = new AppStore(context, databaseName);
        assertEquals(901L, store.staff(id).optLong("face_uid"));
        assertNotNull(store.department(department));
        assertNotNull(store.record("retained"));
        assertEquals(6, store.getReadableDatabase().getVersion());
    }

    @Test public void existingVersionFourRowsKeepIdsAndAcceptFacePatchesWithLegacyDuplicateNumbers() {
        store.close();
        try (SQLiteDatabase previous = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)) {
            previous.execSQL("CREATE TABLE staff (id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,name TEXT NOT NULL DEFAULT '',department TEXT NOT NULL DEFAULT '')");
            previous.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY,data TEXT NOT NULL,time INTEGER NOT NULL,status INTEGER NOT NULL DEFAULT -1,upload TEXT NOT NULL DEFAULT '',staff_id TEXT NOT NULL DEFAULT '')");
            previous.execSQL("CREATE TABLE departments (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,time INTEGER NOT NULL)");
            for (long id : new long[]{20, 40}) {
                ContentValues row = new ContentValues();
                row.put("id", String.valueOf(id));
                row.put("data", Jsons.obj("id", id, "name", "旧员工", "staff_no", "DUP", "face_uid", id + 100).toString());
                row.put("time", 1L);
                previous.insertOrThrow("staff", null, row);
            }
            previous.setVersion(4);
        }
        store = new AppStore(context, databaseName);
        assertEquals(120L, store.staff(20).optLong("face_uid"));
        assertEquals(2L, store.staffCount());
        store.patchStaff(20, Jsons.obj("face_registered", true));
        assertTrue(store.staff(20).optBoolean("face_registered"));
        expectFailure(() -> store.patchStaffProfile(20, store.staff(20), Jsons.obj("name", "不能保留重复工号")));
        assertEquals(41L, store.createStaffProfile(profile("NEW")));
        assertEquals(6, store.getReadableDatabase().getVersion());
    }

    private static JSONObject profile(String number) { return Jsons.obj("name", "测试员工", "staff_no", number, "department", ""); }
    private static void expectFailure(Runnable operation) {
        try { operation.run(); fail("无效或重复工号不能保存"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("工号")); }
    }
}
