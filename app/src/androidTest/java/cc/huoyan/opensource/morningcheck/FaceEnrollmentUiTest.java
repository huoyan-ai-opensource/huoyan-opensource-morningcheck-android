package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 未选员工时的只读验收；不打开镜头、不创建员工、不写配置、不发送学习或业务云写请求。 */
@RunWith(AndroidJUnit4.class)
public final class FaceEnrollmentUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MorningApplication app;
    private ActivityScenario<FaceEnrollmentActivity> scenario;
    private Map<String, String> originalStaff;
    private Map<String, ?> originalPreferences;
    private long originalWriteVersion;
    private boolean hardwareEnabled;

    @Before public void checkReadOnlyPreconditions() throws Exception {
        hardwareEnabled = "true".equals(InstrumentationRegistry.getArguments()
                .getString("enableFaceEnrollmentCamera"));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        // 手机未选员工先展开完整名单；只读验收不能因此误开镜头。
        assertEquals("运行前应已授予相机权限，测试不能弹出权限申请或改变授权",
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA));
        assertNull("测试只使用无效员工 0，不能借用真实员工", app.io.submit(() -> app.store.staff(0))
                .get(10, TimeUnit.SECONDS));
        originalStaff = staffSnapshot();
        originalPreferences = new HashMap<>(app.getSharedPreferences("device", Context.MODE_PRIVATE).getAll());
        instrumentation.runOnMainSync(() -> {
            assertFalse("无效员工不能有尚未收尾的人脸操作", app.faceOperations.isWriting(0));
            originalWriteVersion = app.faceOperations.version(0);
        });
    }

    @After public void closePageWithoutChangingDeviceData() throws Exception {
        if (scenario != null) scenario.close();
        if (originalStaff != null) {
            // 先清空之前提交的本地读队列，再核对没有出现学习标记或员工资料变化。
            assertEquals("页面验收不得改写任何员工档案", originalStaff, staffSnapshot());
            assertEquals("页面验收不得保存或覆盖设备配置", originalPreferences,
                    app.getSharedPreferences("device", Context.MODE_PRIVATE).getAll());
            instrumentation.runOnMainSync(this::assertNoLearningWrite);
        }
    }

    @Test public void missingEmployeeDisablesLearningBeforeAndAfterRecreation() throws Exception {
        // 故意不传 EXTRA_STAFF_ID，验证外部漏传时不能误选第一名员工。
        launch(false);
        assertMissingEmployeePage();
        scenario.onActivity(activity -> {
            // performClick 可绕过 enabled 的触控限制，用于验证业务入口自身也有无员工防护。
            activity.findViewById(R.id.enrollment_capture).performClick();
            assertNoLearningWrite();
        });
        drainLocalCallbacks();
        assertMissingEmployeePage();

        scenario.recreate();
        awaitPage();
        drainLocalCallbacks();
        assertMissingEmployeePage();
        scenario.onActivity(activity -> assertNoLearningWrite());
        holdForScreenshot();
    }

    @Test public void backgroundAndResumeKeepMissingEmployeeInWorkspaceWithoutCamera() {
        launch(true);
        scenario.onActivity(activity -> {
            assertNull("显式无效员工 0 也不能建立预览相机宿主", activity.camera);
            assertNull(activity.findViewById(R.id.camera_texture));
        });

        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(activity -> {
            assertNull("后台必须释放页面相机", activity.camera);
            assertNull("后台必须清空旧画面，禁止再次学习旧帧", activity.latestFrame);
            assertNoLearningWrite();
        });

        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPage();
        scenario.onActivity(activity -> {
            assertNull("回前台仍无有效员工，不能建立相机宿主", activity.camera);
            assertNull("回前台不能为无效员工插入 TextureView", activity.findViewById(R.id.camera_texture));
            assertNoLearningWrite();
        });
        assertMissingEmployeePage();
    }

    @Test public void hardwareEnabledInvalidEmployeeStillCannotOpenCameraAfterReturning() {
        assumeTrue("现场相机授权回归需要 enableFaceEnrollmentCamera=true", hardwareEnabled);
        launch(true);
        scenario.onActivity(activity -> {
            assertNull("硬件可用也不能为无效员工打开相机", activity.camera);
            assertNull(activity.latestFrame);
        });

        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(activity -> {
            assertNull("后台释放后不能保留上一帧", activity.latestFrame);
            assertNull("后台释放后不能保留相机宿主", activity.camera);
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        awaitPage();
        scenario.onActivity(activity -> {
            assertNull("返回无效员工工作台不能收到真实画面", activity.latestFrame);
            assertNull(activity.camera);
            assertNoLearningWrite();
        });
        assertMissingEmployeePage();
        holdForScreenshot();
    }

    // 主机可显式留出截图窗口，默认不等待；不向设备存储写照片。
    private void holdForScreenshot() {
        String hold = InstrumentationRegistry.getArguments().getString("previewHoldMs", "0");
        long duration = Long.parseLong(hold);
        assertTrue("截图等待时间必须在 0 到 10000 毫秒之间", duration >= 0 && duration <= 10_000);
        if (duration > 0) {
            Bundle status = new Bundle();
            status.putString("screenshot_ready", "true");
            status.putString("scene", "workspace-empty");
            instrumentation.sendStatus(2, status);
            SystemClock.sleep(duration);
        }
    }

    private void launch(boolean explicitId) {
        Intent intent = new Intent(app, FaceEnrollmentActivity.class);
        if (explicitId) intent.putExtra(FaceEnrollmentActivity.EXTRA_STAFF_ID, 0L);
        // Instrumentation 可启动非导出 Activity，不为测试增加导出入口。
        scenario = ActivityScenario.launch(intent);
        awaitPage();
    }

    private void awaitPage() {
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            scenario.onActivity(activity -> {
                View selection = activity.findViewById(R.id.enrollment_selection);
                ready.set(activity.loaded && selection != null && selection.getWidth() > 0
                        && activity.findViewById(R.id.enrollment_selection).isShown()
                        && activity.findViewById(R.id.enrollment_staff_list) != null);
            });
            return ready.get();
        }, "工作台应完成本地员工读取并显示员工选择区");
    }

    private void assertMissingEmployeePage() {
        scenario.onActivity(activity -> {
            assertTrue("未传员工或传无效员工应显示选择列表", activity.findViewById(R.id.enrollment_selection).isShown());
            assertTrue("员工选择应通过浮层展示", activity.findViewById(R.id.enrollment_selection_overlay).isShown());
            assertNull("未选员工不能建立相机宿主", activity.camera);
            assertNull("未选员工不能建立 TextureView", activity.findViewById(R.id.camera_texture));
            assertNull("未选员工不能保留真实相机画面", activity.latestFrame);
            assertFalse("没有有效员工时学习按钮必须禁用",
                    activity.findViewById(R.id.enrollment_capture).isEnabled());
            RecyclerView employeeGrid = activity.findViewById(R.id.enrollment_staff_list);
            boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            assertEquals("员工选择横屏三列、竖屏两列", landscape ? 3 : 2, ((GridLayoutManager) employeeGrid.getLayoutManager()).getSpanCount());
            assertFullyVisible(activity, R.id.enrollment_search, R.id.enrollment_staff_list,
                    R.id.enrollment_next, R.id.enrollment_selection_close);
            assertEquals("员工列表按上拉加载，不显示旧的上一页按钮", View.GONE,
                    activity.findViewById(R.id.enrollment_previous).getVisibility());
            assertEquals("员工列表不显示旧的页码栏", View.GONE,
                    activity.findViewById(R.id.enrollment_page_info).getVisibility());
            assertNoLearningWrite();
            assertTrue(activity.findViewById(R.id.enrollment_selection_close).performClick());
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            assertFalse("取消选择应关闭浮层并显示采集空状态", activity.findViewById(R.id.enrollment_selection_overlay).isShown());
            assertNull("关闭选择也不能为无效员工打开镜头", activity.camera);
            assertNull(activity.findViewById(R.id.camera_texture));
            assertNull(activity.latestFrame);
            assertFullyVisible(activity, R.id.enrollment_staff_name, R.id.enrollment_choose_staff,
                    R.id.enrollment_preview_status, R.id.enrollment_capture,
                    R.id.enrollment_preview, R.id.enrollment_samples_panel, R.id.enrollment_faces,
                    R.id.enrollment_faces_clear);
            assertFalse("已录入图片标题行应隐藏", activity.findViewById(R.id.enrollment_faces_info).isShown());
            assertEquals("摄像头配置按钮在工作台隐藏", View.GONE,
                    activity.findViewById(R.id.enrollment_camera_settings).getVisibility());
            assertEquals("照片读取由页面自动完成，隐藏独立刷新按钮", View.GONE,
                    activity.findViewById(R.id.enrollment_faces_refresh).getVisibility());
            assertPreviewStatusPosition(activity);
            assertFooterActionsPosition(activity);
            activity.findViewById(R.id.enrollment_capture).performClick();
            assertNoLearningWrite();
            assertTrue(activity.findViewById(R.id.enrollment_choose_staff).performClick());
        });
        instrumentation.waitForIdleSync();
    }

    private void assertFullyVisible(FaceEnrollmentActivity activity, int... ids) {
        for (int id : ids) {
            View view = activity.findViewById(id);
            Rect visible = new Rect();
            assertTrue("当前阶段的列表与操作必须在屏幕中可见：" + id, view.getGlobalVisibleRect(visible));
            assertFalse("布局不能把列表或操作挤成零面积", visible.isEmpty());
            assertEquals("当前页面控件高度不能被父容器裁切", view.getHeight(), visible.height());
            assertEquals("当前页面控件宽度不能被父容器裁切", view.getWidth(), visible.width());
        }
    }

    private void assertPreviewStatusPosition(FaceEnrollmentActivity activity) {
        Rect preview = new Rect(), status = new Rect();
        assertTrue(activity.findViewById(R.id.enrollment_preview).getGlobalVisibleRect(preview));
        assertTrue(activity.findViewById(R.id.enrollment_preview_status).getGlobalVisibleRect(status));
        assertTrue("人脸状态必须完整放在预览内部", preview.contains(status));
        assertTrue("人脸状态应靠预览右侧显示", preview.right - status.right <= preview.width() / 4);
        assertTrue("人脸状态应靠预览顶部显示", status.top - preview.top <= preview.height() / 4);
    }

    private void assertFooterActionsPosition(FaceEnrollmentActivity activity) {
        View captureView = activity.findViewById(R.id.enrollment_capture);
        Rect capture = new Rect(), clear = new Rect(), footer = new Rect(), content = new Rect();
        assertTrue(captureView.getGlobalVisibleRect(capture));
        assertTrue(activity.findViewById(R.id.enrollment_faces_clear).getGlobalVisibleRect(clear));
        assertTrue(((View) captureView.getParent()).getGlobalVisibleRect(footer));
        assertTrue(activity.findViewById(R.id.page_content).getGlobalVisibleRect(content));
        assertEquals("录入与遗忘操作应固定在页面底部", content.bottom, footer.bottom);
        assertTrue("拍照录入应位于底栏左侧", capture.centerX() < footer.centerX());
        assertTrue("遗忘应位于底栏右侧", clear.centerX() > footer.centerX());
        assertTrue("两个操作之间应保留明确空白", clear.left - capture.right >= Math.min(capture.width(), clear.width()) / 2);
        assertEquals("底部两个操作应在同一行", capture.centerY(), clear.centerY());
    }

    /** 只核对无效员工的共享写锁和代次，不构造帧、不调用 SDK register。 */
    private void assertNoLearningWrite() {
        assertFalse("无效员工页面不能占用人脸写锁", app.faceOperations.isWriting(0));
        assertEquals("重建或恢复页面不能发起或重放人脸学习", originalWriteVersion,
                app.faceOperations.version(0));
    }

    private Map<String, String> staffSnapshot() throws Exception {
        return app.io.submit(() -> {
            Map<String, String> snapshot = new TreeMap<>();
            for (JSONObject person : app.store.staff()) snapshot.put(person.optString("id"), person.toString());
            return snapshot;
        }).get(10, TimeUnit.SECONDS);
    }

    private void drainLocalCallbacks() throws Exception {
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
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
