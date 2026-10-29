package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/** 用户指定的真机轮换验收；显式开启后才提交真实同模型激活。 */
@RunWith(AndroidJUnit4.class)
public final class RealModelReactivationDeviceTest {
    private final Instrumentation ins = InstrumentationRegistry.getInstrumentation();

    @Test public void rotatedDeviceCanSelectCurrentThenReactivateSameModel() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("realModelTest")));
        MorningApplication app = (MorningApplication) ins.getTargetContext().getApplicationContext();
        File credential = new File(app.getFilesDir(), "model-test-license");
        String code = new String(Files.readAllBytes(credential.toPath()), StandardCharsets.UTF_8).trim().replace("-", "");
        try (ActivityScenario<LicenseSettingsActivity> scenario = ActivityScenario.launch(new Intent(app,
                LicenseSettingsActivity.class))) {
            await(() -> main(() -> app.sdkSession().viewState().ready
                    && app.sdkSession().certificate().has("model_code")), 30000);
            long[] expiry = new long[1];
            scenario.onActivity(a -> {
                assertEquals("morningcheck", app.sdkSession().certificate().optString("model_code"));
                assertTrue("轮换后未保存原激活码", app.sdkSession().certificate().isNull("activation_serial")
                        || app.sdkSession().certificate().optString("activation_serial", "").isEmpty());
                expiry[0] = app.sdkSession().certificate().optLong("expires_at");
                ActivationFlowFragment.switchModel(a);
            });
            await(() -> shown(scenario), 15000);
            screenshot("rotation-current-model-list");
            scenario.onActivity(a -> {
                ListView list = choice(a).findViewById(R.id.department_filter_options);
                assertEquals("当前授权只有一个模型", 1, list.getAdapter().getCount());
                list.performItemClick(list.getChildAt(0), 0, 0);
            });
            // 跨过周期刷新，确认当前项不会重新弹列表或提交激活。
            SystemClock.sleep(5500);
            scenario.onActivity(a -> {
                assertNull(choice(a));
                assertNull(input(a));
                assertFalse(selection(a, app).submitted);
                assertTrue(a.findViewById(R.id.settings_switch_model).isEnabled());
                a.findViewById(R.id.settings_activate).performClick();
            });
            await(() -> {
                AtomicBoolean ready = new AtomicBoolean();
                scenario.onActivity(a -> ready.set(input(a) != null && input(a).getDialog() != null));
                return ready.get();
            }, 15000);
            screenshot("rotation-reactivation-input");
            scenario.onActivity(a -> {
                View root = input(a).getDialog().getWindow().getDecorView();
                root.findViewById(R.id.activation_code_clear).performClick();
                for (char c : code.toCharArray()) root.findViewWithTag("activation-key-" + c).performClick();
                assertTrue(root.findViewById(R.id.activation_code_activate).isEnabled());
                root.findViewById(R.id.activation_code_activate).performClick();
            });
            await(() -> shown(scenario), 15000);
            scenario.onActivity(a -> {
                ListView list = choice(a).findViewById(R.id.department_filter_options);
                list.performItemClick(list.getChildAt(0), 0, 0);
            });
            await(() -> main(() -> app.sdkSession().activation().phase == MorningSdkSession.ActivationPhase.SUCCEEDED), 240000);
            await(() -> main(() -> !app.sdkSession().certificate().isNull("activation_serial")
                    && !app.sdkSession().certificate().optString("activation_serial", "").isEmpty()), 15000);
            scenario.onActivity(a -> {
                assertEquals("morningcheck", app.sdkSession().certificate().optString("model_code"));
                assertEquals("重新激活不重置有效期", expiry[0], app.sdkSession().certificate().optLong("expires_at"));
                assertTrue(app.sdkSession().viewState().ready);
                assertNull(input(a));
                assertNull(choice(a));
            });
            screenshot("rotation-same-model-success");
        } finally {
            credential.delete();
        }
    }

    private static ModelSelectionSession selection(LicenseSettingsActivity activity, MorningApplication app) {
        Fragment flow = activity.getSupportFragmentManager().findFragmentByTag("modelActivation");
        return new ViewModelProvider(flow).get(ModelSelectionViewModel.class).session(app.sdkSession());
    }

    private boolean main(BooleanSupplier value) {
        AtomicBoolean result = new AtomicBoolean();
        ins.runOnMainSync(() -> result.set(value.getAsBoolean()));
        return result.get();
    }

    private boolean shown(ActivityScenario<LicenseSettingsActivity> scenario) {
        AtomicBoolean result = new AtomicBoolean();
        scenario.onActivity(a -> result.set(choice(a) != null && choice(a).isShowing()));
        return result.get();
    }

    private static SdkActivationCodeDialog input(LicenseSettingsActivity activity) {
        Fragment flow = activity.getSupportFragmentManager().findFragmentByTag("modelActivation");
        if (flow == null) return null;
        Fragment fragment = flow.getChildFragmentManager().findFragmentByTag(SdkActivationCodeDialog.TAG);
        return fragment instanceof SdkActivationCodeDialog ? (SdkActivationCodeDialog) fragment : null;
    }

    private static AlertDialog choice(LicenseSettingsActivity activity) {
        try {
            Fragment flow = activity.getSupportFragmentManager().findFragmentByTag("modelActivation");
            if (flow == null) return null;
            java.lang.reflect.Field field = ActivationFlowFragment.class.getDeclaredField("modelDialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(flow);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void await(BooleanSupplier condition, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < end);
        fail("等待实际界面或 SDK 状态超时");
    }

    private void screenshot(String name) throws Exception {
        ins.waitForIdleSync();
        SystemClock.sleep(300);
        File directory = new File(ins.getTargetContext().getExternalFilesDir(null), "real-model-review");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap screenshot = ins.getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            screenshot.recycle();
        }
    }
}
