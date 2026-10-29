package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.GridView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.Consumer;

import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;

/**
 * 日期筛选只编辑弹窗草稿；日历连续点选自动组成范围，确定后才替换列表条件。
 */
final class RecordDateFilterDialog extends Dialog {
    private static final int FIRST_YEAR = 1970;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.CHINA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final RecordDateRange.Preset[] PRESETS = {
            RecordDateRange.Preset.TODAY, RecordDateRange.Preset.LAST_24_HOURS,
            RecordDateRange.Preset.LAST_7_DAYS, RecordDateRange.Preset.LAST_14_DAYS,
            RecordDateRange.Preset.LAST_30_DAYS, RecordDateRange.Preset.ALL};
    private static final int[] PRESET_IDS = {R.id.record_date_today, R.id.record_date_24_hours,
            R.id.record_date_7_days, R.id.record_date_14_days, R.id.record_date_30_days, R.id.record_date_all};

    private final Activity host;
    private final Context uiContext;
    private final ZoneId zone = ZoneId.systemDefault();
    private final boolean sideBySide;
    private final boolean compactWindow;
    private final Days days = new Days();
    private final Spinner yearPicker, monthPicker, hourPicker, minutePicker;
    private final CheckBox followPicker;
    private final Spinner presetPicker;
    private final int lastYear = LocalDate.now().getYear() + 20;
    private Consumer<RecordDateRange> applied;
    private RecordDateRange.Preset preset;
    private LocalDateTime start, end;
    private LocalDate rangeAnchor;
    private YearMonth month;
    private boolean followNow, editEnd, editingTime, editingDateEndpoint, syncing, closed;

