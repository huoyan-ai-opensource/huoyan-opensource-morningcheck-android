package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import org.junit.Test;

public final class SecuritySettingsTest {
    @Test
    public void passwordLimitsAcceptOnlySixToThirtyTwoAsciiDigits() {
        assertFalse(SecuritySettings.validPassword(null));
        assertFalse(SecuritySettings.validPassword("12345".toCharArray()));
        assertFalse(SecuritySettings.validPassword("                                 ".toCharArray()));
        assertFalse(SecuritySettings.validPassword("      ".toCharArray()));
        assertFalse(SecuritySettings.validPassword("\u3000\u00a0\u3000\u00a0\u3000\u00a0".toCharArray()));
        assertTrue(SecuritySettings.validPassword("123456".toCharArray()));
        assertTrue(SecuritySettings.validPassword("000000".toCharArray()));
        for (String value : new String[]{"Abc123", "12345a", "12345-", "12345 ", " 123456",
                "１２３４５６", "١٢٣٤٥٦", "12345\n", "12345\u0000"})
            assertFalse("只能接受 ASCII 数字：" + value, SecuritySettings.validPassword(value.toCharArray()));
        assertTrue(SecuritySettings.validPassword("12345678901234567890123456789012".toCharArray()));
        assertFalse(SecuritySettings.validPassword("123456789012345678901234567890123".toCharArray()));
    }

    @Test
    public void independentSaltPreventsEqualPasswordsHavingEqualStoredValues() {
        char[] password = "123456".toCharArray();
        String first = SecuritySettings.encodePassword(password);
        String second = SecuritySettings.encodePassword(password);
        assertNotEquals(first, second);
        assertNotEquals("123456", first);
        assertTrue(first.matches("pbkdf2-sha1\\$50000\\$[0-9a-f]{32}\\$[0-9a-f]{32}"));
        assertTrue(SecuritySettings.matchesPassword(first, password));
        assertTrue(SecuritySettings.matchesPassword(second, password));
        assertFalse(SecuritySettings.matchesPassword(first, "654321".toCharArray()));
    }

    @Test
    public void storedFormatMatchesIndependentPbkdf2Vector() {
        // Python hashlib 独立计算的数字口令向量，包含必须保留的前导零。
        String stored = "pbkdf2-sha1$50000$000102030405060708090a0b0c0d0e0f$a3310687e589b00524aff6fd3c302dba";
        assertTrue(SecuritySettings.matchesPassword(stored, "001234".toCharArray()));
        assertFalse(SecuritySettings.matchesPassword(stored, "001235".toCharArray()));
        assertFalse(SecuritySettings.matchesPassword(stored.replace("$50000$", "$1300000$"), "001234".toCharArray()));
    }

    @Test
    public void verificationPreservesLeadingZerosAndRejectsWhitespaceAndUnicodeDigits() {
        String stored = SecuritySettings.encodePassword("00123456".toCharArray());
        assertTrue(SecuritySettings.matchesPassword(stored, "00123456".toCharArray()));
        assertFalse(SecuritySettings.matchesPassword(stored, "123456".toCharArray()));
        assertFalse(SecuritySettings.matchesPassword(stored, " 00123456 ".toCharArray()));
        assertFalse(SecuritySettings.matchesPassword(stored, "００１２３４５６".toCharArray()));
    }

    @Test
    public void damagedOrPlaintextStoredValuesNeverBecomeDefaultPassword() {
        char[] password = "123456".toCharArray();
        assertFalse(SecuritySettings.matchesPassword(null, password));
        assertFalse(SecuritySettings.matchesPassword("", password));
        assertFalse(SecuritySettings.matchesPassword("123456", password));
        assertFalse(SecuritySettings.matchesPassword("pbkdf2-sha1$1$00$00", password));
        assertFalse(SecuritySettings.matchesPassword("pbkdf2-sha1$2147483647$00$00", password));
        assertFalse(SecuritySettings.matchesPassword("pbkdf2-sha1$50000$zz$00", password));
        assertFalse(SecuritySettings.matchesPassword("pbkdf2-sha1$50000$000102030405060708090a0b0c0d0e0f$"
                + "8e7437eb29eab691a29431b65aacb9ab8e7437eb29eab691a29431b65aacb9ab", password));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPasswordCannotBeEncoded() {
        SecuritySettings.encodePassword("12345a".toCharArray());
    }
}
