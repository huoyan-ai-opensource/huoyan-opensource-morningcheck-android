package cc.huoyan.opensource.morningcheck;

import static cc.huoyan.opensource.morningcheck.data.Jsons.obj;
import static cc.huoyan.opensource.morningcheck.data.Jsons.put;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.camera.CheckPhotoAnnotator;
import cc.huoyan.opensource.morningcheck.camera.HandEvidenceCapture;
import cc.huoyan.opensource.morningcheck.camera.PreviewFaceDetector;
import cc.huoyan.opensource.morningcheck.data.RecordPolicy;
import cc.huoyan.opensource.morningcheck.domain.CaptureSession;
import cc.huoyan.opensource.morningcheck.domain.CheckGuidance;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.CheckVoiceText;
import cc.huoyan.opensource.morningcheck.domain.FaceDetectionResult;
import cc.huoyan.opensource.morningcheck.domain.FacePresencePolicy;
import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.hardware.LightController;
import cc.huoyan.opensource.morningcheck.hardware.TemperatureSource;

/**
 * 现场晨检仅负责连续采集流水线；完成后通过记录 ID 打开独立结果页面。
 */
public final class CheckActivity extends CameraMorningActivity {
    private static final String IDENTITY_VOICE_KEY = "stage:IDENTITY";
    private static final String STATE_IDENTITY_VOICE_ANNOUNCED = "check.identity_voice_announced";
    private static final String STATE_CONNECTION_LOST = "check.connection_lost";
    private TemperatureSource thermometer;
    private LightController.Scope lighting;
    private boolean lightingResumed;
    private String lightState = "";
    private LightConfig requestedLightConfig;
    private Boolean requestedLightOn;
    private MorningVoice speech;
    private boolean identityVoiceAnnounced;
    private boolean connectionLost;
    private boolean paused, frameBusy, saving, waitingDeparture, resultOpened, resumed;
    private long lastPerson;
    private long staffCount;
    private int staffLoadRequest;
    private int frameRequest;
    private String activeFrameRequest;
    private long nextFrameAt;
    private final CaptureSession captureSession = new CaptureSession();
    private String sensorState = "";
    private double liveTemperature = Double.NaN;
    private long temperatureReceivedAt, identityReceivedAt, temperatureReceivedNanos, candidateFrameNanos;
    private boolean facePreviewBusy;
    private int facePreviewGeneration;
    private int faceOverlayGeneration;
    private CameraSource.Frame facePreviewFrame;
    private JSONArray facePreviewBoxes;
    private CheckPipeline.Stage facePreviewStage;
    private int facePreviewSession, facePreviewAppliedGeneration;
    private boolean facePreviewMirror;
    private static final long SAMPLE_MAX_AGE_MS = 5_000;
    private TextView checkHint, checkPromptLabel, checkPerson, checkPersonDetail, checkProgress, checkTemperature, pauseButton;
    private Boolean checkPromptWarning;
    private CheckPipeline pipeline;
    private AlertDialog exitDialog;
    private boolean exitRequested, exitApproved, pausedBeforeExit;
    private boolean dialogCapturePaused, rebuildingPreview, previewPausedForDialog;
    private CheckPipeline.Rules checkRules;
    private final FacePresencePolicy facePresence = new FacePresencePolicy();
    private CheckPipeline.Stage facePresenceStage;
    private final Runnable facePresenceExpiry = this::updateVoice;
    private boolean temperatureRequested;
    private final Runnable temperaturePresenceExpiry = () -> {
        syncTemperatureWithFace();
        updateCheck();
    };
    private String inferenceIssue = "", identityIssue = "";
    private boolean storageError, identityReadError;
    private String shownPromptError = "";
    private JSONObject activeStaff;
    private CameraSource.Frame identityPhoto, facePhoto, backPhoto, palmPhoto;
    private final HandEvidenceCapture handCapture = new HandEvidenceCapture();
    private HandEvidenceCapture.Evidence backEvidence, palmEvidence;

    @Override
    protected String pageKey() {
        return "check";
    }

    @Override
    protected int activityLayout() {
        if (pipeline == null && connectionLost) return R.layout.activity_check_disconnected;
        return pipeline == null ? R.layout.activity_check_design_ready : R.layout.activity_check_design_active;
    }

