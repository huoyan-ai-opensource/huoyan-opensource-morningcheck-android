package cc.huoyan.opensource.morningcheck.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 系统 Camera2 / USB UVC 预览与低频 RGB 采集，供晨检流水线消费。
 * start/stop/close 可从任意线程调用；帧回调在相机线程，错误回调在主线程。
 * 每帧拥有独立字节数组，调用方可保留用于异步识别或证据保存。
 */
public final class CameraSource implements AutoCloseable {
    private static final long FRAME_INTERVAL_MS = 500;
    // 主线程维护跨页面的停机交接；新宿主等待原生释放完成，不能只等旧 View 销毁。
    private static final List<Run> closingRuns = new ArrayList<>();
    private static final List<CameraSource> waitingSources = new ArrayList<>();
    private final Activity activity;
    private final TextureView preview;
    private final Listener listener;
    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Run activeRun;
    private Run retiringRun;
    private boolean wanted;
    private boolean closed;
    private String preferredId;
    private CameraImageOptions options = CameraImageOptions.defaults();
    private volatile List<String> supportedResolutions = java.util.Collections.emptyList();
    private volatile String activeResolution = "";

    public interface Listener {
        void onFrame(Frame frame);

        void onError(String message);
    }

    public static final class Frame {
        public final byte[] rgb;
        public final int width;
        public final int height;
        public final long timestampNanos;
        public final long receivedAtNanos;
        public final long captureTimeMillis;

        public Frame(byte[] rgb, int width, int height) {
            this(rgb, width, height, System.nanoTime());
        }

        private Frame(byte[] rgb, int width, int height, long timestampNanos) {
            this(rgb, width, height, timestampNanos, System.nanoTime(), System.currentTimeMillis());
        }

        private Frame(byte[] rgb, int width, int height, long timestampNanos, long receivedAtNanos, long captureTimeMillis) {
            if (rgb == null || width <= 0 || height <= 0 || (long) width * height * 3 != rgb.length) {
                throw new IllegalArgumentException("RGB 画面数据与尺寸不一致");
            }
            this.rgb = rgb;
            this.width = width;
            this.height = height;
            this.timestampNanos = timestampNanos;
            // 业务时间比较使用 App 收帧时钟，不能假设相机 HAL 时间戳与 nanoTime 同源。
            this.receivedAtNanos = receivedAtNanos;
            // 收帧时固定墙钟；部分 Camera2 时间戳来源未知，不能与 nanoTime 直接换算。
            this.captureTimeMillis = captureTimeMillis;
        }

        /**
         * 返回新的位图，使用者负责在保存或显示完成后释放。
         */
        public Bitmap toBitmap() {
            int[] colors = new int[width * height];
            for (int pixel = 0, offset = 0; pixel < colors.length; pixel++, offset += 3) {
                colors[pixel] = 0xff000000 | (rgb[offset] & 255) << 16
                        | (rgb[offset + 1] & 255) << 8 | (rgb[offset + 2] & 255);
            }
            // 证据保存会直接写入异常框和时间标注，需要返回可写位图。
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(colors, 0, width, 0, 0, width, height);
            return bitmap;
        }
    }

    public static final class CameraInfo {
        public final String id;
        public final String label;
        public final int facing;

        private CameraInfo(String id, int facing) {
            this.id = id;
            this.facing = facing;
            String direction = facing == CameraCharacteristics.LENS_FACING_EXTERNAL ? "外接摄像头"
                    : facing == CameraCharacteristics.LENS_FACING_FRONT ? "前置摄像头" : "后置摄像头";
            label = direction + " · " + id;
        }

        private CameraInfo(UsbCameraCatalog.Entry entry) {
            id = entry.id;
            facing = CameraCharacteristics.LENS_FACING_EXTERNAL;
            label = entry.label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public CameraSource(Activity activity, TextureView preview, Listener listener) {
        if (activity == null || preview == null || listener == null)
            throw new IllegalArgumentException("缺少相机宿主或回调");
        this.activity = activity;
        this.preview = preview;
        this.listener = listener;
        manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * 返回可选镜头 ID，包含 USB UVC 和 Camera2；系统 ID 沿用原格式。
     */
    public static String[] listCameras(Context context) {
        List<CameraInfo> infos = listCameraInfo(context);
        String[] ids = new String[infos.size()];
        for (int index = 0; index < infos.size(); index++) ids[index] = infos.get(index).id;
        return ids;
    }

    private static String[] listSystemCameras(Context context) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            return manager == null ? new String[0] : manager.getCameraIdList();
        } catch (CameraAccessException | SecurityException error) {
            return new String[0];
        }
    }

