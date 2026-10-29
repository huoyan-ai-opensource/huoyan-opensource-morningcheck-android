package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

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

import java.lang.reflect.Field;
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

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 仅使用本机 WS 夹具及不可用镜头 ID 验证设置导航，不打开现场设备或访问业务云。 */
@RunWith(AndroidJUnit4.class)
public final class SettingsNavigationTest {
    private static final String FACE_CAMERA = "usb:SETTINGS-NAVIGATION-NO-DEVICE-FACE";
    private static final String HAND_CAMERA = "usb:SETTINGS-NAVIGATION-NO-DEVICE-HAND";
    private static final Map<Integer, Class<? extends BaseDeviceSettingsActivity>> PAGES = Map.of(
            0, DevicesActivity.class, 1, BusinessCloudSettingsActivity.class,
            3, LicenseSettingsActivity.class, 4, CameraSettingsActivity.class, 5, CheckRulesSettingsActivity.class,
            6, TemperatureSettingsActivity.class, 7, SystemSettingsActivity.class);
    private static final Map<Integer, Integer> SETTINGS_ENTRIES = Map.of(
            0, R.id.home_connection, 3, R.id.home_authorization, 4, R.id.home_camera,
            5, R.id.home_items, 6, R.id.home_temperature, 7, R.id.home_system);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger initCount = new AtomicInteger(), businessRequests = new AtomicInteger();
    private final AtomicInteger cloudStatusReads = new AtomicInteger(), cloudStatusWrites = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> homeScenario;
    private HomeActivity originalHome;
    private Application.ActivityLifecycleCallbacks cameraIsolation;

