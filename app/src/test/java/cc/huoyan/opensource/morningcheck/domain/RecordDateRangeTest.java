package cc.huoyan.opensource.morningcheck.domain;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** 覆盖自然日、滚动时段、分钟边界及重建持久化的查询语义。 */
public final class RecordDateRangeTest {
    private final TimeZone local = TimeZone.getTimeZone("Asia/Shanghai");

    @Test public void todayUsesLocalMidnightAndMovesToNewDayWhenResolvedAgain() {
        RecordDateRange selection = RecordDateRange.today();
        RecordDateRange.Bounds before = selection.resolvedAt(at(local, 2026, 10, 12, 23, 59), local);
        assertEquals(at(local, 2026, 10, 12, 0, 0), before.startInclusive);
        assertEquals(at(local, 2026, 10, 13, 0, 0), before.endExclusive);
        RecordDateRange.Bounds after = selection.resolvedAt(at(local, 2026, 10, 13, 0, 0), local);
        assertEquals(before.endExclusive, after.startInclusive);
        assertEquals(at(local, 2026, 10, 14, 0, 0), after.endExclusive);
    }

    @Test public void lastTwentyFourHoursCrossesMidnightWithoutRoundingToDate() {
        long now = at(local, 2026, 10, 12, 1, 23) + 45_789;
        RecordDateRange.Bounds result = RecordDateRange.preset(RecordDateRange.Preset.LAST_24_HOURS).resolvedAt(now, local);
        assertEquals(at(local, 2026, 10, 11, 1, 23) + 45_789, result.startInclusive);
        assertEquals(now + 1, result.endExclusive);
    }

    @Test public void recentNaturalDaysIncludeTodayAndHaveStableBoundsWithinTheDay() {
        RecordDateRange.Preset[] presets = {RecordDateRange.Preset.LAST_7_DAYS,
                RecordDateRange.Preset.LAST_14_DAYS, RecordDateRange.Preset.LAST_30_DAYS};
        int[][] expectedStarts = {{10, 6}, {9, 29}, {9, 13}};
        for (int i = 0; i < presets.length; i++) {
            RecordDateRange selection = RecordDateRange.preset(presets[i]);
            RecordDateRange.Bounds morning = selection.resolvedAt(at(local, 2026, 10, 12, 6, 0), local);
            assertEquals(at(local, 2026, expectedStarts[i][0], expectedStarts[i][1], 0, 0), morning.startInclusive);
            assertEquals(at(local, 2026, 10, 13, 0, 0), morning.endExclusive);
            assertEquals(morning, selection.resolvedAt(at(local, 2026, 10, 12, 22, 0), local));
        }
    }

    @Test public void naturalDayRespectsDaylightSavingWhileTwentyFourHoursRemainsDuration() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        long now = at(zone, 2026, 3, 8, 12, 0);
        RecordDateRange.Bounds today = RecordDateRange.today().resolvedAt(now, zone);
        assertEquals(23 * 60 * 60_000L, today.endExclusive - today.startInclusive);
        RecordDateRange.Bounds rolling = RecordDateRange.preset(RecordDateRange.Preset.LAST_24_HOURS).resolvedAt(now, zone);
        assertEquals(24 * 60 * 60_000L + 1, rolling.endExclusive - rolling.startInclusive);
    }

    @Test public void fixedCustomRangeIncludesAllSecondsOfEndingMinuteAndAllowsSameMinute() {
        long minute = at(local, 2026, 10, 12, 18, 28);
        RecordDateRange selection = RecordDateRange.custom(minute + 12_000, minute + 50_000, false);
        RecordDateRange.Bounds result = selection.resolvedAt(minute + 55_000, local);
        assertEquals(minute, result.startInclusive);
        assertEquals(minute + 60_000, result.endExclusive);
    }

    @Test public void followNowUsesCurrentInstantRatherThanStoredEndAndMovesEachQuery() {
        long start = at(local, 2026, 10, 12, 0, 0);
        RecordDateRange selection = RecordDateRange.custom(start, start + 60_000, true);
        long first = start + 123_456;
        assertEquals(first + 1, selection.resolvedAt(first, local).endExclusive);
        assertEquals(first + 600_001, selection.resolvedAt(first + 600_000, local).endExclusive);
        assertTrue(selection.followsNow());
    }

    @Test public void allHasNoBoundsAndSerializationPreservesCustomIntent() {
        assertFalse(RecordDateRange.all().resolvedAt(0, local).bounded);
        for (RecordDateRange.Preset preset : RecordDateRange.Preset.values()) {
            if (preset == RecordDateRange.Preset.CUSTOM) continue;
            RecordDateRange range = RecordDateRange.preset(preset);
            assertEquals(range, RecordDateRange.deserialize(range.serialize()));
        }
        RecordDateRange range = RecordDateRange.custom(at(local, 2026, 10, 11, 0, 0),
                at(local, 2026, 10, 12, 18, 28), true);
        assertEquals(range, RecordDateRange.deserialize(range.serialize()));
        assertEquals(RecordDateRange.all(), RecordDateRange.deserialize("1|CUSTOM|invalid|0|true"));
        assertEquals(RecordDateRange.all(), RecordDateRange.deserialize(null));
    }

    @Test public void reversedRangeAndFutureStartFollowingNowAreRejected() {
        expectInvalid(() -> RecordDateRange.custom(120_000, 0, false));
        expectInvalid(() -> RecordDateRange.custom(-1, 0, false));
        expectInvalid(() -> RecordDateRange.custom(120_000, 0, true).resolvedAt(100_000, local));
        expectInvalid(() -> RecordDateRange.Bounds.between(100, 100));
    }

    private static void expectInvalid(Runnable operation) {
        try { operation.run(); fail("无效日期范围不能继续查询"); }
        catch (IllegalArgumentException expected) { }
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}
