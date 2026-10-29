package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 手机竖屏的真实入口滚动容器在返回、统计刷新和重建后保持位置，使用只读业务库与本机 SDK 夹具。 */
@RunWith(AndroidJUnit4.class)
public final class HomeScrollPositionTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger initCount = new AtomicInteger(), businessRequests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> scenario;
    private HomeActivity home;
    private Application.ActivityLifecycleCallbacks viewportFixture;

    @Before public void prepareIsolatedSdkAndScrollableHome() throws Exception {
        Context target = instrumentation.getTargetContext();
        // 当前横屏使用直接展示的网格；仅手机竖屏采用独立入口 ScrollView。
        assumeTrue("本项仅验证具有真实入口滚动容器的手机竖屏布局",
                target.getResources().getBoolean(R.bool.mc_phone)
                        && target.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device);
        originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("不能中断操作员的门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
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
                            JSONObject data = new JSONObject();
                            if ("init".equals(command)) initCount.incrementAndGet();
                            if ("getStatus".equals(command))
                                data.put("sdk_state", "READY").put("model_ready", true)
                                        .put("certificate_valid", true).put("device_id", "HOME-SCROLL-TEST");
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "首页滚动测试夹具")
                                    .put("data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false).commit());
        viewportFixture = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { constrainViewport(activity); }
            @Override public void onActivityResumed(Activity activity) { constrainViewport(activity); }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(viewportFixture);
        scenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        scenario.onActivity(activity -> home = activity);
        await(() -> onMain(() -> home.loaded && app.sdkSession().viewState().ready), "首页统计和隔离 SDK 应加载完成");
        awaitScrollable(home);
        assertEquals("首页只需初始化一次 SDK", 1, initCount.get());
    }

    @After public void finishPagesAndRestoreConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
        } finally {
            try {
                if (app != null) onMain(() -> {
                    if (viewportFixture != null) app.unregisterActivityLifecycleCallbacks(viewportFixture);
                    finishPages();
                    app.sdkSession().disconnect();
                    return null;
                });
                instrumentation.waitForIdleSync();
                if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    if (originalDevice != null) restore(device, originalDevice);
                    if (originalBinding != null)
                        assertEquals("首页滚动测试不能改变门店绑定", originalBinding, binding.getAll());
                } finally {
                    for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                    if (endpoint != null) endpoint.shutdown();
                }
            }
        }
        assertEquals("首页和系统设置导航不能访问业务云", 0, businessRequests.get());
        assertNull("SDK 夹具不能出现协议解析失败", serverFailure.get());
    }

    @Test public void openingSystemSettingsAndReturningKeepsBottomPosition() {
        ScrollView originalScroll = onMain(() -> shortcutScroll(home));
        int bottom = scrollToBottom(home);
        onMain(() -> {
            assertTrue("系统设置应从首页独立入口打开", home.findViewById(R.id.home_system).performClick());
            return null;
        });
        await(() -> resumed(SystemSettingsActivity.class) != null, "应进入系统设置 Activity");
        await(() -> onMain(() -> ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.STOPPED).contains(home)), "设置覆盖后首页应完成停止回调");
        SystemSettingsActivity system = resumed(SystemSettingsActivity.class);
        onMain(() -> {
            assertFalse("进入设置不能销毁首页实例", home.isDestroyed());
            home.loaded = false;
            assertTrue("设置页返回入口应可点击", system.findViewById(R.id.header_home).performClick());
            return null;
        });
        await(() -> onMain(() -> resumedOnMain(HomeActivity.class) == home && home.loaded), "返回首页应完成统计刷新");
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("返回首页应沿用入口视图", originalScroll, shortcutScroll(home));
            assertEquals("返回后入口列表应停留在离开前的位置", bottom, shortcutScroll(home).getScrollY());
            return null;
        });
        assertEquals("页面切换不能重新初始化 SDK", 1, initCount.get());
    }

    @Test public void staffAndRecordNotificationsRefreshSummaryWithoutResettingScroll() {
        ScrollView originalScroll = onMain(() -> shortcutScroll(home));
        int bottom = scrollToBottom(home);
        for (boolean staff : new boolean[]{true, false}) {
            onMain(() -> {
                home.loaded = false;
                if (staff) app.notifyStaffChanged(); else app.notifyRecordsChanged();
                return null;
            });
            await(() -> onMain(() -> home.loaded), "员工和记录通知应完成首页统计刷新");
            instrumentation.waitForIdleSync();
            onMain(() -> {
                assertSame("统计更新不能重建入口视图", originalScroll, shortcutScroll(home));
                assertEquals("统计更新不能将入口列表滚回开头", bottom, shortcutScroll(home).getScrollY());
                return null;
            });
        }
    }

    @Test public void layoutRenderAndActivityRecreationRestoreSavedScroll() {
        int bottom = scrollToBottom(home);
        ScrollView originalScroll = onMain(() -> shortcutScroll(home));
        onMain(() -> {
            home.render();
            // 与创建回调相同，在首轮测量前应用测试视口，避免恢复值被合法裁成零。
            constrainViewport(home);
            assertNotSame("真实重排应重新加载首页 XML", originalScroll, shortcutScroll(home));
            return null;
        });
        awaitRestored(home, bottom, "首页重排应恢复入口位置");
        HomeActivity previousHome = home;
        scenario.recreate();
        scenario.onActivity(activity -> home = activity);
        assertNotSame("系统重建应生成新的首页实例", previousHome, home);
        await(() -> onMain(() -> home.loaded && resumedOnMain(HomeActivity.class) == home), "重建首页应重新加载统计");
        awaitRestored(home, bottom, "Activity 重建应恢复入口位置");
        assertEquals("首页重建不能重新初始化共享 SDK", 1, initCount.get());
    }

    private void constrainViewport(Activity activity) {
        if (!(activity instanceof HomeActivity)) return;
        ScrollView scroll = activity.findViewById(R.id.home_shortcut_scroll);
        if (scroll == null) return;
        // 仅缩小真实 ScrollView 的测试视口，保证各字号均有非零滚动范围，不改设备或生产布局。
        int height = Math.round(160 * activity.getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams params = scroll.getLayoutParams();
        LinearLayout.LayoutParams linear = (LinearLayout.LayoutParams) params;
        if (params.height == height && linear.weight == 0) return;
        params.height = height;
        linear.weight = 0;
        scroll.setLayoutParams(params);
    }

    private int scrollToBottom(HomeActivity activity) {
        awaitScrollable(activity);
        int bottom = onMain(() -> {
            ScrollView scroll = shortcutScroll(activity);
            scroll.scrollTo(0, scrollRange(scroll));
            return scroll.getScrollY();
        });
        assertTrue("测试必须实际滑到非零位置", bottom > 0);
        instrumentation.waitForIdleSync();
        return bottom;
    }

    private void awaitScrollable(HomeActivity activity) {
        assertNotNull("本项回归需实际入口滚动容器", onMain(() -> shortcutScroll(activity)));
        await(() -> onMain(() -> {
            ScrollView scroll = shortcutScroll(activity);
            return scroll.isLaidOut() && scrollRange(scroll) > 0;
        }), "入口网格应完成测量并提供非零滚动范围");
    }

    private void awaitRestored(HomeActivity activity, int expected, String message) {
        awaitScrollable(activity);
        await(() -> onMain(() -> shortcutScroll(activity).getScrollY() == expected), message);
        instrumentation.waitForIdleSync();
        onMain(() -> { assertEquals(message, expected, shortcutScroll(activity).getScrollY()); return null; });
    }

    private static int scrollRange(ScrollView scroll) {
        if (scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight()
                - (scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom()));
    }

    private static ScrollView shortcutScroll(HomeActivity activity) {
        ScrollView scroll = activity.findViewById(R.id.home_shortcut_scroll);
        assertNotNull("首页应有独立入口滚动容器", scroll);
        return scroll;
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private <T extends Activity> T resumed(Class<T> type) { return onMain(() -> resumedOnMain(type)); }

    private static <T extends Activity> T resumedOnMain(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }

    private <T> T onMain(Callable<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(work.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("首页滚动验收失败", failure.get());
        return result.get();
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set
                    ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, Object> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
            else throw new AssertionError("未知设备配置类型：" + entry.getKey());
        }
        assertTrue("应恢复全部设备配置", editor.commit());
        assertEquals(snapshot, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
