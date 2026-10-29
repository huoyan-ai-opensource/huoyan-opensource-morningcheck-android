package cc.huoyan.opensource.morningcheck.hardware;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.serialport.SerialPort;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * 页面持有的串口测温源。start 持续采集、readOnce 获取一次真实读数，所有业务回调均在主线程。
 * 仅使用调用方保存的路径/波特率，不自动切换串口或修改设备权限。
 */
public final class TemperatureSource implements AutoCloseable {
    private static final byte[] CONFIG = "ConfigT\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] QUERY = "CalcT\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final long READ_TIMEOUT_MS = 10_000;
    private static final long MODE_REPLY_IDLE_MS = 200;
    private final Executor main;
    private final Worker worker;
    private final LongSupplier clock;
    private final PortFactory ports;
    private final Listener listener;
    private volatile Session active;
    private boolean closed;

    public interface Listener {
        void onTemperature(double temperature);

        void onState(String message);

        default void onError(String message) {
        }
    }

    public interface ModeChangeListener {
        void onSuccess();

        void onError(String message);
    }

    public TemperatureSource(Listener listener) {
        this(requireListener(listener), mainExecutor(), new HandlerWorker(),
                SystemClock::elapsedRealtime, TemperatureSource::openPort);
    }

    // 测试替换串口与调度器，直接覆盖真实采集源的取消、超时及释放行为。
    TemperatureSource(Listener listener, Executor main, Worker worker,
                      LongSupplier clock, PortFactory ports) {
        this.listener = requireListener(listener);
        this.main = main;
        this.worker = worker;
        this.clock = clock;
        this.ports = ports;
    }

    /**
     * activeMode=false 接收 {36.65}；true 发送 ConfigT/CalcT 并接收文本或 CT 返回帧。
     */
    public void start(String path, int baud, boolean activeMode) {
        main.execute(() -> begin(path, baud, activeMode, false));
    }

    /**
     * 首个有效读数或 10 秒读取超时后关闭串口，不复用历史温度及前一会话的解析缓存。
     */
    public void readOnce(String path, int baud, boolean activeMode) {
        main.execute(() -> begin(path, baud, activeMode, true));
    }

    /**
     * 串行关闭原采集后只发送一次模式指令；收到本次 success 才确认，不修改读取协议。
     * 完成或失败均先释放端口，再回主线程。stop/close 会取消未交付的结果。
     */
    public void setMeasurementMode(String path, int baud, TemperatureMeasurementMode mode,
                                   ModeChangeListener callback) {
        if (callback == null) throw new IllegalArgumentException("缺少模式切换回调");
        main.execute(() -> {
            if (closed) return;
            stopInternal();
            if (path == null || path.trim().isEmpty() || baud <= 0) {
                callback.onError("请配置有效的测温串口与波特率");
                return;
            }
            if (mode == null) {
                callback.onError("请选择有效的测温模式");
                return;
            }
            Session next = new Session(path.trim(), baud, mode, callback);
            active = next;
            worker.post(next::open);
        });
    }

    private void begin(String path, int baud, boolean activeMode, boolean once) {
        if (closed) return;
        stopInternal();
        if (path == null || path.trim().isEmpty() || baud <= 0) {
            String message = "请配置有效的测温串口与波特率";
            listener.onState(message);
            listener.onError(message);
            return;
        }
        Session next = new Session(path.trim(), baud, activeMode, once);
        active = next;
        listener.onState("正在连接测温设备");
        // 所有会话共用串行队列；快速多次启动也必须等旧端口关闭后再打开新端口。
        worker.post(next::open);
    }

    public void stop() {
        main.execute(this::stopInternal);
    }

    /**
     * 持续采集的等待提示不会关闭会话；页面据此区分暂未测到与设备已经断开。
     */
    public boolean isRunning() {
        Session session = active;
        return session != null && !session.cancelled && !closed;
    }

    @Override
    public void close() {
        main.execute(() -> {
            if (closed) return;
            closed = true;
            stopInternal();
            worker.post(worker::shutdown);
        });
    }

    private void stopInternal() {
        Session old = active;
        active = null;
        if (old != null) {
            old.cancelled = true;
            worker.post(old::release);
        }
    }

    private final class Session {
        final String path;
        final int baud;
        final boolean activeMode;
        final boolean once;
        final TemperatureProtocol protocol;
        final TemperatureMeasurementMode measurementMode;
        final ModeChangeListener modeChangeListener;
        final TemperatureModeReply modeReply;
        final byte[] receive = new byte[512];
        volatile boolean cancelled;
        Port port;
        InputStream input;
        OutputStream output;
        long lastQueryAt;
        long lastReplyByteAt;
        boolean received;
        boolean released;
        double pendingTemperature;

