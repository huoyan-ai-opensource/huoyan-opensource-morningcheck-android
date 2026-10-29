package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;

/**
 * 日期入口与弹窗由列表页面持有；离页关闭，重排时由页面显式保存并恢复草稿。
 */
final class RecordDateFilterUi {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.CHINA);
    private final Activity activity;
    private final TextView trigger;
    private final Consumer<RecordDateRange> changed;
    private RecordDateRange range;
    private RecordDateFilterDialog dialog;

    RecordDateFilterUi(Activity activity, TextView trigger, RecordDateRange initial, Consumer<RecordDateRange> changed) {
        this.activity = activity;
        this.trigger = trigger;
        this.changed = changed;
        range = initial == null ? RecordDateRange.all() : initial;
        trigger.setOnClickListener(view -> show(null));
        refreshLabel();
    }

    void replace(RecordDateRange range) {
        this.range = range == null ? RecordDateRange.all() : range;
        refreshLabel();
    }

    void refreshLabel() {
        int label = presetLabel(range.preset());
        if (label != 0) {
            setLabel(range.preset() == RecordDateRange.Preset.ALL ? activity.getString(R.string.i18n_b_date_range) : activity.getString(label));
            trigger.setContentDescription(activity.getString(R.string.record_date_filter_label) + "，" + activity.getString(label));
            return;
        }
        String start = formatted(range.customStart());
        String end = range.followsNow() ? activity.getString(R.string.record_date_now) : formatted(range.customEnd());
        setLabel(activity.getString(R.string.i18n_b_custom_date));
        trigger.setContentDescription(activity.getString(R.string.record_date_full_range,
                activity.getString(R.string.record_date_filter_label), start, end));
    }

    private void setLabel(CharSequence text) {
        if (!TextUtils.equals(trigger.getText(), text)) trigger.setText(text);
    }

    Bundle saveState() {
        Bundle saved = new Bundle();
        saved.putBoolean("showing", dialog != null && dialog.isShowing());
        if (dialog != null && dialog.isShowing()) saved.putBundle("draft", dialog.saveDraft());
        return saved;
    }

    void restoreState(Bundle saved) {
        if (saved != null && saved.getBoolean("showing")) show(saved.getBundle("draft"));
    }

    void dismiss() {
        RecordDateFilterDialog previous = dialog;
        dialog = null;
        if (previous != null) previous.dismiss();
    }

    private void show(Bundle draft) {
        if (activity.isFinishing() || activity.isDestroyed() || (dialog != null && dialog.isShowing()))
            return;
        RecordDateFilterDialog next = new RecordDateFilterDialog(activity, range, selected -> {
            range = selected;
            refreshLabel();
            if (changed != null) changed.accept(selected);
        });
        next.restoreDraft(draft);
        dialog = next;
        next.setOnDismissListener(ignored -> {
            if (dialog == next) dialog = null;
        });
        next.show();
    }

    private ZonedDateTime at(long millis) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
    }

    private String formatted(long millis) {
        return SHORT.format(at(millis));
    }

    private int presetLabel(RecordDateRange.Preset preset) {
        switch (preset) {
            case TODAY:
                return R.string.record_date_today;
            case LAST_24_HOURS:
                return R.string.record_date_24_hours;
            case LAST_7_DAYS:
                return R.string.record_date_7_days;
            case LAST_14_DAYS:
                return R.string.record_date_14_days;
            case LAST_30_DAYS:
                return R.string.record_date_30_days;
            case ALL:
                return R.string.record_date_all;
            default:
                return 0;
        }
    }
}
