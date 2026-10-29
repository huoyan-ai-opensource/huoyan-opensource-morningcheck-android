package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.Assert.*;

public class RecordPolicyTest {
    private final LocalDate today = LocalDate.of(2026, 9, 28);

    @Test public void certificateIsValidThroughExpiryDate() {
        assertTrue(RecordPolicy.healthValid("2026-09-28", today));
        assertTrue(RecordPolicy.healthValid("2026-09-29", today));
        assertFalse(RecordPolicy.healthValid("2026-09-27", today));
        assertFalse(RecordPolicy.healthValid("", today));
        assertFalse(RecordPolicy.healthValid(null, today));
        assertFalse(RecordPolicy.healthValid("2026-13-40", today));
    }

    private JSONObject record(double temperature) {
        JSONObject staff = Jsons.obj("id", 7, "name", "测试员工", "health_expiry", "2026-09-28");
        JSONObject record = RecordPolicy.create("record-1", staff, temperature, Collections.emptyList(),
                1000, today, true);
        return record;
    }

    @Test public void newRecordKeepsCapturedDepartmentIdAndPathAfterStaffChanges() {
        JSONObject staff = Jsons.obj("id", 7, "name", "测试员工", "department_id", 44,
                "department", "门店 / 后厨", "health_expiry", "2026-09-28");
        JSONObject record = RecordPolicy.create("snapshot", staff, 36.5, Collections.emptyList(),
                1000, today, true);
        Jsons.put(staff, "department_id", 55);
        Jsons.put(staff, "department", "前厅");
        assertEquals(44L, record.optLong("department_id"));
        assertEquals("门店 / 后厨", record.optString("department"));
    }

    @Test public void missingTemperatureIsRecordedAsAbnormal() {
        JSONObject record = record(Double.NaN);
        assertEquals("未测", record.optString("temperature"));
        assertEquals("未测", record.optString("temperature_source"));
        assertEquals(1, record.optInt("abnormal_temperature"));
        assertEquals(0, record.optInt("status"));
    }

    @Test public void newRecordStoresMachineResultWithoutManualReviewFields() {
        JSONObject record = record(36.6);
        assertEquals(1, record.optInt("status"));
        assertEquals("未上传", record.optString("upload"));
        assertFalse(record.has("review"));
        assertFalse(record.has("review_note"));
        assertFalse(record.has("review_at"));
    }
}