        Session(String path, int baud, boolean activeMode, boolean once) {
            this(path, baud, activeMode, once, null, null);
        }

        Session(String path, int baud, TemperatureMeasurementMode mode, ModeChangeListener callback) {
            this(path, baud, false, false, mode, callback);
        }

        private Session(String path, int baud, boolean activeMode, boolean once,
                        TemperatureMeasurementMode mode, ModeChangeListener callback) {
            this.path = path;
            this.baud = baud;
            this.activeMode = activeMode;
            this.once = once;
            protocol = new TemperatureProtocol(activeMode);
            measurementMode = mode;
            modeChangeListener = callback;
            modeReply = mode == null ? null : new TemperatureModeReply();
        }

        boolean current() {
            return !cancelled && active == this;
        }

        void open() {
            if (!current()) {
                release();
                return;
            }
            try {
                port = ports.open(path, baud);
                input = port.input();
                output = port.output();
                // 只排空打开瞬间已有的字节，不把上次测量留在串口缓冲区的读数当成新结果。
                int stale = input.available();
                while (stale > 0 && current()) {
                    int read = input.read(receive, 0, Math.min(receive.length, stale));
                    if (read <= 0) break;
                    stale -= read;
                }
                if (!current()) {
                    release();
                    return;
                }
                if (measurementMode != null) {
                    // 指令按手册发送原始 ASCII，不附加 CR/LF，不自动重发。
                    write(measurementMode.command());
                    lastReplyByteAt = clock.getAsLong();
                } else {
                    if (activeMode) write(CONFIG);
                    lastQueryAt = clock.getAsLong() - 800;
                    state("测温设备已连接，等待读数");
                }
                worker.postDelayed(this::timeout, READ_TIMEOUT_MS);
                worker.post(this::poll);
            } catch (IOException | SecurityException | IllegalArgumentException |
                     LinkageError error) {
                if (current()) fail("测温设备无法打开：" + message(error) + "；请检查设备配置");
                else release();
            }
        }

        void poll() {
            if (!current()) {
                release();
                return;
            }
            try {
                if (measurementMode != null) {
                    pollModeReply();
                    return;
                }
                long now = clock.getAsLong();
                if (activeMode && now - lastQueryAt >= 800) {
                    write(QUERY);
                    lastQueryAt = now;
                }
                // 非阻塞串口配合 available，单次读取有上限，查询与关闭不会被无数据的 read 卡住。
                pendingTemperature = Double.NaN;
                for (int chunk = 0; chunk < 8 && current(); chunk++) {
                    int available = input.available();
                    if (available <= 0) break;
                    int read = input.read(receive, 0, Math.min(receive.length, available));
                    if (read < 0) throw new IOException("测温设备已断开");
                    if (read == 0) break;
                    protocol.accept(receive, read, value -> {
                        if (!once || !Double.isFinite(pendingTemperature))
                            pendingTemperature = value;
                    });
                    if (once && Double.isFinite(pendingTemperature)) break;
                }
                if (Double.isFinite(pendingTemperature)) temperature(pendingTemperature);
                if (current()) worker.postDelayed(this::poll, 100);
            } catch (IOException | SecurityException error) {
                if (current()) fail("测温采集中断：" + message(error));
            }
        }

        void timeout() {
            if (!current() || received) return;
            if (measurementMode != null) {
                finishModeChange("10 秒内未收到 success，模式切换未确认，请重试");
                return;
            }
            String message = "10 秒内未收到有效体温，请检查测温模块并重试";
            if (once) fail(message);
            else {
                // 持续晨检采集允许无人测温；提示一次后继续等待硬件读数。
                main.execute(() -> {
                    if (current() && !closed) {
                        listener.onState(message);
                        if (current() && !closed) listener.onError(message);
                    }
                });
            }
        }

        void write(byte[] command) throws IOException {
            output.write(command);
            output.flush();
        }

