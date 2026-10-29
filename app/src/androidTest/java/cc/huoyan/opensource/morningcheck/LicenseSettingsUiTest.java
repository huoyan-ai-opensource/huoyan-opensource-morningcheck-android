package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.sdk.SdkDetailsFormatter;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 用本机只读 SDK 夹具验证授权信息内联及前台五秒刷新，不修改人员或门店数据。 */
@RunWith(AndroidJUnit4.class)
public final class LicenseSettingsUiTest {
    private static final int[][] WINDOWS = {{360, 640}, {640, 360}, {800, 1280},
            {1280, 800}, {360, 480}, {640, 320}};
    private static final long ACTIVATED = 1_792_704_000L, ISSUED = 1_792_790_400L;
    private static final long RENEWED = 1_792_876_800L, NEXT_RENEW = 1_792_963_200L, EXPIRES = 1_824_240_000L;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger(), initCount = new AtomicInteger();
    private final AtomicInteger managementWrites = new AtomicInteger();
    private final AtomicInteger activationWrites = new AtomicInteger(), activationProgressReads = new AtomicInteger();
    private final AtomicInteger activationInitCount = new AtomicInteger();
    private final AtomicReference<String> submittedActivation = new AtomicReference<>();
    private final AtomicReference<String> activationRequestId = new AtomicReference<>();
    private final AtomicReference<WebSocket> activationSocket = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> commands = new CopyOnWriteArrayList<>();
    private int expectedActivationWrites;
    private final AtomicInteger modelQueries = new AtomicInteger();
    private final AtomicInteger inputModelQueries = new AtomicInteger();
    private final AtomicReference<String> submittedModel = new AtomicReference<>();
    private volatile int modelReplyCode;
    private volatile int modelCount = 3;
    private volatile boolean modelReady = true;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> certificateReads = new CopyOnWriteArrayList<>();
    private volatile String activationSerial = "TEST-ONLY-LICENSE-ONE";
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<LicenseSettingsActivity> scenario;

