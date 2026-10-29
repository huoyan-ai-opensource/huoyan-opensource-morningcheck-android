package cc.huoyan.opensource.morningcheck;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.Arrays;

import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.hardware.LightController;

/**
 * 灯光参数和测试在独立页面完成，离开前台即撤销测试开灯。
 */
public final class LightingSettingsActivity extends BaseMorningActivity {
    private LightController.Scope lighting;
    private LoadingDialog loading;
    private AlertDialog commandFormatDialog;
    private boolean resumed, binding;
    private int commandFormat;
    private String mode, path, baud, gpio, onLevel, offLevel, onCommand, offCommand;

    @Override
    protected String pageKey() {
        return "lighting";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_lighting_settings;
    }

    @Override
    protected void setup(Bundle saved) {
        mode = setting("light_mode", LightConfig.SERIAL);
        path = setting("light_serial_path", LightConfig.DEFAULT_PATH);
        baud = setting("light_baud", String.valueOf(LightConfig.DEFAULT_BAUD));
        gpio = setting("light_gpio", LightConfig.DEFAULT_GPIO);
        onLevel = setting("light_on_level", "0");
        offLevel = setting("light_off_level", "1");
        commandFormat = validCommandFormat(prefs.getInt("light_command_format", LightConfig.ASCII));
        String savedOnCommand = setting("light_on_command", LightConfig.DEFAULT_ON_COMMAND);
        String savedOffCommand = setting("light_off_command", LightConfig.DEFAULT_OFF_COMMAND);
        if (saved != null) {
            mode = saved.getString("mode", mode);
            path = saved.getString("path", path);
            baud = saved.getString("baud", baud);
            gpio = saved.getString("gpio", gpio);
            onLevel = saved.getString("on", onLevel);
            offLevel = saved.getString("off", offLevel);
            if (saved.getInt("lightCommandStateVersion", 0) == 2) {
                commandFormat = validCommandFormat(saved.getInt("commandFormat", LightConfig.ASCII));
                onCommand = saved.getString("onCommand", "");
                offCommand = saved.getString("offCommand", "");
                return;
            } else if (saved.getInt("lightCommandStateVersion", 0) == 1) {
                savedOnCommand = saved.getString("onCommand", savedOnCommand);
                savedOffCommand = saved.getString("offCommand", savedOffCommand);
            }
        }
        try {
            onCommand = displayCommand(savedOnCommand, commandFormat);
            offCommand = displayCommand(savedOffCommand, commandFormat);
        } catch (IllegalArgumentException invalid) {
            // 旧配置可能包含非字符字节，退回十六进制展示，避免默认字符格式改写设备协议。
            commandFormat = LightConfig.HEXADECIMAL;
            onCommand = editableHexCommand(savedOnCommand);
            offCommand = editableHexCommand(savedOffCommand);
        }
    }

    @Override
    protected void beforeRender() {
        rememberForm();
        dismissCommandFormat();
        stopTest();
    }

    @Override
    protected void bindPage() {
        ((TextView) findViewById(R.id.header_page_title)).setText(getString(R.string.i18n_a_lighting));
        binding = true;
        TextView pathField = findViewById(R.id.lighting_path);
        pathField.setText(path);
        TextView baudField = findViewById(R.id.lighting_baud);
        baudField.setText(baud);
        SerialChoices.bind(this, pathField, true, () -> {
            rememberForm();
            stopTest();
        });
        SerialChoices.bind(this, baudField, false, () -> {
            rememberForm();
            stopTest();
        });
        fill(R.id.lighting_gpio, gpio);
        fill(R.id.lighting_on_level, onLevel);
        fill(R.id.lighting_off_level, offLevel);
        fill(R.id.lighting_on_command, onCommand);
        fill(R.id.lighting_off_command, offCommand);
        binding = false;
        findViewById(R.id.lighting_serial_mode).setOnClickListener(v -> changeMode(LightConfig.SERIAL));
        findViewById(R.id.lighting_gpio_mode).setOnClickListener(v -> changeMode(LightConfig.GPIO));
        findViewById(R.id.lighting_save).setOnClickListener(v -> saveConfiguration());
        findViewById(R.id.lighting_test_on).setOnClickListener(v -> test(true));
        findViewById(R.id.lighting_test_off).setOnClickListener(v -> test(false));
        findViewById(R.id.lighting_command_format).setOnClickListener(v -> selectCommandFormat());
        showCommandFormat();
        showMode();
    }

