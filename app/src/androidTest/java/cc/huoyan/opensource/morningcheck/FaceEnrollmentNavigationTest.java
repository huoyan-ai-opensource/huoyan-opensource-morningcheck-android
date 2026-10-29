package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.ScreenViewGeometry.visibleOnScreen;
import static cc.huoyan.opensource.morningcheck.RecyclerListTestHelper.*;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
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
import cc.huoyan.opensource.morningcheck.data.Jsons;
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
import okio.Buffer;

/** 同屏人脸工作台只使用 UUID 测试库、本机 SDK 夹具和不存在的镜头，不接触业务员工或真实学习。 */
@RunWith(AndroidJUnit4.class)
public final class FaceEnrollmentNavigationTest {
    private static final long FIRST_ID = 91001;
    private static final long DETAIL_ID = 91042;
    private static final String TEST_DEVICE = "FACE-ENTRY-ISOLATED-TEST";
    private static final String TEST_LIGHT_PATH = "/dev/huoyan-enrollment-test";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicInteger businessRequests = new AtomicInteger(), registerRequests = new AtomicInteger();
    private final AtomicInteger detailRequests = new AtomicInteger(), deleteRequests = new AtomicInteger();
    private final AtomicInteger faceDetectionRequests = new AtomicInteger();
    private final AtomicReference<PendingRegistration> pendingFaceDetection = new AtomicReference<>();
    private volatile int syntheticFaceCount = 1, faceDetectionCode, delayedFaceDetectionAt;
    private volatile byte[] lastDetectedRgb, registeredRgb;
    private final AtomicReference<Long> lastDeletedUid = new AtomicReference<>();
    private final Map<Long, Set<Long>> sdkSamples = new ConcurrentHashMap<>();
    private final Map<Long, Long> sdkPersonIds = new ConcurrentHashMap<>();
    private final Set<Long> queriedPersonIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Long> lastForgottenPerson = new AtomicReference<>();
    private final Map<Long, byte[]> imageFixtures = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> imageRequests = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> expectedLocalSamples = new HashMap<>();
    private final AtomicReference<PendingRegistration> pendingRegistration = new AtomicReference<>();
    private final AtomicReference<PendingRegistration> pendingDetail = new AtomicReference<>();
    private final AtomicReference<PendingRegistration> pendingDeletion = new AtomicReference<>();
    private final CountDownLatch registerReceived = new CountDownLatch(1);
    private final CountDownLatch detailReceived = new CountDownLatch(1);
    private volatile boolean allowSyntheticRegistration;
    private volatile boolean allowSyntheticDelete;
    private volatile boolean detailItemsOnly;
    private volatile boolean delayDeletion;
    private volatile long unavailableSampleUid;
    private volatile long delayedDetailPerson;
    private volatile int deleteResponseCode, detailResponseCode;
    private long expectedRegisteredStaffId, expectedRegisteredUid;
    private MorningApplication app;
    private AppStore originalStore, testStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private ActivityScenario<? extends BaseMorningActivity> scenario;
    private android.app.Application.ActivityLifecycleCallbacks deviceOrientation;
    private final CopyOnWriteArrayList<FakeEnrollmentLight> lightDevices = new CopyOnWriteArrayList<>();
    private final AtomicInteger lightOnWrites = new AtomicInteger(), lightOffWrites = new AtomicInteger();
    private ExecutorService lightWorker;
    private LightController originalLightController;
    private boolean lightControllerInjected;