    @Before public void isolateSdkAndBusinessEndpoints() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = snapshot(device);
        originalBinding = snapshot(binding);
        onMain(() -> {
            assertFalse("测试不能中断正在执行的门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            String command = request.getString("cmd");
                            commands.add(command);
                            JSONObject data = new JSONObject();
                            switch (command) {
                                case "init":
                                    initCount.incrementAndGet();
                                    if (activationWrites.get() > 0) activationInitCount.incrementAndGet();
                                    break;
                                case "queryLicenseModels":
                                case "queryDeviceModels":
                                    modelQueries.incrementAndGet();
                                    JSONObject modelParams = request.optJSONObject("params");
                                    if ("queryLicenseModels".equals(command)) {
                                        assertNotNull(modelParams);
                                        assertFalse(modelParams.getString("activation_code").isEmpty());
                                        inputModelQueries.incrementAndGet();
                                    } else assertTrue(modelParams == null || !modelParams.has("activation_code"));
                                    org.json.JSONArray models = new org.json.JSONArray();
                                    for (int i = 0; i < modelCount; i++) models.put(new JSONObject()
                                            .put("model_code", "morning-model-" + i)
                                            .put("model_name", i == 1 ? "晨检模型 B · 长名称换行与列表滚动验收" : "晨检模型 " + (i + 1))
                                            .put("chip", "rk3576").put("current", i == 0));
                                    socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                            .put("code", modelReplyCode).put("reason", modelReplyCode == 0 ? "" : "测试授权无效")
                                            .put("data", new JSONObject().put("models", models)).toString());
                                    return;
                                case "activate":
                                    submittedModel.set(request.getJSONObject("params").getString("model_code"));
                                    activationWrites.incrementAndGet();
                                    submittedActivation.set(request.getJSONObject("params").getString("activation_code"));
                                    activationRequestId.set(request.getString("requestId"));
                                    activationSocket.set(socket);
                                    // 回执由测试显式放行，覆盖双击和重建期间仍在途的同一次写操作。
                                    return;
                                case "getStatus":
                                    data.put("sdk_state", modelReady ? "READY" : "MODEL_PREPARING").put("model_ready", modelReady)
                                            .put("certificate_valid", true).put("key_lost", false).put("model_version", "1.0.3")
                                            .put("device_id", "LICENSE-TEST-ONLY");
                                    break;
                                case "getCertInfo":
                                    certificateReads.add(SystemClock.elapsedRealtime());
                                    data = certificateFixture();
                                    break;
                                case "getDownloadProgress":
                                    if (activationWrites.get() > 0) activationProgressReads.incrementAndGet();
                                    data.put("downloaded", 100).put("total", 100);
                                    break;
                                default:
                                    // 观察授权不应隐式激活、续期、改配置或变更门店。
                                    if (!command.startsWith("get")) managementWrites.incrementAndGet();
                                    break;
                            }
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "授权页面隔离夹具").put("data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false).commit());
    }

    @After public void closePagesAndRestorePreferences() throws Exception {
        Map<String, Object> bindingAfter = null;
        try {
            if (scenario != null) scenario.close();
        } finally {
            try {
                if (app != null) onMain(() -> {
                    finishPages();
                    app.sdkSession().disconnect();
                    return null;
                });
                instrumentation.waitForIdleSync();
                if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            } finally {
                try {
                    if (originalDevice != null) restore(device, originalDevice);
                    if (originalBinding != null) {
                        bindingAfter = snapshot(binding);
                        restore(binding, originalBinding);
                    }
                } finally {
                    for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                    if (endpoint != null) endpoint.shutdown();
                }
            }
        }
        assertEquals("授权观察不能改变 SDK 门店归属", originalBinding, bindingAfter);
        assertEquals("授权观察不能访问业务云", 0, businessRequests.get());
        assertEquals("授权观察不能自动提交管理写操作", 0, managementWrites.get());
        assertEquals("只能提交测试明确发起的隔离激活", expectedActivationWrites, activationWrites.get());
        assertNull("夹具协议应正确", serverFailure.get());
    }



    @Test public void regularAndCompactFormsBindAndValidateActivationWithoutWritingToSdk() throws Exception {
        launchPage();
        capture("license-overview");
        LicenseSettingsActivity activity = onMain(this::currentLicense);
        View originalForm = onMain(() -> activity.formView);
        try {
            for (boolean compact : new boolean[]{false, true}) {
                onMain(() -> {
                    View form = activity.getLayoutInflater().inflate(compact
                            ? activity.compactFormLayout() : activity.regularFormLayout(), null);
                    activity.formView = form;
                    if (compact) activity.bindCompact(form);
                    else activity.bindRegular(form);
                    activity.refreshStatus();
                    View activate = form.findViewById(R.id.settings_activate);
                    assertNotNull("普通与紧凑布局都必须保留激活入口", activate);
                    assertTrue("有可用连接时激活入口应可用", activate.isEnabled());
                    assertTrue("两套表单都必须实际绑定激活入口", activate.hasOnClickListeners());
                    View switchModel = form.findViewById(R.id.settings_switch_model);
                    assertNotNull("模型切换实现暂时保留", switchModel);
                    assertEquals("普通与紧凑授权布局都应隐藏模型切换入口", View.GONE, switchModel.getVisibility());
                    assertTrue(activate.performClick());
                    return null;
                });
                SdkActivationCodeDialog inputDialog = awaitInput(activity);
                onMain(() -> {
                    EditText input = inputDialog.getDialog().findViewById(R.id.activation_code_editor);
                    assertNotNull(input);
                    input.setText("");
                    View submit = inputDialog.getDialog().findViewById(R.id.activation_code_activate);
                    assertFalse("空激活码应明确禁用提交", submit.isEnabled());
                    submit.performClick();
                    assertTrue("空激活码必须留在输入窗", inputDialog.getDialog().isShowing());
                    return null;
                });
                assertEquals("错误校验完成前不能发送管理命令", 0, managementWrites.get());
                onMain(() -> {
                    inputDialog.getDialog().findViewById(R.id.activation_code_cancel).performClick();
                    return null;
                });
                instrumentation.waitForIdleSync();
                onMain(() -> {
                    assertNull("取消输入应移除专用输入窗", currentInput(activity));
                    return null;
                });
            }
        } finally {
            onMain(() -> {
                SdkActivationCodeDialog input = currentInput(activity);
                if (input != null) input.dismissNow();
                activity.dismissFormDialog();
                activity.formView = originalForm;
                activity.refreshStatus();
                return null;
            });
        }
        assertEquals("空输入和取消不能发送激活或其他管理命令", 0, managementWrites.get());
    }



    @Test public void activationDraftAndSelectionSurviveActivityRecreationAndInPlaceRearrangement() {
        launchPage();
        openActivationInput();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            EditText editor = currentInput(activity).getDialog().findViewById(R.id.activation_code_editor);
            editor.setText("Z2FBW9L7");
            editor.setSelection(2, 6);
            return null;
        });
        scenario.recreate();
        await(() -> onMain(() -> currentLicense() != null && currentInput(currentLicense()) != null
                && currentInput(currentLicense()).getDialog() != null), "真正重建 Activity 后应恢复激活输入窗");
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            assertDraft(activity, "Z2FBW9L7", 2, 6);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            assertDraft(activity, "Z2FBW9L7", 2, 6);
            currentInput(activity).getDialog().findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        assertEquals("只恢复输入草稿不能提交激活", 0, activationWrites.get());
        assertEquals("重建和原地重排应订阅原会话", 1, initCount.get());
    }

    @Test public void changedConnectionRejectsSubmissionAndKeepsEditableDraft() {
        launchPage();
        openActivationInput();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            SdkActivationCodeDialog dialog = currentInput(activity);
            EditText editor = dialog.getDialog().findViewById(R.id.activation_code_editor);
            editor.setText("Z2FBW9L7M78R586H");
            editor.setSelection(5);
            device.edit().putString(MorningSdkSettings.ENDPOINT_KEY,
                    endpoint.url("/another-sdk").toString().replaceFirst("http", "ws")).commit();
            dialog.getDialog().findViewById(R.id.activation_code_activate).performClick();
            TextView error = dialog.getDialog().findViewById(R.id.activation_code_error);
            assertFalse("错误只通过 Toast 展示", error.isShown());
            assertDraft(activity, "Z2FBW9L7M78R586H", 5, 5);
            assertTrue("拒绝后应能编辑和重新提交", editor.isEnabled());
            assertTrue(dialog.getDialog().findViewById(R.id.activation_code_activate).isEnabled());
            dialog.getDialog().findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        assertEquals("旧连接不能收到激活写入", 0, activationWrites.get());
    }

    @Test public void cancellingRealScanActivityRestoresTheSameEditableInputDraft() {
        launchPage();
        openActivationInput();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            SdkActivationCodeDialog dialog = currentInput(activity);
            EditText editor = dialog.getDialog().findViewById(R.id.activation_code_editor);
            editor.setText("Z2FBW9L7"); editor.setSelection(2, 6);
            dialog.getDialog().findViewById(R.id.activation_code_scan).performClick();
            return null;
        });
        await(() -> onMain(() -> currentScanner() != null), "扫码入口应打开独立的激活扫码页面");
        onMain(() -> {
            SdkActivationScanActivity scanner = currentScanner();
            scanner.setResult(Activity.RESULT_CANCELED);
            scanner.finish();
            return null;
        });
        await(() -> onMain(() -> currentLicense() != null && currentLicense().uiReady()
                && currentInput(currentLicense()) != null
                && currentInput(currentLicense()).getDialog() != null
                && currentInput(currentLicense()).getDialog().<EditText>findViewById(R.id.activation_code_editor).isEnabled()),
                "扫码取消后应返回可编辑的激活输入窗");
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            assertDraft(activity, "Z2FBW9L7", 2, 6);
            SdkActivationCodeDialog dialog = currentInput(activity);
            assertTrue("扫码取消后应允许再次扫码", dialog.getDialog().findViewById(R.id.activation_code_scan).isEnabled());
            assertTrue("扫码取消后应允许关闭输入窗", dialog.getDialog().findViewById(R.id.activation_code_cancel).isEnabled());
            dialog.getDialog().findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        assertEquals("取消扫码不能提交激活", 0, activationWrites.get());
        assertEquals("扫码页往返沿用原有 SDK 会话", 1, initCount.get());
    }

    @Test public void doubleClickAndRecreationSubmitOneGroupedCodeAndFinishOnReceiptWithoutInit() throws Exception {
        launchPage();
        expectedActivationWrites = 1;
        int initializationsBefore = initCount.get();
        openActivationInput();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            SdkActivationCodeDialog dialog = currentInput(activity);
            EditText editor = dialog.getDialog().findViewById(R.id.activation_code_editor);
            editor.setText("Z2FBW9L7M78R586H");
            View submit = dialog.getDialog().findViewById(R.id.activation_code_activate);
            assertTrue(submit.isEnabled());
            submit.performClick();
            submit.performClick();
            return null;
        });
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "应先选择模型");
        assertEquals("查询后未选择不能激活", 0, activationWrites.get());
        scenario.recreate();
        await(() -> onMain(() -> currentLicense() != null && currentLicense().uiReady()
                && modelDialog(currentLicense()) != null), "重建恢复同一模型列表");
        assertEquals("重建不能重放手输激活码的模型查询", 1, inputModelQueries.get());
        capture("model-selection-landscape");
        modelReady = false;
        chooseModel(1);
        await(() -> activationWrites.get() == 1 && onMain(() -> activationLoadingShowing(currentLicense())),
                "只提交一次激活，并展示本次请求的等待弹窗");
        SystemClock.sleep(2_300);
        assertEquals("等待回执不轮询模型下载进度", 0, activationProgressReads.get());
        assertEquals("morning-model-1", submittedModel.get());
        assertEquals("后端应收到四组格式", "Z2FB-W9L7-M78R-586H", submittedActivation.get());
        assertEquals("激活尚未回执不能提前初始化", 0, activationInitCount.get());
        scenario.recreate();
        await(() -> onMain(() -> currentLicense() != null && currentLicense().uiReady()
                && activationLoadingShowing(currentLicense())), "重建应恢复同一次激活的等待弹窗");
        assertEquals("重建不能重放激活", 1, activationWrites.get());
        assertTrue(activationSocket.get().send(new JSONObject().put("requestId", activationRequestId.get())
                .put("code", 0).put("reason", "隔离激活完成").put("data", new JSONObject()).toString()));
        await(() -> onMain(() -> app.sdkSession().activation().phase == MorningSdkSession.ActivationPhase.SUCCEEDED
                && !activationLoadingShowing(currentLicense())), "成功回执应立即结束激活和等待弹窗");
        onMain(() -> {
            assertFalse("成功回执不能伪造模型就绪状态", app.sdkSession().viewState().ready);
            assertFalse("成功回执后管理操作已经结束", app.sdkSession().operation().running);
            return null;
        });
        assertEquals("完整激活链不追加初始化", 0, activationInitCount.get());
        assertEquals(initializationsBefore, initCount.get());
        assertEquals("回执完成后仍只能有一次激活写入", 1, activationWrites.get());
        await(() -> onMain(() -> "MODEL_PREPARING".equals(app.sdkSession().status().optString("sdk_state"))),
                "结束后的只读刷新应显示真实未就绪状态");
        // 夹具线程仍会接收周期查询；顺序断言只检查本次稳定快照。
        ArrayList<String> observedCommands = new ArrayList<>(commands);
        int activateAt = observedCommands.indexOf("activate");
        assertFalse("激活后不能追加 init",
                observedCommands.subList(activateAt + 1, observedCommands.size()).contains("init"));
        assertTrue("激活后应独立读取真实状态",
                observedCommands.subList(activateAt + 1, observedCommands.size()).contains("getStatus"));
    }

    @Test public void missingCodeSelectsBeforeInputAndRecreationDoesNotRequeryOrWriteTwice() throws Exception {
        activationSerial = "";
        launchPage();
        expectedActivationWrites = 1;
        onMain(() -> { ActivationFlowFragment.switchModel(currentLicense()); return null; });
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "缺码时先展示模型列表");
        onMain(() -> { assertNull(currentInput(currentLicense())); return null; });
        assertEquals(0, activationWrites.get());
        chooseModel(1);
        awaitInput(onMain(this::currentLicense));
        int queriesAfterSelection = modelQueries.get();
        onMain(() -> {
            assertEquals("", currentInput(currentLicense()).getDialog()
                    .<EditText>findViewById(R.id.activation_code_editor).getText().toString());
            return null;
        });
        scenario.recreate();
        await(() -> onMain(() -> currentLicense() != null && currentInput(currentLicense()) != null
                && currentInput(currentLicense()).getDialog() != null), "补码弹窗应恢复");
        onMain(() -> {
            android.app.Dialog input = currentInput(currentLicense()).getDialog();
            input.<EditText>findViewById(R.id.activation_code_editor).setText("Z2FBW9L7M78R586H");
            input.findViewById(R.id.activation_code_activate).performClick();
            input.findViewById(R.id.activation_code_activate).performClick();
            return null;
        });
        await(() -> activationWrites.get() == 1, "补码后提交原来选中的模型");
        assertEquals("morning-model-1", submittedModel.get());
        assertEquals("Z2FB-W9L7-M78R-586H", submittedActivation.get());
        assertEquals("补码不按激活码重新查模型", 0, inputModelQueries.get());
        assertEquals("页面重建只能补充授权页的只读模型查询", queriesAfterSelection + 1, modelQueries.get());
        assertTrue(activationSocket.get().send(new JSONObject().put("requestId", activationRequestId.get())
                .put("code", 0).put("reason", "").put("data", new JSONObject()).toString()));
        await(() -> onMain(() -> !app.sdkSession().operation().running
                && !activationLoadingShowing(currentLicense()) && currentInput(currentLicense()) == null),
                "成功回执关闭补码和等待弹窗");
        assertEquals(1, activationWrites.get());
        assertEquals(0, activationInitCount.get());
    }

    @Test public void missingCodeCurrentModelAndCancelledInputDoNotActivate() {
        activationSerial = "";
        launchPage();
        onMain(() -> { ActivationFlowFragment.switchModel(currentLicense()); return null; });
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "应展示当前设备模型");
        chooseModel(0);
        await(() -> onMain(() -> modelDialog(currentLicense()) == null), "当前模型只关闭列表");
        onMain(() -> {
            assertNull(currentInput(currentLicense()));
            ActivationFlowFragment.switchModel(currentLicense());
            return null;
        });
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "重新打开模型列表");
        chooseModel(1);
        awaitInput(onMain(this::currentLicense));
        onMain(() -> {
            android.app.Dialog input = currentInput(currentLicense()).getDialog();
            assertFalse(input.findViewById(R.id.activation_code_activate).isEnabled());
            input.findViewById(R.id.activation_code_cancel).performClick();
            return null;
        });
        await(() -> onMain(() -> currentInput(currentLicense()) == null), "取消应结束切换");
        assertEquals(0, inputModelQueries.get());
        assertEquals(0, activationWrites.get());
    }

    @Test public void fiveSecondRefreshStopsOffPageAndImmediatelyReloadsWhenReturning() {
        launchPage();
        // 首次预约可能早于建链完成；从稳定轮询周期开始测量，不把建链耗时算入间隔。
        int initialReads = certificateReads.size();
        await(() -> certificateReads.size() > initialReads, "建链后应进入证书定时读取周期");
        int previousReads = certificateReads.size();
        int previousModelQueries = modelQueries.get();
        long previousReadAt = certificateReads.get(previousReads - 1);
        activationSerial = "TEST-ONLY-LICENSE-TWO";
        await(() -> onMain(() -> app.sdkSession().certificate().optString("activation_serial").equals(activationSerial)),
                "停留授权页无需点击刷新，五秒后应展示新证书激活码");
        assertTrue("证书自动读取间隔应达到五秒周期",
                certificateReads.get(previousReads) - previousReadAt >= 4_500);
        assertEquals("同一周期不能重复读取证书", previousReads + 1, certificateReads.size());
        assertEquals("证书五秒刷新不能重复访问云端模型列表", previousModelQueries, modelQueries.get());
        onMain(() -> {
            assertTrue(currentLicense().findViewById(R.id.header_home).performClick());
            return null;
        });
        await(() -> onMain(() -> currentHome() != null), "授权页应能返回首页");
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        int stoppedReads = certificateReads.size();
        SystemClock.sleep(5_600);
        assertEquals("离开授权页应停止证书周期读取", stoppedReads, certificateReads.size());
        activationSerial = "TEST-ONLY-LICENSE-RETURN";
        long returnedAt = SystemClock.elapsedRealtime();
        onMain(() -> {
            HomeActivity home = currentHome();
            assertTrue(home.findViewById(R.id.home_settings).performClick());
            return null;
        });
        await(() -> onMain(() -> app.sdkSession().certificate().optString("activation_serial").equals(activationSerial)),
                "返回授权页应立即取回最新证书");
        assertTrue("返回不应等下一个五秒周期", SystemClock.elapsedRealtime() - returnedAt < 3_000);
        assertEquals("后台和返回不能重新初始化 SDK", 1, initCount.get());
    }

    @Test public void recreationAndLayoutRearrangementKeepOneCertificatePollingChain() {
        launchPage();
        scenario.recreate();
        await(() -> onMain(() -> app.sdkSession().certificate().optString("activation_serial").equals(activationSerial)), "重建应恢复授权详情");
        scenario.onActivity(activity -> activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration())));
        instrumentation.waitForIdleSync();
        int beforeCycle = certificateReads.size();
        await(() -> certificateReads.size() > beforeCycle, "重建后应恢复五秒证书读取");
        int settledReads = certificateReads.size();
        SystemClock.sleep(10_200);
        assertEquals("重建和布局重排后每五秒只读一次证书", settledReads + 2, certificateReads.size());
        for (int index = settledReads + 1; index < certificateReads.size(); index++)
            assertTrue("相邻周期不应出现重复预约", certificateReads.get(index) - certificateReads.get(index - 1) >= 4_500);
        assertEquals("重建只订阅原会话，不重放初始化", 1, initCount.get());
    }



    @Test public void modelListCancelFailureAndEmptyKeepDraftWithoutActivation() throws Exception {
        launchPage();
        submitCode();
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "模型列表应显示");
        onMain(() -> { modelDialog(currentLicense()).cancel(); return null; });
        awaitEditableDraft();
        assertEquals(0, activationWrites.get());
        for (int failure : new int[]{-44, 0}) {
            modelReplyCode = failure;
            modelCount = 0;
            int previous = inputModelQueries.get();
            onMain(() -> {
                currentInput(currentLicense()).getDialog().findViewById(R.id.activation_code_activate).performClick();
                return null;
            });
            await(() -> inputModelQueries.get() > previous, "手输激活码的模型查询应发出");
            awaitEditableDraft();
            assertEquals(0, activationWrites.get());
        }
    }

    @Test public void modelListSupportsNativeOrientationAnd130PercentFont() throws Exception {
        device.edit().putInt(AppFontSettings.KEY, 3).commit();
        modelCount = 12;
        launchPage();
        capture("license-overview-130");
        submitCode();
        await(() -> onMain(() -> modelDialog(currentLicense()) != null), "列表应显示");
        instrumentation.waitForIdleSync();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            assertEquals(AppFontSettings.scaleFor(3), activity.getResources().getConfiguration().fontScale, .01f);
            AlertDialog dialog = modelDialog(activity);
            ViewGroup root = (ViewGroup) dialog.getWindow().getDecorView();
            assertContained(root, dialog.findViewById(R.id.department_filter_close));
            assertEquals(12, dialog.<ListView>findViewById(R.id.department_filter_options).getCount());
            return null;
        });
        capture("model-selection-130-native-top");
        onMain(() -> {
            modelDialog(currentLicense()).<ListView>findViewById(R.id.department_filter_options).setSelection(11);
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            AlertDialog dialog = modelDialog(currentLicense());
            ListView list = dialog.findViewById(R.id.department_filter_options);
            assertEquals(11, list.getLastVisiblePosition());
            assertContained((ViewGroup) dialog.getWindow().getDecorView(),
                    dialog.findViewById(R.id.department_filter_close));
            return null;
        });
        capture("model-selection-130-native-bottom");
        onMain(() -> { modelDialog(currentLicense()).cancel(); return null; });
        awaitEditableDraft();
    }

    @Test public void everyScreenAndFontKeepsModelSwitchAndActivationInsideLicensePage() {
        onMain(() -> {
            for (int[] window : WINDOWS) for (int font = 0; font < 4; font++) {
                Configuration configuration = new Configuration(app.getResources().getConfiguration());
                configuration.screenWidthDp = window[0];
                configuration.screenHeightDp = window[1];
                configuration.smallestScreenWidthDp = Math.min(window[0], window[1]);
                configuration.orientation = window[0] > window[1]
                        ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                configuration.fontScale = AppFontSettings.scaleFor(font);
                Context context = new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
                boolean compact = context.getResources().getBoolean(R.bool.mc_compact);
                String label = window[0] + "×" + window[1] + "，字号 " + Math.round(configuration.fontScale * 100) + "%";
                LayoutInflater inflater = LayoutInflater.from(context);
                ViewGroup root = (ViewGroup) inflater.inflate(compact
                        ? R.layout.activity_devices_compact : R.layout.activity_devices, null);
                ((TextView) root.findViewById(R.id.header_page_title)).setText("授权与密钥");
                FrameLayout body = root.findViewById(R.id.settings_body);
                int inset = context.getResources().getDimensionPixelSize(R.dimen.dp_12);
                root.findViewById(R.id.page_content).setPadding(inset, inset, inset, inset);
                body.setPadding(0, 0, 0, 0);
                body.setBackground(null);
                body.addView(inflater.inflate(R.layout.view_license_design, body, false));
                ((TextView) root.findViewById(R.id.settings_current_model)).setText("晨检合成包·头部手部检测模型");
                ((TextView) root.findViewById(R.id.settings_model_version)).setText("2026.10.21");
                ((TextView) root.findViewById(R.id.settings_license_state)).setText("已激活");
                ListView fields = root.findViewById(R.id.settings_license_fields);
                fields.setAdapter(new android.widget.ArrayAdapter<String>(context, R.layout.item_license_design,
                        new String[]{"激活时间", "过期时间", "授权版本"}) {
                    @Override public View getView(int position, View reusable, ViewGroup parent) {
                        View row = reusable == null
                                ? inflater.inflate(R.layout.item_license_design, parent, false) : reusable;
                        ((TextView) row.findViewById(R.id.record_detail_field_label)).setText(getItem(position));
                        ((TextView) row.findViewById(R.id.record_detail_field_value)).setText(
                                position == 2 ? "quarterly" : "2026-10-21 13:55");
                        return row;
                    }
                });
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(window[0] * density), height = Math.round(window[1] * density);
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, width, height);
                assertNoPageScroll(root);
                assertEquals(label + "：模型切换入口应隐藏", View.GONE,
                        root.findViewById(R.id.settings_switch_model).getVisibility());
                for (int id : new int[]{R.id.settings_current_model, R.id.settings_model_version, R.id.settings_activate}) {
                    View view = root.findViewById(id);
                    assertNotNull(label + "：授权页必须展示模型和操作入口", view);
                    assertEquals(label + "：模型与操作控件应保持可见", View.VISIBLE, view.getVisibility());
                    assertContained(root, view);
                    assertContained(body, view);
                }
                Rect activate = boundsInAncestor(root, root.findViewById(R.id.settings_activate));
                for (int id : new int[]{R.id.settings_current_model, R.id.settings_model_version})
                    assertFalse(label + "：模型与切换入口不能覆盖激活按钮",
                            Rect.intersects(activate, boundsInAncestor(root, root.findViewById(id))));
                assertTrue(label + "：授权列表必须保留可见行", fields.getChildCount() > 0);
                assertTrue(label + "：授权列表至少容纳一行", fields.getHeight() >= fields.getChildAt(0).getHeight());
                for (int id : new int[]{R.id.settings_current_model, R.id.settings_model_version, R.id.settings_activate}) {
                    TextView text = root.findViewById(id);
                    assertNotNull(label + "：文字必须完成排版", text.getLayout());
                    int available = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                    assertTrue(label + "：模型信息和按钮文字不能被截断", text.getLayout().getHeight() <= available + 1);
                    assertContained((ViewGroup) text.getParent(), text);
                }
            }
            return null;
        });
    }

    private static Rect boundsInAncestor(ViewGroup ancestor, View view) {
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        ancestor.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }

    private void submitCode() {
        openActivationInput();
        onMain(() -> {
            LicenseSettingsActivity activity = currentLicense();
            android.app.Dialog dialog = currentInput(activity).getDialog();
            dialog.<EditText>findViewById(R.id.activation_code_editor).setText("Z2FBW9L7M78R586H");
            dialog.findViewById(R.id.activation_code_activate).performClick();
            return null;
        });
    }

    private void awaitEditableDraft() {
        await(() -> onMain(() -> {
            SdkActivationCodeDialog input = currentInput(currentLicense());
            return input != null && input.getDialog() != null && input.getDialog().isShowing()
                    && input.getDialog().<EditText>findViewById(R.id.activation_code_editor).isEnabled();
        }), "应返回可编辑的原草稿");
        onMain(() -> {
            assertEquals("Z2FBW9L7M78R586H", currentInput(currentLicense()).getDialog()
                    .<EditText>findViewById(R.id.activation_code_editor).getText().toString());
            return null;
        });
    }

    private void chooseModel(int position) {
        onMain(() -> {
            ListView list = modelDialog(currentLicense()).findViewById(R.id.department_filter_options);
            list.performItemClick(list.getChildAt(position), position, position);
            return null;
        });
    }

    private static AlertDialog modelDialog(LicenseSettingsActivity activity) {
        try {
            Fragment flow = activationFlow(activity);
            if (flow == null) return null;
            java.lang.reflect.Field field = ActivationFlowFragment.class.getDeclaredField("modelDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(flow);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        // 等 SurfaceFlinger 提交布局后的画面，避免截图仍是滚动前一帧。
        SystemClock.sleep(250);
        java.io.File directory = new java.io.File(app.getExternalFilesDir(null), "model-selection-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
        } finally { screenshot.recycle(); }
    }

    private JSONObject certificateFixture() throws Exception {
        return new JSONObject().put("activation_serial", activationSerial).put("license_type", "测试授权")
                .put("model_code", "test-morning-model").put("model_channel", "测试通道")
                .put("activated_at", ACTIVATED).put("issued_at", ISSUED)
                .put("has_renewed_at", true).put("renewed_at", RENEWED)
                .put("has_next_renew_at", true).put("next_renew_at", NEXT_RENEW)
                .put("expires_at", EXPIRES).put("sdk_version", "测试 SDK 版本");
    }

    private void launchPage() {
        scenario = ActivityScenario.launch(new Intent(app, LicenseSettingsActivity.class));
        try {
            await(() -> onMain(() -> app.sdkSession().viewState().ready
                    && app.sdkSession().certificate().optString("activation_serial").equals(activationSerial)), "隔离 SDK 和内联证书应加载完成");
        } catch (AssertionError failure) {
            throw new AssertionError("fixture commands=" + commands + " server=" + serverFailure.get()
                    + " connection=" + onMain(() -> app.sdkSession().connectionMessage())
                    + " status=" + onMain(() -> app.sdkSession().status())
                    + " certificate present=" + onMain(() -> app.sdkSession().certificate().length()), failure);
        }
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
    }

    private String fieldText(LicenseSettingsActivity activity) {
        if (activity == null) return "";
        ListView fields = activity.findViewById(R.id.settings_license_fields);
        if (fields == null || fields.getAdapter() == null) return "";
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < fields.getAdapter().getCount(); index++)
            text.append(fields.getAdapter().getItem(index)).append('\n');
        return text.toString();
    }

    private static String visibleText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView && view.isShown()) text.append(((TextView) view).getText());
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                text.append(visibleText(((ViewGroup) view).getChildAt(index)));
        return text.toString();
    }



    private static void assertContained(ViewGroup root, View view) {
        assertNotNull(view);
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue("控件应有可见尺寸：" + bounds, bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件不能超出页面：" + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
    }

    private static void assertNoPageScroll(View view) {
        assertFalse("授权页只允许详情列表内部滚动", view instanceof ScrollView);
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                assertNoPageScroll(((ViewGroup) view).getChildAt(index));
    }

    private static void assertAbsentOrHidden(Activity activity, String resourceName) {
        int id = activity.getResources().getIdentifier(resourceName, "id", activity.getPackageName());
        View view = id == 0 ? null : activity.findViewById(id);
        assertTrue("已合并的入口不应继续展示：" + resourceName, view == null || view.getVisibility() == View.GONE);
        if (view != null) assertFalse("旧入口不能残留路由：" + resourceName, view.hasOnClickListeners());
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private LicenseSettingsActivity currentLicense() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof LicenseSettingsActivity) return (LicenseSettingsActivity) activity;
        return null;
    }

    private static SdkActivationCodeDialog currentInput(LicenseSettingsActivity activity) {
        Fragment flow = activationFlow(activity);
        if (flow == null) return null;
        Fragment fragment = flow.getChildFragmentManager().findFragmentByTag(SdkActivationCodeDialog.TAG);
        return fragment instanceof SdkActivationCodeDialog ? (SdkActivationCodeDialog) fragment : null;
    }

    private static Fragment activationFlow(LicenseSettingsActivity activity) {
        return activity == null ? null : activity.getSupportFragmentManager().findFragmentByTag("modelActivation");
    }

    private void openActivationInput() {
        LicenseSettingsActivity activity = onMain(this::currentLicense);
        onMain(() -> {
            assertTrue(activity.findViewById(R.id.settings_activate).performClick());
            return null;
        });
        awaitInput(activity);
    }

    private SdkActivationCodeDialog awaitInput(LicenseSettingsActivity activity) {
        await(() -> onMain(() -> {
            SdkActivationCodeDialog input = currentInput(activity);
            return activity.uiReady() && input != null && input.getDialog() != null && input.getDialog().isShowing();
        }), "激活输入窗和前台表单应准备好");
        return onMain(() -> currentInput(activity));
    }

    private static boolean activationLoadingShowing(LicenseSettingsActivity activity) {
        try {
            Fragment flow = activationFlow(activity);
            if (flow == null) return false;
            java.lang.reflect.Field field = ActivationFlowFragment.class.getDeclaredField("activationLoading");
            field.setAccessible(true);
            LoadingDialog loading = (LoadingDialog) field.get(flow);
            if (loading == null) return false;
            java.lang.reflect.Field dialogField = LoadingDialog.class.getDeclaredField("dialog");
            dialogField.setAccessible(true);
            android.app.Dialog dialog = (android.app.Dialog) dialogField.get(loading);
            return dialog != null && dialog.isShowing();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void assertDraft(LicenseSettingsActivity activity, String text, int start, int end) {
        SdkActivationCodeDialog dialog = currentInput(activity);
        assertNotNull("专用输入弹窗应存在", dialog);
        assertNotNull(dialog.getDialog());
        assertTrue(dialog.getDialog().isShowing());
        EditText editor = dialog.getDialog().findViewById(R.id.activation_code_editor);
        assertEquals("输入草稿不能丢失", text, editor.getText().toString());
        assertEquals("选区起点不能丢失", start, editor.getSelectionStart());
        assertEquals("选区终点不能丢失", end, editor.getSelectionEnd());
        assertFalse("专用编辑层不能打开系统键盘", editor.getShowSoftInputOnFocus());
    }

    private HomeActivity currentHome() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof HomeActivity) return (HomeActivity) activity;
        return null;
    }

    private SdkActivationScanActivity currentScanner() {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (activity instanceof SdkActivationScanActivity) return (SdkActivationScanActivity) activity;
        return null;
    }

    private <T> T onMain(Callable<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(work.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("授权界面回归失败", failure.get());
        return result.get();
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            values.put(entry.getKey(), entry.getValue() instanceof Set
                    ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return values;
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, Object> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
            else throw new AssertionError("未知配置类型：" + entry.getKey());
        }
        assertTrue("应恢复测试前配置", editor.commit());
        assertEquals(original, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
