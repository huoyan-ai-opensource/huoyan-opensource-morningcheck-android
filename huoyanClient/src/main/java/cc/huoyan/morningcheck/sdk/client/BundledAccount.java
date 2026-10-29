package cc.huoyan.morningcheck.sdk.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 读取发行 JAR 的归属账号；不依赖 Android res 或宿主的运行时配置。
 */
final class BundledAccount {
    /**
     * JAR 根目录的 Java 资源路径，编译宿主 APK 时必须保留。
     */
    private static final String CONFIG_PATH = "/raw/config";

    private BundledAccount() {
    }

    /**
     * 每次构造绑定请求读取配置；错误时不回退到默认账号，也不发送绑定。
     */
    static long getAccountId() {
        try (InputStream input = BundledAccount.class.getResourceAsStream(CONFIG_PATH)) {
            return readAccountId(input);
        } catch (IOException invalid) {
            throw new IllegalStateException("无法读取 JAR 内 raw/config", invalid);
        }
    }

    /**
     * 解析十进制正 long，保留完整精度；输入流由调用者关闭。
     */
    static long readAccountId(InputStream input) throws IOException {
        if (input == null) throw new IllegalStateException("JAR 内缺少 raw/config");
        Properties config = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                // Properties 默认保留最后一个同名键，发行账号不能用这种方式消解歧义。
                if ("Account_id".equals(key) && containsKey(key)) {
                    throw new IllegalStateException("raw/config 的 Account_id 不能重复");
                }
                return super.put(key, value);
            }
        };
        try {
            config.load(input);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("raw/config 格式无效", invalid);
        }
        String value = config.getProperty("Account_id", "").trim();
        if (!value.matches("[0-9]{1,19}")) {
            throw new IllegalStateException("raw/config 的 Account_id 必须为 long 范围内的正整数");
        }
        try {
            long accountId = Long.parseLong(value);
            if (accountId > 0) return accountId;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("raw/config 的 Account_id 超出 long 范围", invalid);
        }
        throw new IllegalStateException("raw/config 的 Account_id 必须大于 0");
    }
}
