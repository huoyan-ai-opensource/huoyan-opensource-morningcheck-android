package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Method;
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
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 使用独立数据库和本机 WS 夹具，验收分页控件、筛选及页面重建，不读写真实业务资料。 */
@RunWith(AndroidJUnit4.class)
public final class DataPaginationUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private final CopyOnWriteArrayList<String> sdkCommands = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final CountDownLatch delayedStaffRequest = new CountDownLatch(1);
    private final CountDownLatch finishStaffResponse = new CountDownLatch(1);
    private final AtomicInteger delayedStaffRequests = new AtomicInteger();
    private volatile boolean allowDelayedStaffQuery;
    private volatile JSONObject isolatedFaceDetail;
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private final ArrayList<String> testPhotoNames = new ArrayList<>();
    private ActivityScenario<? extends BaseMorningActivity> scenario;

    @Before public void isolateDatabaseAndConnections() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        onMain(() -> {
            assertFalse("不能中断操作员的门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        originalStore = app.store;
        databaseName = "pagination-ui-" + UUID.randomUUID() + ".db";
        // SQLiteOpenHelper 在不同 Android 版本调用不同重载，全部映射到这份 UUID 测试库。
        isolatedStore = new AppStore(new ContextWrapper(app) {
            @Override public File getDatabasePath(String name) { return app.getDatabasePath(databaseName); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        app.io.submit(() -> isolatedStore.getWritableDatabase()).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.store = isolatedStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (allowDelayedStaffQuery && "/cloud/staff/1/".equals(request.getPath())) {
                    if (!"pagination-test-token".equals(request.getHeader("X-USER-TOKEN"))) {
                        serverFailure.set(new AssertionError("本机云查询应携带配置的测试令牌"));
                        return new MockResponse().setResponseCode(401);
                    }
                    delayedStaffRequests.incrementAndGet();
                    delayedStaffRequest.countDown();
                    if (!finishStaffResponse.await(10, TimeUnit.SECONDS))
                        return new MockResponse().setResponseCode(504);
                    return new MockResponse().setBody(Jsons.obj("code", 0, "data",
                            Jsons.obj("id", 1, "name", "回执更新员工", "staff_no", "S1", "department", "回执更新部门")).toString());
                }
                if (request.getPath() == null || !request.getPath().startsWith("/sdk")) {
                    businessRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            sdkCommands.add(request.optString("cmd"));
                            JSONObject data = "getStatus".equals(request.optString("cmd"))
                                    ? Jsons.obj("sdk_state", "READY", "model_ready", true, "certificate_valid", true)
                                    : "userDetail".equals(request.optString("cmd")) && isolatedFaceDetail != null
                                            ? isolatedFaceDetail
                                    : new JSONObject();
                            socket.send(Jsons.obj("requestId", request.getString("requestId"), "code", 0,
                                    "reason", "分页本机测试夹具", "data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start(java.net.InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putString("face_camera", "usb:UI-DESIGN-NO-CAMERA")
                .putString("hand_camera", "usb:UI-DESIGN-NO-CAMERA")
                .putBoolean("voice", false).commit());
    }

    @After public void restoreDatabaseAndConfiguration() throws Exception {
        finishStaffResponse.countDown();
        try {
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> {
                finishPages();
                app.sdkSession().disconnect();
                return null;
            });
            instrumentation.waitForIdleSync();
            if (app != null) app.networkIo.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
        } finally {
            try {
                if (isolatedStore != null) app.io.submit(() -> {
                    for (String name : testPhotoNames) {
                        File photo = isolatedStore.photo(name);
                        assertTrue("仅清理本用例创建的照片", !photo.exists() || photo.delete());
                    }
                    return null;
                }).get(10, TimeUnit.SECONDS);
                if (originalStore != null) onMain(() -> { app.store = originalStore; return null; });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue(app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "分页测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("分页不能访问业务云", 0, businessRequests.get());
        assertEquals("普通页面和详情刷新不能查询暂停的云端托管", 0, sdkCommandCount("getCloudStatus"));
        assertEquals("隐藏入口不能提交云端托管开关", 0, sdkCommandCount("setCloudStatus"));
        assertNull(serverFailure.get());
    }



    @Test public void staffSearchPagesAndRestoresSameQueryAfterRecreation() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 55; i++) isolatedStore.saveStaff(Jsons.obj("id", i,
                    "name", (i <= 45 ? "分页员工 " : "其他员工 ") + i, "staff_no", "S" + i,
                    "department", i <= 45 ? "后厨" : "前厅", "created_at", i));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, StaffActivity.class));
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            assertTrue("仍可添加本机员工", page.findViewById(R.id.staff_add).isShown());
            // 部门由独立筛选器选择，搜索框按当前产品规则查询姓名或工号。
            ((EditText) page.findViewById(R.id.staff_search)).setText("分页员工");
            return null;
        });
        awaitStaffPage("第 1 / 2 页 · 共 45 条", 24);
        String firstId = onMain(() -> personAt(resumed(StaffActivity.class), 0).optString("id"));
        click(StaffActivity.class, R.id.staff_next);
        awaitStaffPage("第 2 / 2 页 · 共 45 条", 45);
        assertEquals("累计追加保留已加载的首条", firstId, onMain(() -> personAt(resumed(StaffActivity.class), 0).optString("id")));
        onMain(() -> {
            assertFalse(resumed(StaffActivity.class).findViewById(R.id.staff_next).isEnabled());
            return null;
        });
        scenario.recreate();
        awaitStaffPage("第 2 / 2 页 · 共 45 条", 45);
        onMain(() -> {
            assertEquals("分页员工", ((EditText) resumed(StaffActivity.class).findViewById(R.id.staff_search)).getText().toString());
            ((EditText) resumed(StaffActivity.class).findViewById(R.id.staff_search)).setText("不存在");
            return null;
        });
        awaitStaffPage("第 1 / 1 页 · 共 0 条", 0);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            assertTrue(page.findViewById(R.id.staff_empty).isShown());
            assertFalse(page.findViewById(R.id.staff_previous).isEnabled());
            return null;
        });
    }

    @Test public void staffFirstLastAndJumpValidateInputAndClampAfterRefresh() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 55; i++) isolatedStore.saveStaff(Jsons.obj("id", i,
                    "name", "跳页员工 " + i, "staff_no", "J" + i, "created_at", i));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, StaffActivity.class));
        awaitListTestOrientation(StaffActivity.class);
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        captureUi("staff-list-refined");
        assertPagerEdges(StaffActivity.class, R.id.staff_first, R.id.staff_previous,
                R.id.staff_next, R.id.staff_last, true, false);
        click(StaffActivity.class, R.id.staff_last);
        awaitStaffPage("第 3 / 3 页 · 共 55 条", 55);
        assertPagerEdges(StaffActivity.class, R.id.staff_first, R.id.staff_previous,
                R.id.staff_next, R.id.staff_last, false, true);
        click(StaffActivity.class, R.id.staff_first);
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "1");
        captureUi("list-page-inline-jump");
        rejectPageJump(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, R.id.staff_page_info, "");
        rejectPageJump(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, R.id.staff_page_info, "abc");
        rejectPageJump(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, R.id.staff_page_info, "0");
        rejectPageJump(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, R.id.staff_page_info, "4");
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            page.<EditText>findViewById(R.id.staff_page_input).setText("001");
            assertTrue(page.findViewById(R.id.staff_page_jump).performClick());
            assertTrue("提交当前页不应进入读取状态", page.findViewById(R.id.staff_page_input).isEnabled());
            return null;
        });
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "1");
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            page.<EditText>findViewById(R.id.staff_page_input).setText("2");
            // 重复绘制同一份列表不能替操作员提交页码或覆盖正在键入的草稿。
            Method render = StaffActivity.class.getDeclaredMethod("showStaffPage");
            render.setAccessible(true);
            render.invoke(page);
            assertEquals("未确认的页码草稿应保留", "2",
                    page.<EditText>findViewById(R.id.staff_page_input).getText().toString());
            return null;
        });
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        jumpToPage(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "2");
        awaitStaffPage("第 2 / 3 页 · 共 55 条", 48);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "2");
        assertEquals("跳页应保留首条并加载到第二页边界", 31L,
                onMain(() -> personAt(resumed(StaffActivity.class), 24).optLong("id")).longValue());
        scenario.recreate();
        awaitStaffPage("第 2 / 3 页 · 共 55 条", 48);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "2");
        click(StaffActivity.class, R.id.staff_first);
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            EditText input = page.findViewById(R.id.staff_page_input);
            input.setText("2");
            input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            assertFalse("IME 跳转与按钮确认应共用读取期间的禁用状态", input.isEnabled());
            return null;
        });
        awaitStaffPage("第 2 / 3 页 · 共 55 条", 48);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "2");
        onMain(() -> {
            resumed(StaffActivity.class).<EditText>findViewById(R.id.staff_search).setText("不存在的跳页员工");
            return null;
        });
        awaitStaffPage("第 1 / 1 页 · 共 0 条", 0);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            EditText input = page.findViewById(R.id.staff_page_input);
            assertEquals("从第二页筛选为空后，页码框不能残留旧页码", "1", input.getText().toString());
            assertFalse("空列表应禁用页码输入", input.isEnabled());
            assertFalse("空列表应禁用跳转按钮", page.findViewById(R.id.staff_page_jump).isEnabled());
            page.<EditText>findViewById(R.id.staff_search).setText("");
            return null;
        });
        awaitStaffPage("第 1 / 3 页 · 共 55 条", 24);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "1");
        click(StaffActivity.class, R.id.staff_last);
        awaitStaffPage("第 3 / 3 页 · 共 55 条", 55);
        app.io.submit(() -> {
            for (int i = 21; i <= 55; i++) isolatedStore.removeStaff(i);
        }).get(10, TimeUnit.SECONDS);
        // 用进程内实际资料变更通知触发刷新，验证末页消失后页码会收敛。
        onMain(() -> { app.notifyStaffChanged(); return null; });
        awaitStaffPage("第 1 / 1 页 · 共 20 条", 20);
        assertPagerEdges(StaffActivity.class, R.id.staff_first, R.id.staff_previous,
                R.id.staff_next, R.id.staff_last, true, true);
        assertInlinePageInput(StaffActivity.class, R.id.staff_page_input, R.id.staff_page_jump, "1");
        assertEquals(20L, onMain(() -> personAt(resumed(StaffActivity.class), 0).optLong("id")).longValue());
    }









    @Test public void staffEditorKeepsFocusedTabletFieldWhenWindowBecomesPhone() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, StaffEditActivity.class));
        awaitPage(StaffEditActivity.class, R.id.staff_edit_save);
        StaffEditActivity page = onMain(() -> resumed(StaffEditActivity.class));
        Configuration original = onMain(() -> new Configuration(page.getResources().getConfiguration()));
        int[] fields = {R.id.staff_edit_name, R.id.staff_edit_number};
        int[] groups = {R.id.staff_edit_group_name, R.id.staff_edit_group_number};
        String[] drafts = {"重排中的员工姓名", "0023"};
        try {
            for (int index = 0; index < fields.length; index++) {
                final int inputId = fields[index], groupId = groups[index];
                final String draft = drafts[index];
                onMain(() -> {
                    reflowStaffEditor(page, original, 800, 1280);
                    assertFalse("起点必须实际采用平板资源", page.getResources().getBoolean(R.bool.mc_phone));
                    EditText input = page.findViewById(inputId);
                    assertTrue("平板完整表单应显示待编辑项", input.isShown());
                    input.setText(draft);
                    assertTrue(input.requestFocus());
                    input.setSelection(2);
                    reflowStaffEditor(page, original, 360, 640);
                    assertTrue("重排后必须实际采用手机资源", page.getResources().getBoolean(R.bool.mc_phone));
                    EditText restored = page.findViewById(inputId);
                    assertNotSame("重排必须加载新 XML，不能仅保留旧控件", input, restored);
                    assertTrue("手机必须继续显示重排前正在编辑的资料项", page.findViewById(groupId).isShown());
                    assertEquals("草稿不能因窗口变窄丢失", draft, restored.getText().toString());
                    assertTrue("重排后焦点必须仍在当前编辑项", restored.hasFocus());
                    assertEquals("重排后光标位置必须保留", 2, restored.getSelectionStart());
                    return null;
                });
            }
        } finally {
            onMain(() -> {
                // 只改变测试进程的资源选择并立即还原，不修改系统分辨率、密度或字体。
                page.getResources().updateConfiguration(original, page.getResources().getDisplayMetrics());
                page.onConfigurationChanged(new Configuration(original));
                return null;
            });
        }
    }

    @SuppressWarnings("deprecation")
    private static void reflowStaffEditor(StaffEditActivity page, Configuration baseline, int width, int height) {
        Configuration config = new Configuration(baseline);
        config.smallestScreenWidthDp = width;
        config.screenWidthDp = width;
        config.screenHeightDp = height;
        config.orientation = Configuration.ORIENTATION_PORTRAIT;
        config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_SIZE_MASK)
                | (width >= 600 ? Configuration.SCREENLAYOUT_SIZE_XLARGE : Configuration.SCREENLAYOUT_SIZE_NORMAL);
        page.getResources().updateConfiguration(config, page.getResources().getDisplayMetrics());
        page.onConfigurationChanged(config);
    }








    private static Rect pageBounds(View view) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        return new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    }

    private void assertReadableStaffControl(BaseMorningActivity page, int id, int minimumDp) {
        TextView value = page.findViewById(id);
        View root = page.findViewById(R.id.main_root);
        Rect visible = new Rect();
        assertTrue("当前资料与操作控件必须可见：" + id, value.getGlobalVisibleRect(visible));
        assertEquals("输入与操作控件不能被上层裁切：" + id, value.getHeight(), visible.height());
        assertTrue("固定控件不能被键盘压矮：" + id,
                value.getHeight() >= minimumDp * page.getResources().getDisplayMetrics().density - 1);
        assertNotNull("有文字的控件必须完成文本排版", value.getLayout());
        assertTrue("字体与上下内边距必须完整容纳在控件中：" + id,
                value.getLayout().getLineBottom(0) + value.getPaddingTop() + value.getPaddingBottom() <= value.getHeight());
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        assertTrue("控件必须完整位于输入法上方：" + id,
                visible.bottom <= origin[1] + root.getHeight() - root.getPaddingBottom());
    }

    @Test public void departmentFirstLastAndJumpRestorePageAndResetForSearch() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 55; i++) isolatedStore.saveDepartment(0,
                    String.format(java.util.Locale.ROOT, "跳页部门%02d", i));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, DepartmentsActivity.class));
        awaitListTestOrientation(DepartmentsActivity.class);
        awaitDepartmentTree(55);
        long lastId = onMain(() -> RecyclerListTestHelper.id(resumed(DepartmentsActivity.class)
                .findViewById(R.id.department_list), 40));
        showDepartmentRow(lastId);
        int offset = onMain(() -> {
            RecyclerView rows = resumed(DepartmentsActivity.class).findViewById(R.id.department_list);
            return RecyclerListTestHelper.row(rows, 40).getTop() - rows.getPaddingTop();
        });
        captureUi("department-list-refined");
        scenario.recreate();
        awaitDepartmentTree(55);
        awaitRecyclerAnchor(DepartmentsActivity.class, R.id.department_list, Long.toString(lastId), offset);
        RecyclerView.Adapter<?> adapter = onMain(() -> resumed(DepartmentsActivity.class)
                .<RecyclerView>findViewById(R.id.department_list).getAdapter());
        onMain(() -> {
            resumed(DepartmentsActivity.class).<EditText>findViewById(R.id.department_search).setText("跳页部门01");
            return null;
        });
        awaitDepartmentTree(1);
        onMain(() -> {
            RecyclerView rows = resumed(DepartmentsActivity.class).findViewById(R.id.department_list);
            assertSame("部门筛选不能替换 Adapter", adapter, rows.getAdapter());
            assertEquals("跳页部门01", RecyclerListTestHelper.item(rows, 0).optString("name"));
            assertEquals("新筛选从首条开始", 0, RecyclerListTestHelper.first(rows));
            resumed(DepartmentsActivity.class).<EditText>findViewById(R.id.department_search).setText("");
            return null;
        });
        awaitDepartmentTree(55);
        await(() -> onMain(() -> RecyclerListTestHelper.first(resumed(DepartmentsActivity.class)
                .findViewById(R.id.department_list)) == 0), "清除搜索后完整树应从顶部展示");
    }

    @Test public void departmentManagementPaginatesAndRenamesFromCustomDialog() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 25; i++) isolatedStore.saveDepartment(0, "测试部门" + i);
            isolatedStore.saveStaff(Jsons.obj("id", 8, "name", "部门成员", "staff_no", "S8", "department", "测试部门25"));
            isolatedStore.saveRecord(Jsons.obj("id", "historic-department", "staff_id", 8,
                    "staff_name", "部门成员", "department", "测试部门25", "created_at", 1));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, DepartmentsActivity.class));
        awaitDepartmentTree(25);
        onMain(() -> {
            ((EditText) resumed(DepartmentsActivity.class).findViewById(R.id.department_search)).setText("测试部门25");
            return null;
        });
        awaitDepartmentTree(1);
        DepartmentsActivity host = onMain(() -> resumed(DepartmentsActivity.class));
        Rect hostBounds = onMain(() -> pageBounds(host.findViewById(R.id.main_root)));
        onMain(() -> {
            RecyclerView list = resumed(DepartmentsActivity.class).findViewById(R.id.department_list);
            assertTrue(RecyclerListTestHelper.click(list, 0));
            return null;
        });
        awaitDepartmentEditor();
        onMain(() -> {
            assertSame("部门详情不能跳转 Activity", host, resumed(DepartmentsActivity.class));
            assertEquals("弹窗不能挤压部门管理页", hostBounds, pageBounds(host.findViewById(R.id.main_root)));
            assertDepartmentDialogVisible(departmentDialog());
            return null;
        });
        awaitDepartmentEditorWindowVisible();
        instrumentation.waitForIdleSync();
        // 只为截图等待合成器提交弹窗画面，业务就绪仍由布局与活动窗口断言确认。
        SystemClock.sleep(150);
        captureUi("department-edit");
        onMain(() -> {
            Dialog edit = departmentDialog();
            ((EditText) edit.findViewById(R.id.department_edit_name)).setText("弹窗编辑后的部门");
            return null;
        });
        clickDepartmentDialog(R.id.department_edit_save);
        awaitDepartmentEditorClosed();
        awaitPage(DepartmentsActivity.class, R.id.department_add);
        app.io.submit(() -> {
            assertEquals("弹窗编辑后的部门", isolatedStore.staff(8).optString("department"));
            assertEquals("历史采集部门不改写", "测试部门25", isolatedStore.record("historic-department").optString("department"));
        }).get(10, TimeUnit.SECONDS);
    }

    private static void assertDepartmentControlVisible(Dialog dialog, int id) {
        View control = dialog.findViewById(id);
        assertNotNull("缺少部门输入或底部动作：" + id, control);
        Rect visible = new Rect(), bounds = pageBounds(control);
        Point globalOrigin = new Point();
        View decor = dialog.getWindow().getDecorView();
        Rect decorVisible = new Rect(), decorBounds = pageBounds(decor);
        assertTrue("部门控件必须可见：" + id, control.isShown() && control.getGlobalVisibleRect(visible));
        assertTrue("部门弹窗根节点必须可见", decor.getGlobalVisibleRect(decorVisible, globalOrigin));
        // 可见区域相对 Dialog 根节点；用根节点屏幕偏移统一坐标，不受输入框自身滚动影响。
        visible.offset(decorBounds.left - globalOrigin.x, decorBounds.top - globalOrigin.y);
        assertTrue("部门控件必须有完整尺寸：" + id, bounds.width() > 0 && bounds.height() > 0);
        assertEquals("部门输入与底部动作不能被裁切：" + id, bounds, visible);
        assertTrue("部门控件必须完整位于弹窗内：" + id, pageBounds(dialog.getWindow().getDecorView()).contains(bounds));
        if (id != R.id.department_edit_panel)
            assertTrue("部门控件必须完整位于白卡内：" + id, pageBounds(dialog.findViewById(R.id.department_edit_panel)).contains(bounds));
        if (control instanceof TextView) {
            TextView text = (TextView) control;
            assertNotNull("部门控件文字必须完成排版：" + id, text.getLayout());
            assertTrue("部门输入和按钮文字不能上下裁切：" + id, text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
    }

    @Test public void departmentEditorSavesEditedTextAndUpdatesStaffDepartment() throws Exception {
        app.io.submit(() -> {
            long id = isolatedStore.saveDepartment(0, "输入测试部门");
            isolatedStore.saveStaff(Jsons.obj("id", 90504, "name", "部门输入测试员工", "staff_no", "S90504", "department", "输入测试部门"));
            return id;
        }).get(10, TimeUnit.SECONDS);
        launchDepartments();
        openDepartmentNamed("输入测试部门");
        DepartmentsActivity host = onMain(() -> resumed(DepartmentsActivity.class));
        onMain(() -> {
            Dialog dialog = departmentDialog();
            assertDepartmentDialogVisible(dialog);
            EditText input = dialog.findViewById(R.id.department_edit_name);
            assertTrue("部门名称应使用系统输入法", input.getShowSoftInputOnFocus());
            input.setText("实时输入后的部门");
            assertEquals("编辑应即时写入原字段", "实时输入后的部门", input.getText().toString());
            assertDepartmentDialogVisible(dialog);
            return null;
        });
        clickDepartmentDialog(R.id.department_edit_save);
        awaitDepartmentEditorClosed();
        assertSame("保存只关闭弹窗，不替换管理页", host, onMain(() -> resumed(DepartmentsActivity.class)));
        JSONObject result = app.io.submit(() -> isolatedStore.staff(90504)).get(10, TimeUnit.SECONDS);
        assertEquals("实时输入后的部门", result.optString("department"));
    }

    @Test public void departmentDialogCancellationAndDuplicateNameNeverCreateRows() throws Exception {
        app.io.submit(() -> isolatedStore.saveDepartment(0, "已经存在的部门")).get(10, TimeUnit.SECONDS);
        launchDepartments();
        DepartmentsActivity host = onMain(() -> resumed(DepartmentsActivity.class));
        click(DepartmentsActivity.class, R.id.department_add);
        awaitDepartmentEditor();
        onMain(() -> {
            Dialog dialog = departmentDialog();
            ((EditText) dialog.findViewById(R.id.department_edit_name)).setText("仅取消的草稿");
            assertTrue(dialog.findViewById(R.id.department_edit_cancel).performClick());
            return null;
        });
        clickDepartmentConfirmation("放弃修改", "继续编辑", false);
        onMain(() -> {
            assertEquals("继续编辑应保留草稿", "仅取消的草稿",
                    ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).getText().toString());
            assertTrue(departmentDialog().findViewById(R.id.department_edit_cancel).performClick());
            return null;
        });
        clickDepartmentConfirmation("放弃修改", "放弃并关闭", true);
        awaitDepartmentEditorClosed();
        assertSame(host, onMain(() -> resumed(DepartmentsActivity.class)));
        assertEquals(1L, app.io.submit(() -> isolatedStore.departmentsPage("", 1, 20).totalRows)
                .get(10, TimeUnit.SECONDS).longValue());
        click(DepartmentsActivity.class, R.id.department_add);
        awaitDepartmentEditor();
        onMain(() -> {
            ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).setText("已经存在的部门");
            return null;
        });
        clickDepartmentDialog(R.id.department_edit_save);
        await(() -> onMain(() -> departmentDialog() != null
                && departmentDialog().findViewById(R.id.department_edit_save).isEnabled()
                ),
                "重复名称必须显示错误并保留弹窗");
        assertEquals(1L, app.io.submit(() -> isolatedStore.departmentsPage("", 1, 20).totalRows)
                .get(10, TimeUnit.SECONDS).longValue());
    }

    @Test public void departmentDialogBackAndOutsideTapConfirmDiscardWithoutSaving() throws Exception {
        launchDepartments();
        DepartmentsActivity host = onMain(() -> resumed(DepartmentsActivity.class));
        click(DepartmentsActivity.class, R.id.department_add);
        awaitDepartmentEditor();
        onMain(() -> {
            EditText input = departmentDialog().findViewById(R.id.department_edit_name);
            assertTrue("部门名称应使用系统输入法", input.getShowSoftInputOnFocus());
            input.setText("返回和外部点击待确认草稿");
            return null;
        });
        await(() -> onMain(() -> departmentDialog() != null
                && "返回和外部点击待确认草稿".contentEquals(((EditText) departmentDialog()
                        .findViewById(R.id.department_edit_name)).getText())
                && departmentDialog().getWindow().getDecorView().hasWindowFocus()), "草稿应保留在原字段，弹窗继续保持焦点");
        Dialog originalDialog = onMain(this::departmentDialog);
        boolean imeVisible = onMain(() -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(originalDialog.getWindow().getDecorView());
            assertNotNull("部门弹窗应提供系统输入法状态", insets);
            return insets.isVisible(WindowInsetsCompat.Type.ime());
        });
        if (imeVisible) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            await(() -> onMain(() -> {
                assertSame("收起输入法不能替换部门弹窗", originalDialog, departmentDialog());
                assertTrue("首次返回应保留部门表单", originalDialog.isShowing());
                assertEquals("收起输入法不能丢失部门草稿", "返回和外部点击待确认草稿",
                        originalDialog.<EditText>findViewById(R.id.department_edit_name).getText().toString());
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(originalDialog.getWindow().getDecorView());
                return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())
                        && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0;
            }), "首次返回应先收起系统输入法并保留未保存表单");
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        clickDepartmentConfirmation("放弃修改", "继续编辑", false);
        awaitDepartmentEditor();
        await(() -> onMain(() -> departmentDialog().getWindow().getDecorView().hasWindowFocus()), "继续编辑后应恢复部门弹窗焦点");
        float[] outsidePoint = onMain(() -> {
            assertSame("返回取消不能替换管理页", host, resumed(DepartmentsActivity.class));
            assertSame("继续编辑不能另建弹窗", originalDialog, departmentDialog());
            assertEquals("继续编辑必须保留已回填草稿", "返回和外部点击待确认草稿",
                    ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).getText().toString());
            Rect hostBounds = pageBounds(host.findViewById(R.id.main_root));
            Rect dialogBounds = pageBounds(departmentDialog().getWindow().getDecorView());
            // 按真实窗口边界选择宿主内的外部区域，不依赖截图或固定设备坐标。
            float x, y;
            if (dialogBounds.left - hostBounds.left >= 2) {
                x = (hostBounds.left + dialogBounds.left) / 2f;
                y = (Math.max(hostBounds.top, dialogBounds.top) + Math.min(hostBounds.bottom, dialogBounds.bottom)) / 2f;
            } else if (hostBounds.right - dialogBounds.right >= 2) {
                x = (dialogBounds.right + hostBounds.right) / 2f;
                y = (Math.max(hostBounds.top, dialogBounds.top) + Math.min(hostBounds.bottom, dialogBounds.bottom)) / 2f;
            } else if (dialogBounds.top - hostBounds.top >= 2) {
                x = (Math.max(hostBounds.left, dialogBounds.left) + Math.min(hostBounds.right, dialogBounds.right)) / 2f;
                y = (hostBounds.top + dialogBounds.top) / 2f;
            } else {
                assertTrue("部门弹窗应留下可点击的外部区域", hostBounds.bottom - dialogBounds.bottom >= 2);
                x = (Math.max(hostBounds.left, dialogBounds.left) + Math.min(hostBounds.right, dialogBounds.right)) / 2f;
                y = (dialogBounds.bottom + hostBounds.bottom) / 2f;
            }
            assertTrue("外部点击必须仍在宿主屏幕内", hostBounds.contains((int) x, (int) y));
            assertFalse("外部点击不能落在弹窗窗口内", dialogBounds.contains((int) x, (int) y));
            return new float[]{x, y};
        });
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, outsidePoint[0], outsidePoint[1], 0);
        try {
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("真实外部按下事件应注入成功", instrumentation.getUiAutomation().injectInputEvent(down, true));
        } finally { down.recycle(); }
        SystemClock.sleep(60);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, outsidePoint[0], outsidePoint[1], 0);
        try {
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("真实外部抬起事件应注入成功", instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { up.recycle(); }
        clickDepartmentConfirmation("放弃修改", "放弃并关闭", true);
        awaitDepartmentEditorClosed();
        assertSame("放弃草稿只关闭弹窗，不退出宿主", host, onMain(() -> resumed(DepartmentsActivity.class)));
        assertEquals("返回与外部关闭不能保存未确认草稿", 0L,
                app.io.submit(() -> isolatedStore.departmentsPage("", 1, 20).totalRows).get(10, TimeUnit.SECONDS).longValue());
    }

    @Test public void departmentDialogRecreationAndConfigurationKeepUnsavedDraft() throws Exception {
        long id = app.io.submit(() -> isolatedStore.saveDepartment(0, "重建前部门")).get(10, TimeUnit.SECONDS);
        launchDepartments();
        openDepartmentNamed("重建前部门");
        onMain(() -> {
            ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).setText("重建后保留的草稿");
            return null;
        });
        scenario.recreate();
        awaitDepartmentEditor();
        onMain(() -> {
            assertEquals("重建后保留的草稿", ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).getText().toString());
            DepartmentsActivity host = resumed(DepartmentsActivity.class);
            host.onConfigurationChanged(new Configuration(host.getResources().getConfiguration()));
            return null;
        });
        instrumentation.waitForIdleSync();
        awaitDepartmentEditor();
        onMain(() -> {
            assertEquals("布局重排不能丢弃草稿", "重建后保留的草稿",
                    ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).getText().toString());
            assertDepartmentDialogVisible(departmentDialog());
            int editors = 0;
            for (Fragment fragment : resumed(DepartmentsActivity.class).getSupportFragmentManager().getFragments())
                if ("department_editor".equals(fragment.getTag()) && fragment.isAdded()) editors++;
            assertEquals("重建和重排只保留一个部门弹窗", 1, editors);
            return null;
        });
        assertEquals("草稿尚未保存不能写数据库", "重建前部门",
                app.io.submit(() -> isolatedStore.department(id).optString("name")).get(10, TimeUnit.SECONDS));
        clickDepartmentDialog(R.id.department_edit_save);
        awaitDepartmentEditorClosed();
        assertEquals("重建后保留的草稿", app.io.submit(() -> isolatedStore.department(id).optString("name")).get(10, TimeUnit.SECONDS));
    }

    @Test public void departmentDialogPendingSaveSurvivesRecreationWithoutDuplicateWrite() throws Exception {
        app.io.submit(() -> {
            SQLiteDatabase db = isolatedStore.getWritableDatabase();
            // 审计仅存在于 UUID 测试库，用事务实际写入次数识别重建后的重复提交。
            db.execSQL("CREATE TABLE codex_department_writes (id INTEGER PRIMARY KEY)");
            db.execSQL("CREATE TRIGGER codex_department_insert AFTER INSERT ON departments BEGIN INSERT INTO codex_department_writes(id) VALUES(NULL); END");
            return null;
        }).get(10, TimeUnit.SECONDS);
        launchDepartments();
        click(DepartmentsActivity.class, R.id.department_add);
        awaitDepartmentEditor();
        onMain(() -> {
            ((EditText) departmentDialog().findViewById(R.id.department_edit_name)).setText("在途保存只写一次");
            return null;
        });
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> queueFailure = new AtomicReference<>();
        app.io.execute(() -> {
            blocked.countDown();
            try { if (!release.await(15, TimeUnit.SECONDS)) queueFailure.set(new AssertionError("保存队列隔离等待超时")); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); queueFailure.set(error); }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            clickDepartmentDialog(R.id.department_edit_save);
            await(() -> onMain(() -> departmentDialog() != null
                    && !departmentDialog().findViewById(R.id.department_edit_save).isEnabled()), "保存提交后应禁用重复提交");
            scenario.recreate();
            await(() -> onMain(() -> departmentDialog() != null && departmentDialog().isShowing()
                    && departmentDialog().findViewById(R.id.department_edit_save) != null), "重建后应恢复在途保存弹窗");
            onMain(() -> {
                Dialog dialog = departmentDialog();
                assertFalse("重建不能开放第二次保存", dialog.findViewById(R.id.department_edit_save).isEnabled());
                assertEquals("在途保存只写一次", ((EditText) dialog.findViewById(R.id.department_edit_name)).getText().toString());
                dialog.findViewById(R.id.department_edit_save).performClick();
                return null;
            });
        } finally { release.countDown(); }
        awaitDepartmentEditorClosed();
        app.io.submit(() -> {
            assertEquals(1L, isolatedStore.departmentsPage("", 1, 20).totalRows);
            try (android.database.Cursor count = isolatedStore.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM codex_department_writes", null)) {
                assertTrue(count.moveToFirst());
                assertEquals("Activity 重建不能重放已提交的新增事务", 1L, count.getLong(0));
            }
            return null;
        }).get(10, TimeUnit.SECONDS);
        assertNull(queueFailure.get());
    }

    private void launchDepartments() {
        scenario = ActivityScenario.launch(new Intent(app, DepartmentsActivity.class));
        awaitPage(DepartmentsActivity.class, R.id.department_add);
        await(() -> onMain(() -> resumed(DepartmentsActivity.class).findViewById(R.id.department_list).isEnabled()), "部门列表应读取完成");
    }

    private void openDepartmentNamed(String name) {
        onMain(() -> { ((EditText) resumed(DepartmentsActivity.class).findViewById(R.id.department_search)).setText(name); return null; });
        await(() -> onMain(() -> {
            DepartmentsActivity host = resumed(DepartmentsActivity.class);
            RecyclerView list = host == null ? null : host.findViewById(R.id.department_list);
            return list != null && list.isEnabled() && RecyclerListTestHelper.count(list) == 1 && list.getChildCount() > 0
                    && name.equals(((JSONObject) RecyclerListTestHelper.item(list, 0)).optString("name"));
        }), "查询应只显示指定部门：" + name);
        long departmentId = onMain(() -> ((JSONObject) RecyclerListTestHelper.item(((RecyclerView) resumed(DepartmentsActivity.class)
                .findViewById(R.id.department_list)), 0)).optLong("id"));
        clickDepartmentRowAction(departmentId, R.id.business_row_state);
        awaitDepartmentEditor();
    }

    private Dialog departmentDialog() {
        DepartmentsActivity host = resumed(DepartmentsActivity.class);
        if (host == null) return null;
        Fragment fragment = host.getSupportFragmentManager().findFragmentByTag("department_editor");
        return fragment instanceof DialogFragment ? ((DialogFragment) fragment).getDialog() : null;
    }

    private void awaitDepartmentEditor() {
        await(() -> onMain(() -> {
            Dialog dialog = departmentDialog();
            View save = dialog == null ? null : dialog.findViewById(R.id.department_edit_save);
            return dialog != null && dialog.isShowing() && save != null && save.isEnabled() && save.getHeight() > 0;
        }), "部门编辑应显示为可操作的自定义弹窗");
        instrumentation.waitForIdleSync();
    }

    private void awaitDepartmentEditorClosed() {
        await(() -> onMain(() -> departmentDialog() == null || !departmentDialog().isShowing()), "保存或取消应只关闭部门编辑弹窗");
        instrumentation.waitForIdleSync();
    }

    private void awaitDepartmentEditorWindowVisible() {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            await(() -> {
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root == null) return false;
                java.util.List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText("部门详情");
                java.util.List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(
                        app.getPackageName() + ":id/department_edit_save");
                try {
                    boolean titleVisible = false;
                    for (AccessibilityNodeInfo title : titles)
                        titleVisible |= title.isVisibleToUser() && "部门详情".contentEquals(title.getText());
                    return titleVisible && buttons.size() == 1 && buttons.get(0).isVisibleToUser()
                            && buttons.get(0).isEnabled();
                } finally {
                    for (AccessibilityNodeInfo node : titles) node.recycle();
                    for (AccessibilityNodeInfo node : buttons) node.recycle();
                    root.recycle();
                }
            }, "截图前活动窗口必须实际出现部门详情标题及可操作的保存按钮");
        } finally { info.flags = originalFlags; instrumentation.getUiAutomation().setServiceInfo(info); }
    }

    private void clickDepartmentDialog(int id) {
        onMain(() -> { Dialog dialog = departmentDialog(); assertNotNull(dialog); assertTrue(dialog.findViewById(id).performClick()); return null; });
    }

    private static void assertDepartmentDialogVisible(Dialog dialog) {
        for (int id : new int[]{R.id.department_edit_panel, R.id.department_edit_name, R.id.department_edit_actions,
                R.id.department_edit_cancel, R.id.department_edit_save}) assertDepartmentControlVisible(dialog, id);
    }

    private void clickDepartmentConfirmation(String title, String label, boolean positive) {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            await(() -> {
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root == null) return false;
                java.util.List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByText(title);
                java.util.List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(positive ? "android:id/button1" : "android:id/button2");
                try { return !titles.isEmpty() && buttons.size() == 1 && label.contentEquals(buttons.get(0).getText())
                        && buttons.get(0).isEnabled() && buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                finally {
                    for (AccessibilityNodeInfo node : titles) node.recycle();
                    for (AccessibilityNodeInfo node : buttons) node.recycle();
                    root.recycle();
                }
            }, "部门确认窗应明确提示范围且按钮可点击：" + title + " / " + label);
        } finally { info.flags = originalFlags; instrumentation.getUiAutomation().setServiceInfo(info); }
    }

    @Test public void recordDetailPhotosNavigateAllFramesAndReturnWithoutChangingEvidence() throws Exception {
        String fontArgument = InstrumentationRegistry.getArguments().getString("photo_test_font_index", "");
        if (!fontArgument.isEmpty()) {
            int fontIndex = Integer.parseInt(fontArgument);
            assertTrue("照片界面字体档位必须有效", fontIndex >= 0 && fontIndex <= 3);
            assertTrue(preferences.edit().putInt(AppFontSettings.KEY, fontIndex).commit());
        }
        String recordId = "photo-navigation-" + UUID.randomUUID();
        app.io.submit(() -> {
            JSONArray photos = new JSONArray();
            int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
            for (int i = 0; i < colors.length; i++) {
                Bitmap bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(colors[i]);
                String name;
                try { name = isolatedStore.savePhoto(recordId, "frame-" + i, bitmap); }
                finally { bitmap.recycle(); }
                testPhotoNames.add(name);
                photos.put(Jsons.obj("file", name, "label", "采集照片 " + (i + 1)));
            }
            isolatedStore.saveRecord(Jsons.obj("id", recordId, "staff_id", 9,
                    "staff_name", "照片导航测试员工", "status", 0, "upload", "已上传", "created_at", 1,
                    "temperature", "38.1", "abnormal_temperature", 1, "captured_photos", photos));
            return null;
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class));
        awaitRecordPage("第 1 / 1 页 · 共 1 条", 1);
        awaitListTestOrientation(RecordsActivity.class);
        onMain(() -> {
            RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list);
            assertTrue(RecyclerListTestHelper.click(list, 0));
            return null;
        });
        awaitPage(RecordDetailActivity.class, R.id.record_detail_photos);
        awaitListTestOrientation(RecordDetailActivity.class);
        await(() -> onMain(() -> {
            RecordDetailActivity page = resumed(RecordDetailActivity.class);
            RecyclerView list = page == null ? null : page.findViewById(R.id.record_detail_photos);
            return list != null && list.isShown() && list.getAdapter() != null
                    && list.getAdapter().getItemCount() == 4
                    && list.getLayoutManager() instanceof GridLayoutManager
                    && !list.isLayoutRequested() && list.getChildCount() > 0;
        }), "详情应展示全部四张采集照片");
        onMain(() -> {
            RecordDetailActivity page = resumed(RecordDetailActivity.class);
            RecyclerView list = page.findViewById(R.id.record_detail_photos);
            Rect visible = new Rect();
            View viewport = (View) list.getParent();
            assertEquals("照片网格应铺满采集照片区域宽度",
                    viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight(), list.getWidth());
            assertEquals("照片网格应铺满采集照片区域高度",
                    viewport.getHeight() - viewport.getPaddingTop() - viewport.getPaddingBottom(), list.getHeight());
            assertTrue("采集照片列表必须可见", list.getGlobalVisibleRect(visible));
            assertEquals("采集照片列表不能被横向裁切", list.getWidth(), visible.width());
            assertEquals("采集照片列表不能被上下裁切", list.getHeight(), visible.height());
            TextView hint = page.findViewById(R.id.record_photo_hint);
            assertTrue("照片下方应保留查看入口说明", hint.isShown());
            assertEquals("点击图片查看详情", hint.getText().toString());
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            assertEquals("采集照片每行应展示两张", 2, manager.getSpanCount());
            assertEquals("采集照片应按竖向网格排列", RecyclerView.VERTICAL, manager.getOrientation());
            View first = manager.findViewByPosition(0), second = manager.findViewByPosition(1);
            assertNotNull("第一张采集照片应完成布局", first);
            assertNotNull("第二张采集照片应完成布局", second);
            Rect left = new Rect(), right = new Rect();
            manager.getDecoratedBoundsWithMargins(first, left);
            manager.getDecoratedBoundsWithMargins(second, right);
            assertEquals("同一行的采集照片顶部应对齐", left.top, right.top);
            assertTrue("每行两张采集照片应等宽", Math.abs(left.width() - right.width()) <= 1);
            assertEquals("首列应从照片区域左边缘开始", list.getPaddingLeft(), left.left);
            assertEquals("两列应连续占满照片区域", left.right, right.left);
            assertEquals("第二列应覆盖到照片区域右边缘", list.getWidth() - list.getPaddingRight(), right.right);
            assertFalse("采集照片网格不应横向滚动", list.canScrollHorizontally(1) || list.canScrollHorizontally(-1));
            return null;
        });
        captureUi("record-photo-list-start");
        RecordDetailActivity source = onMain(() -> resumed(RecordDetailActivity.class));
        onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            list.scrollToPosition(3);
            return null;
        });
        await(() -> onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            View last = manager.findViewByPosition(3);
            Rect visible = new Rect();
            return !list.isLayoutRequested() && last != null && last.isShown()
                    && last.getLocalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
        }), "竖向网格应能查看最后一张照片");
        captureUi("record-photo-list-end");
        onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            ((GridLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(1, 0);
            return null;
        });
        await(() -> onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            View second = manager.findViewByPosition(1);
            return !list.isLayoutRequested() && second != null && second.isShown()
                    && second.findViewById(R.id.record_photo_thumbnail).hasOnClickListeners();
        }), "照片网格应能返回第二张照片并打开原始下标");
        int[] scrollPosition = onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            int first = manager.findFirstVisibleItemPosition();
            int offset = manager.getDecoratedTop(manager.findViewByPosition(first)) - list.getPaddingTop();
            assertTrue(manager.findViewByPosition(1).findViewById(R.id.record_photo_thumbnail).performClick());
            return new int[]{first, offset};
        });
        awaitPhotoPosition(2, 4);
        click(RecordPhotoActivity.class, R.id.photo_next);
        awaitPhotoPosition(3, 4);
        click(RecordPhotoActivity.class, R.id.photo_next);
        awaitPhotoPosition(4, 4);
        onMain(() -> {
            RecordPhotoActivity photo = resumed(RecordPhotoActivity.class);
            assertFalse("最后一张不能继续向后", photo.findViewById(R.id.photo_next).isEnabled());
            assertInsidePage(photo, R.id.photo_previous);
            assertInsidePage(photo, R.id.photo_next);
            return null;
        });
        click(RecordPhotoActivity.class, R.id.photo_previous);
        awaitPhotoPosition(3, 4);
        RecordPhotoActivity oldViewer = onMain(() -> resumed(RecordPhotoActivity.class));
        onMain(() -> { oldViewer.recreate(); return null; });
        await(() -> onMain(() -> resumed(RecordPhotoActivity.class) != null
                && resumed(RecordPhotoActivity.class) != oldViewer), "查看器重建应替换原页面");
        awaitPhotoPosition(3, 4);
        click(RecordPhotoActivity.class, R.id.header_home);
        awaitPage(RecordDetailActivity.class, R.id.record_detail_photos);
        await(() -> onMain(() -> {
            RecyclerView list = source.findViewById(R.id.record_detail_photos);
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            View first = manager.findViewByPosition(scrollPosition[0]);
            return !list.isLayoutRequested() && first != null
                    && manager.findFirstVisibleItemPosition() == scrollPosition[0]
                    && manager.getDecoratedTop(first) - list.getPaddingTop() == scrollPosition[1];
        }), "查看器返回后应保留照片竖向浏览位置");
        onMain(() -> {
            assertSame("查看器返回原详情而不是新建页面", source, resumed(RecordDetailActivity.class));
            assertTrue(source.findViewById(R.id.record_detail_fields).isShown());
            assertTrue(source.findViewById(R.id.record_detail_photos).isShown());
            source.recreate();
            return null;
        });
        await(() -> onMain(() -> {
            RecordDetailActivity page = resumed(RecordDetailActivity.class);
            if (page == null || page == source || !page.findViewById(R.id.record_detail_fields).isShown()) return false;
            RecyclerView list = page.findViewById(R.id.record_detail_photos);
            if (list.getAdapter() == null || list.getAdapter().getItemCount() != 4
                    || !(list.getLayoutManager() instanceof GridLayoutManager) || list.isLayoutRequested()) return false;
            GridLayoutManager manager = (GridLayoutManager) list.getLayoutManager();
            View first = manager.findViewByPosition(scrollPosition[0]);
            return first != null && manager.findFirstVisibleItemPosition() == scrollPosition[0]
                    && manager.getDecoratedTop(first) - list.getPaddingTop() == scrollPosition[1];
        }),
                "详情重建应同时展示字段与完整照片列表");
        click(RecordDetailActivity.class, R.id.header_home);
        awaitRecordPage("第 1 / 1 页 · 共 1 条", 1);
        JSONObject result = app.io.submit(() -> isolatedStore.record(recordId)).get(10, TimeUnit.SECONDS);
        assertEquals(0, result.optInt("status"));
        assertEquals(1, result.optInt("abnormal_temperature"));
        assertEquals("38.1", result.optString("temperature"));
        assertEquals("已上传", result.optString("upload"));
        assertEquals(4, result.optJSONArray("captured_photos").length());
        for (String name : testPhotoNames) assertTrue("页面导航不能删除证据照片", isolatedStore.photo(name).isFile());
    }

    private void awaitPhotoPosition(int selected, int total) {
        awaitListTestOrientation(RecordPhotoActivity.class);
        await(() -> onMain(() -> {
            RecordPhotoActivity photo = resumed(RecordPhotoActivity.class);
            if (photo == null) return false;
            TextView position = photo.findViewById(R.id.photo_position);
            TextView caption = photo.findViewById(R.id.photo_caption);
            RecyclerView pager = photo.findViewById(R.id.photo_pager);
            if (pager == null || pager.isLayoutRequested() || pager.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
                return false;
            RecyclerView.ViewHolder holder = pager.findViewHolderForAdapterPosition(selected - 1);
            if (holder == null || pager.getLayoutManager().getDecoratedLeft(holder.itemView) != pager.getPaddingLeft())
                return false;
            ImageView image = holder.itemView.findViewById(R.id.photo_image);
            return position != null && (selected + "/" + total).equals(position.getText().toString().replaceAll("\\s+", ""))
                    && caption != null && caption.getText().toString().contains("采集照片 " + selected)
                    && image != null && image.getDrawable() != null
                    && holder.itemView.findViewById(R.id.photo_empty).getVisibility() == View.GONE;
        }), "照片查看器未收敛到第 " + selected + " / " + total + " 张");
    }

    @Test public void sdkCertificateAndModelDetailsAreIndependentPages() throws Exception {
        assertFalse("旧版 cloud kind 已不属于可访问的详情类型", SdkDetailsActivity.isDetailKind("cloud"));
        for (String kind : new String[]{"certificate", "model", "status", "capabilities", "cloud"}) {
            scenario = ActivityScenario.launch(new Intent(app, SdkDetailsActivity.class).putExtra(SdkDetailsActivity.EXTRA_KIND, kind));
            awaitListTestOrientation(SdkDetailsActivity.class);
            awaitPage(SdkDetailsActivity.class, R.id.sdk_detail_fields);
            captureUi("sdk-" + kind);
            String expectedTitle = "certificate".equals(kind) ? "设备授权" : "model".equals(kind) ? "模型准备进度"
                    : "capabilities".equals(kind) ? "SDK 能力" : "SDK 状态";
            String command = "certificate".equals(kind) ? "getCertInfo" : "model".equals(kind) ? "getDownloadProgress"
                    : "capabilities".equals(kind) ? "getCapabilities" : "getStatus";
            boolean statusPage = "status".equals(kind) || "cloud".equals(kind);
            onMain(() -> {
                SdkDetailsActivity page = resumed(SdkDetailsActivity.class);
                if (statusPage) {
                    View refresh = page.findViewById(R.id.sdk_detail_refresh);
                    assertEquals("SDK 状态页不提供手动刷新入口", View.GONE, refresh.getVisibility());
                    assertFalse(refresh.isEnabled());
                    assertFalse(refresh.hasOnClickListeners());
                } else assertInsidePage(page, R.id.sdk_detail_refresh);
                assertEquals("旧版 cloud Intent 应回退为设备状态，其余详情类型仍各自显示", expectedTitle,
                        ((TextView) page.findViewById(R.id.header_page_title)).getText().toString());
                assertCloudHostingUnavailable(page);
                return null;
            });
            if (!statusPage) {
                awaitPage(SdkDetailsActivity.class, R.id.sdk_detail_refresh);
                int previousRequests = sdkCommandCount(command);
                click(SdkDetailsActivity.class, R.id.sdk_detail_refresh);
                await(() -> sdkCommandCount(command) > previousRequests, "详情刷新仍应发出对应的真实只读查询：" + command);
                awaitPage(SdkDetailsActivity.class, R.id.sdk_detail_refresh);
            }
            scenario.recreate();
            awaitPage(SdkDetailsActivity.class, R.id.sdk_detail_fields);
            onMain(() -> {
                SdkDetailsActivity page = resumed(SdkDetailsActivity.class);
                assertEquals("重建不能恢复云端托管类型", expectedTitle,
                        ((TextView) page.findViewById(R.id.header_page_title)).getText().toString());
                if (statusPage) {
                    View refresh = page.findViewById(R.id.sdk_detail_refresh);
                    assertEquals("重建后也不恢复状态页刷新按钮", View.GONE, refresh.getVisibility());
                    assertFalse(refresh.hasOnClickListeners());
                }
                assertCloudHostingUnavailable(page);
                return null;
            });
            assertEquals(0, sdkCommandCount("getCloudStatus"));
            assertEquals(0, sdkCommandCount("setCloudStatus"));
            scenario.close();
            scenario = null;
        }
    }

    private int sdkCommandCount(String command) {
        int count = 0;
        for (String actual : sdkCommands) if (command.equals(actual)) count++;
        return count;
    }

    private static void assertCloudHostingUnavailable(SdkDetailsActivity page) {
        View enable = page.findViewById(R.id.sdk_detail_cloud_enable);
        assertEquals("详情页不再提供云端托管开关", View.GONE, enable.getVisibility());
        assertFalse("隐藏开关不能被状态刷新重新启用", enable.isEnabled());
        assertFalse("隐藏开关不能残留写操作监听", enable.hasOnClickListeners());
        assertFalse("程序点击也不能进入开启确认", enable.performClick());
        ListView list = page.findViewById(R.id.sdk_detail_fields);
        assertNotNull("其余 SDK 详情仍有数据列表", list.getAdapter());
        for (int index = 0; index < list.getCount(); index++) {
            String[] row = (String[]) list.getItemAtPosition(index);
            for (String field : row) assertFalse("SDK 状态详情不再展示云端托管文案", field.contains("云端托管"));
        }
    }





    private <T extends BaseMorningActivity> void awaitPage(Class<T> type, int control) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            return page != null && page.findViewById(control) != null && page.findViewById(control).isEnabled()
                    && page.findViewById(control).getHeight() > 0;
        }), "页面未就绪：" + type.getSimpleName());
    }

    private void captureUi(String name) throws Exception {
        instrumentation.waitForIdleSync();
        // 等系统旋转过渡和上一条短提示结束，截图记录稳定的列表布局。
        SystemClock.sleep(2100);
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("页面截图必须可读取", bitmap);
        if ("landscape".equals(InstrumentationRegistry.getArguments().getString("expected_orientation")))
            assertTrue("横屏验证必须实际使用横屏视口", bitmap.getWidth() > bitmap.getHeight());
        File folder = new File(app.getFilesDir(), "codex-ui-evidence");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private void assertInsidePage(BaseMorningActivity page, int id) {
        View root = page.findViewById(R.id.main_root), action = page.findViewById(id);
        Rect visible = new Rect();
        assertTrue("操作控件必须可见", action.getGlobalVisibleRect(visible));
        assertEquals("操作控件不能被裁切", action.getHeight(), visible.height());
        assertTrue("操作控件必须在当前页面内", visible.bottom <= root.getHeight());
    }

    private <T extends BaseMorningActivity> void awaitListTestOrientation(Class<T> type) {
        String expected = InstrumentationRegistry.getArguments().getString("expected_orientation", "");
        if (expected.isEmpty()) return;
        setListTestOrientation(type, "portrait".equals(expected));
    }

    private <T extends BaseMorningActivity> void setListTestOrientation(Class<T> type, boolean portrait) {
        await(() -> onMain(() -> resumed(type) != null), "方向检查前页面应已恢复");
        onMain(() -> {
            resumed(type).setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            return null;
        });
        await(() -> onMain(() -> {
            T page = resumed(type);
            if (page == null) return false;
            View root = page.findViewById(android.R.id.content);
            return root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested()
                    && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && page.getResources().getConfiguration().orientation == (portrait
                    ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "列表必须实际使用指定方向：" + (portrait ? "portrait" : "landscape"));
    }

    @Test public void recordsFirstLastAndJumpKeepPageAfterRecreationAndResetForResultFilter() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 55; i++) isolatedStore.saveRecord(Jsons.obj("id", "jump-record-" + i,
                    "staff_id", i, "staff_name", "跳页记录员工 " + i, "staff_no", "R" + i,
                    "created_at", i, "status", i <= 45 ? 0 : 1, "temperature", "36.5"));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class));
        awaitListTestOrientation(RecordsActivity.class);
        awaitRecordPage("第 1 / 3 页 · 共 55 条", 24);
        captureUi("records-list-refined");
        assertPagerEdges(RecordsActivity.class, R.id.records_first, R.id.records_previous,
                R.id.records_next, R.id.records_last, true, false);
        click(RecordsActivity.class, R.id.records_last);
        awaitRecordPage("第 3 / 3 页 · 共 55 条", 55);
        assertPagerEdges(RecordsActivity.class, R.id.records_first, R.id.records_previous,
                R.id.records_next, R.id.records_last, false, true);
        click(RecordsActivity.class, R.id.records_first);
        awaitRecordPage("第 1 / 3 页 · 共 55 条", 24);
        jumpToPage(RecordsActivity.class, R.id.records_page_input, R.id.records_page_jump, "2");
        awaitRecordPage("第 2 / 3 页 · 共 55 条", 48);
        assertInlinePageInput(RecordsActivity.class, R.id.records_page_input, R.id.records_page_jump, "2");
        assertEquals("jump-record-55", onMain(() -> ((JSONObject) RecyclerListTestHelper.item(((RecyclerView) resumed(RecordsActivity.class)
                .findViewById(R.id.records_list)), 0)).optString("id")));
        scenario.recreate();
        awaitRecordPage("第 2 / 3 页 · 共 55 条", 48);
        assertInlinePageInput(RecordsActivity.class, R.id.records_page_input, R.id.records_page_jump, "2");
        selectRecordFilter(1);
        awaitRecordPage("第 1 / 1 页 · 共 10 条", 10);
        assertInlinePageInput(RecordsActivity.class, R.id.records_page_input, R.id.records_page_jump, "1");
        assertPagerEdges(RecordsActivity.class, R.id.records_first, R.id.records_previous,
                R.id.records_next, R.id.records_last, true, true);
        scenario.recreate();
        awaitRecordPage("第 1 / 1 页 · 共 10 条", 10);
        onMain(() -> {
            assertEquals("重建后保留异常结果筛选", 1,
                    ((Spinner) resumed(RecordsActivity.class).findViewById(R.id.records_filter)).getSelectedItemPosition());
            return null;
        });
    }

    @Test public void recordFiltersPageAndStaySelectedAfterRecreationAndRefresh() throws Exception {
        app.io.submit(() -> {
            for (int i = 1; i <= 55; i++) isolatedStore.saveRecord(Jsons.obj("id", "row-" + i,
                    "staff_id", 1, "staff_name", "测试员工", "created_at", i, "status", i <= 45 ? 0 : 1,
                    "temperature", "36.5", "upload", i <= 25 ? "结果待核实" : "已上传"));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class));
        awaitListTestOrientation(RecordsActivity.class);
        awaitRecordPage("第 1 / 3 页 · 共 55 条", 24);
        selectRecordFilter(2);
        awaitRecordPage("第 1 / 2 页 · 共 45 条", 24);
        click(RecordsActivity.class, R.id.records_next);
        awaitRecordPage("第 2 / 2 页 · 共 45 条", 45);
        selectRecordFilter(1);
        awaitRecordPage("第 1 / 1 页 · 共 10 条", 10);
        scenario.recreate();
        awaitRecordPage("第 1 / 1 页 · 共 10 条", 10);
        onMain(() -> {
            Spinner result = resumed(RecordsActivity.class).findViewById(R.id.records_filter);
            assertEquals(3, result.getCount());
            assertEquals(1, result.getSelectedItemPosition());
            return null;
        });
        app.io.submit(() -> {
            for (int i = 46; i <= 55; i++) isolatedStore.patchRecord("row-" + i, Jsons.obj("status", 0));
        }).get(10, TimeUnit.SECONDS);
        onMain(() -> { resumed(RecordsActivity.class).reload(false, null); return null; });
        awaitRecordPage("第 1 / 1 页 · 共 0 条", 0);
        onMain(() -> {
            RecordsActivity page = resumed(RecordsActivity.class);
            assertTrue(page.findViewById(R.id.records_empty).isShown());
            assertEquals(1, ((Spinner) page.findViewById(R.id.records_filter)).getSelectedItemPosition());
            return null;
        });
    }

    @Test public void combinedStaffFiltersIncludeDescendantsAndSurviveRecreation() throws Exception {
        long parent = seedHierarchyFilters();
        scenario = ActivityScenario.launch(new Intent(app, StaffActivity.class)
                .putExtra(StaffActivity.EXTRA_DEPARTMENT_ID, parent));
        awaitListTestOrientation(StaffActivity.class);
        awaitStaffPage("第 1 / 2 页 · 共 45 条", 24);
        onMain(() -> {
            ((Spinner) resumed(StaffActivity.class).findViewById(R.id.staff_enabled_filter)).setSelection(1);
            return null;
        });
        awaitStaffPage("第 1 / 2 页 · 共 30 条", 24);
        captureUi("staff-combined-filters");
        click(StaffActivity.class, R.id.staff_next);
        awaitStaffPage("第 2 / 2 页 · 共 30 条", 30);
        scenario.recreate();
        awaitStaffPage("第 2 / 2 页 · 共 30 条", 30);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            assertEquals(1, ((Spinner) page.findViewById(R.id.staff_enabled_filter)).getSelectedItemPosition());
            assertEquals("测试总部门", ((Spinner) page.findViewById(R.id.staff_department_filter)).getSelectedItem().toString());
            ((EditText) page.findViewById(R.id.staff_search)).setText("JOB-00");
            return null;
        });
        awaitStaffPage("第 1 / 1 页 · 共 6 条", 6);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            RecyclerView list = page.findViewById(R.id.staff_list);
            for (int i = 0; i < RecyclerListTestHelper.count(list); ++i) {
                JSONObject person = (JSONObject) RecyclerListTestHelper.item(list, i);
                assertTrue(person.optBoolean("enabled", true));
                assertTrue(person.optString("staff_no").startsWith("JOB-00"));
                assertTrue(person.optString("department").startsWith("测试总部门"));
            }
            ((EditText) page.findViewById(R.id.staff_search)).setText("名单%_");
            return null;
        });
        awaitStaffPage("第 1 / 1 页 · 共 0 条", 0);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            assertTrue(page.findViewById(R.id.staff_empty).isShown());
            assertInsidePage(page, R.id.staff_empty);
            assertInsidePage(page, R.id.staff_enabled_filter);
            assertInsidePage(page, R.id.staff_department_filter);
            return null;
        });
        assertEquals(0, businessRequests.get());
    }

    @Test public void combinedRecordFiltersSearchSnapshotsAndKeepOnlyThreeResultChoices() throws Exception {
        long parent = seedHierarchyFilters();
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class)
                .putExtra(RecordsActivity.EXTRA_DEPARTMENT_ID, parent));
        awaitListTestOrientation(RecordsActivity.class);
        awaitRecordPage("第 1 / 2 页 · 共 45 条", 24);
        onMain(() -> {
            Spinner result = resumed(RecordsActivity.class).findViewById(R.id.records_filter);
            assertEquals(3, result.getCount());
            assertEquals("全部", result.getItemAtPosition(0));
            assertEquals("正常", result.getItemAtPosition(1));
            assertEquals("异常", result.getItemAtPosition(2));
            result.setSelection(2);
            return null;
        });
        awaitRecordPage("第 1 / 1 页 · 共 23 条", 23);
        captureUi("records-combined-filters");
        onMain(() -> { assertFalse(resumed(RecordsActivity.class).findViewById(R.id.records_next).isEnabled()); return null; });
        awaitRecordPage("第 1 / 1 页 · 共 23 条", 23);
        scenario.recreate();
        awaitRecordPage("第 1 / 1 页 · 共 23 条", 23);
        onMain(() -> {
            RecordsActivity page = resumed(RecordsActivity.class);
            assertEquals(2, ((Spinner) page.findViewById(R.id.records_filter)).getSelectedItemPosition());
            ((EditText) page.findViewById(R.id.records_search)).setText("JOB-00");
            return null;
        });
        awaitRecordPage("第 1 / 1 页 · 共 5 条", 5);
        onMain(() -> {
            RecordsActivity page = resumed(RecordsActivity.class);
            RecyclerView list = page.findViewById(R.id.records_list);
            for (int i = 0; i < RecyclerListTestHelper.count(list); ++i) {
                JSONObject record = (JSONObject) RecyclerListTestHelper.item(list, i);
                assertTrue(record.optString("staff_no").startsWith("JOB-00"));
                assertEquals(0, record.optInt("status"));
            }
            assertInsidePage(page, R.id.records_filter);
            assertInsidePage(page, R.id.records_department_filter);
            return null;
        });
        assertEquals(0, businessRequests.get());
    }

    private long seedHierarchyFilters() throws Exception {
        return app.io.submit(() -> {
            long parent = isolatedStore.saveDepartment(0, "测试总部门", 0, null, 0);
            long child = isolatedStore.saveDepartment(0, "后厨", parent, null, 0);
            long grandchild = isolatedStore.saveDepartment(0, "备餐", child, null, 0);
            long other = isolatedStore.saveDepartment(0, "其他门店", 0, null, 0);
            for (int i = 0; i < 60; ++i) {
                long department = i < 45 ? (i % 2 == 0 ? child : grandchild) : other;
                String path = isolatedStore.department(department).optString("path");
                String number = String.format(java.util.Locale.ROOT, "JOB-%03d", i);
                isolatedStore.saveStaff(Jsons.obj("id", 200 + i, "name", "名单员工" + i,
                        "staff_no", number, "department", path, "department_id", department,
                        "enabled", i % 3 != 0, "created_at", i));
                isolatedStore.saveRecord(Jsons.obj("id", "hierarchy-record-" + i, "staff_id", 200 + i,
                        "staff_name", "历史员工" + i, "staff_no", number, "department", path,
                        "department_id", department, "status", i % 2, "created_at", i,
                        "temperature", "36.5", "upload", "未上传"));
            }
            return parent;
        }).get(10, TimeUnit.SECONDS);
    }

    @Test public void departmentTreeOpensScopedListsAndReturnsToSameDirectory() throws Exception {
        long parent = seedHierarchyFilters();
        scenario = ActivityScenario.launch(new Intent(app, DepartmentsActivity.class));
        awaitListTestOrientation(DepartmentsActivity.class);
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            RecyclerView list = page == null ? null : page.findViewById(R.id.department_list);
            return list != null && list.isEnabled() && RecyclerListTestHelper.count(list) == 4 && list.getChildCount() > 0;
        }), "部门目录应一次显示全部父子节点");
        showDepartmentRow(parent);
        onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            RecyclerView list = page.findViewById(R.id.department_list);
            View row = RecyclerListTestHelper.row(list, RecyclerListTestHelper.first(list));
            assertTrue("部门管理应显示固定表头", page.findViewById(R.id.department_table_header).isShown());
            ViewGroup header = page.findViewById(R.id.department_table_header);
            View[] columns = {row.findViewById(R.id.business_row_name), row.findViewById(R.id.department_row_staff)};
            for (int i = 0; i < columns.length; ++i) {
                Rect title = pageBounds(header.getChildAt(i)), cell = pageBounds(columns[i]);
                assertEquals("部门表头左边界应与数据列对齐", title.left, cell.left, 1);
                assertEquals("部门表头右边界应与数据列对齐", title.right, cell.right, 1);
            }
            Rect actions = pageBounds(header.getChildAt(2));
            assertEquals(actions.left, pageBounds(row.findViewById(R.id.business_row_state)).left, 1);
            assertEquals(actions.right, pageBounds(row.findViewById(R.id.department_row_delete)).right, 1);
            return null;
        });
        captureUi("department-hierarchy");
        clickDepartmentRowAction(parent, R.id.department_row_staff);
        awaitStaffPage("第 1 / 2 页 · 共 45 条", 24);
        onMain(() -> { assertEquals(parent, resumed(StaffActivity.class).getIntent().getLongExtra(StaffActivity.EXTRA_DEPARTMENT_ID, 0));
            resumed(StaffActivity.class).onBackPressed(); return null; });
        awaitPage(DepartmentsActivity.class, R.id.department_list);
        showDepartmentRow(parent);
        onMain(() -> {
            RecyclerView list = resumed(DepartmentsActivity.class).findViewById(R.id.department_list);
            for (int index = 0; index < RecyclerListTestHelper.count(list); index++) {
                if (RecyclerListTestHelper.id(list, index) == parent) {
                    assertTrue(RecyclerListTestHelper.row(list, index).performLongClick());
                    return null;
                }
            }
            fail("完整树应包含当前父部门");
            return null;
        });
        await(() -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            java.util.List<AccessibilityNodeInfo> choices = root.findAccessibilityNodeInfosByText("查看晨检记录");
            try {
                for (AccessibilityNodeInfo choice : choices) {
                    AccessibilityNodeInfo action = choice;
                    while (action != null && !action.isClickable()) action = action.getParent();
                    if (action != null && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
                return false;
            } finally {
                for (AccessibilityNodeInfo choice : choices) choice.recycle();
                root.recycle();
            }
        }, "部门长按菜单应能查看本部门及下级的晨检记录");
        awaitRecordPage("第 1 / 2 页 · 共 45 条", 24);
        onMain(() -> { resumed(RecordsActivity.class).onBackPressed(); return null; });
        awaitPage(DepartmentsActivity.class, R.id.department_list);
        awaitDepartmentTree(4);
        onMain(() -> {
            RecyclerView list = resumed(DepartmentsActivity.class).findViewById(R.id.department_list);
            int parentIndex = -1, childIndex = -1;
            for (int index = 0; index < RecyclerListTestHelper.count(list); index++) {
                JSONObject item = RecyclerListTestHelper.item(list, index);
                if (item.optLong("id") == parent) parentIndex = index;
                if ("后厨".equals(item.optString("name"))) childIndex = index;
            }
            assertTrue("完整树中直属子部门应紧随父节点", parentIndex >= 0 && childIndex == parentIndex + 1);
            assertEquals(1, RecyclerListTestHelper.item(list, childIndex).optInt("_tree_depth"));
            return null;
        });
        scenario.recreate();
        awaitDepartmentTree(4);
        assertEquals(0, businessRequests.get());
    }

    private void clickDepartmentRowAction(long departmentId, int actionId) {
        showDepartmentRow(departmentId);
        // 将实际数据行滚入列表视口，再验证操作入口完整触达并注入真实手势。
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.department_list);
            if (!list.isEnabled()) return false;
            for (int i = 0; i < list.getChildCount(); ++i) {
                int position = list.getChildAdapterPosition(list.getChildAt(i));
                if (position == RecyclerView.NO_POSITION) continue;
                if (((JSONObject) RecyclerListTestHelper.item(list, position)).optLong("id") != departmentId) continue;
                View action = list.getChildAt(i).findViewById(actionId);
                assertTrue("部门入口应存在且可操作：" + actionId, action != null && action.isShown() && action.isEnabled());
                action.requestRectangleOnScreen(new Rect(0, 0, action.getWidth(), action.getHeight()), true);
                return true;
            }
            return false;
        }), "部门快捷入口应进入列表视口：" + departmentId);
        AtomicReference<float[]> touchPoint = new AtomicReference<>();
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.department_list);
            if (!list.isEnabled()) return false;
            for (int i = 0; i < list.getChildCount(); ++i) {
                int position = list.getChildAdapterPosition(list.getChildAt(i));
                if (position == RecyclerView.NO_POSITION) continue;
                if (((JSONObject) RecyclerListTestHelper.item(list, position)).optLong("id") == departmentId) {
                    View action = list.getChildAt(i).findViewById(actionId);
                    assertTrue(action.isShown());
                    if (!departmentRowFullyVisible(action, list)) return false;
                    assertTrue("部门入口应可点击：" + actionId, action.isEnabled() && action.isClickable());
                    Rect bounds = pageBounds(action);
                    touchPoint.set(new float[]{bounds.exactCenterX(), bounds.exactCenterY()});
                    return true;
                }
            }
            return false;
        }), "部门行快捷入口应可以操作：" + departmentId);
        float[] point = touchPoint.get();
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        try {
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("部门入口真实按下应注入成功", instrumentation.getUiAutomation().injectInputEvent(down, true));
        } finally { down.recycle(); }
        SystemClock.sleep(60);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point[0], point[1], 0);
        try {
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            assertTrue("部门入口真实抬起应注入成功", instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { up.recycle(); }
    }

    private void showDepartmentRow(long departmentId) {
        Rect[] footerBefore = onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            return new Rect[]{pageBounds(page.findViewById(R.id.department_table_header)),
                    pageBounds(page.findViewById(R.id.department_end))};
        });
        // 数据页与屏幕上的行数不同；短横屏可只有一行，先按部门 ID 定位再滚入视口。
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.department_list);
            if (!list.isEnabled()) return false;
            for (int position = 0; position < RecyclerListTestHelper.count(list); ++position) {
                if (((JSONObject) RecyclerListTestHelper.item(list, position)).optLong("id") != departmentId) continue;
                RecyclerListTestHelper.scroll(list, position, list.getPaddingTop());
                return true;
            }
            return false;
        }), "完整树应包含目标部门：" + departmentId);
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            RecyclerView list = page == null ? null : page.findViewById(R.id.department_list);
            if (list == null || !list.isEnabled()) return false;
            for (int i = 0; i < list.getChildCount(); ++i) {
                int position = list.getChildAdapterPosition(list.getChildAt(i));
                if (position == RecyclerView.NO_POSITION) continue;
                if (((JSONObject) RecyclerListTestHelper.item(list, position)).optLong("id") != departmentId) continue;
                View row = list.getChildAt(i);
                if (!departmentRowVerticallyVisible(row, list)) return false;
                for (int action : new int[]{R.id.business_row_state, R.id.department_row_staff, R.id.department_row_delete}) {
                    View control = row.findViewById(action);
                    if (control.isShown()) assertTrue("滚动后部门入口必须纵向完整可见：" + action,
                            departmentRowVerticallyVisible(control, list));
                }
                int[] footer = {R.id.department_table_header, R.id.department_end};
                for (int item = 0; item < footer.length; ++item) {
                    assertInsidePage(page, footer[item]);
                    assertEquals("滚动列表不能移动固定表头和尾行", footerBefore[item], pageBounds(page.findViewById(footer[item])));
                }
                return true;
            }
            return false;
        }), "目标部门行及入口应滚入可操作视口：" + departmentId);
    }

    private static boolean departmentRowFullyVisible(View view, RecyclerView list) {
        Rect visible = new Rect(), viewport = new Rect();
        return view.isShown() && view.getGlobalVisibleRect(visible) && list.getGlobalVisibleRect(viewport)
                && visible.width() == view.getWidth() && visible.height() == view.getHeight()
                && viewport.contains(visible);
    }

    private static boolean departmentRowVerticallyVisible(View view, RecyclerView list) {
        Rect bounds = pageBounds(view), viewport = new Rect();
        return view.isShown() && list.getGlobalVisibleRect(viewport) && bounds.height() > 0
                && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom;
    }

    @Test public void departmentEditorMovesBranchAndExcludesItsOwnDescendants() throws Exception {
        long parent = seedHierarchyFilters();
        scenario = ActivityScenario.launch(new Intent(app, DepartmentsActivity.class));
        awaitListTestOrientation(DepartmentsActivity.class);
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            RecyclerView list = page == null ? null : page.findViewById(R.id.department_list);
            return list != null && list.isEnabled() && RecyclerListTestHelper.count(list) == 4 && list.getChildCount() > 0;
        }), "编辑前部门目录应已读取");
        clickDepartmentRowAction(parent, R.id.business_row_state);
        awaitDepartmentEditor();
        onMain(() -> {
            Spinner parents = departmentDialog().findViewById(R.id.department_edit_parent);
            assertEquals("顶级部门以及其他门店可选，自身及两个后代不可选", 2, parents.getCount());
            for (int i = 0; i < parents.getCount(); ++i)
                assertFalse(parents.getItemAtPosition(i).toString().contains("测试总部门"));
            return null;
        });
        captureUi("department-parent-editor");
        clickDepartmentDialog(R.id.department_edit_cancel);
        awaitDepartmentEditorClosed();
        openDepartmentNamed("其他门店");
        onMain(() -> {
            Spinner parents = departmentDialog().findViewById(R.id.department_edit_parent);
            int target = -1;
            for (int i = 0; i < parents.getCount(); ++i)
                if ("测试总部门".equals(parents.getItemAtPosition(i).toString())) target = i;
            assertTrue("可以把顶级门店移动到另一个上级下", target > 0);
            parents.setSelection(target);
            return null;
        });
        instrumentation.waitForIdleSync();
        scenario.recreate();
        awaitDepartmentEditor();
        onMain(() -> {
            Spinner parents = departmentDialog().findViewById(R.id.department_edit_parent);
            assertEquals("仅修改上级部门的草稿也应在重建后保留", "测试总部门", parents.getSelectedItem().toString());
            assertTrue(departmentDialog().findViewById(R.id.department_edit_save).isEnabled());
            return null;
        });
        clickDepartmentDialog(R.id.department_edit_save);
        awaitDepartmentEditorClosed();
        app.io.submit(() -> {
            JSONObject moved = isolatedStore.departmentsPage("其他门店", 1, 20).rows.get(0);
            assertEquals(parent, moved.optLong("parent_id"));
            assertEquals("测试总部门 / 其他门店", moved.optString("path"));
            assertEquals(60L, isolatedStore.staffPage("", 0, parent, 1, 20).total);
            assertEquals(60L, isolatedStore.recordsPage("", 0, parent, 1, 20).total);
            assertEquals("测试总部门 / 其他门店", isolatedStore.staff(259).optString("department"));
            assertEquals("其他门店", isolatedStore.record("hierarchy-record-59").optString("department"));
            return null;
        }).get(10, TimeUnit.SECONDS);
        assertEquals(0, businessRequests.get());
    }

    private <T extends BaseMorningActivity> void assertPagerEdges(Class<T> type, int firstId,
            int previousId, int nextId, int lastId, boolean atFirst, boolean atLast) {
        onMain(() -> {
            T page = resumed(type);
            for (int id : new int[]{firstId, previousId, nextId, lastId}) {
                View control = page.findViewById(id);
                assertNotNull("分页动作必须存在", control);
                assertFalse("兼容分页动作在累计列表中保持隐藏", control.isShown());
            }
            assertEquals("首页边界应禁用首页动作", !atFirst, page.findViewById(firstId).isEnabled());
            assertEquals("首页边界应禁用上一页", !atFirst, page.findViewById(previousId).isEnabled());
            assertEquals("末页边界应禁用下一页", !atLast, page.findViewById(nextId).isEnabled());
            assertEquals("末页边界应禁用末页动作", !atLast, page.findViewById(lastId).isEnabled());
            return null;
        });
    }

    private <T extends BaseMorningActivity> void assertInlinePageInput(Class<T> type, int inputId,
            int confirmId, String expected) {
        onMain(() -> {
            T page = resumed(type);
            EditText input = page.findViewById(inputId);
            View confirm = page.findViewById(confirmId);
            assertNotNull("分页栏应直接提供页码输入框", input);
            assertTrue("兼容页码输入应可输入", input.isEnabled());
            assertTrue("兼容页码跳转应可操作", confirm.isEnabled());
            assertEquals("读取结束后内联框应显示实际页码", expected, input.getText().toString());
            return null;
        });
    }

    private <T extends BaseMorningActivity> void rejectPageJump(Class<T> type, int inputId,
            int confirmId, int infoId, String value) {
        onMain(() -> {
            T page = resumed(type);
            EditText input = page.findViewById(inputId);
            String before = page.<TextView>findViewById(infoId).getText().toString();
            input.setText(value);
            String accepted = input.getText().toString();
            // 数字输入框可先过滤字母；过滤后的空输入同样不能触发翻页。
            if ("abc".equals(value)) assertTrue("非数字不能成为有效页码", accepted.isEmpty() || !accepted.matches("[0-9]+"));
            assertTrue(page.findViewById(confirmId).performClick());
            assertEquals("无效输入不能改变当前分页数据", before, page.<TextView>findViewById(infoId).getText().toString());
            assertTrue("无效输入不能进入读取状态", input.isEnabled());
            assertEquals("无效页码应保留供操作员修改", accepted, input.getText().toString());
            return null;
        });
    }

    private <T extends BaseMorningActivity> void jumpToPage(Class<T> type, int inputId,
            int confirmId, String pageNumber) {
        onMain(() -> {
            T page = resumed(type);
            EditText input = page.findViewById(inputId);
            assertTrue("手输页码前输入框应可用", input.isEnabled());
            input.setText(pageNumber);
            assertEquals(pageNumber, input.getText().toString());
            assertTrue(page.findViewById(confirmId).performClick());
            assertFalse("读取目标页时应禁用重复跳转", input.isEnabled());
            assertFalse("读取目标页时应禁用确认按钮", page.findViewById(confirmId).isEnabled());
            return null;
        });
    }

    @Test public void staffRecyclerKeepsAdapterAndAnchorAcrossAppendRefreshReturnAndRecreation() throws Exception {
        app.io.submit(() -> {
            for (int index = 1; index <= 80; index++) isolatedStore.saveStaff(Jsons.obj("id", index,
                    "name", "滚动员工" + index, "staff_no", "SCROLL-" + index, "created_at", index));
            AppStore.DataPage window = isolatedStore.staffWindow("", 0, 0, 3, 24);
            assertEquals(3, window.page);
            assertEquals(72, window.rows.size());
            assertEquals(80, window.rows.get(0).optLong("id"));
            assertEquals(9, window.rows.get(71).optLong("id"));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, StaffActivity.class));
        awaitListTestOrientation(StaffActivity.class);
        awaitStaffPage("第 1 / 4 页 · 共 80 条", 24);
        RecyclerView.Adapter<?> adapter = onMain(() -> resumed(StaffActivity.class)
                .<RecyclerView>findViewById(R.id.staff_list).getAdapter());
        click(StaffActivity.class, R.id.staff_load_more);
        awaitStaffPage("第 2 / 4 页 · 共 80 条", 48);
        click(StaffActivity.class, R.id.staff_load_more);
        awaitStaffPage("第 3 / 4 页 · 共 80 条", 72);
        positionRecycler(StaffActivity.class, R.id.staff_list, 38, -7);
        String anchor = onMain(() -> RecyclerListTestHelper.item(resumed(StaffActivity.class)
                .findViewById(R.id.staff_list), 38).optString("id"));
        int offset = recyclerOffset(StaffActivity.class, R.id.staff_list);
        onMain(() -> {
            RecyclerView list = resumed(StaffActivity.class).findViewById(R.id.staff_list);
            assertSame("追加页不能重建员工 Adapter", adapter, list.getAdapter());
            Set<String> ids = new HashSet<>();
            for (JSONObject row : resumed(StaffActivity.class).staff) ids.add(row.optString("id"));
            assertEquals(72, ids.size());
            assertTrue(RecyclerListTestHelper.click(list, RecyclerListTestHelper.first(list)));
            return null;
        });
        awaitPage(StaffEditActivity.class, R.id.staff_edit_save);
        onMain(() -> { resumed(StaffEditActivity.class).finish(); return null; });
        awaitStaffPage("第 3 / 4 页 · 共 80 条", 72);
        awaitRecyclerAnchor(StaffActivity.class, R.id.staff_list, anchor, offset);
        app.io.submit(() -> isolatedStore.saveStaff(Jsons.obj("id", 81, "name", "最新员工",
                "staff_no", "SCROLL-81", "created_at", 81))).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyStaffChanged(); return null; });
        awaitStaffPage("第 3 / 4 页 · 共 81 条", 72);
        awaitRecyclerAnchor(StaffActivity.class, R.id.staff_list, anchor, offset);
        onMain(() -> {
            assertSame("资料刷新和返回均复用员工 Adapter", adapter,
                    resumed(StaffActivity.class).<RecyclerView>findViewById(R.id.staff_list).getAdapter());
            return null;
        });
        scenario.recreate();
        awaitStaffPage("第 3 / 4 页 · 共 81 条", 72);
        awaitRecyclerAnchor(StaffActivity.class, R.id.staff_list, anchor, offset);
        int oldPosition = onMain(() -> RecyclerListTestHelper.first(resumed(StaffActivity.class)
                .findViewById(R.id.staff_list)));
        app.io.submit(() -> { isolatedStore.removeStaff(Long.parseLong(anchor)); return null; }).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyStaffChanged(); return null; });
        awaitStaffPage("第 3 / 4 页 · 共 80 条", 72);
        await(() -> onMain(() -> {
            RecyclerView list = resumed(StaffActivity.class).findViewById(R.id.staff_list);
            View first = RecyclerListTestHelper.row(list, RecyclerListTestHelper.first(list));
            return RecyclerListTestHelper.first(list) == oldPosition && recyclerIdle(resumed(StaffActivity.class))
                    && !anchor.equals(RecyclerListTestHelper.item(list, oldPosition).optString("id"))
                    && first != null && ((LinearLayoutManager) list.getLayoutManager()).getDecoratedTop(first)
                    - list.getPaddingTop() == offset;
        }), "锚点员工删除后应留在邻近行并保留偏移");
    }

    @Test public void recordsRecyclerKeepsAdapterAndAnchorAndClampsAfterLastPageDisappears() throws Exception {
        app.io.submit(() -> {
            for (int index = 1; index <= 80; index++) isolatedStore.saveRecord(Jsons.obj("id", "scroll-record-" + index,
                    "staff_id", index, "staff_name", "滚动记录" + index, "created_at", index, "status", 1));
            AppStore.DataPage window = isolatedStore.recordsWindow("", 0, 0,
                    cc.huoyan.opensource.morningcheck.domain.RecordDateRange.all().resolvedAt(
                            System.currentTimeMillis(), java.util.TimeZone.getDefault()), 3, 24);
            assertEquals(3, window.page);
            assertEquals(72, window.rows.size());
            assertEquals("scroll-record-80", window.rows.get(0).optString("id"));
            assertEquals("scroll-record-9", window.rows.get(71).optString("id"));
        }).get(10, TimeUnit.SECONDS);
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class));
        awaitListTestOrientation(RecordsActivity.class);
        awaitRecordPage("第 1 / 4 页 · 共 80 条", 24);
        RecyclerView.Adapter<?> adapter = onMain(() -> resumed(RecordsActivity.class)
                .<RecyclerView>findViewById(R.id.records_list).getAdapter());
        click(RecordsActivity.class, R.id.records_load_more);
        awaitRecordPage("第 2 / 4 页 · 共 80 条", 48);
        click(RecordsActivity.class, R.id.records_load_more);
        awaitRecordPage("第 3 / 4 页 · 共 80 条", 72);
        positionRecycler(RecordsActivity.class, R.id.records_list, 38, -7);
        String anchor = onMain(() -> RecyclerListTestHelper.item(resumed(RecordsActivity.class)
                .findViewById(R.id.records_list), 38).optString("id"));
        int offset = recyclerOffset(RecordsActivity.class, R.id.records_list);
        onMain(() -> {
            RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list);
            assertSame("追加页不能重建记录 Adapter", adapter, list.getAdapter());
            assertTrue(RecyclerListTestHelper.click(list, RecyclerListTestHelper.first(list)));
            return null;
        });
        await(() -> onMain(() -> {
            RecordDetailActivity detail = resumed(RecordDetailActivity.class);
            return detail != null && detail.currentRecord != null && anchor.equals(detail.currentRecord.optString("id"));
        }), "真实行点击应打开当前锚点记录详情");
        onMain(() -> { resumed(RecordDetailActivity.class).finish(); return null; });
        awaitRecordPage("第 3 / 4 页 · 共 80 条", 72);
        awaitRecyclerAnchor(RecordsActivity.class, R.id.records_list, anchor, offset);
        app.io.submit(() -> { isolatedStore.saveRecord(Jsons.obj("id", "scroll-record-81", "staff_id", 81,
                "staff_name", "最新记录", "created_at", 81, "status", 1)); return null; }).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyRecordsChanged(); return null; });
        awaitRecordPage("第 3 / 4 页 · 共 81 条", 72);
        awaitRecyclerAnchor(RecordsActivity.class, R.id.records_list, anchor, offset);
        onMain(() -> {
            assertSame("返回和资料刷新均复用记录 Adapter", adapter,
                    resumed(RecordsActivity.class).<RecyclerView>findViewById(R.id.records_list).getAdapter());
            return null;
        });
        scenario.recreate();
        awaitRecordPage("第 3 / 4 页 · 共 81 条", 72);
        awaitRecyclerAnchor(RecordsActivity.class, R.id.records_list, anchor, offset);
        app.io.submit(() -> isolatedStore.getWritableDatabase().delete("records", "id<>?",
                new String[]{"scroll-record-1"})).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyRecordsChanged(); return null; });
        awaitRecordPage("第 1 / 1 页 · 共 1 条", 1);
        app.io.submit(() -> isolatedStore.getWritableDatabase().delete("records", null, null)).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyRecordsChanged(); return null; });
        awaitRecordPage("第 1 / 1 页 · 共 0 条", 0);
        onMain(() -> {
            assertTrue(resumed(RecordsActivity.class).findViewById(R.id.records_empty).isShown());
            assertEquals("没有更多了", resumed(RecordsActivity.class).<TextView>findViewById(R.id.records_load_more).getText().toString());
            return null;
        });
    }

    private static boolean recyclerIdle(BaseMorningActivity page) {
        try {
            java.lang.reflect.Field loading = page.getClass().getDeclaredField("pageLoading");
            loading.setAccessible(true);
            if (loading.getBoolean(page)) return false;
            try {
                java.lang.reflect.Field submitting = page.getClass().getDeclaredField("submittingRows");
                submitting.setAccessible(true);
                return !submitting.getBoolean(page);
            } catch (NoSuchFieldException ignored) {
                return true;
            }
        } catch (ReflectiveOperationException invalid) {
            throw new AssertionError(invalid);
        }
    }

    private <T extends BaseMorningActivity> void positionRecycler(Class<T> type, int listId, int position, int offset) {
        onMain(() -> { RecyclerListTestHelper.scroll(resumed(type).findViewById(listId), position, offset); return null; });
        await(() -> onMain(() -> {
            T page = resumed(type);
            RecyclerView list = page == null ? null : page.findViewById(listId);
            return list != null && RecyclerListTestHelper.first(list) == position
                    && !list.isLayoutRequested() && RecyclerListTestHelper.row(list, position) != null;
        }), "指定业务行应滚入视口：" + position);
    }

    private <T extends BaseMorningActivity> int recyclerOffset(Class<T> type, int listId) {
        return onMain(() -> {
            RecyclerView list = resumed(type).findViewById(listId);
            View first = RecyclerListTestHelper.row(list, RecyclerListTestHelper.first(list));
            return ((LinearLayoutManager) list.getLayoutManager()).getDecoratedTop(first) - list.getPaddingTop();
        });
    }

    private <T extends BaseMorningActivity> void awaitRecyclerAnchor(Class<T> type, int listId, String id, int offset) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            RecyclerView list = page == null ? null : page.findViewById(listId);
            if (list == null || !recyclerIdle(page) || list.isLayoutRequested()) return false;
            int position = RecyclerListTestHelper.first(list);
            View first = RecyclerListTestHelper.row(list, position);
            return first != null && id.equals(RecyclerListTestHelper.item(list, position).optString("id"))
                    && ((LinearLayoutManager) list.getLayoutManager()).getDecoratedTop(first) - list.getPaddingTop() == offset;
        }), "刷新、返回或重建后应恢复业务锚点及偏移：" + id);
    }

    private void awaitDepartmentTree(int count) {
        await(() -> onMain(() -> {
            DepartmentsActivity page = resumed(DepartmentsActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.department_list);
            return list != null && list.isEnabled() && RecyclerListTestHelper.count(list) == count
                    && recyclerIdle(page) && !list.isLayoutRequested()
                    && (count == 0 || ("已显示全部 " + count + " 个部门").contentEquals(
                            page.<TextView>findViewById(R.id.department_end).getText()));
        }), "部门完整树未收敛到：" + count);
    }

    private void awaitStaffPage(String expected, int count) {
        await(() -> onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.staff_list);
            if (!expected.contentEquals(((TextView) page.findViewById(R.id.staff_page_info)).getText())
                    || RecyclerListTestHelper.count(list) != count || !list.isEnabled()) return false;
            if (expected.contains("共 45 条") && ((EditText) page.findViewById(R.id.staff_search)).getText().toString().equals("后厨"))
                for (int i = 0; i < count; i++) assertEquals("后厨", personAt(page, i).optString("department"));
            return true;
        }), "员工页未收敛到：" + expected);
    }

    private void awaitRecordPage(String expected, int count) {
        await(() -> onMain(() -> {
            RecordsActivity page = resumed(RecordsActivity.class);
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.records_list);
            if (!expected.contentEquals(((TextView) page.findViewById(R.id.records_page_info)).getText())
                    || RecyclerListTestHelper.count(list) != count || !list.isEnabled()) return false;
            int filter = ((Spinner) page.findViewById(R.id.records_filter)).getSelectedItemPosition();
            for (int i = 0; i < count; i++) {
                JSONObject record = (JSONObject) RecyclerListTestHelper.item(list, i);
                if (filter == 1) assertEquals(1, record.optInt("status"));
                if (filter == 2) assertEquals(0, record.optInt("status"));
            }
            return true;
        }), "记录页未收敛到：" + expected);
    }

    private static JSONObject personAt(StaffActivity page, int position) {
        return (JSONObject) RecyclerListTestHelper.item(((RecyclerView) page.findViewById(R.id.staff_list)), position);
    }

    private void selectRecordFilter(int position) {
        onMain(() -> {
            ((Spinner) resumed(RecordsActivity.class).findViewById(R.id.records_filter)).setSelection(position);
            return null;
        });
    }

    private <T extends BaseMorningActivity> void click(Class<T> type, int id) {
        onMain(() -> {
            assertTrue(resumed(type).findViewById(id).isEnabled());
            assertTrue(resumed(type).findViewById(id).performClick());
            return null;
        });
    }

    private <T extends Activity> T resumed(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }

    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
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
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
