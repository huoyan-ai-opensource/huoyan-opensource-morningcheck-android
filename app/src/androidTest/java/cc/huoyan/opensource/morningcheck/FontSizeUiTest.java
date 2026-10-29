package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Layout;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.domain.HealthDateSelection;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 字号行为使用隔离 SDK；尺寸矩阵使用 Android 的真实 XML 测量和排版结果。 */
@RunWith(AndroidJUnit4.class)
public final class FontSizeUiTest {
    private static final String TAG = "FontSizeUiTest";
    private static final int[][] WINDOWS = {{360, 640}, {640, 360}, {800, 1280}, {1280, 800}, {360, 480}, {640, 320}};
    private static final String LONG_NAME = "欧阳长名字员工测试";
    private static final String LONG_DEPARTMENT = "中央厨房食品安全质量检查部门";
    private static final String LONG_NUMBER = "TEST-EMPLOYEE-20261012-001";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger initCount = new AtomicInteger(), unexpectedWrites = new AtomicInteger();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final Set<String> geometryFailures = new LinkedHashSet<>();
    private MorningApplication app;
    private SharedPreferences device, binding;
    private Map<String, Object> originalDevice, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<HomeActivity> homeScenario;
    private String measurementCase;

    @Before public void isolateSdkAndPreferences() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalDevice = new HashMap<>(device.getAll());
        originalBinding = new HashMap<>(binding.getAll());
        onMain(() -> {
            assertFalse("字号测试不能打断正在执行的门店登录", app.shopLogin().getState().isRunning());
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
                            JSONObject request = new JSONObject(text), data = new JSONObject();
                            String command = request.getString("cmd");
                            if ("init".equals(command)) initCount.incrementAndGet();
                            else if (!command.startsWith("get")) unexpectedWrites.incrementAndGet();
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY")
                                    .put("model_ready", true).put("certificate_valid", true)
                                    .put("device_id", "FONT-SIZE-TEST-ONLY");
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "字号隔离夹具").put("data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).putInt(AppFontSettings.KEY, AppFontSettings.DEFAULT_INDEX).commit());
    }

    @After public void restoreTestState() throws Exception {
        try {
            if (homeScenario != null) homeScenario.close();
            if (app != null) onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            instrumentation.waitForIdleSync();
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } finally {
            try {
                if (device != null && originalDevice != null) restore(device, originalDevice);
                if (binding != null && originalBinding != null) {
                    assertEquals("字号调整不能改变门店归属", originalBinding, binding.getAll());
                    restore(binding, originalBinding);
                }
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "字号测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertNull("隔离 SDK 夹具不能发生异常", serverFailure.get());
        assertEquals("字号调整不能访问业务云", 0, businessRequests.get());
        assertEquals("字号调整不能执行 SDK 管理写入", 0, unexpectedWrites.get());
    }

    @Test public void fontDraftApplyReturnAndRecreationKeepOneSharedSdkInitialization() throws Exception {
        launchHome();
        HomeActivity originalHome = onMain(() -> current(HomeActivity.class));
        float standardHomeText = onMain(() -> ((TextView) originalHome.findViewById(R.id.home_start)).getTextSize());
        onMain(() -> { originalHome.navigate("settings", 7); return null; });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) != null
                && current(SystemSettingsActivity.class).loaded), "系统设置应进入前台并读取完成");
        SystemSettingsActivity first = onMain(() -> current(SystemSettingsActivity.class));
        onMain(() -> {
            assertFontEntryOnly(first.getWindow().getDecorView());
            required(first, "settings_font_current").performClick();
            return null;
        });
        awaitStableFontPreview(first, "标准档初次布局");
        float standardPreview = onMain(() -> fontPreview(first).getTextSize());
        onMain(() -> {
            assertTrue("首次打开应显示已保存的标准档",
                    ((TextView) required(fontDialogRoot(first), "settings_font_current")).getText().toString()
                            .contains(first.getResources().getStringArray(R.array.app_font_size_descriptions)[1]));
            Log.i(TAG, "标准预览取样：" + fontPreviewState(first));
            measurementCase = "real-settings-preview-font100";
            checkText(fontPreview(first));
            return null;
        });
        for (int index : new int[]{0, 2, 3}) {
            final int choice = index;
            scrollToFont(first, choice);
            onMain(() -> {
                Log.i(TAG, "点击字号前，目标=" + choice + "：" + fontPreviewState(first));
                chooseFont(first, choice);
                assertTrue("选择字号不能关闭预览弹窗", first.formDialog.isShowing());
                assertTrue("选择字号应更新当前档位",
                        ((TextView) required(fontDialogRoot(first), "settings_font_current")).getText().toString()
                                .contains(first.getResources().getStringArray(R.array.app_font_size_descriptions)[choice]));
                assertEquals("预览不能提前保存字号", 1, AppFontSettings.readIndex(app));
                Log.i(TAG, "点击字号后，目标=" + choice + "：" + fontPreviewState(first));
                return null;
            });
            awaitStableFontPreview(first, "选档 " + choice + " 后布局");
            onMain(() -> {
                measurementCase = "real-settings-preview-font" + Math.round(AppFontSettings.scaleFor(choice) * 100);
                checkText(fontPreview(first));
                return null;
            });
        }
        onMain(() -> {
            Log.i(TAG, "超大预览验收，标准=" + standardPreview + "：" + fontPreviewState(first));
            assertTrue("大号预览必须真的增大文字；标准=" + standardPreview + "，" + fontPreviewState(first),
                    fontPreview(first).getTextSize() > standardPreview);
            required(fontDialogRoot(first), "settings_font_cancel").performClick();
            assertEquals("取消预览不能保存草稿", 1, AppFontSettings.readIndex(app));
            required(first, "settings_font_current").performClick();
            return null;
        });
        awaitStableFontPreview(first, "取消后重新打开");
        scrollToFont(first, 3);
        onMain(() -> {
            assertEquals("取消后重新打开应恢复保存档位", standardPreview,
                    fontPreview(first).getTextSize(), .01f);
            chooseFont(first, 3);
            first.onConfigurationChanged(new Configuration(first.getResources().getConfiguration()));
            assertTrue("页面重排应关闭未应用的预览", first.formDialog == null || !first.formDialog.isShowing());
            assertEquals("页面重排不能应用未保存草稿", 1, AppFontSettings.readIndex(app));
            first.recreate();
            return null;
        });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) != null
                && current(SystemSettingsActivity.class) != first && current(SystemSettingsActivity.class).loaded), "草稿重建应完成");
        SystemSettingsActivity draftRestored = onMain(() -> current(SystemSettingsActivity.class));
        onMain(() -> {
            required(draftRestored, "settings_font_current").performClick();
            return null;
        });
        awaitStableFontPreview(draftRestored, "重建后重新打开");
        onMain(() -> {
            assertEquals("重建后应使用保存的标准档", standardPreview,
                    fontPreview(draftRestored).getTextSize(), .01f);
            required(fontDialogRoot(draftRestored), "settings_font_apply").performClick();
            return null;
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertSame("应用相同字号无需重建页面", draftRestored, current(SystemSettingsActivity.class));
            assertTrue("应用后应关闭弹窗", draftRestored.formDialog == null || !draftRestored.formDialog.isShowing());
            required(draftRestored, "settings_font_current").performClick();
            return null;
        });
        awaitStableFontPreview(draftRestored, "再次打开并保存特大档");
        scrollToFont(draftRestored, 3);
        onMain(() -> {
            chooseFont(draftRestored, 3);
            required(fontDialogRoot(draftRestored), "settings_font_apply").performClick();
            return null;
        });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) != null
                && current(SystemSettingsActivity.class) != draftRestored
                && current(SystemSettingsActivity.class).uiReady()
                && !current(SystemSettingsActivity.class).getWindow().getDecorView().isLayoutRequested()
                && Math.abs(current(SystemSettingsActivity.class).getResources().getConfiguration().fontScale
                - AppFontSettings.scaleFor(3)) < .001f), "应用后当前页面应使用特大字号配置");
        assertEquals("应用字号应持久保存", 3, AppFontSettings.readIndex(app));
        SystemSettingsActivity appliedSettings = onMain(() -> current(SystemSettingsActivity.class));
        onMain(() -> {
            measurementCase = "real-system-settings-font130";
            View root = appliedSettings.getWindow().getDecorView();
            assertFontEntryOnly(root);
            checkTree(root, root, false);
            captureXml(root);
            return null;
        });
        captureScreen("real-system-settings-font130-screen");
        onMain(() -> { current(SystemSettingsActivity.class).findViewById(R.id.header_home).performClick(); return null; });
        await(() -> onMain(() -> current(HomeActivity.class) != null
                && current(HomeActivity.class).loaded
                && Math.abs(current(HomeActivity.class).getResources().getConfiguration().fontScale
                - AppFontSettings.scaleFor(3)) < .001f), "返回已有首页应应用新字号");
        onMain(() -> {
            HomeActivity refreshed = current(HomeActivity.class);
            assertTrue("返回首页的真实文字应增大", ((TextView) refreshed.findViewById(R.id.home_start)).getTextSize() > standardHomeText);
            refreshed.recreate();
            return null;
        });
        await(() -> onMain(() -> current(HomeActivity.class) != null && current(HomeActivity.class).loaded
                && Math.abs(current(HomeActivity.class).getResources().getConfiguration().fontScale
                - AppFontSettings.scaleFor(3)) < .001f), "重新创建首页仍应使用保存字号");
        assertEquals("页面字体重建不能重复初始化共享 SDK", 1, initCount.get());
        assertGeometryPasses();
    }

    @Test public void fontDialogKeepsPreviewAndActionsReachableInBothOrientationsAtLargestFont() throws Exception {
        assertTrue(device.edit().putInt(AppFontSettings.KEY, 3).commit());
        launchHome();
        onMain(() -> { current(HomeActivity.class).navigate("settings", 7); return null; });
        await(() -> onMain(() -> current(SystemSettingsActivity.class) != null
                && current(SystemSettingsActivity.class).loaded), "系统设置应读取完成");
        int originalOrientation = onMain(() -> current(SystemSettingsActivity.class).getRequestedOrientation());
        try {
            for (int requested : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                int expected = requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                onMain(() -> { current(SystemSettingsActivity.class).setRequestedOrientation(requested); return null; });
                await(() -> onMain(() -> {
                    SystemSettingsActivity activity = current(SystemSettingsActivity.class);
                    if (activity == null || !activity.loaded) return false;
                    View host = activity.getWindow().getDecorView();
                    return activity.getResources().getConfiguration().orientation == expected
                            && (expected == Configuration.ORIENTATION_PORTRAIT
                            ? host.getHeight() > host.getWidth() : host.getWidth() > host.getHeight())
                            && !host.isLayoutRequested();
                }), "字号弹窗应在真实横竖屏窗口中验证");
                SystemSettingsActivity activity = onMain(() -> current(SystemSettingsActivity.class));
                onMain(() -> {
                    assertEquals("最大应用字号应保持生效", AppFontSettings.scaleFor(3),
                            activity.getResources().getConfiguration().fontScale, .001f);
                    required(activity, "settings_font_current").performClick();
                    return null;
                });
                awaitStableFontPreview(activity, "横竖屏字号弹窗打开");
                for (int index = 0; index < 4; index++) {
                    int choice = index;
                    scrollToFont(activity, choice);
                    onMain(() -> { chooseFont(activity, choice); return null; });
                    awaitStableFontPreview(activity, "真实方向下预览档位 " + choice);
                    onMain(() -> {
                        measurementCase = "real-font-dialog-" + expected + "-preview" + choice;
                        View root = fontDialogRoot(activity);
                        checkTree(root, root, false);
                        for (String name : new String[]{"settings_font_preview", "settings_font_cancel", "settings_font_apply"}) {
                            View action = required(root, name);
                            Rect visible = new Rect();
                            assertTrue("字号预览与操作应始终可见：" + name, action.getGlobalVisibleRect(visible));
                            assertEquals("字号预览与操作不可被纵向裁切：" + name, action.getHeight(), visible.height());
                        }
                        captureXml(root);
                        return null;
                    });
                }
                onMain(() -> {
                    required(fontDialogRoot(activity), "settings_font_cancel").performClick();
                    assertEquals("横竖屏试字号不能写入草稿", 3, AppFontSettings.readIndex(app));
                    return null;
                });
            }
        } finally {
            onMain(() -> {
                SystemSettingsActivity activity = current(SystemSettingsActivity.class);
                if (activity != null) activity.setRequestedOrientation(originalOrientation);
                return null;
            });
        }
        assertEquals("字号弹窗不能重复初始化共享 SDK", 1, initCount.get());
        assertGeometryPasses();
    }

    @Test public void newFontBaselineMapsLegacyChoicesAndPreservesPreviousLargeTextSize() throws Exception {
        Map<String, Object> initial = new HashMap<>(device.getAll());
        try {
            assertTrue(device.edit().remove(AppFontSettings.KEY).remove("app_font_size").commit());
            assertEquals("首次使用应默认新标准字号", AppFontSettings.DEFAULT_INDEX, AppFontSettings.readIndex(app));
            assertEquals("新标准应与原大号保持同样大小", 1.15f, AppFontSettings.scaleFor(1), .0001f);
            assertEquals("小号应为新标准的 85%", 1.15f * .85f, AppFontSettings.scaleFor(0), .0001f);
            assertEquals("大号应为新标准的 115%", 1.15f * 1.15f, AppFontSettings.scaleFor(2), .0001f);
            assertEquals("特大应为新标准的 130%", 1.15f * 1.3f, AppFontSettings.scaleFor(3), .0001f);
            for (int legacy = 0; legacy < 4; legacy++) {
                assertTrue(device.edit().remove(AppFontSettings.KEY).putInt("app_font_size", legacy).commit());
                int expected = new int[]{0, 0, 1, 2}[legacy];
                assertEquals("旧设备应映射最接近的视觉字号：" + legacy, expected, AppFontSettings.readIndex(app));
                assertFalse("读取兼容字号不应写盘", device.contains(AppFontSettings.KEY));
            }
            assertTrue(device.edit().remove(AppFontSettings.KEY).putInt("app_font_size", 2).commit());
            assertEquals("原大号设备升级后应显示新标准", 1, AppFontSettings.readIndex(app));
            assertEquals("原大号设备升级不能再放大字体", 1.15f,
                    AppFontSettings.wrap(app).getResources().getConfiguration().fontScale, .0001f);
            assertFalse("再次应用相同实际字号无需重建", AppFontSettings.save(app, 1));
            assertFalse("未改变字号无需写入新版偏好", device.contains(AppFontSettings.KEY));
            assertTrue("主动切换字号应保存新版偏好", AppFontSettings.save(app, 3));
            assertEquals(3, device.getInt(AppFontSettings.KEY, -1));
            assertTrue(device.edit().putInt("app_font_size", 0).commit());
            assertEquals("新版偏好应优先于残留旧值", 3, AppFontSettings.readIndex(app));
            for (int invalid : new int[]{-1, 4, Integer.MAX_VALUE}) {
                assertTrue(device.edit().remove(AppFontSettings.KEY).putInt("app_font_size", invalid).commit());
                assertEquals("坏的旧值应回到新标准", AppFontSettings.DEFAULT_INDEX, AppFontSettings.readIndex(app));
            }
            assertTrue(device.edit().remove(AppFontSettings.KEY).putString("app_font_size", "旧类型错误").commit());
            assertEquals("旧偏好类型错误应回到新标准", AppFontSettings.DEFAULT_INDEX, AppFontSettings.readIndex(app));
            assertTrue(device.edit().putInt("app_font_size", 0).putString(AppFontSettings.KEY, "新类型错误").commit());
            assertEquals("新版坏值应回到新标准，不能重用旧值", AppFontSettings.DEFAULT_INDEX, AppFontSettings.readIndex(app));
        } finally {
            restore(device, initial);
        }
    }

    @Test public void savedFontContextKeepsScreenQualifiersAndRejectsCorruptStoredValue() throws Exception {
        onMain(() -> {
            for (int[] size : WINDOWS) for (int index = 0; index < 4; index++) {
                device.edit().putInt(AppFontSettings.KEY, index).commit();
                Context base = configurationContext(size[0], size[1], 1f);
                Context wrapped = AppFontSettings.wrap(base);
                Configuration before = base.getResources().getConfiguration(), after = wrapped.getResources().getConfiguration();
                assertEquals(AppFontSettings.scaleFor(index), after.fontScale, .001f);
                assertEquals("字体不能改变屏幕宽度限定符", before.screenWidthDp, after.screenWidthDp);
                assertEquals("字体不能改变屏幕高度限定符", before.screenHeightDp, after.screenHeightDp);
                assertEquals("字体不能改变最小宽度限定符", before.smallestScreenWidthDp, after.smallestScreenWidthDp);
                assertEquals("字体不能改变横竖屏限定符", before.orientation, after.orientation);
                assertEquals("字体不能改变系统密度", before.densityDpi, after.densityDpi);
                assertEquals("字体不能改写原Context资源", 1f, before.fontScale, .001f);
                assertEquals("字体不能改变实际显示密度", base.getResources().getDisplayMetrics().density,
                        wrapped.getResources().getDisplayMetrics().density, .001f);
            }
            for (int invalid : new int[]{-1, 4, Integer.MAX_VALUE}) {
                device.edit().putInt(AppFontSettings.KEY, invalid).commit();
                assertEquals(1, AppFontSettings.readIndex(app));
                assertEquals(AppFontSettings.scaleFor(AppFontSettings.DEFAULT_INDEX),
                        AppFontSettings.wrap(app).getResources().getConfiguration().fontScale, .001f);
            }
            device.edit().putString(AppFontSettings.KEY, "历史错误类型").commit();
            assertEquals("错误类型偏好应回到标准档", 1, AppFontSettings.readIndex(app));
            device.edit().putInt(AppFontSettings.KEY, 1).commit();
            assertFalse("相同档位不应触发多余重建", AppFontSettings.save(app, 1));
            return null;
        });
    }


    @Test public void realOrientationChangesUpdateScreenQualifiersWhileKeepingSavedFontSize() throws Exception {
        assertTrue(device.edit().putInt(AppFontSettings.KEY, 3).commit());
        launchHome();
        int originalOrientation = onMain(() -> current(HomeActivity.class).getRequestedOrientation());
        try {
            for (int requested : new int[]{ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}) {
                int expected = requested == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                onMain(() -> { current(HomeActivity.class).setRequestedOrientation(requested); return null; });
                AtomicReference<String> previousState = new AtomicReference<>();
                // 必须等待系统真的旋转窗口；桌面模式忽略方向请求时不能伪造方向验收。
                await(() -> onMain(() -> {
                    HomeActivity home = current(HomeActivity.class);
                    if (home == null) {
                        String state = "方向请求=" + requested + "，前台首页=null";
                        if (!state.equals(previousState.getAndSet(state))) Log.i(TAG, state);
                        return false;
                    }
                    View window = home.getWindow().getDecorView();
                    Configuration configuration = home.getResources().getConfiguration();
                    Configuration baseConfiguration = home.getBaseContext().getResources().getConfiguration();
                    String state = "方向请求=" + requested + "，实际请求=" + home.getRequestedOrientation()
                            + "，配置方向=" + configuration.orientation + "，配置尺寸=" + configuration.screenWidthDp
                            + "x" + configuration.screenHeightDp + "，窗口px=" + window.getWidth() + "x" + window.getHeight()
                            + "，已载=" + home.loaded + "，等待布局=" + window.isLayoutRequested()
                            + "，焦点=" + window.hasWindowFocus() + "，字体=" + configuration.fontScale
                            + "，wide=" + home.getResources().getBoolean(R.bool.mc_wide)
                            + "，compact=" + home.getResources().getBoolean(R.bool.mc_compact)
                            + "，base方向=" + baseConfiguration.orientation + "，base尺寸=" + baseConfiguration.screenWidthDp
                            + "x" + baseConfiguration.screenHeightDp;
                    if (!state.equals(previousState.getAndSet(state))) Log.i(TAG, state);
                    boolean shape = expected == Configuration.ORIENTATION_PORTRAIT
                            ? window.getHeight() > window.getWidth() : window.getWidth() > window.getHeight();
                    return home.loaded && configuration.orientation == expected && shape && !window.isLayoutRequested();
                }), "系统必须真实切换到 " + (expected == Configuration.ORIENTATION_PORTRAIT ? "竖屏" : "横屏"));
                onMain(() -> {
                    HomeActivity home = current(HomeActivity.class);
                    Configuration configuration = home.getResources().getConfiguration();
                    assertEquals("真正旋转后应保留应用字号", AppFontSettings.scaleFor(3), configuration.fontScale, .001f);
                    assertEquals("方向对应的宽高限定符不能锁在初始窗口", expected == Configuration.ORIENTATION_LANDSCAPE,
                            configuration.screenWidthDp > configuration.screenHeightDp);
                    assertEquals("宽屏资源应随实际窗口切换", expected == Configuration.ORIENTATION_LANDSCAPE
                            || configuration.screenWidthDp >= 640, home.getResources().getBoolean(R.bool.mc_wide));
                    assertEquals("紧凑资源应随真实高度切换", configuration.screenHeightDp < 520,
                            home.getResources().getBoolean(R.bool.mc_compact));
                    assertEquals("真正旋转后保存档位不能丢失", 3, AppFontSettings.readIndex(home));
                    return null;
                });
            }
            assertEquals("真实方向变化不能重复初始化共享 SDK", 1, initCount.get());
        } finally {
            onMain(() -> { HomeActivity home = current(HomeActivity.class);
                if (home != null) home.setRequestedOrientation(originalOrientation); return null; });
        }
    }

    @Test public void majorPageAndDataRowXmlFitsFourFontSizesAcrossPhoneAndTabletWindows() throws Exception {
        String pageScope = InstrumentationRegistry.getArguments().getString("fontPages", "");
        boolean filtersOnly = "filters".equals(pageScope);
        boolean listsOnly = "lists".equals(pageScope);
        int[] pages = listsOnly ? new int[]{R.layout.activity_staff, R.layout.activity_records, R.layout.view_departments_design}
                : filtersOnly ? new int[]{R.layout.activity_staff, R.layout.activity_records}
                : new int[]{R.layout.activity_staff, R.layout.activity_staff_edit,
                R.layout.view_departments_design, R.layout.activity_records, R.layout.activity_record_design, R.layout.activity_record_photo,
                R.layout.activity_lighting_settings, R.layout.activity_sdk_details,
                R.layout.activity_login, R.layout.activity_sdk_activation_scan};
        int[] rows = listsOnly ? new int[]{R.layout.item_staff_row, R.layout.item_record_row, R.layout.item_department_design}
                : filtersOnly ? new int[]{R.layout.item_staff_row, R.layout.item_record_row}
                : new int[]{R.layout.item_staff_row, R.layout.item_department_design, R.layout.item_record_row,
                R.layout.item_face_employee, R.layout.item_record_design_field, R.layout.item_license_design};
        onMain(() -> {
            for (int[] size : WINDOWS) for (int index = 0; index < 4; index++) {
                Context context = configurationContext(size[0], size[1], AppFontSettings.scaleFor(index));
                for (int resource : pages) {
                    measurementCase = caseLabel(context, resource, index, size);
                    try {
                        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(resource, null);
                        preparePage(root, resource, context);
                        measure(root, size[0], size[1]);
                        checkTree(root, root, false);
                        if ((index == 1 || index == 3) && (resource == R.layout.activity_staff
                                || resource == R.layout.activity_records
                                || resource == R.layout.view_departments_design
                                || resource == R.layout.activity_record_design)) captureXml(root);
                        if (resource == R.layout.activity_staff_edit) {
                            for (String group : new String[]{"staff_edit_group_name", "staff_edit_group_number",
                                    "staff_edit_group_department", "staff_edit_group_health"}) {
                                for (String other : new String[]{"staff_edit_group_name", "staff_edit_group_number",
                                        "staff_edit_group_department", "staff_edit_group_health"})
                                    required(root, other).setVisibility(group.equals(other) ? View.VISIBLE : View.GONE);
                                measure(root, size[0], size[1]);
                                checkTree(root, root, false);
                            }
                        }
                    } catch (Throwable failure) { issue(null, "布局构建失败：" + failure); }
                }
                for (int resource : rows) {
                    measurementCase = caseLabel(context, resource, index, size);
                    try {
                        ViewGroup row = (ViewGroup) LayoutInflater.from(context).inflate(resource, null);
                        prepareValues(row);
                        int width = Math.round((size[0] - 40) * context.getResources().getDisplayMetrics().density);
                        // 窄屏数据表允许横向查看，独立行应使用真实表格的最小宽度，而不是页面视口宽度。
                        int tableResource = resource == R.layout.item_staff_row ? R.layout.activity_staff
                                : resource == R.layout.item_record_row ? R.layout.activity_records
                                : resource == R.layout.item_department_design ? R.layout.view_departments_design : 0;
                        if (tableResource != 0) {
                            ViewGroup page = (ViewGroup) LayoutInflater.from(context).inflate(tableResource, null);
                            String tableId = resource == R.layout.item_staff_row ? "staff_table_content"
                                    : resource == R.layout.item_record_row ? "records_table_content" : "department_table_content";
                            width = Math.max(width, required(page, tableId).getMinimumWidth());
                        }
                        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
                        checkTree(row, row, false);
                        if (resource == R.layout.item_record_row) {
                            TextView date = row.findViewById(R.id.record_row_date);
                            Layout dateLayout = date.getLayout();
                            int dateWidth = date.getWidth() - date.getCompoundPaddingLeft() - date.getCompoundPaddingRight();
                            if (dateLayout == null || dateLayout.getLineCount() != 1
                                    || dateLayout.getEllipsisCount(0) != 0 || dateLayout.getLineWidth(0) > dateWidth + 1)
                                issue(date, "完整日期和时间必须在同一行显示，不省略或裁切");
                        }
                        if ((index == 1 || index == 3) && (resource == R.layout.item_staff_row
                                || resource == R.layout.item_record_row)) captureXml(row);
                    } catch (Throwable failure) { issue(null, "数据行构建失败：" + failure); }
                }
            }
            return null;
        });
        assertGeometryPasses();
    }

    @Test public void currentDesignPagesKeepBusinessControlsReadableAtNewFontBaseline() throws Exception {
        boolean resultOnly = "result".equals(InstrumentationRegistry.getArguments().getString("fontPages", ""));
        int[] pages = resultOnly ? new int[]{R.layout.activity_result_design}
                : new int[]{R.layout.activity_home_design, R.layout.activity_check_design_ready,
                R.layout.activity_check_design_active, R.layout.activity_result_design, R.layout.activity_face_design};
        onMain(() -> {
            for (int[] size : WINDOWS) for (int index = 0; index < 4; index++) {
                Context context = configurationContext(size[0], size[1], AppFontSettings.scaleFor(index));
                for (int resource : pages) {
                    measurementCase = caseLabel(context, resource, index, size);
                    try {
                        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(resource, null);
                        prepareCurrentDesignPage(root, resource, context);
                        measure(root, size[0], size[1]);
                        checkTree(root, root, false);
                        captureXml(root);
                        if (resource == R.layout.activity_face_design) {
                            // 选择浮层由真实页面按需展开；分别测采集与选人，隐藏区不参与断言。
                            required(root, "enrollment_selection_overlay").setVisibility(View.VISIBLE);
                            required(root, "enrollment_selection").setVisibility(View.VISIBLE);
                            ((TextView) required(root, "enrollment_choose_staff")).setText("关闭选择");
                            measurementCase += "-selection";
                            measure(root, size[0], size[1]);
                            checkTree(root, root, false);
                            captureXml(root);
                        }
                    } catch (Throwable failure) { issue(null, "当前业务布局构建失败：" + failure); }
                }
            }
            return null;
        });
        assertGeometryPasses();
    }

    @Test public void deviceFormsKeepActionsReadableAtEveryFontSizeAndWindow() throws Exception {
        boolean systemOnly = "system".equals(InstrumentationRegistry.getArguments().getString("fontForms", ""));
        int[] regular = systemOnly ? new int[]{R.layout.view_system_design} : new int[]{R.layout.view_sdk_design, R.layout.settings_cloud,
                R.layout.view_license_design, R.layout.view_camera_design, R.layout.view_rules_design,
                R.layout.view_temperature_design, R.layout.view_system_design};
        int[] compact = systemOnly ? new int[]{R.layout.view_system_design} : new int[]{R.layout.view_sdk_design, R.layout.settings_cloud_compact,
                R.layout.view_license_design, R.layout.view_camera_design, R.layout.view_rules_design,
                R.layout.view_temperature_design, R.layout.view_system_design};
        List<int[]> windows = new ArrayList<>(Arrays.asList(WINDOWS));
        if (systemOnly) windows.add(new int[]{1920, 1080});
        onMain(() -> {
            for (int[] size : windows) for (int index = 0; index < 4; index++) {
                Context context = configurationContext(size[0], size[1], AppFontSettings.scaleFor(index));
                boolean useCompact = context.getResources().getBoolean(R.bool.mc_compact);
                for (int resource : useCompact ? compact : regular) {
                    measurementCase = caseLabel(context, resource, index, size);
                    try {
                        LayoutInflater inflater = LayoutInflater.from(context);
                        ViewGroup root = (ViewGroup) inflater.inflate(useCompact
                                ? R.layout.activity_devices_compact : R.layout.activity_devices, null);
                        preparePage(root, resource, context);
                        FrameLayout body = root.findViewById(R.id.settings_body);
                        if (resource != R.layout.settings_cloud && resource != R.layout.settings_cloud_compact) {
                            int inset = context.getResources().getDimensionPixelSize(R.dimen.dp_12);
                            root.findViewById(R.id.page_content).setPadding(inset, inset, inset, inset);
                            body.setPadding(0, 0, 0, 0);
                            body.setBackground(null);
                        }
                        body.addView(inflater.inflate(resource, body, false));
                        prepareValues(root);
                        prepareSpinners(root, context);
                        TextView fontLabel = root.findViewById(R.id.settings_font_label);
                        TextView fontValue = root.findViewById(R.id.settings_font_value);
                        if (fontLabel != null) fontLabel.setText(context.getResources()
                                .getStringArray(R.array.app_font_size_labels)[index]);
                        if (fontValue != null) fontValue.setText(new String[]{"85%  ›", "100%  ›", "115%  ›", "130%  ›"}[index]);
                        measure(root, size[0], size[1]);
                        checkTree(root, root, false);
                        if (resource == R.layout.view_sdk_design) {
                            required(root, "settings_sdk_local_panel").setVisibility(View.GONE);
                            required(root, "settings_sdk_lan_panel").setVisibility(View.VISIBLE);
                            measure(root, size[0], size[1]);
                            checkTree(root, root, false);
                        }
                        if (resource == R.layout.view_system_design) {
                            assertFontEntryOnly(root);
                            View capabilities = required(root, "settings_capabilities");
                            assertEquals("系统设置暂时隐藏 SDK 能力入口", View.GONE, capabilities.getVisibility());
                            assertFalse("隐藏的 SDK 能力入口不应显示", capabilities.isShown());
                        }
                        if (index == 1 || index == 3) captureXml(root);
                    } catch (Throwable failure) { issue(null, "设置表单构建失败：" + failure); }
                }
                measurementCase = caseLabel(context, R.layout.activity_place_settings, index, size);
                try {
                    ViewGroup place = (ViewGroup) LayoutInflater.from(context)
                            .inflate(R.layout.activity_place_settings, null);
                    preparePage(place, R.layout.activity_place_settings, context);
                    measure(place, size[0], size[1]);
                    checkTree(place, place, false);
                    if (index == 1 || index == 3) captureXml(place);
                } catch (Throwable failure) { issue(null, "场所页面构建失败：" + failure); }
            }
            return null;
        });
        assertGeometryPasses();
    }



    private void launchHome() {
        homeScenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        await(() -> onMain(() -> current(HomeActivity.class) != null && current(HomeActivity.class).loaded
                && app.sdkSession().viewState().ready), "首页应连接只读 SDK 夹具");
        instrumentation.waitForIdleSync();
        homeScenario.onActivity(activity -> {
            assertHomePlace(activity.findViewById(R.id.main_root), activity.currentShopName());
            assertTrue("首页提供补光配置入口", activity.findViewById(R.id.home_lighting).isShown());
        });
    }

    private Context configurationContext(int width, int height, float fontScale) {
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.screenWidthDp = width;
        configuration.screenHeightDp = height;
        configuration.smallestScreenWidthDp = Math.min(width, height);
        configuration.orientation = width > height ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = fontScale;
        return new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
    }

    private void preparePage(ViewGroup root, int layout, Context context) {
        prepareHeader(root, layout, context);
        prepareValues(root);
        if (layout == R.layout.activity_records)
            ((TextView) required(root, "records_date_filter")).setText("日期范围");
        prepareSpinners(root, context);
    }

    /** 使用当前页面实际页头；现场晨检独立页头不具备返回与关于设备控件。 */
    private void prepareHeader(ViewGroup root, int layout, Context context) {
        boolean home = layout == R.layout.activity_home_design;
        boolean phone = context.getResources().getBoolean(R.bool.mc_phone);
        TextView title = root.findViewById(R.id.header_page_title);
        if (optional(root, "header_leading") != null) {
            if (home) {
                TextView place = root.findViewById(R.id.header_shop);
                place.setVisibility(View.VISIBLE);
                place.setText("未设置场所");
                assertHomePlace(root, "");
            } else assertHiddenShopViews(root);
            TextView backLabel = root.findViewById(R.id.header_back_label);
            int backWidth = Math.round(backLabel.getTextSize() * 4)
                    + context.getResources().getDimensionPixelSize(R.dimen.dp_30);
            LinearLayout.LayoutParams leading = (LinearLayout.LayoutParams) required(root, "header_leading").getLayoutParams();
            leading.width = home ? ViewGroup.LayoutParams.WRAP_CONTENT : backWidth;
            leading.weight = 0;
            LinearLayout.LayoutParams trailing = (LinearLayout.LayoutParams) required(root, "header_trailing").getLayoutParams();
            trailing.width = home ? 0 : backWidth;
            trailing.weight = 0;
            required(root, "header_brand").setVisibility(home ? View.VISIBLE : View.GONE);
            required(root, "header_home").setVisibility(home ? View.GONE : View.VISIBLE);
            backLabel.setVisibility(home ? View.GONE : View.VISIBLE);
            TextView about = (TextView) required(root, "home_settings");
            about.setVisibility(home ? View.VISIBLE : View.GONE);
            about.setText("关于设备");
            required(root, "header_workspace").setVisibility(context.getResources().getBoolean(R.bool.mc_wide)
                    && !phone ? View.VISIBLE : View.GONE);
            title.setVisibility(home ? View.GONE : View.VISIBLE);
        }
        if (title != null) title.setText(pageTitle(layout));
    }

    private void prepareCurrentDesignPage(ViewGroup root, int layout, Context context) {
        prepareHeader(root, layout, context);
        prepareValues(root);
        if (layout == R.layout.activity_home_design) {
            ((TextView) required(root, "home_hero_detail")).setText("身份识别 · 体温 · 头部 · 手部");
            ((TextView) required(root, "home_start")).setText("→  进入晨检");
            ((TextView) required(root, "home_status_badge")).setText("客户端 v0.1.0\nSDK 0.1.1");
            ((TextView) required(root, "home_status_message")).setText("设备运行正常");
            int iconDp = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    && context.getResources().getConfiguration().screenWidthDp < 1100 ? 24 : 28;
            for (int id : new int[]{R.id.home_staff, R.id.home_records, R.id.home_connection,
                    R.id.home_lighting, R.id.home_authorization, R.id.home_camera,
                    R.id.home_items, R.id.home_temperature, R.id.home_system}) {
                View icon = ((ViewGroup) root.findViewById(id)).getChildAt(0);
                icon.getLayoutParams().width = Math.round(iconDp * context.getResources().getDisplayMetrics().density);
                icon.getLayoutParams().height = icon.getLayoutParams().width;
            }
        } else if (layout == R.layout.activity_check_design_ready) {
            ((TextView) required(root, "check_person")).setText("等待准备");
            ((TextView) required(root, "check_person_detail")).setText("");
            ((TextView) required(root, "check_hint")).setText("请先添加员工并录入人脸");
            ((TextView) required(root, "check_progress")).setText("识别到员工后自动开始");
            required(root, "check_temperature").setVisibility(View.GONE);
            required(root, "check_prompt_label").setVisibility(View.GONE);
            required(root, "check_ready_devices").setVisibility(View.VISIBLE);
        } else if (layout == R.layout.activity_check_design_active) {
            ((TextView) required(root, "check_prompt_label")).setText("检测提示");
            ((TextView) required(root, "check_hint")).setText("请翻转双手手心\n保持双手完整入镜");
            ((TextView) required(root, "check_progress")).setText("检查双手手心 · 步骤 4/5 · 连续采样 1/3");
            required(root, "check_temperature").setVisibility(View.GONE);
        } else if (layout == R.layout.activity_result_design) {
            ((TextView) required(root, "result_head")).setText("异常");
            ((TextView) required(root, "result_hand")).setText("异常");
            ((TextView) required(root, "result_evidence_caption")).setText("4 张照片");
        } else if (layout == R.layout.activity_face_design) {
            required(root, "enrollment_selection_overlay").setVisibility(View.GONE);
            required(root, "enrollment_selection").setVisibility(View.GONE);
            ((TextView) required(root, "enrollment_preview_status")).setText(R.string.face_enrollment_status_ready);
            required(root, "enrollment_preview_placeholder").setVisibility(View.GONE);
            ((TextView) required(root, "enrollment_faces_empty")).setText("该员工暂无人脸样本");
            ((TextView) required(root, "enrollment_page_info")).setText("1 / 12");
        }
    }

    private static void assertHomePlace(View root, String name) {
        TextView place = root.findViewById(R.id.header_shop);
        assertNotNull("首页应展示场所名称", place);
        assertEquals("首页场所名称应可见", View.VISIBLE, place.getVisibility());
        assertEquals("首页采用当前场所名称，未配置时显示占位", name.isEmpty() ? "未设置场所" : name,
                place.getText().toString());
        assertFalse("场所名称展示不应保留旧扫码导航监听", place.hasOnClickListeners());
        assertFalse("场所名称展示不能触发旧扫码导航", place.performClick());
    }

    private static void assertHiddenShopViews(View root) {
        for (int id : new int[]{R.id.header_shop}) {
            View entry = root.findViewById(id);
            if (entry == null) continue;
            assertEquals("字号矩阵中门店入口应隐藏且不占布局空间", View.GONE, entry.getVisibility());
            assertFalse("隐藏门店入口不能保留导航监听", entry.hasOnClickListeners());
            assertFalse("隐藏门店入口不能触发导航", entry.performClick());
        }
    }

    private String pageTitle(int layout) {
        if (layout == R.layout.activity_staff) return "员工管理";
        if (layout == R.layout.activity_staff_edit) return "编辑员工";
        if (layout == R.layout.view_departments_design) return "部门管理";
        if (layout == R.layout.activity_face_design) return "人脸录入";
        if (layout == R.layout.activity_check_design_active || layout == R.layout.activity_check_design_ready) return "晨检工作台";
        if (layout == R.layout.activity_result_design) return "检查结果";
        if (layout == R.layout.activity_records) return "晨检记录";
        if (layout == R.layout.activity_record_design) return "记录详情";
        if (layout == R.layout.activity_record_photo) return "采集照片";
        if (layout == R.layout.activity_lighting_settings) return "灯光管理";
        if (layout == R.layout.activity_sdk_details) return "设备详情";
        if (layout == R.layout.activity_place_settings) return "场所";
        return "系统设置";
    }

    private void prepareValues(View root) {
        String[][] values = {{"home_status_badge", "就绪"}, {"home_status_message", "晨检 SDK 已连接，设备可用"},
                {"staff_row_name", LONG_NAME}, {"staff_row_number", LONG_NUMBER}, {"staff_row_department", LONG_DEPARTMENT},
                {"staff_row_health", "未过期 · 2032-02-29"}, {"staff_row_enabled", "启用中"}, {"staff_row_face", "已录人脸"},
                {"business_row_avatar", "欧"}, {"business_row_name", LONG_DEPARTMENT}, {"business_row_meta", "部门 · 128 名员工"},
                {"business_row_state", "编辑"}, {"staff_detail_name", LONG_NAME}, {"staff_detail_identity", LONG_NUMBER},
                {"staff_detail_department", LONG_DEPARTMENT}, {"staff_detail_health", "未过期 · 2032-02-29"},
                {"staff_detail_enabled", "启用中"}, {"staff_detail_face_state", "已登记 12 张人脸图片"},
                {"staff_edit_name", LONG_NAME}, {"staff_edit_number", LONG_NUMBER}, {"staff_edit_department_value", LONG_DEPARTMENT},
                {"staff_edit_health", "2032-02-29"}, {"check_person", LONG_NAME},
                {"enrollment_staff_name", LONG_NUMBER + "-" + LONG_NAME},
                {"check_person_detail", LONG_DEPARTMENT + " · 工号 " + LONG_NUMBER}, {"check_temperature", "体温 · 36.65°C"},
                {"check_progress", "正在检查手心"}, {"check_hint", "请将双手放在镜头前，检测完成后自动进入下一步"},
                {"result_title", "发现异常"},
                {"result_summary_hint", "请查看标注照片与异常明细"}, {"result_person", LONG_NAME + " · " + LONG_DEPARTMENT},
                {"result_date", "2032-02-29 08:12:34"}, {"result_temperature", "36.65 °C"},
                {"result_health", "2032-02-29"},
                {"result_findings", "手背：检测到戒指\n手心：检测到伤口\n头部：未佩戴口罩"},
                {"result_flow_state", "播报结束后下一位 · 可停留查看"},
                {"record_row_name", LONG_NAME}, {"record_row_number", LONG_NUMBER},
                {"record_row_department", LONG_DEPARTMENT}, {"record_row_result", "异常"},
                {"record_row_date", "2032-02-29 08:12"}, {"record_detail_name", LONG_NAME},
                {"record_detail_outcome", "异常"}, {"record_detail_staff_no", LONG_NUMBER},
                {"record_detail_date", "2032-02-29 08:12:34"}, {"record_detail_department", LONG_DEPARTMENT},
                {"record_detail_field_label", "员工工号"}, {"record_detail_field_value", LONG_NUMBER},
                {"photo_position", "12 / 12"}, {"photo_caption", "手背 · 已标注戒指位置"},
                {"staff_page_info", "第 12 / 345 页 · 共 6890 条"},
                {"department_page_info", "第 12 / 345 页 · 共 6890 条"},
                {"records_page_info", "第 12 / 345 页 · 共 6890 条"},
                {"staff_page_input", "345"}, {"department_page_input", "345"}, {"records_page_input", "345"},
                {"settings_version", "当前版本：0.1.0"}, {"settings_temperature_value", "36.65"},
                {"settings_shop_name", "中央厨房食品安全质量检查场所"}, {"place_name", "中央厨房食品安全质量检查场所"},
                {"place_phone", "+86 138 0013 8000"}, {"place_address", "测试市食品安全示范区中央厨房园区食品安全路 100 号一楼晨检场所"},
                {"settings_font_label", "特大"}, {"settings_font_value", "130%  ›"},
                {"settings_system_sdk_version", "0.1.1"}, {"settings_system_sdk_details", "查看详情  ›"},
                {"settings_temperature_reading", "持续读取中"}, {"settings_temperature_updated", "更新时间  08:15:32"}};
        for (String[] value : values) {
            View target = optional(root, value[0]);
            if (target instanceof TextView) ((TextView) target).setText(value[1]);
        }
        View personDetail = optional(root, "check_person_detail");
        if (personDetail != null) personDetail.setVisibility(View.VISIBLE);
    }

    private void prepareSpinners(View view, Context context) {
        if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            String name = resourceName(spinner);
            if (name.equals("staff_enabled_filter")) spinner.setAdapter(Ui.filterAdapter(context,
                    Arrays.asList(context.getResources().getStringArray(R.array.list_filter_staff_statuses)), "启用状态"));
            else if (name.equals("records_filter")) spinner.setAdapter(Ui.filterAdapter(context,
                    Arrays.asList(context.getResources().getStringArray(R.array.list_filter_record_results)), "结果"));
            else if (name.contains("department_filter")) spinner.setAdapter(Ui.filterAdapter(context,
                    Arrays.asList("全部部门", LONG_DEPARTMENT), "部门"));
            else spinner.setAdapter(Ui.spinnerAdapter(context, Arrays.asList("默认设备", "备用设备")));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            prepareSpinners(((ViewGroup) view).getChildAt(i), context);
    }

    private void measure(View view, int widthDp, int heightDp) {
        float density = view.getResources().getDisplayMetrics().density;
        measurePixels(view, Math.round(widthDp * density), Math.round(heightDp * density));
    }

    private void measurePixels(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private void checkTree(View root, View view, boolean dataViewport) {
        checkTree(root, view, dataViewport, dataViewport);
    }

    private void checkTree(View root, View view, boolean horizontalData, boolean verticalData) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof AbsListView) checkDataViewport((AbsListView) view);
        if (view instanceof RecyclerView) {
            checkDataViewport((RecyclerView) view);
            checkResultPhotoViewport((RecyclerView) view);
        }
        if (view instanceof HorizontalScrollView) checkHorizontalDataViewport((HorizontalScrollView) view);
        if (view instanceof AbsListView || view instanceof RecyclerView
                || view instanceof ScrollView || view instanceof HorizontalScrollView)
            checkBounds(root, view, horizontalData, verticalData);
        if (view instanceof TextView && ((TextView) view).length() > 0) {
            checkText((TextView) view);
            if (resourceName(view).equals("result_findings")) checkFindingViewport((TextView) view);
            if (!horizontalData || !verticalData) checkBounds(root, view, horizontalData, verticalData);
        } else if (view.isClickable() && (!horizontalData || !verticalData))
            checkBounds(root, view, horizontalData, verticalData);
        boolean listData = view instanceof AbsListView || view instanceof ScrollView;
        RecyclerView.LayoutManager recyclerLayout = view instanceof RecyclerView
                ? ((RecyclerView) view).getLayoutManager() : null;
        boolean childHorizontal = horizontalData || listData || view instanceof HorizontalScrollView
                || recyclerLayout != null && recyclerLayout.canScrollHorizontally();
        boolean childVertical = verticalData || listData
                || recyclerLayout != null && recyclerLayout.canScrollVertically();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            checkTree(root, ((ViewGroup) view).getChildAt(i), childHorizontal, childVertical);
    }

    private void checkHorizontalDataViewport(HorizontalScrollView viewport) {
        if (viewport.getChildCount() != 1) { issue(viewport, "横向数据视口必须只有一个表格内容容器"); return; }
        View table = viewport.getChildAt(0);
        int availableHeight = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
        if (table.getHeight() > availableHeight + 1)
            issue(viewport, "横向表格不能在纵向溢出数据视口：" + table.getHeight() + " > " + availableHeight);
        int availableWidth = viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight();
        if (table.getWidth() > availableWidth) {
            int oldX = viewport.getScrollX(), oldY = viewport.getScrollY();
            try {
                // 数据表允许内部横向移动；必须实际能移动到最后一列，不能只忽略越界。
                int expected = table.getWidth() - availableWidth;
                viewport.scrollTo(expected, 0);
                if (viewport.getScrollX() != expected)
                    issue(viewport, "横向表格最后一列无法滚入视口：" + viewport.getScrollX() + " != " + expected);
            } finally { viewport.scrollTo(oldX, oldY); }
        }
    }

    private void checkFindingViewport(TextView findings) {
        Layout layout = findings.getLayout();
        if (layout == null || layout.getLineCount() == 0) return;
        ViewParent parent = findings.getParent();
        while (parent instanceof View && !(parent instanceof ScrollView)) parent = parent.getParent();
        if (!(parent instanceof ScrollView)) { issue(findings, "异常明细缺少独立数据视口"); return; }
        ScrollView viewport = (ScrollView) parent;
        int available = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
        int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        if (available < lineHeight) issue(findings, "异常明细视口不能完整显示一行：" + available + " < " + lineHeight);
    }

    private void checkResultPhotoViewport(RecyclerView viewport) {
        if (!resourceName(viewport).equals("result_evidence_grid")) return;
        int availableHeight = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
        int availableWidth = viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight();
        if (availableHeight <= 0 || availableWidth <= 0) {
            issue(viewport, "采集照片应有完整的浏览区域");
            return;
        }
        boolean landscape = viewport.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int columns = landscape ? 3 : 2;
        int gap = viewport.getResources().getDimensionPixelSize(R.dimen.dp_6);
        if (viewport.getLayoutManager() == null)
            viewport.setLayoutManager(landscape
                    ? new LinearLayoutManager(viewport.getContext(), RecyclerView.HORIZONTAL, false)
                    : new GridLayoutManager(viewport.getContext(), 2));
        View row = LayoutInflater.from(viewport.getContext()).inflate(R.layout.item_result_photo, viewport, false);
        int expectedHeight = landscape ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (row.getLayoutParams().height != expectedHeight)
            issue(viewport, "横屏照片应适应视口高度，竖屏照片应按自然高度排列");
        TextView label = row.findViewById(R.id.result_photo_label);
        label.setText("手背检查 · 戒指");
        int rowWidth = Math.max(1, (availableWidth - gap * (columns - 1)) / columns);
        if (landscape) measurePixels(row, rowWidth, availableHeight);
        else {
            row.measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        }
        checkTree(row, row, false);
        View image = row.findViewById(R.id.result_photo_image);
        if (image.getHeight() <= 0) issue(viewport, "照片标题不能挤占全部图片高度");
        View root = viewport.getRootView();
        TextView findingsTitle = root.findViewById(R.id.result_findings_title);
        TextView photosTitle = (TextView) ((ViewGroup) required(root, "result_evidence_caption").getParent()).getChildAt(0);
        if (Math.abs(findingsTitle.getTextSize() - photosTitle.getTextSize()) > .5f)
            issue(findingsTitle, "异常明细与采集照片标题字号应一致");
        if (findingsTitle.getCurrentTextColor() != photosTitle.getCurrentTextColor())
            issue(findingsTitle, "异常明细与采集照片标题颜色应一致");
    }

    private void checkDataViewport(ViewGroup viewport) {
        String name = resourceName(viewport), label;
        int rowLayout;
        switch (name) {
            case "result_evidence_grid": rowLayout = R.layout.item_result_photo; label = "result_photo_label"; break;
            case "staff_list": rowLayout = R.layout.item_staff_row; label = "staff_row_name"; break;
            case "department_list": rowLayout = R.layout.item_department_design; label = "business_row_name"; break;
            case "records_list": rowLayout = R.layout.item_record_row; label = "record_row_name"; break;
            case "enrollment_staff_list": rowLayout = R.layout.item_face_employee; label = "business_row_name"; break;
            case "enrollment_faces": rowLayout = R.layout.item_face_sample; label = "face_sample_uid"; break;
            case "record_detail_fields": rowLayout = R.layout.item_record_design_field; label = "record_detail_field_label"; break;
            case "sdk_detail_fields": rowLayout = R.layout.item_record_design_field; label = "record_detail_field_label"; break;
            case "settings_license_fields": rowLayout = R.layout.item_license_design; label = "record_detail_field_label"; break;
            default: return;
        }
        // 只要求至少能看清一条关键信息；数据列表和照片网格仍可按正常业务滚动。
        if (viewport instanceof RecyclerView && ((RecyclerView) viewport).getLayoutManager() == null) {
            RecyclerView list = (RecyclerView) viewport;
            if (name.equals("enrollment_staff_list"))
                list.setLayoutManager(new GridLayoutManager(viewport.getContext(),
                        viewport.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 3 : 2));
            else BusinessListUi.bind(list);
        }
        View row = LayoutInflater.from(viewport.getContext()).inflate(rowLayout, viewport, false);
        TextView key = (TextView) required(row, label);
        key.setText("检查数据");
        int columns = viewport instanceof GridView ? Math.max(1, ((GridView) viewport).getNumColumns()) : 1;
        if (viewport instanceof RecyclerView
                && ((RecyclerView) viewport).getLayoutManager() instanceof GridLayoutManager)
            columns = ((GridLayoutManager) ((RecyclerView) viewport).getLayoutManager()).getSpanCount();
        int width = Math.max(1, (viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight()) / columns);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        Layout layout = key.getLayout();
        if (layout == null || layout.getLineCount() == 0) { issue(viewport, "无法测得列表关键数据行高"); return; }
        int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        int available = viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom();
        if (available < lineHeight) issue(viewport, "数据视口不能完整显示一行关键信息：" + available + " < " + lineHeight);
    }

    private void checkBounds(View root, View view) {
        checkBounds(root, view, false, false);
    }

    private void checkBounds(View root, View view, boolean horizontalData, boolean verticalData) {
        Rect bounds = new Rect(); view.getDrawingRect(bounds);
        if (view != root && root instanceof ViewGroup) ((ViewGroup) root).offsetDescendantRectToMyCoords(view, bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) issue(view, "没有可操作尺寸 " + bounds);
        else if ((!horizontalData && (bounds.left < -1 || bounds.right > root.getWidth() + 1))
                || (!verticalData && (bounds.top < -1 || bounds.bottom > root.getHeight() + 1)))
            issue(view, "超出固定页面 " + bounds + " / " + root.getWidth() + "x" + root.getHeight());
    }

    private void checkText(TextView view) {
        Layout layout = view.getLayout();
        if (layout == null) { issue(view, "没有 Android 文字排版结果"); return; }
        int availableHeight = view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom();
        if (!(view instanceof EditText) && layout.getHeight() > availableHeight + 1)
            issue(view, "文字纵向截断：" + layout.getHeight() + " > " + availableHeight);
        // 长姓名、工号、部门和证书值允许按原控件规则省略；操作名称必须完整。
        if (isOperationLabel(view)) {
            for (int line = 0; line < layout.getLineCount(); line++) if (layout.getEllipsisCount(line) > 0)
                issue(view, "操作文字被省略：" + view.getText());
            int availableWidth = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
            for (int line = 0; line < layout.getLineCount(); line++) if (layout.getLineWidth(line) > availableWidth + 1)
                issue(view, "操作文字横向截断：" + view.getText());
        }
    }

    private boolean isOperationLabel(TextView view) {
        if (view.getLabelFor() != View.NO_ID) return true;
        String name = resourceName(view);
        if (name.startsWith("staff_row_") || name.startsWith("record_row_")
                || name.equals("business_row_name") || name.equals("business_row_meta")
                || name.equals("business_row_avatar") || name.equals("enrollment_staff_name")
                || name.equals("record_detail_field_value")) return false;
        if (name.equals("header_shop") || name.equals("staff_edit_department_value")
                || name.equals("staff_edit_health") || view instanceof EditText) return false;
        if (view.isClickable()) return true;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            // 部门名和设备名是选择器的动态数据；可省略，触摸目标和文字高度仍分别检查。
            if (parent instanceof Spinner) return false;
            if (((View) parent).isClickable() && !(parent instanceof AbsListView)) return true;
        }
        return false;
    }

    private void issue(View view, String problem) {
        String entry = measurementCase + " / " + (view == null ? "root" : resourceName(view)) + " / " + problem;
        if (geometryFailures.add(entry)) Log.e(TAG, entry);
    }

    private void assertGeometryPasses() {
        if (!geometryFailures.isEmpty()) fail("字号尺寸矩阵发现 " + geometryFailures.size() + " 项问题：\n"
                + android.text.TextUtils.join("\n", geometryFailures));
    }

    private String caseLabel(Context context, int resource, int index, int[] size) {
        return context.getResources().getResourceEntryName(resource) + "-" + size[0] + "x" + size[1]
                + "-font" + Math.round(AppFontSettings.scaleFor(index) * 100);
    }

    private void assertReadablePopupRole(TextView view, boolean title) {
        assertNotNull("弹窗应保留关键文字", view);
        // Android 14 起大字号使用非线性缩放，必须用对应的逆转换还原 sp。
        float sizeSp = android.os.Build.VERSION.SDK_INT >= 34
                ? TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, view.getTextSize(),
                        view.getResources().getDisplayMetrics())
                : view.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
        assertTrue("弹窗的局部主题应保持可读字号，实际 " + sizeSp + "sp",
                sizeSp >= (title ? 17f : 15f) && sizeSp <= (title ? 20f : 18f));
    }

    private void captureXml(View root) throws Exception {
        File directory = new File(app.getCacheDir(), "font-size-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(directory, measurementCase + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { bitmap.recycle(); }
    }

    private void captureScreen(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("应能获取真实前台页面截图", bitmap);
        File directory = new File(app.getCacheDir(), "font-size-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }


    private void awaitStableFontPreview(SystemSettingsActivity activity, String phase) {
        instrumentation.waitForIdleSync();
        AtomicReference<String> previous = new AtomicReference<>();
        AtomicInteger stable = new AtomicInteger();
        await(() -> onMain(() -> {
            if (activity.formDialog == null || !activity.formDialog.isShowing()) return false;
            TextView preview = fontPreview(activity);
            String state = fontPreviewState(activity);
            if (!state.equals(previous.get())) {
                Log.i(TAG, "预览等待 " + phase + "：" + state);
                previous.set(state);
                stable.set(0);
            } else stable.incrementAndGet();
            return current(SystemSettingsActivity.class) == activity && activity.uiReady()
                    && preview.isAttachedToWindow() && preview.isShown()
                    && preview.getWidth() > 0 && preview.getHeight() > 0
                    && preview.getLayout() != null && !preview.isLayoutRequested()
                    && !fontDialogRoot(activity).isLayoutRequested() && stable.get() >= 3;
        }), "字号预览应完成真实布局：" + phase);
    }

    /** 诊断只读取实际页面和独立资源配置，不修改预览文字或产品状态。 */
    private String fontPreviewState(SystemSettingsActivity activity) {
        TextView preview = fontPreview(activity);
        Configuration applied = activity.getResources().getConfiguration();
        int saved = AppFontSettings.readIndex(activity);
        String draft = ((TextView) required(fontDialogRoot(activity), "settings_font_current")).getText().toString();
        return "保存=" + saved + "，草稿=" + draft
                + "，应用fontScale=" + applied.fontScale
                + "，textSize=" + preview.getTextSize()
                + "，标准预览px=" + SystemSettingsActivity.previewTextSize(activity, 1)
                + "，尺寸=" + applied.screenWidthDp + "x" + applied.screenHeightDp
                + "，sw=" + applied.smallestScreenWidthDp + "，density=" + applied.densityDpi
                + "，activity=" + System.identityHashCode(activity)
                + "，form=" + System.identityHashCode(activity.formView)
                + "，preview=" + System.identityHashCode(preview)
                + "，attached=" + preview.isAttachedToWindow() + "，shown=" + preview.isShown()
                + "，bounds=" + preview.getWidth() + "x" + preview.getHeight()
                + "，layoutRequested=" + preview.isLayoutRequested()
                + "，lifecycle=" + activity.getLifecycle().getCurrentState()
                + "，foreground=" + activity.foreground
                + "，loaded=" + activity.loaded + "，ready=" + activity.uiReady();
    }

    private static View fontDialogRoot(SystemSettingsActivity activity) {
        assertNotNull("字号入口应打开弹窗", activity.formDialog);
        assertTrue("字号弹窗应保持显示", activity.formDialog.isShowing());
        assertNotNull("字号弹窗应有窗口", activity.formDialog.getWindow());
        return activity.formDialog.getWindow().getDecorView();
    }

    private static TextView fontPreview(SystemSettingsActivity activity) {
        return (TextView) required(fontDialogRoot(activity), "settings_font_preview");
    }

    private void scrollToFont(SystemSettingsActivity activity, int index) throws Exception {
        onMain(() -> {
            ((ListView) required(fontDialogRoot(activity), "settings_font_options")).setSelection(index);
            return null;
        });
        await(() -> onMain(() -> {
            ListView options = (ListView) required(fontDialogRoot(activity), "settings_font_options");
            View row = options.getChildAt(index - options.getFirstVisiblePosition());
            return row != null && row.isShown() && !options.isLayoutRequested();
        }), "四档字号应能通过列表滚动选择");
    }

    private static void chooseFont(SystemSettingsActivity activity, int index) {
        ListView options = (ListView) required(fontDialogRoot(activity), "settings_font_options");
        assertEquals("字号弹窗应提供四档", 4, options.getCount());
        int position = index - options.getFirstVisiblePosition();
        View row = options.getChildAt(position);
        assertNotNull("字号选项应可见：" + index, row);
        Rect visible = new Rect();
        assertTrue("字号选项应能滚入视口：" + index, row.getGlobalVisibleRect(visible));
        assertEquals("字号列表至少应完整容纳被选择的一项：" + index, row.getHeight(), visible.height());
        assertTrue("字号选项必须执行真实点击", options.performItemClick(row, index,
                options.getAdapter().getItemId(index)));
    }

    private static void assertFontEntryOnly(View root) {
        View entry = required(root, "settings_font_current");
        assertEquals("系统设置应保留字号入口", View.VISIBLE, entry.getVisibility());
        for (String name : new String[]{"settings_font_preview", "settings_font_apply", "settings_font_small",
                "settings_font_default", "settings_font_large", "settings_font_largest"})
            assertNull("系统设置页不再内嵌字号预览与操作：" + name, optional(root, name));
        View cloud = required(root, "settings_business_cloud");
        assertFalse("系统设置暂时隐藏业务云服务地址入口", cloud.isShown());
        assertEquals("业务云连接区域隐藏后不能占位", View.GONE, ((View) cloud.getParent()).getVisibility());
        View fontContainer = (View) entry.getParent();
        ViewGroup fontRow = (ViewGroup) fontContainer.getParent();
        assertEquals("字号区域应从该行内容左侧开始", fontRow.getPaddingLeft(), fontContainer.getLeft());
        boolean landscape = ((LinearLayout) fontRow).getOrientation() == LinearLayout.HORIZONTAL;
        if (landscape) {
            View automatic = (View) required(root, "settings_auto_check_state").getParent().getParent();
            assertTrue("横屏字号区域应与自动晨检并排，不能独占整行", fontContainer.getRight() < automatic.getLeft());
            assertTrue("横屏字号与自动晨检应平分可用宽度",
                    Math.abs(fontContainer.getWidth() - automatic.getWidth()) <= 1);
        } else {
            assertEquals("竖屏字号区域应占满该行可用宽度",
                    fontRow.getWidth() - fontRow.getPaddingRight(), fontContainer.getRight());
        }
        assertEquals("字号入口应占满字号区域的可用宽度",
                fontContainer.getWidth() - fontContainer.getPaddingLeft() - fontContainer.getPaddingRight(), entry.getWidth());
        assertActionRightAligned(entry, required(root, "settings_font_value"));
        View sdk = required(root, "settings_system_sdk");
        assertActionRightAligned(sdk, required(root, "settings_system_sdk_details"));
        assertSystemEntryOrder(root, landscape);
    }

    private static void assertSystemEntryOrder(View root, boolean landscape) {
        String[] names = {"settings_version", "settings_system_sdk", "settings_shop_name",
                "settings_voice_state", "settings_font_current", "settings_auto_check_state",
                "settings_security_password"};
        Rect previous = null;
        for (int index = 0; index < names.length; index++) {
            View item = (View) required(root, names[index]).getParent();
            if (index == 3 || index == 5) item = (View) item.getParent();
            Rect bounds = new Rect();
            item.getDrawingRect(bounds);
            ((ViewGroup) root).offsetDescendantRectToMyCoords(item, bounds);
            if (previous != null) {
                if (landscape && index % 2 == 1) {
                    assertEquals("横屏系统设置应按顺序成对排列：" + names[index], previous.top, bounds.top);
                    assertTrue("横屏后一个设置应位于右侧：" + names[index], previous.right <= bounds.left);
                } else {
                    assertTrue("系统设置应依次为客户端、SDK、场所、语音、字号、自动晨检、安全密码：" + names[index],
                            previous.bottom <= bounds.top);
                }
            }
            previous = bounds;
        }
    }

    private static void assertActionRightAligned(View parent, View action) {
        Rect bounds = new Rect();
        action.getDrawingRect(bounds);
        ((ViewGroup) parent).offsetDescendantRectToMyCoords(action, bounds);
        assertEquals("设置入口的右侧操作应贴齐内容右边距：" + resourceName(action),
                parent.getWidth() - parent.getPaddingRight(), bounds.right);
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return view.getClass().getSimpleName();
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Resources.NotFoundException generatedId) {
            return view.getClass().getSimpleName() + "#" + Integer.toHexString(view.getId());
        }
    }

    private static View optional(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private static View required(View root, String name) {
        View view = optional(root, name); assertNotNull("缺少字号控件：" + name, view); return view;
    }

    private static View required(Activity activity, String name) { return required(activity.getWindow().getDecorView(), name); }

    private <T extends Activity> T current(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity) && !activity.isFinishing()) return type.cast(activity);
        return null;
    }

    private void finishPages() {
        for (Stage stage : Stage.values()) for (Activity activity : new ArrayList<>(
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private static void restore(SharedPreferences preferences, Map<String, Object> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else fail("不能还原未知偏好类型：" + key);
        }
        assertTrue(editor.commit());
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return value.get();
    }

    private void await(Callable<Boolean> condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (condition.call()) return; } catch (Exception error) { throw new AssertionError(error); }
            SystemClock.sleep(40);
        }
        fail(message);
    }
}
