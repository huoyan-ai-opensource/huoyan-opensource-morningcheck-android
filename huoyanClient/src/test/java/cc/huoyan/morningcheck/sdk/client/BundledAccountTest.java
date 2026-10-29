package cc.huoyan.morningcheck.sdk.client;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** 验证发行账号读取和精度，配置错误不能回退到其他账号。 */
public class BundledAccountTest {
    @Test public void readsDefaultBundledResource() {
        assertEquals(2097500510197125120L, BundledAccount.getAccountId());
    }

    @Test public void preservesLongPrecisionAndAcceptsComments() throws Exception {
        assertEquals(2097500510197125121L, read("Account_id=2097500510197125121\n"));
        assertEquals(Long.MAX_VALUE, read("Account_id=9223372036854775807\r\n"));
        assertEquals(1L, read("# 客户发行配置\n! 注释\nAccount_id = 1 \n"));
    }

    @Test public void rejectsMissingInvalidAndOverflowingAccounts() {
        assertThrows(IllegalStateException.class, () -> BundledAccount.readAccountId(null));
        assertThrows(IllegalStateException.class, () -> read("account_id=123\n"));
        for (String value : new String[]{"", "0", "-1", "+1", "1.5", "1e3", "abc",
                "9223372036854775808", "12345678901234567890"}) {
            assertThrows(IllegalStateException.class, () -> read("Account_id=" + value));
        }
    }

    @Test public void rejectsRepeatedAccountInsteadOfTakingLastValue() {
        assertThrows(IllegalStateException.class, () -> read("Account_id=1\nAccount_id=2\n"));
        assertThrows(IllegalStateException.class, () -> read("Account_id=1\nAccount_id=1\n"));
        // 即使第二个键通过 Properties 转义，也必须视为同一个发行账号键。
        assertThrows(IllegalStateException.class, () -> read("Account_id=1\nAccount\\u005fid=2\n"));
    }

    private static long read(String text) throws IOException {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            return BundledAccount.readAccountId(stream);
        }
    }
}
