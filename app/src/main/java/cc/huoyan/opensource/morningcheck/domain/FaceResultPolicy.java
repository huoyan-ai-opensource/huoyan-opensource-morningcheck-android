package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人脸写入与查询的终态口径；明确失败可重新采集，未知结果必须先核对。
 */
public final class FaceResultPolicy {
    public enum Registration {SAVED, NO_FACE, REJECTED, UNKNOWN}

    /**
     * 一次解析同时确认编号和图片地址，避免两套列表错配后删除了其他样本。
     */
    public static final class VerifiedSamples {
        public final JSONArray uids;
        public final Map<Long, String> urls;

        private VerifiedSamples(JSONArray uids, Map<Long, String> urls) {
            this.uids = uids;
            this.urls = Collections.unmodifiableMap(new LinkedHashMap<>(urls));
        }
    }

    private FaceResultPolicy() {
    }

    public static Registration registration(int code, JSONObject data) {
        if (code == 40009 || code == -20002) return Registration.UNKNOWN;
        if (code != 0) return Registration.REJECTED;
        Long uid = integer(data == null ? null : data.opt("uid"));
        if (uid == null || uid < 0) return Registration.UNKNOWN;
        return uid == 0 ? Registration.NO_FACE : Registration.SAVED;
    }

    public static boolean isAbsentOrDeleted(int code) {
        return code == 0 || code == -6;
    }

    /**
     * -6 是人脸库目标不存在，不是通信故障；其他错误不允许清空本机登记状态。
     */
    public static JSONArray verifiedUids(int code, JSONObject data) {
        return verifiedSamples(code, data).uids;
    }

    public static VerifiedSamples verifiedSamples(int code, JSONObject data) {
        if (code == -6) return new VerifiedSamples(new JSONArray(), Collections.emptyMap());
        if (code != 0) throw new IllegalArgumentException("SDK 尚未确认人脸明细");
        // 新协议只认同一项里的 UID 和图片；有 items 但内容无效时不能回退旧字段冒充成功。
        if (data != null && data.has("items")) return items(data.opt("items"));
        JSONArray raw = data == null ? null : data.optJSONArray("uids");
        if (raw == null)
            throw new IllegalArgumentException("SDK 人脸明细缺少 uids，未更改本机登记状态");
        JSONArray result = new JSONArray();
        Map<Long, String> urls = new LinkedHashMap<>();
        for (int i = 0; i < raw.length(); i++) {
            Long uid = integer(raw.opt(i));
            if (uid == null || uid <= 0)
                throw new IllegalArgumentException("SDK 人脸编号无效，未更改本机登记状态");
            if (urls.containsKey(uid))
                throw new IllegalArgumentException("SDK 人脸编号重复，未更改本机登记状态");
            result.put(uid);
            urls.put(uid, "");
        }
        // 旧壳没有 items，仅在旧编号列表已确认后按 UID 关联旧图片字段。
        return new VerifiedSamples(result, FaceSamplePresentation.urls(result, data.optJSONArray("samples")));
    }

    private static VerifiedSamples items(Object raw) {
        if (!(raw instanceof JSONArray))
            throw new IllegalArgumentException("SDK 人脸 items 格式无效，未更改本机登记状态");
        JSONArray source = (JSONArray) raw, uids = new JSONArray();
        Map<Long, String> urls = new LinkedHashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            Long uid = integer(item == null ? null : item.opt("uid"));
            if (uid == null || uid <= 0 || urls.containsKey(uid))
                throw new IllegalArgumentException("SDK 人脸 items 编号无效或重复，未更改本机登记状态");
            Object available = item.opt("image_available"), url = item.opt("image_url"), mime = item.opt("mime_type");
            if (!(available instanceof Boolean) || !(url instanceof String) || !(mime instanceof String)
                    || ((Boolean) available && ((String) url).trim().isEmpty()))
                throw new IllegalArgumentException("SDK 人脸 items 图片字段无效，未更改本机登记状态");
            uids.put(uid);
            // 明确无原图的样本仍可删除，但不得尝试加载回执中残留的 URL。
            urls.put(uid, (Boolean) available ? (String) url : "");
        }
        return new VerifiedSamples(uids, urls);
    }

    static Long integer(Object value) {
        if (!(value instanceof Number)) return null;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        try {
            BigInteger exact = value instanceof BigInteger ? (BigInteger) value
                    : value instanceof BigDecimal ? ((BigDecimal) value).toBigIntegerExact() : null;
            if (exact != null) return exact.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || exact.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0 ? null : exact.longValue();
        } catch (ArithmeticException invalid) {
            return null;
        }
        if (!(value instanceof Double) && !(value instanceof Float)) return null;
        double number = ((Number) value).doubleValue();
        // 大 UID 必须保留整数存储；浮点数超过安全整数范围时无法确认原始身份。
        double safe = value instanceof Float ? 0x1.0p24 : 0x1.0p53;
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number >= safe)
            return null;
        return (long) number;
    }
}
