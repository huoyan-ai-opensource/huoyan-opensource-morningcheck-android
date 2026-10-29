package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 两通道共用的纯 JSON 参数约束，不加载 Binder、SharedMemory 或宿主类。
 */
final class RequestParameters {
    private RequestParameters() {
    }

    /**
     * 校验具名入口及 Binder 所需字段；成功不代表业务准入，后者仍由壳决定。
     */
    static void validate(String command, JSONObject p) throws JSONException {
        switch (command) {
            case "activate":
                requiredString(p, "activation_code");
                requiredString(p, "model_code");
                break;
            case "queryLicenseModels":
                requiredString(p, "activation_code");
                break;
            case "queryDeviceModels":
                if (p.length() != 0) throw new IllegalArgumentException("queryDeviceModels 不接受参数");
                break;
            case "setBaseUrl":
                requiredString(p, "url");
                break;
            case "register":
            case "forget":
            case "userDetail":
                positiveId(p, "person_id");
                break;
            case "forgetByUid":
                positiveId(p, "uid");
                break;
            case "bindDevice":
                positiveId(p, "account_id");
                requiredString(p, "shop_id");
                optionalString(p, "shop_name");
                optionalString(p, "shop_no");
                break;
            case "setCloudStatus":
                if (!(p.opt("enabled") instanceof Boolean))
                    throw new IllegalArgumentException("enabled 必须为布尔值");
                break;
            case "submitFeedback":
                validateFeedback(p);
                break;
            case "detectFace":
                faceLimit(p);
                break;
            case "getCapabilities":
            case "getStatus":
            case "getCertInfo":
            case "getDownloadProgress":
            case "identify":
            case "checkHead":
            case "checkHand":
            case "userCount":
            case "uploadPhoto":
            case "init":
            case "getCloudStatus":
            case "get_class_table":
                break;
            default:
                throw new IllegalArgumentException("晨检 SDK 不支持命令：" + command);
        }
    }

    /**
     * WS 省略上限时与壳一致按 64；两通道均拒绝非整数、空值和 int 溢出。
     */
    static int faceLimit(JSONObject p) throws JSONException {
        if (!p.has("max_faces")) return 64;
        Integer limit = optionalInteger(p, "max_faces");
        if (limit == null || limit <= 0)
            throw new IllegalArgumentException("max_faces 必须为正整数");
        return limit;
    }

    static void validateFeedback(JSONObject p) throws JSONException {
        requiredString(p, "request_id");
        requiredString(p, "photo_file_url");
        optionalInteger(p, "feedback_type");
        JSONArray goods = feedbackGoods(p);
        for (int i = 0; i < goods.length(); i++) {
            JSONObject item = goods.getJSONObject(i);
            optionalString(item, "goods_id");
            optionalString(item, "goods_name");
            optionalString(item, "goods_plu");
            optionalString(item, "source");
            optionalInteger(item, "selected");
            optionalNumber(item, "distance");
            optionalNumber(item, "width");
            optionalNumber(item, "height");
            optionalNumber(item, "x");
            optionalNumber(item, "y");
        }
    }

    static JSONArray feedbackGoods(JSONObject p) {
        Object goods = p.opt("goods");
        if (goods != null && goods != JSONObject.NULL && !(goods instanceof JSONArray))
            throw new IllegalArgumentException("goods 必须为数组");
        return goods instanceof JSONArray ? (JSONArray) goods : new JSONArray();
    }

    static String requiredString(JSONObject p, String key) throws JSONException {
        String value = optionalString(p, key);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException(key + " 必须为非空字符串");
        return value;
    }

    static String optionalString(JSONObject p, String key) throws JSONException {
        if (!p.has(key) || p.isNull(key)) return null;
        if (!(p.get(key) instanceof String))
            throw new IllegalArgumentException(key + " 必须为字符串");
        return p.getString(key);
    }

    static Double optionalNumber(JSONObject p, String key) throws JSONException {
        if (!p.has(key) || p.isNull(key)) return null;
        Object value = p.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须为数值");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number))
            throw new IllegalArgumentException(key + " 必须为有限数值");
        return number;
    }

    static Integer optionalInteger(JSONObject p, String key) throws JSONException {
        Double value = optionalNumber(p, key);
        if (value == null) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || value != Math.rint(value))
            throw new IllegalArgumentException(key + " 必须为整数");
        return value.intValue();
    }

    private static void positiveId(JSONObject p, String key) {
        Object value = p.opt(key);
        // 保留完整 int64，不能通过 double 将溢出值误认成另一人员 ID。
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() <= 0)
            throw new IllegalArgumentException(key + " 必须为正整数");
    }
}
