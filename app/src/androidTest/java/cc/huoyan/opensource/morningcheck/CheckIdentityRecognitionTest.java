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
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
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
public final class CheckIdentityRecognitionTest {
    private static final long STAFF_ID = 42;
    private static final long SECOND_STAFF_ID = 43;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger identityRequests = new AtomicInteger(), faceRequests = new AtomicInteger(),
            binaryReplies = new AtomicInteger(), headReplies = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicReference<Long> fixturePerson = new AtomicReference<>(0L);
    private long sdkPersonId, secondSdkPersonId, disabledSdkPersonId;
    private final AtomicReference<Boolean> fixtureMatched = new AtomicReference<>(true);
    private final AtomicInteger fixtureFaceCount = new AtomicInteger(1), fixtureFaceCode = new AtomicInteger();
    private final AtomicReference<Boolean> fixtureMissingFaces = new AtomicReference<>(false);
    private volatile boolean fixtureHat = true, fixtureMask = true;
    private final AtomicReference<String> holdCommand = new AtomicReference<>();
    private final AtomicReference<PendingReply> pendingReply = new AtomicReference<>();
    private volatile byte[] faceInput, headInput;
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private AppStore originalStore, testStore;
    private String databaseName;
    private MockWebServer endpoint;
    private ActivityScenario<CheckActivity> scenario;
    private CheckActivity checkActivity;
    private CheckPipeline pipeline;

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
        databaseName = "identity-recognition-test-" + UUID.randomUUID() + ".db";
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(app, databaseName);
        app.io.submit(() -> {
            try {
                testStore.saveStaff(new JSONObject().put("id", 7).put("staff_no", "TEST-7").put("name", "本地首名员工").put("enabled", true));
                testStore.saveStaff(new JSONObject().put("id", 8).put("staff_no", "TEST-8").put("name", "禁用员工").put("enabled", false));
                testStore.saveStaff(new JSONObject().put("id", STAFF_ID).put("name", "按编号读取的员工")
                        .put("staff_no", "TEST-" + STAFF_ID).put("department", "测试夹具").put("enabled", true));
                testStore.saveStaff(new JSONObject().put("id", SECOND_STAFF_ID).put("name", "换入画面的员工")
                        .put("staff_no", "TEST-" + SECOND_STAFF_ID).put("department", "测试夹具").put("enabled", true));
                sdkPersonId = testStore.staff(STAFF_ID).getLong("person_id");
                secondSdkPersonId = testStore.staff(SECOND_STAFF_ID).getLong("person_id");
                disabledSdkPersonId = testStore.staff(8).getLong("person_id");
                assertTrue("SDK标识由存储层生成正数", sdkPersonId > 0 && secondSdkPersonId > 0 && disabledSdkPersonId > 0);
                assertNotEquals("身份测试必须区分SDK标识与本地主键", STAFF_ID, sdkPersonId);
                assertNotEquals(SECOND_STAFF_ID, secondSdkPersonId);
                assertNotEquals(8L, disabledSdkPersonId);
                fixturePerson.set(sdkPersonId);
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
                .putBoolean("voice", false).putBoolean("temperature", true).putBoolean("head", true)
                .putBoolean("hat", true).putBoolean("mask", true)
                .putString("temperature_mode", "串口被动上报")
                .putString("serial_path", "/dev/IDENTITY-READ-NO-DEVICE")
                .putString("cloud_url", "").putString("cloud_token", "").commit());
        // 历史旁路参数已撤回，即使调用者仍传入 true 也必须执行真实身份识别。
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class)
                .putExtra("skip_identity_for_flow_test", true));
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "应连接本机隔离 SDK");
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> ready.set(activity.loaded && readField(activity, "pipeline") != null
                    && activity.findViewById(R.id.check_pause) != null));
            return ready.get();
        }, "现场页应完成员工数量读取并自动启动");
        scenario.onActivity(activity -> {
            checkActivity = activity;
            pipeline = (CheckPipeline) readField(activity, "pipeline");
            assertEquals("保留生产流程四次连续身份采样", 4, pipeline.getRequiredSamples());
            activity.stopCamera();
            activity.cameraError = "";
            assertNull("只消费人工测试帧，不打开任何镜头", activity.camera);
        });
    }

    @After public void restoreOriginalStoreAndDeviceConfiguration() throws Exception {
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
    }

    @Test public void actualIdentityRequiresFourSamplesAndSelectsSdkPerson() throws Exception {
        CameraSource.Frame last = collectIdentity();
        assertEquals(4, identityRequests.get());
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        assertEquals(STAFF_ID, onMain(pipeline::getPersonId).longValue());
        assertTrue(onMain(() -> Double.isNaN(pipeline.getTemperature())));
        scenario.onActivity(activity -> {
            JSONObject person = (JSONObject) readField(activity, "activeStaff");
            assertEquals("SDK编号须反查回业务本地主键，不能任取首员工", STAFF_ID, person.optLong("id"));
            assertEquals("识别回执的SDK编号应与档案独立标识一致", sdkPersonId, person.optLong("person_id"));
            assertNotEquals(person.optLong("id"), person.optLong("person_id"));
            assertEquals("按编号读取的员工", person.optString("name"));
            assertSame(last, readField(activity, "facePhoto"));
            assertEquals("姓名独立显示，不与部门挤在一行", "按编号读取的员工",
                    ((TextView) activity.findViewById(R.id.check_person)).getText().toString());
            assertTrue(((TextView) activity.findViewById(R.id.check_person_detail)).getText().toString().contains("工号 TEST-"));
        });
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(36.6, onMain(pipeline::getTemperature), .001);
    }

    @Test public void unknownSdkPersonCannotUseFirstLocalEmployee() throws Exception {
        fixturePerson.set(999L);
        for (int sample = 0; sample < 4; sample++) feedFrame();
        assertUnconfirmed();
        assertEquals(4L, app.io.submit(testStore::staffCount).get(10, TimeUnit.SECONDS).longValue());
    }

    @Test public void unassignedSdkPersonMatchingExistingLocalKeyCannotFallbackToThatEmployee() throws Exception {
        assertNotNull("碰撞数字必须确实是一个已有本地主键",
                app.io.submit(() -> testStore.staff(STAFF_ID)).get(10, TimeUnit.SECONDS));
        assertNull("该数字尚未分配为任何员工的SDK标识",
                app.io.submit(() -> testStore.staffByPersonId(STAFF_ID)).get(10, TimeUnit.SECONDS));
        fixturePerson.set(STAFF_ID);
        for (int sample = 0; sample < 4; sample++) feedFrame();
        assertUnconfirmed();
        assertEquals("未知SDK标识不能因碰巧存在同号本地主键就放行", 4L,
                app.io.submit(testStore::staffCount).get(10, TimeUnit.SECONDS).longValue());
    }

    @Test public void assignedSdkPersonMatchingAnotherLocalKeyResolvesItsOwnEmployee() throws Exception {
        app.io.submit(() -> {
            try {
                // 只通过真实保存接口新增数字碰撞的业务员工，不注入任何person_id映射。
                testStore.saveStaff(new JSONObject().put("id", sdkPersonId).put("staff_no", "COLLISION-LOCAL")
                        .put("name", "与SDK编号同号的另一名员工").put("enabled", true));
                assertNotEquals(sdkPersonId, testStore.staff(sdkPersonId).getLong("person_id"));
            } catch (Exception error) { throw new AssertionError(error); }
        }).get(10, TimeUnit.SECONDS);
        fixturePerson.set(sdkPersonId);
        collectIdentity();
        assertEquals("应按SDK标识反查原员工，不能直接读取同号另一名员工", STAFF_ID,
                onMain(pipeline::getPersonId).longValue());
        scenario.onActivity(activity -> {
            JSONObject person = (JSONObject) readField(activity, "activeStaff");
            assertEquals(STAFF_ID, person.optLong("id"));
            assertEquals(sdkPersonId, person.optLong("person_id"));
            assertEquals("按编号读取的员工", person.optString("name"));
        });
    }

    @Test public void disabledSdkEmployeeCannotConfirmIdentity() {
        fixturePerson.set(disabledSdkPersonId);
        for (int sample = 0; sample < 4; sample++) feedFrame();
        assertUnconfirmed();
    }

    @Test public void softDeletedSdkEmployeeCannotConfirmIdentityOrBorrowAnotherLocalEmployee() throws Exception {
        app.io.submit(() -> testStore.removeStaff(STAFF_ID)).get(10, TimeUnit.SECONDS);
        assertNull("软删除的本地主键不能再查到在职员工", app.io.submit(() -> testStore.staff(STAFF_ID)).get(10, TimeUnit.SECONDS));
        assertNull("已删除SDK标识也必须从识别反查中排除",
                app.io.submit(() -> testStore.staffByPersonId(sdkPersonId)).get(10, TimeUnit.SECONDS));
        fixturePerson.set(sdkPersonId);
        for (int sample = 0; sample < 4; sample++) feedFrame();
        assertUnconfirmed();
        assertEquals("被识别到的已删除员工不能重新入库", 3L,
                app.io.submit(testStore::staffCount).get(10, TimeUnit.SECONDS).longValue());
    }

    @Test public void idleTemperatureCannotBecomeNextEmployeesMeasurement() {
        feedTemperature(36.6);
        assertFalse(onMain(pipeline::isIdentityConfirmed));
        assertTrue(onMain(() -> Double.isNaN(pipeline.getTemperature())));
        collectIdentity();
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        assertTrue("无人时的设备读数不能用于后来进入的员工", onMain(() -> Double.isNaN(pipeline.getTemperature())));
        feedTemperature(36.7);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(36.7, onMain(pipeline::getTemperature), .001);
    }

    @Test public void noFaceKeepsThermometerStoppedAndIgnoresUnsolicitedReadings() {
        fixtureMatched.set(false);
        feedTemperature(36.6);
        AtomicReference<Object> thermometer = new AtomicReference<>();
        AtomicReference<Object> originalSession = new AtomicReference<>();
        scenario.onActivity(activity -> {
            thermometer.set(readField(activity, "thermometer"));
            originalSession.set(readField(thermometer.get(), "active"));
        });
        for (int i = 0; i < 5; i++) {
            feedFrame();
            scenario.onActivity(activity -> {
                assertTrue("无人时不显示设备主动上报的读数", temperatureText(activity).contains("--"));
                assertFalse((Boolean) readField(activity, "temperatureRequested"));
                assertSame("空识别回执不能反复重开串口", originalSession.get(), readField(thermometer.get(), "active"));
                assertTrue(Double.isNaN(pipeline.getTemperature()));
                assertNull(readField(activity, "activeStaff"));
            });
        }
        fixtureMatched.set(true);
        collectIdentity();
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        feedTemperature(36.7);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(36.7, onMain(pipeline::getTemperature), .001);
    }

    @Test public void facePresenceStartsMeasurementAndAbsenceRejectsQueuedTemperature() {
        scenario.onActivity(activity -> {
            assertFalse("进入页面不能直接启动测温", (Boolean) readField(activity, "temperatureRequested"));
            observeFace(activity, new CameraSource.Frame(new byte[12], 2, 2), true);
            assertTrue("检测到人脸后才启动测温，无需先匹配到员工", (Boolean) readField(activity, "temperatureRequested"));
            temperatureListener(activity).onTemperature(36.6);
            assertEquals(36.6, (Double) readField(activity, "liveTemperature"), .001);
            observeFace(activity, new CameraSource.Frame(new byte[12], 2, 2), false);
            assertFalse("离开画面停止测温", (Boolean) readField(activity, "temperatureRequested"));
            assertTrue(Double.isNaN((Double) readField(activity, "liveTemperature")));
            temperatureListener(activity).onTemperature(37.0);
            assertTrue("已排队的无脸读数也不能回填", Double.isNaN((Double) readField(activity, "liveTemperature")));
            observeFace(activity, new CameraSource.Frame(new byte[12], 2, 2), true);
            assertTrue("重新入镜创建新测温会话", (Boolean) readField(activity, "temperatureRequested"));
            assertTrue("新会话不沿用旧温度", Double.isNaN((Double) readField(activity, "liveTemperature")));
        });
    }

    @Test public void faceEvidenceExpiryStopsMeasurementWithoutAnotherCameraFrame() {
        scenario.onActivity(activity -> observeFace(activity, new CameraSource.Frame(new byte[12], 2, 2), true));
        await(() -> onMain(() -> !(Boolean) readField(checkActivity, "temperatureRequested")),
                "没有新的人脸帧时必须自动停止测温");
        feedTemperature(36.9);
        assertTrue(onMain(() -> Double.isNaN((Double) readField(checkActivity, "liveTemperature"))));
    }

    private static void observeFace(CheckActivity activity, CameraSource.Frame frame, boolean present) {
        try {
            java.lang.reflect.Method method = CheckActivity.class.getDeclaredMethod("acceptFacePresence",
                    CameraSource.Frame.class, CheckPipeline.Stage.class, boolean.class);
            method.setAccessible(true);
            method.invoke(activity, frame, CheckPipeline.Stage.IDENTITY, present);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test public void previousEmployeeMustLeaveBeforeNewSamples() {
        scenario.onActivity(activity -> {
            writeField(activity, "lastPerson", STAFF_ID);
            writeField(activity, "waitingDeparture", true);
        });
        feedFrame();
        assertUnconfirmed();
        scenario.onActivity(activity -> assertTrue("等待离场按本地主键判断，不能拿SDK编号与lastPerson直接比较",
                (Boolean) readField(activity, "waitingDeparture")));
        fixtureMatched.set(false);
        feedFrame();
        scenario.onActivity(activity -> assertFalse((Boolean) readField(activity, "waitingDeparture")));
        fixtureMatched.set(true);
        collectIdentity();
        assertEquals(STAFF_ID, onMain(pipeline::getPersonId).longValue());
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
    }

    @Test public void differentSdkPersonDuringDepartureWaitStartsNewEmployeesLocalSamples() {
        scenario.onActivity(activity -> {
            writeField(activity, "lastPerson", STAFF_ID);
            writeField(activity, "waitingDeparture", true);
        });
        fixturePerson.set(secondSdkPersonId);
        feedFrame();
        scenario.onActivity(activity -> {
            assertFalse("已换另一名员工后无需等待前人再次出现", (Boolean) readField(activity, "waitingDeparture"));
            assertEquals("下一人的候选采样仍使用本地主键", SECOND_STAFF_ID, pipeline.getCandidateId());
            assertEquals(1, pipeline.getProgress());
            assertFalse(pipeline.isIdentityConfirmed());
            assertEquals("前一人的lastPerson仍是本地主键", STAFF_ID, ((Number) readField(activity, "lastPerson")).longValue());
        });
        for (int sample = 0; sample < 3; sample++) feedFrame();
        assertEquals(SECOND_STAFF_ID, onMain(pipeline::getPersonId).longValue());
        assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
        scenario.onActivity(activity -> {
            JSONObject person = (JSONObject) readField(activity, "activeStaff");
            assertEquals(SECOND_STAFF_ID, person.optLong("id"));
            assertEquals(secondSdkPersonId, person.optLong("person_id"));
        });
        feedTemperature(36.8);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        assertEquals(36.8, onMain(pipeline::getTemperature), .001);
    }

    @Test public void maskedEmployeeUsesFacePresenceWithoutRepeatingIdentityRecognition() {
        enterHead();
        // 口罩可能使身份匹配失败；头检只接受已锁定身份后的同帧在场结果。
        fixtureMatched.set(false);
        fixturePerson.set(secondSdkPersonId);
        for (int sample = 1; sample <= 4; sample++) {
            feedFrame();
            assertEquals(sample, headReplies.get());
            assertEquals(sample, faceRequests.get());
        }
        assertEquals(CheckPipeline.Stage.HAND_BACK, onMain(pipeline::getStage));
        assertEquals("只有身份阶段的四帧才调用 identify", 4, identityRequests.get());
        assertEquals(STAFF_ID, onMain(pipeline::getPersonId).longValue());
        assertEquals(36.6, onMain(pipeline::getTemperature), .001);
    }

    @Test public void singlePersonHeadRequiresClothingAndRestartsFourCompliantSamplesAfterMissingItem() {
        enterHead();
        for (boolean missingHat : new boolean[]{false, true}) {
            fixtureHat = !missingHat;
            fixtureMask = missingHat;
            for (int sample = 0; sample < 5; sample++) {
                feedFrame();
                assertEquals("单人入镜不能代替着装达标", CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
                assertEquals("缺任一要求着装均不能累计通过次数", 0, onMain(pipeline::getProgress).intValue());
            }
            onMain(() -> {
                assertTrue(pipeline.getCurrentIssues().contains(missingHat
                        ? CheckPipeline.Issue.HAT_MISSING : CheckPipeline.Issue.MASK_MISSING));
                assertTrue("修正前的警示不能写入最终异常", pipeline.getFindings().isEmpty());
                return null;
            });
        }
        fixtureHat = fixtureMask = true;
        for (int sample = 1; sample <= 3; sample++) {
            feedFrame();
            assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
            assertEquals(sample, onMain(pipeline::getProgress).intValue());
        }
        fixtureMask = false;
        feedFrame();
        assertEquals("三次合规后再次缺口罩须整组归零", 0, onMain(pipeline::getProgress).intValue());
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
        fixtureMask = true;
        for (int sample = 1; sample <= 4; sample++) {
            feedFrame();
            assertEquals(sample < 4 ? CheckPipeline.Stage.HEAD : CheckPipeline.Stage.HAND_BACK,
                    onMain(pipeline::getStage));
            if (sample < 4) assertEquals(sample, onMain(pipeline::getProgress).intValue());
        }
        assertEquals("头检只使用最初身份采样，不重新匹配戴口罩的员工", 4, identityRequests.get());
        assertEquals("每个单人头检帧均须经过真实detectFace和checkHead", 18, faceRequests.get());
        assertEquals(18, headReplies.get());
        assertEquals("着装更正不丢失已锁定员工", STAFF_ID, onMain(pipeline::getPersonId).longValue());
        assertEquals("着装更正不丢失已确认体温", 36.6, onMain(pipeline::getTemperature), .001);
        assertTrue("已修正着装不应污染最终检测结果", onMain(() -> pipeline.getFindings().isEmpty()));
    }

    @Test public void zeroOrMultipleFacesInterruptHeadSamplesWithoutLosingLockedPerson() {
        enterHead();
        feedFrame();
        assertEquals(1, onMain(pipeline::getProgress).intValue());
        for (int count : new int[]{0, 2, 3}) {
            fixtureFaceCount.set(count);
            int checked = headReplies.get();
            feedFrame();
            assertEquals("非单人画面不能送头检", checked, headReplies.get());
            assertEquals(0, onMain(pipeline::getProgress).intValue());
            assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
            assertEquals(STAFF_ID, onMain(pipeline::getPersonId).longValue());
            assertEquals(36.6, onMain(pipeline::getTemperature), .001);
        }
        fixtureFaceCount.set(1);
        feedFrame();
        assertEquals("重新单人入镜后从第一帧重新采样", 1, onMain(pipeline::getProgress).intValue());
    }

    @Test public void failedFaceDetectionCannotBeTreatedAsSingleFace() {
        enterHead();
        feedFrame();
        fixtureFaceCode.set(40010);
        feedFrame();
        assertEquals(1, headReplies.get());
        assertEquals(0, onMain(pipeline::getProgress).intValue());
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    @Test public void missingFaceArrayCannotBeTreatedAsAnEmptySuccessfulDetection() {
        enterHead();
        fixtureMissingFaces.set(true);
        feedFrame();
        assertEquals(0, headReplies.get());
        assertEquals(0, onMain(pipeline::getProgress).intValue());
        scenario.onActivity(activity -> assertFalse("无效回执不能作为真实无人证据",
                ((TextView) activity.findViewById(R.id.check_hint)).getText().toString().contains("未检测到人脸")));
    }

    @Test public void headDetectionReceivesExactlyTheSamePixelsAsFaceDetection() {
        enterHead();
        CameraSource.Frame frame = feedFrame();
        assertArrayEquals(frame.rgb, faceInput);
        assertArrayEquals("禁止把上一帧在场结果套到下一张头检图片", faceInput, headInput);
    }

    @Test public void headChainKeepsOneFrameInFlightUntilBothRepliesReturn() {
        enterHead();
        holdCommand.set("detectFace");
        scenario.onActivity(activity -> activity.onCameraFrame(frame()));
        await(() -> pendingReply.get() != null, "应暂存第一张人脸检测回执");
        scenario.onActivity(activity -> {
            activity.onCameraFrame(frame());
            activity.onCameraFrame(frame());
            assertTrue((Boolean) readField(activity, "frameBusy"));
        });
        assertEquals(1, faceRequests.get());
        assertEquals(0, headReplies.get());
        holdCommand.set("checkHead");
        releasePending();
        await(() -> pendingReply.get() != null && "checkHead".equals(pendingReply.get().command),
                "同帧应继续请求头检并等待回执");
        scenario.onActivity(activity -> {
            activity.onCameraFrame(frame());
            assertTrue((Boolean) readField(activity, "frameBusy"));
        });
        assertEquals("等待头检时也不能启动下一次人脸检测", 1, faceRequests.get());
        holdCommand.set(null);
        releasePending();
        await(() -> onMain(() -> !(Boolean) readField(checkActivity, "frameBusy")), "整轮应释放忙标记");
        assertEquals(1, onMain(pipeline::getProgress).intValue());
        feedFrame();
        assertEquals(2, faceRequests.get());
    }

    @Test public void pausedFaceReplyCannotStartHeadDetection() {
        enterHead();
        holdCommand.set("detectFace");
        scenario.onActivity(activity -> activity.onCameraFrame(frame()));
        await(() -> pendingReply.get() != null, "应暂存待取消的人脸检测回执");
        scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.check_pause).performClick()));
        holdCommand.set(null);
        releasePending();
        drainReplies();
        assertEquals(0, headReplies.get());
        assertEquals(0, onMain(pipeline::getProgress).intValue());
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    @Test public void pausedHeadReplyCannotAdvanceSamples() {
        enterHead();
        holdCommand.set("checkHead");
        scenario.onActivity(activity -> activity.onCameraFrame(frame()));
        await(() -> pendingReply.get() != null, "应暂存待取消的头检回执");
        scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.check_pause).performClick()));
        holdCommand.set(null);
        releasePending();
        drainReplies();
        assertEquals(0, onMain(pipeline::getProgress).intValue());
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    @Test public void lifecyclePauseCancelsFaceReplyBeforeOnStop() {
        assertLifecyclePauseDropsReply("detectFace");
    }

    @Test public void lifecyclePauseCancelsHeadReplyBeforeOnStop() {
        assertLifecyclePauseDropsReply("checkHead");
    }

    private void assertLifecyclePauseDropsReply(String command) {
        enterHead();
        holdCommand.set(command);
        scenario.onActivity(activity -> activity.onCameraFrame(frame()));
        await(() -> pendingReply.get() != null, "应暂存生命周期暂停前的 " + command + " 回执");
        try {
            // 桌面多窗口可能自动恢复 RESUMED；显式派发 onPause 保留待验收的 onStop 前窗口。
            scenario.onActivity(activity -> {
                instrumentation.callActivityOnPause(activity);
                assertFalse("必须已执行 onPause", (Boolean) readField(activity, "resumed"));
                assertTrue("不能依赖 onStop 把 foreground 清除后才拦住回包", activity.foreground);
            });
            holdCommand.set(null);
            releasePending();
            drainReplies();
            scenario.onActivity(activity -> {
                assertFalse("迟到回执期间仍应处于暂停状态", (Boolean) readField(activity, "resumed"));
                assertTrue("用例必须覆盖还未 onStop 的生命周期窗口", activity.foreground);
                assertFalse("暂停后不能残留忙标记", (Boolean) readField(activity, "frameBusy"));
            });
            assertEquals("生命周期暂停后旧帧不能推进头检", 0, onMain(pipeline::getProgress).intValue());
            assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
            assertEquals("detectFace".equals(command) ? 0 : 1, headReplies.get());
        } finally {
            // 恢复宿主状态，让 ActivityScenario 按正常生命周期完成清理。
            scenario.onActivity(activity -> instrumentation.callActivityOnResume(activity));
        }
    }

    @Test public void expiredFaceReplyCannotStartHeadDetection() throws Exception {
        enterHead();
        holdCommand.set("detectFace");
        CameraSource.Frame old = frame();
        scenario.onActivity(activity -> activity.onCameraFrame(old));
        await(() -> pendingReply.get() != null, "应暂存将过期的人脸检测回执");
        // 调整人工帧的业务收帧时钟，无须等待真实五秒或修改设备时间。
        field(old, "receivedAtNanos").setLong(old, System.nanoTime() - 6_000_000_000L);
        holdCommand.set(null);
        releasePending();
        await(() -> onMain(() -> !(Boolean) readField(checkActivity, "frameBusy")), "过期回包应结束本轮");
        assertEquals(0, headReplies.get());
        assertEquals(0, onMain(pipeline::getProgress).intValue());
    }

    @Test public void expiredHeadReplyCannotAdvanceSamples() throws Exception {
        enterHead();
        holdCommand.set("checkHead");
        CameraSource.Frame old = frame();
        scenario.onActivity(activity -> activity.onCameraFrame(old));
        await(() -> pendingReply.get() != null, "应暂存将过期的头检回执");
        field(old, "receivedAtNanos").setLong(old, System.nanoTime() - 6_000_000_000L);
        holdCommand.set(null);
        releasePending();
        await(() -> onMain(() -> !(Boolean) readField(checkActivity, "frameBusy")), "过期头检回包应结束本轮");
        assertEquals(0, onMain(pipeline::getProgress).intValue());
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    private void enterHead() {
        collectIdentity();
        feedTemperature(36.6);
        assertEquals(CheckPipeline.Stage.HEAD, onMain(pipeline::getStage));
    }

    private CameraSource.Frame collectIdentity() {
        CameraSource.Frame last = null;
        for (int sample = 1; sample <= 4; sample++) {
            last = feedFrame();
            if (sample < 4) {
                assertFalse("不足四帧不得确认员工", onMain(pipeline::isIdentityConfirmed));
                assertEquals(sample, onMain(pipeline::getProgress).intValue());
                assertEquals(CheckPipeline.Stage.IDENTITY, onMain(pipeline::getStage));
            }
        }
        assertTrue(onMain(pipeline::isIdentityConfirmed));
        return last;
    }

    private void assertUnconfirmed() {
        scenario.onActivity(activity -> {
            assertFalse(pipeline.isIdentityConfirmed());
            assertEquals(0, pipeline.getCandidateId());
            assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertNull(readField(activity, "activeStaff"));
            assertNull(readField(activity, "facePhoto"));
        });
    }

    private CameraSource.Frame feedFrame() {
        int expected = binaryReplies.get() + 1;
        CameraSource.Frame frame = frame();
        scenario.onActivity(activity -> activity.onCameraFrame(frame));
        await(() -> binaryReplies.get() >= expected
                && onMain(() -> !(Boolean) readField(checkActivity, "frameBusy")),
                "身份回执与档案读库或完整人脸检测/头检链必须处理完成");
        return frame;
    }

    private static CameraSource.Frame frame() {
        return new CameraSource.Frame(new byte[]{1, 2, 3}, 1, 1);
    }

    private final class IdentityFixture extends WebSocketListener {
        private final Map<String, JSONObject> images = new ConcurrentHashMap<>();

        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }

        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd");
                if ("detectFace".equals(command)) {
                    assertEquals("最多请求三张人脸，第三张用于识别多人场景", 3,
                            request.getJSONObject("params").getInt("max_faces"));
                    faceRequests.incrementAndGet();
                    images.put(request.getString("requestId"), request);
                    return;
                }
                if ("checkHead".equals(command)) {
                    images.put(request.getString("requestId"), request);
                    return;
                }
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
                JSONObject request = images.remove(requestId);
                assertNotNull("图片必须对应 identify、detectFace 或 checkHead 声明", request);
                assertEquals("人工测试帧只有一个RGB像素", 3, buffer.remaining());
                byte[] pixels = new byte[buffer.remaining()];
                buffer.get(pixels);
                String command = request.getString("cmd");
                JSONObject data;
                int code = 0;
                if ("identify".equals(command)) {
                    data = new JSONObject().put("matched", fixtureMatched.get()).put("person_id", fixturePerson.get());
                } else if ("detectFace".equals(command)) {
                    faceInput = pixels;
                    data = new JSONObject();
                    if (!fixtureMissingFaces.get()) {
                        JSONArray faces = new JSONArray();
                        for (int i = 0; i < fixtureFaceCount.get(); i++) faces.put(new JSONObject()
                                .put("x", .1 + i * .2).put("y", .1).put("w", .15).put("h", .5)
                                .put("score", .95).put("landmarks", new JSONArray(new double[]{
                                        .12, .2, .2, .2, .16, .3, .12, .4, .2, .4})));
                        data.put("faces", faces);
                    }
                    code = fixtureFaceCode.get();
                } else {
                    headInput = pixels;
                    JSONArray boxes = new JSONArray();
                    if (fixtureHat) boxes.put(new JSONObject().put("class_id", DetectionClasses.HEAD_HAT).put("score", .95));
                    if (fixtureMask) boxes.put(new JSONObject().put("class_id", DetectionClasses.HEAD_MASK).put("score", .95));
                    data = new JSONObject().put("boxes", boxes);
                }
                PendingReply reply = new PendingReply(socket, requestId, command, code, data);
                if (command.equals(holdCommand.get())) assertTrue("同时最多一张请求等待回执",
                        pendingReply.compareAndSet(null, reply));
                else reply.send();
            } catch (Throwable error) { serverFailure.set(error); }
        }

        private void reply(WebSocket socket, String id, JSONObject data) throws Exception {
            assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", 0)
                    .put("reason", "本机身份读取测试").put("data", data).toString()));
        }
    }

    private void releasePending() {
        PendingReply reply = pendingReply.getAndSet(null);
        assertNotNull("必须有一张已抵达夹具的请求可释放", reply);
        try { reply.send(); }
        catch (Exception error) { throw new AssertionError("释放隔离 SDK 回执失败", error); }
    }

    private void drainReplies() {
        AtomicReference<Boolean> drained = new AtomicReference<>(false);
        // 同一 WebSocket 后续状态回执返回后，先发送的旧图帧回执才算确实经过客户端。
        scenario.onActivity(activity -> activity.sdk.refreshStatus((code, message, data) -> drained.set(true)));
        await(drained::get, "应处理完已释放的旧图帧回执");
        instrumentation.waitForIdleSync();
    }

    private final class PendingReply {
        final WebSocket socket;
        final String id, command;
        final int code;
        final JSONObject data;

        PendingReply(WebSocket socket, String id, String command, int code, JSONObject data) {
            this.socket = socket;
            this.id = id;
            this.command = command;
            this.code = code;
            this.data = data;
        }

        void send() throws Exception {
            assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", code)
                    .put("reason", "本机身份与在场测试").put("data", data).toString()));
            binaryReplies.incrementAndGet();
            if ("checkHead".equals(command)) headReplies.incrementAndGet();
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
