package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.hardware.LightController;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 注入假灯和本机拒绝端点，验证页面所有权、离页关灯及短屏布局，不访问现场灯光。 */
@RunWith(AndroidJUnit4.class)
public final class LightingSettingsUiTest {
    private static final String TEST_PATH = "/dev/huoyan-light-ui-test";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private final List<FakeDevice> devices = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> pendingCallbacks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> deviceFailure = new AtomicReference<>();
    private final AtomicInteger onWrites = new AtomicInteger(), offWrites = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences preferences, binding;
    private Map<String, Object> originalPreferences, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<? extends BaseMorningActivity> scenario;
    private LightController originalController, fakeController;
    private boolean controllerInjected;
    private ExecutorService worker;
    private volatile CountDownLatch openEntered, allowOpen;

    @Before public void isolateEndpointsPreferencesAndLamp() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("灯光回归不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() == null || !request.getPath().startsWith("/sdk"))
                    businessRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("灯光页面隔离回归");
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).putString("light_mode", LightConfig.SERIAL)
                .putString("light_serial_path", TEST_PATH).putString("light_baud", "115200")
                .putString("light_on_command", LightConfig.DEFAULT_ON_COMMAND)
                .putString("light_off_command", LightConfig.DEFAULT_OFF_COMMAND)
                .putInt("light_command_format", LightConfig.ASCII)
                .putString("light_gpio", "122,148").putString("light_on_level", "0")
                .putString("light_off_level", "1").commit());
        worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "隔离灯光页面回归"));
        fakeController = new LightController(worker, pendingCallbacks::add, config -> {
            CountDownLatch entered = openEntered, release = allowOpen;
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("假设备打开等待超时");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("假设备打开被打断", error);
                }
            }
            FakeDevice device = new FakeDevice(config);
            devices.add(device);
            return device;
        });
        onMain(() -> {
            originalController = (LightController) staticField("instance");
            setStaticField("instance", fakeController);
            controllerInjected = true;
            return null;
        });
    }

    @After public void closeFakeLampAndRestoreAllConfiguration() throws Exception {
        try {
            if (allowOpen != null) allowOpen.countDown();
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> {
                finishPages();
                app.sdkSession().disconnect();
                return null;
            });
            instrumentation.waitForIdleSync();
            if (worker != null) worker.submit(() -> null).get(10, TimeUnit.SECONDS);
            drainCallbacks();
            for (FakeDevice device : devices) {
                assertTrue("页面结束应释放每条假灯控制链路", device.closed.get());
                assertFalse("页面结束不能残留开灯", device.on);
            }
            assertNull("不能关闭后再次写入已释放的假灯设备", deviceFailure.get());
        } finally {
            try {
                if (controllerInjected) onMain(() -> { setStaticField("instance", originalController); return null; });
                if (worker != null) worker.shutdownNow();
                if (app != null) app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
                if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
                if (binding != null && originalBinding != null)
                    assertEquals("灯光设置不能改变 SDK 门店归属", originalBinding, binding.getAll());
            } finally {
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("灯光测试和配置不能访问业务云", 0, businessRequests.get());
    }

    @Test public void homeEntryOpensIndependentPageOnlyExplicitTestsOpenLampAndReturnClosesIt() {
        scenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        await(() -> onMain(() -> {
            HomeActivity home = resumed(HomeActivity.class);
            return home != null && home.loaded && home.findViewById(R.id.home_lighting) != null;
        }), "首页应有独立灯光入口");
        onMain(() -> {
            assertTrue(resumed(HomeActivity.class).findViewById(R.id.home_lighting).performClick());
            return null;
        });
        awaitPage();
        assertEquals("进入设置不能自动打开灯光", 0, onWrites.get());
        assertTrue("没有开灯动作不应触碰实体控制链路", devices.isEmpty());
        startAndAwaitOn();
        drainCallbacks();
        assertTrue("开灯回调完成后假设备应保持开启", devices.get(devices.size() - 1).on);
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_test_off).performClick());
            return null;
        });
        await(() -> offWrites.get() > 0 && allClosed(), "点击关闭应发出关灯并释放设备");
        drainCallbacks();
        assertTrue("关灯回调不能重新打开假设备", allClosed());
        startAndAwaitOn();
        int previousOff = offWrites.get();
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.header_home).performClick());
            return null;
        });
        await(() -> offWrites.get() > previousOff && allClosed(), "返回首页应关闭测试灯并释放端口");
        await(() -> onMain(() -> resumed(HomeActivity.class) != null), "页头返回应到首页");
        drainCallbacks();
    }

    @Test public void editingOrSavingDraftClosesLampAndOldSuccessCannotChangeNewState() {
        launchPage();
        startAndAwaitOn();
        awaitWorker();
        assertFalse("保留迟到回调以覆盖旧成功污染", pendingCallbacks.isEmpty());
        int previousOff = offWrites.get();
        onMain(() -> {
            resumed(LightingSettingsActivity.class).<TextView>findViewById(R.id.lighting_on_command).setText("B00101A0");
            assertNull("修改参数应撤销页面灯光所有权", readField(resumed(LightingSettingsActivity.class), "lighting"));
            return null;
        });
        await(() -> offWrites.get() > previousOff && allClosed(), "参数修改应使用旧配置关灯");
        drainCallbacks();
        assertIdle();
        onMain(() -> {
            assertEquals("未保存草稿不修改持久串口", TEST_PATH, preferences.getString("light_serial_path", ""));
            assertEquals("未保存草稿不修改持久指令", LightConfig.DEFAULT_ON_COMMAND, LightConfig.read(preferences).onCommand);
            return null;
        });
        startAndAwaitOn();
        assertEquals("ASCII:B00101A0", devices.get(devices.size() - 1).config.onCommand);
        int saveOff = offWrites.get();
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_save).performClick());
            return null;
        });
        await(() -> offWrites.get() > saveOff && allClosed(), "保存配置也应先关闭测试灯");
        drainCallbacks();
        assertIdle();
        onMain(() -> {
            assertEquals("ASCII:B00101A0", preferences.getString("light_on_command", ""));
            return null;
        });
    }

    @Test public void redrawBackgroundAndRecreationCloseLampAndRejectPreviousCallbacks() {
        launchPage();
        startAndAwaitOn();
        int beforeRedrawOff = offWrites.get();
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            View oldTest = page.findViewById(R.id.lighting_test_on);
            page.onConfigurationChanged(new Configuration(page.getResources().getConfiguration()));
            assertNotSame(oldTest, page.findViewById(R.id.lighting_test_on));
            assertNull("重排表单应停止测试灯", readField(page, "lighting"));
            return null;
        });
        await(() -> offWrites.get() > beforeRedrawOff && allClosed(), "表单重排应关闭旧测试灯");
        drainCallbacks();
        assertIdle();
        startAndAwaitOn();
        int beforeBackgroundOff = offWrites.get();
        scenario.moveToState(Lifecycle.State.CREATED);
        await(() -> offWrites.get() > beforeBackgroundOff && allClosed(), "进入后台应关闭测试灯");
        drainCallbacks();
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPage();
        assertIdle();
        startAndAwaitOn();
        int beforeRecreateOff = offWrites.get();
        scenario.recreate();
        awaitPage();
        await(() -> offWrites.get() > beforeRecreateOff && allClosed(), "Activity 重建应释放旧测试灯");
        drainCallbacks();
        assertIdle();
    }

    @Test public void leavingWhilePortOpensCannotSendQueuedOnCommand() throws Exception {
        launchPage();
        openEntered = new CountDownLatch(1);
        allowOpen = new CountDownLatch(1);
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_test_on).performClick());
            return null;
        });
        assertTrue("假设备应到达打开中的窗口", openEntered.await(10, TimeUnit.SECONDS));
        scenario.moveToState(Lifecycle.State.CREATED);
        allowOpen.countDown();
        await(() -> !devices.isEmpty() && allClosed(), "离页后才打开的端口也应关灯并关闭");
        assertEquals("离页后排队的旧开灯命令必须作废", 0, onWrites.get());
        assertTrue("清理阶段应明确发送关灯", offWrites.get() > 0);
        drainCallbacks();
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPage();
        assertIdle();
    }

    @Test public void phoneAndTabletPortraitLandscapeKeepCoreControlsVisibleWithoutPageScrolling() {
        onMain(() -> {
            for (float fontScale : new float[]{1, 1.3f}) {
                assertLayout(360, 640, 320, fontScale);
                assertLayout(640, 320, 320, fontScale);
                assertLayout(800, 1280, 600, fontScale);
                assertLayout(1280, 800, 600, fontScale);
            }
            return null;
        });
    }

    @Test public void oldSavedStateCannotRestoreCommandsFromBeforeDefaultsReset() {
        launchPage();
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            Bundle oldState = new Bundle();
            oldState.putString("onCommand", "A0 01 01 A0");
            oldState.putString("offCommand", "A0 01 00 A0");
            oldState.putString("path", TEST_PATH);
            page.setup(oldState);
            page.bindPage();
            assertEquals("A00101A0",
                    page.<TextView>findViewById(R.id.lighting_on_command).getText().toString());
            assertEquals("A00100A0",
                    page.<TextView>findViewById(R.id.lighting_off_command).getText().toString());

            Bundle prefixedState = new Bundle();
            prefixedState.putInt("lightCommandStateVersion", 1);
            prefixedState.putString("onCommand", "HEX:41");
            prefixedState.putString("offCommand", "DEC:66");
            page.setup(prefixedState);
            page.bindPage();
            assertEquals("A", page.<TextView>findViewById(R.id.lighting_on_command).getText().toString());
            assertEquals("B", page.<TextView>findViewById(R.id.lighting_off_command).getText().toString());

            page.<TextView>findViewById(R.id.lighting_on_command).setText("C00101A0");
            Bundle currentState = new Bundle();
            page.onSaveInstanceState(currentState);
            page.setup(currentState);
            page.bindPage();
            assertEquals("C00101A0", page.<TextView>findViewById(R.id.lighting_on_command).getText().toString());
            assertEquals("B", page.<TextView>findViewById(R.id.lighting_off_command).getText().toString());
            return null;
        });
        assertTrue("恢复表单不能触碰灯光设备", devices.isEmpty());
    }

    @Test public void formatPickerConvertsBothCommandsAndRetainsDraftAndSavedFormatAcrossRecreation() {
        launchPage();
        assertCommands(R.string.lighting_format_character, "A00101A0", "A00100A0");
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            page.<TextView>findViewById(R.id.lighting_on_command).setText("A");
            page.<TextView>findViewById(R.id.lighting_off_command).setText("B");
            return null;
        });
        chooseCommandFormat(1);
        assertCommands(R.string.lighting_format_decimal, "65", "66");
        scenario.recreate();
        awaitPage();
        assertCommands(R.string.lighting_format_decimal, "65", "66");
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            page.onConfigurationChanged(new Configuration(page.getResources().getConfiguration()));
            return null;
        });
        assertCommands(R.string.lighting_format_decimal, "65", "66");
        assertEquals("草稿切换不提前保存格式", LightConfig.ASCII,
                preferences.getInt("light_command_format", -1));
        saveCommandForm();
        assertEquals(LightConfig.DECIMAL, preferences.getInt("light_command_format", -1));
        assertEquals("DEC:65", preferences.getString("light_on_command", ""));
        assertEquals("DEC:66", preferences.getString("light_off_command", ""));
        assertSavedCommandBytes('A', 'B');

        chooseCommandFormat(2);
        assertCommands(R.string.lighting_format_hexadecimal, "41", "42");
        saveCommandForm();
        assertEquals(LightConfig.HEXADECIMAL, preferences.getInt("light_command_format", -1));
        assertEquals("HEX:41", preferences.getString("light_on_command", ""));
        assertEquals("HEX:42", preferences.getString("light_off_command", ""));
        assertSavedCommandBytes('A', 'B');
        scenario.close();
        launchPage();
        assertCommands(R.string.lighting_format_hexadecimal, "41", "42");

        chooseCommandFormat(0);
        assertCommands(R.string.lighting_format_character, "A", "B");
        saveCommandForm();
        assertEquals(LightConfig.ASCII, preferences.getInt("light_command_format", -1));
        assertEquals("ASCII:A", preferences.getString("light_on_command", ""));
        assertEquals("ASCII:B", preferences.getString("light_off_command", ""));
        assertSavedCommandBytes('A', 'B');
        scenario.close();
        launchPage();
        assertCommands(R.string.lighting_format_character, "A", "B");
        onMain(() -> {
            resumed(LightingSettingsActivity.class).<TextView>findViewById(R.id.lighting_on_command).setText(" A ");
            return null;
        });
        chooseCommandFormat(1);
        assertCommands(R.string.lighting_format_decimal, "32 65 32", "66");
        chooseCommandFormat(2);
        assertCommands(R.string.lighting_format_hexadecimal, "20 41 20", "42");
        chooseCommandFormat(0);
        assertCommands(R.string.lighting_format_character, " A ", "B");
        assertTrue("切换、保存和恢复格式不能触碰灯光设备", devices.isEmpty());
    }

    @Test public void failedFormatConversionRetainsBothDraftsAndOriginalSelection() {
        launchPage();
        chooseCommandFormat(1);
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            page.<TextView>findViewById(R.id.lighting_on_command).setText("65");
            page.<TextView>findViewById(R.id.lighting_off_command).setText("256");
            return null;
        });
        chooseCommandFormat(2);
        assertCommands(R.string.lighting_format_decimal, "65", "256");

        onMain(() -> {
            resumed(LightingSettingsActivity.class).<TextView>findViewById(R.id.lighting_off_command).setText("0");
            return null;
        });
        chooseCommandFormat(0);
        assertCommands(R.string.lighting_format_decimal, "65", "0");
        scenario.recreate();
        awaitPage();
        assertCommands(R.string.lighting_format_decimal, "65", "0");
        assertEquals("转换失败不能改写已保存开灯指令", LightConfig.DEFAULT_ON_COMMAND,
                preferences.getString("light_on_command", ""));
        assertEquals("转换失败不能改写已保存关灯指令", LightConfig.DEFAULT_OFF_COMMAND,
                preferences.getString("light_off_command", ""));
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            page.<TextView>findViewById(R.id.lighting_on_command).setText("");
            page.<TextView>findViewById(R.id.lighting_off_command).setText("");
            return null;
        });
        chooseCommandFormat(0);
        assertCommands(R.string.lighting_format_character, "", "");
        chooseCommandFormat(2);
        assertCommands(R.string.lighting_format_hexadecimal, "", "");
        assertTrue("转换失败不能触碰灯光设备", devices.isEmpty());
    }

    private void assertLayout(int widthDp, int heightDp, int smallestWidthDp, float fontScale) {
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.screenWidthDp = widthDp;
        configuration.screenHeightDp = heightDp;
        configuration.smallestScreenWidthDp = smallestWidthDp;
        configuration.orientation = widthDp > heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = fontScale;
        Context context = new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_lighting_settings, null);
        root.findViewById(R.id.lighting_serial_fields).setVisibility(View.VISIBLE);
        root.findViewById(R.id.lighting_gpio_fields).setVisibility(View.GONE);
        ((TextView) root.findViewById(R.id.lighting_command_format)).setText(R.string.lighting_format_character);
        ((TextView) root.findViewById(R.id.lighting_on_command)).setText("A00101A0");
        ((TextView) root.findViewById(R.id.lighting_off_command)).setText("A00100A0");
        measure(root, context, widthDp, heightDp);
        for (int id : new int[]{R.id.lighting_serial_mode, R.id.lighting_gpio_mode, R.id.lighting_path, R.id.lighting_baud,
                R.id.lighting_command_format, R.id.lighting_on_command, R.id.lighting_off_command,
                R.id.lighting_save, R.id.lighting_test_on, R.id.lighting_test_off})
            assertContained(root, root.findViewById(id), widthDp, heightDp);
        root.findViewById(R.id.lighting_serial_fields).setVisibility(View.GONE);
        root.findViewById(R.id.lighting_gpio_fields).setVisibility(View.VISIBLE);
        measure(root, context, widthDp, heightDp);
        for (int id : new int[]{R.id.lighting_gpio, R.id.lighting_on_level, R.id.lighting_off_level,
                R.id.lighting_save, R.id.lighting_test_on, R.id.lighting_test_off})
            assertContained(root, root.findViewById(id), widthDp, heightDp);
        assertNoPageScroll(root);
    }

    private static void measure(ViewGroup root, Context context, int widthDp, int heightDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void assertContained(ViewGroup root, View view, int widthDp, int heightDp) {
        assertNotNull("缺少灯光核心控件", view);
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue("控件应具有可见尺寸：" + widthDp + "x" + heightDp + " " + bounds,
                bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件应完整位于页面内：" + widthDp + "x" + heightDp + " " + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
    }

    private static void assertNoPageScroll(View view) {
        assertFalse("灯光配置不依赖整页滚动", view instanceof ScrollView);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                assertNoPageScroll(((ViewGroup) view).getChildAt(i));
    }

    private void launchPage() {
        scenario = ActivityScenario.launch(new Intent(app, LightingSettingsActivity.class));
        awaitPage();
    }

    private void chooseCommandFormat(int position) {
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_command_format).performClick());
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            AlertDialog dialog = (AlertDialog) readField(resumed(LightingSettingsActivity.class), "commandFormatDialog");
            assertNotNull("点击指令格式应显示项目选择弹窗", dialog);
            assertTrue("格式弹窗应处于显示状态", dialog.isShowing());
            ListView list = dialog.findViewById(R.id.department_filter_options);
            assertNotNull(list);
            assertEquals(3, list.getAdapter().getCount());
            assertEquals("字符", list.getAdapter().getItem(0));
            assertEquals("十进制", list.getAdapter().getItem(1));
            assertEquals("十六进制", list.getAdapter().getItem(2));
            View row = list.getAdapter().getView(position, null, list);
            assertTrue("选择格式应经过真实弹窗点击回调",
                    list.performItemClick(row, position, list.getAdapter().getItemId(position)));
            assertFalse("点选后应关闭格式弹窗", dialog.isShowing());
            return null;
        });
        instrumentation.waitForIdleSync();
    }

    private void assertCommands(int formatLabel, String on, String off) {
        onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            assertEquals(page.getString(formatLabel), page.<TextView>findViewById(R.id.lighting_command_format).getText().toString());
            assertEquals(on, page.<TextView>findViewById(R.id.lighting_on_command).getText().toString());
            assertEquals(off, page.<TextView>findViewById(R.id.lighting_off_command).getText().toString());
            return null;
        });
    }

    private void saveCommandForm() {
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_save).performClick());
            return null;
        });
    }

    private void assertSavedCommandBytes(char on, char off) {
        LightConfig saved = LightConfig.read(preferences);
        assertArrayEquals(new byte[]{(byte) on}, LightConfig.commandBytes(saved.onCommand));
        assertArrayEquals(new byte[]{(byte) off}, LightConfig.commandBytes(saved.offCommand));
    }

    private void awaitPage() {
        await(() -> onMain(() -> {
            LightingSettingsActivity page = resumed(LightingSettingsActivity.class);
            return page != null && page.loaded && page.findViewById(R.id.lighting_test_on) != null;
        }), "灯光管理应在独立 Activity 显示");
        instrumentation.waitForIdleSync();
    }

    private void startAndAwaitOn() {
        int previous = onWrites.get();
        onMain(() -> {
            assertTrue(resumed(LightingSettingsActivity.class).findViewById(R.id.lighting_test_on).performClick());
            return null;
        });
        await(() -> onWrites.get() > previous, "点击测试开灯应在后台写入开灯命令");
    }

    private void assertIdle() {
        assertTrue("结束测试后假灯应关闭并释放", allClosed());
        onMain(() -> {
            assertNull("结束测试后页面不应保留灯光所有权", readField(resumed(LightingSettingsActivity.class), "lighting"));
            return null;
        });
    }

    private boolean allClosed() {
        for (FakeDevice device : devices) if (!device.closed.get() || device.on) return false;
        return true;
    }

    private final class FakeDevice implements LightController.Device {
        final LightConfig config;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean on;
        FakeDevice(LightConfig config) { this.config = config; }
        @Override public void setOn(boolean value) throws IOException {
            if (closed.get()) {
                IOException failure = new IOException("设备已关闭");
                deviceFailure.compareAndSet(null, failure);
                throw failure;
            }
            on = value;
            if (value) onWrites.incrementAndGet(); else offWrites.incrementAndGet();
        }
        @Override public void close() { closed.set(true); }
    }

    private void drainCallbacks() {
        awaitWorker();
        onMain(() -> {
            Runnable action;
            while ((action = pendingCallbacks.poll()) != null) action.run();
            return null;
        });
    }

    private void awaitWorker() {
        try {
            if (worker != null) worker.submit(() -> null).get(10, TimeUnit.SECONDS);
        } catch (Exception error) { throw new AssertionError("灯光后台队列应完成当前命令", error); }
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private static <T extends Activity> T resumed(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("灯光页面主线程验收失败", failure.get());
        return value.get();
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Object staticField(String name) {
        try {
            Field field = LightController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setStaticField(String name, Object value) {
        try {
            Field field = LightController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
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
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else throw new AssertionError("未知配置类型：" + entry.getKey());
        }
        assertTrue(editor.commit());
        assertEquals("应恢复完整设备配置", original, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
