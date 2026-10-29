package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.data.Jsons;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/** 安全密码与晨检出口使用独立数据库、本机 SDK 夹具，不采集真实图像或提交业务云。 */
@RunWith(AndroidJUnit4.class)
public final class SecurityCheckUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger businessRequests = new AtomicInteger(), inferenceRequests = new AtomicInteger();
    private final CopyOnWriteArrayList<String> unexpectedSdkCommands = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private ActivityScenario<? extends Activity> scenario;
    private CountDownLatch releaseStorageQueue;
    private volatile boolean resultSdkReady;
    private Instrumentation.ActivityMonitor nextEmployeeMonitor;

    @Before
    public void isolateSettingsStorageAndSdk() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        onMain(() -> {
            assertFalse("不能中断正在执行的门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        originalStore = app.store;
        databaseName = "security-check-ui-" + UUID.randomUUID() + ".db";
        isolatedStore = new AppStore(new ContextWrapper(app) {
            @Override
            public File getDatabasePath(String name) {
                return app.getDatabasePath(databaseName);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }

            @Override
            public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                       DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        app.io.submit(() -> isolatedStore.getWritableDatabase()).get(10, TimeUnit.SECONDS);
        onMain(() -> {
            app.store = isolatedStore;
            return null;
        });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"/sdk".equals(request.getPath())) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket socket, Response response) {
                        sockets.add(socket);
                    }

                    @Override
                    public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            String command = request.optString("cmd");
                            if (!new HashSet<>(java.util.Arrays.asList("init", "getStatus", "getCertInfo",
                                    "getDownloadProgress", "getCapabilities", "get_class_table", "getCloudStatus")).contains(command)) {
                                inferenceRequests.incrementAndGet();
                                unexpectedSdkCommands.add(command);
                                Log.e("安全设置验收", "隔离夹具拒绝非只读 SDK 命令：" + command);
                                socket.send(Jsons.obj("requestId", request.getString("requestId"), "code", -1,
                                        "reason", "安全设置测试不能执行该 SDK 命令", "data", new JSONObject()).toString());
                                return;
                            }
                            JSONObject data = "getStatus".equals(command)
                                    ? Jsons.obj("sdk_state", resultSdkReady ? "READY" : "MODEL_NOT_READY", "model_ready", resultSdkReady,
                                            "certificate_valid", true, "device_id", "SECURITY-UI-TEST")
                                    : new JSONObject();
                            socket.send(Jsons.obj("requestId", request.getString("requestId"), "code", 0,
                                    "reason", "本机安全设置测试夹具", "data", data).toString());
                        } catch (Throwable error) {
                            serverFailure.set(error);
                        }
                    }

                    @Override
                    public void onMessage(WebSocket socket, ByteString bytes) {
                        inferenceRequests.incrementAndGet();
                        unexpectedSdkCommands.add("binary");
                        Log.e("安全设置验收", "隔离夹具拒绝 SDK 图像二进制请求");
                    }
                });
            }
        });
        endpoint.start(java.net.InetAddress.getByName("127.0.0.2"), 0);
        int fontIndex = Integer.parseInt(InstrumentationRegistry.getArguments().getString("security_test_font_index", "1"));
        assertTrue("测试字体档位必须有效", fontIndex >= 0 && fontIndex <= 3);
        // 原配置已完整快照；清空后才能验收未改过密码设备的默认口令。
        assertTrue(preferences.edit().clear()
                .putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putString("face_camera", "usb:SECURITY-TEST-NO-CAMERA")
                .putString("hand_camera", "usb:SECURITY-TEST-NO-CAMERA")
                .putString("light_serial_path", "/dev/codex_security_test")
                .putString("serial_path", "/dev/codex_security_test")
                .putBoolean("voice", false).putInt(AppFontSettings.KEY, fontIndex).commit());
    }

    @After
    public void restoreOperatorSettingsAndStorage() throws Exception {
        if (releaseStorageQueue != null) releaseStorageQueue.countDown();
        try {
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> {
                finishPages();
                app.sdkSession().disconnect();
                return null;
            });
            instrumentation.waitForIdleSync();
            if (app != null) {
                app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS);
                app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
                instrumentation.waitForIdleSync();
            }
        } finally {
            try {
                if (app != null && originalStore != null) onMain(() -> {
                    app.store = originalStore;
                    return null;
                });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue(app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                if (nextEmployeeMonitor != null) instrumentation.removeMonitor(nextEmployeeMonitor);
                for (WebSocket socket : sockets) socket.close(1001, "安全设置测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("安全设置与退出不能访问业务云", 0, businessRequests.get());
        assertEquals("模型未就绪时不能调用采集、学习或其它写命令：" + unexpectedSdkCommands, 0, inferenceRequests.get());
        assertNull(serverFailure.get());
    }

    @Test
    public void systemSettingsChangesPasswordOnlyWithOriginalAndPersistsAutomaticCheck() throws Exception {
        assertTrue("新设备应接受默认安全密码", verify("123456"));
        assertFalse("错误口令不能通过默认密码校验", verify("000000"));
        scenario = ActivityScenario.launch(new Intent(app, SystemSettingsActivity.class));
        awaitPage(SystemSettingsActivity.class, R.id.settings_security_password);
        orient(SystemSettingsActivity.class);
        capturePageArtifact("security-system-settings");
        onMain(() -> {
            SystemSettingsActivity page = resumed(SystemSettingsActivity.class);
            CompoundButton automatic = page.findViewById(R.id.settings_auto_check);
            assertFalse("首次配置不应未经选择自动晨检", automatic.isChecked());
            automatic.performClick();
            assertTrue("点击自动晨检开关后应实际切换为开启", automatic.isChecked());
            return null;
        });
        assertTrue(preferences.getBoolean(SecuritySettings.AUTO_CHECK_KEY, false));
        scenario.recreate();
        awaitPage(SystemSettingsActivity.class, R.id.settings_security_password);
        orient(SystemSettingsActivity.class);
        onMain(() -> {
            SystemSettingsActivity page = resumed(SystemSettingsActivity.class);
            assertTrue("重建后开机自动晨检选项应保留", page.<CompoundButton>findViewById(R.id.settings_auto_check).isChecked());
            assertTrue(page.findViewById(R.id.settings_security_password).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(SystemSettingsActivity.class);
        scrollDialogControlIntoView(dialog, R.id.security_password_original);
        scrollDialogControlIntoView(dialog, R.id.security_password_new);
        scrollDialogControlIntoView(dialog, R.id.security_password_repeat);
        onMain(() -> {
            assertEquals(View.VISIBLE, dialog.findViewById(R.id.security_password_new_group).getVisibility());
            assertDialogControlVisible(dialog, android.R.id.button1);
            assertDialogControlVisible(dialog, android.R.id.button2);
            dialog.<EditText>findViewById(R.id.security_password_original).setText("000000");
            dialog.<EditText>findViewById(R.id.security_password_new).setText("654321");
            dialog.<EditText>findViewById(R.id.security_password_repeat).setText("654321");
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()),
                "错误原密码后应留在弹窗并恢复输入");
        assertTrue("错误原密码不能更改当前密码", verify("123456"));
        assertFalse("错误原密码不能写入新密码", verify("654321"));
        onMain(() -> {
            dialog.<EditText>findViewById(R.id.security_password_original).setText("123456");
            dialog.<EditText>findViewById(R.id.security_password_new).setText("654321");
            dialog.<EditText>findViewById(R.id.security_password_repeat).setText("654321");
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> !dialog.isShowing()), "正确原密码应完成改密并关闭弹窗");
        assertFalse("改密后旧密码必须失效", verify("123456"));
        assertTrue("改密后新密码必须有效", verify("654321"));
        scenario.recreate();
        awaitPage(SystemSettingsActivity.class, R.id.settings_security_password);
        assertTrue("页面重建不能恢复旧密码", verify("654321"));
        assertFalse(verify("123456"));
    }

    @Test
    public void builtInNumericKeyboardPreservesLeadingZerosAndRequiresMatchingConfirmation() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, SystemSettingsActivity.class));
        awaitPage(SystemSettingsActivity.class, R.id.settings_security_password);
        orient(SystemSettingsActivity.class);
        onMain(() -> {
            assertTrue(resumed(SystemSettingsActivity.class).findViewById(R.id.settings_security_password).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(SystemSettingsActivity.class);
        assertPasswordKeyboardVisible(dialog);
        captureEmptyPasswordArtifact(dialog, "security-password-change");
        enterPassword(dialog, R.id.security_password_original, "123456");
        enterPassword(dialog, R.id.security_password_new, "00123456");
        onMain(() -> {
            assertEquals(0, dialog.<EditText>findViewById(R.id.security_password_repeat).length());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertTrue("确认密码为空时不能提交成功", dialog.isShowing());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            return null;
        });
        assertFalse("确认密码必填，空值不能写入新密码", verify("00123456"));
        enterPassword(dialog, R.id.security_password_repeat, "00123457");
        onMain(() -> {
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            assertTrue("确认密码不一致时应保留弹窗", dialog.isShowing());
            assertTrue("确认密码不一致时不能锁定提交", dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertTrue("确认密码不一致时应定位确认字段", dialog.findViewById(R.id.security_password_repeat).isSelected());
            return null;
        });
        assertTrue("确认不一致不能改写原密码", verify("123456"));
        assertFalse("确认不一致不能提前写入新密码", verify("00123456"));
        rotatePasswordDialogRoundTrip(SystemSettingsActivity.class, dialog);
        clickPasswordKey(dialog, R.id.security_password_key_delete);
        clickPasswordKey(dialog, R.id.security_password_key_6);
        onMain(() -> {
            assertEquals("退格只能删除当前字段末尾一位", "00123456",
                    dialog.<EditText>findViewById(R.id.security_password_repeat).getText().toString());
            assertEquals("修改确认字段不能影响原密码", "123456",
                    dialog.<EditText>findViewById(R.id.security_password_original).getText().toString());
            assertEquals("修改确认字段不能影响新密码或丢掉前导零", "00123456",
                    dialog.<EditText>findViewById(R.id.security_password_new).getText().toString());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> !dialog.isShowing()), "一致的数字密码应保存成功");
        assertTrue("内置键盘输入的前导零必须持久化", verify("00123456"));
        assertFalse("前导零不能在校验时被去掉", verify("123456"));
        onMain(() -> {
            for (int id : new int[]{R.id.security_password_original, R.id.security_password_new, R.id.security_password_repeat})
                assertEquals("改密完成后不能残留密码", 0, dialog.<EditText>findViewById(id).length());
            return null;
        });

        scenario.close();
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        awaitPage(CheckActivity.class, R.id.check_exit);
        orient(CheckActivity.class);
        onMain(() -> {
            assertTrue(resumed(CheckActivity.class).findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog verification = awaitPasswordDialog(CheckActivity.class);
        assertPasswordKeyboardVisible(verification);
        captureEmptyPasswordArtifact(verification, "security-password-verify");
        submitPassword(verification, "00123456");
        awaitPage(HomeActivity.class, R.id.home_settings);
    }

    @Test
    public void launcherAutomaticCheckRequiresPasswordExitAndDoesNotLoopBackFromHome() throws Exception {
        assertTrue(preferences.edit().putBoolean(SecuritySettings.AUTO_CHECK_KEY, true).commit());
        scenario = ActivityScenario.launch(new Intent(app, HomeActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        awaitPage(CheckActivity.class, R.id.check_exit);
        orient(CheckActivity.class);
        await(() -> onMain(() -> app.sdkSession().viewState().phase == MorningSdkState.Phase.MODEL_NOT_READY),
                "本机 SDK 夹具应保持模型未就绪，避免启动真实采集");
        CheckActivity page = onMain(() -> resumed(CheckActivity.class));
        onMain(() -> {
            assertEquals("晨检页不再显示顶部导航栏", View.GONE,
                    ((View) page.findViewById(R.id.header_leading).getParent()).getVisibility());
            assertBottomRightExit(page, R.id.check_exit);
            return null;
        });
        capturePageArtifact("security-check-waiting");
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("系统返回不能直接退出晨检", page, resumed(CheckActivity.class));
            assertFalse("系统返回不能替代右下角退出入口", passwordDialog(page) != null && passwordDialog(page).isShowing());
            assertTrue(page.findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog first = awaitPasswordDialog(CheckActivity.class);
        onMain(() -> {
            assertEquals("退出仅需要当前密码", View.GONE, first.findViewById(R.id.security_password_new_group).getVisibility());
            first.<EditText>findViewById(R.id.security_password_original).setText("123456");
            assertTrue(first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> !first.isShowing()), "取消退出验证应关闭弹窗");
        onMain(() -> {
            assertEquals("取消后不能保留已输入的密码", 0,
                    first.<EditText>findViewById(R.id.security_password_original).length());
            return null;
        });
        assertSame(page, onMain(() -> resumed(CheckActivity.class)));
        onMain(() -> {
            assertTrue(page.findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(CheckActivity.class);
        submitPassword(dialog, "000000");
        await(() -> onMain(() -> dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()),
                "错误密码必须留在晨检验证窗");
        assertSame(page, onMain(() -> resumed(CheckActivity.class)));
        submitPassword(dialog, "123456");
        awaitPage(HomeActivity.class, R.id.home_settings);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(500);
        onMain(() -> {
            assertNotNull("验证成功应返回首页", resumed(HomeActivity.class));
            assertNull("自动晨检已开启也不能把已授权退出送回晨检", resumed(CheckActivity.class));
            assertTrue("退出只跳过本次自动进入，不能关闭开机选项", SecuritySettings.isAutoCheckEnabled(app));
            return null;
        });
        capturePageArtifact("security-home-after-approved-exit");
    }

    @Test
    public void resultPageAlsoRequiresPasswordAndPreservesSavedRecord() throws Exception {
        String recordId = "security-result-" + UUID.randomUUID();
        app.io.submit(() -> isolatedStore.saveRecord(Jsons.obj("id", recordId, "staff_id", 25,
                "staff_name", "安全出口测试员工", "staff_no", "SAFE25", "created_at", System.currentTimeMillis(),
                "status", 0, "temperature", "38.2", "abnormal_temperature", 1,
                "abnormal_face", 0, "abnormal_hand", 0, "findings", new JSONArray().put("体温偏高"),
                "upload", "未上传"))).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, ResultActivity.class).putExtra("record_id", recordId));
        awaitPage(ResultActivity.class, R.id.result_exit);
        orient(ResultActivity.class);
        await(() -> onMain(() -> resumed(ResultActivity.class) != null
                && resumed(ResultActivity.class).currentRecord != null), "结果页应读取本机记录");
        ResultActivity page = onMain(() -> resumed(ResultActivity.class));
        onMain(() -> {
            assertEquals("SDK 未就绪时不能误示即将进入下一位", "", flowState(page));
            assertTrue(page.findViewById(R.id.result_stay).performClick());
            assertBottomRightExit(page, R.id.result_exit);
            return null;
        });
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("系统返回不能绕过结果页退出密码", page, resumed(ResultActivity.class));
            assertTrue(page.findViewById(R.id.result_exit).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(ResultActivity.class);
        submitPassword(dialog, "000000");
        await(() -> onMain(() -> dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()),
                "结果页错误密码后应继续停留");
        assertSame(page, onMain(() -> resumed(ResultActivity.class)));
        submitPassword(dialog, "123456");
        awaitPage(HomeActivity.class, R.id.home_settings);
        JSONObject stored = app.io.submit(() -> isolatedStore.record(recordId)).get(10, TimeUnit.SECONDS);
        assertEquals("退出不能修改已保存的结果", 0, stored.optInt("status"));
        assertEquals("38.2", stored.optString("temperature"));
        assertEquals(1, stored.optInt("abnormal_temperature"));
        assertEquals("未上传", stored.optString("upload"));
    }

    @Test
    public void stoppingHostCancelsQueuedVerificationAndClearsPasswordDraft() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        awaitPage(CheckActivity.class, R.id.check_exit);
        orient(CheckActivity.class);
        await(() -> onMain(() -> resumed(CheckActivity.class) != null && resumed(CheckActivity.class).loaded),
                "后台切换前应完成待机页读取");
        CheckActivity page = onMain(() -> resumed(CheckActivity.class));
        onMain(() -> {
            assertTrue(page.findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(CheckActivity.class);
        blockStorageQueue();
        submitPassword(dialog, "123456");
        onMain(() -> {
            assertFalse("排队中的验证不能重复提交", dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            return null;
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        await(() -> onMain(() -> !dialog.isShowing()), "宿主停止后必须关闭验证窗");
        onMain(() -> {
            assertEquals("后台离页必须清空密码输入", 0,
                    dialog.<EditText>findViewById(R.id.security_password_original).length());
            assertEquals("后台离页不能保留隐藏的新密码字段", 0,
                    dialog.<EditText>findViewById(R.id.security_password_new).length());
            assertEquals("后台离页不能保留隐藏的确认密码字段", 0,
                    dialog.<EditText>findViewById(R.id.security_password_repeat).length());
            return null;
        });
        releaseStorageQueue.countDown();
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPage(CheckActivity.class, R.id.check_exit);
        onMain(() -> {
            assertSame("后台取消后的迟到验证不能跳转首页", page, resumed(CheckActivity.class));
            assertNull("后台取消不能遗留安全弹窗", passwordDialog(page));
            assertTrue(page.findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog reopened = awaitPasswordDialog(CheckActivity.class);
        onMain(() -> {
            assertEquals("返回后重新验证不能恢复旧密码草稿", 0,
                    reopened.<EditText>findViewById(R.id.security_password_original).length());
            assertTrue(reopened.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        capturePageArtifact("security-check-after-background-cancel");
    }

    @Test
    public void resultReadBehindExitPromptRestoresStayAndNextActionsAfterCancel() throws Exception {
        String recordId = "security-reading-result-" + UUID.randomUUID();
        app.io.submit(() -> isolatedStore.saveRecord(Jsons.obj("id", recordId, "staff_id", 26,
                "staff_name", "读盘出口测试员工", "staff_no", "SAFE26", "created_at", System.currentTimeMillis(),
                "status", 0, "temperature", "38.1", "abnormal_temperature", 1,
                "abnormal_face", 0, "abnormal_hand", 0, "findings", new JSONArray().put("体温偏高"),
                "upload", "未上传"))).get(10, TimeUnit.SECONDS);
        blockStorageQueue();
        scenario = ActivityScenario.launch(new Intent(app, ResultActivity.class).putExtra("record_id", recordId));
        awaitPage(ResultActivity.class, R.id.result_exit);
        orient(ResultActivity.class);
        ResultActivity page = onMain(() -> resumed(ResultActivity.class));
        onMain(() -> {
            assertNull("夹具释放读盘前结果应尚未加载", page.currentRecord);
            assertEquals(View.GONE, page.findViewById(R.id.result_stay).getVisibility());
            assertEquals(View.GONE, page.findViewById(R.id.result_next).getVisibility());
            assertTrue(page.findViewById(R.id.result_exit).performClick());
            return null;
        });
        AlertDialog dialog = awaitPasswordDialog(ResultActivity.class);
        onMain(() -> {
            dialog.<EditText>findViewById(R.id.security_password_original).setText("123456");
            return null;
        });
        releaseStorageQueue.countDown();
        await(() -> onMain(() -> page.currentRecord != null
                && recordId.equals(page.currentRecord.optString("id"))), "打开验证窗时本机记录仍应完成读盘");
        onMain(() -> {
            assertTrue("读盘结束不能替换操作中的验证窗", dialog.isShowing());
            assertEquals("读盘不能丢失当前密码输入", 6,
                    dialog.<EditText>findViewById(R.id.security_password_original).length());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> page.findViewById(R.id.result_body).isShown()
                && page.findViewById(R.id.result_stay).isShown()
                && page.findViewById(R.id.result_next).isShown()),
                "读盘期间取消退出后必须恢复结果内容及停留、下一位按钮");
        onMain(() -> {
            View stay = page.findViewById(R.id.result_stay), next = page.findViewById(R.id.result_next);
            assertTrue("恢复后的停留按钮必须可操作", stay.isEnabled() && stay.hasOnClickListeners());
            assertTrue("恢复后的下一位按钮必须可操作", next.isEnabled() && next.hasOnClickListeners());
            assertEquals("取消验证后密码必须清空", 0,
                    dialog.<EditText>findViewById(R.id.security_password_original).length());
            assertTrue(stay.performClick());
            assertTrue("恢复后的停留按钮应实际暂停自动下一位",
                    page.<TextView>findViewById(R.id.result_stay).getText().toString().contains("已停留"));
            return null;
        });
        capturePageArtifact("security-result-after-reading-cancel");
    }

    @Test
    public void exitPasswordDialogSurvivesContentReflowAndCancelRestoresCheckAndResult() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        awaitPage(CheckActivity.class, R.id.check_exit);
        orient(CheckActivity.class);
        await(() -> onMain(() -> resumed(CheckActivity.class) != null && resumed(CheckActivity.class).loaded),
                "重排验收前应完成晨检待机页读取");
        CheckActivity check = onMain(() -> resumed(CheckActivity.class));
        onMain(() -> {
            assertEquals("正常晨检流程应未暂停", false, privateField(check, "paused"));
            assertEquals("前台晨检应启用语音调度", true, privateField(privateField(check, "speech"), "active"));
            assertTrue(check.findViewById(R.id.check_exit).performClick());
            return null;
        });
        AlertDialog checkDialog = awaitPasswordDialog(CheckActivity.class);
        onMain(() -> {
            checkDialog.<EditText>findViewById(R.id.security_password_original).setText("123");
            View previousExit = check.findViewById(R.id.check_exit);
            // 直接走平台配置变化入口，验证只重建内容、不会再次 onResume 的路径。
            check.onConfigurationChanged(new Configuration(check.getResources().getConfiguration()));
            assertNotSame("配置变化必须真实替换晨检内容视图", previousExit, check.findViewById(R.id.check_exit));
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("晨检内容重排应保留原宿主", check, resumed(CheckActivity.class));
            assertSame("晨检内容重排不能关闭或替换密码窗", checkDialog, passwordDialog(check));
            assertTrue(checkDialog.isShowing());
            assertEquals("晨检重排应保留正在输入的密码", "123",
                    checkDialog.<EditText>findViewById(R.id.security_password_original).getText().toString());
            assertEquals("密码窗打开时晨检应持续暂停", true, privateField(check, "paused"));
            assertEquals("内容重排不能擅自恢复语音", false, privateField(privateField(check, "speech"), "active"));
            assertTrue(checkDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> passwordDialog(check) == null
                && Boolean.FALSE.equals(privateField(check, "paused"))
                && Boolean.TRUE.equals(privateField(privateField(check, "speech"), "active"))),
                "晨检重排后取消退出应恢复原来的暂停与语音状态");
        onMain(() -> {
            assertEquals("取消重排后的验证窗应清空草稿", 0,
                    checkDialog.<EditText>findViewById(R.id.security_password_original).length());
            View exit = check.findViewById(R.id.check_exit);
            assertTrue("新晨检视图的退出按钮应可操作", exit.isShown() && exit.isEnabled() && exit.hasOnClickListeners());
            return null;
        });
        scenario.close();
        scenario = null;
        instrumentation.waitForIdleSync();

        String recordId = "security-reflow-result-" + UUID.randomUUID();
        app.io.submit(() -> isolatedStore.saveRecord(Jsons.obj("id", recordId, "staff_id", 27,
                "staff_name", "重排出口测试员工", "staff_no", "SAFE27", "created_at", System.currentTimeMillis(),
                "status", 0, "temperature", "38.1", "abnormal_temperature", 1,
                "abnormal_face", 0, "abnormal_hand", 0, "findings", new JSONArray().put("体温偏高"),
                "upload", "未上传"))).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, ResultActivity.class).putExtra("record_id", recordId));
        awaitPage(ResultActivity.class, R.id.result_exit);
        orient(ResultActivity.class);
        ResultActivity result = onMain(() -> resumed(ResultActivity.class));
        await(() -> onMain(() -> result.currentRecord != null && Boolean.TRUE.equals(privateField(result, "nextScheduled"))),
                "结果读盘完成后应处于正常自动下一位流程");
        onMain(() -> {
            assertEquals("未选择停留前结果应未暂停", false, privateField(result, "paused"));
            assertEquals("前台结果应启用语音调度", true, privateField(privateField(result, "speech"), "active"));
            assertTrue(result.findViewById(R.id.result_exit).performClick());
            return null;
        });
        AlertDialog resultDialog = awaitPasswordDialog(ResultActivity.class);
        onMain(() -> {
            resultDialog.<EditText>findViewById(R.id.security_password_original).setText("456");
            View previousExit = result.findViewById(R.id.result_exit);
            result.onConfigurationChanged(new Configuration(result.getResources().getConfiguration()));
            assertNotSame("配置变化必须真实替换结果内容视图", previousExit, result.findViewById(R.id.result_exit));
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("结果内容重排应保留原宿主", result, resumed(ResultActivity.class));
            assertSame("结果内容重排不能关闭或替换密码窗", resultDialog, passwordDialog(result));
            assertTrue(resultDialog.isShowing());
            assertEquals("结果重排应保留正在输入的密码", "456",
                    resultDialog.<EditText>findViewById(R.id.security_password_original).getText().toString());
            assertEquals("密码窗打开时结果应持续暂停", true, privateField(result, "paused"));
            assertEquals("内容重排不能擅自恢复结果语音", false, privateField(privateField(result, "speech"), "active"));
            assertEquals("验证期间不能继续预约下一位", false, privateField(result, "nextScheduled"));
            assertTrue(resultDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            return null;
        });
        await(() -> onMain(() -> passwordDialog(result) == null
                && Boolean.FALSE.equals(privateField(result, "paused"))
                && Boolean.TRUE.equals(privateField(privateField(result, "speech"), "active"))
                && Boolean.TRUE.equals(privateField(result, "nextScheduled"))),
                "结果重排后取消退出应恢复语音及自动下一位预约");
        onMain(() -> {
            View stay = result.findViewById(R.id.result_stay), next = result.findViewById(R.id.result_next);
            assertTrue("重排后的停留按钮必须可操作", stay.isShown() && stay.isEnabled() && stay.hasOnClickListeners());
            assertTrue("重排后的下一位按钮必须可操作", next.isShown() && next.isEnabled() && next.hasOnClickListeners());
            assertEquals("取消重排后的结果验证窗应清空草稿", 0,
                    resultDialog.<EditText>findViewById(R.id.security_password_original).length());
            assertTrue(stay.performClick());
            assertEquals("恢复后的停留操作应实际暂停流程", true, privateField(result, "paused"));
            assertEquals("停留应取消自动下一位预约", false, privateField(result, "nextScheduled"));
            return null;
        });
    }

    @Test
    public void abnormalResultCountsDownAndReflowKeepsDeadlineUntilStay() throws Exception {
        ResultActivity page = launchCountdownResult(0);
        int first = onMain(() -> countdownSeconds(page));
        assertTrue("异常结果应从十五秒开始展示，而不是正常结果的五秒", first >= 13 && first <= 15);
        long deadline = onMain(() -> (Long) privateField(page, "nextPersonAt"));
        capturePageArtifact("result-countdown-before-stay");
        await(() -> onMain(() -> countdownSeconds(page) >= 0 && countdownSeconds(page) < first),
                "自动下一位文案应随真实计时减少");
        onMain(() -> {
            int before = countdownSeconds(page);
            View previous = page.findViewById(R.id.result_flow_state);
            page.onConfigurationChanged(new Configuration(page.getResources().getConfiguration()));
            assertNotSame("配置重排应真实替换结果内容", previous, page.findViewById(R.id.result_flow_state));
            assertEquals("同宿主重排不能延长下一位的截止时间", deadline,
                    ((Long) privateField(page, "nextPersonAt")).longValue());
            assertTrue("重排后的倒计时不能重新从十五秒开始", countdownSeconds(page) <= before);
            assertTrue(page.findViewById(R.id.result_stay).performClick());
            assertEquals("已停留 · 点击下一位继续", flowState(page));
            assertAutomaticNextCancelled(page);
            return null;
        });
        SystemClock.sleep(1200);
        onMain(() -> {
            assertSame("停留不能跳往下一位", page, resumed(ResultActivity.class));
            assertEquals("停留后定时回调不能覆盖停留提示", "已停留 · 点击下一位继续", flowState(page));
            assertAutomaticNextCancelled(page);
            assertEquals(0, nextEmployeeMonitor.getHits());
            return null;
        });
    }

    @Test
    public void resultCountdownCancelsOnBackgroundAndRestartsOnResume() throws Exception {
        ResultActivity page = launchCountdownResult(0);
        long originalDeadline = onMain(() -> (Long) privateField(page, "nextPersonAt"));
        scenario.moveToState(Lifecycle.State.CREATED);
        String stoppedText = onMain(() -> {
            assertAutomaticNextCancelled(page);
            return flowState(page);
        });
        SystemClock.sleep(1200);
        onMain(() -> {
            assertAutomaticNextCancelled(page);
            assertEquals("离开结果页后不能继续回写旧视图", stoppedText, flowState(page));
            assertEquals(0, nextEmployeeMonitor.getHits());
            return null;
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        await(() -> onMain(() -> resumed(ResultActivity.class) == page && countdownSeconds(page) >= 13
                && Boolean.TRUE.equals(privateField(page, "nextScheduled"))), "回前台后应重新展示异常结果的完整倒计时");
        onMain(() -> {
            assertTrue("恢复不能沿用已经撤销的旧截止时间",
                    ((Long) privateField(page, "nextPersonAt")) > originalDeadline);
            return null;
        });
        scenario.close();
        scenario = null;
        onMain(() -> {
            assertTrue(page.isDestroyed());
            assertAutomaticNextCancelled(page);
            return null;
        });
    }

    @Test
    public void normalResultCountsFiveSecondsThenWaitsForSpeechBeforeNextEmployee() throws Exception {
        ResultActivity page = launchCountdownResult(1);
        onMain(() -> {
            int initial = countdownSeconds(page);
            assertTrue("正常结果倒计时应为五秒", initial >= 3 && initial <= 5);
            ((MorningVoice) privateField(page, "speech")).close();
            return null;
        });
        CountdownSpeaker speaker = new CountdownSpeaker();
        onMain(() -> {
            MorningVoice voice = new MorningVoice(page.main, () -> true, listener -> {
                listener.ready(TextToSpeech.SUCCESS);
                return speaker;
            });
            setPrivateField(page, "speech", voice);
            voice.resume();
            voice.enterStage("countdown-result", "晨检正常");
            return null;
        });
        await(() -> onMain(() -> speaker.speaking), "假语音应实际进入播报状态");
        await(() -> onMain(() -> "播报结束后下一位 · 可停留查看".equals(flowState(page))),
                "五秒结束后若仍在播报，应明确展示等待语音结束");
        onMain(() -> {
            assertSame("播报未完成时不能提前离开结果页", page, resumed(ResultActivity.class));
            assertEquals("不能显示负秒数或反复归零", "播报结束后下一位 · 可停留查看", flowState(page));
            assertEquals(0, nextEmployeeMonitor.getHits());
            speaker.speaking = false;
            return null;
        });
        await(() -> nextEmployeeMonitor.getHits() == 1, "播报结束后应按同一计时预约下一位");
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertTrue("下一位导航后应关闭当前结果页", page.isFinishing() || page.isDestroyed());
            assertEquals("计时与语音回调不能重复发起下一位", 1, nextEmployeeMonitor.getHits());
            assertAutomaticNextCancelled(page);
            return null;
        });
    }

    private ResultActivity launchCountdownResult(int status) throws Exception {
        resultSdkReady = true;
        nextEmployeeMonitor = instrumentation.addMonitor(CheckActivity.class.getName(),
                new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        String recordId = "security-countdown-result-" + UUID.randomUUID();
        app.io.submit(() -> isolatedStore.saveRecord(Jsons.obj("id", recordId, "staff_id", 28,
                "staff_name", "倒计时测试员工", "staff_no", "SAFE28", "created_at", System.currentTimeMillis(),
                "status", status, "temperature", "36.5", "abnormal_temperature", 0,
                "abnormal_face", 0, "abnormal_hand", 0, "findings", new JSONArray(), "upload", "未上传")))
                .get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, ResultActivity.class).putExtra("record_id", recordId));
        awaitPage(ResultActivity.class, R.id.result_exit);
        await(() -> onMain(() -> {
            ResultActivity result = resumed(ResultActivity.class);
            return result != null && result.currentRecord != null && result.sdkReady() && countdownSeconds(result) >= 0;
        }), "结果读盘与隔离 SDK 就绪后应显示自动下一位倒计时");
        return onMain(() -> resumed(ResultActivity.class));
    }

    private static String flowState(ResultActivity page) {
        return page.<TextView>findViewById(R.id.result_flow_state).getText().toString();
    }

    private static int countdownSeconds(ResultActivity page) {
        String text = flowState(page);
        return text.matches("\\d+秒后下一位 · 可停留查看") ? Integer.parseInt(text.substring(0, text.indexOf('秒'))) : -1;
    }

    private static void assertAutomaticNextCancelled(ResultActivity page) {
        assertEquals("取消后不能保留自动下一位预约", false, privateField(page, "nextScheduled"));
        assertEquals("取消后应清空本轮截止时间", 0L, ((Long) privateField(page, "nextPersonAt")).longValue());
        assertFalse("计时回调不能遗留在消息队列", page.main.hasCallbacks((Runnable) privateField(page, "nextPerson")));
        assertFalse("等待语音的下一位回调不能遗留", page.main.hasCallbacks((Runnable) privateField(page, "nextAfterSpeech")));
    }

    private static void setPrivateField(Object owner, String name, Object value) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class CountdownSpeaker implements MorningVoice.Speaker {
        boolean speaking;

        @Override
        public int setLanguage() {
            return TextToSpeech.LANG_AVAILABLE;
        }

        @Override
        public int speak(String text) {
            speaking = true;
            return TextToSpeech.SUCCESS;
        }

        @Override
        public boolean isSpeaking() {
            return speaking;
        }

        @Override
        public void stop() {
            speaking = false;
        }

        @Override
        public void shutdown() {
            speaking = false;
        }
    }

    private static Object privateField(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private boolean verify(String password) throws Exception {
        boolean existingPassword = preferences.contains("security_password_hash");
        return app.io.submit(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                return SecuritySettings.verify(app, password);
            } finally {
                long elapsed = SystemClock.elapsedRealtime() - started;
                Log.i("安全设置验收", (existingPassword ? "常规密码校验" : "首次密码初始化与校验") + "耗时 " + elapsed + " ms");
                if (existingPassword) assertTrue("常规密码校验应在 3 秒内完成，实际 " + elapsed + " ms", elapsed <= 3000);
            }
        }).get(15, TimeUnit.SECONDS);
    }

    private void blockStorageQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        releaseStorageQueue = new CountDownLatch(1);
        app.io.execute(() -> {
            entered.countDown();
            try {
                if (!releaseStorageQueue.await(20, TimeUnit.SECONDS))
                    serverFailure.compareAndSet(null, new AssertionError("本机存储阻塞夹具等待超时"));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                serverFailure.compareAndSet(null, error);
            }
        });
        assertTrue("应先阻塞本机存储队列再触发界面", entered.await(5, TimeUnit.SECONDS));
    }

    private void capturePageArtifact(String name) throws Exception {
        if (!Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("security_capture_artifacts", "false"))) return;
        instrumentation.waitForIdleSync();
        onMain(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (activity instanceof BaseMorningActivity) {
                    AlertDialog dialog = passwordDialog((BaseMorningActivity) activity);
                    assertFalse("安全密码窗口启用防截图，证据只采集普通页面", dialog != null && dialog.isShowing());
                }
            return null;
        });
        // 等窗口过渡结束再取视觉证据，避免截图停在系统淡入淡出的中间帧。
        SystemClock.sleep(300);
        Bitmap bitmap = null;
        long deadline = SystemClock.elapsedRealtime() + 2000;
        do {
            bitmap = instrumentation.getUiAutomation().takeScreenshot();
            if (bitmap != null) break;
            // 密码窗口已关闭后，仍需等合成器移除它的防截图图层；不清除任何安全标记。
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertNotNull("普通页面截图必须可读取", bitmap);
        File folder = new File(app.getFilesDir(), "codex-ui-evidence");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private void captureEmptyPasswordArtifact(AlertDialog dialog, String name) throws Exception {
        if (!Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("security_capture_password_artifacts", "false"))) return;
        onMain(() -> {
            assertTrue("密码弹窗生产逻辑必须保持防截图", (dialog.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            for (int id : new int[]{R.id.security_password_original, R.id.security_password_new, R.id.security_password_repeat})
                assertEquals("仅允许截取未输入密码的初始弹窗", 0, dialog.<EditText>findViewById(id).length());
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return null;
        });
        Bitmap bitmap = null;
        try {
            instrumentation.waitForIdleSync();
            // 仅显式开启视觉验收时解除空白窗口的保护，并等待上一操作的 Toast 消退。
            SystemClock.sleep(2500);
            long deadline = SystemClock.elapsedRealtime() + 2000;
            do {
                bitmap = instrumentation.getUiAutomation().takeScreenshot();
                if (bitmap != null) break;
                SystemClock.sleep(100);
            } while (SystemClock.elapsedRealtime() < deadline);
            assertNotNull("空白密码弹窗截图必须可读取", bitmap);
            File folder = new File(app.getFilesDir(), "codex-ui-evidence");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally {
            if (bitmap != null) bitmap.recycle();
            onMain(() -> {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                return null;
            });
        }
    }

    private <T extends BaseMorningActivity> AlertDialog awaitPasswordDialog(Class<T> type) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            AlertDialog dialog = page == null ? null : passwordDialog(page);
            return dialog != null && dialog.isShowing()
                    && dialog.findViewById(R.id.security_password_original) != null
                    && dialog.findViewById(R.id.security_password_original).getHeight() > 0
                    && dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled();
        }), "安全密码弹窗应显示且允许输入");
        return onMain(() -> passwordDialog(resumed(type)));
    }

    private AlertDialog passwordDialog(BaseMorningActivity page) {
        if (page instanceof SystemSettingsActivity) return ((SystemSettingsActivity) page).formDialog;
        if (!(page instanceof CheckActivity) && !(page instanceof ResultActivity)) return null;
        try {
            Field field = page.getClass().getDeclaredField("exitDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(page);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void submitPassword(AlertDialog dialog, String value) {
        enterPassword(dialog, R.id.security_password_original, value);
        onMain(() -> {
            assertDialogControlVisible(dialog, android.R.id.button1);
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            return null;
        });
    }

    private void enterPassword(AlertDialog dialog, int fieldId, String value) {
        scrollDialogControlIntoView(dialog, fieldId);
        Map<Integer, String> untouched = onMain(() -> {
            EditText field = dialog.findViewById(fieldId);
            assertFalse("密码字段不能弹出系统输入法", field.getShowSoftInputOnFocus());
            assertTrue("密码字段应可切换当前输入位置", field.performClick());
            Map<Integer, String> previous = new HashMap<>();
            for (int id : new int[]{R.id.security_password_original, R.id.security_password_new, R.id.security_password_repeat}) {
                EditText item = dialog.findViewById(id);
                assertEquals("只有当前密码字段处于选中态", id == fieldId, item.isSelected());
                if (id != fieldId) previous.put(id, item.getText().toString());
            }
            return previous;
        });
        clickPasswordKey(dialog, R.id.security_password_key_clear);
        onMain(() -> {
            assertEquals("清空键应清空当前字段", 0, dialog.<EditText>findViewById(fieldId).length());
            return null;
        });
        int[] digits = {R.id.security_password_key_0, R.id.security_password_key_1,
                R.id.security_password_key_2, R.id.security_password_key_3, R.id.security_password_key_4,
                R.id.security_password_key_5, R.id.security_password_key_6, R.id.security_password_key_7,
                R.id.security_password_key_8, R.id.security_password_key_9};
        for (int index = 0; index < value.length(); index++) {
            int digit = value.charAt(index) - '0';
            assertTrue("夹具只能通过数字键输入 ASCII 数字", digit >= 0 && digit < digits.length);
            clickPasswordKey(dialog, digits[digit]);
        }
        onMain(() -> {
            assertEquals("数字键盘输入不能丢掉前导零或改变数字", value,
                    dialog.<EditText>findViewById(fieldId).getText().toString());
            for (Map.Entry<Integer, String> entry : untouched.entrySet())
                assertEquals("清空和输入只应作用于当前字段", entry.getValue(),
                        dialog.<EditText>findViewById(entry.getKey()).getText().toString());
            return null;
        });
    }

    private void clickPasswordKey(AlertDialog dialog, int id) {
        scrollDialogControlIntoView(dialog, id);
        onMain(() -> {
            assertTrue("内置密码按键应可点击：" + id, dialog.findViewById(id).performClick());
            return null;
        });
    }

    private void assertPasswordKeyboardVisible(AlertDialog dialog) {
        assertPasswordDialogGeometry(dialog);
        for (int id : new int[]{R.id.security_password_key_0, R.id.security_password_key_1,
                R.id.security_password_key_2, R.id.security_password_key_3, R.id.security_password_key_4,
                R.id.security_password_key_5, R.id.security_password_key_6, R.id.security_password_key_7,
                R.id.security_password_key_8, R.id.security_password_key_9,
                R.id.security_password_key_clear, R.id.security_password_key_delete})
            scrollDialogControlIntoView(dialog, id);
        scrollDialogControlIntoView(dialog, R.id.security_password_original);
        onMain(() -> {
            assertDialogControlVisible(dialog, android.R.id.button1);
            assertDialogControlVisible(dialog, android.R.id.button2);
            return null;
        });
    }

    private void assertPasswordDialogGeometry(AlertDialog dialog) {
        await(() -> onMain(() -> {
            View decor = dialog.getWindow().getDecorView();
            View card = dialog.findViewById(R.id.security_password_card);
            View root = (View) card.getParent();
            return decor.getWidth() > 0 && decor.getHeight() > 0 && card.getHeight() > 0
                    && !decor.isLayoutRequested() && !root.isLayoutRequested() && !card.isLayoutRequested();
        }), "安全密码窗口应完成尺寸调整");
        onMain(() -> {
            View decor = dialog.getWindow().getDecorView();
            View card = dialog.findViewById(R.id.security_password_card);
            assertTrue("全屏密码遮罩必须使用 FrameLayout 根容器", card.getParent() instanceof FrameLayout);
            FrameLayout root = (FrameLayout) card.getParent();
            assertEquals("密码遮罩宽度必须铺满窗口", decor.getWidth(), root.getWidth());
            assertEquals("密码遮罩高度必须铺满窗口，不能随正文收缩", decor.getHeight(), root.getHeight());
            int[] cardPosition = new int[2], rootPosition = new int[2];
            card.getLocationOnScreen(cardPosition);
            root.getLocationOnScreen(rootPosition);
            assertEquals("密码卡片必须在遮罩内水平居中", rootPosition[0] + root.getWidth() / 2f,
                    cardPosition[0] + card.getWidth() / 2f, 2f);
            assertEquals("密码卡片必须在遮罩内垂直居中", rootPosition[1] + root.getHeight() / 2f,
                    cardPosition[1] + card.getHeight() / 2f, 2f);
            LinearLayout content = dialog.findViewById(R.id.security_password_content);
            assertEquals("横屏应左右排列输入和键盘，竖屏应上下排列", root.getWidth() > root.getHeight()
                    ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL, content.getOrientation());
            return null;
        });
    }

    private <T extends BaseMorningActivity> void rotatePasswordDialogRoundTrip(Class<T> type, AlertDialog dialog) {
        T originalHost = onMain(() -> resumed(type));
        boolean originalPortrait = onMain(() -> originalHost.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT);
        Map<Integer, EditText> fields = new HashMap<>();
        Map<Integer, String> drafts = onMain(() -> {
            Map<Integer, String> values = new HashMap<>();
            for (int id : new int[]{R.id.security_password_original, R.id.security_password_new, R.id.security_password_repeat}) {
                EditText field = dialog.findViewById(id);
                fields.put(id, field);
                values.put(id, field.getText().toString());
            }
            return values;
        });
        for (boolean portrait : new boolean[]{!originalPortrait, originalPortrait}) {
            orient(type, portrait);
            await(() -> onMain(() -> {
                View decor = dialog.getWindow().getDecorView(), hostDecor = originalHost.getWindow().getDecorView();
                return decor.getWidth() == hostDecor.getWidth() && decor.getHeight() == hostDecor.getHeight()
                        && !decor.isLayoutRequested();
            }), "旋转后安全密码窗口应跟随同一宿主调整尺寸");
            onMain(() -> {
                assertSame("密码输入中旋转不能重建宿主", originalHost, resumed(type));
                assertSame("旋转不能替换正在输入的密码弹窗", dialog, passwordDialog(originalHost));
                assertTrue(dialog.isShowing());
                for (Map.Entry<Integer, EditText> entry : fields.entrySet()) {
                    assertSame("旋转应保留原来的输入控件", entry.getValue(), dialog.findViewById(entry.getKey()));
                    assertEquals("旋转不能清空或改写密码草稿", drafts.get(entry.getKey()), entry.getValue().getText().toString());
                }
                assertTrue("旋转应保留当前确认字段选择", dialog.findViewById(R.id.security_password_repeat).isSelected());
                return null;
            });
            assertPasswordKeyboardVisible(dialog);
        }
    }

    private void scrollDialogControlIntoView(AlertDialog dialog, int id) {
        await(() -> onMain(() -> {
            View view = dialog.findViewById(id);
            assertNotNull("安全弹窗控件必须存在：" + id, view);
            if (view.getWidth() == 0 || view.getHeight() == 0) return false;
            // 手机和 130% 字号只滚动输入区，不要求三个密码字段同时完整显示。
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
            Rect visible = new Rect();
            return view.isShown() && view.getGlobalVisibleRect(visible)
                    && visible.width() == view.getWidth() && visible.height() == view.getHeight();
        }), "安全弹窗控件应可滚动到完整可见：" + id);
        onMain(() -> {
            assertDialogControlVisible(dialog, id);
            return null;
        });
    }

    private static void assertDialogControlVisible(AlertDialog dialog, int id) {
        View view = dialog.findViewById(id);
        Rect visible = new Rect();
        assertNotNull("安全弹窗控件必须存在：" + id, view);
        assertTrue("安全弹窗控件必须可见：" + id, view.isShown() && view.getGlobalVisibleRect(visible));
        assertEquals("安全弹窗控件不能上下裁切：" + id, view.getHeight(), visible.height());
        assertEquals("安全弹窗控件不能左右裁切：" + id, view.getWidth(), visible.width());
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNotNull(text.getLayout());
            assertTrue("安全弹窗字体不能上下裁切：" + id,
                    text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
    }

    private static void assertBottomRightExit(Activity page, int id) {
        View exit = page.findViewById(id), root = page.findViewById(R.id.main_root);
        Rect visible = new Rect();
        assertTrue("退出入口应完整可见", exit.isShown() && exit.getGlobalVisibleRect(visible));
        assertEquals(exit.getWidth(), visible.width());
        assertEquals(exit.getHeight(), visible.height());
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        assertTrue("退出入口应位于页面右下角", visible.centerX() > origin[0] + root.getWidth() / 2
                && visible.centerY() > origin[1] + root.getHeight() / 2);
    }

    private <T extends BaseMorningActivity> void awaitPage(Class<T> type, int controlId) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            View control = page == null ? null : page.findViewById(controlId);
            return control != null && control.isShown() && control.isEnabled() && control.getHeight() > 0
                    && !page.findViewById(android.R.id.content).isLayoutRequested();
        }), "页面应就绪：" + type.getSimpleName());
    }

    private <T extends BaseMorningActivity> void orient(Class<T> type) {
        String expected = InstrumentationRegistry.getArguments().getString("expected_orientation", "");
        if (expected.isEmpty()) return;
        orient(type, "portrait".equals(expected));
    }

    private <T extends BaseMorningActivity> void orient(Class<T> type, boolean portrait) {
        onMain(() -> {
            resumed(type).setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            return null;
        });
        await(() -> onMain(() -> {
            T page = resumed(type);
            if (page == null) return false;
            View root = page.findViewById(android.R.id.content);
            return !root.isLayoutRequested() && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && page.getResources().getConfiguration().orientation == (portrait
                    ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "安全页面必须实际使用指定方向：" + (portrait ? "portrait" : "landscape"));
    }

    private <T extends Activity> T resumed(Class<T> type) {
        for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(page) && !page.isFinishing()) return type.cast(page);
        return null;
    }

    private void finishPages() {
        for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED})
            for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (app.getPackageName().equals(page.getPackageName()) && !page.isFinishing()) page.finish();
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                result.set(action.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
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
        assertTrue(editor.commit());
        assertEquals(original, preferences.getAll());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
