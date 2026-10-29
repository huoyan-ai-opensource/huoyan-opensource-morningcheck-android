package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

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
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;

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
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 记录展示只在 x86 模拟器 UUID 库验收；不调用业务后端，也不启动真实 SDK 壳。 */
@RunWith(AndroidJUnit4.class)
public final class RecordPresentationUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final String prefix = "record-presentation-" + UUID.randomUUID();
    private final Map<String, JSONObject> fixtures = new LinkedHashMap<>();
    private final AtomicInteger cloudRequests = new AtomicInteger(), sdkRequests = new AtomicInteger();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName, wsEndpoint;
    private ActivityScenario<? extends Activity> scenario;

    @Before public void isolateRecords() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT), device = Build.DEVICE.toLowerCase(Locale.ROOT);
        assumeTrue("记录展示夹具只允许 x86 模拟器，不读写现场机器",
                x86 && (hardware.contains("ranchu") || hardware.contains("goldfish") || device.startsWith("generic_x86")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("不能中断已有门店绑定", onMain(() -> !app.shopLogin().getState().isRunning()));
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
        drainIo();
        originalStore = app.store;
        databaseName = prefix + ".db";
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
                return new MockResponse().setResponseCode(503).setBody("仅验收晨检记录界面，本机 SDK 保持未就绪");
            }
        });
        // 127.0.0.2 同属回环，但不命中 LocalWsTransport 对 localhost 的真实壳保活。
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        wsEndpoint = "ws://127.0.0.2:" + endpoint.getPort() + "/sdk";
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, wsEndpoint)
                .putString("cloud_url", "http://127.0.0.2:" + endpoint.getPort() + "/cloud")
                .putString("cloud_token", "LOCAL-RECORD-PRESENTATION-TEST").putBoolean("voice", false).commit());
        onMain(() -> { app.sdkSession().reconnect(MorningSdkSettings.read(preferences)); return null; });
        await(() -> sdkRequests.get() > 0, "SDK 请求必须实际到达本机测试端点");
        add(record("normal", "正常记录员工", "A001", 1, 0, true, 1_760_000_000_000L));
        add(record("abnormal", "异常记录员工", "A002", 0, 1, true, 1_760_000_060_000L)
                .put("findings", new JSONArray().put("手部检查异常，仅为界面测试夹具")));
        add(record("unknown", "历史缺项员工", "", -1, -1, null, 0L));
        add(record("null-profile", "显式空值资料", "A007", -1, -1, null, 0L)
                .put("staff_name", JSONObject.NULL).put("staff_no", JSONObject.NULL).put("head_enabled", JSONObject.NULL));
        add(record("disabled-head", "头检未启用员工", "A004", 1, 0, false, 1_760_000_180_000L));
        add(record("legacy-head", "旧记录头检异常", "A006", 0, 1, null, 1_760_000_200_000L));
        add(record("long", "长姓名晨检记录测试员工", "STAFF-20261010-0005", 1, 0, true, 1_760_000_240_000L));
        app.io.submit(() -> { for (JSONObject record : fixtures.values()) isolatedStore.saveRecord(record); }).get(10, TimeUnit.SECONDS);
    }

    @After public void restoreRecords() throws Exception {
        if (app == null || originalStore == null) return;
        try {
            if (scenario != null) scenario.close();
            onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            drainIo();
            assertEquals("浏览晨检记录不能调用业务云", 0, cloudRequests.get());
            for (Map.Entry<String, JSONObject> fixture : fixtures.entrySet()) {
                assertEquals("浏览不能改写记录快照", fixture.getValue().toString(),
                        app.io.submit(() -> isolatedStore.record(fixture.getKey()).toString()).get(10, TimeUnit.SECONDS));
            }
        } finally {
            try {
                onMain(() -> { app.store = originalStore; return null; });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue("只删除本轮 UUID 数据库", app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally { if (endpoint != null) endpoint.shutdown(); }
        }
    }

    @Test public void listShowsCurrentColumnsWithAlignedTitlesAndSnapshotValues() throws Exception {
        fixtures.get(prefix + "-normal").put("department", "原门店 / 中央厨房");
        fixtures.get(prefix + "-unknown").remove("department");
        fixtures.get(prefix + "-null-profile").put("department", JSONObject.NULL);
        fixtures.get(prefix + "-disabled-head").put("department", "  ");
        fixtures.get(prefix + "-long").put("department", "中央厨房食品安全质量检查部门");
        // 填满本用例的独立页，在四种屏幕上都能实际纵向滚动并验证固定表头。
        for (int index = 0; fixtures.size() < AppStore.DEFAULT_PAGE_SIZE; index++)
            add(record("scroll-" + index, "用于翻阅记录的长姓名测试员工" + index,
                    "LONG-STAFF-NUMBER-" + index, index % 2, index % 2 == 0 ? 1 : 0,
                    true, 1_760_001_000_000L + index * 60_000L));
        app.io.submit(() -> {
            isolatedStore.saveStaff(new JSONObject().put("id", 998001).put("name", "当前员工资料")
                    .put("staff_no", "CURRENT-998001").put("department", "旧员工部门"));
            for (JSONObject record : fixtures.values()) isolatedStore.saveRecord(record);
            // 员工随后换部门，历史记录仍必须展示采集快照；缺失快照也不能借用当前部门。
            isolatedStore.saveStaff(new JSONObject().put("id", 998001).put("name", "当前员工资料")
                    .put("staff_no", "CURRENT-998001").put("department", "新员工部门"));
            return null;
        })
                .get(10, TimeUnit.SECONDS);
        launchList();
        onMain(() -> {
            RecordsActivity page = resumed(RecordsActivity.class);
            RecyclerView list = page.findViewById(R.id.records_list);
            assertSingleHeader(page);
            RecyclerView.ViewHolder recycled = null;
            for (int pass = 0; pass < 2; pass++) for (int index = 0; index < RecyclerListTestHelper.count(list); index++) {
                int position = pass == 0 ? index : RecyclerListTestHelper.count(list) - 1 - index;
                JSONObject record = RecyclerListTestHelper.item(list, position);
                RecyclerView.ViewHolder holder = RecyclerListTestHelper.bind(list, position, recycled);
                View row = holder.itemView;
                if (recycled != null) assertSame("状态变化仍应复用列表行", recycled, holder);
                measureRow(row, list.getWidth());
                assertListRow(page, (ViewGroup) row, record);
                recycled = holder;
            }
            return null;
        });
        Map<Integer, Rect> fixed = onMain(() -> fixedControls(resumed(RecordsActivity.class), true));
        for (int position = 0; position < fixtures.size(); position++) {
            showListPosition(position); final int target = position;
            onMain(() -> {
                RecordsActivity page = resumed(RecordsActivity.class);
                RecyclerView list = page.findViewById(R.id.records_list);
                ViewGroup row = (ViewGroup) RecyclerListTestHelper.row(list, target);
                assertListRow(page, row, (JSONObject) RecyclerListTestHelper.item(list, target));
                assertFixedControls(page, fixed);
                assertIsolatedConnection(); return null;
            });
            assertEachColumnCanBeRead(position);
        }
        onMain(() -> {
            RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list);
            assertTrue("验收必须实际纵向滚动到后面的记录", RecyclerListTestHelper.first(list) > 0);
            return null;
        });
        showListPosition(0); capture("RecordsActivity");
    }

    @Test public void realListClickLoadsMatchingRecordWithoutExposingInternalId() throws Exception {
        launchList(); String targetId = prefix + "-abnormal";
        int position = onMain(() -> {
            RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list);
            for (int index = 0; index < RecyclerListTestHelper.count(list); index++)
                if (targetId.equals(((JSONObject) RecyclerListTestHelper.item(list, index)).optString("id"))) return index;
            throw new AssertionError("目标记录不在列表中");
        });
        showListPosition(position);
        onMain(() -> {
            RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list);
            View row = RecyclerListTestHelper.row(list, position);
            assertNotNull("点击应使用真实挂载行", row);
            assertTrue(RecyclerListTestHelper.click(list, position));
            return null;
        });
        awaitDetail(targetId);
        onMain(() -> {
            RecordDetailActivity page = resumed(RecordDetailActivity.class);
            assertEquals(targetId, page.getIntent().getStringExtra(RecordDetailActivity.EXTRA_RECORD_ID));
            assertSummary(page, fixtures.get(targetId));
            assertNoInternalId(page.findViewById(android.R.id.content), targetId);
            return null;
        });
        capture("RecordDetailActivity-clicked");
    }

    @Test public void detailsUseSnapshotColorsAndSeparateProfileRows() throws Exception {
        for (JSONObject record : fixtures.values()) {
            String id = record.optString("id");
            scenario = ActivityScenario.launch(new Intent(app, RecordDetailActivity.class).putExtra(RecordDetailActivity.EXTRA_RECORD_ID, id));
            awaitDetail(id);
            onMain(() -> {
                RecordDetailActivity page = resumed(RecordDetailActivity.class);
                assertSummary(page, record);
                assertCheckColors(page, record);
                assertNoInternalId(page.findViewById(android.R.id.content), id);
                for (String removed : new String[]{"record_detail_meta", "record_detail_context", "record_detail_review", "record_detail_upload", "record_detail_tab_audit"}) {
                    int viewId = page.getResources().getIdentifier(removed, "id", page.getPackageName());
                    assertTrue("详情不能残留已移除的控件：" + removed, viewId == 0 || page.findViewById(viewId) == null);
                }
                assertFullyVisible(page.findViewById(R.id.record_detail_tab_photos));
                assertIsolatedConnection(); return null;
            });
            capture("RecordDetailActivity-" + id.substring(prefix.length() + 1));
            scenario.close(); scenario = null;
        }
    }

    private static void assertListRow(RecordsActivity page, ViewGroup row, JSONObject record) {
        String name = nullableProfileValue(record, "staff_name"), number = nullableProfileValue(record, "staff_no");
        assertEquals(name.isEmpty() ? "未记录" : name, text(row, R.id.record_row_name));
        assertEquals(number.isEmpty() ? "未记录" : number, text(row, R.id.record_row_number));
        String department = nullableProfileValue(record, "department");
        assertEquals("部门列必须取历史记录快照，缺失字段不能补当前员工部门",
                department.isEmpty() ? "未记录" : department, text(row, R.id.record_row_department));
        int status = record.optInt("status", -1);
        assertTextColor(row, R.id.record_row_result, status == 1 ? "正常" : status == 0 ? "异常" : "未记录", status == 1 ? Ui.GREEN : status == 0 ? Ui.ORANGE : Ui.MUTED);
        long time = record.optLong("created_at");
        assertEquals(time > 0 ? date(time, "yyyy-MM-dd HH:mm") : "未记录", text(row, R.id.record_row_date));
        assertEquals("日期和时间应完整展示在同一行", 1,
                ((TextView) row.findViewById(R.id.record_row_date)).getLayout().getLineCount());
        int[] titles = listTitles(), values = listValues();
        String[] headings = {"员工", "检查时间", "结果", "操作"};
        ViewGroup header = page.findViewById(R.id.records_table_header);
        Rect previous = null; Integer top = null, centerTwice = null;
        assertTrue("记录行不能小于此屏幕的最小行高", row.getHeight() >= row.getMinimumHeight());
        for (int index = 0; index < values.length; index++) {
            assertNull("每条记录不再重复字段标题", row.findViewById(titles[index]));
            TextView title = header.findViewById(titles[index]), value = row.findViewById(values[index]);
            assertEquals(headings[index], title.getText().toString());
            assertCompleteText(title); assertCompleteText(value);
            Rect titleBounds = bounds(header, title), valueBounds = bounds(row, value);
            if (top != null) assertEquals("五个标题应处于同一水平线", top.intValue(), titleBounds.top);
            if (centerTwice != null) assertTrue("五列内容应垂直居中，长文字仍在自己的列里",
                    Math.abs(centerTwice - valueBounds.top - valueBounds.bottom) <= 1);
            top = titleBounds.top; centerTwice = valueBounds.top + valueBounds.bottom;
            assertTrue("换行后也必须保留顶部留白", valueBounds.top >= row.getPaddingTop());
            assertTrue("换行后也必须保留底部留白", valueBounds.bottom <= row.getHeight() - row.getPaddingBottom());
            assertEquals("每列标题与内容左边界一致", titleBounds.left, valueBounds.left);
            assertEquals("每列标题与内容右边界一致", titleBounds.right, valueBounds.right);
            if (previous != null) assertTrue("五列不能相互重叠", valueBounds.left >= previous.right);
            previous = valueBounds;
        }
        for (TextView view : texts(row)) if (view.isShown()) assertFalse("记录行不展示上传文案", view.getText().toString().contains("上传") || view.getText().toString().contains("待核实"));
        assertNoInternalId(row, record.optString("id"));
    }

    private static void assertSummary(RecordDetailActivity page, JSONObject record) {
        ViewGroup root = page.findViewById(android.R.id.content);
        String name = nullableProfileValue(record, "staff_name"), number = nullableProfileValue(record, "staff_no");
        assertTextColor(root, R.id.record_detail_name,
                (name.isEmpty() ? "未记录姓名" : name) + " · " + (number.isEmpty() ? "未记录" : number),
                name.isEmpty() ? Ui.MUTED : Ui.INK);
        assertTextColor(root, R.id.record_detail_staff_no, number.isEmpty() ? "未记录" : number, number.isEmpty() ? Ui.MUTED : Ui.INK);
        String department = nullableProfileValue(record, "department"), shop = nullableProfileValue(record, "capture_shop_name");
        assertTextColor(root, R.id.record_detail_department, department.isEmpty() ? "未记录" : department,
                department.isEmpty() ? Ui.MUTED : Ui.BLUE);
        assertTextColor(root, R.id.record_detail_shop, shop.isEmpty() ? "未记录" : shop,
                shop.isEmpty() ? Ui.MUTED : Ui.INK);
        for (int id : new int[]{R.id.record_detail_staff_no, R.id.record_detail_shop})
            assertEquals("工号合并到姓名栏，门店资料行暂时隐藏", View.GONE,
                    ((View) root.findViewById(id).getParent()).getVisibility());
        long time = record.optLong("created_at");
        assertTextColor(root, R.id.record_detail_date, time > 0 ? date(time, "MM-dd HH:mm") : "时间未记录", time > 0 ? Ui.INK : Ui.MUTED);
        int status = record.optInt("status", -1);
        assertTextColor(root, R.id.record_detail_outcome, status == 1 ? "正常" : status == 0 ? "异常" : "未记录", status == 1 ? Ui.GREEN : status == 0 ? Ui.ORANGE : Ui.MUTED);
        Rect previous = null; Set<View> rows = new HashSet<>();
        for (int id : new int[]{R.id.record_detail_name, R.id.record_detail_outcome,
                R.id.record_detail_date, R.id.record_detail_department}) {
            View value = root.findViewById(id); assertFullyVisible(value);
            if (id != R.id.record_detail_name)
                assertTrue("日期、部门和结果各占独立行", rows.add((View) value.getParent()));
            Rect current = bounds(root, value);
            if (previous != null) assertTrue("详情资料行不能相互覆盖", current.top >= previous.bottom);
            previous = current;
        }
    }

    private static void assertCheckColors(RecordDetailActivity page, JSONObject record) {
        ListView list = page.findViewById(R.id.record_detail_fields); ListAdapter adapter = list.getAdapter();
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("体温", flagColor(record.optInt("abnormal_temperature", -1)));
        expected.put("健康证", flagColor(record.optInt("abnormal_health_cert_exp", -1)));
        boolean headDisabled = record.has("head_enabled") && !record.isNull("head_enabled") && !record.optBoolean("head_enabled");
        expected.put("头部检查", headDisabled ? Ui.MUTED : flagColor(record.optInt("abnormal_face", -1)));
        expected.put("手部检查", flagColor(record.optInt("abnormal_hand", -1)));
        JSONArray findings = record.optJSONArray("findings");
        if (findings != null && findings.length() > 0) expected.put("异常 1", Ui.ORANGE);
        else expected.put("异常说明", record.optInt("status", -1) == 1 ? Ui.GREEN : Ui.MUTED);
        Set<String> seen = new HashSet<>(); View recycled = null;
        // 正常、异常、缺省项目复用同一个 View，以免上个状态的颜色残留。
        for (int pass = 0; pass < 2; pass++) for (int index = 0; index < adapter.getCount(); index++) {
            int position = pass == 0 ? index : adapter.getCount() - 1 - index;
            View row = adapter.getView(position, recycled, list); measureRow(row, list.getWidth());
            String label = text(row, R.id.record_detail_field_label);
            assertFalse("详情不展示内部编号", label.contains("本机记录编号"));
            assertTrue("检查项不能掺入资料字段：" + label, expected.containsKey(label));
            assertEquals("检查颜色来自已存采集状态：" + label, expected.get(label).intValue(), ((TextView) row.findViewById(R.id.record_detail_field_value)).getCurrentTextColor());
            assertCompleteText(row.findViewById(R.id.record_detail_field_value));
            if ("头部检查".equals(label) && headDisabled)
                assertEquals("本次未启用", text(row, R.id.record_detail_field_value));
            assertNoInternalId(row, record.optString("id")); seen.add(label); recycled = row;
        }
        assertEquals(expected.keySet(), seen);
    }

    private static int flagColor(int flag) { return flag == 0 ? Ui.GREEN : flag == 1 ? Ui.ORANGE : Ui.MUTED; }
    private static void assertTextColor(View root, int id, String expected, int color) {
        TextView value = root.findViewById(id); assertNotNull(value);
        assertEquals(expected, value.getText().toString()); assertEquals(expected, color, value.getCurrentTextColor());
    }
    private static void assertNoInternalId(View root, String id) {
        for (TextView value : texts(root)) assertFalse("界面不能泄露本机记录编号", value.getText().toString().contains(id) || value.getText().toString().contains("本机记录编号"));
    }
    private static ArrayList<TextView> texts(View root) {
        ArrayList<TextView> result = new ArrayList<>();
        if (root instanceof TextView) result.add((TextView) root);
        if (root instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) root).getChildCount(); index++) result.addAll(texts(((ViewGroup) root).getChildAt(index)));
        return result;
    }
    private static void assertCompleteText(TextView view) {
        assertTrue("文字必须有实际尺寸", view.getWidth() > 0 && view.getHeight() > 0);
        Layout layout = view.getLayout(); assertNotNull("应完成文字布局", layout);
        if (view.getEllipsize() == null)
            for (int line = 0; line < layout.getLineCount(); line++) assertEquals("未声明省略的字段不能截断", 0, layout.getEllipsisCount(line));
        assertTrue("文字不能纵向裁切", layout.getHeight() <= view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom());
        if (view.getEllipsize() == null)
            assertEquals("未声明省略的字段必须完整排版到末尾", view.getText().length(), layout.getLineEnd(layout.getLineCount() - 1));
    }
    private static void assertFullyVisible(View view) {
        assertNotNull(view); assertTrue("字段必须进入真实视口", view.isShown());
        Rect visible = new Rect(); assertTrue(view.getGlobalVisibleRect(visible));
        assertEquals("字段不可横向裁切", view.getWidth(), visible.width()); assertEquals("字段不可纵向裁切", view.getHeight(), visible.height());
        if (view instanceof TextView) assertCompleteText((TextView) view);
    }
    private static Rect bounds(ViewGroup root, View child) { Rect result = new Rect(); child.getDrawingRect(result); root.offsetDescendantRectToMyCoords(child, result); return result; }
    private static void measureRow(View row, int width) { row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight()); }
    private static String text(View root, int id) { return ((TextView) root.findViewById(id)).getText().toString(); }
    private static String nullableProfileValue(JSONObject record, String key) { return record.isNull(key) ? "" : record.optString(key).trim(); }
    private static String date(long time, String pattern) { return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(time)); }
    private static int[] listTitles() { return new int[]{R.id.record_row_name_title, R.id.record_row_date_title, R.id.record_row_result_title, R.id.record_row_actions_title}; }
    private static int[] listValues() { return new int[]{R.id.record_row_name, R.id.record_row_date, R.id.record_row_result, R.id.record_row_view}; }
    private static int[] detailValues() { return new int[]{R.id.record_detail_name, R.id.record_detail_outcome, R.id.record_detail_staff_no, R.id.record_detail_date, R.id.record_detail_department, R.id.record_detail_shop}; }

    private static void assertSingleHeader(RecordsActivity page) {
        ViewGroup root = page.findViewById(android.R.id.content);
        ViewGroup header = page.findViewById(R.id.records_table_header);
        assertNotNull("记录列表需要公共表头", header);
        assertEquals("页面只能出现一个表头", 1, countViews(root, R.id.records_table_header));
        for (int id : listTitles()) {
            assertEquals("字段标题只在顶部显示一次", 1, countViews(root, id));
            assertSame(root.findViewById(id), header.findViewById(id));
        }
        ViewGroup table = page.findViewById(R.id.records_table_content);
        ViewGroup viewport = (ViewGroup) table.getParent();
        RecyclerView rows = page.findViewById(R.id.records_list);
        int width = viewport.getWidth() - viewport.getPaddingLeft() - viewport.getPaddingRight();
        assertEquals("表格跟随实际容器宽度，不能被长文字撑宽", width, table.getWidth());
        assertEquals("固定表头与记录列表使用同一宽度", header.getWidth(), rows.getWidth());
        assertSame("表头直接位于实际表格内", table, header.getParent());
        assertSame("仅记录列表负责纵向滚动", table, rows.getParent());
    }

    private static int countViews(View view, int id) {
        int count = view.getId() == id ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) count += countViews(group.getChildAt(index), id);
        }
        return count;
    }

    private static Rect screenBounds(View view) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        return new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    }

    private static Map<Integer, Rect> fixedControls(RecordsActivity page, boolean includeHeader) {
        Map<Integer, Rect> result = new LinkedHashMap<>();
        for (int id : new int[]{R.id.records_search, R.id.records_filter, R.id.records_department_filter, R.id.records_date_filter,
                R.id.records_load_more}) {
            assertFullyVisible(page.findViewById(id));
            result.put(id, screenBounds(page.findViewById(id)));
        }
        if (includeHeader) result.put(R.id.records_table_header, screenBounds(page.findViewById(R.id.records_table_header)));
        return result;
    }

    private static void assertFixedControls(RecordsActivity page, Map<Integer, Rect> expected) {
        for (Map.Entry<Integer, Rect> item : expected.entrySet())
            assertEquals("滚动记录不能移动表头、搜索、筛选及分页", item.getValue(), screenBounds(page.findViewById(item.getKey())));
    }

    private void assertEachColumnCanBeRead(int position) {
        Map<Integer, Rect> fixed = onMain(() -> fixedControls(resumed(RecordsActivity.class), false));
        Rect headerBefore = onMain(() -> screenBounds(resumed(RecordsActivity.class).findViewById(R.id.records_table_header)));
        int[] titles = listTitles(), values = listValues();
        for (int column = 0; column < values.length; column++) {
            final int targetColumn = column;
            onMain(() -> {
                RecordsActivity page = resumed(RecordsActivity.class);
                RecyclerView list = page.findViewById(R.id.records_list);
                View row = RecyclerListTestHelper.row(list, position);
                TextView title = page.findViewById(titles[targetColumn]);
                TextView value = row.findViewById(values[targetColumn]);
                assertFullyVisible(title); assertFullyVisible(value);
                Rect heading = screenBounds(title), data = screenBounds(value);
                assertEquals("表头与当前可见记录列的左边界一致", heading.left, data.left);
                assertEquals("表头与当前可见记录列的右边界一致", heading.right, data.right);
                Rect header = screenBounds(page.findViewById(R.id.records_table_header));
                assertEquals("查看记录不能移动固定表头的垂直位置", headerBefore.top, header.top);
                assertEquals("查看记录不能改变固定表头高度", headerBefore.bottom, header.bottom);
                assertSingleHeader(page); assertFixedControls(page, fixed);
                return null;
            });
        }
    }

    private void add(JSONObject record) { fixtures.put(record.optString("id"), record); }
    private JSONObject record(String suffix, String name, String staffNo, int status, int flag, Boolean headEnabled, long time) throws Exception {
        JSONObject record = new JSONObject().put("id", prefix + "-" + suffix).put("staff_id", 998001).put("staff_name", name)
                .put("staff_no", staffNo).put("department", "后厨").put("capture_shop_name", "采集测试门店")
                .put("created_at", time).put("status", status).put("temperature", "36.5").put("temperature_source", "测温设备")
                .put("health_expiry", "2099-12-31").put("findings", new JSONArray()).put("upload", "未上传");
        if (flag >= 0) for (String key : new String[]{"abnormal_temperature", "abnormal_health_cert_exp", "abnormal_face", "abnormal_hand"}) record.put(key, flag);
        if (headEnabled != null) record.put("head_enabled", headEnabled);
        return record;
    }
    private void launchList() {
        scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class)); awaitExpectedOrientation(RecordsActivity.class);
        await(() -> onMain(() -> { RecordsActivity page = resumed(RecordsActivity.class); if (page == null) return false; RecyclerView list = page.findViewById(R.id.records_list); return RecyclerListTestHelper.count(list) == fixtures.size() && list.isEnabled() && list.getWidth() > 0; }), "记录列表应读取隔离夹具并完成布局");
    }
    private void awaitDetail(String id) {
        awaitExpectedOrientation(RecordDetailActivity.class);
        await(() -> onMain(() -> { RecordDetailActivity page = resumed(RecordDetailActivity.class); return page != null && page.currentRecord != null && id.equals(page.currentRecord.optString("id")) && page.findViewById(R.id.record_detail_date).getHeight() > 0; }), "详情应加载点击对应的真实记录");
    }
    private void showListPosition(int position) {
        onMain(() -> { RecyclerListTestHelper.scroll(((RecyclerView) resumed(RecordsActivity.class).findViewById(R.id.records_list)), position, 0); return null; });
        await(() -> onMain(() -> { RecyclerView list = resumed(RecordsActivity.class).findViewById(R.id.records_list); int child = position - RecyclerListTestHelper.first(list); if (child < 0 || child >= list.getChildCount()) return false; View row = list.getChildAt(child); Rect visible = new Rect(); if (!row.getGlobalVisibleRect(visible) || row.getHeight() != visible.height()) return false; for (int id : listValues()) if (row.findViewById(id).getWidth() <= 0) return false; return true; }), "记录行应纵向完整进入视口：" + position);
    }
    private <T extends Activity> void awaitExpectedOrientation(Class<T> type) {
        String expected = InstrumentationRegistry.getArguments().getString("expectedRecordsLayoutOrientation", ""); if (expected.isEmpty()) return;
        assertTrue("验收方向只允许 portrait 或 landscape", "portrait".equals(expected) || "landscape".equals(expected));
        boolean portrait = "portrait".equals(expected);
        await(() -> onMain(() -> resumed(type) != null), "设置方向前记录页面应已恢复");
        onMain(() -> { resumed(type).setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
        await(() -> onMain(() -> { T page = resumed(type); if (page == null) return false; View root = page.findViewById(android.R.id.content); return root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested() && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight()) && page.getResources().getConfiguration().orientation == (portrait ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE); }), "真实记录页面窗口应符合方向：" + expected);
    }
    private void assertIsolatedConnection() {
        assertEquals(SdkConfig.Channel.WS, app.sdkSession().client().getActiveChannel());
        assertEquals(wsEndpoint, MorningSdkSettings.read(preferences).getWsEndpoint());
        assertTrue(app.sdkSession().matchesConfiguration(SdkConfig.webSocket(wsEndpoint)));
    }

    /** 截图与真实测量只写应用外部 files，执行方再拉回 codexFile。 */
    private void capture(String scene) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("captureRecordsPresentation"))) return;
        instrumentation.waitForIdleSync(); SystemClock.sleep(150);
        String capturePrefix = InstrumentationRegistry.getArguments().getString("recordsPresentationCapturePrefix", "records").replaceAll("[^a-zA-Z0-9_-]", "_");
        File folder = new File(app.getExternalFilesDir(null), "records-presentation-ui"); assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        JSONObject evidence = onMain(() -> {
            Activity page = resumed(RecordDetailActivity.class); if (page == null) page = resumed(RecordsActivity.class); assertNotNull(page);
            View root = page.findViewById(android.R.id.content); JSONArray fields = new JSONArray();
            if (page instanceof RecordsActivity) { for (int id : listTitles()) fields.put(measure(page.findViewById(id))); RecyclerView list = page.findViewById(R.id.records_list); for (int row = 0; row < list.getChildCount(); row++) for (int id : listValues()) fields.put(measure(list.getChildAt(row).findViewById(id))); }
            else { for (int id : detailValues()) fields.put(measure(page.findViewById(id))); ListView list = page.findViewById(R.id.record_detail_fields); for (int row = 0; row < list.getChildCount(); row++) fields.put(measure(list.getChildAt(row).findViewById(R.id.record_detail_field_value))); }
            return new JSONObject().put("scene", scene).put("width", root.getWidth()).put("height", root.getHeight()).put("orientation", page.getResources().getConfiguration().orientation).put("fields", fields);
        });
        evidence.put("screenshotWidth", bitmap.getWidth()).put("screenshotHeight", bitmap.getHeight());
        try {
            try (FileOutputStream stream = new FileOutputStream(new File(folder, capturePrefix + "-" + scene + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            try (FileOutputStream stream = new FileOutputStream(new File(folder, capturePrefix + "-" + scene + ".json"))) { stream.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8)); }
        } finally { bitmap.recycle(); }
    }
    private static JSONObject measure(View view) throws Exception {
        TextView text = (TextView) view; int[] origin = new int[2]; view.getLocationOnScreen(origin);
        return new JSONObject().put("id", view.getResources().getResourceEntryName(view.getId())).put("text", text.getText().toString()).put("color", String.format(Locale.ROOT, "#%08X", text.getCurrentTextColor()))
                .put("x", origin[0]).put("y", origin[1]).put("width", view.getWidth()).put("height", view.getHeight()).put("lines", text.getLineCount());
    }
    private void drainIo() throws Exception { instrumentation.waitForIdleSync(); app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS); app.io.submit(() -> null).get(10, TimeUnit.SECONDS); instrumentation.waitForIdleSync(); }
    private <T extends Activity> T resumed(Class<T> type) { for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (type.isInstance(page)) return type.cast(page); return null; }
    private void finishPages() { for (Stage stage : Stage.values()) for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))) if (page.getPackageName().equals(app.getPackageName()) && !page.isFinishing()) page.finish(); }
    private <T> T onMain(Callable<T> action) { AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>(); instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } }); if (failure.get() != null) throw new AssertionError(failure.get()); return value.get(); }
    private static Map<String, Object> snapshot(SharedPreferences preferences) { Map<String, Object> result = new HashMap<>(); for (Map.Entry<String, ?> item : preferences.getAll().entrySet()) result.put(item.getKey(), item.getValue() instanceof Set ? new HashSet<>((Set<?>) item.getValue()) : item.getValue()); return result; }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences preferences, Map<String, Object> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> item : values.entrySet()) { Object value = item.getValue(); if (value instanceof String) editor.putString(item.getKey(), (String) value); else if (value instanceof Boolean) editor.putBoolean(item.getKey(), (Boolean) value); else if (value instanceof Integer) editor.putInt(item.getKey(), (Integer) value); else if (value instanceof Long) editor.putLong(item.getKey(), (Long) value); else if (value instanceof Float) editor.putFloat(item.getKey(), (Float) value); else if (value instanceof Set) editor.putStringSet(item.getKey(), (Set<String>) value); else throw new AssertionError("未知偏好类型：" + item.getKey()); }
        assertTrue("必须恢复原有设备配置", editor.commit()); assertEquals(values, preferences.getAll());
    }
    private static void await(BooleanSupplier condition, String message) { long deadline = SystemClock.elapsedRealtime() + 10_000; do { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } while (SystemClock.elapsedRealtime() < deadline); fail(message); }
}
