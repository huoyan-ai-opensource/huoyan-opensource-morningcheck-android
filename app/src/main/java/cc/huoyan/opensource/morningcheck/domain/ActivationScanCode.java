package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * 激活二维码沿用零售协议：读取 JSON serial，不把扫码串号套入手输字符规则。
 */
public final class ActivationScanCode {
    private ActivationScanCode() {
    }

    /**
     * 解析有效激活二维码；错误内容只返回协议错误，避免回显二维码或激活码。
     */
    public static String parse(String text) {
        if (text == null || text.trim().isEmpty()) throw invalid();
        try {
            JSONTokener source = new JSONTokener(text.trim());
            Object value = source.nextValue();
            if (!(value instanceof JSONObject) || source.nextClean() != 0) throw invalid();
            Object serial = ((JSONObject) value).opt("serial");
            if (!(serial instanceof String) || ((String) serial).trim().isEmpty()) throw invalid();
            String code = ((String) serial).trim();
            String plain = code.replace("-", "");
            // 兼容零售提交格式；其他长度及字符属于 native 协议，不在客户端猜测或截断。
            if (plain.length() != 16) return code;
            return plain.substring(0, 4) + '-' + plain.substring(4, 8) + '-'
                    + plain.substring(8, 12) + '-' + plain.substring(12, 16);
        } catch (JSONException malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("二维码未包含有效的激活信息，请使用激活二维码。");
    }
}
