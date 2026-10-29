package cc.huoyan.opensource.morningcheck.domain;

import static org.junit.Assert.*;

import org.junit.Test;

/** 手输格式与扫码串号保持独立，错误字符不能悄悄变成另一个激活码。 */
public final class ActivationCodeInputTest {
    private static final String RAW = "Z2FBW9L7M78R586H";
    private static final String GROUPED = "Z2FB-W9L7-M78R-586H";

    @Test public void legalAlphabetKeepsLAndRejectsAmbiguousOrLowercaseCharacters() {
        assertTrue(ActivationCodeInput.accepts("123456789"));
        assertTrue(ActivationCodeInput.accepts("ABCDEFGHJKLMNPQ"));
        assertTrue(ActivationCodeInput.accepts("RSTUVWXYZ"));
        for (String invalid : new String[]{null, "0", "I", "O", "a", "中文", " A", "A\n"})
            assertFalse("非法字符不能自动改写：" + invalid, ActivationCodeInput.accepts(invalid));
    }

    @Test public void onlyExactlySixteenLegalCharactersCanBeSubmitted() {
        assertTrue(ActivationCodeInput.accepts(""));
        assertFalse(ActivationCodeInput.complete(""));
        assertFalse(ActivationCodeInput.complete(RAW.substring(0, 15)));
        assertTrue(ActivationCodeInput.complete(RAW));
        assertFalse(ActivationCodeInput.accepts(RAW + "A"));
        assertFalse(ActivationCodeInput.complete(GROUPED));
    }

    @Test public void rawAndCorrectlyGroupedPasteProduceTheSameBackendCode() {
        assertEquals(RAW, ActivationCodeInput.decode(RAW));
        assertEquals(RAW, ActivationCodeInput.decode(GROUPED));
        assertEquals(GROUPED, ActivationCodeInput.normalize(RAW));
        assertEquals(GROUPED, ActivationCodeInput.normalize(GROUPED));
        assertEquals("Z2FB", ActivationCodeInput.decode("Z2FB"));
        assertNull(ActivationCodeInput.normalize("Z2FB"));
    }

    @Test public void malformedSeparatorsAndScanSerialAreNotSilentlySanitized() {
        for (String invalid : new String[]{"Z2F-BW9L7-M78R-586H", "Z2FB--W9L7-M78R-586H",
                "Z2FB W9L7 M78R 586H", "Z2FB-W9L7-M78R-586h", "TEST-ONLY-LICENSE-ONE"}) {
            assertNull("错误格式不能被纠正后提交：" + invalid, ActivationCodeInput.decode(invalid));
            assertNull(ActivationCodeInput.normalize(invalid));
            assertEquals("旧协议串号不能被截断成手输草稿", "", ActivationCodeInput.restore(invalid));
        }
        assertEquals(RAW, ActivationCodeInput.restore(GROUPED));
        assertEquals("Z2FBW9", ActivationCodeInput.restore("Z2FBW9"));
    }
}
