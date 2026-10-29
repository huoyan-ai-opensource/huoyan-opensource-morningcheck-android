package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.data.RecordPhotos;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
import cc.huoyan.opensource.morningcheck.hardware.TemperatureSource;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;

/** 人工 RGB 图只进入独立数据库与本机 SDK 夹具，验证实际保存的异常框和完整页面链路。 */
@RunWith(AndroidJUnit4.class)
public final class CheckEvidenceCaptureTest {
    private static final int WIDTH = 640, HEIGHT = 480;
    private static final int FACE_COLOR = Color.rgb(70, 100, 120);
    private static final int RING_COLOR = Color.rgb(100, 130, 150);
    private static final int WOUND_COLOR = Color.rgb(90, 155, 120);
    private static final int NORMAL_COLOR = Color.rgb(160, 180, 200);
    private static final long PERSON_ID = 91;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicInteger detectionsReplied = new AtomicInteger(), identifyRequests = new AtomicInteger(),
            faceRequests = new AtomicInteger();
    private final CopyOnWriteArrayList<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final List<JSONObject> ownedRecords = new ArrayList<>();
    private MorningApplication app;
    private SharedPreferences preferences;
    private Map<String, Object> originalPreferences;
    private AppStore originalStore, testStore;
    private String databaseName;
    private MockWebServer endpoint;
    private ActivityScenario<CheckActivity> scenario;
    private CheckActivity checkActivity;
    private Instrumentation.ActivityMonitor resultMonitor;
    private Instrumentation.ActivityMonitor photoMonitor;
    private ResultActivity resultActivity;
    private RecordPhotoActivity photoActivity;
    private CheckPipeline pipeline;
    private volatile long sdkPersonId;
    private volatile JSONArray nextBoxes = new JSONArray();

