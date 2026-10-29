package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 云端名册只更新业务资料，不推断新 SDK 是否已登记人脸，也不改变本机启停。
 */
public final class StaffPolicy {
    private StaffPolicy() {
    }

    public static String requireStaffNumber(String value) {
        String number = value == null ? "" : value.trim();
        if (number.isEmpty()) throw new IllegalArgumentException("请输入工号，工号不能为空");
        return number;
    }

    public static JSONObject fromCloud(JSONObject existing, JSONObject remote, long expectedId, long now) {
        if (remote == null) throw new IllegalArgumentException("云端员工资料为空");
        long id = remote.optLong("id", 0);
        if (id <= 0 || (expectedId > 0 && id != expectedId)) {
            throw new IllegalArgumentException("云端员工编号缺失或与请求不一致，未覆盖本地档案");
        }
        if (existing != null && existing.optLong("id") != id) {
            throw new IllegalArgumentException("本机与云端员工编号不一致");
        }
        String name = text(remote, "name").trim();
        if (name.isEmpty())
            throw new IllegalArgumentException("云端员工资料缺少姓名，未覆盖本地档案");
        JSONObject out = existing == null
                ? Jsons.obj("id", id, "created_at", now, "enabled", true, "face_registered", false)
                : Jsons.copy(existing);
        Jsons.put(out, "name", name);
        Jsons.put(out, "department", text(remote, "department"));
        // 云端只给部门文本，本地层级 ID 须重新解析，不能沿用上次导入的归属。
        out.remove("department_id");
        Jsons.put(out, "staff_no", requireStaffNumber(text(remote, "staff_no")));
        Jsons.put(out, "avatar", text(remote, "avatar"));
        // 云端清空或撤回健康证时也必须清空本机日期，避免沿用旧的未来有效期。
        String expiry = text(remote, "health_cert_expire_time").trim();
        if (expiry.length() >= 10) expiry = expiry.substring(0, 10);
        try {
            expiry = LocalDate.parse(expiry).toString();
        } catch (DateTimeParseException invalid) {
            expiry = "";
        }
        Jsons.put(out, "health_expiry", expiry);
        Jsons.put(out, "cloud_synced_at", now);
        return out;
    }

    public static JSONArray pageRecords(JSONObject response) {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        JSONArray records = data == null ? null : data.optJSONArray("records");
        if (records == null)
            throw new IllegalArgumentException("云端名册响应缺少 data.records，未导入");
        return records;
    }

    public static int pageCount(JSONObject response) {
        JSONObject data = response == null ? null : response.optJSONObject("data");
        int pages = data == null ? -1 : data.optInt("pages", -1);
        if (pages < 0 || pages > 1000)
            throw new IllegalArgumentException("云端名册分页信息无效，未导入");
        if (pages == 0 && pageRecords(response).length() != 0) {
            throw new IllegalArgumentException("云端名册页数和内容不一致，未导入");
        }
        return pages;
    }

    private static String text(JSONObject value, String key) {
        return value.isNull(key) ? "" : value.optString(key, "");
    }
}
