package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.hardware.TemperatureSource;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/** 身份读取使用独立测试库、本机 SDK 夹具和人工帧，不打开相机或测温硬件。 */
@RunWith(AndroidJUnit4.class)
public final class CheckIdentityReadTest {
    private static final long PERSON_ID = 42;
    private static final long SECOND_PERSON_ID = 43;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger identityRequests = new AtomicInteger(), binaryReplies = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> storageFixtureFailure = new AtomicReference<>();
    private final AtomicReference<Long> fixturePerson = new AtomicReference<>(PERSON_ID);
    private final AtomicReference<Boolean> fixtureMatched = new AtomicReference<>(true);
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private AppStore originalStore, testStore;
    private String databaseName;
    private MockWebServer endpoint;
    private ActivityScenario<CheckActivity> scenario;
    private CheckPipeline pipeline;
    private CountDownLatch releaseIo;

    @Before public void prepareIsolatedIdentityFlow() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("测试不弹权限窗口；设备需已授予相机权限",
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        onMain(() -> {
            assertFalse("测试不能中断正在执行的门店登录", app.shopLogin().getState().isRunning());
            app.sdkSession().disconnect();
            return null;
        });
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        databaseName = "identity-read-test-" + UUID.randomUUID() + ".db";
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(app, databaseName);
        app.io.submit(() -> {
            try {
                testStore.saveStaff(new JSONObject().put("id", 7).put("staff_no", "TEST-7").put("name", "无关测试员工").put("enabled", false));
                testStore.saveStaff(new JSONObject().put("id", PERSON_ID).put("name", "按编号读取的员工")
                        .put("staff_no", "TEST-" + PERSON_ID).put("department", "测试夹具").put("enabled", true));
                testStore.saveStaff(new JSONObject().put("id", SECOND_PERSON_ID).put("name", "换入画面的员工")
                        .put("staff_no", "TEST-" + SECOND_PERSON_ID).put("department", "测试夹具").put("enabled", true));
            } catch (Exception error) { throw new AssertionError(error); }
        }).get(10, TimeUnit.SECONDS);
        onMain(() -> { originalStore = app.store; app.store = testStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                assertEquals("所有请求必须只进入本机 SDK 夹具", "/sdk", request.getPath());
                return new MockResponse().withWebSocketUpgrade(new IdentityFixture());
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:IDENTITY-READ-NO-DEVICE-FACE")
                .putString("hand_camera", "usb:IDENTITY-READ-NO-DEVICE-HAND")
                .putBoolean("voice", false).putBoolean("temperature", true)
                .putString("temperature_mode", "串口被动上报")
                .putString("serial_path", "/dev/IDENTITY-READ-NO-DEVICE")
                .putString("cloud_url", "").putString("cloud_token", "").commit());
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "应连接本机隔离 SDK");
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> ready.set(activity.loaded && readField(activity, "pipeline") != null
                    && activity.findViewById(R.id.check_pause) != null));
            return ready.get();
        }, "现场页应完成员工数量读取并自动启动");
        scenario.onActivity(activity -> {
            // 单次身份采样即可观察推进，保留真实 Activity 的请求与读库路径。
            pipeline = new CheckPipeline(new CheckPipeline.Rules(1, 4, 5, true, true, true, 37.3));
            writeField(activity, "pipeline", pipeline);
            activity.render();
            activity.stopCamera();
            activity.cameraError = "";
            assertNull("只消费人工测试帧，不打开任何镜头", activity.camera);
        });
    }

    @After public void restoreOriginalStoreAndDeviceConfiguration() throws Exception {
        if (releaseIo != null) releaseIo.countDown();
        try {
            if (scenario != null) scenario.close();
            if (app != null && originalStore != null) {
                onMain(() -> { app.sdkSession().disconnect(); return null; });
                app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
                instrumentation.waitForIdleSync();
                assertEquals("测试不得生成晨检完成记录", 0L,
                        app.io.submit(() -> testStore.recordsPage(0, 1, 20).totalRows).get(10, TimeUnit.SECONDS).longValue());
            }
        } finally {
            if (app != null && originalStore != null) onMain(() -> { app.store = originalStore; return null; });
            if (testStore != null) testStore.close();
            if (databaseName != null) assertTrue("应清理本用例的独立数据库", app.deleteDatabase(databaseName));
            if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
            for (WebSocket socket : sockets) socket.close(1001, "测试结束");
            if (endpoint != null) endpoint.shutdown();
        }
        assertNull("本机 SDK 夹具不应出现协议错误", serverFailure.get());
        assertNull("存储队列夹具应在限定时间内释放", storageFixtureFailure.get());
    }

    @Test public void blockedStaffReadKeepsOneIdentityRequestAndThenAdvancesById() throws Exception {
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff();
        feedFrame();
        instrumentation.waitForIdleSync();
        assertEquals("档案读取期间不能重叠提交第二帧 identify", 1, identityRequests.get());
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        releaseIo.countDown();
        await(() -> onMain(pipeline::isIdentityConfirmed), "档案读取完成后应确认员工并在同一步等待体温");
        scenario.onActivity(activity -> {
            JSONObject person = (JSONObject) readField(activity, "activeStaff");
            assertEquals("必须按SDK返回ID选中档案", PERSON_ID, person.optLong("id"));
            assertEquals("按编号读取的员工", person.optString("name"));
            assertFalse((Boolean) readField(activity, "frameBusy"));
            assertNotNull("身份帧应成为当前员工证据", readField(activity, "facePhoto"));
            assertNull(activity.camera);
        });
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        assertTrue(onMain(() -> Double.isNaN(pipeline.getTemperature())));
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    @Test public void realtimeTemperatureWaitsForPendingIdentityAndThenAdvancesTogether() throws Exception {
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff();
        feedTemperature(36.6);
        scenario.onActivity(activity -> {
            assertTrue((Boolean) readField(activity, "frameBusy"));
            assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertEquals(0, pipeline.getPersonId());
            assertNull(readField(activity, "activeStaff"));
            assertTrue("等待身份读库时仍实时显示设备温度", temperatureText(activity).contains("36.6"));
        });
        releaseIo.countDown();
        await(() -> onMain(() -> pipeline.getStage() == CheckPipeline.Stage.HEAD), "同一员工档案与真实读数齐备后才推进头检");
        scenario.onActivity(activity -> {
            assertEquals(PERSON_ID, pipeline.getPersonId());
            assertEquals(36.6, pipeline.getTemperature(), 0.001);
            assertEquals(PERSON_ID, ((JSONObject) readField(activity, "activeStaff")).optLong("id"));
            assertNotNull(readField(activity, "identityPhoto"));
            assertFalse((Boolean) readField(activity, "frameBusy"));
        });
    }

    @Test public void temperatureDuringNextEmployeesStaffReadCannotAdvancePreviousEmployee() throws Exception {
        feedFrame();
        await(() -> onMain(pipeline::isIdentityConfirmed), "先确认员工 A，等待设备温度");
        assertEquals(PERSON_ID, onMain(pipeline::getPersonId).longValue());
        fixturePerson.set(SECOND_PERSON_ID);
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff(2);
        feedTemperature(38.1);
        scenario.onActivity(activity -> {
            assertTrue((Boolean) readField(activity, "frameBusy"));
            assertEquals("员工 B 的档案尚未回来，体温不能把 A 推到头检", CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertEquals(PERSON_ID, pipeline.getPersonId());
            assertTrue(temperatureText(activity).contains("38.1"));
        });
        releaseIo.countDown();
        await(() -> onMain(() -> pipeline.getPersonId() == SECOND_PERSON_ID), "应按 B 的回执重建当前人员证据");
        scenario.onActivity(activity -> {
            assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertTrue("换员工后必须重新获取该员工体温", Double.isNaN(pipeline.getTemperature()));
            assertTrue("旧员工等待中的异常体温不能混入新结论", pipeline.getFindings().isEmpty());
            assertFalse(temperatureText(activity).contains("38.1"));
            assertEquals(SECOND_PERSON_ID, ((JSONObject) readField(activity, "activeStaff")).optLong("id"));
        });
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(SECOND_PERSON_ID, onMain(pipeline::getPersonId).longValue());
        assertTrue(onMain(() -> pipeline.getFindings().isEmpty()));
    }

    @Test public void unmatchedIdentityClearsEarlyTemperatureBeforeNextEmployee() throws Exception {
        scenario.onActivity(activity -> {
            pipeline = new CheckPipeline(new CheckPipeline.Rules(4, 4, 5, true, true, true, 37.3));
            writeField(activity, "pipeline", pipeline);
            activity.render();
            activity.stopCamera();
            activity.cameraError = "";
        });
        feedFrame();
        await(() -> onMain(() -> pipeline.getProgress() == 1), "第一帧应确认候选员工");
        feedTemperature(38.1);
        scenario.onActivity(activity -> assertTrue(temperatureText(activity).contains("38.1")));
        fixtureMatched.set(false);
        feedFrame();
        await(() -> onMain(() -> pipeline.getCandidateId() == 0), "失配必须清除原候选");
        scenario.onActivity(activity -> {
            assertTrue(Double.isNaN(pipeline.getTemperature()));
            assertFalse(temperatureText(activity).contains("38.1"));
            assertNull(readField(activity, "activeStaff"));
            assertNull(readField(activity, "identityPhoto"));
        });
        fixturePerson.set(SECOND_PERSON_ID);
        fixtureMatched.set(true);
        for (int sample = 1; sample <= 4; sample++) {
            final int count = sample;
            feedFrame();
            await(() -> onMain(() -> pipeline.getProgress() == count), "新员工身份采样应从零开始");
        }
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        assertTrue(onMain(() -> Double.isNaN(pipeline.getTemperature())));
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertTrue(onMain(() -> pipeline.getFindings().isEmpty()));
    }

    @Test public void cameraFailureRejectsLateTemperatureAndNewFrameRequiresIdentityAgain() throws Exception {
        feedFrame();
        await(() -> onMain(pipeline::isIdentityConfirmed), "相机故障前先确认当前员工");
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff(2);
        scenario.onActivity(activity -> {
            TemperatureSource.Listener listener = temperatureListener(activity);
            activity.cameraError = "测试相机已断开";
            activity.onCameraError();
            assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertEquals(0, pipeline.getPersonId());
            assertEquals(0, pipeline.getProgress());
            assertNull(readField(activity, "activeStaff"));
            assertNull(readField(activity, "identityPhoto"));
            listener.onTemperature(38.1);
            assertTrue("相机失效后的迟到温度不能推进旧员工", Double.isNaN(pipeline.getTemperature()));
            assertFalse(temperatureText(activity).contains("38.1"));
        });
        releaseIo.countDown();
        drainStorageCallbacks();
        scenario.onActivity(activity -> {
            assertEquals("相机故障前的迟到身份读库不能恢复旧人员", 0, pipeline.getPersonId());
            assertEquals(0, pipeline.getProgress());
            assertNull(readField(activity, "activeStaff"));
            assertNull(readField(activity, "identityPhoto"));
            assertFalse((Boolean) readField(activity, "frameBusy"));
        });
        feedFrame();
        await(() -> onMain(pipeline::isIdentityConfirmed), "收到有效新帧后重新核验当前员工");
        assertEquals(3, identityRequests.get());
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertTrue(onMain(() -> pipeline.getFindings().isEmpty()));
    }

    @Test public void earlyTemperatureOlderThanFiveSecondsCannotAdvanceNewIdentity() throws Exception {
        feedTemperature(38.1);
        scenario.onActivity(activity -> {
            assertTrue(temperatureText(activity).contains("38.1"));
            writeField(activity, "temperatureReceivedAt", SystemClock.elapsedRealtime() - 10_000);
        });
        feedFrame();
        await(() -> onMain(pipeline::isIdentityConfirmed), "新的身份帧仍应核对员工");
        scenario.onActivity(activity -> {
            assertEquals("提前读数已经过期，必须继续等待实际测温", CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertTrue(Double.isNaN(pipeline.getTemperature()));
            assertTrue(pipeline.getFindings().isEmpty());
        });
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(36.6, onMain(pipeline::getTemperature), 0.001);
        assertTrue(onMain(() -> pipeline.getFindings().isEmpty()));
    }

    @Test public void pauseRejectsStaffReadThatArrivesAfterIdentityReply() throws Exception {
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff();
        feedTemperature(38.1);
        AtomicReference<TemperatureSource.Listener> oldListener = new AtomicReference<>();
        scenario.onActivity(activity -> oldListener.set(temperatureListener(activity)));
        scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.check_pause).performClick()));
        scenario.onActivity(activity -> oldListener.get().onTemperature(39.1));
        releaseIo.countDown();
        drainStorageCallbacks();
        scenario.onActivity(activity -> {
            assertTrue((Boolean) readField(activity, "paused"));
            assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
            assertNull("暂停后的迟到读盘不能设置当前员工", readField(activity, "activeStaff"));
            assertNull("暂停后的迟到读盘不能留下身份照片", readField(activity, "facePhoto"));
            assertFalse((Boolean) readField(activity, "frameBusy"));
            assertTrue("暂停清除当前温度且拒绝迟到读数", Double.isNaN(pipeline.getTemperature()));
            assertFalse(temperatureText(activity).contains("38.1"));
            assertFalse(temperatureText(activity).contains("39.1"));
        });
    }

    @Test public void leavingPageRejectsStaffReadThatArrivesAfterIdentityReply() throws Exception {
        blockStorageQueue();
        feedFrame();
        awaitReplyWaitingForStaff();
        feedTemperature(38.1);
        AtomicReference<TemperatureSource.Listener> oldListener = new AtomicReference<>();
        AtomicReference<CheckActivity> target = new AtomicReference<>();
        scenario.onActivity(activity -> {
            target.set(activity);
            oldListener.set(temperatureListener(activity));
        });
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> lifecycleFailure = new AtomicReference<>();
        ActivityLifecycleCallback callback = (activity, stage) -> {
            if (activity != target.get() || stage != Stage.STOPPED) return;
            try { oldListener.get().onTemperature(39.1); }
            catch (Throwable error) { lifecycleFailure.set(error); }
            finally {
                // onStop 已真实执行，立即放行迟到读库；不让同步状态切换与 IO 夹具互相等待。
                releaseIo.countDown();
                stopped.countDown();
            }
        };
        onMain(() -> { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback); return null; });
        try {
            scenario.moveToState(Lifecycle.State.CREATED);
            assertTrue("应观察到现场页真正停止", stopped.await(5, TimeUnit.SECONDS));
            assertNull("停止后的迟到温度回调不应抛异常", lifecycleFailure.get());
        } finally {
            releaseIo.countDown();
            onMain(() -> { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback); return null; });
        }
        drainStorageCallbacks();
        scenario.onActivity(activity -> {
            assertFalse(activity.foreground);
            assertNull("离开页面必须丢弃半段流水线", readField(activity, "pipeline"));
            assertNull("离开后的迟到读盘不能选中员工", readField(activity, "activeStaff"));
            assertNull(readField(activity, "facePhoto"));
            assertEquals("旧流水线不能继续推进", CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
            assertFalse((Boolean) readField(activity, "frameBusy"));
        });
    }

    private void blockStorageQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        releaseIo = new CountDownLatch(1);
        app.io.execute(() -> {
            entered.countDown();
            try {
                if (!releaseIo.await(10, TimeUnit.SECONDS))
                    storageFixtureFailure.compareAndSet(null, new AssertionError("存储队列测试等待超时"));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                storageFixtureFailure.compareAndSet(null, error);
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    private void feedFrame() {
        scenario.onActivity(activity -> activity.onCameraFrame(new CameraSource.Frame(new byte[]{1, 2, 3}, 1, 1)));
    }

    private void awaitReplyWaitingForStaff() {
        awaitReplyWaitingForStaff(1);
    }

    private void awaitReplyWaitingForStaff(int replies) {
        await(() -> binaryReplies.get() == replies, "本机夹具应返回当前帧身份回执");
        await(() -> {
            AtomicReference<Boolean> pendingRead = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                pendingRead.set((Boolean) readField(activity, "frameBusy")
                        && pipeline.getStage() == CheckPipeline.Stage.IDENTITY);
            });
            return pendingRead.get();
        }, "已返回SDK回执，当前帧应仍等待员工档案");
        // refreshStatus 的独立回执作为传输屏障；同一 WS 上更早的 identify 回调已交到主线程。
        CountDownLatch barrier = new CountDownLatch(1);
        scenario.onActivity(activity -> activity.sdk.refreshStatus((code, message, data) -> barrier.countDown()));
        try { assertTrue("身份回执传输屏障超时", barrier.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        instrumentation.waitForIdleSync();
    }

    private void drainStorageCallbacks() throws Exception {
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        // 后台夹具只记录错误，由测试线程报告，避免断言杀死整个 instrumentation 进程。
        assertNull("存储队列夹具应在限定时间内释放", storageFixtureFailure.get());
    }

    private final class IdentityFixture extends WebSocketListener {
        private final Map<String, JSONObject> images = new ConcurrentHashMap<>();

        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }

        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd");
                if ("identify".equals(command)) {
                    identityRequests.incrementAndGet();
                    images.put(request.getString("requestId"), request);
                    return;
                }
                assertTrue("不允许发送学习、绑定、检测或云端写入", "init".equals(command)
                        || "getStatus".equals(command) || "getCapabilities".equals(command)
                        || "get_class_table".equals(command) || "getCertInfo".equals(command)
                        || "getDownloadProgress".equals(command) || "getCloudStatus".equals(command));
                JSONObject data = new JSONObject();
                if ("getStatus".equals(command)) data.put("sdk_state", "READY").put("model_ready", true)
                        .put("certificate_valid", true).put("device_id", "IDENTITY-READ-TEST");
                reply(socket, request.getString("requestId"), data);
            } catch (Throwable error) { serverFailure.set(error); }
        }

        @Override public void onMessage(WebSocket socket, ByteString bytes) {
            try {
                ByteBuffer buffer = bytes.asByteBuffer();
                int length = buffer.getInt();
                assertTrue("图片请求ID长度有效", length > 0 && length < buffer.remaining());
                byte[] id = new byte[length];
                buffer.get(id);
                String requestId = new String(id, StandardCharsets.UTF_8);
                assertNotNull("图片必须对应identify声明", images.remove(requestId));
                assertEquals("人工测试帧只有一个RGB像素", 3, buffer.remaining());
                reply(socket, requestId, new JSONObject().put("matched", fixtureMatched.get())
                        .put("person_id", fixturePerson.get()));
                binaryReplies.incrementAndGet();
            } catch (Throwable error) { serverFailure.set(error); }
        }

        private void reply(WebSocket socket, String id, JSONObject data) throws Exception {
            assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", 0)
                    .put("reason", "本机身份读取测试").put("data", data).toString()));
        }
    }

    private <T> T onMain(Callable<T> operation) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(operation.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程身份读取验收失败", failure.get());
        return value.get();
    }

    private static Object readField(Object target, String name) {
        try { return field(target, name).get(target); }
        catch (IllegalAccessException error) { throw new AssertionError(error); }
    }

    private void feedTemperature(double value) {
        scenario.onActivity(activity -> temperatureListener(activity).onTemperature(value));
    }

    private static TemperatureSource.Listener temperatureListener(CheckActivity activity) {
        return (TemperatureSource.Listener) readField(readField(activity, "thermometer"), "listener");
    }

    private static String temperatureText(CheckActivity activity) {
        return ((TextView) activity.findViewById(R.id.check_temperature)).getText().toString();
    }

    private static void writeField(Object target, String name, Object value) {
        try { field(target, name).set(target, value); }
        catch (IllegalAccessException error) { throw new AssertionError(error); }
    }

    private static Field field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new AssertionError("缺少测试状态：" + name);
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
            else throw new AssertionError("不支持的设备配置类型：" + value);
        }
        assertTrue("应恢复所有设备配置", editor.commit());
        assertEquals(original, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
