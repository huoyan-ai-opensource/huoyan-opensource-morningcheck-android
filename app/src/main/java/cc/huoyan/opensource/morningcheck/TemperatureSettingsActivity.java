package cc.huoyan.opensource.morningcheck;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import cc.huoyan.opensource.morningcheck.hardware.TemperatureMeasurementMode;
import cc.huoyan.opensource.morningcheck.hardware.TemperatureSource;

/**
 * 测温设置：独立 Activity 直接绑定对应 XML。
 */
public final class TemperatureSettingsActivity extends BaseDeviceSettingsActivity {
    private TemperatureSource temperatureSource;
    private TemperatureSource.Listener readListener;
    private boolean reading;
    private int readGeneration;
    private String readingConfiguration;
    private AlertDialog measurementModeDialog;
    private TemperatureSource.ModeChangeListener modeChangeListener;
    private boolean switchingMode;
    private int modeChangeGeneration;

    @Override
    protected int settingsSection() {
        return 6;
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_temperature_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_temperature_design;
    }

    @Override
    protected void bindRegular(View form) {
        content.setPadding(0, 0, 0, 0);
        form.setSaveFromParentEnabled(false);
        List<String> modes = Arrays.asList(getResources().getStringArray(R.array.settings_temperature_modes));
        Spinner mode = settingsSelect(form, R.id.settings_temperature_mode, modes, modes.indexOf(setting("temperature_mode", "串口被动上报")));
        android.widget.ArrayAdapter<String> modeAdapter = new android.widget.ArrayAdapter<>(this, R.layout.item_setting_value, modes);
        modeAdapter.setDropDownViewResource(R.layout.item_spinner_option);
        mode.setAdapter(modeAdapter);
        mode.setSelection(Math.max(0, modes.indexOf(setting("temperature_mode", "串口被动上报"))));
        String[] selectedMode = {String.valueOf(mode.getSelectedItem())};
        TextView path = form.findViewById(R.id.settings_serial_path);
        path.setText(setting("serial_path", "/dev/ttyS4"));
        SerialChoices.bind(this, path, true, "点选后立即生效", this::temperatureConfigurationChanged);
        TextView baud = form.findViewById(R.id.settings_baud);
        baud.setText(setting("baud", "115200"));
        SerialChoices.bind(this, baud, false, "点选后立即生效", this::temperatureConfigurationChanged);
        watchTemperatureField(form, path, "serial_path");
        watchTemperatureField(form, baud, "baud");
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = String.valueOf(mode.getSelectedItem());
                if (value.equals(selectedMode[0]) || form != formView || !uiReady()) return;
                selectedMode[0] = value;
                if (!modes.contains(value)) {
                    rejectTemperatureConfiguration(getString(R.string.settings_temperature_invalid_mode));
                    return;
                }
                prefs.edit().putString("temperature_mode", value).apply();
                temperatureConfigurationChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        settingsAction(form, R.id.settings_temperature_measurement_mode, this::selectMeasurementMode);
        showMeasurementMode();
        bindReadAction(form);
    }

    private void selectMeasurementMode() {
        if (!uiReady() || switchingMode || measurementModeDialog != null && measurementModeDialog.isShowing()) return;
        View expectedForm = formView;
        int expectedView = viewGeneration;
        int expectedChange = modeChangeGeneration;
        String expectedConfiguration = temperatureConfiguration(expectedForm);
        TemperatureMeasurementMode saved = savedMeasurementMode();
        int selected = saved.ordinal();
        measurementModeDialog = DesignChoiceDialog.create(this,
                getString(R.string.settings_temperature_measurement_mode),
                getString(R.string.settings_temperature_measurement_help),
                Arrays.asList(getResources().getStringArray(R.array.settings_temperature_measurement_modes)),
                selected, true, position -> {
                    if (uiReady() && formView == expectedForm && viewGeneration == expectedView
                            && modeChangeGeneration == expectedChange
                            && expectedConfiguration.equals(temperatureConfiguration(expectedForm)))
                        changeMeasurementMode(TemperatureMeasurementMode.values()[position]);
                });
        ImmersiveUi.show(measurementModeDialog);
    }