    RecordDateFilterDialog(Context context, RecordDateRange initial, Consumer<RecordDateRange> applied) {
        super(context);
        this.applied = applied;
        host = activityOf(context);
        if (host != null) setOwnerActivity(host);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Rect bounds = availableWindowBounds();
        sideBySide = bounds.width() > bounds.height();
        uiContext = context;
        compactWindow = !sideBySide || bounds.height() < size(R.dimen.dp_400)
                || bounds.width() < size(R.dimen.dp_400, R.dimen.dp_260, R.dimen.dp_60);
        setContentView(LayoutInflater.from(uiContext).inflate(R.layout.dialog_record_date_design, null));
        setTitle(R.string.record_date_filter_title);

        seed(initial == null ? RecordDateRange.all() : initial);
        yearPicker = findViewById(R.id.record_date_year);
        monthPicker = findViewById(R.id.record_date_month);
        hourPicker = findViewById(R.id.record_date_hour);
        minutePicker = findViewById(R.id.record_date_minute);
        followPicker = findViewById(R.id.record_date_follow_now);
        presetPicker = findViewById(R.id.record_date_preset_picker);
        List<String> years = new ArrayList<>(), months = new ArrayList<>();
        List<String> hours = new ArrayList<>(), minutes = new ArrayList<>();
        for (int year = FIRST_YEAR; year <= lastYear; year++)
            years.add(context.getString(R.string.record_date_year_value, year));
        for (int value = 1; value <= 12; value++)
            months.add(context.getString(R.string.record_date_month_value, value));
        for (int value = 0; value < 24; value++)
            hours.add(String.format(Locale.CHINA, "%02d", value));
        for (int value = 0; value < 60; value++)
            minutes.add(String.format(Locale.CHINA, "%02d", value));
        yearPicker.setAdapter(options(years));
        monthPicker.setAdapter(options(months));
        hourPicker.setAdapter(options(hours));
        minutePicker.setAdapter(options(minutes));
        if (presetPicker != null) {
            List<String> labels = new ArrayList<>();
            for (int resource : new int[]{R.string.record_date_today, R.string.record_date_24_hours,
                    R.string.record_date_7_days, R.string.record_date_14_days, R.string.record_date_30_days,
                    R.string.record_date_all, R.string.record_date_custom})
                labels.add(context.getString(resource));
            presetPicker.setAdapter(options(labels));
            presetPicker.setOnItemSelectedListener(listener(() -> {
                int position = presetPicker.getSelectedItemPosition();
                if (position == PRESETS.length) {
                    if (preset != RecordDateRange.Preset.CUSTOM) {
                        preset = RecordDateRange.Preset.CUSTOM;
                        updateUi();
                    }
                    return;
                }
                if (position < 0 || position >= PRESETS.length) return;
                if (preset == PRESETS[position]) return;
                seed(RecordDateRange.preset(PRESETS[position]));
                updateUi();
            }));
        }

        AdapterView.OnItemSelectedListener monthChanged = listener(() -> {
            int year = FIRST_YEAR + yearPicker.getSelectedItemPosition();
            int chosenMonth = monthPicker.getSelectedItemPosition() + 1;
            if (year < FIRST_YEAR || chosenMonth < 1) return;
            YearMonth next = YearMonth.of(year, chosenMonth);
            if (!next.equals(month)) {
                month = next;
                updateUi();
            }
        });
        yearPicker.setOnItemSelectedListener(monthChanged);
        monthPicker.setOnItemSelectedListener(monthChanged);
        AdapterView.OnItemSelectedListener timeChanged = listener(() -> {
            int hour = hourPicker.getSelectedItemPosition(), minute = minutePicker.getSelectedItemPosition();
            if (hour < 0 || minute < 0 || (editEnd && followNow)) return;
            LocalDateTime selected = editing();
            if (selected.getHour() == hour && selected.getMinute() == minute) return;
            assign(selected.withHour(hour).withMinute(minute));
            preset = RecordDateRange.Preset.CUSTOM;
            updateUi();
        });
        hourPicker.setOnItemSelectedListener(timeChanged);
        minutePicker.setOnItemSelectedListener(timeChanged);
        followPicker.setOnCheckedChangeListener((button, checked) -> {
            if (syncing || followNow == checked) return;
            rangeAnchor = null;
            editingDateEndpoint = false;
            followNow = checked;
            preset = RecordDateRange.Preset.CUSTOM;
            if (checked) {
                end = now();
                editEnd = false;
                month = YearMonth.from(start);
            }
            updateUi();
        });
        for (int index = 0; index < PRESETS.length; index++) {
            final RecordDateRange.Preset chosen = PRESETS[index];
            View button = findViewById(PRESET_IDS[index]);
            if (button != null) button.setOnClickListener(view -> {
                seed(RecordDateRange.preset(chosen));
                updateUi();
            });
        }
        findViewById(R.id.record_date_start).setOnClickListener(view -> beginEdit(false));
        findViewById(R.id.record_date_end).setOnClickListener(view -> beginEdit(true));
        findViewById(R.id.record_date_start_date).setOnClickListener(view -> beginEdit(false, false));
        findViewById(R.id.record_date_end_date).setOnClickListener(view -> beginEdit(true, false));
        findViewById(R.id.record_date_start_time).setOnClickListener(view -> beginEdit(false, true));
        findViewById(R.id.record_date_end_time).setOnClickListener(view -> beginEdit(true, true));
        findViewById(R.id.record_date_back_to_calendar).setOnClickListener(view -> {
            editingTime = false;
            updateUi();
        });
        findViewById(R.id.record_date_previous).setOnClickListener(view -> step(-1));
        findViewById(R.id.record_date_next).setOnClickListener(view -> step(1));
        GridView calendar = findViewById(R.id.record_date_days);
        calendar.setAdapter(days);
        calendar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                days.notifyDataSetChanged();
        });
        calendar.setOnItemClickListener((parent, view, position, id) -> {
            LocalDate chosen = dayAt(position);
            if (chosen.getYear() < FIRST_YEAR || chosen.getYear() > lastYear) return;
            boolean preciseTime = findViewById(R.id.record_date_extra).getVisibility() == View.VISIBLE;
            if (editingDateEndpoint || preciseTime) {
                if (editEnd && followNow) followNow = false;
                assign(LocalDateTime.of(chosen, preciseTime ? editing().toLocalTime()
                        : editEnd ? java.time.LocalTime.of(23, 59) : java.time.LocalTime.MIDNIGHT));
                editingDateEndpoint = false;
                rangeAnchor = null;
            } else {
                followNow = false;
                // 始终按最近两次点击自动排序；锚点保留最后点击日期，跨月和重建后仍可接着选。
                if (rangeAnchor == null) {
                    start = chosen.atStartOfDay();
                    end = chosen.atTime(23, 59);
                    editEnd = true;
                } else {
                    start = (chosen.isBefore(rangeAnchor) ? chosen : rangeAnchor).atStartOfDay();
                    end = (chosen.isAfter(rangeAnchor) ? chosen : rangeAnchor).atTime(23, 59);
                    editEnd = !chosen.isBefore(rangeAnchor);
                }
                rangeAnchor = chosen;
            }
            preset = RecordDateRange.Preset.CUSTOM;
            month = YearMonth.from(chosen);
            updateUi();
        });
        findViewById(R.id.record_date_cancel).setOnClickListener(view -> dismiss());
        findViewById(R.id.record_date_reset).setOnClickListener(view -> {
            seed(RecordDateRange.all());
            updateUi();
        });
        findViewById(R.id.record_date_yesterday).setOnClickListener(view -> {
            LocalDate yesterday = LocalDate.now(zone).minusDays(1);
            seed(RecordDateRange.custom(millis(yesterday.atStartOfDay()),
                    millis(yesterday.atTime(23, 59)), false));
            updateUi();
        });
        findViewById(R.id.record_date_this_month).setOnClickListener(view -> {
            YearMonth current = YearMonth.now(zone);
            seed(RecordDateRange.custom(millis(current.atDay(1).atStartOfDay()),
                    millis(current.atEndOfMonth().atTime(23, 59)), false));
            updateUi();
        });
        findViewById(R.id.record_date_title).setOnLongClickListener(view -> {
            rangeAnchor = null;
            editingDateEndpoint = false;
            int visibility = findViewById(R.id.record_date_extra).getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            for (int id : new int[]{R.id.record_date_extra, R.id.record_date_start_time,
                    R.id.record_date_end_time, R.id.record_date_month_pickers})
                findViewById(id).setVisibility(visibility);
            findViewById(R.id.record_date_month_navigation).setVisibility(visibility == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        });
        findViewById(R.id.record_date_confirm).setOnClickListener(view -> confirm());
        setCanceledOnTouchOutside(true);
        updateUi();
    }

    private void seed(RecordDateRange range) {
        preset = range.preset();
        followNow = range.followsNow();
        if (preset == RecordDateRange.Preset.CUSTOM) {
            start = at(range.customStart());
            end = followNow ? now() : at(range.customEnd());
        } else if (preset == RecordDateRange.Preset.ALL) {
            start = LocalDate.now(zone).atStartOfDay();
            end = now();
        } else {
            RecordDateRange.Bounds bounds = range.resolvedAt(System.currentTimeMillis(), TimeZone.getDefault());
            start = at(bounds.startInclusive);
            end = at(bounds.endExclusive - 1);
        }
        editEnd = false;
        editingTime = false;
        editingDateEndpoint = false;
        rangeAnchor = null;
        month = YearMonth.from(start);
    }

    private ArrayAdapter<String> options(List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(uiContext, R.layout.item_design_date_option, values);
        adapter.setDropDownViewResource(R.layout.item_design_date_option);
        return adapter;
    }

    private AdapterView.OnItemSelectedListener listener(Runnable changed) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!syncing && !closed) changed.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
    }

    private void beginEdit(boolean endSelected) {
        beginEdit(endSelected, false);
    }

    private void beginEdit(boolean endSelected, boolean timeSelected) {
        rangeAnchor = null;
        editingDateEndpoint = !timeSelected;
        editEnd = endSelected;
        editingTime = timeSelected;
        if (editEnd && followNow) {
            end = now();
            followNow = false;
        }
        if (!timeSelected && findViewById(R.id.record_date_extra).getVisibility() != View.VISIBLE) {
            start = start.with(java.time.LocalTime.MIDNIGHT);
            end = end.with(java.time.LocalTime.of(23, 59));
        }
        preset = RecordDateRange.Preset.CUSTOM;
        month = YearMonth.from(editing());
        updateUi();
    }

    private void step(int direction) {
        YearMonth next = month.plusMonths(direction);
        if (next.getYear() < FIRST_YEAR || next.getYear() > lastYear) return;
        month = next;
        updateUi();
    }

    private LocalDateTime editing() {
        return editEnd ? end : start;
    }

    private void assign(LocalDateTime value) {
        if (editEnd) end = value;
        else start = value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone).withSecond(0).withNano(0);
    }

    private LocalDateTime at(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().withSecond(0).withNano(0);
    }

    private long millis(LocalDateTime value) {
        return value.atZone(zone).toInstant().toEpochMilli();
    }

    private RecordDateRange proposed() {
        if (preset != RecordDateRange.Preset.CUSTOM) return RecordDateRange.preset(preset);
        RecordDateRange value = RecordDateRange.custom(millis(start), millis(end), followNow);
        // 跟随当前时间时也要校验未来的起点，避免确认后列表查询才失败。
        value.resolvedAt(System.currentTimeMillis(), TimeZone.getDefault());
        return value;
    }

    private void confirm() {
        RecordDateRange value;
        try {
            value = proposed();
        } catch (RuntimeException invalid) {
            showError();
            return;
        }
        Consumer<RecordDateRange> callback = applied;
        dismiss();
        if (callback != null) callback.accept(value);
    }

    private void updateUi() {
        if (followNow) end = now();
        ((TextView) findViewById(R.id.record_date_start_date)).setText(DATE.format(start));
        ((TextView) findViewById(R.id.record_date_start_time)).setText(TIME.format(start));
        ((TextView) findViewById(R.id.record_date_end_date)).setText(DATE.format(end));
        ((TextView) findViewById(R.id.record_date_end_time)).setText(TIME.format(end));
        View startView = findViewById(R.id.record_date_start), endView = findViewById(R.id.record_date_end);
        startView.setSelected(!editEnd && preset == RecordDateRange.Preset.CUSTOM);
        endView.setSelected(editEnd && preset == RecordDateRange.Preset.CUSTOM);
        startView.setContentDescription(getContext().getString(R.string.record_date_start) + "，" + DATE.format(start) + " " + TIME.format(start));
        endView.setContentDescription(getContext().getString(R.string.record_date_end) + "，" + DATE.format(end) + " " + TIME.format(end));
        for (int index = 0; index < PRESETS.length; index++) {
            TextView button = findViewById(PRESET_IDS[index]);
            if (button == null) continue;
            button.setSelected(preset == PRESETS[index]);
        }
        LocalDate today = LocalDate.now(zone);
        boolean wholeDays = preset == RecordDateRange.Preset.CUSTOM && !followNow
                && start.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                && end.toLocalTime().equals(java.time.LocalTime.of(23, 59));
        findViewById(R.id.record_date_yesterday).setSelected(wholeDays
                && start.toLocalDate().equals(today.minusDays(1)) && end.toLocalDate().equals(today.minusDays(1)));
        findViewById(R.id.record_date_this_month).setSelected(wholeDays
                && start.toLocalDate().equals(today.withDayOfMonth(1))
                && end.toLocalDate().equals(YearMonth.from(today).atEndOfMonth()));
        ((TextView) findViewById(R.id.record_date_month_title)).setText(
                getContext().getString(R.string.i18n_b_calendar_month, month.getYear(), month.getMonthValue()));
        syncing = true;
        if (presetPicker != null) presetPicker.setSelection(preset == RecordDateRange.Preset.CUSTOM
                ? PRESETS.length : preset.ordinal());
        yearPicker.setSelection(month.getYear() - FIRST_YEAR);
        monthPicker.setSelection(month.getMonthValue() - 1);
        hourPicker.setSelection(editing().getHour());
        minutePicker.setSelection(editing().getMinute());
        followPicker.setChecked(followNow);
        syncing = false;
        findViewById(R.id.record_date_calendar_body).setVisibility(editingTime ? View.GONE : View.VISIBLE);
        findViewById(R.id.record_date_time_editor).setVisibility(editingTime ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.record_date_editing)).setText(editEnd ? R.string.record_date_edit_end : R.string.record_date_edit_start);
        findViewById(R.id.record_date_previous).setEnabled(month.isAfter(YearMonth.of(FIRST_YEAR, 1)));
        findViewById(R.id.record_date_next).setEnabled(month.isBefore(YearMonth.of(lastYear, 12)));
        days.notifyDataSetChanged();
        findViewById(R.id.record_date_error).setVisibility(View.GONE);
    }

    private void showError() {
        ToastEx.showFailureToast(getContext(), getContext().getString(R.string.record_date_range_error));
    }

    Bundle saveDraft() {
        Bundle saved = new Bundle();
        saved.putString("preset", preset.name());
        saved.putLong("start", millis(start));
        saved.putLong("end", millis(end));
        saved.putBoolean("follow", followNow);
        saved.putBoolean("edit_end", editEnd);
        saved.putBoolean("editing_time", editingTime);
        saved.putBoolean("editing_date_endpoint", editingDateEndpoint);
        if (rangeAnchor != null) saved.putLong("range_anchor", rangeAnchor.toEpochDay());
        saved.putString("month", month.toString());
        return saved;
    }

    void restoreDraft(Bundle saved) {
        if (saved == null) return;
        try {
            RecordDateRange.Preset restored = RecordDateRange.Preset.valueOf(saved.getString("preset", preset.name()));
            LocalDateTime restoredStart = at(saved.getLong("start", millis(start)));
            LocalDateTime restoredEnd = at(saved.getLong("end", millis(end)));
            YearMonth restoredMonth = YearMonth.parse(saved.getString("month", month.toString()));
            LocalDate restoredAnchor = saved.containsKey("range_anchor")
                    ? LocalDate.ofEpochDay(saved.getLong("range_anchor")) : null;
            if (restoredStart.getYear() < FIRST_YEAR || restoredStart.getYear() > lastYear
                    || restoredEnd.getYear() < FIRST_YEAR || restoredEnd.getYear() > lastYear
                    || restoredMonth.getYear() < FIRST_YEAR || restoredMonth.getYear() > lastYear
                    || restoredAnchor != null && (restoredAnchor.getYear() < FIRST_YEAR || restoredAnchor.getYear() > lastYear))
                return;
            preset = restored;
            start = restoredStart;
            end = restoredEnd;
            month = restoredMonth;
            followNow = saved.getBoolean("follow");
            editEnd = saved.getBoolean("edit_end");
            editingTime = saved.getBoolean("editing_time");
            editingDateEndpoint = saved.getBoolean("editing_date_endpoint");
            rangeAnchor = restoredAnchor;
            updateUi();
        } catch (RuntimeException ignored) { /* 损坏的临时草稿不覆盖已经应用的日期条件。 */ }
    }

    @Override
    public void show() {
        if (closed) return;
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setWindowAnimations(0);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        super.show();
        if (window != null) {
            Rect bounds = availableWindowBounds();
            int[] size = panelSize(bounds.width(), bounds.height());
            window.setGravity(Gravity.CENTER);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.35f);
            window.setLayout(size[0], size[1]);
            ImmersiveUi.enable(window);
        }
    }

    int[] panelSize(int width, int height) {
        int margin = size(R.dimen.dp_12) * 2;
        int preferredWidth = sideBySide ? size(R.dimen.dp_260, R.dimen.dp_100, R.dimen.dp_20)
                : Math.round(width * .86f);
        int preferredHeight = size(R.dimen.dp_260, R.dimen.dp_60, R.dimen.dp_10);
        return new int[]{Math.max(1, Math.min(width - margin, preferredWidth)),
                Math.max(1, Math.min(height - margin, preferredHeight))};
    }

    @Override
    public void dismiss() {
        if (!closed) {
            closed = true;
            applied = null;
            yearPicker.setOnItemSelectedListener(null);
            monthPicker.setOnItemSelectedListener(null);
            hourPicker.setOnItemSelectedListener(null);
            minutePicker.setOnItemSelectedListener(null);
            followPicker.setOnCheckedChangeListener(null);
            if (presetPicker != null) presetPicker.setOnItemSelectedListener(null);
            ((GridView) findViewById(R.id.record_date_days)).setOnItemClickListener(null);
        }
        super.dismiss();
    }

    private int size(int... dimensions) {
        int result = 0;
        for (int dimension : dimensions)
            result += uiContext.getResources().getDimensionPixelSize(dimension);
        return result;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private Rect availableWindowBounds() {
        if (host != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = host.getWindowManager().getCurrentWindowMetrics();
                Rect bounds = new Rect(metrics.getBounds());
                android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.captionBar());
                bounds.left += insets.left;
                bounds.top += insets.top;
                bounds.right -= insets.right;
                bounds.bottom -= insets.bottom;
                return bounds;
            }
            View decor = host.getWindow().getDecorView();
            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                Rect bounds = new Rect(0, 0, decor.getWidth(), decor.getHeight());
                WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(decor);
                if (windowInsets != null) {
                    Insets insets = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.captionBar());
                    bounds.left += insets.left;
                    bounds.top += insets.top;
                    bounds.right -= insets.right;
                    bounds.bottom -= insets.bottom;
                }
                return bounds;
            }
        }
        Configuration configuration = getContext().getResources().getConfiguration();
        float density = getContext().getResources().getDisplayMetrics().density;
        return new Rect(0, 0, Math.max(1, Math.round(configuration.screenWidthDp * density)),
                Math.max(1, Math.round(configuration.screenHeightDp * density)));
    }

    private LocalDate dayAt(int position) {
        LocalDate first = month.atDay(1);
        return first.minusDays(first.getDayOfWeek().getValue() - 1L).plusDays(position);
    }

    private final class Days extends BaseAdapter {
        @Override
        public int getCount() {
            return ((month.atDay(1).getDayOfWeek().getValue() - 1 + month.lengthOfMonth() + 6) / 7) * 7;
        }

        @Override
        public Object getItem(int position) {
            return dayAt(position);
        }

        @Override
        public long getItemId(int position) {
            return dayAt(position).toEpochDay();
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return YearMonth.from(dayAt(position)).equals(month);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            TextView cell = recycled instanceof TextView ? (TextView) recycled
                    : (TextView) LayoutInflater.from(uiContext).inflate(R.layout.item_record_date_day, parent, false);
            LocalDate date = dayAt(position);
            int rowHeight = Math.max(1, parent.getHeight() / (getCount() / 7));
            cell.setMinimumHeight(size(R.dimen.dp_0));
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            float nominal = uiContext.getResources().getDimension(R.dimen.sp_8);
            cell.setTag(nominal);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, nominal);
            // 六行日期的高度由布局保证，不能通过缩小文字掩盖日历被其它控件挤压的问题。
            boolean bounded = preset != RecordDateRange.Preset.ALL;
            boolean first = bounded && date.equals(start.toLocalDate());
            boolean last = bounded && date.equals(end.toLocalDate());
            boolean chosen = first || last;
            boolean inRange = bounded && !date.isBefore(start.toLocalDate()) && !date.isAfter(end.toLocalDate());
            cell.setText(isEnabled(position) ? String.valueOf(date.getDayOfMonth()) : "");
            cell.setEnabled(isEnabled(position));
            cell.setAlpha(isEnabled(position) ? 1f : .35f);
            int background = android.R.color.transparent;
            if (isEnabled(position)) {
                if (first && last) background = R.drawable.mc_button_primary;
                else if (first) background = R.drawable.record_date_range_start;
                else if (last) background = R.drawable.record_date_range_end;
                else if (inRange) background = R.drawable.record_date_range_day;
            }
            cell.setBackgroundResource(background);
            cell.setTextColor(ContextCompat.getColor(uiContext, chosen ? R.color.mc_white
                    : YearMonth.from(date).equals(month) ? R.color.mc_ink : R.color.mc_muted));
            cell.setContentDescription(chosen ? getContext().getString(R.string.record_date_a11y_selected, date.toString()) : date.toString());
            return cell;
        }
    }
}
