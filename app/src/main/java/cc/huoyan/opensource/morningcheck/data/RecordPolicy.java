package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 保存采集时的原始检测结论和员工资料快照。
 */
public final class RecordPolicy {
    private RecordPolicy() {
    }

    public static boolean healthValid(String expiry, LocalDate today) {
        if (expiry == null || today == null) return false;
        try {
            return !LocalDate.parse(expiry.trim()).isBefore(today);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    public static JSONObject create(String id, JSONObject staff, double temperature, List<String> findings,
                                    long now, LocalDate today, boolean headEnabled) {
        JSONArray issues = new JSONArray(findings);
        boolean healthBad = !healthValid(staff.optString("health_expiry"), today);
        boolean missingTemperature = !Double.isFinite(temperature);
        if (healthBad) issues.put("健康证过期或未登记");
        if (missingTemperature) issues.put("体温未测");
        boolean face = false, hand = false, temp = missingTemperature;
        for (String finding : findings) {
            if (finding.contains("工作帽") || finding.contains("口罩")) face = true;
            if (finding.startsWith("手背") || finding.startsWith("手心")) hand = true;
            if (finding.startsWith("体温")) temp = true;
        }
        return Jsons.obj("id", id, "staff_id", staff.optLong("id"), "staff_name", staff.optString("name"),
                "staff_no", staff.optString("staff_no"), "department", staff.optString("department"),
                "department_id", staff.optLong("department_id", 0), "health_expiry", staff.optString("health_expiry"),
                "created_at", now, "temperature", missingTemperature ? "未测" : String.format(Locale.US, "%.1f", temperature),
                "temperature_source", missingTemperature ? "未测" : "设备采集",
                "head_enabled", headEnabled, "findings", issues, "status", issues.length() == 0 ? 1 : 0,
                "abnormal_health_cert_exp", healthBad ? 1 : 0, "abnormal_temperature", temp ? 1 : 0,
                "abnormal_face", face ? 1 : 0, "abnormal_hand", hand ? 1 : 0,
                "upload", "未上传");
    }
}