    private void changeMeasurementMode(TemperatureMeasurementMode requested) {
        if (!uiReady() || switchingMode || requested == null) return;
        View expectedForm = formView;
        int expectedView = viewGeneration;
        String path = temperaturePath(expectedForm).trim();
        int baud;
        try {
            baud = Integer.parseInt(temperatureBaud(expectedForm).trim());
            if (baud <= 0 || !path.startsWith("/dev/") || path.length() <= 5)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            notice(getString(R.string.settings_temperature_mode_invalid_config));
            return;
        }
        boolean resumeReading = reading;
        cancelReading();
        resetReadDisplay();
        int expectedChange = ++modeChangeGeneration;
        String expectedConfiguration = temperatureConfiguration(expectedForm);
        switchingMode = true;
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        readingDialog.show(getString(R.string.settings_temperature_mode_switching), this::cancelMeasurementModeChange);
        modeChangeListener = new TemperatureSource.ModeChangeListener() {
            private boolean current() {
                return switchingMode && uiReady() && formView == expectedForm
                        && viewGeneration == expectedView && modeChangeGeneration == expectedChange
                        && expectedConfiguration.equals(temperatureConfiguration(expectedForm));
            }

            @Override
            public void onSuccess() {
                if (!current()) return;
                // 只记录模块明确确认的模式，并绑定串口参数；发送完成不代表切换成功。
                prefs.edit().putString("temperature_measurement_mode", requested.getValue())
                        .putString("temperature_measurement_path", path)
                        .putString("temperature_measurement_baud", String.valueOf(baud)).apply();
                finish();
                showMeasurementMode();
                toast(getString(R.string.settings_temperature_mode_switched, measurementModeLabel(requested)));
            }

            @Override
            public void onError(String message) {
                if (!current()) return;
                finish();
                notice(getString(R.string.settings_temperature_mode_failed,
                        message == null || message.trim().isEmpty()
                                ? getString(R.string.settings_temperature_unknown_error) : message));
            }

            private void finish() {
                switchingMode = false;
                modeChangeListener = null;
                if (readingDialog != null) readingDialog.dismiss();
                if (resumeReading) readTemperature();
            }
        };
        ensureTemperatureSource();
        temperatureSource.setMeasurementMode(path, baud, requested, modeChangeListener);
    }

    private TemperatureMeasurementMode savedMeasurementMode() {
        if (formView == null) return TemperatureMeasurementMode.TRIGGER;
        String baud;
        try {
            baud = String.valueOf(Integer.parseInt(temperatureBaud(formView).trim()));
        } catch (IllegalArgumentException invalid) {
            return TemperatureMeasurementMode.TRIGGER;
        }
        // 无本机确认记录时显示设备默认动态触发；仅展示，不补发指令或写入成功记录。
        if (!temperaturePath(formView).trim().equals(setting("temperature_measurement_path", ""))
                || !baud.equals(setting("temperature_measurement_baud", ""))) return TemperatureMeasurementMode.TRIGGER;
        TemperatureMeasurementMode saved = TemperatureMeasurementMode.fromValue(setting("temperature_measurement_mode", ""));
        return saved == null ? TemperatureMeasurementMode.TRIGGER : saved;
    }

    private String measurementModeLabel(TemperatureMeasurementMode mode) {
        return getResources().getStringArray(R.array.settings_temperature_measurement_modes)[mode.ordinal()];
    }

    private void showMeasurementMode() {
        if (formView == null) return;
        TextView field = formView.findViewById(R.id.settings_temperature_measurement_mode);
        field.setText(measurementModeLabel(savedMeasurementMode()));
    }

    private void cancelMeasurementModeChange() {
        modeChangeGeneration++;
        modeChangeListener = null;
        if (measurementModeDialog != null) measurementModeDialog.dismiss();
        measurementModeDialog = null;
        if (!switchingMode) return;
        switchingMode = false;
        if (readingDialog != null) readingDialog.dismiss();
        if (temperatureSource != null) temperatureSource.stop();
    }

