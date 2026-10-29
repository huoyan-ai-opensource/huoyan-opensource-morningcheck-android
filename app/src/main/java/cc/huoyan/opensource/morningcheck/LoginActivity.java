package cc.huoyan.opensource.morningcheck;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.login.QrFrameDecoder;
import cc.huoyan.opensource.morningcheck.login.ShopBindingStore;
import cc.huoyan.opensource.morningcheck.login.ShopLoginCoordinator;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;

/**
 * 扫码即提交门店登录；请求及保存归进程协调器所有，不随页面销毁重复发起。
 */
public final class LoginActivity extends AppFontActivity implements ShopLoginCoordinator.Listener {
    public static final String EXTRA_RETURN_TO_CALLER = "return_to_caller";

    @Override
    public void startActivity(Intent intent, Bundle options) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        super.startActivity(intent, options);
        overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static final int CAMERA_PERMISSION = 3101;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoderThread = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "晨检-门店二维码"));
    private final QrFrameDecoder decoder = new QrFrameDecoder();
    private final AtomicBoolean decoding = new AtomicBoolean();
    private ShopLoginCoordinator coordinator;
    private CameraSource camera;
    private SharedPreferences prefs;
    private TextView statusTitle, statusMessage, previewMessage, retry;
    private View progress, permissionSettings, chooseCamera;
    private volatile boolean foreground, scanning;
    private volatile int cameraGeneration;
    private boolean permissionRequested, permissionRequestInFlight, navigated;
    private String cameraFailure = "", selectedCamera = "";
    private AlertDialog cameraDialog;
    private LoadingDialog loading;
    private String shownError = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loading = new LoadingDialog(this);
        ImmersiveUi.enable(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_login);
        View root = findViewById(R.id.login_root);
        ImmersiveUi.bindInsets(root);
        prefs = getSharedPreferences("device", MODE_PRIVATE);
        coordinator = ((MorningApplication) getApplication()).shopLogin();
        if (state == null) coordinator.clearResult();
        else {
            permissionRequested = state.getBoolean("permission_requested");
            permissionRequestInFlight = state.getBoolean("permission_in_flight");
            cameraFailure = state.getString("camera_failure", "");
            selectedCamera = state.getString("camera_id", "");
        }
        statusTitle = findViewById(R.id.login_status_title);
        statusMessage = findViewById(R.id.login_status_message);
        previewMessage = findViewById(R.id.login_preview_message);
        progress = findViewById(R.id.login_progress);
        retry = findViewById(R.id.login_retry);
        permissionSettings = findViewById(R.id.login_permission_settings);
        chooseCamera = findViewById(R.id.login_choose_camera);
        findViewById(R.id.login_back).setOnClickListener(view -> finish());
        findViewById(R.id.scan_dialog_preview_frame).setOnLongClickListener(view -> {
            View controls = findViewById(R.id.scan_dialog_controls);
            controls.setVisibility(controls.isShown() ? View.GONE : View.VISIBLE);
            return true;
        });
        retry.setOnClickListener(view -> retryScan());
        permissionSettings.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        chooseCamera.setOnClickListener(view -> showCameraChoices());
        camera = new CameraSource(this, findViewById(R.id.login_preview), new CameraSource.Listener() {
            @Override
            public void onFrame(CameraSource.Frame frame) {
                main.post(() -> {
                    if (foreground && coordinator.getState().phase == ShopLoginCoordinator.Phase.IDLE)
                        loading.dismiss();
                });
                decodeFrame(frame);
            }

            @Override
            public void onError(String message) {
                if (!foreground || !scanning || coordinator.getState().phase != ShopLoginCoordinator.Phase.IDLE)
                    return;
                stopCamera();
                cameraFailure = message;
                renderIdle();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        foreground = true;
        if (hasCameraPermission() && getString(R.string.login_permission_denied).equals(cameraFailure))
            cameraFailure = "";
        coordinator.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureAppFontCurrent();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveUi.hideSystemBars(getWindow());
    }

    @Override
    protected void onStop() {
        foreground = false;
        stopCamera();
        coordinator.detach(this);
        if (cameraDialog != null) {
            cameraDialog.dismiss();
            cameraDialog = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
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
    public void onState(ShopLoginCoordinator.State state) {
        if (!foreground || isFinishing() || navigated) return;
        if (state.phase == ShopLoginCoordinator.Phase.IDLE) {
            renderIdle();
            return;
        }
        stopCamera();
        boolean running = state.isRunning();
        findViewById(R.id.scan_dialog_controls).setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        if (running) loading.show(state.message);
        else loading.dismiss();
        if (state.phase == ShopLoginCoordinator.Phase.ERROR) showError(state.message);
        chooseCamera.setVisibility(View.GONE);
        permissionSettings.setVisibility(View.GONE);
        retry.setVisibility(state.phase == ShopLoginCoordinator.Phase.ERROR ? View.VISIBLE : View.GONE);
        retry.setText(R.string.login_retry_scan);
        statusTitle.setText(R.string.login_scan_title);
        statusMessage.setText(R.string.login_scan_hint);
        previewMessage.setVisibility(View.GONE);
        if (state.phase == ShopLoginCoordinator.Phase.SUCCESS) {
            navigated = true;
            ToastEx.showSuccessToast(this, getString(R.string.login_success));
            if (getIntent().getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)) {
                // 场所页发起的登录只更新展示名称，SDK 归属仍由协调器完整保存。
                String name = new ShopBindingStore(this).read().optString("shop_name", "").trim();
                prefs.edit().putString("shop_name", name).apply();
                setResult(RESULT_OK);
            } else {
                startActivity(new Intent(this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            finish();
        }
    }

    private void showError(String message) {
        if (!message.equals(shownError)) ToastEx.showFailureToast(this, message);
        shownError = message;
    }

    private void renderIdle() {
        loading.dismiss();
        progress.setVisibility(View.GONE);
        boolean failed = !cameraFailure.isEmpty();
        findViewById(R.id.scan_dialog_controls).setVisibility(failed ? View.VISIBLE : View.GONE);
        chooseCamera.setVisibility(failed && !hasCameraPermission() ? View.GONE : View.VISIBLE);
        statusTitle.setText(R.string.login_scan_title);
        statusMessage.setText(R.string.login_scan_hint);
        if (failed) showError(cameraFailure);
        else shownError = "";
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        retry.setText(R.string.login_retry_camera);
        permissionSettings.setVisibility(failed && !hasCameraPermission() ? View.VISIBLE : View.GONE);
        if (failed) {
            previewMessage.setVisibility(View.GONE);
        } else startCamera();
    }

    private void startCamera() {
        if (!foreground || scanning || coordinator.getState().phase != ShopLoginCoordinator.Phase.IDLE)
            return;
        if (!hasCameraPermission()) {
            if (permissionRequestInFlight) return;
            if (!permissionRequested) {
                permissionRequested = true;
                permissionRequestInFlight = true;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            } else {
                cameraFailure = getString(R.string.login_permission_denied);
                renderIdle();
            }
            return;
        }
        scanning = true;
        cameraGeneration++;
        loading.show(getString(R.string.login_camera_starting));
        camera.start(selectedCamera);
    }

    private void stopCamera() {
        scanning = false;
        cameraGeneration++;
        camera.stop();
    }

    private void decodeFrame(CameraSource.Frame frame) {
        if (!foreground || !scanning || !decoding.compareAndSet(false, true)) return;
        int generation = cameraGeneration;
        try {
            decoderThread.execute(() -> {
                String text;
                try {
                    text = decoder.decode(frame.rgb, frame.width, frame.height);
                } finally {
                    decoding.set(false);
                }
                main.post(() -> {
                    // 旋转、切换相机或退出后的旧帧不能提交登录，也不能改变新预览。
                    if (!foreground || !scanning || generation != cameraGeneration
                            || coordinator.getState().phase != ShopLoginCoordinator.Phase.IDLE)
                        return;
                    previewMessage.setVisibility(View.GONE);
                    if (text == null) return;
                    stopCamera();
                    SdkConfig config;
                    try {
                        config = MorningSdkSettings.read(prefs);
                    } catch (IllegalArgumentException invalidConfig) {
                        // 升级保留的旧地址可能已无效；按登录失败显示，不在主线程崩溃。
                        coordinator.login(text, null);
                        return;
                    }
                    coordinator.login(text, config);
                });
            });
        } catch (RejectedExecutionException ignored) {
            decoding.set(false);
        }
    }

    private void retryScan() {
        if (coordinator.getState().isRunning() || permissionRequestInFlight) return;
        cameraFailure = "";
        // 先归回待扫码状态，授权回调才能继续；失败后必须由用户显式触发本次重试。
        if (!hasCameraPermission()) permissionRequested = false;
        coordinator.clearResult();
    }

    private void showCameraChoices() {
        if (coordinator.getState().phase != ShopLoginCoordinator.Phase.IDLE) return;
        List<CameraSource.CameraInfo> cameras = CameraSource.listCameraInfo(this);
        if (cameras.isEmpty()) {
            cameraFailure = getString(R.string.login_no_camera);
            stopCamera();
            renderIdle();
            return;
        }
        String[] labels = new String[cameras.size()];
        for (int index = 0; index < cameras.size(); index++)
            labels[index] = cameras.get(index).label;
        stopCamera();
        cameraDialog = new ProjectDialog.Builder(this).setTitle(R.string.login_choose_camera)
                .setItems(labels, (dialog, which) -> {
                    selectedCamera = cameras.get(which).id;
                    cameraFailure = "";
                }).setNegativeButton(android.R.string.cancel, null).create();
        cameraDialog.setOnDismissListener(dialog -> {
            cameraDialog = null;
            if (foreground && coordinator.getState().phase == ShopLoginCoordinator.Phase.IDLE)
                renderIdle();
        });
        ImmersiveUi.show(cameraDialog);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != CAMERA_PERMISSION) return;
        permissionRequestInFlight = false;
        cameraFailure = hasCameraPermission() ? "" : getString(R.string.login_permission_denied);
        if (foreground && coordinator.getState().phase == ShopLoginCoordinator.Phase.IDLE)
            renderIdle();
    }
}
