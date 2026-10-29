package cc.huoyan.opensource.morningcheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.domain.ActivationScanCode;
import cc.huoyan.opensource.morningcheck.login.QrFrameDecoder;

/**
 * 独立激活扫码页只返回解析后的码；不连接 SDK、不提交激活，也不改变门店归属。
 */
public final class SdkActivationScanActivity extends AppFontActivity {
    private LoadingDialog loading;
    private String shownError = "";

    private void showError(String value) {
        if (!value.equals(shownError)) ToastEx.showFailureToast(this, value);
        shownError = value;
    }

    /**
     * 成功结果的激活码字段；调用方收到后沿用自己的激活与错误处理流程。
     */
    public static final String EXTRA_CODE = "cc.huoyan.opensource.morningcheck.activation.CODE";
    private static final long PREVIEW_TIMEOUT_MS = 12_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoderThread = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "晨检-激活二维码"));
    private final QrFrameDecoder decoder = new QrFrameDecoder();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private CameraSource camera;
    private TextView statusTitle, statusMessage, previewMessage;
    private View retry, permissionSettings, chooseCamera;
    private AlertDialog cameraDialog;
    // 相机帧来自设备线程；代次会在暂停、切换镜头及提交结果时作废旧解码回调。
    private volatile boolean foreground, scanning;
    private volatile int cameraGeneration;
    private boolean permissionRequested, permissionRequestInFlight, delivered, receivedFrame;
    private String cameraFailure = "", selectedCamera = "";
    private final Runnable previewTimeout = () -> {
        if (!foreground || !scanning || receivedFrame || delivered) return;
        stopCamera();
        cameraFailure = getString(R.string.sdk_activation_scan_camera_timeout);
        renderIdle();
    };
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                permissionRequestInFlight = false;
                cameraFailure = granted ? "" : getString(R.string.sdk_activation_scan_permission_denied);
                if (foreground && !delivered) renderIdle();
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loading = new LoadingDialog(this);
        ImmersiveUi.enable(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_sdk_activation_scan);
        ImmersiveUi.bindInsets(findViewById(R.id.sdk_activation_scan_root));
        if (state == null)
            selectedCamera = getSharedPreferences("device", MODE_PRIVATE).getString("face_camera", "");
        else {
            permissionRequested = state.getBoolean("permission_requested");
            permissionRequestInFlight = state.getBoolean("permission_in_flight");
            cameraFailure = state.getString("camera_failure", "");
            selectedCamera = state.getString("camera_id", "");
        }
        statusTitle = findViewById(R.id.sdk_activation_scan_status_title);
        statusMessage = findViewById(R.id.sdk_activation_scan_status_message);
        previewMessage = findViewById(R.id.sdk_activation_scan_preview_message);
        retry = findViewById(R.id.sdk_activation_scan_retry);
        permissionSettings = findViewById(R.id.sdk_activation_scan_permission_settings);
        chooseCamera = findViewById(R.id.sdk_activation_scan_choose_camera);
        findViewById(R.id.sdk_activation_scan_back).setOnClickListener(view -> finish());
        findViewById(R.id.scan_dialog_preview_frame).setOnLongClickListener(view -> {
            View controls = findViewById(R.id.scan_dialog_controls);
            controls.setVisibility(controls.isShown() ? View.GONE : View.VISIBLE);
            return true;
        });
        retry.setOnClickListener(view -> retryScan());
        permissionSettings.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        chooseCamera.setOnClickListener(view -> showCameraChoices());
        camera = new CameraSource(this, findViewById(R.id.sdk_activation_scan_preview),
                new CameraSource.Listener() {
                    @Override
                    public void onFrame(CameraSource.Frame frame) {
                        decodeFrame(frame);
                    }

                    @Override
                    public void onError(String message) {
                        if (!foreground || !scanning || delivered) return;
                        stopCamera();
                        cameraFailure = message == null || message.isEmpty()
                                ? getString(R.string.sdk_activation_scan_camera_unavailable) : message;
                        renderIdle();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        foreground = true;
        if (hasCameraPermission() && getString(R.string.sdk_activation_scan_permission_denied).equals(cameraFailure))
            cameraFailure = "";
        renderIdle();
    }

    @Override
    protected void onPause() {
        foreground = false;
        stopCamera();
        if (cameraDialog != null) {
            cameraDialog.dismiss();
            cameraDialog = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        foreground = false;
        camera.close();
        decoderThread.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean("permission_requested", permissionRequested);
        state.putBoolean("permission_in_flight", permissionRequestInFlight);
        state.putString("camera_failure", cameraFailure);
        state.putString("camera_id", selectedCamera);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveUi.hideSystemBars(getWindow());
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        super.startActivity(intent, options);
        overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        foreground = false;
        if (camera != null) stopCamera();
        super.finish();
        overridePendingTransition(0, 0);
    }

    private void renderIdle() {
        if (!foreground || delivered || isFinishing()) return;
        boolean failed = !cameraFailure.isEmpty();
        findViewById(R.id.scan_dialog_controls).setVisibility(failed ? View.VISIBLE : View.GONE);
        boolean permitted = hasCameraPermission();
        chooseCamera.setVisibility(permitted ? View.VISIBLE : View.GONE);
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        permissionSettings.setVisibility(failed && !permitted ? View.VISIBLE : View.GONE);
        statusTitle.setText(R.string.sdk_activation_scan_ready);
        statusMessage.setText(R.string.sdk_activation_scan_hint);
        if (failed) {
            loading.dismiss();
            showError(cameraFailure);
            previewMessage.setVisibility(View.GONE);
        } else startCamera();
    }

    private void startCamera() {
        if (!foreground || scanning || delivered) return;
        if (!hasCameraPermission()) {
            if (permissionRequestInFlight) return;
            if (!permissionRequested) {
                permissionRequested = true;
                permissionRequestInFlight = true;
                permission.launch(Manifest.permission.CAMERA);
            } else {
                cameraFailure = getString(R.string.sdk_activation_scan_permission_denied);
                renderIdle();
            }
            return;
        }
        scanning = true;
        receivedFrame = false;
        cameraGeneration++;
        loading.show(getString(R.string.sdk_activation_scan_camera_starting));
        main.removeCallbacks(previewTimeout);
        main.postDelayed(previewTimeout, PREVIEW_TIMEOUT_MS);
        camera.start(selectedCamera);
    }

    private void stopCamera() {
        scanning = false;
        cameraGeneration++;
        main.removeCallbacks(previewTimeout);
        camera.stop();
    }

    private void decodeFrame(CameraSource.Frame frame) {
        if (!foreground || !scanning || !decoding.compareAndSet(false, true)) return;
        int generation = cameraGeneration;
        try {
            decoderThread.execute(() -> {
                String code = null;
                boolean invalid = false;
                try {
                    String raw = decoder.decode(frame.rgb, frame.width, frame.height);
                    if (raw != null) {
                        try {
                            code = ActivationScanCode.parse(raw);
                        } catch (IllegalArgumentException wrongCode) {
                            invalid = true;
                        }
                    }
                } finally {
                    decoding.set(false);
                }
                String result = code;
                boolean wrongQr = invalid;
                main.post(() -> {
                    if (!foreground || !scanning || delivered || generation != cameraGeneration)
                        return;
                    receivedFrame = true;
                    loading.dismiss();
                    main.removeCallbacks(previewTimeout);
                    previewMessage.setVisibility(View.GONE);
                    if (wrongQr) {
                        findViewById(R.id.scan_dialog_controls).setVisibility(View.VISIBLE);
                        showError(getString(R.string.sdk_activation_scan_invalid_message));
                        return;
                    }
                    if (result == null) return;
                    delivered = true;
                    stopCamera();
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_CODE, result));
                    finish();
                });
            });
        } catch (RejectedExecutionException closed) {
            decoding.set(false);
        }
    }

    private void retryScan() {
        if (!foreground || delivered || permissionRequestInFlight) return;
        stopCamera();
        cameraFailure = "";
        if (!hasCameraPermission()) permissionRequested = false;
        renderIdle();
    }

    private void showCameraChoices() {
        if (!foreground || delivered || cameraDialog != null) return;
        List<CameraSource.CameraInfo> cameras = CameraSource.listCameraInfo(this);
        if (cameras.isEmpty()) {
            stopCamera();
            cameraFailure = getString(R.string.sdk_activation_scan_no_camera);
            renderIdle();
            return;
        }
        String[] labels = new String[cameras.size()];
        for (int index = 0; index < cameras.size(); index++)
            labels[index] = cameras.get(index).label;
        stopCamera();
        AlertDialog dialog = new ProjectDialog.Builder(this).setTitle(R.string.sdk_activation_scan_choose_camera)
                .setItems(labels, (view, which) -> {
                    selectedCamera = cameras.get(which).id;
                    cameraFailure = "";
                }).setNegativeButton(android.R.string.cancel, null).create();
        cameraDialog = dialog;
        dialog.setOnDismissListener(view -> {
            if (cameraDialog == dialog) cameraDialog = null;
            if (foreground && !delivered) renderIdle();
        });
        ImmersiveUi.show(dialog);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}