    public static List<CameraInfo> listCameraInfo(Context context) {
        List<CameraInfo> cameras = new ArrayList<>();
        List<UsbCameraCatalog.Entry> usbCameras = UsbCameraCatalog.list(context);
        for (UsbCameraCatalog.Entry entry : usbCameras) cameras.add(new CameraInfo(entry));
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return cameras;
        for (String id : listSystemCameras(context)) {
            try {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                // 工控机可能把 UVC 镜头误标为后置，但硬件级别仍为 EXTERNAL。
                // 有直连 USB 时不重复展示该别名；旧 ID 仅在系统仍提供时可继续打开。
                if (!usbCameras.isEmpty() && level != null
                        && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL)
                    continue;
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                cameras.add(new CameraInfo(id, facing == null ? CameraCharacteristics.LENS_FACING_BACK : facing));
            } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) {
                // USB 热拔插期间被移除的条目不再展示。
            }
        }
        return cameras;
    }

    /**
     * 空 ID 优先 USB，其次系统外接、前置、后置；指定 ID 不可用时不改用另一台镜头。
     */
    public void start(String preferredCameraId) {
        start(preferredCameraId, CameraImageOptions.defaults());
    }

    public List<String> supportedResolutions() {
        return new ArrayList<>(supportedResolutions);
    }

    public String activeResolution() {
        return activeResolution;
    }

    public void start(String preferredCameraId, CameraImageOptions imageOptions) {
        onMain(() -> {
            if (closed) return;
            stopInternal();
            options = imageOptions;
            supportedResolutions = java.util.Collections.emptyList();
            activeResolution = "";
            preferredId = preferredCameraId == null ? "" : preferredCameraId.trim();
            wanted = true;
            preview.setSurfaceTextureListener(surfaceListener);
            if (preview.isAvailable()) openAvailableSurface();
        });
    }

    public void stop() {
        onMain(this::stopInternal);
    }

    @Override
    public void close() {
        onMain(() -> {
            closed = true;
            stopInternal();
        });
    }