        void pollModeReply() throws IOException {
            TemperatureModeReply.Result result = TemperatureModeReply.Result.PENDING;
            long now = clock.getAsLong();
            for (int chunk = 0; chunk < 8 && current(); chunk++) {
                int available = input.available();
                if (available <= 0) break;
                int read = input.read(receive, 0, Math.min(receive.length, available));
                if (read < 0) throw new IOException("测温设备已断开");
                if (read == 0) break;
                lastReplyByteAt = now;
                result = modeReply.accept(receive, read);
                if (result != TemperatureModeReply.Result.PENDING) break;
            }
            if (result == TemperatureModeReply.Result.PENDING && now - lastReplyByteAt >= MODE_REPLY_IDLE_MS)
                result = modeReply.finishWhenIdle();
            if (result == TemperatureModeReply.Result.SUCCESS) finishModeChange(null);
            else if (result == TemperatureModeReply.Result.FAILURE)
                finishModeChange("测温模块未返回 success，模式切换失败");
            else if (current()) worker.postDelayed(this::poll, 100);
        }

        void finishModeChange(String error) {
            cancelled = true;
            release();
            main.execute(() -> {
                if (active != this || closed) return;
                active = null;
                if (error == null) modeChangeListener.onSuccess();
                else modeChangeListener.onError(error);
            });
        }

        void temperature(double value) {
            if (!current()) return;
            boolean first = !received;
            received = true;
            if (once) {
                cancelled = true;
                release();
                main.execute(() -> {
                    // 单次读取已释放端口，但结果仍属于本会话；切页或新读取会使该结果失效。
                    if (active != this || closed) return;
                    listener.onState("已获取真实体温");
                    if (active != this || closed) return;
                    active = null;
                    listener.onTemperature(value);
                });
            } else {
                main.execute(() -> {
                    if (current() && !closed) {
                        if (first) listener.onState("测温设备正常");
                        if (current() && !closed) listener.onTemperature(value);
                    }
                });
            }
        }

        void state(String message) {
            main.execute(() -> {
                if (current() && !closed) listener.onState(message);
            });
        }

        void fail(String message) {
            if (measurementMode != null) {
                finishModeChange(message);
                return;
            }
            cancelled = true;
            release();
            main.execute(() -> {
                if (active != this || closed) return;
                listener.onState(message);
                if (active != this || closed) return;
                active = null;
                listener.onError(message);
            });
        }

        void release() {
            if (released) return;
            released = true;
            if (port != null) {
                port.close();
                port = null;
            }
            input = null;
            output = null;
        }
    }

    interface Worker {
        void post(Runnable action);

        void postDelayed(Runnable action, long delayMs);

        void shutdown();
    }

    interface Port {
        InputStream input();

        OutputStream output();

        void close();
    }

    interface PortFactory {
        Port open(String path, int baud) throws IOException;
    }

    private static final class HandlerWorker implements Worker {
        private final HandlerThread thread = new HandlerThread("晨检测温采集");
        private final Handler handler;

        HandlerWorker() {
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        @Override
        public void post(Runnable action) {
            handler.post(action);
        }

        @Override
        public void postDelayed(Runnable action, long delayMs) {
            handler.postDelayed(action, delayMs);
        }

        @Override
        public void shutdown() {
            thread.quitSafely();
        }
    }

    private static Executor mainExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return action -> {
            if (Looper.myLooper() == Looper.getMainLooper()) action.run();
            else handler.post(action);
        };
    }

    private static Port openPort(String path, int baud) throws IOException {
        File device = new ReadWriteDeviceFile(path);
        if (!device.getCanonicalPath().startsWith("/dev/"))
            throw new IOException("所选路径不是串口设备");
        if (!device.exists()) throw new IOException("设备不存在：" + path);
        device.canRead();
        device.canWrite();
        SerialPort serial = SerialPort.newBuilder(device, baud).dataBits(8).parity(0).stopBits(1)
                .flags(OsConstants.O_NONBLOCK).build();
        return new Port() {
            @Override
            public InputStream input() {
                return serial.getInputStream();
            }

            @Override
            public OutputStream output() {
                return serial.getOutputStream();
            }

            @Override
            public void close() {
                serial.tryClose();
            }
        };
    }

    /**
     * 依赖默认在权限不足时尝试 su/chmod。此包装在权限检查时直接抛错，
     * 使它永远不会进入提权分支；后续 native open 仍受系统权限/SELinux 检查。
     */
    private static final class ReadWriteDeviceFile extends File {
        ReadWriteDeviceFile(String path) {
            super(path);
        }

        @Override
        public boolean canRead() {
            if (!super.canRead()) throw new SecurityException("没有串口读取权限");
            return true;
        }

        @Override
        public boolean canWrite() {
            if (!super.canWrite()) throw new SecurityException("没有串口写入权限");
            return true;
        }
    }

    private static Listener requireListener(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("缺少测温回调");
        return listener;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
