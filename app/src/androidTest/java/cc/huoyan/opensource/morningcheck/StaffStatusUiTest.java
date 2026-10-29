package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.Layout;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
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
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 员工列表在模拟器或指定 C106 / NB55 的 UUID 数据库中验收，SDK 使用本机 503 端点。 */
@RunWith(AndroidJUnit4.class)
public final class StaffStatusUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger cloudRequests = new AtomicInteger();
    private final AtomicInteger sdkRequests = new AtomicInteger();
    private final Map<Long, JSONObject> fixtures = new LinkedHashMap<>();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private String isolatedWsEndpoint;
    private ActivityScenario<? extends Activity> scenario;

    @Before public void isolateStaffFixtures() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assumeTrue("仅允许模拟器或指定 C106 / NB55 运行隔离夹具",
                "C106".equals(Build.MODEL) || "NB55".equals(Build.MODEL)
                        || x86 && (hardware.contains("ranchu") || hardware.contains("goldfish")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("不能中断现有门店绑定", onMain(() -> !app.shopLogin().getState().isRunning()));
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
        drainIo();
        originalStore = app.store;
        databaseName = "staff-status-ui-" + UUID.randomUUID() + ".db";
        isolatedStore = new AppStore(new ContextWrapper(app) {
            @Override public File getDatabasePath(String name) { return app.getDatabasePath(databaseName); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        onMain(() -> { app.store = isolatedStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/cloud")) cloudRequests.incrementAndGet();
                else if ("/sdk".equals(request.getPath())) sdkRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("员工资料界面回归：本机 SDK 保持未连接");
            }
        });
        // LocalWsTransport 会为 127.0.0.1/localhost 绑定现场壳以维持存活；127.0.0.2
        // 同属本机回环，但不命中该规则，所有请求只到这份 MockWebServer。
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        isolatedWsEndpoint = "ws://127.0.0.2:" + endpoint.getPort() + "/sdk";
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, isolatedWsEndpoint)
                .putString("cloud_url", "http://127.0.0.2:" + endpoint.getPort() + "/cloud").putString("cloud_token", "LOCAL-STAFF-UI-TEST")
                .putBoolean("voice", false).commit());
        String fontIndex = InstrumentationRegistry.getArguments().getString("staffUiFontIndex");
        if (fontIndex != null) assertTrue(preferences.edit().putInt(AppFontSettings.KEY, Integer.parseInt(fontIndex)).commit());
        onMain(() -> { app.sdkSession().reconnect(MorningSdkSettings.read(preferences)); assertIsolatedConnection(); return null; });
        await(() -> sdkRequests.get() > 0, "WS 连接请求应实际抵达本机测试端点");
        LocalDate today = LocalDate.now();
        fixtures.put(99101L, person(99101, "空配置员工", "", "", "", true));
        fixtures.put(99102L, person(99102, "缺失配置员工", null, null, null, true));
        fixtures.put(99103L, person(99103, "有效健康证员工", "S003", "后厨", today.plusDays(30).toString(), true));
        fixtures.put(99104L, person(99104, "停用过期员工", "S004", "前厅", today.minusDays(1).toString(), false));
        fixtures.put(99105L, person(99105, "当日健康证员工", "S005", "备餐", today.toString(), true));
        fixtures.put(99106L, person(99106, "需要换行显示的长姓名测试员工", "STAFF-LONG-NUMBER-20261010-006", "后厨备餐与清洁部门", today.plusDays(60).toString(), true));
        // 直接向独立库保存原始 JSON，保留 null 字段，覆盖历史资料尚未配置的路径。
        app.io.submit(() -> {
            SQLiteDatabase db = isolatedStore.getWritableDatabase();
            for (JSONObject person : fixtures.values()) {
                ContentValues fields = new ContentValues();
                fields.put("id", person.optString("id"));
                fields.put("data", person.toString());
                fields.put("time", person.optLong("created_at"));
                fields.put("name", person.optString("name"));
                fields.put("department", string(person, "department"));
                db.insertOrThrow("staff", null, fields);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    @After public void restoreStaffFixtures() throws Exception {
        if (app == null || originalStore == null) return;
        try {
            if (scenario != null) scenario.close();
            onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            drainIo();
            assertEquals("浏览员工列表与详情不能调用业务云", 0, cloudRequests.get());
        } finally {
            try {
                onMain(() -> { app.store = originalStore; return null; });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue("仅删除本用例 UUID 数据库", app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally { if (endpoint != null) endpoint.shutdown(); }
        }
    }









    @Test public void listShowsDesignColumnsAndResetsRecycledColors() throws Exception {
        launchList();
        onMain(() -> {
            StaffActivity activity = resumed(StaffActivity.class);
            RecyclerView list = activity.findViewById(R.id.staff_list);
            assertEquals(fixtures.size(), RecyclerListTestHelper.count(list));
            assertSingleTableHeader(activity);
            RecyclerView.ViewHolder recycled = null;
            // 依次复用同一行，并反向重绑，使有效/过期/缺省的文字和颜色不会残留。
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < RecyclerListTestHelper.count(list); i++) {
                int position = pass == 0 ? i : RecyclerListTestHelper.count(list) - i - 1;
                JSONObject person = RecyclerListTestHelper.item(list, position);
                RecyclerView.ViewHolder holder = RecyclerListTestHelper.bind(list, position, recycled);
                View row = holder.itemView;
                if (recycled != null) assertSame("员工列表应复用传入的行", recycled, holder);
                row.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
                assertFields(row, person, true);
                assertTitledColumns(activity, (ViewGroup) row);
                recycled = holder;
            }
            return null;
        });
        // 所有列留在同一视口，列表滚动不移动公共表头。
        Map<Integer, Rect> fixed = onMain(() -> fixedControls(resumed(StaffActivity.class), true));
        for (int position = 0; position < fixtures.size(); position++) {
            showListPosition(position);
            final int target = position;
            onMain(() -> {
                StaffActivity activity = resumed(StaffActivity.class);
                RecyclerView list = activity.findViewById(R.id.staff_list);
                View row = RecyclerListTestHelper.row(list, target);
                assertFields(row, (JSONObject) RecyclerListTestHelper.item(list, target), true);
                assertTitledColumns(activity, (ViewGroup) row);
                assertFixedControls(activity, fixed);
                assertIsolatedConnection();
                return null;
            });
            assertEachColumnCanBeRead(position);
        }
        showListPosition(0);
        captureList();
    }

    @Test public void listHeaderAndActionsStayFixedWhileRowsScroll() throws Exception {
        // 仅扩充本用例的 UUID 数据库，不向现场库或业务云添加资料。
        ArrayList<JSONObject> extra = new ArrayList<>();
        for (int index = 0; fixtures.size() < AppStore.DEFAULT_PAGE_SIZE; index++) {
            long id = 99200L + index;
            JSONObject value = person(id, "用于纵向滚动验收的长姓名测试员工第" + index + "号", "LONG-STAFF-NUMBER-" + index,
                    "用于检查列表滚动和列对齐的后厨备餐与清洁管理部门", LocalDate.now().plusDays(30).toString(), true);
            fixtures.put(id, value);
            extra.add(value);
        }
        app.io.submit(() -> { for (JSONObject value : extra) isolatedStore.saveStaff(value); }).get(10, TimeUnit.SECONDS);
        launchList();
        Map<Integer, Rect> fixed = onMain(() -> fixedControls(resumed(StaffActivity.class), true));
        showListPosition(fixtures.size() - 1);
        onMain(() -> {
            StaffActivity activity = resumed(StaffActivity.class);
            RecyclerView list = activity.findViewById(R.id.staff_list);
            assertTrue("验收必须实际滚动到较后面的数据行", RecyclerListTestHelper.first(list) > 0);
            assertSingleTableHeader(activity);
            assertFixedControls(activity, fixed);
            return null;
        });
        assertEachColumnCanBeRead(fixtures.size() - 1);
        onMain(() -> { assertFixedControls(resumed(StaffActivity.class), fixed); return null; });
        showListPosition(0);
    }

    private String[] staffSnapshot(long id) {
        try (Cursor cursor = isolatedStore.getReadableDatabase().query("staff", new String[]{"data"},
                "id=?", new String[]{String.valueOf(id)}, null, null, null)) {
            assertTrue("快照中的目标员工应存在", cursor.moveToFirst());
            return new String[]{isolatedStore.staff(id).toString(), cursor.getString(0)};
        }
    }

    private void assertFields(View root, JSONObject person, boolean row) {
        int[] ids = new int[]{R.id.staff_row_number, R.id.staff_row_enabled, R.id.staff_row_department, R.id.staff_row_health};
        String number = string(person, "staff_no"), department = string(person, "department"), expiry = string(person, "health_expiry");
        boolean enabled = person.optBoolean("enabled", true);
        boolean expired = !expiry.isEmpty() && LocalDate.parse(expiry).isBefore(LocalDate.now());
        assertTextAndColor(root, ids[0], number.isEmpty() ? "无" : number, number.isEmpty() ? Ui.MUTED : Ui.INK);
        assertTextAndColor(root, ids[1], enabled ? (row ? "启用" : "启用中") : (row ? "停用" : "已停用"), enabled ? Ui.GREEN : Ui.ORANGE);
        assertTextAndColor(root, ids[2], department.isEmpty() ? "未分配" : department, row ? Ui.INK : department.isEmpty() ? Ui.MUTED : Ui.BLUE);
        assertTextAndColor(root, ids[3], expiry.isEmpty() ? "未分配" : (expired ? "已过期" : "未过期") + " · " + expiry,
                expiry.isEmpty() ? Ui.MUTED : expired ? Ui.ORANGE : Ui.GREEN);
        assertFalse("缺省字段不能显示 JSON null", text(root, ids[0]).contains("null") || text(root, ids[2]).contains("null") || text(root, ids[3]).contains("null"));
    }

    private static void assertTextAndColor(View root, int id, String expected, int color) {
        TextView value = root.findViewById(id);
        assertNotNull("缺少状态字段 " + id, value);
        assertEquals(expected, value.getText().toString());
        assertEquals("字段颜色应与业务状态相符：" + expected, color, value.getCurrentTextColor());
    }

    private static void assertIndependentRows(ViewGroup root, int[] ids) {
        ArrayList<Rect> fields = new ArrayList<>();
        for (int id : ids) {
            TextView value = root.findViewById(id);
            assertEquals("独立字段应可见", View.VISIBLE, value.getVisibility());
            assertTrue("字段行应有实际高度", value.getHeight() > 0);
            Rect bounds = new Rect(); value.getDrawingRect(bounds);
            root.offsetDescendantRectToMyCoords(value, bounds);
            for (Rect other : fields) assertFalse("工号、状态、部门及健康证不能互相覆盖", Rect.intersects(other, bounds));
            fields.add(bounds);
        }
    }

    private static void assertSingleTableHeader(StaffActivity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        ViewGroup header = activity.findViewById(R.id.staff_table_header);
        assertEquals(1, countViews(root, R.id.staff_table_header));
        assertTrue(header.isShown());
        assertEquals(6, header.getChildCount());
        String[] titles = {"姓名", "工号", "部门", "人脸", "状态", "操作"};
        for (int i = 0; i < titles.length; i++) {
            assertEquals(titles[i], ((TextView) header.getChildAt(i)).getText().toString());
            assertFullyVisible(header.getChildAt(i));
        }
        assertEquals("固定表格应填满列表视口", header.getWidth(),
                activity.findViewById(R.id.staff_list).getWidth());
    }

    private static int countViews(View root, int id) {
        int count = root.getId() == id ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) count += countViews(group.getChildAt(index), id);
        }
        return count;
    }

    private static void assertTitledColumns(StaffActivity activity, ViewGroup row) {
        ViewGroup header = activity.findViewById(R.id.staff_table_header);
        int[] columns = {0, 1, 2, 3, 4, 6};
        for (int i = 0; i < columns.length; i++) {
            View title = header.getChildAt(i), value = row.getChildAt(columns[i]);
            assertTrue(value.getWidth() > 0 && value.getHeight() > 0);
            assertTrue("标题与内容列左边界一致", Math.abs(title.getLeft() - value.getLeft()) <= 1);
            assertTrue("标题与内容列右边界一致", Math.abs(title.getRight() - value.getRight()) <= 1);
            int titleLeft = title.getLeft(), valueLeft = value.getLeft();
            if (row.isAttachedToWindow()) {
                Rect titleBounds = screenBounds(title), valueBounds = screenBounds(value);
                assertEquals("实际列表与表头的屏幕左边界一致", titleBounds.left, valueBounds.left, 1);
                assertEquals("实际列表与表头的屏幕右边界一致", titleBounds.right, valueBounds.right, 1);
                titleLeft = titleBounds.left;
                valueLeft = valueBounds.left;
            }
            if (value instanceof TextView) {
                TextView heading = (TextView) title, content = (TextView) value;
                int expectedGravity = i == 0 ? android.view.Gravity.START : android.view.Gravity.CENTER_HORIZONTAL;
                assertEquals("姓名表头靠左，其余表头居中", expectedGravity,
                        heading.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                assertEquals("数据文字必须与表头采用相同对齐方式", expectedGravity,
                        content.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                assertEquals("表头左右留白必须对称", heading.getCompoundPaddingLeft(), heading.getCompoundPaddingRight());
                assertEquals("数据左右留白必须对称", content.getCompoundPaddingLeft(), content.getCompoundPaddingRight());
                for (int line = 0; line < content.getLayout().getLineCount(); line++) {
                    if (i == 0) {
                        float titleStart = titleLeft + heading.getCompoundPaddingLeft()
                                + heading.getLayout().getLineLeft(0);
                        float contentStart = valueLeft + content.getCompoundPaddingLeft()
                                + content.getLayout().getLineLeft(line);
                        assertEquals("姓名文字与表头左侧对齐", titleStart, contentStart, 1f);
                    } else {
                        float titleCenter = titleLeft + heading.getCompoundPaddingLeft()
                                + heading.getLayout().getWidth() / 2f;
                        float contentCenter = valueLeft + content.getCompoundPaddingLeft()
                                + (content.getLayout().getLineLeft(line) + content.getLayout().getLineRight(line)) / 2f;
                        assertEquals("每行实际文字必须位于表头列中心", titleCenter, contentCenter, 1f);
                    }
                }
            }
        }
        TextView name = row.findViewById(R.id.staff_row_name), number = row.findViewById(R.id.staff_row_number);
        assertEquals("姓名与工号列宽比例应为 1:1.5", (name.getWidth() + number.getWidth()) / 2.5,
                name.getWidth(), 1);
        assertTrue("姓名必须在工号左侧", name.getRight() <= number.getLeft());
        assertEquals("姓名与工号应在同一行居中显示", name.getTop() + name.getHeight() / 2,
                number.getTop() + number.getHeight() / 2, 1);
        assertEquals("健康证保留在详情，不占用设计表格", View.GONE,
                row.findViewById(R.id.staff_row_health).getVisibility());
        for (int id : new int[]{R.id.staff_row_enroll, R.id.staff_row_edit, R.id.staff_row_delete}) {
            TextView action = row.findViewById(id);
            assertTrue(action.getWidth() > 0 && action.getHeight() > 0);
            Layout layout = action.getLayout();
            assertNotNull(layout);
            assertEquals(0, layout.getEllipsisCount(0));
            assertTrue(layout.getHeight() <= action.getHeight());
        }
    }

    private static Rect descendantBounds(ViewGroup root, View child) {
        Rect bounds = new Rect();
        child.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
    }

    private static Rect screenBounds(View view) {
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        return new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    }

    private static Map<Integer, Rect> fixedControls(StaffActivity activity, boolean includeHeader) {
        Map<Integer, Rect> result = new LinkedHashMap<>();
        for (int id : new int[]{R.id.staff_search, R.id.staff_add, R.id.staff_departments}) {
            assertFullyVisible(activity.findViewById(id));
            result.put(id, screenBounds(activity.findViewById(id)));
        }
        if (includeHeader) result.put(R.id.staff_table_header, screenBounds(activity.findViewById(R.id.staff_table_header)));
        return result;
    }

    private static void assertFixedControls(StaffActivity activity, Map<Integer, Rect> expected) {
        for (Map.Entry<Integer, Rect> entry : expected.entrySet())
            assertEquals("滚动数据不能移动公共表头、搜索或操作控件", entry.getValue(), screenBounds(activity.findViewById(entry.getKey())));
    }

    private void assertEachColumnCanBeRead(int position) {
        onMain(() -> {
            StaffActivity activity = resumed(StaffActivity.class);
            RecyclerView list = activity.findViewById(R.id.staff_list);
            ViewGroup row = (ViewGroup) RecyclerListTestHelper.row(list, position);
            assertTitledColumns(activity, row);
            for (int id : rowIds()) {
                View field = row.findViewById(id);
                Rect visible = new Rect();
                assertTrue(field.getGlobalVisibleRect(visible));
                assertEquals(field.getWidth(), visible.width());
                assertEquals(field.getHeight(), visible.height());
            }
            for (int id : new int[]{R.id.staff_row_enroll, R.id.staff_row_edit, R.id.staff_row_delete})
                assertFullyVisible(row.findViewById(id));
            return null;
        });
    }

    private void assertIsolatedConnection() {
        assertEquals("夹具必须显式使用 WS，不能退回 AIDL", SdkConfig.Channel.WS, app.sdkSession().client().getActiveChannel());
        assertEquals("页面不能改写测试端点", isolatedWsEndpoint, MorningSdkSettings.read(preferences).getWsEndpoint());
        assertTrue("共享会话应使用当前本机测试配置", app.sdkSession().matchesConfiguration(SdkConfig.webSocket(isolatedWsEndpoint)));
    }



    private static void assertFullyVisible(View view) {
        String label = view.getId() == View.NO_ID ? ((TextView) view).getText().toString()
                : view.getResources().getResourceEntryName(view.getId());
        assertTrue("字段或按钮应实际显示：" + label, view.isShown());
        assertTrue("字段或按钮应有完整尺寸：" + label, view.getWidth() > 0 && view.getHeight() > 0);
        Rect visible = new Rect();
        assertTrue("字段或按钮应进入实际视口：" + label, view.getGlobalVisibleRect(visible));
        assertEquals("字段或按钮不能被父容器横向裁切：" + label, view.getWidth(), visible.width());
        assertEquals("字段或按钮不能被父容器纵向裁切：" + label, view.getHeight(), visible.height());
        Rect viewport = new Rect();
        assertTrue(view.getRootView().getGlobalVisibleRect(viewport));
        assertTrue("字段或按钮应完全位于窗口视口内：" + label, viewport.contains(visible));
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Layout layout = text.getLayout();
            assertNotNull("实际文字布局应已完成：" + label, layout);
            if (view.getId() != R.id.staff_row_name)
                for (int line = 0; line < layout.getLineCount(); line++)
                    assertEquals("详情、状态、日期及按钮文字不能省略：" + label, 0, layout.getEllipsisCount(line));
            int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
            assertTrue("字段或按钮文字不能纵向裁切：" + label + "，文字高度=" + layout.getHeight()
                    + "，可用高度=" + availableHeight, layout.getHeight() <= availableHeight);
        }
    }

    private void showListPosition(int position) {
        onMain(() -> { RecyclerListTestHelper.scroll(((RecyclerView) resumed(StaffActivity.class).findViewById(R.id.staff_list)), position, 0); return null; });
        await(() -> onMain(() -> {
            RecyclerView list = resumed(StaffActivity.class).findViewById(R.id.staff_list);
            View row = RecyclerListTestHelper.row(list, position);
            if (row == null) return false;
            JSONObject person = (JSONObject) RecyclerListTestHelper.item(list, position);
            if (!person.optString("name").equals(text(row, R.id.staff_row_name))) return false;
            for (int id : rowIds()) {
                View field = row.findViewById(id);
                Rect fieldBounds = screenBounds(field), listBounds = screenBounds(list);
                if (!field.isShown() || field.getWidth() <= 0 || field.getHeight() <= 0
                        || fieldBounds.top < listBounds.top || fieldBounds.bottom > listBounds.bottom) return false;
            }
            return true;
        }), "员工行滚动后各列的垂直信息应完整进入列表视口：" + position);
    }

    private static int[] rowIds() { return new int[]{R.id.staff_row_name, R.id.staff_row_number, R.id.staff_row_department, R.id.staff_row_enabled, R.id.staff_row_face}; }
    private static String text(View root, int id) { return ((TextView) root.findViewById(id)).getText().toString(); }
    private static String string(JSONObject person, String key) { return person.isNull(key) ? "" : person.optString(key, "").trim(); }
    private static JSONObject person(long id, String name, String number, String department, String expiry, boolean enabled) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("staff_no", number == null ? JSONObject.NULL : number)
                .put("department", department == null ? JSONObject.NULL : department).put("health_expiry", expiry == null ? JSONObject.NULL : expiry)
                .put("enabled", enabled).put("face_registered", false).put("created_at", id);
    }

    private void launchList() {
        scenario = ActivityScenario.launch(new Intent(app, StaffActivity.class));
        awaitExpectedStaffOrientation(StaffActivity.class);
        await(() -> onMain(() -> {
            StaffActivity activity = resumed(StaffActivity.class);
            if (activity == null) return false;
            RecyclerView list = activity.findViewById(R.id.staff_list);
            return RecyclerListTestHelper.count(list) == fixtures.size() && list.isEnabled() && list.getWidth() > 0;
        }), "员工列表应读取独立夹具并完成布局");
    }



    private <T extends Activity> void awaitExpectedStaffOrientation(Class<T> type) {
        String expected = InstrumentationRegistry.getArguments().getString("expectedStaffLayoutOrientation", "");
        if (expected.isEmpty()) return;
        assertTrue("验收方向只允许 portrait 或 landscape", "portrait".equals(expected) || "landscape".equals(expected));
        boolean portrait = "portrait".equals(expected);
        await(() -> onMain(() -> resumed(type) != null), "指定方向前员工页面应已恢复");
        onMain(() -> { resumed(type).setRequestedOrientation(portrait
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
        await(() -> onMain(() -> {
            T page = resumed(type);
            if (page == null) return false;
            View root = page.findViewById(android.R.id.content);
            return root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested()
                    && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && page.getResources().getConfiguration().orientation == (portrait
                            ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "实际员工页面窗口应符合验收方向：" + expected);
    }

    /** 可选截图只写应用自己的外部 files；由执行方拉回工作区 codexFile。 */
    private void captureList() throws Exception {
        String prefix = InstrumentationRegistry.getArguments().getString("staffUiCapturePrefix", "");
        if (prefix.isEmpty()) return;
        instrumentation.waitForIdleSync();
        // 等待系统合成器的旋转过渡结束，再保存实际列布局。
        SystemClock.sleep(500);
        File directory = new File(app.getExternalFilesDir(null), "staff-status-ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory,
                prefix.replaceAll("[^A-Za-z0-9_-]", "_") + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }

    private static JSONObject measure(View view) throws Exception {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        TextView text = (TextView) view;
        return new JSONObject().put("id", view.getResources().getResourceEntryName(view.getId())).put("text", text.getText().toString())
                .put("color", String.format(Locale.ROOT, "#%08X", text.getCurrentTextColor())).put("x", origin[0]).put("y", origin[1])
                .put("width", view.getWidth()).put("height", view.getHeight());
    }
    private void drainIo() throws Exception {
        instrumentation.waitForIdleSync();
        app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS);
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }
    private <T extends Activity> T resumed(Class<T> type) {
        for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (type.isInstance(activity)) return type.cast(activity);
        return null;
    }
    private void finishPages() {
        for (Stage stage : Stage.values()) for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
            if (activity.getPackageName().equals(app.getPackageName()) && !activity.isFinishing()) activity.finish();
    }
    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return value.get();
    }
    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, Object> original) {
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
        assertTrue(editor.commit()); assertEquals(original, preferences.getAll());
    }
    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}

