package cc.huoyan.opensource.morningcheck.hardware;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

/** 用可控串口字节流和时钟验证真实采集源，不依赖 Android 主线程或真实设备。 */
public class TemperatureSourceTest {
    @Test public void onceDiscardsOldBytesAndReturnsFirstFreshValidReadingAfterRelease() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        port.input.feed("{39.9}");
        f.once(false);
        f.worker.runReady();
        f.main.drain();
        assertTrue(f.values.isEmpty());
        assertEquals(0, port.closes);

        port.input.feed("{0}{NaN}{bad}{36.5}{38.6}");
        f.worker.advance(100);
        assertEquals("成功回调到达页面前必须关闭串口", 1, port.closes);
        assertTrue(f.values.isEmpty());
        f.main.drain();
        assertEquals(Arrays.asList(36.5), f.values);
        assertEquals(0, f.liveAtTemperature.get(0).intValue());
        f.worker.advance(20_000);
        f.main.drain();
        assertEquals(1, f.values.size());
        assertTrue(f.errors.isEmpty());
        assertEquals(1, port.closes);
    }

    @Test public void activeOnceUsesLegacyCommandsAndWaitsForCompleteCtFrame() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.once(true);
        f.worker.runReady();
        assertEquals("ConfigT\r\nCalcT\r\n", port.commands());
        port.input.feed(new byte[]{'C', 'T', 36, 65, 0});
        f.worker.advance(100);
        f.main.drain();
        assertTrue(f.values.isEmpty());
        assertEquals(0, port.closes);
        port.input.feed(new byte[5]);
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(36.65), f.values);
        assertEquals(1, port.closes);
        f.worker.advance(20_000);
        assertEquals("结束后不能继续向已关闭串口查询", "ConfigT\r\nCalcT\r\n", port.commands());
    }

    @Test public void onceTimesOutAtTenSecondsAndReportsErrorAfterRelease() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.once(true);
        f.worker.runReady();
        f.worker.advance(9_999);
        f.main.drain();
        assertEquals(0, port.closes);
        assertTrue(f.errors.isEmpty());
        f.worker.advance(1);
        assertEquals(1, port.closes);
        assertTrue(f.errors.isEmpty());
        f.main.drain();
        assertTrue(f.values.isEmpty());
        assertEquals(1, f.errors.size());
        assertTrue(f.errors.get(0).contains("10 秒"));
        assertEquals(f.errors.get(0), f.states.get(f.states.size() - 1));
        assertEquals(0, f.liveAtError.get(0).intValue());
        String commands = port.commands();
        f.worker.advance(10_000);
        f.main.drain();
        assertEquals(commands, port.commands());
        assertEquals(1, f.errors.size());
    }

    @Test public void continuousTimeoutReportsOnceAndKeepsWaitingForHardware() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.start(false);
        assertTrue(f.source.isRunning());
        f.worker.runReady();
        f.worker.advance(10_000);
        f.main.drain();
        assertEquals(1, f.errors.size());
        assertEquals(0, port.closes);
        assertTrue("无人测温的等待提示不能结束持续采集", f.source.isRunning());
        port.input.feed("{36.7}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(36.7), f.values);
        assertEquals("测温设备正常", f.states.get(f.states.size() - 1));
        f.worker.advance(20_000);
        f.main.drain();
        assertEquals(1, f.errors.size());
        f.main.runOnMain(f.source::stop);
        assertFalse(f.source.isRunning());
        f.worker.runReady();
        assertEquals(1, port.closes);
    }

    @Test public void continuousReadingKeepsLatestFramesAndStopsQueryingWhenPageStops() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.start(true);
        f.worker.runReady();
        f.main.drain();
        assertEquals("ConfigT\r\nCalcT\r\n", port.commands());
        port.input.feed("{36.1}{36.6}");
        f.worker.advance(100);
        f.main.drain();
        port.input.feed("{36.8}");
        f.worker.advance(700);
        f.main.drain();
        assertEquals(Arrays.asList(36.6, 36.8), f.values);
        assertTrue(f.source.isRunning());
        assertEquals(0, port.closes);
        assertEquals("ConfigT\r\nCalcT\r\nCalcT\r\n", port.commands());
        port.input.feed("{39.5}");
        f.worker.advance(100);
        f.main.runOnMain(f.source::stop);
        assertFalse(f.source.isRunning());
        f.worker.runReady();
        f.main.drain();
        assertEquals("离开页面后已排队的读数不能更新结果", Arrays.asList(36.6, 36.8), f.values);
        assertEquals(1, port.closes);
        String commands = port.commands();
        f.worker.advance(20_000);
        f.main.drain();
        assertEquals("关闭后不能继续发送查询指令", commands, port.commands());
    }

    @Test public void readFailureReleasesPortAndReportsMatchingStateAndError() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.start(false);
        f.worker.runReady();
        port.input.error = new IOException("串口读失败");
        f.worker.advance(100);
        assertEquals(1, port.closes);
        f.main.drain();
        assertEquals(Arrays.asList("测温采集中断：串口读失败"), f.errors);
        assertFalse("IO 失败必须与暂未测到体温区分", f.source.isRunning());
        assertEquals(f.errors.get(0), f.states.get(f.states.size() - 1));
        assertTrue(f.values.isEmpty());
    }

    @Test public void openFailureHasExplicitErrorAndNextRequestCanRecover() {
        Fixture f = new Fixture();
        f.ports.error = new IOException("设备不存在");
        f.once(false);
        f.worker.runReady();
        f.main.drain();
        assertEquals(1, f.errors.size());
        assertTrue(f.errors.get(0).contains("设备不存在"));
        assertEquals(f.errors.get(0), f.states.get(f.states.size() - 1));
        assertTrue(f.values.isEmpty());
        f.ports.error = null;
        FakePort next = f.ports.prepare();
        f.once(false);
        f.worker.runReady();
        next.input.feed("{36.6}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(36.6), f.values);
        assertEquals(1, next.closes);
    }

    @Test public void rapidReplacementClosesOldPortBeforeOpeningLastRequestOnly() {
        Fixture f = new Fixture();
        FakePort old = f.ports.prepare();
        f.start(false);
        f.worker.runReady();
        FakePort next = f.ports.prepare();
        f.main.runOnMain(() -> {
            f.source.readOnce("/dev/ttyS1", 115200, false);
            f.source.readOnce(" /dev/ttyS4 ", 921600, true);
        });
        f.worker.runReady();
        f.main.drain();
        assertEquals(Arrays.asList("打开:/dev/ttyS0:115200", "关闭:/dev/ttyS0",
                "打开:/dev/ttyS4:921600"), f.ports.events);
        assertEquals("重复启动不能同时持有旧端口与新端口", 1, f.ports.maxLive);
        assertEquals(1, old.closes);
        next.input.feed("{36.8}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(36.8), f.values);
        assertEquals(1, next.closes);
    }

    @Test public void queuedOldReadingCannotCompleteReplacementRequest() {
        for (boolean once : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            FakePort old = f.ports.prepare();
            if (once) f.once(false); else f.start(false);
            f.worker.runReady();
            old.input.feed("{39.5}");
            f.worker.advance(100);
            assertTrue(f.values.isEmpty());
            FakePort next = f.ports.prepare();
            f.once(false);
            f.worker.runReady();
            f.main.drain();
            assertTrue("旧会话迟到结果不能被当成本次获取的读数", f.values.isEmpty());
            next.input.feed("{36.4}");
            f.worker.advance(100);
            f.main.drain();
            assertEquals(Arrays.asList(36.4), f.values);
            assertEquals(1, old.closes);
            assertEquals(1, next.closes);
        }
    }

    @Test public void stopDropsQueuedCompletionAndAllowsFreshSession() {
        Fixture f = new Fixture();
        FakePort old = f.ports.prepare();
        f.once(false);
        f.worker.runReady();
        old.input.feed("{36.9}");
        f.worker.advance(100);
        f.main.runOnMain(f.source::stop);
        f.main.drain();
        f.worker.runReady();
        assertTrue(f.values.isEmpty());
        assertEquals(1, old.closes);
        FakePort next = f.ports.prepare();
        f.once(false);
        f.worker.runReady();
        next.input.feed("{36.3}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(36.3), f.values);
    }

    @Test public void closeCancelsPendingOpenAndCannotBeRestarted() {
        Fixture f = new Fixture();
        f.ports.prepare();
        f.once(false);
        f.main.runOnMain(() -> {
            f.source.close();
            f.source.close();
            f.source.start("/dev/ttyS0", 115200, false);
            f.source.readOnce("/dev/ttyS0", 115200, false);
        });
        f.worker.runReady();
        f.main.drain();
        assertTrue(f.ports.events.isEmpty());
        assertEquals(1, f.worker.shutdowns);
        f.worker.advance(20_000);
        assertTrue(f.values.isEmpty());
        assertTrue(f.errors.isEmpty());
    }

    @Test public void invalidConfigurationStopsOldSessionWithoutOpeningAnotherPort() {
        Fixture f = new Fixture();
        FakePort old = f.ports.prepare();
        f.start(false);
        f.worker.runReady();
        f.main.runOnMain(() -> f.source.readOnce(" ", 0, false));
        f.worker.runReady();
        f.main.drain();
        assertEquals(1, old.closes);
        assertEquals(1, f.errors.size());
        assertEquals("请配置有效的测温串口与波特率", f.errors.get(0));
        assertEquals(f.errors.get(0), f.states.get(f.states.size() - 1));
    }

    @Test public void pageClosingFromCompletionStateSuppressesTemperatureCallback() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.closeOnCompletedState = true;
        f.once(false);
        f.worker.runReady();
        port.input.feed("{36.5}");
        f.worker.advance(100);
        f.main.drain();
        f.worker.runReady();
        assertTrue(f.values.isEmpty());
        assertEquals(1, port.closes);
        assertEquals(1, f.worker.shutdowns);
    }

    @Test
    public void measurementCommandsRequireSuccessAndNeverSendReadProtocolCommands() {
        String[] commands = {"*inside", "*outside", "*trigger"};
        for (TemperatureMeasurementMode mode : TemperatureMeasurementMode.values()) {
            Fixture f = new Fixture();
            FakePort port = f.ports.prepare();
            f.mode(mode);
            f.worker.runReady();
            f.main.drain();
            assertEquals(commands[mode.ordinal()], port.commands());
            assertTrue(f.appliedModes.isEmpty());
            assertTrue(f.values.isEmpty());
            assertTrue(f.states.isEmpty());
            port.input.feed("success\r");
            f.worker.advance(100);
            assertEquals("完成回调之前必须释放串口", 1, port.closes);
            assertTrue(f.appliedModes.isEmpty());
            f.main.drain();
            assertEquals(Arrays.asList(mode), f.appliedModes);
            assertEquals(Arrays.asList(0), f.liveAtModeResult);
            f.worker.advance(20_000);
            f.main.drain();
            assertEquals("不能重发模式指令或追加读数指令", commands[mode.ordinal()], port.commands());
            assertEquals(1, f.appliedModes.size());
            assertTrue(f.modeErrors.isEmpty());
        }
    }

    @Test
    public void measurementReplyDropsStaleSuccessAndAcceptsFragmentedFreshReplyAmongReadings() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        port.input.feed("success\r{39.9}");
        f.mode(TemperatureMeasurementMode.OUTSIDE);
        f.worker.runReady();
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        port.input.feed("{36.2}{36.6}suc");
        f.worker.advance(100);
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        port.input.feed("cess\r{37.1}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.OUTSIDE), f.appliedModes);
        assertTrue("控制期间温度帧不能完成请求或推进晨检", f.values.isEmpty());
        assertTrue(f.modeErrors.isEmpty());
        assertEquals(1, port.closes);
    }

    @Test
    public void modeReplyIgnoresTheTailOfATemperatureFramePartlyDiscardedBeforeSending() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        port.input.feed("{36.");
        f.mode(TemperatureMeasurementMode.INSIDE);
        f.worker.runReady();
        port.input.feed("65}success\r{36.7}");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.INSIDE), f.appliedModes);
        assertTrue(f.modeErrors.isEmpty());
        assertTrue(f.values.isEmpty());
        assertEquals(1, port.closes);
    }

    @Test
    public void fragmentedTemperatureTailWaitsForItsBoundaryWithoutAcceptingNoiseAsSuccess() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        port.input.feed("{36.");
        f.mode(TemperatureMeasurementMode.TRIGGER);
        f.worker.runReady();
        port.input.feed("6");
        f.worker.advance(600);
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        assertTrue(f.modeErrors.isEmpty());
        port.input.feed("5}su");
        f.worker.advance(100);
        port.input.feed("ccess\r");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.TRIGGER), f.appliedModes);
        assertTrue(f.modeErrors.isEmpty());

        for (String noise : Arrays.asList("not}success\r", "unsuccessful}success\r",
                "successfully}success\r", "noise65}success\r", "65success\r")) {
            Fixture invalid = new Fixture();
            FakePort invalidPort = invalid.ports.prepare();
            invalid.mode(TemperatureMeasurementMode.OUTSIDE);
            invalid.worker.runReady();
            invalidPort.input.feed(noise);
            invalid.worker.advance(100);
            invalid.main.drain();
            assertTrue(noise, invalid.appliedModes.isEmpty());
            assertEquals(noise, 1, invalid.modeErrors.size());
            assertEquals(1, invalidPort.closes);
        }
    }

    @Test
    public void bareSuccessWaitsForIdleButAnExtendedWordCannotConfirmMode() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.mode(TemperatureMeasurementMode.TRIGGER);
        f.worker.runReady();
        port.input.feed("success");
        f.worker.advance(100);
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        f.worker.advance(199);
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        f.worker.advance(1);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.TRIGGER), f.appliedModes);

        Fixture extended = new Fixture();
        FakePort extendedPort = extended.ports.prepare();
        extended.mode(TemperatureMeasurementMode.INSIDE);
        extended.worker.runReady();
        extendedPort.input.feed("success");
        extended.worker.advance(100);
        extendedPort.input.feed("fully\r");
        extended.worker.advance(100);
        extended.main.drain();
        assertTrue(extended.appliedModes.isEmpty());
        assertEquals(1, extended.modeErrors.size());
        assertEquals(1, extendedPort.closes);
    }

    @Test
    public void otherRepliesAreErrorsRatherThanModeConfirmation() {
        for (String reply : Arrays.asList("OK\r", "error\r", "unsuccessful\r", "Success\r")) {
            Fixture f = new Fixture();
            FakePort port = f.ports.prepare();
            f.mode(TemperatureMeasurementMode.INSIDE);
            f.worker.runReady();
            port.input.feed(reply);
            f.worker.advance(100);
            f.main.drain();
            assertTrue(reply, f.appliedModes.isEmpty());
            assertEquals(reply, 1, f.modeErrors.size());
            assertEquals(1, port.closes);
            assertEquals(Arrays.asList(0), f.liveAtModeResult);
            assertTrue("模式失败使用独立回调，不冒充读取错误", f.errors.isEmpty());
        }
    }

    @Test
    public void temperatureFramesWithoutSuccessTimeOutAndReleaseModePort() {
        Fixture f = new Fixture();
        FakePort port = f.ports.prepare();
        f.mode(TemperatureMeasurementMode.OUTSIDE);
        f.worker.runReady();
        port.input.feed("{36.5}{37.0}");
        f.worker.advance(9_999);
        f.main.drain();
        assertTrue(f.appliedModes.isEmpty());
        assertTrue(f.modeErrors.isEmpty());
        assertEquals(0, port.closes);
        f.worker.advance(1);
        assertEquals(1, port.closes);
        f.main.drain();
        assertEquals(1, f.modeErrors.size());
        assertTrue(f.modeErrors.get(0).contains("10 秒"));
        assertTrue(f.values.isEmpty());
        assertFalse(f.source.isRunning());
    }

    @Test
    public void modeOpenWriteAndReadFailuresReportAfterRelease() {
        Fixture open = new Fixture();
        open.ports.error = new IOException("打开失败");
        open.mode(TemperatureMeasurementMode.INSIDE);
        open.worker.runReady();
        open.main.drain();
        assertEquals(1, open.modeErrors.size());
        assertTrue(open.modeErrors.get(0).contains("打开失败"));

        for (boolean writing : new boolean[]{true, false}) {
            Fixture f = new Fixture();
            FakePort port = f.ports.prepare();
            if (writing) port.writeError = new IOException("写入失败");
            f.mode(TemperatureMeasurementMode.OUTSIDE);
            f.worker.runReady();
            if (!writing) {
                port.input.error = new IOException("读取失败");
                f.worker.advance(100);
            }
            assertEquals(1, port.closes);
            assertTrue(f.modeErrors.isEmpty());
            f.main.drain();
            assertTrue(f.appliedModes.isEmpty());
            assertEquals(1, f.modeErrors.size());
            assertTrue(f.modeErrors.get(0).contains(writing ? "写入失败" : "读取失败"));
            assertEquals(Arrays.asList(0), f.liveAtModeResult);
        }
    }

    @Test
    public void modeChangeClosesContinuousReadingBeforeOpeningControlPort() {
        Fixture f = new Fixture();
        FakePort reading = f.ports.prepare();
        f.start(true);
        f.worker.runReady();
        FakePort control = f.ports.prepare();
        f.mode(TemperatureMeasurementMode.TRIGGER);
        f.worker.runReady();
        assertEquals(Arrays.asList("打开:/dev/ttyS0:115200", "关闭:/dev/ttyS0",
                "打开:/dev/ttyS0:115200"), f.ports.events);
        assertEquals(1, f.ports.maxLive);
        assertEquals(1, reading.closes);
        assertEquals("*trigger", control.commands());
        control.input.feed("success\r");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.TRIGGER), f.appliedModes);
        assertFalse("是否恢复测试由页面决定，硬件层不能偷偷重开采集", f.source.isRunning());
    }

    @Test
    public void replacementModeDropsQueuedOldConfirmationAndKeepsNewRequest() {
        Fixture f = new Fixture();
        FakePort old = f.ports.prepare();
        f.mode(TemperatureMeasurementMode.OUTSIDE);
        f.worker.runReady();
        old.input.feed("success\r");
        f.worker.advance(100);
        FakePort next = f.ports.prepare();
        f.mode(TemperatureMeasurementMode.INSIDE);
        f.worker.runReady();
        f.main.drain();
        assertTrue("旧请求的回执不能保存为新模式", f.appliedModes.isEmpty());
        f.worker.advance(9_800);
        f.main.drain();
        assertTrue("旧请求的超时不能关闭新会话", f.modeErrors.isEmpty());
        assertEquals(0, next.closes);
        next.input.feed("success\r");
        f.worker.advance(100);
        f.main.drain();
        assertEquals(Arrays.asList(TemperatureMeasurementMode.INSIDE), f.appliedModes);
        assertEquals(1, old.closes);
        assertEquals(1, next.closes);
        assertEquals(1, f.ports.maxLive);
    }

    @Test
    public void stoppingOrClosingDropsQueuedModeConfirmationAndTimeout() {
        for (boolean closing : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            FakePort port = f.ports.prepare();
            f.mode(TemperatureMeasurementMode.TRIGGER);
            f.worker.runReady();
            port.input.feed("success\r");
            f.worker.advance(100);
            f.main.runOnMain(() -> {
                if (closing) f.source.close();
                else f.source.stop();
            });
            f.worker.runReady();
            f.main.drain();
            f.worker.advance(20_000);
            f.main.drain();
            assertTrue(f.appliedModes.isEmpty());
            assertTrue(f.modeErrors.isEmpty());
            assertEquals(1, port.closes);
        }

        Fixture pending = new Fixture();
        FakePort unopened = pending.ports.prepare();
        pending.mode(TemperatureMeasurementMode.INSIDE);
        pending.main.runOnMain(pending.source::close);
        pending.worker.runReady();
        pending.main.drain();
        assertTrue(pending.ports.events.isEmpty());
        assertEquals("", unopened.commands());
        assertTrue(pending.appliedModes.isEmpty());
    }

    @Test
    public void modeValuesDoNotGuessUnknownHardwareState() {
        assertNull(TemperatureMeasurementMode.fromValue(null));
        assertNull(TemperatureMeasurementMode.fromValue(""));
        assertNull(TemperatureMeasurementMode.fromValue("unknown"));
        for (TemperatureMeasurementMode mode : TemperatureMeasurementMode.values()) {
            assertSame(mode, TemperatureMeasurementMode.fromValue(mode.getValue()));
        }
    }

    private static final class Fixture {
        final MainQueue main = new MainQueue();
        final FakeWorker worker = new FakeWorker();
        final FakePorts ports = new FakePorts();
        final List<Double> values = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<Integer> liveAtTemperature = new ArrayList<>();
        final List<Integer> liveAtError = new ArrayList<>();
        final List<TemperatureMeasurementMode> appliedModes = new ArrayList<>();
        final List<String> modeErrors = new ArrayList<>();
        final List<Integer> liveAtModeResult = new ArrayList<>();
        boolean closeOnCompletedState;
        final TemperatureSource source = new TemperatureSource(new TemperatureSource.Listener() {
            @Override public void onTemperature(double temperature) {
                assertTrue("温度回调必须在主线程", main.dispatching);
                values.add(temperature);
                liveAtTemperature.add(ports.live);
            }
            @Override public void onState(String message) {
                assertTrue("状态回调必须在主线程", main.dispatching);
                states.add(message);
                if (closeOnCompletedState && "已获取真实体温".equals(message)) source.close();
            }
            @Override public void onError(String message) {
                assertTrue("错误回调必须在主线程", main.dispatching);
                errors.add(message);
                liveAtError.add(ports.live);
            }
        }, main, worker, () -> worker.now, ports);

        void once(boolean active) {
            main.runOnMain(() -> source.readOnce("/dev/ttyS0", 115200, active));
        }
        void start(boolean active) {
            main.runOnMain(() -> source.start("/dev/ttyS0", 115200, active));
        }
        void mode(TemperatureMeasurementMode mode) {
            main.runOnMain(() -> source.setMeasurementMode("/dev/ttyS0", 115200, mode,
                    new TemperatureSource.ModeChangeListener() {
                        @Override
                        public void onSuccess() {
                            assertTrue("模式切换回调必须在主线程", main.dispatching);
                            appliedModes.add(mode);
                            liveAtModeResult.add(ports.live);
                        }

                        @Override
                        public void onError(String message) {
                            assertTrue("模式切换错误必须在主线程", main.dispatching);
                            modeErrors.add(message);
                            liveAtModeResult.add(ports.live);
                        }
                    }));
        }
    }

    private static final class MainQueue implements Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean dispatching;
        @Override public void execute(Runnable action) {
            if (dispatching) action.run();
            else pending.addLast(action);
        }
        void runOnMain(Runnable action) {
            boolean previous = dispatching;
            dispatching = true;
            try { action.run(); } finally { dispatching = previous; }
        }
        void drain() {
            while (!pending.isEmpty()) runOnMain(pending.removeFirst());
        }
    }

    private static final class FakeWorker implements TemperatureSource.Worker {
        final PriorityQueue<Task> pending = new PriorityQueue<>(Comparator
                .comparingLong((Task task) -> task.due).thenComparingLong(task -> task.order));
        long now;
        long order;
        int shutdowns;
        @Override public void post(Runnable action) { postDelayed(action, 0); }
        @Override public void postDelayed(Runnable action, long delayMs) {
            if (shutdowns == 0) pending.add(new Task(now + delayMs, order++, action));
        }
        @Override public void shutdown() { shutdowns++; pending.clear(); }
        void runReady() { advance(0); }
        void advance(long elapsed) {
            long target = now + elapsed;
            while (!pending.isEmpty() && pending.peek().due <= target) {
                Task task = pending.remove();
                now = task.due;
                task.action.run();
            }
            now = target;
        }
    }

    private static final class Task {
        final long due;
        final long order;
        final Runnable action;
        Task(long due, long order, Runnable action) {
            this.due = due; this.order = order; this.action = action;
        }
    }

    private static final class FakePorts implements TemperatureSource.PortFactory {
        final ArrayDeque<FakePort> prepared = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        int live;
        int maxLive;
        IOException error;
        FakePort prepare() {
            FakePort port = new FakePort(this);
            prepared.addLast(port);
            return port;
        }
        @Override public TemperatureSource.Port open(String path, int baud) throws IOException {
            if (error != null) throw error;
            FakePort port = prepared.removeFirst();
            port.path = path;
            live++;
            maxLive = Math.max(maxLive, live);
            events.add("打开:" + path + ":" + baud);
            return port;
        }
    }

    private static final class FakePort implements TemperatureSource.Port {
        final FakePorts owner;
        final FakeInput input = new FakeInput();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        String path;
        int closes;
        IOException writeError;
        FakePort(FakePorts owner) { this.owner = owner; }
        @Override public InputStream input() { return input; }
        @Override public OutputStream output() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    if (writeError != null) throw writeError;
                    output.write(value);
                }

                @Override
                public void write(byte[] values, int offset, int length) throws IOException {
                    if (writeError != null) throw writeError;
                    output.write(values, offset, length);
                }
            };
        }
        @Override public void close() {
            closes++;
            owner.live--;
            owner.events.add("关闭:" + path);
        }
        String commands() { return new String(output.toByteArray(), StandardCharsets.US_ASCII); }
    }

    private static final class FakeInput extends InputStream {
        final ArrayDeque<Integer> bytes = new ArrayDeque<>();
        IOException error;
        void feed(String value) { feed(value.getBytes(StandardCharsets.US_ASCII)); }
        void feed(byte[] value) { for (byte b : value) bytes.addLast(b & 255); }
        @Override public int available() throws IOException {
            if (error != null) throw error;
            return bytes.size();
        }
        @Override public int read() { return bytes.isEmpty() ? -1 : bytes.removeFirst(); }
        @Override public int read(byte[] target, int offset, int length) {
            int count = Math.min(length, bytes.size());
            for (int i = 0; i < count; i++) target[offset + i] = (byte) read();
            return count;
        }
    }
}
