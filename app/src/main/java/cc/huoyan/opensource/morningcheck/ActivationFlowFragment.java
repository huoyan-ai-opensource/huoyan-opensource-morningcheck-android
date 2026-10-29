package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;

/**
 * 首页与设备授权共用选模会话；页面重建恢复状态，不重放激活请求。
 */
public final class ActivationFlowFragment extends Fragment {
    public static final String RESULT = "modelActivationFinished";
    private static final String TAG = "modelActivation";
    private static final String PREF_CODE = "sdk_activation_code";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable draw = this::render;
    private MorningSdkSession sdk;
    private ModelSelectionSession session;
    private LoadingDialog activationLoading;
    private AlertDialog modelDialog;
    private boolean scannerLaunched;
    private String pendingScanCode = "";
    private final ActivityResultLauncher<Intent> scanner = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                scannerLaunched = false;
                // 新进程没有原选模会话，不能把恢复的扫码结果留给下一次手动激活。
                if (session == null || session.stage != ModelSelectionSession.Stage.INPUT) {
                    pendingScanCode = "";
                    postRender();
                    return;
                }
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    String code = result.getData().getStringExtra(SdkActivationScanActivity.EXTRA_CODE);
                    pendingScanCode = code == null ? "" : code;
                    if (pendingScanCode.isEmpty()) notice("未读取到有效的激活码，请重新扫码。");
                }
                postRender();
            });

    public static void open(FragmentActivity activity) {
        open(activity, false);
    }

    public static void switchModel(FragmentActivity activity) {
        open(activity, true);
    }

    private static void open(FragmentActivity activity, boolean switching) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        ActivationFlowFragment flow = (ActivationFlowFragment) manager.findFragmentByTag(TAG);
        if (flow == null) {
            flow = new ActivationFlowFragment();
            manager.beginTransaction().add(flow, TAG).commitNow();
        }
        if (flow.session.stage == ModelSelectionSession.Stage.IDLE && flow.canSubmit())
            flow.session.open(switching);
    }

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        sdk = ((MorningApplication) requireActivity().getApplication()).sdkSession();
        session = new ViewModelProvider(this).get(ModelSelectionViewModel.class).session(sdk);
        activationLoading = new LoadingDialog(requireActivity());
        if (state != null) {
            scannerLaunched = state.getBoolean("scanning");
            pendingScanCode = state.getString("pendingScanCode", "");
        }
        getChildFragmentManager().setFragmentResultListener(SdkActivationCodeDialog.RESULT_KEY, this,
                (key, result) -> {
                    String action = result.getString("action", "");
                    if ("activate".equals(action)) submit(result.getString("code", ""));
                    else if ("scan".equals(action) && !scannerLaunched && isResumed()) {
                        scannerLaunched = true;
                        SdkActivationCodeDialog input = codeDialog();
                        if (input != null && input.getDialog() != null) input.getDialog().hide();
                        scanner.launch(new Intent(requireContext(), SdkActivationScanActivity.class));
                        requireActivity().overridePendingTransition(0, 0);
                    } else if ("dismissed".equals(action) && session.stage == ModelSelectionSession.Stage.INPUT) {
                        session.cancel();
                    }
                    postRender();
                });
    }

    @Override
    public void onStart() {
        super.onStart();
        session.attach(this::postRender);
    }

    @Override
    public void onResume() {
        super.onResume();
        postRender();
    }

    @Override
    public void onStop() {
        session.detach();
        main.removeCallbacks(draw);
        activationLoading.dismiss();
        dismissModels();
        super.onStop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean("scanning", scannerLaunched);
        state.putString("pendingScanCode", pendingScanCode);
        super.onSaveInstanceState(state);
    }

    private SharedPreferences prefs() {
        return requireActivity().getSharedPreferences("device", Activity.MODE_PRIVATE);
    }

    private void postRender() {
        main.removeCallbacks(draw);
        main.post(draw);
    }

    private boolean canSubmit() {
        try {
            if (!sdk.matchesConfiguration(MorningSdkSettings.read(prefs()))) {
                notice("连接配置已改变，请先重新连接，再执行激活。");
                return false;
            }
            if (sdk.operation().running) {
                notice(getString(R.string.sdk_flow_activation_busy));
                return false;
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            notice("SDK 连接配置无效，请核对后重新连接。");
            return false;
        }
    }

    private void submit(String code) {
        if (!isResumed() || !canSubmit()) {
            SdkActivationCodeDialog input = codeDialog();
            if (input != null) input.onSubmitRejected("");
            return;
        }
        if (code == null || code.trim().isEmpty()) {
            notice(getString(R.string.sdk_flow_activation_empty));
            return;
        }
        prefs().edit().putString(PREF_CODE, code.trim()).apply();
        session.input(code);
    }

    private void render() {
        if (!isAdded() || !isResumed() || getChildFragmentManager().isStateSaved()) return;
        session.validateConnection();
        if (session.stage == ModelSelectionSession.Stage.IDLE) {
            pendingScanCode = "";
            scannerLaunched = false;
        }
        if (!pendingScanCode.isEmpty() && session.stage == ModelSelectionSession.Stage.INPUT) {
            String code = pendingScanCode;
            pendingScanCode = "";
            submit(code);
        }
        ModelSelectionSession.Stage stage = session.stage;
        SdkActivationCodeDialog input = codeDialog();
        if (stage != ModelSelectionSession.Stage.CHOOSING) dismissModels();
        if (stage == ModelSelectionSession.Stage.QUERYING || stage == ModelSelectionSession.Stage.ACTIVATING) {
            if (input != null && input.getDialog() != null) input.getDialog().hide();
            activationLoading.show(stage == ModelSelectionSession.Stage.QUERYING ? "正在读取模型列表…"
                    : session.switching ? "正在切换模型…" : "正在激活…");
            return;
        }
        activationLoading.dismiss();
        if (stage == ModelSelectionSession.Stage.INPUT) {
            if (scannerLaunched) return;
            if (input == null)
                SdkActivationCodeDialog.open(getChildFragmentManager(),
                        session.needsActivationCode ? "" : prefs().getString(PREF_CODE, ""), session.switching);
            else if (input.getDialog() != null) {
                input.onSubmitRejected("");
                input.getDialog().show();
            }
        } else if (stage == ModelSelectionSession.Stage.CHOOSING) {
            if (input != null && input.getDialog() != null) input.getDialog().hide();
            showModels();
        } else {
            if (input != null && (stage == ModelSelectionSession.Stage.IDLE || session.submitted || session.switching))
                input.dismiss();
            if (stage == ModelSelectionSession.Stage.FINISHED) {
                if (session.resultCode == 0)
                    ToastEx.showSuccessToast(requireContext(), session.switching ? "模型切换成功" : "激活成功");
                else notice(session.resultMessage);
                if (session.submitted)
                    getParentFragmentManager().setFragmentResult(RESULT, new Bundle());
                session.consumed();
            }
        }
    }

    private void showModels() {
        if (modelDialog != null) return;
        List<String> labels = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < session.models.size(); i++) {
            ModelSelectionSession.Model model = session.models.get(i);
            labels.add(model.name);
            if (session.switching && model.current) selected = i;
        }
        modelDialog = DesignChoiceDialog.create(requireActivity(), session.switching ? "切换模型" : "选择模型",
                session.switching ? "选择其他模型后开始切换" : "请选择本次使用的晨检模型",
                labels, selected, true, position -> {
                    if (!isResumed()) return;
                    if (canSubmit()) session.select(position);
                    else session.cancel();
                });
        modelDialog.setOnCancelListener(dialog -> session.cancel());
        modelDialog.setOnDismissListener(dialog -> modelDialog = null);
        ImmersiveUi.show(modelDialog);
    }

    private SdkActivationCodeDialog codeDialog() {
        Fragment input = getChildFragmentManager().findFragmentByTag(SdkActivationCodeDialog.TAG);
        return input instanceof SdkActivationCodeDialog ? (SdkActivationCodeDialog) input : null;
    }

    private void dismissModels() {
        if (modelDialog != null) modelDialog.dismiss();
        modelDialog = null;
    }

    private void notice(String message) {
        if (isAdded()) ToastEx.createToast(requireContext(), message == null || message.trim().isEmpty()
                ? "操作失败，请重试" : message);
    }
}
