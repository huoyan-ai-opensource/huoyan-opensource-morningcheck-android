package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static cc.huoyan.opensource.morningcheck.RecyclerListTestHelper.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
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
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.opensource.morningcheck.camera.CameraImageOptions;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 在指定真机窗口验证 UI；使用独立数据库、回环端点和结束后恢复的配置。 */
@RunWith(AndroidJUnit4.class)
public final class DesignUiDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MorningApplication app;
    private AppStore originalStore, fixture;
    private SharedPreferences preferences;
    private Map<String, ?> originalPreferences;
    private MockWebServer endpoint;
    private String databaseName;
    private ActivityScenario<?> scenario;
    private long emptyDepartment;
    private final java.util.Set<String> textFailures = new java.util.LinkedHashSet<>();

    @Before public void isolate() throws Exception {
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        main(() -> {
            assertFalse(app.shopLogin().getState().isRunning());
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        app.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
        preferences = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        originalStore = app.store;
        databaseName = "design-ui-" + UUID.randomUUID() + ".db";
        fixture = new AppStore(new ContextWrapper(app) {
            @Override public File getDatabasePath(String name) { return app.getDatabasePath(databaseName); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return app.openOrCreateDatabase(databaseName, mode, factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return app.openOrCreateDatabase(databaseName, mode, factory, handler);
            }
        });
        main(() -> { app.store = fixture; return null; });
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(503).setBody("UI fixture: SDK offline");
            }
        });
        endpoint.start(InetAddress.getByName("127.0.0.2"), 0);
        assertTrue(preferences.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, "ws://127.0.0.2:" + endpoint.getPort() + "/sdk")
                .putString("cloud_url", "").putString("cloud_token", "").putBoolean("voice", false)
                .putString("face_camera", "usb:UI-DESIGN-NO-CAMERA").putString("hand_camera", "usb:UI-DESIGN-NO-CAMERA")
                .putString("light_mode", "serial").commit());
        app.io.submit(() -> {
            SQLiteDatabase database = fixture.getWritableDatabase();
            // 整批夹具共用一次事务提交，避免每名员工及记录分别触发磁盘同步。
            database.beginTransaction();
            try {
                long department = fixture.saveDepartment(0, "后厨");
                fixture.saveDepartment(0, "热菜组", department, null, 0);
                emptyDepartment = fixture.saveDepartment(0, "待分配部门");
                for (int i = 1; i <= 124; i++) {
                    fixture.saveStaff(new JSONObject().put("id", i).put("name", i == 1 ? "张晨" : "测试员工" + i)
                            .put("staff_no", "MC" + String.format(java.util.Locale.ROOT, "%03d", i))
                            .put("department", "后厨").put("department_id", department).put("enabled", true)
                            .put("health_expiry", "2027-10-14").put("face_registered", false));
                    fixture.saveRecord(new JSONObject().put("id", "ui-" + i).put("staff_id", i)
                            .put("staff_name", i == 1 ? "张晨" : "测试员工" + i).put("staff_no", "MC" + i)
                            .put("department", "后厨").put("created_at", System.currentTimeMillis() - i * 60000L)
                            .put("status", i % 2).put("temperature", 36.5).put("upload", "未上传")
                            .put("head_enabled", true).put("abnormal_face", 0).put("abnormal_hand", i % 2 == 0 ? 1 : 0)
                            .put("abnormal_temperature", 0).put("abnormal_health_cert_exp", 0)
                            .put("health_expiry", "2027-10-14").put("temperature_source", "UI 测试夹具"));
                }
                database.setTransactionSuccessful();
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { database.endTransaction(); }
        }).get(10, TimeUnit.SECONDS);
    }

    @After public void restore() throws Exception {
        if (app == null || originalStore == null) return;
        try {
            if (scenario != null) scenario.close();
            main(() -> { finishPages(); app.sdkSession().disconnect(); return null; });
            app.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertNotNull(app.io.submit(() -> fixture.staff(1)).get(10, TimeUnit.SECONDS));
            assertNotNull(app.io.submit(() -> fixture.department(emptyDepartment)).get(10, TimeUnit.SECONDS));
        } finally {
            main(() -> { app.store = originalStore; return null; });
            fixture.close();
            assertTrue(app.deleteDatabase(databaseName));
            SharedPreferences.Editor edit = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String) edit.putString(key, (String) value);
                else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) edit.putInt(key, (Integer) value);
                else if (value instanceof Long) edit.putLong(key, (Long) value);
                else if (value instanceof Float) edit.putFloat(key, (Float) value);
                else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
            }
            assertTrue(edit.commit());
            if (endpoint != null) endpoint.shutdown();
        }
    }



    @Test public void supplementaryPhotosKeepCaptureOrderAndOpenTheirOriginalPosition() throws Exception {
        org.json.JSONArray photos = new org.json.JSONArray();
        for (int i = 1; i <= 5; i++) photos.put(new JSONObject()
                .put("file", "").put("label", "采集顺序 " + i));
        app.io.submit(() -> fixture.patchRecord("ui-1", new JSONObject()
                .put("captured_photos", photos))).get(10, TimeUnit.SECONDS);
        RecordDetailActivity detail = launch(RecordDetailActivity.class);
        await(() -> count(detail.findViewById(R.id.record_detail_photos)) == 5);
        main(() -> {
            RecyclerView grid = detail.findViewById(R.id.record_detail_photos);
            assertEquals(2, ((GridLayoutManager) grid.getLayoutManager()).getSpanCount());
            scroll(grid, 4, 0);
            return null;
        });
        await(() -> row(detail.findViewById(R.id.record_detail_photos), 4) != null);
        main(() -> { click(detail.findViewById(R.id.record_detail_photos), 4); return null; });
        await(() -> resumed() instanceof RecordPhotoActivity);
        RecordPhotoActivity photo = main(() -> (RecordPhotoActivity) resumed());
        orient(photo);
        await(() -> "采集顺序 5".contentEquals(photo.<TextView>findViewById(R.id.photo_caption).getText()));
        main(() -> {
            assertFalse(photo.findViewById(R.id.photo_next).isEnabled());
            photo.findViewById(R.id.photo_previous).performClick(); return null;
        });
        await(() -> "采集顺序 4".contentEquals(photo.<TextView>findViewById(R.id.photo_caption).getText()));
        screenshot("record-supplementary-photo");
    }

    @Test public void departmentDeleteStillRequiresConfirmationAndCanBeCancelled() throws Exception {
        assertTrue(preferences.edit().putInt(AppFontSettings.KEY, 3).commit());
        DepartmentsActivity departments = launch(DepartmentsActivity.class);
        await(() -> {
            RecyclerView list = departments.findViewById(R.id.department_list);
            return list.isEnabled() && list.getChildCount() > 0
                    && count(list) > 0 && item(list, 0).optLong("parent_id") == 0;
        });
        main(() -> { assertDepartmentColumnsAligned(departments); return null; });
        screenshot("departments");
        main(() -> { departments.<EditText>findViewById(R.id.department_search).setText("热菜"); return null; });
        await(() -> {
            RecyclerView list = departments.findViewById(R.id.department_list);
            return list.isEnabled() && list.getChildCount() > 0
                    && count(list) > 0 && "热菜组".equals(item(list, 0).optString("name"));
        });
        main(() -> { assertDepartmentColumnsAligned(departments); return null; });
        screenshot("departments-child");
        main(() -> { departments.<EditText>findViewById(R.id.department_search).setText(""); return null; });
        await(() -> {
            RecyclerView list = departments.findViewById(R.id.department_list);
            return list.isEnabled() && list.getChildCount() > 0
                    && count(list) > 0 && item(list, 0).optLong("parent_id") == 0;
        });
        main(() -> { departments.findViewById(R.id.department_add).performClick(); return null; });
        await(() -> departmentEditor(departments) != null
                && departmentEditor(departments).findViewById(R.id.department_edit_name).isEnabled());
        screenshot("department-editor");
        main(() -> {
            android.app.Dialog dialog = departmentEditor(departments);
            visible(departments, dialog.findViewById(R.id.department_edit_parent));
            visible(departments, dialog.findViewById(R.id.department_edit_save));
            assertTrue(dialog.<EditText>findViewById(R.id.department_edit_name).getShowSoftInputOnFocus());
            return null;
        });
        main(() -> {
            android.app.Dialog dialog = departmentEditor(departments);
            visible(departments, dialog.findViewById(R.id.department_edit_save));
            dialog.findViewById(R.id.department_edit_cancel).performClick();
            return null;
        });
        await(() -> departmentEditor(departments) == null);
        main(() -> {
            RecyclerView list = departments.findViewById(R.id.department_list);
            for (int i = 0; i < list.getChildCount(); i++) {
                JSONObject value = item(list, list.getChildAdapterPosition(list.getChildAt(i)));
                if (value.optLong("id") == emptyDepartment) {
                    list.getChildAt(i).findViewById(R.id.department_row_delete).performClick(); return null;
                }
            }
            throw new AssertionError("Empty department row missing");
        });
        await(() -> confirmation(departments) != null && confirmation(departments).isShowing());
        assertNull("删除不能打开编辑弹窗", main(() -> departmentEditor(departments)));
        screenshot("department-delete-confirmation");
        main(() -> { confirmation(departments).getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); return null; });
        assertNotNull(app.io.submit(() -> fixture.department(emptyDepartment)).get(10, TimeUnit.SECONDS));
    }

    @Test public void lightingTabsKeepDraftsAndFixedSaveVisible() throws Exception {
        LightingSettingsActivity page = launch(LightingSettingsActivity.class);
        main(() -> { visible(page, page.findViewById(R.id.lighting_save)); return null; });
        screenshot("lighting-serial");
        String path = main(() -> page.<TextView>findViewById(R.id.lighting_path).getText().toString());
        main(() -> { page.findViewById(R.id.lighting_gpio_mode).performClick(); return null; });
        instrumentation.waitForIdleSync();
        main(() -> {
            assertTrue(page.findViewById(R.id.lighting_gpio_mode).isSelected());
            assertFalse(page.findViewById(R.id.lighting_serial_fields).isShown());
            visible(page, page.findViewById(R.id.lighting_gpio));
            visible(page, page.findViewById(R.id.lighting_save)); return null;
        });
        screenshot("lighting-gpio");
        main(() -> { visible(page, page.findViewById(R.id.lighting_test_off));
            visible(page, page.findViewById(R.id.lighting_save)); return null; });
        screenshot("lighting-gpio-actions");
        main(() -> { page.findViewById(R.id.lighting_serial_mode).performClick();
            assertEquals(path, page.<TextView>findViewById(R.id.lighting_path).getText().toString()); return null; });
        assertEquals("serial", preferences.getString("light_mode", ""));
        CameraSettingsActivity camera = launch(CameraSettingsActivity.class);
        main(() -> { camera.findViewById(R.id.settings_preview_hand).performClick(); return null; });
        instrumentation.waitForIdleSync();
        main(() -> {
            assertTrue(camera.<android.widget.RadioButton>findViewById(R.id.settings_preview_hand).isChecked());
            assertFalse(camera.<android.widget.RadioButton>findViewById(R.id.settings_preview_face).isChecked());
            return null;
        });
        screenshot("camera-hand");
        main(() -> { camera.findViewById(R.id.settings_preview_face).performClick(); return null; });
        instrumentation.waitForIdleSync();
        screenshot("camera-face");
        FaceEnrollmentActivity enrollment = launch(FaceEnrollmentActivity.class);
        screenshot("face-selection");
        main(() -> { enrollment.findViewById(R.id.enrollment_selection_close).performClick(); return null; });
        instrumentation.waitForIdleSync();
        screenshot("face-workspace");
        main(() -> { visible(enrollment, enrollment.findViewById(R.id.enrollment_capture));
            visible(enrollment, enrollment.findViewById(R.id.enrollment_faces_clear)); return null; });
        launch(CheckActivity.class);
        screenshot("check-ready");
    }

    @Test public void listsAccumulateBeyondOneHundredAndResetSearch() throws Exception {
        StaffActivity staff = launch(StaffActivity.class);
        loadAll(staff, R.id.staff_list, R.id.staff_load_more, 124);
        main(() -> {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            RecyclerView list = staff.findViewById(R.id.staff_list);
            for (int i = 0; i < count(list); i++)
                assertTrue(ids.add(item(list, i).optLong("id")));
            staff.<EditText>findViewById(R.id.staff_search).setText("张晨");
            return null;
        });
        await(() -> count(staff.findViewById(R.id.staff_list)) == 1
                && staff.<RecyclerView>findViewById(R.id.staff_list).isEnabled());
        main(() -> {
            assertEquals("张晨", item(staff.findViewById(R.id.staff_list), 0).optString("name"));
            return null;
        });
        RecordsActivity records = launch(RecordsActivity.class);
        loadAll(records, R.id.records_list, R.id.records_load_more, 124);
        main(() -> {
            java.util.Set<String> ids = new java.util.HashSet<>();
            RecyclerView list = records.findViewById(R.id.records_list);
            for (int i = 0; i < count(list); i++)
                assertTrue(ids.add(item(list, i).optString("id")));
            records.<EditText>findViewById(R.id.records_search).setText("张晨");
            return null;
        });
        await(() -> count(records.findViewById(R.id.records_list)) == 1
                && records.<RecyclerView>findViewById(R.id.records_list).isEnabled());
        FaceEnrollmentActivity enrollment = launch(FaceEnrollmentActivity.class);
        loadAll(enrollment, R.id.enrollment_staff_list, R.id.enrollment_next, 124);
        main(() -> {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            RecyclerView grid = enrollment.findViewById(R.id.enrollment_staff_list);
            assertEquals(enrollment.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 3 : 2,
                    ((GridLayoutManager) grid.getLayoutManager()).getSpanCount());
            for (int i = 0; i < count(grid); i++)
                assertTrue(ids.add(item(grid, i).optLong("id")));
            enrollment.<EditText>findViewById(R.id.enrollment_search).setText("张晨");
            return null;
        });
        await(() -> count(enrollment.findViewById(R.id.enrollment_staff_list)) == 1
                && enrollment.<RecyclerView>findViewById(R.id.enrollment_staff_list).isEnabled());
        main(() -> {
            RecyclerView grid = enrollment.findViewById(R.id.enrollment_staff_list);
            assertEquals("张晨", item(grid, 0).optString("name"));
            click(grid, 0);
            return null;
        });
        await(() -> enrollment.findViewById(R.id.enrollment_selection_overlay).getVisibility() == View.GONE);
    }



    @Test public void cameraCropAndMirrorApplyImmediatelyAndKeepRoleSettings() throws Exception {
        CameraImageOptions savedFace = new CameraImageOptions("640x480", false, 0, 0, 1, 1);
        CameraImageOptions savedHand = new CameraImageOptions("800x600", false, 0, 0, 1, 1);
        saveCameraFixtureOptions(savedFace, savedHand);
        CameraSettingsActivity page = launch(CameraSettingsActivity.class);
        awaitCameraFixturePreview(page, savedFace);
        CameraSource source = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        CameraImageOptions preview = main(() -> (CameraImageOptions) field(source, "options"));
        int generation = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        main(() -> {
            dragCameraCrop(page, 0, .2f, .15f);
            assertCameraOptions(new CameraImageOptions("640x480", false, .2f, .15f, 1, 1),
                    CameraImageOptions.read(preferences, "face"));
            assertSame("调整范围只更新配置，不重启完整画面预览", preview, field(source, "options"));
            assertEquals(generation, field(page, "cameraPreviewGeneration"));
            page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).performClick();
            assertTrue(preferences.getBoolean("face_image_mirror", false));
            assertTrue(((CameraImageOptions) field(source, "options")).mirror);
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > generation);
            return null;
        });
        CameraImageOptions appliedFace = new CameraImageOptions("640x480", true, 0, .15f, .8f, 1);
        awaitCameraFixturePreview(page, appliedFace);
        main(() -> {
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            assertCameraCropFrame(page, 0, .15f, .8f, 1);
            page.findViewById(R.id.settings_preview_hand).performClick();
            assertFalse(page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertCameraOptions(savedHand, CameraImageOptions.read(preferences, "hand"));
            return null;
        });
        awaitCameraFixturePreview(page, savedHand);
        main(() -> { page.findViewById(R.id.settings_preview_face).performClick(); return null; });
        awaitCameraFixturePreview(page, appliedFace);
        main(() -> {
            assertTrue(page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertCameraCropFrame(page, 0, .15f, .8f, 1);
            return null;
        });
    }

    @Test public void cameraImageChangesPersistBothRolesAndSurviveRecreation() throws Exception {
        CameraImageOptions savedFace = new CameraImageOptions("640x480", false, 0, 0, 1, 1);
        CameraImageOptions savedHand = new CameraImageOptions("800x600", true, 0, 0, 1, 1);
        saveCameraFixtureOptions(savedFace, savedHand);
        CameraSettingsActivity page = launch(CameraSettingsActivity.class);
        awaitCameraFixturePreview(page, savedFace);
        CameraSource faceSource = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        CameraImageOptions facePreview = main(() -> (CameraImageOptions) field(faceSource, "options"));
        int faceGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        chooseCameraResolutionFixture(page, "1280x720");
        CameraImageOptions faceResolution = new CameraImageOptions("1280x720", false, 0, 0, 1, 1);
        awaitCameraFixturePreview(page, faceResolution);
        main(() -> {
            assertCameraOptions(faceResolution, CameraImageOptions.read(preferences, "face"));
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > faceGeneration);
            assertNotSame(facePreview, field(faceSource, "options"));
            return null;
        });
        int faceCropGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        CameraImageOptions faceFullPreview = main(() -> (CameraImageOptions) field(faceSource, "options"));
        chooseCameraResolutionFixture(page, "1280x720");
        main(() -> {
            assertEquals("重复选择当前分辨率不能重启预览", faceCropGeneration, field(page, "cameraPreviewGeneration"));
            dragCameraCrop(page, 0, .2f, .15f);
            dragCameraCrop(page, 2, .9f, .95f);
            assertCameraOptions(new CameraImageOptions("1280x720", false, .2f, .15f, .9f, .95f),
                    CameraImageOptions.read(preferences, "face"));
            assertCameraCropFrame(page, .2f, .15f, .9f, .95f);
            assertSame(faceFullPreview, field(faceSource, "options"));
            assertEquals(faceCropGeneration, field(page, "cameraPreviewGeneration"));
            page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).performClick();
            assertCameraOptions(new CameraImageOptions("1280x720", true, .1f, .15f, .8f, .95f),
                    CameraImageOptions.read(preferences, "face"));
            assertTrue(((CameraImageOptions) field(faceSource, "options")).mirror);
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > faceCropGeneration);
            return null;
        });
        awaitCameraFixturePreview(page, new CameraImageOptions("1280x720", true, .1f, .15f, .8f, .95f));
        int faceMirrorGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        CameraImageOptions faceMirroredPreview = main(() -> (CameraImageOptions) field(faceSource, "options"));
        CameraImageOptions appliedFace = new CameraImageOptions("1280x720", true, .1f, .15f, .75f, .9f);
        main(() -> {
            // 镜像已经用于预览，范围坐标与新画面的方向保持一致。
            assertCameraCropFrame(page, .1f, .15f, .8f, .95f);
            dragCameraCrop(page, 2, .75f, .9f);
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            assertSame(faceMirroredPreview, field(faceSource, "options"));
            assertEquals(faceMirrorGeneration, field(page, "cameraPreviewGeneration"));
            assertCameraOptions(savedHand, CameraImageOptions.read(preferences, "hand"));
            page.findViewById(R.id.settings_preview_hand).performClick();
            return null;
        });
        awaitCameraFixturePreview(page, savedHand);
        CameraSource handSource = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        CameraImageOptions handPreview = main(() -> (CameraImageOptions) field(handSource, "options"));
        int handGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        chooseCameraResolutionFixture(page, "640x480");
        CameraImageOptions handResolution = new CameraImageOptions("640x480", true, 0, 0, 1, 1);
        awaitCameraFixturePreview(page, handResolution);
        main(() -> {
            assertCameraOptions(handResolution, CameraImageOptions.read(preferences, "hand"));
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > handGeneration);
            assertNotSame(handPreview, field(handSource, "options"));
            return null;
        });
        int handCropGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        CameraImageOptions handFullPreview = main(() -> (CameraImageOptions) field(handSource, "options"));
        main(() -> {
            dragCameraCrop(page, 0, .1f, .2f);
            dragCameraCrop(page, 2, .95f, .85f);
            assertCameraOptions(new CameraImageOptions("640x480", true, .1f, .2f, .95f, .85f),
                    CameraImageOptions.read(preferences, "hand"));
            assertSame(handFullPreview, field(handSource, "options"));
            assertEquals(handCropGeneration, field(page, "cameraPreviewGeneration"));
            page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).performClick();
            assertCameraOptions(new CameraImageOptions("640x480", false, .05f, .2f, .9f, .85f),
                    CameraImageOptions.read(preferences, "hand"));
            assertFalse(((CameraImageOptions) field(handSource, "options")).mirror);
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > handCropGeneration);
            return null;
        });
        awaitCameraFixturePreview(page, new CameraImageOptions("640x480", false, .05f, .2f, .9f, .85f));
        int handMirrorGeneration = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        CameraImageOptions handMirroredPreview = main(() -> (CameraImageOptions) field(handSource, "options"));
        CameraImageOptions appliedHand = new CameraImageOptions("640x480", false, .15f, .25f, .9f, .85f);
        main(() -> {
            assertCameraCropFrame(page, .05f, .2f, .9f, .85f);
            dragCameraCrop(page, 0, .15f, .25f);
            assertCameraCropFrame(page, .15f, .25f, .9f, .85f);
            assertCameraOptions(appliedHand, CameraImageOptions.read(preferences, "hand"));
            assertSame(handMirroredPreview, field(handSource, "options"));
            assertEquals(handMirrorGeneration, field(page, "cameraPreviewGeneration"));
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            return null;
        });
        scenario.recreate();
        await(() -> resumed() instanceof CameraSettingsActivity && resumed() != page);
        CameraSettingsActivity restored = main(() -> (CameraSettingsActivity) resumed());
        awaitCameraFixturePreview(restored, appliedHand);
        main(() -> {
            assertTrue(restored.<android.widget.RadioButton>findViewById(R.id.settings_preview_hand).isChecked());
            assertFalse(restored.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            assertCameraOptions(appliedHand, CameraImageOptions.read(preferences, "hand"));
            assertCameraResolutionLabel(restored, appliedHand.resolution);
            assertCameraCropFrame(restored, .15f, .25f, .9f, .85f);
            restored.findViewById(R.id.settings_preview_face).performClick();
            return null;
        });
        awaitCameraFixturePreview(restored, appliedFace);
        main(() -> {
            assertTrue(restored.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertCameraResolutionLabel(restored, appliedFace.resolution);
            assertCameraCropFrame(restored, .1f, .15f, .75f, .9f);
            return null;
        });
    }

    @Test public void cameraImageChangesSurviveFinishWithoutSave() throws Exception {
        CameraImageOptions savedFace = new CameraImageOptions("640x480", false, .1f, .12f, .92f, .94f);
        CameraImageOptions savedHand = new CameraImageOptions("800x600", true, .04f, .08f, .9f, .88f);
        saveCameraFixtureOptions(savedFace, savedHand);
        CameraSettingsActivity page = launch(CameraSettingsActivity.class);
        awaitCameraFixturePreview(page, savedFace);
        CameraSource source = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        int generation = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        chooseCameraResolutionFixture(page, "1280x720");
        CameraImageOptions resolution = new CameraImageOptions("1280x720", false, .1f, .12f, .92f, .94f);
        awaitCameraFixturePreview(page, resolution);
        CameraImageOptions appliedFace = new CameraImageOptions("1280x720", true, .08f, .22f, .8f, .94f);
        main(() -> {
            dragCameraCrop(page, 0, .2f, .22f);
            page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).performClick();
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            assertTrue(((CameraImageOptions) field(source, "options")).mirror);
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > generation);
            return null;
        });
        awaitCameraFixturePreview(page, appliedFace);
        main(() -> {
            page.finish();
            return null;
        });
        await(() -> !(resumed() instanceof CameraSettingsActivity));
        CameraSettingsActivity reopened = launch(CameraSettingsActivity.class);
        awaitCameraFixturePreview(reopened, appliedFace);
        main(() -> {
            assertTrue(reopened.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertCameraResolutionLabel(reopened, appliedFace.resolution);
            assertCameraCropFrame(reopened, .08f, .22f, .8f, .94f);
            assertCameraOptions(appliedFace, CameraImageOptions.read(preferences, "face"));
            assertCameraOptions(savedHand, CameraImageOptions.read(preferences, "hand"));
            return null;
        });
    }

    @Test public void selectingAnotherCameraForSameRoleImmediatelySavesAndStartsPreview() throws Exception {
        String faceId = "usb:UI-DESIGN-NO-FACE-CAMERA", handId = "usb:UI-DESIGN-NO-HAND-CAMERA";
        assertTrue(preferences.edit().putString("face_camera", faceId).putString("hand_camera", handId).commit());
        CameraImageOptions savedFace = new CameraImageOptions("640x480", false, .1f, .12f, .92f, .94f);
        CameraImageOptions savedHand = new CameraImageOptions("800x600", true, 0, 0, 1, 1);
        saveCameraFixtureOptions(savedFace, savedHand);
        CameraSettingsActivity page = launch(CameraSettingsActivity.class);
        awaitCameraFixturePreview(page, savedFace);
        CameraSource source = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        CameraImageOptions preview = main(() -> (CameraImageOptions) field(source, "options"));
        int generation = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        int selected = main(() -> {
            page.findViewById(R.id.settings_choose_active_camera).performClick();
            return ((java.util.List<?>) field(page, "cameraIds")).indexOf(handId);
        });
        selectCameraChoiceRow(page, selected);
        main(() -> {
            assertTrue(page.<android.widget.RadioButton>findViewById(R.id.settings_preview_face).isChecked());
            assertEquals(handId, preferences.getString("face_camera", ""));
            assertEquals(handId, field(source, "preferredId"));
            assertNotSame(preview, field(source, "options"));
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > generation);
            return null;
        });
        awaitCameraFixturePreview(page, CameraImageOptions.defaults());
        main(() -> {
            assertEquals(handId, preferences.getString("face_camera", ""));
            assertEquals(handId, field(source, "preferredId"));
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > generation);
            assertEquals(View.VISIBLE, page.findViewById(R.id.settings_camera_crop).getVisibility());
            assertCameraOptions(CameraImageOptions.defaults(), CameraImageOptions.read(preferences, "face"));
            assertCameraOptions(savedHand, CameraImageOptions.read(preferences, "hand"));
            return null;
        });
        chooseCameraResolutionFixture(page, "1280x720");
        CameraImageOptions selectedResolution = new CameraImageOptions("1280x720", false, 0, 0, 1, 1);
        awaitCameraFixturePreview(page, selectedResolution);
        main(() -> {
            assertCameraOptions(selectedResolution, CameraImageOptions.read(preferences, "face"));
            assertCameraOptions(savedHand, CameraImageOptions.read(preferences, "hand"));
            return null;
        });
    }

    private void saveCameraFixtureOptions(CameraImageOptions face, CameraImageOptions hand) {
        SharedPreferences.Editor edit = preferences.edit();
        face.save(edit, "face");
        hand.save(edit, "hand");
        assertTrue(edit.commit());
    }

    private void awaitCameraFixturePreview(CameraSettingsActivity page, CameraImageOptions applied) throws Exception {
        assertEquals("相机配置用例需预先授予 CAMERA 权限", android.content.pm.PackageManager.PERMISSION_GRANTED,
                androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA));
        await(() -> {
            CameraSource source = (CameraSource) field(page, "cameraPreviewSource");
            if (source == null || (Integer) field(page, "cameraPreviewGeneration") <= 0
                    || (Boolean) field(page, "cameraPreviewRunning")) return false;
            String role = page.<android.widget.RadioButton>findViewById(R.id.settings_preview_hand).isChecked() ? "hand" : "face";
            if (!preferences.getString(role + "_camera", "").equals(field(source, "preferredId"))) return false;
            CameraImageOptions actual = (CameraImageOptions) field(source, "options");
            return applied.resolution.equals(actual.resolution) && applied.mirror == actual.mirror
                    && !(Boolean) field(source, "wanted");
        });
        main(() -> {
            CameraSource source = (CameraSource) field(page, "cameraPreviewSource");
            assertCameraOptions(applied.fullPreview(), (CameraImageOptions) field(source, "options"));
            assertCameraSaveActionAbsent(page);
            return null;
        });
    }

    private void chooseCameraResolutionFixture(CameraSettingsActivity page, String resolution) throws Exception {
        java.util.List<String> sizes = java.util.Arrays.asList("640x480", "800x600", "1280x720");
        main(() -> {
            CameraSource source = (CameraSource) field(page, "cameraPreviewSource");
            // 无镜头窗口仅注入选择器候选值；options 与启动代次仍由真实页面调用产生，不伪造采集帧。
            java.lang.reflect.Field supported = CameraSource.class.getDeclaredField("supportedResolutions");
            supported.setAccessible(true);
            supported.set(source, sizes);
            java.lang.reflect.Field actual = CameraSource.class.getDeclaredField("activeResolution");
            actual.setAccessible(true);
            actual.set(source, ((CameraImageOptions) field(source, "options")).resolution);
            page.findViewById(R.id.settings_camera_resolution).performClick();
            return null;
        });
        selectCameraChoiceRow(page, sizes.indexOf(resolution));
        main(() -> { assertCameraResolutionLabel(page, resolution); return null; });
    }

    private void selectCameraChoiceRow(CameraSettingsActivity page, int position) throws Exception {
        assertTrue("选择项必须存在", position >= 0);
        await(() -> page.formDialog != null && page.formDialog.isShowing()
                && page.formDialog.<ListView>findViewById(R.id.department_filter_options).getChildCount() > 0);
        main(() -> {
            page.formDialog.<ListView>findViewById(R.id.department_filter_options).setSelection(position);
            return null;
        });
        await(() -> {
            ListView list = page.formDialog.findViewById(R.id.department_filter_options);
            return list.getChildAt(position - list.getFirstVisiblePosition()) != null;
        });
        main(() -> {
            ListView list = page.formDialog.findViewById(R.id.department_filter_options);
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            assertTrue(row.isShown());
            list.performItemClick(row, position, list.getItemIdAtPosition(position));
            return null;
        });
        await(() -> page.formDialog == null || !page.formDialog.isShowing());
    }

    private void dragCameraCrop(CameraSettingsActivity page, int corner, float x, float y) throws Exception {
        CameraCropView crop = page.findViewById(R.id.settings_camera_crop);
        assertTrue(crop.isShown());
        assertTrue(crop.getWidth() > 0 && crop.getHeight() > 0);
        // 无镜头夹具以控件尺寸定义坐标平面，只验证裁剪交互，不宣称获得真实画面。
        crop.imageSize(crop.getWidth(), crop.getHeight());
        float fromX = (Float) field(crop, corner == 0 ? "left" : "right");
        float fromY = (Float) field(crop, corner == 0 ? "top" : "bottom");
        long time = SystemClock.uptimeMillis();
        int[] actions = {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time + i * 30L, actions[i],
                    crop.getWidth() * (i == 0 ? fromX : x), crop.getHeight() * (i == 0 ? fromY : y), 0);
            try { assertTrue(crop.onTouchEvent(event)); } finally { event.recycle(); }
        }
    }

    private void assertCameraCropFrame(CameraSettingsActivity page, float left, float top, float right, float bottom) throws Exception {
        CameraCropView crop = page.findViewById(R.id.settings_camera_crop);
        assertEquals(left, (Float) field(crop, "left"), .002f);
        assertEquals(top, (Float) field(crop, "top"), .002f);
        assertEquals(right, (Float) field(crop, "right"), .002f);
        assertEquals(bottom, (Float) field(crop, "bottom"), .002f);
    }

    private void assertCameraOptions(CameraImageOptions expected, CameraImageOptions actual) {
        assertEquals(expected.resolution, actual.resolution);
        assertEquals(expected.mirror, actual.mirror);
        assertEquals(expected.left, actual.left, .002f);
        assertEquals(expected.top, actual.top, .002f);
        assertEquals(expected.right, actual.right, .002f);
        assertEquals(expected.bottom, actual.bottom, .002f);
    }

    private void assertCameraResolutionLabel(CameraSettingsActivity page, String resolution) {
        assertTrue("分辨率控件应展示已生效的选择值", page.<TextView>findViewById(R.id.settings_camera_resolution)
                .getText().toString().contains(resolution.replace("x", " × ")));
    }

    private void assertCameraSaveActionAbsent(CameraSettingsActivity page) {
        int saveId = page.getResources().getIdentifier("settings_save_camera", "id", page.getPackageName());
        if (saveId != 0) assertNull("相机配置即时生效，不再保留保存按钮", page.findViewById(saveId));
    }

    @Test public void employeeSearchUsesSystemInputAndClearsWithoutSelecting() throws Exception {
        FaceEnrollmentActivity page = launch(FaceEnrollmentActivity.class);
        await(() -> count(page.findViewById(R.id.enrollment_staff_list)) > 0);
        main(() -> {
            EditText search = page.findViewById(R.id.enrollment_search);
            assertTrue(search.getShowSoftInputOnFocus());
            search.setText("张晨");
            assertEquals("张晨", search.getText().toString());
            page.findViewById(R.id.enrollment_search_clear).performClick();
            assertEquals("", search.getText().toString());
            return null;
        });
        screenshot("face-selection");
    }

    @Test public void cloudSwitchWaitsForConfirmedStateAndDoesNotRepeatAfterRecreation() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<JSONObject> pending = new AtomicReference<>();
        AtomicReference<okhttp3.WebSocket> socket = new AtomicReference<>();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() {
                    @Override public void onMessage(okhttp3.WebSocket connection, String text) {
                        try {
                            JSONObject query = new JSONObject(text), data = new JSONObject();
                            String command = query.getString("cmd");
                            if ("setCloudStatus".equals(command)) {
                                writes.incrementAndGet(); pending.set(query); socket.set(connection); return;
                            }
                            if ("getCloudStatus".equals(command)) data.put("enabled", enabled.get());
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY")
                                    .put("model_ready", true).put("certificate_valid", true).put("device_id", "UI-CLOUD-FIXTURE");
                            connection.send(new JSONObject().put("requestId", query.getString("requestId"))
                                    .put("code", 0).put("data", data).toString());
                        } catch (Exception error) { throw new AssertionError(error); }
                    }
                });
            }
        });
        LicenseSettingsActivity page = launch(LicenseSettingsActivity.class);
        await(() -> page.findViewById(R.id.license_cloud_enabled).isEnabled());
        main(() -> {
            page.<android.widget.Switch>findViewById(R.id.license_cloud_enabled).performClick();
            assertFalse(page.<android.widget.Switch>findViewById(R.id.license_cloud_enabled).isChecked());
            AlertDialog confirm = (AlertDialog) field(field(page, "cloudService"), "confirmation");
            confirm.findViewById(android.R.id.button2).performClick();
            assertEquals(0, writes.get());
            page.<android.widget.Switch>findViewById(R.id.license_cloud_enabled).performClick();
            confirm = (AlertDialog) field(field(page, "cloudService"), "confirmation");
            confirm.findViewById(android.R.id.button1).performClick();
            return null;
        });
        await(() -> writes.get() == 1);
        scenario.recreate();
        await(() -> resumed() instanceof LicenseSettingsActivity && resumed() != page);
        LicenseSettingsActivity restored = main(() -> (LicenseSettingsActivity) resumed());
        main(() -> {
            assertFalse(restored.findViewById(R.id.license_cloud_enabled).isEnabled());
            assertFalse(restored.<android.widget.Switch>findViewById(R.id.license_cloud_enabled).isChecked());
            return null;
        });
        screenshot("cloud-service-progress");
        enabled.set(true);
        socket.get().send(new JSONObject().put("requestId", pending.get().getString("requestId"))
                .put("code", 0).put("data", new JSONObject()).toString());
        await(() -> restored.findViewById(R.id.license_cloud_enabled).isEnabled()
                && restored.<android.widget.Switch>findViewById(R.id.license_cloud_enabled).isChecked());
        assertEquals(1, writes.get());
        screenshot("cloud-service-enabled");
    }

    @Test public void actualCameraReportsSizesAndRepeatedSelectionKeepsPreview() throws Exception {
        org.junit.Assume.assumeTrue("显式启用实际镜头预览验收", "true".equals(
                InstrumentationRegistry.getArguments().getString("enableCameraHardware")));
        preferences.edit().putString("face_camera", "0").commit();
        SharedPreferences.Editor draft = preferences.edit();
        cc.huoyan.opensource.morningcheck.camera.CameraImageOptions.defaults().save(draft, "face");
        draft.commit();
        CameraSettingsActivity page = launch(CameraSettingsActivity.class);
        await(() -> page.findViewById(R.id.settings_camera_preview_message).getVisibility() == View.GONE);
        CameraSource source = main(() -> (CameraSource) field(page, "cameraPreviewSource"));
        CameraImageOptions preview = main(() -> (CameraImageOptions) field(source, "options"));
        int generation = main(() -> (Integer) field(page, "cameraPreviewGeneration"));
        main(() -> {
            assertCameraSaveActionAbsent(page);
            assertFalse(source.supportedResolutions().isEmpty());
            assertTrue(source.supportedResolutions().contains(source.activeResolution()));
            page.findViewById(R.id.settings_camera_resolution).performClick();
            return null;
        });
        selectCameraChoiceRow(page, main(() -> source.supportedResolutions().indexOf(source.activeResolution())));
        main(() -> {
            assertSame(preview, field(source, "options"));
            assertEquals(generation, field(page, "cameraPreviewGeneration"));
            assertEquals(source.activeResolution(), preferences.getString("face_image_resolution", ""));
            return null;
        });
        screenshot("camera-hardware");
        main(() -> {
            page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).performClick();
            assertTrue(page.<android.widget.Switch>findViewById(R.id.settings_camera_mirror).isChecked());
            assertTrue(preferences.getBoolean("face_image_mirror", false));
            assertTrue(((CameraImageOptions) field(source, "options")).mirror);
            assertNotSame(preview, field(source, "options"));
            assertTrue((Integer) field(page, "cameraPreviewGeneration") > generation);
            return null;
        });
        await(() -> (Integer) field(page, "cameraPreviewGeneration") > generation
                && ((CameraImageOptions) field(source, "options")).mirror
                && page.findViewById(R.id.settings_camera_preview_message).getVisibility() == View.GONE);
        assertTrue(preferences.getBoolean("face_image_mirror", false));
        screenshot("camera-hardware-mirrored");
    }



    @Test public void sdkDraftSelectionAndCancelledProbeDoNotSaveConnection() throws Exception {
        DevicesActivity page = launch(DevicesActivity.class);
        String savedEndpoint = preferences.getString(MorningSdkSettings.ENDPOINT_KEY, "");
        main(() -> {
            page.findViewById(R.id.settings_sdk_lan).performClick();
            page.<EditText>findViewById(R.id.settings_sdk_url).setText("300.1.1.1");
            page.findViewById(R.id.settings_connect).performClick();
            assertNotNull(page.<EditText>findViewById(R.id.settings_sdk_url).getError());
            assertEquals(savedEndpoint, preferences.getString(MorningSdkSettings.ENDPOINT_KEY, ""));
            page.<EditText>findViewById(R.id.settings_sdk_url).setText("192.0.2.1");
            page.findViewById(R.id.settings_sdk_test).performClick();
            AlertDialog loading = (AlertDialog) field(page, "loading");
            assertNotNull(loading);
            loading.findViewById(R.id.loading_cancel).performClick();
            assertNull(field(page, "loading"));
            assertEquals(savedEndpoint, preferences.getString(MorningSdkSettings.ENDPOINT_KEY, ""));
            return null;
        });
        scenario.recreate();
        await(() -> resumed() instanceof DevicesActivity);
        main(() -> {
            Activity restored = resumed();
            assertEquals("192.0.2.1", restored.<EditText>findViewById(R.id.settings_sdk_url).getText().toString());
            visible(restored, restored.findViewById(R.id.settings_connect));
            assertEquals(savedEndpoint, preferences.getString(MorningSdkSettings.ENDPOINT_KEY, ""));
            return null;
        });
        screenshot("sdk-lan-draft");
    }




    @Test public void dateDialogKeepsDraftsAndAppliesWholeCalendarDays() throws Exception {
        RecordsActivity records = launch(RecordsActivity.class);
        await(() -> count(records.findViewById(R.id.records_list)) > 0);
        Object filter = field(records, "dateFilter");
        Object original = field(filter, "range");
        main(() -> { records.findViewById(R.id.records_date_filter).performClick(); return null; });
        RecordDateFilterDialog dialog = (RecordDateFilterDialog) field(filter, "dialog");
        main(() -> {
            visible(records, dialog.findViewById(R.id.record_date_confirm));
            dialog.findViewById(R.id.record_date_start_date).performClick();
            android.os.Bundle draft = dialog.saveDraft();
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            assertEquals(java.time.LocalTime.MIDNIGHT,
                    java.time.Instant.ofEpochMilli(draft.getLong("start")).atZone(zone).toLocalTime());
            assertEquals(java.time.LocalTime.of(23, 59),
                    java.time.Instant.ofEpochMilli(draft.getLong("end")).atZone(zone).toLocalTime());
            dialog.findViewById(R.id.record_date_today).performClick();
            assertEquals(original, field(filter, "range"));
            return null;
        });
        screenshot("record-date-filter");
        main(() -> { dialog.findViewById(R.id.record_date_cancel).performClick(); return null; });
        assertEquals(original, main(() -> field(filter, "range")));
        main(() -> { records.findViewById(R.id.records_date_filter).performClick(); return null; });
        RecordDateFilterDialog yesterday = (RecordDateFilterDialog) field(filter, "dialog");
        main(() -> {
            yesterday.findViewById(R.id.record_date_yesterday).performClick();
            yesterday.findViewById(R.id.record_date_confirm).performClick();
            cc.huoyan.opensource.morningcheck.domain.RecordDateRange selected =
                    (cc.huoyan.opensource.morningcheck.domain.RecordDateRange) field(filter, "range");
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate date = java.time.LocalDate.now(zone).minusDays(1);
            assertEquals(date.atStartOfDay(zone).toInstant().toEpochMilli(), selected.customStart());
            assertEquals(date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), selected.customEnd());
            records.findViewById(R.id.records_date_filter).performClick();
            return null;
        });
        RecordDateFilterDialog month = (RecordDateFilterDialog) field(filter, "dialog");
        main(() -> {
            month.findViewById(R.id.record_date_this_month).performClick();
            android.os.Bundle draft = month.saveDraft();
            month.findViewById(R.id.record_date_reset).performClick();
            month.restoreDraft(draft);
            assertTrue(month.findViewById(R.id.record_date_this_month).isSelected());
            month.findViewById(R.id.record_date_title).performLongClick();
            return null;
        });
        instrumentation.waitForIdleSync();
        main(() -> {
            visible(records, month.findViewById(R.id.record_date_preset_picker));
            visible(records, month.findViewById(R.id.record_date_start_time));
            visible(records, month.findViewById(R.id.record_date_confirm));
            android.widget.GridView days = month.findViewById(R.id.record_date_days);
            assertEquals(days.getCount(), days.getChildCount());
            assertTrue(days.getHeight() >= (days.getCount() / 7) * records.getResources().getDimension(R.dimen.sp_8) * 1.2f);
            return null;
        });
        screenshot("record-date-extra");
        main(() -> {
            month.findViewById(R.id.record_date_title).performLongClick();
            month.findViewById(R.id.record_date_confirm).performClick();
            cc.huoyan.opensource.morningcheck.domain.RecordDateRange selected =
                    (cc.huoyan.opensource.morningcheck.domain.RecordDateRange) field(filter, "range");
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.YearMonth current = java.time.YearMonth.now(zone);
            assertEquals(current.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), selected.customStart());
            assertEquals(current.atEndOfMonth().atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), selected.customEnd());
            return null;
        });
    }



    @Test public void thresholdKeypadValidatesAndCancelKeepsSavedValue() throws Exception {
        CheckRulesSettingsActivity rules = launch(CheckRulesSettingsActivity.class);
        String original = preferences.getString("temperature_limit", "37.3");
        main(() -> { rules.findViewById(R.id.settings_temperature_limit).performClick(); return null; });
        await(() -> rules.formDialog != null && rules.formDialog.isShowing());
        screenshot("temperature-threshold");
        main(() -> {
            AlertDialog dialog = rules.formDialog;
            visible(rules, dialog.findViewById(android.R.id.button1));
            dialog.getWindow().getDecorView().findViewWithTag("threshold:9").performClick();
            dialog.findViewById(android.R.id.button1).performClick();
            assertTrue(dialog.isShowing());
            assertTrue(dialog.findViewById(R.id.threshold_error).isShown());
            assertEquals(original, preferences.getString("temperature_limit", "37.3"));
            dialog.findViewById(android.R.id.button2).performClick();
            assertEquals(original, preferences.getString("temperature_limit", "37.3"));
            rules.findViewById(R.id.settings_temperature_limit).performClick();
            for (String key : new String[]{"3", "8", ".", "2"})
                rules.formDialog.getWindow().getDecorView().findViewWithTag("threshold:" + key).performClick();
            rules.formDialog.findViewById(android.R.id.button1).performClick();
            assertEquals("38.2", preferences.getString("temperature_limit", "37.3"));
            return null;
        });
    }

    @Test public void checkAnimationPlaysInCardAndReleasesImagesOnRedraw() throws Exception {
        AtomicReference<Throwable> serverFailure = prepareCheckReadySdk();
        assertTrue(preferences.edit().putInt(AppFontSettings.KEY, 3).putBoolean("temperature", true)
                .putString("shop_name", "安和食堂 · 后厨").commit());
        CheckActivity check = launch(CheckActivity.class);
        awaitCheckCaptureReady(check);
        String firstTime = main(() -> {
            visible(check, check.findViewById(R.id.check_header));
            assertEquals("安和食堂 · 后厨", ((TextView) check.findViewById(R.id.header_shop)).getText().toString());
            assertNull("晨检顶栏没有返回首页按钮", check.findViewById(R.id.header_home));
            android.widget.TextClock clock = check.findViewById(R.id.check_clock);
            assertTrue(clock.getText().toString().matches("\\d{4}-\\d{2}-\\d{2}  \\d{2}:\\d{2}:\\d{2}"));
            return clock.getText().toString();
        });
        await(() -> !firstTime.equals(((TextView) check.findViewById(R.id.check_clock)).getText().toString()));
        main(() -> {
            // 无相机夹具只验收页面绘制，不将设备缺失提示留在截图上。
            check.cameraError = "";
            java.lang.reflect.Method update = CheckActivity.class.getDeclaredMethod("updateCheck");
            update.setAccessible(true);
            update.invoke(check);
            if (ToastEx.toast != null) ToastEx.toast.cancel();
            return null;
        });
        FaceAlignmentGuideView image = main(() -> check.findViewById(R.id.check_face_guide));
        main(() -> {
            visible(check, image);
            assertEquals(check.getString(R.string.check_tutorial_guide), image.getContentDescription());
            android.graphics.Movie animation = (android.graphics.Movie) field(image, "animation");
            assertNotNull("卡片必须成功解码完整晨检 GIF", animation);
            assertTrue("完整动画包含身份、头部、手背和手心", animation.duration() >= 14000);
            assertNotNull(field(image, "still"));
            assertFalse("常驻动画不能暂停晨检", (Boolean) field(check, "dialogCapturePaused"));
            return null;
        });
        // 等待测试夹具的无设备 Toast 退出动画结束，截图只保留晨检页面。
        SystemClock.sleep(1000);
        screenshot("check-inline-animation");
        main(() -> {
            android.graphics.RectF guideBounds = (android.graphics.RectF) field(check.overlay, "bounds");
            assertTrue("人脸引导框必须实际绘制", guideBounds.height() > 0);
            android.graphics.RectF preview = (android.graphics.RectF) field(check.overlay, "contentBounds");
            assertTrue("头肩引导完整位于实际相机画面内", preview.contains(guideBounds));
            check.render();
            assertEquals("重排保留场所顶栏", "安和食堂 · 后厨",
                    ((TextView) check.findViewById(R.id.header_shop)).getText().toString());
            assertNull("重排释放旧动画", field(image, "animation"));
            assertNull("重排释放旧静态图", field(image, "still"));
            assertNotSame(image, check.findViewById(R.id.check_face_guide));
            return null;
        });
        await(() -> check.findViewById(R.id.check_clock).isLaidOut());
        main(() -> {
            visible(check, check.findViewById(R.id.check_clock));
            return null;
        });
        assertNull("页面动画不发送识别请求", serverFailure.get());
    }

    @Test public void checkExitPasswordKeepsPreviewSuspendedAcrossRedrawAndPreservesManualPause() throws Exception {
        AtomicReference<Throwable> serverFailure = prepareCheckReadySdk();
        CheckActivity check = launch(CheckActivity.class);
        awaitCheckCaptureReady(check);
        CheckPipeline pipeline = main(() -> (CheckPipeline) field(check, "pipeline"));
        CheckPipeline.Stage stage = main(pipeline::getStage);
        CameraSource beforeRedraw = main(() -> check.camera);
        main(() -> {
            check.findViewById(R.id.check_progress).performLongClick();
            check.findViewById(R.id.check_pause).performClick();
            assertTrue((Boolean) field(check, "paused"));
            check.ensureCamera(check.cameraForStage());
            assertTrue("无设备夹具只观测预览资源请求", (Boolean) field(beforeRedraw, "wanted"));
            check.findViewById(R.id.check_exit).performClick();
            assertTrue((Boolean) field(check, "exitRequested"));
            assertTrue((Boolean) field(check, "dialogCapturePaused"));
            assertFalse("密码验证期间必须停止预览请求", (Boolean) field(beforeRedraw, "wanted"));
            assertEquals(0f, check.<android.view.TextureView>findViewById(R.id.camera_texture).getAlpha(), 0f);
            return null;
        });
        await(() -> {
            AlertDialog dialog = (AlertDialog) field(check, "exitDialog");
            return dialog != null && dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null
                    && dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getHeight() > 0;
        });
        AlertDialog password = main(() -> (AlertDialog) field(check, "exitDialog"));
        main(() -> {
            check.render();
            assertSame("内容重排不能关闭或替换正在验证的密码窗口", password, field(check, "exitDialog"));
            assertTrue(password.isShowing());
            assertTrue((Boolean) field(check, "exitRequested"));
            assertTrue((Boolean) field(check, "dialogCapturePaused"));
            assertTrue("重排应释放旧采集源", (Boolean) field(beforeRedraw, "closed"));
            assertFalse((Boolean) field(beforeRedraw, "wanted"));
            assertNotSame(beforeRedraw, check.camera);
            assertFalse("新页面的预览也必须服从密码暂停状态", (Boolean) field(check.camera, "wanted"));
            check.ensureCamera(check.cameraForStage());
            assertFalse("直接请求相机也不能绕过密码窗口的暂停", (Boolean) field(check.camera, "wanted"));
            assertEquals(0f, check.<android.view.TextureView>findViewById(R.id.camera_texture).getAlpha(), 0f);
            return null;
        });
        CameraSource afterRedraw = main(() -> check.camera);
        dismissDialogAndObserveResume(check, () -> {
            password.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            return null;
        }, () -> {
            assertFalse(password.isShowing());
            assertNull(field(check, "exitDialog"));
            assertFalse((Boolean) field(check, "exitRequested"));
            assertFalse((Boolean) field(check, "dialogCapturePaused"));
            assertTrue("取消密码验证不能解除原有的手动暂停", (Boolean) field(check, "paused"));
            assertSame(afterRedraw, check.camera);
            assertTrue("取消验证应恢复新页面的预览请求", (Boolean) field(afterRedraw, "wanted"));
            assertFalse("取消验证不能重新启用已退休的采集源", (Boolean) field(beforeRedraw, "wanted"));
            assertEquals(1f, check.<android.view.TextureView>findViewById(R.id.camera_texture).getAlpha(), 0f);
            assertSame(pipeline, field(check, "pipeline"));
            assertEquals("取消验证继续停留在原步骤", stage, pipeline.getStage());
            assertEquals("继续采集", check.<TextView>findViewById(R.id.check_pause).getText().toString());
            return null;
        });
        assertNull("密码预览夹具不得出现协议错误", serverFailure.get());
    }

    private void dismissDialogAndObserveResume(CheckActivity check, Callable<Void> dismiss, Callable<Void> restored) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean complete = new java.util.concurrent.atomic.AtomicBoolean();
        main(() -> {
            dismiss.call();
            // Dialog 的关闭监听在主线程队列中执行；紧随其后观测恢复，早于无效 USB 的异步失败回调。
            check.main.post(() -> {
                try { restored.call(); } catch (Throwable error) { failure.set(error); }
                finally { complete.set(true); }
            });
            return null;
        });
        await(complete::get);
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private AtomicReference<Throwable> prepareCheckReadySdk() {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        assertTrue(preferences.edit().putBoolean("temperature", false).putBoolean("head", true)
                .putBoolean("hat", true).putBoolean("mask", true)
                .putString("serial_path", "/dev/CODEX-TUTORIAL-NO-DEVICE")
                .putString("light_serial_path", "/dev/CODEX-TUTORIAL-NO-DEVICE").commit());
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() {
                    @Override public void onMessage(okhttp3.WebSocket connection, String text) {
                        try {
                            JSONObject query = new JSONObject(text), data = new JSONObject();
                            String command = query.getString("cmd");
                            assertTrue("晨检页面测试只允许本机 SDK 状态查询", java.util.Arrays.asList("init", "getStatus",
                                    "getCertInfo", "getDownloadProgress", "getCapabilities", "get_class_table", "getCloudStatus").contains(command));
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY").put("model_ready", true)
                                    .put("certificate_valid", true).put("device_id", "UI-TUTORIAL-FIXTURE");
                            connection.send(new JSONObject().put("requestId", query.getString("requestId"))
                                    .put("code", 0).put("data", data).toString());
                        } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
                    }

                    @Override public void onMessage(okhttp3.WebSocket connection, okio.ByteString bytes) {
                        serverFailure.compareAndSet(null, new AssertionError("晨检页面测试不得向 SDK 发送真实图像"));
                    }
                });
            }
        });
        return serverFailure;
    }

    private void awaitCheckCaptureReady(CheckActivity check) throws Exception {
        await(() -> check.loaded && check.sdkReady() && field(check, "pipeline") != null && check.camera != null);
        // 不存在的 USB 设备应完成错误回调，之后同步观测退出密码窗口的暂停和恢复动作。
        await(() -> !(Boolean) field(check.camera, "wanted"));
    }

    @Test public void homeStateColorsAndDisconnectedActionsMatchDesign() throws Exception {
        HomeActivity home = launch(HomeActivity.class);
        java.lang.reflect.Method show = HomeActivity.class.getDeclaredMethod("showHomeStatus",
                cc.huoyan.opensource.morningcheck.sdk.MorningSdkState.class, boolean.class);
        show.setAccessible(true);
        for (String state : new String[]{"MISSING", "NOT_ACTIVATED", "INITIALIZING", "READY"}) {
            main(() -> {
                JSONObject status = new JSONObject().put("sdk_state", state).put("certificate_valid", true).put("model_ready", true);
                show.invoke(home, cc.huoyan.opensource.morningcheck.sdk.MorningSdkState.from(true, status, null), "MISSING".equals(state));
                visible(home, home.findViewById(R.id.home_start));
                assertEquals(!"INITIALIZING".equals(state), home.findViewById(R.id.home_start).isEnabled());
                return null;
            });
            screenshot("home-" + state.toLowerCase(java.util.Locale.ROOT));
        }
        CheckActivity check = launch(CheckActivity.class);
        main(() -> {
            java.lang.reflect.Field lost = CheckActivity.class.getDeclaredField("connectionLost");
            lost.setAccessible(true); lost.setBoolean(check, true);
            check.render();
            return null;
        });
        screenshot("check-disconnected");
        main(() -> {
            visible(check, check.findViewById(R.id.check_disconnected_home));
            visible(check, check.findViewById(R.id.check_disconnected_settings));
            return null;
        });
    }

    @Test public void newNavigationAndSelectionsFitLargestFont() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        HomeActivity home = launch(HomeActivity.class);
        main(() -> { visible(home, home.findViewById(R.id.home_lighting));
            assertEquals(0, home.getResources().getIdentifier("home_shop_settings", "id", home.getPackageName())); return null; });
        screenshot("revision-home-130");
        SystemSettingsActivity system = launch(SystemSettingsActivity.class);
        main(() -> { visible(system, system.findViewById(R.id.settings_shop_name));
            system.findViewById(R.id.settings_font_current).performClick(); return null; });
        await(() -> system.formDialog != null && system.formDialog.isShowing());
        screenshot("revision-font-choice-130");
        main(() -> {
            AlertDialog dialog = system.formDialog;
            visible(system, dialog.findViewById(R.id.settings_font_preview));
            visible(system, dialog.findViewById(R.id.settings_font_apply));
            visible(system, dialog.findViewById(R.id.settings_font_cancel));
            ListView options = dialog.findViewById(R.id.settings_font_options);
            assertEquals(4, options.getCount());
            dialog.findViewById(R.id.settings_font_cancel).performClick();
            assertEquals(3, AppFontSettings.readIndex(system)); return null;
        });
        TemperatureSettingsActivity temperature = launch(TemperatureSettingsActivity.class);
        main(() -> {
            assertFalse(temperature.findViewById(R.id.settings_serial_path) instanceof EditText);
            assertFalse(temperature.findViewById(R.id.settings_baud) instanceof EditText);
            temperature.findViewById(R.id.settings_baud).performClick(); return null;
        });
        screenshot("revision-baud-choice-130");
        main(() -> { temperature.onBackPressed(); return null; });
        LightingSettingsActivity light = launch(LightingSettingsActivity.class);
        main(() -> {
            assertFalse(light.findViewById(R.id.lighting_path) instanceof EditText);
            assertFalse(light.findViewById(R.id.lighting_baud) instanceof EditText);
            visible(light, light.findViewById(R.id.lighting_save)); return null;
        });
        screenshot("revision-lighting-130");
    }

    @Test public void staffDeleteAndDepartmentChoiceStayOnTheirCurrentPages() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        StaffActivity staff = launch(StaffActivity.class);
        await(() -> staff.<RecyclerView>findViewById(R.id.staff_list).getChildCount() > 0);
        main(() -> { staff.<RecyclerView>findViewById(R.id.staff_list).getChildAt(0).findViewById(R.id.staff_row_delete).performClick(); return null; });
        await(() -> field(staff, "deleteConfirmation") != null);
        assertSame(staff, main(this::resumed));
        screenshot("revision-staff-delete-130");
        main(() -> { AlertDialog dialog = (AlertDialog) field(staff, "deleteConfirmation");
            visible(staff, dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            staff.<RecyclerView>findViewById(R.id.staff_list).getChildAt(0).findViewById(R.id.staff_row_edit).performClick(); return null; });
        await(() -> resumed() instanceof StaffEditActivity);
        StaffEditActivity edit = main(() -> (StaffEditActivity) resumed());
        await(() -> edit.findViewById(R.id.staff_edit_department_value).isEnabled());
        main(() -> { edit.findViewById(R.id.staff_edit_department_value).performClick(); return null; });
        Object editor = field(edit, "editor");
        await(() -> field(editor, "departmentDialog") != null);
        assertSame(edit, main(this::resumed));
        screenshot("revision-staff-department-130");
        main(() -> { DepartmentFilterDialog selector = (DepartmentFilterDialog) field(editor, "departmentDialog");
            AlertDialog dialog = (AlertDialog) field(selector, "dialog");
            visible(edit, dialog.findViewById(R.id.department_filter_close));
            ListView list = dialog.findViewById(R.id.department_filter_options);
            assertEquals(4, list.getCount());
            list.setSelection(list.getCount()-1); return null; });
        screenshot("revision-department-list-end-130");
    }

    @Test public void loadingBeforeFirstLayoutHasValidWidthAndClosesWithPage() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        AtomicReference<LoadingDialog> loading = new AtomicReference<>();
        AtomicReference<android.app.Dialog> shown = new AtomicReference<>();
        androidx.test.runner.lifecycle.ActivityLifecycleCallback callback = (activity, stage) -> {
            if (!(activity instanceof SystemSettingsActivity) || stage != Stage.CREATED) return;
            assertEquals("Reproduce a loading request before host measurement", 0,
                    activity.getWindow().getDecorView().getWidth());
            LoadingDialog value = new LoadingDialog(activity);
            value.show(activity.getString(R.string.login_camera_starting));
            loading.set(value);
            android.app.Dialog dialog;
            try { dialog = (android.app.Dialog) field(value, "dialog"); }
            catch (Exception error) { throw new AssertionError(error); }
            shown.set(dialog);
            assertTrue("First frame must have a finite positive width", dialog.getWindow().getAttributes().width > 0);
        };
        main(() -> { ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback); return null; });
        try {
            Activity page = launch(SystemSettingsActivity.class);
            assertNotNull(loading.get());
            await(() -> shown.get().getWindow().getDecorView().getWidth() > 0);
            main(() -> {
                android.app.Dialog dialog = shown.get();
                assertTextFits(dialog.getWindow().getDecorView());
                visible(page, dialog.findViewById(R.id.loading_message));
                assertTrue(textFailures.toString(), textFailures.isEmpty());
                return null;
            });
            screenshot("revision-loading-first-layout-130");
            scenario.close(); scenario = null;
            assertFalse("Page exit must close loading", main(() -> shown.get().isShowing()));
        } finally {
            main(() -> {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
                if (loading.get() != null) loading.get().dismiss();
                return null;
            });
        }
    }

    @Test public void currentPagesKeepTextAndFixedActionsAtLargestFont() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        Class<?>[] pages = {StaffActivity.class, RecordsActivity.class, DepartmentsActivity.class,
                SystemSettingsActivity.class, LicenseSettingsActivity.class, TemperatureSettingsActivity.class,
                LightingSettingsActivity.class, CameraSettingsActivity.class, CheckRulesSettingsActivity.class,
                CheckActivity.class, FaceEnrollmentActivity.class, DevicesActivity.class, RecordDetailActivity.class,
                HomeActivity.class, ResultActivity.class, RecordPhotoActivity.class, SdkDetailsActivity.class,
                LoginActivity.class, SdkActivationScanActivity.class, StaffEditActivity.class};
        for (Class<?> type : pages) {
            Activity page = launch((Class<? extends Activity>) type);
            main(() -> { assertEquals(1.3f, page.getResources().getConfiguration().fontScale, .01f);
                TextView back = page.findViewById(R.id.header_back_label);
                if (back != null && back.isShown()) { visible(page, back); assertEquals(4, back.getText().length()); }
                assertTextFits(page.getWindow().getDecorView());
                return null; });
            screenshot("revision-" + type.getSimpleName() + "-130");
            main(() -> { scrollToEnd(page.getWindow().getDecorView()); return null; });
            instrumentation.waitForIdleSync();
            main(() -> { assertTextFits(page.getWindow().getDecorView()); return null; });
            if (page instanceof SystemSettingsActivity) {
                SdkInstallController install = main(() -> new SdkInstallController((SystemSettingsActivity) page,
                        null, () -> {}, () -> {}, "http://127.0.0.2/unused-layout-only", new SdkInstallController.Platform() {
                    public boolean isInstalled() { return false; }
                    public boolean canInstallPackages() { return false; }
                    public void openPermission() { throw new AssertionError("Layout inspection must not install"); }
                    public void install(File apk) { throw new AssertionError("Layout inspection must not install"); }
                }));
                try {
                    main(() -> { install.onResume(true); return null; });
                    instrumentation.waitForIdleSync();
                    main(() -> {
                        android.app.Dialog dialog = (android.app.Dialog) field(install, "prompt");
                        assertTrue(dialog instanceof ProjectDialog);
                        assertTextFits(dialog.getWindow().getDecorView());
                        visible(page, dialog.findViewById(android.R.id.button1));
                        visible(page, dialog.findViewById(android.R.id.button2));
                        visible(page, dialog.findViewById(android.R.id.button3));
                        return null;
                    });
                    screenshot("revision-sdk-install-prompt-130");
                } finally { main(() -> { install.onDestroy(); return null; }); }
            }
        }
        assertTrue("130% text overflow:\n" + android.text.TextUtils.join("\n", textFailures), textFailures.isEmpty());
    }

    @Test public void choiceWindowIsReadyBeforeFirstFrameAndStableOnReopen() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        Activity page = launch(SystemSettingsActivity.class);
        for (int count : new int[]{0, 3, 30}) {
            ArrayList<String> labels = new ArrayList<>();
            for (int i = 0; i < count; i++) labels.add("选项 " + i + " · 当前门店的部门设置");
            for (int attempt = 0; attempt < 2; attempt++) {
                ArrayList<Rect> frames = new ArrayList<>();
                AlertDialog dialog = main(() -> {
                    AlertDialog choice = DesignChoiceDialog.create(page, "选择部门", "点选后关闭",
                            labels, count - 1, value -> fail("取消和重选不能提交"));
                    ImmersiveUi.show(choice);
                    int margin = page.getResources().getDimensionPixelSize(R.dimen.mc_choice_window_margin);
                    int maxWidth = page.getResources().getDimensionPixelSize(R.dimen.mc_choice_window_max_width);
                    int width = Math.max(1, Math.min(page.getWindow().getDecorView().getWidth() - margin * 2,
                            maxWidth));
                    // 此处仍在 show 的同一次主线程调用中，异步 OnShowListener 尚未执行。
                    assertEquals(width, choice.getWindow().getAttributes().width);
                    assertEquals(android.graphics.Color.TRANSPARENT,
                            ((android.graphics.drawable.ColorDrawable) choice.getWindow().getDecorView().getBackground()).getColor());
                    ListView list = choice.findViewById(R.id.department_filter_options);
                    assertEquals(count, list.getCount());
                    assertEquals(count > 0, list.getLayoutParams().height > 0);
                    View panel = (View) list.getParent();
                    panel.getViewTreeObserver().addOnPreDrawListener(() -> {
                        if (frames.size() < 4) {
                            frames.add(new Rect(panel.getLeft(), panel.getTop(), panel.getRight(), panel.getBottom()));
                            panel.postInvalidateOnAnimation();
                        }
                        return true;
                    });
                    return choice;
                });
                try {
                    await(() -> frames.size() == 4);
                    main(() -> {
                        for (Rect frame : frames) assertEquals("首帧后不得改变弹窗尺寸", frames.get(0), frame);
                        visible(page, dialog.findViewById(R.id.department_filter_close));
                        assertTextFits(dialog.getWindow().getDecorView());
                        ListView list = dialog.findViewById(R.id.department_filter_options);
                        if (count > 0) {
                            assertEquals("打开即定位当前选项", count - 1, list.getLastVisiblePosition());
                            assertEquals(labels.get(count - 1), list.getAdapter().getItem(count - 1));
                        } else assertTrue(dialog.findViewById(R.id.department_filter_empty).isShown());
                        return null;
                    });
                    if (attempt == 1) screenshot("choice-stable-" + count + "-130");
                    main(() -> {
                        if (count == 0) {
                            android.view.MotionEvent outside = android.view.MotionEvent.obtain(0, 0,
                                    android.view.MotionEvent.ACTION_OUTSIDE, -1, -1, 0);
                            try { dialog.onTouchEvent(outside); } finally { outside.recycle(); }
                        } else {
                            ListView list = dialog.findViewById(R.id.department_filter_options);
                            list.performItemClick(list.getChildAt(list.getChildCount() - 1), count - 1, count - 1);
                        }
                        assertFalse("重选与点击遮罩均应关闭且不提交", dialog.isShowing());
                        return null;
                    });
                } finally { main(() -> { dialog.dismiss(); return null; }); }
            }
        }
        assertTrue(textFailures.toString(), textFailures.isEmpty());
    }

    @Test
    public void staffStatusChoicesFitTogetherAndPreserveSelectionAtDefaultAndLargestFont() throws Exception {
        for (int font : new int[]{AppFontSettings.DEFAULT_INDEX, 3}) {
            assertTrue(preferences.edit().putInt(AppFontSettings.KEY, font).commit());
            StaffEditActivity editor = launch(StaffEditActivity.class);
            await(() -> editor.<EditText>findViewById(R.id.staff_edit_name).isEnabled());
            float[] referenceTextSizes = main(() -> {
                assertTrue("字体参照必须使用真实新增员工弹窗", editor.isDialogEditor());
                assertEquals(AppFontSettings.scaleFor(font),
                        editor.getResources().getConfiguration().fontScale, .01f);
                return new float[]{editor.<TextView>findViewById(R.id.staff_edit_dialog_title).getTextSize(),
                        editor.<EditText>findViewById(R.id.staff_edit_name).getTextSize(),
                        editor.<TextView>findViewById(R.id.staff_edit_cancel).getTextSize()};
            });
            screenshot("staff-editor-reference-font" + Math.round(AppFontSettings.scaleFor(font) * 100));
            StaffActivity page = launch(StaffActivity.class);
            await(() -> page.<RecyclerView>findViewById(R.id.staff_list).isEnabled()
                    && count(page.findViewById(R.id.staff_list)) > 0);
            ChoiceSpinner spinner = main(() -> page.findViewById(R.id.staff_enabled_filter));
            java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
            main(() -> {
                assertEquals(AppFontSettings.scaleFor(font),
                        page.getResources().getConfiguration().fontScale, .01f);
                android.widget.AdapterView.OnItemSelectedListener original = spinner.getOnItemSelectedListener();
                assertNotNull(original);
                // 保留真实筛选监听器，同时计数，防止重选或取消导致重复查询。
                spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        changes.incrementAndGet();
                        original.onItemSelected(parent, view, position, id);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                        original.onNothingSelected(parent);
                    }
                });
                return null;
            });
            instrumentation.waitForIdleSync();
            changes.set(0);
            AlertDialog dialog = openStaffStatusDialog(spinner);
            try {
                main(() -> {
                    assertStaffStatusDialogFits(page, dialog, spinner.getSelectedItemPosition());
                    if (page.getResources().getConfiguration().smallestScreenWidthDp >= 720) {
                        assertEquals("选择弹窗标题应与新增员工标题字号一致", referenceTextSizes[0],
                                dialog.<TextView>findViewById(R.id.department_filter_title).getTextSize(), .01f);
                        assertEquals("选择弹窗说明应与新增员工字段字号一致", referenceTextSizes[1],
                                dialog.<TextView>findViewById(R.id.choice_subtitle).getTextSize(), .01f);
                        assertEquals("选择弹窗取消应与新增员工关闭按钮字号一致", referenceTextSizes[2],
                                dialog.<TextView>findViewById(R.id.department_filter_close).getTextSize(), .01f);
                        ListView list = dialog.findViewById(R.id.department_filter_options);
                        for (int position = 0; position < list.getChildCount(); position++) {
                            assertEquals("选择弹窗选项应与新增员工字段字号一致", referenceTextSizes[1],
                                    list.getChildAt(position).<TextView>findViewById(R.id.department_filter_option_name)
                                            .getTextSize(), .01f);
                        }
                    }
                    return null;
                });
                screenshot("staff-status-options-font" + Math.round(AppFontSettings.scaleFor(font) * 100));
                main(() -> {
                    ListView list = dialog.findViewById(R.id.department_filter_options);
                    assertTrue(list.performItemClick(list.getChildAt(1), 1, list.getItemIdAtPosition(1)));
                    return null;
                });
                await(() -> changes.get() == 1 && spinner.getSelectedItemPosition() == 1 && !dialog.isShowing());
                main(() -> {
                    assertEquals("选择启用后应应用真实员工筛选", 1, field(page, "enabledFilter"));
                    return null;
                });
            } finally {
                main(() -> { dialog.dismiss(); return null; });
            }

            AlertDialog repeated = openStaffStatusDialog(spinner);
            try {
                main(() -> {
                    assertStaffStatusDialogFits(page, repeated, 1);
                    ListView list = repeated.findViewById(R.id.department_filter_options);
                    assertTrue(list.performItemClick(list.getChildAt(1), 1, list.getItemIdAtPosition(1)));
                    return null;
                });
                instrumentation.waitForIdleSync();
                main(() -> {
                    assertFalse("重选当前启用状态应关闭弹窗", repeated.isShowing());
                    assertEquals("重选当前状态不得重复筛选", 1, changes.get());
                    assertEquals(1, spinner.getSelectedItemPosition());
                    return null;
                });
            } finally {
                main(() -> { repeated.dismiss(); return null; });
            }

            AlertDialog cancelled = openStaffStatusDialog(spinner);
            try {
                main(() -> {
                    assertStaffStatusDialogFits(page, cancelled, 1);
                    assertTrue(cancelled.findViewById(R.id.department_filter_close).performClick());
                    return null;
                });
                instrumentation.waitForIdleSync();
                main(() -> {
                    assertFalse("取消应关闭弹窗", cancelled.isShowing());
                    assertEquals("取消不得修改员工筛选", 1, changes.get());
                    assertEquals(1, spinner.getSelectedItemPosition());
                    return null;
                });
            } finally {
                main(() -> { cancelled.dismiss(); return null; });
            }
        }
        assertTrue(textFailures.toString(), textFailures.isEmpty());
    }

    private AlertDialog openStaffStatusDialog(ChoiceSpinner spinner) throws Exception {
        main(() -> { assertTrue(spinner.performClick()); return null; });
        AlertDialog dialog = main(() -> (AlertDialog) field(spinner, "choiceDialog"));
        await(() -> dialog.isShowing()
                && dialog.<ListView>findViewById(R.id.department_filter_options).getChildCount() > 0);
        instrumentation.waitForIdleSync();
        return dialog;
    }

    private void assertStaffStatusDialogFits(StaffActivity page, AlertDialog dialog, int selected) {
        TextView title = dialog.findViewById(R.id.department_filter_title);
        assertEquals("启用状态", title.getText().toString());
        visible(page, title);
        visible(page, dialog.findViewById(R.id.department_filter_close));
        ListView list = dialog.findViewById(R.id.department_filter_options);
        assertEquals("启用状态始终有三个选项", 3, list.getCount());
        assertEquals("三个状态应同时完整显示，不需要滚动", 3, list.getChildCount());
        assertEquals(0, list.getFirstVisiblePosition());
        assertEquals(2, list.getLastVisiblePosition());
        String[] labels = {"全部", "启用", "停用"};
        Rect listBounds = screenBounds(list);
        for (int position = 0; position < labels.length; position++) {
            View row = list.getChildAt(position);
            visible(page, row);
            assertTrue("选项行不得被列表边缘裁切：" + labels[position], listBounds.contains(screenBounds(row)));
            TextView label = row.findViewById(R.id.department_filter_option_name);
            assertEquals(labels[position], label.getText().toString());
            visible(page, label);
            TextView marker = row.findViewById(R.id.department_filter_option_selected);
            assertEquals(position == selected ? "•" : "", marker.getText().toString());
            visible(page, marker);
        }
        View panel = (View) list.getParent();
        assertTrue("选择弹窗整体应位于实际页面窗口内",
                screenBounds(page.getWindow().getDecorView()).contains(screenBounds(panel)));
        if (page.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            float density = page.getResources().getDisplayMetrics().density;
            boolean largeWindow = page.getResources().getConfiguration().screenHeightDp >= 480;
            int maxWidthDp = largeWindow ? 480 : 400;
            int maxHeightDp = largeWindow ? 370 : 330;
            assertTrue("横屏选择弹窗宽度不得超过" + maxWidthDp + "dp",
                    panel.getWidth() <= Math.round(maxWidthDp * density));
            assertTrue("横屏三项状态弹窗高度不得超过" + maxHeightDp + "dp",
                    panel.getHeight() <= Math.round(maxHeightDp * density));
        }
        assertTextFits(dialog.getWindow().getDecorView());
    }

    @Test public void listChoicesUseSharedDialogAndKeepSelection() throws Exception {
        preferences.edit().putInt(AppFontSettings.KEY, 3).commit();
        Class<?>[] pages = {StaffActivity.class, RecordsActivity.class, TemperatureSettingsActivity.class};
        int[] ids = {R.id.staff_enabled_filter, R.id.records_filter, R.id.settings_temperature_mode};
        for (int i = 0; i < pages.length; i++) {
            Activity page = launch((Class<? extends Activity>) pages[i]);
            final int id = ids[i];
            ChoiceSpinner spinner = main(() -> page.findViewById(id));
            main(() -> { spinner.performClick(); return null; });
            AlertDialog dialog = main(() -> (AlertDialog) field(spinner, "choiceDialog"));
            await(() -> dialog.isShowing() && dialog.<ListView>findViewById(R.id.department_filter_options).getChildCount() > 0);
            main(() -> {
                visible(page, dialog.findViewById(R.id.department_filter_close));
                ListView list = dialog.findViewById(R.id.department_filter_options);
                assertEquals(spinner.getCount(), list.getCount());
                list.performItemClick(list.getChildAt(1), 1, list.getItemIdAtPosition(1));
                return null;
            });
            await(() -> spinner.getSelectedItemPosition() == 1 && !dialog.isShowing());
        }
    }

    @Test public void confirmedDepartmentDeleteRemovesOnlySelectedEmptyRow() throws Exception {
        long disposable = app.io.submit(() -> fixture.saveDepartment(0, "删除回归专用部门")).get(10, TimeUnit.SECONDS);
        DepartmentsActivity page = launch(DepartmentsActivity.class);
        await(() -> page.<RecyclerView>findViewById(R.id.department_list).getChildCount() > 0);
        main(() -> {
            RecyclerView list = page.findViewById(R.id.department_list);
            for (int i = 0; i < list.getChildCount(); i++) {
                Object row = item(list, list.getChildAdapterPosition(list.getChildAt(i)));
                if (row instanceof JSONObject && ((JSONObject) row).optLong("id") == disposable) {
                    list.getChildAt(i).findViewById(R.id.department_row_delete).performClick();
                    assertNull(departmentEditor(page));
                    return null;
                }
            }
            throw new AssertionError("Delete target missing");
        });
        main(() -> { confirmation(page).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return null; });
        await(() -> !(Boolean) field(page, "deletingDepartment"));
        assertNull(app.io.submit(() -> fixture.department(disposable)).get(10, TimeUnit.SECONDS));
        assertNotNull(app.io.submit(() -> fixture.department(emptyDepartment)).get(10, TimeUnit.SECONDS));
        assertNotNull(app.io.submit(() -> fixture.staff(1)).get(10, TimeUnit.SECONDS));
    }

    @Test public void staffDeletePreservesLocalRowUntilSdkConfirms() throws Exception {
        final long target = 10_001;
        final String recordId = "ui-delete-person";
        long personId = app.io.submit(() -> {
            fixture.saveStaff(new JSONObject().put("id", target).put("name", "独立人员标识删除夹具")
                    .put("staff_no", "DELETE-PERSON-10001").put("enabled", true));
            fixture.saveRecord(new JSONObject().put("id", recordId).put("staff_id", target)
                    .put("staff_name", "独立人员标识删除夹具").put("created_at", System.currentTimeMillis()));
            return fixture.staff(target).getLong("person_id");
        }).get(10, TimeUnit.SECONDS);
        assertTrue("删除回归必须区分本地主键和 SDK 人员标识", personId > 0 && personId != target);
        java.util.concurrent.atomic.AtomicInteger resultCode = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong forgottenPerson = new java.util.concurrent.atomic.AtomicLong();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() {
                    @Override public void onMessage(okhttp3.WebSocket connection, String text) {
                        try {
                            JSONObject query = new JSONObject(text), data = new JSONObject();
                            String command = query.getString("cmd");
                            int code = 0;
                            if ("forget".equals(command)) {
                                forgottenPerson.set(query.getJSONObject("params").getLong("person_id"));
                                code = resultCode.get();
                                writes.incrementAndGet();
                            }
                            if ("getStatus".equals(command)) data.put("sdk_state", "READY")
                                    .put("model_ready", true).put("certificate_valid", true).put("device_id", "UI-DELETE-FIXTURE");
                            connection.send(new JSONObject().put("requestId", query.getString("requestId"))
                                    .put("code", code).put("message", code == 0 ? "" : "fixture rejection").put("data", data).toString());
                        } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
                    }
                });
            }
        });
        StaffActivity page = launch(StaffActivity.class);
        await(() -> page.sdk.isConnected() && page.<RecyclerView>findViewById(R.id.staff_list).getChildCount() > 0);
        main(() -> { page.<EditText>findViewById(R.id.staff_search).setText("DELETE-PERSON-10001"); return null; });
        await(() -> count(page.findViewById(R.id.staff_list)) == 1
                && page.<RecyclerView>findViewById(R.id.staff_list).isEnabled()
                && page.<RecyclerView>findViewById(R.id.staff_list).getChildCount() == 1);
        for (int code : new int[]{-1, 0}) {
            resultCode.set(code);
            int expected = writes.get() + 1;
            main(() -> {
                assertEquals(target, item(page.findViewById(R.id.staff_list), 0).getLong("id"));
                page.<RecyclerView>findViewById(R.id.staff_list).getChildAt(0).findViewById(R.id.staff_row_delete).performClick();
                ((AlertDialog) field(page, "deleteConfirmation")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return null;
            });
            await(() -> serverFailure.get() != null || writes.get() == expected && !(Boolean) field(page, "deletingStaff"));
            assertNull("删除夹具不得出现协议错误", serverFailure.get());
            assertEquals("forget 必须发送 SDK 人员标识，不能发送本地主键", personId, forgottenPerson.get());
            JSONObject row = app.io.submit(() -> fixture.staff(target)).get(10, TimeUnit.SECONDS);
            if (code != 0) {
                assertNotNull("SDK 拒绝时必须保留员工资料", row);
                assertEquals(personId, row.getLong("person_id"));
                assertNotNull(app.io.submit(() -> fixture.staffByPersonId(personId)).get(10, TimeUnit.SECONDS));
            } else {
                assertNull("确认删除后员工不再出现在可见名册", row);
                assertNull(app.io.submit(() -> fixture.staffByPersonId(personId)).get(10, TimeUnit.SECONDS));
                await(() -> count(page.findViewById(R.id.staff_list)) == 0);
            }
            final int deleted = code == 0 ? 1 : 0;
            app.io.submit(() -> {
                try (android.database.Cursor stored = fixture.getReadableDatabase().query("staff",
                        new String[]{"person_id", "deleted"}, "id=?", new String[]{String.valueOf(target)},
                        null, null, null)) {
                    assertTrue("软删除仍须保留人员标识占用行", stored.moveToFirst());
                    assertEquals(personId, stored.getLong(0));
                    assertEquals(deleted, stored.getInt(1));
                    assertFalse(stored.moveToNext());
                }
            }).get(10, TimeUnit.SECONDS);
            assertSame(page, main(this::resumed));
        }
        JSONObject retained = app.io.submit(() -> fixture.record(recordId)).get(10, TimeUnit.SECONDS);
        assertNotNull("删除员工不删除历史晨检记录", retained);
        assertEquals(target, retained.getLong("staff_id"));
    }

    private Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void loadAll(Activity page, int listId, int footerId, int total) throws Exception {
        await(() -> count(page.findViewById(listId)) > 0
                && page.<RecyclerView>findViewById(listId).isEnabled());
        for (int attempts = 0; attempts < 10; attempts++) {
            int before = main(() -> count(page.findViewById(listId)));
            if (before == total) break;
            main(() -> { page.findViewById(footerId).performClick(); return null; });
            await(() -> count(page.findViewById(listId)) > before
                    && page.<RecyclerView>findViewById(listId).isEnabled());
        }
        assertEquals(total, (int) main(() -> count(page.findViewById(listId))));
        main(() -> { page.findViewById(footerId).performClick(); return null; });
        instrumentation.waitForIdleSync();
        assertEquals(total, (int) main(() -> count(page.findViewById(listId))));
    }

    private void assertDepartmentColumnsAligned(DepartmentsActivity page) {
        android.view.ViewGroup header = page.findViewById(R.id.department_table_header);
        RecyclerView list = page.findViewById(R.id.department_list);
        assertEquals(3, header.getChildCount());
        int checkedRows = 0;
        for (int index = 0; index < list.getChildCount(); index++) {
            Object item = item(list, list.getChildAdapterPosition(list.getChildAt(index)));
            if (!(item instanceof JSONObject)) continue;
            checkedRows++;
            View row = list.getChildAt(index);
            TextView name = row.findViewById(R.id.business_row_name);
            TextView staff = row.findViewById(R.id.department_row_staff);
            TextView edit = row.findViewById(R.id.business_row_state);
            TextView delete = row.findViewById(R.id.department_row_delete);
            Rect[] cells = {screenBounds(name), screenBounds(staff), screenBounds(edit)};
            cells[2].right = screenBounds(delete).right;
            for (int column = 0; column < cells.length; column++) {
                Rect title = screenBounds(header.getChildAt(column));
                assertEquals("部门表头与数据列屏幕左边界一致", title.left, cells[column].left, 1);
                assertEquals("部门表头与数据列屏幕右边界一致", title.right, cells[column].right, 1);
            }
            TextView titleName = (TextView) header.getChildAt(0);
            float titleStart = screenBounds(titleName).left + titleName.getCompoundPaddingLeft()
                    + titleName.getLayout().getLineLeft(0);
            float nameStart = screenBounds(name).left + name.getCompoundPaddingLeft()
                    + name.getLayout().getLineLeft(0);
            if (((JSONObject) item).optLong("parent_id") == 0)
                assertEquals("根部门名称文字与表头左侧对齐", titleStart, nameStart, 1f);
            else {
                assertTrue("子部门保留层级缩进", name.getPaddingStart() > 0);
                assertTrue("子部门文字保留层级偏移", nameStart > titleStart);
            }
            assertEquals("人数文字与表头居中对齐", screenBounds(header.getChildAt(1)).exactCenterX(),
                    textLineCenter(staff), 1f);
            assertEquals("操作按钮文字整体与表头居中对齐", screenBounds(header.getChildAt(2)).exactCenterX(),
                    (textLineCenter(edit) + textLineCenter(delete)) / 2f, 1f);
        }
        assertTrue("必须实际检查部门数据，不能仅检查列表底栏", checkedRows > 0);
    }

    private float textLineCenter(TextView view) {
        return screenBounds(view).left + view.getCompoundPaddingLeft()
                + (view.getLayout().getLineLeft(0) + view.getLayout().getLineRight(0)) / 2f;
    }

    private AlertDialog confirmation(DepartmentsActivity page) throws Exception {
        return (AlertDialog) field(page, "deleteConfirmation");
    }

    private android.app.Dialog departmentEditor(DepartmentsActivity page) {
        androidx.fragment.app.Fragment fragment = page.getSupportFragmentManager()
                .findFragmentByTag(DepartmentEditorDialog.TAG);
        if (!(fragment instanceof DepartmentEditorDialog)) return null;
        android.app.Dialog dialog = ((DepartmentEditorDialog) fragment).getDialog();
        return dialog != null && dialog.isShowing() ? dialog : null;
    }

    private <T extends Activity> T launch(Class<T> type) throws Exception {
        if (scenario != null) scenario.close();
        main(() -> { finishPages(); return null; });
        Intent intent = new Intent(app, type);
        if (type == ResultActivity.class || type == RecordPhotoActivity.class || type == RecordDetailActivity.class)
            intent.putExtra("record_id", "ui-1");
        if (type == StaffEditActivity.class) intent.putExtra(StaffEditActivity.EXTRA_DIALOG, true);
        scenario = ActivityScenario.launch(intent);
        await(() -> type.isInstance(resumed()));
        T page = main(() -> type.cast(resumed()));
        orient(page);
        return page;
    }

    private void orient(Activity page) throws Exception {
        boolean portrait = "portrait".equals(InstrumentationRegistry.getArguments().getString("design_orientation"));
        main(() -> { page.setRequestedOrientation(portrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); return null; });
        await(() -> page.getResources().getConfiguration().orientation == (portrait ? 1 : 2));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
    }

    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
        File directory = new File(app.getExternalFilesDir(null), "design-ui");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { image.recycle(); }
    }

    private void visible(Activity page, View view) {
        assertNotNull(view); assertTrue(view.isShown());
        Rect bounds = new Rect(); assertTrue(view.getGlobalVisibleRect(bounds));
        assertEquals("Control must not be vertically clipped", view.getHeight(), bounds.height());
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) assertTrue("Text must fit: " + text.getText(),
                    text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        assertTrue(bounds.bottom <= page.getWindow().getDecorView().getHeight());
    }

    private void assertTextFits(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (text.getText().length() > 0 && text.getWidth() > 0 && text.getHeight() > 0 && layout != null) {
                String id = text.getId() == View.NO_ID ? "no-id" : text.getResources().getResourceEntryName(text.getId());
                String label = id + ": " + text.getText();
                int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                if (layout.getHeight() > availableHeight + 1)
                    textFailures.add("Height " + layout.getHeight() + ">" + availableHeight + " " + label);
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                for (int line = 0; line < layout.getLineCount(); line++) {
                    if (layout.getEllipsisCount(line) == 0 && layout.getLineWidth(line) > width + 2)
                        textFailures.add("Width " + layout.getLineWidth(line) + ">" + width + " " + label);
                    if (text.isClickable() && !(text instanceof EditText) && layout.getEllipsisCount(line) != 0)
                        textFailures.add("Action ellipsized " + label);
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTextFits(group.getChildAt(i));
        }
    }

    private void scrollToEnd(View view) {
        if (!view.isShown()) return;
        if (view instanceof android.widget.ScrollView) ((android.widget.ScrollView) view).fullScroll(View.FOCUS_DOWN);
        if (view instanceof ListView) {
            ListView list = (ListView) view;
            if (list.getCount() > 0) list.setSelection(list.getCount() - 1);
        }
        if (view instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) view;
            if (count(list) > 0) scroll(list, count(list) - 1, 0);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scrollToEnd(group.getChildAt(i));
        }
    }

    private Rect screenBounds(View view) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        return new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    }

    private Activity resumed() {
        for (Activity page : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) return page;
        return null;
    }
    private void finishPages() {
        for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.CREATED, Stage.PAUSED, Stage.STOPPED})
            for (Activity page : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))) page.finish();
    }
    private void await(Callable<Boolean> condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) { if (main(condition)) return; SystemClock.sleep(50); }
        fail("Timed out waiting for UI state");
    }
    private <T> T main(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.call()); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }
}