    public String getCameraId() {
        Run run = activeRun;
        return run == null ? null : run.cameraId;
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openAvailableSurface();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            if (activeRun == null) openAvailableSurface();
            else updateTransform(activeRun);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            Run run = activeRun != null && activeRun.texture == surface ? activeRun : retiringRun;
            boolean deferred = run != null && run.texture == surface && !run.retired;
            if (deferred) run.releaseTexture = true;
            retireActiveRun();
            // TextureView 退出后原生停止仍在后台，等采集真正关闭再释放纹理。
            return !deferred;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private void stopInternal() {
        wanted = false;
        waitingSources.remove(this);
        retireActiveRun();
        // 后台停机期间继续接收纹理销毁事件，否则 View 会先于原生采集释放纹理。
        if (retiringRun == null && preview.getSurfaceTextureListener() == surfaceListener)
            preview.setSurfaceTextureListener(null);
    }

    private void retireActiveRun() {
        Run old = activeRun;
        activeRun = null;
        if (old != null) {
            retiringRun = old;
            old.invalidated = true;
            if (!old.retired && !closingRuns.contains(old)) closingRuns.add(old);
            old.handler.post(old::releaseWhenPossible);
        }
    }

    private void openAvailableSurface() {
        if (!wanted || closed || activeRun != null || !preview.isAvailable()
                || preview.getWidth() <= 0 || preview.getHeight() <= 0) return;
        if (retiringRun != null && !retiringRun.retired) return;
        if (!closingRuns.isEmpty()) {
            if (!waitingSources.contains(this)) waitingSources.add(this);
            return;
        }
        waitingSources.remove(this);
        retiringRun = null;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("请先允许相机权限");
            return;
        }
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null) return;
        Run run = new Run(texture, preferredId);
        activeRun = run;
        run.handler.post(run::open);
    }

    private void updateTransform(Run run) {
        if (run == null || run != activeRun || run.previewSize == null || run.invalidated) return;
        int viewWidth = preview.getWidth();
        int viewHeight = preview.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;
        if (run.usb != null) {
            // UVC 无 Camera2 传感器旋转元数据，预览和 RGB 都保持原始方向及比例。
            float fit = Math.min(viewWidth / (float) run.previewSize.getWidth(),
                    viewHeight / (float) run.previewSize.getHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(run.previewSize.getWidth() * fit / viewWidth,
                    run.previewSize.getHeight() * fit / viewHeight, viewWidth / 2f, viewHeight / 2f);
            applyImageOptions(matrix, run, run.previewSize.getWidth(), run.previewSize.getHeight());
            preview.setTransform(matrix);
            return;
        }
        int rotation = run.frameRotation();
        int displayDegrees = run.displayRotationDegrees();
        boolean sensorSwapped = run.sensorOrientation % 180 != 0;
        int width = sensorSwapped ? run.previewSize.getHeight() : run.previewSize.getWidth();
        int height = sensorSwapped ? run.previewSize.getWidth() : run.previewSize.getHeight();
        int rotatedWidth = displayDegrees % 180 != 0 ? height : width;
        int rotatedHeight = displayDegrees % 180 != 0 ? width : height;
        float fit = Math.min(viewWidth / (float) rotatedWidth, viewHeight / (float) rotatedHeight);
        Matrix matrix = new Matrix();
        // Camera2 的 SurfaceTexture 已带传感器归正和前摄镜像；这里只补偿显示旋转，
        // 不能再应用一次 RGB 的传感器旋转，否则真机预览会横置或与检测框左右相反。
        matrix.setScale(width / (float) viewWidth, height / (float) viewHeight);
        matrix.postTranslate(-width / 2f, -height / 2f);
        matrix.postRotate(-displayDegrees);
        matrix.postScale(fit, fit);
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f);
        applyImageOptions(matrix, run, rotatedWidth, rotatedHeight);
        preview.setTransform(matrix);
        run.lastPreviewRotation = rotation;
    }

    private void applyImageOptions(Matrix matrix, Run run, int width, int height) {
        float viewWidth = preview.getWidth(), viewHeight = preview.getHeight();
        CameraImageOptions o = run.imageOptions;
        // 先消除 Camera2 前摄固有镜像，再按用户选项同步预览与送检 RGB。
        if (run.frontFacing != o.mirror) matrix.postScale(-1, 1, viewWidth / 2, viewHeight / 2);
        float oldFit = Math.min(viewWidth / width, viewHeight / height);
        float cropWidth = o.width(width), cropHeight = o.height(height);
        float newFit = Math.min(viewWidth / cropWidth, viewHeight / cropHeight);
        float cropCenterX = (viewWidth - width * oldFit) / 2 + (o.leftPixel(width) + cropWidth / 2) * oldFit;
        float cropCenterY = (viewHeight - height * oldFit) / 2 + (o.topPixel(height) + cropHeight / 2) * oldFit;
        matrix.postTranslate(-cropCenterX, -cropCenterY);
        matrix.postScale(newFit / oldFit, newFit / oldFit);
        matrix.postTranslate(viewWidth / 2, viewHeight / 2);
        int insetX = Math.max(0, Math.round((viewWidth - cropWidth * newFit) / 2));
        int insetY = Math.max(0, Math.round((viewHeight - cropHeight * newFit) / 2));
        preview.setClipBounds(new Rect(insetX, insetY, (int) viewWidth - insetX, (int) viewHeight - insetY));
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private final class Run {
        final SurfaceTexture texture;
        final String requestedId;
        final CameraImageOptions imageOptions;
        final HandlerThread thread = new HandlerThread("晨检相机采集");
        final Handler handler;
        volatile boolean invalidated;
        volatile String cameraId;
        volatile Size previewSize;
        volatile int sensorOrientation;
        volatile boolean frontFacing;
        volatile int lastPreviewRotation = -1;
        CameraDevice device;
        CameraDevice closingDevice;
        CameraCaptureSession session;
        ImageReader reader;
        Surface previewSurface;
        UsbCameraCapture usb;
        boolean opening;
        volatile boolean releaseTexture;
        volatile boolean retired;
        long lastFrameAt = -FRAME_INTERVAL_MS;

        Run(SurfaceTexture texture, String requestedId) {
            this.texture = texture;
            this.requestedId = requestedId;
            imageOptions = options;
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        boolean isCurrent() {
            return !invalidated && activeRun == this;
        }

        @SuppressWarnings("MissingPermission")
        void open() {
            if (!isCurrent()) {
                releaseWhenPossible();
                return;
            }
            try {
                if (requestedId.isEmpty() || requestedId.startsWith(UsbCameraCatalog.PREFIX)) {
                    UsbCameraCatalog.Entry entry = UsbCameraCatalog.resolve(activity, requestedId);
                    if (entry != null) {
                        cameraId = entry.id;
                        usb = new UsbCameraCapture(activity, handler, entry, texture, imageOptions.resolution, this::isCurrent,
                                new UsbCameraCapture.Callback() {
                                    @Override
                                    public void onSizes(List<String> sizes) {
                                        if (isCurrent())
                                            supportedResolutions = new ArrayList<>(sizes);
                                    }

                                    @Override
                                    public void onReady(int width, int height) {
                                        if (!isCurrent()) return;
                                        activeResolution = width + "x" + height;
                                        previewSize = new Size(width, height);
                                        main.post(() -> updateTransform(Run.this));
                                    }

                                    @Override
                                    public void onFrame(Frame frame) {
                                        if (isCurrent()) listener.onFrame(configuredFrame(frame));
                                    }

                                    @Override
                                    public void onError(String message) {
                                        fail(message);
                                    }
                                });
                        usb.open();
                        return;
                    }
                }
                if (manager == null) throw new IllegalStateException("当前设备不支持系统相机");
                cameraId = chooseCameraId();
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                frontFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = sensor == null ? 0 : sensor;
                StreamConfigurationMap streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (streams == null) throw new IllegalStateException("摄像头没有可用的画面配置");
                Size[] previewSizes = streams.getOutputSizes(SurfaceTexture.class);
                Size[] frameSizes = streams.getOutputSizes(ImageFormat.YUV_420_888);
                List<String> available = new ArrayList<>();
                if (frameSizes != null && previewSizes != null) for (Size candidate : frameSizes) {
                    for (Size surface : previewSizes)
                        if (sameAspect(candidate, surface)) {
                            String value = candidate.getWidth() + "x" + candidate.getHeight();
                            if (!available.contains(value)) available.add(value);
                            break;
                        }
                }
                supportedResolutions = available;
                Size frameSize = chooseFrameSize(frameSizes, previewSizes);
                if (!imageOptions.resolution.isEmpty()) {
                    if (!available.contains(imageOptions.resolution))
                        throw new IllegalStateException("当前相机不支持已选分辨率，请重新选择");
                    String[] dimensions = imageOptions.resolution.split("x");
                    frameSize = new Size(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
                }
                previewSize = choosePreviewSize(previewSizes, frameSize);
                activeResolution = frameSize.getWidth() + "x" + frameSize.getHeight();
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                previewSurface = new Surface(texture);
                reader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(this::consumeImage, handler);
                main.post(() -> updateTransform(this));
                opening = true;
                manager.openCamera(cameraId, deviceCallback, handler);
            } catch (IllegalArgumentException error) {
                opening = false;
                // 直连 UVC 会让部分工控机的 external HAL 移除原别名，CameraManager
                // 仍可能缓存旧 ID。不能据此猜测 USB 镜头映射，须明确引导用户重新选择。
                android.util.Log.w("晨检相机", "选定摄像头不可用：" + requestedId, error);
                fail("选定摄像头 " + requestedId + " 当前不可用，请在采集设备中重新选择摄像头");
            } catch (CameraAccessException | SecurityException |
                     IllegalStateException error) {
                opening = false;
                fail("无法打开摄像头：" + readable(error));
            }
        }

        String chooseCameraId() throws CameraAccessException {
            String[] ids = manager.getCameraIdList();
            if (!requestedId.isEmpty()) {
                for (String id : ids) if (id.equals(requestedId)) return id;
                throw new IllegalStateException("选定摄像头 " + requestedId + " 未连接，请在设备设置中重新选择");
            }
            String front = null;
            for (String id : ids) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                    return id;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) front = id;
            }
            if (front != null) return front;
            if (ids.length != 0) return ids[0];
            throw new IllegalStateException("未发现系统摄像头，请确认设备连接及 Camera2 支持");
        }

        final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice opened) {
                opening = false;
                device = opened;
                if (!isCurrent()) {
                    releaseWhenPossible();
                    return;
                }
                try {
                    opened.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), sessionCallback, handler);
                } catch (CameraAccessException | IllegalArgumentException |
                         IllegalStateException error) {
                    fail("无法配置相机预览：" + readable(error));
                }
            }

            @Override
            public void onDisconnected(CameraDevice disconnected) {
                opening = false;
                device = disconnected;
                fail("摄像头已断开，请连接后重新进入晨检");
            }

            @Override
            public void onError(CameraDevice failed, int code) {
                opening = false;
                device = failed;
                String reason = code == ERROR_CAMERA_IN_USE || code == ERROR_MAX_CAMERAS_IN_USE
                        ? "摄像头被其他应用占用，请关闭占用后重试"
                        : code == ERROR_CAMERA_DISABLED ? "系统已停用摄像头" : "摄像头错误（" + code + "），请重新连接";
                fail(reason);
            }

            @Override
            public void onClosed(CameraDevice closedDevice) {
                if (closingDevice == closedDevice) closingDevice = null;
                if (invalidated) releaseWhenPossible();
            }
        };

        final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession configured) {
                if (!isCurrent() || device == null) {
                    configured.close();
                    return;
                }
                session = configured;
                try {
                    CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    request.addTarget(previewSurface);
                    request.addTarget(reader.getSurface());
                    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                    if (Build.VERSION.SDK_INT >= 31) {
                        int[] modes = manager.getCameraCharacteristics(cameraId)
                                .get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
                        if (modes != null) for (int mode : modes) {
                            if (mode == CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) {
                                // 归正由本组件统一处理，避免 HAL 再次旋转或裁切造成框与画面不一致。
                                request.set(CaptureRequest.SCALER_ROTATE_AND_CROP, mode);
                                break;
                            }
                        }
                    }
                    Integer[] continuousAf = availableAutoFocus();
                    if (continuousAf.length > 0)
                        request.set(CaptureRequest.CONTROL_AF_MODE, continuousAf[0]);
                    configured.setRepeatingRequest(request.build(), null, handler);
                } catch (CameraAccessException | IllegalArgumentException |
                         IllegalStateException error) {
                    fail("无法启动相机采集：" + readable(error));
                }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession failed) {
                failed.close();
                fail("当前摄像头不支持预览与识别同时采集，请更换摄像头");
            }
        };

        Integer[] availableAutoFocus() throws CameraAccessException {
            int[] modes = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (modes != null) {
                for (int mode : modes)
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        return new Integer[]{mode};
                for (int mode : modes)
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        return new Integer[]{mode};
            }
            return new Integer[0];
        }

        int frameRotation() {
            return YuvFrameConverter.uprightRotation(sensorOrientation, displayRotationDegrees(), frontFacing);
        }

        int displayRotationDegrees() {
            int display = activity.getWindowManager().getDefaultDisplay().getRotation();
            return display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180
                                                         : display == Surface.ROTATION_270 ? 270 : 0;
        }

        void consumeImage(ImageReader source) {
            Frame frame = null;
            // 始终获取并关闭最新帧，SDK 处理较慢时不积压旧画面。
            try (Image image = source.acquireLatestImage()) {
                if (image == null || !isCurrent()) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
                lastFrameAt = now;
                int rotation = frameRotation();
                if (rotation != lastPreviewRotation) main.post(() -> updateTransform(this));
                Rect crop = image.getCropRect();
                Image.Plane[] planes = image.getPlanes();
                byte[] rgb = YuvFrameConverter.toRgb(
                        planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                        planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                        planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(),
                        crop.left, crop.top, crop.width(), crop.height(), rotation);
                boolean swap = rotation == 90 || rotation == 270;
                frame = new Frame(rgb, swap ? crop.height() : crop.width(), swap ? crop.width() : crop.height(), image.getTimestamp());
            } catch (IllegalStateException error) {
                if (isCurrent()) fail("相机画面已中断，请重新连接");
            } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                if (isCurrent()) fail("无法读取当前摄像头的画面格式");
            }
            if (frame != null && isCurrent()) listener.onFrame(configuredFrame(frame));
        }

        Frame configuredFrame(Frame frame) {
            byte[] pixels = imageOptions.pixels(frame.rgb, frame.width, frame.height);
            if (pixels == frame.rgb) return frame;
            return new Frame(pixels, imageOptions.width(frame.width), imageOptions.height(frame.height),
                    frame.timestampNanos, frame.receivedAtNanos, frame.captureTimeMillis);
        }

        void fail(String message) {
            boolean notify = isCurrent();
            invalidated = true;
            releaseWhenPossible();
            main.post(() -> {
                if (activeRun == this) {
                    activeRun = null;
                    retiringRun = retired ? null : this;
                    if (!retired && !closingRuns.contains(this)) closingRuns.add(this);
                    boolean report = notify && wanted && !closed;
                    wanted = false;
                    if (report) listener.onError(message);
                }
            });
        }

        void releaseWhenPossible() {
            if (usb != null) {
                usb.close();
                usb = null;
            }
            if (session != null) {
                session.close();
                session = null;
            }
            if (device != null) {
                closingDevice = device;
                device.close();
                device = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (previewSurface != null) {
                previewSurface.release();
                previewSurface = null;
            }
            // openCamera 无取消接口。收到迟到的打开回调与最终 onClosed 后才能重开同一实例。
            if (!opening && closingDevice == null && !retired) {
                retired = true;
                thread.quitSafely();
                main.post(() -> {
                    if (releaseTexture) texture.release();
                    closingRuns.remove(this);
                    if (retiringRun == this) {
                        retiringRun = null;
                        if (!wanted && preview.getSurfaceTextureListener() == surfaceListener)
                            preview.setSurfaceTextureListener(null);
                        openAvailableSurface();
                    }
                    if (closingRuns.isEmpty()) {
                        List<CameraSource> ready = new ArrayList<>(waitingSources);
                        waitingSources.clear();
                        for (CameraSource source : ready) source.openAvailableSurface();
                    }
                });
            }
        }
    }

    private static Size chooseFrameSize(Size[] frames, Size[] previews) {
        if (frames == null || previews == null)
            throw new IllegalStateException("摄像头不支持预览与识别画面");
        List<Size> matching = new ArrayList<>();
        for (Size frame : frames) {
            for (Size preview : previews) {
                if (sameAspect(frame, preview)) {
                    matching.add(frame);
                    break;
                }
            }
        }
        if (matching.isEmpty()) throw new IllegalStateException("摄像头预览与识别画面的比例不兼容");
        return chooseSize(matching.toArray(new Size[0]));
    }

    private static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0)
            throw new IllegalStateException("摄像头不支持 YUV 识别画面");
        Size best = sizes[0];
        double score = Double.MAX_VALUE;
        for (Size candidate : sizes) {
            if (candidate.getWidth() == 640 && candidate.getHeight() == 480) return candidate;
            double candidateScore = Math.abs(Math.log((candidate.getWidth() * (double) candidate.getHeight()) / (640d * 480)))
                    + Math.abs(candidate.getWidth() / (double) candidate.getHeight() - 4d / 3d);
            if (candidateScore < score) {
                score = candidateScore;
                best = candidate;
            }
        }
        return best;
    }

    private static Size choosePreviewSize(Size[] sizes, Size frame) {
        if (sizes == null || sizes.length == 0)
            throw new IllegalStateException("摄像头不支持 TextureView 预览");
        for (Size size : sizes) if (size.equals(frame)) return size;
        List<Size> matching = new ArrayList<>();
        for (Size size : sizes) if (sameAspect(size, frame)) matching.add(size);
        return chooseSize(matching.toArray(new Size[0]));
    }

    private static boolean sameAspect(Size a, Size b) {
        return Math.abs(a.getWidth() / (double) a.getHeight() - b.getWidth() / (double) b.getHeight()) < 0.001;
    }

    private static String readable(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
