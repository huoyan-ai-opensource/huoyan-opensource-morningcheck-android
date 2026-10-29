package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.login.ShopBindingStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 场所编辑与扫码登录使用真实页面栈和本机 WS 夹具，测试结束恢复全部设备与归属配置。 */
@RunWith(AndroidJUnit4.class)
public final class PlaceSettingsUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final BlockingQueue<BindingCall> bindings = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicInteger bindCount = new AtomicInteger(), businessRequests = new AtomicInteger();
    private final AtomicInteger unexpectedWrites = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, ?> originalDevice, originalBinding;
    private MockWebServer server;
    private ActivityScenario<SystemSettingsActivity> scenario;
    private Application.ActivityLifecycleCallbacks cameraIsolation;

    @Before
    public void isolateSdkAndUserConfiguration() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = new HashMap<>(device.getAll());
        originalBinding = new HashMap<>(binding.getAll());
        onMain(() -> {
            assertFalse("场所测试不能打断正在执行的登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            app.shopLogin().clearResult();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
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
                            if ("bindDevice".equals(command)) {
                                bindCount.incrementAndGet();
                                bindings.add(new BindingCall(socket, request));
                                return;
                            }
                            if (!"init".equals(command) && !command.startsWith("get"))
                                unexpectedWrites.incrementAndGet();
                            JSONObject data = new JSONObject();
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY")
                                    .put("model_ready", true).put("certificate_valid", true)
                                    .put("device_id", "PLACE-SETTINGS-TEST-ONLY");
                            if ("getCloudStatus".equals(command)) data.put("enabled", false);
                            reply(socket, request, 0, data);
                        } catch (Throwable error) {
                            serverFailure.set(error);
                        }
                    }
                });
            }
        });
        server.start();
        assertTrue(binding.edit().clear().commit());
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, server.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", server.url("/cloud").toString())
                .putString("shop_name", "测试原场所").putBoolean("voice", false)
                .putBoolean(SecuritySettings.AUTO_CHECK_KEY, false).commit());
        cameraIsolation = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
                if (!(activity instanceof LoginActivity)) return;
                // 本组验证页面返回与绑定回执，二维码像素和相机采集由各自测试覆盖。
                writeLoginField((LoginActivity) activity, "permissionRequested", true);
                writeLoginField((LoginActivity) activity, "permissionRequestInFlight", false);
                writeLoginField((LoginActivity) activity, "selectedCamera", "usb:PLACE-TEST-NO-CAMERA");
                writeLoginField((LoginActivity) activity, "cameraFailure", "场所测试不采集摄像头");
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(cameraIsolation);
    }

    @After
    public void restoreUserConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> {
                if (cameraIsolation != null) app.unregisterActivityLifecycleCallbacks(cameraIsolation);
                finishPages();
                app.sdkSession().disconnect();
                return null;
            });
            for (WebSocket socket : sockets) socket.close(1001, "场所测试结束");
            if (app != null) await(() -> onMain(() -> !app.shopLogin().getState().isRunning()),
                    "测试绑定结束后才能恢复原配置");
            instrumentation.waitForIdleSync();
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } finally {
            try {
                if (device != null && originalDevice != null) restore(device, originalDevice);
                if (binding != null && originalBinding != null) restore(binding, originalBinding);
                if (app != null) onMain(() -> { app.shopLogin().clearResult(); return null; });
            } finally {
                if (server != null) server.shutdown();
            }
        }
        assertNull("场所夹具不能发生异常", serverFailure.get());
        assertEquals("场所设置不能访问业务服务", 0, businessRequests.get());
        assertEquals("场所设置不能触发其他 SDK 管理写入", 0, unexpectedWrites.get());
    }

    @Test
    public void editAndLoginReturnToPlaceThenRefreshOriginalSettings() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, SystemSettingsActivity.class));
        await(() -> onMain(() -> current(SystemSettingsActivity.class) != null
                && current(SystemSettingsActivity.class).uiReady()), "系统设置应进入前台");
        SystemSettingsActivity settings = onMain(() -> current(SystemSettingsActivity.class));
        awaitStableLayout(settings);
        onMain(() -> {
            TextView placeName = settings.findViewById(R.id.settings_shop_name);
            assertEquals("测试原场所", placeName.getText().toString());
            assertNotNull("场所行右侧应显示进入页面的箭头图片", placeName.getCompoundDrawablesRelative()[2]);
            assertTrue("场所行右箭头应具有可见宽度", placeName.getCompoundDrawablesRelative()[2].getBounds().width() > 0);
            assertTrue("场所行右箭头应具有可见高度", placeName.getCompoundDrawablesRelative()[2].getBounds().height() > 0);
            capturePage(settings, "system-settings.png");
            assertTrue(settings.findViewById(R.id.settings_shop_name).performClick());
            return null;
        });
        await(() -> onMain(() -> current(PlaceSettingsActivity.class) != null), "点击场所应打开独立页面");
        awaitStableLayout(onMain(() -> current(PlaceSettingsActivity.class)));
        onMain(() -> {
            assertTrue("场所入口不能继续使用编辑弹窗", settings.formDialog == null || !settings.formDialog.isShowing());
            PlaceSettingsActivity place = current(PlaceSettingsActivity.class);
            assertNotNull(place.findViewById(R.id.page_content));
            capturePage(place, "place-settings.png");
            ((EditText) place.findViewById(R.id.place_name)).setText("  测试手动场所  ");
            ((EditText) place.findViewById(R.id.place_phone)).setText("  13800138000  ");
            ((EditText) place.findViewById(R.id.place_address)).setText("  测试市食品安全路 100 号中央厨房  ");
            assertTrue(place.findViewById(R.id.place_save).performClick());
            return null;
        });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) == settings
                && "测试手动场所".contentEquals(((TextView) settings.findViewById(R.id.settings_shop_name)).getText())),
                "保存应返回原系统设置并刷新场所名称");
        assertEquals("测试手动场所", device.getString("shop_name", ""));
        assertEquals("13800138000", device.getString("shop_phone", ""));
        assertEquals("测试市食品安全路 100 号中央厨房", device.getString("shop_address", ""));
        assertEquals("仅修改场所名称不能建立 SDK 归属", 0, new ShopBindingStore(app).read().length());
        assertEquals(0, bindCount.get());
        onMain(() -> { settings.findViewById(R.id.settings_shop_name).performClick(); return null; });
        await(() -> onMain(() -> current(PlaceSettingsActivity.class) != null), "应能再次打开场所页");
        PlaceSettingsActivity originalPlace = onMain(() -> current(PlaceSettingsActivity.class));
        onMain(() -> {
            assertEquals("测试手动场所", ((EditText) originalPlace.findViewById(R.id.place_name)).getText().toString());
            assertEquals("13800138000", ((EditText) originalPlace.findViewById(R.id.place_phone)).getText().toString());
            assertEquals("测试市食品安全路 100 号中央厨房", ((EditText) originalPlace.findViewById(R.id.place_address)).getText().toString());
            ((EditText) originalPlace.findViewById(R.id.place_phone)).setText("13900139000");
            ((EditText) originalPlace.findViewById(R.id.place_address)).setText("测试市食品安全路 200 号新场所");
            assertTrue(originalPlace.findViewById(R.id.place_login).performClick());
            return null;
        });
        await(() -> onMain(() -> current(LoginActivity.class) != null), "场所登录应进入扫码页面");
        String raw = "PLACE-TEST-QR";
        onMain(() -> {
            LoginActivity login = current(LoginActivity.class);
            assertTrue("场所页登录成功应返回调用页", login.getIntent().getBooleanExtra("return_to_caller", false));
            app.shopLogin().login(raw, MorningSdkSettings.read(device));
            return null;
        });
        BindingCall call = bindings.poll(10, TimeUnit.SECONDS);
        assertNotNull("扫码应提交一次真实 bindDevice 请求", call);
        assertEquals(raw, call.request.getJSONObject("params").getString("shop_id"));
        reply(call.socket, call.request, 0, new JSONObject().put("response", new JSONObject()
                .put("account_id", "PLACE-TEST-ACCOUNT").put("shop_id", "PLACE-TEST-SHOP")
                .put("shop_name", "测试登录场所")));
        await(() -> onMain(() -> current(PlaceSettingsActivity.class) == originalPlace
                && "测试登录场所".contentEquals(((EditText) originalPlace.findViewById(R.id.place_name)).getText())),
                "登录成功应留在原场所页面并展示新的场所名称");
        JSONObject scope = new ShopBindingStore(app).read();
        assertEquals("PLACE-TEST-SHOP", scope.getString("shop_id"));
        assertEquals("测试登录场所", scope.getString("shop_name"));
        assertEquals("测试登录场所", device.getString("shop_name", ""));
        assertEquals("登录不能自动提交未保存的电话草稿", "13800138000", device.getString("shop_phone", ""));
        assertEquals("登录不能自动提交未保存的地址草稿", "测试市食品安全路 100 号中央厨房", device.getString("shop_address", ""));
        assertEquals("扫码登录不能重复提交绑定", 1, bindCount.get());
        awaitStableLayout(originalPlace);
        onMain(() -> {
            assertEquals("登录返回后应保留未保存的电话", "13900139000",
                    ((EditText) originalPlace.findViewById(R.id.place_phone)).getText().toString());
            assertEquals("登录返回后应保留未保存的地址", "测试市食品安全路 200 号新场所",
                    ((EditText) originalPlace.findViewById(R.id.place_address)).getText().toString());
            capturePage(originalPlace, "place-settings.png");
            assertTrue(originalPlace.findViewById(R.id.place_save).performClick());
            return null;
        });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) == settings
                && "测试登录场所".contentEquals(((TextView) settings.findViewById(R.id.settings_shop_name)).getText())),
                "从场所页面返回后系统设置应刷新登录场所名称");
        assertEquals("13900139000", device.getString("shop_phone", ""));
        assertEquals("测试市食品安全路 200 号新场所", device.getString("shop_address", ""));
    }

    private void awaitStableLayout(Activity activity) {
        await(() -> onMain(() -> {
            View root = activity.findViewById(R.id.main_root);
            return root != null && root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested();
        }), "页面应完成布局后再采集截图");
    }

    private void capturePage(Activity activity, String filename) throws Exception {
        File folder = new File(app.getCacheDir(), "place-settings-qa");
        assertTrue("应能创建场所页面截图目录", folder.isDirectory() || folder.mkdirs());
        View root = activity.findViewById(R.id.main_root);
        Bitmap image = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(image));
            try (FileOutputStream output = new FileOutputStream(new File(folder, filename))) {
                assertTrue("应能保存场所页面截图", image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            image.recycle();
        }
    }

    private static void reply(WebSocket socket, JSONObject request, int code, JSONObject data) throws Exception {
        socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                .put("code", code).put("reason", "场所隔离夹具").put("data", data).toString());
    }

    private static void writeLoginField(LoginActivity activity, String name, Object value) {
        try {
            Field field = LoginActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("隔离登录相机失败：" + name, error);
        }
    }

    private <T extends Activity> T current(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity) && !activity.isFinishing()) return type.cast(activity);
        return null;
    }

    private void finishPages() {
        for (Stage stage : Stage.values()) for (Activity activity : new ArrayList<>(
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        }
        assertTrue("应恢复测试前配置", editor.commit());
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }

    private void await(Callable<Boolean> condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (condition.call()) return; }
            catch (Exception error) { throw new AssertionError(error); }
            SystemClock.sleep(40);
        }
        fail(message);
    }

    private static final class BindingCall {
        final WebSocket socket;
        final JSONObject request;

        BindingCall(WebSocket socket, JSONObject request) {
            this.socket = socket;
            this.request = request;
        }
    }
}
