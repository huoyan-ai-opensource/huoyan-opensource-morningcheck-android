package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.domain.ActivationScanCode;
import cc.huoyan.opensource.morningcheck.login.QrFrameDecoder;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;

/** 扫码返回与生命周期在本机夹具验证；不使用真实相机、不访问真实 SDK 或门店。 */
@RunWith(AndroidJUnit4.class)
public final class SdkActivationScanUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, ?> oldDevice, oldBinding, scanDevice;
    private MockWebServer endpoint;
    private ActivityScenario<SdkActivationScanActivity> scenario;
    private Application.ActivityLifecycleCallbacks cameraIsolation;
    private long sdkGeneration, activationId;

    @Before public void isolateCameraAndEndpoint() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        oldDevice = device.getAll();
        oldBinding = binding.getAll();
        instrumentation.runOnMainSync(() -> {
            app.sdkSession().disconnect();
            sdkGeneration = app.sdkSession().generation();
            activationId = app.sdkSession().activation().id;
        });
        endpoint = new MockWebServer();
        endpoint.enqueue(new MockResponse().setResponseCode(503));
        endpoint.start(java.net.InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:ACTIVATION-TEST-NO-DEVICE").commit());
        scanDevice = device.getAll();
        cameraIsolation = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (!(activity instanceof SdkActivationScanActivity)) return;
                String orientation = InstrumentationRegistry.getArguments().getString("design_orientation", "");
                if (!orientation.isEmpty()) activity.setRequestedOrientation("portrait".equals(orientation)
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                write(activity, "permissionRequested", true);
                write(activity, "permissionRequestInFlight", false);
                write(activity, "cameraFailure", "扫码测试不采集真实摄像头");
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

    @After public void restoreConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null && cameraIsolation != null) app.unregisterActivityLifecycleCallbacks(cameraIsolation);
        } finally {
            if (device != null && oldDevice != null) restore(device, oldDevice);
            if (binding != null && oldBinding != null) restore(binding, oldBinding);
            if (endpoint != null) endpoint.shutdown();
        }
    }

    @Test public void cancelReturnsCanceledWithoutChangingSdkOrShop() {
        launch();
        scenario.onActivity(activity -> activity.findViewById(R.id.sdk_activation_scan_back).performClick());
        assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        assertNull(scenario.getResult().getResultData());
        assertExternalStateUnchanged();
    }

    @Test public void generatedActivationQrDecodesAndReturnsWithoutSubmitting() throws Exception {
        byte[] rgb = qr("{\"serial\":\"Z2FBW9L7M78R586H\"}");
        assertEquals("Z2FB-W9L7-M78R-586H", ActivationScanCode.parse(new QrFrameDecoder().decode(rgb, 320, 240)));
        launch();
        scenario.onActivity(activity -> injectFrame(activity, rgb));
        assertEquals(Activity.RESULT_OK, scenario.getResult().getResultCode());
        Intent returned = scenario.getResult().getResultData();
        assertNotNull(returned);
        assertEquals("Z2FB-W9L7-M78R-586H", returned.getStringExtra(SdkActivationScanActivity.EXTRA_CODE));
        assertExternalStateUnchanged();
    }

    @Test public void shopQrIsRejectedWithoutReturningOrBinding() throws Exception {
        byte[] rgb = qr("{\"shop_id\":\"TEST-ONLY-SHOP\"}");
        assertThrows(IllegalArgumentException.class,
                () -> ActivationScanCode.parse(new QrFrameDecoder().decode(rgb, 320, 240)));
        launch();
        scenario.onActivity(activity -> injectFrame(activity, rgb));
        // 解码由页面自己的后台线程执行；等同一队列收尾，避免测试和 UI 回写抢跑。
        scenario.onActivity(activity -> {
            java.util.concurrent.ExecutorService executor = read(activity, "decoderThread");
            try { executor.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception error) { throw new AssertionError("二维码解码未收尾", error); }
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            assertFalse("错误二维码不能关闭扫码页", activity.isFinishing());
            assertEquals(app.getString(R.string.sdk_activation_scan_invalid_title),
                    ((TextView) activity.findViewById(R.id.sdk_activation_scan_status_title)).getText().toString());
            activity.findViewById(R.id.sdk_activation_scan_back).performClick();
        });
        assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        assertExternalStateUnchanged();
    }

    @Test public void recreationPreservesTemporaryCameraWithoutSavingPreferences() {
        launch();
        scenario.onActivity(activity -> write(activity, "selectedCamera", "usb:TEMPORARY-SCAN-ONLY"));
        scenario.recreate();
        scenario.onActivity(activity -> {
            assertEquals("usb:TEMPORARY-SCAN-ONLY", read(activity, "selectedCamera"));
            assertEquals("usb:ACTIVATION-TEST-NO-DEVICE", device.getString("face_camera", ""));
            assertFalse((Boolean) read(activity, "scanning"));
            activity.findViewById(R.id.sdk_activation_scan_back).performClick();
        });
        assertEquals(Activity.RESULT_CANCELED, scenario.getResult().getResultCode());
        assertExternalStateUnchanged();
    }

    @Test public void scannerFitsPhoneAndTabletInBothOrientationsWithoutScrolling() {
        instrumentation.runOnMainSync(() -> {
            assertFits(360, 640);
            assertFits(640, 360);
            assertFits(800, 1280);
            assertFits(1280, 800);
        });
    }

    private void launch() {
        scenario = ActivityScenario.launchActivityForResult(new Intent(app, SdkActivationScanActivity.class));
    }

    private void assertExternalStateUnchanged() {
        assertEquals("扫码不能改写 SDK 配置或摄像头选择", scanDevice, device.getAll());
        assertEquals("扫码不能改写门店归属", oldBinding, binding.getAll());
        assertEquals("扫码不能创建 SDK 请求", 0, endpoint.getRequestCount());
        instrumentation.runOnMainSync(() -> {
            assertEquals(sdkGeneration, app.sdkSession().generation());
            assertEquals(activationId, app.sdkSession().activation().id);
            assertFalse(app.sdkSession().operation().running);
        });
    }

    private void assertFits(int widthDp, int heightDp) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.screenWidthDp = widthDp;
        config.screenHeightDp = heightDp;
        config.smallestScreenWidthDp = Math.min(widthDp, heightDp);
        config.orientation = widthDp > heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        config.fontScale = 1;
        Context context = new ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme);
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_sdk_activation_scan, null);
        // 权限被拒绝是控件最多的常用错误分支；重试与权限设置均须完整显示。
        root.findViewById(R.id.sdk_activation_scan_choose_camera).setVisibility(View.GONE);
        root.findViewById(R.id.sdk_activation_scan_retry).setVisibility(View.VISIBLE);
        root.findViewById(R.id.sdk_activation_scan_permission_settings).setVisibility(View.VISIBLE);
        ((TextView) root.findViewById(R.id.sdk_activation_scan_status_message)).setText(R.string.sdk_activation_scan_permission_denied);
        float density = context.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(widthDp * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(heightDp * density), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_back));
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_preview));
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_status_title));
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_status_message));
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_retry));
        assertContained(root, root.findViewById(R.id.sdk_activation_scan_permission_settings));
        assertNoScrolling(root);
    }

    private static void assertContained(ViewGroup root, View view) {
        assertNotNull(view);
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue("控件应有可见尺寸：" + bounds, bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件不能被页面裁切：" + bounds, bounds.left >= 0 && bounds.top >= 0
                && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof ViewGroup && parent != root) {
            ViewGroup ancestor = (ViewGroup) parent;
            Rect local = new Rect();
            view.getDrawingRect(local);
            ancestor.offsetDescendantRectToMyCoords(view, local);
            assertTrue("祖先容器不能裁切控件：" + local, local.left >= 0 && local.top >= 0
                    && local.right <= ancestor.getWidth() && local.bottom <= ancestor.getHeight());
            parent = ancestor.getParent();
        }
    }

    private static void assertNoScrolling(View view) {
        assertFalse("扫码页不能依赖滑动操作", view instanceof ScrollView || view instanceof HorizontalScrollView);
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                assertNoScrolling(((ViewGroup) view).getChildAt(index));
    }

    private static byte[] qr(String value) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 240,
                Collections.singletonMap(EncodeHintType.CHARACTER_SET, "UTF-8"));
        byte[] rgb = new byte[320 * 240 * 3];
        for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
            int offset = (y * 320 + x) * 3;
            byte pixel = matrix.get(x, y) ? 0 : (byte) 255;
            rgb[offset] = pixel; rgb[offset + 1] = pixel; rgb[offset + 2] = pixel;
        }
        return rgb;
    }

    private static void injectFrame(SdkActivationScanActivity activity, byte[] rgb) {
        write(activity, "scanning", true);
        try {
            Method decode = SdkActivationScanActivity.class.getDeclaredMethod("decodeFrame", CameraSource.Frame.class);
            decode.setAccessible(true);
            decode.invoke(activity, new CameraSource.Frame(rgb, 320, 240));
        } catch (ReflectiveOperationException error) { throw new AssertionError("无法注入二维码测试帧", error); }
    }

    private static void write(Object owner, String name, Object value) {
        try {
            Field field = SdkActivationScanActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError("无法隔离扫码状态", error); }
    }

    @SuppressWarnings("unchecked") private static <T> T read(Object owner, String name) {
        try {
            Field field = SdkActivationScanActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError("无法读取扫码夹具状态", error); }
    }

    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, ?> snapshot) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) edit.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) edit.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) edit.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) edit.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) edit.putStringSet(entry.getKey(), (Set<String>) value);
        }
        assertTrue(edit.commit());
    }
}
