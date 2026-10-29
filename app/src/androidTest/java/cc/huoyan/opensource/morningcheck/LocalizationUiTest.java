package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;

/** 验证语言资源、实际绑定与关键操作的排版，不访问业务云或操作真实硬件。 */
@RunWith(AndroidJUnit4.class)
public final class LocalizationUiTest {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final int[][] WINDOWS = {{1280, 800}, {800, 1280}, {360, 800}};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void configurationContextsChooseLanguageWithoutChangingProcessLocale() {
        Locale original = Locale.getDefault();
        Context english = context(Locale.ENGLISH, 1280, 800);
        Context chinese = context(Locale.SIMPLIFIED_CHINESE, 1280, 800);
        assertEquals("Normal", english.getString(R.string.i18n_b_normal));
        assertEquals("正常", chinese.getString(R.string.i18n_b_normal));
        assertEquals("Abnormal", english.getString(R.string.i18n_b_abnormal));
        assertEquals("异常", chinese.getString(R.string.i18n_b_abnormal));
        assertEquals("创建局部语言 Context 不能改变系统或进程语言", original, Locale.getDefault());
        assertEquals("未知文案不能被局部替换", "正常餐饮有限公司 · 员工甲",
                UiMessages.text(english, "正常餐饮有限公司 · 员工甲"));
    }

    @Test public void englishStaticPagesAndDialogsResolveTranslatedTextAndFormatArguments() {
        instrumentation.runOnMainSync(() -> {
            Context english = context(Locale.ENGLISH, 1280, 800);
            for (int resource : new int[]{R.layout.activity_home_design, R.layout.activity_check_design_ready,
                    R.layout.activity_check_design_active, R.layout.dialog_project_alert,
                    R.layout.dialog_record_date_design, R.layout.dialog_security_password}) {
                View root = LayoutInflater.from(english).inflate(resource, null);
                assertEnglishText(root, english.getResources().getResourceEntryName(resource));
            }
            for (int array : new int[]{R.array.list_filter_staff_statuses, R.array.list_filter_record_results,
                    R.array.settings_temperature_modes, R.array.settings_temperature_measurement_modes,
                    R.array.settings_sdk_transports})
                for (String label : english.getResources().getStringArray(array))
                    assertNoHan("选项 " + array, label);
            String total = english.getString(R.string.i18n_b_photo_count, 12);
            assertTrue("格式参数应显示实际数值", total.contains("12"));
            assertFalse("格式参数不能原样显示", total.contains("%1$"));
            assertNoHan("格式化照片数", total);
            assertNoHan("月份", english.getString(R.string.i18n_b_calendar_month, 2032, 5));
        });
    }

    @Test public void localizedStaffStatusPreservesUserFieldsEvenWhenTheyMatchMessageKeys() throws Exception {
        Context english = context(Locale.ENGLISH, 1280, 800);
        JSONObject person = new JSONObject().put("name", "正常").put("staff_no", "无")
                .put("department", "未分配").put("enabled", true).put("health_expiry", "2099-12-31");
        String before = person.toString();
        instrumentation.runOnMainSync(() -> {
            TextView number = new TextView(english), status = new TextView(english);
            TextView department = new TextView(english), health = new TextView(english);
            StaffInfoUi.bindValues(person, number, status, department, health);
            assertEquals("真实工号不能按缺省文案翻译", "无", number.getText().toString());
            assertEquals("真实部门即使等于消息字典也必须原样", "未分配", department.getText().toString());
            assertEquals("Enabled", status.getText().toString());
            assertNoHan("健康证系统状态", health.getText());
        });
        assertEquals("本地化展示不能改写姓名、工号或部门数据", before, person.toString());

        JSONObject missing = new JSONObject().put("enabled", false);
        instrumentation.runOnMainSync(() -> {
            TextView number = new TextView(english), status = new TextView(english);
            TextView department = new TextView(english), health = new TextView(english);
            StaffInfoUi.bindValues(missing, number, status, department, health);
            assertEquals("None", number.getText().toString());
            assertEquals("Disabled", status.getText().toString());
            assertNoHan("缺省部门", department.getText());
            assertNoHan("缺省健康证", health.getText());
            assertFalse("缺省部门文案不能为空", department.getText().toString().trim().isEmpty());
        });
    }

