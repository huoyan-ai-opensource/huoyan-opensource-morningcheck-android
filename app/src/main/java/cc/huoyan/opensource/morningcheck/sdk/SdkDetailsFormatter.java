package cc.huoyan.opensource.morningcheck.sdk;

import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * SDK 详情仅采用合法字段；未知值明确留空，不把缺少证据展示成未授权或已关闭。
 */
public final class SdkDetailsFormatter {
    private SdkDetailsFormatter() {
    }

    public static String certificate(JSONObject data) {
        if (data == null || data.length() == 0) return "尚未取得授权信息，等待 SDK 返回。";
        Object serial = data.opt("activation_serial");
        String activationCode = serial instanceof String && !((String) serial).trim().isEmpty()
                ? ((String) serial).trim() : "未记录";
        return "激活码：" + activationCode
                + "\n授权类型：" + text(data, "license_type")
                + "\n模型：" + text(data, "model_code") + " · " + text(data, "model_channel")
                + "\n激活时间：" + seconds(data.opt("activated_at"))
                + "\n签发时间：" + seconds(data.opt("issued_at"))
                + "\n最近续期：" + optionalDate(data, "has_renewed_at", "renewed_at", "尚无续期记录")
                + "\n下次续期：" + optionalDate(data, "has_next_renew_at", "next_renew_at", "尚未安排")
                + "\n到期时间：" + seconds(data.opt("expires_at"))
                + "\nSDK 版本：" + text(data, "sdk_version");
    }

    public static String keyState(JSONObject status) {
        String state = status == null ? "" : status.optString("sdk_state");
        if (state.startsWith("KEY_ROTATION_"))
            return MorningSdkState.from(true, status, null).title;
        Object lost = status == null ? null : status.opt("key_lost");
        if (Boolean.TRUE.equals(lost) || "KEY_LOST".equals(state)) return "需要恢复";
        if (Boolean.FALSE.equals(lost) && ("READY".equals(state) || "OFFLINE".equals(state))) return "可用";
        return "待确认";
    }

    public static String seconds(Object value) {
        return seconds(value, ZoneId.systemDefault());
    }

    static String seconds(Object value, ZoneId zone) {
        Long epoch = integer(value);
        // 服务返回 Unix 秒；拒绝常见的毫秒误传，也避免把未知零值展示成 1970 年。
        if (epoch == null || epoch <= 0 || epoch > 253402300799L) return "待确认";
        try {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .withZone(zone).format(Instant.ofEpochSecond(epoch));
        } catch (RuntimeException invalid) {
            return "待确认";
        }
    }

    private static String optionalDate(JSONObject data, String flag, String field, String absent) {
        Object present = data.opt(flag);
        if (Boolean.FALSE.equals(present)) return absent;
        return Boolean.TRUE.equals(present) ? seconds(data.opt(field)) : "待确认";
    }

    public static String download(JSONObject data) {
        if (data == null) return "下载进度尚未返回。";
        Long bytes = integer(data.opt("downloaded")), total = integer(data.opt("total"));
        if (bytes == null || total == null || bytes < 0 || total < 0 || (total > 0 && bytes > total))
            return "下载进度尚未确认，请刷新后查看。";
        if (total == 0) return "已接收 " + size(bytes) + "，下载总量尚未确定。";
        int percent = BigInteger.valueOf(bytes).multiply(BigInteger.valueOf(100))
                .divide(BigInteger.valueOf(total)).intValue();
        return "已下载 " + percent + "%（" + size(bytes) + " / " + size(total) + "）"
                + (bytes.equals(total) ? "\n文件下载完成后仍需等待模型加载及 SDK 就绪。" : "\n请保持网络连接，模型加载完成后即可开始晨检。");
    }

    public static String capabilities(JSONObject data) {
        if (data == null || data.length() == 0) return "尚未取得 SDK 能力，请连接后刷新。";
        Long version = integer(data.opt("api_version"));
        return "接口版本：" + (version != null && version > 0 ? version : "待确认")
                + "\n身份识别：" + capability(data.opt("identify"))
                + "\n头部 / 手部检查：" + capability(data.opt("check"))
                + "\n人员登记：" + capability(data.opt("register"))
                + "\n人员遗忘：" + capability(data.opt("forget"))
                + "\n底库查询：" + capability(data.opt("user_query"))
                + "\n能力支持情况与当前授权、模型是否就绪分别判断。";
    }

    public static Boolean cloudEnabled(JSONObject data) {
        Object value = data == null ? null : data.opt("enabled");
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /**
     * 与壳允许的 http(s) 地址一致；禁止在地址中携带账户、密码或无效端口。
     */
    public static String validateBaseUrl(String input) {
        String value = input == null ? "" : input.trim();
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() > 65535 || uri.getPort() == 0)
                throw new IllegalArgumentException("请输入不含账户密码的有效 http:// 或 https:// 地址");
            return value;
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("请输入不含账户密码的有效 http:// 或 https:// 地址");
        }
    }

    private static String capability(Object value) {
        return Boolean.TRUE.equals(value) ? "支持" : Boolean.FALSE.equals(value) ? "不支持" : "待确认";
    }

    private static Long integer(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                ? ((Number) value).longValue() : null;
    }

    private static String size(long bytes) {
        if (bytes >= 1024 * 1024)
            return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String text(JSONObject data, String key) {
        Object value = data.opt(key);
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : "待确认";
    }
}