    @Override
    protected void setup(Bundle state) {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 现场页面只允许通过右下角的密码出口离开。
            }
        });
        connectionLost = state != null && state.getBoolean(STATE_CONNECTION_LOST);
        lastPerson = getIntent().getLongExtra("last_person", 0);
        waitingDeparture = lastPerson > 0;
        identityVoiceAnnounced = state != null && state.getBoolean(STATE_IDENTITY_VOICE_ANNOUNCED);
        thermometer = new TemperatureSource(new TemperatureSource.Listener() {
            @Override
            public void onTemperature(double value) {
                acceptTemperature(value);
            }

            @Override
            public void onState(String message) {
                sensorState = message;
                updateCheck();
            }

            @Override
            public void onError(String message) {
                notice(message);
                updateCheck();
            }
        });
        speech = new MorningVoice(this, main, () -> rule("voice", true));
        if (identityVoiceAnnounced) speech.restoreAnnounced(IDENTITY_VOICE_KEY);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean(STATE_CONNECTION_LOST, connectionLost);
        state.putBoolean(STATE_IDENTITY_VOICE_ANNOUNCED,
                identityVoiceAnnounced || speech != null && speech.hasAnnounced(IDENTITY_VOICE_KEY));
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDataLoaded() {
        if (dialogCapturePaused) return;
        if (currentRecord != null) {
            openResult();
            return;
        }
        if (!exitRequested && pipeline == null && hasEmployeeForCheck() && sdkReady()) startCheck(false);
        else if (pipeline == null) render();
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        showWorkLoading(getString(R.string.i18n_a_loading_staff));
        int request = ++staffLoadRequest;
        runIo(() -> {
            try {
                long count = store.staffCount();
                main.post(() -> {
                    if (destroyed || !foreground || request != staffLoadRequest) return;
                    workLoading.dismiss();
                    staffCount = count;
                    loaded = true;
                    if (done != null) done.run();
                    else onDataLoaded();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (request == staffLoadRequest) {
                        if (workLoading != null) workLoading.dismiss();
                        notice(getString(R.string.i18n_a_staff_read_failed, failure(error)));
                        clearFacePresence();
                        speech.clearStatus();
                    }
                });
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        if (currentRecord != null) openResult();
        else if (pipeline != null) render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        resumed = true;
        if (!dialogCapturePaused) speech.resume();
        lightingResumed = true;
        requestedLightConfig = null;
        requestedLightOn = null;
        lighting = LightController.getInstance().acquire((on, success, message) -> {
            if (!lightingResumed || destroyed) return;
            lightState = message;
            updateCheck();
        });
        updateLighting();
        updateCheck();
        if (previewPausedForDialog && pipeline != null && currentRecord == null && !saving)
            ensureCamera(cameraForStage());
        if (pipeline != null && pipeline.getStage() == CheckPipeline.Stage.IDENTITY && captureIsCurrent())
            startThermometer();
        if (currentRecord != null) openResult();
    }

    @Override
    protected void onPause() {
        resumed = false;
        // onPause 到 onStop 之间也不能继续两段推理；恢复时从当前步骤重新连续采样。
        session++;
        cancelFrame();
        interruptSamples();
        thermometer.stop();
        speech.pause();
        lightingResumed = false;
        closeLighting();
        super.onPause();
    }

    private void closeLighting() {
        if (lighting != null) lighting.close();
        lighting = null;
        requestedLightConfig = null;
        requestedLightOn = null;
    }

    private void updateLighting() {
        if (!lightingResumed || lighting == null) return;
        boolean on = pipeline != null && pipeline.getStage() != CheckPipeline.Stage.RESULT
                && !capturePaused() && !saving && currentRecord == null && sdkReady() && cameraError.isEmpty();
        LightConfig config;
        try {
            config = LightConfig.read(prefs);
        } catch (IllegalArgumentException invalid) {
            lightState = getString(R.string.i18n_a_light_invalid, UiMessages.text(this, invalid.getMessage()));
            if (requestedLightConfig != null) lighting.turnOff(requestedLightConfig);
            requestedLightConfig = null;
            requestedLightOn = null;
            return;
        }
        if (config.equals(requestedLightConfig) && Boolean.valueOf(on).equals(requestedLightOn))
            return;
        // 失败后只在业务状态改变或返回页面时重试，避免每帧重复打开故障串口。
        requestedLightConfig = config;
        requestedLightOn = on;
        lighting.set(config, on);
    }

    @Override
    protected void onSdkStateChanged() {
        if (dialogCapturePaused) return;
        if (pipeline != null && !saving && currentRecord == null && !captureIsCurrent()) {
            connectionLost = !sdk.isConnected();
            discardPartialCheck();
            if (foreground) render();
        }
        if (connectionLost && sdk.isConnected()) {
            connectionLost = false;
            if (foreground && pipeline == null) render();
        }
        if (foreground && !exitRequested && loaded && pipeline == null && hasEmployeeForCheck() && sdkReady())
            startCheck(false);
        else updateCheck();
    }

    @Override
    protected void onStop() {
        dismissExitDialog();
        clearFacePresence();
        closeLighting();
        staffLoadRequest++;
        loaded = false;
        // 返回现场时重新确认人员，不能把离开前的体温、头手证据接到另一个人身上。
        if (!saving && currentRecord == null) discardPartialCheck();
        else cancelFrame();
        thermometer.stop();
        if (speech != null) speech.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dismissExitDialog();
        clearFacePresence();
        closeLighting();
        thermometer.close();
        if (speech != null) speech.close();
        super.onDestroy();
    }

    @Override
    protected void beforeNavigate(String target, Runnable action) {
        // 普通导航、页头及返回键均不能绕过右下角的密码验证。
    }

    @Override
    public void onBackPressed() {
        // 弹窗自行处理返回取消，晨检页不执行通用返回首页导航。
    }

    private void requestExit() {
        if (!foreground || destroyed || isFinishing() || exitRequested) return;
        if (saving) {
            showWorkLoading(getString(R.string.i18n_a_saving_check_record));
            return;
        }
        exitRequested = true;
        pausedBeforeExit = paused;
        paused = true;
        suspendDialogCapture();
        exitDialog = SecurityPasswordDialog.showVerify(this, () -> {
            exitApproved = true;
            startActivity(new Intent(this, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_SKIP_AUTO_CHECK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        }, () -> {
            exitDialog = null;
            exitRequested = false;
            paused = pausedBeforeExit;
            restoreDialogCapture();
        });
    }

    private void dismissExitDialog() {
        if (exitDialog != null) exitDialog.dismiss();
    }

    private void openResult() {
        if (!foreground || !resumed || destroyed || dialogCapturePaused || currentRecord == null || resultOpened) return;
        resultOpened = true;
        startActivity(new Intent(this, ResultActivity.class).putExtra("record_id", currentRecord.optString("id")));
        finish();
    }

    private void startCheck(boolean userInitiated) {
        if (dialogCapturePaused) return;
        if (!loaded) {
            showWorkLoading(getString(R.string.i18n_a_loading_staff));
            return;
        }
        if (!hasEmployeeForCheck()) {
            notice(getString(R.string.i18n_a_add_staff_and_enroll_their_faces_before_starting_a_check));
            navigate("staff");
            return;
        }
        if (!sdkReady()) {
            notice(getString(R.string.i18n_a_the_morning_check_sdk_is_not_ready_connect_activate_and_initialize_it_in_de));

            navigate("settings");
            return;
        }
        session++;
        captureSession.begin(sdkGeneration(), sdk.getStatus().optString("device_id"));
        saving = false;
        paused = false;
        sensorState = "";
        currentRecord = null;
        activeStaff = null;
        if (userInitiated) {
            identityVoiceAnnounced = false;
            speech.reset();
        }
        resetVoiceState();
        if (userInitiated) {
            waitingDeparture = false;
            lastPerson = 0;
        }
        identityPhoto = facePhoto = backPhoto = palmPhoto = null;
        latestFrame = null;
        cameraError = "";
        handCapture.reset();
        backEvidence = palmEvidence = null;
        double limit;
        try {
            limit = Double.parseDouble(setting("temperature_limit", "37.3"));
        } catch (NumberFormatException e) {
            limit = 37.3;
        }
        checkRules = new CheckPipeline.Rules(4, 4, 5, rule("head", true), rule("hat", true), rule("mask", true), limit);
        pipeline = new CheckPipeline(checkRules);
        if (!rule("temperature", true)) pipeline.skipTemperature();
        // 身份流程一启动就排入首句；不等相机帧、脸框或上一位离场的识别回包。
        speech.enterStage(IDENTITY_VOICE_KEY,
                CheckVoiceText.stage(CheckPipeline.Stage.IDENTITY, checkRules.requireMask, checkRules.requireHat));
        render();
    }

    private boolean hasEmployeeForCheck() {
        return staffCount > 0;
    }

    @Override
    protected void render() {
        rebuildingPreview = true;
        try {
            super.render();
        } finally {
            rebuildingPreview = false;
        }
        if (foreground && resumed && !dialogCapturePaused && !exitApproved && !isFinishing()) {
            speech.resume();
            updateCheck();
        }
    }

    @Override
    protected void beforeRender() {
        // 重排只替换页面内容，独立密码窗口继续持有暂停状态，由自身监听调整尺寸。
        cancelFrame();
        checkHint = checkPromptLabel = checkPerson = checkPersonDetail = checkProgress = checkTemperature = pauseButton = null;
        checkPromptWarning = null;
        super.beforeRender();
    }

    private boolean captureIsCurrent() {
        return captureSession.accepts(sdkReady(), sdkGeneration(), sdk.getStatus().optString("device_id"));
    }

    private void cancelFrame() {
        frameRequest++;
        facePreviewGeneration++;
        facePreviewBusy = false;
        faceOverlayGeneration++;
        clearFacePresence();
        if (overlay != null) overlay.show(null, 640, 480, false);
        frameBusy = false;
        String request = activeFrameRequest;
        activeFrameRequest = null;
        if (request != null) sdk.cancel(request);
    }

    private void discardPartialCheck() {
        session++;
        cancelFrame();
        captureSession.clear();
        handCapture.reset();
        backEvidence = palmEvidence = null;
        pipeline = null;
        updateLighting();
        activeStaff = null;
        identityPhoto = facePhoto = backPhoto = palmPhoto = null;
        latestFrame = null;
        resetVoiceState();
        clearTemperatureSample();
        nextFrameAt = 0;
        stopCamera();
        if (thermometer != null) thermometer.stop();
    }

    @Override
    protected void bindHeader(View root) {
        updateHeaderShop();
    }

    @Override
    protected void updateHeaderShop() {
        TextView shop = findViewById(R.id.header_shop);
        if (shop == null) return;
        String name = currentShopName();
        shop.setText(name.isEmpty() ? getString(R.string.i18n_a_no_workplace_set) : name);
    }

    @Override
    protected void bindPage() {
        content.findViewById(R.id.check_exit).setOnClickListener(v -> requestExit());
        if (currentRecord != null) {
            openResult();
            return;
        }
        if (pipeline == null) {
            if (connectionLost) {
                updateVoice();
                return;
            }
            ((TextView) content.findViewById(R.id.check_person)).setText(getString(R.string.i18n_a_not_ready));
            ((TextView) content.findViewById(R.id.check_hint)).setText(!hasEmployeeForCheck()
                    ? getString(R.string.i18n_a_add_staff_and_enroll_their_faces_first) : getString(R.string.i18n_a_connect_the_device_to_start_a_check));
            content.findViewById(R.id.check_temperature).setVisibility(View.GONE);
            content.findViewById(R.id.check_prompt_label).setVisibility(View.GONE);
            // 自动检查尚未启动，不能把设计中的“已连接”示例当作真实硬件状态。
            content.findViewById(R.id.check_ready_devices).setVisibility(View.VISIBLE);
            updateVoice();
            return;
        }
        View view = content;
        view.findViewById(R.id.check_progress).setOnLongClickListener(v -> {
            view.findViewById(R.id.check_pause).setVisibility(View.VISIBLE);
            return true;
        });
        FrameLayout preview = previewFrame(R.layout.view_check_camera_preview);
        ((android.view.TextureView) preview.findViewById(R.id.camera_texture)).setOpaque(false);
        ((FrameLayout) view.findViewById(R.id.check_camera_slot)).addView(preview);
        checkHint = view.findViewById(R.id.check_hint);
        checkPromptLabel = view.findViewById(R.id.check_prompt_label);
        checkPerson = view.findViewById(R.id.check_person);
        checkPersonDetail = view.findViewById(R.id.check_person_detail);
        checkProgress = view.findViewById(R.id.check_progress);
        checkTemperature = view.findViewById(R.id.check_temperature);
        pauseButton = view.findViewById(R.id.check_pause);
        pauseButton.setText(paused ? getString(R.string.i18n_a_resume) : getString(R.string.i18n_a_pause));
        pauseButton.setOnClickListener(v -> {
            paused = !paused;
            session++;
            cancelFrame();
            interruptSamples();
            if (paused) thermometer.stop();
            else if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY) startThermometer();
            updateCheck();
            pauseButton.setText(paused ? getString(R.string.i18n_a_resume) : getString(R.string.i18n_a_pause));
        });
        updateCheck();
        ensureCamera(cameraForStage());
        if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY) startThermometer();
    }

    private boolean capturePaused() {
        return paused || dialogCapturePaused;
    }

    private void suspendDialogCapture() {
        dialogCapturePaused = true;
        previewPausedForDialog = true;
        // 弹窗前的推理、档案读取和连续采样均失效；只保留已完成的步骤。
        session++;
        cancelFrame();
        interruptSamples();
        thermometer.stop();
        speech.pause();
        if (camera != null) camera.stop();
        latestFrame = null;
        showCameraPreview(false);
        updateCheck();
    }

    private void restoreDialogCapture() {
        if (exitRequested) return;
        dialogCapturePaused = false;
        // 重排和离页关闭窗口时，不能先重开即将释放的旧预览。
        if (!foreground || !resumed || destroyed || isFinishing() || exitApproved || rebuildingPreview) return;
        speech.resume();
        onSdkStateChanged();
        if (currentRecord != null) openResult();
        else if (pipeline != null && !saving) {
            if (previewPausedForDialog) ensureCamera(cameraForStage());
            if (!paused && pipeline.getStage() == CheckPipeline.Stage.IDENTITY) startThermometer();
        }
        updateCheck();
    }

    private void showCameraPreview(boolean visible) {
        View texture = content == null ? null : content.findViewById(R.id.camera_texture);
        if (texture != null) texture.setAlpha(visible ? 1f : 0f);
        if (overlay != null) overlay.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    protected void ensureCamera(String id) {
        if (dialogCapturePaused || exitApproved || isFinishing()) {
            previewPausedForDialog = true;
            if (camera != null) camera.stop();
            showCameraPreview(false);
            return;
        }
        previewPausedForDialog = false;
        showCameraPreview(true);
        super.ensureCamera(id);
    }

    @Override
    protected String cameraForStage() {
        boolean hand = pipeline != null && (pipeline.getStage() == CheckPipeline.Stage.HAND_BACK || pipeline.getStage() == CheckPipeline.Stage.HAND_PALM);
        return setting(hand ? "hand_camera" : "face_camera", "");
    }

    private boolean sameSession(int value) {
        return !destroyed && foreground && resumed && session == value && pipeline != null
                && captureIsCurrent() && pageKey().equals("check") && !capturePaused();
    }

    @Override
    protected void onCameraFrame(CameraSource.Frame frame) {
        if (!foreground || !resumed || !pageKey().equals("check") || pipeline == null || capturePaused() || saving
                || currentRecord != null || !captureIsCurrent())
            return;
        if (!cameraError.isEmpty()) {
            cameraError = "";
            if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY) startThermometer();
        }
        updatePreviewStatus();
        CheckPipeline.Stage stage = pipeline.getStage();
        if (stage == CheckPipeline.Stage.RESULT) return;
        int expectedSession = session;
        // 在身份/业务队列节流之前更新在场证据；档案读取和 SDK 忙不能延长旧的缺脸事实。
        if (stage == CheckPipeline.Stage.IDENTITY)
            updateFacePreview(frame, expectedSession, stage);
        if (frameBusy || SystemClock.elapsedRealtime() < nextFrameAt) return;
        frameBusy = true;
        if (stage == CheckPipeline.Stage.IDENTITY) {
            int request = ++frameRequest;
            activeFrameRequest = sdk.identify(frame.rgb, frame.width, frame.height, (code, message, data) -> {
                if (request != frameRequest || !sameSession(expectedSession) || stage != pipeline.getStage())
                    return;
                if (code != 0) {
                    frameBusy = false;
                    inferenceFailure(code);
                    return;
                }
                long personId = data.optLong("person_id");
                boolean matched = data.optBoolean("matched");
                boolean matchAvailable = data.opt("matched") instanceof Boolean;
                inferenceIssue = "";
                // 匹配到另一位员工仍代表有人脸；此证据只取消入镜提醒，不改变身份准入。
                if (matched) acceptFacePresence(frame, stage, true);
                if (!matched)
                    acceptIdentity(request, expectedSession, 0, false, matchAvailable, null, frame);
                else {
                    // SDK 人员标识与本地主键独立；反查期间仍占用本帧，流水线和记录只使用本地主键。
                    runIo(() -> {
                        try {
                            JSONObject known = store.staffByPersonId(personId);
                            long staffId = known == null ? 0 : known.optLong("id");
                            main.post(() -> acceptIdentity(request, expectedSession, staffId, true, matchAvailable, known, frame));
                        } catch (Exception error) {
                            main.post(() -> {
                                if (request != frameRequest || !sameSession(expectedSession))
                                    return;
                                frameBusy = false;
                                interruptSamples();
                                nextFrameAt = SystemClock.elapsedRealtime() + 2000;
                                identityIssue = getString(R.string.i18n_a_could_not_load_staff_details_try_again_later);
                                identityReadError = true;
                                updateVoice();
                            });
                        }
                    });
                }
            });
        } else if (stage == CheckPipeline.Stage.HEAD) {
            detectHeadFace(frame, expectedSession);
        } else detect(frame, stage, expectedSession);
    }

    private void detectHeadFace(CameraSource.Frame frame, int expectedSession) {
        int request = ++frameRequest;
        activeFrameRequest = sdk.detectFace(frame.rgb, frame.width, frame.height,
                FaceDetectionResult.MAX_FACES, (code, message, data) -> {
                    if (request != frameRequest || !sameSession(expectedSession)
                            || pipeline.getStage() != CheckPipeline.Stage.HEAD) return;
                    if (code != 0) {
                        frameBusy = false;
                        clearFacePresence();
                        clearFaceOverlay(frame);
                        inferenceFailure(code);
                        return;
                    }
                    final FaceDetectionResult faces;
                    try {
                        faces = FaceDetectionResult.parse(data);
                    } catch (IllegalArgumentException invalid) {
                        frameBusy = false;
                        clearFacePresence();
                        clearFaceOverlay(frame);
                        inferenceFailure(HuoyanClient.ERROR_INVALID_REQUEST);
                        return;
                    }
                    long now = System.nanoTime();
                    if (!facePresence.observeDetection(frame.receivedAtNanos, faces.count, now)) {
                        rejectExpiredHeadFrame(frame);
                        return;
                    }
                    inferenceIssue = "";
                    acceptFacePreview(frame, faces.boxes, true, expectedSession, CheckPipeline.Stage.HEAD,
                            ++facePreviewGeneration, false);
                    if (!faces.isSingle()) {
                        frameBusy = false;
                        // 多人或无人只清除本组头检样本，已锁定的员工和体温仍属于本轮。
                        pipeline.onHeadDetections(Collections.emptyList(), false);
                        updateCheck();
                        return;
                    }
                    updateVoice();
                    // 头检只判断单人入镜；口罩会影响身份比对，因此不再重复 identify。
                    // 两段请求始终持有同一 RGB 帧，整轮结束前不接收下一帧业务采样。
                    detect(frame, CheckPipeline.Stage.HEAD, expectedSession);
                });
    }

    private void rejectExpiredHeadFrame(CameraSource.Frame frame) {
        frameBusy = false;
        clearFacePresence();
        clearFaceOverlay(frame);
        pipeline.onHeadDetections(Collections.emptyList(), false);
        inferenceIssue = getString(R.string.i18n_a_head_sample_expired_stay_in_view);
        updateCheck();
    }

    private void clearFaceOverlay(CameraSource.Frame frame) {
        faceOverlayGeneration++;
        if (overlay != null) overlay.show(null, frame.width, frame.height, false);
    }

    private void acceptIdentity(int request, int expectedSession, long id, boolean matched, boolean matchAvailable,
                                JSONObject known, CameraSource.Frame frame) {
        if (request != frameRequest || !sameSession(expectedSession)
                || pipeline.getStage() != CheckPipeline.Stage.IDENTITY) return;
        frameBusy = false;
        // IO 队列可能等待导入或照片保存；过期身份帧不能推进下一步或充当当前人员证据。
        long frameAge = System.nanoTime() - frame.receivedAtNanos;
        if (frameAge < 0 || frameAge >= FacePresencePolicy.MAX_MATCH_FRAME_AGE_NANOS) {
            interruptSamples();
            identityIssue = getString(R.string.i18n_a_identity_sample_expired_keep_facing_the_camera);
            identityReadError = true;
            updateVoice();
            return;
        }
        // lastPerson 来自本机记录，必须在 person_id 反查后比较，不能混用两种编号。
        if (waitingDeparture && matched && known != null && id == lastPerson) {
            updateVoice();
            return;
        }
        if (waitingDeparture) {
            waitingDeparture = false;
            startThermometer();
        }
        long previousCandidate = pipeline.getCandidateId();
        boolean validIdentity = matched && known != null && known.optBoolean("enabled", true);
        identityReadError = false;
        identityIssue = matched && known == null ? getString(R.string.i18n_a_no_staff_profile_found_add_the_staff_details_first)
                : matched && !known.optBoolean("enabled", true) ? getString(R.string.i18n_a_staff_profile_disabled_contact_an_administrator) : "";
        if (matchAvailable)
            facePresence.match(frame.receivedAtNanos, validIdentity, System.nanoTime());
        else facePresence.matchingUnknown(frame.receivedAtNanos);
        syncTemperatureWithFace();
        pipeline.onIdentity(id, validIdentity);
        if (previousCandidate > 0 && previousCandidate != pipeline.getCandidateId()) {
            // 候选员工改变后换一轮串口采集，丢弃已排队的上一人读数。
            startThermometer();
        }
        if (previousCandidate != pipeline.getCandidateId() || candidateFrameNanos == 0)
            candidateFrameNanos = pipeline.getCandidateId() > 0 ? frame.receivedAtNanos : 0;
        // 读数必须晚于本轮候选人的采集帧，不能沿用上一人的温度。
        identityReceivedAt = validIdentity ? SystemClock.elapsedRealtime() : 0;
        if (pipeline.getCandidateId() > 0) activeStaff = known;
        else {
            activeStaff = null;
            identityPhoto = facePhoto = null;
        }
        if (matched && known == null) {
            updateCheck();
            return;
        }
        if (pipeline.getPersonId() > 0) {
            activeStaff = known;
            identityPhoto = frame;
            facePhoto = frame;
        }
        applyTemperatureSample();
        afterSample(CheckPipeline.Stage.IDENTITY, frame);
    }

    private void detect(CameraSource.Frame frame, CheckPipeline.Stage stage, int expectedSession) {
        int request = ++frameRequest;
        frameBusy = true;
        HuoyanClient.Callback callback = (code, message, data) -> {
            if (request != frameRequest || !sameSession(expectedSession)
                    || stage != pipeline.getStage()) return;
            frameBusy = false;
            if (code != 0) {
                if (stage == CheckPipeline.Stage.HEAD) {
                    clearFacePresence();
                    clearFaceOverlay(frame);
                }
                inferenceFailure(code);
                return;
            }
            if (stage == CheckPipeline.Stage.HEAD) {
                long age = System.nanoTime() - frame.receivedAtNanos;
                if (age < 0 || age >= FacePresencePolicy.MAX_MATCH_FRAME_AGE_NANOS) {
                    rejectExpiredHeadFrame(frame);
                    return;
                }
            }
            inferenceIssue = "";
            JSONArray boxes = data.optJSONArray("boxes");
            if (boxes == null) {
                inferenceFailure(HuoyanClient.ERROR_INVALID_REQUEST);
                return;
            }
            List<CheckPipeline.Detection> detections = new ArrayList<>();
            if (boxes != null) for (int i = 0; i < boxes.length(); i++) {
                JSONObject b = boxes.optJSONObject(i);
                if (b != null)
                    detections.add(new CheckPipeline.Detection(b.optInt("class_id", -1), (float) b.optDouble("score", 0)));
            }
            showDetectionOverlay(frame, stage, boxes);
            // HEAD 仅由同帧 detectFace 的单脸结果进入；不把帽子/口罩框当作入镜证据。
            if (stage == CheckPipeline.Stage.HEAD) pipeline.onHeadDetections(detections, true);
            else {
                pipeline.onDetections(detections);
                if (pipeline.getStage() != stage || pipeline.getProgress() > 0) {
                    // 坐标与本次送检 RGB 一起保留，不把前帧戒指框套在末帧正常照片上。
                    handCapture.accept(frame, boxes);
                    if (pipeline.getStage() != stage) {
                        if (stage == CheckPipeline.Stage.HAND_BACK)
                            backEvidence = handCapture.evidence();
                        else palmEvidence = handCapture.evidence();
                        handCapture.reset();
                    }
                } else handCapture.reset();
            }
            afterSample(stage, frame);
        };
        activeFrameRequest = stage == CheckPipeline.Stage.HEAD
                ? sdk.checkHead(frame.rgb, frame.width, frame.height, 30, callback)
                : sdk.checkHand(frame.rgb, frame.width, frame.height, 30, callback);
    }

    @Override
    protected String cameraRole() {
        return pipeline != null && (pipeline.getStage() == CheckPipeline.Stage.HAND_BACK || pipeline.getStage() == CheckPipeline.Stage.HAND_PALM)
                ? "hand" : "face";
    }

    private void updateFacePreview(CameraSource.Frame frame, int expectedSession, CheckPipeline.Stage expectedStage) {
        if (facePreviewBusy) return;
        facePreviewBusy = true;
        int expectedGeneration = ++facePreviewGeneration;
        // CameraSource 已统一预览与送检帧方向，检测框不再二次镜像。
        boolean mirror = false;
        runIo(() -> {
            PreviewFaceDetector.Result result = PreviewFaceDetector.detectResult(frame);
            main.post(() -> acceptFacePreview(frame, result.boxes, result.available,
                    expectedSession, expectedStage, expectedGeneration, mirror));
        });
    }

    private void acceptFacePreview(CameraSource.Frame frame, JSONArray boxes, boolean available, int expectedSession,
                                   CheckPipeline.Stage expectedStage, int expectedGeneration, boolean mirror) {
        if (expectedGeneration != facePreviewGeneration) return;
        facePreviewBusy = false;
        long frameAgeNanos = System.nanoTime() - frame.receivedAtNanos;
        if (!sameSession(expectedSession) || !resumed || pipeline.getStage() != expectedStage
                || frameAgeNanos < 0 || frameAgeNanos >= FacePresencePolicy.MAX_AGE_NANOS) return;
        facePreviewFrame = frame;
        facePreviewBoxes = available ? boxes : null;
        facePreviewStage = expectedStage;
        facePreviewSession = expectedSession;
        facePreviewAppliedGeneration = expectedGeneration;
        facePreviewMirror = mirror;
        if (expectedStage == CheckPipeline.Stage.IDENTITY) {
            if (available)
                acceptFacePresence(frame, expectedStage, boxes != null && boxes.length() > 0);
            else if (facePresence.unknown(frame.receivedAtNanos)) {
                syncTemperatureWithFace();
                updateVoice();
            }
        }
        showFacePreviewIfNeeded();
    }

    private void showFacePreviewIfNeeded() {
        CameraSource.Frame frame = facePreviewFrame;
        if (overlay == null || frame == null || !resumed || !sameSession(facePreviewSession)
                || facePreviewAppliedGeneration != facePreviewGeneration || pipeline.getStage() != facePreviewStage)
            return;
        long frameAgeNanos = System.nanoTime() - frame.receivedAtNanos;
        if (frameAgeNanos < 0 || frameAgeNanos >= FacePresencePolicy.MAX_AGE_NANOS) return;
        overlay.show(facePreviewBoxes, frame.width, frame.height, facePreviewMirror,
                facePreviewBoxes != null && facePreviewBoxes.length() > 1);
        int shownGeneration = ++faceOverlayGeneration;
        scheduleFaceOverlayExpiry(frame, shownGeneration, facePreviewMirror);
    }

    private void showDetectionOverlay(CameraSource.Frame frame, CheckPipeline.Stage stage, JSONArray boxes) {
        if (overlay == null) return;
        long nowNanos = System.nanoTime();
        long frameAgeNanos = nowNanos - frame.receivedAtNanos;
        // 着装框只使用同帧的单脸证据；绘制时效比完整采样时效更短。
        if (stage == CheckPipeline.Stage.HEAD && (frameAgeNanos < 0
                || frameAgeNanos >= FacePresencePolicy.MAX_AGE_NANOS
                || !facePresence.isPresent(nowNanos) || facePresence.isMultiple(nowNanos))) return;
        boolean mirror = false;
        int shownGeneration = ++faceOverlayGeneration;
        overlay.show(boxes, frame.width, frame.height, mirror);
        if (stage == CheckPipeline.Stage.HEAD)
            scheduleFaceOverlayExpiry(frame, shownGeneration, mirror);
    }

    private void scheduleFaceOverlayExpiry(CameraSource.Frame frame, int shownGeneration, boolean mirror) {
        long remainingNanos = FacePresencePolicy.MAX_AGE_NANOS - (System.nanoTime() - frame.receivedAtNanos);
        long remainingMillis = Math.max(1, (remainingNanos + 999_999L) / 1_000_000L);
        main.postDelayed(() -> {
            if (shownGeneration == faceOverlayGeneration && overlay != null)
                overlay.show(null, frame.width, frame.height, mirror);
        }, remainingMillis);
    }

    private void acceptFacePresence(CameraSource.Frame frame, CheckPipeline.Stage expectedStage, boolean present) {
        if (pipeline == null || pipeline.getStage() != expectedStage) return;
        if (facePresence.observe(frame.receivedAtNanos, present, System.nanoTime())) {
            syncTemperatureWithFace();
            updateVoice();
        }
    }

    private void clearFacePresence() {
        main.removeCallbacks(facePresenceExpiry);
        facePresence.reset();
        stopFaceTemperature();
        facePreviewFrame = null;
        facePreviewBoxes = null;
    }

    private void scheduleFacePresenceExpiry(long nowNanos) {
        main.removeCallbacks(facePresenceExpiry);
        if (!facePresence.isAbsent(nowNanos) && !facePresence.isUnmatched(nowNanos)
                && !facePresence.isMultiple(nowNanos)) return;
        // 没有新有效帧时立即撤销提醒，不能对着断流画面无限重复入镜提示。
        long remaining = facePresence.expiresAtNanos() - nowNanos;
        main.postDelayed(facePresenceExpiry, Math.max(1, (remaining + 999_999L) / 1_000_000L));
    }

    private void inferenceFailure(int code) {
        interruptSamples();
        nextFrameAt = SystemClock.elapsedRealtime() + (code == 40010 ? 1000 : 2000);
        inferenceIssue = code == 40010 ? getString(R.string.i18n_a_check_busy_please_wait) : getString(R.string.i18n_a_check_failed_recovering);
        updateVoice();
        if (code != 40010) sdk.refreshStatus(null);
    }

    private void afterSample(CheckPipeline.Stage before, CameraSource.Frame frame) {
        CheckPipeline.Stage now = pipeline.getStage();
        if (before != now) {
            if (before == CheckPipeline.Stage.HEAD) facePhoto = frame;
            if (before == CheckPipeline.Stage.HAND_BACK)
                backPhoto = backEvidence == null || backEvidence.primary == null ? null : backEvidence.primary.frame;
            if (before == CheckPipeline.Stage.HAND_PALM)
                palmPhoto = palmEvidence == null || palmEvidence.primary == null ? null : palmEvidence.primary.frame;
            if (before == CheckPipeline.Stage.IDENTITY) {
                // 两项共同确认后结束身份采集，后续阶段保留本次锁定的体温。
                cancelFrame();
                thermometer.stop();
            }
            if (now == CheckPipeline.Stage.HAND_BACK && camera != null && !setting("face_camera", "").equals(setting("hand_camera", ""))) {
                session++;
                camera.stop();
                ensureCamera(cameraForStage());
            }
        }
        updateCheck();
        if (pipeline.getStage() == CheckPipeline.Stage.RESULT) finishCheck();
    }

    private void startThermometer() {
        if (!foreground || capturePaused() || !pageKey().equals("check") || pipeline == null
                || pipeline.getStage() != CheckPipeline.Stage.IDENTITY) return;
        stopFaceTemperature();
        clearTemperatureSample();
        if (waitingDeparture) {
            thermometer.stop();
            sensorState = getString(R.string.i18n_a_waiting_for_the_previous_person_to_leave);
            updateCheck();
            return;
        }
        if (!rule("temperature", true)) {
            sensorState = getString(R.string.i18n_a_temperature_check_disabled);
            updateCheck();
            return;
        }
        if (!canMeasureFaceTemperature() || thermometer == null) return;
        temperatureRequested = true;
        scheduleTemperaturePresenceExpiry();
        int baud;
        try {
            baud = Integer.parseInt(setting("baud", "115200"));
        } catch (NumberFormatException e) {
            baud = 115200;
        }
        thermometer.start(setting("serial_path", "/dev/ttyS4"), baud, setting("temperature_mode", "串口被动上报").equals("串口主动查询"));
    }

    private boolean canMeasureFaceTemperature() {
        return foreground && resumed && !destroyed && !capturePaused() && !waitingDeparture
                && pipeline != null && pipeline.getStage() == CheckPipeline.Stage.IDENTITY
                && !pipeline.isTemperatureSkipped() && captureIsCurrent() && cameraError.isEmpty()
                && facePresence.isPresent(System.nanoTime());
    }

    private void syncTemperatureWithFace() {
        if (!canMeasureFaceTemperature()) {
            stopFaceTemperature();
            return;
        }
        if (!temperatureRequested) startThermometer();
        else scheduleTemperaturePresenceExpiry();
    }

    private void scheduleTemperaturePresenceExpiry() {
        main.removeCallbacks(temperaturePresenceExpiry);
        long remaining = facePresence.presenceExpiresAtNanos() - System.nanoTime();
        main.postDelayed(temperaturePresenceExpiry, Math.max(1, (remaining + 999_999L) / 1_000_000L));
    }

    private void stopFaceTemperature() {
        main.removeCallbacks(temperaturePresenceExpiry);
        temperatureRequested = false;
        if (thermometer != null) thermometer.stop();
        liveTemperature = Double.NaN;
        temperatureReceivedAt = temperatureReceivedNanos = 0;
        if (checkTemperature != null && pipeline != null && pipeline.getStage() == CheckPipeline.Stage.IDENTITY)
            checkTemperature.setText(R.string.mc_check_temperature_value_placeholder);
    }

    private void clearTemperatureSample() {
        liveTemperature = Double.NaN;
        temperatureReceivedAt = identityReceivedAt = 0;
        temperatureReceivedNanos = candidateFrameNanos = 0;
    }

    private void acceptTemperature(double value) {
        if (!canMeasureFaceTemperature()) return;
        liveTemperature = value;
        temperatureReceivedAt = SystemClock.elapsedRealtime();
        temperatureReceivedNanos = System.nanoTime();
        // 识别及档案读取期间仅显示实时读数，必须先核对当前帧人员，再决定能否推进。
        applyTemperatureSample();
        afterSample(CheckPipeline.Stage.IDENTITY, null);
    }

    private void applyTemperatureSample() {
        long now = SystemClock.elapsedRealtime();
        if (!canMeasureFaceTemperature() || frameBusy || pipeline.getStage() != CheckPipeline.Stage.IDENTITY
                || !pipeline.isIdentityConfirmed() || temperatureReceivedAt == 0
                || candidateFrameNanos == 0 || temperatureReceivedNanos < candidateFrameNanos
                || now - temperatureReceivedAt > SAMPLE_MAX_AGE_MS
                || identityReceivedAt == 0 || now - identityReceivedAt > SAMPLE_MAX_AGE_MS) return;
        pipeline.onTemperature(liveTemperature);
    }

    private void updateCheck() {
        updatePreviewStatus();
        updateLighting();
        updateVoice();
        if (checkProgress == null || pipeline == null || !pageKey().equals("check") || currentRecord != null)
            return;
        CheckPipeline.Stage stage = pipeline.getStage();
        String[] names = {getString(R.string.i18n_a_face_recognition), getString(R.string.i18n_a_headwear_check),
                getString(R.string.i18n_a_backs_of_hands), getString(R.string.i18n_a_palms),
                getString(R.string.i18n_a_check_complete)};
        View handGuide = content.findViewById(R.id.check_hand_guide);
        boolean palm = stage == CheckPipeline.Stage.HAND_PALM;
        boolean hand = palm || stage == CheckPipeline.Stage.HAND_BACK;
        if (overlay != null) overlay.setGuideVisible(!hand && stage != CheckPipeline.Stage.RESULT);
        if (handGuide != null)
            handGuide.setVisibility(hand && !capturePaused() && cameraError.isEmpty() ? View.VISIBLE : View.GONE);
        if (hand && handGuide != null) {
            ((android.widget.ImageView) content.findViewById(R.id.check_hand_guide_image))
                    .setImageResource(palm ? R.drawable.mc_hand_palm_guide : R.drawable.mc_hand_back_guide);
            ((TextView) content.findViewById(R.id.check_hand_guide_caption))
                    .setText(palm ? R.string.design_hand_palm_guide : R.string.design_hand_back_guide);
        }
        checkPerson.setText(activeStaff == null ? getString(R.string.i18n_a_waiting_to_identify_staff) : activeStaff.optString("name"));
        checkPerson.setTextColor(ContextCompat.getColor(this, activeStaff == null ? R.color.mc_muted : R.color.mc_blue));
        if (checkPersonDetail != null) {
            String department = activeStaff == null ? "" : activeStaff.optString("department").trim();
            String jobNumber = activeStaff == null ? "" : activeStaff.optString("staff_no").trim();
            checkPersonDetail.setText(department + (!department.isEmpty() && !jobNumber.isEmpty() ? " · " : "")
                    + (jobNumber.isEmpty() ? "" : getString(R.string.i18n_a_staff_number, jobNumber)));
            checkPersonDetail.setVisibility(activeStaff == null || department.isEmpty() && jobNumber.isEmpty()
                    ? View.GONE : View.VISIBLE);
        }
        checkProgress.setText(getString(R.string.i18n_a_check_progress, names[stage.ordinal()], (stage.ordinal() + 1), pipeline.getProgress(), pipeline.getRequiredSamples()));
        double temperature = stage == CheckPipeline.Stage.IDENTITY
                ? liveTemperature : pipeline.getTemperature();
        checkTemperature.setVisibility(pipeline.isTemperatureSkipped() ? View.GONE : View.VISIBLE);
        checkTemperature.setText(Double.isFinite(temperature) ? getString(R.string.i18n_a_temperature, temperature)
                : getString(R.string.i18n_a_temperature_c));
    }

    private void updatePreviewStatus() {
        if (content == null) return;
        TextView status = content.findViewById(R.id.check_camera_status);
        TextView stage = content.findViewById(R.id.check_camera_stage);
        if (status == null || stage == null) return;
        boolean paused = pipeline != null && capturePaused();
        boolean ready = pipeline != null && !paused && sdkReady() && captureIsCurrent()
                && cameraError.isEmpty() && latestFrame != null;
        status.setText(paused ? getString(R.string.i18n_a_check_paused) : ready ? getString(R.string.i18n_a_device_ready) : getString(R.string.i18n_a_waiting_for_device));
        status.setTextColor(ContextCompat.getColor(this, ready ? R.color.mc_green : R.color.mc_muted));
        status.setBackgroundResource(ready ? R.drawable.mc_badge_success : R.drawable.mc_field);
        String[] names = {getString(R.string.i18n_a_face_recognition), getString(R.string.i18n_a_headwear_check),
                getString(R.string.i18n_a_backs_of_hands), getString(R.string.i18n_a_palms),
                getString(R.string.i18n_a_check_complete)};
        stage.setText(pipeline == null ? getString(R.string.i18n_a_prepare_for_check) : names[pipeline.getStage().ordinal()]);
    }

    private void resetVoiceState() {
        inferenceIssue = identityIssue = "";
        storageError = identityReadError = false;
        facePresenceStage = null;
        clearFacePresence();
        if (speech != null) {
            // 暂离、重排或 SDK 恢复只清理采样；同一页面已播过的身份首句不能随重置再播。
            identityVoiceAnnounced |= speech.hasAnnounced(IDENTITY_VOICE_KEY);
            speech.reset();
            if (identityVoiceAnnounced) speech.restoreAnnounced(IDENTITY_VOICE_KEY);
        }
    }

    private void updateVoice() {
        if (destroyed) return;
        if (pipeline == null || currentRecord != null) {
            clearFacePresence();
            if (speech != null) speech.clearStatus();
            return;
        }
        CheckPipeline.Stage stage = pipeline.getStage();
        if (facePresenceStage != stage) {
            clearFacePresence();
            facePresenceStage = stage;
        }
        boolean canCapture = foreground && resumed && !storageError && !capturePaused() && sdkReady()
                && cameraError.isEmpty() && inferenceIssue.isEmpty() && !identityReadError && !waitingDeparture;
        if (!canCapture || stage == CheckPipeline.Stage.RESULT) clearFacePresence();
        // 文字不等待语音冷却，也不依赖语音开关；预览、匹配回包和到期任务共用这一刷新入口。
        renderCheckPrompt();
        if (speech == null) return;
        boolean identityEntry = stage == CheckPipeline.Stage.IDENTITY && foreground && resumed && !capturePaused();
        speech.enterStage("stage:" + stage, canCapture || identityEntry ? UiMessages.text(this, CheckVoiceText.stage(stage,
                checkRules == null || checkRules.requireMask, checkRules == null || checkRules.requireHat)) : "");
        if (!canCapture || stage == CheckPipeline.Stage.RESULT) {
            speech.clearStatus();
            return;
        }
        long nowNanos = System.nanoTime();
        if (stage == CheckPipeline.Stage.HEAD && facePresence.isMultiple(nowNanos)) {
            speech.update("face_multiple:" + stage, getString(R.string.i18n_a_multiple_people_detected), 0, 5000);
            scheduleFacePresenceExpiry(nowNanos);
        } else if ((stage == CheckPipeline.Stage.IDENTITY || stage == CheckPipeline.Stage.HEAD)
                && (facePresence.isAbsent(nowNanos)
                || stage == CheckPipeline.Stage.IDENTITY && facePresence.isUnmatched(nowNanos))) {
            // 无人先等待五秒；明确未匹配无需连续凑满五秒，仍由语音出口等待同阶段上一句结束。
            boolean absent = facePresence.isAbsent(nowNanos);
            speech.update((absent ? "face_absent:" : "face_unmatched:") + stage,
                    absent ? getString(R.string.i18n_a_please_step_into_view) : getString(R.string.i18n_a_no_staff_match), absent ? 5000 : 0, 5000);
            scheduleFacePresenceExpiry(nowNanos);
        } else {
            main.removeCallbacks(facePresenceExpiry);
            speech.clearStatus();
        }
    }

    private void renderCheckPrompt() {
        if (checkHint == null || pipeline == null) return;
        String error = !cameraError.isEmpty() ? cameraError
                : identityReadError ? identityIssue : inferenceIssue;
        if (!error.isEmpty() && !error.equals(shownPromptError)) notice(error.replace('\n', '，'));
        shownPromptError = error;
        boolean showGuidance = !storageError && !saving && sdkReady() && error.isEmpty();
        checkHint.setVisibility(showGuidance ? View.VISIBLE : View.GONE);
        if (checkPromptLabel != null)
            checkPromptLabel.setVisibility(showGuidance ? View.VISIBLE : View.GONE);
        if (!showGuidance) return;
        CheckGuidance.Prompt prompt;
        if (paused) prompt = new CheckGuidance.Prompt(getString(R.string.i18n_a_capture_paused_tap_resume_to_continue), false);
        else if (waitingDeparture)
            prompt = new CheckGuidance.Prompt(getString(R.string.i18n_a_check_complete_leave_the_camera_view_before_checking_again), false);
        else if (!identityIssue.isEmpty()) prompt = new CheckGuidance.Prompt(identityIssue, true);
        else if (pipeline.getStage() == CheckPipeline.Stage.HEAD && facePresence.isMultiple(System.nanoTime()))
            prompt = new CheckGuidance.Prompt(getString(R.string.i18n_a_multiple_people_detected_only_one_person_should_stay_in_view), true);
        else if (facePresence.isAbsent(System.nanoTime()))
            prompt = new CheckGuidance.Prompt(getString(R.string.i18n_a_no_face_detected_please_step_into_camera_view), true);
        else if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY && facePresence.isUnmatched(System.nanoTime()))
            prompt = new CheckGuidance.Prompt(getString(R.string.i18n_a_no_staff_match_make_sure_your_face_is_enrolled), true);
        else prompt = CheckGuidance.from(pipeline, checkRules);
        // 相同采样提示不反复触发布局和无障碍播报，也不逐帧重建背景。
        String localizedPrompt = UiMessages.text(this, prompt.text);
        if (!localizedPrompt.contentEquals(checkHint.getText())) checkHint.setText(localizedPrompt);
        if (checkPromptWarning == null || checkPromptWarning != prompt.warning) {
            checkPromptWarning = prompt.warning;
            checkHint.setBackgroundResource(prompt.warning ? R.drawable.mc_check_prompt_warning : R.drawable.mc_check_prompt_normal);
            int ink = ContextCompat.getColor(checkHint.getContext(), prompt.warning
                    ? R.color.mc_check_prompt_warning_ink : R.color.mc_check_prompt_ink);
            checkHint.setTextColor(ink);
            if (checkPromptLabel != null) {
                checkPromptLabel.setText(prompt.warning ? R.string.mc_check_prompt_warning_label : R.string.mc_check_prompt_label);
                checkPromptLabel.setTextColor(ink);
            }
        }
    }

    private LoadingDialog workLoading;

    private void showWorkLoading(String message) {
        if (workLoading == null) workLoading = new LoadingDialog(this);
        workLoading.show(message);
    }

    private void finishCheck() {
        if (saving || currentRecord != null) return;
        saving = true;
        showWorkLoading(getString(R.string.i18n_a_saving_check_record));
        storageError = false;
        updateVoice();
        updateLighting();
        stopCamera();
        thermometer.stop();
        String id = UUID.randomUUID().toString();
        JSONObject result = RecordPolicy.create(id, activeStaff, pipeline.getTemperature(), pipeline.getFindings(), System.currentTimeMillis(), LocalDate.now(), rule("head", true));
        put(result, "capture_shop_name", currentShopName());
        try {
            long shop = Long.parseLong(setting("shop_id", "")), device = Long.parseLong(setting("device_id", ""));
            String base = setting("cloud_url", "").trim().replaceAll("/+$", "");
            if (shop > 0 && device > 0 && !base.isEmpty()) {
                put(result, "upload_base", base);
                put(result, "upload_shop_id", shop);
                put(result, "upload_device_id", device);
                put(result, "upload_shop_name", setting("shop_name", ""));
            }
        } catch (
                NumberFormatException ignored) {/* 未配置业务云时仍允许保存本机记录，首次上传时明确配置归属。 */}
        CameraSource.Frame identity = identityPhoto, f = facePhoto, b = backPhoto, p = palmPhoto;
        HandEvidenceCapture.Evidence back = backEvidence, palm = palmEvidence;
        CheckPipeline completedPipeline = pipeline;
        double measuredTemperature = completedPipeline.getTemperature();
        runIo(() -> {
            try {
                if (f == null || b == null || p == null || back == null || palm == null)
                    throw new IllegalStateException("采集照片不完整，不能生成完成记录");
                String faceFile = saveFaceFrame(id, "face", f, measuredTemperature);
                String backFile = saveHandFrame(id, "back", back.primary);
                String palmFile = saveHandFrame(id, "palm", palm.primary);
                put(result, "face_img_file", faceFile);
                put(result, "back_hand_img_file", backFile);
                put(result, "front_hand_img_file", palmFile);
                JSONArray captured = new JSONArray();
                // 身份与头部检查可能留下不同帧；保留实际证据，关闭头检时不重复保存同一帧。
                if (identity != null && identity != f)
                    captured.put(obj("file", saveFaceFrame(id, "identity", identity, measuredTemperature),
                            "label", "身份采集", "stage", "identity"));
                captured.put(obj("file", faceFile, "label", result.optBoolean("head_enabled") ? "头部检查" : "身份采集", "stage", "face"));
                captured.put(handPhotoInfo(backFile, "back", back.primary));
                captured.put(handPhotoInfo(palmFile, "palm", palm.primary));
                appendExtraHandPhotos(id, "back", back, captured);
                appendExtraHandPhotos(id, "palm", palm, captured);
                put(result, "captured_photos", captured);
                store.saveRecord(result);
                main.post(() -> {
                    application.notifyRecordsChanged();
                    if (!destroyed && pipeline == completedPipeline) {
                        saving = false;
                        workLoading.dismiss();
                        lastPerson = result.optLong("staff_id");
                        waitingDeparture = true;
                        currentRecord = result;
                        identityPhoto = facePhoto = backPhoto = palmPhoto = null;
                        handCapture.reset();
                        backEvidence = palmEvidence = null;
                        if (foreground) openResult();
                    }

                });
            } catch (Exception e) {
                main.post(() -> {
                    if (destroyed || pipeline != completedPipeline) return;
                    saving = false;
                    workLoading.dismiss();
                    notice(failure(e));
                    paused = true;
                    storageError = true;
                    updateVoice();
                    ImmersiveUi.show(new ProjectDialog.Builder(this).setTitle(getString(R.string.i18n_a_check_record_not_saved)).setMessage(failure(e))
                            .setNegativeButton(getString(R.string.i18n_a_check_again), (d, w) -> startCheck(true)).setPositiveButton(getString(R.string.i18n_a_retry_save), (d, w) -> {
                                if (pipeline == completedPipeline) {
                                    paused = false;
                                    finishCheck();
                                }
                            }).setCancelable(false).create());
                });
            }
        });
    }

    private String saveFaceFrame(String id, String stage, CameraSource.Frame frame, double temperature) throws Exception {
        Bitmap bitmap = frame.toBitmap();
        try {
            CheckPhotoAnnotator.annotateFace(this, bitmap, frame.captureTimeMillis, temperature);
            return store.savePhoto(id, stage, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private String saveHandFrame(String id, String stage, HandEvidenceCapture.Photo photo) throws Exception {
        Bitmap bitmap = photo.frame.toBitmap();
        try {
            CheckPhotoAnnotator.annotateHand(this, bitmap, photo.abnormalities);
            return store.savePhoto(id, stage, bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    private JSONObject handPhotoInfo(String file, String stage, HandEvidenceCapture.Photo photo) {
        String label = "back".equals(stage) ? "手背检查" : "手心检查";
        if (!photo.labels().isEmpty()) label += " · " + String.join("、", photo.labels());
        return obj("file", file, "stage", stage, "label", label, "class_ids", new JSONArray(photo.classIds()));
    }

    private void appendExtraHandPhotos(String id, String stage, HandEvidenceCapture.Evidence evidence,
                                       JSONArray captured) throws Exception {
        for (int index = 0; index < evidence.extras.size(); index++) {
            HandEvidenceCapture.Photo photo = evidence.extras.get(index);
            String file = saveHandFrame(id, stage + "-extra-" + (index + 1), photo);
            captured.put(handPhotoInfo(file, stage, photo));
        }
    }

    private void interruptSamples() {
        if (pipeline == null) return;
        pipeline.interruptSamples();
        handCapture.reset();
        if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY) {
            clearTemperatureSample();
            activeStaff = null;
            identityPhoto = facePhoto = null;
            if (!paused && resumed) startThermometer();
        }
    }

    @Override
    protected void onCameraError() {
        cancelFrame();
        // 相机中断与流水线采样一起清理，恢复后不保留上一组异常照片。
        handCapture.reset();
        if (pipeline != null) {
            pipeline.interruptSamples();
            if (pipeline.getStage() == CheckPipeline.Stage.IDENTITY) {
                clearTemperatureSample();
                activeStaff = null;
                identityPhoto = facePhoto = null;
                thermometer.stop();
            }
        }
        updateCheck();
    }
}
