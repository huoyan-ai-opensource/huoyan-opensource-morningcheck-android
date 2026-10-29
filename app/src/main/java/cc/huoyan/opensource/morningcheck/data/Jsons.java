package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

public final class Jsons {
    private Jsons() {
    }

    public static JSONObject obj(Object... values) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
            return out;
        } catch (JSONException error) {
            throw new IllegalArgumentException("数据格式无效", error);
        }
    }

    public static JSONObject copy(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException error) {
            throw new IllegalArgumentException("数据格式无效", error);
        }
    }

    public static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException("数据格式无效", error);
        }
    }

    /**
     * 基于执行时的最新快照合并字段，调用方不得传入整份旧记录。
     */
    public static JSONObject patch(JSONObject current, JSONObject changes) {
        if (current == null)
            throw new IllegalStateException("本地数据已删除，不能由异步回调重新创建");
        JSONObject updated = copy(current);
        Iterator<String> keys = changes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("id".equals(key) || "created_at".equals(key)) {
                throw new IllegalArgumentException("不可修改本地数据标识或创建时间");
            }
            put(updated, key, changes.opt(key));
        }
        return updated;
    }
}
