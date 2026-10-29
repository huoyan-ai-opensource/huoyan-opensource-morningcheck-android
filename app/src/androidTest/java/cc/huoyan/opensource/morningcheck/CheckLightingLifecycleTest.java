package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.content.ContextCompat;
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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.hardware.LightController;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/** 使用独立员工库、本机 SDK 和假灯验证现场采集开关灯，不访问业务云或现场硬件。 */
@RunWith(AndroidJUnit4.class)
public final class CheckLightingLifecycleTest {
    private static final String TEST_PATH = "/dev/huoyan-check-light-test";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger onWrites = new AtomicInteger(), offWrites = new AtomicInteger();
    private final AtomicInteger businessRequests = new AtomicInteger(), forbiddenCommands = new AtomicInteger();
    private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();
    private final AtomicReference<PendingIdentity> pendingIdentity = new AtomicReference<>();
    private final CopyOnWriteArrayList<FakeDevice> devices = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> lightCallbacks = new ConcurrentLinkedQueue<>();
    private MorningApplication app;
    private SharedPreferences preferences, binding;
    private Map<String, Object> originalPreferences, originalBinding;
    private AppStore originalStore, testStore;
    private String databaseName;
    private MockWebServer endpoint;
    private ActivityScenario<CheckActivity> scenario;
    private CheckActivity page;
    private ExecutorService lightWorker;
    private LightController originalController;
    private boolean controllerInjected;

    @Before public void isolateStoreSdkAndLight() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("测试不弹权限窗口；设备需已授予相机权限",
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("测试不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        databaseName = "check-lighting-test-" + UUID.randomUUID() + ".db";
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(app, databaseName);
        app.io.submit(() -> {
            try { testStore.saveStaff(new JSONObject().put("id", 42).put("staff_no", "TEST-LIGHT-42")
                    .put("name", "隔离灯光测试员工").put("enabled", true)); }
            catch (Exception error) { throw new AssertionError(error); }
        }).get(10, TimeUnit.SECONDS);
        onMain(() -> { originalStore = app.store; app.store = testStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503).setBody("隔离灯光生命周期测试");
                }
                return new MockResponse().withWebSocketUpgrade(new ReadyFixture());
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:CHECK-LIGHT-NO-DEVICE-FACE")
                .putString("hand_camera", "usb:CHECK-LIGHT-NO-DEVICE-HAND")
                .putBoolean("voice", false).putBoolean("temperature", false)
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putString("light_mode", LightConfig.SERIAL).putString("light_serial_path", TEST_PATH)
                .putString("light_baud", "115200").putString("light_gpio", "122,148")
                .putString("light_on_level", "0").putString("light_off_level", "1").commit());
        lightWorker = Executors.newSingleThreadExecutor(task -> new Thread(task, "隔离晨检灯光回归"));
        LightController fake = new LightController(lightWorker, lightCallbacks::add, config -> {
            assertEquals("只允许使用隔离假设备配置", TEST_PATH, config.path);
            FakeDevice device = new FakeDevice();
            devices.add(device);
            return device;
        });
        onMain(() -> {
            originalController = (LightController) controllerField().get(null);
            controllerField().set(null, fake);
            controllerInjected = true;
            return null;
        });
    }

    @After public void releaseAllResourcesAndRestoreConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            instrumentation.waitForIdleSync();
            flushLightWorker();
            drainLightCallbacks();
            for (FakeDevice device : devices) {
                assertTrue("页面结束后每条灯光链路都应释放", device.closed.get());
                assertFalse("页面结束后不能遗留亮灯", device.on);
            }
            if (testStore != null) assertEquals("测试不能生成晨检完成记录", 0L,
                    app.io.submit(() -> testStore.recordsPage(0, 1, 20).totalRows).get(10, TimeUnit.SECONDS).longValue());
            if (binding != null && originalBinding != null)
                assertEquals("测试不能改变 SDK 门店归属", originalBinding, binding.getAll());
        } finally {
            try {
                if (controllerInjected) onMain(() -> { controllerField().set(null, originalController); return null; });
                if (lightWorker != null) lightWorker.shutdownNow();
                if (app != null) app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
                if (app != null && originalStore != null) onMain(() -> { app.store = originalStore; return null; });
                if (testStore != null) testStore.close();
                if (databaseName != null) assertTrue("应清理独立测试数据库", app.deleteDatabase(databaseName));
                if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
                if (binding != null && originalBinding != null) restore(binding, originalBinding);
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("灯光回归不能请求业务云", 0, businessRequests.get());
        assertEquals("灯光回归不能提交学习、绑定或检测写操作", 0, forbiddenCommands.get());
        assertNull("隔离夹具不应出现协议或设备错误", fixtureFailure.get());
    }

    @Test public void activeCaptureAutomaticallyOpensLampPauseClosesAndResumeReopens() {
        launchCapture();
        int opened = onWrites.get();
        assertTrue("采集可用后应自动发出开灯命令", opened > 0);
        onMain(() -> { invokeUpdateCheck(page); invokeUpdateCheck(page); return null; });
        flushLightWorker();
        assertEquals("连续状态刷新不能重复发送开灯", opened, onWrites.get());
        int previousOff = offWrites.get();
        onMain(() -> { assertTrue(page.findViewById(R.id.check_pause).performClick()); return null; });
        await(() -> offWrites.get() > previousOff && allClosed(), "暂停采集应关闭并释放灯光");
        drainLightCallbacks();
        assertTrue(onMain(() -> (Boolean) readField(page, "paused")));
        assertEquals("暂停后的旧灯回调不能重开灯", opened, onWrites.get());
        onMain(() -> { assertTrue(page.findViewById(R.id.check_pause).performClick()); return null; });
        await(() -> onWrites.get() > opened && lampIsOn(), "继续采集应恢复灯光");
        drainLightCallbacks();
        assertFalse(onMain(() -> (Boolean) readField(page, "paused")));
    }

    @Test public void backgroundClosesLampAndDelayedIdentityOrStatusCannotReopenIt() throws Exception {
        launchCapture();
        onMain(() -> { page.onCameraFrame(new CameraSource.Frame(new byte[]{1, 2, 3}, 1, 1)); return null; });
        await(() -> pendingIdentity.get() != null, "应保留一条在途身份读取回执");
        int opened = onWrites.get(), previousOff = offWrites.get();
        scenario.moveToState(Lifecycle.State.CREATED);
        await(() -> offWrites.get() > previousOff && allClosed(), "后台停止应关闭并释放灯光");
        assertFalse(onMain(() -> page.foreground));
        assertNull(onMain(() -> readField(page, "pipeline")));
        PendingIdentity delayed = pendingIdentity.getAndSet(null);
        reply(delayed.socket, delayed.id, new JSONObject().put("matched", false));
        // 同一通道的新状态回执作为传输屏障，确保旧身份结果已交到客户端主线程。
        CountDownLatch barrier = new CountDownLatch(1);
        onMain(() -> { page.sdk.refreshStatus((code, message, data) -> barrier.countDown()); return null; });
        assertTrue("延迟回执传输屏障应完成", barrier.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        onMain(() -> { page.onStatus(readyStatus()); return null; });
        drainLightCallbacks();
        flushLightWorker();
        assertEquals("后台旧 SDK 和灯光回执均不能重新开灯", opened, onWrites.get());
        assertTrue(allClosed());
        assertNull(onMain(() -> readField(page, "lighting")));
    }

    @Test public void confirmedHomeNavigationClosesCaptureLampAndHomeDoesNotOwnIt() {
        launchCapture();
        int opened = onWrites.get(), previousOff = offWrites.get();
        onMain(() -> { assertTrue(page.findViewById(R.id.header_home).performClick()); return null; });
        confirmLeaveDialog();
        await(() -> onMain(() -> resumed(HomeActivity.class) != null), "确认离开后应显示首页");
        await(() -> offWrites.get() > previousOff && allClosed(), "返回首页应关灯并释放端口");
        drainLightCallbacks();
        flushLightWorker();
        assertEquals("首页不能延续现场开灯状态", opened, onWrites.get());
        assertNull(onMain(() -> readField(page, "lighting")));
    }

    private void launchCapture() {
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "现场页应连接本机 READY SDK 夹具");
        await(() -> onMain(() -> {
            CheckActivity current = resumed(CheckActivity.class);
            return current != null && current.loaded && readField(current, "pipeline") != null
                    && current.findViewById(R.id.check_pause) != null;
        }), "有员工且 SDK 就绪后应自动建立晨检流水线");
        onMain(() -> {
            page = resumed(CheckActivity.class);
            assertEquals(CheckPipeline.Stage.IDENTITY, ((CheckPipeline) readField(page, "pipeline")).getStage());
            // 不存在的 USB 标识不打开现场镜头；停相机并清错误模拟可采集状态。
            page.stopCamera();
            page.cameraError = "";
            invokeUpdateCheck(page);
            return null;
        });
        await(this::lampIsOn, "采集可用时应自动打开假灯");
        drainLightCallbacks();
    }

    private final class ReadyFixture extends WebSocketListener {
        private final Map<String, JSONObject> images = new ConcurrentHashMap<>();
        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd"), id = request.getString("requestId");
                if ("identify".equals(command)) { images.put(id, request); return; }
                boolean allowed = "init".equals(command) || "getStatus".equals(command)
                        || "getCapabilities".equals(command) || "get_class_table".equals(command)
                        || "getCertInfo".equals(command) || "getDownloadProgress".equals(command)
                        || "getCloudStatus".equals(command);
                if (!allowed) forbiddenCommands.incrementAndGet();
                assertTrue("仅允许初始化与只读查询：" + command, allowed);
                reply(socket, id, "getStatus".equals(command) ? readyStatus() : new JSONObject());
            } catch (Throwable error) { fixtureFailure.compareAndSet(null, error); }
        }
        @Override public void onMessage(WebSocket socket, ByteString bytes) {
            try {
                ByteBuffer buffer = bytes.asByteBuffer();
                int length = buffer.getInt();
                assertTrue("图片请求 ID 长度应有效", length > 0 && length < buffer.remaining());
                byte[] id = new byte[length];
                buffer.get(id);
                String requestId = new String(id, StandardCharsets.UTF_8);
                assertNotNull("人工帧必须对应身份读取声明", images.remove(requestId));
                assertEquals("只消费一个人工 RGB 像素", 3, buffer.remaining());
                assertTrue("只允许保留一个在途身份请求", pendingIdentity.compareAndSet(null, new PendingIdentity(socket, requestId)));
            } catch (Throwable error) { fixtureFailure.compareAndSet(null, error); }
        }
    }

    private static final class PendingIdentity {
        final WebSocket socket;
        final String id;
        PendingIdentity(WebSocket socket, String id) { this.socket = socket; this.id = id; }
    }

    private final class FakeDevice implements LightController.Device {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean on;
        @Override public void setOn(boolean value) throws IOException {
            if (closed.get()) {
                IOException failure = new IOException("关闭后的假灯不能再次写入");
                fixtureFailure.compareAndSet(null, failure);
                throw failure;
            }
            on = value;
            if (value) onWrites.incrementAndGet(); else offWrites.incrementAndGet();
        }
        @Override public void close() { closed.set(true); }
    }

    private static JSONObject readyStatus() throws Exception {
        return new JSONObject().put("sdk_state", "READY").put("model_ready", true)
                .put("certificate_valid", true).put("device_id", "CHECK-LIGHTING-TEST");
    }

    private static void reply(WebSocket socket, String id, JSONObject data) throws Exception {
        assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", 0)
                .put("reason", "隔离晨检灯光回归").put("data", data).toString()));
    }

    private boolean lampIsOn() {
        for (FakeDevice device : devices) if (device.on && !device.closed.get()) return true;
        return false;
    }
    private boolean allClosed() {
        for (FakeDevice device : devices) if (device.on || !device.closed.get()) return false;
        return true;
    }
    private void flushLightWorker() {
        try { if (lightWorker != null) lightWorker.submit(() -> null).get(10, TimeUnit.SECONDS); }
        catch (Exception error) { throw new AssertionError("灯光 IO 队列应完成当前指令", error); }
    }
    private void drainLightCallbacks() {
        flushLightWorker();
        onMain(() -> { Runnable callback; while ((callback = lightCallbacks.poll()) != null) callback.run(); return null; });
    }

    private void confirmLeaveDialog() {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            await(() -> {
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root == null) return false;
                List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText("离开现场晨检？");
                List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                try { return !titles.isEmpty() && buttons.size() == 1 && buttons.get(0).isEnabled()
                        && "离开".contentEquals(buttons.get(0).getText())
                        && buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                finally {
                    for (AccessibilityNodeInfo node : titles) node.recycle();
                    for (AccessibilityNodeInfo node : buttons) node.recycle();
                    root.recycle();
                }
            }, "应通过实际确认窗的离开按钮返回首页");
        } finally {
            info.flags = originalFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
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
    private <T> T onMain(Callable<T> operation) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(operation.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("现场灯光主线程验收失败", failure.get());
        return value.get();
    }
    private static Field controllerField() throws Exception {
        Field field = LightController.class.getDeclaredField("instance");
        field.setAccessible(true);
        return field;
    }
    private static Object readField(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void invokeUpdateCheck(CheckActivity activity) {
        try { Method method = CheckActivity.class.getDeclaredMethod("updateCheck"); method.setAccessible(true); method.invoke(activity); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
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
        assertTrue("应恢复完整测试前配置", editor.commit());
        assertEquals(original, preferences.getAll());
    }
    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(40); }
        while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
