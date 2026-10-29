package cc.huoyan.opensource.morningcheck.hardware;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 进程内只有一条灯光 IO 队列；页面所有权和代次共同阻止离页后排队的旧开灯。
 */
public final class LightController {
    private static final String TAG = "MorningLight";
    private static LightController instance;
    private final Executor worker, main;
    private final DeviceFactory devices;
    private Scope owner;
    private long generation;
    // 以下硬件状态只在 worker 队列访问。
    private Device device;
    private LightConfig deviceConfig;

    public interface Listener {
        void onState(boolean on, boolean success, String message);
    }

    public interface Device {
        void setOn(boolean on) throws IOException;

        void close();
    }

    public interface DeviceFactory {
        Device open(LightConfig config) throws IOException;
    }

    private LightController() {
        this(Executors.newSingleThreadExecutor(task -> new Thread(task, "晨检灯光控制")),
                mainExecutor(), LightDevice::open);
    }

    public LightController(Executor worker, Executor main, DeviceFactory devices) {
        this.worker = worker;
        this.main = main;
        this.devices = devices;
    }

    public static synchronized LightController getInstance() {
        if (instance == null) instance = new LightController();
        return instance;
    }

    public synchronized Scope acquire(Listener listener) {
        owner = new Scope(listener);
        generation++;
        // 新页面接管前先关掉旧控制链路，不能把测试灯状态交给其他页面。
        worker.execute(() -> {
            try {
                releaseDevice();
            } catch (IOException error) {
                Log.e(TAG, "交接页面时关灯失败：" + message(error));
            }
        });
        return owner;
    }

    public final class Scope implements AutoCloseable {
        private final Listener listener;
        private LightConfig config;
        private boolean requestedOn, released;

        private Scope(Listener listener) {
            this.listener = listener;
        }

        public void set(LightConfig config, boolean on) {
            request(config, on, false);
        }

        /**
         * 现场测试允许重复发送；流水线仍用 set 去掉连续帧的重复命令。
         */
        public void turnOn(LightConfig config) {
            request(config, true, true);
        }

        private void request(LightConfig config, boolean on, boolean force) {
            final long request;
            synchronized (LightController.this) {
                if (released || owner != this) return;
                if (config == null) throw new IllegalArgumentException("缺少灯光配置");
                // 连续检测帧只更新业务状态，不重复发送同一条开灯命令。
                if (!force && config.equals(this.config) && requestedOn == on) return;
                this.config = config;
                requestedOn = on;
                request = ++generation;
            }
            worker.execute(() -> apply(this, request, config, on, false));
        }

        /**
         * 即使页面没有开过灯也允许显式发送关灯，用于现场验证设备当前状态。
         */
        public void turnOff(LightConfig config) {
            request(config, false, true);
        }

        @Override
        public void close() {
            final long request;
            final LightConfig saved;
            synchronized (LightController.this) {
                if (released) return;
                released = true;
                if (owner != this) return;
                saved = config;
                request = ++generation;
            }
            worker.execute(() -> apply(this, request, saved, false, true));
        }
    }

    private synchronized boolean current(Scope scope, long request, boolean closing) {
        return owner == scope && generation == request && (closing || !scope.released);
    }

    private void apply(Scope scope, long request, LightConfig config, boolean on, boolean closing) {
        if (!current(scope, request, closing)) return;
        try {
            if (device != null && !deviceConfig.equals(config)) releaseDevice();
            if (config != null && device == null) {
                device = devices.open(config);
                deviceConfig = config;
            }
            // 端口打开期间可能发生离页，仍使用刚打开的旧配置关灯，不发送旧开灯。
            if (!current(scope, request, closing)) {
                releaseDevice();
                return;
            }
            if (device != null) device.setOn(on);
            if (!on && device != null) {
                device.close();
                device = null;
                deviceConfig = null;
            }
            report(scope, request, on, true, on ? "开灯命令已发送，请确认实际灯光" : "关灯命令已发送", closing);
        } catch (IOException | RuntimeException | LinkageError error) {
            String detail = message(error);
            // 多 GPIO 可能部分成功，开灯失败也逐节点回写关灯，避免残留亮灯。
            try {
                releaseDevice();
            } catch (IOException cleanup) {
                detail += "；关灯失败：" + message(cleanup);
            }
            Log.e(TAG, (on ? "开灯" : "关灯") + "失败：" + detail);
            synchronized (this) {
                if (current(scope, request, closing)) scope.config = null;
            }
            report(scope, request, false, false, (on ? "开灯失败：" : "关灯失败：") + detail, closing);
        } finally {
            synchronized (this) {
                // 关闭完成后释放捕获 Activity 的监听器；新页面已接管时不清理新 owner。
                if (closing && current(scope, request, true)) owner = null;
            }
        }
    }

    private void releaseDevice() throws IOException {
        if (device == null) return;
        IOException failure = null;
        // 写失败再尝试关命令；同一串行队列中不会被后来的开灯插入。
        Device closingDevice = device;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    closingDevice.setOn(false);
                    failure = null;
                    break;
                } catch (IOException error) {
                    failure = error;
                } catch (RuntimeException | LinkageError error) {
                    failure = new IOException(message(error), error);
                }
            }
        } finally {
            try {
                closingDevice.close();
            } catch (RuntimeException | LinkageError error) {
                if (failure == null) failure = new IOException(message(error), error);
                else failure.addSuppressed(error);
            }
            device = null;
            deviceConfig = null;
        }
        if (failure != null) throw failure;
    }

    private void report(Scope scope, long request, boolean on, boolean success, String message, boolean closing) {
        main.execute(() -> {
            if (current(scope, request, closing) && !scope.released && scope.listener != null)
                scope.listener.onState(on, success, message);
        });
    }

    private static Executor mainExecutor() {
        Handler main = new Handler(Looper.getMainLooper());
        return action -> main.post(action);
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.getClass().getSimpleName() : text;
    }
}
