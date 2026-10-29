package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 员工列表与详情共用的展示文案，不修改档案或晨检准入规则。
 */
public final class StaffPresentation {
    private StaffPresentation() {
    }

    public static String number(JSONObject person) {
        String value = text(person, "staff_no");
        return value.isEmpty() ? "无" : value;
    }

    public static String department(JSONObject person) {
        String value = text(person, "department");
        return value.isEmpty() ? "未分配" : value;
    }

    public static boolean enabled(JSONObject person) {
        return person == null || person.optBoolean("enabled", true);
    }

    public static Health health(JSONObject person, LocalDate today) {
        Objects.requireNonNull(today, "展示健康证状态需要当前日期");
        String value = text(person, "health_expiry");
        if (!value.isEmpty()) {
            try {
                LocalDate expiry = LocalDate.parse(value);
                // 到期日当天仍未过期；空值或非法日期不能显示为有效健康证。
                boolean expired = expiry.isBefore(today);
                return new Health(expiry.toString(), expired ? "已过期" : "未过期",
                        expired ? HealthStatus.EXPIRED : HealthStatus.VALID);
            } catch (DateTimeParseException invalid) {
                // 展示层只回退缺省文案，保留原档案供编辑页修正。
            }
        }
        return new Health("无", "未分配", HealthStatus.UNASSIGNED);
    }

    private static String text(JSONObject person, String key) {
        return person == null || person.isNull(key) ? "" : person.optString(key, "").trim();
    }

    public enum HealthStatus {UNASSIGNED, VALID, EXPIRED}

    public static final class Health {
        public final String expiry;
        public final String state;
        public final HealthStatus status;

        private Health(String expiry, String state, HealthStatus status) {
            this.expiry = expiry;
            this.state = state;
            this.status = status;
        }
    }
}
