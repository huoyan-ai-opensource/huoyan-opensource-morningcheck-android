package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 隔离现场服务，验证规则即时保存、无效草稿与页面重建，以及固定页面的字号适配。 */
@RunWith(AndroidJUnit4.class)
public final class CheckRulesSettingsUiTest {
    private static final int[][] WINDOWS = {{360, 640}, {640, 360}, {800, 1280},
            {1280, 800}, {360, 480}, {640, 320}};
    private static final int[] TOGGLE_IDS = {R.id.settings_head, R.id.settings_hat,
            R.id.settings_mask, R.id.settings_temperature};
    private static final int[] MODEL_IDS = {R.id.settings_current_model,
            R.id.settings_model_version, R.id.settings_switch_model};
    private static final String[] TOGGLE_KEYS = {"head", "hat", "mask", "temperature"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<CheckRulesSettingsActivity> scenario;

    @Before public void isolateSdkAndRestoreablePreferences() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device);
        originalBinding = snapshot(binding);
        instrumentation.runOnMainSync(() -> {
            assertFalse("规则回归不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() == null || !request.getPath().startsWith("/sdk"))
                    businessRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("晨检规则隔离回归");
            }
        });
        endpoint.start();
        SharedPreferences.Editor editor = device.edit()
                .putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false)
                .putString("temperature_limit", "37.3")
                .putInt(AppFontSettings.KEY, AppFontSettings.DEFAULT_INDEX);
        for (String key : TOGGLE_KEYS) editor.putBoolean(key, true);
        assertTrue(editor.commit());
    }

    @After public void closePageAndRestorePreferences() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null) instrumentation.runOnMainSync(() -> {
                finishPages();
                app.sdkSession().disconnect();
            });
            instrumentation.waitForIdleSync();
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (binding != null && originalBinding != null)
                assertEquals("晨检规则不应改变门店归属", originalBinding, binding.getAll());
        } finally {
            try {
                if (device != null && originalDevice != null) restore(device, originalDevice);
                if (binding != null && originalBinding != null) restore(binding, originalBinding);
            } finally {
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("本地规则调整不应访问业务云", 0, businessRequests.get());
    }

    @Test public void validTemperatureEditsSaveImmediatelyIncludingBothRangeBoundaries() {
        launchPage();
        scenario.onActivity(activity -> {
            assertEquals("37.3", input(activity).getText().toString());
            assertNull("规则页不再提供底部保存按钮", optional(activity.findViewById(R.id.main_root), "settings_save_threshold"));
            assertNull("手机和短屏也直接编辑，不再弹窗调整阈值", optional(activity.findViewById(R.id.main_root), "settings_edit_threshold"));
            for (String value : new String[]{"37.6", "36.1", "43", "37.35"}) {
                input(activity).setText(value);
                assertSaved(value);
                assertEquals("已保存，下次晨检生效", status(activity).getText().toString());
                assertNull("校验提示使用输入附近文案，不能弹出系统错误浮层", input(activity).getError());
                assertNull("改变温度不能打开编辑弹窗", activity.formDialog);
            }
        });
        closePage();
        launchPage();
        scenario.onActivity(activity -> {
            assertEquals("重新进入直接读取最近保存的体温上限", 37.35,
                    Double.parseDouble(input(activity).getText().toString()), 0);
            assertSaved("37.35");
        });
    }

    @Test public void invalidAndIncompleteTemperatureDraftsKeepLatestValidRule() {
        launchPage();
        scenario.onActivity(activity -> {
            input(activity).setText("37.8");
            assertSaved("37.8");
            for (String value : new String[]{"", ".", "37.", "35.9", "43.1", "44", "NaN", "Infinity"}) {
                input(activity).setText(value);
                assertSaved("37.8");
                assertTrue("无效输入须说明允许范围及仍生效的值：" + value,
                        status(activity).getText().toString().contains("36.1")
                                && status(activity).getText().toString().contains("43")
                                && status(activity).getText().toString().contains("37.8"));
                assertEquals(View.VISIBLE, status(activity).getVisibility());
                assertNull(input(activity).getError());
                assertNull("输入校验不能打断连续编辑", activity.formDialog);
            }
            input(activity).setText("38.2");
            assertSaved("38.2");
            assertEquals("重新输入合法值后应恢复保存状态", "已保存，下次晨检生效",
                    status(activity).getText().toString());
        });
    }

    @Test public void redrawAndRecreationKeepDraftWithoutAllowingOldInputToChangeSavedRule() {
        launchPage();
        AtomicReference<EditText> removedInput = new AtomicReference<>();
        scenario.onActivity(activity -> {
            EditText oldInput = input(activity);
            oldInput.setText("37.8");
            oldInput.setText("35.2");
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertNotSame("重排必须创建新表单", oldInput, input(activity));
            assertEquals("重排仍保留当前无效草稿，便于继续编辑", "35.2", input(activity).getText().toString());
            assertSaved("37.8");
            oldInput.setText("39.4");
            assertSaved("37.8");
            input(activity).setText("38.1");
            assertSaved("38.1");
            input(activity).setText("44");
            removedInput.set(input(activity));
        });
        scenario.recreate();
        scenario.onActivity(activity -> {
            assertEquals("系统重建保留无效草稿", "44", input(activity).getText().toString());
            assertSaved("38.1");
            assertTrue("草稿恢复也要展示当前生效值", status(activity).getText().toString().contains("38.1"));
            removedInput.get().setText("39.2");
            assertSaved("38.1");
        });
        closePage();
        launchPage();
        scenario.onActivity(activity -> {
            assertEquals("完整离开后重新进入，以合法规则为准", 38.1,
                    Double.parseDouble(input(activity).getText().toString()), 0);
            assertSaved("38.1");
        });
    }

    @Test public void ruleSwitchesSaveImmediatelyAndSurviveRecreation() {
        launchPage();
        scenario.onActivity(activity -> {
            // 先调整头部子项，再关闭头部；不依赖父开关关闭后的子项可交互性。
            for (int index : new int[]{1, 2, 3, 0}) {
                Switch toggle = activity.findViewById(TOGGLE_IDS[index]);
                assertTrue(toggle.isChecked());
                toggle.setChecked(false);
                assertFalse("关闭开关应立即保存：" + TOGGLE_KEYS[index], device.getBoolean(TOGGLE_KEYS[index], true));
            }
            assertSaved("37.3");
        });
        scenario.recreate();
        scenario.onActivity(activity -> {
            for (int index = 0; index < TOGGLE_IDS.length; index++) {
                Switch toggle = activity.findViewById(TOGGLE_IDS[index]);
                assertFalse("重建读取已保存开关状态", toggle.isChecked());
                toggle.setChecked(true);
                assertTrue("恢复开关也应立即保存", device.getBoolean(TOGGLE_KEYS[index], false));
            }
        });
    }

    @Test public void temperatureDialogConfirmsCancelsAndRejectsInvalidRangesAtDefaultAndLargestFontSizes() throws Exception {
        for (int font : new int[]{AppFontSettings.DEFAULT_INDEX, 3}) {
            assertTrue(device.edit().putInt(AppFontSettings.KEY, font)
                    .putString("temperature_limit", "37.3").commit());
            launchPage();
            AtomicReference<CheckRulesSettingsActivity> page = new AtomicReference<>();
            scenario.onActivity(activity -> {
                page.set(activity);
                assertEquals("页面必须使用当前字号", AppFontSettings.scaleFor(font),
                        activity.getResources().getConfiguration().fontScale, .001f);
                ViewGroup root = activity.findViewById(R.id.main_root);
                for (int id : TOGGLE_IDS) assertContained(root, root.findViewById(id), "实际规则页");
                for (int id : MODEL_IDS) assertNull("晨检规则不再承载模型卡", root.findViewById(id));
                assertContained(root, input(activity), "实际体温输入框");
                assertEquals("合法温度不占用校验提示区域", View.GONE, status(activity).getVisibility());
            });
            CheckRulesSettingsActivity activity = page.get();
            String scene = font == AppFontSettings.DEFAULT_INDEX ? "default" : "largest";
            capture(activity, scene + "-rules");

            AlertDialog confirmed = openTemperatureDialog(activity);
            typeTemperature(confirmed, "38.6");
            onMain(() -> {
                assertEquals("数字键只编辑弹窗草稿", "38.6", thresholdDraft(confirmed).getText().toString());
                assertEquals("确认前页面仍保留生效阈值", "37.3", input(activity).getText().toString());
                assertSaved("37.3");
                assertTemperatureDialogVisible(activity, confirmed);
                return null;
            });
            capture(activity, scene + "-temperature-dialog");
            tap(onMain(() -> confirmed.findViewById(android.R.id.button1)));
            await(() -> onMain(() -> !confirmed.isShowing()), "确认合法阈值后应关闭温度弹窗");
            onMain(() -> {
                assertEquals("确认后页面显示新的阈值", "38.6", input(activity).getText().toString());
                assertSaved("38.6");
                return null;
            });

            AlertDialog cancelled = openTemperatureDialog(activity);
            typeTemperature(cancelled, "39.2");
            tap(onMain(() -> cancelled.findViewById(android.R.id.button2)));
            await(() -> onMain(() -> !cancelled.isShowing()), "取消应关闭温度弹窗");
            onMain(() -> {
                assertEquals("取消不能把弹窗草稿写回页面", "38.6", input(activity).getText().toString());
                assertSaved("38.6");
                return null;
            });

            for (String invalid : new String[]{"35.9", "43.1"}) {
                AlertDialog rejected = openTemperatureDialog(activity);
                onMain(() -> {
                    assertEquals("重新打开不能恢复已取消的草稿", "38.6", thresholdDraft(rejected).getText().toString());
                    return null;
                });
                typeTemperature(rejected, invalid);
                tap(onMain(() -> rejected.findViewById(android.R.id.button1)));
                onMain(() -> {
                    assertTrue("超出允许范围必须留在原弹窗：" + invalid, rejected.isShowing());
                    assertSame("校验失败不能替换编辑弹窗", rejected, activity.formDialog);
                    TextView error = rejected.findViewById(R.id.threshold_error);
                    assertEquals("非法范围应显示校验原因", View.VISIBLE, error.getVisibility());
                    assertTrue("校验原因必须说明允许范围", error.getText().toString().contains("36.1")
                            && error.getText().toString().contains("43"));
                    assertEquals("非法范围不能修改页面阈值", "38.6", input(activity).getText().toString());
                    assertSaved("38.6");
                    assertTemperatureDialogVisible(activity, rejected);
                    return null;
                });
                tap(onMain(() -> rejected.findViewById(android.R.id.button2)));
                await(() -> onMain(() -> !rejected.isShowing()), "校验失败后仍应允许取消");
            }
            closePage();
        }
    }

    @Test public void everyScreenAndFontSizeKeepsRulesAndValidationVisibleWithoutPageScrolling() {
        instrumentation.runOnMainSync(() -> {
            for (int[] window : WINDOWS) for (int font = 0; font < 4; font++) {
                Configuration configuration = new Configuration(app.getResources().getConfiguration());
                configuration.screenWidthDp = window[0];
                configuration.screenHeightDp = window[1];
                configuration.smallestScreenWidthDp = Math.min(window[0], window[1]);
                configuration.orientation = window[0] > window[1]
                        ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                configuration.fontScale = AppFontSettings.scaleFor(font);
                Context context = new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
                boolean compact = context.getResources().getBoolean(R.bool.mc_compact);
                String label = window[0] + "×" + window[1] + "，字号 " + Math.round(configuration.fontScale * 100) + "%";
                LayoutInflater inflater = LayoutInflater.from(context);
                ViewGroup root = (ViewGroup) inflater.inflate(compact
                        ? R.layout.activity_devices_compact : R.layout.activity_devices, null);
                ((TextView) root.findViewById(R.id.header_page_title)).setText("晨检规则");
                FrameLayout body = root.findViewById(R.id.settings_body);
                int inset = context.getResources().getDimensionPixelSize(R.dimen.dp_12);
                root.findViewById(R.id.page_content).setPadding(inset, inset, inset, inset);
                body.setPadding(0, 0, 0, 0);
                body.setBackground(null);
                body.addView(inflater.inflate(R.layout.view_rules_design, body, false));
                for (int id : new int[]{R.id.rules_head_state, R.id.rules_temperature_state})
                    ((TextView) root.findViewById(id)).setText(R.string.rules_enabled);
                for (int id : new int[]{R.id.rules_hat_state, R.id.rules_mask_state})
                    ((TextView) root.findViewById(id)).setText(R.string.rules_wear);
                ((EditText) root.findViewById(R.id.settings_temperature_limit)).setText("43.1");
                ((TextView) root.findViewById(R.id.settings_temperature_limit_status)).setText("请输入 36.1–43°C，仍使用 37.3°C");
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(window[0] * density), height = Math.round(window[1] * density);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                assertNoPageScroll(root, label);
                for (int id : MODEL_IDS) {
                    assertNull(label + "：模型信息与切换入口只放授权页", root.findViewById(id));
                }
                for (int id : TOGGLE_IDS) assertContained(root, root.findViewById(id), label);
                assertContained(root, root.findViewById(R.id.settings_temperature_limit), label);
                assertContained(root, root.findViewById(R.id.settings_temperature_limit_status), label);
                assertTextTree(body, root, label);
                assertNull(label, optional(root, "settings_save_threshold"));
                assertNull(label, optional(root, "settings_edit_threshold"));
            }
        });
    }

    private void launchPage() {
        scenario = ActivityScenario.launch(new Intent(app, CheckRulesSettingsActivity.class));
        instrumentation.waitForIdleSync();
    }

    private void closePage() {
        scenario.close();
        scenario = null;
        instrumentation.waitForIdleSync();
    }

    private EditText input(CheckRulesSettingsActivity activity) {
        EditText input = activity.findViewById(R.id.settings_temperature_limit);
        assertNotNull("所有尺寸都需要直接输入体温上限", input);
        return input;
    }

    private TextView status(CheckRulesSettingsActivity activity) {
        TextView status = activity.findViewById(R.id.settings_temperature_limit_status);
        assertNotNull("输入旁应有规则保存与校验提示", status);
        return status;
    }

    private void assertSaved(String expected) {
        assertEquals("实际晨检规则应使用最近确认的合法阈值",
                Double.parseDouble(expected), Double.parseDouble(device.getString("temperature_limit", "")), 0);
    }

    private static View optional(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private static void assertContained(ViewGroup root, View view, String label) {
        assertNotNull(label, view);
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue(label + "：控件需要真实可见尺寸 " + bounds, bounds.width() > 0 && bounds.height() > 0);
        assertTrue(label + "：控件应完整位于页面内 " + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
        ViewGroup body = root.findViewById(R.id.settings_body);
        if (body != null && isDescendant(body, view))
            assertInsideAncestor(body, view, true, label + "：设置内容区");
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            View ancestor = (View) parent;
            Rect clip = ancestor.getClipBounds();
            if (ancestor instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) ancestor;
                if (group.getClipChildren() || group.getClipToPadding())
                    assertInsideAncestor(group, view, group.getClipToPadding(), label + "：裁剪祖先");
            }
            if (clip != null) {
                Rect local = boundsInAncestor(ancestor, view);
                assertTrue(label + "：控件应落在祖先 clipBounds 内 " + local + " / " + clip,
                        clip.contains(local));
            }
            if (ancestor == root) break;
        }
    }

    private static boolean isDescendant(ViewGroup ancestor, View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }

    private static Rect boundsInAncestor(View ancestor, View view) {
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        ((ViewGroup) ancestor).offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }

    private static void assertInsideAncestor(ViewGroup ancestor, View view, boolean clipPadding, String label) {
        Rect bounds = boundsInAncestor(ancestor, view);
        Rect available = new Rect(clipPadding ? ancestor.getPaddingLeft() : 0,
                clipPadding ? ancestor.getPaddingTop() : 0,
                ancestor.getWidth() - (clipPadding ? ancestor.getPaddingRight() : 0),
                ancestor.getHeight() - (clipPadding ? ancestor.getPaddingBottom() : 0));
        assertTrue(label + "：不能只在屏幕内而被内容容器裁掉 " + bounds + " / " + available,
                available.contains(bounds));
    }

    private static void assertTextTree(View view, ViewGroup root, String label) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && ((TextView) view).length() > 0) {
            TextView text = (TextView) view;
            assertContained(root, text, label + "：" + text.getText());
            Layout layout = text.getLayout();
            assertNotNull(label + "：文字必须完成 Android 排版", layout);
            int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
            assertTrue(label + "：文字纵向不能截断 " + text.getText(), layout.getHeight() <= availableHeight + 1);
            int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(label + "：规则说明不能省略 " + text.getText(), 0, layout.getEllipsisCount(line));
                assertTrue(label + "：文字横向不能截断 " + text.getText(), layout.getLineWidth(line) <= availableWidth + 1);
            }
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertTextTree(((ViewGroup) view).getChildAt(index), root, label);
    }

    private static void assertNoPageScroll(View view, String label) {
        assertFalse(label + "：规则页不依赖整页滚动", view instanceof ScrollView || view instanceof HorizontalScrollView);
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            assertNoPageScroll(((ViewGroup) view).getChildAt(index), label);
    }

    private void finishPages() {
        for (Stage stage : Stage.values()) for (Activity activity : new ArrayList<>(
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private AlertDialog openTemperatureDialog(CheckRulesSettingsActivity activity) {
        tap(onMain(() -> input(activity)));
        await(() -> onMain(() -> activity.formDialog != null && activity.formDialog.isShowing()
                        && activity.formDialog.getWindow() != null
                        && !activity.formDialog.getWindow().getDecorView().isLayoutRequested()
                        && activity.formDialog.findViewById(R.id.threshold_draft).getWidth() > 0),
                "体温输入应打开并完成自定义温度弹窗布局");
        return onMain(() -> {
            assertTemperatureDialogVisible(activity, activity.formDialog);
            return activity.formDialog;
        });
    }

    private static EditText thresholdDraft(AlertDialog dialog) {
        EditText draft = dialog.findViewById(R.id.threshold_draft);
        assertNotNull("温度弹窗必须提供独立草稿输入框", draft);
        return draft;
    }

    private void typeTemperature(AlertDialog dialog, String value) {
        for (char digit : value.toCharArray()) {
            View key = onMain(() -> dialog.getWindow().getDecorView().findViewWithTag("threshold:" + digit));
            assertNotNull("温度弹窗缺少数字按键：" + digit, key);
            tap(key);
        }
    }

    private static void assertTemperatureDialogVisible(CheckRulesSettingsActivity activity, AlertDialog dialog) {
        assertTrue("温度弹窗必须保持显示", dialog.isShowing());
        assertNotNull("温度弹窗必须使用独立窗口", dialog.getWindow());
        ViewGroup decor = (ViewGroup) dialog.getWindow().getDecorView();
        Rect screen = ScreenViewGeometry.bounds(activity.getWindow().getDecorView());
        for (int id : new int[]{R.id.threshold_draft, android.R.id.button1, android.R.id.button2, R.id.threshold_error}) {
            View control = dialog.findViewById(id);
            assertNotNull("温度弹窗控件必须存在", control);
            if (id == R.id.threshold_error && control.getVisibility() != View.VISIBLE) continue;
            assertTrue("温度弹窗输入与操作必须实际显示", control.isShown());
            assertContained(decor, control, "实际温度弹窗");
            Rect visible = new Rect();
            assertTrue("温度弹窗控件必须在屏幕上可见", ScreenViewGeometry.visibleOnScreen(control, visible));
            assertEquals("温度弹窗控件不能被裁剪", ScreenViewGeometry.bounds(control), visible);
            assertTrue("温度弹窗控件必须完整处于屏幕内", screen.contains(visible));
        }
        View confirm = dialog.findViewById(android.R.id.button1);
        assertTrue("确认按钮始终允许触摸", confirm.isEnabled() && confirm.isClickable());
        assertTextTree(decor, decor, "实际温度弹窗");
    }

    private void tap(View target) {
        await(() -> onMain(() -> target.isShown() && target.isEnabled() && target.getWidth() > 0
                && target.getHeight() > 0 && !target.isLayoutRequested()), "真实触摸目标应完成布局");
        Rect visible = onMain(() -> {
            Rect area = new Rect();
            assertTrue("触摸目标必须在设备屏幕上可见", ScreenViewGeometry.visibleOnScreen(target, area));
            return area;
        });
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                visible.exactCenterX(), visible.exactCenterY(), 0);
        MotionEvent release = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP,
                visible.exactCenterX(), visible.exactCenterY(), 0);
        press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        release.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue("应注入实际触摸按下", instrumentation.getUiAutomation().injectInputEvent(press, true));
            assertTrue("应注入实际触摸松开", instrumentation.getUiAutomation().injectInputEvent(release, true));
        } finally {
            press.recycle();
            release.recycle();
        }
        instrumentation.waitForIdleSync();
    }

    private void capture(CheckRulesSettingsActivity activity, String scene) throws Exception {
        if (!"true".equalsIgnoreCase(InstrumentationRegistry.getArguments().getString("rulesCapture", "false"))) return;
        CountDownLatch frames = new CountDownLatch(1);
        onMain(() -> {
            View decor = activity.getWindow().getDecorView();
            // 连续跨两帧再读系统合成画面，同时保留实际温度弹窗的图像。
            decor.postOnAnimation(() -> decor.postOnAnimation(frames::countDown));
            return null;
        });
        assertTrue("截图应等待页面两个绘制帧", frames.await(5, TimeUnit.SECONDS));
        SystemClock.sleep(100);
        File targetDirectory = app.getExternalFilesDir(null);
        assertNotNull("截图必须保存到目标应用的外部文件目录", targetDirectory);
        File directory = new File(targetDirectory, "qa-rules");
        assertTrue("截图目录必须创建成功", directory.isDirectory() || directory.mkdirs());
        String prefix = InstrumentationRegistry.getArguments().getString("rulesCapturePrefix", "device")
                .replaceAll("[^A-Za-z0-9_-]", "_");
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("实际设备截图必须生成", screenshot);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, prefix + "-" + scene + ".png"))) {
            assertTrue("实际设备截图必须保存成功", screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            screenshot.recycle();
        }
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private void await(Callable<Boolean> condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (condition.call()) return; }
            catch (Exception error) { throw new AssertionError(error); }
            SystemClock.sleep(40);
        }
        fail(message);
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) copy.put(entry.getKey(),
                entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, Object> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else fail("不能恢复未知偏好类型：" + key);
        }
        assertTrue(editor.commit());
    }
}
