package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.login.ShopBindingStore;
import cc.huoyan.opensource.morningcheck.login.ShopLoginCoordinator;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 仅在本机夹具验证真实 Activity/WS/持久化路径，不访问任何 SDK 云端。 */
@RunWith(AndroidJUnit4.class)
public class ShopLoginNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final BlockingQueue<BindCall> bindings = new LinkedBlockingQueue<>();
    private final BlockingQueue<BindCall> activations = new LinkedBlockingQueue<>();
    private final BlockingQueue<JSONObject> modelQueries = new LinkedBlockingQueue<>();
    private final AtomicInteger bindCount = new AtomicInteger();
    private final AtomicInteger initCount = new AtomicInteger(), activationCount = new AtomicInteger();
    private final AtomicInteger activationProgressCount = new AtomicInteger();
    private volatile String fixtureState = "NOT_ACTIVATED";
    private volatile long downloadBytes = 100, downloadTotal = 100;
    private volatile int progressCode;
    private volatile int modelQueryCode;
    private volatile String currentModel = "morningcheck";
    private volatile boolean certificateHasSerial = true;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private MorningApplication app;
    private SharedPreferences device, bindingPrefs;
    private Map<String, ?> originalDevice, originalBinding;
    private MockWebServer server;
    private ActivityScenario<HomeActivity> homeScenario;
    private HomeActivity originalHome;
    private Application.ActivityLifecycleCallbacks loginCameraIsolation;

    @Before public void prepareIsolatedEndpoint() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        bindingPrefs = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = device.getAll();
        originalBinding = bindingPrefs.getAll();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject envelope = new JSONObject(text);
                            String command = envelope.getString("cmd");
                            if ("bindDevice".equals(command)) {
                                bindCount.incrementAndGet();
                                bindings.add(new BindCall(socket, envelope));
                                return;
                            }
                            JSONObject data = new JSONObject();
                            if ("init".equals(command)) {
                                initCount.incrementAndGet();
                            }
                            if ("queryLicenseModels".equals(command)) {
                                modelQueries.add(envelope);
                                data.put("models", new org.json.JSONArray().put(new JSONObject()
                                        .put("model_code", "morningcheck").put("model_name", "晨检模型")
                                        .put("current", "morningcheck".equals(currentModel)))
                                        .put(new JSONObject().put("model_code", "morningcheck-next")
                                                .put("model_name", "新版晨检模型")
                                                .put("current", "morningcheck-next".equals(currentModel))));
                            }
                            if ("activate".equals(command)) {
                                activationCount.incrementAndGet();
                                fixtureState = "MODEL_PREPARING";
                                // 写回执由测试释放；页面必须只提交一次，不能用状态或进度代替回执。
                                activations.add(new BindCall(socket, envelope));
                                return;
                            }
                            if ("getStatus".equals(command)) {
                                data.put("sdk_state", fixtureState).put("device_id", "TEST-ONLY-DEVICE")
                                        .put("model_version", "morningcheck-next".equals(currentModel) ? "fixture-v2" : "fixture-v1")
                                        .put("model_ready", "READY".equals(fixtureState))
                                        .put("certificate_valid", !"NOT_ACTIVATED".equals(fixtureState));
                            } else if ("getCertInfo".equals(command)) {
                                if (certificateHasSerial) data.put("activation_serial", "TEST-ONLY-CERT-SERIAL");
                                data.put("model_code", currentModel)
                                        .put("model_version", "morningcheck-next".equals(currentModel) ? "fixture-v2" : "fixture-v1");
                            } else if ("getDownloadProgress".equals(command)) {
                                if (activationCount.get() > 0) activationProgressCount.incrementAndGet();
                                data.put("downloaded", downloadBytes).put("total", downloadTotal);
                            } else if ("getCloudStatus".equals(command)) {
                                data.put("enabled", false);
                            }
                            // 未激活也可绑定；不能以模型READY作为扫码登录门槛。
                            reply(socket, envelope, "init".equals(command) ? -3
                                    : "getDownloadProgress".equals(command) ? progressCode
                                    : "queryLicenseModels".equals(command) ? modelQueryCode : 0, data);
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        server.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        server.url("/").toString().replaceFirst("http", "ws")).commit());
        instrumentation.runOnMainSync(() -> app.shopLogin().clearResult());
        loginCameraIsolation = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (!(activity instanceof LoginActivity)) return;
                if (state != null) {
                    state.putBoolean("permission_requested", true);
                    state.putBoolean("permission_in_flight", false);
                    state.putString("camera_id", "usb:SHOP-LOGIN-TEST-NO-DEVICE");
                    state.putString("camera_failure", "绑定测试不采集摄像头");
                }
                // 本组只注入二维码文本；创建后、onStart 前隔离相机，避免系统授权页暂停登录页。
                writeLoginField((LoginActivity) activity, "permissionRequested", true);
                writeLoginField((LoginActivity) activity, "permissionRequestInFlight", false);
                writeLoginField((LoginActivity) activity, "selectedCamera", "usb:SHOP-LOGIN-TEST-NO-DEVICE");
                writeLoginField((LoginActivity) activity, "cameraFailure", "绑定测试不采集摄像头");
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        app.registerActivityLifecycleCallbacks(loginCameraIsolation);
        homeScenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        homeScenario.onActivity(home -> {
            originalHome = home;
            home.startActivity(new Intent(home, LoginActivity.class));
        });
        await(() -> resumed(LoginActivity.class) != null, "应从首页任务栈进入登录页");
    }

    @After public void restoreUserConfiguration() throws Exception {
        try {
            if (homeScenario != null) homeScenario.close();
        } finally {
            try {
                // 先停止页面与夹具连接，等进程协调器收尾后才能恢复，避免迟到保存覆盖原归属。
                instrumentation.runOnMainSync(() -> {
                    if (loginCameraIsolation != null) app.unregisterActivityLifecycleCallbacks(loginCameraIsolation);
                    for (Stage stage : Stage.values()) {
                        for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
                                .getActivitiesInStage(stage))) {
                            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing())
                                activity.finish();
                        }
                    }
                    app.sdkSession().disconnect();
                });
                for (WebSocket socket : sockets) socket.close(1001, "测试结束");
                await(() -> !state().isRunning(), "应先结束测试绑定再恢复配置");
                instrumentation.waitForIdleSync();
            } finally {
                try {
                    if (device != null && originalDevice != null) restore(device, originalDevice);
                } finally {
                    if (bindingPrefs != null && originalBinding != null) restore(bindingPrefs, originalBinding);
                }
                instrumentation.runOnMainSync(() -> app.shopLogin().clearResult());
                if (server != null) server.shutdown();
            }
        }
    }

    @Test public void successSurvivesRecreationAndReturnsHomeAfterSaving() throws Exception {
        String raw = "  TEST-ONLY-007  ";
        BindCall call = scan(raw);
        JSONObject params = call.request.getJSONObject("params");
        assertEquals(raw, params.getString("shop_id"));
        assertEquals(2097500510197125120L, params.getLong("account_id"));
        recreateLogin();
        assertEquals(1, bindCount.get());
        assertEquals("页面切换和旋转应复用同一 SDK 初始化", 1, initCount.get());
        call.respond(0, new JSONObject().put("response", new JSONObject()
                .put("account_id", "TEST-ONLY-ACCOUNT").put("shop_id", "TEST-ONLY-SHOP")
                .put("shop_name", "测试夹具门店")));
        await(() -> resumed(HomeActivity.class) != null, "完整回执后应返回首页");
        assertSame("应返回已有首页实例", originalHome, resumed(HomeActivity.class));
        JSONObject saved = new ShopBindingStore(app).read();
        assertEquals("TEST-ONLY-SHOP", saved.getString("shop_id"));
        assertEquals(raw, saved.getString("external_shop_id"));
        assertEquals("测试夹具门店", saved.getString("shop_name"));
        assertTrue("新归属应对应当前 SDK 连接", ShopBindingStore.matchesConnection(saved, MorningSdkSettings.read(device)));
        await(() -> {
            AtomicReference<String> label = new AtomicReference<>("");
            instrumentation.runOnMainSync(() -> {
                HomeActivity home = resumedOnMain(HomeActivity.class);
                if (home != null) label.set(((TextView) home.findViewById(R.id.header_shop)).getText().toString());
            });
            return label.get().contains("测试夹具门店");
        }, "首页应刷新已登录门店");
        instrumentation.runOnMainSync(() -> {
            TextView header = originalHome.findViewById(R.id.header_shop);
            assertTrue("登录返回后右上角应显示门店名", header.isShown());
            assertEquals("测试夹具门店", header.getText().toString());
        });
        assertEquals(1, bindCount.get());
        assertBusinessConfigurationUnchanged();
        assertNull(serverFailure.get());
    }

    @Test public void requestEchoWithoutCloudScopeCannotLoginOrOverwriteOldBinding() throws Exception {
        BindCall call = scan("TEST-ONLY-INVALID");
        call.respond(0, new JSONObject().put("request", call.request.getJSONObject("params")));
        assertFailedWithoutReplay("归属不完整");
    }

    @Test public void unknownResultStaysOnLoginAndDoesNotReplayAfterRecreation() throws Exception {
        BindCall call = scan("TEST-ONLY-UNKNOWN");
        call.respond(40009, new JSONObject());
        assertFailedWithoutReplay("结果未知");
    }

    @Test public void activationReceiptFinishesImmediatelyAcrossRecreationAndPages() throws Exception {
        LicenseSettingsActivity license = openLicense();
        BindCall activation = submitActivation(license);
        instrumentation.runOnMainSync(() -> {
            assertFalse("回执在途不能提前开放晨检", app.sdkSession().viewState().ready);
            assertTrue("激活回执前仍在途", app.sdkSession().operation().running);
            assertFalse("在途期间禁止重复提交", app.sdkSession().activate("TEST-ONLY-DUPLICATE", "morningcheck"));
            int progressId = license.getResources().getIdentifier("settings_model_progress", "id", license.getPackageName());
            View progress = progressId == 0 ? null : license.findViewById(progressId);
            assertTrue("激活不需要独立模型进度入口", progress == null || progress.getVisibility() == View.GONE);
        });
        assertEquals("激活未返回前不能追加初始化", 1, initCount.get());
        await(() -> activationDialogShowing(license), "激活流程应自动展示等待弹窗");
        assertNoProgressPollingWhileWaiting();
        instrumentation.runOnMainSync(license::recreate);
        await(() -> resumed(LicenseSettingsActivity.class) != null
                && resumed(LicenseSettingsActivity.class) != license, "授权页应正常重建");
        LicenseSettingsActivity recreated = resumed(LicenseSettingsActivity.class);
        await(() -> activationDialogShowing(recreated), "重建应恢复同一激活等待");
        instrumentation.runOnMainSync(() -> {
            recreated.findViewById(R.id.header_home).performClick();
        });
        await(() -> resumed(HomeActivity.class) != null, "在途激活应允许返回首页");
        instrumentation.runOnMainSync(() -> {
            revealHomeEntry(R.id.home_start);
            View start = originalHome.findViewById(R.id.home_start);
            assertTrue("在途返回首页应保持入口可见", start.isShown());
            assertFalse("激活在途时首页应阻止再次提交", start.isEnabled());
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertNull("其他页面在途时不能再次弹出输入并重放请求",
                codeDialog(originalHome)));
        assertEquals("重建、离页及重复点击不得重放激活", 1, activationCount.get());
        activation.respond(0, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.SUCCEEDED,
                "成功回执应立即结束激活，不等待 READY");
        await(() -> !activationDialogShowing(originalHome), "成功后关闭 Loading，结果用 Toast");
        instrumentation.runOnMainSync(() -> {
            assertFalse("写回执成功后操作已结束", app.sdkSession().operation().running);
            assertFalse("成功提示不伪造模型就绪事实", app.sdkSession().viewState().ready);
        });
        assertEquals("夹具仍未 READY，不能靠状态轮询结束激活", "MODEL_PREPARING", fixtureState);
        fixtureState = "READY";
        instrumentation.runOnMainSync(() -> app.sdkSession().refreshDetails());
        await(() -> "READY".equals(sdkStateName()), "完成后的只读刷新应接收真实状态");
        instrumentation.runOnMainSync(() -> {
            revealHomeEntry(R.id.home_start);
            View start = originalHome.findViewById(R.id.home_start);
            assertTrue("就绪后晨检入口仍应可见且可操作", start.isShown() && start.isEnabled());
        });
        assertEquals(1, initCount.get());
        assertEquals(1, activationCount.get());
        assertNull(serverFailure.get());
    }

    @Test public void failedActivationShowsFailureAndNeverInitializes() throws Exception {
        LicenseSettingsActivity license = openLicense();
        BindCall activation = submitActivation(license);
        await(() -> activationDialogShowing(license), "应显示激活等待");
        activation.respond(40003, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.FAILED, "明确失败应结束激活流程");
        await(() -> !activationDialogShowing(license), "失败后关闭 Loading");
        instrumentation.runOnMainSync(() -> {
            assertFalse(app.sdkSession().operation().running);
            assertFalse(app.sdkSession().viewState().ready);
        });
        assertEquals("失败不能追加初始化", 1, initCount.get());
        assertEquals("失败不能自动重新激活", 1, activationCount.get());
        assertNull(serverFailure.get());
    }

    @Test public void homeActivationOpensInputWithoutOpeningLicensePage() throws Exception {
        HomeActivity home = returnHome();
        downloadBytes = 0;
        downloadTotal = 0;
        progressCode = 40010;
        instrumentation.runOnMainSync(() -> clickHomeEntry(R.id.home_start));
        await(() -> inputDialogShowing(home), "首页激活应直接打开输入框");
        assertSame("输入期间首页仍为前台宿主", home, resumed(HomeActivity.class));
        assertNull("首页激活不应先跳转授权页", resumed(LicenseSettingsActivity.class));
        BindCall activation = submitInputAndChooseModel(home, 0);
        assertNoProgressPollingWhileWaiting();
        activation.respond(0, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.SUCCEEDED,
                "进度无总量或 BUSY 都不影响激活回执终态");
        await(() -> !activationDialogShowing(home), "成功回执后首页关闭等待");
        assertEquals("MODEL_PREPARING", fixtureState);
        assertEquals(1, initCount.get());
        assertEquals(1, activationCount.get());
        assertNull(serverFailure.get());
    }

    @Test public void homeActivationRestoresInputDraftAfterCancellingModelAndQueryFailure() throws Exception {
        HomeActivity home = returnHome();
        instrumentation.runOnMainSync(() -> clickHomeEntry(R.id.home_start));
        await(() -> inputDialogShowing(home), "首页应先展示激活输入框");
        AtomicReference<SdkActivationCodeDialog> originalInput = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> originalInput.set(codeDialog(home)));
        submitActivationInput(home, "Z2FBW9L7M78R586H");
        await(() -> modelDialogShowing(home), "合法输入后应展示模型列表");
        assertModelQueryUsesInputCode();
        instrumentation.runOnMainSync(() -> {
            assertTrue("取消应走模型弹窗真实按钮", modelDialog(home)
                    .findViewById(R.id.department_filter_close).performClick());
        });
        await(() -> inputReadyWithDraft(home, "Z2FBW9L7M78R586H"), "取消选模应恢复原输入和完整草稿");
        instrumentation.runOnMainSync(() -> assertSame("取消选模不应重建或丢失输入弹窗",
                originalInput.get(), codeDialog(home)));
        assertEquals("取消选模不得激活", 0, activationCount.get());

        modelQueryCode = 40003;
        submitActivationInput(home, null);
        assertModelQueryUsesInputCode();
        await(() -> inputReadyWithDraft(home, "Z2FBW9L7M78R586H"), "查询失败应恢复草稿并允许直接重试");
        instrumentation.runOnMainSync(() -> assertSame("查询失败保留同一个输入弹窗",
                originalInput.get(), codeDialog(home)));
        assertEquals("查询失败不得发送激活", 0, activationCount.get());

        modelQueryCode = 0;
        submitActivationInput(home, null);
        await(() -> modelDialogShowing(home), "失败后的原草稿应可再次查询");
        assertModelQueryUsesInputCode();
        instrumentation.runOnMainSync(() -> clickModel(home, 0));
        BindCall activation = activations.poll(10, TimeUnit.SECONDS);
        assertNotNull("取消和失败后重试应能提交真实激活", activation);
        assertEquals("Z2FB-W9L7-M78R-586H", activation.request.getJSONObject("params").getString("activation_code"));
        activation.respond(0, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.SUCCEEDED,
                "重试成功应按写回执结束");
        await(() -> !activationDialogShowing(home), "重试成功后关闭等待");
        assertEquals("仅最终选择模型产生一次写请求", 1, activationCount.get());
        assertNull(serverFailure.get());
    }

    @Test public void switchingUsesCurrentCertificateWithoutInputAndOnlyWritesForNewModel() throws Exception {
        returnHome();
        assertTrue(device.edit().putString("sdk_activation_code", "TEST-ONLY-STALE-DRAFT").commit());
        fixtureState = "READY";
        instrumentation.runOnMainSync(() -> {
            app.sdkSession().refreshDetails();
            clickHomeEntry(R.id.home_items);
        });
        await(() -> resumed(CheckRulesSettingsActivity.class) != null, "应进入晨检规则");
        CheckRulesSettingsActivity rules = resumed(CheckRulesSettingsActivity.class);
        await(() -> "READY".equals(sdkStateName()), "切换前应读取当前设备授权");
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            for (int id : new int[]{R.id.settings_switch_model, R.id.settings_current_model,
                    R.id.settings_model_version})
                assertNull("晨检规则不再展示模型与切换入口", rules.findViewById(id));
            assertTrue(rules.findViewById(R.id.header_home).performClick());
        });
        assertTrue("晨检规则不能查询云端模型列表", modelQueries.isEmpty());
        await(() -> resumed(HomeActivity.class) != null, "应返回已有首页");
        instrumentation.runOnMainSync(() -> clickHomeEntry(R.id.home_settings));
        await(() -> resumed(LicenseSettingsActivity.class) != null, "应进入授权与密钥");
        LicenseSettingsActivity license = resumed(LicenseSettingsActivity.class);
        await(() -> currentModelDisplayed(license, "晨检模型", "fixture-v1"), "授权页应展示当前模型和真实版本");
        openModelSwitch(license);
        JSONObject firstQuery = modelQueries.poll(10, TimeUnit.SECONDS);
        assertNotNull("切换应通过真实 WS 查询当前设备模型", firstQuery);
        assertNoActivationCode(firstQuery);
        instrumentation.runOnMainSync(() -> {
            assertNull("切换模型不要求再次输入激活码", codeDialog(license));
            ListView list = modelDialog(license).findViewById(R.id.department_filter_options);
            assertEquals("当前模型应标记为选中", "•", list.getChildAt(0)
                    .<TextView>findViewById(R.id.department_filter_option_selected).getText().toString());
            clickModel(license, 0);
        });
        await(() -> !modelDialogShowing(license), "重选当前模型应关闭列表");
        assertNull("当前模型不得触发激活写请求", activations.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(0, activationCount.get());
        openModelSwitch(license);
        JSONObject secondQuery = modelQueries.poll(10, TimeUnit.SECONDS);
        assertNotNull(secondQuery);
        assertNoActivationCode(secondQuery);
        instrumentation.runOnMainSync(() -> clickModel(license, 1));
        BindCall activation = activations.poll(10, TimeUnit.SECONDS);
        assertNotNull("选择新模型应提交一次真实 WS 激活", activation);
        JSONObject params = activation.request.getJSONObject("params");
        assertEquals("应使用当前设备证书串号，不要求用户再次输入", "TEST-ONLY-CERT-SERIAL",
                params.getString("activation_code"));
        assertEquals("morningcheck-next", params.getString("model_code"));
        await(() -> activationDialogShowing(license), "切换在途应展示等待");
        assertNoProgressPollingWhileWaiting();
        currentModel = "morningcheck-next";
        activation.respond(0, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.SUCCEEDED,
                "切换按成功回执结束，不等待 READY");
        await(() -> !activationDialogShowing(license), "切换结束后关闭等待");
        await(() -> "morningcheck-next".equals(certificateModel()), "只读刷新应取得新模型信息");
        await(() -> currentModelDisplayed(license, "新版晨检模型", "fixture-v2"), "切换成功后刷新真实模型名称和版本");
        assertEquals(1, initCount.get());
        assertEquals(1, activationCount.get());
        assertNull(serverFailure.get());
    }

    @Test public void rotatedCertificateWithoutSerialRequestsExplicitCodeAndRetainsSwitchSemantics() throws Exception {
        returnHome();
        assertTrue(device.edit().putString("sdk_activation_code", "TEST-ONLY-STALE-DRAFT").commit());
        certificateHasSerial = false;
        fixtureState = "READY";
        instrumentation.runOnMainSync(() -> {
            app.sdkSession().refreshDetails();
            clickHomeEntry(R.id.home_settings);
        });
        await(() -> resumed(LicenseSettingsActivity.class) != null, "应进入授权与密钥");
        LicenseSettingsActivity originalLicense = resumed(LicenseSettingsActivity.class);
        await(() -> "READY".equals(sdkStateName()) && !certificateHasActivationSerial(),
                "轮换后的夹具仍已授权，但本地没有激活码");
        openMissingSerialInput(originalLicense);
        instrumentation.runOnMainSync(originalLicense::recreate);
        await(() -> resumed(LicenseSettingsActivity.class) != null
                && resumed(LicenseSettingsActivity.class) != originalLicense, "补码输入应支持页面重建");
        LicenseSettingsActivity license = resumed(LicenseSettingsActivity.class);
        await(() -> inputDialogShowing(license), "重建后恢复补充激活码输入");
        instrumentation.runOnMainSync(() -> {
            assertEquals("补码不能自动使用旧激活草稿", "", codeDialog(license).getDialog()
                    .<EditText>findViewById(R.id.activation_code_editor).getText().toString());
            assertTrue(codeDialog(license).getDialog().findViewById(R.id.activation_code_cancel).performClick());
        });
        await(() -> codeDialogRemoved(license), "取消补码应返回授权页");
        assertEquals("补码取消不能提交激活", 0, activationCount.get());

        String scannedCode = "TEST-ONLY-ROTATED-LICENSE-20261021";
        openMissingSerialInput(license);
        submitScannedActivationCode(license, scannedCode);
        instrumentation.runOnMainSync(() -> clickModel(license, 0));
        await(() -> !modelDialogShowing(license) && codeDialogRemoved(license), "补码后重选当前模型应直接关闭");
        assertNull("补码后仍应保留 current 语义，不能激活当前模型", activations.poll(300, TimeUnit.MILLISECONDS));
        assertEquals(0, activationCount.get());

        openMissingSerialInput(license);
        submitScannedActivationCode(license, scannedCode);
        instrumentation.runOnMainSync(() -> clickModel(license, 1));
        BindCall activation = activations.poll(10, TimeUnit.SECONDS);
        assertNotNull("明确补码并选择新模型后才可提交切换", activation);
        JSONObject params = activation.request.getJSONObject("params");
        assertEquals("切换应使用本次扫码结果，不能使用旧草稿", scannedCode, params.getString("activation_code"));
        assertEquals("morningcheck-next", params.getString("model_code"));
        currentModel = "morningcheck-next";
        activation.respond(0, new JSONObject());
        await(() -> activationPhase() == MorningSdkSession.ActivationPhase.SUCCEEDED,
                "补码切换也按激活回执结束");
        await(() -> !activationDialogShowing(license), "成功后关闭切换等待");
        assertEquals("页面重建与补码都不能重放初始化", 1, initCount.get());
        assertEquals("只有最终选择新模型才有写请求", 1, activationCount.get());
        assertNull(serverFailure.get());
    }

    private void openMissingSerialInput(LicenseSettingsActivity license) {
        instrumentation.runOnMainSync(() -> {
            View entry = license.findViewById(R.id.settings_switch_model);
            assertEquals("模型切换入口暂时隐藏", View.GONE, entry.getVisibility());
            // 入口暂时隐藏，直接驱动保留的内部会话以继续覆盖轮换证书场景。
            ActivationFlowFragment.switchModel(license);
        });
        await(() -> inputDialogShowing(license), "有效证书缺少激活码时应要求补充，不能误报未激活");
        instrumentation.runOnMainSync(() -> {
            Dialog dialog = codeDialog(license).getDialog();
            assertEquals("补码不能自动使用缓存草稿", "", dialog.<EditText>findViewById(R.id.activation_code_editor)
                    .getText().toString());
            assertEquals("应明确标记补码用途", "补充激活码", dialog.<TextView>findViewById(R.id.activation_code_title)
                    .getText().toString());
            assertNull("缺码时不能先展示可写入的模型选项", modelDialog(license));
        });
    }

    private void submitScannedActivationCode(LicenseSettingsActivity license, String code) throws Exception {
        instrumentation.runOnMainSync(() -> {
            modelQueries.clear();
            assertTrue(codeDialog(license).getDialog().findViewById(R.id.activation_code_scan).performClick());
        });
        await(() -> resumed(SdkActivationScanActivity.class) != null, "补码应允许打开扫码页");
        instrumentation.runOnMainSync(() -> {
            SdkActivationScanActivity scanner = resumedOnMain(SdkActivationScanActivity.class);
            scanner.setResult(Activity.RESULT_OK, new Intent().putExtra(SdkActivationScanActivity.EXTRA_CODE, code));
            scanner.finish();
        });
        await(() -> resumed(LicenseSettingsActivity.class) == license && modelDialogShowing(license),
                "扫码结果应直接查询并展示模型，不受手输十六位格式限制");
        JSONObject query = modelQueries.poll(10, TimeUnit.SECONDS);
        assertNotNull("补码后应通过 WS 查询该激活码的模型", query);
        assertEquals(code, query.getJSONObject("params").getString("activation_code"));
    }

    private boolean certificateHasActivationSerial() {
        AtomicReference<Boolean> present = new AtomicReference<>(true);
        instrumentation.runOnMainSync(() -> present.set(app.sdkSession().certificate().has("activation_serial")));
        return present.get();
    }

    private boolean codeDialogRemoved(FragmentActivity host) {
        AtomicReference<Boolean> removed = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> removed.set(codeDialog(host) == null));
        return removed.get();
    }

    private LicenseSettingsActivity openLicense() {
        returnHome();
        instrumentation.runOnMainSync(() -> clickHomeEntry(R.id.home_settings));
        await(() -> resumed(LicenseSettingsActivity.class) != null, "应进入设备授权");
        return resumed(LicenseSettingsActivity.class);
    }

    private HomeActivity returnHome() {
        instrumentation.runOnMainSync(() -> resumedOnMain(LoginActivity.class).finish());
        await(() -> resumed(HomeActivity.class) != null, "应返回首页");
        await(() -> sdkStateName().equals("NOT_ACTIVATED"), "应先完成连接和初始化观察");
        return resumed(HomeActivity.class);
    }

    private void revealHomeEntry(int entryId) {
        if (entryId == R.id.home_settings || entryId == R.id.header_shop) return;
        View entry = originalHome.findViewById(entryId);
        if (entry != null) entry.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, entry.getWidth(), entry.getHeight()), true);
    }

    private void clickHomeEntry(int entryId) {
        revealHomeEntry(entryId);
        View entry = originalHome.findViewById(entryId);
        assertNotNull(entry);
        assertTrue("首页入口应可见且可操作", entry.isShown() && entry.isEnabled());
        assertTrue("首页入口应通过正式点击路由打开", entry.performClick());
    }

    private BindCall submitActivation(LicenseSettingsActivity license) throws Exception {
        instrumentation.runOnMainSync(() -> {
            assertTrue(license.findViewById(R.id.settings_activate).performClick());
        });
        await(() -> inputDialogShowing(license), "激活按钮应打开统一流程内的专用输入框");
        return submitInputAndChooseModel(license, 0);
    }

    private BindCall submitInputAndChooseModel(FragmentActivity host, int position) throws Exception {
        submitActivationInput(host, "Z2FBW9L7M78R586H");
        await(() -> modelDialogShowing(host), "输入后应先选择模型");
        assertModelQueryUsesInputCode();
        instrumentation.runOnMainSync(() -> clickModel(host, position));
        BindCall call = activations.poll(10, TimeUnit.SECONDS);
        assertNotNull("激活输入应提交一次真实WS请求", call);
        assertEquals("Z2FB-W9L7-M78R-586H", call.request.getJSONObject("params").getString("activation_code"));
        assertEquals(position == 0 ? "morningcheck" : "morningcheck-next",
                call.request.getJSONObject("params").getString("model_code"));
        return call;
    }

    private void submitActivationInput(FragmentActivity host, String code) {
        // 保留真实输入窗的布局与校验过程，下一次主线程操作再提交合法的十六位激活码。
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            SdkActivationCodeDialog fragment = codeDialog(host);
            assertNotNull("激活输入应属于统一流程的子 Fragment", fragment);
            Dialog input = fragment.getDialog();
            assertNotNull("专用激活输入框应已创建", input);
            assertTrue("激活输入框应实际显示", input.isShowing());
            if (code != null) ((EditText) input.findViewById(R.id.activation_code_editor)).setText(code);
            View submit = input.findViewById(R.id.activation_code_activate);
            assertTrue("合法激活码应允许提交", submit.isEnabled());
            modelQueries.clear();
            assertTrue("点击真实激活按钮应提交请求", submit.performClick());
        });
    }

    private void assertModelQueryUsesInputCode() throws Exception {
        JSONObject query = modelQueries.poll(10, TimeUnit.SECONDS);
        assertNotNull("输入提交应通过真实 WS 查询模型", query);
        assertEquals("Z2FB-W9L7-M78R-586H", query.getJSONObject("params").getString("activation_code"));
    }

    private void openModelSwitch(LicenseSettingsActivity license) {
        instrumentation.runOnMainSync(() -> {
            modelQueries.clear();
            View entry = license.findViewById(R.id.settings_switch_model);
            assertNotNull("模型切换实现暂时保留", entry);
            assertEquals("模型切换入口暂时隐藏", View.GONE, entry.getVisibility());
            ActivationFlowFragment.switchModel(license);
        });
        await(() -> modelDialogShowing(license), "免输码切换应直接展示当前授权模型列表");
    }

    private static void clickModel(FragmentActivity host, int position) {
        ListView list = modelDialog(host).findViewById(R.id.department_filter_options);
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        assertNotNull("待选模型必须实际显示", row);
        assertTrue("选择应通过真实列表回调提交", list.performItemClick(row, position, list.getItemIdAtPosition(position)));
    }

    private void assertNoProgressPollingWhileWaiting() {
        // 超过原进度预约间隔，验证等待写回执时没有另一条下载进度循环。
        SystemClock.sleep(1_650);
        assertEquals("激活等待不轮询 getDownloadProgress", 0, activationProgressCount.get());
    }

    private static void assertNoActivationCode(JSONObject request) {
        JSONObject params = request.optJSONObject("params");
        assertTrue("设备授权范围查询不得填入手输或缓存激活码",
                params == null || params.isNull("activation_code"));
    }

    private String certificateModel() {
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(app.sdkSession().certificate().optString("model_code")));
        return result.get();
    }

    private boolean currentModelDisplayed(LicenseSettingsActivity license, String name, String version) {
        AtomicReference<Boolean> matches = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            TextView modelName = license.findViewById(R.id.settings_current_model);
            TextView modelVersion = license.findViewById(R.id.settings_model_version);
            matches.set(modelName != null && modelVersion != null && name.contentEquals(modelName.getText())
                    && modelVersion.getText().toString().contains(version));
        });
        return matches.get();
    }

    private MorningSdkSession.ActivationPhase activationPhase() {
        AtomicReference<MorningSdkSession.ActivationPhase> phase = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> phase.set(app.sdkSession().activation().phase));
        return phase.get();
    }

    private boolean activationDialogShowing(FragmentActivity host) {
        AtomicReference<Boolean> showing = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            android.app.Dialog dialog = activationDialog(host);
            showing.set(dialog != null && dialog.isShowing());
        });
        return showing.get();
    }

    private boolean inputDialogShowing(FragmentActivity host) {
        AtomicReference<Boolean> showing = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            SdkActivationCodeDialog input = codeDialog(host);
            showing.set(input != null && input.getDialog() != null && input.getDialog().isShowing());
        });
        return showing.get();
    }

    private boolean inputReadyWithDraft(FragmentActivity host, String draft) {
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            SdkActivationCodeDialog input = codeDialog(host);
            Dialog dialog = input == null ? null : input.getDialog();
            if (dialog == null || !dialog.isShowing()) return;
            EditText editor = dialog.findViewById(R.id.activation_code_editor);
            View submit = dialog.findViewById(R.id.activation_code_activate);
            ready.set(editor != null && submit != null && submit.isEnabled()
                    && draft.equals(editor.getText().toString().replace("-", "")));
        });
        return ready.get();
    }

    private boolean modelDialogShowing(FragmentActivity host) {
        AtomicReference<Boolean> showing = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            AlertDialog dialog = modelDialog(host);
            ListView list = dialog == null ? null : dialog.findViewById(R.id.department_filter_options);
            showing.set(dialog != null && dialog.isShowing() && list != null && list.getChildCount() == 2);
        });
        return showing.get();
    }

    private static Fragment activationFlow(FragmentActivity host) {
        return host.getSupportFragmentManager().findFragmentByTag("modelActivation");
    }

    private static SdkActivationCodeDialog codeDialog(FragmentActivity host) {
        Fragment flow = activationFlow(host);
        if (flow == null) return null;
        Fragment input = flow.getChildFragmentManager().findFragmentByTag(SdkActivationCodeDialog.TAG);
        return input instanceof SdkActivationCodeDialog ? (SdkActivationCodeDialog) input : null;
    }

    private static android.app.Dialog activationDialog(FragmentActivity host) {
        try {
            Fragment flow = activationFlow(host);
            if (flow == null) return null;
            Field field = flow.getClass().getDeclaredField("activationLoading");
            field.setAccessible(true);
            Object loading = field.get(flow);
            if (loading == null) return null;
            Field dialog = LoadingDialog.class.getDeclaredField("dialog");
            dialog.setAccessible(true);
            return (android.app.Dialog) dialog.get(loading);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static AlertDialog modelDialog(FragmentActivity host) {
        try {
            Fragment flow = activationFlow(host);
            if (flow == null) return null;
            Field field = flow.getClass().getDeclaredField("modelDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(flow);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private String sdkStateName() {
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(app.sdkSession().status().optString("sdk_state")));
        return result.get();
    }

    private BindCall scan(String raw) throws Exception {
        // 注入扫码解码结果；真实二维码像素解码由 QrFrameDecoderTest 覆盖。
        instrumentation.runOnMainSync(() -> app.shopLogin().login(raw, MorningSdkSettings.read(device)));
        BindCall call = bindings.poll(10, TimeUnit.SECONDS);
        assertNull(serverFailure.get());
        assertNotNull("未收到绑定请求", call);
        return call;
    }

    private void assertFailedWithoutReplay(String message) throws Exception {
        await(() -> state().phase == ShopLoginCoordinator.Phase.ERROR, "应显示绑定错误");
        assertTrue(state().message, state().message.contains(message));
        recreateLogin();
        assertNotNull(resumed(LoginActivity.class));
        assertNull(resumed(HomeActivity.class));
        assertEquals(originalBinding, bindingPrefs.getAll());
        assertEquals(1, bindCount.get());
        assertNull(bindings.poll(300, TimeUnit.MILLISECONDS));
        assertBusinessConfigurationUnchanged();
        instrumentation.runOnMainSync(() -> {
            LoginActivity login = resumedOnMain(LoginActivity.class);
            assertNotNull("绑定失败重试仍应在前台登录页", login);
            // 重试会清空相机失败提示；本组不测取景，用采集标记阻止随后 IDLE 通知打开相机或授权页。
            writeLoginField(login, "scanning", true);
            assertTrue(login.findViewById(R.id.login_retry).performClick());
        });
        assertEquals(ShopLoginCoordinator.Phase.IDLE, state().phase);
        assertEquals(1, bindCount.get());
        assertNull(serverFailure.get());
    }

    private void assertBusinessConfigurationUnchanged() {
        Map<String, ?> now = device.getAll();
        for (Map.Entry<String, ?> item : originalDevice.entrySet()) {
            if (!item.getKey().equals(MorningSdkSettings.TRANSPORT_KEY)
                    && !item.getKey().equals(MorningSdkSettings.ENDPOINT_KEY))
                assertEquals(item.getKey(), item.getValue(), now.get(item.getKey()));
        }
    }

    private ShopLoginCoordinator.State state() {
        AtomicReference<ShopLoginCoordinator.State> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(app.shopLogin().getState()));
        return result.get();
    }

    private void recreateLogin() {
        LoginActivity previous = resumed(LoginActivity.class);
        assertNotNull(previous);
        instrumentation.runOnMainSync(previous::recreate);
        await(() -> {
            LoginActivity current = resumed(LoginActivity.class);
            return current != null && current != previous;
        }, "登录页应正常重建");
    }

    private <T extends Activity> T resumed(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(resumedOnMain(type)));
        return result.get();
    }

    private static <T extends Activity> T resumedOnMain(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }

    private static void writeLoginField(LoginActivity activity, String name, Object value) {
        try {
            Field field = LoginActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError("登录测试相机隔离失败：" + name, error); }
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }

    private static void reply(WebSocket socket, JSONObject request, int code, JSONObject data) throws Exception {
        socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                .put("code", code).put("reason", code == 0 ? "成功" : "测试夹具回执")
                .put("data", data).toString());
    }

    private static final class BindCall {
        final WebSocket socket;
        final JSONObject request;
        BindCall(WebSocket socket, JSONObject request) { this.socket = socket; this.request = request; }
        void respond(int code, JSONObject data) throws Exception { reply(socket, request, code, data); }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences preferences, Map<String, ?> snapshot) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : snapshot.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), (Set<String>) value);
        }
        assertTrue("应恢复测试前配置", editor.commit());
    }
}
