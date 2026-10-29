package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.regex.Pattern;

import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.CheckVoiceText;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;
import cc.huoyan.opensource.morningcheck.sdk.SdkDetailsFormatter;

/** 旧数据与领域文案只在显示边界翻译，不能改变历史记录、设备回执或用户数据。 */
@RunWith(AndroidJUnit4.class)
public final class LocalizationMessageTest {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");

    @Test public void checkStagesAndCombinedFindingsTranslateWithoutChangingOriginalValues() {
        Context english = context(Locale.ENGLISH);
        Context chinese = context(Locale.SIMPLIFIED_CHINESE);
        for (CheckPipeline.Stage stage : CheckPipeline.Stage.values()) {
            for (boolean mask : new boolean[]{false, true}) {
                for (boolean hat : new boolean[]{false, true}) {
                    String original = CheckVoiceText.stage(stage, mask, hat);
                    assertEnglish(UiMessages.text(english, original));
                    assertEquals(original, UiMessages.text(chinese, original));
                }
            }
        }
        String stored = "未戴口罩；手背：戒指、伤口等3项\n请伸出双手手背";
        String displayed = UiMessages.text(english, stored);
        assertEnglish(displayed);
        assertTrue(displayed, displayed.contains("Ring"));
        assertTrue(displayed, displayed.contains("Wound"));
        assertTrue(displayed, displayed.contains("3"));
        assertEquals(stored, UiMessages.text(chinese, stored));
        assertEnglish(UiMessages.text(english, "体温偏高：37.8°C"));
        assertEnglish(UiMessages.text(english, CheckVoiceText.result(true)));
        assertEnglish(UiMessages.text(english, CheckVoiceText.result(false)));
    }

    @Test public void sdkPhasesAndDownloadProgressUseEnglishAtTheBoundary() throws Exception {
        Context english = context(Locale.ENGLISH);
        for (MorningSdkState.Phase phase : MorningSdkState.Phase.values()) {
            JSONObject status = new JSONObject().put("sdk_state", phase.name())
                    .put("certificate_valid", true).put("model_ready", true)
                    .put("key_lost", phase == MorningSdkState.Phase.KEY_LOST)
                    .put("busy", phase == MorningSdkState.Phase.BUSY).put("busy_operation", "checkHand");
            MorningSdkState state = MorningSdkState.from(phase != MorningSdkState.Phase.DISCONNECTED,
                    status, new JSONObject().put("downloaded", 73).put("total", 100));
            assertEnglish(UiMessages.text(english, state.title));
            assertEnglish(UiMessages.text(english, state.detail));
        }
        String download = SdkDetailsFormatter.download(new JSONObject().put("downloaded", 73).put("total", 100));
        String displayed = UiMessages.text(english, download);
        assertEnglish(displayed);
        assertTrue(displayed, displayed.contains("73%"));
    }

    @Test public void certificateLabelsTranslateWhileIdentifiersRemainIntact() throws Exception {
        JSONObject certificate = new JSONObject().put("activation_serial", "DEMO123456789XYZ")
                .put("license_type", "TRIAL").put("model_code", "MODEL_ABC")
                .put("model_channel", "stable").put("sdk_version", "2.0.0")
                .put("activated_at", 1793232000L).put("issued_at", 1793232000L)
                .put("expires_at", 1824768000L).put("has_renewed_at", false)
                .put("has_next_renew_at", false);
        String originalJson = certificate.toString();
        String displayed = UiMessages.text(context(Locale.ENGLISH), SdkDetailsFormatter.certificate(certificate));
        assertEnglish(displayed);
        assertTrue(displayed, displayed.contains("DEMO123456789XYZ"));
        assertTrue(displayed, displayed.contains("MODEL_ABC"));
        assertTrue(displayed, displayed.contains("2.0.0"));
        assertEquals(originalJson, certificate.toString());

        JSONObject localizedIdentifiers = new JSONObject(certificate.toString())
                .put("activation_serial", "手背").put("license_type", "初始化")
                .put("model_code", "手背").put("model_channel", "初始化").put("sdk_version", "手背");
        String identifiersJson = localizedIdentifiers.toString();
        String identifiersDisplayed = UiMessages.text(context(Locale.ENGLISH),
                SdkDetailsFormatter.certificate(localizedIdentifiers));
        assertTrue(identifiersDisplayed, identifiersDisplayed.contains("Activation code: 手背"));
        assertTrue(identifiersDisplayed, identifiersDisplayed.contains("License type: 初始化"));
        assertTrue(identifiersDisplayed, identifiersDisplayed.contains("Model: 手背 · 初始化"));
        assertTrue(identifiersDisplayed, identifiersDisplayed.contains("SDK version: 手背"));
        assertEquals(identifiersJson, localizedIdentifiers.toString());

        assertEnglish(UiMessages.text(context(Locale.ENGLISH), SdkDetailsFormatter.certificate(new JSONObject()
                .put("has_renewed_at", false).put("has_next_renew_at", false))));
    }

    @Test public void unknownUserTextIsNotRewrittenByMessageFragments() {
        Context english = context(Locale.ENGLISH);
        for (String value : new String[]{"正常餐饮有限公司", "研发部", "张三", "手背：研发部", "研发部等3项", "张三 · 正常餐饮有限公司"})
            assertEquals(value, UiMessages.text(english, value));
    }

    @Test public void systemLanguagePreferencesKeepResourcesAndPromptsInTheSameLanguage() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 24);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(target.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.FRANCE, Locale.US));
        Context fallbackEnglish = target.createConfigurationContext(configuration);
        assertEquals("Normal", fallbackEnglish.getString(R.string.i18n_b_normal));
        assertTrue(UiMessages.isEnglish(fallbackEnglish));
        assertEnglish(UiMessages.text(fallbackEnglish, CheckVoiceText.result(true)));
        assertTutorialLanguage(fallbackEnglish, true);

        configuration.setLocales(new LocaleList(Locale.SIMPLIFIED_CHINESE, Locale.US));
        Context chineseFirst = target.createConfigurationContext(configuration);
        assertEquals("正常", chineseFirst.getString(R.string.i18n_b_normal));
        assertFalse(UiMessages.isEnglish(chineseFirst));
        assertEquals("晨检正常", UiMessages.text(chineseFirst, CheckVoiceText.result(true)));
        assertTutorialLanguage(chineseFirst, false);

        configuration.setLocales(new LocaleList(Locale.US, Locale.SIMPLIFIED_CHINESE));
        Context englishFirst = target.createConfigurationContext(configuration);
        assertEquals("Normal", englishFirst.getString(R.string.i18n_b_normal));
        assertTrue(UiMessages.isEnglish(englishFirst));
        assertTutorialLanguage(englishFirst, true);
    }

    private void assertTutorialLanguage(Context context, boolean english) {
        TypedValue animation = new TypedValue();
        TypedValue preview = new TypedValue();
        context.getResources().getValue(R.raw.mc_check_tutorial, animation, true);
        context.getResources().getValue(R.drawable.mc_check_tutorial_preview, preview, true);
        assertTrue(animation.string.toString(), animation.string.toString().contains(
                english ? "raw-en/" : "mc_check_tutorial_zh.gif"));
        assertTrue(preview.string.toString(), preview.string.toString().contains(
                english ? "drawable-en-nodpi" : "mc_check_tutorial_preview_zh.png"));
    }

    private Context context(Locale locale) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(target.getResources().getConfiguration());
        configuration.setLocale(locale);
        return target.createConfigurationContext(configuration);
    }

    private static void assertEnglish(String value) {
        assertFalse("英文显示中残留中文：" + value, HAN.matcher(value).find());
    }
}