    private int validCommandFormat(int format) {
        return format == LightConfig.DECIMAL || format == LightConfig.HEXADECIMAL ? format : LightConfig.ASCII;
    }

    private String commandPrefix() {
        return commandFormat == LightConfig.DECIMAL ? "DEC:" : commandFormat == LightConfig.HEXADECIMAL ? "HEX:" : "ASCII:";
    }

    private String displayCommand(String command, int format) {
        String encoded = LightConfig.formatCommand(command, format);
        return encoded.substring(encoded.indexOf(':') + 1);
    }

    private String editableHexCommand(String command) {
        try {
            return displayCommand(command, LightConfig.HEXADECIMAL);
        } catch (IllegalArgumentException invalid) {
            // 错误配置保留为可编辑内容，不能把非法的数字指令误当字符发送。
            if (command == null) return "";
            return command.regionMatches(true, 0, "HEX:", 0, 4) ? command.substring(4) : command;
        }
    }

    private void selectCommandFormat() {
        if (!resumed || commandFormatDialog != null && commandFormatDialog.isShowing()) return;
        int selected = commandFormat == LightConfig.ASCII ? 0 : commandFormat == LightConfig.DECIMAL ? 1 : 2;
        commandFormatDialog = DesignChoiceDialog.create(this, getString(R.string.lighting_command_format),
                getString(R.string.lighting_command_format_help),
                Arrays.asList(getResources().getStringArray(R.array.lighting_command_formats)), selected,
                position -> changeCommandFormat(position == 0 ? LightConfig.ASCII : position == 1 ? LightConfig.DECIMAL : LightConfig.HEXADECIMAL));
        ImmersiveUi.show(commandFormatDialog);
    }

    private void changeCommandFormat(int format) {
        if (format == commandFormat) return;
        rememberForm();
        try {
            String nextOnCommand = convertCommandText(onCommand, format);
            String nextOffCommand = convertCommandText(offCommand, format);
            stopTest();
            commandFormat = format;
            onCommand = nextOnCommand;
            offCommand = nextOffCommand;
            binding = true;
            ((TextView) findViewById(R.id.lighting_on_command)).setText(onCommand);
            ((TextView) findViewById(R.id.lighting_off_command)).setText(offCommand);
            binding = false;
            showCommandFormat();
        } catch (IllegalArgumentException invalid) {
            notice(invalid.getMessage());
        }
    }

    private String convertCommandText(String text, int format) {
        if (text.isEmpty() || commandFormat != LightConfig.ASCII && text.trim().isEmpty()) return "";
        return displayCommand(commandPrefix() + text, format);
    }

    private void showCommandFormat() {
        int label = commandFormat == LightConfig.ASCII ? R.string.lighting_format_character
                : commandFormat == LightConfig.DECIMAL ? R.string.lighting_format_decimal : R.string.lighting_format_hexadecimal;
        int hint = commandFormat == LightConfig.ASCII ? R.string.lighting_character_hint
                : commandFormat == LightConfig.DECIMAL ? R.string.lighting_decimal_hint : R.string.lighting_hexadecimal_hint;
        ((TextView) findViewById(R.id.lighting_command_format)).setText(label);
        ((TextView) findViewById(R.id.lighting_on_command)).setHint(hint);
        ((TextView) findViewById(R.id.lighting_off_command)).setHint(hint);
    }

    private void dismissCommandFormat() {
        if (commandFormatDialog != null) commandFormatDialog.dismiss();
        commandFormatDialog = null;
    }

    private void fill(int id, String value) {
        EditText input = findViewById(id);
        input.setText(value);
        input.addTextChangedListener(watcher(() -> {
            if (!binding) {
                rememberForm();
                stopTest();
            }
        }));
    }

