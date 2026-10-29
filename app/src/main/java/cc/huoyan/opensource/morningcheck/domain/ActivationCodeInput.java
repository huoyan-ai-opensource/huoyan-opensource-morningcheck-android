package cc.huoyan.opensource.morningcheck.domain;

/**
 * 手输激活码规则；扫码协议由 SDK 保持独立，不能用此规则改写扫码数据。
 */
public final class ActivationCodeInput {
    public static final String CHARACTERS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    public static final int LENGTH = 16;

    private ActivationCodeInput() {
    }

    /**
     * 检查未分组的草稿；小写及易混淆的 0、I、O 均不自动替换。
     */
    public static boolean accepts(String value) {
        if (value == null || value.length() > LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            if (CHARACTERS.indexOf(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * 只有十六位合法草稿可以提交。
     */
    public static boolean complete(String value) {
        return accepts(value) && value.length() == LENGTH;
    }

    /**
     * 允许原始字符或完整 4-4-4-4 粘贴；不随意删掉错误位置的连字符。
     */
    public static String decode(String value) {
        if (accepts(value)) return value;
        if (value == null || value.length() != 19 || value.charAt(4) != '-'
                || value.charAt(9) != '-' || value.charAt(14) != '-') return null;
        String raw = value.substring(0, 4) + value.substring(5, 9)
                + value.substring(10, 14) + value.substring(15, 19);
        return complete(raw) ? raw : null;
    }

    /**
     * 将合法完整输入规范成后端使用的四段格式，非法输入返回 null。
     */
    public static String normalize(String value) {
        String raw = decode(value);
        if (!complete(raw)) return null;
        return raw.substring(0, 4) + '-' + raw.substring(4, 8) + '-'
                + raw.substring(8, 12) + '-' + raw.substring(12, 16);
    }

    /**
     * 恢复可编辑草稿；旧协议串号不能被裁剪成另一个激活码。
     */
    public static String restore(String value) {
        String raw = decode(value);
        return raw == null ? "" : raw;
    }
}
