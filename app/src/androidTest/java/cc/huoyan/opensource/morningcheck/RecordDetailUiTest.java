package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.ContentValues;
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
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ListView;
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
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.data.RecordPolicy;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 历史详情的界面回归；夹具只能显式运行于 x86 模拟器，现场设备仅提供只读入口。 */
@RunWith(AndroidJUnit4.class)
public final class RecordDetailUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final String prefix = "record-detail-ui-" + UUID.randomUUID();
    private final List<String> recordIds = new ArrayList<>(), photoNames = new ArrayList<>();
    private final AtomicInteger cloudRequests = new AtomicInteger();
    private MorningApplication app;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private AppStore originalStore, isolatedStore;
    private MockWebServer endpoint;
    private String databaseName, readOnlyRecordId, readOnlyRecordSnapshot;
    private Map<String, Object> readOnlyPreferences;
    private ActivityScenario<RecordDetailActivity> scenario;

    @Before public void obtainApplicationWithoutChangingDevice() {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
    }

    /** 测试只能清理自己创建的 UUID 文件；配置、业务库在任何失败路径都恢复。 */
    @After public void restoreFixtureOrCheckReadOnlyState() throws Exception {
        if (scenario != null) scenario.close();
        if (readOnlyRecordId != null) {
            assertEquals("只读验收不能改写晨检记录", readOnlyRecordSnapshot,
                    onIo(() -> app.store.record(readOnlyRecordId).toString()));
            assertEquals("只读验收不能修改设备配置", readOnlyPreferences, snapshot(preferences));
            return;
        }
        if (originalStore == null) return;
        try {
            onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            app.networkIo.submit(() -> null).get(10, TimeUnit.SECONDS);
            onIo(() -> {
                for (String id : recordIds) {
                    assertTrue(id.startsWith(prefix));
                    isolatedStore.getWritableDatabase().delete("records", "id=?", new String[]{id});
                }
                for (String name : photoNames) {
                    assertTrue(name.startsWith(prefix));
                    File photo = isolatedStore.photo(name);
                    assertTrue("自己的测试照片应能清理", !photo.exists() || photo.delete());
                }
                return null;
            });
            instrumentation.waitForIdleSync();
            assertEquals("浏览历史详情不能触发业务云请求", 0, cloudRequests.get());
        } finally {
            try {
                onMain(() -> { app.store = originalStore; return null; });
                if (isolatedStore != null) isolatedStore.close();
                if (databaseName != null) assertTrue(app.deleteDatabase(databaseName));
                if (originalPreferences != null) restore(preferences, originalPreferences);
            } finally {
                if (endpoint != null) endpoint.shutdown();
            }
        }
    }

    @Test public void readsRequestedRecordAndKeepsReadOnlySections() throws Exception {
        isolateFixtures(); String id = newRecordId("details");
        String finding = longText("完整检查说明", "检查说明末尾标记");
        save(record(id).put("staff_name", "按编号加载的历史员工").put("findings", new JSONArray().put(finding)));
        launch(id);
        scenario.onActivity(activity -> {
            assertEquals(id, activity.currentRecord.optString("id"));
            assertTrue(text(activity, R.id.record_detail_name).contains("按编号加载的历史员工"));
            assertReadOnlySections(activity);
            ListView list = activity.findViewById(R.id.record_detail_fields);
            boolean found = false;
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                View row = list.getAdapter().getView(i, null, list);
                found |= ((TextView) row.findViewById(R.id.record_detail_field_value)).getText().toString().equals(finding);
            }
            assertTrue("长检查说明应完整保留在数据列表", found);
        });
        scenario.recreate(); awaitLoaded(id);
        scenario.onActivity(activity -> { assertTrue(activity.findViewById(R.id.record_detail_photos).isShown()); assertReadOnlySections(activity); });
    }

    @Test public void fivePhotosUseTwoColumnsAndShowMissingFileWithoutDroppingIt() throws Exception {
        isolateFixtures(); String id = newRecordId("five"); JSONArray captured = new JSONArray();
        for (int i = 0; i < 5; i++) captured.put(new JSONObject().put("file", i == 4 ? prefix + "-missing.jpg" : createPhoto(id, "sample-" + i, i == 1 ? Color.BLUE : Color.RED)).put("label", "采集 " + (i + 1)));
        save(record(id).put("captured_photos", captured)); launch(id);
        await(() -> visibleColor(0, Color.RED), "照片网格应读取真实测试照片");
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.record_detail_photos);
            assertTwoColumnPhotoGrid(list);
            assertPhotoHint(activity);
            assertEquals("不能限制为三张照片", 5, list.getAdapter().getItemCount());
            View row = visibleRow(list, 0); ImageView image = row.findViewById(R.id.record_photo_thumbnail);
            assertTrue("缩略图应有可见高度", image.getHeight() > 0);
            Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
            assertTrue("缩略图应按需采样", Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 480);
            list.smoothScrollToPosition(4);
        });
        await(() -> onMain(() -> {
            RecordDetailActivity page = currentDetail();
            if (page == null) return false;
            View row = visibleRow(page.findViewById(R.id.record_detail_photos), 4);
            if (row == null) return false;
            TextView empty = row.findViewById(R.id.record_photo_empty); ImageView image = row.findViewById(R.id.record_photo_thumbnail);
            return empty.isShown() && empty.getText().toString().contains("缺失") && image.getDrawable() == null && !image.hasOnClickListeners();
        }), "缺失文件应显示占位且不能打开查看器", () -> visiblePhotoState(4));
        scenario.recreate(); awaitLoaded(id);
        scenario.onActivity(activity -> { assertTrue(activity.findViewById(R.id.record_detail_photos).isShown()); assertEquals(5, ((RecyclerView) activity.findViewById(R.id.record_detail_photos)).getAdapter().getItemCount()); });
    }

    @Test public void verticalGridCanOpenLastOfTwelvePhotosAndReturnToItsPosition() throws Exception {
        isolateFixtures(); String id = newRecordId("many"); JSONArray captured = new JSONArray();
        for (int i = 0; i < 12; i++) captured.put(new JSONObject().put("file", createPhoto(id, "many-" + i, Color.GREEN)).put("label", "照片 " + (i + 1)));
        save(record(id).put("captured_photos", captured)); launch(id);
        await(() -> visibleColor(0, Color.GREEN), "照片网格应完成加载");
        scenario.onActivity(activity -> {
            RecyclerView list = activity.findViewById(R.id.record_detail_photos);
            assertTwoColumnPhotoGrid(list);
            assertEquals(12, list.getAdapter().getItemCount());
            assertTrue("多张采集照片应支持向下滚动", list.canScrollVertically(1));
            assertFalse("两列照片不能横向滑动", list.canScrollHorizontally(1) || list.canScrollHorizontally(-1));
            list.smoothScrollToPosition(11);
        });
        await(() -> visibleColor(11, Color.GREEN), "照片列表应能滚动到第十二张，不能仅显示前三张");
        await(() -> onMain(() -> {
            RecordDetailActivity page = currentDetail();
            return page != null && page.<RecyclerView>findViewById(R.id.record_detail_photos).getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
        }), "打开末张照片前应完成滚动");
        AtomicInteger firstPosition = new AtomicInteger(), firstTop = new AtomicInteger();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(RecordPhotoActivity.class.getName(), null, false);
        RecordPhotoActivity opened;
        try {
            scenario.onActivity(activity -> {
                RecyclerView list = activity.findViewById(R.id.record_detail_photos);
                GridLayoutManager layout = (GridLayoutManager) list.getLayoutManager();
                firstPosition.set(layout.findFirstVisibleItemPosition());
                firstTop.set(layout.getDecoratedTop(layout.findViewByPosition(firstPosition.get())) - list.getPaddingTop());
                visibleRow(list, 11).findViewById(R.id.record_photo_thumbnail).performClick();
            });
            Activity routed = instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            assertTrue("点击末张应打开图片查看器", routed instanceof RecordPhotoActivity);
            opened = (RecordPhotoActivity) routed;
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        awaitPhoto(false, 12, 12, Color.GREEN);
        onMain(() -> {
            assertEquals(11, opened.getIntent().getIntExtra(RecordPhotoActivity.EXTRA_PHOTO_INDEX, -1));
            assertEquals("12/12", text(opened, R.id.photo_position).replace(" ", ""));
            opened.onBackPressed();
            return null;
        });
        await(() -> photoPositionMatches(firstPosition.get(), firstTop.get()), "关闭查看器应保留照片网格的滚动位置");
        scenario.recreate(); awaitLoaded(id);
        await(() -> photoPositionMatches(firstPosition.get(), firstTop.get()), "详情重建应保留照片网格的滚动位置");
    }

    @Test public void selectedPhotoRoutesToViewerAndNavigationAndRecreationKeepItsIndex() throws Exception {
        isolateFixtures(); String id = newRecordId("viewer"); JSONArray captured = new JSONArray();
        int[] colors = {Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.MAGENTA};
        for (int i = 0; i < colors.length; i++) captured.put(new JSONObject().put("file", createPhoto(id, "viewer-" + i, colors[i])).put("label", "照片 " + (i + 1)));
        save(record(id).put("captured_photos", captured)); launch(id);
        await(() -> visibleColor(1, Color.BLUE), "第二张缩略图应显示蓝色照片");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(RecordPhotoActivity.class.getName(), null, false);
        RecordPhotoActivity opened;
        try { scenario.onActivity(activity -> visibleRow(activity.findViewById(R.id.record_detail_photos), 1).findViewById(R.id.record_photo_thumbnail).performClick()); Activity routed = instrumentation.waitForMonitorWithTimeout(monitor, 10000); assertTrue(routed instanceof RecordPhotoActivity); opened = (RecordPhotoActivity) routed; }
        finally { instrumentation.removeMonitor(monitor); }
        awaitPhotoOrientation();
        awaitPhoto(false, 2, 5, Color.BLUE);
        onMain(() -> { assertEquals(1, opened.getIntent().getIntExtra(RecordPhotoActivity.EXTRA_PHOTO_INDEX, -1)); assertEquals("2/5", text(opened, R.id.photo_position).replace(" ", "")); return null; });
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 3, 5, Color.GREEN);
        onMain(() -> { opened.recreate(); return null; });
        await(() -> onMain(() -> { RecordPhotoActivity page = resumedPhoto(); return page != null && page != opened && matchesPhoto(false, 3, 5, Color.GREEN); }), "照片页重建应保持当前选择");
        onMain(() -> { RecordPhotoActivity page = resumedPhoto(); page.findViewById(R.id.photo_previous).performClick(); return null; });
        awaitPhoto(false, 2, 5, Color.BLUE);
        onMain(() -> { resumedPhoto().onBackPressed(); return null; });
        await(() -> onMain(() -> currentDetail() != null && currentDetail().findViewById(R.id.record_detail_photos).isShown()), "关闭查看器应返回原详情照片页签");
    }

    @Test public void photoSwipesRespectBoundsAndCanContinuePastMissingFiles() throws Exception {
        isolateFixtures();
        String id = newRecordId("swipe-missing");
        JSONArray captured = new JSONArray()
                .put(new JSONObject().put("file", createPhoto(id, "first", Color.RED)).put("label", "首张"))
                .put(new JSONObject().put("file", prefix + "-missing.jpg").put("label", "缺失照片"))
                .put(new JSONObject().put("file", createPhoto(id, "last", Color.GREEN)).put("label", "末张"));
        save(record(id).put("captured_photos", captured));
        launch(id);
        await(() -> visibleColor(0, Color.RED), "首张缩略图应读取完成");
        openVisiblePhoto(0);
        awaitPhoto(false, 1, 3, Color.RED);
        gesturePhoto(false, .2f, .5f, .8f, .5f, MotionEvent.ACTION_UP);
        assertPhoto(false, 1, 3, Color.RED);
        gesturePhoto(false, .5f, .25f, .5f, .75f, MotionEvent.ACTION_UP);
        assertPhoto(false, 1, 3, Color.RED);
        gesturePhoto(false, .75f, .5f, .65f, .5f, MotionEvent.ACTION_CANCEL);
        awaitPhoto(false, 1, 3, Color.RED);
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        awaitMissingPhoto(2, 3);
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 3, 3, Color.GREEN);
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        assertPhoto(false, 3, 3, Color.GREEN);
        gesturePhoto(false, .2f, .5f, .8f, .5f, MotionEvent.ACTION_UP);
        awaitMissingPhoto(2, 3);
        gesturePhoto(false, .2f, .5f, .8f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 1, 3, Color.RED);
        onMain(() -> {
            assertFalse(resumedPhoto().findViewById(R.id.photo_previous).isEnabled());
            resumedPhoto().findViewById(R.id.photo_next).performClick();
            return null;
        });
        awaitMissingPhoto(2, 3);
    }

    @Test public void photoPagerFollowsFingerBeforeReleaseWithoutOpeningLoadingDialog() throws Exception {
        isolateFixtures();
        String id = newRecordId("follow-finger");
        save(record(id).put("captured_photos", new JSONArray()
                .put(new JSONObject().put("file", createPhoto(id, "red", Color.RED)).put("label", "红色照片"))
                .put(new JSONObject().put("file", createPhoto(id, "blue", Color.BLUE)).put("label", "蓝色照片"))));
        launch(id);
        await(() -> visibleColor(0, Color.RED), "首张缩略图应读取完成");
        openVisiblePhoto(0);
        awaitPhoto(false, 1, 2, Color.RED);
        Rect bounds = photoBounds(false);
        long down = SystemClock.uptimeMillis();
        float startX = bounds.left + bounds.width() * .8f, y = bounds.exactCenterY();
        sendPointer(down, MotionEvent.ACTION_DOWN, startX, y);
        try {
            for (int i = 1; i <= 10; i++) {
                SystemClock.sleep(16);
                sendPointer(down, MotionEvent.ACTION_MOVE, startX - bounds.width() * .035f * i, y);
            }
            instrumentation.waitForIdleSync();
            onMain(() -> {
                RecyclerView pager = resumedPhoto().findViewById(R.id.photo_pager);
                View first = visibleRow(pager, 0), second = visibleRow(pager, 1);
                assertEquals("未抬手时照片应跟随手指滚动", RecyclerView.SCROLL_STATE_DRAGGING, pager.getScrollState());
                assertNotNull("拖动时原照片应仍部分可见", first);
                assertNotNull("拖动时下一张照片应已进入窗口", second);
                assertTrue("原照片应跟随手指向左移动", first.getLeft() < -pager.getWidth() * .2f);
                assertTrue("下一张应部分进入照片区域", second.getLeft() > 0 && second.getLeft() < pager.getWidth() * .85f);
                assertTrue(hasColor(first.findViewById(R.id.photo_image), Color.RED));
                assertTrue(hasColor(second.findViewById(R.id.photo_image), Color.BLUE));
                assertNoPhotoLoadingDialog();
                return null;
            });
            sendPointer(down, MotionEvent.ACTION_MOVE, bounds.left + bounds.width() * .2f, y);
        } finally {
            sendPointer(down, MotionEvent.ACTION_UP, bounds.left + bounds.width() * .2f, y);
        }
        awaitPhoto(false, 2, 2, Color.BLUE);
        onMain(() -> { assertNoPhotoLoadingDialog(); return null; });
    }

    @Test public void allTwelvePhotosArePreloadedAndPagerRestoresSelectionWithoutPerPhotoIo() throws Exception {
        isolateFixtures();
        String id = newRecordId("preloaded");
        JSONArray captured = new JSONArray();
        int[] colors = {Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.MAGENTA, Color.CYAN,
                Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK, Color.rgb(180, 70, 40)};
        for (int i = 0; i < colors.length; i++) {
            captured.put(new JSONObject().put("file", createPhoto(id, "preloaded-" + i, colors[i]))
                    .put("label", "预加载照片 " + (i + 1)));
        }
        save(record(id).put("captured_photos", captured));
        launch(id);
        await(() -> visibleColor(0, Color.RED), "首张缩略图应读取完成");
        openVisiblePhoto(0);
        awaitPhoto(false, 1, 12, colors[0]);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> blocked = app.io.submit(() -> {
            entered.countDown();
            try {
                assertTrue("测试必须及时释放存储线程", release.await(60, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
        try {
            assertTrue("预加载后应能阻塞共享存储线程进行导航验证", entered.await(10, TimeUnit.SECONDS));
            for (int i = 1; i < colors.length; i++) {
                gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
                awaitPhoto(false, i + 1, colors.length, colors[i]);
                onMain(() -> { assertNoPhotoLoadingDialog(); return null; });
            }
        } finally {
            release.countDown();
            blocked.get(10, TimeUnit.SECONDS);
        }
        RecordPhotoActivity previous = onMain(RecordDetailUiTest::resumedPhoto);
        onMain(() -> { previous.recreate(); return null; });
        await(() -> onMain(() -> resumedPhoto() != null && resumedPhoto() != previous), "预加载照片页应完成重建");
        awaitPhoto(false, 12, 12, colors[11]);
        gesturePhoto(false, .2f, .5f, .8f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 11, 12, colors[10]);
        onMain(() -> { resumedPhoto().onBackPressed(); return null; });
        await(() -> onMain(() -> currentDetail() != null && currentDetail().findViewById(R.id.record_detail_photos).isShown()),
                "返回详情时应保留照片页签");
    }

    @Test public void fullscreenPhotoCanSwipeZoomDragAndRestoreCurrentSelection() throws Exception {
        isolateFixtures();
        String id = newRecordId("fullscreen");
        JSONArray captured = new JSONArray();
        int[] colors = {Color.RED, Color.BLUE, Color.GREEN};
        for (int i = 0; i < colors.length; i++) {
            captured.put(new JSONObject().put("file", createPhoto(id, "fullscreen-" + i, colors[i]))
                    .put("label", "全屏照片 " + (i + 1)));
        }
        save(record(id).put("captured_photos", captured));
        launch(id);
        await(() -> visibleColor(0, Color.RED), "首张缩略图应读取完成");
        openVisiblePhoto(0);
        awaitPhoto(false, 1, 3, Color.RED);
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 2, 3, Color.BLUE);
        gesturePhoto(false, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(true, 2, 3, Color.BLUE);
        capturePhotoUi("fullscreen-fit");
        float[] original = photoMatrix();
        gesturePhoto(true, .7f, .4f, .5f, .5f, MotionEvent.ACTION_UP);
        assertPhoto(true, 2, 3, Color.BLUE);
        float[] repositioned = photoMatrix();
        assertEquals("原尺寸拖动不能改变缩放比例", original[Matrix.MSCALE_X], repositioned[Matrix.MSCALE_X], .01f);
        assertTrue("原尺寸也应允许将图片任意部位拖到中央",
                Math.abs(repositioned[Matrix.MTRANS_X] - original[Matrix.MTRANS_X]) > 1f);
        Rect fullscreenBounds = photoBounds(true);
        float sourceAtCenter = (fullscreenBounds.width() / 2f - repositioned[Matrix.MTRANS_X]) / repositioned[Matrix.MSCALE_X];
        pinchFullscreenPhoto();
        await(() -> photoMatrix()[Matrix.MSCALE_X] > original[Matrix.MSCALE_X] * 1.5f,
                "双指张开应放大全屏图片");
        capturePhotoUi("fullscreen-zoomed");
        float[] zoomed = photoMatrix();
        assertEquals("拖至中央的图片位置在双指放大后应继续位于手势中心",
                fullscreenBounds.width() / 2f,
                sourceAtCenter * zoomed[Matrix.MSCALE_X] + zoomed[Matrix.MTRANS_X], 3f);
        gesturePhoto(true, .7f, .5f, .3f, .5f, MotionEvent.ACTION_UP);
        assertPhoto(true, 2, 3, Color.BLUE);
        float[] dragged = photoMatrix();
        assertTrue("放大后的单指拖动应平移图片，不能切换照片",
                Math.abs(dragged[Matrix.MTRANS_X] - zoomed[Matrix.MTRANS_X]) > 1f);
        assertEquals("拖动不能改变缩放比例", zoomed[Matrix.MSCALE_X], dragged[Matrix.MSCALE_X], .05f);
        gesturePhoto(true, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        SystemClock.sleep(80);
        gesturePhoto(true, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        await(() -> Math.abs(photoMatrix()[Matrix.MSCALE_X] - original[Matrix.MSCALE_X]) < .05f,
                "双击应将放大图片恢复到适配尺寸");
        assertEquals("双击恢复应重置图片平移", original[Matrix.MTRANS_X], photoMatrix()[Matrix.MTRANS_X], 1f);
        SystemClock.sleep(350);
        gesturePhoto(true, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        await(() -> onMain(() -> fullscreenDialog() == null || !fullscreenDialog().isShowing()),
                "单击全屏图片应关闭全屏查看器");
        awaitPhoto(false, 2, 3, Color.BLUE);
        gesturePhoto(false, .8f, .5f, .2f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(false, 3, 3, Color.GREEN);
        gesturePhoto(false, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        awaitPhoto(true, 3, 3, Color.GREEN);
        RecordPhotoActivity previous = onMain(RecordDetailUiTest::resumedPhoto);
        onMain(() -> { previous.recreate(); return null; });
        await(() -> onMain(() -> resumedPhoto() != null && resumedPhoto() != previous),
                "图片页重建应完成");
        awaitPhoto(true, 3, 3, Color.GREEN);
        gesturePhoto(true, .5f, .5f, .5f, .5f, MotionEvent.ACTION_UP);
        await(() -> onMain(() -> fullscreenDialog() == null || !fullscreenDialog().isShowing()),
                "单击全屏图片应关闭全屏查看器");
        awaitPhoto(false, 3, 3, Color.GREEN);
    }

    @Test public void missingRecordHasVisibleEmptyState() throws Exception {
        isolateFixtures(); String id = newRecordId("missing");
        scenario = ActivityScenario.launch(new Intent(app, RecordDetailActivity.class).putExtra(RecordDetailActivity.EXTRA_RECORD_ID, id));
        awaitExpectedOrientation();
        await(() -> onMain(() -> currentDetail() != null
                && currentDetail().findViewById(R.id.record_detail_empty).isShown()
                && "暂无记录，点击重新读取".equals(text(currentDetail(), R.id.record_detail_empty))), "缺失记录应显示明确空态");
        scenario.onActivity(activity -> {
            assertNull(activity.currentRecord);
            assertTrue(activity.findViewById(R.id.record_detail_body).isShown());
            assertEquals(View.GONE, activity.findViewById(R.id.record_detail_tabs).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.record_detail_fields).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.record_detail_photos).getVisibility());
        });
        save(record(id));
        scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.record_detail_empty).performClick()));
        awaitLoaded(id);
        scenario.onActivity(activity -> {
            assertEquals(View.GONE, activity.findViewById(R.id.record_detail_empty).getVisibility());
            assertReadOnlySections(activity);
        });
    }

    private void isolateFixtures() throws Exception {
        assumeTrue("夹具需显式 enableRecordDetailFixtures=true",
                "true".equals(InstrumentationRegistry.getArguments().getString("enableRecordDetailFixtures")));
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
        String device = Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
        assumeTrue("夹具只允许已识别的 x86 Android 模拟器，禁止写入现场机器",
                x86 && (hardware.contains("ranchu") || hardware.contains("goldfish") || device.startsWith("generic_x86")));
        assumeTrue("不能中断现有门店绑定", onMain(() -> !app.shopLogin().getState().isRunning()));
        onMain(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
        drainIo();
        originalPreferences = snapshot(preferences);
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
                return new MockResponse().setResponseCode(503).setBody("仅测试历史记录界面，SDK 保持未就绪");
            }
        });
        // 同机回环不命中 SDK 壳的 localhost 保活规则，避免启动任何现场服务。
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "LOCAL-UI-TEST")
                .putBoolean("voice", false).commit());
    }

    private String newRecordId(String suffix) {
        String id = prefix + "-" + suffix;
        recordIds.add(id);
        return id;
    }

    private JSONObject record(String id) throws Exception {
        return new JSONObject().put("id", id).put("staff_id", 0).put("staff_name", "历史详情测试员工")
                .put("department", "界面测试").put("created_at", System.currentTimeMillis()).put("status", 0)
                .put("temperature", "36.5").put("temperature_source", "测试夹具").put("health_expiry", "2099-12-31")
                .put("abnormal_face", 1).put("abnormal_hand", 1).put("abnormal_temperature", 0)
                .put("abnormal_health_cert_exp", 0).put("findings", new JSONArray().put("仅验证界面，不代表真实晨检"))
                .put("upload", "结果待核实").put("upload_error", "历史未知回执，浏览时不能重试");
    }

    private void save(JSONObject record) throws Exception {
        onIo(() -> { isolatedStore.saveRecord(record); return null; });
    }

    private String createPhoto(String recordId, String stage, int color) throws Exception {
        String name = onIo(() -> {
            Bitmap bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            try { return isolatedStore.savePhoto(recordId, stage, bitmap); }
            finally { bitmap.recycle(); }
        });
        photoNames.add(name);
        return name;
    }

    private void launch(String id) {
        scenario = ActivityScenario.launch(new Intent(app, RecordDetailActivity.class)
                .putExtra(RecordDetailActivity.EXTRA_RECORD_ID, id));
        awaitLoaded(id);
    }

    private void awaitLoaded(String id) {
        awaitExpectedOrientation();
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> ready.set(activity.currentRecord != null
                    && id.equals(activity.currentRecord.optString("id"))
                    && activity.findViewById(R.id.record_detail_photos).getWidth() > 0
                    && activity.findViewById(R.id.record_detail_photos).getHeight() > 0));
            return ready.get();
        }, "历史详情应完成按编号读取和窗口布局");
    }

    private void awaitExpectedOrientation() {
        String expected = InstrumentationRegistry.getArguments().getString("expectedRecordsLayoutOrientation", "");
        if (expected.isEmpty()) return;
        assertTrue("记录验收方向只允许 portrait 或 landscape", "portrait".equals(expected) || "landscape".equals(expected));
        boolean portrait = "portrait".equals(expected);
        await(() -> onMain(() -> currentDetail() != null), "指定方向前记录详情应恢复");
        onMain(() -> { currentDetail().setRequestedOrientation(portrait
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
        await(() -> onMain(() -> {
            RecordDetailActivity page = currentDetail();
            if (page == null) return false;
            View root = page.findViewById(android.R.id.content);
            return root.getWidth() > 0 && root.getHeight() > 0 && !root.isLayoutRequested()
                    && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && page.getResources().getConfiguration().orientation == (portrait
                            ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "实际记录详情窗口应符合验收方向：" + expected);
    }

    private boolean visibleColor(int position, int color) { return onMain(() -> { RecordDetailActivity page = currentDetail(); if (page == null) return false; RecyclerView list = page.findViewById(R.id.record_detail_photos); View row = visibleRow(list, position); return row != null && hasColor(row.findViewById(R.id.record_photo_thumbnail), color); }); }
    private String visiblePhotoState(int position) {
        return onMain(() -> {
            RecordDetailActivity page = currentDetail();
            if (page == null) return "详情页未处于前台";
            RecyclerView list = page.findViewById(R.id.record_detail_photos);
            GridLayoutManager layout = list.getLayoutManager() instanceof GridLayoutManager
                    ? (GridLayoutManager) list.getLayoutManager() : null;
            String state = "目标照片=" + position + "，可见范围=" + (layout == null ? "尚未布局"
                    : layout.findFirstVisibleItemPosition() + ".." + layout.findLastVisibleItemPosition())
                    + "，子项数=" + list.getChildCount() + "，列表尺寸=" + list.getWidth() + "x" + list.getHeight() + "，列表显示=" + list.isShown();
            View row = visibleRow(list, position);
            if (row == null) return state + "，目标行尚未进入窗口";
            TextView empty = row.findViewById(R.id.record_photo_empty);
            ImageView image = row.findViewById(R.id.record_photo_thumbnail);
            android.graphics.drawable.Drawable drawable = image.getDrawable();
            Rect visible = new Rect();
            state += "，行已挂载=" + row.isAttachedToWindow() + "，占位显示=" + empty.isShown()
                    + "，占位在屏幕内=" + empty.getGlobalVisibleRect(visible) + "，占位区域=" + visible
                    + "，占位文案=" + empty.getText() + "，图片=" + (drawable == null ? "null" : drawable.getClass().getSimpleName())
                    + "，有点击监听=" + image.hasOnClickListeners();
            if (drawable instanceof BitmapDrawable) state += "，位图=" + ((BitmapDrawable) drawable).getBitmap();
            return state;
        });
    }
    private static View visibleRow(RecyclerView list, int position) {
        RecyclerView.LayoutManager layout = list.getLayoutManager();
        if (layout == null) return null;
        View row = layout.findViewByPosition(position);
        Rect visible = new Rect();
        return row != null && row.getLocalVisibleRect(visible) && !visible.isEmpty() ? row : null;
    }

    private static void assertTwoColumnPhotoGrid(RecyclerView list) {
        assertTrue("采集照片应采用网格布局", list.getLayoutManager() instanceof GridLayoutManager);
        GridLayoutManager layout = (GridLayoutManager) list.getLayoutManager();
        assertEquals("每行应展示两张采集照片", 2, layout.getSpanCount());
        assertEquals("采集照片应竖向排列", RecyclerView.VERTICAL, layout.getOrientation());
        View first = visibleRow(list, 0), second = visibleRow(list, 1);
        assertNotNull("首行左侧照片应可见", first);
        assertNotNull("首行右侧照片应可见", second);
        Rect left = new Rect(), right = new Rect();
        layout.getDecoratedBoundsWithMargins(first, left);
        layout.getDecoratedBoundsWithMargins(second, right);
        assertEquals("同一行的照片顶部应对齐", left.top, right.top);
        assertTrue("两列照片应等宽", Math.abs(left.width() - right.width()) <= 1);
        assertEquals("首列应从照片区域左边缘开始", list.getPaddingLeft(), left.left);
        assertEquals("两列应连续占满照片区域", left.right, right.left);
        assertEquals("第二列应覆盖到照片区域右边缘", list.getWidth() - list.getPaddingRight(), right.right);
    }

    private static void assertPhotoHint(Activity page) {
        TextView hint = page.findViewById(R.id.record_photo_hint);
        assertNotNull("采集照片应有点击查看说明", hint);
        assertTrue("点击查看说明应可见", hint.isShown());
        assertEquals("点击图片查看详情", hint.getText().toString());
    }

    private void openVisiblePhoto(int position) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(RecordPhotoActivity.class.getName(), null, false);
        try {
            scenario.onActivity(activity -> visibleRow(activity.findViewById(R.id.record_detail_photos), position)
                    .findViewById(R.id.record_photo_thumbnail).performClick());
            assertTrue("点击采集照片应进入照片页",
                    instrumentation.waitForMonitorWithTimeout(monitor, 10000) instanceof RecordPhotoActivity);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        awaitPhotoOrientation();
    }

    private void awaitPhotoOrientation() {
        String expected = InstrumentationRegistry.getArguments().getString("expectedRecordsLayoutOrientation", "");
        if (expected.isEmpty()) return;
        boolean portrait = "portrait".equals(expected);
        onMain(() -> {
            resumedPhoto().setRequestedOrientation(portrait
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            return null;
        });
        await(() -> onMain(() -> {
            ImageView image = photoView(false);
            RecordPhotoActivity page = resumedPhoto();
            View root = page == null ? null : page.getWindow().getDecorView();
            return image != null && root != null && !root.isLayoutRequested() && !image.isLayoutRequested()
                    && image.getWidth() > 0 && image.getHeight() > 0
                    && (portrait ? root.getHeight() > root.getWidth() : root.getWidth() > root.getHeight())
                    && image.getResources().getConfiguration().orientation == (portrait
                    ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE);
        }), "检查照片页应使用指定方向：" + expected);
        // 系统旋转窗口的交接晚于 XML 重排，真实触摸需等旧窗口和读取遮罩退场。
        SystemClock.sleep(700);
        instrumentation.waitForIdleSync();
    }

    private void awaitPhoto(boolean fullscreen, int position, int count, int color) {
        await(() -> onMain(() -> matchesPhoto(fullscreen, position, count, color)),
                (fullscreen ? "全屏" : "照片页") + "应显示第 " + position + "/" + count + " 张图片", () -> onMain(() -> {
                    ImageView image = photoView(fullscreen);
                    RecordPhotoActivity page = resumedPhoto();
                    RecyclerView pager = page == null ? null : page.findViewById(R.id.photo_pager);
                    View target = pager == null || pager.getLayoutManager() == null ? null
                            : pager.getLayoutManager().findViewByPosition(position - 1);
                    return "窗口=" + fullscreenDialog() + "，图片=" + image
                            + "，焦点=" + (image != null && image.hasWindowFocus())
                            + "，页码=" + (page == null ? "无页面" : text(page, R.id.photo_position))
                            + "，滚动状态=" + (pager == null ? "无分页列表" : pager.getScrollState())
                            + "，目标页偏移=" + (target == null ? "目标未挂载" : target.getLeft() - pager.getPaddingLeft())
                            + "，分页宽度=" + (pager == null ? 0 : pager.getWidth());
                }));
    }

    private void assertPhoto(boolean fullscreen, int position, int count, int color) {
        awaitPhoto(fullscreen, position, count, color);
    }

    private static boolean matchesPhoto(boolean fullscreen, int position, int count, int color) {
        RecordPhotoActivity page = resumedPhoto();
        if (page == null) return false;
        ImageView image = photoView(fullscreen);
        TextView indicator = fullscreen ? fullscreenDialog() == null ? null
                : fullscreenDialog().findViewById(R.id.photo_fullscreen_position) : page.findViewById(R.id.photo_position);
        RecyclerView pager = page.findViewById(R.id.photo_pager);
        View row = fullscreen || pager == null ? null : visibleRow(pager, position - 1);
        boolean settled = fullscreen || pager != null && pager.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                && row != null && Math.abs(row.getLeft() - pager.getPaddingLeft()) <= 1;
        return settled && image != null && image.hasWindowFocus() && hasColor(image, color) && indicator != null
                && (position + "/" + count).equals(indicator.getText().toString().replace(" ", ""));
    }

    private void awaitMissingPhoto(int position, int count) {
        await(() -> onMain(() -> {
            RecordPhotoActivity page = resumedPhoto();
            if (page == null) return false;
            ImageView image = photoView(false);
            RecyclerView pager = page.findViewById(R.id.photo_pager);
            View row = visibleRow(pager, position - 1);
            return image != null && image.hasWindowFocus() && image.getDrawable() == null
                    && pager.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                    && row != null && Math.abs(row.getLeft() - pager.getPaddingLeft()) <= 1
                    && (position + "/" + count).equals(text(page, R.id.photo_position).replace(" ", ""));
        }), "缺失照片应保留位置且解除读取遮罩，允许继续滑动");
    }

    private static Dialog fullscreenDialog() {
        RecordPhotoActivity page = resumedPhoto();
        if (page == null) return null;
        try {
            Field field = RecordPhotoActivity.class.getDeclaredField("fullscreenDialog");
            field.setAccessible(true);
            return (Dialog) field.get(page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取照片页的全屏窗口", failure);
        }
    }

    private static ImageView photoView(boolean fullscreen) {
        if (fullscreen) {
            Dialog dialog = fullscreenDialog();
            return dialog == null ? null : dialog.findViewById(R.id.photo_fullscreen_image);
        }
        RecordPhotoActivity page = resumedPhoto();
        if (page == null) return null;
        RecyclerView pager = page.findViewById(R.id.photo_pager);
        if (pager == null) return null;
        String label = text(page, R.id.photo_position).replace(" ", "");
        int divider = label.indexOf('/');
        if (divider < 1) return null;
        int position;
        try {
            position = Integer.parseInt(label.substring(0, divider)) - 1;
        } catch (NumberFormatException ignored) {
            return null;
        }
        View row = pager.getLayoutManager() == null ? null : pager.getLayoutManager().findViewByPosition(position);
        return row == null ? null : row.findViewById(R.id.photo_image);
    }

    private static void assertNoPhotoLoadingDialog() {
        try {
            Field holder = RecordPhotoActivity.class.getDeclaredField("readingDialog");
            holder.setAccessible(true);
            Object loading = holder.get(resumedPhoto());
            if (loading == null) return;
            Field window = LoadingDialog.class.getDeclaredField("dialog");
            window.setAccessible(true);
            Dialog dialog = (Dialog) window.get(loading);
            assertTrue("翻页过程中不能弹出逐张读取遮罩", dialog == null || !dialog.isShowing());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法核对照片读取窗口", failure);
        }
    }

    private float[] photoMatrix() {
        return onMain(() -> {
            ImageView image = photoView(true);
            assertNotNull("全屏图片应存在", image);
            float[] values = new float[9];
            image.getImageMatrix().getValues(values);
            return values;
        });
    }

    private Rect photoBounds(boolean fullscreen) {
        await(() -> onMain(() -> {
            ImageView image = photoView(fullscreen);
            return image != null && image.hasWindowFocus() && !image.isLayoutRequested();
        }), "手势注入前图片窗口应完成布局并获得焦点");
        return onMain(() -> {
            ImageView image = photoView(fullscreen);
            assertNotNull("操作前图片区域应存在", image);
            Rect bounds = new Rect();
            assertTrue("操作前图片区域应在屏幕内", image.getGlobalVisibleRect(bounds));
            assertTrue("操作前图片区域应获得焦点", image.hasWindowFocus());
            return bounds;
        });
    }

    private void capturePhotoUi(String name) throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("图片查看器应能截图核对", screenshot);
        String expected = InstrumentationRegistry.getArguments().getString("expectedRecordsLayoutOrientation", "");
        if (!expected.isEmpty()) assertEquals("全屏大图应使用实际指定方向", "portrait".equals(expected),
                screenshot.getHeight() > screenshot.getWidth());
        File directory = new File(app.getCacheDir(), "photo-viewer-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }

    private void gesturePhoto(boolean fullscreen, float startX, float startY, float endX, float endY, int finalAction) {
        Rect bounds = photoBounds(fullscreen);
        long down = SystemClock.uptimeMillis();
        sendPointer(down, MotionEvent.ACTION_DOWN, bounds.left + bounds.width() * startX,
                bounds.top + bounds.height() * startY);
        boolean moving = startX != endX || startY != endY;
        if (moving) {
            for (int i = 1; i <= 10; i++) {
                SystemClock.sleep(16);
                sendPointer(down, MotionEvent.ACTION_MOVE,
                        bounds.left + bounds.width() * (startX + (endX - startX) * i / 10f),
                        bounds.top + bounds.height() * (startY + (endY - startY) * i / 10f));
            }
        }
        sendPointer(down, finalAction, bounds.left + bounds.width() * endX, bounds.top + bounds.height() * endY);
        instrumentation.waitForIdleSync();
    }

    private void sendPointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private void pinchFullscreenPhoto() {
        Rect bounds = photoBounds(true);
        float centerX = bounds.exactCenterX(), centerY = bounds.exactCenterY();
        long down = SystemClock.uptimeMillis();
        sendPointer(down, MotionEvent.ACTION_DOWN, centerX - bounds.width() * .1f, centerY);
        sendPinch(down, MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                centerX, centerY, bounds.width() * .1f);
        for (int i = 1; i <= 10; i++) {
            SystemClock.sleep(16);
            sendPinch(down, MotionEvent.ACTION_MOVE, centerX, centerY, bounds.width() * (.1f + .02f * i));
        }
        sendPinch(down, MotionEvent.ACTION_POINTER_UP | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                centerX, centerY, bounds.width() * .3f);
        sendPointer(down, MotionEvent.ACTION_UP, centerX - bounds.width() * .3f, centerY);
        instrumentation.waitForIdleSync();
    }

    private void sendPinch(long down, int action, float centerX, float centerY, float halfSpan) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = centerX + (i == 0 ? -halfSpan : halfSpan);
            coordinates[i].y = centerY;
            coordinates[i].pressure = 1f;
            coordinates[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 2, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            instrumentation.sendPointerSync(event);
        } finally {
            event.recycle();
        }
    }

    private boolean photoPositionMatches(int position, int top) {
        return onMain(() -> {
            RecordDetailActivity page = currentDetail();
            if (page == null) return false;
            RecyclerView list = page.findViewById(R.id.record_detail_photos);
            if (!(list.getLayoutManager() instanceof GridLayoutManager)) return false;
            GridLayoutManager layout = (GridLayoutManager) list.getLayoutManager();
            View row = layout.findViewByPosition(position);
            return list.isShown() && layout.findFirstVisibleItemPosition() == position && row != null
                    && Math.abs(layout.getDecoratedTop(row) - list.getPaddingTop() - top) <= 1;
        });
    }
    private static RecordDetailActivity currentDetail() { for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (page instanceof RecordDetailActivity) return (RecordDetailActivity) page; return null; }
    private static RecordPhotoActivity resumedPhoto() { for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if (page instanceof RecordPhotoActivity) return (RecordPhotoActivity) page; return null; }
    private static void assertReadOnlySections(RecordDetailActivity page) {
        assertTrue(page.findViewById(R.id.record_detail_fields).isShown());
        assertTrue(page.findViewById(R.id.record_detail_photos).isShown());
        TextView title = page.findViewById(R.id.record_detail_tab_photos);
        assertEquals("采集照片", title.getText().toString());
        assertTrue(title.isShown());
        assertFalse("采集照片标题不再充当切换标签", title.hasOnClickListeners());
        for (String name : new String[]{"record_detail_review", "record_detail_upload", "record_detail_tab_audit", "record_detail_tab_check", "record_design_upload", "record_design_upload_state", "record_extra_photos"}) { int id = page.getResources().getIdentifier(name, "id", page.getPackageName()); assertTrue("已移除功能不得留下控件", id == 0 || page.findViewById(id) == null); }
    }
    private static boolean hasColor(ImageView image, int expected) { if (image == null || !image.isShown() || !(image.getDrawable() instanceof BitmapDrawable)) return false; Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap(); if (bitmap == null || bitmap.isRecycled()) return false; int actual = bitmap.getPixel(bitmap.getWidth()/2, bitmap.getHeight()/2); return Math.abs(Color.red(actual)-Color.red(expected)) < 15 && Math.abs(Color.green(actual)-Color.green(expected)) < 15 && Math.abs(Color.blue(actual)-Color.blue(expected)) < 15; }
    private static String text(Activity activity, int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private static String longText(String start, String ending) { StringBuilder text = new StringBuilder(start); for (int i = 0; i < 16; i++) text.append("；第").append(i + 1).append("项检查说明需要完整保留"); return text.append("；").append(ending).toString(); }
    private void finishPages() {
        for (Stage stage : Stage.values())
            for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)))
                if (page.getPackageName().equals(app.getPackageName()) && !page.isFinishing()) page.finish();
    }

    private static Map<String, Object> snapshot(SharedPreferences preferences) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }

    private static void restore(SharedPreferences preferences, Map<String, Object> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) {
                Set<String> strings = new HashSet<>();
                for (Object string : (Set<?>) value) strings.add((String) string);
                editor.putStringSet(entry.getKey(), strings);
            } else throw new AssertionError("未支持的配置值类型：" + entry.getKey());
        }
        assertTrue("测试结束必须恢复全部设备配置", editor.commit());
    }

    private void drainIo() throws Exception {
        for (int i = 0; i < 3; i++) { onIo(() -> null); instrumentation.waitForIdleSync(); }
    }

    private <T> T onIo(Callable<T> action) throws Exception { return app.io.submit(action).get(10, TimeUnit.SECONDS); }

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

    private static void await(BooleanSupplier condition, String message) {
        await(condition, message, null);
    }

    private static void await(BooleanSupplier condition, String message, Supplier<String> diagnostics) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(diagnostics == null ? message : message + "；" + diagnostics.get());
    }
}
