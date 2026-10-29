package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 真实记录和图片解码的界面回归；所有照片为测试色块，SDK 和云端均隔离在本机夹具。 */
@RunWith(AndroidJUnit4.class)
public final class ResultEvidenceUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<String> recordIds = new ArrayList<>();
    private final List<String> photoNames = new ArrayList<>();
    private final Map<String, Object> changedPreferences = new HashMap<>();
    private final AtomicInteger cloudRequests = new AtomicInteger();
    private final String prefix = "ui-evidence-" + UUID.randomUUID();
    private MorningApplication app;
    private SharedPreferences preferences;
    private MockWebServer endpoint;
    private ActivityScenario<ResultActivity> scenario;
    private CountDownLatch pendingIoRelease;

    /** 拒绝所有 SDK 请求，保证测试不会发起真实识别、登录、云上传或切换下一位。 */
    @Before public void isolateConnections() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        for (String key : new String[]{MorningSdkSettings.TRANSPORT_KEY, MorningSdkSettings.ENDPOINT_KEY,
                "cloud_url", "cloud_token", "voice"}) changedPreferences.put(key, preferences.getAll().get(key));
        String fontArgument = InstrumentationRegistry.getArguments().getString("photo_test_font_index", "");
        if (!fontArgument.isEmpty()) {
            int fontIndex = Integer.parseInt(fontArgument);
            assertTrue("照片界面字体档位必须有效", fontIndex >= 0 && fontIndex <= 3);
            changedPreferences.put(AppFontSettings.KEY, preferences.getAll().get(AppFontSettings.KEY));
            assertTrue(preferences.edit().putInt(AppFontSettings.KEY, fontIndex).commit());
        }
        instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/cloud")) cloudRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("仅测试界面，SDK 保持未就绪");
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString()).putString("cloud_token", "")
                .putBoolean("voice", false).commit());
        onIo(() -> null);
    }

    /** 只删除本用例显式创建的 UUID 记录和照片，并恢复自己更改的配置键。 */
    @After public void restoreOwnFixtures() throws Exception {
        if (pendingIoRelease != null) pendingIoRelease.countDown();
        try {
            if (scenario != null) scenario.close();
        } finally {
            if (app != null) instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
            try {
                if (app != null) {
                    onIo(() -> {
                        for (String id : recordIds) {
                            assertTrue(id.startsWith(prefix));
                            app.store.getWritableDatabase().delete("records", "id=?", new String[]{id});
                        }
                        for (String name : photoNames) {
                            assertTrue(name.startsWith(prefix));
                            File file = app.store.photo(name);
                            assertTrue("测试照片应能清理", !file.exists() || file.delete());
                        }
                        return null;
                    });
                }
            } finally {
                if (preferences != null) {
                    SharedPreferences.Editor restore = preferences.edit();
                    for (Map.Entry<String, Object> entry : changedPreferences.entrySet()) {
                        if (entry.getValue() instanceof String) restore.putString(entry.getKey(), (String) entry.getValue());
                        else if (entry.getValue() instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        else if (entry.getValue() instanceof Integer) restore.putInt(entry.getKey(), (Integer) entry.getValue());
                        else restore.remove(entry.getKey());
                    }
                    assertTrue("应恢复测试前配置", restore.commit());
                }
                if (endpoint != null) endpoint.shutdown();
                assertEquals("本地证据测试不能触发业务云请求", 0, cloudRequests.get());
            }
        }
    }

    @Test public void resultReadsOnlyItsOwnRecordEvenWhenUnrelatedRecordIsDamaged() throws Exception {
        String recordId = newRecordId("direct-read");
        String damagedId = newRecordId("unrelated-damaged");
        saveRecord(record(recordId).put("staff_name", "按编号读取的测试结果"));
        saveRecord(record(damagedId));
        onIo(() -> {
            ContentValues broken = new ContentValues();
            broken.put("data", "{");
            assertEquals(1, app.store.getWritableDatabase().update("records", broken, "id=?", new String[]{damagedId}));
            return null;
        });
        launch(recordId);
        drainLocalDecode();
        scenario.onActivity(activity -> {
            assertEquals(recordId, activity.currentRecord.optString("id"));
            assertTrue(((TextView) activity.findViewById(R.id.result_person)).getText().toString()
                    .startsWith("按编号读取的测试结果"));
            assertActionsVisible(activity);
        });
        // 本地记录修改后仍应按 ID 刷新当前页，不能重新解码无关记录。
        JSONObject changes = new JSONObject().put("staff_name", "更新后的测试员工")
                .put("health_expiry", "2032-12-31");
        onIo(() -> { app.store.patchRecord(recordId, changes); return null; });
        scenario.onActivity(activity -> {
            app.notifyRecordsChanged();
            activity.reload(false, null);
        });
        await(() -> {
            AtomicReference<Boolean> saved = new AtomicReference<>(false);
            scenario.onActivity(activity -> saved.set("更新后的测试员工".equals(
                    activity.currentRecord.optString("staff_name"))
                    && "2032-12-31".equals(activity.currentRecord.optString("health_expiry"))));
            return saved.get();
        }, "修改本地记录后应按编号刷新当前结果");
        drainLocalDecode();
        scenario.onActivity(activity -> {
            assertEquals(recordId, activity.currentRecord.optString("id"));
            assertEquals(1, activity.currentRecord.optInt("status"));
            assertEquals("未测", activity.currentRecord.optString("temperature"));
            assertEquals("仅验证结果界面与本地照片，不代表一次真实晨检。",
                    activity.currentRecord.optJSONArray("findings").optString(0));
        });
    }

    @Test public void readablePhotoMissingSlotAndRecreationRejectOldDecode() throws Exception {
        String recordId = newRecordId("mixed");
        String redFile = createPhoto(recordId, "red", Color.RED);
        String blueFile = createPhoto(recordId, "blue", Color.BLUE);
        saveRecord(record(recordId).put("face_img_file", redFile)
                .put("back_hand_img_file", prefix + "-missing.jpg"));
        launch(recordId);
        await(() -> currentImageHasColor(Color.RED), "应显示真实本地红色测试照片");
        scenario.onActivity(activity -> {
            assertTrue(activity.findViewById(R.id.result_evidence).isShown());
            RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
            assertEquals("历史记录继续展示三个证据槽位", 3, grid.getAdapter().getItemCount());
            assertPhotoLayout(activity);
            assertNoAllPhotosNavigation(activity);
            ImageView face = photoImage(activity, 0);
            Bitmap bitmap = ((BitmapDrawable) face.getDrawable()).getBitmap();
            assertTrue("应采样为缩略图而非加载原尺寸", Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 480);
            assertTrue("可读证据允许进入真实照片查看器", photoCell(activity, 0).hasOnClickListeners()
                    || face.hasOnClickListeners());
            assertPhotoViewport(face);
            assertFindingsViewport(activity);
            assertActionsVisible(activity);
            saveScreenshot(activity.findViewById(R.id.main_root), "result-one-photo");
        });
        for (int position : new int[]{1, 2}) {
            selectPhoto(position);
            scenario.onActivity(activity -> {
                assertEmptyPhoto(activity, position, position == 1
                        ? "缺失的手背照片应保持空图" : "未保存的手心照片应保持空图");
                assertFindingsViewport(activity);
                assertActionsVisible(activity);
            });
        }
        selectPhoto(0);

        // 故意让旧页面的红图解码排队；重建前修改同一测试记录，旧任务不得回写新页面或旧脱离视图。
        CountDownLatch ioEntered = new CountDownLatch(1);
        pendingIoRelease = new CountDownLatch(1);
        app.io.execute(() -> {
            ioEntered.countDown();
            try { assertTrue("测试队列等待超时", pendingIoRelease.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        });
        assertTrue(ioEntered.await(5, TimeUnit.SECONDS));
        AtomicReference<ResultActivity> previous = new AtomicReference<>();
        AtomicReference<ImageView> detachedPendingImage = new AtomicReference<>();
        scenario.onActivity(activity -> {
            previous.set(activity);
            activity.render();
        });
        instrumentation.waitForIdleSync();
        await(() -> {
            AtomicReference<Boolean> laidOut = new AtomicReference<>(false);
            scenario.onActivity(activity -> laidOut.set(hasVisiblePhotoCell(activity, 0)));
            return laidOut.get();
        }, "重排后的新网格应完成实际布局");
        scenario.onActivity(activity -> {
            detachedPendingImage.set(photoImage(activity, 0));
            assertEmptyImage("重排后的新视图应等待读盘", detachedPendingImage.get());
        });
        Future<?> patched = app.io.submit(() -> {
            try { app.store.patchRecord(recordId, new JSONObject().put("face_img_file", blueFile)); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        try {
            scenario.recreate();
        } finally {
            pendingIoRelease.countDown();
        }
        patched.get(10, TimeUnit.SECONDS);
        await(() -> currentImageHasColor(Color.BLUE), "重建后应读取最新蓝图，不能回显旧红图");
        scenario.onActivity(activity -> {
            assertNotSame(previous.get(), activity);
            assertTrue(previous.get().isDestroyed());
            assertFalse(detachedPendingImage.get().isAttachedToWindow());
            assertEmptyImage("已离开的旧视图不能收到迟到解码结果", detachedPendingImage.get());
            assertEquals(blueFile, activity.currentRecord.optString("face_img_file"));
            assertPhotoViewport(photoImage(activity, 0));
            assertFindingsViewport(activity);
            assertActionsVisible(activity);
            saveScreenshot(activity.findViewById(R.id.main_root), "result-after-recreation");
        });
    }

    @Test public void historicalRecordsWithoutReadablePhotosShowPlaceholders() throws Exception {
        for (boolean missingFile : new boolean[]{true, false}) {
            String recordId = newRecordId(missingFile ? "missing" : "empty");
            JSONObject value = record(recordId);
            if (missingFile) value.put("face_img_file", prefix + "-absent-history.jpg");
            saveRecord(value);
            launch(recordId);
            drainLocalDecode();
            scenario.onActivity(activity -> {
                assertEquals("缺图历史记录仍应显示三类证据占位", View.VISIBLE,
                        activity.findViewById(R.id.result_evidence).getVisibility());
                RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
                assertEquals("缺图仍保留三个默认槽位", 3, grid.getAdapter().getItemCount());
                assertPhotoLayout(activity);
                assertNoAllPhotosNavigation(activity);
            });
            for (int i = 0; i < 3; i++) {
                final int position = i;
                selectPhoto(position);
                scenario.onActivity(activity -> {
                    assertEmptyPhoto(activity, position, "没有可读取照片时不能沿用旧图");
                    TextView placeholder = photoCell(activity, position).findViewById(R.id.result_photo_empty);
                    assertTrue("缺失照片应有可见提示", placeholder.isShown());
                    assertTrue("缺失照片提示不能是空文案", placeholder.getText().length() > 0);
                });
            }
            selectPhoto(0);
            scenario.onActivity(activity -> {
                assertTrue(((TextView) activity.findViewById(R.id.result_findings)).getText().length() > 0);
                assertFindingsViewport(activity);
                assertActionsVisible(activity);
                saveScreenshot(activity.findViewById(R.id.main_root), missingFile ? "result-missing-photo" : "result-no-photo");
            });
            scenario.close();
            scenario = null;
        }
    }

    @Test public void allCapturedPhotosScrollWithoutRecycledImageOrIndexMixup() throws Exception {
        String recordId = newRecordId("scroll-reuse");
        JSONArray captured = new JSONArray();
        int missingIndex = 6;
        int[] colors = {
                Color.rgb(60, 60, 60), Color.rgb(60, 60, 140), Color.rgb(60, 60, 220),
                Color.rgb(60, 140, 60), Color.rgb(60, 140, 140), Color.rgb(60, 140, 220),
                Color.rgb(140, 60, 60), Color.rgb(140, 60, 140), Color.rgb(140, 60, 220),
                Color.rgb(220, 140, 60), Color.rgb(220, 140, 140), Color.rgb(220, 140, 220)
        };
        for (int i = 0; i < colors.length; i++) {
            captured.put(new JSONObject().put("label", "滚动证据 " + (i + 1))
                    .put("file", i == missingIndex ? "" : createPhoto(recordId, "scroll-" + i, colors[i])));
        }
        saveRecord(record(recordId).put("status", 0).put("abnormal_hand", 1).put("captured_photos", captured));
        launch(recordId);
        drainLocalDecode();
        scenario.onActivity(activity -> {
            RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
            assertEquals("结果页不能把多张照片截断为三张代表图", 12, grid.getAdapter().getItemCount());
            assertPhotoLayout(activity);
            if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
                assertTrue("横屏多张采集照片应能横向滑动", grid.canScrollHorizontally(1));
            else assertTrue("竖屏多张采集照片应能向下滑动", grid.canScrollVertically(1));
            assertNoAllPhotosNavigation(activity);
        });

        // 十二个独立色块跨越真实照片视口，末尾、缺项和开头的切换会复用照片卡片。
        selectPhoto(11);
        scenario.onActivity(activity -> {
            assertPhotoIdentity(activity, 11, colors[11], "滚动证据 12");
            assertActionsVisible(activity);
            assertFindingsViewport(activity);
        });
        selectPhoto(missingIndex);
        scenario.onActivity(activity -> {
            assertEquals("空文件仍应保留原采集索引", "滚动证据 7",
                    ((TextView) photoCell(activity, missingIndex).findViewById(R.id.result_photo_label)).getText().toString());
            assertEmptyPhoto(activity, missingIndex, "回收后的缺图项不能沿用末尾色块");
            assertTrue(((TextView) photoCell(activity, missingIndex).findViewById(R.id.result_photo_empty)).isShown());
            assertEquals("缺项不能让后续照片下标前移", 12,
                    ((RecyclerView) activity.findViewById(R.id.result_evidence_grid)).getAdapter().getItemCount());
            assertActionsVisible(activity);
            assertFindingsViewport(activity);
        });
        selectPhoto(0);
        scenario.onActivity(activity -> {
            assertPhotoIdentity(activity, 0, colors[0], "滚动证据 1");
            assertActionsVisible(activity);
            assertFindingsViewport(activity);
            saveScreenshot(activity.findViewById(R.id.main_root), "result-twelve-inline-photos");
        });

        // 点击末尾图应携带原始第十二项索引，不能因中间空图或复用卡片打开另一张照片。
        selectPhoto(11);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(RecordPhotoActivity.class.getName(), null, false);
        RecordPhotoActivity viewer = null;
        try {
            scenario.onActivity(activity -> assertTrue(photoCell(activity, 11).performClick()));
            viewer = (RecordPhotoActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10_000);
            assertNotNull("末尾缩略图应允许单张放大", viewer);
            assertEquals("单图放大必须保留当前记录", recordId,
                    viewer.getIntent().getStringExtra(RecordDetailActivity.EXTRA_RECORD_ID));
            assertEquals("空项后单图放大仍按原采集索引打开", 11,
                    viewer.getIntent().getIntExtra(RecordPhotoActivity.EXTRA_PHOTO_INDEX, -1));
        } finally {
            if (viewer != null) {
                RecordPhotoActivity opened = viewer;
                instrumentation.runOnMainSync(opened::finish);
            }
            instrumentation.removeMonitor(monitor);
        }
        await(() -> {
            AtomicReference<Boolean> returned = new AtomicReference<>(false);
            scenario.onActivity(activity -> returned.set(activity.currentRecord != null
                    && recordId.equals(activity.currentRecord.optString("id"))
                    && activity.findViewById(R.id.result_evidence_grid).getWidth() > 0));
            return returned.get();
        }, "关闭放大查看器后应返回同一晨检结果");
        selectPhoto(11);
        scenario.onActivity(activity -> {
            assertPhotoIdentity(activity, 11, colors[11], "滚动证据 12");
            assertPhotoLayout(activity);
            assertActionsVisible(activity);
            assertFindingsViewport(activity);
        });
    }

    @Test public void detachedCheckLayoutFitsWithoutCameraOrRecognition() throws Exception {
        String recordId = newRecordId("layout-host");
        saveRecord(record(recordId));
        launch(recordId);
        scenario.onActivity(activity -> {
            // 只用现有 Activity 的主题和逻辑屏幕尺寸渲染 XML，不启动 CheckActivity、不申请相机或运行识别。
            View window = activity.findViewById(R.id.main_root);
            ViewGroup check = (ViewGroup) activity.getLayoutInflater().inflate(R.layout.activity_check_design_active, null, false);
            copyWindowInsets(check, window);
            TextView title = check.findViewById(R.id.header_page_title);
            title.setVisibility(View.VISIBLE);
            title.setText("晨检工作台");
            TextView prompt = check.findViewById(R.id.check_hint);
            prompt.setText("未匹配员工\n请确认已录入人脸");
            TextView progress = check.findViewById(R.id.check_progress);
            // 用实际运行中的最长阶段文本验收，短夹具无法发现手机横屏的末尾截断。
            progress.setText("人脸识别 · 步骤 1/5 · 连续采样 0/4");
            TextView temperature = check.findViewById(R.id.check_temperature);
            temperature.setVisibility(View.GONE);
            check.measure(View.MeasureSpec.makeMeasureSpec(window.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(window.getHeight(), View.MeasureSpec.EXACTLY));
            check.layout(0, 0, check.getMeasuredWidth(), check.getMeasuredHeight());
            saveScreenshot(check, "check-layout-only-not-recognition");
            Rect titleBounds = new Rect(0, 0, title.getWidth(), title.getHeight());
            check.offsetDescendantRectToMyCoords(title, titleBounds);
            assertTrue("当前晨检页头标题必须相对整页居中",
                    Math.abs(titleBounds.exactCenterX() - check.getWidth() / 2f) <= 1f);
            assertDetachedFirstLineVisible(check, title, "晨检页头标题", true);
            Layout text = progress.getLayout();
            assertNotNull(text);
            assertEquals("阶段与采样文字不能被最大行数截断",
                    progress.getText().length(), text.getLineEnd(text.getLineCount() - 1));
            Rect progressLines = new Rect(progress.getCompoundPaddingLeft(), progress.getTotalPaddingTop(),
                    progress.getWidth() - progress.getCompoundPaddingRight(),
                    progress.getTotalPaddingTop() + text.getLineBottom(text.getLineCount() - 1));
            assertDetachedRectVisible(check, progress, progressLines, "全部阶段与采样进度");
            assertEquals("现场页不展示独立测温提示", View.GONE, temperature.getVisibility());
            assertDetachedBounds(check, check.findViewById(R.id.check_exit));
            assertDetachedFirstLineVisible(check, prompt, "当前异常提示", true);
            assertEquals("当前异常提示应完整显示", prompt.getText().length(),
                    prompt.getLayout().getLineEnd(prompt.getLayout().getLineCount() - 1));
            View camera = check.findViewById(R.id.check_camera_slot);
            assertTrue("布局必须为真实预览保留可用面积", camera.getWidth() > 0 && camera.getHeight() > 0);
            if (activity.getResources().getBoolean(R.bool.mc_phone)
                    && activity.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                assertTrue("长提示不能把手机预览压成一条细线",
                        camera.getHeight() >= 100 * activity.getResources().getDisplayMetrics().density);
        });
    }

    /** 不绑定业务控制器，只检查当前设备资源选择后的全部本项目 XML 能否真实膨胀和排版。 */
    @Test public void allLocalLayoutsInflateAndMeasureWithoutStartingTheirFlows() throws Exception {
        String recordId = newRecordId("all-layout-host");
        saveRecord(record(recordId));
        launch(recordId);
        scenario.onActivity(activity -> {
            View window = activity.findViewById(R.id.main_root);
            Field[] resources = R.layout.class.getDeclaredFields();
            Arrays.sort(resources, Comparator.comparing(Field::getName));
            List<String> failures = new ArrayList<>();
            int checked = 0;
            for (Field resource : resources) {
                if (resource.getType() != int.class || !Modifier.isStatic(resource.getModifiers())) continue;
                try {
                    // 项目开启 nonTransitiveRClass，遍历只包含自己的布局。提供父容器才能验证根节点尺寸。
                    // 设置容器是 FrameLayout，摄像头只是 TextureView；单独膨胀 XML 不会启动真实流程。
                    View layout = activity.getLayoutInflater().inflate(resource.getInt(null), new FrameLayout(activity), false);
                    assertNotNull("根节点必须能生成布局参数", layout.getLayoutParams());
                    copyWindowInsets(layout, window);
                    measureDetached(layout, window.getWidth(), window.getHeight());
                    checked++;
                } catch (Exception | AssertionError error) {
                    failures.add(resource.getName() + "：" + describeCauses(error));
                }
            }
            assertTrue("应找到本项目的布局资源", resources.length > 0);
            assertTrue("离线布局膨胀或排版失败：\n" + String.join("\n", failures), failures.isEmpty());
            assertTrue("应实际完成至少一个布局检查", checked > 0);
            android.util.Log.i("MorningLayoutTest", "当前屏幕全部本项目布局检查通过，共 " + checked + " 项，无排除项");
        });
    }

    /** 检查当前九宫格主页，滚动只作用于入口，主卡与页头始终固定。 */
    @Test public void detachedHomeKeepsCurrentCardsStartAndHeaderAboutVisible() throws Exception {
        String recordId = newRecordId("home-layout-host");
        saveRecord(record(recordId));
        launch(recordId);
        scenario.onActivity(activity -> {
            View window = activity.findViewById(R.id.main_root);
            ViewGroup home = (ViewGroup) activity.getLayoutInflater().inflate(
                    R.layout.activity_home_design, new FrameLayout(activity), false);
            copyWindowInsets(home, window);
            LinearLayout.LayoutParams leading = (LinearLayout.LayoutParams) home.findViewById(R.id.header_leading).getLayoutParams();
            leading.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            leading.weight = 0;
            LinearLayout.LayoutParams trailing = (LinearLayout.LayoutParams) home.findViewById(R.id.header_trailing).getLayoutParams();
            trailing.width = 0;
            trailing.weight = 0;
            TextView about = home.findViewById(R.id.home_settings);
            about.setVisibility(View.VISIBLE);
            about.setText(activity.getString(R.string.mc_home_design_about));
            TextView place = home.findViewById(R.id.header_shop);
            assertNotNull("首页应展示场所名称", place);
            place.setVisibility(View.VISIBLE);
            String placeName = activity.currentShopName();
            place.setText(placeName.isEmpty() ? "未设置场所" : placeName);
            assertFalse("场所名称展示不应保留旧扫码导航监听", place.hasOnClickListeners());
            int iconDp = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                    && activity.getResources().getConfiguration().screenWidthDp < 1100 ? 24 : 28;
            for (int id : homeShortcutIds()) {
                View icon = ((ViewGroup) home.findViewById(id)).getChildAt(0);
                icon.getLayoutParams().width = Math.round(iconDp * activity.getResources().getDisplayMetrics().density);
                icon.getLayoutParams().height = icon.getLayoutParams().width;
            }
            measureDetached(home, window.getWidth(), window.getHeight());
            saveScreenshot(home, "home-current-design-layout-only");
            ScrollView scroll = home.findViewById(R.id.home_shortcut_scroll);
            Rect header = homeBounds(home, home.getChildAt(0));
            Rect start = homeBounds(home, home.findViewById(R.id.home_start));
            Rect aboutBounds = homeBounds(home, about);
            assertDetachedFirstLineVisible(home, home.findViewById(R.id.home_start), "开始晨检", true);
            assertDetachedFirstLineVisible(home, about, "关于设备", true);
            for (int id : homeShortcutIds()) {
                View entry = home.findViewById(id);
                String label = activity.getResources().getResourceEntryName(id);
                assertNotNull(label + " 当前功能卡片必须存在", entry);
                assertTrue(label + " 触控高度至少44dp", entry.getHeight() >= 44 * activity.getResources().getDisplayMetrics().density);
                if (scroll != null) {
                    ViewGroup grid = (ViewGroup) scroll.getChildAt(0);
                    scroll.scrollTo(0, homeBounds(grid, entry).top);
                    assertEquals("入口只允许竖向滚动", 0, scroll.getScrollX());
                    assertHomeGridEntryVisible(home, scroll, entry, firstVisibleText(entry), label);
                } else assertHomeCardContentVisible(home, entry, label);
                assertEquals("入口滚动不能移动页头", header, homeBounds(home, home.getChildAt(0)));
                assertEquals("入口滚动不能移动晨检按钮", start, homeBounds(home, home.findViewById(R.id.home_start)));
                assertEquals("入口滚动不能移动关于设备", aboutBounds, homeBounds(home, about));
            }
        });
    }

    @Test public void currentHomeShortcutsKeepNavigationAndScrollAfterRecreation() {
        AtomicReference<Integer> offset = new AtomicReference<>();
        try (ActivityScenario<HomeActivity> home = ActivityScenario.launch(HomeActivity.class)) {
            instrumentation.waitForIdleSync();
            home.onActivity(activity -> {
                for (int id : homeShortcutIds()) {
                    View entry = activity.findViewById(id);
                    assertTrue("当前入口应实际显示", entry.isShown());
                    assertTrue("当前入口应绑定导航", entry.hasOnClickListeners());
                }
                ScrollView scroll = activity.findViewById(R.id.home_shortcut_scroll);
                if (scroll != null) {
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    offset.set(scroll.getScrollY());
                }
            });
            home.recreate();
            instrumentation.waitForIdleSync();
            home.onActivity(activity -> {
                for (int id : homeShortcutIds()) assertTrue("重建后入口仍可导航", activity.findViewById(id).hasOnClickListeners());
                if (offset.get() != null) assertEquals("重建后恢复入口滚动位置", (int) offset.get(),
                        ((ScrollView) activity.findViewById(R.id.home_shortcut_scroll)).getScrollY());
            });
        }
    }

    private static int[] homeShortcutIds() {
        return new int[]{R.id.home_staff, R.id.home_records, R.id.home_connection,
                R.id.home_lighting, R.id.home_authorization, R.id.home_camera,
                R.id.home_items, R.id.home_temperature, R.id.home_system};
    }

    /** 对当前规格展示的文字与图标共同验收，保留全部名称和可访问入口。 */
    private static void assertHomeCardContentVisible(ViewGroup root, View entry, String label) {
        List<TextView> texts = homeCardTexts(entry, label);
        for (int index = 0; index < texts.size(); index++) {
            String textLabel = label + (index == 0 ? " 标题" : " 短描述");
            assertHomeSingleLineComplete(texts.get(index), textLabel);
            assertDetachedFirstLineVisible(root, texts.get(index), textLabel, true);
        }
        ImageView icon = homeCardIcon(entry, label);
        assertHomeCardArrangement(entry, texts, icon, label);
        assertDetachedRectVisible(root, icon,
                new Rect(0, 0, icon.getWidth(), icon.getHeight()), label + " 图标");
    }

    private static List<TextView> homeCardTexts(View entry, String label) {
        List<TextView> texts = new ArrayList<>();
        collectHomeCardViews(entry, texts, new ArrayList<>());
        assertEquals(label + " 必须同时包含标题和一段短描述", 2, texts.size());
        assertTrue(label + " 标题不能为空", texts.get(0).getText().length() > 0);
        assertEquals(label + " 应展示对应功能的短描述", homeCardDescription(entry.getId()),
                texts.get(1).getText().toString());
        return texts;
    }

    private static ImageView homeCardIcon(View entry, String label) {
        List<ImageView> icons = new ArrayList<>();
        collectHomeCardViews(entry, new ArrayList<>(), icons);
        assertEquals(label + " 必须包含一个可见图标", 1, icons.size());
        ImageView icon = icons.get(0);
        assertNotNull(label + " 图标必须有实际图像", icon.getDrawable());
        assertTrue(label + " 图标必须有实际绘制面积", icon.getWidth() > 0 && icon.getHeight() > 0);
        return icon;
    }

    /** 所有规格都使用左侧图标、右侧上下两段文字，不能退回图标在上方或隐藏解释的排法。 */
    private static void assertHomeCardArrangement(View entry, List<TextView> texts, ImageView icon, String label) {
        assertTrue(label + " 卡片必须有子视图容器", entry instanceof ViewGroup);
        ViewGroup card = (ViewGroup) entry;
        Rect iconBounds = homeBounds(card, icon);
        Rect titleBounds = homeBounds(card, texts.get(0));
        Rect captionBounds = homeBounds(card, texts.get(1));
        assertTrue(label + " 图标必须位于标题左侧", iconBounds.right <= titleBounds.left);
        assertTrue(label + " 图标必须位于解释左侧", iconBounds.right <= captionBounds.left);
        assertTrue(label + " 解释必须位于标题下方", titleBounds.bottom <= captionBounds.top);
        Rect contentBounds = new Rect(iconBounds);
        contentBounds.union(titleBounds);
        contentBounds.union(captionBounds);
        Rect availableBounds = new Rect(card.getPaddingLeft(), card.getPaddingTop(),
                card.getWidth() - card.getPaddingRight(), card.getHeight() - card.getPaddingBottom());
        // 按实际子视图验收整个组合，避免文字容器占满剩余宽度后只有垂直居中的情况。
        if (entry.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
            assertTrue(label + " 图标与两段文字必须水平整体居中，内容=" + contentBounds
                        + "，可用区域=" + availableBounds,
                Math.abs(contentBounds.exactCenterX() - availableBounds.exactCenterX()) <= 2f);
        assertTrue(label + " 图标与两段文字必须垂直整体居中，内容=" + contentBounds
                        + "，可用区域=" + availableBounds,
                Math.abs(contentBounds.exactCenterY() - availableBounds.exactCenterY()) <= 2f);
    }

    private static void collectHomeCardViews(View view, List<TextView> texts, List<ImageView> icons) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) texts.add((TextView) view);
        if (view instanceof ImageView) icons.add((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                collectHomeCardViews(group.getChildAt(index), texts, icons);
        }
    }

    private static String homeCardDescription(int id) {
        if (id == R.id.home_staff) return "资料、部门与人脸";
        if (id == R.id.home_records) return "检查结果与照片";
        if (id == R.id.home_connection) return "本机与局域网";
        if (id == R.id.home_authorization) return "授权状态与激活";
        if (id == R.id.home_camera) return "头部与手部预览";
        if (id == R.id.home_lighting) return "串口与 GPIO 补光";
        if (id == R.id.home_items) return "着装与体温阈值";
        if (id == R.id.home_temperature) return "串口配置与测试";
        if (id == R.id.home_system) return "语音、字号与安全";
        throw new AssertionError("没有对应短描述的当前首页卡片：" + id);
    }

    private static void assertHomeSingleLineComplete(TextView text, String label) {
        Layout layout = text.getLayout();
        assertNotNull(label + " 必须完成文字排版", layout);
        assertEquals(label + " 必须保持单行", 1, layout.getLineCount());
        assertEquals(label + " 不能被最大行数截断", text.getText().length(), layout.getLineEnd(0));
        assertEquals(label + " 不能省略", 0, layout.getEllipsisCount(0));
    }

    private static Rect homeBounds(ViewGroup home, View child) {
        Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
        home.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
    }

    /** 滚动容器的本地坐标包含滚动量，先换算真实视口，再验收图标及两段文字的完整行盒。 */
    private static void assertHomeGridEntryVisible(ViewGroup home, ScrollView viewport,
                                                   View entry, TextView title, String label) {
        Rect available = new Rect(viewport.getScrollX() + viewport.getPaddingLeft(),
                viewport.getScrollY() + viewport.getPaddingTop(),
                viewport.getScrollX() + viewport.getWidth() - viewport.getPaddingRight(),
                viewport.getScrollY() + viewport.getHeight() - viewport.getPaddingBottom());
        home.offsetDescendantRectToMyCoords(viewport, available);
        assertTrue(label + " 应能滚动到整张卡片可见", available.contains(homeBounds(home, entry)));
        assertEquals(label + " 必须可见", View.VISIBLE, entry.getVisibility());
        List<TextView> texts = homeCardTexts(entry, label);
        assertSame(label + " 第一段文字必须为入口标题", title, texts.get(0));
        for (int index = 0; index < texts.size(); index++) {
            TextView text = texts.get(index);
            String textLabel = label + (index == 0 ? " 标题" : " 短描述");
            assertHomeSingleLineComplete(text, textLabel);
            assertDetachedFirstLineVisible((ViewGroup) entry, text, textLabel, true);
            Layout layout = text.getLayout();
            Rect line = new Rect(text.getCompoundPaddingLeft() + (int) Math.floor(layout.getLineLeft(0)),
                    text.getTotalPaddingTop() + layout.getLineTop(0),
                    text.getCompoundPaddingLeft() + (int) Math.ceil(layout.getLineRight(0)),
                    text.getTotalPaddingTop() + layout.getLineBottom(0));
            home.offsetDescendantRectToMyCoords(text, line);
            assertTrue(textLabel + " 必须完整位于滚动视口内", available.contains(line));
        }
        ImageView icon = homeCardIcon(entry, label);
        assertHomeCardArrangement(entry, texts, icon, label);
        assertDetachedRectVisible((ViewGroup) entry, icon,
                new Rect(0, 0, icon.getWidth(), icon.getHeight()), label + " 图标");
        assertTrue(label + " 图标必须完整位于滚动视口内", available.contains(homeBounds(home, icon)));
    }

    private String newRecordId(String suffix) {
        String id = prefix + "-" + suffix;
        recordIds.add(id);
        return id;
    }

    private JSONObject record(String id) throws Exception {
        return new JSONObject().put("id", id).put("staff_id", 0).put("staff_name", "界面测试色块记录")
                .put("created_at", System.currentTimeMillis()).put("status", 1)
                .put("temperature", "未测").put("temperature_source", "测试夹具")
                .put("health_expiry", "").put("abnormal_face", 0).put("abnormal_hand", 0)
                .put("findings", new JSONArray().put("仅验证结果界面与本地照片，不代表一次真实晨检。"));
    }

    private void saveRecord(JSONObject record) throws Exception {
        onIo(() -> { app.store.saveRecord(record); return null; });
    }

    private String createPhoto(String recordId, String stage, int color) throws Exception {
        String name = onIo(() -> {
            Bitmap bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            try { return app.store.savePhoto(recordId, stage, bitmap); }
            finally { bitmap.recycle(); }
        });
        photoNames.add(name);
        return name;
    }

    private void launch(String recordId) {
        scenario = ActivityScenario.launch(new Intent(app, ResultActivity.class)
                .putExtra("record_id", recordId));
        String expectedOrientation = InstrumentationRegistry.getArguments().getString("expected_orientation", "");
        if (!expectedOrientation.isEmpty()) {
            assertTrue("方向参数必须是 portrait 或 landscape",
                    "portrait".equals(expectedOrientation) || "landscape".equals(expectedOrientation));
            scenario.onActivity(activity -> activity.setRequestedOrientation("portrait".equals(expectedOrientation)
                    ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        }
        await(() -> {
            AtomicReference<Boolean> loaded = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.main_root);
                RecyclerView photos = activity.findViewById(R.id.result_evidence_grid);
                boolean orientationMatches = expectedOrientation.isEmpty()
                        || ("portrait".equals(expectedOrientation)
                        ? root.getHeight() > root.getWidth()
                        && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                        : root.getWidth() > root.getHeight()
                        && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
                loaded.set(activity.currentRecord != null && recordId.equals(activity.currentRecord.optString("id"))
                        && activity.findViewById(R.id.result_next).getWidth() > 0
                        && photos.getWidth() > 0 && photos.getHeight() > 0 && photos.getChildCount() > 0
                        && !root.isLayoutRequested() && orientationMatches);
            });
            return loaded.get();
        }, "测试记录应完成本地加载");
        scenario.onActivity(activity -> assertHiddenShopViews(activity.findViewById(R.id.main_root)));
    }

    private static void assertHiddenShopViews(View root) {
        View header = root.findViewById(R.id.header_shop);
        assertNotNull("页头门店控件应保留以便恢复", header);
        for (View entry : new View[]{header}) {
            if (entry == null) continue;
            assertEquals("门店入口应隐藏且不占布局空间", View.GONE, entry.getVisibility());
            assertFalse("隐藏门店入口不能保留导航监听", entry.hasOnClickListeners());
            assertFalse("隐藏门店入口不能触发导航", entry.performClick());
        }
    }

    /** 先等数据库回调排入解码，再等待解码回主线程，避免仅靠初始 GONE 得到假阳性。 */
    private void drainLocalDecode() throws Exception {
        for (int i = 0; i < 3; i++) {
            onIo(() -> null);
            instrumentation.waitForIdleSync();
        }
    }

    private boolean currentImageHasColor(int color) {
        AtomicReference<Boolean> matches = new AtomicReference<>(false);
        scenario.onActivity(activity -> {
            RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
            if (grid == null || grid.getLayoutManager() == null) return;
            View cell = grid.getLayoutManager().findViewByPosition(0);
            if (cell != null) matches.set(hasColor(cell.findViewById(R.id.result_photo_image), color));
        });
        return matches.get();
    }

    /** 只读取实际屏幕里的照片卡片，不能手工绑定脱离窗口的假证据。 */
    private static View photoCell(ResultActivity activity, int position) {
        RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
        assertNotNull("结果页应直接展示照片列表", grid);
        assertNotNull("照片列表应完成布局", grid.getLayoutManager());
        View cell = grid.getLayoutManager().findViewByPosition(position);
        assertNotNull("照片位置必须在当前列表视口内：" + position, cell);
        return cell;
    }

    private static boolean hasVisiblePhotoCell(ResultActivity activity, int position) {
        RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
        if (grid == null || grid.getLayoutManager() == null) return false;
        View cell = grid.getLayoutManager().findViewByPosition(position);
        if (cell == null) return false;
        View image = cell.findViewById(R.id.result_photo_image);
        Rect visible = new Rect();
        return image != null && image.getGlobalVisibleRect(visible) && visible.height() > 0;
    }

    private void selectPhoto(int position) throws Exception {
        scenario.onActivity(activity -> {
            RecyclerView grid = activity.findViewById(R.id.result_evidence_grid);
            ((LinearLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        });
        instrumentation.waitForIdleSync();
        await(() -> {
            AtomicReference<Boolean> visible = new AtomicReference<>(false);
            scenario.onActivity(activity -> visible.set(hasVisiblePhotoCell(activity, position)));
            return visible.get();
        }, "目标照片应能通过本页照片列表滚动到达");
        drainLocalDecode();
    }

    private static void assertPhotoLayout(ResultActivity activity) {
        RecyclerView photos = activity.findViewById(R.id.result_evidence_grid);
        assertTrue("采集照片应采用线性列表", photos.getLayoutManager() instanceof LinearLayoutManager);
        LinearLayoutManager layout = (LinearLayoutManager) photos.getLayoutManager();
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            assertEquals("横屏采集照片应横向排列", RecyclerView.HORIZONTAL, layout.getOrientation());
            assertFalse("横屏采集照片不能向下滑动", photos.canScrollVertically(1));
            assertFalse("横屏采集照片不能向上滑动", photos.canScrollVertically(-1));
        } else {
            assertTrue("竖屏采集照片应采用网格", layout instanceof GridLayoutManager);
            assertEquals("竖屏采集照片每行应展示两张", 2, ((GridLayoutManager) layout).getSpanCount());
            assertEquals("竖屏采集照片应竖向排列", RecyclerView.VERTICAL, layout.getOrientation());
            assertFalse("竖屏采集照片不能向右滑动", photos.canScrollHorizontally(1));
            assertFalse("竖屏采集照片不能向左滑动", photos.canScrollHorizontally(-1));
        }
        assertTrue("照片区应有实际可用高度", photos.getHeight() > photos.getPaddingTop() + photos.getPaddingBottom());
        assertTrue("照片列表应完成实际卡片布局", photos.getChildCount() > 0);
        int available = photos.getHeight() - photos.getPaddingTop() - photos.getPaddingBottom();
        int columns = landscape ? 3 : 2;
        int gap = activity.getResources().getDimensionPixelSize(R.dimen.dp_6);
        int expectedWidth = (photos.getWidth() - photos.getPaddingLeft() - photos.getPaddingRight()
                - gap * (columns - 1)) / columns;
        for (int i = 0; i < photos.getChildCount(); i++) {
            View cell = photos.getChildAt(i);
            if (landscape) {
                assertEquals("横屏照片卡片宽度应按当前照片区均分", expectedWidth, cell.getWidth());
                assertEquals("横屏照片卡片应完整适应照片区高度", available, cell.getHeight());
                assertEquals("横屏照片卡片顶部不能越出列表", photos.getPaddingTop(), cell.getTop());
                assertEquals("横屏照片卡片底部不能越出列表", photos.getHeight() - photos.getPaddingBottom(), cell.getBottom());
            } else {
                assertTrue("竖屏照片两列应等宽铺满可用宽度", Math.abs(expectedWidth - cell.getWidth()) <= 1);
                ImageView image = cell.findViewById(R.id.result_photo_image);
                View frame = (View) image.getParent();
                TextView label = cell.findViewById(R.id.result_photo_label);
                assertEquals("竖屏照片区应保持4:3比例", Math.round(frame.getWidth() * 3f / 4f), frame.getHeight());
                assertTrue("竖屏照片标签应有自然文字高度", label.getHeight() > 0);
                assertTrue("竖屏照片卡片应按照片和标签自然高度排列",
                        Math.abs(frame.getHeight() + label.getHeight() - cell.getHeight()) <= 1);
                assertTrue("竖屏照片卡片左侧不能横向溢出", cell.getLeft() >= photos.getPaddingLeft());
                assertTrue("竖屏照片卡片右侧不能横向溢出", cell.getRight() <= photos.getWidth() - photos.getPaddingRight());
            }
        }
        TextView findings = activity.findViewById(R.id.result_findings_title);
        TextView title = activity.findViewById(R.id.result_evidence_title);
        assertNotNull("应保留异常明细标题", findings);
        assertNotNull("采集照片应有可见标题", title);
        assertEquals("异常明细与采集照片标题字号应一致", title.getTextSize(), findings.getTextSize(), 0.01f);
        assertEquals("异常明细与采集照片标题颜色应一致", title.getCurrentTextColor(), findings.getCurrentTextColor());
        assertEquals("异常明细与采集照片标题字重应一致", title.getTypeface().getStyle(), findings.getTypeface().getStyle());
        int oldUpload = activity.getResources().getIdentifier("result_upload", "id", activity.getPackageName());
        assertTrue("结果页应移除旧上传状态占位", oldUpload == 0 || activity.findViewById(oldUpload) == null);
    }

    private static ImageView photoImage(ResultActivity activity, int position) {
        return photoCell(activity, position).findViewById(R.id.result_photo_image);
    }

    private static void assertPhotoIdentity(ResultActivity activity, int position, int color, String label) {
        View cell = photoCell(activity, position);
        assertEquals("回收的照片卡片应保留当前索引标签", label,
                ((TextView) cell.findViewById(R.id.result_photo_label)).getText().toString());
        ImageView image = cell.findViewById(R.id.result_photo_image);
        assertTrue("回收的照片卡片不能显示另一采集项的色块", hasColor(image, color));
        assertTrue("可读照片应允许单张放大", cell.hasOnClickListeners());
        assertPhotoViewport(image);
    }

    private static void assertEmptyPhoto(ResultActivity activity, int position, String message) {
        View cell = photoCell(activity, position);
        ImageView image = cell.findViewById(R.id.result_photo_image);
        assertEmptyImage(message, image);
        assertFalse("缺失照片不能从图片打开查看器", image.hasOnClickListeners());
        assertFalse("缺失照片不能从卡片打开查看器", cell.hasOnClickListeners());
    }

    private static void assertNoAllPhotosNavigation(ResultActivity activity) {
        int oldId = activity.getResources().getIdentifier("result_evidence_all", "id", activity.getPackageName());
        assertTrue("全部照片跳转入口应从结果页移除", oldId == 0 || activity.findViewById(oldId) == null);
        TextView caption = activity.findViewById(R.id.result_evidence_caption);
        assertNotNull("照片区应显示非导航说明", caption);
        assertFalse("照片说明不应成为另一页入口", caption.hasOnClickListeners());
    }

    private static boolean hasColor(ImageView image, int expected) {
        if (image == null || !(image.getDrawable() instanceof BitmapDrawable) || !image.isShown()) return false;
        Rect visible = new Rect();
        if (!image.getGlobalVisibleRect(visible) || visible.isEmpty()) return false;
        Bitmap bitmap = ((BitmapDrawable) image.getDrawable()).getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return false;
        int actual = bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2);
        return Math.abs(Color.red(actual) - Color.red(expected)) < 15
                && Math.abs(Color.green(actual) - Color.green(expected)) < 15
                && Math.abs(Color.blue(actual) - Color.blue(expected)) < 15;
    }

    /** Android 11 的 setImageBitmap(null) 可保留空 BitmapDrawable，空图语义以是否有 bitmap 为准。 */
    private static void assertEmptyImage(String message, ImageView image) {
        assertTrue(message, image.getDrawable() == null
                || (image.getDrawable() instanceof BitmapDrawable
                && ((BitmapDrawable) image.getDrawable()).getBitmap() == null));
    }

    private static void assertActionsVisible(ResultActivity activity) {
        Rect root = new Rect();
        assertTrue(activity.findViewById(R.id.main_root).getGlobalVisibleRect(root));
        for (int id : new int[]{R.id.result_stay, R.id.result_next}) {
            View action = activity.findViewById(id);
            Rect visible = new Rect();
            assertTrue("操作按钮必须在屏幕内显示", action.getGlobalVisibleRect(visible));
            assertTrue("操作按钮不能被窗口裁切", root.contains(visible));
            assertEquals("操作按钮高度不能被父容器裁切", action.getHeight(), visible.height());
            assertEquals("操作按钮宽度不能被父容器裁切", action.getWidth(), visible.width());
            assertTrue("操作按钮应有实际可用高度", visible.height() > 0);
            assertTrue("操作按钮不能低于自身最小高度", visible.height() >= action.getMinimumHeight());
        }
    }

    /** 有照片不只检查 drawable；证据必须占有用户能看见的实际画面区域。 */
    private static void assertPhotoViewport(ImageView image) {
        Rect visible = new Rect();
        assertTrue("已解码证据应在当前窗口可见", image.getGlobalVisibleRect(visible));
        float density = image.getResources().getDisplayMetrics().density;
        assertTrue("照片视口不能被标题和操作区挤成细线", visible.width() >= 24 * density && visible.height() >= 24 * density);
    }

    /** 异常列表允许滚动，但初始可见区域至少应能完整显示第一行文字。 */
    private static void assertFindingsViewport(ResultActivity activity) {
        TextView findings = activity.findViewById(R.id.result_findings);
        if (activity.currentRecord.optInt("status") == 1) {
            assertEquals("正常结果不应额外占用异常明细区域", View.GONE,
                    activity.findViewById(R.id.result_findings_scroll).getVisibility());
            assertEquals("正常结果不应展示异常明细标题", View.GONE,
                    activity.findViewById(R.id.result_findings_title).getVisibility());
            return;
        }
        Rect visible = new Rect();
        assertTrue("检查说明必须有可见区域", findings.getLocalVisibleRect(visible));
        assertNotNull("检查说明应已完成文字排版", findings.getLayout());
        assertTrue("检查说明必须包含一行以上文字", findings.getLayout().getLineCount() > 0);
        int firstLineHeight = findings.getLayout().getLineBottom(0) - findings.getLayout().getLineTop(0);
        assertTrue("检查说明不能被照片挤成零高或半行", visible.height() >= firstLineHeight);
        assertTrue("检查说明视口必须有宽度", visible.width() > 0);
    }

    private static void assertDetachedBounds(ViewGroup root, View child) {
        assertNotNull("布局中必须存在操作入口", child);
        Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, bounds);
        assertTrue("操作按钮应有实际绘制面积", bounds.width() > 0 && bounds.height() > 0);
        assertTrue("静态晨检布局不能把操作按钮排到屏幕外", new Rect(0, 0, root.getWidth(), root.getHeight()).contains(bounds));
    }

    private static void measureDetached(View view, int width, int height) {
        assertTrue("用于布局验证的真实窗口必须有可用面积", width > 0 && height > 0);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    /** 离线树不会收到系统栏 Insets，复制宿主实际留白，按用户真正可用的内容区域检查容量。 */
    private static void copyWindowInsets(View layout, View window) {
        View root = layout.findViewById(R.id.main_root);
        if (root != null) root.setPadding(window.getPaddingLeft(), window.getPaddingTop(),
                window.getPaddingRight(), window.getPaddingBottom());
    }

    private static String describeCauses(Throwable error) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (result.length() > 0) result.append(" → ");
            result.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
        }
        return result.toString();
    }

    private static TextView firstVisibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView text = firstVisibleText(group.getChildAt(index));
                if (text != null) return text;
            }
        }
        return null;
    }

    /** 使用文本真实行盒与全部父级裁切区域判断，不把有尺寸但被父级截掉的文字误当可见。 */
    private static void assertDetachedFirstLineVisible(ViewGroup root, TextView text, String label, boolean complete) {
        assertNotNull(label + " 文字控件必须存在", text);
        Layout layout = text.getLayout();
        assertNotNull(label + " 必须完成文字排版", layout);
        assertTrue(label + " 不能是空文字", text.getText().length() > 0 && layout.getLineCount() > 0);
        if (complete) assertEquals(label + " 的核心入口名称不能省略", 0, layout.getEllipsisCount(0));
        Rect line = new Rect(
                text.getCompoundPaddingLeft() + (int) Math.floor(layout.getLineLeft(0)),
                text.getTotalPaddingTop() + layout.getLineTop(0),
                text.getCompoundPaddingLeft() + (int) Math.ceil(layout.getLineRight(0)),
                text.getTotalPaddingTop() + layout.getLineBottom(0));
        assertTrue(label + " 首行必须有实际面积", !line.isEmpty());
        assertTrue(label + " 首行不能被文字控件自身裁切",
                new Rect(0, 0, text.getWidth(), text.getHeight()).contains(line));
        assertDetachedRectVisible(root, text, line, label);
    }

    private static void assertDetachedRectVisible(ViewGroup root, View child, Rect local, String label) {
        Rect required = new Rect(local);
        root.offsetDescendantRectToMyCoords(child, required);
        assertTrue(label + " 必须位于当前屏幕内", new Rect(0, 0, root.getWidth(), root.getHeight()).contains(required));
        for (View current = child; current != root; ) {
            assertEquals(label + " 的控件及父级必须可见", View.VISIBLE, current.getVisibility());
            assertTrue(label + " 不能被透明度隐藏", current.getAlpha() > 0);
            assertTrue(label + " 必须位于被检查的页面树内", current.getParent() instanceof ViewGroup);
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent.getClipChildren() || parent.getClipToPadding()) {
                Rect available = parent.getClipToPadding()
                        ? new Rect(parent.getPaddingLeft(), parent.getPaddingTop(),
                        parent.getWidth() - parent.getPaddingRight(), parent.getHeight() - parent.getPaddingBottom())
                        : new Rect(0, 0, parent.getWidth(), parent.getHeight());
                if (parent != root) root.offsetDescendantRectToMyCoords(parent, available);
                assertTrue(label + " 不能被父容器裁切：" + parent.getClass().getSimpleName(), available.contains(required));
            }
            current = parent;
        }
    }

    /** 截图仅存 App 缓存供主任务 run-as 导出，不把模拟色块写入业务相册。 */
    private void saveScreenshot(View root, String label) {
        File directory = new File(app.getCacheDir(), "ui-reshape-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File output = new File(directory, label + "-" + root.getWidth() + "x" + root.getHeight() + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (Exception error) { throw new AssertionError("测试截图保存失败", error); }
        finally { bitmap.recycle(); }
    }

    private <T> T onIo(Callable<T> operation) throws Exception {
        return app.io.submit(operation).get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message);
    }
}