    private void watchTemperatureField(View form, TextView field, String key) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (form != formView || !uiReady()) return;
                String value = field.getText().toString().trim();
                try {
                    if ("serial_path".equals(key)) {
                        if (!value.startsWith("/dev/") || value.length() <= 5)
                            throw new IllegalArgumentException();
                    } else {
                        int speed = Integer.parseInt(value);
                        if (speed <= 0) throw new IllegalArgumentException();
                        value = String.valueOf(speed);
                    }
                } catch (IllegalArgumentException invalid) {
                    rejectTemperatureConfiguration(getString(R.string.settings_temperature_invalid_config));
                    return;
                }
                // 各字段独立校验保存，另一项暂时无效也不能丢失本次有效选择。
                prefs.edit().putString(key, value).apply();
                temperatureConfigurationChanged();
            }
        });
    }

    @Override
    protected void bindCompact(View form) {
        bindRegular(form);
    }

    @Override
    protected void saveForm(View view) {
        // 实时配置以持久化值为准，不再保留可能覆盖新选择的旧表单草稿。
    }

    @Override
    protected void restoreForm(View view) {
    }

    private void rejectTemperatureConfiguration(String message) {
        cancelMeasurementModeChange();
        cancelReading();
        showMeasurementMode();
        resetReadDisplay();
        notice(message);
    }

    private void bindReadAction(View form) {
        ((TextView) form.findViewById(R.id.settings_temperature_value)).setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.sp_18) * 2);
        settingsAction(form, R.id.settings_read_temperature, () -> {
            if (reading) {
                cancelReading();
                resetReadDisplay();
            } else readTemperature();
        });
        resetReadDisplay();
    }

    private LoadingDialog readingDialog;

    private void readTemperature() {
        if (reading || switchingMode || !uiReady()) return;
        final View expectedForm = formView;
        final int expectedView = viewGeneration;
        String mode = temperatureMode(expectedForm);
        String path = temperaturePath(expectedForm).trim();
        int baud;
        try {
            baud = Integer.parseInt(temperatureBaud(expectedForm).trim());
            if (baud <= 0 || !path.startsWith("/dev/") || path.length() <= 5)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            cancelReading();
            readingConfiguration = temperatureConfiguration(expectedForm);
            notice(getString(R.string.settings_temperature_invalid_config));
            resetReadDisplay();
            return;
        }
        List<String> modes = Arrays.asList(getResources().getStringArray(R.array.settings_temperature_modes));
        if (!modes.contains(mode)) {
            cancelReading();
            readingConfiguration = temperatureConfiguration(expectedForm);
            notice(getString(R.string.settings_temperature_invalid_mode));
            resetReadDisplay();
            return;
        }
        cancelReading();
        final int expectedRead = readGeneration;
        readingConfiguration = temperatureConfiguration(expectedForm);
        resetReadDisplay();
        if (readingDialog == null) readingDialog = new LoadingDialog(this);
        readingDialog.show(getString(R.string.settings_temperature_reading), () -> {
            cancelReading();
            resetReadDisplay();
        });
        setReadState(true);
        readListener = new TemperatureSource.Listener() {
            @Override
            public void onTemperature(double temperature) {
                if (!currentRead(expectedForm, expectedView, expectedRead)) return;
                if (!Double.isFinite(temperature) || temperature <= 10 || temperature >= 60) {
                    clearReadingValue();
                    setReadState(true);
                    return;
                }
                readingDialog.dismiss();
                ((TextView) expectedForm.findViewById(R.id.settings_temperature_value))
                        .setText(String.format(Locale.CHINA, "%.2f", temperature));
                ((TextView) expectedForm.findViewById(R.id.settings_temperature_updated))
                        .setText(getString(R.string.settings_temperature_updated,
                                new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())));
                setReadState(true);
            }

            @Override
            public void onState(String message) {
                if (!currentRead(expectedForm, expectedView, expectedRead)) return;
                if (message != null && !message.trim().isEmpty()) setReadState(true);
            }

            @Override
            public void onError(String message) {
                if (!currentRead(expectedForm, expectedView, expectedRead)) return;
                // 无人测温时源仍保持采集，不能把等待读数当作断线而关掉串口。
                if (temperatureSource != null && temperatureSource.isRunning()) {
                    setReadState(true);
                    return;
                }
                finishReading(getString(R.string.settings_temperature_read_failed,
                        message == null || message.trim().isEmpty()
                                ? getString(R.string.settings_temperature_unknown_error) : message));
            }
        };
        ensureTemperatureSource();
        temperatureSource.start(path, baud, mode.equals(modes.get(1)));
    }

    private void ensureTemperatureSource() {
        if (temperatureSource == null) {
            // 复用串口源的串行关闭队列；源先隔离旧会话，再由本次回调核对页面与参数。
            temperatureSource = new TemperatureSource(new TemperatureSource.Listener() {
                @Override
                public void onTemperature(double temperature) {
                    if (readListener != null) readListener.onTemperature(temperature);
                }

                @Override
                public void onState(String message) {
                    if (readListener != null) readListener.onState(message);
                }

                @Override
                public void onError(String message) {
                    if (readListener != null) readListener.onError(message);
                }
            });
        }
    }

    private boolean currentRead(View expectedForm, int expectedView, int expectedRead) {
        return reading && uiReady() && formView == expectedForm && viewGeneration == expectedView
                && readGeneration == expectedRead
                && readingConfiguration.equals(temperatureConfiguration(expectedForm));
    }

    private void finishReading(String message) {
        cancelReading();
        resetReadDisplay();
        notice(message);
    }

    private void setReadState(boolean busy) {
        reading = busy;
        if (formView == null) return;
        TextView status = formView.findViewById(R.id.settings_temperature_reading);
        status.setVisibility(View.GONE);
        TextView button = formView.findViewById(R.id.settings_read_temperature);
        button.setText(busy ? R.string.settings_stop_temperature_button : R.string.settings_read_temperature_button);
        button.setEnabled(true);
        button.setAlpha(1f);
    }

    private void resetReadDisplay() {
        if (formView == null) return;
        clearReadingValue();
        setReadState(false);
    }

    private void clearReadingValue() {
        if (formView == null) return;
        ((TextView) formView.findViewById(R.id.settings_temperature_value))
                .setText(R.string.settings_temperature_no_value);
        ((TextView) formView.findViewById(R.id.settings_temperature_updated))
                .setText(R.string.settings_temperature_no_update);
    }

    private void temperatureConfigurationChanged() {
        cancelMeasurementModeChange();
        showMeasurementMode();
        if (formView == null || !reading || readingConfiguration == null
                || readingConfiguration.equals(temperatureConfiguration(formView))) return;
        cancelReading();
        resetReadDisplay();
        readTemperature();
    }

    private String temperatureMode(View form) {
        Spinner mode = form.findViewById(R.id.settings_temperature_mode);
        return mode == null ? setting("temperature_mode", "串口被动上报") : String.valueOf(mode.getSelectedItem());
    }

    private String temperaturePath(View form) {
        TextView path = form.findViewById(R.id.settings_serial_path);
        return path == null ? setting("serial_path", "/dev/ttyS4") : path.getText().toString();
    }

    private String temperatureBaud(View form) {
        TextView baud = form.findViewById(R.id.settings_baud);
        return baud == null ? setting("baud", "115200") : baud.getText().toString();
    }

    private String temperatureConfiguration(View form) {
        return temperatureMode(form) + '\n' + temperaturePath(form) + '\n' + temperatureBaud(form);
    }

    private void cancelReading() {
        // 页面和参数各自带代次，串口关闭期间已排队的旧回调也不能修改新表单。
        if (readingDialog != null) readingDialog.dismiss();
        readGeneration++;
        reading = false;
        readingConfiguration = null;
        readListener = null;
        if (temperatureSource != null) temperatureSource.stop();
    }

    @Override
    protected void releaseSettingsView() {
        cancelMeasurementModeChange();
        cancelReading();
    }

    @Override
    protected void onPause() {
        cancelMeasurementModeChange();
        cancelReading();
        resetReadDisplay();
        super.onPause();
    }

    @Override
    protected void onStop() {
        cancelMeasurementModeChange();
        cancelReading();
        resetReadDisplay();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (temperatureSource != null) {
            temperatureSource.close();
            temperatureSource = null;
        }
        super.onDestroy();
    }
}
