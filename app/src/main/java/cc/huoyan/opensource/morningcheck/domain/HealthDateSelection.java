package cc.huoyan.opensource.morningcheck.domain;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 日历草稿与表单日期分开，取消或旋转不会意外提交日期。
 */
public final class HealthDateSelection {
    private LocalDate selected;
    private YearMonth month;

    public HealthDateSelection(String selectedDate, String visibleMonth, LocalDate today) {
        try {
            selected = LocalDate.parse(selectedDate);
        } catch (Exception invalid) {
            selected = today;
        }
        try {
            month = YearMonth.parse(visibleMonth);
        } catch (Exception invalid) {
            month = YearMonth.from(selected);
        }
    }

    public LocalDate selected() {
        return selected;
    }

    public YearMonth month() {
        return month;
    }

    public int leadingCells() {
        return month.atDay(1).getDayOfWeek().getValue() - 1;
    }

    public int dayAt(int cell) {
        int day = cell - leadingCells() + 1;
        return day > 0 && day <= month.lengthOfMonth() ? day : 0;
    }

    public void browse(int year, int monthNumber) {
        month = YearMonth.of(year, monthNumber);
    }

    public void step(long amount) {
        month = month.plusMonths(amount);
    }

    public void choose(int day) {
        selected = month.atDay(day);
    }
}
