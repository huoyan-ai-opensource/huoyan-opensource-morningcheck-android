package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.os.Build;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import android.widget.Spinner;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
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
import cc.huoyan.opensource.morningcheck.data.RecordPhotos;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;
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

/**
 * 人工图帧经真实 WS 客户端和现场晨检流程落库，再由结果、记录和首页读取同一数据。
 * AI 与测温回执是本机夹具；不接真实摄像头、串口、SDK 壳或业务云。
 */
@RunWith(AndroidJUnit4.class)
public final class MorningCheckFullFlowTest {
    private static final int WIDTH = 640, HEIGHT = 480;
    private static final long PERSON_ID = 901;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicInteger identifyRequests = new AtomicInteger(), faceRequests = new AtomicInteger(),
            detectionsReplied = new AtomicInteger();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences prefs;
    private Map<String, Object> originalPreferences;
    private AppStore originalStore, testStore;
    private String databaseName;
    private File ownedFileRoot;
    private boolean isolationStarted;
    private long departmentId;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> scenario;
    private HomeActivity home;
    private CheckActivity check;
    private ResultActivity result;
    private RecordsActivity records;
    private CheckPipeline pipeline;
    private Instrumentation.ActivityMonitor checkMonitor, resultMonitor, recordsMonitor;
    private volatile JSONArray nextBoxes = new JSONArray();
    private volatile long sdkPersonId;

