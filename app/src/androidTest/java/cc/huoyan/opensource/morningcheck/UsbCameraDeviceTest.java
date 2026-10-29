package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;

/** 显式启用的真实 USB 采集回归；不进入业务页面，不改设备设置和员工资料。 */
@RunWith(AndroidJUnit4.class)
public final class UsbCameraDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final LinkedBlockingQueue<Sample> frames = new LinkedBlockingQueue<>(8);
    private final AtomicReference<String> error = new AtomicReference<>();
    private final JSONArray evidence = new JSONArray();
    private final List<CameraSource.CameraInfo> usb = new ArrayList<>();
    private ActivityScenario<CameraProbeActivity> scenario;
    private File output;

    @Before public void prepareHardwareFixture() throws Exception {
        assumeTrue("只有明确指定硬件验收时才打开现场摄像头", "true".equals(
                InstrumentationRegistry.getArguments().getString("enableUsbHardware")));
        Context context = instrumentation.getTargetContext();
        assertEquals("先允许相机权限再执行硬件验收", PackageManager.PERMISSION_GRANTED,
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA));
        for (CameraSource.CameraInfo info : CameraSource.listCameraInfo(context))
            if (info.id.startsWith("usb:")) usb.add(info);
        assertEquals("YF023D 应枚举到两路独立 USB 视频设备", 2, usb.size());
        assertEquals("两路摄像头不能被相同 ID 合并", 2,
                new HashSet<>(java.util.Arrays.asList(usb.get(0).id, usb.get(1).id)).size());
        output = new File(context.getCacheDir(), "usb-camera-evidence");
        assertTrue(output.isDirectory() || output.mkdirs());
        scenario = ActivityScenario.launch(new Intent(context, CameraProbeActivity.class));
        scenario.onActivity(activity -> activity.source = new CameraSource(activity, activity.preview,
                new CameraSource.Listener() {
                    @Override public void onFrame(CameraSource.Frame frame) {
                        Sample sample = new Sample(activity.source.getCameraId(), frame);
                        if (!frames.offer(sample)) { frames.poll(); frames.offer(sample); }
                    }
                    @Override public void onError(String message) { error.set(message); }
                }));
    }

    @After public void releaseHardware() throws Exception {
        if (scenario != null) scenario.close();
        if (output != null) try (FileOutputStream stream = new FileOutputStream(new File(output, "evidence.json"))) {
            stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void bothUsbDevicesDeliverFramesAndCanReopenAfterSwitch() throws Exception {
        for (int index = 0; index < usb.size(); index++) {
            CameraSource.CameraInfo info = usb.get(index);
            start(info.id);
            Sample sample = awaitFrame(info.id);
            record(info, sample, "camera-" + index);
            scenario.onActivity(activity -> activity.source.stop());
        }
        // 连续更改选择时，排队中的打开请求不能在最终选项之后恢复旧镜头。
        error.set(null);
        scenario.onActivity(activity -> {
            activity.source.start(usb.get(1).id);
            activity.source.start(usb.get(0).id);
        });
        frames.clear();
        awaitFrame(usb.get(0).id);
        for (int index = 0; index < 3; index++) {
            Sample sample = awaitFrame(usb.get(0).id);
            assertEquals("最终切换后不能回流另一台镜头的帧", usb.get(0).id, sample.id);
        }
        scenario.onActivity(activity -> activity.source.stop());
        instrumentation.waitForIdleSync();
        frames.clear();
        assertNull("停止后不能继续交付采集帧", frames.poll(1200, TimeUnit.MILLISECONDS));
        start(usb.get(1).id);
        record(usb.get(1), awaitFrame(usb.get(1).id), "camera-reopened");
        // YF023D 的旧配置为 0；直连 UVC 后 external HAL 可能撤回这个别名。
        // 系统仍提供时应收到原 ID 的帧，否则必须明确提示重选，不能偷偷切到另一支镜头。
        verifyLegacyExternalChoice("0");
        start(usb.get(0).id);
        awaitFrame(usb.get(0).id);
    }

    private void verifyLegacyExternalChoice(String id) throws Exception {
        start(id);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String failure = error.get();
            if (failure != null) {
                assertTrue("失效的旧配置应引导用户重选：" + failure, failure.contains("重新选择"));
                assertTrue("不可回退到未选择的 USB 镜头", frames.isEmpty());
                evidence.put(new JSONObject().put("id", id).put("sample", "legacy-camera2")
                        .put("outcome", "requires_reselection").put("message", failure));
                return;
            }
            Sample sample = frames.poll(200, TimeUnit.MILLISECONDS);
            if (sample != null) {
                assertEquals("旧配置不能自动映射到任意 USB 镜头", id, sample.id);
                evidence.put(new JSONObject().put("id", id).put("sample", "legacy-camera2")
                        .put("outcome", "frame").put("width", sample.frame.width).put("height", sample.frame.height));
                return;
            }
        }
        fail("旧配置应返回画面或明确提示，不能停在黑屏");
    }

    private void start(String id) {
        error.set(null);
        frames.clear();
        scenario.onActivity(activity -> activity.source.start(id));
        Log.i("USB摄像头验收", "打开 " + id);
        Bundle progress = new Bundle();
        progress.putString("stream", "\n打开摄像头 " + id + "，如有系统 USB 权限提示需允许\n");
        instrumentation.sendStatus(2, progress);
    }

    private Sample awaitFrame(String expectedId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 45000;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNull("摄像头错误：" + error.get(), error.get());
            Sample sample = frames.poll(200, TimeUnit.MILLISECONDS);
            if (sample == null) continue;
            assertEquals("返回帧必须来自选定摄像头", expectedId, sample.id);
            assertEquals((long) sample.frame.width * sample.frame.height * 3, sample.frame.rgb.length);
            assertTrue(sample.frame.width > 0 && sample.frame.height > 0);
            return sample;
        }
        throw new AssertionError("指定摄像头未在时限内交付 RGB 帧：" + expectedId + "；" + error.get());
    }

    private void record(CameraSource.CameraInfo info, Sample sample, String name) throws Exception {
        CRC32 checksum = new CRC32();
        checksum.update(sample.frame.rgb);
        int min = 255, max = 0;
        for (byte value : sample.frame.rgb) { min = Math.min(min, value & 255); max = Math.max(max, value & 255); }
        assertTrue("RGB 帧应包含现场画面而非全零空缓冲", max > min);
        JSONObject row = new JSONObject().put("id", info.id).put("label", info.label)
                .put("width", sample.frame.width).put("height", sample.frame.height)
                .put("crc32", checksum.getValue()).put("sample", name);
        evidence.put(row);
        Log.i("USB摄像头验收", row.toString());
        Bitmap rgbImage = sample.frame.toBitmap();
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + "-rgb.png"))) {
            assertTrue(rgbImage.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { rgbImage.recycle(); }
        AtomicReference<Bitmap> preview = new AtomicReference<>();
        scenario.onActivity(activity -> {
            assertTrue("需要真实可用的预览表面", activity.preview.isAvailable());
            preview.set(activity.preview.getBitmap());
        });
        Bitmap image = preview.get();
        assertNotNull("预览应能读取画面", image);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + "-preview.png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
        // TextureView.getBitmap 不包含 View 的缩放矩阵，屏幕截图用于核对实际留白和比例。
        Bitmap screen = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screen);
        try (FileOutputStream stream = new FileOutputStream(new File(output, name + "-screen.png"))) {
            assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { screen.recycle(); }
    }

    private static final class Sample {
        final String id;
        final CameraSource.Frame frame;
        Sample(String id, CameraSource.Frame frame) { this.id = id; this.frame = frame; }
    }
}