    private void rememberForm() {
        if (findViewById(R.id.lighting_path) == null) return;
        path = text(R.id.lighting_path);
        baud = text(R.id.lighting_baud);
        gpio = text(R.id.lighting_gpio);
        onLevel = text(R.id.lighting_on_level);
        offLevel = text(R.id.lighting_off_level);
        onCommand = ((TextView) findViewById(R.id.lighting_on_command)).getText().toString();
        offCommand = ((TextView) findViewById(R.id.lighting_off_command)).getText().toString();
    }

    private String text(int id) {
        return ((TextView) findViewById(id)).getText().toString().trim();
    }

    private void changeMode(String value) {
        if (value.equals(mode)) return;
        stopTest();
        mode = value;
        showMode();
    }

    private void showMode() {
        boolean serial = LightConfig.SERIAL.equals(mode);
        findViewById(R.id.lighting_serial_fields).setVisibility(serial ? View.VISIBLE : View.GONE);
        findViewById(R.id.lighting_gpio_fields).setVisibility(serial ? View.GONE : View.VISIBLE);
        View gpioMap = findViewById(R.id.lighting_gpio_map);
        if (gpioMap != null) gpioMap.setVisibility(serial ? View.GONE : View.VISIBLE);
        styleMode(R.id.lighting_serial_mode, serial);
        styleMode(R.id.lighting_gpio_mode, !serial);
    }

    private void styleMode(int id, boolean selected) {
        TextView button = findViewById(id);
        button.setBackgroundResource(R.drawable.mc_segment_item);
        button.setTextColor(getColorCompat(selected ? R.color.mc_blue : R.color.mc_muted));
        button.setSelected(selected);
    }

    private int getColorCompat(int id) {
        return androidx.core.content.ContextCompat.getColor(this, id);
    }

    private LightConfig configuration() {
        rememberForm();
        try {
            return new LightConfig(mode, path, LightConfig.SERIAL.equals(mode) ? Integer.parseInt(baud) : LightConfig.DEFAULT_BAUD,
                    gpio, LightConfig.GPIO.equals(mode) ? Integer.parseInt(onLevel) : 0,
                    LightConfig.GPIO.equals(mode) ? Integer.parseInt(offLevel) : 1,
                    commandPrefix() + onCommand, commandPrefix() + offCommand);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("波特率和电平必须是有效整数");
        }
    }

    private void saveConfiguration() {
        try {
            LightConfig config = configuration();
            stopTest();
            config.save(prefs);
            prefs.edit().putInt("light_command_format", commandFormat).apply();
            toast(getString(R.string.lighting_saved_short));
        } catch (IllegalArgumentException invalid) {
            showError(invalid.getMessage());
        }
    }

    private void test(boolean on) {
        if (!resumed) return;
        try {
            LightConfig config = configuration();
            if (lighting == null) acquireLighting();
            if (loading == null) loading = new LoadingDialog(this);
            loading.show(getString(on ? R.string.lighting_turning_on : R.string.lighting_turning_off));
            if (on) lighting.turnOn(config);
            else lighting.turnOff(config);
        } catch (IllegalArgumentException invalid) {
            showError(invalid.getMessage());
        }
    }

    private void acquireLighting() {
        lighting = LightController.getInstance().acquire((on, success, message) -> {
            if (!resumed || destroyed) return;
            if (loading != null) loading.dismiss();
            if (success) toast(message);
            else notice(message);
        });
    }

    private void showError(String message) {
        stopTest();
        notice(message);
    }

    private void stopTest() {
        if (loading != null) loading.dismiss();
        if (lighting != null) lighting.close();
        lighting = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        dismissCommandFormat();
        stopTest();
        super.onPause();
    }

    @Override
    protected void onStop() {
        stopTest();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        dismissCommandFormat();
        stopTest();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        rememberForm();
        state.putString("mode", mode);
        state.putString("path", path);
        state.putString("baud", baud);
        state.putString("onCommand", onCommand);
        state.putString("offCommand", offCommand);
        state.putInt("lightCommandStateVersion", 2);
        state.putInt("commandFormat", commandFormat);
        state.putString("gpio", gpio);
        state.putString("on", onLevel);
        state.putString("off", offLevel);
        super.onSaveInstanceState(state);
    }
}
