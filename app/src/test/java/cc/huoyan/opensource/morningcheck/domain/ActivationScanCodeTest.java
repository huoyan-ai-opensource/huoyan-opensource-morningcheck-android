package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 扫码协议与手输分开，错误二维码不能触发激活或误转换串号。 */
public final class ActivationScanCodeTest {
    @Test public void groupsSixteenCharacterSerials() {
        assertEquals("Z2FB-W9L7-M78R-586H",
                ActivationScanCode.parse("{\"serial\":\"Z2FBW9L7M78R586H\"}"));
        assertEquals("Z2FB-W9L7-M78R-586H",
                ActivationScanCode.parse("{\"serial\":\" Z2FB-W9L7-M78R-586H \"}"));
    }

    @Test public void preservesScannedCharactersAndOtherSerialLengths() {
        assertEquals("HY2-87IA-VY86-QZXY",
                ActivationScanCode.parse("{\"serial\":\"HY2-87IA-VY86-QZXY\"}"));
        assertEquals("abcd-EF01-Ijkl-OPqr",
                ActivationScanCode.parse("{\"serial\":\"abcdEF01IjklOPqr\"}"));
        assertEquals("SDK-ACT-12345",
                ActivationScanCode.parse("{\"serial\":\" SDK-ACT-12345 \",\"shop_id\":12}"));
    }

    @Test public void rejectsMissingEmptyOrNonStringSerial() {
        for (String value : new String[]{"{}", "{\"shop_id\":12}", "{\"serial\":null}",
                "{\"serial\":0}", "{\"serial\":true}", "{\"serial\":{}}",
                "{\"serial\":[]}", "{\"serial\":\"\"}", "{\"serial\":\"   \"}"})
            assertThrows(value, IllegalArgumentException.class, () -> ActivationScanCode.parse(value));
    }

    @Test public void rejectsBareSerialsMalformedJsonAndTrailingData() {
        for (String value : new String[]{null, "", " ", "Z2FBW9L7M78R586H",
                "[]", "\"Z2FBW9L7M78R586H\"", "{\"serial\":",
                "{\"serial\":\"Z2FBW9L7M78R586H\"}garbage",
                "{\"serial\":\"Z2FBW9L7M78R586H\"}{\"serial\":\"another\"}"})
            assertThrows(IllegalArgumentException.class, () -> ActivationScanCode.parse(value));
    }
}
