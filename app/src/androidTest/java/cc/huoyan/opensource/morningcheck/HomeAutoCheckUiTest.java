package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/** 截获晨检导航并使用只读 SDK 夹具，验证冷启动和返回边界，不操作现场摄像头。 */
@RunWith(AndroidJUnit4.class)
public final class HomeAutoCheckUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> unexpectedRequests = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> scenario;
    private Instrumentation.ActivityMonitor checkStarts;

    @Before
    public void isolateSdkAndCaptureCheckNavigation() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device);
        originalBinding = snapshot(binding);
        instrumentation.runOnMainSync(() -> {
            assertFalse("不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    unexpectedRequests.add("HTTP " + request.getMethod() + " "
                            + (request.getRequestUrl() == null ? "未知路径" : request.getRequestUrl().encodedPath()));
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, Response response) {
                        sockets.add(socket);
                    }

                    @Override
                    public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            String command = request.getString("cmd");
                            // 连接会额外读取能力及类别表，首页还会查询云服务状态；仅允许这些明确的只读请求。
                            if (!"init".equals(command) && !"getStatus".equals(command)
                                    && !"getCertInfo".equals(command) && !"getDownloadProgress".equals(command)
                                    && !"getCapabilities".equals(command) && !"get_class_table".equals(command)
                                    && !"getCloudStatus".equals(command)) {
                                businessRequests.incrementAndGet();
                                unexpectedRequests.add("WS " + command);
                                throw new AssertionError("冷启动不应发送业务命令：" + command);
                            }
                            JSONObject data = new JSONObject();
                            if ("getStatus".equals(command))
                                data.put("sdk_state", "READY").put("model_ready", true)
                                        .put("certificate_valid", true).put("device_id", "HOME-AUTO-CHECK-TEST");
                            else if ("getCloudStatus".equals(command)) data.put("enabled", false);
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "冷启动测试夹具").put("data", data).toString());
                        } catch (Throwable error) {
                            serverFailure.compareAndSet(null, error);
                        }
                    }

                    @Override
                    public void onMessage(WebSocket socket, ByteString bytes) {
                        businessRequests.incrementAndGet();
                        unexpectedRequests.add("WS 二进制消息（" + bytes.size() + " 字节）");
                    }
                });
            }
        });
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).remove(SecuritySettings.AUTO_CHECK_KEY).commit());
        checkStarts = instrumentation.addMonitor(CheckActivity.class.getName(),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
    }

    @After
    public void closePagesAndRestoreConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
            instrumentation.runOnMainSync(() -> {
                finishPages();
                app.sdkSession().disconnect();
            });
            instrumentation.waitForIdleSync();
            app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } finally {
            if (checkStarts != null) instrumentation.removeMonitor(checkStarts);
            try {
                if (originalDevice != null) restore(device, originalDevice);
                if (originalBinding != null)
                    assertEquals("冷启动不能修改门店绑定", originalBinding, binding.getAll());
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("冷启动只能读取本机 SDK 状态，意外请求：" + unexpectedRequests, 0, businessRequests.get());
        assertNull("SDK 夹具不能解析失败", serverFailure.get());
    }

    @Test
    public void disabledLauncherColdStartStaysOnHome() {
        launch(launcher());
        assertEquals(0, checkStarts.getHits());
    }

    @Test
    public void enabledLauncherRoutesOnceAcrossResumeNewIntentAndRecreation() {
        assertTrue(device.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        launch(launcher());
        assertEquals("首次桌面启动应进入晨检", 1, checkStarts.getHits());
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(home -> instrumentation.callActivityOnNewIntent(home, launcher()));
        scenario.recreate();
        awaitHomeLoaded();
        assertEquals("返回、复用和重建不能重复自动跳转", 1, checkStarts.getHits());
    }

    @Test
    public void explicitPasswordExitSkipsAutoCheckEvenForLauncherIntent() {
        assertTrue(device.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        launch(launcher().putExtra(HomeActivity.EXTRA_SKIP_AUTO_CHECK, true));
        scenario.recreate();
        awaitHomeLoaded();
        assertEquals("密码退出应停留首页", 0, checkStarts.getHits());
    }

    @Test
    public void ordinaryInternalNavigationDoesNotAutoCheck() {
        assertTrue(device.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        launch(new Intent(app, HomeActivity.class));
        assertEquals("内部首页导航不能被开机开关劫持", 0, checkStarts.getHits());
    }

    private Intent launcher() {
        return new Intent(app, HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    private void launch(Intent intent) {
        scenario = ActivityScenario.launch(intent);
        awaitHomeLoaded();
    }

    private void awaitHomeLoaded() {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        do {
            scenario.onActivity(home -> ready.set(home.loaded && app.sdkSession().viewState().ready));
            if (ready.get()) break;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("首页和隔离 SDK 应加载完成", ready.get());
        instrumentation.waitForIdleSync();
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
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
            else throw new AssertionError("未知配置类型：" + entry.getKey());
        }
        assertTrue(editor.commit());
        assertEquals(original, preferences.getAll());
    }
}
