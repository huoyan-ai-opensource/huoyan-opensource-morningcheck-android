package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StaffPresentationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Test public void absentPersonAndMissingFieldsUseExplicitDefaults() {
        for (JSONObject person : new JSONObject[]{null, new JSONObject()}) {
            assertEquals("无", StaffPresentation.number(person));
            assertEquals("未分配", StaffPresentation.department(person));
            assertTrue(StaffPresentation.enabled(person));
            assertHealth(person, "无", "未分配", StaffPresentation.HealthStatus.UNASSIGNED);
        }
    }

    @Test public void jsonNullAndBlankFieldsNeverLeakNullOrWhitespace() {
        for (Object value : new Object[]{JSONObject.NULL, "", "  \t\n "}) {
            JSONObject person = Jsons.obj("staff_no", value, "department", value, "health_expiry", value);
            assertEquals("无", StaffPresentation.number(person));
            assertEquals("未分配", StaffPresentation.department(person));
            assertHealth(person, "无", "未分配", StaffPresentation.HealthStatus.UNASSIGNED);
        }
    }

    @Test public void configuredFieldsTrimWithoutChangingStoredValues() {
        JSONObject person = Jsons.obj("staff_no", "  A007  ", "department", "  后厨  ",
                "health_expiry", " 2026-10-11 ");
        String stored = person.toString();
        assertEquals("A007", StaffPresentation.number(person));
        assertEquals("后厨", StaffPresentation.department(person));
        assertHealth(person, "2026-10-11", "未过期", StaffPresentation.HealthStatus.VALID);
        assertEquals(stored, person.toString());
    }

    @Test public void yesterdayIsExpired() {
        assertHealth(Jsons.obj("health_expiry", "2026-10-09"),
                "2026-10-09", "已过期", StaffPresentation.HealthStatus.EXPIRED);
    }

    @Test public void expiryTodayIsStillValid() {
        assertHealth(Jsons.obj("health_expiry", "2026-10-10"),
                "2026-10-10", "未过期", StaffPresentation.HealthStatus.VALID);
    }

    @Test public void tomorrowIsValid() {
        assertHealth(Jsons.obj("health_expiry", "2026-10-11"),
                "2026-10-11", "未过期", StaffPresentation.HealthStatus.VALID);
    }

    @Test public void invalidOrNonIsoExpiryCannotAppearValid() {
        for (Object expiry : new Object[]{"null", "not-a-date", "2026-02-30", "2025-02-29",
                "2026-13-01", "2026/10/11", "2026-10-11T00:00:00", "2026-1-1", 20261011}) {
            JSONObject person = Jsons.obj("health_expiry", expiry);
            String stored = person.toString();
            assertHealth(person, "无", "未分配", StaffPresentation.HealthStatus.UNASSIGNED);
            assertEquals(stored, person.toString());
        }
    }

    @Test public void leapDayRemainsARealExpiryDate() {
        StaffPresentation.Health health = StaffPresentation.health(
                Jsons.obj("health_expiry", "2024-02-29"), LocalDate.of(2024, 2, 29));
        assertEquals("2024-02-29", health.expiry);
        assertEquals("未过期", health.state);
        assertEquals(StaffPresentation.HealthStatus.VALID, health.status);
    }

    @Test public void enabledPreservesExistingOptBooleanCompatibility() {
        for (Object value : new Object[]{true, "true", "TRUE", JSONObject.NULL, "", 0, "unknown"}) {
            JSONObject person = Jsons.obj("enabled", value);
            assertEquals(person.optBoolean("enabled", true), StaffPresentation.enabled(person));
            assertTrue(StaffPresentation.enabled(person));
        }
        for (Object value : new Object[]{false, "false", "FALSE"}) {
            JSONObject person = Jsons.obj("enabled", value);
            assertEquals(person.optBoolean("enabled", true), StaffPresentation.enabled(person));
            assertFalse(StaffPresentation.enabled(person));
        }
    }

    private static void assertHealth(JSONObject person, String expiry, String state,
                                     StaffPresentation.HealthStatus status) {
        StaffPresentation.Health health = StaffPresentation.health(person, TODAY);
        assertEquals(expiry, health.expiry);
        assertEquals(state, health.state);
        assertEquals(status, health.status);
    }
}
