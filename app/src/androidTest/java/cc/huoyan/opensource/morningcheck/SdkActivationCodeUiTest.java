package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.fragment.app.Fragment;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.domain.ActivationCodeInput;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 仅操作激活草稿，以本机 SDK 夹具和结果监听器隔离真实授权写入。 */
@RunWith(AndroidJUnit4.class)
public final class SdkActivationCodeUiTest {
    private static final String RAW = "Z2FBW9L7M78R586H", GROUPED = "Z2FB-W9L7-M78R-586H";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger(), managementWrites = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Bundle> results = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> scenario;

    @Before public void isolateEveryEndpointBeforeShowingInput() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device); originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("测试不能打断门店登录", app.shopLogin().getState().isRunning());
            if (ToastEx.toast != null) ToastEx.toast.cancel();
            ToastEx.toast = null;
            finishPages(); app.sdkSession().disconnect(); return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            String command = request.getString("cmd");
                            if (!"init".equals(command) && !command.startsWith("get")) managementWrites.incrementAndGet();
                            JSONObject data = new JSONObject();
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY")
                                    .put("model_ready", true).put("certificate_valid", true).put("key_lost", false);
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "输入窗只读隔离夹具").put("data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start(java.net.InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false).commit());
    }

    @After public void closeInputAndRestoreOriginalConfiguration() throws Exception {
        Map<String, Object> bindingAfter = null;
        try { if (scenario != null) scenario.close(); }
        finally {
            try {
                if (app != null) onMain(() -> {
                    if (ToastEx.toast != null) ToastEx.toast.cancel();
                    ToastEx.toast = null;
                    finishPages(); app.sdkSession().disconnect(); return null;
                });
                instrumentation.waitForIdleSync();
                if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    if (originalDevice != null) restore(device, originalDevice);
                    if (originalBinding != null) { bindingAfter = snapshot(binding); restore(binding, originalBinding); }
                } finally {
                    for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                    if (endpoint != null) endpoint.shutdown();
                }
            }
        }
        assertEquals("输入测试不能变更 SDK 门店归属", originalBinding, bindingAfter);
        assertEquals("不能访问真实或模拟业务云", 0, businessRequests.get());
        assertEquals("输入测试不能激活、续期或提交任何管理操作", 0, managementWrites.get());
        assertNull("本机夹具协议应正确", serverFailure.get());
    }

    @Test public void dedicatedKeyboardUsesLegalAlphabetAndEnablesOnlyCompleteGroupedSubmission() {
        launchInput();
        onMain(() -> {
            Dialog dialog = input().getDialog();
            assertFalse(dialog.findViewById(R.id.activation_code_activate).isEnabled());
            List<TextView> keys = characterKeys(input().keyboardView());
            Set<String> alphabet = new HashSet<>();
            for (TextView key : keys) alphabet.add(key.getText().toString());
            assertEquals(ActivationCodeInput.CHARACTERS.length(), alphabet.size());
            for (int i = 0; i < ActivationCodeInput.CHARACTERS.length(); i++)
                assertTrue(alphabet.contains(String.valueOf(ActivationCodeInput.CHARACTERS.charAt(i))));
            assertFalse(alphabet.contains("0")); assertFalse(alphabet.contains("I")); assertFalse(alphabet.contains("O"));
            saveEvidence(dialog.findViewById(R.id.activation_code_panel), "attached-empty.png");
            for (int i = 0; i < RAW.length(); i++) {
                key(dialog, "activation-key-" + RAW.charAt(i)).performClick();
                assertEquals("每次键入只增一个字符", RAW.substring(0, i + 1), editor().getText().toString());
                assertEquals("恰好十六位才启用", i == RAW.length() - 1,
                        dialog.findViewById(R.id.activation_code_activate).isEnabled());
                if (i == 5) saveEvidence(dialog.findViewById(R.id.activation_code_panel), "attached-partial.png");
            }
            int[] groups = {R.id.activation_code_segment_1, R.id.activation_code_segment_2,
                    R.id.activation_code_segment_3, R.id.activation_code_segment_4};
            for (int i = 0; i < groups.length; i++)
                assertEquals(RAW.substring(i * 4, i * 4 + 4), ((TextView) dialog.findViewById(groups[i])).getText().toString());
            saveEvidence(dialog.findViewById(R.id.activation_code_panel), "attached-complete.png");
            View submit = dialog.findViewById(R.id.activation_code_activate);
            submit.performClick(); submit.performClick();
            assertEquals("同次双击只产生一个提交结果", 1, results.size());
            assertEquals("activate", results.get(0).getString("action"));
            assertEquals(GROUPED, results.get(0).getString("code"));
            return null;
        });
    }

    @Test public void invalidPastePreservesSelectedCharactersAndLegalPasteOrReplacementKeepsSelection() {
        launchInput();
        onMain(() -> {
            EditText editor = editor();
            editor.setText("ABCD1234"); editor.setSelection(2, 6);
            for (String invalid : new String[]{"0", "I", "O", "ab", "A0B", "中文"}) {
                editor.getText().replace(2, 6, invalid);
                assertEquals("拒绝非法输入不能删除原选区：" + invalid, "ABCD1234", editor.getText().toString());
                assertEquals("拒绝非法输入不能移动选区起点", 2, editor.getSelectionStart());
                assertEquals("拒绝非法输入不能移动选区终点", 6, editor.getSelectionEnd());
                assertInvalidInputMessage();
            }
            editor.setSelection(2, 6);
            key(input().getDialog(), "activation-key-Z").performClick();
            assertEquals("ABZ34", editor.getText().toString());
            assertEquals(3, editor.getSelectionStart()); assertEquals(3, editor.getSelectionEnd());
            assertNoInputMessage();
            editor.getText().replace(0, editor.length(), GROUPED);
            assertEquals("完整四组粘贴应还原成可编辑原码", RAW, editor.getText().toString());
            editor.setSelection(4, 8);
            editor.getText().replace(4, 8, "1-23");
            assertEquals("任意位置连字符不能被删掉后冒充有效输入", RAW, editor.getText().toString());
            assertEquals(4, editor.getSelectionStart()); assertEquals(8, editor.getSelectionEnd());
            assertInvalidInputMessage();
            editor.setSelection(0, 4);
            key(input().getDialog(), "activation-backspace").performClick();
            assertEquals("退格应优先删除选区", RAW.substring(4), editor.getText().toString());
            assertEquals(0, editor.getSelectionStart());
            input().getDialog().findViewById(R.id.activation_code_clear).performClick();
            assertEquals("", editor.getText().toString());
            assertEquals(0, editor.getSelectionStart());
            assertNoInputMessage();
            assertFalse(input().getDialog().findViewById(R.id.activation_code_activate).isEnabled());
            return null;
        });
    }

    @Test public void rejectedCharacterWithoutSelectionDoesNotSwallowNextLegalKeyOrLeaveStaleError() {
        launchInput();
        onMain(() -> {
            EditText editor = editor();
            editor.setText(""); editor.setSelection(0);
            editor.getText().insert(0, "0");
            assertEquals("空选区的非法字符必须被完整拒绝", "", editor.getText().toString());
            assertEquals(0, editor.getSelectionStart()); assertEquals(0, editor.getSelectionEnd());
            assertInvalidInputMessage();
            key(input().getDialog(), "activation-key-Z").performClick();
            assertEquals("合法输入不能被上一次拒绝标记吞掉", "Z", editor.getText().toString());
            assertEquals(1, editor.getSelectionStart()); assertEquals(1, editor.getSelectionEnd());
            assertNoInputMessage();
            input().getDialog().findViewById(R.id.activation_code_clear).performClick();
            assertEquals("", editor.getText().toString());
            assertEquals(0, editor.getSelectionStart()); assertEquals(0, editor.getSelectionEnd());
            assertNoInputMessage();
            assertFalse(input().getDialog().findViewById(R.id.activation_code_activate).isEnabled());
            return null;
        });
        assertTrue("草稿操作不能产生业务提交", results.isEmpty());
    }

    @Test public void requestedOrientationRotationResizesActualDialogAndPreservesDraftAndSelection() {
        launchInput();
        int originalRequest = onMain(() -> home().getRequestedOrientation());
        int originalOrientation = onMain(() -> home().getResources().getConfiguration().orientation);
        int opposite = originalOrientation == Configuration.ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        onMain(() -> {
            editor().setText("Z2FBW9L7"); editor().setSelection(2, 6);
            return null;
        });
        try {
            for (int orientation : new int[]{opposite, originalOrientation}) {
                onMain(() -> {
                    // 让 Android 实际更新窗口与资源，不用手工传 Configuration 冒充旋转。
                    home().setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                            ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    return null;
                });
                await(() -> onMain(() -> rotationSettled(orientation)), "实际旋转后激活弹窗应按宿主窗口重新量高和量宽");
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    assertEquals("真实旋转不能丢掉草稿", "Z2FBW9L7", editor().getText().toString());
                    assertEquals("真实旋转不能改变选区起点", 2, editor().getSelectionStart());
                    assertEquals("真实旋转不能改变选区终点", 6, editor().getSelectionEnd());
                    Dialog dialog = input().getDialog();
                    int[] expected = actualPanelSize(home());
                    assertEquals(expected[0], dialog.getWindow().getAttributes().width);
                    assertEquals(expected[1], dialog.getWindow().getAttributes().height);
                    ViewGroup panel = dialog.findViewById(R.id.activation_code_panel);
                    assertEquals("面板宽度应与旋转后的实际窗口一致", expected[0], panel.getWidth());
                    assertEquals("面板高度应与旋转后的实际窗口一致", expected[1], panel.getHeight());
                    for (int id : requiredViews()) assertVisible(dialog.findViewById(id));
                    for (View key : allKeys(input().keyboardView())) {
                        assertVisible(key); assertTouchSize(key);
                    }
                    assertSame("旋转后键区仍应属于当前业务弹窗", dialog.findViewById(R.id.activation_code_keyboard),
                            input().keyboardView());
                    assertFalse("内嵌键区不能遮住原激活码字段", Rect.intersects(
                            ScreenViewGeometry.bounds(editor()), ScreenViewGeometry.bounds(input().keyboardView())));
                    saveEvidence(panel, orientation == Configuration.ORIENTATION_LANDSCAPE
                            ? "attached-rotated-landscape.png" : "attached-rotated-portrait.png");
                    return null;
                });
            }
        } finally {
            onMain(() -> { if (home() != null) home().setRequestedOrientation(originalRequest); return null; });
        }
        assertTrue("实际旋转不能生成授权提交", results.isEmpty());
    }

    private boolean rotationSettled(int orientation) {
        HomeActivity activity = home();
        SdkActivationCodeDialog fragment = input();
        if (activity == null || fragment == null || fragment.getDialog() == null
                || activity.getResources().getConfiguration().orientation != orientation) return false;
        View decor = activity.getWindow().getDecorView();
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0 || (decor.getWidth() > decor.getHeight()) != landscape) return false;
        Dialog dialog = fragment.getDialog();
        if (!dialog.isShowing() || dialog.getWindow() == null) return false;
        View panel = dialog.findViewById(R.id.activation_code_panel);
        int[] expected = actualPanelSize(activity);
        return panel != null && panel.getWidth() == expected[0] && panel.getHeight() == expected[1]
                && dialog.getWindow().getAttributes().width == expected[0]
                && dialog.getWindow().getAttributes().height == expected[1];
    }

    private int[] actualPanelSize(Activity activity) {
        Rect bounds;
        if (Build.VERSION.SDK_INT >= 30) bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        else {
            View decor = activity.getWindow().getDecorView();
            bounds = new Rect(0, 0, decor.getWidth(), decor.getHeight());
        }
        View panel = input().getDialog().findViewById(R.id.activation_code_panel);
        return SdkActivationCodeDialog.panelSize(bounds.width(), bounds.height(), panel.getContext());
    }

    private void assertInvalidInputMessage() {
        TextView error = input().getDialog().findViewById(R.id.activation_code_error);
        assertEquals("非法输入不能在表单中追加错误文案", View.GONE, error.getVisibility());
        assertToastMessage(home().getString(R.string.activation_code_invalid));
    }

    private void assertNoInputMessage() {
        TextView error = input().getDialog().findViewById(R.id.activation_code_error);
        assertEquals("合法编辑后不能保留表单错误文案", View.GONE, error.getVisibility());
        assertEquals("", error.getText().toString());
        assertEquals("合法编辑后应恢复字符规则", View.VISIBLE,
                input().getDialog().findViewById(R.id.activation_code_panel)
                        .findViewWithTag("activation-alphabet-hint").getVisibility());
    }

    private static void assertToastMessage(String expected) {
        assertNotNull("错误必须通过统一 Toast 反馈", ToastEx.toast);
        assertNotNull(ToastEx.toast.getView());
        TextView message = ToastEx.toast.getView().findViewById(R.id.toast_text);
        assertEquals(expected, message.getText().toString());
    }

    @Test public void rejectedSubmissionReenablesEditingAndScanOrCancelNeverWritesSdk() {
        launchInput();
        onMain(() -> {
            SdkActivationCodeDialog dialog = input();
            editor().setText(RAW); editor().setSelection(5);
            dialog.getDialog().findViewById(R.id.activation_code_activate).performClick();
            assertEquals(1, results.size());
            dialog.onSubmitRejected("连接配置已改变，请先重新连接。");
            assertEquals(RAW, editor().getText().toString());
            assertEquals(5, editor().getSelectionStart());
            assertTrue(editor().isEnabled());
            assertTrue(dialog.getDialog().findViewById(R.id.activation_code_activate).isEnabled());
            TextView error = dialog.getDialog().findViewById(R.id.activation_code_error);
            assertEquals("提交失败不能在表单中追加错误文案", View.GONE, error.getVisibility());
            assertToastMessage("连接配置已改变，请先重新连接。");
            saveEvidence(dialog.getDialog().findViewById(R.id.activation_code_panel), "attached-error.png");
            dialog.getDialog().findViewById(R.id.activation_code_scan).performClick();
            assertEquals(2, results.size());
            assertEquals("scan", results.get(1).getString("action"));
            // 独立结果监听器模拟扫码页取消；宿主恢复输入后才能继续修改或取消。
            dialog.onSubmitRejected("");
            dialog.getDialog().findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> { assertNull("取消必须移除输入窗", input()); return null; });
        assertEquals("取消不能产生额外提交结果", 2, results.size());
    }

    @Test public void currentAttachedWindowKeepsSegmentsKeysAndActionsVisibleWithSystemImeDisabled() {
        launchInput();
        onMain(() -> {
            Dialog dialog = input().getDialog();
            ViewGroup panel = dialog.findViewById(R.id.activation_code_panel);
            assertNoScroll(panel);
            assertExpandedLandscapeSizing(panel);
            for (int id : requiredViews()) assertVisible(dialog.findViewById(id));
            for (View key : allKeys(input().keyboardView())) {
                assertVisible(key); assertTouchSize(key);
            }
            for (int id : new int[]{R.id.activation_code_activate, R.id.activation_code_cancel,
                    R.id.activation_code_scan, R.id.activation_code_clear}) assertTouchSize(dialog.findViewById(id));
            editor().performClick();
            assertFalse(editor().getShowSoftInputOnFocus());
            editor().setText(RAW);
            editor().setSelection(0);
            View fourth = dialog.findViewById(R.id.activation_code_segment_4);
            Rect edit = ScreenViewGeometry.bounds(editor()), box = ScreenViewGeometry.bounds(fourth);
            long now = SystemClock.uptimeMillis();
            MotionEvent tap = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
                    box.centerX() - edit.left, box.centerY() - edit.top, 0);
            editor().onTouchEvent(tap); tap.recycle();
            assertTrue("二维分组点击必须选择第四组，而非同列的第二组", editor().getSelectionStart() >= 12);
            return null;
        });
    }

    @Test public void floatingCodeInputNeverResizesOrMovesTheHomeLayout() {
        launchHome();
        Map<View, Rect> originalLayout = onMain(() -> {
            Map<View, Rect> bounds = new HashMap<>();
            rememberHostBounds(home().getWindow().getDecorView(), bounds);
            assertTrue("主页必须先完成布局才能比较弹出效果", bounds.size() > 1);
            return bounds;
        });
        openInput();
        onMain(() -> {
            int mode = input().getDialog().getWindow().getAttributes().softInputMode;
            assertEquals("专用键盘不能让窗口调整业务布局", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals("打开激活输入不应请求系统键盘", WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
            assertHostBoundsUnchanged(originalLayout);
            key(input().getDialog(), "activation-key-A").performClick();
            key(input().getDialog(), "activation-key-5").performClick();
            assertEquals("A5", editor().getText().toString());
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertHostBoundsUnchanged(originalLayout);
            input().getDialog().findViewById(R.id.activation_code_clear).performClick();
            assertEquals("", editor().getText().toString());
            input().getDialog().findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertNull("取消应移除悬浮输入窗", input());
            assertHostBoundsUnchanged(originalLayout);
            return null;
        });
        assertTrue("输入和取消不能提交激活", results.isEmpty());
    }

    @Test public void inlineKeyboardEditsOriginalCodeAndScanOrBackActsOnTheSameDialog() {
        launchInput();
        await(() -> onMain(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(input().keyboardView());
            return insets != null && !insets.isVisible(WindowInsetsCompat.Type.systemBars())
                    && !insets.isVisible(WindowInsetsCompat.Type.ime());
        }), "激活弹窗应保持系统栏与系统输入法隐藏");
        Map<View, Rect> originalForm = onMain(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(input().keyboardView());
            assertNotNull("必须读取业务弹窗的Insets", insets);
            assertFalse("激活弹窗不能显示顶部或底部系统栏", insets.isVisible(WindowInsetsCompat.Type.systemBars()));
            assertFalse("激活弹窗不能引入系统IME", insets.isVisible(WindowInsetsCompat.Type.ime()));
            assertEquals("激活弹窗的IME占位应为零", 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            assertSame("键区必须直接包含在业务Dialog中", input().keyboardView(),
                    input().getDialog().findViewById(R.id.activation_code_keyboard));
            assertEquals("键区和输入框必须使用同一弹窗窗口", editor().getWindowToken(),
                    input().keyboardView().getWindowToken());
            assertNull("键区不能复制输入框", input().keyboardView().findViewById(R.id.activation_code_editor));
            assertNull("键区不能复制标题", input().keyboardView().findViewById(R.id.activation_code_title));
            assertFalse("内嵌键区不能遮住原激活码字段", Rect.intersects(
                    ScreenViewGeometry.bounds(editor()), ScreenViewGeometry.bounds(input().keyboardView())));
            Map<View, Rect> bounds = new HashMap<>();
            rememberHostBounds(input().getDialog().findViewById(R.id.activation_code_panel), bounds);
            key(input().getDialog(), "activation-key-A").performClick();
            key(input().getDialog(), "activation-key-5").performClick();
            assertEquals("按键应实时写入原字段", "A5", editor().getText().toString());
            assertEquals("A5", ((TextView) input().getDialog().findViewById(R.id.activation_code_segment_1)).getText().toString());
            assertTrue("按键不能转移原字段焦点", editor().hasFocus());
            return bounds;
        });
        Rect originalField = onMain(() -> ScreenViewGeometry.bounds(editor()));
        tapScreen(originalField);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertTrue("点击输入字段后内嵌键区应继续显示", input().isKeyboardShowing());
            assertEquals("A5", editor().getText().toString());
            assertTrue("点击字段应直接取得输入焦点", editor().hasFocus());
            assertHostBoundsUnchanged(originalForm);
            return null;
        });
        Rect scan = onMain(() -> {
            assertTrue(input().isKeyboardShowing());
            assertHostBoundsUnchanged(originalForm);
            return ScreenViewGeometry.bounds(input().getDialog().findViewById(R.id.activation_code_scan));
        });
        tapScreen(scan);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertNotNull("扫码结果交付前应保留草稿表单", input());
            assertEquals("点击扫码应直接交付，不能被收键盘手势吞掉", 1, results.size());
            assertEquals("scan", results.get(0).getString("action"));
            assertTrue("提交时内嵌键区仍应留在弹窗中", input().isKeyboardShowing());
            assertFalse("提交期间输入应锁定", editor().isEnabled());
            assertEquals("A5", editor().getText().toString());
            assertHostBoundsUnchanged(originalForm);
            input().onSubmitRejected("");
            assertTrue("扫码取消后应恢复输入", editor().isEnabled());
            assertTrue("扫码取消后键区仍可直接使用", input().isKeyboardShowing());
            return null;
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertNull("返回应关闭整个激活输入弹窗", input());
            return null;
        });
        assertEquals("返回不能产生额外授权提交", 1, results.size());
    }

    private void tapScreen(Rect target) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, target.centerX(), target.centerY(), 0);
        try { instrumentation.sendPointerSync(down); instrumentation.sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
    }

    @Test public void phoneAndTabletContextsKeepInlineControlsVisibleAtRegularAndLargestFonts() {
        onMain(() -> {
            for (float fontScale : new float[]{1f, 1.3f}) {
                assertConfigurationFits(320, 640, fontScale, "phone-portrait");
                assertConfigurationFits(320, 480, fontScale, "phone-short-portrait");
                assertConfigurationFits(640, 320, fontScale, "phone-landscape");
                assertConfigurationFits(480, 320, fontScale, "phone-narrow-landscape");
                assertConfigurationFits(800, 1280, fontScale, "tablet-portrait");
                assertConfigurationFits(1280, 800, fontScale, "tablet-landscape");
                assertConfigurationFits(960, 600, fontScale, "tablet-medium-landscape");
                assertConfigurationFits(800, 480, fontScale, "tablet-short-landscape");
            }
            return null;
        });
    }

    private void assertConfigurationFits(int widthDp, int heightDp, float fontScale, String name) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.screenWidthDp = widthDp; config.screenHeightDp = heightDp;
        config.smallestScreenWidthDp = 1; config.fontScale = fontScale;
        boolean landscape = widthDp > heightDp;
        config.orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        Context context = new ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme);
        ViewGroup panel = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.dialog_sdk_activation_code, null);
        SdkActivationCodeDialog.createKeyboard(panel);
        float density = context.getResources().getDisplayMetrics().density;
        int[] size = SdkActivationCodeDialog.panelSize(Math.round(widthDp * density),
                Math.round(heightDp * density), context);
        int width = size[0], height = size[1];
        int widthLimit = landscape ? 800 : 560;
        assertTrue("弹窗宽度应保持当前布局的上限", width <= Math.round(widthLimit * density));
        assertTrue("弹窗左右应保留边距", width <= Math.round((widthDp - 16) * density));
        assertTrue("弹窗上下应保留边距", height <= Math.round((heightDp - 16) * density));
        if (landscape && widthDp >= 600 && heightDp >= 480) {
            assertTrue("有空间的横屏应扩大激活输入面板", width >= Math.round(Math.min(600, widthDp * 0.7f) * density));
            assertTrue("有空间的横屏应扩大键区与操作区域", height >= Math.round(Math.min(440, heightDp - 24) * density));
        }
        String evidenceName = name + (fontScale > 1 ? "-130" : "-100");
        int[] groups = {R.id.activation_code_segment_1, R.id.activation_code_segment_2,
                R.id.activation_code_segment_3, R.id.activation_code_segment_4};
        String[] states = {"", "Z2FBW9", RAW};
        for (int state = 0; state < states.length; state++) {
            String value = states[state];
            for (int i = 0; i < groups.length; i++) {
                int start = Math.min(value.length(), i * 4);
                String part = value.substring(start, Math.min(value.length(), start + 4));
                ((TextView) panel.findViewById(groups[i])).setText(part.isEmpty() ? "····" : part);
            }
            panel.findViewById(R.id.activation_code_activate).setEnabled(ActivationCodeInput.complete(value));
            SdkActivationCodeDialog.fitPanel(panel, width, height, size[2]);
            measurePanel(panel, width, height);
            assertConfiguredControls(panel);
            assertKeyboardControls(panel.findViewById(R.id.activation_code_keyboard));
            assertExpandedLandscapeSizing(panel);
            saveEvidence(panel, evidenceName + '-' + (state == 0 ? "empty" : state == 1 ? "partial" : "complete") + ".png");
        }
        TextView error = panel.findViewById(R.id.activation_code_error);
        assertNotEquals("错误反馈应使用 Toast，不在输入面板内展示", View.VISIBLE, error.getVisibility());
        SdkActivationCodeDialog.fitPanel(panel, width, height, size[2]);
        measurePanel(panel, width, height);
        assertConfiguredControls(panel);
        assertKeyboardControls(panel.findViewById(R.id.activation_code_keyboard));
        saveEvidence(panel, evidenceName + "-refitted.png");
    }

    private static void measurePanel(View panel, int width, int height) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }
    private static void assertConfiguredControls(ViewGroup panel) {
        assertNoScroll(panel);
        for (int id : requiredViews()) assertContained(panel, panel.findViewById(id));
        assertContained(panel, panel.findViewWithTag("activation-alphabet-hint"));
        View rules = panel.findViewById(R.id.activation_code_rules);
        if (rules.getVisibility() == View.VISIBLE) assertContained(panel, rules);
        assertContained(panel, panel.findViewById(R.id.activation_code_keyboard));
        for (View key : allKeys(panel.findViewById(R.id.activation_code_keyboard))) assertContained(panel, key);
        for (int id : new int[]{R.id.activation_code_activate, R.id.activation_code_cancel,
                R.id.activation_code_scan, R.id.activation_code_clear}) assertTouchSize(panel.findViewById(id));
    }
    private static void assertKeyboardControls(LinearLayout keyboard) {
        assertNoScroll(keyboard);
        assertNull("内嵌键区不应复制标题", keyboard.findViewById(R.id.activation_code_title));
        assertNull("内嵌键区不应复制输入框", keyboard.findViewById(R.id.activation_code_editor));
        List<View> keys = allKeys(keyboard);
        assertEquals("全部合法字符和退格键都应参与实际布局", ActivationCodeInput.CHARACTERS.length() + 1, keys.size());
        for (View key : keys) { assertContained(keyboard, key); assertTouchSize(key); }
    }
    private static void assertContained(ViewGroup root, View view) {
        assertNotNull(view);
        Rect bounds = new Rect(); view.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue("控件必须有尺寸：" + view.getTag() + bounds, bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件不能超出配置面板：" + view.getTag() + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
        View ancestor = view;
        while (ancestor != root && ancestor.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) ancestor.getParent();
            if (parent.getClipChildren()) {
                Rect childBounds = new Rect(); view.getDrawingRect(childBounds);
                parent.offsetDescendantRectToMyCoords(view, childBounds);
                Rect clipping = parent.getClipToPadding()
                        ? new Rect(parent.getPaddingLeft(), parent.getPaddingTop(),
                                parent.getWidth() - parent.getPaddingRight(), parent.getHeight() - parent.getPaddingBottom())
                        : new Rect(0, 0, parent.getWidth(), parent.getHeight());
                assertTrue("控件不能被中间父容器裁切：" + view.getTag() + childBounds + " / " + clipping,
                        clipping.contains(childBounds));
            }
            ancestor = parent;
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            assertTrue("文字高度不能截断：" + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                assertEquals("文字不能省略：" + text.getText(), 0, text.getLayout().getEllipsisCount(line));
                assertTrue("文字宽度不能截断：" + text.getText(), text.getLayout().getLineWidth(line)
                        <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
        }
    }

    private void launchHome() {
        scenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        await(() -> onMain(() -> home() != null && app.sdkSession().viewState().ready), "只读 SDK 夹具应连接就绪");
        String orientation = InstrumentationRegistry.getArguments().getString("design_orientation", "");
        if (!orientation.isEmpty()) {
            boolean portrait = "portrait".equals(orientation);
            onMain(() -> { home().setRequestedOrientation(portrait
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
            await(() -> onMain(() -> home().getResources().getConfiguration().orientation == (portrait ? 1 : 2)),
                    "输入窗使用指定真机方向");
        }
        instrumentation.waitForIdleSync();
        await(() -> onMain(() -> home().findViewById(R.id.page_content).getWidth() > 0
                && home().findViewById(R.id.page_content).getHeight() > 0), "主页应完成布局");
    }

    private void launchInput() {
        launchHome();
        openInput();
    }

    private void openInput() {
        onMain(() -> {
            HomeActivity activity = home();
            activity.getSupportFragmentManager().setFragmentResultListener(SdkActivationCodeDialog.RESULT_KEY, activity,
                    (key, result) -> {
                        String action = result.getString("action");
                        if ("activate".equals(action) || "scan".equals(action)) results.add(new Bundle(result));
                    });
            SdkActivationCodeDialog.open(activity.getSupportFragmentManager());
            activity.getSupportFragmentManager().executePendingTransactions();
            return null;
        });
        instrumentation.waitForIdleSync();
        await(() -> onMain(() -> input() != null && input().getDialog() != null
                && input().getDialog().isShowing() && input().getDialog().findViewById(R.id.activation_code_panel).getWidth() > 0
                && input().isKeyboardShowing() && input().keyboardView().getWidth() > 0),
                "专用输入窗应显示并完成布局");
    }

    private static void rememberHostBounds(View view, Map<View, Rect> bounds) {
        if (view.isShown()) bounds.put(view, ScreenViewGeometry.bounds(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) rememberHostBounds(group.getChildAt(i), bounds);
        }
    }

    private static void assertHostBoundsUnchanged(Map<View, Rect> original) {
        for (Map.Entry<View, Rect> entry : original.entrySet()) {
            View view = entry.getKey();
            assertTrue("悬浮输入不能替换或移除宿主视图：" + view.getId(), view.isAttachedToWindow() && view.isShown());
            assertEquals("悬浮输入不能改变宿主控件位置或尺寸：" + view.getId(),
                    entry.getValue(), ScreenViewGeometry.bounds(view));
        }
    }

    private SdkActivationCodeDialog input() {
        HomeActivity activity = home();
        if (activity == null) return null;
        Fragment fragment = activity.getSupportFragmentManager().findFragmentByTag(SdkActivationCodeDialog.TAG);
        return fragment instanceof SdkActivationCodeDialog ? (SdkActivationCodeDialog) fragment : null;
    }
    private EditText editor() { return input().getDialog().findViewById(R.id.activation_code_editor); }
    private HomeActivity home() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof HomeActivity) return (HomeActivity) activity;
        return null;
    }
    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }
    private View key(Dialog dialog, String tag) {
        View key = input().keyboardView().findViewWithTag(tag);
        assertNotNull("缺少专用键：" + tag, key); return key;
    }
    private static List<TextView> characterKeys(View root) {
        List<TextView> keys = new ArrayList<>();
        for (View key : allKeys(root))
            if (key instanceof TextView && ((String) key.getTag()).startsWith("activation-key-")) keys.add((TextView) key);
        return keys;
    }
    private static List<View> allKeys(View root) {
        List<View> keys = new ArrayList<>();
        if (root.getTag() instanceof String && (((String) root.getTag()).startsWith("activation-key-")
                || "activation-backspace".equals(root.getTag()))) keys.add(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            keys.addAll(allKeys(((ViewGroup) root).getChildAt(i)));
        return keys;
    }
    private static int[] requiredViews() {
        return new int[]{R.id.activation_code_title,
                R.id.activation_code_segment_1, R.id.activation_code_segment_2,
                R.id.activation_code_segment_3, R.id.activation_code_segment_4, R.id.activation_code_scan,
                R.id.activation_code_clear, R.id.activation_code_cancel, R.id.activation_code_activate};
    }
    private static void assertVisible(View view) {
        assertNotNull(view); Rect visible = new Rect();
        assertTrue("控件必须真实可见", view.isShown() && ScreenViewGeometry.visibleOnScreen(view, visible));
        assertEquals("控件不能被窗口或父容器裁切", ScreenViewGeometry.bounds(view), visible);
    }
    private static void assertTouchSize(View view) {
        float density = view.getResources().getDisplayMetrics().density;
        Configuration config = view.getResources().getConfiguration();
        View panel = view;
        while (panel.getId() != R.id.activation_code_panel && panel.getParent() instanceof View)
            panel = (View) panel.getParent();
        boolean expanded = panel.getId() == R.id.activation_code_panel
                && panel.getWidth() > view.getResources().getDimensionPixelSize(R.dimen.dp_472)
                && panel.getHeight() >= view.getResources().getDimensionPixelSize(R.dimen.dp_440);
        int minimumWidth = Math.round(44 * density);
        int minimumHeight = Math.round((expanded && panel.getHeight() < Math.round(520 * density)
                && config.fontScale > 1 ? 42 : 44) * density);
        boolean key = view.getTag() instanceof String && (((String) view.getTag()).startsWith("activation-key-")
                || "activation-backspace".equals(view.getTag()));
        if (!expanded) {
            if (key) {
                ViewGroup row = (ViewGroup) view.getParent();
                ViewGroup keyboard = (ViewGroup) row.getParent();
                int gap = view.getResources().getDimensionPixelSize(R.dimen.dp_3);
                // 小手机必须在有限宽高中容纳九列四行，不能套用大屏的 44dp 下限。
                minimumWidth = Math.min(minimumWidth, Math.max(1, (keyboard.getWidth() - gap * 8) / 9));
                minimumHeight = Math.min(Math.round(36 * density), Math.max(Math.round(18 * density),
                        (keyboard.getHeight() - gap * 3) / 4));
            } else {
                minimumWidth = Math.round(30 * density);
                minimumHeight = Math.round(28 * density);
            }
        }
        assertTrue("触控宽度不足可用空间的下限：" + view.getTag(), view.getWidth() + 1 >= minimumWidth);
        assertTrue("触控高度不足可用空间的下限：" + view.getTag(), view.getHeight() + 1 >= minimumHeight);
    }

    private static void assertExpandedLandscapeSizing(ViewGroup panel) {
        Configuration config = panel.getResources().getConfiguration();
        if (config.screenWidthDp < 1000 || config.screenHeightDp < 700
                || config.screenWidthDp <= config.screenHeightDp) return;
        float density = panel.getResources().getDisplayMetrics().density;
        assertEquals("大横屏面板应利用可用空间", Math.round(800 * density), panel.getWidth(), 1);
        assertEquals("大横屏应同步增加面板高度", Math.round(560 * density), panel.getHeight(), 1);
        TextView segment = panel.findViewById(R.id.activation_code_segment_1);
        TextView key = panel.findViewWithTag("activation-key-A");
        assertEquals("大横屏激活码应清晰可读", panel.getResources().getDimension(R.dimen.sp_24), segment.getTextSize(), 1);
        assertEquals("大横屏键帽应同步放大", panel.getResources().getDimension(R.dimen.sp_22), key.getTextSize(), 1);
    }
    private static void assertNoScroll(View root) {
        assertFalse("激活表单不允许依赖滚动", root instanceof ScrollView);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            assertNoScroll(((ViewGroup) root).getChildAt(i));
    }
    private void saveEvidence(View view, String filename) {
        assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        File directory = new File(app.getFilesDir(), "codex-ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError("应写出原生 UI 验证图片", failure); }
        finally { bitmap.recycle(); }
    }
    private <T> T onMain(Callable<T> work) {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(work.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("激活输入 UI 回归失败", failure.get());
        return result.get();
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, Object> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
            else throw new AssertionError("未知配置类型");
        }
        assertTrue("应恢复测试前配置", editor.commit());
        assertEquals(values, preferences.getAll());
    }
    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
