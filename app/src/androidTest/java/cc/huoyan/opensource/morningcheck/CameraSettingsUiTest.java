package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.camera.CameraImageOptions;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 显式启用的真实摄像头设置验收；SDK/业务云隔离，不写员工、晨检记录或系统显示参数。 */
@RunWith(AndroidJUnit4.class)
public final class CameraSettingsUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<CameraSource.CameraInfo> usb = new ArrayList<>();
    private final JSONArray evidence = new JSONArray();
    private final AtomicInteger cloudRequests = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private ActivityScenario<CameraSettingsActivity> scenario;
    private CameraProbeActivity probe;
    private File output;
    private boolean completed;
    private boolean preferencesRestored;

    @Before public void isolateConnectionsAndCheckHardware() throws Exception {
        assumeTrue("只有显式启用硬件验收才打开现场摄像头", "true".equals(
                InstrumentationRegistry.getArguments().getString("enableUsbHardware")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assertEquals("执行前需授予相机权限", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA));
        for (CameraSource.CameraInfo info : CameraSource.listCameraInfo(app))
            if (info.id.startsWith("usb:")) usb.add(info);
        assertEquals("YF023D 应连接两路独立 USB 摄像头", 2, usb.size());
        assertNotEquals("两路设备应有不同 ID", usb.get(0).id, usb.get(1).id);

        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            originalPreferences.put(entry.getKey(), value instanceof Set
                    ? new HashSet<>((Set<?>) value) : value);
        }
        instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/cloud"))
                    cloudRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("摄像头设置测试，不连接真实 SDK");
            }
        });
        endpoint.start();
        SharedPreferences.Editor initial = preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false)
                .putString("face_camera", usb.get(0).id).putString("hand_camera", usb.get(1).id);
        CameraImageOptions.defaults().save(initial, "face");
        CameraImageOptions.defaults().save(initial, "hand");
        assertTrue(initial.commit());
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        output = new File(app.getCacheDir(), "camera-settings-evidence");
        assertTrue(output.isDirectory() || output.mkdirs());
    }

    @After public void closeSourcesAndRestoreEveryDevicePreference() throws Exception {
        try {
            closeProbe();
        } finally {
            try {
                if (scenario != null) scenario.close();
            } finally {
                try {
                    if (app != null) instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
                    if (preferences != null && originalPreferences != null) {
                        SharedPreferences.Editor restore = preferences.edit().clear();
                        for (Map.Entry<String, Object> entry : originalPreferences.entrySet())
                            restorePreference(restore, entry.getKey(), entry.getValue());
                        assertTrue("应持久化恢复全部设备配置", restore.commit());
                        assertEquals("测试不能留下草稿镜头或本机夹具地址", originalPreferences, preferences.getAll());
                        preferencesRestored = true;
                    }
                } finally {
                    try {
                        if (endpoint != null) endpoint.shutdown();
                    } finally {
                        if (output != null) {
                            JSONObject metadata = new JSONObject().put("completed", completed)
                                    .put("preferences_restored", preferencesRestored)
                                    .put("business_cloud_requests", cloudRequests.get())
                                    .put("samples", evidence);
                            try (FileOutputStream stream = new FileOutputStream(new File(output, "evidence.json"))) {
                                stream.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        }
        assertEquals("预览测试不得触发业务云请求", 0, cloudRequests.get());
    }

    @Test public void cameraSelectionsApplyImmediatelySurviveRecreationAndReleaseBothUsbDevices() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, CameraSettingsActivity.class));
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> ready.set(activity.findViewById(R.id.settings_camera_texture) != null
                    && activity.findViewById(R.id.settings_camera_role).isShown()));
            return ready.get();
        }, 10_000, "摄像头设置页应显示预览与角色选择");

        // 选择镜头后立即保存并更新该角色的预览，不依赖额外保存操作。
        selectCamera(true, usb.get(1));
        selectRole(R.id.settings_preview_face);
        awaitPreview(usb.get(1), R.id.settings_preview_face);
        assertSavedCameras(usb.get(1), usb.get(1));
        recordPreview("face-applied", usb.get(1), R.id.settings_preview_face);

        selectCamera(false, usb.get(0));
        selectRole(R.id.settings_preview_hand);
        awaitPreview(usb.get(0), R.id.settings_preview_hand);
        assertSavedCameras(usb.get(1), usb.get(0));
        recordPreview("hand-applied", usb.get(0), R.id.settings_preview_hand);

        // 重建后仍使用即时保存的两路配置，切换角色不会覆盖另一角色的镜头。
        scenario.recreate();
        awaitPreview(usb.get(0), R.id.settings_preview_hand);
        assertSavedCameras(usb.get(1), usb.get(0));
        recordPreview("hand-after-recreation", usb.get(0), R.id.settings_preview_hand);
        selectRole(R.id.settings_preview_face);
        awaitPreview(usb.get(1), R.id.settings_preview_face);
        assertSavedCameras(usb.get(1), usb.get(0));
        selectRole(R.id.settings_preview_hand);
        awaitPreview(usb.get(0), R.id.settings_preview_hand);

        // 保留设置 Activity，只让它进入后台，另一宿主应可重新打开刚使用的 USB。
        scenario.moveToState(Lifecycle.State.CREATED);
        verifyProbeCanOpen(usb.get(0), "reopened-after-background");
        assertNotEquals("后台验收不能销毁设置页来掩盖 onPause 释放缺陷",
                Lifecycle.State.DESTROYED, scenario.getState());
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPreview(usb.get(0), R.id.settings_preview_hand);
        recordPreview("resumed-hand", usb.get(0), R.id.settings_preview_hand);

        selectRole(R.id.settings_preview_face);
        awaitPreview(usb.get(1), R.id.settings_preview_face);
        scenario.close();
        scenario = null;
        verifyProbeCanOpen(usb.get(1), "reopened-after-exit");
        completed = true;
    }

    private void selectCamera(boolean face, CameraSource.CameraInfo expected) {
        selectRole(face ? R.id.settings_preview_face : R.id.settings_preview_hand);
        scenario.onActivity(activity -> {
            View button = activity.findViewById(R.id.settings_choose_active_camera);
            assertNotNull("当前相机角色应提供镜头选择入口", button);
            assertTrue(button.isShown());
            assertTrue(button.performClick());
            AlertDialog dialog = (AlertDialog) readField(activity, "formDialog");
            assertNotNull("镜头选择应打开选项列表", dialog);
            assertTrue(dialog.isShowing());
            ListView choices = dialog.findViewById(R.id.department_filter_options);
            assertNotNull("镜头选择应提供自定义选项列表", choices);
            int position = findCameraPosition(choices.getAdapter(), expected.label);
            View row = choices.getAdapter().getView(position, null, choices);
            assertTrue(choices.performItemClick(row, position, choices.getAdapter().getItemId(position)));
            assertEquals("选择镜头不能改变当前角色", face ? R.id.settings_preview_face : R.id.settings_preview_hand,
                    ((RadioGroup) activity.findViewById(R.id.settings_camera_role)).getCheckedRadioButtonId());
            assertTrue("当前角色入口应显示即时保存的镜头", ((TextView) button).getText().toString().contains(expected.label));
        });
        instrumentation.waitForIdleSync();
    }

    private static int findCameraPosition(android.widget.Adapter adapter, String label) {
        for (int index = 0; index < adapter.getCount(); index++)
            if (label.equals(String.valueOf(adapter.getItem(index)))) return index;
        throw new AssertionError("选择列表缺少实际 USB 镜头：" + label);
    }

    private void selectRole(int roleId) {
        scenario.onActivity(activity -> {
            RadioButton role = activity.findViewById(roleId);
            assertNotNull(role);
            assertTrue("预览角色应可点击", role.isShown() && role.isEnabled());
            role.performClick();
        });
    }

    private void assertSavedCameras(CameraSource.CameraInfo face, CameraSource.CameraInfo hand) {
        assertEquals("身份镜头选择后应立即保存", face.id, preferences.getString("face_camera", ""));
        assertEquals("手部镜头选择后应立即保存", hand.id, preferences.getString("hand_camera", ""));
    }

    private void awaitPreview(CameraSource.CameraInfo expected, int roleId) {
        AtomicReference<String> lastState = new AtomicReference<>("尚未采集");
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                TextureView texture = activity.findViewById(R.id.settings_camera_texture);
                TextView message = activity.findViewById(R.id.settings_camera_preview_message);
                TextView label = activity.findViewById(R.id.settings_camera_preview_label);
                RadioGroup roles = activity.findViewById(R.id.settings_camera_role);
                RadioButton selected = activity.findViewById(roleId);
                CameraSource source = (CameraSource) readField(activity, "cameraPreviewSource");
                String actualId = source == null ? null : source.getCameraId();
                lastState.set("实际镜头=" + actualId + "，标签=" + label.getText() + "，提示=" + message.getText());
                if (!expected.id.equals(actualId) || roles.getCheckedRadioButtonId() != roleId
                        || !label.isShown() || !label.getText().toString().contains(expected.label)
                        || !label.getText().toString().contains(selected.getText())
                        || message.getVisibility() != View.GONE || !texture.isShown() || !texture.isAvailable()) return;
                Bitmap image = texture.getBitmap(96, 72);
                if (image == null) return;
                try { ready.set(hasPicture(image)); }
                finally { image.recycle(); }
            });
            return ready.get();
        }, 45_000, () -> "所选角色应显示实际镜头的非纯色画面：" + expected.label + "；" + lastState.get());
    }

    private void recordPreview(String name, CameraSource.CameraInfo expected, int roleId) throws Exception {
        AtomicReference<Bitmap> image = new AtomicReference<>();
        AtomicReference<JSONObject> row = new AtomicReference<>();
        scenario.onActivity(activity -> {
            TextureView texture = activity.findViewById(R.id.settings_camera_texture);
            TextView label = activity.findViewById(R.id.settings_camera_preview_label);
            image.set(texture.getBitmap());
            try {
                row.set(new JSONObject().put("sample", name).put("camera_id", expected.id)
                        .put("camera_label", expected.label).put("preview_label", label.getText())
                        .put("role", roleId == R.id.settings_preview_face ? "face" : "hand")
                        .put("width", texture.getWidth()).put("height", texture.getHeight()));
            } catch (org.json.JSONException error) { throw new AssertionError(error); }
        });
        saveBitmap(name + "-preview", image.get());
        saveBitmap(name + "-screen", instrumentation.getUiAutomation().takeScreenshot());
        evidence.put(row.get());
    }

    private void verifyProbeCanOpen(CameraSource.CameraInfo expected, String name) throws Exception {
        LinkedBlockingQueue<CameraSource.Frame> frames = new LinkedBlockingQueue<>(2);
        AtomicReference<String> error = new AtomicReference<>();
        // ActivityScenario.launch 会强制添加 CLEAR_TASK，破坏“仅后台释放”的验收前提。
        // 测试宿主通过 Instrumentation 正常入栈，保留仍在后台的设置 Activity。
        probe = (CameraProbeActivity) instrumentation.startActivitySync(new Intent(app, CameraProbeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                CameraProbeActivity activity = probe;
                activity.source = new CameraSource(activity, activity.preview, new CameraSource.Listener() {
                    @Override public void onFrame(CameraSource.Frame frame) {
                        if (!frames.offer(frame)) { frames.poll(); frames.offer(frame); }
                    }
                    @Override public void onError(String message) { error.set(message); }
                });
                activity.source.start(expected.id);
            });
            CameraSource.Frame frame = null;
            long deadline = SystemClock.elapsedRealtime() + 45_000;
            while (frame == null && SystemClock.elapsedRealtime() < deadline) {
                assertNull("设置页退出采集后，同一 USB 应能被另一宿主打开：" + error.get(), error.get());
                frame = frames.poll(100, TimeUnit.MILLISECONDS);
            }
            assertNotNull("另一宿主未收到释放后摄像头的采集帧：" + expected.id, frame);
            instrumentation.runOnMainSync(() -> assertEquals("重新打开不能回退到其他镜头",
                    expected.id, probe.source.getCameraId()));
            assertEquals((long) frame.width * frame.height * 3, frame.rgb.length);
            saveBitmap(name + "-rgb", frame.toBitmap());
            saveBitmap(name + "-screen", instrumentation.getUiAutomation().takeScreenshot());
            evidence.put(new JSONObject().put("sample", name).put("camera_id", expected.id)
                    .put("camera_label", expected.label).put("width", frame.width).put("height", frame.height));
        } finally {
            // CameraProbeActivity.onDestroy 关闭自身 source，不让测试宿主占用下一段预览。
            closeProbe();
        }
    }

    private void closeProbe() {
        CameraProbeActivity activity = probe;
        if (activity == null) return;
        instrumentation.runOnMainSync(activity::finish);
        await(() -> {
            AtomicReference<Boolean> destroyed = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> destroyed.set(activity.isDestroyed()));
            return destroyed.get();
        }, 5_000, "测试采集宿主应销毁并关闭相机源");
        probe = null;
    }

    private void saveBitmap(String name, Bitmap image) throws Exception {
        assertNotNull("证据图片不能是空引用：" + name, image);
        try {
            assertTrue("应获取真实非纯色画面：" + name, hasPicture(image));
            try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } finally { image.recycle(); }
    }

    private static boolean hasPicture(Bitmap image) {
        int first = image.getPixel(0, 0) & 0xffffff;
        for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 48))
            for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 64))
                if ((image.getPixel(x, y) & 0xffffff) != first) return true;
        return false;
    }

    private static Object readField(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // 弹窗与相机分别属于共享设置基类和摄像头页，沿继承层查找。
            } catch (IllegalAccessException error) {
                throw new AssertionError("无法读取验收需要的相机或弹窗实例：" + name, error);
            }
        }
        throw new AssertionError("缺少验收需要的相机或弹窗实例：" + name);
    }

    @SuppressWarnings("unchecked")
    private static void restorePreference(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
        else throw new AssertionError("未知设备配置类型：" + key);
    }

    private static void await(BooleanSupplier condition, long timeout, String message) {
        await(condition, timeout, () -> message);
    }

    private static void await(BooleanSupplier condition, long timeout, java.util.function.Supplier<String> message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message.get());
    }
}
