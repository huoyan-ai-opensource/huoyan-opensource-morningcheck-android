package cc.huoyan.opensource.morningcheck.sdk;

import org.json.JSONObject;
import org.junit.Test;
import java.time.ZoneOffset;
import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;
import static org.junit.Assert.*;

public final class SdkDetailsFormatterTest {
    @Test public void activationCodeUsesRecordedShellSerialWithoutInventingAnOldCode() {
        assertTrue(SdkDetailsFormatter.certificate(obj("activation_serial", "  SAMPLE-ONLY  "))
                .startsWith("激活码：SAMPLE-ONLY\n"));
        for (Object value : new Object[]{JSONObject.NULL, "", "  ", 123})
            assertTrue(SdkDetailsFormatter.certificate(obj("activation_serial", value))
                    .startsWith("激活码：未记录\n"));
        assertTrue(SdkDetailsFormatter.certificate(obj("license_type", "正式授权"))
                .startsWith("激活码：未记录\n"));
        assertEquals("尚未取得授权信息，等待 SDK 返回。", SdkDetailsFormatter.certificate(obj()));
    }
    @Test public void keyStateRequiresRealSdkFacts() {
        assertEquals("可用", SdkDetailsFormatter.keyState(obj("sdk_state", "READY", "key_lost", false)));
        assertEquals("可用", SdkDetailsFormatter.keyState(obj("sdk_state", "OFFLINE", "key_lost", false)));
        assertEquals("需要恢复", SdkDetailsFormatter.keyState(obj("sdk_state", "OFFLINE", "key_lost", true)));
        assertEquals("需要恢复", SdkDetailsFormatter.keyState(obj("sdk_state", "READY", "key_lost", true)));
        assertEquals("需要恢复", SdkDetailsFormatter.keyState(obj("sdk_state", "KEY_LOST")));
        for (JSONObject value : new JSONObject[]{obj(), obj("sdk_state", "READY"), obj("sdk_state", "OFFLINE"),
                obj("sdk_state", "READY", "key_lost", "false"), obj("key_lost", false)})
            assertEquals("待确认", SdkDetailsFormatter.keyState(value));
        assertEquals("设备身份待审核", SdkDetailsFormatter.keyState(obj("sdk_state", "KEY_ROTATION_PENDING")));
        assertEquals("设备身份审核未通过", SdkDetailsFormatter.keyState(obj("sdk_state", "KEY_ROTATION_REJECTED")));
        assertEquals("正在恢复设备身份", SdkDetailsFormatter.keyState(obj("sdk_state", "KEY_ROTATION_RESTORING")));
    }
    @Test public void unixSecondsUseRequestedTimeZone() {
        assertEquals("2026-01-01 00:00", SdkDetailsFormatter.seconds(1767225600L, ZoneOffset.UTC));
        assertEquals("2026-01-01 08:00", SdkDetailsFormatter.seconds(1767225600L, ZoneOffset.ofHours(8)));
    }
    @Test public void unknownAndMillisecondsDoNotBecomeValidDates() {
        for (Object value : new Object[]{null, JSONObject.NULL, 0, -1, "1767225600", 1767225600000L, 12.5})
            assertEquals("待确认", SdkDetailsFormatter.seconds(value));
    }
    @Test public void renewalPresenceFlagsOverrideStaleNumericValues() {
        String value = SdkDetailsFormatter.certificate(obj("has_next_renew_at", false,
                "next_renew_at", 1767225600L, "has_renewed_at", false, "renewed_at", 1767225600L));
        assertTrue(value.contains("下次续期：尚未安排"));
        assertTrue(value.contains("最近续期：尚无续期记录"));
        assertTrue(SdkDetailsFormatter.certificate(obj("next_renew_at", 1767225600L)).contains("下次续期：待确认"));
    }
    @Test public void unfinishedDownloadCannotRoundUpToCompletion() {
        String value = SdkDetailsFormatter.download(obj("downloaded", Long.MAX_VALUE - 1, "total", Long.MAX_VALUE));
        assertTrue(value.contains("99%"));
        assertFalse(value.contains("100%"));
    }
    @Test public void unknownTotalsAndInvalidDownloadFieldsDoNotInventProgress() {
        assertTrue(SdkDetailsFormatter.download(obj("downloaded", 1024, "total", 0)).contains("总量尚未确定"));
        for (JSONObject value : new JSONObject[]{obj(), obj("downloaded", -1, "total", 10),
                obj("received", 5, "total", 10), obj("downloaded", 11, "total", 10), obj("downloaded", "5", "total", 10)})
            assertFalse(SdkDetailsFormatter.download(value).contains("%"));
    }
    @Test public void downloadCompletionDoesNotClaimEngineReady() {
        assertTrue(SdkDetailsFormatter.download(obj("downloaded", 5, "total", 5)).contains("仍需等待"));
    }
    @Test public void capabilityAndCloudFieldsRequireRealBooleans() {
        String value = SdkDetailsFormatter.capabilities(obj("identify", true, "check", false, "register", "true"));
        assertTrue(value.contains("身份识别：支持"));
        assertTrue(value.contains("头部 / 手部检查：不支持"));
        assertTrue(value.contains("人员登记：待确认"));
        assertNull(SdkDetailsFormatter.cloudEnabled(obj("enabled", "true")));
        assertNull(SdkDetailsFormatter.cloudEnabled(obj()));
        assertEquals(Boolean.FALSE, SdkDetailsFormatter.cloudEnabled(obj("enabled", false)));
        assertEquals(Boolean.TRUE, SdkDetailsFormatter.cloudEnabled(obj("enabled", true)));
    }
    @Test public void cloudAddressesAcceptHttpAndHttpsWithoutCredentials() {
        assertEquals("https://example.test/api", SdkDetailsFormatter.validateBaseUrl(" https://example.test/api "));
        assertEquals("http://192.168.1.2:8080", SdkDetailsFormatter.validateBaseUrl("http://192.168.1.2:8080"));
        for (String value : new String[]{"", "ws://example.test", "https://", "https://user:password@example.test",
                "http://example.test:99999", "https://example.test#secret", "http://example.test:0"}) {
            try { SdkDetailsFormatter.validateBaseUrl(value); fail("应拒绝非法地址"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }
}
