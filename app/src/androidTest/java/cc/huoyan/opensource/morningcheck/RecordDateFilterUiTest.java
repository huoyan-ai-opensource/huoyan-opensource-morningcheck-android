package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.GridView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatViewInflater;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.domain.RecordDateRange;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 日期选择草稿与列表条件分离，覆盖范围确认、分钟编辑、重排以及窗口内的完整日历。 */
@RunWith(AndroidJUnit4.class)
public final class RecordDateFilterUiTest {
    private static final int[] PRESET_IDS = {R.id.record_date_today, R.id.record_date_24_hours,
            R.id.record_date_7_days, R.id.record_date_14_days, R.id.record_date_30_days, R.id.record_date_all};
    private static final RecordDateRange.Preset[] PRESETS = {RecordDateRange.Preset.TODAY,
            RecordDateRange.Preset.LAST_24_HOURS, RecordDateRange.Preset.LAST_7_DAYS,
            RecordDateRange.Preset.LAST_14_DAYS, RecordDateRange.Preset.LAST_30_DAYS, RecordDateRange.Preset.ALL};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger businessRequests = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences preferences, binding;
    private Map<String, Object> originalPreferences, originalBinding;
    private MockWebServer endpoint;
    private ActivityScenario<RecordsActivity> scenario;

