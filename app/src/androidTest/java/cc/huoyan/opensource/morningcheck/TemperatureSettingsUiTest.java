package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.hardware.TemperatureMeasurementMode;
import cc.huoyan.opensource.morningcheck.hardware.TemperatureSource;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** 隔离 SDK 与业务云，验证持续测温的数值更新、停止和页面资源释放。 */
@RunWith(AndroidJUnit4.class)
public final class TemperatureSettingsUiTest {
    private static final String MISSING_DEVICE = "/dev/huoyan-temperature-ui-test-missing";
    private Instrumentation instrumentation;
    private MorningApplication app;
    private SharedPreferences device;
    private Map<String, Object> originalPreferences;
    private MockWebServer endpoint;
    private ActivityScenario<TemperatureSettingsActivity> scenario;
    private final AtomicInteger businessRequests = new AtomicInteger();

    @Before public void isolateEndpointsAndDeviceConfiguration() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>();
        for (Map.Entry<String, ?> entry : device.getAll().entrySet())
            originalPreferences.put(entry.getKey(), entry.getValue() instanceof Set
                    ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().startsWith("/cloud"))
                    businessRequests.incrementAndGet();
                return new MockResponse().setResponseCode(503).setBody("测温设置测试，不连接真实 SDK");
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false)
                .putString("temperature_mode", "串口被动上报")
                .remove("temperature_measurement_mode").remove("temperature_measurement_path")
                .remove("temperature_measurement_baud")
                .putString("serial_path", MISSING_DEVICE).putString("baud", "115200").commit());
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    @After public void closePageAndRestoreDeviceConfiguration() throws Exception {
        try {
            if (scenario != null) scenario.close();
        } finally {
            try {
                if (app != null) instrumentation.runOnMainSync(() -> app.sdkSession().disconnect());
                if (device != null && originalPreferences != null) restorePreferences();
            } finally {
                if (endpoint != null) endpoint.shutdown();
            }
        }
        assertEquals("设置试读不能访问业务云", 0, businessRequests.get());
    }

    @Test public void configurationChangesSaveImmediatelyWithoutStartingTemperatureRead() {
        AtomicInteger writes = new AtomicInteger();
        SharedPreferences.OnSharedPreferenceChangeListener observer = (preferences, key) -> {
            if ("temperature_mode".equals(key) || "serial_path".equals(key) || "baud".equals(key))
                writes.incrementAndGet();
        };
        device.registerOnSharedPreferenceChangeListener(observer);
        try {
            launchPage();
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                assertNull("页面打开不能自动启动串口", source(activity));
                assertNoSaveControl(activity.getResources(), activity.getPackageName(), activity.formView);
                assertEquals("初始化表单不能重新写入配置", 0, writes.get());
                configure(activity, MISSING_DEVICE + "-live", "38400", true);
                assertSavedConfiguration(MISSING_DEVICE + "-live", "38400", "串口主动查询");
                assertNull("选择配置也不能自动启动测温测试", source(activity));
            });
            scenario.close();
            scenario = null;
            launchPage();
            scenario.onActivity(activity -> {
                assertFormConfiguration(activity, MISSING_DEVICE + "-live", "38400", "串口主动查询");
                assertSavedConfiguration(MISSING_DEVICE + "-live", "38400", "串口主动查询");
                assertNull(source(activity));
            });
        } finally {
            device.unregisterOnSharedPreferenceChangeListener(observer);
        }
    }

    @Test public void latestSavedConfigurationWinsOverRestoredOldForm() {
        launchPage();
        scenario.onActivity(activity -> {
            configure(activity, MISSING_DEVICE + "-live", "38400", true);
            activity.formState.putString("text:" + R.id.settings_serial_path, MISSING_DEVICE + "-stale");
            activity.formState.putString("text:" + R.id.settings_baud, "9600");
            activity.formState.putInt("selection:" + R.id.settings_temperature_mode, 0);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFormConfiguration(activity, MISSING_DEVICE + "-live", "38400", "串口主动查询");
            assertSavedConfiguration(MISSING_DEVICE + "-live", "38400", "串口主动查询");
            assertNull(source(activity));
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        assertTrue(device.edit().putString("serial_path", MISSING_DEVICE + "-latest")
                .putString("baud", "57600").putString("temperature_mode", "串口被动上报").commit());
        scenario.recreate();
        scenario.moveToState(Lifecycle.State.RESUMED);
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            assertFormConfiguration(activity, MISSING_DEVICE + "-latest", "57600", "串口被动上报");
            assertSavedConfiguration(MISSING_DEVICE + "-latest", "57600", "串口被动上报");
            assertNull("恢复最新配置不能恢复旧的在途测试", source(activity));
        });
    }

    @Test public void eachValidFieldPersistsIndependentlyOfAnotherInvalidField() {
        launchPage();
        scenario.onActivity(activity -> {
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText("/dev/");
            ((TextView) activity.findViewById(R.id.settings_baud)).setText("38400");
            selectMode(activity, true);
            assertSavedConfiguration(MISSING_DEVICE, "38400", "串口主动查询");
            ((TextView) activity.findViewById(R.id.settings_baud)).setText("0");
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText(MISSING_DEVICE + "-valid");
            assertSavedConfiguration(MISSING_DEVICE + "-valid", "38400", "串口主动查询");
            assertNull("无效字段和其他有效选择均不能自动启动串口", source(activity));
        });
        scenario.recreate();
        scenario.onActivity(activity -> assertFormConfiguration(activity,
                MISSING_DEVICE + "-valid", "38400", "串口主动查询"));
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test public void serialChoiceDialogClosesOnRedrawAndNewChoiceSavesImmediately() {
        launchPage();
        AtomicReference<TextView> oldField = new AtomicReference<>();
        scenario.onActivity(activity -> {
            oldField.set(activity.findViewById(R.id.settings_baud));
            assertTrue(oldField.get().performClick());
        });
        ListView oldOptions = awaitSerialChoiceList("波特率选择应打开真实项目列表弹窗");
        scenario.onActivity(activity -> {
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertNotSame(oldField.get(), activity.findViewById(R.id.settings_baud));
        });
        instrumentation.waitForIdleSync();
        await(() -> {
            AtomicReference<Boolean> closed = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> closed.set(!oldOptions.isAttachedToWindow()
                    && currentSerialChoiceList() == null));
            return closed.get();
        }, "重排必须关闭绑定旧字段的弹窗");
        scenario.onActivity(activity -> {
            chooseSerialOption(oldOptions, "9600");
            assertEquals("旧弹窗回调不能修改已脱离页面的字段", "115200", oldField.get().getText().toString());
            assertSavedConfiguration(MISSING_DEVICE, "115200", "串口被动上报");
            assertTrue(activity.findViewById(R.id.settings_baud).performClick());
        });
        ListView newOptions = awaitSerialChoiceList("新表单应能重新打开选择弹窗");
        scenario.onActivity(activity -> {
            assertNotSame(oldOptions, newOptions);
            chooseSerialOption(newOptions, "57600");
        });
        instrumentation.waitForIdleSync();
        scenario.onActivity(activity -> {
            assertFormConfiguration(activity, MISSING_DEVICE, "57600", "串口被动上报");
            assertSavedConfiguration(MISSING_DEVICE, "57600", "串口被动上报");
            assertNull("重新选择配置不能自动启动串口", source(activity));
        });
        scenario.recreate();
        scenario.onActivity(activity -> {
            assertFormConfiguration(activity, MISSING_DEVICE, "57600", "串口被动上报");
            assertSavedConfiguration(MISSING_DEVICE, "57600", "串口被动上报");
        });
    }

    @Test public void continuousReadFollowsChangedParametersAndRejectsOldCallbacks() {
        launchPage();
        scenario.onActivity(activity -> {
            configure(activity, MISSING_DEVICE + "-first", "38400", true);
            View button = activity.findViewById(R.id.settings_read_temperature);
            assertTrue(button.performClick());
            TemperatureSource source = source(activity);
            Object firstSession = readField(source, "active");
            assertSession(firstSession, MISSING_DEVICE + "-first", 38400, true);
            TemperatureSource.Listener firstListener = listener(activity);
            firstListener.onTemperature(36.65);
            assertEquals("36.65", valueText(activity));
            firstListener.onTemperature(36.80);
            assertEquals("后续帧自动更新读数", "36.80", valueText(activity));
            assertSame("收到读数不能关闭持续采集串口", firstSession, readField(source, "active"));
            firstListener.onTemperature(Double.NaN);
            assertEquals("无效值不能继续展示历史体温", "--.--", valueText(activity));
            assertSame("单帧无效读数不能关掉持续采集", firstSession, readField(source, "active"));
            firstListener.onTemperature(36.75);

            configure(activity, MISSING_DEVICE + "-next", "57600", false);
            Object nextSession = readField(source, "active");
            assertSame("修改参数继续复用串口源的串行关闭队列", source, source(activity));
            assertNotSame("修改参数必须更换采集会话", firstSession, nextSession);
            assertSession(nextSession, MISSING_DEVICE + "-next", 57600, false);
            assertEquals("参数变化后不能展示旧数值", "--.--", valueText(activity));
            assertSavedConfiguration(MISSING_DEVICE + "-next", "57600", "串口被动上报");
            firstListener.onTemperature(38.60);
            firstListener.onError("旧会话失败");
            assertEquals("--.--", valueText(activity));
            assertSame("旧回调不能结束新读取", nextSession, readField(source, "active"));
            TemperatureSource.Listener nextListener = listener(activity);
            nextListener.onTemperature(36.50);
            assertEquals("36.50", valueText(activity));

            ((TextView) activity.findViewById(R.id.settings_baud)).setText("0");
            assertNull("无效参数应停止测试", readField(source, "active"));
            assertFalse(isReading(activity));
            assertEquals("--.--", valueText(activity));
            assertSavedConfiguration(MISSING_DEVICE + "-next", "57600", "串口被动上报");
            nextListener.onTemperature(37.20);
            assertEquals("停止后的旧回调不能显示温度", "--.--", valueText(activity));
            ((TextView) activity.findViewById(R.id.settings_baud)).setText("19200");
            assertSavedConfiguration(MISSING_DEVICE + "-next", "19200", "串口被动上报");
            assertNull("已停止的测试不能因新选择擅自恢复", readField(source, "active"));
            assertTrue(button.performClick());
            assertSession(readField(source, "active"), MISSING_DEVICE + "-next", 19200, false);
            assertTrue(button.performClick());
            assertNull("手动停止应释放串口", readField(source, "active"));
            assertEquals("--.--", valueText(activity));
        });
    }

    @Test public void redrawBackgroundAndRecreationReleaseSourceWithoutAutomaticallyResumingTest() {
        launchPage();
        AtomicReference<TemperatureSource.Listener> backgroundListener = new AtomicReference<>();
        AtomicReference<TemperatureSource> backgroundSource = new AtomicReference<>();
        scenario.onActivity(activity -> {
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
            TemperatureSource oldSource = source(activity);
            TemperatureSource.Listener oldListener = listener(activity);
            View oldValue = activity.findViewById(R.id.settings_temperature_value);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertNotSame("重排应装载新表单", oldValue, activity.findViewById(R.id.settings_temperature_value));
            assertNull("重排应停止旧串口会话", readField(oldSource, "active"));
            assertFalse("重排不能自动恢复测试", isReading(activity));
            oldListener.onTemperature(36.90);
            oldListener.onError("重排前的失败");
            assertEquals("--.--", valueText(activity));
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
            backgroundSource.set(source(activity));
            backgroundListener.set(listener(activity));
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        instrumentation.runOnMainSync(() -> {
            assertNull("进入后台应停止串口会话", readField(backgroundSource.get(), "active"));
            backgroundListener.get().onTemperature(36.55);
            backgroundListener.get().onError("后台旧回调");
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            assertFalse("返回页面不能自动恢复测试", isReading(activity));
            assertEquals("--.--", valueText(activity));
            assertNull(readField(source(activity), "active"));
        });
        scenario.recreate();
        assertTrue("销毁旧 Activity 应关闭测温源工作队列", (Boolean) readField(backgroundSource.get(), "closed"));
        scenario.onActivity(activity -> {
            assertFalse(isReading(activity));
            assertNull("重建不能自动创建采集源", source(activity));
            assertEquals("--.--", valueText(activity));
        });
    }

    @Test public void missingSerialDeviceReportsFailureAndCanRetryWithoutDefaultTemperature() {
        launchPage();
        scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick()));
        await(() -> {
            AtomicReference<Boolean> failed = new AtomicReference<>(false);
            scenario.onActivity(activity -> failed.set(!isReading(activity) && source(activity) != null
                    && readField(source(activity), "active") == null));
            return failed.get();
        }, "不存在的串口应结束读取并允许重试");
        scenario.onActivity(activity -> {
            assertEquals("失败不能展示默认体温", "--.--", valueText(activity));
            assertEquals(View.GONE, activity.findViewById(R.id.settings_temperature_reading).getVisibility());
            assertEquals(activity.getString(R.string.settings_read_temperature_button),
                    ((TextView) activity.findViewById(R.id.settings_read_temperature)).getText().toString());
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText("/tmp/not-a-serial-device");
            assertSavedConfiguration(MISSING_DEVICE, "115200", "串口被动上报");
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
            assertFalse(isReading(activity));
            assertNull("无效表单不能打开串口", readField(source(activity), "active"));
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText(MISSING_DEVICE + "-retry");
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
            assertSession(readField(source(activity), "active"), MISSING_DEVICE + "-retry", 115200, false);
        });
    }

    @Test
    public void measurementModePickerWaitsForSuccessBeforeSavingOrDisplayingSelection() {
        launchPage();
        scenario.onActivity(activity -> {
            TextView field = activity.findViewById(R.id.settings_temperature_measurement_mode);
            String original = field.getText().toString();
            assertEquals("设备默认使用动态触发模式", "动态触发", original);
            assertFalse("默认展示不能伪造设备回执", device.contains("temperature_measurement_mode"));
            assertFalse(device.contains("temperature_measurement_path"));
            assertFalse(device.contains("temperature_measurement_baud"));
            assertNull("进入页面不能发送测温模式指令", source(activity));
            assertTrue(field.performClick());
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) readField(activity, "measurementModeDialog");
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            ListView choices = dialog.findViewById(R.id.department_filter_options);
            assertNotNull(choices);
            assertEquals("测温算法模式与读取方式分别选择", 3, choices.getAdapter().getCount());
            assertEquals("室内模式", choices.getAdapter().getItem(0));
            assertEquals("室外模式", choices.getAdapter().getItem(1));
            assertEquals("动态触发", choices.getAdapter().getItem(2));
            assertNull("仅打开选择框不能占用串口", source(activity));
            View option = choices.getAdapter().getView(0, null, choices);
            assertTrue(choices.performItemClick(option, 0, choices.getAdapter().getItemId(0)));
            assertFalse(dialog.isShowing());
            assertTrue(isSwitchingMeasurementMode(activity));
            assertFalse("仅发送命令不能保存成功模式", device.contains("temperature_measurement_mode"));
            assertEquals("等待回执期间保留上次确认值", original, field.getText().toString());
            TemperatureSource.ModeChangeListener callback = modeChangeListener(activity);
            assertNotNull(callback);
            callback.onSuccess();
            assertFalse(isSwitchingMeasurementMode(activity));
            assertEquals("inside", device.getString("temperature_measurement_mode", ""));
            assertEquals(MISSING_DEVICE, device.getString("temperature_measurement_path", ""));
            assertEquals("115200", device.getString("temperature_measurement_baud", ""));
            assertEquals("室内模式", field.getText().toString());
            assertSavedConfiguration(MISSING_DEVICE, "115200", "串口被动上报");
            assertFalse("切换前未测试就不能自动开始读数", isReading(activity));
        });
    }

    @Test
    public void measurementModeFailureKeepsLastAcknowledgedModeAndAllowsRetry() {
        assertTrue(device.edit().putString("temperature_measurement_mode", "inside")
                .putString("temperature_measurement_path", MISSING_DEVICE)
                .putString("temperature_measurement_baud", "115200").commit());
        launchPage();
        scenario.onActivity(activity -> {
            TextView field = activity.findViewById(R.id.settings_temperature_measurement_mode);
            assertEquals("室内模式", field.getText().toString());
            changeMeasurementMode(activity, TemperatureMeasurementMode.OUTSIDE);
            TemperatureSource.ModeChangeListener failed = modeChangeListener(activity);
            assertTrue(isSwitchingMeasurementMode(activity));
            assertEquals("inside", device.getString("temperature_measurement_mode", ""));
            failed.onError("未收到 success");
            assertFalse(isSwitchingMeasurementMode(activity));
            assertEquals("失败不能把发送过的模式当成已生效", "inside",
                    device.getString("temperature_measurement_mode", ""));
            assertEquals("室内模式", field.getText().toString());
            changeMeasurementMode(activity, TemperatureMeasurementMode.OUTSIDE);
            TemperatureSource.ModeChangeListener retry = modeChangeListener(activity);
            assertNotSame(failed, retry);
            failed.onSuccess();
            assertTrue("旧失败请求的迟到回执不能完成新请求", isSwitchingMeasurementMode(activity));
            assertEquals("inside", device.getString("temperature_measurement_mode", ""));
            retry.onSuccess();
            assertEquals("outside", device.getString("temperature_measurement_mode", ""));
            assertEquals("室外模式", field.getText().toString());
            assertSavedConfiguration(MISSING_DEVICE, "115200", "串口被动上报");
        });
    }

    @Test
    public void cancelledMeasurementModeRequestDiscardsLateSuccess() {
        launchPage();
        scenario.onActivity(activity -> {
            changeMeasurementMode(activity, TemperatureMeasurementMode.INSIDE);
            TemperatureSource.ModeChangeListener cancelled = modeChangeListener(activity);
            Object loading = readField(activity, "readingDialog");
            android.app.Dialog dialog = (android.app.Dialog) readField(loading, "dialog");
            assertTrue(dialog.isShowing());
            assertTrue(dialog.findViewById(R.id.loading_cancel).performClick());
            assertFalse(dialog.isShowing());
            assertFalse(isSwitchingMeasurementMode(activity));
            cancelled.onSuccess();
            assertFalse("取消后的迟到成功不能保存模式", device.contains("temperature_measurement_mode"));
            changeMeasurementMode(activity, TemperatureMeasurementMode.TRIGGER);
            assertTrue(isSwitchingMeasurementMode(activity));
            modeChangeListener(activity).onSuccess();
            assertEquals("trigger", device.getString("temperature_measurement_mode", ""));
        });
    }

    @Test
    public void redrawClosesMeasurementModePickerAndRejectsItsOldSelection() {
        launchPage();
        scenario.onActivity(activity -> {
            assertTrue(activity.findViewById(R.id.settings_temperature_measurement_mode).performClick());
            androidx.appcompat.app.AlertDialog dialog =
                    (androidx.appcompat.app.AlertDialog) readField(activity, "measurementModeDialog");
            ListView choices = dialog.findViewById(R.id.department_filter_options);
            View oldOption = choices.getAdapter().getView(0, null, choices);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFalse(dialog.isShowing());
            assertTrue(choices.performItemClick(oldOption, 0, choices.getAdapter().getItemId(0)));
            assertFalse("旧表单弹窗不能向新页面提交硬件指令", isSwitchingMeasurementMode(activity));
            assertNull(source(activity));
            assertFalse(device.contains("temperature_measurement_mode"));
        });
    }

    @Test
    public void measurementModeConfirmationIsBoundToSerialPortAndBaud() {
        assertTrue(device.edit().putString("temperature_measurement_mode", "inside")
                .putString("temperature_measurement_path", MISSING_DEVICE)
                .putString("temperature_measurement_baud", "115200").commit());
        launchPage();
        scenario.onActivity(activity -> {
            TextView field = activity.findViewById(R.id.settings_temperature_measurement_mode);
            assertEquals("室内模式", field.getText().toString());
            ((TextView) activity.findViewById(R.id.settings_baud)).setText("38400");
            assertEquals("其他串口参数使用设备默认值，不沿用上次确认", "动态触发", field.getText().toString());
            assertNull("修改参数不能擅自向硬件补发模式", source(activity));
            ((TextView) activity.findViewById(R.id.settings_baud)).setText("115200");
            assertEquals("室内模式", field.getText().toString());
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText(MISSING_DEVICE + "-other");
            assertEquals("更换端口后回到设备默认模式", "动态触发", field.getText().toString());
            assertEquals("默认展示不能改写已确认的原端口设置", "inside",
                    device.getString("temperature_measurement_mode", ""));
            assertEquals(MISSING_DEVICE, device.getString("temperature_measurement_path", ""));
            assertEquals("115200", device.getString("temperature_measurement_baud", ""));
            assertNull("回到默认模式也不自动发送指令", source(activity));
        });
    }

    @Test
    public void parameterChangesAndRedrawDiscardInFlightMeasurementModeCallbacks() {
        launchPage();
        scenario.onActivity(activity -> {
            changeMeasurementMode(activity, TemperatureMeasurementMode.OUTSIDE);
            TemperatureSource.ModeChangeListener beforePathChange = modeChangeListener(activity);
            ((TextView) activity.findViewById(R.id.settings_serial_path)).setText(MISSING_DEVICE + "-new");
            assertFalse(isSwitchingMeasurementMode(activity));
            beforePathChange.onSuccess();
            beforePathChange.onError("旧端口的迟到回执");
            assertFalse(device.contains("temperature_measurement_mode"));
            changeMeasurementMode(activity, TemperatureMeasurementMode.TRIGGER);
            TemperatureSource.ModeChangeListener beforeRedraw = modeChangeListener(activity);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            assertFalse(isSwitchingMeasurementMode(activity));
            beforeRedraw.onSuccess();
            assertFalse("重排前的旧请求不能保存模式", device.contains("temperature_measurement_mode"));
            assertFalse(isReading(activity));
        });
    }

    @Test
    public void backgroundDiscardsMeasurementModeCallbackWithoutResumingRequest() {
        launchPage();
        AtomicReference<TemperatureSource.ModeChangeListener> callback = new AtomicReference<>();
        scenario.onActivity(activity -> {
            changeMeasurementMode(activity, TemperatureMeasurementMode.INSIDE);
            callback.set(modeChangeListener(activity));
            assertTrue(isSwitchingMeasurementMode(activity));
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        instrumentation.runOnMainSync(() -> {
            callback.get().onSuccess();
            callback.get().onError("后台迟到回执");
            assertFalse(device.contains("temperature_measurement_mode"));
        });
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            assertFalse(isSwitchingMeasurementMode(activity));
            assertFalse(isReading(activity));
            assertFalse(device.contains("temperature_measurement_mode"));
        });
    }

    @Test
    public void successfulMeasurementModeChangeResumesOnlyPreviouslyRunningRead() {
        launchPage();
        scenario.onActivity(activity -> {
            selectMode(activity, true);
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
            TemperatureSource previousSource = source(activity);
            Object previousRead = readField(previousSource, "active");
            assertTrue(isReading(activity));
            changeMeasurementMode(activity, TemperatureMeasurementMode.TRIGGER);
            assertFalse("切模式时先停止采集，不能两个会话抢串口", isReading(activity));
            assertTrue(isSwitchingMeasurementMode(activity));
            modeChangeListener(activity).onSuccess();
            assertFalse(isSwitchingMeasurementMode(activity));
            assertTrue("切换完成后恢复原来进行中的读取", isReading(activity));
            assertSame(previousSource, source(activity));
            Object resumed = readField(previousSource, "active");
            assertNotSame(previousRead, resumed);
            assertSession(resumed, MISSING_DEVICE, 115200, true);
            assertEquals("trigger", device.getString("temperature_measurement_mode", ""));
            assertSavedConfiguration(MISSING_DEVICE, "115200", "串口主动查询");
            assertTrue(activity.findViewById(R.id.settings_read_temperature).performClick());
        });
    }

    @Test public void currentLayoutsOmitSaveAndKeepTestControlReachableAtEveryWindowAndFontSize() {
        instrumentation.runOnMainSync(() -> {
            for (float scale : new float[]{1.15f, 1.495f})
                for (int[] size : new int[][]{{360, 640}, {640, 320}, {1280, 800}, {800, 1280}})
                    assertCurrentLayout(size[0], size[1], scale);
        });
    }

    private void assertCurrentLayout(int widthDp, int heightDp, float fontScale) {
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.screenWidthDp = widthDp;
        configuration.screenHeightDp = heightDp;
        configuration.smallestScreenWidthDp = Math.min(widthDp, heightDp);
        configuration.orientation = widthDp > heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        configuration.fontScale = fontScale;
        Context context = new ContextThemeWrapper(app.createConfigurationContext(configuration), R.style.AppTheme);
        ViewGroup root = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.view_temperature_design, null);
        root.findViewById(R.id.settings_temperature_reading).setVisibility(View.GONE);
        ((TextView) root.findViewById(R.id.settings_serial_path)).setText("/dev/ttyS4");
        ((TextView) root.findViewById(R.id.settings_baud)).setText("115200");
        ((TextView) root.findViewById(R.id.settings_temperature_measurement_mode)).setText("动态触发");
        ((Spinner) root.findViewById(R.id.settings_temperature_mode)).setAdapter(
                new android.widget.ArrayAdapter<>(context, R.layout.item_setting_value,
                        context.getResources().getStringArray(R.array.settings_temperature_modes)));
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertNoSaveControl(context.getResources(), context.getPackageName(), root);
        assertContained(root, root.findViewById(R.id.settings_read_temperature));
        for (int id : new int[]{R.id.settings_temperature_measurement_mode, R.id.settings_temperature_mode, R.id.settings_serial_path,
                R.id.settings_baud, R.id.settings_temperature_value})
            assertContained(root, root.findViewById(id));
        assertNoPageScroll(root);
    }

    private static void assertContained(ViewGroup root, View view) {
        assertNotNull(view);
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue("控件应具有可见尺寸", bounds.width() > 0 && bounds.height() > 0);
        assertTrue("控件应完整位于页面内：" + bounds,
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
    }

    private static void assertNoSaveControl(android.content.res.Resources resources, String packageName, View root) {
        int saveId = resources.getIdentifier("settings_save_temperature", "id", packageName);
        if (saveId != 0) assertNull("实时设置不再显示保存按钮", root.findViewById(saveId));
    }

    private static void assertNoPageScroll(View view) {
        assertFalse("测温设置不依赖整页滚动", view instanceof ScrollView);
        if (view instanceof ViewGroup)
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
                assertNoPageScroll(((ViewGroup) view).getChildAt(index));
    }

    private void launchPage() {
        scenario = ActivityScenario.launch(new Intent(app, TemperatureSettingsActivity.class));
        String orientation = InstrumentationRegistry.getArguments().getString("design_orientation", "");
        if (!"landscape".equals(orientation) && !"portrait".equals(orientation)) return;
        boolean landscape = "landscape".equals(orientation);
        scenario.onActivity(activity -> activity.setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        instrumentation.waitForIdleSync();
        await(() -> {
            AtomicReference<Boolean> matched = new AtomicReference<>(false);
            scenario.onActivity(activity -> matched.set(activity.getResources().getConfiguration().orientation
                    == (landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT)));
            return matched.get();
        }, "测温页应按专项参数进入指定方向");
        instrumentation.waitForIdleSync();
    }

    private static ListView currentSerialChoiceList() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            ListView options = root.findViewById(R.id.department_filter_options);
            if (options != null && options.isAttachedToWindow()) return options;
        }
        return null;
    }

    private ListView awaitSerialChoiceList(String message) {
        instrumentation.waitForIdleSync();
        AtomicReference<ListView> choices = new AtomicReference<>();
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                ListView options = currentSerialChoiceList();
                if (options != null && options.isShown() && options.getWidth() > 0 && options.getHeight() > 0
                        && options.getChildCount() > 0) choices.set(options);
            });
            return choices.get() != null;
        }, message);
        return choices.get();
    }

    private static void chooseSerialOption(ListView options, String value) {
        for (int index = 0; index < options.getAdapter().getCount(); index++) {
            if (!value.equals(String.valueOf(options.getAdapter().getItem(index)))) continue;
            View row = options.getAdapter().getView(index, null, options);
            assertTrue(options.performItemClick(row, index, options.getAdapter().getItemId(index)));
            return;
        }
        fail("串口选项列表缺少测试值：" + value);
    }

    private void configure(TemperatureSettingsActivity activity, String path, String baud, boolean active) {
        ((TextView) activity.findViewById(R.id.settings_serial_path)).setText(path);
        ((TextView) activity.findViewById(R.id.settings_baud)).setText(baud);
        selectMode(activity, active);
    }

    private static void selectMode(TemperatureSettingsActivity activity, boolean active) {
        Spinner mode = activity.findViewById(R.id.settings_temperature_mode);
        mode.setSelection(active ? 1 : 0);
        // 同步分发选择回执，避免用布局调度时机影响正在切换的串口会话断言。
        mode.getOnItemSelectedListener().onItemSelected(mode, mode.getSelectedView(),
                mode.getSelectedItemPosition(), mode.getSelectedItemId());
    }

    private void assertSavedConfiguration(String path, String baud, String mode) {
        assertEquals("串口路径应与最后一次有效选择一致", path, device.getString("serial_path", ""));
        assertEquals("波特率应与最后一次有效选择一致", baud, device.getString("baud", ""));
        assertEquals("测温方式应与最后一次有效选择一致", mode, device.getString("temperature_mode", ""));
    }

    private static void assertFormConfiguration(TemperatureSettingsActivity activity,
                                                String path, String baud, String mode) {
        assertEquals(path, ((TextView) activity.findViewById(R.id.settings_serial_path)).getText().toString());
        assertEquals(baud, ((TextView) activity.findViewById(R.id.settings_baud)).getText().toString());
        assertEquals(mode, ((Spinner) activity.findViewById(R.id.settings_temperature_mode)).getSelectedItem());
    }

    private static void assertSession(Object session, String path, int baud, boolean activeMode) {
        assertNotNull(session);
        assertEquals(path, readField(session, "path"));
        assertEquals(baud, readField(session, "baud"));
        assertEquals(activeMode, readField(session, "activeMode"));
        assertEquals("设置页使用持续采集", false, readField(session, "once"));
    }

    private static boolean isReading(TemperatureSettingsActivity activity) {
        return (Boolean) readField(activity, "reading");
    }

    private static String valueText(TemperatureSettingsActivity activity) {
        return ((TextView) activity.findViewById(R.id.settings_temperature_value)).getText().toString();
    }

    private static TemperatureSource source(TemperatureSettingsActivity activity) {
        return (TemperatureSource) readField(activity, "temperatureSource");
    }

    private static TemperatureSource.Listener listener(TemperatureSettingsActivity activity) {
        return (TemperatureSource.Listener) readField(activity, "readListener");
    }

    private static boolean isSwitchingMeasurementMode(TemperatureSettingsActivity activity) {
        return (Boolean) readField(activity, "switchingMode");
    }

    private static TemperatureSource.ModeChangeListener modeChangeListener(TemperatureSettingsActivity activity) {
        return (TemperatureSource.ModeChangeListener) readField(activity, "modeChangeListener");
    }

    private static void changeMeasurementMode(TemperatureSettingsActivity activity, TemperatureMeasurementMode mode) {
        try {
            Method method = TemperatureSettingsActivity.class.getDeclaredMethod("changeMeasurementMode", TemperatureMeasurementMode.class);
            method.setAccessible(true);
            method.invoke(activity, mode);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object readField(Object target, String name) {
        assertNotNull("缺少验收所需实例：" + name, target);
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SuppressWarnings("unchecked")
    private void restorePreferences() {
        SharedPreferences.Editor editor = device.edit().clear();
        for (Map.Entry<String, Object> entry : originalPreferences.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) editor.putStringSet(entry.getKey(), new HashSet<>((Set<String>) value));
            else throw new AssertionError("未知配置类型：" + entry.getKey());
        }
        assertTrue(editor.commit());
        assertEquals("试读测试应恢复全部设备配置", originalPreferences, device.getAll());
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
