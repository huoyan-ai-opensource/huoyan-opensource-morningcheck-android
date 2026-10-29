package cc.huoyan.opensource.morningcheck.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 所有 UVC 开关和资源释放均串行运行在 CameraSource 的采集线程。
 */
final class UsbCameraCapture {
    interface Callback {
        void onReady(int width, int height);

        void onSizes(List<String> sizes);

        void onFrame(CameraSource.Frame frame);

        void onError(String message);
    }

    private final Context context;
    private final Handler handler;
    private final UsbCameraCatalog.Entry entry;
    private final SurfaceTexture texture;
    private final BooleanSupplier isCurrent;
    private final Callback callback;
    private final String resolution;
    private USBMonitor monitor;
    private USBMonitor.UsbControlBlock usbConnection;
    private UVCCamera camera;
    private Surface surface;
    private volatile boolean closed;
    private volatile int width;
    private volatile int height;
    private volatile long lastFrameAt = -500;

    UsbCameraCapture(Context context, Handler handler, UsbCameraCatalog.Entry entry,
                     SurfaceTexture texture, String resolution, BooleanSupplier isCurrent, Callback callback) {
        this.context = context;
        this.handler = handler;
        this.entry = entry;
        this.texture = texture;
        this.resolution = resolution;
        this.isCurrent = isCurrent;
        this.callback = callback;
    }

    void open() {
        if (!current()) return;
        try {
            // 1.0.13 修复了 target 35 下旧库的 PendingIntent/动态接收器注册异常。
            monitor = new USBMonitor(context, usbListener, handler);
            monitor.register();
            monitor.requestPermission(entry.device);
        } catch (RuntimeException | LinkageError error) {
            fail("无法申请 USB 摄像头权限：" + readable(error));
        }
    }

