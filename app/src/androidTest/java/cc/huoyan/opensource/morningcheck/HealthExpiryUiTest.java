package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 日期弹窗仅在 x86 模拟器运行，使用 UUID 本地库和本机端点，截图供宿主导出验收。 */
@RunWith(AndroidJUnit4.class)
public final class HealthExpiryUiTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MorningApplication app;
    private AppStore originalStore, isolatedStore;
    private SharedPreferences preferences;
    private Map<String, ?> originalPreferences;
    private String databaseName;
    private MockWebServer endpoint;
    private ActivityScenario<StaffEditActivity> scenario;
    private int originalRequestedOrientation;
    private boolean orientationCaptured;

    @Before public void isolateStorageAndNetwork() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assumeTrue("日期测试不读写现场机器", x86 && (hardware.contains("ranchu") || hardware.contains("goldfish")));
        MorningApplication candidate = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("不能中断现有门店绑定", onMain(() -> !candidate.shopLogin().getState().isRunning()));
        app = candidate;
        originalStore = app.store;
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = preferences.getAll();
        onMain(() -> {
            assertFalse(app.shopLogin().getState().isRunning());
            app.sdkSession().disconnect();
            return null;
        });
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        databaseName = "health-date-ui-" + UUID.randomUUID() + ".db";
        isolatedStore = new AppStore(new ContextWrapper(app) {
            @Override public File getDatabasePath(String name) { return app.getDatabasePath(databaseName); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        app.io.submit(() -> isolatedStore.getWritableDatabase()).get(10, TimeUnit.SECONDS);
        onMain(() -> { app.store = isolatedStore; return null; });
        endpoint = new MockWebServer();
        endpoint.enqueue(new MockResponse().setResponseCode(503));
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putBoolean("voice", false).commit());
        scenario = ActivityScenario.launch(StaffEditActivity.class);
        await(() -> onMain(() -> activity().findViewById(R.id.staff_edit_save).isEnabled()));
        originalRequestedOrientation = onMain(() -> activity().getRequestedOrientation());
        orientationCaptured = true;
    }

    @After public void restoreStorageAndPreferences() throws Exception {
        if (scenario != null) {
            if (orientationCaptured) onMain(() -> {
                activity().setRequestedOrientation(originalRequestedOrientation);
                return null;
            });
            scenario.close();
        }
        if (app != null) {
            onMain(() -> { app.sdkSession().disconnect(); app.store = originalStore; return null; });
            app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (isolatedStore != null) isolatedStore.close();
            if (databaseName != null) app.deleteDatabase(databaseName);
            if (preferences != null && originalPreferences != null) {
                SharedPreferences.Editor restored = preferences.edit().clear();
                for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) restored.putString(entry.getKey(), (String) value);
                    else if (value instanceof Boolean) restored.putBoolean(entry.getKey(), (Boolean) value);
                    else if (value instanceof Integer) restored.putInt(entry.getKey(), (Integer) value);
                    else if (value instanceof Long) restored.putLong(entry.getKey(), (Long) value);
                    else if (value instanceof Float) restored.putFloat(entry.getKey(), (Float) value);
                    else if (value instanceof java.util.Set) restored.putStringSet(entry.getKey(), (java.util.Set<String>) value);
                }
                assertTrue(restored.commit());
            }
        }
        if (endpoint != null) endpoint.shutdown();
    }

    @Test public void confirmsCancelsAndClearsOnlyTheSelectedFormDate() throws Exception {
        openPicker();
        selectDate(LocalDate.of(2032, 2, 3));
        assertEquals("选择有效期（可留空）", onMain(this::formDate));
        onMain(() -> { dialog().findViewById(R.id.health_date_confirm).performClick(); return null; });
        assertEquals("2032-02-03", onMain(this::formDate));
        openPicker();
        selectDate(LocalDate.of(2033, 8, 12));
        onMain(() -> { dialog().findViewById(R.id.health_date_cancel).performClick(); return null; });
        assertEquals("2032-02-03", onMain(this::formDate));
        openPicker();
        onMain(() -> { dialog().findViewById(R.id.health_date_clear).performClick(); return null; });
        assertEquals("选择有效期（可留空）", onMain(this::formDate));
        assertEquals(0L, (long) app.io.submit(() -> isolatedStore.staffCount()).get(10, TimeUnit.SECONDS));
    }

    @Test public void centeredCompactCalendarShowsEveryCellWithoutScrolling() throws Exception {
        openPicker();
        selectDate(LocalDate.of(2032, 5, 31));
        awaitStableDialog();
        onMain(() -> { assertCompactCalendar(); return null; });
        capture("health-date-compact");
    }

    @Test public void spinnerMenusRemainUsableAndOnlyChangeTheCalendarDraft() throws Exception {
        openPicker();
        selectDate(LocalDate.of(2032, 2, 3));
        awaitStableDialog();
        tapView(onMain(() -> dialog().findViewById(R.id.health_date_year)));
        tapPopupOption("2033年", "health-date-year-options");
        await(() -> onMain(() -> "2033年".equals(((Spinner) dialog().findViewById(R.id.health_date_year)).getSelectedItem())));
        tapView(onMain(() -> dialog().findViewById(R.id.health_date_month)));
        tapPopupOption("3月", "health-date-month-options");
        await(() -> onMain(() -> "3月".equals(((Spinner) dialog().findViewById(R.id.health_date_month)).getSelectedItem())));
        awaitStableDialog();
        onMain(() -> {
            assertCompactCalendar();
            assertEquals("2032-02-03", ((TextView) dialog().findViewById(R.id.health_date_selected)).getText().toString());
            assertEquals("选择有效期（可留空）", formDate());
            GridView days = dialog().findViewById(R.id.health_date_days);
            assertEquals("2033-03-01", days.getChildAt(1).getContentDescription());
            days.performItemClick(days.getChildAt(3), 3, 3);
            return null;
        });
        assertEquals("2033-03-03", onMain(() -> ((TextView) dialog().findViewById(R.id.health_date_selected)).getText().toString()));
        assertEquals("选择有效期（可留空）", onMain(this::formDate));
        onMain(() -> { dialog().findViewById(R.id.health_date_cancel).performClick(); return null; });
        assertEquals("选择有效期（可留空）", onMain(this::formDate));
    }

    @Test public void arrowsBrowseAcrossYearsAndLeapDayRemainsSelectable() throws Exception {
        openPicker();
        selectDate(LocalDate.of(2031, 12, 31));
        onMain(() -> { dialog().findViewById(R.id.health_date_next).performClick(); return null; });
        await(() -> onMain(() -> "2032年".equals(((Spinner) dialog().findViewById(R.id.health_date_year)).getSelectedItem())
                && "1月".equals(((Spinner) dialog().findViewById(R.id.health_date_month)).getSelectedItem())));
        onMain(() -> { dialog().findViewById(R.id.health_date_previous).performClick(); return null; });
        await(() -> onMain(() -> "2031年".equals(((Spinner) dialog().findViewById(R.id.health_date_year)).getSelectedItem())
                && "12月".equals(((Spinner) dialog().findViewById(R.id.health_date_month)).getSelectedItem())));
        assertEquals("2031-12-31", onMain(() -> ((TextView) dialog().findViewById(R.id.health_date_selected)).getText().toString()));
        selectDate(LocalDate.of(2032, 2, 29));
        awaitStableDialog();
        onMain(() -> { assertCompactCalendar(); return null; });
        onMain(() -> { dialog().findViewById(R.id.health_date_confirm).performClick(); return null; });
        assertEquals("2032-02-29", onMain(this::formDate));
    }

    @Test public void calendarDraftSurvivesLandscapePortraitAndRecreation() throws Exception {
        openPicker();
        selectDate(LocalDate.of(2032, 2, 3));
        for (int orientation : new int[]{Configuration.ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_PORTRAIT}) {
            onMain(() -> {
                activity().setRequestedOrientation(orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return null;
            });
            // 只接受系统真正旋转后的窗口，桌面模式忽略方向请求时不能冒充横屏验收。
            await(() -> onMain(() -> {
                View window = activity().getWindow().getDecorView();
                boolean correctShape = orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? window.getWidth() > window.getHeight() : window.getHeight() > window.getWidth();
                return activity().getResources().getConfiguration().orientation == orientation
                        && correctShape && dialog() != null && dialog().isShowing();
            }));
            awaitStableDialog();
            onMain(() -> {
                assertEquals("2032-02-03", ((TextView) dialog().findViewById(R.id.health_date_selected)).getText().toString());
                assertCompactCalendar();
                assertEquals("选择有效期（可留空）", formDate());
                return null;
            });
            capture(orientation == Configuration.ORIENTATION_LANDSCAPE ? "health-date-landscape" : "health-date-portrait");
        }
        scenario.recreate();
        await(() -> onMain(() -> dialog() != null && dialog().isShowing()));
        awaitStableDialog();
        onMain(() -> { assertCompactCalendar(); return null; });
        assertEquals("2032-02-03", onMain(() -> ((TextView) dialog().findViewById(R.id.health_date_selected)).getText().toString()));
        onMain(() -> { dialog().findViewById(R.id.health_date_confirm).performClick(); return null; });
        assertEquals("2032-02-03", onMain(this::formDate));
    }

    private StaffEditActivity activity() { AtomicReference<StaffEditActivity> value = new AtomicReference<>(); scenario.onActivity(value::set); return value.get(); }
    private HealthExpiryDialog dialog() throws Exception {
        Field field = StaffEditActivity.class.getDeclaredField("editor"); field.setAccessible(true);
        StaffProfileEditor editor = (StaffProfileEditor) field.get(activity());
        return editor == null ? null : editor.dateDialog();
    }
    private String formDate() { return ((TextView) activity().findViewById(R.id.staff_edit_health)).getText().toString(); }
    private void openPicker() throws Exception {
        onMain(() -> {
            StaffEditActivity activity = activity();
            assertFalse(activity.findViewById(R.id.staff_edit_health) instanceof EditText);
            while (!activity.findViewById(R.id.staff_edit_health).isShown()) activity.findViewById(R.id.staff_edit_next_field).performClick();
            activity.findViewById(R.id.staff_edit_health).performClick();
            return null;
        });
        await(() -> onMain(() -> dialog() != null && dialog().isShowing()));
        awaitStableDialog();
    }
    private void selectDate(LocalDate date) throws Exception {
        onMain(() -> {
            Spinner year = dialog().findViewById(R.id.health_date_year);
            for (int i = 0; i < year.getCount(); i++) if ((date.getYear() + "年").equals(year.getItemAtPosition(i))) year.setSelection(i);
            ((Spinner) dialog().findViewById(R.id.health_date_month)).setSelection(date.getMonthValue() - 1); return null;
        });
        instrumentation.waitForIdleSync();
        await(() -> onMain(() -> {
            GridView days = dialog().findViewById(R.id.health_date_days);
            int cell = date.withDayOfMonth(1).getDayOfWeek().getValue() - 1 + date.getDayOfMonth() - 1;
            return days.getChildCount() == 42 && !days.isLayoutRequested()
                    && days.getChildAt(cell).getContentDescription().toString().startsWith(date.toString());
        }));
        onMain(() -> {
            GridView days = dialog().findViewById(R.id.health_date_days);
            int cell = date.withDayOfMonth(1).getDayOfWeek().getValue() - 1 + date.getDayOfMonth() - 1;
            days.performItemClick(days.getChildAt(cell), cell, cell); return null;
        });
    }

    private void assertCompactCalendar() throws Exception {
        View hostWindow = activity().getWindow().getDecorView();
        int[] hostLocation = new int[2]; hostWindow.getLocationOnScreen(hostLocation);
        Rect hostBounds = new Rect(hostLocation[0], hostLocation[1], hostLocation[0] + hostWindow.getWidth(),
                hostLocation[1] + hostWindow.getHeight());
        View panel = dialog().findViewById(R.id.health_date_panel);
        assertFullyVisible(panel);
        int[] panelLocation = new int[2]; panel.getLocationOnScreen(panelLocation);
        Rect panelBounds = new Rect(panelLocation[0], panelLocation[1], panelLocation[0] + panel.getWidth(),
                panelLocation[1] + panel.getHeight());
        float density = activity().getResources().getDisplayMetrics().density;
        boolean landscape = hostBounds.width() > hostBounds.height();
        boolean largeLandscape = landscape && hostBounds.width() >= 840 * density && hostBounds.height() >= 520 * density;
        assertTrue("日期弹窗过宽：" + panelBounds,
                panelBounds.width() <= Math.round((largeLandscape ? 920 : landscape ? 704 : 440) * density) + 1);
        assertTrue("日期弹窗过高：" + panelBounds,
                panelBounds.height() <= Math.round((landscape && !largeLandscape ? 432 : 560) * density) + 1);
        if (largeLandscape) {
            assertTrue("大横屏日期弹窗应明显扩大：" + panelBounds, panelBounds.width() >= Math.round(800 * density));
            assertTrue("大横屏日期弹窗应利用高度：" + panelBounds, panelBounds.height() >= Math.round(480 * density));
        }
        int margin = Math.max(1, Math.round(8 * density) - 1);
        assertTrue("弹窗左侧应能看到原页面", panelBounds.left - hostBounds.left >= margin);
        assertTrue("弹窗右侧应能看到原页面", hostBounds.right - panelBounds.right >= margin);
        assertTrue("弹窗顶部应能看到原页面", panelBounds.top - hostBounds.top >= margin);
        assertTrue("弹窗底部应能看到原页面", hostBounds.bottom - panelBounds.bottom >= margin);
        assertEquals("日期弹窗应水平居中", hostBounds.exactCenterX(), panelBounds.exactCenterX(), Math.max(1, 2 * density));
        assertEquals("日期弹窗应垂直居中", hostBounds.exactCenterY(), panelBounds.exactCenterY(), Math.max(1, 2 * density));
        for (int id : new int[]{R.id.health_date_year, R.id.health_date_month, R.id.health_date_days,
                R.id.health_date_selected, R.id.health_date_weekday, R.id.health_date_previous, R.id.health_date_next,
                R.id.health_date_confirm, R.id.health_date_cancel, R.id.health_date_clear}) assertFullyVisible(dialog().findViewById(id));
        for (int id : new int[]{R.id.health_date_previous, R.id.health_date_next, R.id.health_date_confirm,
                R.id.health_date_cancel, R.id.health_date_clear}) {
            TextView button = dialog().findViewById(id);
            assertTrue(button.getText() + "触控高度不足", button.getHeight() >= Math.round((largeLandscape ? 60 : 44) * density));
            assertTextFits(button);
        }
        for (int id : new int[]{R.id.health_date_year, R.id.health_date_month}) {
            Spinner spinner = dialog().findViewById(id);
            assertTrue("年月选择器触控高度不足", spinner.getHeight() >= Math.round((largeLandscape ? 60 : 44) * density));
            assertNotNull(spinner.getSelectedView());
            assertFullyVisible(spinner.getSelectedView());
            assertTextFits((TextView) spinner.getSelectedView());
        }
        assertTextFits(dialog().findViewById(R.id.health_date_selected));
        GridView days = dialog().findViewById(R.id.health_date_days);
        assertEquals("日历必须有42格", 42, days.getCount());
        assertEquals("42格应同时挂载显示", 42, days.getChildCount());
        assertEquals(0, days.getFirstVisiblePosition());
        assertEquals(41, days.getLastVisiblePosition());
        assertFalse("日历不应需要向上滚动", days.canScrollVertically(-1));
        assertFalse("日历不应需要向下滚动", days.canScrollVertically(1));
        for (int i = 0; i < 42; i++) {
            TextView cell = (TextView) days.getChildAt(i);
            assertFullyVisible(cell);
            assertTextFits(cell);
        }
    }

    private void tapView(View view) throws Exception {
        Rect bounds = onMain(() -> {
            assertFullyVisible(view);
            int[] location = new int[2]; view.getLocationOnScreen(location);
            return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        });
        tap(bounds.exactCenterX(), bounds.exactCenterY());
    }

    private void tapPopupOption(String text, String screenshot) throws Exception {
        AccessibilityServiceInfo service = instrumentation.getUiAutomation().getServiceInfo();
        int originalFlags = service.flags;
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(service);
        // 弹层进场动画会暂时缩小无障碍边界；先等动画结束，最终验收仍要求完整 44dp。
        SystemClock.sleep(500);
        capture(screenshot + "-opened");
        AtomicReference<Rect> target = new AtomicReference<>();
        AtomicLong nextScrollAt = new AtomicLong(SystemClock.uptimeMillis() + 250);
        AtomicInteger scrollCount = new AtomicInteger();
        AtomicReference<String> previousMenu = new AtomicReference<>();
        AtomicReference<Rect> previousTarget = new AtomicReference<>();
        AtomicReference<Rect> previousViewport = new AtomicReference<>();
        AtomicInteger stableTarget = new AtomicInteger();
        try {
            await(() -> {
            List<AccessibilityWindowInfo> windows = instrumentation.getUiAutomation().getWindows();
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            StringBuilder description = new StringBuilder();
            AccessibilityNodeInfo active = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (active != null) {
                roots.add(active);
                description.append("activeRoot=").append(active.getClassName()).append(",text=").append(active.getText()).append(';');
            }
            try {
                for (AccessibilityWindowInfo window : windows) {
                    Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                    AccessibilityNodeInfo root = window.getRoot();
                    description.append(" window=").append(window.getId()).append('/').append(window.getType())
                            .append(",active=").append(window.isActive()).append(",focused=").append(window.isFocused())
                            .append(",bounds=").append(bounds).append(",root=")
                            .append(root == null ? "null" : root.getClassName()).append(';');
                    if (root != null) roots.add(root);
                }
                boolean scrollablePopup = false;
                for (AccessibilityNodeInfo root : roots) {
                    AccessibilityNodeInfo menu = visibleListMenu(root);
                    if (menu == null) continue;
                    try {
                        Rect menuBounds = new Rect(); menu.getBoundsInScreen(menuBounds);
                        description.append(" popupOptions=").append(visibleMenuDescription(menu));
                        List<AccessibilityNodeInfo> options = menu.findAccessibilityNodeInfosByText(text);
                        try {
                            for (AccessibilityNodeInfo option : options) {
                                if (!text.contentEquals(option.getText() == null ? "" : option.getText()) || !option.isVisibleToUser()) continue;
                                Rect bounds = new Rect(); option.getBoundsInScreen(bounds);
                                float density = instrumentation.getTargetContext().getResources().getDisplayMetrics().density;
                                int minimumHeight = Math.round(44 * density);
                                boolean lowTouchArea = bounds.height() < minimumHeight;
                                boolean nearTop = bounds.top <= menuBounds.top + 1;
                                boolean nearBottom = bounds.bottom >= menuBounds.bottom - 1;
                                String optionState = "待选=" + text + "，选项=" + bounds + "，菜单视口=" + menuBounds
                                        + "，density=" + density + "，最小触控=" + minimumHeight
                                        + "，近上沿=" + nearTop + "，近下沿=" + nearBottom + "，窗口=" + description;
                                if (!optionState.equals(previousMenu.getAndSet(optionState)))
                                    Log.i("HealthExpiryUiTest", optionState);
                                boolean sameTarget = bounds.equals(previousTarget.getAndSet(new Rect(bounds)));
                                boolean sameViewport = menuBounds.equals(previousViewport.getAndSet(new Rect(menuBounds)));
                                if (sameTarget && sameViewport) stableTarget.incrementAndGet();
                                else stableTarget.set(0);
                                if (stableTarget.get() < 3) return false;
                                if (lowTouchArea && (nearTop || nearBottom)) {
                                    // 边缘选项的无障碍边界可能已被菜单裁切；先真实滚入视口，再验收完整触控区域。
                                    stableTarget.set(0); previousTarget.set(null); previousViewport.set(null);
                                    long now = SystemClock.uptimeMillis();
                                    if (now >= nextScrollAt.get() && scrollCount.get() < 16) {
                                        boolean moved = option.performAction(
                                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                                        if (!moved) moved = menu.performAction(nearTop
                                                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                                        if (moved) {
                                            scrollablePopup = true;
                                            scrollCount.incrementAndGet();
                                            nextScrollAt.set(now + 250);
                                            capture(screenshot + "-edge-scrolling");
                                        }
                                    }
                                    return false;
                                }
                                assertTrue("日期下拉选项触控高度不足：" + optionState, !lowTouchArea);
                                assertTrue("日期下拉选项必须完整处于菜单视口：" + optionState,
                                        menuBounds.contains(bounds));
                                target.set(bounds); return true;
                            }
                        } finally { for (AccessibilityNodeInfo option : options) option.recycle(); }
                        long now = SystemClock.uptimeMillis();
                        if (!scrollablePopup && now >= nextScrollAt.get() && scrollCount.get() < 16
                                && scrollVisibleMenu(menu)) {
                            scrollablePopup = true;
                            if (scrollCount.getAndIncrement() == 0) capture(screenshot + "-scrolling");
                            nextScrollAt.set(now + 250);
                        }
                    } finally { menu.recycle(); }
                }
                String menu = description.toString();
                if (!menu.equals(previousMenu.getAndSet(menu))) Log.i("HealthExpiryUiTest", "待选=" + text + "，菜单=" + menu);
                return false;
            } finally {
                for (AccessibilityNodeInfo root : roots) root.recycle();
                for (AccessibilityWindowInfo window : windows) window.recycle();
            }
        });
        capture(screenshot);
        tap(target.get().exactCenterX(), target.get().exactCenterY());
        } finally {
            AccessibilityServiceInfo restored = instrumentation.getUiAutomation().getServiceInfo();
            restored.flags = originalFlags;
            instrumentation.getUiAutomation().setServiceInfo(restored);
        }
    }

    private static AccessibilityNodeInfo visibleListMenu(AccessibilityNodeInfo node) {
        CharSequence type = node.getClassName();
        if (node.isVisibleToUser() && type != null && type.toString().endsWith("ListView"))
            return AccessibilityNodeInfo.obtain(node);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                AccessibilityNodeInfo menu = visibleListMenu(child);
                if (menu != null) return menu;
            } finally { child.recycle(); }
        }
        return null;
    }

    private static boolean scrollVisibleMenu(AccessibilityNodeInfo node) {
        CharSequence type = node.getClassName();
        if (node.isVisibleToUser() && node.isScrollable() && type != null && type.toString().endsWith("ListView"))
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { if (scrollVisibleMenu(child)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private static String visibleMenuDescription(AccessibilityNodeInfo node) {
        StringBuilder visible = new StringBuilder();
        appendVisibleMenu(node, visible);
        return visible.toString();
    }

    private static void appendVisibleMenu(AccessibilityNodeInfo node, StringBuilder visible) {
        CharSequence text = node.getText();
        if (node.isVisibleToUser() && text != null && (text.toString().endsWith("年") || text.toString().endsWith("月"))) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            visible.append(text).append('@').append(bounds).append(' ');
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { appendVisibleMenu(child, visible); } finally { child.recycle(); }
        }
    }

    private void tap(float x, float y) {
        long pressedAt = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(pressedAt, pressedAt, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(pressedAt, pressedAt + 80, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue("无法按下日期控件", instrumentation.getUiAutomation().injectInputEvent(down, true));
            assertTrue("无法释放日期控件", instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }
    private static void assertFullyVisible(View view) {
        Rect rect = new Rect(); assertTrue(view.isShown()); assertTrue(view.getGlobalVisibleRect(rect));
        assertTrue(rect.height() >= view.getHeight()); assertTrue(rect.width() >= view.getWidth());
    }
    private static void assertTextFits(TextView view) {
        float textWidth = view.getPaint().measureText(view.getText().toString());
        int contentWidth = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        assertTrue(view.getText() + "文字超出按钮", contentWidth >= Math.ceil(textWidth));
        assertNotNull(view.getLayout());
        assertEquals(1, view.getLayout().getLineCount());
        assertEquals(0, view.getLayout().getEllipsisCount(0));
        assertTrue(view.getText() + "文字高度被裁切", view.getHeight() - view.getCompoundPaddingTop()
                - view.getCompoundPaddingBottom() >= view.getLayout().getHeight());
    }
    private void awaitStableDialog() throws Exception {
        instrumentation.waitForIdleSync();
        // waitForIdleSync 不等待窗口进入动画；先等框架动画结束，再验证连续绘制边界稳定。
        SystemClock.sleep(500);
        long deadline = SystemClock.uptimeMillis() + 8000;
        Rect previous = null;
        int stable = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            Rect current = onMain(() -> {
                HealthExpiryDialog picker = dialog();
                if (picker == null || !picker.isShowing()) return null;
                View panel = picker.findViewById(R.id.health_date_panel);
                Rect result = new Rect();
                GridView calendar = picker.findViewById(R.id.health_date_days);
                return panel.getAlpha() == 1f && !panel.isLayoutRequested() && calendar.getChildCount() == 42
                        && !calendar.isLayoutRequested() && panel.getGlobalVisibleRect(result) ? result : null;
            });
            stable = current != null && current.equals(previous) ? stable + 1 : 0;
            if (stable >= 3) { instrumentation.waitForIdleSync(); return; }
            previous = current;
            SystemClock.sleep(100);
        }
        fail("日期弹窗绘制边界未在时限内稳定");
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        File directory = new File(app.getCacheDir(), "health-expiry-qa"); assertTrue(directory.isDirectory() || directory.mkdirs());
        String prefix = InstrumentationRegistry.getArguments().getString("qa_prefix", "device").replaceAll("[^A-Za-z0-9_-]", "_");
        try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-" + name + ".png"))) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        bitmap.recycle();
    }
    private <T> T onMain(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get()); return value.get();
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < deadline) { if (condition.call()) return; SystemClock.sleep(40); }
        fail("日期页面状态未在时限内收敛");
    }
}
