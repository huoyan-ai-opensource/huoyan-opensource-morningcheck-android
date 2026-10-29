package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 样本的界面序号取当前列表位置，SDK UID 只用于查询和删除。
 */
public final class FaceSamplePresentation {
    private FaceSamplePresentation() {
    }

    public static int ordinal(JSONArray uids, long uid) {
        if (uids != null) for (int i = 0; i < uids.length(); i++)
            if (uids.optLong(i) == uid) return i + 1;
        return 0;
    }

    public static Map<Long, String> urls(JSONArray uids, JSONArray samples) {
        Map<Long, String> result = new LinkedHashMap<>();
        if (uids != null) for (int i = 0; i < uids.length(); i++) result.put(uids.optLong(i), "");
        if (samples != null) for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.optJSONObject(i);
            Long uid = FaceResultPolicy.integer(sample == null ? null : sample.opt("uid"));
            // 旧字段也按精确编号关联，失精度浮点数不能误配到另一个已确认的 UID。
            if (uid != null && uid > 0 && result.containsKey(uid))
                result.put(uid, sample.optString("image_url"));
        }
        return result;
    }
}
