package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.view.View;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 本机结果、记录筛选和采集证据的完整浏览回归，数据库与 SDK 均使用隔离夹具。 */
@RunWith(AndroidJUnit4.class)
public final class LocalRecordFlowUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger unexpectedRequests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final String recordId = "local-record-flow-" + UUID.randomUUID();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private ActivityScenario<ResultActivity> resultScenario;
    private ActivityScenario<RecordsActivity> recordsScenario;

    @Before public void isolateDatabaseAndSdk() throws Exception {
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
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        originalStore = app.store;
        databaseName = "local-record-flow-ui-" + UUID.randomUUID() + ".db";
        isolatedStore = new AppStore(new ContextWrapper(app) {
            @Override public File getDatabasePath(String name) { return app.getDatabasePath(databaseName); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        onMain(() -> { app.store = isolatedStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (!"/sdk".equals(request.getPath())) {
                    unexpectedRequests.incrementAndGet();
                    return new MockResponse().setResponseCode(503);
                }
                return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
                    @Override public void onMessage(WebSocket socket, String text) {
                        try {
                            JSONObject request = new JSONObject(text);
                            JSONObject data = new JSONObject();
                            if ("getStatus".equals(request.optString("cmd")))
                                data.put("sdk_state", "MODEL_NOT_READY").put("model_ready", false)
                                        .put("certificate_valid", true).put("device_id", "LOCAL-RECORD-FLOW-TEST");
                            socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                    .put("code", 0).put("reason", "本机记录浏览夹具").put("data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "LOCAL-TEST-TOKEN")
                .putString("device_id", "101").putString("shop_id", "202").putString("shop_name", "本机测试门店")
                .putBoolean("voice", false).commit());
        JSONObject value = new JSONObject().put("id", recordId).put("staff_id", 303)
                .put("staff_name", "本机记录测试员工").put("created_at", System.currentTimeMillis())
                .put("status", 1).put("temperature", "36.5").put("temperature_source", "测试夹具")
                .put("health_expiry", "2099-12-31").put("findings", new JSONArray())
                .put("abnormal_health_cert_exp", 0).put("abnormal_temperature", 0)
                .put("abnormal_face", 0).put("abnormal_hand", 0).put("upload", "未上传");
        app.io.submit(() -> isolatedStore.saveRecord(value)).get(10, TimeUnit.SECONDS);
    }

    @After public void restoreOriginalStore() throws Exception {
        try {
            if (resultScenario != null) resultScenario.close();
            if (recordsScenario != null) recordsScenario.close();
            if (app != null) onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            instrumentation.waitForIdleSync();
            if (app != null && originalStore != null) {
                // 队列清空后再关闭夹具数据库，避免页面读取落到已关闭的库。
                app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS);
                app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
                instrumentation.waitForIdleSync();
            }
        } finally {
            try {
                if (app != null) onMain(() -> {
                    if (originalStore != null) app.store = originalStore;
                    return null;
                });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue(app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "记录浏览测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("本机记录浏览不应请求业务云接口", 0, unexpectedRequests.get());
        assertNull(serverFailure.get());
    }

    @Test public void resultAndRecordsKeepLocalDataFiltersAndAllCapturedPhotos() throws Exception {
        resultScenario = ActivityScenario.launch(new Intent(app, ResultActivity.class)
                .putExtra("record_id", recordId));
        AtomicReference<ResultActivity> source = new AtomicReference<>();
        resultScenario.onActivity(source::set);
        await(() -> onMain(() -> source.get().loaded && source.get().currentRecord != null
                && recordId.equals(source.get().currentRecord.optString("id"))), "结果页应读取本机记录");
        resultScenario.onActivity(activity -> {
            assertTrue("结果页验收前应允许停留查看", activity.findViewById(R.id.result_stay).performClick());
            android.util.Log.i("本机记录回归", "结果页已停留，开始验证本机记录浏览");
        });
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        resultScenario.close();
        resultScenario = null;
        assertTrue("离开结果页后应销毁原页面", source.get().isDestroyed());

        recordsScenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class));
        awaitRecords("第 1 / 1 页 · 共 1 条", 1, 0);
        android.util.Log.i("本机记录回归", "记录列表已加载，开始验证结果筛选");
        // 本机记录的正常、异常筛选不依赖业务云。
        recordsScenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.records_filter)).setSelection(1));
        awaitRecords("第 1 / 1 页 · 共 1 条", 1, 1);
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        recordsScenario.onActivity(activity -> {
            assertEquals(1, ((Spinner) activity.findViewById(R.id.records_filter)).getSelectedItemPosition());
            RecyclerView list = activity.findViewById(R.id.records_list);
            JSONObject row = (JSONObject) RecyclerListTestHelper.item(list, 0);
            assertEquals(recordId, row.optString("id"));
            assertEquals("未上传", row.optString("upload"));
            assertNotNull("列表应已完成实际布局", list.getChildAt(0));
        });
        recordsScenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.records_filter)).setSelection(2));
        awaitRecords("第 1 / 1 页 · 共 0 条", 0, 2);
        recordsScenario.onActivity(activity -> ((Spinner) activity.findViewById(R.id.records_filter)).setSelection(1));
        awaitRecords("第 1 / 1 页 · 共 1 条", 1, 1);

        JSONArray captures = new JSONArray();
        for (String label : new String[]{"人脸与头部", "手背", "手心", "头部异常 1", "头部异常 2", "手背异常 1", "手背异常 2", "手心异常"})
            captures.put(new JSONObject().put("label", label).put("file", ""));
        JSONObject changes = new JSONObject().put("captured_photos", captures);
        app.io.submit(() -> isolatedStore.patchRecord(recordId, changes)).get(10, TimeUnit.SECONDS);
        AtomicReference<RecordDetailActivity> detail = new AtomicReference<>();
        try {
            // 从真实返回栈进入详情，不能用另一个 ActivityScenario 清掉正在验收的记录列表。
            recordsScenario.onActivity(activity -> activity.startActivity(new Intent(activity, RecordDetailActivity.class)
                    .putExtra(RecordDetailActivity.EXTRA_RECORD_ID, recordId)));
            await(() -> onMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof RecordDetailActivity) {
                        detail.set((RecordDetailActivity) activity);
                        return true;
                    }
                return false;
            }), "应从记录列表进入详情页并保留返回栈");
            await(() -> onMain(() -> detail.get().loaded && detail.get().currentRecord != null
                    && recordId.equals(detail.get().currentRecord.optString("id"))), "详情页应读取本机记录");
            await(() -> onMain(() -> {
                View photos = detail.get().findViewById(R.id.record_detail_photos);
                if (!(photos instanceof RecyclerView)) return false;
                RecyclerView list = (RecyclerView) photos;
                return list.getAdapter() != null && list.getAdapter().getItemCount() == 8
                        && list.getWidth() > 0 && list.getHeight() > 0;
            }), "详情照片列表应加载全部八张采集证据");
            app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
            onMain(() -> {
                RecordDetailActivity activity = detail.get();
                android.util.Log.i("本机记录回归", "详情已加载，开始验证全部采集照片");
                assertEquals("统一展示不删除原采集证据", 8,
                        activity.currentRecord.optJSONArray("captured_photos").length());
                RecyclerView photos = activity.findViewById(R.id.record_detail_photos);
                assertTrue("采集照片应使用网格布局", photos.getLayoutManager() instanceof GridLayoutManager);
                GridLayoutManager layout = (GridLayoutManager) photos.getLayoutManager();
                assertEquals("每行应展示两张采集照片", 2, layout.getSpanCount());
                assertEquals("采集照片应竖向滑动", RecyclerView.VERTICAL, layout.getOrientation());
                assertTrue("八张照片应超出首屏并支持竖向滑动", photos.canScrollVertically(1));
                assertFalse("两列照片不应横向滚动", photos.canScrollHorizontally(1) || photos.canScrollHorizontally(-1));
                photos.scrollToPosition(7);
                return null;
            });
            instrumentation.waitForIdleSync();
            await(() -> onMain(() -> {
                RecyclerView photos = detail.get().findViewById(R.id.record_detail_photos);
                GridLayoutManager layout = (GridLayoutManager) photos.getLayoutManager();
                View last = layout.findViewByPosition(7);
                android.graphics.Rect visible = new android.graphics.Rect();
                return last != null && last.isShown() && last.getLocalVisibleRect(visible)
                        && visible.width() > 0 && visible.height() > 0;
            }), "向下滑动后应能看到最后一张采集照片");
        } finally {
            onMain(() -> {
                if (detail.get() != null && !detail.get().isFinishing()) detail.get().finish();
                return null;
            });
        }
        instrumentation.waitForIdleSync();
        android.util.Log.i("本机记录回归", "详情已关闭，返回原记录列表验收筛选");
        awaitRecords("第 1 / 1 页 · 共 1 条", 1, 1);
        recordsScenario.onActivity(activity -> assertEquals("从详情返回仍保留正常结果筛选", 1,
                activity.<Spinner>findViewById(R.id.records_filter).getSelectedItemPosition()));
        app.io.submit(() -> null).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        JSONObject stored = app.io.submit(() -> isolatedStore.record(recordId)).get(10, TimeUnit.SECONDS);
        assertEquals("未上传", stored.optString("upload"));
        assertEquals("", stored.optString("cloud_record_id"));
        assertEquals("浏览结果与记录不应请求业务云", 0, unexpectedRequests.get());
    }

    private void awaitRecords(String expected, int count, int filter) {
        await(() -> {
            AtomicReference<Boolean> shown = new AtomicReference<>(false);
            recordsScenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.records_list);
                shown.set(list.isEnabled() && RecyclerListTestHelper.count(list) == count
                        && loadedFilter(activity) == filter
                        && expected.contentEquals(((TextView) activity.findViewById(R.id.records_page_info)).getText()));
            });
            return shown.get();
        }, "记录筛选未自动收敛到：" + expected);
    }

    private static int loadedFilter(RecordsActivity activity) {
        try {
            Field value = RecordsActivity.class.getDeclaredField("loadedFilter");
            value.setAccessible(true);
            return value.getInt(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
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
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }
}
