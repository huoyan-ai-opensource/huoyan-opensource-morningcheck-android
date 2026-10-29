package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.ScreenViewGeometry.visibleOnScreen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.login.ShopLoginCoordinator;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 真实窗口的沉浸与输入法验收；不录入业务资料、不保存镜头或系统显示配置。 */
@RunWith(AndroidJUnit4.class)
public final class ImmersiveUiTest {
    @Rule public final TestName testName = new TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final JSONArray evidence = new JSONArray();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<? extends Activity> scenario;
    private AlertDialog dialog;
    private File output;
    private boolean completed;
    private boolean restored;
    private boolean isolated;
    private boolean educationDismissed;

    @Before public void isolateAllConnections() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
        assumeTrue("沉浸夹具只复用 x86 模拟器，不中断现场设备会话",
                x86 && (hardware.contains("ranchu") || hardware.contains("goldfish")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshotPreferences(device);
        originalBinding = snapshotPreferences(binding);
        onMain(() -> {
            assertFalse("不能中断操作员尚未结束的登录", app.shopLogin().getState().isRunning());
            isolated = true;
            app.sdkSession().disconnect();
            app.shopLogin().clearResult();
            return null;
        });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/cloud"))
                    businessRequests.incrementAndGet();
                // 不升级 WebSocket，登录只能连接失败，永远不会提交真实或模拟成功的绑定。
                return new MockResponse().setResponseCode(503).setBody("沉浸界面测试：SDK 未连接");
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false).commit());
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        output = new File(app.getCacheDir(), "immersive-evidence");
        assertTrue(output.isDirectory() || output.mkdirs());
    }

    @After public void closeWindowsAndRestoreConfiguration() throws Exception {
        try {
            if (dialog != null) onMain(() -> { dialog.dismiss(); return null; });
            if (scenario != null) scenario.close();
        } finally {
            try {
                if (app != null && isolated) {
                    onMain(() -> { app.sdkSession().disconnect(); return null; });
                    await(() -> !onMain(() -> app.shopLogin().getState().isRunning()), 8_000,
                            "应先结束仅本机的失败登录，再恢复配置");
                    onMain(() -> { app.shopLogin().clearResult(); return null; });
                }
            } finally {
                try {
                    if (isolated && device != null && originalDevice != null) restorePreferences(device, originalDevice);
                    if (isolated && binding != null && originalBinding != null) restorePreferences(binding, originalBinding);
                    restored = isolated && originalDevice != null && originalDevice.equals(device.getAll())
                            && originalBinding != null && originalBinding.equals(binding.getAll());
                    if (isolated) assertTrue("必须恢复全部设备及门店配置", restored);
                } finally {
                    try { if (endpoint != null) endpoint.shutdown(); }
                    finally {
                        if (output != null) {
                            JSONObject metadata = new JSONObject().put("test", testName.getMethodName())
                                    .put("completed", completed).put("preferences_restored", restored)
                                    .put("fullscreen_education_dismissed", educationDismissed)
                                    .put("business_cloud_requests", businessRequests.get()).put("samples", evidence);
                            try (FileOutputStream stream = new FileOutputStream(
                                    new File(output, testName.getMethodName() + ".json"))) {
                                stream.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        }
        assertEquals("沉浸显示验收不应请求业务云", 0, businessRequests.get());
    }

    @Test public void pagesStayFullscreenAcrossRecreationAndBarsAreTransient() throws Exception {
        launch(new Intent(app, HomeActivity.class));
        Activity home = currentActivity();
        // 模拟器未装本机壳时只关闭安装引导，不进入安装、授权或连接设置。
        onMain(() -> {
            try {
                Field field = HomeActivity.class.getDeclaredField("sdkInstall");
                field.setAccessible(true);
                SdkInstallController installation = (SdkInstallController) field.get(home);
                if (installation != null) installation.onPause();
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            return null;
        });
        awaitFullscreen(R.id.main_root);
        recordPage("home", R.id.main_root);
        assertTransientBarsDoNotMoveContent();
        scenario.recreate();
        awaitFullscreen(R.id.main_root);
        recordPage("home-recreated", R.id.main_root);

        launch(new Intent(app, DevicesActivity.class).putExtra(DevicesActivity.EXTRA_SECTION, 0));
        awaitFullscreen(R.id.main_root);
        assertSdkSection();
        recordPage("sdk-settings", R.id.main_root);
        scenario.recreate();
        awaitFullscreen(R.id.main_root);
        assertSdkSection();
        recordPage("sdk-settings-recreated", R.id.main_root);

        scenario.close();
        scenario = null;
        // 先进入等待连接，再创建登录页；页面不会启动扫码镜头，503 夹具只能产生确定失败。
        onMain(() -> {
            app.shopLogin().login("IMMERSIVE-UI-NO-BIND", MorningSdkSettings.read(device));
            return null;
        });
        launch(new Intent(app, LoginActivity.class));
        await(() -> onMain(() -> app.shopLogin().getState().phase == ShopLoginCoordinator.Phase.ERROR),
                8_000, "登录应停在隔离连接失败态，不产生绑定");
        awaitFullscreen(R.id.login_root);
        recordPage("login", R.id.login_root);
        scenario.recreate();
        awaitFullscreen(R.id.login_root);
        recordPage("login-recreated", R.id.login_root);
        assertEquals("失败登录不得改写门店归属", originalBinding, binding.getAll());
        assertCameraPreferencesUnchanged();
        completed = true;
    }

    @Test public void inputDialogAndKeyboardRestoreImmersiveLayoutWithoutSaving() throws Exception {
        launch(new Intent(app, DevicesActivity.class).putExtra(DevicesActivity.EXTRA_SECTION, 0));
        awaitFullscreen(R.id.main_root);
        assertSdkSection();
        Activity activity = currentActivity();
        Window host = activity.getWindow();
        Snapshot baseline = onMain(() -> snapshot(host, activity.findViewById(R.id.main_root)));
        EditText pageInput = onMain(() -> activity.findViewById(R.id.settings_sdk_url));
        // 系统输入法显示时允许窗口避让；收起后必须恢复页面与弹窗的原有布局。
        if (pageInput != null) {
            Rect inputBaseline = onMain(() -> screenBounds(pageInput));
            showKeyboard(host, pageInput);
            recordPage("sdk-keyboard", R.id.main_root);
            assertInputAboveKeyboard(host, pageInput);
            hideKeyboard(host);
            awaitFullscreen(R.id.main_root);
            assertLayoutRestored(baseline, host, onMain(() -> activity.findViewById(R.id.main_root)));
            await(() -> onMain(() -> inputBaseline.equals(screenBounds(pageInput))),
                    5_000, "收起键盘后应恢复输入框和设置标题的原有位置");
        }

        AtomicInteger submitted = new AtomicInteger();
        EditText input = onMain(() -> {
            View form = activity.getLayoutInflater().inflate(R.layout.dialog_text_prompt, null);
            EditText edit = form.findViewById(R.id.prompt_value);
            edit.setText("仅验证输入法，不保存");
            dialog = ImmersiveUi.show(new ProjectDialog.Builder(activity).setTitle("沉浸输入验收")
                    .setView(form).setNegativeButton("取消", null)
                    .setPositiveButton("确定", (ignored, which) -> submitted.incrementAndGet()).create());
            return edit;
        });
        Window dialogWindow = onMain(dialog::getWindow);
        awaitBarsHidden(dialogWindow);
        Snapshot dialogBaseline = onMain(() -> snapshot(dialogWindow, dialogWindow.getDecorView()));
        Rect promptBaseline = onMain(() -> screenBounds(input));
        recordWindow("input-dialog", dialogWindow, onMain(dialogWindow::getDecorView));
        showKeyboard(dialogWindow, input);
        assertInputAboveKeyboard(dialogWindow, input);
        recordWindow("input-dialog-keyboard", dialogWindow, onMain(dialogWindow::getDecorView));
        hideKeyboard(dialogWindow);
        await(() -> onMain(() -> !dialogWindow.getDecorView().isLayoutRequested()
                && dialogBaseline.decor.equals(screenBounds(dialogWindow.getDecorView()))
                && promptBaseline.equals(screenBounds(input))), 5_000, "输入关闭后应恢复业务弹窗与原字段边界");
        assertLayoutRestored(dialogBaseline, dialogWindow, onMain(dialogWindow::getDecorView));
        // 系统输入法关闭后，原业务弹窗继续保持沉浸显示。
        awaitBarsHidden(dialogWindow);
        onMain(() -> { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); return null; });
        await(() -> onMain(() -> !dialog.isShowing()), 5_000, "取消应关闭输入弹窗");
        awaitFullscreen(R.id.main_root);
        assertLayoutRestored(baseline, host, onMain(() -> activity.findViewById(R.id.main_root)));
        recordPage("after-dialog-cancel", R.id.main_root);
        assertEquals("关闭验收弹窗不能提交输入", 0, submitted.get());
        assertCameraPreferencesUnchanged();
        completed = true;
    }

    private void launch(Intent intent) {
        if (scenario != null) scenario.close();
        scenario = ActivityScenario.launch(intent);
        instrumentation.waitForIdleSync();
        await(() -> onMain(() -> !(currentActivity() instanceof BaseMorningActivity)
                || ((BaseMorningActivity) currentActivity()).loaded), 8_000,
                "窗口验收前应完成页面首次数据读取与布局");
    }

    private Activity currentActivity() {
        AtomicReference<Activity> result = new AtomicReference<>();
        scenario.onActivity(result::set);
        return result.get();
    }

    private void assertSdkSection() {
        Activity activity = currentActivity();
        onMain(() -> {
            assertTrue("SDK 设置应由独立 DevicesActivity 承载", activity instanceof DevicesActivity);
            assertTrue(activity.findViewById(R.id.settings_sdk_lan).isSelected());
            assertTrue(activity.findViewById(R.id.settings_sdk_url).isShown());
            return null;
        });
    }

    private void awaitFullscreen(int rootId) {
        Activity activity = currentActivity();
        await(() -> {
            dismissFullscreenEducation();
            return onMain(() -> {
            View root = activity.findViewById(rootId);
            if (root == null || !root.hasWindowFocus()) return false;
            Snapshot state = snapshot(activity.getWindow(), root);
            return state != null && state.barsHidden() && state.root.equals(state.decor)
                    && state.root.width() > 0 && state.root.height() > 0 && !state.imeVisible
                    && state.padding.equals(new Rect(state.cutout.left, state.cutout.top,
                    state.cutout.right, state.cutout.bottom));
            });
        }, 12_000, "系统栏应隐藏，页面根布局应覆盖完整窗口且拥有焦点");
        onMain(() -> {
            Window window = activity.getWindow();
            Snapshot state = snapshot(window, activity.findViewById(rootId));
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    WindowCompat.getInsetsController(window, window.getDecorView()).getSystemBarsBehavior());
            assertEquals("关闭键盘后左侧只保留刘海安全区", state.cutout.left, state.padding.left);
            assertEquals("顶部不能残留状态栏占位", state.cutout.top, state.padding.top);
            assertEquals("关闭键盘后右侧只保留刘海安全区", state.cutout.right, state.padding.right);
            assertEquals("底部不能残留导航栏或键盘占位", state.cutout.bottom, state.padding.bottom);
            return null;
        });
    }

    private void assertTransientBarsDoNotMoveContent() throws Exception {
        Activity activity = currentActivity();
        Window window = activity.getWindow();
        View root = onMain(() -> activity.findViewById(R.id.main_root));
        Snapshot baseline = onMain(() -> snapshot(window, root));
        float x = baseline.decor.left + baseline.decor.width() * 0.75f;
        float top = baseline.decor.top + 1;
        float distance = Math.max(60, baseline.decor.height() / 7f);
        Bitmap before = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(before);
        try {
            long down = SystemClock.uptimeMillis();
            for (int index = 0; index <= 12; index++) {
                int action = index == 0 ? MotionEvent.ACTION_DOWN
                        : index == 12 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        x, top + distance * index / 12f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { assertTrue("系统边缘滑动应成功注入", instrumentation.getUiAutomation().injectInputEvent(event, true)); }
                finally { event.recycle(); }
                SystemClock.sleep(16);
            }
            // 部分系统不向应用分发临时系统栏的可见性，实际边缘画面必须有变化且不能打开通知面板。
            await(() -> onMain(root::hasWindowFocus) && !screenTopEdgeMatches(before), 3_000,
                    "一次边缘滑动应临时显示系统栏，并保留应用焦点");
            assertLayoutRestored(baseline, window, root);
            recordWindow("home-transient-bars", window, root);
            await(() -> screenTopEdgeMatches(before), 12_000, "临时系统栏应自动隐藏，还原全屏边缘画面");
            awaitFullscreen(R.id.main_root);
        } finally { before.recycle(); }
    }

    private boolean screenTopEdgeMatches(Bitmap before) {
        Bitmap after = instrumentation.getUiAutomation().takeScreenshot();
        if (after == null) return false;
        try {
            if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) return false;
            int band = Math.max(24, before.getHeight() / 24);
            int changed = 0;
            // 首页顶部是稳定页头；底部 SDK 重试提示可能变化，不能把业务文案变化当作系统栏。
            for (int y = 0; y < band; y += 2) {
                for (int x = 0; x < before.getWidth(); x += 2)
                    if (before.getPixel(x, y) != after.getPixel(x, y) && ++changed > 20) return false;
            }
            return true;
        } finally { after.recycle(); }
    }

    @SuppressWarnings("deprecation")
    private void dismissFullscreenEducation() {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return;
        try {
            String owner = String.valueOf(root.getPackageName());
            if (!owner.equals("com.android.systemui") && !owner.equals("android")) return;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("android:id/ok")) {
                try {
                    String label = String.valueOf(node.getText()).trim();
                    // 仅处理系统第一次全屏教育提示，不点击权限对话框或任意应用的确认按钮。
                    if (node.isVisibleToUser() && node.isEnabled()
                            && (label.equalsIgnoreCase("Got it") || label.equals("知道了") || label.equals("我知道了")))
                        educationDismissed |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } finally { node.recycle(); }
            }
        } finally { root.recycle(); }
    }

    private void showKeyboard(Window window, EditText input) {
        await(() -> onMain(() -> input.isShown() && input.hasWindowFocus()
                && !input.isLayoutRequested()), 5_000, "点按前原输入框应已取得窗口焦点并完成布局");
        onMain(() -> {
            assertTrue("普通输入框应允许系统输入法", input.getShowSoftInputOnFocus());
            assertTrue("应能聚焦原业务输入框", input.requestFocus());
            return null;
        });
        await(() -> onMain(() -> {
            InputMethodManager keyboard = (InputMethodManager) input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            return keyboard != null && keyboard.isActive(input);
        }), 5_000, "显示键盘前应建立原输入框的系统输入连接");
        onMain(() -> {
            InputMethodManager keyboard = (InputMethodManager) input.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            WindowCompat.getInsetsController(window, window.getDecorView()).show(WindowInsetsCompat.Type.ime());
            return null;
        });
        await(() -> onMain(() -> {
            WindowInsetsCompat insets = inputInsets(window);
            return input.hasFocus() && insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
                    && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0;
        }), 10_000, "聚焦普通输入框后应实际显示系统输入法");
        awaitKeyboardGeometryStable(window, input);
    }

    private void awaitKeyboardGeometryStable(Window window, EditText input) {
        long visibleAt = SystemClock.elapsedRealtime();
        long unchangedSince = visibleAt;
        String previous = null;
        // 系统输入法会改变可见区域，等待窗口避让和输入框布局完成后再检查遮挡。
        do {
            String current = onMain(() -> {
                View decor = window.getDecorView();
                WindowInsetsCompat insets = inputInsets(window);
                if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())
                        || !input.isAttachedToWindow() || !input.isShown() || !input.hasFocus()
                        || decor.isLayoutRequested() || input.isLayoutRequested()) return null;
                Rect visible = new Rect();
                decor.getWindowVisibleDisplayFrame(visible);
                Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
                return keyboard.toString() + ";" + visible.toShortString() + ";"
                        + screenBounds(decor).toShortString() + ";" + screenBounds(input).toShortString();
            });
            long now = SystemClock.elapsedRealtime();
            if (current == null || !current.equals(previous)) {
                previous = current;
                unchangedSince = now;
            } else if (now - visibleAt >= 750 && now - unchangedSince >= 300) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() - visibleAt < 10_000);
        fail("系统输入法显示后窗口和输入框几何应稳定：" + previous);
    }

    private void hideKeyboard(Window window) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(() -> onMain(() -> {
            WindowInsetsCompat insets = inputInsets(window);
            return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                    && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0;
        }), 10_000, "系统输入法应真正关闭且不再占用输入区域");
    }

    private void assertInputAboveKeyboard(Window window, EditText input) {
        AtomicReference<String> geometry = new AtomicReference<>();
        try {
            await(() -> onMain(() -> {
                WindowInsetsCompat insets = inputInsets(window);
                if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime())) return false;
                Rect visible = new Rect();
                // 系统返回屏幕可见区域，避免对已经缩小的弹窗再次扣除整个 IME 高度。
                input.getWindowVisibleDisplayFrame(visible);
                Rect bounds = screenBounds(input), clipped = new Rect();
                geometry.set("输入框=" + bounds + "，系统输入法上方可见区域=" + visible);
                return input.isAttachedToWindow() && input.isShown() && input.hasFocus()
                        && bounds.width() > 0 && bounds.height() > 0 && visible.contains(bounds)
                        && visibleOnScreen(input, clipped) && clipped.equals(bounds)
                        && input.getRootView() == window.getDecorView();
            }), 5_000, "系统输入法显示时原业务输入框应完整可见且可继续输入");
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "；" + geometry.get(), failure);
        }
    }

    private void assertLayoutRestored(Snapshot baseline, Window window, View root) {
        onMain(() -> {
            Snapshot state = snapshot(window, root);
            assertNotNull(state);
            assertEquals("临时系统栏或收起键盘后不能改变页面占用范围", baseline.root, state.root);
            assertEquals("不能累加系统栏或输入法 padding", baseline.padding, state.padding);
            return null;
        });
    }

    private WindowInsetsCompat inputInsets(Window window) {
        // 系统已为浮动弹窗消费 IME inset，从宿主读取实际键盘占位。
        Window source = window.isFloating() ? currentActivity().getWindow() : window;
        return ViewCompat.getRootWindowInsets(source.getDecorView());
    }

    private void awaitBarsHidden(Window window) {
        await(() -> onMain(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(window.getDecorView());
            return insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars())
                    && !insets.isVisible(WindowInsetsCompat.Type.navigationBars());
        }), 12_000, "弹窗获得焦点或键盘收起后应隐藏状态栏和系统导航栏");
    }

    private void recordPage(String name, int rootId) throws Exception {
        Activity activity = currentActivity();
        recordWindow(name, activity.getWindow(), onMain(() -> activity.findViewById(rootId)));
    }

    private void recordWindow(String name, Window window, View root) throws Exception {
        Snapshot state = onMain(() -> snapshot(window, root));
        assertNotNull(state);
        // 必须截取整个显示画面；View.draw 不包含系统栏与输入法窗口，无法证明沉浸状态。
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
        JSONObject sample = new JSONObject().put("sample", name).put("root", state.root.toShortString())
                .put("decor", state.decor.toShortString()).put("padding", state.padding.toShortString())
                .put("status_bar_visible", state.statusVisible).put("navigation_bar_visible", state.navVisible)
                .put("ime_visible", state.imeVisible).put("ime_bottom", state.imeBottom);
        evidence.put(sample);
    }

    private static Snapshot snapshot(Window window, View root) {
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(window.getDecorView());
        return insets == null ? null : new Snapshot(window, root, insets);
    }

    private static Rect screenBounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static final class Snapshot {
        final Rect root, decor, padding;
        final Insets cutout;
        final boolean statusVisible, navVisible, imeVisible;
        final int imeBottom;
        Snapshot(Window window, View view, WindowInsetsCompat insets) {
            root = screenBounds(view);
            decor = screenBounds(window.getDecorView());
            padding = new Rect(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), view.getPaddingBottom());
            cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
            statusVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars());
            navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars());
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        }
        boolean barsHidden() { return !statusVisible && !navVisible; }
    }

    private void assertCameraPreferencesUnchanged() {
        assertEquals("身份镜头配置不得变化", originalDevice.get("face_camera"), device.getAll().get("face_camera"));
        assertEquals("手部镜头配置不得变化", originalDevice.get("hand_camera"), device.getAll().get("hand_camera"));
    }

    private <T> T onMain(Callable<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(work.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程验收失败", failure.get());
        return result.get();
    }

    private static Map<String, Object> snapshotPreferences(SharedPreferences preferences) {
        Map<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            snapshot.put(entry.getKey(), entry.getValue() instanceof Set
                    ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static void restorePreferences(SharedPreferences preferences, Map<String, Object> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else throw new AssertionError("未知设备配置类型：" + entry.getKey());
        }
        assertTrue("应完整恢复测试前配置", editor.commit());
    }

    private static void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(60);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