    @Test public void largestAppFontKeepsPrimaryActionsReachableAcrossScreenSizes() {
        instrumentation.runOnMainSync(() -> {
            for (int[] size : WINDOWS) {
                Context english = context(Locale.ENGLISH, size[0], size[1]);
                String label = size[0] + "×" + size[1];
                ViewGroup home = (ViewGroup) LayoutInflater.from(english).inflate(R.layout.activity_home_design, null);
                measure(home, size[0], size[1]);
                assertAction(home, R.id.home_start, label + " 首页晨检入口");
                assertAction(home, R.id.home_settings, label + " 关于设备");

                ViewGroup check = (ViewGroup) LayoutInflater.from(english).inflate(R.layout.activity_check_design_ready, null);
                // 正常晨检不显示 head，和 Activity 运行时的布局一致。
                check.findViewById(R.id.check_header).setVisibility(View.GONE);
                measure(check, size[0], size[1]);
                assertAction(check, R.id.check_exit, label + " 晨检退出");

                for (int footer : new int[]{R.layout.view_security_password_actions, R.layout.view_health_date_actions}) {
                    ViewGroup actions = (ViewGroup) LayoutInflater.from(english).inflate(footer, null);
                    int width = size[0] - Math.round(english.getResources().getDimension(R.dimen.dp_12)) * 2;
                    actions.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.AT_MOST));
                    actions.layout(0, 0, actions.getMeasuredWidth(), actions.getMeasuredHeight());
                    for (int index = 0; index < actions.getChildCount(); index++)
                        assertTextFits((TextView) actions.getChildAt(index), label + " 弹窗底部操作");
                }
            }
        });
    }

    @Test public void englishTemperatureSelectionRetainsCanonicalStoredMode() throws Exception {
        MorningApplication app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        Assume.assumeTrue("真实 Activity 用系统英文运行；局部语言 Context 测试不依赖此条件",
                "en".equals(app.getResources().getConfiguration().locale.getLanguage()));
        SharedPreferences preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        Map<String, Object> original = snapshot(preferences);
        instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, "ws://127.0.0.1:9/sdk")
                .putString("cloud_url", "").putString("cloud_token", "").putBoolean("voice", false)
                .putString("temperature_mode", "串口被动上报")
                .putString("serial_path", "/dev/huoyan-localization-missing").putString("baud", "115200").commit());
        try (ActivityScenario<TemperatureSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(app, TemperatureSettingsActivity.class))) {
            await(() -> {
                AtomicReference<Boolean> ready = new AtomicReference<>(false);
                scenario.onActivity(activity -> ready.set(activity.uiReady()
                        && activity.findViewById(R.id.settings_temperature_mode).isLaidOut()));
                return ready.get();
            });
            AtomicReference<AlertDialog> choices = new AtomicReference<>();
            scenario.onActivity(activity -> {
                ChoiceSpinner mode = activity.findViewById(R.id.settings_temperature_mode);
                assertNoHan("英文读取方式", String.valueOf(mode.getSelectedItem()));
                assertEquals("打开英文页面不能改写配置", "串口被动上报", preferences.getString("temperature_mode", ""));
                assertTrue("读取方式应打开真实选择弹窗", mode.performClick());
                try {
                    java.lang.reflect.Field field = ChoiceSpinner.class.getDeclaredField("choiceDialog");
                    field.setAccessible(true);
                    choices.set((AlertDialog) field.get(mode));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            await(() -> {
                AtomicReference<Boolean> ready = new AtomicReference<>(false);
                instrumentation.runOnMainSync(() -> ready.set(choices.get() != null && choices.get().isShowing()
                        && choices.get().<ListView>findViewById(R.id.department_filter_options).getChildCount() == 2));
                return ready.get();
            });
            instrumentation.runOnMainSync(() -> {
                ListView options = choices.get().findViewById(R.id.department_filter_options);
                assertTrue(options.performItemClick(options.getChildAt(1), 1, options.getItemIdAtPosition(1)));
            });
            // Spinner 选择监听由布局消息派发；等待真实保存完成，再验证持久化值和显示值。
            await(() -> "串口主动查询".equals(preferences.getString("temperature_mode", "")));
            scenario.onActivity(activity -> {
                Spinner mode = activity.findViewById(R.id.settings_temperature_mode);
                assertEquals(1, mode.getSelectedItemPosition());
                assertNoHan("英文主动查询选项", String.valueOf(mode.getSelectedItem()));
                assertEquals("英文选项必须写回既有配置值", "串口主动查询", preferences.getString("temperature_mode", ""));
            });
        } finally {
            instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
            restore(preferences, original);
        }
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.call()) return;
            SystemClock.sleep(50);
        }
        fail("等待真实界面回调超时");
    }

    private Context context(Locale locale, int width, int height) {
        Context app = instrumentation.getTargetContext();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.densityDpi = 160;
        configuration.screenWidthDp = width;
        configuration.screenHeightDp = height;
        configuration.smallestScreenWidthDp = Math.min(width, height);
        configuration.orientation = width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = AppFontSettings.scaleFor(3);
        return new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
    }

    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void assertAction(ViewGroup root, int id, String label) {
        TextView action = root.findViewById(id);
        assertNotNull(label, action);
        assertEquals(label, View.VISIBLE, action.getVisibility());
        assertTrue(label, action.getWidth() > 0 && action.getHeight() > 0);
        Rect bounds = new Rect(0, 0, action.getWidth(), action.getHeight());
        root.offsetDescendantRectToMyCoords(action, bounds);
        assertTrue(label + "应在可见窗口内：" + bounds,
                new Rect(0, 0, root.getWidth(), root.getHeight()).contains(bounds));
        assertTextFits(action, label);
    }

    private static void assertTextFits(TextView view, String label) {
        assertNoHan(label, view.getText());
        Layout layout = view.getLayout();
        assertNotNull(label + "文字应完成排版", layout);
        assertTrue(label + "文字高度不可被裁切", layout.getHeight()
                <= view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom() + 1);
        for (int line = 0; line < layout.getLineCount(); line++)
            assertEquals(label + "关键操作不能省略", 0, layout.getEllipsisCount(line));
    }

    private static void assertEnglishText(View view, String source) {
        if (view instanceof TextView && !(view instanceof TextClock)) {
            TextView text = (TextView) view;
            assertNoHan(source + "正文", text.getText());
            assertNoHan(source + "输入提示", text.getHint());
        }
        assertNoHan(source + "辅助说明", view.getContentDescription());
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                assertEnglishText(((ViewGroup) view).getChildAt(index), source);
    }

    private static void assertNoHan(String message, CharSequence value) {
        assertFalse(message + "仍包含中文：" + value, value != null && HAN.matcher(value).find());
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set
                    ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, Object> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
        }
        assertTrue(editor.commit());
    }
}
