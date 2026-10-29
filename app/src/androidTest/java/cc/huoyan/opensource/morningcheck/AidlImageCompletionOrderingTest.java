package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.morningcheck.app.aidl.DetectionListParcel;
import cc.huoyan.morningcheck.app.aidl.FaceListParcel;
import cc.huoyan.morningcheck.app.aidl.FrameParcel;
import cc.huoyan.morningcheck.app.aidl.ISdkCommService;
import cc.huoyan.morningcheck.app.aidl.RegisterResultParcel;
import cc.huoyan.morningcheck.sdk.client.HuoyanClient;

/** 用假服务控制真实图像载体与调度线程，不绑定 SDK、不请求业务网络。 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 27)
public final class AidlImageCompletionOrderingTest {
    @Test public void faceCallbackCanImmediatelyCheckHeadOrRegisterAfterItsCarrierCloses() throws Exception {
        for (String nextCommand : new String[]{"checkHead", "register"}) {
            try (Harness harness = new Harness(false, true)) {
                CountDownLatch finished = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                List<Integer> codes = new ArrayList<>();
                harness.request("detectFace", frameParams().put("max_faces", 3), (code, message, data) -> {
                    try {
                        codes.add(code);
                        assertEquals(0, code);
                        assertFalse("交付结果前必须已关闭上一帧句柄", harness.detectFrame.get().shmKey.getFileDescriptor().valid());
                        JSONObject next = frameParams();
                        next.put("max_detections", 8).put("person_id", 21);
                        harness.request(nextCommand, next, (nextCode, nextMessage, nextData) -> {
                            codes.add(nextCode);
                            finished.countDown();
                        });
                    } catch (Throwable error) {
                        failure.set(error);
                        finished.countDown();
                    } finally {
                        // 确认回调中提交的下一帧已在 control 入队，再放行上一帧工作线程。
                        harness.control.execute(harness.control.callbackSubmitted::countDown);
                    }
                });
                assertTrue("串接图像调用未完成", finished.await(5, TimeUnit.SECONDS));
                if (failure.get() != null) throw new AssertionError("图像交付顺序不正确", failure.get());
                assertEquals(List.of(0, 0), codes);
                assertEquals(1, harness.nextCalls);
            }
        }
    }

    @Test public void cancelAndTimeoutDoNotReleaseImageSlotBeforeRealCallReturns() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            try (Harness harness = new Harness(true, false)) {
                CountDownLatch terminal = new CountDownLatch(1), rejected = new CountDownLatch(1);
                AtomicReference<Integer> terminalCode = new AtomicReference<>(), rejectedCode = new AtomicReference<>();
                String id = harness.request("detectFace", frameParams().put("max_faces", 3), (code, message, data) -> {
                    terminalCode.set(code);
                    terminal.countDown();
                });
                assertTrue(harness.detectEntered.await(3, TimeUnit.SECONDS));
                if (timeout) harness.timeout(id); else harness.cancel(id);
                assertTrue(terminal.await(3, TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(timeout ? HuoyanClient.ERROR_RESULT_UNKNOWN : HuoyanClient.ERROR_CANCELLED), terminalCode.get());
                assertTrue("真实调用仍运行时不能关闭载体", harness.detectFrame.get().shmKey.getFileDescriptor().valid());
                harness.request("checkHead", frameParams().put("max_detections", 8), (code, message, data) -> {
                    rejectedCode.set(code);
                    rejected.countDown();
                });
                assertTrue(rejected.await(3, TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(HuoyanClient.ERROR_BUSY), rejectedCode.get());
                assertEquals(0, harness.nextCalls);
                harness.releaseDetect.countDown();
                harness.awaitImageRelease();
                assertFalse(harness.detectFrame.get().shmKey.getFileDescriptor().valid());
            }
        }
    }

    private static JSONObject frameParams() throws Exception {
        return new JSONObject().put("width", 2).put("height", 1).put("format", "rgb");
    }

    private static final class Harness implements AutoCloseable {
        final Object client;
        final Class<?> clientType;
        final ControlledScheduler control;
        final CountDownLatch detectEntered = new CountDownLatch(1), releaseDetect = new CountDownLatch(1);
        final AtomicReference<FrameParcel> detectFrame = new AtomicReference<>();
        volatile int nextCalls;

        Harness(boolean blockDetect, boolean gateCompletion) throws Exception {
            clientType = Class.forName("cc.huoyan.morningcheck.sdk.client.AidlSdkClient");
            Constructor<?> constructor = clientType.getDeclaredConstructor(Context.class, HuoyanClient.Listener.class);
            constructor.setAccessible(true);
            client = constructor.newInstance(ApplicationProvider.getApplicationContext(), null);
            ScheduledThreadPoolExecutor original = (ScheduledThreadPoolExecutor) field("control").get(client);
            control = new ControlledScheduler(gateCompletion);
            field("control").set(client, control);
            original.shutdownNow();
            ISdkCommService service = (ISdkCommService) Proxy.newProxyInstance(ISdkCommService.class.getClassLoader(),
                    new Class<?>[]{ISdkCommService.class}, (proxy, method, args) -> {
                        if ("detectFace".equals(method.getName())) {
                            detectFrame.set((FrameParcel) args[0]);
                            detectEntered.countDown();
                            if (blockDetect && !releaseDetect.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("假服务没有收到结束信号");
                            return FaceListParcel.emptyOk();
                        }
                        if ("checkHead".equals(method.getName())) {
                            nextCalls++;
                            return DetectionListParcel.emptyOk();
                        }
                        if ("register".equals(method.getName())) {
                            nextCalls++;
                            return RegisterResultParcel.ok(31);
                        }
                        throw new AssertionError("本用例不应调用其他 SDK 方法：" + method.getName());
                    });
            field("service").set(client, service);
            field("connected").setBoolean(client, true);
        }

        String request(String command, JSONObject params, HuoyanClient.Callback callback) throws Exception {
            return (String) method("request", String.class, JSONObject.class, byte[].class, HuoyanClient.Callback.class)
                    .invoke(client, command, params, new byte[6], callback);
        }

        void cancel(String id) throws Exception { method("cancel", String.class).invoke(client, id); }

        void timeout(String id) throws Exception {
            control.submit(() -> {
                try {
                    Object pending = ((Map<?, ?>) field("pending").get(client)).get(id);
                    assertNotNull(pending);
                    method("timeout", pending.getClass()).invoke(client, pending);
                } catch (Exception failure) { throw new AssertionError(failure); }
            }).get(3, TimeUnit.SECONDS);
        }

        void awaitImageRelease() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                if (control.submit(() -> {
                    try { return field("activeImageId").get(client) == null; }
                    catch (Exception failure) { throw new AssertionError(failure); }
                }).get(1, TimeUnit.SECONDS)) return;
                Thread.sleep(10);
            }
            fail("真实调用结束后未释放图像槽");
        }

        Field field(String name) throws Exception {
            Field value = clientType.getDeclaredField(name);
            value.setAccessible(true);
            return value;
        }

        Method method(String name, Class<?>... parameters) throws Exception {
            Method value = clientType.getDeclaredMethod(name, parameters);
            value.setAccessible(true);
            return value;
        }

        @Override public void close() throws Exception {
            releaseDetect.countDown();
            control.callbackSubmitted.countDown();
            method("destroy").invoke(client);
            control.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static final class ControlledScheduler extends ScheduledThreadPoolExecutor {
        final CountDownLatch callbackSubmitted = new CountDownLatch(1);
        final AtomicBoolean gate;

        ControlledScheduler(boolean gateCompletion) {
            super(1);
            gate = new AtomicBoolean(gateCompletion);
            setRemoveOnCancelPolicy(true);
        }

        @Override public void execute(Runnable command) {
            super.execute(command);
            // 固定旧竞态的时序：工作线程第一次交付后先让主线程回调提交下一帧。
            if ("晨检-AIDL-调用".equals(Thread.currentThread().getName()) && gate.compareAndSet(true, false)) {
                try {
                    if (!callbackSubmitted.await(4, TimeUnit.SECONDS)) throw new AssertionError("回调未提交下一帧");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }
}