    @Before public void isolateEmployeeStoreAndSdk() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        boolean emulator = x86 && (Build.FINGERPRINT.toLowerCase(Locale.ROOT).contains("generic")
                || hardware.contains("ranchu") || hardware.contains("goldfish"));
        assumeTrue("仅允许指定 C106 / NB55 真机或明确识别的 x86 模拟器运行隔离夹具",
                "C106".equals(Build.MODEL) || "NB55".equals(Build.MODEL) || emulator);
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        deviceOrientation = new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity page, Bundle state) {
                String orientation = InstrumentationRegistry.getArguments().getString("design_orientation", "");
                if (!orientation.isEmpty()) page.setRequestedOrientation("portrait".equals(orientation)
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            @Override public void onActivityStarted(Activity page) { }
            @Override public void onActivityResumed(Activity page) { }
            @Override public void onActivityPaused(Activity page) { }
            @Override public void onActivityStopped(Activity page) { }
            @Override public void onActivitySaveInstanceState(Activity page, Bundle state) { }
            @Override public void onActivityDestroyed(Activity page) { }
        };
        app.registerActivityLifecycleCallbacks(deviceOrientation);
        assertEquals("测试不申请或改变权限，设备须预先授予相机权限", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA));
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        onMain(() -> {
            if (ToastEx.toast != null) ToastEx.toast.cancel();
            ToastEx.toast = null;
            assertFalse("不能中断正在执行的门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        databaseName = "face-entry-test-" + UUID.randomUUID() + ".db";
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(app, databaseName);
        app.io.submit(() -> testStore.getWritableDatabase()).get(10, TimeUnit.SECONDS);
        onMain(() -> { originalStore = app.store; app.store = testStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path != null && path.matches("/[0-9]+\\.jpg")) {
                    long uid = Long.parseLong(path.substring(1, path.length() - 4));
                    byte[] fixture = imageFixtures.get(uid);
                    if (fixture != null && "GET".equals(request.getMethod())) {
                        if (request.getHeader("X-USER-TOKEN") != null || request.getHeader("Authorization") != null)
                            serverFailure.set(new AssertionError("样本图片请求不能携带业务或SDK令牌"));
                        imageRequests.computeIfAbsent(uid, key -> new AtomicInteger()).incrementAndGet();
                        return new MockResponse().setHeader("Content-Type", "image/jpeg")
                                .setBody(new Buffer().write(fixture));
                    }
                }
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new EnrollmentFixture());
            }
        });
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:FACE-ENTRY-NO-SUCH-CAMERA")
                .putString("hand_camera", "usb:FACE-ENTRY-NO-SUCH-HAND-CAMERA")
                .putString("cloud_url", endpoint.url("/forbidden").toString()).putString("cloud_token", "")
                .putBoolean("voice", false)
                .putString("light_mode", LightConfig.SERIAL).putString("light_serial_path", TEST_LIGHT_PATH)
                .putString("light_baud", "115200").putString("light_on_command", LightConfig.DEFAULT_ON_COMMAND)
                .putString("light_off_command", LightConfig.DEFAULT_OFF_COMMAND).commit());
        // 所有旧的人造帧用例也必须走假灯，不能因新增自动补光访问现场串口或 GPIO。
        lightWorker = Executors.newSingleThreadExecutor(task -> new Thread(task, "隔离人脸录入补光"));
        Handler lightMain = new Handler(Looper.getMainLooper());
        LightController fakeController = new LightController(lightWorker, lightMain::post, config -> {
            if (!TEST_LIGHT_PATH.equals(config.path) || !LightConfig.SERIAL.equals(config.mode)) {
                IOException error = new IOException("人脸录入测试只能打开隔离假灯配置");
                serverFailure.compareAndSet(null, error);
                throw error;
            }
            FakeEnrollmentLight device = new FakeEnrollmentLight();
            lightDevices.add(device);
            return device;
        });
        onMain(() -> {
            originalLightController = (LightController) lightControllerField().get(null);
            lightControllerField().set(null, fakeController);
            lightControllerInjected = true;
            return null;
        });
    }

    @After public void restoreEmployeeStoreAndConfiguration() throws Exception {
        try {
            PendingRegistration pending = pendingRegistration.getAndSet(null);
            if (pending != null) reply(pending.socket, pending.requestId, Jsons.obj("uid", 0));
            PendingRegistration delayed = pendingDetail.getAndSet(null);
            if (delayed != null) reply(delayed.socket, delayed.requestId, sampleDetail(delayed.personId));
            PendingRegistration deletion = pendingDeletion.getAndSet(null);
            if (deletion != null) reply(deletion.socket, deletion.requestId, -1, new JSONObject());
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> {
                finishPages();
                app.sdkSession().disconnect();
                return null;
            });
            instrumentation.waitForIdleSync();
            if (app != null) app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS);
            if (app != null) app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
            flushLightWorker();
            for (FakeEnrollmentLight device : lightDevices) {
                assertTrue("人脸录入页结束后假灯链路应释放", device.closed.get());
                assertFalse("人脸录入页结束后不能遗留亮灯", device.on);
            }
            if (testStore != null) {
                for (JSONObject employee : app.io.submit(() -> testStore.staff()).get(10, TimeUnit.SECONDS)) {
                    Set<Long> expected = expectedLocalSamples.get(employee.optLong("id"));
                    if (expected != null && !expected.isEmpty()) {
                        assertTrue("仅预期夹具员工可以保存样本", employee.optBoolean("face_registered"));
                        assertEquals("员工样本只能含本用例预期 UID", expected, uidSet(employee.optJSONArray("face_uids")));
                    } else {
                        assertFalse("不能为其他员工登记测试样本", employee.optBoolean("face_registered"));
                        assertTrue("无样本员工不能遗留 UID", uidSet(employee.optJSONArray("face_uids")).isEmpty());
                    }
                    assertFalse("测试收尾不能遗留人脸待核对标记", employee.optBoolean("face_write_unknown"));
                }
            }
        } finally {
            if (app != null && deviceOrientation != null) app.unregisterActivityLifecycleCallbacks(deviceOrientation);
            try {
                if (lightControllerInjected) onMain(() -> {
                    lightControllerField().set(null, originalLightController);
                    return null;
                });
                if (lightWorker != null) lightWorker.shutdownNow();
                if (originalStore != null) onMain(() -> { app.store = originalStore; return null; });
                if (testStore != null) testStore.close();
                if (databaseName != null) assertTrue("应删除本轮独立员工库", app.deleteDatabase(databaseName));
                if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "人脸录入导航测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("人脸选择和导航不能访问业务云", 0, businessRequests.get());
        assertEquals("只有明确的人工帧回归允许提交本机 register", allowSyntheticRegistration ? 1 : 0,
                registerRequests.get());
        assertNull("隔离 SDK 不应收到未声明的命令或错误帧", serverFailure.get());
    }

    @Test public void homeStaffEntryOpensUnifiedSelectionWithoutCamera() throws Exception {
        seedStaff(3);
        launch(HomeActivity.class);
        awaitControl(HomeActivity.class, R.id.home_staff);
        click(HomeActivity.class, R.id.home_staff);
        awaitControl(StaffActivity.class, R.id.staff_enroll);
        click(StaffActivity.class, R.id.staff_enroll);
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        assertEmployeeColumns();
        assertSelectionWithoutCamera();
        onMain(() -> {
            assertFalse("员工管理的录入入口不能携带任意员工预选", resumed(FaceEnrollmentActivity.class)
                    .getIntent().hasExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID));
            return null;
        });
        holdForScreenshot("workspace-empty");
    }

    @Test public void staffEntryOpensSameSelectionWithoutCamera() throws Exception {
        seedStaff(3);
        launch(StaffActivity.class);
        awaitControl(StaffActivity.class, R.id.staff_enroll);
        click(StaffActivity.class, R.id.staff_enroll);
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        assertSelectionWithoutCamera();
    }



    @Test public void employeeSearchPagesAndRestoresSearchAndPageAfterRecreation() throws Exception {
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        assertEmployeeColumns();
        click(FaceEnrollmentActivity.class, R.id.enrollment_next);
        awaitSelection("第 2 / 3 页 · 共 55 条", 48);
        search("后厨");
        awaitSelection("第 1 / 2 页 · 共 45 条", 24);
        click(FaceEnrollmentActivity.class, R.id.enrollment_next);
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        assertFalse(onMain(() -> resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_next).isEnabled()));
        scenario.recreate();
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        assertEmployeeColumns();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertEquals("后厨", ((EditText) page.findViewById(R.id.enrollment_search)).getText().toString());
            RecyclerView employees = page.findViewById(R.id.enrollment_staff_list);
            for (int i = 0; i < count(employees); i++)
                assertEquals("后厨", ((JSONObject) item(employees, i)).optString("department"));
            assertFalse(page.findViewById(R.id.enrollment_next).isEnabled());
            assertEquals(View.GONE, page.findViewById(R.id.enrollment_previous).getVisibility());
            return null;
        });
        assertSelectionWithoutCamera();
    }

    @Test public void secondColumnTapKeepsScrolledEmployeeRowsAndSelectsTheirActualId() throws Exception {
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        assertEmployeeColumns();
        onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            scroll(employees, 6, 0);
            return null;
        });
        await(() -> onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            return !employees.isLayoutRequested() && first(employees) > 0
                    && employees.getChildCount() >= 2;
        }), "选择前应实际滚动员工网格，覆盖非首页首行的高亮行为");
        assertEmployeeColumns();
        int[] viewport = employeeViewport();
        long selectedId = tapVisibleSecondColumnEmployee();
        awaitCapture(selectedId);
        instrumentation.waitForIdleSync();
        assertSelectedRow(selectedId);
        assertVisibleSelectedEmployee(selectedId);
        assertArrayEquals("点击第二列不能把员工网格滚回顶部", viewport, employeeViewport());

        onMain(() -> {
            resumed(FaceEnrollmentActivity.class).onSdkStateChanged();
            return null;
        });
        instrumentation.waitForIdleSync();
        assertArrayEquals("SDK 状态刷新不能改变当前员工网格滚动位置", viewport, employeeViewport());
        assertVisibleSelectedEmployee(selectedId);
        assertEquals("选择和状态刷新不得自动提交学习", 0, registerRequests.get());
    }

    @Test public void phoneEmployeeChooserRestoresSelectionAndViewportWithoutUsingHiddenCamera() throws Exception {
        assumeTrue("手机采用展开员工选择区", app.getResources().getBoolean(R.bool.mc_phone));
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        onMain(() -> { scroll(resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list), 6, 0); return null; });
        await(() -> onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            return !employees.isLayoutRequested() && first(employees) > 0
                    && employees.getChildCount() >= 2;
        }), "手机选择区应先滚动到非首行");
        int[] viewport = employeeViewport();
        long selectedId = tapVisibleSecondColumnEmployee();
        awaitCapture(selectedId);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertFalse("选好员工后列表应收起，为相机和照片留出空间", page.findViewById(R.id.enrollment_selection).isShown());
            assertTrue(page.findViewById(R.id.enrollment_choose_staff).isShown());
            assertTrue("更换员工操作应可达", page.findViewById(R.id.enrollment_choose_staff).performClick());
            assertNull("选择员工期间不能持有隐藏相机", page.camera);
            assertNull("选择员工期间不能学习旧帧", page.latestFrame);
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            return null;
        });
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        assertEmployeeColumns();
        assertArrayEquals("展开选择区应回到选人前的视口", viewport, employeeViewport());
        scenario.recreate();
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        assertEmployeeColumns();
        assertSelectedRow(selectedId);
        assertArrayEquals("重建展开选择区应保留滚动位置", viewport, employeeViewport());
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertNull("重建时展开选择区不能重新打开镜头", page.camera);
            page.findViewById(R.id.enrollment_capture).performClick();
            assertEquals("隐藏采集入口也不能提交 register", 0, registerRequests.get());
            assertTrue(page.findViewById(R.id.enrollment_choose_staff).performClick());
            return null;
        });
        awaitCapture(selectedId);
        assertEquals(0, registerRequests.get());
        assertEquals(0, deleteRequests.get());
    }

    @Test public void employeeSearchLoadsMatchesAndClearsWithoutSelectingEmployee() throws Exception {
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        tapEmployeeSearch();
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && page.findViewById(R.id.enrollment_search).hasFocus()
                    && employeeSearchImeVisible(page);
        }), "实时搜索前应实际打开系统输入法");
        CountDownLatch storageEntered = new CountDownLatch(1), releaseStorage = new CountDownLatch(1);
        java.util.concurrent.Future<?> storageBarrier = app.io.submit(() -> {
            storageEntered.countDown();
            try { assertTrue("输入验收结束后应释放读库屏障", releaseStorage.await(30, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        try {
            assertTrue("搜索前应建立可控的读库屏障", storageEntered.await(10, TimeUnit.SECONDS));
            onMain(() -> {
                EditText input = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_search);
                String keyword = String.valueOf(DETAIL_ID);
                for (int index = 0; index < keyword.length(); index++) {
                    input.getText().append(keyword.charAt(index));
                    assertEquals("每个字符应即时写入原员工搜索框", keyword.substring(0, index + 1),
                            input.getText().toString());
                }
                return null;
            });
            long[] loadingVisibleAt = {0};
            AtomicReference<String> loadingInputState = new AtomicReference<>();
            try {
                await(() -> onMain(() -> {
                    FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                    Dialog loading = page == null ? null : operationLoadingDialog(page);
                    if (loading == null || !loading.isShowing()) {
                        loadingVisibleAt[0] = 0;
                        loadingInputState.set("读取提示尚未显示");
                        return false;
                    }
                    int inputFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                    assertEquals("只读 Loading 不能抢占输入或拦截输入区域", inputFlags,
                            loading.getWindow().getAttributes().flags & inputFlags);
                    EditText input = page.findViewById(R.id.enrollment_search);
                    boolean hasFocus = input.hasFocus(), hasWindowFocus = input.hasWindowFocus();
                    boolean imeVisible = employeeSearchImeVisible(page);
                    loadingInputState.set("输入焦点=" + hasFocus + "，窗口焦点=" + hasWindowFocus
                            + "，系统输入法=" + imeVisible + "，读取提示=" + loading.isShowing());
                    if (!hasFocus || !hasWindowFocus || !imeVisible) {
                        loadingVisibleAt[0] = 0;
                        return false;
                    }
                    if (loadingVisibleAt[0] == 0) loadingVisibleAt[0] = SystemClock.elapsedRealtime();
                    return SystemClock.elapsedRealtime() - loadingVisibleAt[0] >= 300;
                }), "员工搜索 Loading 显示期间应持续保留原输入焦点和系统键盘");
            } catch (AssertionError error) {
                throw new AssertionError(error.getMessage() + "；" + loadingInputState.get(), error);
            }
        } finally { releaseStorage.countDown(); }
        storageBarrier.get(10, TimeUnit.SECONDS);
        awaitSelection("第 1 / 1 页 · 共 1 条", 1);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertEmployeeSearchInputActive(page);
            assertNull("读取结束应关闭 Loading", operationLoadingDialog(page));
            RecyclerView employees = page.findViewById(R.id.enrollment_staff_list);
            assertEquals("实际列表必须返回目标员工，不能只更新输入框", DETAIL_ID,
                    ((JSONObject) item(employees, 0)).optLong("id"));
            assertTrue(page.findViewById(R.id.enrollment_search_clear).performClick());
            assertEquals("清空无需确认应直接修改搜索框", "",
                    page.<EditText>findViewById(R.id.enrollment_search).getText().toString());
            return null;
        });
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertEmployeeSearchInputActive(page);
            InputMethodManager keyboard = (InputMethodManager) page.getSystemService(Context.INPUT_METHOD_SERVICE);
            assertNotNull(keyboard);
            keyboard.hideSoftInputFromWindow(page.findViewById(R.id.enrollment_search).getWindowToken(), 0);
            WindowCompat.getInsetsController(page.getWindow(), page.getWindow().getDecorView())
                    .hide(WindowInsetsCompat.Type.ime());
            page.findViewById(R.id.enrollment_search).clearFocus();
            return null;
        });
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null) return false;
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(page.getWindow().getDecorView());
            return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                    && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0;
        }), "搜索验收结束应收起系统输入法");
        assertEquals("仅实时搜索不能提交学习", 0, registerRequests.get());
        assertEquals("仅实时搜索不能删除样本", 0, deleteRequests.get());
    }

    @Test public void employeeSearchReleasesCameraAndReturnsToSelectedEmployee() throws Exception {
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        long selectedId = chooseEmployeeAt(2);
        awaitCapture(selectedId);
        CameraSource originalCamera = onMain(() -> resumed(FaceEnrollmentActivity.class).camera);
        assertNotNull("选人前应已建立采集相机", originalCamera);
        showEmployeeChooser();
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page.camera == null && page.latestFrame == null;
        }), "打开选人覆盖层应释放相机和旧帧");
        boolean captureVisible = onMain(() -> resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_capture_panel).isShown());
        boolean samplesVisible = onMain(() -> resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_samples_panel).isShown());
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            EditText input = page.findViewById(R.id.enrollment_search);
            assertTrue("员工搜索应使用系统输入法", input.getShowSoftInputOnFocus());
            input.setText(String.valueOf(DETAIL_ID));
            return null;
        });
        awaitSelection("第 1 / 1 页 · 共 1 条", 1);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertEmployeeSearchWorkspace(page, captureVisible, samplesVisible);
            assertTrue(page.findViewById(R.id.enrollment_staff_list).isShown());
            assertNull("隔离测试的不存在镜头不能产生真实采集帧", page.latestFrame);
            page.onSdkStateChanged();
            assertEmployeeSearchWorkspace(page, captureVisible, samplesVisible);
            assertTrue(page.findViewById(R.id.enrollment_search_clear).performClick());
            return null;
        });
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        showCaptureWorkspace();
        awaitCapture(selectedId);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertWorkspacePanels(page);
            assertTrue("返回采集后照片列表应恢复", page.findViewById(R.id.enrollment_samples_panel).isShown());
            assertNotSame("关闭选人覆盖层后应重新取得采集相机", originalCamera, page.camera);
            return null;
        });
        assertEquals("搜索和恢复镜头不能提交学习", 0, registerRequests.get());
        assertEquals("搜索和恢复镜头不能删除样本", 0, deleteRequests.get());
    }

    private void tapEmployeeSearch() {
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null || operationLoadingDialog(page) != null) return false;
            EditText input = page.findViewById(R.id.enrollment_search);
            Rect visible = new Rect();
            return input.isShown() && input.hasWindowFocus() && !input.isLayoutRequested()
                    && input.getWidth() > 0 && input.getHeight() > 0
                    && visibleOnScreen(input, visible) && visible.height() == input.getHeight();
        }), "初始读取结束后搜索框应有窗口焦点并完成布局");
        Rect target = onMain(() -> {
            EditText input = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_search);
            assertTrue("员工搜索应使用系统输入法", input.getShowSoftInputOnFocus());
            Rect visible = new Rect();
            assertTrue("员工搜索应有真实可点击区域", visibleOnScreen(input, visible));
            return visible;
        });
        // 由真实触摸建立输入连接，避免窗口刚恢复时直接请求 IME 被系统丢弃。
        long pressedAt = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(pressedAt, pressedAt, MotionEvent.ACTION_DOWN,
                target.exactCenterX(), target.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(pressedAt, pressedAt + 80, MotionEvent.ACTION_UP,
                target.exactCenterX(), target.exactCenterY(), 0);
        try {
            instrumentation.sendPointerSync(down);
            SystemClock.sleep(80);
            instrumentation.sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private boolean employeeSearchImeVisible(FaceEnrollmentActivity page) {
        if (page == null) return false;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(page.getWindow().getDecorView());
        return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
                && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0;
    }

    private void assertEmployeeSearchInputActive(FaceEnrollmentActivity page) {
        EditText input = page.findViewById(R.id.enrollment_search);
        assertTrue("读取员工时应保留原搜索框焦点", input.hasFocus());
        assertTrue("读取员工时不能将窗口焦点交给 Loading", input.hasWindowFocus());
        assertTrue("读取员工时系统输入法应持续可用", employeeSearchImeVisible(page));
    }

    private void assertEmployeeSearchWorkspace(FaceEnrollmentActivity page,
                                              boolean captureVisible, boolean samplesVisible) {
        assertWorkspacePanels(page);
        assertEquals("搜索不能改变采集面板可见性", captureVisible,
                page.findViewById(R.id.enrollment_capture_panel).isShown());
        assertEquals("搜索不能改变照片面板可见性", samplesVisible,
                page.findViewById(R.id.enrollment_samples_panel).isShown());
        // 选人覆盖层在所有尺寸都释放镜头，搜索期间不能重新占用采集资源。
        assertNull("选择员工时不能持有采集相机", page.camera);
        assertNull("选择员工时不能持有旧采集帧", page.latestFrame);
    }

    @Test public void chosenEmployeeAndSelectionContextSurviveRecreationAndChangingEmployee() throws Exception {
        seedStaff(55);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        search("后厨");
        awaitSelection("第 1 / 2 页 · 共 45 条", 24);
        click(FaceEnrollmentActivity.class, R.id.enrollment_next);
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        long selectedId = chooseEmployeeAt(3);
        awaitCapture(selectedId);
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        assertEmployeeColumns();
        assertSelectedRow(selectedId);
        scenario.recreate();
        awaitCapture(selectedId);
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        assertEmployeeColumns();
        assertSelectedRow(selectedId);
        AtomicReference<RecyclerView> previousList = new AtomicReference<>();
        AtomicReference<EditText> previousSearch = new AtomicReference<>();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertWorkspacePanels(page);
            previousList.set(page.findViewById(R.id.enrollment_staff_list));
            previousSearch.set(page.findViewById(R.id.enrollment_search));
            assertEquals("后厨", previousSearch.get().getText().toString());
            return null;
        });
        long replacementCandidate = onMain(() -> item(resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_staff_list), 4).optLong("id"));
        seedSampleFixture(replacementCandidate, 77901);
        long replacementId = chooseEmployeeAt(4);
        assertNotEquals("更换员工应允许实际选择另一名员工", selectedId, replacementId);
        awaitCapture(replacementId);
        awaitSelection("第 2 / 2 页 · 共 45 条", 45);
        assertSelectedRow(replacementId);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertWorkspacePanels(page);
            assertSame("同屏换人不能重建列表 View", previousList.get(), page.findViewById(R.id.enrollment_staff_list));
            assertSame("同屏换人不能重建搜索输入 View", previousSearch.get(), page.findViewById(R.id.enrollment_search));
            assertEquals("后厨", previousSearch.get().getText().toString());
            assertNull("不存在的镜头不能留下上一位员工画面", page.latestFrame);
            return null;
        });
        awaitSampleGrid(replacementId, new long[]{77901});
        assertSampleImageShown(77901);
        holdForScreenshot("workspace-selected");
    }

    @Test public void emptyRosterAndMissingExplicitIdNeverSelectArbitraryEmployee() throws Exception {
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 1 页 · 共 0 条", 0);
        assertSelectionWithoutCamera();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue(page.findViewById(R.id.enrollment_empty).isShown());
            page.findViewById(R.id.enrollment_capture).performClick();
            assertEquals(0, registerRequests.get());
            assertNull(page.camera);
            return null;
        });
        scenario.close();
        seedStaff(3);
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, 91999L));
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        assertSelectionWithoutCamera();
        assertEquals(0, registerRequests.get());
    }

    @Test
    public void captureLightsBeforeFirstFrameAndChooserOrCameraStopClosesIt() throws Exception {
        seedStaff(3);
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "录入页应连接隔离 SDK");
        flushLightWorker();
        assertEquals("未选择员工时不能开灯", 0, lightOnWrites.get());
        assertTrue("员工选择区域不能占用补光设备", enrollmentLightsClosed());

        long employeeId = chooseEmployeeAt(0);
        awaitCapture(employeeId);
        prepareSyntheticLightingCapture();
        await(this::enrollmentLampOn, "选人后应在首帧、人脸检测之前打开补光灯");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertNull("首帧尚未到达也应能补光", page.latestFrame);
            assertFalse("不能依赖人脸检测完成后才开灯", (Boolean) readField(page, "previewReceivedFrame"));
            return null;
        });
        int opened = lightOnWrites.get();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            page.onSdkStateChanged();
            page.onSdkStateChanged();
            for (int index = 0; index < 3; index++) {
                page.latestFrame = new CameraSource.Frame(rgbTestImage(2, 2), 2, 2);
                page.onCameraFrame(page.latestFrame);
            }
            return null;
        });
        await(() -> faceDetectionRequests.get() > 0 && onMain(() ->
                !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")), "隔离人脸预检应完成");
        flushLightWorker();
        assertEquals("连续画面与状态刷新不能重复发送同一配置的开灯命令", opened, lightOnWrites.get());

        int previousOff = lightOffWrites.get();
        showEmployeeChooser();
        await(() -> lightOffWrites.get() > previousOff && enrollmentLightsClosed(), "打开选人浮层应立即关灯并释放设备");
        assertNull(onMain(() -> readField(resumed(FaceEnrollmentActivity.class), "lighting")));
        showCaptureWorkspace();
        awaitCapture(employeeId);
        prepareSyntheticLightingCapture();
        await(() -> lightOnWrites.get() > opened && enrollmentLampOn(), "返回可采集区域后应恢复补光");

        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            page.stopCamera();
            page.onSdkStateChanged();
            return null;
        });
        await(this::enrollmentLightsClosed, "停止相机后即使收到 SDK 状态刷新也不能遗留亮灯");
        assertNull(onMain(() -> readField(resumed(FaceEnrollmentActivity.class), "lighting")));
    }

    @Test
    public void pauseCameraFailureAndSdkLossCloseLampAndCaptureRecoveryReopensIt() throws Exception {
        seedStaff(3);
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "录入页应连接隔离 SDK");
        prepareSyntheticLightingCapture();
        await(this::enrollmentLampOn, "可采集录入页应打开补光灯");
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        int opened = lightOnWrites.get();
        scenario.moveToState(Lifecycle.State.STARTED);
        await(this::enrollmentLightsClosed, "仅 onPause、尚未 onStop 时就必须关灯");
        onMain(() -> {
            assertTrue("此时仍未执行 onStop", page.foreground);
            assertNotNull("仅暂停不会借相机释放间接关灯", page.camera);
            assertNull("暂停必须主动释放灯光所有权", readField(page, "lighting"));
            page.onSdkStateChanged();
            return null;
        });
        flushLightWorker();
        assertEquals("暂停后的 SDK 刷新不能重新开灯", opened, lightOnWrites.get());
        scenario.moveToState(Lifecycle.State.RESUMED);
        await(() -> lightOnWrites.get() > opened && enrollmentLampOn(), "恢复交互且采集可用后应重新开灯");

        onMain(() -> {
            page.cameraError = "隔离回归：摄像头中断";
            page.onCameraError();
            return null;
        });
        await(this::enrollmentLightsClosed, "摄像头错误时应立即关灯");
        int beforeRecovery = lightOnWrites.get();
        onMain(() -> {
            page.cameraError = "";
            page.onSdkStateChanged();
            return null;
        });
        await(() -> lightOnWrites.get() > beforeRecovery && enrollmentLampOn(), "摄像头恢复可用后应重新开灯");
        onMain(() -> {
            app.sdkSession().configurationError("隔离回归：SDK 准入丢失");
            return null;
        });
        await(this::enrollmentLightsClosed, "失去 SDK 准入后应关闭补光灯");
        assertNull(onMain(() -> readField(page, "lighting")));
        int beforeSdkRecovery = lightOnWrites.get();
        onMain(() -> {
            app.sdkSession().connect(MorningSdkSettings.read(preferences));
            return null;
        });
        await(() -> onMain(() -> app.sdkSession().viewState().ready) && enrollmentLampOn()
                && lightOnWrites.get() > beforeSdkRecovery, "SDK 重新就绪后应恢复补光");

        scenario.moveToState(Lifecycle.State.CREATED);
        await(this::enrollmentLightsClosed, "后台停止必须关闭并释放全部补光设备");
        onMain(() -> {
            assertFalse(page.foreground);
            assertNull(page.camera);
            assertNull(readField(page, "lighting"));
            page.onSdkStateChanged();
            return null;
        });
        flushLightWorker();
        assertTrue("后台迟到状态不能重新开灯", enrollmentLightsClosed());
    }

    @Test public void enrollmentRequiresExactlyOneFaceAndExpiresWithoutNewCameraFrames() throws Exception {
        assertTrue(preferences.edit().putInt(AppFontSettings.KEY, 3).commit());
        openSyntheticDetectionWorkspace();
        for (int count : new int[]{0, 2, 3, 1}) {
            syntheticFaceCount = count;
            int requestCount = faceDetectionRequests.get();
            injectSyntheticDetectionFrame(2, 2);
            await(() -> faceDetectionRequests.get() > requestCount && onMain(() ->
                    !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")), "检测回执应结束单在途请求");
            await(() -> onMain(() -> {
                TextView status = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_preview_status);
                return status.getLayout() != null && !status.isLayoutRequested();
            }), "状态文案应完成本帧布局后再检查字号与裁切");
            onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                assertEquals("只有恰好一张脸可保存", count == 1,
                        page.findViewById(R.id.enrollment_capture).isEnabled());
                assertEquals("所有检测到的人脸都计数，不忽略背景脸", count,
                        ((Number) readField(page, "detectedFaces")).intValue());
                TextView status = page.findViewById(R.id.enrollment_preview_status);
                assertEquals("预览状态应区分无人、多人和可录入", count == 0 ? "未有人脸"
                        : count == 1 ? "可录入" : "多人入镜", status.getText().toString());
                int color = count == 0 ? R.color.mc_enrollment_status_warning
                        : count == 1 ? R.color.mc_enrollment_status_ready : R.color.mc_enrollment_status_danger;
                assertNotNull("预览状态应有可区分的背景颜色", status.getBackgroundTintList());
                assertEquals("预览状态颜色应随人脸检测结果切换", ContextCompat.getColor(page, color),
                        status.getBackgroundTintList().getDefaultColor());
                Rect previewBounds = new Rect(), statusBounds = new Rect();
                assertTrue(page.findViewById(R.id.enrollment_preview).getGlobalVisibleRect(previewBounds));
                assertTrue(status.getGlobalVisibleRect(statusBounds));
                assertEquals("130% 字体下状态栏宽度不能裁切", status.getWidth(), statusBounds.width());
                assertEquals("130% 字体下状态栏高度不能裁切", status.getHeight(), statusBounds.height());
                assertTrue("状态栏必须完整位于预览内部", previewBounds.contains(statusBounds));
                assertTrue("状态栏应靠预览右上角显示", previewBounds.right - statusBounds.right <= previewBounds.width() / 4
                        && statusBounds.top - previewBounds.top <= previewBounds.height() / 4);
                assertNotNull("状态文案应完成布局", status.getLayout());
                assertTrue("130% 字体下状态文案高度不能裁切", status.getLayout().getHeight()
                        <= status.getHeight() - status.getCompoundPaddingTop() - status.getCompoundPaddingBottom());
                for (int line = 0; line < status.getLayout().getLineCount(); line++) {
                    assertEquals("130% 字体下状态文案不能省略", 0, status.getLayout().getEllipsisCount(line));
                    assertTrue("130% 字体下状态文案宽度不能裁切", status.getLayout().getLineWidth(line)
                            <= status.getWidth() - status.getCompoundPaddingLeft() - status.getCompoundPaddingRight());
                }
                if (count != 1) page.findViewById(R.id.enrollment_capture).performClick();
                return null;
            });
        }
        await(() -> onMain(() -> !resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_capture).isEnabled()), "相机断流后旧单脸结果必须自动过期");
        onMain(() -> {
            TextView status = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_preview_status);
            assertNotEquals("检测结果过期后不能继续显示可录入", "可录入", status.getText().toString());
            return null;
        });
        assertEquals("预览与禁用按钮都不能自动学习", 0, registerRequests.get());
        assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
    }

    @Test public void finalCaptureDetectionRejectsSecondPersonWithoutCreatingUnknownWrite() throws Exception {
        openSyntheticDetectionWorkspace();
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        syntheticFaceCount = 2;
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        await(() -> faceDetectionRequests.get() == 2 && onMain(() ->
                !app.faceOperations.isWriting(FIRST_ID)), "保存复检发现第二人时必须释放预检占用");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            assertEquals("多人入镜", ((TextView) page.findViewById(R.id.enrollment_preview_status)).getText().toString());
            return null;
        });
        assertEquals("第二人出现时不能发送 register", 0, registerRequests.get());
        assertFalse("纯检测失败不能留下写入未知标记", employee(FIRST_ID).optBoolean("face_write_unknown"));
    }

    @Test public void confirmedSingleFaceRemainsClickableDuringPreviewAndQueuesFrozenRegistrationOnce() throws Exception {
        openSyntheticDetectionWorkspace();
        allowSyntheticRegistration = true;
        beginDelayedPreviewAfterConfirmedSingleFace();
        byte[] frozenRgb = markedRgb(71);
        pushSyntheticDetectionFrame(frozenRgb);
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        pushSyntheticDetectionFrame(markedRgb(119));
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue("等旧检测时必须锁定员工", app.faceOperations.isWriting(FIRST_ID));
            assertFalse("已受理的保存不能再次点击", page.findViewById(R.id.enrollment_capture).isEnabled());
            page.findViewById(R.id.enrollment_capture).performClick();
            return null;
        });
        assertEquals("等待已有检测完成，不能并发发送复检", 2, faceDetectionRequests.get());
        assertEquals(0, registerRequests.get());
        assertFalse("等检测时尚未进入未知写入", employee(FIRST_ID).optBoolean("face_write_unknown"));
        PendingRegistration preview = pendingFaceDetection.getAndSet(null);
        assertNotNull(preview);
        reply(preview.socket, preview.requestId, faceDetectionData(1));
        completeSyntheticRegistration(FIRST_ID, 0);
        assertEquals("初检、在途预览和最终复检各一次", 3, faceDetectionRequests.get());
        assertEquals("连点或后续帧均不能重复登记", 1, registerRequests.get());
        assertArrayEquals("最终复检必须使用点击时冻结的图，不是旧检测图或更新的预览图", frozenRgb, lastDetectedRgb);
        assertArrayEquals("登记必须使用点击时冻结的完整图", frozenRgb, registeredRgb);
        await(() -> !app.faceOperations.isWriting(FIRST_ID), "登记回执应释放原员工占用");
    }

    @Test public void queuedCaptureStillRejectsMultipleFacesInFinalFrozenFrameDetection() throws Exception {
        openSyntheticDetectionWorkspace();
        beginDelayedPreviewAfterConfirmedSingleFace();
        pushSyntheticDetectionFrame(markedRgb(72));
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        assertEquals(2, faceDetectionRequests.get());
        syntheticFaceCount = 2;
        PendingRegistration preview = pendingFaceDetection.getAndSet(null);
        reply(preview.socket, preview.requestId, faceDetectionData(1));
        await(() -> faceDetectionRequests.get() == 3 && onMain(() ->
                !app.faceOperations.isWriting(FIRST_ID)), "旧预览单脸不能跳过最终多人复检");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            assertEquals("多人入镜", ((TextView) page.findViewById(R.id.enrollment_preview_status)).getText().toString());
            return null;
        });
        assertEquals(0, registerRequests.get());
        assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
    }

    @Test public void previewNoFaceOrMultipleFacesImmediatelyRevokesPriorSingleFacePermission() throws Exception {
        openSyntheticDetectionWorkspace();
        for (int count : new int[]{0, 2}) {
            beginDelayedPreviewAfterConfirmedSingleFace();
            int expectedRequests = faceDetectionRequests.get();
            PendingRegistration preview = pendingFaceDetection.getAndSet(null);
            reply(preview.socket, preview.requestId, faceDetectionData(count));
            await(() -> onMain(() -> !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")),
                    "无人或多人预览回包应结束本轮检测");
            onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                assertFalse("旧单脸结果不能跨过无人或多人回包继续放行", page.findViewById(R.id.enrollment_capture).isEnabled());
                assertEquals(count, ((Number) readField(page, "detectedFaces")).intValue());
                page.findViewById(R.id.enrollment_capture).performClick();
                return null;
            });
            assertEquals(expectedRequests, faceDetectionRequests.get());
        }
        assertEquals(0, registerRequests.get());
    }

    @Test public void queuedCaptureIsCancelledWhenPreviousPreviewFindsNoFaceOrMultipleFaces() throws Exception {
        openSyntheticDetectionWorkspace();
        for (int count : new int[]{0, 2}) {
            beginDelayedPreviewAfterConfirmedSingleFace();
            int expectedRequests = faceDetectionRequests.get();
            click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
            assertTrue(app.faceOperations.isWriting(FIRST_ID));
            PendingRegistration preview = pendingFaceDetection.getAndSet(null);
            reply(preview.socket, preview.requestId, faceDetectionData(count));
            await(() -> onMain(() -> !app.faceOperations.isWriting(FIRST_ID)), "旧预览判定不安全应取消排队保存");
            onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
                assertEquals(count, ((Number) readField(page, "detectedFaces")).intValue());
                return null;
            });
            assertEquals("无人或多人旧回包不能继续发最终复检", expectedRequests, faceDetectionRequests.get());
            assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
        }
        assertEquals(0, registerRequests.get());
    }

    @Test public void pausingWhileCaptureWaitsForPreviewCancelsQueuedFinalDetection() throws Exception {
        openSyntheticDetectionWorkspace();
        beginDelayedPreviewAfterConfirmedSingleFace();
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        onMain(() -> { instrumentation.callActivityOnPause(page); return null; });
        try {
            assertFalse("尚未发复检也必须在onPause释放员工", app.faceOperations.isWriting(FIRST_ID));
            PendingRegistration preview = pendingFaceDetection.getAndSet(null);
            reply(preview.socket, preview.requestId, faceDetectionData(1));
            await(() -> onMain(() -> !(Boolean) readField(page, "detecting")), "旧预览回包仅释放在途位置");
            assertEquals("暂停后不能补发最终复检", 2, faceDetectionRequests.get());
            assertEquals(0, registerRequests.get());
            assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
            onMain(() -> {
                assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
                assertFalse((Boolean) readField(page, "resumed"));
                assertTrue(page.foreground);
                return null;
            });
        } finally {
            onMain(() -> { instrumentation.callActivityOnResume(page); return null; });
        }
    }

    @Test public void captureButtonRemainsVisibleInFixedEnrollmentActions() throws Exception {
        openSyntheticDetectionWorkspace();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            Rect capture = new Rect();
            assertTrue("固定拍照录入按钮应完整显示", visibleOnScreen(page.findViewById(R.id.enrollment_capture), capture));
            assertEquals(ScreenViewGeometry.bounds(page.findViewById(R.id.enrollment_capture)), capture);
            assertFalse("新版工作台不重复显示相机设置入口", page.findViewById(R.id.enrollment_camera_settings).isShown());
            return null;
        });
    }

    private void beginDelayedPreviewAfterConfirmedSingleFace() {
        syntheticFaceCount = 1;
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        delayedFaceDetectionAt = faceDetectionRequests.get() + 1;
        pushSyntheticDetectionFrame(markedRgb(35));
        await(() -> pendingFaceDetection.get() != null, "第二轮预览检测应仍在等待回执");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue((Boolean) readField(page, "detecting"));
            assertTrue("已有新鲜单脸确认时，下一轮检测不能使保存按钮闪烁禁用",
                    page.findViewById(R.id.enrollment_capture).isEnabled());
            return null;
        });
    }

    private void pushSyntheticDetectionFrame(byte[] rgb) {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            page.latestFrame = new CameraSource.Frame(rgb, 2, 2);
            page.onCameraFrame(page.latestFrame);
            return null;
        });
    }

    private static byte[] markedRgb(int marker) {
        byte[] rgb = rgbTestImage(2, 2);
        rgb[0] = (byte) marker;
        return rgb;
    }

    @Test public void delayedDetectionStaysSingleInFlightAndOldReplyCannotEnableCaptureAfterCameraChange() throws Exception {
        openSyntheticDetectionWorkspace();
        delayedFaceDetectionAt = 1;
        injectSyntheticDetectionFrame(2, 2);
        await(() -> pendingFaceDetection.get() != null, "应收到尚未返回的人脸检测");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            for (int i = 0; i < 5; i++) {
                page.latestFrame = new CameraSource.Frame(rgbTestImage(2, 2), 2, 2);
                page.onCameraFrame(page.latestFrame);
            }
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            page.stopCamera();
            return null;
        });
        assertEquals("新帧不能堆积并行检测", 1, faceDetectionRequests.get());
        PendingRegistration pending = pendingFaceDetection.getAndSet(null);
        reply(pending.socket, pending.requestId, faceDetectionData(1));
        await(() -> onMain(() -> !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")),
                "旧请求返回应释放单在途位置");
        onMain(() -> {
            assertFalse("旧相机结果不能恢复保存入口", resumed(FaceEnrollmentActivity.class)
                    .findViewById(R.id.enrollment_capture).isEnabled());
            return null;
        });
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        assertEquals(0, registerRequests.get());
    }

    @Test public void detectorFailureClearsPreviousSingleFacePermission() throws Exception {
        openSyntheticDetectionWorkspace();
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        faceDetectionCode = -3;
        injectSyntheticDetectionFrame(2, 2);
        await(() -> faceDetectionRequests.get() == 2 && onMain(() ->
                !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")), "错误回执应结束检测");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            assertEquals(-1, ((Number) readField(page, "detectedFaces")).intValue());
            return null;
        });
        assertEquals(0, registerRequests.get());
    }

    @Test public void cancelledFinalDetectionNeverCreatesUnknownWriteOrSendsLateRegistration() throws Exception {
        openSyntheticDetectionWorkspace();
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        delayedFaceDetectionAt = 2;
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        await(() -> pendingFaceDetection.get() != null, "保存前最终检测应正在等待回执");
        assertTrue("最终检测期间也要锁定员工防止切人", app.faceOperations.isWriting(FIRST_ID));
        assertFalse("尚未准备 register 时不能写入结果未知标记", employee(FIRST_ID).optBoolean("face_write_unknown"));
        onMain(() -> { resumed(FaceEnrollmentActivity.class).stopCamera(); return null; });
        assertFalse("相机切换应释放尚未发起写入的预检占用", app.faceOperations.isWriting(FIRST_ID));
        PendingRegistration pending = pendingFaceDetection.getAndSet(null);
        reply(pending.socket, pending.requestId, faceDetectionData(1));
        await(() -> onMain(() -> !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "detecting")),
                "取消后的迟到预检应完成释放");
        assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
        assertEquals("取消后返回单脸也不能补发 register", 0, registerRequests.get());
    }

    @Test public void pausedStartedPageCannotRegisterAfterLateFinalSingleFaceReply() throws Exception {
        openSyntheticDetectionWorkspace();
        injectSyntheticDetectionFrame(2, 2);
        awaitSingleFaceCapture();
        delayedFaceDetectionAt = 2;
        click(FaceEnrollmentActivity.class, R.id.enrollment_capture);
        await(() -> pendingFaceDetection.get() != null, "最终检测应正在等待回执");
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        // 桌面自由窗口会主动恢复顶层 Activity，直接派发回调以固定 onPause→onStop 窗口。
        onMain(() -> { instrumentation.callActivityOnPause(page); return null; });
        try {
            onMain(() -> {
                assertFalse((Boolean) readField(page, "resumed"));
                assertTrue("本例必须仍在 onStop 前验证", page.foreground);
                return null;
            });
            assertFalse("onPause 即释放尚未发送的预检，不等 onStop", app.faceOperations.isWriting(FIRST_ID));
            assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
            PendingRegistration pending = pendingFaceDetection.getAndSet(null);
            reply(pending.socket, pending.requestId, faceDetectionData(1));
            await(() -> onMain(() -> !(Boolean) readField(page, "detecting")), "暂停后迟到回执应仅释放检测占用");
            onMain(() -> {
                assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
                assertFalse((Boolean) readField(page, "resumed"));
                assertTrue(page.foreground);
                return null;
            });
            assertEquals("仅暂停尚未停止时也不能补发 register", 0, registerRequests.get());
            assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
        } finally {
            onMain(() -> { instrumentation.callActivityOnResume(page); return null; });
        }
    }

    @Test public void pausedStartedPageCannotRestoreCaptureFromLatePreviewDetection() throws Exception {
        openSyntheticDetectionWorkspace();
        delayedFaceDetectionAt = 1;
        injectSyntheticDetectionFrame(2, 2);
        await(() -> pendingFaceDetection.get() != null, "实时检测应正在等待回执");
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        onMain(() -> { instrumentation.callActivityOnPause(page); return null; });
        try {
            onMain(() -> {
                assertFalse((Boolean) readField(page, "resumed"));
                assertTrue("本例必须仍在 onStop 前验证", page.foreground);
                return null;
            });
            PendingRegistration pending = pendingFaceDetection.getAndSet(null);
            reply(pending.socket, pending.requestId, faceDetectionData(1));
            await(() -> onMain(() -> !(Boolean) readField(page, "detecting")), "暂停页迟到预览应释放检测占用");
            onMain(() -> {
                assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
                assertFalse((Boolean) readField(page, "resumed"));
                assertTrue(page.foreground);
                assertEquals("暂停前单脸回执不能恢复过期状态", -1,
                        ((Number) readField(page, "detectedFaces")).intValue());
                page.findViewById(R.id.enrollment_capture).performClick();
                return null;
            });
            assertEquals(1, faceDetectionRequests.get());
            assertEquals(0, registerRequests.get());
        } finally {
            onMain(() -> { instrumentation.callActivityOnResume(page); return null; });
        }
    }

    private void openSyntheticDetectionWorkspace() throws Exception {
        seedStaff(3);
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "本机 SDK 应就绪");
    }

    @Test public void delayedSyntheticRegistrationBlocksEmployeeChangeAndOnlyReleasesOriginalEmployee() throws Exception {
        seedStaff(55);
        allowSyntheticRegistration = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        click(FaceEnrollmentActivity.class, R.id.enrollment_next);
        awaitSelection("第 2 / 3 页 · 共 55 条", 48);
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "本机 SDK 应就绪");
        JSONObject otherBefore = app.io.submit(() -> testStore.staff(FIRST_ID + 1)).get(10, TimeUnit.SECONDS);
        showCaptureWorkspace();
        submitSyntheticRegistration();
        assertTrue("本机夹具应收到人工 register 帧", registerReceived.await(10, TimeUnit.SECONDS));
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue((Boolean) readField(page, "sending"));
            assertTrue(app.faceOperations.isWriting(FIRST_ID));
            assertFalse(app.faceOperations.isWriting(FIRST_ID + 1));
            assertInFlightWorkspaceGuard(page, FIRST_ID);
            return null;
        });
        PendingRegistration pending = pendingRegistration.getAndSet(null);
        assertNotNull(pending);
        assertEquals("声明必须使用所选员工的独立 SDK 标识", sdkPersonId(FIRST_ID), pending.personId);
        reply(pending.socket, pending.requestId, Jsons.obj("uid", 0));
        await(() -> onMain(() -> !app.faceOperations.isWriting(FIRST_ID)
                && !(Boolean) readField(resumed(FaceEnrollmentActivity.class), "sending")), "原员工写占用应随空人脸回执释放");
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        assertFalse(app.io.submit(() -> testStore.staff(FIRST_ID)).get(10, TimeUnit.SECONDS).optBoolean("face_write_unknown"));
        assertEquals("原员工回执不能写到另一位员工", otherBefore.toString(),
                app.io.submit(() -> testStore.staff(FIRST_ID + 1)).get(10, TimeUnit.SECONDS).toString());
        awaitSelection("第 2 / 3 页 · 共 55 条", 48);
        long replacement = chooseEmployeeAt(0);
        assertNotEquals(FIRST_ID, replacement);
        awaitCapture(replacement);
    }

    @Test public void standaloneRegistrationSuccessStaysOnUnifiedPageAndAllowsChoosingAnotherEmployee() throws Exception {
        seedStaff(3);
        allowSyntheticRegistration = true;
        launch(FaceEnrollmentActivity.class);
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        expectedRegisteredStaffId = chooseEmployeeAt(1);
        expectedRegisteredUid = 77001;
        awaitCapture(expectedRegisteredStaffId);
        submitSyntheticRegistration();
        completeSyntheticRegistration(expectedRegisteredStaffId, expectedRegisteredUid);
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && !page.isFinishing() && !app.faceOperations.isWriting(expectedRegisteredStaffId)
                    && page.findViewById(R.id.enrollment_staff_list).isEnabled()
                    && !((Boolean) readField(page, "sending"))
                    && ((JSONObject) readField(page, "person")).optBoolean("face_registered");
        }), "独立入口学习成功后应留在录入页并允许更换员工");
        assertRegisteredTestEmployee();
        awaitSampleGrid(expectedRegisteredStaffId, new long[]{expectedRegisteredUid});
        assertSampleImageShown(expectedRegisteredUid);
        holdForScreenshot("workspace-with-sample");
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        assertSelectedRow(expectedRegisteredStaffId);
        long another = chooseEmployeeAt(0);
        assertNotEquals(expectedRegisteredStaffId, another);
        awaitCapture(another);
        assertEquals("成功后更换员工不能自动重放 register", 1, registerRequests.get());
    }

    @Test public void recreationDuringRegistrationKeepsSharedWriteGuardUntilOriginalReplyCompletes() throws Exception {
        seedStaff(55);
        allowSyntheticRegistration = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSelection("第 1 / 3 页 · 共 55 条", 24);
        click(FaceEnrollmentActivity.class, R.id.enrollment_next);
        awaitSelection("第 2 / 3 页 · 共 55 条", 48);
        submitSyntheticRegistration();
        assertTrue("重建前人工 register 必须已经发送", registerReceived.await(10, TimeUnit.SECONDS));
        FaceEnrollmentActivity originalPage = onMain(() -> resumed(FaceEnrollmentActivity.class));
        JSONObject otherBefore = app.io.submit(() -> testStore.staff(FIRST_ID + 1)).get(10, TimeUnit.SECONDS);

        scenario.recreate();
        awaitCapture(FIRST_ID);
        awaitLockedEmployeePage("第 2 / 3 页 · 共 55 条", 48);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertNotSame("重建必须实际建立新 Activity", originalPage, page);
            assertTrue("旧页应已销毁，回执仍由原批次收尾", originalPage.isDestroyed());
            assertTrue("原员工的进程共享写锁必须跨过页面重建", app.faceOperations.isWriting(FIRST_ID));
            assertInFlightWorkspaceGuard(page, FIRST_ID);
            // 给新页一张新鲜人工帧，排除无画面禁用因素；共享锁仍须阻止重复提交。
            page.stopCamera();
            page.latestFrame = new CameraSource.Frame(new byte[]{1, 2, 3}, 1, 1);
            page.cameraError = "";
            page.onCameraFrame(page.latestFrame);
            assertFalse("有新鲜画面也不能重复采集同一在途员工", page.findViewById(R.id.enrollment_capture).isEnabled());
            page.findViewById(R.id.enrollment_capture).performClick();
            return null;
        });
        assertEquals("页面重建或程序点击不能重放 register", 1, registerRequests.get());

        completeSyntheticRegistration(FIRST_ID, 0);
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            JSONObject person = page == null ? null : (JSONObject) readField(page, "person");
            return page != null && !app.faceOperations.isWriting(FIRST_ID)
                    && person != null && !person.optBoolean("face_write_unknown")
                    && page.findViewById(R.id.enrollment_staff_list).isEnabled()
                    && page.findViewById(R.id.enrollment_search).isEnabled();
        }), "原回执收尾后重建页应刷新档案并恢复更换员工");
        assertFalse(app.io.submit(() -> testStore.staff(FIRST_ID)).get(10, TimeUnit.SECONDS).optBoolean("face_write_unknown"));
        assertEquals("原页迟到回执不能修改其他员工", otherBefore.toString(),
                app.io.submit(() -> testStore.staff(FIRST_ID + 1)).get(10, TimeUnit.SECONDS).toString());
        awaitSelection("第 2 / 3 页 · 共 55 条", 48);
        long replacement = chooseEmployeeAt(0);
        assertNotEquals(FIRST_ID, replacement);
        awaitCapture(replacement);
        assertEquals("原回执完成和重新选人不能追加 register", 1, registerRequests.get());
    }



    @Test public void deletingOneSampleRemovesOnlyThatUidAndKeepsOtherEmployee() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78001, 78002);
        seedSampleFixture(FIRST_ID + 1, 79001);
        allowSyntheticDelete = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78001, 78002});
        assertSampleImageShown(78001);
        assertSampleImageShown(78002);
        Bitmap retained = sampleBitmap(78002);
        int reads = detailRequests.get(), retainedDownloads = imageRequestCount(78002);
        JSONObject other = employee(FIRST_ID + 1);
        clickSampleDelete(78001);
        awaitSampleGrid(FIRST_ID, new long[]{78002});
        expectedLocalSamples.get(FIRST_ID).remove(78001L);
        assertEquals(1, deleteRequests.get());
        assertEquals(expectedLocalSamples.get(FIRST_ID), uidSet(employee(FIRST_ID).optJSONArray("face_uids")));
        assertEquals("单样本删除不能更新其他员工档案", other.toString(), employee(FIRST_ID + 1).toString());
        assertEquals(1, sdkSamples.get(sdkPersonId(FIRST_ID)).size());
        assertTrue(sdkSamples.get(sdkPersonId(FIRST_ID)).contains(78002L));
        assertTrue(sdkSamples.get(sdkPersonId(FIRST_ID + 1)).contains(79001L));
        assertSampleImageShown(78002);
        assertSame("单删不能重新解码仍保留的图片", retained, sampleBitmap(78002));
        assertEquals("单删成功直接更新已核对列表，不再读取整份 userDetail", reads, detailRequests.get());
        assertEquals("单删不能重新下载仍保留的图片", retainedDownloads, imageRequestCount(78002));
        assertOperationFinishedWithToast("人脸样本已删除", true);
    }



    @Test public void clearingCurrentEmployeeRemovesAllItsUidsAndKeepsOtherEmployee() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78101, 78102, 78103);
        seedSampleFixture(FIRST_ID + 1, 79101);
        allowSyntheticDelete = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78101, 78102, 78103});
        int reads = detailRequests.get();
        JSONObject other = employee(FIRST_ID + 1);
        click(FaceEnrollmentActivity.class, R.id.enrollment_faces_clear);
        awaitSampleGrid(FIRST_ID, new long[0]);
        expectedLocalSamples.remove(FIRST_ID);
        assertEquals(1, deleteRequests.get());
        assertFalse(employee(FIRST_ID).optBoolean("face_registered"));
        assertTrue(uidSet(employee(FIRST_ID).optJSONArray("face_uids")).isEmpty());
        assertEquals("清空只作用于当前员工", other.toString(), employee(FIRST_ID + 1).toString());
        assertEquals("整组删除必须传 SDK person_id，不传本地主键", sdkPersonId(FIRST_ID), lastForgottenPerson.get().longValue());
        assertTrue("列表核对必须传独立 SDK person_id", queriedPersonIds.contains(sdkPersonId(FIRST_ID)));
        assertNull(sdkSamples.get(sdkPersonId(FIRST_ID)));
        assertTrue(sdkSamples.get(sdkPersonId(FIRST_ID + 1)).contains(79101L));
        assertEquals("遗忘成功直接清空当前快照，不再发起整份核对", reads, detailRequests.get());
        assertOperationFinishedWithToast("该员工的人脸样本已清空", true);
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue(page.findViewById(R.id.enrollment_faces_empty).isShown());
            assertFalse(page.findViewById(R.id.enrollment_faces_clear).isEnabled());
            return null;
        });
    }

    @Test public void deletionKeepsVisibleImagesAndOneLoadingUntilReplyAndRejectsRepeatedActions() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78401, 78402);
        seedSampleFixture(FIRST_ID + 1, 79401);
        allowSyntheticDelete = true;
        delayDeletion = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78401, 78402});
        assertSampleImageShown(78401);
        assertSampleImageShown(78402);
        Bitmap firstImage = sampleBitmap(78401), secondImage = sampleBitmap(78402);
        int reads = detailRequests.get(), firstDownloads = imageRequestCount(78401), secondDownloads = imageRequestCount(78402);
        JSONObject original = employee(FIRST_ID), other = employee(FIRST_ID + 1);
        clickSampleDelete(78401);
        await(() -> pendingDeletion.get() != null, "直接点击删除应进入 SDK 请求，无须二次确认");
        Dialog loading = onMain(() -> operationLoadingDialog(resumed(FaceEnrollmentActivity.class)));
        assertNotNull("删除执行期间应显示唯一的 Loading 弹窗", loading);
        assertTrue(loading.isShowing());
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue(app.faceOperations.isWriting(FIRST_ID));
            GridView grid = page.findViewById(R.id.enrollment_faces);
            assertEquals("删除在途不能清空图片列表", 2, grid.getCount());
            View delete = grid.getAdapter().getView(0, null, grid).findViewById(R.id.face_sample_delete);
            assertFalse(delete.isEnabled());
            assertFalse(page.findViewById(R.id.enrollment_faces_clear).isEnabled());
            delete.performClick();
            page.findViewById(R.id.enrollment_faces_clear).performClick();
            page.findViewById(R.id.enrollment_faces_refresh).performClick();
            page.onSdkStateChanged();
            assertSame("状态回调与重复点击不能替换在途 Loading", loading, operationLoadingDialog(page));
            return null;
        });
        assertSame("在途单删仍显示原图，不闪空占位", firstImage, sampleBitmap(78401));
        assertSame(secondImage, sampleBitmap(78402));
        assertEquals("重复操作不得再次请求删除", 1, deleteRequests.get());
        assertEquals("删除期间不得并发全量核对", reads, detailRequests.get());
        PendingRegistration deletion = pendingDeletion.getAndSet(null);
        assertNotNull(deletion);
        sdkSamples.get(sdkPersonId(FIRST_ID)).remove(78401L);
        expectedLocalSamples.get(FIRST_ID).remove(78401L);
        reply(deletion.socket, deletion.requestId, new JSONObject());
        awaitSampleGrid(FIRST_ID, new long[]{78402});
        assertSame(secondImage, sampleBitmap(78402));
        assertEquals("请求结束不能重新下载旧图", secondDownloads, imageRequestCount(78402));
        assertEquals(firstDownloads, imageRequestCount(78401));
        assertEquals(reads, detailRequests.get());
        assertEquals(other.toString(), employee(FIRST_ID + 1).toString());
        assertTrue(original.optJSONArray("face_uids").length() > employee(FIRST_ID).optJSONArray("face_uids").length());
        assertOperationFinishedWithToast("人脸样本已删除", true);
        assertFalse("原 Loading 窗口应释放", loading.isShowing());
    }

    @Test public void capturedSamplePersistsImmediatelyAndLeavingDoesNotRegisterItAgain() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78401, 78402);
        allowSyntheticRegistration = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78401, 78402});
        assertSampleImageShown(78401);
        assertSampleImageShown(78402);
        Bitmap firstImage = sampleBitmap(78401), secondImage = sampleBitmap(78402);
        int reads = detailRequests.get(), firstDownloads = imageRequestCount(78401), secondDownloads = imageRequestCount(78402);
        submitSyntheticRegistration();
        assertTrue(registerReceived.await(10, TimeUnit.SECONDS));
        Dialog loading = onMain(() -> operationLoadingDialog(resumed(FaceEnrollmentActivity.class)));
        assertNotNull(loading);
        assertTrue(loading.isShowing());
        assertSame("拍照提交时不能清除已显示人脸", firstImage, sampleBitmap(78401));
        assertSame(secondImage, sampleBitmap(78402));
        completeSyntheticRegistration(FIRST_ID, 78403);
        awaitSampleGrid(FIRST_ID, new long[]{78401, 78402, 78403});
        assertSampleImageShown(78403);
        assertSame("新增仅插入新 UID，不重新解码既有人脸", firstImage, sampleBitmap(78401));
        assertSame(secondImage, sampleBitmap(78402));
        assertEquals("已核对快照拍照成功后不再整表查询", reads, detailRequests.get());
        assertEquals(firstDownloads, imageRequestCount(78401));
        assertEquals(secondDownloads, imageRequestCount(78402));
        assertOperationFinishedWithToast("人脸学习已保存", true);
        assertFalse(loading.isShowing());
        JSONObject saved = employee(FIRST_ID);
        assertTrue("拍照回执应立即保存员工录入状态", saved.optBoolean("face_registered"));
        assertEquals("新拍照片不需要额外保存按钮", 78403L, saved.optLong("face_uid"));
        assertEquals(expectedLocalSamples.get(FIRST_ID), uidSet(saved.optJSONArray("face_uids")));
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        onMain(() -> { page.onBackPressed(); return null; });
        await(() -> onMain(page::isFinishing), "录入已立即保存，返回即可退出工作台");
        assertEquals("拍照仅提交一次，离开工作台不能重放 register", 1, registerRequests.get());
        assertEquals(0, deleteRequests.get());
        assertEquals(saved.toString(), employee(FIRST_ID).toString());
    }

    @Test public void registrationWithoutVerifiedSamplesUsesOneFollowUpQueryAndKeepsItsLoading() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78411);
        allowSyntheticRegistration = true;
        detailResponseCode = -1;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        await(() -> detailRequests.get() > 0 && onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && !(Boolean) readField(page, "queryingFaces");
        }), "初次核对明确失败后仍允许人工采集");
        int reads = detailRequests.get();
        detailResponseCode = 0;
        delayedDetailPerson = sdkPersonId(FIRST_ID);
        submitSyntheticRegistration();
        assertTrue(registerReceived.await(10, TimeUnit.SECONDS));
        Dialog loading = onMain(() -> operationLoadingDialog(resumed(FaceEnrollmentActivity.class)));
        assertNotNull(loading);
        completeSyntheticRegistration(FIRST_ID, 78412);
        assertTrue("未核对快照写入成功后应补一次只读核对", detailReceived.await(10, TimeUnit.SECONDS));
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertSame("必要核对与学习沿用同一个 Loading 窗口", loading, operationLoadingDialog(page));
            assertTrue(loading.isShowing());
            return null;
        });
        assertEquals(reads + 1, detailRequests.get());
        delayedDetailPerson = 0;
        PendingRegistration pending = pendingDetail.getAndSet(null);
        assertNotNull(pending);
        reply(pending.socket, pending.requestId, sampleDetail(pending.personId));
        awaitSampleGrid(FIRST_ID, new long[]{78411, 78412});
        assertOperationFinishedWithToast("人脸学习已保存", true);
        assertEquals("必要核对完成后不得再次自触发 userDetail", reads + 1, detailRequests.get());
        assertFalse(loading.isShowing());
    }

    @Test public void registrationCompletesWhileStoppedAndRestoresSamplesWhenResumed() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78421);
        allowSyntheticRegistration = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78421});
        assertSampleImageShown(78421);
        submitSyntheticRegistration();
        assertTrue("后台前 register 必须已发出", registerReceived.await(10, TimeUnit.SECONDS));
        FaceEnrollmentActivity page = onMain(() -> resumed(FaceEnrollmentActivity.class));
        int reads = detailRequests.get();
        scenario.moveToState(Lifecycle.State.CREATED);
        try {
            onMain(() -> {
                assertFalse("用例必须实际进入已停止状态", page.foreground);
                assertNull("离开页面应关闭在途 Loading", operationLoadingDialog(page));
                assertTrue(app.faceOperations.isWriting(FIRST_ID));
                return null;
            });
            completeSyntheticRegistration(FIRST_ID, 78422);
            await(() -> onMain(() -> !app.faceOperations.isWriting(FIRST_ID)
                    && !(Boolean) readField(page, "sending")), "后台成功回执仍须保存并释放原员工占用");
            assertEquals(expectedLocalSamples.get(FIRST_ID), uidSet(employee(FIRST_ID).optJSONArray("face_uids")));
            onMain(() -> {
                assertFalse(page.foreground);
                assertNull("后台回执不得重开 Loading", operationLoadingDialog(page));
                return null;
            });
        } finally {
            scenario.moveToState(Lifecycle.State.RESUMED);
        }
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78421, 78422});
        assertSampleImageShown(78421);
        assertSampleImageShown(78422);
        onMain(() -> {
            assertSame("停止和恢复不应依赖重建 Activity 才恢复样本", page, resumed(FaceEnrollmentActivity.class));
            assertNull("恢复读取完成后不能遗留 Loading", operationLoadingDialog(page));
            return null;
        });
        assertTrue("停止时已释放图片，恢复前台须重新核对并恢复完整列表", detailRequests.get() > reads);
        assertEquals("恢复页面不能再次提交已成功的 register", 1, registerRequests.get());
        assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
    }

    @Test public void allSamplesStayInOneScrollableListAndOnlyVisibleThumbnailsDownload() throws Exception {
        seedStaff(3);
        long[] uids = new long[AppStore.DEFAULT_PAGE_SIZE * 2 + 3];
        for (int index = 0; index < uids.length; index++) uids[index] = 78501 + index;
        seedSampleFixture(FIRST_ID, uids);
        imageFixtures.put(uids[0], jpegFixture(1920, 1080));
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, uids);
        assertSampleImageShown(uids[0]);
        assertSampleListWithoutPagination(uids.length);
        saveEnrollmentScreenshot("face-samples");
        assertEquals("尚未滚到末尾时不应下载整份图片列表", 0,
                imageRequests.containsKey(uids[uids.length - 1]) ? imageRequests.get(uids[uids.length - 1]).get() : 0);
        onMain(() -> {
            GridView grid = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_faces);
            ImageView image = grid.getChildAt(0).findViewById(R.id.face_sample_image);
            Bitmap thumbnail = ((BitmapDrawable) image.getDrawable()).getBitmap();
            assertTrue("大图只能解码为最长边不超过480像素的缩略图",
                    Math.max(thumbnail.getWidth(), thumbnail.getHeight()) <= 480);
            grid.setSelection(uids.length - 1);
            // 滚动尚未布局时状态回调仍可到达；更新图片和按钮状态不能把末尾位置同步回旧视口。
            resumed(FaceEnrollmentActivity.class).onSdkStateChanged();
            return null;
        });
        assertSampleImageShown(uids[uids.length - 1]);
        saveEnrollmentScreenshot("face-samples-end");
        awaitSampleGrid(FIRST_ID, uids);
        instrumentation.waitForIdleSync();
        long[] viewport = onMain(() -> {
            GridView grid = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_faces);
            int first = grid.getFirstVisiblePosition();
            assertTrue("重建前样本列表应实际离开首行", first > 0);
            return new long[]{grid.getAdapter().getItemId(first),
                    grid.getChildAt(0).getTop() - grid.getPaddingTop()};
        });
        assertEquals("滚动图片只读取样本，不能删除或学习", 0, deleteRequests.get());
        scenario.recreate();
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, uids);
        assertSampleListWithoutPagination(uids.length);
        assertSampleImageShown(uids[uids.length - 1]);
        await(() -> onMain(() -> {
            GridView grid = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_faces);
            return !grid.isLayoutRequested() && grid.getChildCount() > 0
                    && grid.getAdapter().getItemId(grid.getFirstVisiblePosition()) == viewport[0]
                    && grid.getChildAt(0).getTop() - grid.getPaddingTop() == viewport[1];
        }), "重建后应自动恢复同一员工、同一设备的首个样本 UID 和行内偏移，不能由测试补滚动");
    }

    @Test public void staleSampleTileCannotDeleteAfterEmployeeChanges() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78601);
        seedSampleFixture(FIRST_ID + 1, 79601);
        allowSyntheticDelete = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78601});
        JSONObject original = employee(FIRST_ID);
        View oldDelete = sampleDeleteControl(78601);
        selectEmployeeById(FIRST_ID + 1);
        awaitCapture(FIRST_ID + 1);
        awaitSampleGrid(FIRST_ID + 1, new long[]{79601});
        onMain(() -> { oldDelete.performClick(); return null; });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        assertEquals("旧员工图片遗留的点击回调不能删除当前员工样本", 0, deleteRequests.get());
        assertEquals(original.toString(), employee(FIRST_ID).toString());
        assertEquals(expectedLocalSamples.get(FIRST_ID), sdkSamples.get(sdkPersonId(FIRST_ID)));
        assertEquals(expectedLocalSamples.get(FIRST_ID + 1), sdkSamples.get(sdkPersonId(FIRST_ID + 1)));
        assertSampleImageShown(79601);
    }

    @Test public void refreshInProgressRejectsSampleDeletionUsingPreviousVerification() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78701);
        allowSyntheticDelete = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78701});
        View oldDelete = sampleDeleteControl(78701);
        int reads = detailRequests.get();
        delayedDetailPerson = sdkPersonId(FIRST_ID);
        click(FaceEnrollmentActivity.class, R.id.enrollment_faces_refresh);
        assertTrue(detailReceived.await(10, TimeUnit.SECONDS));
        assertEquals(reads + 1, detailRequests.get());
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            oldDelete.performClick();
            page.findViewById(R.id.enrollment_faces_clear).performClick();
            return null;
        });
        assertEquals("核对在途不能沿用旧验证发送删除", 0, deleteRequests.get());
        delayedDetailPerson = 0;
        PendingRegistration pending = pendingDetail.getAndSet(null);
        assertNotNull(pending);
        reply(pending.socket, pending.requestId, sampleDetail(pending.personId));
        awaitSampleGrid(FIRST_ID, new long[]{78701});
        instrumentation.waitForIdleSync();
        assertEquals("核对结束不会自动重放被拒绝的删除", 0, deleteRequests.get());
        assertEquals(expectedLocalSamples.get(FIRST_ID), sdkSamples.get(sdkPersonId(FIRST_ID)));
        assertEquals(expectedLocalSamples.get(FIRST_ID), uidSet(employee(FIRST_ID).optJSONArray("face_uids")));
    }

    @Test public void busyToReadyStatusKeepsVerifiedSamplesWithoutQueryingOrDownloadingAgain() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78711, 78712);
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78711, 78712});
        assertSampleImageShown(78711);
        assertSampleImageShown(78712);
        Bitmap firstImage = sampleBitmap(78711), secondImage = sampleBitmap(78712);
        int reads = detailRequests.get(), firstDownloads = imageRequestCount(78711), secondDownloads = imageRequestCount(78712);
        onMain(() -> {
            JSONObject status = app.sdkSession().status();
            app.sdkSession().onStatus(Jsons.obj("sdk_state", "READY", "model_ready", true,
                    "certificate_valid", true, "device_id", TEST_DEVICE, "busy", true,
                    "busy_operation", "register"));
            assertTrue(app.sdkSession().status().optBoolean("busy"));
            app.sdkSession().onStatus(status);
            return null;
        });
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        awaitSampleGrid(FIRST_ID, new long[]{78711, 78712});
        assertEquals("同一连接从忙恢复就绪不能重复查询已核对样本", reads, detailRequests.get());
        assertSame(firstImage, sampleBitmap(78711));
        assertSame(secondImage, sampleBitmap(78712));
        assertEquals(firstDownloads, imageRequestCount(78711));
        assertEquals(secondDownloads, imageRequestCount(78712));
        assertNull("普通 SDK 状态回调不弹出读取 Loading", onMain(() -> operationLoadingDialog(resumed(FaceEnrollmentActivity.class))));
    }

    @Test public void rejectedAndUnknownDeletionKeepUidAndImageUntilSdkConfirmsActualState() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78201);
        seedSampleFixture(FIRST_ID + 1, 79201);
        allowSyntheticDelete = true;
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78201});
        assertSampleImageShown(78201);
        JSONObject other = employee(FIRST_ID + 1);
        for (int code : new int[]{-1, 40009}) {
            deleteResponseCode = code;
            int before = deleteRequests.get();
            int readsBefore = detailRequests.get();
            int downloadsBefore = imageRequestCount(78201);
            Bitmap retained = sampleBitmap(78201);
            clickSampleDelete(78201);
            await(() -> deleteRequests.get() == before + 1, "应收到隔离删除请求并返回测试错误");
            await(() -> onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                JSONObject person = page == null ? null : (JSONObject) readField(page, "person");
                return person != null && !(Boolean) readField(page, "sending")
                        && !(Boolean) readField(page, "personLoading") && !(Boolean) readField(page, "pageLoading")
                        && person.optBoolean("face_write_unknown") == (code == 40009);
            }), "失败收尾应保留未知写标记、释放在途占用并完成员工刷新");
            awaitSampleGrid(FIRST_ID, new long[]{78201});
            assertEquals("明确失败或未知回执都不能擅自清本地 UID", expectedLocalSamples.get(FIRST_ID),
                    uidSet(employee(FIRST_ID).optJSONArray("face_uids")));
            assertTrue(sdkSamples.get(sdkPersonId(FIRST_ID)).contains(78201L));
            assertSampleImageShown(78201);
            assertSame("失败回执也不清除已显示图片", retained, sampleBitmap(78201));
            assertEquals("失败不会重新下载原图片", downloadsBefore, imageRequestCount(78201));
            assertEquals("失败不会自动核对整份人脸样本", readsBefore, detailRequests.get());
            assertOperationFinishedWithToast(null, false);
            assertEquals(other.toString(), employee(FIRST_ID + 1).toString());
            if (code == 40009) {
                assertTrue("未知删除不能自动被当作失败解锁", employee(FIRST_ID).optBoolean("face_write_unknown"));
                assertEquals("未知写必须在原设备上核对", TEST_DEVICE, employee(FIRST_ID).optString("face_write_device"));
                assertEquals("未知写不能用自动 userDetail 覆盖未确定结果", readsBefore, detailRequests.get());
                onMain(() -> {
                    FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                    assertFalse("未知写应禁止继续采集", page.findViewById(R.id.enrollment_capture).isEnabled());
                    View clear = page.findViewById(R.id.enrollment_faces_clear);
                    assertFalse("未知写应禁止再次清空", clear.isEnabled());
                    GridView grid = page.findViewById(R.id.enrollment_faces);
                    View delete = grid.getAdapter().getView(0, null, grid).findViewById(R.id.face_sample_delete);
                    assertFalse("未知写应禁止再次单删", delete.isEnabled());
                    // 程序点击也不能越过禁用状态重放删除。
                    clear.performClick();
                    delete.performClick();
                    return null;
                });
                assertEquals("未知写不能重放删除", before + 1, deleteRequests.get());
                click(FaceEnrollmentActivity.class, R.id.enrollment_faces_refresh);
                await(() -> onMain(() -> {
                    FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                    JSONObject person = page == null ? null : (JSONObject) readField(page, "person");
                    return person != null && !person.optBoolean("face_write_unknown")
                            && page.findViewById(R.id.enrollment_faces_clear).isEnabled();
                }), "人工核对原设备回执后才允许解除未知写并恢复样本操作");
                awaitSampleGrid(FIRST_ID, new long[]{78201});
                assertSampleImageShown(78201);
                assertFalse(employee(FIRST_ID).optBoolean("face_write_unknown"));
                assertEquals("核对只读实际状态，不能重放删除", before + 1, deleteRequests.get());
                assertEquals(expectedLocalSamples.get(FIRST_ID), uidSet(employee(FIRST_ID).optJSONArray("face_uids")));
            }
        }
        assertEquals(2, deleteRequests.get());
    }

    @Test public void lateSampleQueryCannotReplaceImagesAfterSelectingAnotherEmployee() throws Exception {
        seedStaff(3);
        seedSampleFixture(FIRST_ID, 78301);
        seedSampleFixture(FIRST_ID + 1, 79301);
        scenario = ActivityScenario.launch(new Intent(app, FaceEnrollmentActivity.class)
                .putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, FIRST_ID));
        awaitCapture(FIRST_ID);
        awaitSampleGrid(FIRST_ID, new long[]{78301});
        JSONObject originalEmployee = employee(FIRST_ID);
        delayedDetailPerson = sdkPersonId(FIRST_ID);
        click(FaceEnrollmentActivity.class, R.id.enrollment_faces_refresh);
        assertTrue("刷新应在本机夹具等待回执", detailReceived.await(10, TimeUnit.SECONDS));
        awaitSelection("第 1 / 1 页 · 共 3 条", 3);
        selectEmployeeById(FIRST_ID + 1);
        awaitCapture(FIRST_ID + 1);
        awaitSampleGrid(FIRST_ID + 1, new long[]{79301});
        delayedDetailPerson = 0;
        PendingRegistration late = pendingDetail.getAndSet(null);
        assertNotNull(late);
        assertEquals(sdkPersonId(FIRST_ID), late.personId);
        reply(late.socket, late.requestId, Jsons.obj("uids", new JSONArray().put(79999),
                "samples", new JSONArray().put(Jsons.obj("uid", 79999, "image_url", imageUrl(79999)))));
        // 同一 WS 上的状态回执作为屏障，旧 userDetail 已交给 App 主线程后再读取档案。
        CountDownLatch barrier = new CountDownLatch(1);
        onMain(() -> { resumed(FaceEnrollmentActivity.class).sdk.refreshStatus((c, m, d) -> barrier.countDown()); return null; });
        assertTrue(barrier.await(10, TimeUnit.SECONDS));
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        awaitSampleGrid(FIRST_ID + 1, new long[]{79301});
        assertSampleImageShown(79301);
        assertEquals("换人后的迟到查询不能改写原员工的已确认样本", originalEmployee.toString(), employee(FIRST_ID).toString());
        assertEquals(0, imageRequests.containsKey(79999L) ? imageRequests.get(79999L).get() : 0);
        assertEquals(expectedLocalSamples.get(FIRST_ID + 1), uidSet(employee(FIRST_ID + 1).optJSONArray("face_uids")));
    }















    private void saveEnrollmentScreenshot(String name) throws Exception {
        if ("face-samples".equals(name) || "face-samples-end".equals(name)) {
            await(() -> onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                GridView grid = page.findViewById(R.id.enrollment_faces);
                if (grid.getChildCount() == 0) return false;
                for (int index = 0; index < grid.getChildCount(); index++) {
                    ImageView thumbnail = grid.getChildAt(index).findViewById(R.id.face_sample_image);
                    if (thumbnail.getDrawable() == null) return false;
                }
                return true;
            }), "截图前等待可见夹具缩略图完成");
        }
        instrumentation.waitForIdleSync();
        // 等待窗口尺寸提交到 SurfaceFlinger，避免确认框截图仍处于旧位置。
        SystemClock.sleep(300);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File folder = new File(app.getExternalFilesDir(null), "design-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
    }



    private void collectVisibleDetailText(View view, List<String> texts) {
        if (!view.isShown()) return;
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectVisibleDetailText(group.getChildAt(i), texts);
        }
    }

    private void seedSampleFixture(long staffId, long... uids) throws Exception {
        Set<Long> expected = new ConcurrentSkipListSet<>();
        JSONArray values = new JSONArray();
        for (long uid : uids) {
            expected.add(uid);
            values.put(uid);
            imageFixtures.put(uid, jpegFixture());
        }
        sdkSamples.put(sdkPersonId(staffId), new ConcurrentSkipListSet<>(expected));
        expectedLocalSamples.put(staffId, expected);
        app.io.submit(() -> testStore.patchStaff(staffId, Jsons.obj("face_registered", uids.length > 0,
                "face_uids", values, "face_uid", uids.length == 0 ? 0 : uids[uids.length - 1]))).get(10, TimeUnit.SECONDS);
    }

    private JSONObject employee(long id) throws Exception {
        return app.io.submit(() -> testStore.staff(id)).get(10, TimeUnit.SECONDS);
    }

    private void selectEmployeeById(long id) {
        showEmployeeChooser();
        int target = onMain(() -> {
            RecyclerView list = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            assertTrue(list.isEnabled());
            for (int position = 0; position < count(list); position++)
                if (((JSONObject) item(list, position)).optLong("id") == id) {
                    return position;
                }
            throw new AssertionError("当前页没有需要选择的测试员工");
        });
        assertEquals(id, chooseEmployeeAt(target));
    }

    private void awaitSampleGrid(long staffId, long[] expected) {
        showCaptureWorkspace();
        Set<Long> uids = new ConcurrentSkipListSet<>();
        for (long uid : expected) uids.add(uid);
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null || ((Number) readField(page, "staffId")).longValue() != staffId
                    || app.faceOperations.isWriting(staffId)) return false;
            GridView grid = page.findViewById(R.id.enrollment_faces);
            if (grid == null || grid.getCount() != expected.length || !page.findViewById(R.id.enrollment_faces_refresh).isEnabled()) return false;
            Set<Long> shown = new ConcurrentSkipListSet<>();
            for (int position = 0; position < grid.getCount(); position++) shown.add(grid.getAdapter().getItemId(position));
            return uids.equals(shown) && !((TextView) page.findViewById(R.id.enrollment_faces_info))
                    .getText().toString().contains("核对");
        }), "样本区域应只展示当前员工经 SDK 确认的 UID：" + uids);
    }

    private void assertSampleImageShown(long uid) {
        try {
            await(() -> onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                GridView grid = page.findViewById(R.id.enrollment_faces);
                for (int position = 0; position < grid.getChildCount(); position++) {
                    View tile = grid.getChildAt(position);
                    int absolutePosition = grid.getFirstVisiblePosition() + position;
                    // UID 只作为测试夹具的内部删除/图片键；界面按当前列表位置显示“人脸 N”。
                    if (grid.getAdapter().getItemId(absolutePosition) != uid) continue;
                    TextView label = tile.findViewById(R.id.face_sample_uid);
                    if (!("人脸" + (absolutePosition + 1)).contentEquals(label.getText())) continue;
                    ImageView image = tile.findViewById(R.id.face_sample_image);
                    return tile.isShown() && image.getDrawable() != null
                            && tile.findViewById(R.id.face_sample_missing).getVisibility() == View.GONE;
                }
                return false;
            }), "样本 URL 对应的有效 JPEG 应在当前工作台显示：" + uid);
            assertTrue("图片必须来自本机 SDK 返回的 mock URL",
                    imageRequests.containsKey(uid) && imageRequests.get(uid).get() > 0);
        } catch (AssertionError failure) {
            String diagnostics = onMain(() -> {
                StringBuilder value = new StringBuilder("图片显示失败现场：目标 UID ").append(uid)
                        .append("，请求数=").append(imageRequests.containsKey(uid) ? imageRequests.get(uid).get() : 0);
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                if (page == null) return value.append("，没有前台人脸录入页").toString();
                for (String field : new String[]{"staffId", "selectionVersion", "sampleReadVersion",
                        "sampleDecodeVersion", "sampleImageLoadingPosition", "queryingFaces", "decodingFaces",
                        "sampleDevice", "verifiedGeneration", "verifiedVersion", "sampleScrollStaffId",
                        "sampleScrollDevice", "sampleScrollUid", "sampleScrollPosition", "sampleScrollTop",
                        "sampleScrollPending"})
                    value.append('\n').append(field).append('=').append(readField(page, field));
                value.append("\n前台=").append(page.foreground).append("，已销毁=").append(page.destroyed)
                        .append("，SDK代次=").append(app.sdkSession().generation());
                Object batch = readField(page, "sampleImageBatch");
                value.append("\n图片批次=").append(batch == null ? "无" : "存在");
                if (batch != null) for (String field : new String[]{"employee", "selection", "generation", "device", "version"}) {
                    Field reflected = batch.getClass().getDeclaredField(field);
                    reflected.setAccessible(true);
                    value.append(' ').append(field).append('=').append(reflected.get(batch));
                }
                GridView grid = page.findViewById(R.id.enrollment_faces);
                android.graphics.Rect visible = new android.graphics.Rect();
                visibleOnScreen(grid, visible);
                value.append("\n网格=").append(grid.getWidth()).append('x').append(grid.getHeight())
                        .append("，全局可见=").append(visible).append("，首项=").append(grid.getFirstVisiblePosition())
                        .append("，末项=").append(grid.getLastVisiblePosition()).append("，选中=").append(grid.getSelectedItemPosition())
                        .append("，总数=").append(grid.getCount()).append("，子项数=").append(grid.getChildCount())
                        .append("，已布局=").append(grid.isLaidOut()).append("，等待布局=").append(grid.isLayoutRequested())
                        .append("，栏数=").append(grid.getNumColumns());
                for (int position = 0; position < grid.getChildCount(); position++) {
                    View tile = grid.getChildAt(position);
                    TextView label = tile.findViewById(R.id.face_sample_uid);
                    ImageView image = tile.findViewById(R.id.face_sample_image);
                    TextView missing = tile.findViewById(R.id.face_sample_missing);
                    android.graphics.Rect tileVisible = new android.graphics.Rect();
                    visibleOnScreen(tile, tileVisible);
                    value.append("\n可见子项 ").append(position).append(' ').append(label.getText())
                            .append("，顶部=").append(tile.getTop()).append("，底部=").append(tile.getBottom())
                            .append("，全局可见=").append(tileVisible).append("，shown=").append(tile.isShown())
                            .append("，drawable=").append(image.getDrawable() != null)
                            .append("，缺图层=").append(missing.getVisibility()).append(' ').append(missing.getText());
                }
                return value.toString();
            });
            Bundle status = new Bundle();
            status.putString("screenshot_ready", "true");
            status.putString("scene", "sample-image-failure");
            status.putString("uid", Long.toString(uid));
            status.putString("stream", diagnostics + "\n");
            instrumentation.sendStatus(2, status);
            // 主机可用 sampleFailureHoldMs 留出失败现场截图时间；默认不额外等待，也不改变原断言。
            long hold = Long.parseLong(InstrumentationRegistry.getArguments().getString("sampleFailureHoldMs", "0"));
            if (hold > 0 && hold <= 10_000) SystemClock.sleep(hold);
            throw new AssertionError(failure.getMessage() + "\n" + diagnostics, failure);
        }
    }

    private void clickSampleDelete(long uid) {
        View delete = sampleDeleteControl(uid);
        onMain(() -> {
            assertTrue(delete.isEnabled());
            assertTrue(delete.performClick());
            return null;
        });
    }

    private View sampleDeleteControl(long uid) {
        return onMain(() -> {
            GridView grid = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_faces);
            for (int position = 0; position < grid.getCount(); position++)
                if (grid.getAdapter().getItemId(position) == uid) {
                    View tile = grid.getAdapter().getView(position, null, grid);
                    assertEquals("删除入口应显示列表序号，SDK UID 保持内部使用", "人脸" + (position + 1),
                            ((TextView) tile.findViewById(R.id.face_sample_uid)).getText().toString());
                    View delete = tile.findViewById(R.id.face_sample_delete);
                    return delete;
                }
            throw new AssertionError("样本列表没有指定 UID");
        });
    }

    private Bitmap sampleBitmap(long uid) {
        return onMain(() -> {
            GridView grid = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_faces);
            for (int index = 0; index < grid.getChildCount(); index++) {
                int position = grid.getFirstVisiblePosition() + index;
                if (grid.getAdapter().getItemId(position) != uid) continue;
                ImageView image = grid.getChildAt(index).findViewById(R.id.face_sample_image);
                assertTrue("已显示图片应持有解码后的 Bitmap", image.getDrawable() instanceof BitmapDrawable);
                return ((BitmapDrawable) image.getDrawable()).getBitmap();
            }
            throw new AssertionError("可见人脸列表没有指定 UID：" + uid);
        });
    }

    private int imageRequestCount(long uid) {
        AtomicInteger count = imageRequests.get(uid);
        return count == null ? 0 : count.get();
    }

    private Dialog operationLoadingDialog(FaceEnrollmentActivity page) {
        Object loading = readField(page, "operationLoading");
        if (loading == null) return null;
        try {
            Field field = LoadingDialog.class.getDeclaredField("dialog");
            field.setAccessible(true);
            return (Dialog) field.get(loading);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void assertOperationFinishedWithToast(String message, boolean success) {
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null || operationLoadingDialog(page) != null || ToastEx.toast == null
                    || ToastEx.toast.getView() == null) return false;
            TextView text = ToastEx.toast.getView().findViewById(R.id.toast_text);
            return text != null && text.length() > 0 && (message == null || message.contentEquals(text.getText()));
        }), "操作结束应关闭 Loading，并通过 Toast 反馈结果：" + message);
        onMain(() -> {
            assertEquals("成功使用短 Toast，错误使用失败类型 Toast", success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG,
                    ToastEx.toast.getDuration());
            return null;
        });
    }

    private void assertSampleListWithoutPagination(int count) {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            GridView grid = page.findViewById(R.id.enrollment_faces);
            assertEquals("图片适配器必须包含全部样本", count, grid.getCount());
            TextView info = page.findViewById(R.id.enrollment_faces_info);
            assertEquals("已录入图片", info.getText().toString());
            assertTrue(info.getContentDescription().toString().contains("共 " + count + " 张"));
            assertFalse("图片区域只显示总张数，不显示页数", info.getText().toString().contains("页"));
            for (String name : new String[]{"enrollment_faces_previous", "enrollment_faces_next"}) {
                int id = page.getResources().getIdentifier(name, "id", page.getPackageName());
                assertTrue("图片列表必须移除翻页按钮", id == 0 || page.findViewById(id) == null);
            }
            assertTrue("工作台保留更换员工入口", page.findViewById(R.id.enrollment_choose_staff).isShown());
            assertEquals(page.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 3 : 4,
                    grid.getNumColumns());
            return null;
        });
    }

    private void submitSyntheticRegistration() {
        submitSyntheticRegistration(1, 1);
    }

    private void submitSyntheticRegistration(int width, int height) {
        showCaptureWorkspace();
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "人工帧只能发给已就绪的本机 SDK");
        injectSyntheticDetectionFrame(width, height);
        awaitSingleFaceCapture();
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertTrue(page.findViewById(R.id.enrollment_capture).isEnabled());
            assertTrue(page.findViewById(R.id.enrollment_capture).performClick());
            return null;
        });
    }

    private void prepareSyntheticLightingCapture() {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertNotNull(page);
            assertNotNull("选人后必须已建立采集预览宿主", page.findViewById(R.id.camera_texture));
            // 不启动相机，保留可采集宿主；代次隔离旧的不存在 USB 镜头错误回调。
            page.cameraGeneration++;
            if (page.camera != null) page.camera.close();
            TextureView preview = page.findViewById(R.id.camera_texture);
            page.camera = new CameraSource(page, preview, new CameraSource.Listener() {
                @Override
                public void onFrame(CameraSource.Frame frame) {
                    throw new AssertionError("未启动的隔离相机不应产生真实画面");
                }

                @Override
                public void onError(String message) {
                    serverFailure.compareAndSet(null, new AssertionError("未启动的隔离相机不应访问设备：" + message));
                }
            });
            page.latestFrame = null;
            page.cameraError = "";
            page.onSdkStateChanged();
            return null;
        });
    }

    private boolean enrollmentLampOn() {
        for (FakeEnrollmentLight device : lightDevices)
            if (!device.closed.get() && device.on) return true;
        return false;
    }

    private boolean enrollmentLightsClosed() {
        for (FakeEnrollmentLight device : lightDevices)
            if (!device.closed.get() || device.on) return false;
        return true;
    }

    private void flushLightWorker() {
        try {
            if (lightWorker != null) lightWorker.submit(() -> null).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new AssertionError("人脸录入假灯 IO 队列应完成当前指令", error);
        }
        instrumentation.waitForIdleSync();
    }

    private static Field lightControllerField() throws ReflectiveOperationException {
        Field field = LightController.class.getDeclaredField("instance");
        field.setAccessible(true);
        return field;
    }

    private final class FakeEnrollmentLight implements LightController.Device {
        final AtomicBoolean closed = new AtomicBoolean();
        volatile boolean on;

        @Override
        public void setOn(boolean value) throws IOException {
            if (closed.get()) {
                IOException error = new IOException("已关闭的人脸录入假灯不能再次写入");
                serverFailure.compareAndSet(null, error);
                throw error;
            }
            on = value;
            if (value) lightOnWrites.incrementAndGet();
            else lightOffWrites.incrementAndGet();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private void injectSyntheticDetectionFrame(int width, int height) {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            page.stopCamera();
            page.latestFrame = new CameraSource.Frame(rgbTestImage(width, height), width, height);
            page.cameraError = "";
            page.onCameraFrame(page.latestFrame);
            return null;
        });
    }

    private void awaitSingleFaceCapture() {
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && page.findViewById(R.id.enrollment_capture).isEnabled();
        }), "实时检测确认恰好一张脸后才允许保存");
    }

    private void completeSyntheticRegistration(long staffId, long uid) throws Exception {
        assertTrue("本机夹具应收到人工 register 帧", registerReceived.await(10, TimeUnit.SECONDS));
        PendingRegistration pending = pendingRegistration.getAndSet(null);
        assertNotNull(pending);
        assertEquals("成功回执必须使用提交员工的 SDK 标识", sdkPersonId(staffId), pending.personId);
        if (uid > 0) {
            sdkSamples.computeIfAbsent(sdkPersonId(staffId), key -> new ConcurrentSkipListSet<>()).add(uid);
            expectedLocalSamples.computeIfAbsent(staffId, key -> new ConcurrentSkipListSet<>()).add(uid);
            imageFixtures.put(uid, jpegFixture());
        }
        reply(pending.socket, pending.requestId, Jsons.obj("uid", uid,
                "image_url", uid > 0 ? imageUrl(uid) : ""));
    }

    private void assertRegisteredTestEmployee() throws Exception {
        JSONObject employee = app.io.submit(() -> testStore.staff(expectedRegisteredStaffId)).get(10, TimeUnit.SECONDS);
        assertTrue(employee.optBoolean("face_registered"));
        assertFalse(employee.optBoolean("face_write_unknown"));
        assertEquals(expectedRegisteredUid, employee.optLong("face_uid"));
        assertEquals(1, employee.getJSONArray("face_uids").length());
        assertEquals(expectedRegisteredUid, employee.getJSONArray("face_uids").getLong(0));
    }

    private void seedStaff(int count) throws Exception {
        app.io.submit(() -> {
            for (int index = 1; index <= count; index++) {
                long localId = FIRST_ID + index - 1;
                testStore.saveStaff(Jsons.obj(
                    "id", FIRST_ID + index - 1, "name", "测试员工 " + index,
                    "staff_no", "S" + (FIRST_ID + index - 1),
                    "department", index <= 45 ? "后厨" : "仓储", "enabled", true,
                    "created_at", index, "face_registered", false,
                    "face_write_unknown", false, "face_write_attempt", ""));
                long sdkId = testStore.staff(localId).optLong("person_id");
                assertTrue("存储层必须生成正数 SDK 标识", sdkId > 0);
                assertNotEquals("测试需明确区分 SDK 标识与本地主键", localId, sdkId);
                sdkPersonIds.put(localId, sdkId);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private long sdkPersonId(long localId) {
        Long value = sdkPersonIds.get(localId);
        if (value == null) throw new AssertionError("测试员工尚未读取持久化 SDK 标识：" + localId);
        return value;
    }

    /** 默认不等待；主机显式要求时提供截图窗口，不在设备写照片。 */
    private void holdForScreenshot(String scene) {
        long duration = Long.parseLong(InstrumentationRegistry.getArguments().getString("previewHoldMs", "0"));
        assertTrue("截图等待必须在 0 到 10000 毫秒之间", duration >= 0 && duration <= 10_000);
        if (duration == 0) return;
        if ("workspace-selected".equals(scene)) {
            // 截图时将当前选中行滚入视口，不改变员工选择或分页条件。
            onMain(() -> {
                FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
                RecyclerView list = page.findViewById(R.id.enrollment_staff_list);
                long selectedId = (Long) readField(page, "staffId");
                for (int i = 0; i < count(list); i++) {
                    if (id(list, i) == selectedId) {
                        scroll(list, i, 0);
                        break;
                    }
                }
                return null;
            });
            instrumentation.waitForIdleSync();
        }
        Bundle status = new Bundle();
        status.putString("screenshot_ready", "true");
        status.putString("scene", scene);
        instrumentation.sendStatus(2, status);
        SystemClock.sleep(duration);
    }

    private <T extends BaseMorningActivity> void launch(Class<T> type) {
        scenario = ActivityScenario.launch(new Intent(app, type));
    }

    private void search(String keyword) {
        showEmployeeChooser();
        onMain(() -> { ((EditText) resumed(FaceEnrollmentActivity.class)
                .findViewById(R.id.enrollment_search)).setText(keyword); return null; });
    }

    private void assertEmployeeColumns() {
        showEmployeeChooser();
        await(() -> onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            return employees.isLaidOut() && !employees.isLayoutRequested() && employees.getChildCount() >= 2
                    && employees.getChildAt(0).getWidth() > 0 && employees.getChildAt(1).getWidth() > 0;
        }), "员工网格应完成至少两名员工的实际布局");
        onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            boolean landscape = employees.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            assertEquals("员工网格横屏三列、竖屏两列", landscape ? 3 : 2,
                    ((GridLayoutManager) employees.getLayoutManager()).getSpanCount());
            View first = employees.getChildAt(0), second = employees.getChildAt(1);
            int firstPosition = first(employees);
            assertNotEquals("同排两列必须绑定不同员工", id(employees, firstPosition),
                    id(employees, firstPosition + 1));
            assertEquals("前两名可见员工应处于同一排", first.getTop(), second.getTop());
            assertTrue("两列员工卡片应横向分开，不能互相覆盖",
                    first.getRight() <= second.getLeft() || second.getRight() <= first.getLeft());
            assertTrue("第二列员工必须位于网格内", second.getLeft() >= employees.getPaddingLeft()
                    && second.getRight() <= employees.getWidth() - employees.getPaddingRight());
            assertTrue("员工区域必须至少容纳一整行员工卡片", employees.getHeight()
                    >= Math.max(first.getHeight(), second.getHeight()));
            return null;
        });
    }

    private int[] employeeViewport() {
        showEmployeeChooser();
        await(() -> onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            return !employees.isLayoutRequested() && first(employees) >= 0 && employees.getChildCount() > 0;
        }), "员工视口应完成选择区的滚动恢复");
        return onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            assertNotNull("滚动位置验收应有实际可见的员工卡片", employees.getChildAt(0));
            return new int[]{first(employees), employees.getChildAt(0).getTop()};
        });
    }

    /** 使用第二列卡片真实触点，验证屏幕位置与员工 ID 的对应关系。 */
    private long tapVisibleSecondColumnEmployee() {
        Rect target = new Rect();
        long id = onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            assertTrue(employees.isEnabled());
            View second = employees.getChildAt(1);
            assertTrue("第二列卡片应有可点击的屏幕区域", visibleOnScreen(second, target));
            assertFalse(target.isEmpty());
            return id(employees, first(employees) + 1);
        });
        long pressedAt = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(pressedAt, pressedAt, MotionEvent.ACTION_DOWN,
                target.exactCenterX(), target.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(pressedAt, pressedAt + 80, MotionEvent.ACTION_UP,
                target.exactCenterX(), target.exactCenterY(), 0);
        try {
            instrumentation.sendPointerSync(down);
            SystemClock.sleep(80);
            instrumentation.sendPointerSync(up);
        } finally {
            down.recycle();
            up.recycle();
        }
        // 先确认触点已经选人，再切区，避免测试提前隐藏列表。
        await(() -> onMain(() -> ((Number) readField(resumed(FaceEnrollmentActivity.class),
                "staffId")).longValue() == id), "第二列真实触点应选择员工：" + id);
        return id;
    }

    private void assertVisibleSelectedEmployee(long selectedId) {
        showEmployeeChooser();
        onMain(() -> {
            RecyclerView employees = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            boolean selectedVisible = false;
            for (int index = 0; index < employees.getChildCount(); index++) {
                int position = employees.getChildAdapterPosition(employees.getChildAt(index));
                if (position == RecyclerView.NO_POSITION) continue;
                boolean selected = id(employees, position) == selectedId;
                selectedVisible |= selected;
                assertEquals("已显示员工卡片的高亮必须直接跟随本次选择", selected,
                        employees.getChildAt(index).isActivated());
            }
            assertTrue("触点选择的员工必须保持在视口内", selectedVisible);
            return null;
        });
    }

    private long chooseEmployeeAt(int position) {
        showEmployeeChooser();
        onMain(() -> {
            RecyclerView list = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            if (row(list, position) == null) scroll(list, position, 0);
            return null;
        });
        await(() -> onMain(() -> {
            RecyclerView list = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            return !list.isLayoutRequested() && row(list, position) != null;
        }), "目标员工卡片应实际绑定到选择区");
        return onMain(() -> {
            RecyclerView list = resumed(FaceEnrollmentActivity.class).findViewById(R.id.enrollment_staff_list);
            assertTrue(list.isEnabled());
            JSONObject person = (JSONObject) item(list, position);
            long id = person.optLong("id");
            assertTrue(RecyclerListTestHelper.click(list, position));
            return id;
        });
    }

    private void awaitSelection(String expectedInfo, int count) {
        showEmployeeChooser();
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null || !page.loaded) return false;
            RecyclerView list = page.findViewById(R.id.enrollment_staff_list);
            TextView info = page.findViewById(R.id.enrollment_page_info);
            return list != null && list.isShown() && list.isEnabled() && count(list) == count
                    && info != null && expectedInfo.contentEquals(info.getText());
        }), "员工选择页未收敛到：" + expectedInfo);
    }

    private void awaitCapture(long personId) {
        showCaptureWorkspace();
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && page.loaded
                    && ((Number) readField(page, "staffId")).longValue() == personId
                    && page.findViewById(R.id.enrollment_capture_panel).isShown()
                    && page.camera != null && page.findViewById(R.id.camera_texture) != null;
        }), "录入页应按选中员工建立预览宿主：" + personId);
    }

    private void assertSelectionWithoutCamera() {
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            return page != null && ((Number) readField(page, "staffId")).longValue() == 0
                    && !(Boolean) readField(page, "personLoading") && page.camera == null;
        }), "缺失或未选员工应收敛为空状态而不是自动选人");
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertWorkspacePanels(page);
            assertEquals("未选择员工不能自动借用第一名员工", 0L, ((Number) readField(page, "staffId")).longValue());
            assertNull("选人前即使采集面板可见也不能打开相机", page.camera);
            assertNull("未选员工不能保留另一位员工的画面", page.latestFrame);
            assertNull("未选员工不能建立预览 TextureView", page.findViewById(R.id.camera_texture));
            assertFalse(page.findViewById(R.id.enrollment_capture).isEnabled());
            return null;
        });
    }

    private void assertWorkspacePanels(FaceEnrollmentActivity page) {
        boolean choosing = (Boolean) readField(page, "selectionExpanded");
        assertEquals("员工选择浮层应跟随选择状态", choosing,
                page.findViewById(R.id.enrollment_selection_overlay).isShown());
        assertEquals(choosing, page.findViewById(R.id.enrollment_selection).isShown());
        assertTrue("采集工作区保留在浮层下方", page.findViewById(R.id.enrollment_capture_panel).isShown());
        assertEquals(choosing, page.findViewById(R.id.enrollment_staff_list).isShown());
        assertEquals(choosing, page.findViewById(R.id.enrollment_search).isShown());
        assertTrue("必须保留切换人员入口", page.findViewById(R.id.enrollment_choose_staff).isShown());
    }

    private void showEmployeeChooser() {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page != null && !(Boolean) readField(page, "selectionExpanded")) {
                View choose = page.findViewById(R.id.enrollment_choose_staff);
                if (choose.isEnabled()) assertTrue(choose.performClick());
            }
            return null;
        });
    }

    private void showCaptureWorkspace() {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page != null && (Boolean) readField(page, "selectionExpanded")) {
                View choose = page.findViewById(R.id.enrollment_choose_staff);
                if (choose.isEnabled()) assertTrue(choose.performClick());
            }
            return null;
        });
    }

    private void assertSelectedRow(long selectedId) {
        onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            assertWorkspacePanels(page);
            RecyclerView employees = page.findViewById(R.id.enrollment_staff_list);
            boolean found = false;
            for (int position = 0; position < count(employees); position++) {
                boolean selected = ((JSONObject) item(employees, position)).optLong("id") == selectedId;
                found |= selected;
                View row = bind(employees, position, null).itemView;
                assertEquals("列表选中背景须跟随当前员工", selected, row.isActivated());
            }
            assertTrue("断言的员工应在当前页中", found);
            assertEquals(selectedId, ((Number) readField(page, "staffId")).longValue());
            return null;
        });
    }

    private void awaitLockedEmployeePage(String expectedInfo, int count) {
        await(() -> onMain(() -> {
            FaceEnrollmentActivity page = resumed(FaceEnrollmentActivity.class);
            if (page == null) return false;
            RecyclerView employees = page.findViewById(R.id.enrollment_staff_list);
            TextView info = page.findViewById(R.id.enrollment_page_info);
            return employees != null && count(employees) == count
                    && info != null && expectedInfo.contentEquals(info.getText());
        }), "在途重建后的列表仍应读取并显示原页：" + expectedInfo);
    }

    /** 禁用控件之外，程序回调也不能绕过在途员工写锁。 */
    private void assertInFlightWorkspaceGuard(FaceEnrollmentActivity page, long originalId) {
        assertWorkspacePanels(page);
        RecyclerView employees = page.findViewById(R.id.enrollment_staff_list);
        EditText search = page.findViewById(R.id.enrollment_search);
        assertTrue("回调防护测试须有实际两列的其他员工", count(employees) >= 2);
        for (int id : new int[]{R.id.enrollment_staff_list, R.id.enrollment_search,
                R.id.enrollment_previous, R.id.enrollment_next, R.id.enrollment_capture})
            assertFalse("在途学习时列表、搜索、分页与采集必须一起禁用", page.findViewById(id).isEnabled());
        String query = search.getText().toString();
        String pageInfo = ((TextView) page.findViewById(R.id.enrollment_page_info)).getText().toString();
        if (page.getResources().getBoolean(R.bool.mc_phone)) {
            View choose = page.findViewById(R.id.enrollment_choose_staff);
            assertFalse("在途学习时禁止展开员工区", choose.isEnabled());
            boolean expanded = (Boolean) readField(page, "selectionExpanded");
            choose.performClick();
            assertEquals("程序回调也不能展开员工区改变在途采集状态", expanded,
                    (Boolean) readField(page, "selectionExpanded"));
        }
        for (int position : new int[]{0, 1}) {
            assertNotEquals(originalId, id(employees, position));
            assertTrue("在途程序点击应确实经过员工卡片回调", RecyclerListTestHelper.click(employees, position));
            assertEquals("程序点击任意列均不能在途切换员工", originalId,
                    ((Number) readField(page, "staffId")).longValue());
        }
        page.findViewById(R.id.enrollment_previous).performClick();
        page.findViewById(R.id.enrollment_next).performClick();
        page.findViewById(R.id.enrollment_capture).performClick();
        assertEquals("程序列表点击也不能在途切换员工", originalId, ((Number) readField(page, "staffId")).longValue());
        for (int position = 0; position < count(employees); position++) {
            boolean selected = ((JSONObject) item(employees, position)).optLong("id") == originalId;
            assertEquals(selected, bind(employees, position, null).itemView.isActivated());
        }
        assertEquals("在途程序翻页不能改变当前页码", pageInfo,
                ((TextView) page.findViewById(R.id.enrollment_page_info)).getText().toString());
        assertEquals(query, search.getText().toString());
        search.setText("程序在途搜索");
        assertEquals("禁用之外程序更改搜索也必须还原在途查询", query, search.getText().toString());
        assertEquals(pageInfo, ((TextView) page.findViewById(R.id.enrollment_page_info)).getText().toString());
        assertEquals("程序采集不能追加 register", 1, registerRequests.get());
    }

    private <T extends BaseMorningActivity> void awaitControl(Class<T> type, int id) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            View control = page == null ? null : page.findViewById(id);
            return page != null && page.loaded && control != null && control.isShown() && control.isEnabled();
        }), "页面操作应就绪：" + type.getSimpleName());
    }

    private <T extends BaseMorningActivity> void awaitExpectedStaffOrientation(Class<T> type) {
        String expected = InstrumentationRegistry.getArguments().getString("expectedStaffLayoutOrientation", "");
        if (expected.isEmpty()) return;
        assertTrue("验收方向只允许 portrait 或 landscape", "portrait".equals(expected) || "landscape".equals(expected));
        boolean portrait = "portrait".equals(expected);
        await(() -> onMain(() -> resumed(type) != null), "指定方向前员工页面应已恢复");
        onMain(() -> { resumed(type).setRequestedOrientation(portrait
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
        await(() -> onMain(() -> {
            T page = resumed(type);
            if (page == null) return false;
            View root = page.findViewById(android.R.id.content);
            return root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested()
                    && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && page.getResources().getConfiguration().orientation == (portrait
                            ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "实际员工页面窗口应符合验收方向：" + expected);
    }

    private <T extends BaseMorningActivity> void click(Class<T> type, int id) {
        onMain(() -> {
            View control = resumed(type).findViewById(id);
            assertTrue("页面操作必须启用", control.isEnabled());
            assertTrue(control.performClick());
            return null;
        });
    }

    private <T extends Activity> T resumed(Class<T> type) {
        for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(page)) return type.cast(page);
        return null;
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (page instanceof BaseMorningActivity && !page.isFinishing()) page.finish();
    }

    private <T> T onMain(Callable<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(operation.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("人脸导航主线程验收失败", failure.get());
        return result.get();
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = FaceEnrollmentActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
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
            else throw new AssertionError("未知设备配置类型：" + entry.getKey());
        }
        assertTrue("应还原设备配置", editor.commit());
        assertEquals(original, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    private void reply(WebSocket socket, String requestId, JSONObject data) {
        reply(socket, requestId, 0, data);
    }

    private void reply(WebSocket socket, String requestId, int code, JSONObject data) {
        assertTrue(socket.send(Jsons.obj("requestId", requestId, "code", code,
                "reason", "本机人脸录入测试夹具", "data", data).toString()));
    }

    private static final class PendingRegistration {
        final WebSocket socket;
        final String requestId;
        final long personId;
        PendingRegistration(WebSocket socket, String requestId, long personId) {
            this.socket = socket;
            this.requestId = requestId;
            this.personId = personId;
        }
    }

    private final class EnrollmentFixture extends WebSocketListener {
        private final Map<String, JSONObject> registrations = new ConcurrentHashMap<>();
        private final Map<String, JSONObject> detections = new ConcurrentHashMap<>();
        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd");
                if ("detectFace".equals(command)) {
                    assertEquals("人脸检测仅取最多三张，不以三张作为多人阈值", 3,
                            request.getJSONObject("params").getInt("max_faces"));
                    detections.put(request.getString("requestId"), request);
                    return;
                }
                if ("register".equals(command)) {
                    assertTrue("普通导航不能提交学习；仅人工空帧测试允许", allowSyntheticRegistration);
                    registrations.put(request.getString("requestId"), request);
                    registerRequests.incrementAndGet();
                    return;
                }
                JSONObject params = request.optJSONObject("params");
                if ("userDetail".equals(command)) {
                    assertNotNull(params);
                    long personId = params.optLong("person_id");
                    assertTrue("userDetail必须使用存储层实际生成的person_id", sdkPersonIds.containsValue(personId));
                    assertFalse("userDetail不得直接使用本地主键", sdkPersonIds.containsKey(personId));
                    queriedPersonIds.add(personId);
                    detailRequests.incrementAndGet();
                    if (personId == delayedDetailPerson) {
                        assertTrue(pendingDetail.compareAndSet(null,
                                new PendingRegistration(socket, request.getString("requestId"), personId)));
                        detailReceived.countDown();
                    } else reply(socket, request.getString("requestId"), detailResponseCode, sampleDetail(personId));
                    return;
                }
                if ("forgetByUid".equals(command) || "forget".equals(command)) {
                    assertTrue("样本删除只允许显式隔离回归", allowSyntheticDelete);
                    assertNotNull(params);
                    deleteRequests.incrementAndGet();
                    if ("forgetByUid".equals(command)) lastDeletedUid.set(params.optLong("uid"));
                    else {
                        long personId = params.optLong("person_id");
                        assertTrue("forget必须使用存储层实际生成的person_id", sdkPersonIds.containsValue(personId));
                        assertFalse("forget不得直接使用本地主键", sdkPersonIds.containsKey(personId));
                        lastForgottenPerson.set(personId);
                    }
                    if (delayDeletion) {
                        assertTrue("延迟删除期间只允许一条 SDK 写入", pendingDeletion.compareAndSet(null,
                                new PendingRegistration(socket, request.getString("requestId"), params.optLong("person_id"))));
                        return;
                    }
                    if (deleteResponseCode == 0) {
                        if ("forget".equals(command)) sdkSamples.remove(params.optLong("person_id"));
                        else for (Set<Long> values : sdkSamples.values()) values.remove(params.optLong("uid"));
                    }
                    reply(socket, request.getString("requestId"), deleteResponseCode, new JSONObject());
                    return;
                }
                assertTrue("选择和导航只允许只读 SDK 查询及初始化", "init".equals(command)
                        || "getStatus".equals(command) || "getCapabilities".equals(command)
                        || "get_class_table".equals(command) || "getCertInfo".equals(command)
                        || "getDownloadProgress".equals(command));
                JSONObject data = "getStatus".equals(command)
                        ? Jsons.obj("sdk_state", "READY", "model_ready", true, "certificate_valid", true,
                                "device_id", TEST_DEVICE) : new JSONObject();
                reply(socket, request.getString("requestId"), data);
            } catch (Throwable error) { serverFailure.set(error); }
        }
        @Override public void onMessage(WebSocket socket, ByteString bytes) {
            try {
                ByteBuffer buffer = bytes.asByteBuffer();
                int length = buffer.getInt();
                assertTrue(length > 0 && length < buffer.remaining());
                byte[] id = new byte[length];
                buffer.get(id);
                String requestId = new String(id, StandardCharsets.UTF_8);
                JSONObject detection = detections.remove(requestId);
                if (detection != null) {
                    JSONObject params = detection.getJSONObject("params");
                    assertEquals(params.getInt("width") * params.getInt("height") * 3, buffer.remaining());
                    lastDetectedRgb = new byte[buffer.remaining()];
                    buffer.get(lastDetectedRgb);
                    int count = faceDetectionRequests.incrementAndGet();
                    if (count == delayedFaceDetectionAt) {
                        assertTrue(pendingFaceDetection.compareAndSet(null, new PendingRegistration(socket, requestId, 0)));
                    } else reply(socket, requestId, faceDetectionCode, faceDetectionData(syntheticFaceCount));
                    return;
                }
                JSONObject request = registrations.remove(requestId);
                assertNotNull("图片须对应人工 register 声明", request);
                JSONObject params = request.optJSONObject("params");
                assertNotNull(params);
                assertTrue("register必须使用存储层实际生成的person_id", sdkPersonIds.containsValue(params.optLong("person_id")));
                assertFalse("register不得直接使用本地主键", sdkPersonIds.containsKey(params.optLong("person_id")));
                assertEquals("人工帧长度必须符合声明尺寸", params.optInt("width") * params.optInt("height") * 3,
                        buffer.remaining());
                registeredRgb = new byte[buffer.remaining()];
                buffer.get(registeredRgb);
                assertArrayEquals("保存必须使用最终单脸检测的同一张完整 RGB 图", lastDetectedRgb, registeredRgb);
                assertTrue(pendingRegistration.compareAndSet(null,
                        new PendingRegistration(socket, requestId, params.optLong("person_id"))));
                registerReceived.countDown();
            } catch (Throwable error) { serverFailure.set(error); }
        }
    }

    private static JSONObject faceDetectionData(int count) {
        JSONArray faces = new JSONArray();
        for (int i = 0; i < count; i++) faces.put(Jsons.obj("x", .1 + i * .25, "y", .2,
                "w", .2, "h", .3, "score", .99,
                "landmarks", new JSONArray(java.util.Arrays.asList(.1, .2, .2, .2, .15, .3, .1, .4, .2, .4))));
        return Jsons.obj("faces", faces);
    }

    private JSONObject sampleDetail(long personId) {
        JSONArray values = new JSONArray(), details = new JSONArray();
        Set<Long> samples = sdkSamples.get(personId);
        if (samples != null) for (long uid : samples) {
            values.put(uid);
            details.put(Jsons.obj("uid", uid, "image_available", uid != unavailableSampleUid,
                    "image_url", imageUrl(uid), "mime_type", "image/jpeg"));
        }
        return detailItemsOnly ? Jsons.obj("items", details) : Jsons.obj("uids", values, "samples", details);
    }

    private String imageUrl(long uid) { return endpoint.url("/" + uid + ".jpg").toString(); }

    private static byte[] jpegFixture() {
        return jpegFixture(96, 64);
    }

    private static byte[] jpegFixture(int width, int height) {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xff3468b8);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue("本机图片夹具必须为有效 JPEG", image.compress(Bitmap.CompressFormat.JPEG, 90, output));
            return output.toByteArray();
        } finally { image.recycle(); }
    }

    private static byte[] rgbTestImage(int width, int height) {
        byte[] rgb = new byte[width * height * 3];
        for (int i = 0; i < rgb.length; i += 3) { rgb[i] = 52; rgb[i + 1] = 104; rgb[i + 2] = (byte) 184; }
        return rgb;
    }

    private static Set<Long> uidSet(JSONArray values) {
        Set<Long> result = new ConcurrentSkipListSet<>();
        if (values != null) for (int i = 0; i < values.length(); i++) result.add(values.optLong(i));
        return result;
    }
}
