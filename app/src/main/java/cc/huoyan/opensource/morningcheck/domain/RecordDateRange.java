package cc.huoyan.opensource.morningcheck.domain;

import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;

/**
 * 日期筛选保存选择意图，查询时再按本机时区计算范围，避免“当天”跨日后仍查昨天。
 */
public final class RecordDateRange {
    public enum Preset {TODAY, LAST_24_HOURS, LAST_7_DAYS, LAST_14_DAYS, LAST_30_DAYS, ALL, CUSTOM}

    private static final long MINUTE = 60_000L;
    private static final long DAY = 24L * 60 * MINUTE;
    private final Preset preset;
    private final long customStart, customEnd;
    private final boolean followNow;

    private RecordDateRange(Preset preset, long start, long end, boolean followNow) {
        this.preset = preset;
        this.customStart = start;
        this.customEnd = end;
        this.followNow = followNow;
    }

    public static RecordDateRange all() {
        return preset(Preset.ALL);
    }

    public static RecordDateRange today() {
        return preset(Preset.TODAY);
    }

    public static RecordDateRange preset(Preset preset) {
        if (preset == null || preset == Preset.CUSTOM)
            throw new IllegalArgumentException("日期快捷筛选无效");
        return new RecordDateRange(preset, 0, 0, false);
    }

    public static RecordDateRange custom(long startMinuteMillis, long endMinuteMillis, boolean followNow) {
        if (startMinuteMillis < 0 || endMinuteMillis < 0 || endMinuteMillis > Long.MAX_VALUE - MINUTE)
            throw new IllegalArgumentException("日期时间范围无效");
        long start = startMinuteMillis / MINUTE * MINUTE;
        long end = endMinuteMillis / MINUTE * MINUTE;
        if (!followNow && start > end)
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        return new RecordDateRange(Preset.CUSTOM, start, end, followNow);
    }

    public Preset preset() {
        return preset;
    }

    public long customStart() {
        return customStart;
    }

    public long customEnd() {
        return customEnd;
    }

    public boolean followsNow() {
        return preset == Preset.CUSTOM && followNow;
    }

    public Bounds resolvedAt(long nowMillis, TimeZone zone) {
        if (preset == Preset.ALL) return Bounds.all();
        if (nowMillis < 0 || nowMillis == Long.MAX_VALUE || zone == null)
            throw new IllegalArgumentException("查询时间或时区无效");
        if (preset == Preset.CUSTOM) {
            // 固定截止时间包含该分钟的全部秒；跟随当前时刻只包含已经发生的记录。
            return Bounds.between(customStart, followNow ? nowMillis + 1 : customEnd + MINUTE);
        }
        if (preset == Preset.LAST_24_HOURS) return Bounds.between(nowMillis - DAY, nowMillis + 1);

        Calendar start = Calendar.getInstance(zone);
        start.setTimeInMillis(nowMillis);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 1);
        int days = preset == Preset.LAST_7_DAYS ? 7 : preset == Preset.LAST_14_DAYS ? 14
                                                      : preset == Preset.LAST_30_DAYS ? 30 : 1;
        // 近 N 天包含今天，按自然日计算；使用日历加减以兼容夏令时切换。
        start.add(Calendar.DAY_OF_MONTH, 1 - days);
        return Bounds.between(start.getTimeInMillis(), end.getTimeInMillis());
    }

    public String serialize() {
        return "1|" + preset.name() + "|" + customStart + "|" + customEnd + "|" + followNow;
    }

    public static RecordDateRange deserialize(String saved) {
        if (saved == null) return all();
        try {
            String[] values = saved.split("\\|", -1);
            if (values.length != 5 || !"1".equals(values[0])) return all();
            Preset preset = Preset.valueOf(values[1]);
            if (preset != Preset.CUSTOM) return preset(preset);
            if (!"true".equals(values[4]) && !"false".equals(values[4])) return all();
            return custom(Long.parseLong(values[2]), Long.parseLong(values[3]), Boolean.parseBoolean(values[4]));
        } catch (RuntimeException invalid) {
            return all();
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RecordDateRange)) return false;
        RecordDateRange value = (RecordDateRange) other;
        return preset == value.preset && customStart == value.customStart && customEnd == value.customEnd
                && followNow == value.followNow;
    }

    @Override
    public int hashCode() {
        return Objects.hash(preset, customStart, customEnd, followNow);
    }

    /**
     * SQLite 使用左闭右开区间，计数与分页共用同一个已解析快照。
     */
    public static final class Bounds {
        public final boolean bounded;
        public final long startInclusive, endExclusive;

        private Bounds(boolean bounded, long startInclusive, long endExclusive) {
            this.bounded = bounded;
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }

        public static Bounds all() {
            return new Bounds(false, 0, 0);
        }

        public static Bounds between(long startInclusive, long endExclusive) {
            if (endExclusive <= startInclusive)
                throw new IllegalArgumentException("开始时间不能晚于结束时间");
            return new Bounds(true, startInclusive, endExclusive);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Bounds)) return false;
            Bounds value = (Bounds) other;
            return bounded == value.bounded && startInclusive == value.startInclusive && endExclusive == value.endExclusive;
        }

        @Override
        public int hashCode() {
            return Objects.hash(bounded, startInclusive, endExclusive);
        }
    }
}