    @Before public void isolateSdkAndPreservePreferences() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        binding = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
        originalPreferences = snapshot(preferences);
        originalBinding = snapshot(binding);
        instrumentation.runOnMainSync(() -> {
            assertFalse("日期筛选回归不能中断门店登录", app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() == null || !request.getPath().startsWith("/sdk")) businessRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("日期筛选隔离回归");
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).putInt(AppFontSettings.KEY, AppFontSettings.DEFAULT_INDEX).commit());
    }

    @After public void restorePreferencesAndCloseWindows() throws Exception {
        try {
            if (scenario != null) scenario.close();
            if (app != null) instrumentation.runOnMainSync(() -> { finishPages(); app.sdkSession().disconnect(); });
            instrumentation.waitForIdleSync();
            if (app != null) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            if (binding != null) assertEquals("日期筛选不能修改门店归属", originalBinding, binding.getAll());
        } finally {
            try {
                if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
                if (binding != null && originalBinding != null) restore(binding, originalBinding);
            } finally { if (endpoint != null) endpoint.shutdown(); }
        }
        assertEquals("日期筛选只能读取本地记录，不能访问业务云", 0, businessRequests.get());
    }

    @Test public void allShortcutRangesApplyOnlyAfterConfirmationAndCancelKeepsPreviousRange() {
        launch();
        for (int index = 0; index < PRESETS.length; index++) {
            final int selected = index;
            AtomicReference<RecordDateRange> before = new AtomicReference<>();
            scenario.onActivity(activity -> before.set(range(activity)));
            openWithPreset(selected);
            scenario.onActivity(activity -> {
                assertEquals("选择快捷项时不能提前改变列表", before.get(), range(activity));
                dialog(activity).findViewById(R.id.record_date_cancel).performClick();
                assertEquals("取消不能应用草稿", before.get(), range(activity));
                assertFalse("取消后不保留弹窗显示状态", ui(activity).saveState().getBoolean("showing"));
            });
            instrumentation.waitForIdleSync();
            openWithPreset(selected);
            scenario.onActivity(activity -> {
                RecordDateFilterDialog popup = dialog(activity);
                popup.findViewById(R.id.record_date_confirm).performClick();
                assertEquals("确认应应用对应的快捷范围", RecordDateRange.preset(PRESETS[selected]), range(activity));
                assertFalse("确认后弹窗必须关闭", popup.isShowing());
            });
            instrumentation.waitForIdleSync();
        }
    }

    @Test public void calendarAndTimeEditBothEndpointsAndConfirmTheExactMinutes() {
        launch();
        openWithPreset(0);
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            popup.findViewById(R.id.record_date_start).performClick();
            chooseDay(popup, 12);
            popup.findViewById(R.id.record_date_start_time).performClick();
            assertEquals(View.VISIBLE, popup.findViewById(R.id.record_date_time_editor).getVisibility());
            ((Spinner) popup.findViewById(R.id.record_date_hour)).setSelection(8);
            ((Spinner) popup.findViewById(R.id.record_date_minute)).setSelection(25);
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            assertEquals("08:25", ((TextView) popup.findViewById(R.id.record_date_start_time)).getText().toString());
            popup.findViewById(R.id.record_date_end).performClick();
            chooseDay(popup, 13);
            popup.findViewById(R.id.record_date_end_time).performClick();
            assertEquals(View.VISIBLE, popup.findViewById(R.id.record_date_time_editor).getVisibility());
            ((Spinner) popup.findViewById(R.id.record_date_hour)).setSelection(18);
            ((Spinner) popup.findViewById(R.id.record_date_minute)).setSelection(35);
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            assertEquals("18:35", ((TextView) popup.findViewById(R.id.record_date_end_time)).getText().toString());
            popup.findViewById(R.id.record_date_confirm).performClick();
            RecordDateRange applied = range(activity);
            assertEquals(RecordDateRange.Preset.CUSTOM, applied.preset());
            java.time.LocalDateTime start = java.time.Instant.ofEpochMilli(applied.customStart()).atZone(ZoneId.systemDefault()).toLocalDateTime();
            java.time.LocalDateTime end = java.time.Instant.ofEpochMilli(applied.customEnd()).atZone(ZoneId.systemDefault()).toLocalDateTime();
            assertEquals(12, start.getDayOfMonth()); assertEquals(8, start.getHour()); assertEquals(25, start.getMinute());
            assertEquals(13, end.getDayOfMonth()); assertEquals(18, end.getHour()); assertEquals(35, end.getMinute());
            assertFalse(applied.followsNow());
        });
    }

    @Test public void calendarAlwaysUsesTheLatestTwoClicksAndOrdersTheirDates() {
        launch();
        openCalendar();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            YearMonth month = YearMonth.parse(popup.saveDraft().getString("month"));
            chooseDay(popup, 12);
            assertWholeDayDraft(popup, month.atDay(12), month.atDay(12));
            chooseDay(popup, 18);
            assertWholeDayDraft(popup, month.atDay(12), month.atDay(18));
            chooseDay(popup, 22);
            assertWholeDayDraft(popup, month.atDay(18), month.atDay(22));
            chooseDay(popup, 8);
            assertWholeDayDraft(popup, month.atDay(8), month.atDay(22));
            chooseDay(popup, 14);
            assertWholeDayDraft(popup, month.atDay(8), month.atDay(14));
            chooseDay(popup, 14);
            assertWholeDayDraft(popup, month.atDay(14), month.atDay(14));
            chooseDay(popup, 24);
            assertWholeDayDraft(popup, month.atDay(14), month.atDay(24));
            assertEquals("连续点选不能提前改变记录条件", RecordDateRange.all(), range(activity));
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertEquals(wholeDays(month.atDay(14), month.atDay(24)), range(activity));
        });
    }

    @Test public void crossMonthRangeKeepsTheLatestClickAcrossDraftRestoreAndActivityRecreation() {
        launch();
        openCalendar();
        AtomicReference<LocalDate> first = new AtomicReference<>();
        AtomicReference<LocalDate> second = new AtomicReference<>();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            YearMonth month = YearMonth.parse(popup.saveDraft().getString("month"));
            first.set(month.atDay(24));
            chooseDay(popup, 24);
            popup.findViewById(R.id.record_date_next).performClick();
            second.set(month.plusMonths(1).atDay(3));
            chooseDay(popup, 3);
            Bundle draft = popup.saveDraft();
            popup.findViewById(R.id.record_date_reset).performClick();
            popup.restoreDraft(draft);
            assertEquals("恢复草稿应保留正在浏览的下个月", month.plusMonths(1).toString(),
                    popup.saveDraft().getString("month"));
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFalse("重排应释放旧日期窗口", popup.isShowing());
            assertWholeDayDraft(dialog(activity), first.get(), second.get());
        });
        scenario.recreate();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            LocalDate third = YearMonth.from(second.get()).atDay(10);
            assertEquals("重建应保留最近点击日期所在月份", YearMonth.from(second.get()).toString(),
                    popup.saveDraft().getString("month"));
            chooseDay(popup, 10);
            assertWholeDayDraft(popup, second.get(), third);
            assertEquals("恢复后应丢弃最早点选且仍只修改草稿", RecordDateRange.all(), range(activity));
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertEquals(wholeDays(second.get(), third), range(activity));
        });
    }

    @Test public void singleCalendarClickCanApplyWholeDayAndShortcutsResetLatestClick() {
        launch();
        openCalendar();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            chooseDay(popup, 7);
            popup.findViewById(R.id.record_date_cancel).performClick();
            assertEquals("取消单次点选不能改变记录条件", RecordDateRange.all(), range(activity));
        });
        openCalendar();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            YearMonth month = YearMonth.parse(popup.saveDraft().getString("month"));
            chooseDay(popup, 9);
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertEquals("只点一次即可筛选整天", wholeDays(month.atDay(9), month.atDay(9)), range(activity));
        });
        openCalendar();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            RecordDateRange previous = range(activity);
            for (int shortcut : new int[]{R.id.record_date_today, R.id.record_date_yesterday,
                    R.id.record_date_7_days, R.id.record_date_this_month, R.id.record_date_reset}) {
                chooseDay(popup, 7);
                assertTrue("快捷范围必须可以操作", popup.findViewById(shortcut).performClick());
                YearMonth month = YearMonth.parse(popup.saveDraft().getString("month"));
                chooseDay(popup, 12);
                assertWholeDayDraft(popup, month.atDay(12), month.atDay(12));
                chooseDay(popup, 18);
                assertWholeDayDraft(popup, month.atDay(12), month.atDay(18));
                assertEquals("快捷范围和日历草稿均不能提前应用", previous, range(activity));
            }
            popup.findViewById(R.id.record_date_cancel).performClick();
            assertEquals("取消重选范围应保留上次单日条件", previous, range(activity));
        });
    }

    @Test public void calendarRangePaintsStraightInnerEdgesAndKeepsSingleDayRounded() throws Exception {
        launch();
        openCalendar();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            chooseDay(popup, 1);
            chooseDay(popup, 21);
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            GridView calendar = popup.findViewById(R.id.record_date_days);
            View start = calendarDayCell(popup, 1), middle = calendarDayCell(popup, 2), end = calendarDayCell(popup, 21);
            int primary = ContextCompat.getColor(activity, R.color.mc_button_primary_fill),
                    soft = ContextCompat.getColor(activity, R.color.mc_soft);
            Bitmap image = Bitmap.createBitmap(calendar.getWidth(), calendar.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                calendar.draw(new Canvas(image));
                assertEquals("范围起点右上角应为直角实色", primary, image.getPixel(start.getRight() - 1, start.getTop()));
                assertEquals("范围起点右下角应为直角实色", primary, image.getPixel(start.getRight() - 1, start.getBottom() - 1));
                assertEquals("范围终点左上角应为直角实色", primary, image.getPixel(end.getLeft(), end.getTop()));
                assertEquals("范围终点左下角应为直角实色", primary, image.getPixel(end.getLeft(), end.getBottom() - 1));
                for (int x : new int[]{middle.getLeft(), middle.getRight() - 1}) {
                    assertEquals("范围中间日期上角不应露出白缝", soft, image.getPixel(x, middle.getTop()));
                    assertEquals("范围中间日期下角不应露出白缝", soft, image.getPixel(x, middle.getBottom() - 1));
                }
            } finally { image.recycle(); }
        });
        capture("continuous-range");
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            chooseDay(popup, 14);
            chooseDay(popup, 14);
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            View day = calendarDayCell(dialog(activity), 14);
            Drawable.ConstantState state = day.getBackground().getConstantState();
            assertNotNull("单日日期背景应支持独立绘制", state);
            Drawable background = state.newDrawable(activity.getResources()).mutate();
            background.setBounds(0, 0, day.getWidth(), day.getHeight());
            background.setState(day.getDrawableState());
            background.jumpToCurrentState();
            Bitmap image = Bitmap.createBitmap(day.getWidth(), day.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                background.draw(new Canvas(image));
                for (int x : new int[]{0, image.getWidth() - 1}) for (int y : new int[]{0, image.getHeight() - 1})
                    assertEquals("同一天的完整圆角外侧应保持透明", 0, Color.alpha(image.getPixel(x, y)));
                assertEquals("单日背景中心应保留选中颜色", ContextCompat.getColor(activity, R.color.mc_button_primary_fill),
                        image.getPixel(image.getWidth() / 2, image.getHeight() / 2));
            } finally { image.recycle(); }
        });
    }

    @Test public void reversedRangeShowsErrorAndDoesNotCloseOrApply() {
        launch();
        openWithPreset(0);
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            popup.findViewById(R.id.record_date_start).performClick();
            chooseDay(popup, 28);
            popup.findViewById(R.id.record_date_end).performClick();
            chooseDay(popup, 2);
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertTrue("非法范围不能收起弹窗", popup.isShowing());
            assertNotNull("非法日期范围应通过 Toast 提示", ToastEx.toast);
            assertEquals("结束时间不能早于开始时间", ((TextView) ToastEx.toast.getView().findViewById(R.id.toast_text)).getText().toString());
            assertEquals("非法范围不能改变列表", RecordDateRange.all(), range(activity));
        });
    }

    @Test public void followingNowUsesActualCurrentTimeAndManualEndStopsFollowing() {
        launch();
        openWithPreset(2);
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            ((CheckBox) popup.findViewById(R.id.record_date_follow_now)).setChecked(true);
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertTrue("跟随当前时刻需保存动态条件", range(activity).followsNow());
            assertTrue(activity.findViewById(R.id.records_date_filter).getContentDescription().toString().contains("当前时刻"));
            activity.findViewById(R.id.records_date_filter).performClick();
            popup = dialog(activity);
            assertTrue(((CheckBox) popup.findViewById(R.id.record_date_follow_now)).isChecked());
            popup.findViewById(R.id.record_date_end).performClick();
            assertFalse("手动编辑结束时间自动切回固定截止", ((CheckBox) popup.findViewById(R.id.record_date_follow_now)).isChecked());
            popup.findViewById(R.id.record_date_confirm).performClick();
            assertFalse(range(activity).followsNow());
        });
    }

    @Test public void redrawAndRecreationKeepUnconfirmedDraftAndDismissDoesNotReopen() {
        launch();
        openWithPreset(3);
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            popup.findViewById(R.id.record_date_end).performClick();
            chooseDay(popup, 22);
            Bundle draft = popup.saveDraft();
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFalse("旧窗口必须释放", popup.isShowing());
            RecordDateFilterDialog restored = dialog(activity);
            assertNotSame(popup, restored);
            assertEquals("重排保留开始分钟", draft.getLong("start"), restored.saveDraft().getLong("start"));
            assertEquals("重排保留结束分钟", draft.getLong("end"), restored.saveDraft().getLong("end"));
            assertTrue(restored.saveDraft().getBoolean("edit_end"));
            assertEquals("未确认的草稿仍不能应用", RecordDateRange.all(), range(activity));
        });
        scenario.recreate();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog restored = dialog(activity);
            assertTrue("系统重建仍恢复日期窗口", restored.isShowing());
            assertTrue(restored.saveDraft().getBoolean("edit_end"));
            assertEquals("未确认范围不得提前应用", RecordDateRange.all(), range(activity));
            restored.findViewById(R.id.record_date_cancel).performClick();
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFalse("取消过的窗口不能在下一次重排时重新弹出", ui(activity).saveState().getBoolean("showing"));
        });
    }

    @Test public void ordinaryBackgroundStopClosesDraftAndReturnDoesNotReopenIt() {
        launch();
        AtomicReference<RecordsActivity> previous = new AtomicReference<>();
        AtomicReference<RecordDateFilterDialog> previousDialog = new AtomicReference<>();
        openWithPreset(2);
        scenario.onActivity(activity -> {
            previous.set(activity);
            previousDialog.set(dialog(activity));
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        instrumentation.runOnMainSync(() -> {
            assertFalse("普通进入后台必须关闭日期窗口", previousDialog.get().isShowing());
            assertFalse("普通停止不保留显示标记", ui(previous.get()).saveState().getBoolean("showing"));
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            assertFalse("返回原页面不得自动重新打开日期窗口", ui(activity).saveState().getBoolean("showing"));
            assertEquals("未确认草稿仍不能应用", RecordDateRange.all(), range(activity));
        });
    }

    @Test public void calendarAndActionsFitEveryLayoutAtAllFontSizesAndActualWindow() throws Exception {
        instrumentation.runOnMainSync(() -> {
            for (int[] window : new int[][]{{360, 640}, {640, 360}, {800, 1280}, {1280, 800}, {360, 480}, {640, 320}, {640, 480}}) {
                for (int font = 0; font < 4; font++) {
                    Configuration config = new Configuration(app.getResources().getConfiguration());
                    config.screenWidthDp = window[0]; config.screenHeightDp = window[1];
                    config.smallestScreenWidthDp = Math.min(window[0], window[1]);
                    config.orientation = window[0] > window[1] ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                    config.fontScale = AppFontSettings.scaleFor(font);
                    Context context = new ContextThemeWrapper(app.createConfigurationContext(config), R.style.AppTheme);
                    installDateViewFactory(context);
                    RecordDateFilterDialog popup = new RecordDateFilterDialog(context, RecordDateRange.today(), ignored -> { });
                    ViewGroup panel = popup.findViewById(R.id.record_date_panel);
                    float density = context.getResources().getDisplayMetrics().density;
                    int[] size = popup.panelSize(Math.round(window[0] * density), Math.round(window[1] * density));
                    layoutDatePanel(panel, size);
                    String scene = window[0] + "×" + window[1] + "，字号 " + config.fontScale;
                    if (panel.findViewById(R.id.record_date_preset_picker) != null)
                        assertContained(panel, panel.findViewById(R.id.record_date_preset_picker), scene);
                    else for (int id : PRESET_IDS) assertContained(panel, panel.findViewById(id), scene);
                    for (int id : new int[]{R.id.record_date_start, R.id.record_date_end, R.id.record_date_follow_now,
                            R.id.record_date_start_time, R.id.record_date_end_time, R.id.record_date_cancel, R.id.record_date_confirm})
                        assertContained(panel, panel.findViewById(id), scene);
                    assertTrue(scene + "：起止文字必须使用正式 AppCompat 字体组件",
                            panel.findViewById(R.id.record_date_start_time) instanceof AppCompatTextView);
                    assertCalendarFits(panel, density, config.fontScale, scene + "，日期编辑");
                    assertVisibleTextFits(panel, panel, scene + "，日期编辑");

                    Bundle initialDraft = popup.saveDraft();
                    Bundle reversedDraft = new Bundle(initialDraft);
                    reversedDraft.putString("preset", RecordDateRange.Preset.CUSTOM.name());
                    reversedDraft.putLong("start", initialDraft.getLong("end"));
                    reversedDraft.putLong("end", initialDraft.getLong("start"));
                    reversedDraft.putBoolean("follow", false);
                    reversedDraft.putBoolean("edit_end", true);
                    reversedDraft.putBoolean("editing_time", false);
                    popup.restoreDraft(reversedDraft);
                    layoutDatePanel(panel, size);
                    assertEquals(scene + "：恢复的非法草稿必须保留倒置范围", reversedDraft.getLong("start"),
                            popup.saveDraft().getLong("start"));
                    assertEquals(scene + "：恢复的非法草稿必须显示错误提示", View.VISIBLE,
                            panel.findViewById(R.id.record_date_error).getVisibility());
                    assertEquals(scene + "：错误提示必须完整保留", context.getString(R.string.record_date_range_error),
                            ((TextView) panel.findViewById(R.id.record_date_error)).getText().toString());
                    assertCalendarFits(panel, density, config.fontScale, scene + "，非法日期范围");
                    assertVisibleTextFits(panel, panel, scene + "，非法日期范围");
                    popup.restoreDraft(initialDraft);
                    layoutDatePanel(panel, size);
                    assertEquals(scene + "：恢复有效草稿必须清除错误提示", View.GONE,
                            panel.findViewById(R.id.record_date_error).getVisibility());
                    assertTrue(scene + "：开始时间必须能进入编辑", panel.findViewById(R.id.record_date_start_time).performClick());
                    layoutDatePanel(panel, size);
                    assertEquals(scene + "：时间编辑区必须真实显示", View.VISIBLE,
                            panel.findViewById(R.id.record_date_time_editor).getVisibility());
                    assertEquals(scene + "：编辑时间时应让出日历空间", View.GONE,
                            panel.findViewById(R.id.record_date_calendar_body).getVisibility());
                    assertVisibleTextFits(panel, panel, scene + "，开始时间编辑");
                    assertNoPageScroll(panel, scene);
                    popup.dismiss();
                }
            }
        });
        for (int font : new int[]{AppFontSettings.DEFAULT_INDEX, 3}) {
            assertTrue(preferences.edit().putInt(AppFontSettings.KEY, font).commit());
            launch();
            openWithPreset(2);
            scenario.onActivity(activity -> {
                RecordDateFilterDialog popup = dialog(activity);
                View panel = popup.findViewById(R.id.record_date_panel);
                Rect actual = ScreenViewGeometry.bounds(panel), visible = new Rect();
                assertTrue(ScreenViewGeometry.visibleOnScreen(panel, visible));
                assertEquals("实际弹窗必须完整位于屏幕", actual, visible);
                for (int id : new int[]{R.id.record_date_days, R.id.record_date_cancel, R.id.record_date_confirm}) {
                    View view = popup.findViewById(id);
                    assertTrue(ScreenViewGeometry.visibleOnScreen(view, visible));
                    assertEquals("实际控件不得裁切", ScreenViewGeometry.bounds(view), visible);
                }
                assertVisibleTextFits((ViewGroup) panel, panel, "实际日期窗口，字号 " + font);
            });
            capture(font == AppFontSettings.DEFAULT_INDEX ? "default" : "largest");
            scenario.close(); scenario = null;
            instrumentation.waitForIdleSync();
        }
    }

    @Test public void recordFiltersAndPaginationStayInsideEveryWindowWithCustomRange() throws Exception {
        RecordDateRange[] ranges = customEntryRanges();
        String[] cases = {"同年区间", "同日区间", "跨年区间", "跟随当前时刻"};
        launch();
        scenario.onActivity(activity -> {
            for (int[] window : new int[][]{{360, 640}, {640, 360}, {800, 1280}, {1280, 800}, {360, 480}, {640, 320}}) {
                for (int font = 0; font < 4; font++) {
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.screenWidthDp = window[0]; config.screenHeightDp = window[1];
                    config.smallestScreenWidthDp = Math.min(window[0], window[1]);
                    config.orientation = window[0] > window[1] ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                    config.fontScale = AppFontSettings.scaleFor(font);
                    Context context = new ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme);
                    installDateViewFactory(context);
                    ViewGroup panel = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.activity_records, null);
                    TextView trigger = panel.findViewById(R.id.records_date_filter);
                    assertTrue("日期入口必须使用正式 AppCompat 字体组件", trigger instanceof AppCompatTextView);
                    float density = context.getResources().getDisplayMetrics().density;
                    int width = Math.round(window[0] * density), height = Math.round(window[1] * density);
                    int[] size = {width, height};
                    layoutDatePanel(panel, size);
                    RecordDateFilterUi entry = new RecordDateFilterUi(activity, trigger, ranges[0], ignored -> { });
                    String scene = "记录列表 " + window[0] + "×" + window[1] + "，字号 " + config.fontScale;
                    for (int index = 0; index < ranges.length; index++) {
                        RecordDateRange chosen = ranges[index];
                        entry.replace(chosen);
                        layoutDatePanel(panel, size);
                        String rangeScene = scene + "，" + cases[index];
                        assertDateEntryFits(panel, trigger, chosen, rangeScene);
                        if (index == 0 && font == AppFontSettings.DEFAULT_INDEX
                                && ((window[0] == 640 && window[1] == 360)
                                || (window[0] == 1280 && window[1] == 800))) {
                            assertEquals(rangeScene + "：宽窗默认字号应使用单行区间", 1, trigger.getLayout().getLineCount());
                            assertEquals(rangeScene + "：单行日期筛选框必须与搜索框等高",
                                    panel.findViewById(R.id.records_search).getHeight(), trigger.getHeight());
                        }
                        for (int id : new int[]{R.id.records_search, R.id.records_filter, R.id.records_department_filter,
                                R.id.records_date_filter, R.id.records_table_content, R.id.records_load_more})
                            assertContained(panel, panel.findViewById(id), rangeScene);
                        View table = panel.findViewById(R.id.records_table_content);
                        View viewport = (View) table.getParent();
                        View header = panel.findViewById(R.id.records_table_header);
                        View rows = panel.findViewById(R.id.records_list);
                        assertTrue(rangeScene + "：记录表格高度应留在实际容器内", table.getTop() >= 0
                                && table.getBottom() <= viewport.getHeight());
                        assertEquals(rangeScene + "：表头与记录共用实际表格宽度", header.getWidth(), rows.getWidth());
                        assertTrue(rangeScene + "：表头必须保留高度", header.getHeight() > 0);
                        assertTrue(rangeScene + "：筛选和加载更多之外必须留有记录展示空间", rows.getHeight() > 0
                                && rows.getBottom() <= table.getHeight());
                    }
                    entry.dismiss();
                }
            }
        });
        scenario.close(); scenario = null;
        instrumentation.waitForIdleSync();
        for (int font : new int[]{AppFontSettings.DEFAULT_INDEX, 3}) {
            assertTrue(preferences.edit().putInt(AppFontSettings.KEY, font).commit());
            launch();
            for (int index = 0; index < ranges.length; index++) {
                RecordDateRange chosen = ranges[index];
                String scene = "实际日期入口，字号 " + font + "，" + cases[index];
                boolean checkDefaultAlignment = index == 0 && font == AppFontSettings.DEFAULT_INDEX;
                scenario.onActivity(activity -> ui(activity).replace(chosen));
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    TextView trigger = activity.findViewById(R.id.records_date_filter);
                    assertDateEntryFits((ViewGroup) activity.getWindow().getDecorView(), trigger, chosen, scene);
                    View search = activity.findViewById(R.id.records_search);
                    // 手机竖向排列不强制等高；横屏同一排的两个输入框应保持齐整。
                    if (checkDefaultAlignment && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                            && search.getParent() instanceof android.widget.LinearLayout
                            && ((android.widget.LinearLayout) search.getParent()).getOrientation() == android.widget.LinearLayout.HORIZONTAL) {
                        assertEquals(scene + "：实际宽屏默认入口应显示单行区间", 1, trigger.getLayout().getLineCount());
                        assertEquals(scene + "：实际日期筛选框必须与同排搜索框等高", search.getHeight(), trigger.getHeight());
                    }
                    assertTrue(scene + "：自定义日期入口必须能打开日期选择", trigger.performClick());
                });
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    RecordDateFilterDialog popup = dialog(activity);
                    View panel = popup.findViewById(R.id.record_date_panel);
                    assertTrue(scene + "：选择窗口必须实际挂载", panel.isAttachedToWindow());
                    Bundle draft = popup.saveDraft();
                    assertEquals(scene + "：窗口恢复自定义范围", RecordDateRange.Preset.CUSTOM.name(), draft.getString("preset"));
                    assertEquals(scene + "：窗口保留开始分钟", chosen.customStart(), draft.getLong("start"));
                    assertEquals(scene + "：窗口保留跟随当前时刻状态", chosen.followsNow(), draft.getBoolean("follow"));
                    if (!chosen.followsNow()) assertEquals(scene + "：窗口保留结束分钟", chosen.customEnd(), draft.getLong("end"));
                    popup.findViewById(R.id.record_date_confirm).performClick();
                    assertFalse(scene + "：确认后应关闭窗口", popup.isShowing());
                    assertEquals(scene + "：确认仍应用真实起点", chosen.customStart(), range(activity).customStart());
                    assertEquals(scene + "：确认仍应用真实动态截止状态", chosen.followsNow(), range(activity).followsNow());
                    if (!chosen.followsNow()) assertEquals(scene + "：确认仍应用真实结束分钟", chosen.customEnd(), range(activity).customEnd());
                });
                instrumentation.waitForIdleSync();
                if (index == 0) captureDateEntry(font == AppFontSettings.DEFAULT_INDEX ? "default" : "largest");
            }
            scenario.onActivity(activity -> {
                int[] labels = {R.string.record_date_today, R.string.record_date_24_hours,
                        R.string.record_date_7_days, R.string.record_date_14_days, R.string.record_date_30_days, R.string.record_date_all};
                for (int index = 0; index < PRESETS.length; index++) {
                    ui(activity).replace(RecordDateRange.preset(PRESETS[index]));
                    assertEquals("快捷入口保留原来的范围名称", activity.getString(labels[index]),
                            ((TextView) activity.findViewById(R.id.records_date_filter)).getText().toString());
                }
            });
            scenario.close(); scenario = null;
            instrumentation.waitForIdleSync();
        }
    }

    private static RecordDateRange[] customEntryRanges() {
        ZoneId zone = ZoneId.systemDefault();
        long start = LocalDate.of(2026, 10, 1).atTime(8, 25).atZone(zone).toInstant().toEpochMilli();
        long followingStart = LocalDate.now(zone).minusDays(7).atTime(8, 25).atZone(zone).toInstant().toEpochMilli();
        return new RecordDateRange[]{
                RecordDateRange.custom(start, LocalDate.of(2026, 10, 12).atTime(18, 35).atZone(zone).toInstant().toEpochMilli(), false),
                RecordDateRange.custom(start, LocalDate.of(2026, 10, 1).atTime(18, 35).atZone(zone).toInstant().toEpochMilli(), false),
                RecordDateRange.custom(LocalDate.of(2025, 12, 31).atTime(23, 55).atZone(zone).toInstant().toEpochMilli(),
                        LocalDate.of(2026, 1, 1).atTime(0, 5).atZone(zone).toInstant().toEpochMilli(), false),
                RecordDateRange.custom(followingStart, System.currentTimeMillis(), true)};
    }
    private static void assertDateEntryFits(ViewGroup panel, TextView trigger, RecordDateRange range, String scene) {
        assertVisibleTextFits(panel, trigger, scene);
        String rendered = trigger.getText().toString();
        java.time.LocalDateTime start = java.time.Instant.ofEpochMilli(range.customStart()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        java.time.LocalDateTime end = java.time.Instant.ofEpochMilli(range.customEnd()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        DateTimeFormatter date = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.CHINA);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
        assertTrue(scene + "：起点日期不可丢失", rendered.contains(date.format(start)));
        assertTrue(scene + "：起点分钟不可丢失", rendered.contains(time.format(start)));
        if (range.followsNow()) assertTrue(scene + "：动态截止必须明确显示当前时刻",
                rendered.contains(trigger.getContext().getString(R.string.record_date_now)));
        else {
            assertTrue(scene + "：结束分钟不可丢失", rendered.contains(time.format(end)));
            if (!start.toLocalDate().equals(end.toLocalDate())) {
                String endDate = start.getYear() == end.getYear()
                        ? DateTimeFormatter.ofPattern("MM/dd", Locale.CHINA).format(end) : date.format(end);
                assertTrue(scene + "：不同日期及跨年区间不能误省略结束日期", rendered.contains(endDate));
            }
        }
        String fullStart = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.CHINA).format(start);
        String fullEnd = range.followsNow() ? trigger.getContext().getString(R.string.record_date_now)
                : DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.CHINA).format(end);
        String description = trigger.getContentDescription().toString();
        assertTrue(scene + "：无障碍说明始终保留完整开始时间", description.contains(fullStart));
        assertTrue(scene + "：无障碍说明始终保留完整结束时间", description.contains(fullEnd));
    }

    private void launch() { scenario = ActivityScenario.launch(new Intent(app, RecordsActivity.class)); instrumentation.waitForIdleSync(); }
    private RecordDateRange range(RecordsActivity page) { return field(page, "dateRange", RecordDateRange.class); }
    private RecordDateFilterUi ui(RecordsActivity page) { return field(page, "dateFilter", RecordDateFilterUi.class); }
    private RecordDateFilterDialog dialog(RecordsActivity page) {
        RecordDateFilterDialog dialog = field(ui(page), "dialog", RecordDateFilterDialog.class);
        assertNotNull("记录日期入口必须打开日期窗口", dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static <T> T field(Object object, String name, Class<T> type) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private void openCalendar() {
        scenario.onActivity(activity -> activity.findViewById(R.id.records_date_filter).performClick());
        instrumentation.waitForIdleSync();
    }
    private static void assertWholeDayDraft(RecordDateFilterDialog popup, LocalDate start, LocalDate end) {
        Bundle draft = popup.saveDraft();
        RecordDateRange expected = wholeDays(start, end);
        assertEquals("日历点选应切换为自定义范围", RecordDateRange.Preset.CUSTOM.name(), draft.getString("preset"));
        assertEquals("范围应从较早日期零点开始", expected.customStart(), draft.getLong("start"));
        assertEquals("范围应包含较晚日期的完整一天", expected.customEnd(), draft.getLong("end"));
        assertFalse("普通日历点选应使用固定结束日期", draft.getBoolean("follow"));
    }
    private static RecordDateRange wholeDays(LocalDate start, LocalDate end) {
        ZoneId zone = ZoneId.systemDefault();
        return RecordDateRange.custom(start.atStartOfDay(zone).toInstant().toEpochMilli(),
                end.atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), false);
    }
    private void openWithPreset(int index) {
        scenario.onActivity(activity -> activity.findViewById(R.id.records_date_filter).performClick());
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            Spinner picker = popup.findViewById(R.id.record_date_preset_picker);
            if (picker != null && !picker.isShown())
                assertTrue("高级快捷范围隐藏时应先展开日期高级选项", popup.findViewById(R.id.record_date_title).performLongClick());
        });
        instrumentation.waitForIdleSync();
        AtomicReference<Spinner> activePicker = new AtomicReference<>();
        scenario.onActivity(activity -> {
            RecordDateFilterDialog popup = dialog(activity);
            View panel = popup.findViewById(R.id.record_date_panel);
            assertTrue("先等待真实日期窗口完成挂载", panel.isAttachedToWindow());
            assertTrue("快捷选择前窗口应完成布局", panel.getWidth() > 0 && panel.getHeight() > 0);
            Spinner picker = popup.findViewById(R.id.record_date_preset_picker);
            if (picker == null) {
                View button = popup.findViewById(PRESET_IDS[index]);
                assertTrue("快捷范围按钮必须可见", button.isShown());
                button.performClick();
            } else {
                activePicker.set(picker);
                assertTrue("手机的快捷范围入口必须可见", picker.isShown());
                assertTrue("手机必须实际打开快捷范围下拉", picker.performClick());
            }
        });
        instrumentation.waitForIdleSync();
        if (activePicker.get() != null) {
            scenario.onActivity(activity -> {
                Spinner picker = activePicker.get();
                try {
                    Object popupWindow = spinnerPopup(picker);
                    assertTrue("快捷范围列表必须真实显示", popupShowing(popupWindow));
                    android.widget.ListView list = popupList(popupWindow);
                    assertNotNull(list);
                    assertTrue("快捷范围应包含全部六预设与自定义", list.getAdapter().getCount() >= 6);
                    View option = list.getChildAt(index - list.getFirstVisiblePosition());
                    assertNotNull("所选快捷范围必须出现在真实下拉列表", option);
                    assertTrue("快捷范围选项必须已经可见并完成布局", option.isShown() && option.getWidth() > 0 && option.getHeight() > 0);
                    assertTrue("选择真实可见的下拉条目必须成功", list.performItemClick(option, index, list.getAdapter().getItemId(index)));
                    assertFalse("选择范围后应收起快捷列表", popupShowing(popupWindow));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        }
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> assertEquals("快捷范围已提交到弹窗草稿", PRESETS[index].name(), dialog(activity).saveDraft().getString("preset")));
    }
    private static Object spinnerPopup(Spinner picker) throws ReflectiveOperationException {
        if (picker instanceof ChoiceSpinner) {
            Field dialog = ChoiceSpinner.class.getDeclaredField("choiceDialog");
            dialog.setAccessible(true);
            return dialog.get(picker);
        }
        // AppCompat 会替换 XML 的 Spinner；读取最具体类的窗口，不能取其闲置的平台窗口。
        for (Class<?> type = picker.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field popup = type.getDeclaredField("mPopup");
                popup.setAccessible(true);
                Object value = popup.get(picker);
                if (value != null) return value;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(picker.getClass().getName() + ".mPopup");
    }
    private static boolean popupShowing(Object popup) {
        if (popup instanceof AlertDialog) return ((AlertDialog) popup).isShowing();
        if (popup instanceof androidx.appcompat.widget.ListPopupWindow)
            return ((androidx.appcompat.widget.ListPopupWindow) popup).isShowing();
        if (popup instanceof android.widget.ListPopupWindow)
            return ((android.widget.ListPopupWindow) popup).isShowing();
        throw new AssertionError("不支持的快捷范围窗口：" + popup.getClass().getName());
    }
    private static android.widget.ListView popupList(Object popup) {
        if (popup instanceof AlertDialog)
            return ((AlertDialog) popup).findViewById(R.id.department_filter_options);
        if (popup instanceof androidx.appcompat.widget.ListPopupWindow)
            return ((androidx.appcompat.widget.ListPopupWindow) popup).getListView();
        if (popup instanceof android.widget.ListPopupWindow)
            return ((android.widget.ListPopupWindow) popup).getListView();
        throw new AssertionError("不支持的快捷范围窗口：" + popup.getClass().getName());
    }
    private static void chooseDay(RecordDateFilterDialog popup, int day) {
        GridView calendar = popup.findViewById(R.id.record_date_days);
        String selectedMonth = popup.saveDraft().getString("month");
        for (int position = 0; position < calendar.getAdapter().getCount(); position++) {
            LocalDate value = (LocalDate) calendar.getAdapter().getItem(position);
            if (value.getDayOfMonth() == day && value.toString().startsWith(selectedMonth)) {
                calendar.performItemClick(calendar.getChildAt(position), position, calendar.getAdapter().getItemId(position)); return;
            }
        }
        fail("日历未找到当月日期 " + day);
    }
    private static View calendarDayCell(RecordDateFilterDialog popup, int day) {
        GridView calendar = popup.findViewById(R.id.record_date_days);
        String selectedMonth = popup.saveDraft().getString("month");
        for (int position = 0; position < calendar.getAdapter().getCount(); position++) {
            LocalDate value = (LocalDate) calendar.getAdapter().getItem(position);
            if (value.getDayOfMonth() != day || !value.toString().startsWith(selectedMonth)) continue;
            View cell = calendar.getChildAt(position - calendar.getFirstVisiblePosition());
            assertNotNull("日期必须已显示在真实日历中：" + value, cell);
            assertTrue("日期必须完成实际布局：" + value, cell.getWidth() > 0 && cell.getHeight() > 0);
            return cell;
        }
        throw new AssertionError("日历未找到当月日期 " + day);
    }
    private static void layoutDatePanel(ViewGroup panel, int[] size) {
        // Spinner 和日历适配器在首轮布局后填充子项，再测量确认最终显示尺寸。
        for (int pass = 0; pass < 3; pass++) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY));
            panel.layout(0, 0, size[0], size[1]);
        }
    }
    private void installDateViewFactory(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        assertNotSame("合成日期窗口不能修改 application 的共享 inflater", LayoutInflater.from(app), inflater);
        if (inflater.getFactory() != null || inflater.getFactory2() != null) return;
        // 每轮新建的主题上下文持有自己的 inflater；模拟 Activity 的 AppCompat Factory2。
        AppCompatViewInflater widgets = new AppCompatViewInflater();
        inflater.setFactory2(new LayoutInflater.Factory2() {
            @Override public View onCreateView(View parent, String name, Context viewContext, AttributeSet attrs) {
                return widgets.createView(parent, name, viewContext, attrs, false, true, true, false);
            }
            @Override public View onCreateView(String name, Context viewContext, AttributeSet attrs) {
                return onCreateView(null, name, viewContext, attrs);
            }
        });
    }
    private static void assertCalendarFits(ViewGroup panel, float density, float fontScale, String scene) {
        GridView calendar = panel.findViewById(R.id.record_date_days);
        assertContained(panel, calendar, scene);
        assertEquals(scene + "：每个月始终保留六周日期", 42, calendar.getAdapter().getCount());
        assertEquals(scene + "：六周日期必须全部同时显示", 42, calendar.getChildCount());
        assertEquals(scene + "：日历不能滚离首周", 0, calendar.getFirstVisiblePosition());
        assertEquals(scene + "：最后一周必须实际显示", 41, calendar.getLastVisiblePosition());
        Set<Integer> rowTops = new HashSet<>();
        for (int index = 0; index < calendar.getChildCount(); index++) {
            View day = calendar.getChildAt(index);
            assertTrue(scene + "：日期必须使用实际的文字组件", day instanceof TextView);
            TextView cell = (TextView) day;
            assertContained(panel, cell, scene);
            assertTrue(scene + "：第 " + index + " 个日期触控高度至少28dp，实际=" + cell.getHeight(),
                    cell.getHeight() >= Math.round(28 * density));
            assertTrue(scene + "：日期字不能被压缩小于12sp", cell.getTextSize() >= 12 * density * fontScale);
            android.graphics.Paint.FontMetrics metrics = cell.getPaint().getFontMetrics();
            assertTrue(scene + "：日期行必须容纳完整字高", metrics.descent - metrics.ascent
                    <= cell.getHeight() - cell.getCompoundPaddingTop() - cell.getCompoundPaddingBottom() + 1f);
            if (index % 7 == 0) rowTops.add(cell.getTop());
            else assertEquals(scene + "：每周七天必须在同一行", calendar.getChildAt(index - index % 7).getTop(), cell.getTop());
        }
        assertEquals(scene + "：六周日期必须分成六个可见行", 6, rowTops.size());
    }
    private static void assertVisibleTextFits(ViewGroup panel, View view, String scene) {
        // 跳过未显示的错误提示和另一种编辑区；检查真实子项，不自行创建替代选项。
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Spinner) {
            View selected = ((Spinner) view).getSelectedView();
            assertNotNull(scene + "：" + viewName(view) + "必须显示实际选中项", selected);
            assertEquals(scene + "：" + viewName(view) + "选中项必须可见", View.VISIBLE, selected.getVisibility());
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.length() > 0) {
                assertContained(panel, text, scene);
                String target = scene + "：" + viewName(text) + "文字“" + text.getText() + "”";
                Layout layout = text.getLayout();
                assertNotNull(target + "必须完成文字布局", layout);
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                int height = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                assertTrue(target + "必须留有文字显示空间", width > 0 && height > 0);
                android.graphics.Paint.FontMetrics metrics = text.getPaint().getFontMetrics();
                assertTrue(target + "字高不能超过内容区域", metrics.descent - metrics.ascent <= height + 1f);
                assertTrue(target + "文字布局不能超过内容高度：" + layout.getHeight() + " / " + height,
                        layout.getHeight() <= height + 1);
                assertTrue(target + "必须至少显示一行", layout.getLineCount() > 0);
                assertEquals(target + "全部字符必须出现在可见行内", text.length(),
                        layout.getLineEnd(layout.getLineCount() - 1));
                for (int line = 0; line < layout.getLineCount(); line++) {
                    assertEquals(target + "第 " + line + " 行不得省略", 0, layout.getEllipsisCount(line));
                    assertTrue(target + "第 " + line + " 行不得超出内容宽度",
                            layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= width + 1f);
                    assertTrue(target + "第 " + line + " 行必须完整留在内容高度内",
                            layout.getLineTop(line) >= 0 && layout.getLineBottom(line) <= height + 1);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                assertVisibleTextFits(panel, group.getChildAt(index), scene);
        }
    }
    private static void assertContained(ViewGroup panel, View view, String scene) {
        assertNotNull(scene, view); assertEquals(scene, View.VISIBLE, view.getVisibility());
        String target = viewName(view);
        assertTrue(scene + "：" + target + "须有宽度", view.getWidth() > 0); assertTrue(scene + "：" + target + "须有高度", view.getHeight() > 0);
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        ViewParent parent = view.getParent();
        View child = view;
        while (parent instanceof View) {
            bounds.offset(child.getLeft(), child.getTop());
            View ancestor = (View) parent;
            String geometry = scene + "：" + target + "在" + viewName(ancestor) + "内的矩形=" + bounds.toShortString()
                    + "，祖先可用矩形=[0,0," + ancestor.getWidth() + "," + ancestor.getHeight() + "]";
            assertTrue(geometry, bounds.left >= 0 && bounds.top >= 0 && bounds.right <= ancestor.getWidth() && bounds.bottom <= ancestor.getHeight());
            if (ancestor == panel) return;
            child = ancestor; parent = ancestor.getParent();
        }
        fail(scene + "：控件必须属于弹窗面板");
    }
    private static String viewName(View view) {
        if (view.getId() == View.NO_ID) return view.getClass().getSimpleName();
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException generatedId) { return view.getClass().getSimpleName(); }
    }
    private static void assertNoPageScroll(View view, String scene) {
        assertFalse(scene + "：日期窗口不能依赖整页滚动", view instanceof ScrollView);
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) assertNoPageScroll(((ViewGroup) view).getChildAt(index), scene);
    }
    private void finishPages() {
        for (Stage stage : Stage.values()) for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
            if (page.getPackageName().equals(app.getPackageName()) && !page.isFinishing()) page.finish();
    }
    private void capture(String scene) throws Exception {
        capture(scene, "dateCapture", "dateCapturePrefix", "qa-record-date");
    }
    private void captureDateEntry(String scene) throws Exception {
        capture(scene, "dateEntryCapture", "dateEntryCapturePrefix", "qa-record-date-entry");
    }
    private void capture(String scene, String captureKey, String prefixKey, String directoryName) throws Exception {
        if (!"true".equalsIgnoreCase(InstrumentationRegistry.getArguments().getString(captureKey, "false"))) return;
        SystemClock.sleep(180);
        File root = app.getExternalFilesDir(null); assertNotNull(root);
        File directory = new File(root, directoryName); assertTrue(directory.isDirectory() || directory.mkdirs());
        String prefix = InstrumentationRegistry.getArguments().getString(prefixKey, "device").replaceAll("[^A-Za-z0-9_-]", "_");
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-" + scene + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { image.recycle(); }
    }
    private static Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) copy.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return copy;
    }
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs, Map<String, Object> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            else fail("无法恢复偏好类型：" + key);
        }
        assertTrue(editor.commit());
    }
}