    @Before public void isolateHardwareCloudAndRecords() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("设备需预先授权相机，测试不弹权限窗口",
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        onMain(() -> {
            assertFalse("不能打断在途门店登录", app.shopLogin().getState().isRunning());
            app.sdkSession().disconnect();
            return null;
        });
        onIo(() -> null);
        databaseName = "check-evidence-test-" + UUID.randomUUID() + ".db";
        Constructor<AppStore> constructor = AppStore.class.getDeclaredConstructor(Context.class, String.class);
        constructor.setAccessible(true);
        testStore = constructor.newInstance(app, databaseName);
        onMain(() -> { originalStore = app.store; app.store = testStore; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    assertEquals("只允许本机 SDK 通道，不允许业务云请求", "/sdk", request.getPath());
                    return new MockResponse().withWebSocketUpgrade(new SdkFixture());
                } catch (Throwable error) {
                    serverFailure.compareAndSet(null, error);
                    return new MockResponse().setResponseCode(403);
                }
            }
        });
        endpoint.start();
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("face_camera", "usb:EVIDENCE-TEST-NO-FACE-CAMERA")
                .putString("hand_camera", "usb:EVIDENCE-TEST-NO-HAND-CAMERA")
                .putString("serial_path", "/evidence-test-no-serial/" + UUID.randomUUID())
                .putBoolean("voice", false).putBoolean("temperature", true).putBoolean("head", true)
                .putBoolean("hat", true).putBoolean("mask", true)
                .putString("temperature_limit", "37.3")
                .putString("cloud_url", "").putString("cloud_token", "").commit());
        onIo(() -> {
            testStore.saveStaff(new JSONObject().put("id", PERSON_ID).put("staff_no", "EVIDENCE-" + PERSON_ID).put("name", "隔离证据测试员工")
                    .put("department", "本机测试").put("health_expiry", LocalDate.now().plusYears(2).toString())
                    .put("enabled", true));
            sdkPersonId = testStore.staff(PERSON_ID).getLong("person_id");
            assertTrue("SDK 夹具须使用独立人员标识，业务采样仍关联本地员工", sdkPersonId > 0 && sdkPersonId != PERSON_ID);
            return null;
        });
        resultMonitor = instrumentation.addMonitor(ResultActivity.class.getName(), null, false);
    }

    @After public void cleanOnlyOwnUuidEvidenceAndRestoreStore() throws Exception {
        try {
            if (photoActivity != null) onMain(() -> { photoActivity.finish(); return null; });
            if (resultActivity != null) onMain(() -> { resultActivity.finish(); return null; });
            if (scenario != null) scenario.close();
            if (app != null && originalStore != null) {
                onMain(() -> { app.sdkSession().disconnect(); return null; });
                onIo(() -> null);
                instrumentation.waitForIdleSync();
                onIo(() -> {
                    // 相册为应用共享目录，只有独立库中本用例真正保存的 UUID 文件可以删除。
                    for (JSONObject record : testStore.recordsPage(0, 1, 20).rows) {
                        if (ownedRecords.stream().noneMatch(item -> item.optString("id").equals(record.optString("id"))))
                            ownedRecords.add(record);
                    }
                    for (JSONObject record : ownedRecords) {
                        String id = record.optString("id");
                        assertEquals("清理范围必须来自本用例的 UUID 记录", id, UUID.fromString(id).toString());
                        Set<String> files = new HashSet<>();
                        for (RecordPhotos.Photo photo : RecordPhotos.read(record)) if (!photo.file.isEmpty()) files.add(photo.file);
                        for (String key : new String[]{"face_img_file", "back_hand_img_file", "front_hand_img_file"})
                            if (!record.optString(key).isEmpty()) files.add(record.optString(key));
                        for (String name : files) {
                            assertTrue("不得删除本记录以外的照片", name.startsWith(id + "-"));
                            File file = testStore.photo(name);
                            assertTrue("仅清理本用例保存的精确文件", !file.exists() || file.delete());
                        }
                        testStore.getWritableDatabase().delete("records", "id=?", new String[]{id});
                    }
                    return null;
                });
            }
        } finally {
            if (resultMonitor != null) instrumentation.removeMonitor(resultMonitor);
            if (photoMonitor != null) instrumentation.removeMonitor(photoMonitor);
            if (app != null && originalStore != null) onMain(() -> { app.store = originalStore; return null; });
            if (testStore != null) testStore.close();
            if (databaseName != null) assertTrue("清理独立测试数据库", app.deleteDatabase(databaseName));
            if (preferences != null && originalPreferences != null) restore(preferences, originalPreferences);
            for (WebSocket socket : sockets) socket.close(1001, "证据测试结束");
            if (endpoint != null) endpoint.shutdown();
        }
        assertNull("本机 SDK 夹具不能出现协议错误", serverFailure.get());
        assertTrue("正常身份流程必须提交 identify 请求", identifyRequests.get() > 0);
    }

    @Test public void earlyRingEvidenceSurvivesLaterNormalFrameAndFaceHasWatermarks() throws Exception {
        launchAndCollectHead();
        feed(handBoxes(DetectionClasses.HAND_BACK, DetectionClasses.HAND_RING), RING_COLOR);
        for (int i = 0; i < 4; i++) feed(handBoxes(DetectionClasses.HAND_BACK), NORMAL_COLOR);
        assertEquals(CheckPipeline.Stage.HAND_PALM, onMain(pipeline::getStage));
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_PALM), NORMAL_COLOR);
        JSONObject record = waitForSavedResult("异常结果");
        assertTrue(record.optJSONArray("findings").toString().contains("手背：戒指"));
        assertEquals(1, record.optInt("abnormal_hand"));
        Bitmap back = decode(record.optString("back_hand_img_file"));
        try {
            assertBackground(back, RING_COLOR);
            assertRingAnnotation(back);
        } finally { back.recycle(); }
        Bitmap face = decode(record.optString("face_img_file"));
        try {
            assertBackground(face, FACE_COLOR);
            assertTrue("保存 JPEG 左上应有时间水印", differentPixels(face, new Rect(0, 0, 300, 100), FACE_COLOR) > 150);
            assertTrue("保存 JPEG 右上应有温度水印", differentPixels(face, new Rect(400, 0, WIDTH, 100), FACE_COLOR) > 80);
        } finally { face.recycle(); }
        assertEquals("36.6", record.optString("temperature"));
        assertFalse("正常身份结果不应带 identity_skipped", record.optBoolean("identity_skipped", false));
        saveResultScreenshot("异常结果");
    }

    @Test public void rejectedPoseAndPauseClearTheirRingEvidenceBeforeNormalCompletion() throws Exception {
        launchAndCollectHead();
        feed(handBoxes(DetectionClasses.HAND_BACK, DetectionClasses.HAND_RING), RING_COLOR);
        feed(handBoxes(DetectionClasses.HAND_BACK, DetectionClasses.HAND_INCORRECT), RING_COLOR);
        assertEquals("姿态错误必须打断连续采集", 0, onMain(pipeline::getProgress).intValue());
        feed(handBoxes(DetectionClasses.HAND_BACK, DetectionClasses.HAND_RING), RING_COLOR);
        scenario.onActivity(activity -> activity.findViewById(R.id.check_pause).performClick());
        assertEquals("暂停必须清理当前组", 0, onMain(pipeline::getProgress).intValue());
        scenario.onActivity(activity -> activity.findViewById(R.id.check_pause).performClick());
        feed(handBoxes(DetectionClasses.HAND_BACK, DetectionClasses.HAND_RING), RING_COLOR);
        scenario.onActivity(CheckActivity::onCameraError);
        assertEquals("相机中断必须清理当前组采样和异常照片", 0, onMain(pipeline::getProgress).intValue());
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_BACK), NORMAL_COLOR);
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_PALM), NORMAL_COLOR);
        JSONObject record = waitForSavedResult("正常结果");
        assertFalse("被打断的戒指证据不能进入下一组结论", record.optJSONArray("findings").toString().contains("戒指"));
        assertEquals(0, record.optInt("abnormal_hand"));
        assertEquals(1, record.optInt("status"));
        for (RecordPhotos.Photo photo : RecordPhotos.read(record)) {
            assertFalse("不能额外保存被清掉的戒指证据", photo.label.contains("戒指"));
            if (photo.file.equals(record.optString("back_hand_img_file"))) {
                Bitmap back = decode(photo.file);
                try {
                    assertBackground(back, NORMAL_COLOR);
                    assertEquals("正常手背不应出现异常红框", 0, redPixels(back, new Rect(110, 235, 245, 330)));
                } finally { back.recycle(); }
            }
        }
        saveResultScreenshot("正常结果");
    }

    @Test public void separatePalmAbnormalitiesSaveOwnFramesAndShowAllEvidenceInline() throws Exception {
        launchAndCollectHead();
        for (int i = 0; i < 5; i++) feed(handBoxes(DetectionClasses.HAND_BACK), NORMAL_COLOR);
        feed(handBoxes(DetectionClasses.HAND_PALM, DetectionClasses.HAND_RING), RING_COLOR);
        JSONArray woundBoxes = handBoxes(DetectionClasses.HAND_PALM, DetectionClasses.HAND_WOUND);
        woundBoxes.getJSONObject(2).put("x", .68).put("y", .42).put("w", .12).put("h", .18);
        feed(woundBoxes, WOUND_COLOR);
        for (int i = 0; i < 3; i++) feed(handBoxes(DetectionClasses.HAND_PALM), NORMAL_COLOR);
        JSONObject record = waitForSavedResult("手心跨帧异常结果");
        String findings = record.optJSONArray("findings").toString();
        assertTrue("第一有效手心帧的戒指必须保留", findings.contains("手心：戒指"));
        assertTrue("后续另一帧的伤口必须保留", findings.contains("手心：伤口"));
        JSONArray captured = record.getJSONArray("captured_photos");
        JSONObject ringPhoto = null, woundPhoto = null;
        int palmPhotos = 0;
        for (int i = 0; i < captured.length(); i++) {
            JSONObject photo = captured.getJSONObject(i);
            if (!"palm".equals(photo.optString("stage"))) continue;
            palmPhotos++;
            JSONArray classes = photo.getJSONArray("class_ids");
            assertEquals("独立帧只标记本帧出现的异常", 1, classes.length());
            if (classes.getInt(0) == DetectionClasses.HAND_RING) ringPhoto = photo;
            if (classes.getInt(0) == DetectionClasses.HAND_WOUND) woundPhoto = photo;
        }
        assertEquals("不同帧的两种异常应保存主图和补充图", 2, palmPhotos);
        assertNotNull("戒指补充证据应具有来源元数据", ringPhoto);
        assertNotNull("伤口代表证据应具有来源元数据", woundPhoto);
        assertTrue(ringPhoto.getString("label").contains("戒指"));
        assertTrue(woundPhoto.getString("label").contains("伤口"));
        assertNotEquals("两种异常不能共用不同帧的坐标", ringPhoto.getString("file"), woundPhoto.getString("file"));
        assertEquals("异常种类数相同时用更新异常帧作为代表图", woundPhoto.getString("file"), record.getString("front_hand_img_file"));
        Bitmap ring = decode(ringPhoto.getString("file"));
        try { assertBackground(ring, RING_COLOR); assertRingAnnotation(ring); }
        finally { ring.recycle(); }
        Bitmap wound = decode(woundPhoto.getString("file"));
        try {
            assertBackground(wound, WOUND_COLOR);
            assertTrue("伤口左边框必须落在伤口自己的位置", redPixels(wound, new Rect(430, 200, 441, 289)) > 60);
            assertTrue("伤口右边框必须落在伤口自己的位置", redPixels(wound, new Rect(506, 200, 517, 289)) > 60);
            assertTrue("伤口标签应在伤口框附近", differentPixels(wound, new Rect(425, 150, 540, 202), WOUND_COLOR) > 100);
            assertEquals("伤口帧不能画入另一帧的戒指框", 0, redPixels(wound, new Rect(123, 264, 236, 323)));
        } finally { wound.recycle(); }
        copySavedJpeg(record.getString("face_img_file"), "face-watermark.jpg");
        copySavedJpeg(record.getString("back_hand_img_file"), "hand-back-normal.jpg");
        copySavedJpeg(woundPhoto.getString("file"), "hand-palm-wound.jpg");
        copySavedJpeg(ringPhoto.getString("file"), "hand-palm-ring-extra.jpg");
        List<RecordPhotos.Photo> allPhotos = RecordPhotos.read(record);
        int ringIndex = photoIndex(allPhotos, ringPhoto.getString("file"));
        int woundIndex = photoIndex(allPhotos, woundPhoto.getString("file"));
        onMain(() -> {
            RecyclerView list = resultActivity.findViewById(R.id.result_evidence_grid);
            assertNotNull("结果页应直接展示全部照片列表", list);
            assertEquals("主图及补充图应全部在同一结果页", allPhotos.size(), list.getAdapter().getItemCount());
            assertTrue(list.getLayoutManager() instanceof LinearLayoutManager);
            if (resultActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                assertEquals("横屏结果照片应横向浏览", RecyclerView.HORIZONTAL,
                        ((LinearLayoutManager) list.getLayoutManager()).getOrientation());
                assertFalse("横屏结果照片不能上下滚动", list.canScrollVertically(-1) || list.canScrollVertically(1));
            } else {
                assertTrue("竖屏结果照片应使用网格", list.getLayoutManager() instanceof GridLayoutManager);
                assertEquals(2, ((GridLayoutManager) list.getLayoutManager()).getSpanCount());
                assertEquals(RecyclerView.VERTICAL, ((GridLayoutManager) list.getLayoutManager()).getOrientation());
                assertFalse("竖屏结果照片不能左右滚动", list.canScrollHorizontally(-1) || list.canScrollHorizontally(1));
            }
            int oldId = app.getResources().getIdentifier("result_evidence_all", "id", app.getPackageName());
            assertTrue("不应再出现全部照片跳转入口", oldId == 0 || resultActivity.findViewById(oldId) == null);
            TextView caption = resultActivity.findViewById(R.id.result_evidence_caption);
            assertNotNull(caption);
            assertFalse("照片数量说明不应跳转另一页面", caption.hasOnClickListeners());
            return null;
        });

        // 补充图只需在本页滚动即可到达，不能仍依赖查看器的下一张按钮才能看到。
        for (int index : new int[]{woundIndex, ringIndex}) {
            selectInlinePhoto(index);
            final boolean isRing = index == ringIndex;
            onMain(() -> {
                View cell = inlinePhotoCell(index);
                TextView label = cell.findViewById(R.id.result_photo_label);
                assertTrue("本页缩略图应标明具体异常", label.getText().toString().contains(isRing ? "戒指" : "伤口"));
                ImageView image = cell.findViewById(R.id.result_photo_image);
                Bitmap shown = ((BitmapDrawable) image.getDrawable()).getBitmap();
                int actual = shown.getPixel(shown.getWidth() * 600 / WIDTH, shown.getHeight() * 430 / HEIGHT);
                assertTrue("本页补充缩略图必须来自实际对应异常帧",
                        colorDistance(actual, isRing ? RING_COLOR : WOUND_COLOR) < 35);
                assertInlineResultControlsVisible();
                return null;
            });
        }
        selectInlinePhoto(0);
        saveResultScreenshot("手心跨帧异常结果");

        photoMonitor = instrumentation.addMonitor(RecordPhotoActivity.class.getName(), null, false);
        selectInlinePhoto(ringIndex);
        onMain(() -> {
            assertTrue("直接点击本页戒指补充图应允许单张放大", inlinePhotoCell(ringIndex).performClick());
            return null;
        });
        photoActivity = (RecordPhotoActivity) instrumentation.waitForMonitorWithTimeout(photoMonitor, 10_000);
        assertNotNull("单张点击仍应进入独立照片放大 Activity", photoActivity);
        assertEquals(record.getString("id"), photoActivity.getIntent().getStringExtra(RecordDetailActivity.EXTRA_RECORD_ID));
        await(() -> onMain(() -> {
            RecyclerView pager = photoActivity.findViewById(R.id.photo_pager);
            if (pager == null || pager.isLayoutRequested() || pager.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
                return false;
            RecyclerView.ViewHolder holder = pager.findViewHolderForAdapterPosition(ringIndex);
            if (holder == null || pager.getLayoutManager().getDecoratedLeft(holder.itemView) != pager.getPaddingLeft())
                return false;
            ImageView image = holder.itemView.findViewById(R.id.photo_image);
            return (Integer) field(photoActivity, "selected") == ringIndex && image.getDrawable() instanceof BitmapDrawable
                    && holder.itemView.findViewById(R.id.photo_empty).getVisibility() == View.GONE;
        }), "单张放大应直接读取被点击的戒指补充图");
        onMain(() -> {
            RecyclerView pager = photoActivity.findViewById(R.id.photo_pager);
            RecyclerView.ViewHolder holder = pager.findViewHolderForAdapterPosition(ringIndex);
            assertNotNull("戒指补充图应保留在当前滚动页", holder);
            ImageView image = holder.itemView.findViewById(R.id.photo_image);
            assertTrue("补充图查看器应明确注明戒指异常", image.getContentDescription().toString().contains("戒指"));
            assertTrue(image.getDrawable() instanceof BitmapDrawable);
            Bitmap shown = ((BitmapDrawable) image.getDrawable()).getBitmap();
            assertBackground(shown, RING_COLOR);
            assertRingAnnotation(shown);
            saveScreenshot(photoActivity.findViewById(R.id.main_root), "手心补充戒指查看器");
            return null;
        });
    }

    private static int photoIndex(List<RecordPhotos.Photo> photos, String file) {
        for (int i = 0; i < photos.size(); i++) if (file.equals(photos.get(i).file)) return i;
        throw new AssertionError("保存的异常证据必须出现在 RecordPhotos 的同一清单中");
    }

    private View inlinePhotoCell(int position) {
        RecyclerView list = resultActivity.findViewById(R.id.result_evidence_grid);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        assertNotNull("异常照片应能在本页横向列表到达：" + position, holder);
        return holder.itemView;
    }

    private void selectInlinePhoto(int position) throws Exception {
        onMain(() -> {
            RecyclerView list = resultActivity.findViewById(R.id.result_evidence_grid);
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, 0);
            return null;
        });
        instrumentation.waitForIdleSync();
        await(() -> onMain(() -> {
            RecyclerView list = resultActivity.findViewById(R.id.result_evidence_grid);
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
            if (holder == null) return false;
            ImageView image = holder.itemView.findViewById(R.id.result_photo_image);
            Rect visible = new Rect();
            return image.getDrawable() instanceof BitmapDrawable
                    && ((BitmapDrawable) image.getDrawable()).getBitmap() != null
                    && image.getGlobalVisibleRect(visible) && visible.height() > 0;
        }), "本页横向照片列表应显示并解码目标异常证据");
    }

    private void assertInlineResultControlsVisible() {
        Rect root = new Rect();
        assertTrue(resultActivity.findViewById(R.id.main_root).getGlobalVisibleRect(root));
        for (int id : new int[]{R.id.result_stay, R.id.result_next}) {
            View action = resultActivity.findViewById(id);
            Rect visible = new Rect();
            assertTrue("浏览全部照片时操作按钮仍应可见", action.getGlobalVisibleRect(visible));
            assertTrue(root.contains(visible));
            assertEquals("照片滚动不能裁切操作按钮", action.getHeight(), visible.height());
            assertTrue("操作按钮应保留触控高度", visible.height() >= 44 * app.getResources().getDisplayMetrics().density);
        }
        TextView findings = resultActivity.findViewById(R.id.result_findings);
        Rect visible = new Rect();
        assertTrue("浏览全部照片时异常明细仍应可见", findings.getLocalVisibleRect(visible));
        assertNotNull(findings.getLayout());
        assertTrue("异常明细至少应完整显示首行",
                visible.height() >= findings.getLayout().getLineBottom(0) - findings.getLayout().getLineTop(0));
    }

    private void launchAndCollectHead() throws Exception {
        scenario = ActivityScenario.launch(new Intent(app, CheckActivity.class));
        await(() -> onMain(() -> app.sdkSession().viewState().ready), "应连接本机 SDK 夹具");
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> ready.set(activity.loaded && field(activity, "pipeline") != null
                    && activity.findViewById(R.id.check_pause) != null));
            return ready.get();
        }, "现场晨检页面应进入自动流程");
        for (int sample = 1; sample <= 4; sample++) {
            scenario.onActivity(activity -> {
                checkActivity = activity;
                pipeline = (CheckPipeline) field(activity, "pipeline");
                activity.stopCamera();
                assertNull("测试只能消费人工 RGB 帧", activity.camera);
                activity.onCameraFrame(frame(FACE_COLOR));
            });
            final int expected = sample;
            await(() -> identifyRequests.get() >= expected
                            && onMain(() -> !(Boolean) field(checkActivity, "frameBusy")),
                    "identify 回执及员工档案应完成第 " + sample + " 次身份采样");
            if (sample < 4) assertFalse("连续身份样本不足不能确认员工",
                    onMain(pipeline::isIdentityConfirmed));
        }
        await(() -> onMain(pipeline::isIdentityConfirmed),
                "四次 identify 回执匹配员工后应完成身份采样");
        scenario.onActivity(activity -> {
            TemperatureSource source = (TemperatureSource) field(activity, "thermometer");
            ((TemperatureSource.Listener) field(source, "listener")).onTemperature(36.6);
            assertEquals(CheckPipeline.Stage.HEAD, pipeline.getStage());
        });
        for (int i = 0; i < 4; i++) feed(new JSONArray().put(box(DetectionClasses.HEAD_HAT, .2, .1, .6, .2))
                .put(box(DetectionClasses.HEAD_MASK, .25, .35, .5, .3)), FACE_COLOR);
        assertEquals(CheckPipeline.Stage.HAND_BACK, onMain(pipeline::getStage));
        assertEquals("头检四次采样先检测人脸，不再重识别戴口罩的员工", 4, identifyRequests.get());
        assertEquals(4, faceRequests.get());
    }

    private void feed(JSONArray boxes, int background) {
        int expectedReplies = detectionsReplied.get() + 1;
        nextBoxes = boxes;
        scenario.onActivity(activity -> activity.onCameraFrame(frame(background)));
        await(() -> detectionsReplied.get() >= expectedReplies && onMain(() ->
                pipeline.getStage() == CheckPipeline.Stage.RESULT || !(Boolean) field(checkActivity, "frameBusy")),
                "SDK 本帧回执应完成处理");
    }

    private JSONObject waitForSavedResult(String label) throws Exception {
        await(() -> {
            try { return onIo(() -> testStore.recordsPage(0, 1, 20).totalRows) == 1; }
            catch (Exception error) { throw new AssertionError(error); }
        }, "实际照片和完成记录应保存到独立数据库");
        JSONObject record = onIo(() -> testStore.recordsPage(0, 1, 20).rows.get(0));
        ownedRecords.add(record);
        resultActivity = (ResultActivity) instrumentation.waitForMonitorWithTimeout(resultMonitor, 10_000);
        assertNotNull(label + "应自动打开独立结果 Activity", resultActivity);
        await(() -> onMain(() -> resultActivity.loaded && resultActivity.currentRecord != null
                && resultActivity.findViewById(R.id.result_stay) != null), "结果页应读到刚完成的本地记录");
        onMain(() -> { resultActivity.findViewById(R.id.result_stay).performClick(); return null; });
        assertFalse("正常身份记录不应保留跳过身份标记", record.has("identity_skipped"));
        assertEquals("未上传", record.optString("upload"));
        return record;
    }

    private Bitmap decode(String name) throws Exception {
        Bitmap image = onIo(() -> BitmapFactory.decodeFile(testStore.photo(name).getAbsolutePath()));
        assertNotNull("真正保存的 JPEG 必须可读取", image);
        assertEquals(WIDTH, image.getWidth());
        assertEquals(HEIGHT, image.getHeight());
        return image;
    }

    private static void assertRingAnnotation(Bitmap image) {
        assertTrue("戒指框左边应落在其原始归一化坐标", redPixels(image, new Rect(123, 262, 134, 322)) > 50);
        assertTrue("戒指框右边应落在其原始归一化坐标", redPixels(image, new Rect(225, 262, 236, 322)) > 50);
        assertTrue("戒指位置附近应有异常文字标签", differentPixels(image, new Rect(110, 210, 270, 264), RING_COLOR) > 100);
        assertEquals("保存照片不得沿用前摄镜像后的框", 0, redPixels(image, new Rect(400, 262, 420, 322)));
    }

    private static void assertBackground(Bitmap image, int expected) {
        int actual = image.getPixel(600, 430);
        assertTrue("标注照片必须保留实际异常帧而非最后正常帧", colorDistance(actual, expected) < 35);
    }

    private static int differentPixels(Bitmap image, Rect region, int color) {
        int count = 0;
        for (int y = region.top; y < region.bottom; y++) for (int x = region.left; x < region.right; x++)
            if (colorDistance(image.getPixel(x, y), color) > 70) count++;
        return count;
    }

    private static int colorDistance(int first, int second) {
        return Math.abs(Color.red(first) - Color.red(second)) + Math.abs(Color.green(first) - Color.green(second))
                + Math.abs(Color.blue(first) - Color.blue(second));
    }

    private static int redPixels(Bitmap image, Rect region) {
        int count = 0;
        for (int y = region.top; y < region.bottom; y++) for (int x = region.left; x < region.right; x++) {
            int color = image.getPixel(x, y);
            if (Color.red(color) > 150 && Color.red(color) - Color.green(color) > 70
                    && Color.red(color) - Color.blue(color) > 55) count++;
        }
        return count;
    }

    private void saveResultScreenshot(String label) throws Exception {
        onIo(() -> null);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            saveScreenshot(resultActivity.findViewById(R.id.main_root), label);
            return null;
        });
    }

    private void saveScreenshot(View root, String label) throws Exception {
        File directory = new File(app.getFilesDir(), "evidence-result-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(directory,
                label + "-" + root.getWidth() + "x" + root.getHeight() + ".png"))) {
            root.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    /** 仅复制本用例真正保存的 JPEG，便于导出目视核验；业务原图仍按精确 UUID 清理。 */
    private void copySavedJpeg(String file, String artifactName) throws Exception {
        onIo(() -> {
            File directory = new File(app.getFilesDir(), "evidence-result-qa");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileInputStream input = new FileInputStream(testStore.photo(file));
                 FileOutputStream output = new FileOutputStream(new File(directory, artifactName))) {
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) != -1;) output.write(bytes, 0, count);
            }
            return null;
        });
    }

    private static CameraSource.Frame frame(int color) {
        byte[] rgb = new byte[WIDTH * HEIGHT * 3];
        for (int offset = 0; offset < rgb.length; offset += 3) {
            rgb[offset] = (byte) Color.red(color);
            rgb[offset + 1] = (byte) Color.green(color);
            rgb[offset + 2] = (byte) Color.blue(color);
        }
        return new CameraSource.Frame(rgb, WIDTH, HEIGHT);
    }

    private static JSONArray handBoxes(int orientation, int... abnormalities) throws Exception {
        JSONArray boxes = new JSONArray().put(box(orientation, .05, .15, .35, .7))
                .put(box(orientation, .6, .15, .35, .7));
        for (int classId : abnormalities) boxes.put(box(classId, .2, .55, .16, .12));
        return boxes;
    }

    private static JSONObject box(int classId, double x, double y, double width, double height) throws Exception {
        return new JSONObject().put("class_id", classId).put("score", .95)
                .put("x", x).put("y", y).put("w", width).put("h", height);
    }

    private final class SdkFixture extends WebSocketListener {
        private final Map<String, JSONObject> images = new ConcurrentHashMap<>();
        @Override public void onOpen(WebSocket socket, Response response) { sockets.add(socket); }
        @Override public void onMessage(WebSocket socket, String text) {
            try {
                JSONObject request = new JSONObject(text);
                String command = request.getString("cmd");
                if ("identify".equals(command)) {
                    identifyRequests.incrementAndGet();
                    images.put(request.getString("requestId"), request);
                    return;
                }
                if ("detectFace".equals(command)) {
                    assertEquals(3, request.getJSONObject("params").getInt("max_faces"));
                    faceRequests.incrementAndGet();
                    images.put(request.getString("requestId"), request);
                    return;
                }
                if ("checkHead".equals(command) || "checkHand".equals(command)) {
                    images.put(request.getString("requestId"), request);
                    return;
                }
                assertTrue("测试禁止学习、删员工或写云端", "init".equals(command)
                        || "getStatus".equals(command) || "getCapabilities".equals(command)
                        || "get_class_table".equals(command) || "getCertInfo".equals(command)
                        || "getDownloadProgress".equals(command) || "getCloudStatus".equals(command));
                JSONObject data = new JSONObject();
                if ("getStatus".equals(command)) data.put("sdk_state", "READY").put("model_ready", true)
                        .put("certificate_valid", true).put("device_id", "EVIDENCE-TEST-DEVICE");
                reply(socket, request.getString("requestId"), data);
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
        }
        @Override public void onMessage(WebSocket socket, ByteString bytes) {
            try {
                ByteBuffer buffer = bytes.asByteBuffer();
                int length = buffer.getInt();
                assertTrue(length > 0 && length < buffer.remaining());
                byte[] rawId = new byte[length]; buffer.get(rawId);
                String id = new String(rawId, StandardCharsets.UTF_8);
                JSONObject request = images.remove(id);
                assertNotNull("二进制帧必须对应检测或身份声明", request);
                JSONObject params = request.optJSONObject("params");
                assertNotNull("图片请求必须有参数", params);
                assertEquals(WIDTH, params.getInt("width"));
                assertEquals(HEIGHT, params.getInt("height"));
                assertEquals(WIDTH * HEIGHT * 3, buffer.remaining());
                if ("identify".equals(request.optString("cmd"))) {
                    reply(socket, id, new JSONObject().put("matched", true).put("person_id", sdkPersonId));
                } else if ("detectFace".equals(request.optString("cmd"))) {
                    reply(socket, id, new JSONObject().put("faces", new JSONArray().put(
                            new JSONObject().put("x", .2).put("y", .1).put("w", .6).put("h", .7)
                                    .put("score", .95).put("landmarks", new JSONArray(new double[]{
                                            .3, .3, .6, .3, .45, .4, .3, .6, .6, .6})))));
                } else {
                    reply(socket, id, new JSONObject().put("boxes", new JSONArray(nextBoxes.toString())));
                    detectionsReplied.incrementAndGet();
                }
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
        }
        private void reply(WebSocket socket, String id, JSONObject data) throws Exception {
            assertTrue(socket.send(new JSONObject().put("requestId", id).put("code", 0)
                    .put("reason", "隔离图片证据验收").put("data", data).toString()));
        }
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) {}
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("不存在的测试状态：" + name);
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程证据验收失败", failure.get());
        return value.get();
    }

    private <T> T onIo(Callable<T> action) throws Exception { return app.io.submit(action).get(10, TimeUnit.SECONDS); }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        do {
            if (serverFailure.get() != null) throw new AssertionError("本机 SDK 夹具失败", serverFailure.get());
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
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
            else throw new AssertionError("未知设备设置类型");
        }
        assertTrue("应恢复测试前全部配置", editor.commit());
        assertEquals(original, preferences.getAll());
    }
}
