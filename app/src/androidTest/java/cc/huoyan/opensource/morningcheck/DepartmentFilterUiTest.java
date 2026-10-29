package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.ListPopupWindow;
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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import cc.huoyan.opensource.morningcheck.data.Jsons;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 以独立业务库和本机 SDK 验收部门门槛、真实入口、稳定 ID 和窗口收尾。 */
@RunWith(AndroidJUnit4.class)
public final class DepartmentFilterUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private ActivityScenario<? extends BaseMorningActivity> scenario;

    @Before public void isolateStoreAndSdk() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        onMain(() -> {
            assertFalse("不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        originalStore = app.store;
        databaseName = "department-filter-ui-" + UUID.randomUUID() + ".db";
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
                            JSONObject data = "getStatus".equals(request.optString("cmd"))
                                    ? Jsons.obj("sdk_state", "READY", "model_ready", true, "certificate_valid", true)
                                    : new JSONObject();
                            socket.send(Jsons.obj("requestId", request.getString("requestId"), "code", 0,
                                    "reason", "部门筛选隔离夹具", "data", data).toString());
                        } catch (Throwable error) { serverFailure.set(error); }
                    }
                });
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).putInt(AppFontSettings.KEY, AppFontSettings.DEFAULT_INDEX).commit());
    }

    @After public void restoreStoreAndSdk() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null) onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            instrumentation.waitForIdleSync();
            if (app != null) app.networkIo.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
        } finally {
            try {
                if (originalStore != null) onMain(() -> { app.store = originalStore; return null; });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue(app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                for (WebSocket socket : sockets) socket.close(1001, "部门筛选测试结束");
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("筛选不能访问业务云", 0, businessRequests.get());
        assertNull("隔离 SDK 不能发生异常", serverFailure.get());
    }

    @Test public void largeChoiceListMeasuresOnlyVisibleRangeAndCanSelectLastAtLargestFont() throws Exception {
        assertTrue(preferences.edit().putInt(AppFontSettings.KEY, 3).commit());
        launch(StaffActivity.class, 2, 0);
        for (int orientation : new int[]{ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}) {
            onMain(() -> { resumed(StaffActivity.class).setRequestedOrientation(orientation); return null; });
            int expected = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
            await(() -> onMain(() -> resumed(StaffActivity.class) != null
                    && resumed(StaffActivity.class).getResources().getConfiguration().orientation == expected
                    && resumed(StaffActivity.class).loaded), "应完成方向切换");
            AtomicInteger highestBound = new AtomicInteger(-1), created = new AtomicInteger(), selected = new AtomicInteger(-1);
            Dialog choices = onMain(() -> {
                List<String> labels = new ArrayList<>();
                for (int index = 0; index < 1500; index++) labels.add("测试部门" + index);
                androidx.appcompat.app.AlertDialog dialog = DesignChoiceDialog.create(resumed(StaffActivity.class),
                        "选择部门", "选择员工所属部门", labels, 0, selected::set);
                dialog.create();
                ListView rows = list(dialog);
                ListAdapter source = rows.getAdapter();
                rows.setAdapter(new BaseAdapter() {
                    public int getCount() { return source.getCount(); }
                    public Object getItem(int position) { return source.getItem(position); }
                    public long getItemId(int position) { return source.getItemId(position); }
                    public View getView(int position, View recycled, ViewGroup parent) {
                        highestBound.set(Math.max(highestBound.get(), position));
                        if (recycled == null) created.incrementAndGet();
                        return source.getView(position, recycled, parent);
                    }
                });
                return ImmersiveUi.show(dialog);
            });
            try {
                await(() -> onMain(() -> list(choices).getChildCount() > 0), "部门列表应可见");
                onMain(() -> {
                    assertTrue("打开弹窗不能测量全部1500项", highestBound.get() < 100);
                    assertTrue("测量应复用行，创建量只与视口有关", created.get() < 60);
                    assertTrue("弹窗不能越出宿主页面", bounds(choices.getWindow().getDecorView()).height()
                            <= resumed(StaffActivity.class).getWindow().getDecorView().getHeight());
                    list(choices).setSelection(1499);
                    return null;
                });
                await(() -> onMain(() -> list(choices).getLastVisiblePosition() == 1499), "最后一个部门应可滚入视口");
                onMain(() -> {
                    ListView rows = list(choices);
                    View last = rows.getChildAt(1499 - rows.getFirstVisiblePosition());
                    assertTrue(rows.performItemClick(last, 1499, rows.getItemIdAtPosition(1499)));
                    assertEquals(1499, selected.get());
                    assertFalse(choices.isShowing());
                    return null;
                });
            } finally {
                onMain(() -> { choices.dismiss(); return null; });
            }
        }
    }

    @Test public void departmentChoicesUseSharedDialogAndDirectoryUpdatesCloseOldWindow() throws Exception {
        app.io.submit(() -> {
            for (int i = 0; i < 10; i++) isolatedStore.saveDepartment(0, "门槛部门" + i);
        }).get(10, TimeUnit.SECONDS);
        launch(StaffActivity.class, 12, 0);
        List<JSONObject> tenOptions = app.io.submit(() -> isolatedStore.departmentOptions(0)).get(10, TimeUnit.SECONDS);
        onMain(() -> {
            StaffActivity page = resumed(StaffActivity.class);
            // 重复和无效部门不能污染统一选择弹窗的候选项。
            List<JSONObject> options = new ArrayList<>(tenOptions);
            options.add(options.get(0));
            options.add(Jsons.obj("id", 0, "name", "无效零编号"));
            options.add(Jsons.obj("id", -1, "name", "无效负编号"));
            filter(page).replace(options);
            assertEquals(12, spinner(page).getCount());
            assertTrue(spinner(page).performClick());
            assertTrue("部门选择使用统一列表弹窗", dialog(page).isShowing());
            assertEquals(12, list(dialog(page)).getCount());
            assertFalse("不能同时打开原生下拉窗口", dropdown(page).isShowing());
            dialog(page).dismiss();
            return null;
        });
        long extraId = app.io.submit(() -> isolatedStore.saveDepartment(0, "新增第十一部门")).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyDepartmentsChanged(); return null; });
        awaitOptions(StaffActivity.class, 13);
        Dialog oldDialog = open(StaffActivity.class, true);
        onMain(() -> { assertFalse("触摸模式不能同时打开旧下拉", dropdown(resumed(StaffActivity.class)).isShowing()); return null; });
        app.io.submit(() -> isolatedStore.removeDepartment(extraId)).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.notifyDepartmentsChanged(); return null; });
        awaitOptions(StaffActivity.class, 12);
        onMain(() -> {
            assertFalse("目录变化先撤销旧选择窗口", oldDialog.isShowing());
            StaffActivity page = resumed(StaffActivity.class);
            assertTrue(spinner(page).performClick());
            assertTrue("目录更新后仍使用统一弹窗", dialog(page).isShowing());
            assertEquals("应移除已删除部门", 12, list(dialog(page)).getCount());
            assertFalse(dropdown(page).isShowing());
            dialog(page).dismiss();
            return null;
        });
    }







    private long[] seedHierarchy() throws Exception {
        return app.io.submit(() -> {
            long parent = isolatedStore.saveDepartment(0, "中央厨房", 0, null, 0);
            long child = isolatedStore.saveDepartment(0, "后厨", parent, null, 0);
            for (int i = 0; i < 9; i++) isolatedStore.saveDepartment(0, "其他部门" + i);
            long[] departments = {parent, child, 0};
            for (int i = 0; i < departments.length; i++) {
                long id = departments[i];
                String path = id == 0 ? "" : isolatedStore.department(id).optString("path");
                isolatedStore.saveStaff(Jsons.obj("id", i + 1, "name", id == 0 ? "未分配员工" : "筛选员工" + i,
                        "staff_no", "FILTER-" + i, "department", path, "department_id", id, "enabled", true, "created_at", i));
                isolatedStore.saveRecord(Jsons.obj("id", "filter-record-" + i, "staff_id", i + 1,
                        "staff_name", "筛选员工" + i, "staff_no", "FILTER-" + i, "department", path,
                        "department_id", id, "status", 1, "created_at", i, "temperature", "36.5", "upload", "未上传"));
            }
            return new long[]{parent, child};
        }).get(10, TimeUnit.SECONDS);
    }

    private <T extends BaseMorningActivity> void launch(Class<T> type, int options, int rows) {
        scenario = ActivityScenario.launch(new Intent(app, type));
        awaitOptions(type, options);
        awaitPage(type, rows);
    }

    private <T extends BaseMorningActivity> void awaitOptions(Class<T> type, int count) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            return page != null && spinner(page).getCount() == count && spinner(page).isShown()
                    && spinner(page).getWidth() > 0 && page.loaded;
        }), "部门选项应收敛到 " + count + " 项");
    }

    private <T extends BaseMorningActivity> void awaitPage(Class<T> type, int count) {
        await(() -> onMain(() -> {
            T page = resumed(type);
            androidx.recyclerview.widget.RecyclerView rows = page == null ? null
                    : page.findViewById(type == StaffActivity.class ? R.id.staff_list : R.id.records_list);
            return rows != null && rows.isEnabled() && RecyclerListTestHelper.count(rows) == count && page.loaded;
        }), "筛选后数据应收敛到 " + count + " 条");
    }

    private <T extends BaseMorningActivity> Dialog open(Class<T> type, boolean touch) {
        if (touch) {
            int[] center = onMain(() -> {
                View target = spinner(resumed(type));
                int[] location = new int[2];
                target.getLocationOnScreen(location);
                return new int[]{location[0] + target.getWidth() / 2, location[1] + target.getHeight() / 2};
            });
            long down = SystemClock.uptimeMillis();
            MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, center[0], center[1], 0);
            MotionEvent release = MotionEvent.obtain(down, down + 30, MotionEvent.ACTION_UP, center[0], center[1], 0);
            try { instrumentation.sendPointerSync(press); instrumentation.sendPointerSync(release); }
            finally { press.recycle(); release.recycle(); }
        } else onMain(() -> { assertTrue("部门筛选应响应点击", spinner(resumed(type)).performClick()); return null; });
        await(() -> onMain(() -> {
            T page = resumed(type);
            Dialog result = page == null ? null : dialog(page);
            return result != null && result.isShowing() && list(result).getWidth() > 0 && list(result).getChildCount() > 0;
        }), "部门筛选应显示统一选择弹窗");
        return onMain(() -> dialog(resumed(type)));
    }



    private void choose(Dialog dialog, long id) {
        int position = onMain(() -> {
            ListView options = list(dialog);
            for (int i = 0; i < options.getCount(); i++) if (options.getItemIdAtPosition(i) == id) {
                options.setSelection(i);
                return i;
            }
            throw new AssertionError("弹窗缺少部门 ID：" + id);
        });
        await(() -> onMain(() -> {
            ListView options = list(dialog);
            int child = position - options.getFirstVisiblePosition();
            return child >= 0 && child < options.getChildCount() && options.getChildAt(child).getHeight() > 0;
        }), "选中部门应滚动到实际可见行");
        onMain(() -> {
            ListView options = list(dialog);
            View row = options.getChildAt(position - options.getFirstVisiblePosition());
            assertTrue("点击可见行应返回部门 ID", options.performItemClick(row, position, options.getItemIdAtPosition(position)));
            return null;
        });
        await(() -> !dialog.isShowing(), "选中后弹窗应关闭");
    }


    private void captureScreen(String name) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("应获取真实筛选界面的截图", bitmap);
        Configuration configuration = app.getResources().getConfiguration();
        File directory = new File(app.getFilesDir(), "codex-department-filter-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String size = configuration.screenWidthDp + "x" + configuration.screenHeightDp;
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-" + size + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private <T extends BaseMorningActivity> void assertSelection(Class<T> type, long id) {
        onMain(() -> {
            T page = resumed(type);
            assertEquals("控制器应按稳定 ID 选中", id, selectedId(page));
            assertEquals("Activity 应收到真实部门 ID", id, ((Number) field(page, "departmentId")).longValue());
            return null;
        });
    }

    private static DepartmentFilterSpinner spinner(BaseMorningActivity page) {
        return page.findViewById(page instanceof StaffActivity ? R.id.staff_department_filter : R.id.records_department_filter);
    }

    private static ListView list(Dialog dialog) { return dialog.findViewById(R.id.department_filter_options); }
    private static ListFilterUi.DepartmentFilter filter(BaseMorningActivity page) { return field(page, "departmentFilter"); }
    private static Dialog dialog(BaseMorningActivity page) {
        DepartmentFilterDialog controller = field(filter(page), "dialog");
        return controller == null ? null : field(controller, "dialog");
    }
    private static long selectedId(BaseMorningActivity page) { return field(filter(page), "selectedId"); }

    private static ListPopupWindow dropdown(BaseMorningActivity page) throws Exception {
        Field popup = AppCompatSpinner.class.getDeclaredField("mPopup");
        popup.setAccessible(true);
        return (ListPopupWindow) popup.get(spinner(page));
    }

    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
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
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable error) { failure.set(error); }
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