    @Before public void prepareIsolatedRosterAndSdk() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT), device = Build.DEVICE.toLowerCase(Locale.ROOT);
        assumeTrue("全流程回归仅允许 x86 模拟器，不读写现场机器", x86 && (hardware.contains("ranchu")
                || hardware.contains("goldfish") || device.startsWith("generic_x86")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("设备需预先授权相机，测试不弹权限窗口",
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        prefs = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(prefs.getAll());
        onMain(() -> {
            assertFalse("不能打断在途门店登录", app.shopLogin().getState().isRunning());
            isolationStarted = true;
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        onIo(() -> null);
        instrumentation.waitForIdleSync();
        databaseName = "morning-full-flow-" + UUID.randomUUID() + ".db";
        ownedFileRoot = new File(app.getFilesDir(), "morning-full-flow-qa/20261013/photos-" + UUID.randomUUID());
        assertFalse("照片夹具必须使用新的独占目录", ownedFileRoot.exists());
        Context photoContext = new ContextWrapper(app) {
            @Override public File getFilesDir() { return ownedFileRoot; }
        };
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(photoContext, databaseName);
        onMain(() -> { originalStore = app.store; app.store = testStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    assertEquals("仅允许隔离 SDK WS，不允许业务云请求", "/sdk", request.getPath());
                    return new MockResponse().withWebSocketUpgrade(new SdkFixture());
                } catch (Throwable error) {
                    serverFailure.compareAndSet(null, error);
                    return new MockResponse().setResponseCode(403);
                }
            }
        });
        // 仍绑定回环地址；首页按 WS 配置正常导航，不依赖设备上另装真实 SDK 壳。
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(prefs.edit().clear()
                .putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:FULL-FLOW-NO-FACE-CAMERA")
                .putString("hand_camera", "usb:FULL-FLOW-NO-HAND-CAMERA")
                .putString("serial_path", "/full-flow-no-serial/" + UUID.randomUUID())
                .putString("light_mode", "FULL-FLOW-HARDWARE-DISABLED")
                .putBoolean("voice", false).putBoolean("temperature", true).putBoolean("head", true)
                .putBoolean("hat", true).putBoolean("mask", true).putString("temperature_limit", "37.3")
                .putString("shop_name", "隔离全流程QA")
                .putString("cloud_url", "").putString("cloud_token", "").commit());
        onIo(() -> {
            departmentId = testStore.saveDepartment(0, "隔离全流程部门");
            for (long id = PERSON_ID; id < PERSON_ID + 3; id++)
                testStore.saveStaff(new JSONObject().put("id", id).put("staff_no", "FULL-" + id)
                        .put("name", "全流程员工" + id).put("department_id", departmentId)
                        .put("health_expiry", LocalDate.now().plusYears(2).toString()).put("enabled", true));
            sdkPersonId = testStore.staff(PERSON_ID).getLong("person_id");
            assertTrue("SDK 夹具须使用独立人员标识，记录仍使用本地主键", sdkPersonId > 0 && sdkPersonId != PERSON_ID);
            return null;
        });
        checkMonitor = instrumentation.addMonitor(CheckActivity.class.getName(), null, false);
        resultMonitor = instrumentation.addMonitor(ResultActivity.class.getName(), null, false);
        recordsMonitor = instrumentation.addMonitor(RecordsActivity.class.getName(), null, false);
    }

    @After public void restorePreferencesStoreAndOnlyOwnedPhotos() throws Exception {
        List<Throwable> errors = new ArrayList<>();
        if (isolationStarted) {
            cleanup(errors, "关闭测试页面", () -> onMain(() -> { finishPages(); return null; }));
            if (scenario != null) cleanup(errors, "释放页面夹具", () -> { scenario.close(); return null; });
            cleanup(errors, "断开测试 SDK 会话", () -> onMain(() -> { app.sdkSession().disconnect(); return null; }));
            cleanup(errors, "等待本轮 IO 完成", () -> onIo(() -> null));
            cleanup(errors, "等待页面销毁完成", () -> { instrumentation.waitForIdleSync(); return null; });
        }
        for (Instrumentation.ActivityMonitor monitor : new Instrumentation.ActivityMonitor[]{checkMonitor, resultMonitor, recordsMonitor})
            if (monitor != null) cleanup(errors, "移除 ActivityMonitor", () -> { instrumentation.removeMonitor(monitor); return null; });
        if (app != null && originalStore != null)
            cleanup(errors, "恢复原数据库实例", () -> onMain(() -> { app.store = originalStore; return null; }));
        if (prefs != null && originalPreferences != null)
            cleanup(errors, "恢复原设备配置", () -> { restore(prefs, originalPreferences); return null; });
        if (ownedFileRoot != null)
            cleanup(errors, "清理本用例独占照片目录", () -> onIo(() -> { cleanOwnedPhotos(); return null; }));
        if (testStore != null) cleanup(errors, "关闭隔离数据库", () -> { testStore.close(); return null; });
        if (databaseName != null) cleanup(errors, "删除隔离数据库", () -> {
            assertTrue(databaseName.startsWith("morning-full-flow-"));
            if (app.getDatabasePath(databaseName).exists()) assertTrue(app.deleteDatabase(databaseName));
            return null;
        });
        for (WebSocket socket : sockets)
            cleanup(errors, "关闭测试 WS", () -> { socket.close(1001, "全流程测试结束"); return null; });
        if (endpoint != null) cleanup(errors, "释放本机 SDK 夹具", () -> { endpoint.shutdown(); return null; });
        if (serverFailure.get() != null) errors.add(new AssertionError("SDK 夹具不能出现协议错误", serverFailure.get()));
        throwCleanupErrors(errors);
    }

    private void cleanOwnedPhotos() throws Exception {
        if (!ownedFileRoot.exists()) return;
        File qaRoot = new File(app.getFilesDir(), "morning-full-flow-qa/20261013").getCanonicalFile();
        assertEquals("只清理本轮 QA 的独占子目录", qaRoot, ownedFileRoot.getCanonicalFile().getParentFile());
        assertTrue(ownedFileRoot.getName().startsWith("photos-"));
        UUID.fromString(ownedFileRoot.getName().substring("photos-".length()));
        File photos = new File(ownedFileRoot, "check-photos");
        List<Throwable> errors = new ArrayList<>();
        if (photos.exists()) {
            File[] files = photos.listFiles();
            assertNotNull("独占照片目录必须可枚举", files);
            for (File file : files) cleanup(errors, "删除本用例 JPEG", () -> {
                assertEquals(photos.getCanonicalFile(), file.getCanonicalFile().getParentFile());
                String name = file.getName();
                assertTrue("只清理生产流程生成的 UUID JPEG", file.isFile() && name.length() > 40
                        && name.charAt(36) == '-' && name.endsWith(".jpg"));
                UUID.fromString(name.substring(0, 36));
                assertTrue(file.delete());
                return null;
            });
            cleanup(errors, "删除空照片夹具目录", () -> { assertTrue(photos.delete()); return null; });
        }
        cleanup(errors, "删除空文件夹具目录", () -> { assertTrue(ownedFileRoot.delete()); return null; });
        throwCleanupErrors(errors);
    }

    /** 任一清理断言失败也必须继续还原配置、数据库及连接，最后统一报告。 */
    private static void cleanup(List<Throwable> errors, String step, Callable<?> action) {
        try { action.call(); }
        catch (Throwable error) { errors.add(new AssertionError(step + "失败", error)); }
    }

    private static void throwCleanupErrors(List<Throwable> errors) {
        if (errors.isEmpty()) return;
        AssertionError failure = new AssertionError("全流程测试清理失败，共 " + errors.size() + " 项");
        for (Throwable error : errors) failure.addSuppressed(error);
        throw failure;
    }

    @Test public void normalCheckPersistsAndHomeCountsOnlyCompletedEmployee() throws Exception {
        runFullFlow(false);
    }

    @Test public void abnormalCheckPersistsAndRecordFiltersReadSavedResult() throws Exception {
        runFullFlow(true);
    }

    private void runFullFlow(boolean abnormal) throws Exception {
        String label = abnormal ? "abnormal" : "normal";
        scenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        scenario.onActivity(activity -> home = activity);
        await(() -> onMain(() -> home.loaded && app.sdkSession().viewState().ready), "首页应连接隔离 SDK 并读取名册");
        assertHomeCounts(3, 0, 0, 0);
        screenshot(home, label + "-home-before");
        onMain(() -> { assertTrue(home.findViewById(R.id.home_start).performClick()); return null; });
        check = (CheckActivity) instrumentation.waitForMonitorWithTimeout(checkMonitor, 10_000);
        assertNotNull("首页晨检入口应打开独立现场页", check);
        await(() -> onMain(() -> check.foreground && (Boolean) field(check, "resumed")
                && check.loaded && field(check, "pipeline") != null
                && check.findViewById(R.id.check_pause) != null), "现场页应完成自动流程初始化");
        onMain(() -> {
            pipeline = (CheckPipeline) field(check, "pipeline");
            check.stopCamera();
            assertNull("只消费人工 RGB，不使用真实相机", check.camera);
            assertEquals(4, pipeline.getRequiredSamples());
            return null;
        });
        for (int sample = 1; sample <= 4; sample++) {
            int expected = sample;
            onMain(() -> { check.onCameraFrame(frame(Color.rgb(70, 100, 120))); return null; });
            await(() -> identifyRequests.get() >= expected && onMain(() -> !(Boolean) field(check, "frameBusy")),
                    "真实客户端应完成第 " + sample + " 次身份回执及档案读取");
            assertEquals(sample == 4, onMain(pipeline::isIdentityConfirmed));
        }
        onMain(() -> {
            assertEquals("未得到读数不能跳过测温", CheckPipeline.Stage.IDENTITY, pipeline.getStage());
            assertTrue(Double.isNaN(pipeline.getTemperature()));
            TemperatureSource thermometer = (TemperatureSource) field(check, "thermometer");
            ((TemperatureSource.Listener) field(thermometer, "listener")).onTemperature(36.6);
            assertEquals(CheckPipeline.Stage.HEAD, pipeline.getStage());
            return null;
        });
        JSONArray head = new JSONArray().put(box(DetectionClasses.HEAD_HAT, .2, .1, .6, .2))
                .put(box(DetectionClasses.HEAD_MASK, .25, .35, .5, .3));
        for (int i = 0; i < 4; i++) feed(head);
        assertEquals(CheckPipeline.Stage.HAND_BACK, onMain(pipeline::getStage));
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_BACK, abnormal && i == 0));
        assertEquals(CheckPipeline.Stage.HAND_PALM, onMain(pipeline::getStage));
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_PALM, false));
        assertEquals(CheckPipeline.Stage.RESULT, onMain(pipeline::getStage));
        assertEquals("身份阶段执行四次 identify，头检不再重复身份识别", 4, identifyRequests.get());
        assertEquals("四帧头检均先执行真实 detectFace", 4, faceRequests.get());
        assertEquals("头部四帧和两组双手五帧应处理完毕", 14, detectionsReplied.get());
        result = (ResultActivity) instrumentation.waitForMonitorWithTimeout(resultMonitor, 10_000);
        assertNotNull("真实保存成功后应自动打开结果 Activity", result);
        await(() -> onMain(() -> result.loaded && result.currentRecord != null
                && result.findViewById(R.id.result_stay) != null), "结果页应读取本轮完成记录");
        onMain(() -> { assertTrue(result.findViewById(R.id.result_stay).performClick()); return null; });
        JSONObject saved = onIo(() -> {
            AppStore.DataPage page = testStore.recordsPage(0, 1, 20);
            assertEquals("未完成身份采样不能另存记录", 1, page.totalRows);
            return testStore.record(page.rows.get(0).getString("id"));
        });
        assertNotNull(saved);
        assertEquals(PERSON_ID, saved.getLong("staff_id"));
        assertEquals("FULL-" + PERSON_ID, saved.getString("staff_no"));
        assertEquals("全流程员工" + PERSON_ID, saved.getString("staff_name"));
        assertEquals(departmentId, saved.getLong("department_id"));
        assertEquals("隔离全流程部门", saved.getString("department"));
        assertEquals("36.6", saved.getString("temperature"));
        assertEquals(abnormal ? 0 : 1, saved.getInt("status"));
        assertEquals(abnormal ? 1 : 0, saved.getInt("abnormal_hand"));
        assertEquals(abnormal, saved.getJSONArray("findings").toString().contains("手背：戒指"));
        assertFalse("不应跳过员工身份", saved.has("identity_skipped"));
        assertEquals("未上传", saved.getString("upload"));
        onMain(() -> { assertEquals(saved.getString("id"), result.currentRecord.getString("id")); return null; });
        for (RecordPhotos.Photo photo : RecordPhotos.read(saved)) {
            Bitmap image = onIo(() -> BitmapFactory.decodeFile(testStore.photo(photo.file).getAbsolutePath()));
            assertNotNull("生产流程应保存可解码的 JPEG：" + photo.label, image);
            try { assertEquals(WIDTH, image.getWidth()); assertEquals(HEIGHT, image.getHeight()); }
            finally { image.recycle(); }
        }
        screenshot(result, label + "-result");
        onMain(() -> { assertTrue(result.findViewById(R.id.header_home).performClick()); return null; });
        await(() -> onMain(() -> home.foreground && home.loaded), "结果页返回应恢复原首页实例");
        assertHomeCounts(2, 1, abnormal ? 0 : 1, abnormal ? 1 : 0);
        screenshot(home, label + "-home-after");
        onMain(() -> {
            assertTrue(home.findViewById(R.id.home_records).performClick());
            return null;
        });
        records = (RecordsActivity) instrumentation.waitForMonitorWithTimeout(recordsMonitor, 10_000);
        assertNotNull("首页查询入口应打开独立记录 Activity", records);
        await(() -> onMain(() -> records.loaded && field(records, "recordData") != null
                && records.findViewById(R.id.records_list) != null), "记录页应完成实际分页查询");
        if (abnormal) {
            onMain(() -> {
                ((Spinner) records.findViewById(R.id.records_filter)).setSelection(2);
                assertTrue("真实日期入口应可打开", records.findViewById(R.id.records_date_filter).performClick());
                return null;
            });
            instrumentation.waitForIdleSync();
            onMain(() -> {
                RecordDateFilterDialog date = (RecordDateFilterDialog) field(field(records, "dateFilter"), "dialog");
                assertNotNull("日期筛选应打开独立窗口", date);
                assertTrue(date.isShowing());
                View today = date.findViewById(R.id.record_date_today);
                assertTrue("今天快捷范围必须可见", today.isShown());
                assertTrue(today.performClick());
                assertTrue(date.findViewById(R.id.record_date_confirm).performClick());
                return null;
            });
            await(() -> onMain(() -> !(Boolean) field(records, "pageLoading")
                    && ((Number) field(records, "loadedFilter")).intValue() == 2
                    && RecordDateRange.today().equals(field(records, "dateRange"))),
                    "实际异常和今天条件应完成分页查询");
        }
        onMain(() -> {
            RecyclerView list = records.findViewById(R.id.records_list);
            assertEquals(1, RecyclerListTestHelper.count(list));
            assertEquals(saved.getString("id"), ((JSONObject) RecyclerListTestHelper.item(list, 0)).getString("id"));
            if (abnormal) {
                assertEquals(2, field(records, "recordFilter"));
                assertEquals(RecordDateRange.today(), field(records, "dateRange"));
            }
            return null;
        });
        screenshot(records, label + "-records");
    }

    private void assertHomeCounts(long unchecked, long checked, long normal, long abnormal) throws Exception {
        await(() -> onMain(() -> home.loaded
                && ((Number) field(home, "staffCount")).longValue() == unchecked + checked
                && ((Number) field(home, "checkedTodayCount")).longValue() == checked
                && ((Number) field(home, "normalTodayCount")).longValue() == normal
                && ((Number) field(home, "abnormalTodayCount")).longValue() == abnormal),
                "首页必须由真实落库记录计算未检、已检、正常、异常人数");
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        AppStore.HomeSummary summary = onIo(() -> testStore.homeSummary(
                today.atStartOfDay(zone).toInstant().toEpochMilli(),
                today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()));
        assertEquals(unchecked, Math.max(0, summary.staffCount - summary.checkedTodayCount));
        assertEquals(checked, summary.checkedTodayCount);
        assertEquals(normal, summary.normalTodayCount);
        assertEquals(abnormal, summary.abnormalTodayCount);
    }

    private void feed(JSONArray boxes) {
        int expected = detectionsReplied.get() + 1;
        nextBoxes = boxes;
        onMain(() -> { check.onCameraFrame(frame(Color.rgb(130, 155, 180))); return null; });
        await(() -> detectionsReplied.get() >= expected && onMain(() ->
                pipeline.getStage() == CheckPipeline.Stage.RESULT || !(Boolean) field(check, "frameBusy")),
                "真实 WS 检测回执应驱动下一采样或阶段");
    }

    private void screenshot(BaseMorningActivity activity, String label) throws Exception {
        onIo(() -> null);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            View root = activity.findViewById(R.id.main_root);
            assertTrue("截图应来自实际已布局页面", root.getWidth() > 0 && root.getHeight() > 0);
            File directory = new File(app.getFilesDir(), "morning-full-flow-qa/20261013");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(new File(directory,
                    label + "-" + root.getWidth() + "x" + root.getHeight() + ".png"))) {
                root.draw(new Canvas(bitmap));
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { bitmap.recycle(); }
            return null;
        });
    }

    private static CameraSource.Frame frame(int color) {
        byte[] rgb = new byte[WIDTH * HEIGHT * 3];
        for (int offset = 0; offset < rgb.length; offset += 3) {
            rgb[offset] = (byte) Color.red(color); rgb[offset + 1] = (byte) Color.green(color);
            rgb[offset + 2] = (byte) Color.blue(color);
        }
        return new CameraSource.Frame(rgb, WIDTH, HEIGHT);
    }

    private static JSONArray handBoxes(int orientation, boolean ring) throws Exception {
        JSONArray boxes = new JSONArray().put(box(orientation, .05, .15, .35, .7))
                .put(box(orientation, .6, .15, .35, .7));
        if (ring) boxes.put(box(DetectionClasses.HAND_RING, .2, .55, .16, .12));
        return boxes;
    }

    private static JSONObject box(int classId, double x, double y, double width, double height) throws Exception {
        return new JSONObject().put("class_id", classId).put("score", .95)
                .put("x", x).put("y", y).put("w", width).put("h", height);
    }

    private final class SdkFixture extends WebSocketListener {
        private final Map<String, JSONObject> images = new ConcurrentHashMap<>();
        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd");
                if ("identify".equals(command) || "detectFace".equals(command)
                        || "checkHead".equals(command) || "checkHand".equals(command)) {
                    if ("identify".equals(command)) identifyRequests.incrementAndGet();
                    if ("detectFace".equals(command)) {
                        assertEquals(3, request.getJSONObject("params").getInt("max_faces"));
                        faceRequests.incrementAndGet();
                    }
                    images.put(request.getString("requestId"), request);
                    return;
                }
                assertTrue("不允许激活、学习、删除或云端写操作", "init".equals(command)
                        || "getStatus".equals(command) || "getCapabilities".equals(command)
                        || "get_class_table".equals(command) || "getCertInfo".equals(command)
                        || "getDownloadProgress".equals(command) || "getCloudStatus".equals(command));
                JSONObject data = new JSONObject();
                if ("getStatus".equals(command)) data.put("sdk_state", "READY").put("model_ready", true)
                        .put("certificate_valid", true).put("device_id", "FULL-FLOW-TEST");
                reply(socket, request.getString("requestId"), data);
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
        }
        @Override public void onMessage(WebSocket socket, ByteString bytes) {
            try {
                ByteBuffer buffer = bytes.asByteBuffer();
                int length = buffer.getInt();
                assertTrue(length > 0 && length < buffer.remaining());
                byte[] rawId = new byte[length]; buffer.get(rawId);
                String id = new String(rawId, StandardCharsets.UTF_8);
                JSONObject request = images.remove(id);
                assertNotNull("图帧必须对应实际客户端请求", request);
                JSONObject params = request.getJSONObject("params");
                assertEquals(WIDTH, params.getInt("width")); assertEquals(HEIGHT, params.getInt("height"));
                assertEquals(WIDTH * HEIGHT * 3, buffer.remaining());
                if ("identify".equals(request.getString("cmd")))
                    reply(socket, id, new JSONObject().put("matched", true).put("person_id", sdkPersonId));
                else if ("detectFace".equals(request.getString("cmd")))
                    reply(socket, id, new JSONObject().put("faces", new JSONArray().put(
                            new JSONObject().put("x", .2).put("y", .1).put("w", .6).put("h", .7)
                                    .put("score", .95).put("landmarks", new JSONArray(new double[]{
                                            .3, .3, .6, .3, .45, .4, .3, .6, .6, .6})))));
                else {
                    reply(socket, id, new JSONObject().put("boxes", new JSONArray(nextBoxes.toString())));
                    detectionsReplied.incrementAndGet();
                }
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
        }
        private void reply(WebSocket socket, String id, JSONObject data) throws Exception {
            assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", 0)
                    .put("reason", "隔离晨检全流程验收").put("data", data).toString()));
        }
    }

    private void finishPages() {
        Set<Activity> pages = new HashSet<>();
        for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.CREATED, Stage.PAUSED, Stage.STOPPED})
            pages.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
        for (Activity activity : new ArrayList<>(pages)) if (activity instanceof BaseMorningActivity) activity.finish();
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) {}
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("不存在的测试状态：" + name);
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程全流程验收失败", failure.get());
        return value.get();
    }

    private <T> T onIo(Callable<T> action) throws Exception { return app.io.submit(action).get(10, TimeUnit.SECONDS); }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            if (serverFailure.get() != null) throw new AssertionError("隔离 SDK 夹具失败", serverFailure.get());
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs, Map<String, Object> original) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
            else throw new AssertionError("未知设备设置类型");
        }
        assertTrue("应恢复测试前全部设备配置", editor.commit());
        assertTrue("应恢复全部配置且不输出原配置内容", original.equals(prefs.getAll()));
    }
}