    private final USBMonitor.OnDeviceConnectListener usbListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            // 只打开用户选定设备；库的自动枚举不得抢占另一支镜头。
        }

        @Override
        public void onDetach(UsbDevice device) {
            if (matches(device)) fail("USB 摄像头已拔出，请连接后重新进入");
        }

        @Override
        public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock control, boolean createNew) {
            if (!current() || !matches(device)) {
                control.close(true);
                return;
            }
            if (camera != null) return;
            usbConnection = control;
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null || !manager.hasPermission(device)) {
                fail("USB 摄像头权限已失效，请重新允许访问");
                return;
            }
            startPreview(control);
        }

        @Override
        public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock control) {
            if (matches(device)) fail("USB 摄像头连接已中断，请重新进入");
        }

        @Override
        public void onCancel(UsbDevice device) {
            if (matches(device)) fail("未允许访问 USB 摄像头，请重新进入并允许访问");
        }

        @Override
        public void onError(UsbDevice device, USBMonitor.USBException error) {
            if (matches(device)) fail("无法连接 USB 摄像头：" + readable(error));
        }
    };

    private void startPreview(USBMonitor.UsbControlBlock control) {
        try {
            camera = new UVCCamera(null);
            int result = camera.open(control);
            if (result != 0)
                throw new IllegalStateException("设备被占用或不支持 UVC（" + result + "）");
            List<Size> sizes = new ArrayList<>(camera.getSupportedSizeList());
            List<String> available = new ArrayList<>();
            for (Size size : sizes) {
                String value = size.width + "x" + size.height;
                if (size.width > 0 && size.height > 0 && !available.contains(value))
                    available.add(value);
            }
            callback.onSizes(available);
            sizes.sort(Comparator.comparingDouble(UsbCameraCapture::sizeScore));
            boolean configured = false;
            for (Size size : sizes) {
                if (!current()) return;
                if (size.width <= 0 || size.height <= 0) continue;
                if (!resolution.isEmpty() && !resolution.equals(size.width + "x" + size.height))
                    continue;
                try {
                    camera.setPreviewSize(size);
                    configured = true;
                    break;
                } catch (IllegalArgumentException ignored) {
                    // 部分镜头的 MJPEG 声明与实际不符，继续尝试其声明的其他格式。
                }
            }
            if (!configured) throw new IllegalStateException("摄像头没有可用的预览分辨率");
            Size size = camera.getPreviewSize();
            width = size.width;
            height = size.height;
            texture.setDefaultBufferSize(width, height);
            surface = new Surface(texture);
            camera.setPreviewDisplay(surface);
            camera.setFrameCallback(this::consumeFrame, UVCCamera.PIXEL_FORMAT_RGB);
            if (!current()) return;
            callback.onReady(width, height);
            camera.startPreview();
            handler.postDelayed(() -> {
                // 原生 startPreview 没有返回值，避免打开失败时页面永久停在黑色预览。
                if (current() && lastFrameAt < 0)
                    fail("USB 摄像头未返回画面，请确认未被其他应用占用后重试");
            }, 10_000);
        } catch (RuntimeException | LinkageError error) {
            fail("无法打开 USB 摄像头：" + readable(error));
        }
    }

    private void consumeFrame(ByteBuffer buffer) {
        // 原生回调的缓冲区下一帧即复用，必须在回调内复制，不能把 ByteBuffer 交给业务层。
        if (!current()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameAt < 500) return;
        lastFrameAt = now;
        int frameWidth = width;
        int frameHeight = height;
        long length = (long) frameWidth * frameHeight * 3;
        if (length <= 0 || length > Integer.MAX_VALUE || buffer == null || buffer.capacity() < length) {
            handler.post(() -> fail("USB 摄像头返回了不完整的 RGB 画面"));
            return;
        }
        byte[] rgb = new byte[(int) length];
        ByteBuffer frame = buffer.duplicate();
        frame.clear();
        frame.get(rgb);
        CameraSource.Frame result = new CameraSource.Frame(rgb, frameWidth, frameHeight);
        handler.post(() -> {
            if (current()) callback.onFrame(result);
        });
    }

    void close() {
        if (closed) return;
        closed = true;
        // 先停止原生采集并销毁其克隆连接，再销毁 Monitor 的原连接和监听线程。
        try {
            if (camera != null) {
                try {
                    camera.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RGB);
                } finally {
                    camera.destroy(true);
                    camera = null;
                }
            }
        } catch (RuntimeException | LinkageError error) {
            Log.w("晨检USB相机", "释放原生摄像头失败，继续释放 USB 监听和预览资源", error);
        } finally {
            try {
                // 库的克隆连接 close 会按设备键移除 Monitor 中的原连接，
                // 所以原连接也由本会话显式关闭，不能只依赖 monitor.destroy。
                if (usbConnection != null) {
                    usbConnection.close(true);
                    usbConnection = null;
                }
            } catch (RuntimeException error) {
                Log.w("晨检USB相机", "关闭 USB 控制连接失败，继续释放监听资源", error);
            }
            try {
                if (monitor != null) {
                    // 库的授权处理持有 monitor 锁，完整销毁也持同锁，避免退出时迟到授权重建连接。
                    synchronized (monitor) {
                        monitor.destroy();
                    }
                    monitor = null;
                }
            } catch (RuntimeException error) {
                Log.w("晨检USB相机", "释放 USB 监听失败，继续释放预览资源", error);
            } finally {
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
            }
        }
    }

    private void fail(String message) {
        if (current()) callback.onError(message);
    }

    private boolean current() {
        return !closed && isCurrent.getAsBoolean();
    }

    private boolean matches(UsbDevice device) {
        return device != null && entry.device.getDeviceName().equals(device.getDeviceName());
    }

    private static double sizeScore(Size size) {
        double pixels = (double) size.width * size.height;
        double shape = size.height == 0 ? Double.MAX_VALUE : Math.abs(size.width / (double) size.height - 4d / 3d);
        return Math.abs(Math.log(pixels / (640d * 480))) + shape
                + (size.type == UVCCamera.UVC_VS_FRAME_MJPEG ? 0 : 0.1);
    }

    private static String readable(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
