package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

public final class HealthDateSelectionTest {
    @Test public void emptyExpiryStartsAtTodayWithoutRequiringAStoredDate() {
        HealthDateSelection selection = new HealthDateSelection("", "", LocalDate.of(2026, 10, 10));
        assertEquals(LocalDate.of(2026, 10, 10), selection.selected());
        assertEquals("2026-10", selection.month().toString());
    }

    @Test public void browsingShortMonthDoesNotCommitOrClampPreviouslySelectedDay() {
        HealthDateSelection selection = new HealthDateSelection("2028-01-31", "", LocalDate.now());
        selection.step(1);
        assertEquals("2028-02", selection.month().toString());
        assertEquals("2028-01-31", selection.selected().toString());
        selection.choose(29);
        assertEquals("2028-02-29", selection.selected().toString());
    }

    @Test public void gridOnlyEnablesActualMonthDaysAndUsesMondayFirst() {
        HealthDateSelection selection = new HealthDateSelection("2026-02-01", "", LocalDate.now());
        assertEquals(6, selection.leadingCells());
        assertEquals(0, selection.dayAt(5));
        assertEquals(1, selection.dayAt(6));
        assertEquals(28, selection.dayAt(33));
        assertEquals(0, selection.dayAt(34));
    }

    @Test public void selectedDateAndBrowseMonthRestoreIndependently() {
        HealthDateSelection selection = new HealthDateSelection("2032-02-03", "2033-08", LocalDate.now());
        assertEquals("2032-02-03", selection.selected().toString());
        assertEquals("2033-08", selection.month().toString());
        selection.browse(2030, 12);
        selection.step(1);
        assertEquals("2031-01", selection.month().toString());
    }
}
