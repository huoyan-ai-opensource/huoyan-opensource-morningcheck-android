package cc.huoyan.opensource.morningcheck.hardware;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/** 用受控队列覆盖离页、排队、设备失败与双页面交接，不访问真实灯光。 */
public final class LightControllerTest {
    private final QueueExecutor worker = new QueueExecutor(), main = new QueueExecutor();
    private final List<String> commands = new ArrayList<>(), reports = new ArrayList<>();
    private final LightConfig config = serial("/dev/testLight");
    private FakeDevice device;
    private final LightController controller = new LightController(worker, main, value -> {
        commands.add("open:" + value.path);
        return device = new FakeDevice();
    });

    @Test public void queuedOnIsRevokedWhenPageLeaves() throws Exception {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        scope.close();
        drain();
        assertFalse(commands.contains("on"));
        assertTrue(commands.contains("off"));
        assertTrue(commands.contains("close"));
        assertTrue(reports.isEmpty());
        assertNull(owner(controller));
    }

    @Test public void leavingWhileDeviceOpensNeverSendsQueuedOn() {
        LightController.Scope[] page = new LightController.Scope[1];
        LightController blockedOpen = new LightController(worker, main, value -> {
            page[0].close();
            return device = new FakeDevice();
        });
        page[0] = blockedOpen.acquire(null);
        page[0].set(config, true);
        drain();
        assertFalse(commands.contains("on"));
        assertTrue(commands.contains("off"));
        assertTrue(commands.contains("close"));
    }

    @Test public void newOwnerClosesOldLampBeforeOpeningNewLamp() {
        LightController.Scope first = acquire();
        first.set(config, true);
        drain();
        commands.clear();
        LightController.Scope second = acquire();
        second.set(serial("/dev/newLight"), true);
        first.close();
        drain();
        assertEquals(List.of("off", "close", "open:/dev/newLight", "on"), commands);
    }

    @Test public void changingConfigurationClosesOriginalDeviceFirst() {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        drain();
        commands.clear();
        scope.set(serial("/dev/anotherLight"), true);
        drain();
        assertEquals(List.of("off", "close", "open:/dev/anotherLight", "on"), commands);
    }

    @Test public void onFailureAllowsSameConfigurationToRetry() {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        worker.runNext();
        worker.runNext();
        device.failOn = true;
        scope.turnOn(config);
        drain();
        assertTrue(reports.get(reports.size() - 1).startsWith("false:false:"));
        commands.clear();
        scope.set(config, true);
        drain();
        assertEquals(List.of("open:/dev/testLight", "on"), commands);
    }

    @Test public void repeatedFramesDeduplicateButExplicitTestsSendAgain() {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        drain();
        commands.clear();
        scope.set(config, true);
        drain();
        assertTrue(commands.isEmpty());
        scope.turnOn(config);
        drain();
        assertEquals(List.of("on"), commands);
    }

    @Test public void lateMainCallbackCannotUpdateClosedPage() {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        worker.drain();
        assertTrue(reports.isEmpty());
        scope.close();
        drain();
        assertTrue(reports.isEmpty());
    }

    @Test public void failingOffAndCloseStillReleaseHardwareAndOwner() throws Exception {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        drain();
        device.offFailure = new SecurityException("没有权限");
        device.closeFailure = new UnsatisfiedLinkError("关闭失败");
        commands.clear();
        scope.close();
        drain();
        assertTrue(commands.contains("close"));
        assertNull(owner(controller));
        commands.clear();
        acquire().set(config, true);
        drain();
        assertEquals(List.of("open:/dev/testLight", "on"), commands);
    }

    @Test public void offIoFailureIsRetriedThenDeviceClosed() {
        LightController.Scope scope = acquire();
        scope.set(config, true);
        drain();
        device.offFailure = new IOException("写入失败");
        commands.clear();
        scope.close();
        drain();
        assertEquals(List.of("off", "off", "off", "off", "close"), commands);
    }

    private LightController.Scope acquire() {
        return controller.acquire((on, success, message) -> reports.add(on + ":" + success + ":" + message));
    }
    private void drain() { worker.drain(); main.drain(); }
    private static LightConfig serial(String path) { return new LightConfig(LightConfig.SERIAL, path, 115200, "", 0, 1); }
    private static Object owner(LightController value) throws Exception {
        Field field = LightController.class.getDeclaredField("owner");
        field.setAccessible(true);
        return field.get(value);
    }
    private final class FakeDevice implements LightController.Device {
        boolean failOn;
        Throwable offFailure, closeFailure;
        @Override public void setOn(boolean on) throws IOException {
            commands.add(on ? "on" : "off");
            if (on && failOn) throw new IOException("开灯失败");
            if (!on && offFailure instanceof IOException) throw (IOException) offFailure;
            if (!on && offFailure instanceof RuntimeException) throw (RuntimeException) offFailure;
            if (!on && offFailure instanceof LinkageError) throw (LinkageError) offFailure;
        }
        @Override public void close() {
            commands.add("close");
            if (closeFailure instanceof RuntimeException) throw (RuntimeException) closeFailure;
            if (closeFailure instanceof LinkageError) throw (LinkageError) closeFailure;
        }
    }
    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() { tasks.remove().run(); }
        void drain() { while (!tasks.isEmpty()) runNext(); }
    }
}
