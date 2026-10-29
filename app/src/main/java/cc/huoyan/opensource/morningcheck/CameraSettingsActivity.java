package cc.huoyan.opensource.morningcheck;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.opensource.morningcheck.camera.CameraImageOptions;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;

/**
 * 摄像头设置：独立 Activity 直接绑定对应 XML。
 */
public final class CameraSettingsActivity extends BaseDeviceSettingsActivity {
    @Override
    protected int settingsSection() {
        return 4;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_camera_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_camera_design;
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    protected void bindRegular(View form) {
        content.setPadding(0, 0, 0, 0);
        bindCameraSettings(form);
    }

    @Override
    protected void bindCompact(View form) {
        bindRegular(form);
    }

    private static final String CAMERA_ROLE = "camera.preview_hand";
    private static final String CAMERA_PERMISSION = "camera.permission_requested";
    private final List<String> cameraIds = new ArrayList<>(), cameraLabels = new ArrayList<>();
    private String faceCamera = "", handCamera = "";
    private CameraImageOptions faceOptions, handOptions;
    private CameraCropView cropView;
    private android.widget.Switch mirrorSwitch;
    private boolean previewHand, cameraPreviewRunning, cameraPermissionRequested, cameraPermissionInFlight;
    private volatile int cameraPreviewGeneration;
    private CameraSource cameraPreviewSource;
    private TextView cameraPreviewMessage, cameraPreviewLabel;
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                cameraPermissionInFlight = false;
                if (!uiReady()) return;
                if (granted) startCameraPreview(false);
                else
                    showCameraPreviewError(getString(R.string.settings_camera_preview_permission_denied));
            });

    @Override
    protected void setupSettings(@Nullable Bundle state) {
        cameraPermissionRequested = formState.getBoolean(CAMERA_PERMISSION);
    }

    @Override
    protected void onSettingsResumed() {
        startCameraPreview(false);
    }

    @Override
    protected void onPause() {
        // 设置页离开或权限页面覆盖时即释放设备占用，登录和晨检可接管同一镜头。
        stopCameraPreview();
        super.onPause();
    }

    @Override
    protected void releaseSettingsView() {
        stopCameraPreview();
        if (cameraPreviewSource != null) cameraPreviewSource.close();
        cameraPreviewSource = null;
        cameraPreviewMessage = null;
        cameraPreviewLabel = null;
    }

    private void bindCameraSettings(View form) {
        faceCamera = setting("face_camera", "");
        handCamera = setting("hand_camera", "");
        previewHand = formState.getBoolean(CAMERA_ROLE);
        faceOptions = CameraImageOptions.read(prefs, "face");
        handOptions = CameraImageOptions.read(prefs, "hand");
        cropView = form.findViewById(R.id.settings_camera_crop);
        mirrorSwitch = form.findViewById(R.id.settings_camera_mirror);
        mirrorSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (activeOptions().mirror == checked) return;
            CameraImageOptions old = activeOptions();
            updateImageOptions(new CameraImageOptions(old.resolution, checked, 1 - old.right, old.top, 1 - old.left, old.bottom), true);
        });
        settingsAction(form, R.id.settings_camera_resolution, this::chooseResolution);
        updateImageControls();
        refreshCameraChoices();
        ((TextView) findViewById(R.id.header_page_title)).setText(getString(R.string.i18n_a_camera_settings));
        settingsAction(form, R.id.settings_choose_active_camera, () -> chooseCamera(previewHand));
        settingsAction(form, R.id.settings_camera_lighting,
                () -> startActivity(new Intent(this, LightingSettingsActivity.class)));
        updateCameraChoiceButtons();
        cameraPreviewMessage = form.findViewById(R.id.settings_camera_preview_message);
        cameraPreviewLabel = form.findViewById(R.id.settings_camera_preview_label);
        int expectedView = viewGeneration;
        cameraPreviewSource = new CameraSource(this, form.findViewById(R.id.settings_camera_texture),
                new CameraSource.Listener() {
                    @Override
                    public void onFrame(CameraSource.Frame frame) {
                        int generation = cameraPreviewGeneration;
                        main.post(() -> {
                            if (expectedView != viewGeneration || generation != cameraPreviewGeneration
                                    || !cameraPreviewRunning || !uiReady() || cameraPreviewSource == null)
                                return;
                            cameraPreviewMessage.setVisibility(View.GONE);
                            cropView.imageSize(frame.width, frame.height);
                            updateResolutionControl();
                            updateCameraPreviewLabel(cameraPreviewSource.getCameraId());
                        });
                    }

                    @Override
                    public void onError(String message) {
                        if (expectedView != viewGeneration || !uiReady()) return;
                        cameraPreviewRunning = false;
                        cameraPreviewGeneration++;
                        showCameraPreviewError(message);
                    }
                });
        RadioGroup role = form.findViewById(R.id.settings_camera_role);
        role.check(previewHand ? R.id.settings_preview_hand : R.id.settings_preview_face);
        role.setOnCheckedChangeListener((group, checkedId) -> {
            boolean hand = checkedId == R.id.settings_preview_hand;
            if (previewHand == hand) return;
            previewHand = hand;
            formState.putBoolean(CAMERA_ROLE, hand);
            updateImageControls();
            updateCameraChoiceButtons();
            restartCameraPreview();
        });
        cameraPreviewMessage.setOnClickListener(view -> startCameraPreview(true));
        updateCameraPreviewLabel(selectedPreviewCamera());
    }

    private CameraImageOptions activeOptions() {
        return previewHand ? handOptions : faceOptions;
    }

    private void updateImageOptions(CameraImageOptions options, boolean restartPreview) {
        if (previewHand) handOptions = options;
        else faceOptions = options;
        android.content.SharedPreferences.Editor edit = prefs.edit();
        options.save(edit, previewHand ? "hand" : "face");
        edit.apply();
        updateImageControls();
        if (restartPreview) restartCameraPreview();
    }

    private void updateImageControls() {
        if (cropView == null || mirrorSwitch == null) return;
        CameraImageOptions options = activeOptions();
        mirrorSwitch.setChecked(options.mirror);
        cropView.configure(options, (left, top, right, bottom) -> {
            CameraImageOptions old = activeOptions();
            // 拖动只更新采集范围与遮罩，不必为每一帧拖动重新打开相机。
            updateImageOptions(new CameraImageOptions(old.resolution, old.mirror, left, top, right, bottom), false);
        });
        updateResolutionControl();
    }

    private void updateResolutionControl() {
        if (formView == null) return;
        String resolution = activeOptions().resolution;
        if (resolution.isEmpty() && cameraPreviewSource != null)
            resolution = cameraPreviewSource.activeResolution();
        ((TextView) formView.findViewById(R.id.settings_camera_resolution)).setText(
                resolution.isEmpty() ? getString(R.string.i18n_a_auto) : resolution.replace("x", " × ") + "  ›");
    }

    private void chooseResolution() {
        if (cameraPreviewSource == null) return;
        java.util.List<String> sizes = cameraPreviewSource.supportedResolutions();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String size : sizes) labels.add(size.replace("x", " × "));
        String selected = activeOptions().resolution;
        if (selected.isEmpty()) selected = cameraPreviewSource.activeResolution();
        final String current = selected;
        dismissFormDialog();
        formDialog = ImmersiveUi.show(DesignChoiceDialog.create(this, getString(R.string.i18n_a_select_resolution),
                getString(R.string.i18n_a_changes_are_applied_and_saved_immediately), labels, sizes.indexOf(selected), which -> {
                    if (!uiReady()) return;
                    CameraImageOptions old = activeOptions();
                    String resolution = sizes.get(which);
                    if (resolution.equals(old.resolution)) return;
                    updateImageOptions(new CameraImageOptions(resolution, old.mirror, old.left, old.top, old.right, old.bottom),
                            !resolution.equals(current));
                }));
    }

    private void refreshCameraChoices() {
        cameraIds.clear();
        cameraLabels.clear();
        cameraIds.add("");
        cameraLabels.add(getString(R.string.settings_camera_auto));
        for (CameraSource.CameraInfo info : CameraSource.listCameraInfo(this)) {
            cameraIds.add(info.id);
            cameraLabels.add(UiMessages.text(this, info.label));
        }
        keepSavedCameraChoices(cameraLabels, cameraIds);
    }

    private void selectCamera(boolean hand, String id) {
        if (id.equals(hand ? handCamera : faceCamera)) return;
        if (hand) handOptions = CameraImageOptions.defaults();
        else faceOptions = CameraImageOptions.defaults();
        if (hand) handCamera = id;
        else faceCamera = id;
        // 保存镜头 ID 而非列表位置，旋转或 USB 枚举顺序变化时仍对应原来的物理镜头。
        android.content.SharedPreferences.Editor edit = prefs.edit().putString(hand ? "hand_camera" : "face_camera", id);
        (hand ? handOptions : faceOptions).save(edit, hand ? "hand" : "face");
        edit.apply();
        boolean roleChanged = previewHand != hand;
        ((RadioGroup) formView.findViewById(R.id.settings_camera_role))
                .check(hand ? R.id.settings_preview_hand : R.id.settings_preview_face);
        updateCameraChoiceButtons();
        if (!roleChanged) {
            updateImageControls();
            restartCameraPreview();
        }
    }

    private void updateCameraChoiceButtons() {
        View form = formView;
        if (form == null) return;
        ((TextView) form.findViewById(R.id.settings_choose_active_camera))
                .setText(cameraLabel(selectedPreviewCamera()) + "  ›");
    }

    private String cameraLabel(String id) {
        int position = cameraIds.indexOf(id);
        return position >= 0 ? cameraLabels.get(position) : id == null ? "" : id;
    }

    private String selectedPreviewCamera() {
        return previewHand ? handCamera : faceCamera;
    }

    private void updateCameraPreviewLabel(String id) {
        if (cameraPreviewLabel == null) return;
        cameraPreviewLabel.setText(getString(R.string.settings_camera_preview_label_value,
                getString(previewHand ? R.string.settings_camera_preview_hand : R.string.settings_camera_preview_face),
                cameraLabel(id)));
    }

    private void restartCameraPreview() {
        stopCameraPreview();
        updateCameraPreviewLabel(selectedPreviewCamera());
        startCameraPreview(false);
    }

    private void startCameraPreview(boolean retry) {
        if (!uiReady() || cameraPreviewSource == null || cameraPreviewRunning
                || cameraPermissionInFlight) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (cameraPermissionRequested && !retry) {
                showCameraPreviewError(getString(R.string.settings_camera_preview_permission_denied));
                return;
            }
            if (cameraPermissionRequested && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + this.getPackageName())));
                return;
            }
            cameraPermissionRequested = true;
            formState.putBoolean(CAMERA_PERMISSION, true);
            cameraPermissionInFlight = true;
            cameraPreviewMessage.setText(R.string.settings_camera_preview_permission);
            cameraPreviewMessage.setVisibility(View.VISIBLE);
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        cameraPreviewRunning = true;
        cameraPreviewGeneration++;
        cameraPreviewMessage.setText(R.string.settings_camera_preview_loading);
        cameraPreviewMessage.setVisibility(View.VISIBLE);
        updateCameraPreviewLabel(selectedPreviewCamera());
        cropView.imageSize(0, 0);
        updateImageControls();
        cameraPreviewSource.start(selectedPreviewCamera(), activeOptions().fullPreview());
    }

    private void stopCameraPreview() {
        cameraPreviewRunning = false;
        cameraPreviewGeneration++;
        if (cameraPreviewSource != null) cameraPreviewSource.stop();
    }

    private void showCameraPreviewError(String message) {
        if (cameraPreviewMessage == null) return;
        notice(message);
        cameraPreviewMessage.setText(R.string.settings_camera_preview_retry);
        cameraPreviewMessage.setVisibility(View.VISIBLE);
    }

    /**
     * USB 列表变化时保留已保存的镜头，不悄悄切回自动选择。
     */
    private void keepSavedCameraChoices(List<String> labels, List<String> ids) {
        for (String saved : new String[]{faceCamera, handCamera}) {
            if (!saved.isEmpty() && !ids.contains(saved)) {
                ids.add(saved);
                labels.add(getString(R.string.i18n_a_saved_camera, saved));
            }
        }
    }

    private void chooseCamera(boolean hand) {
        dismissFormDialog();
        refreshCameraChoices();
        java.util.List<String> ids = new java.util.ArrayList<>(cameraIds);
        formDialog = ImmersiveUi.show(DesignChoiceDialog.create(this, getString(R.string.i18n_a_select_camera),
                getString(R.string.i18n_a_changes_are_applied_and_saved_immediately), cameraLabels,
                ids.indexOf(hand ? handCamera : faceCamera), which -> {
                    if (!uiReady()) return;
                    selectCamera(hand, ids.get(which));
                }));
    }
}