    @Before public void prepareIsolatedEndpoints() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device);
        originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("测试不能中断操作员正在执行的登录", app.shopLogin().getState().isRunning());
            app.sdkSession().disconnect();
            return null;
        });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getPath();
                if (path == null || !path.startsWith("/sdk")) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            String command = request.getString("cmd");
                            if ("getCloudStatus".equals(command)) cloudStatusReads.incrementAndGet();
                            if ("setCloudStatus".equals(command)) cloudStatusWrites.incrementAndGet();
                            JSONObject data = new JSONObject();
                            if ("init".equals(command)) initCount.incrementAndGet();
                            if ("getStatus".equals(command)) {
                                data.put("sdk_state", "READY").put("model_ready", true)
                                        .put("certificate_valid", true).put("device_id", "SETTINGS-TEST-ONLY");
                            }
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "本机测试夹具")
                                    .put("data", data).toString());
                        } catch (Throwable failure) { serverFailure.set(failure); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                // 保留旧版配置，验证升级后不再按残留地址请求 App 更新服务。
                .putString("update_url", endpoint.url("/updates").toString())
                .putString("cloud_token", "").putBoolean("voice", false)
                .putString("face_camera", FACE_CAMERA).putString("hand_camera", HAND_CAMERA).commit());
        cameraIsolation = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (activity instanceof CameraSettingsActivity) {
                    // 无权限的设备模拟“已问过权限”；已授权设备因指定无效 USB ID 只进入断开提示。
                    writeField(activity, "cameraPermissionRequested", true);
                    ((BaseDeviceSettingsActivity) activity).formState.putBoolean("camera.permission_requested", true);
                }
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(cameraIsolation);
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        homeScenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        homeScenario.onActivity(activity -> originalHome = activity);
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "首页应连接隔离 SDK 夹具");
        assertEquals(1, initCount.get());
    }

    @After public void finishPagesAndRestoreConfiguration() throws Exception {
        try {
            if (homeScenario != null) homeScenario.close();
        } finally {
            try {
                if (app != null) onMain(() -> {
                    if (cameraIsolation != null) app.unregisterActivityLifecycleCallbacks(cameraIsolation);
                    for (Stage stage : Stage.values())
                        for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(stage)))
                            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing())
                                activity.finish();
                    app.sdkSession().disconnect();
                    return null;
                });
                instrumentation.waitForIdleSync();
                if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    if (originalDevice != null) restore(device, originalDevice);
                    if (originalBinding != null)
                        assertEquals("设置导航不能改变 SDK 门店归属", originalBinding, binding.getAll());
                } finally {
                    for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                    if (endpoint != null) endpoint.shutdown();
                }
            }
        }
        assertEquals("隔离导航不能访问业务云", 0, businessRequests.get());
        assertEquals("连接、设置导航和重建不能读取暂停的云端托管", 0, cloudStatusReads.get());
        assertEquals("隐藏设置不能提交云端托管开关", 0, cloudStatusWrites.get());
        assertNull(serverFailure.get());
    }

    @Test public void homeShortcutsOpenTheirOwnActivitiesAndReturnToExistingHome() {
        onMain(() -> {
            assertAbsentOrHidden(originalHome, "home_cloud");
            assertShopShortcutUnavailable(originalHome);
            assertShopHeaderUnavailable(originalHome);
            return null;
        });
        int[] buttons = {R.id.home_connection,
                R.id.home_authorization, R.id.home_camera, R.id.home_items,
                R.id.home_temperature, R.id.home_system, R.id.home_settings};
        int[] sections = {0, 3, 4, 5, 6, 7, 3};
        for (int index = 0; index < buttons.length; index++) {
            final int button = buttons[index];
            onMain(() -> {
                revealHomeEntry(button);
                View entry = originalHome.findViewById(button);
                assertNotNull(entry);
                assertTrue("首页应提供对应快捷入口", entry.isShown() && entry.performClick());
                return null;
            });
            BaseDeviceSettingsActivity page = awaitPage(sections[index]);
            onMain(() -> { assertShopHeaderUnavailable(page); return null; });
            if (sections[index] == 3) onMain(() -> { assertCloudHostingUnavailable((LicenseSettingsActivity) page); return null; });
            assertFalse("进入设置不应销毁首页", originalHome.isDestroyed());
            onMain(() -> { page.findViewById(R.id.header_home).performClick(); return null; });
            await(() -> resumed(HomeActivity.class) != null, "设置页应回到首页");
            assertSame("返回应复用原首页实例", originalHome, resumed(HomeActivity.class));
        }
        assertEquals("页面跳转不得重复初始化共享 SDK", 1, initCount.get());
        assertCameraPreferencesUnchanged();
    }

    @Test public void hiddenShopHeaderKeepsBlankSpaceInertAndAboutTouchableAcrossOrientationsAndRearrangement() {
        String longName = "新海智控晨检设备测试门店名称特别长用于验证右上角关于设备始终可见"
                + "且隐藏门店信息不会留下占位或覆盖关于设备按钮";
        int originalOrientation = onMain(originalHome::getRequestedOrientation);
        try {
            for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                onMain(() -> { originalHome.setRequestedOrientation(orientation); return null; });
                int expected = orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                await(() -> onMain(() -> originalHome.getResources().getConfiguration().orientation == expected
                                && !originalHome.findViewById(R.id.home_settings).isLayoutRequested()),
                        "首页应完成指定方向的布局");
                for (String name : new String[]{"食堂", longName}) {
                    onMain(() -> {
                        assertSame(originalHome, resumedOnMain(HomeActivity.class));
                        device.edit().putString("shop_name", name).apply();
                        originalHome.updateHeaderShop();
                        assertShopHeaderUnavailable(originalHome);
                        View previous = originalHome.content;
                        // 配置回调重新绑定生产 XML，不能把暂时隐藏只留在旧 View 上。
                        originalHome.onConfigurationChanged(new Configuration(originalHome.getResources().getConfiguration()));
                        assertNotSame("重排应替换原页面布局", previous, originalHome.content);
                        assertShopHeaderUnavailable(originalHome);
                        assertShopShortcutUnavailable(originalHome);
                        assertEquals("隐藏入口不应清除已有门店资料", name, device.getString("shop_name", ""));
                        return null;
                    });
                    await(() -> onMain(() -> originalHome.loaded
                                    && !originalHome.findViewById(R.id.home_settings).isLayoutRequested()),
                            "隐藏门店并重排后应完成页头实际布局");
                    onMain(() -> {
                        assertHeaderTouchBounds(originalHome);
                        Rect trailing = globalBounds(originalHome.findViewById(R.id.header_trailing));
                        int[] blank = blankPoint(trailing);
                        dispatchTouch(originalHome, blank[0], blank[1]);
                        assertFalse("隐藏门店后的页头空白不能启动设置导航", originalHome.navigating);
                        return null;
                    });
                    instrumentation.waitForIdleSync();
                    onMain(() -> {
                        assertSame("页头空白不能误触门店或设备页面", originalHome, resumedOnMain(HomeActivity.class));
                        assertNull(resumedOnMain(LicenseSettingsActivity.class));
                        touchControl(originalHome, R.id.home_settings);
                        return null;
                    });
                    LicenseSettingsActivity license = (LicenseSettingsActivity) awaitPage(3);
                    await(() -> onMain(() -> license.loaded), "关于设备应进入授权与密钥页面");
                    onMain(() -> {
                        assertShopHeaderUnavailable(license);
                        assertCloudHostingUnavailable(license);
                        license.recreate();
                        return null;
                    });
                    await(() -> onMain(() -> {
                        LicenseSettingsActivity current = resumedOnMain(LicenseSettingsActivity.class);
                        return current != null && current != license && current.loaded;
                    }), "从关于设备进入授权页后重建应成功");
                    onMain(() -> {
                        LicenseSettingsActivity recreated = resumedOnMain(LicenseSettingsActivity.class);
                        assertShopHeaderUnavailable(recreated);
                        assertCloudHostingUnavailable(recreated);
                        touchControl(recreated, R.id.header_home);
                        return null;
                    });
                    await(() -> resumed(HomeActivity.class) == originalHome, "授权页重建后应返回原首页");
                }
            }
        } finally {
            onMain(() -> { originalHome.setRequestedOrientation(originalOrientation); return null; });
        }
        assertEquals("页头实际触控及授权页重建不能重复初始化共享 SDK", 1, initCount.get());
    }

    @Test public void visibleSettingsUseSeparateActivitiesAndKeepUnsavedConnectionDraft() {
        clickSettingsEntry(0);
        BaseDeviceSettingsActivity[] instances = new BaseDeviceSettingsActivity[8];
        instances[0] = awaitPage(0);
        final String draft = "ws://127.0.0.1:9/unsaved-settings-draft";
        boolean inline = onMain(() -> {
            EditText input = instances[0].findViewById(R.id.settings_sdk_url);
            if (input != null) input.setText(draft);
            return input != null;
        });
        int previous = 0;
        for (int section : new int[]{3, 4, 5, 6, 7}) {
            selectSection(instances[previous], section);
            instances[section] = awaitPage(section);
            assertNotSame("不同设置必须有不同 Activity 实例", instances[previous], instances[section]);
            assertEquals(PAGES.get(section), instances[section].getClass());
            if (section == 3) {
                LicenseSettingsActivity license = (LicenseSettingsActivity) instances[section];
                onMain(() -> {
                    assertCloudHostingUnavailable(license);
                    license.recreate();
                    return null;
                });
                await(() -> {
                    LicenseSettingsActivity current = resumed(LicenseSettingsActivity.class);
                    return current != null && current != license;
                }, "授权设置应支持重建且不能恢复托管入口");
                instances[section] = awaitPage(section);
                onMain(() -> {
                    LicenseSettingsActivity restored = (LicenseSettingsActivity) instances[3];
                    assertCloudHostingUnavailable(restored);
                    restored.refreshStatus();
                    assertCloudHostingUnavailable(restored);
                    return null;
                });
            }
            previous = section;
        }
        selectSection(instances[7], 0);
        assertSame("设置之间切换应返回原实例", instances[0], awaitPage(0));
        if (inline) onMain(() -> {
            assertEquals("未提交地址草稿应保留", draft,
                    ((EditText) instances[0].findViewById(R.id.settings_sdk_url)).getText().toString());
            return null;
        });
        assertEquals("草稿不能改变连接地址", endpoint.url("/sdk").toString().replaceFirst("http", "ws"),
                device.getString(MorningSdkSettings.ENDPOINT_KEY, ""));
        assertEquals("遍历设置页应沿用一次 SDK 初始化", 1, initCount.get());
        assertCameraPreferencesUnchanged();
    }

    @Test public void cameraRoleSurvivesBackgroundRearrangementAndActivityRecreation() {
        onMain(() -> {
            revealHomeEntry(R.id.home_camera);
            View entry = originalHome.findViewById(R.id.home_camera);
            assertTrue("摄像头设置入口应可见且可点击", entry.isShown() && entry.performClick());
            return null;
        });
        CameraSettingsActivity camera = (CameraSettingsActivity) awaitPage(4);
        CameraSource originalSource = onMain(() -> (CameraSource) readField(camera, "cameraPreviewSource"));
        onMain(() -> { camera.findViewById(R.id.settings_preview_hand).performClick(); return null; });
        selectSection(camera, 5);
        BaseDeviceSettingsActivity rules = awaitPage(5);
        onMain(() -> {
            assertFalse("切换页面必须停止相机源", (Boolean) readField(originalSource, "wanted"));
            assertNull("隔离测试不能打开实际镜头", originalSource.getCameraId());
            return null;
        });
        selectSection(rules, 4);
        assertSame("返回摄像头设置应保留原 Activity", camera, awaitPage(4));
        onMain(() -> {
            assertHandRole(camera);
            View previous = camera.findViewById(R.id.settings_camera_texture);
            // 触发真实 Activity 的布局重排回调，不修改设备全局方向或显示配置。
            camera.onConfigurationChanged(new Configuration(camera.getResources().getConfiguration()));
            assertNotSame("配置回调应重新装载对应 XML", previous,
                    camera.findViewById(R.id.settings_camera_texture));
            assertHandRole(camera);
            assertTrue("旧预览源应在重排时关闭", (Boolean) readField(originalSource, "closed"));
            camera.recreate();
            return null;
        });
        await(() -> {
            CameraSettingsActivity current = resumed(CameraSettingsActivity.class);
            return current != null && current != camera;
        }, "摄像头设置应支持系统重建");
        onMain(() -> { assertHandRole(resumedOnMain(CameraSettingsActivity.class)); return null; });
        assertEquals("恢复草稿不得重新初始化 SDK", 1, initCount.get());
        assertCameraPreferencesUnchanged();
    }

    @Test public void systemSettingsKeepsVoiceAndHidesSdkCapabilitiesWithoutAppUpdateRequests() {
        clickSettingsEntry(7);
        SystemSettingsActivity system = (SystemSettingsActivity) awaitPage(7);
        onMain(() -> {
            assertSystemVersion(system);
            Switch voice = system.findViewById(R.id.settings_voice);
            assertNotNull("系统设置应提供语音引导开关", voice);
            voice.setChecked(true);
            assertTrue("系统设置仍应能保存语音引导", device.getBoolean("voice", false));
            return null;
        });
        selectSection(system, 0);
        BaseDeviceSettingsActivity connection = awaitPage(0);
        selectSection(connection, 7);
        assertSame("系统设置应复用原实例", system, awaitPage(7));
        onMain(() -> { system.recreate(); return null; });
        await(() -> {
            SystemSettingsActivity current = resumed(SystemSettingsActivity.class);
            return current != null && current != system;
        }, "系统设置应支持重建");
        SystemSettingsActivity restored = resumed(SystemSettingsActivity.class);
        onMain(() -> {
            assertSystemVersion(restored);
            Switch voice = restored.findViewById(R.id.settings_voice);
            assertNotNull("重建后应保留语音引导开关", voice);
            assertTrue("重建后应恢复语音配置", voice.isChecked());
            View capabilities = restored.findViewById(R.id.settings_capabilities);
            assertNotNull("隐藏能力入口时仍保留对应绑定 ID", capabilities);
            assertEquals("SDK 能力入口应隐藏", View.GONE, capabilities.getVisibility());
            assertFalse("SDK 能力入口不应显示", capabilities.isShown());
            return null;
        });
        assertSame("隐藏能力入口后系统设置应仍在前台", restored, awaitPage(7));
        assertEquals("设置切换和重建不能重新初始化 SDK", 1, initCount.get());
        assertEquals("系统设置不能请求 App 更新或业务接口", 0, businessRequests.get());
    }

    private static void assertShopShortcutUnavailable(HomeActivity home) {
        assertEquals(0, home.getResources().getIdentifier("home_shop_settings", "id", home.getPackageName()));
    }

    private static void assertShopHeaderUnavailable(Activity activity) {
        TextView shop = activity.findViewById(R.id.header_shop);
        assertNotNull("暂时隐藏仍保留页头门店 View", shop);
        assertEquals("页头门店信息不应占位", View.GONE, shop.getVisibility());
        assertFalse("页头门店信息不应显示", shop.isShown());
        assertEquals("隐藏页头不应残留门店名", "", shop.getText().toString());
        assertNull("隐藏页头不应通过无障碍暴露门店名", shop.getContentDescription());
        assertFalse("隐藏页头不能保留门店路由", shop.hasOnClickListeners());
        assertFalse("隐藏页头不能点击", shop.isClickable());
        assertFalse("隐藏页头不能抢占焦点", shop.isFocusable());
        assertFalse("程序点击不能打开隐藏页头入口", shop.performClick());
        assertFalse("隐藏页头不应留下可见触控区域", shop.getGlobalVisibleRect(new Rect()));
    }

    private static void assertHeaderTouchBounds(HomeActivity home) {
        View trailing = home.findViewById(R.id.header_trailing);
        View about = home.findViewById(R.id.home_settings);
        Rect trailingBounds = globalBounds(trailing);
        Rect aboutBounds = globalBounds(about);
        assertShopHeaderUnavailable(home);
        assertFalse("页头容器不能占用空白区域的点击", trailing.hasOnClickListeners());
        assertFalse("页头空白不能覆盖关于设备入口", Rect.intersects(trailingBounds, aboutBounds));
        assertTrue("关于设备应保留点击路由", about.hasOnClickListeners());
        assertEquals("关于设备不能被页头裁切宽度", about.getWidth(), aboutBounds.width());
        assertEquals("关于设备不能被页头裁切高度", about.getHeight(), aboutBounds.height());
        assertTrue("页头入口不能遮挡主页内容", aboutBounds.bottom <= globalBounds(home.content).top);
    }

    private static Rect globalBounds(View view) {
        assertNotNull("触控目标必须存在", view);
        Rect bounds = new Rect();
        assertTrue("触控目标应在屏幕内可见", view.isShown() && view.getGlobalVisibleRect(bounds));
        assertTrue("触控目标应有实际尺寸", bounds.width() > 0 && bounds.height() > 0);
        return bounds;
    }

    private static int[] blankPoint(Rect trailing) {
        assertTrue("页头尾部应保留可触摸的空白区域", trailing.width() > 2 && trailing.height() > 2);
        return new int[]{trailing.centerX(), trailing.centerY()};
    }

    private static void dispatchTouch(Activity activity, int x, int y) {
        View decor = activity.getWindow().getDecorView();
        Rect decorBounds = globalBounds(decor);
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                x - decorBounds.left, y - decorBounds.top, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 16, MotionEvent.ACTION_UP,
                x - decorBounds.left, y - decorBounds.top, 0);
        try {
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void touchControl(Activity activity, int viewId) {
        Rect bounds = globalBounds(activity.findViewById(viewId));
        dispatchTouch(activity, bounds.centerX(), bounds.centerY());
    }

    private static void assertSystemVersion(SystemSettingsActivity activity) {
        assertEquals("系统设置", ((TextView) activity.findViewById(R.id.header_page_title)).getText().toString());
        assertEquals(activity.getString(R.string.settings_version_value, BuildConfig.VERSION_NAME),
                ((TextView) activity.findViewById(R.id.settings_version)).getText().toString());
    }

    private static void assertCloudHostingUnavailable(LicenseSettingsActivity activity) {
        assertAbsentOrHidden(activity, "settings_sdk_cloud");
        assertFalse("授权页状态文案不再展示云端托管", activity.statusDetails().contains("云端托管"));
        assertTrue("设备授权详情应直接内联", activity.findViewById(R.id.settings_license_fields).isShown());
        assertAbsentOrHidden(activity, "settings_cert_info");
        assertAbsentOrHidden(activity, "settings_refresh_keys");
        assertAbsentOrHidden(activity, "settings_model_progress");
    }

    private static void assertAbsentOrHidden(Activity activity, String resourceName) {
        int id = activity.getResources().getIdentifier(resourceName, "id", activity.getPackageName());
        View view = id == 0 ? null : activity.findViewById(id);
        assertTrue("已移除的设置入口不能继续显示：" + resourceName, view == null || view.getVisibility() == View.GONE);
        if (view != null) {
            assertFalse("旧入口不能残留路由：" + resourceName, view.hasOnClickListeners());
            assertFalse("程序点击不能触发隐藏入口：" + resourceName, view.performClick());
        }
    }

    private void selectSection(BaseDeviceSettingsActivity activity, int section) {
        onMain(() -> {
            assertTrue("设置页应提供返回首页入口", activity.findViewById(R.id.header_home).performClick());
            return null;
        });
        await(() -> resumed(HomeActivity.class) != null, "设置页应先返回首页");
        assertSame("设置快捷入口应由原首页承载", originalHome, resumed(HomeActivity.class));
        clickSettingsEntry(section);
    }

    private void clickSettingsEntry(int section) {
        onMain(() -> {
            assertSame("设置快捷入口应在首页前台显示", originalHome, resumedOnMain(HomeActivity.class));
            revealHomeEntry(SETTINGS_ENTRIES.get(section));
            View entry = originalHome.findViewById(SETTINGS_ENTRIES.get(section));
            assertNotNull("首页应提供每类设置的独立入口", entry);
            assertTrue("设置入口应可见且可点击", entry.isShown() && entry.performClick());
            return null;
        });
    }

    private void revealHomeEntry(int entryId) {
        if (entryId == R.id.home_settings || entryId == R.id.header_shop) return;
        View entry = originalHome.findViewById(entryId);
        if (entry != null) entry.requestRectangleOnScreen(new Rect(0, 0, entry.getWidth(), entry.getHeight()), true);
    }

    private BaseDeviceSettingsActivity awaitPage(int section) {
        Class<? extends BaseDeviceSettingsActivity> type = PAGES.get(section);
        await(() -> resumed(type) != null, "应进入独立设置 Activity：" + type.getSimpleName());
        return resumed(type);
    }

    private void assertCameraPreferencesUnchanged() {
        assertEquals("预览角色不能保存镜头设置", FACE_CAMERA, device.getString("face_camera", ""));
        assertEquals("预览角色不能保存镜头设置", HAND_CAMERA, device.getString("hand_camera", ""));
    }

    private static void assertHandRole(CameraSettingsActivity activity) {
        assertEquals("应保留尚未保存的手部预览角色", R.id.settings_preview_hand,
                ((RadioGroup) activity.findViewById(R.id.settings_camera_role)).getCheckedRadioButtonId());
    }

    private <T extends Activity> T resumed(Class<T> type) {
        return onMain(() -> resumedOnMain(type));
    }

    private static <T extends Activity> T resumedOnMain(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }

    private <T> T onMain(Callable<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(work.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程设置验收失败", failure.get());
        return result.get();
    }

    private static Field field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass())
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        throw new AssertionError("缺少验收状态：" + name);
    }

    private static Object readField(Object target, String name) {
        try { return field(target, name).get(target); }
        catch (IllegalAccessException error) { throw new AssertionError(error); }
    }

    private static void writeField(Object target, String name, Object value) {
        try { field(target, name).set(target, value); }
        catch (IllegalAccessException error) { throw new AssertionError(error); }
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
