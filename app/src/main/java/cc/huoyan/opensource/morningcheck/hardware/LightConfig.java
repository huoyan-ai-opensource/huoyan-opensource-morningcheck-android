package cc.huoyan.opensource.morningcheck.hardware;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 补光灯配置与旧版协议一致；测温串口与灯光串口分别保存。
 */
public final class LightConfig {
    public static final String SERIAL = "serial", GPIO = "gpio";
    public static final String DEFAULT_PATH = "/dev/ttyUSB0";
    public static final String DEFAULT_GPIO = "122,148";
    public static final int DEFAULT_BAUD = 115200;
    public static final int ASCII = 0, DECIMAL = 10, HEXADECIMAL = 16;
    // 默认按旧版发送文本 A00101A0 / A00100A0，切换进制只改变表示，不改变实际字节。
    public static final String DEFAULT_ON_COMMAND = "ASCII:A00101A0", DEFAULT_OFF_COMMAND = "ASCII:A00100A0";
    public final String mode, path, onCommand, offCommand;
    public final int baud, onLevel, offLevel;
    private final int[] gpio;

    public LightConfig(String mode, String path, int baud, String selections, int onLevel, int offLevel) {
        this(mode, path, baud, selections, onLevel, offLevel, DEFAULT_ON_COMMAND, DEFAULT_OFF_COMMAND);
    }

    public LightConfig(String mode, String path, int baud, String selections, int onLevel, int offLevel,
                       String onCommand, String offCommand) {
        this.onCommand = normalizeCommand(onCommand);
        this.offCommand = normalizeCommand(offCommand);
        if (SERIAL.equals(mode)) {
            commandBytes(this.onCommand);
            commandBytes(this.offCommand);
            if (Arrays.equals(commandBytes(this.onCommand), commandBytes(this.offCommand)))
                throw new IllegalArgumentException("开启与关闭指令不能相同");
        }
        if (!SERIAL.equals(mode) && !GPIO.equals(mode))
            throw new IllegalArgumentException("请选择灯光控制方式");
        this.mode = mode;
        this.path = path == null ? "" : path.trim();
        this.baud = baud;
        this.onLevel = onLevel;
        this.offLevel = offLevel;
        if (SERIAL.equals(mode) && (!this.path.matches("/dev/[a-zA-Z0-9_.-]+") || baud <= 0))
            throw new IllegalArgumentException("请填写有效的灯光串口路径与波特率");
        if (GPIO.equals(mode) && ((onLevel != 0 && onLevel != 1) || (offLevel != 0 && offLevel != 1) || onLevel == offLevel))
            throw new IllegalArgumentException("开灯、关灯电平必须分别为 0 和 1");
        Set<Integer> pins = new LinkedHashSet<>();
        if (GPIO.equals(mode)) {
            if (selections == null || selections.trim().isEmpty())
                throw new IllegalArgumentException("请填写 GPIO 编号");
            for (String part : selections.split("[,，]", -1)) {
                try {
                    int pin = Integer.parseInt(part.trim());
                    if (pin < 0 || pin > 65535) throw new NumberFormatException();
                    pins.add(pin);
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("GPIO 编号应为整数，多个编号以逗号分隔");
                }
            }
            if (pins.size() > 16) throw new IllegalArgumentException("最多配置 16 个 GPIO");
        }
        gpio = new int[pins.size()];
        int i = 0;
        for (int pin : pins) gpio[i++] = pin;
    }

    public int[] gpioIndexes() {
        return gpio.clone();
    }

    public String gpioSelections() {
        StringBuilder text = new StringBuilder();
        for (int pin : gpio) {
            if (text.length() > 0) text.append(',');
            text.append(pin);
        }
        return text.toString();
    }

    public static LightConfig read(SharedPreferences prefs) {
        try {
            String mode = prefs.getString("light_mode", SERIAL);
            return new LightConfig(mode,
                    prefs.getString("light_serial_path", DEFAULT_PATH),
                    SERIAL.equals(mode) ? Integer.parseInt(prefs.getString("light_baud", String.valueOf(DEFAULT_BAUD))) : DEFAULT_BAUD,
                    prefs.getString("light_gpio", DEFAULT_GPIO),
                    GPIO.equals(mode) ? Integer.parseInt(prefs.getString("light_on_level", "0")) : 0,
                    GPIO.equals(mode) ? Integer.parseInt(prefs.getString("light_off_level", "1")) : 1,
                    prefs.getString("light_on_command", DEFAULT_ON_COMMAND),
                    prefs.getString("light_off_command", DEFAULT_OFF_COMMAND));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("灯光配置中的波特率或电平不是有效整数");
        }
    }

    public static void restoreDefaultCommandsOnce(SharedPreferences prefs) {
        if (prefs.getBoolean("light_hex_defaults_restored", false)) return;
        // 本次升级仅恢复两条指令；保留设备接线参数，也不覆盖用户后续保存的自定义指令。
        prefs.edit().putString("light_on_command", DEFAULT_ON_COMMAND)
                .putString("light_off_command", DEFAULT_OFF_COMMAND)
                .putBoolean("light_hex_defaults_restored", true).apply();
    }

    public void save(SharedPreferences prefs) {
        SharedPreferences.Editor edit = prefs.edit().putString("light_mode", mode);
        if (SERIAL.equals(mode))
            edit.putString("light_serial_path", path).putString("light_baud", String.valueOf(baud))
                    .putString("light_on_command", onCommand).putString("light_off_command", offCommand);
        else
            edit.putString("light_gpio", gpioSelections()).putString("light_on_level", String.valueOf(onLevel))
                    .putString("light_off_level", String.valueOf(offLevel));
        edit.apply();
    }

    /**
     * DEC: / HEX: 后接对应进制的字节，ASCII: 后接文本；无前缀时仍按旧版十六进制配置解析。
     */
    public static byte[] commandBytes(String command) {
        String value = normalizeCommand(command);
        if (value.isEmpty()) throw new IllegalArgumentException("请填写补光指令");
        if (value.startsWith("ASCII:")) {
            String text = value.substring(6);
            if (text.isEmpty() || text.length() > 64 || !text.matches("[ -~]+"))
                throw new IllegalArgumentException("字符指令须为 1–64 个可打印英文字符、数字或符号");
            return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        if (value.regionMatches(true, 0, "DEC:", 0, 4)) {
            String decimal = value.substring(4).trim();
            String[] parts = decimal.split("\\s+");
            if (decimal.isEmpty() || parts.length > 64)
                throw new IllegalArgumentException("十进制指令须为 1–64 个 0–255 的整数，以空格分隔");
            byte[] bytes = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    if (!parts[i].matches("[0-9]+")) throw new NumberFormatException();
                    int number = Integer.parseInt(parts[i]);
                    if (number > 255) throw new NumberFormatException();
                    bytes[i] = (byte) number;
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("十进制指令须为 1–64 个 0–255 的整数，以空格分隔");
                }
            }
            return bytes;
        }
        if (value.regionMatches(true, 0, "HEX:", 0, 4)) value = value.substring(4);
        String hex = value.replaceAll("\\s+", "");
        if (hex.isEmpty() || hex.length() > 128 || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+"))
            throw new IllegalArgumentException("请按所选格式填写有效的字符、十进制或十六进制指令");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    private static String normalizeCommand(String command) {
        if (command == null) return "";
        String value = command.trim();
        // 字符模式中的首尾空格也是设备字节，不能在显示转换或保存时丢弃。
        return value.startsWith("ASCII:") ? command.substring(command.indexOf("ASCII:")) : value;
    }

    public static String formatCommand(String command, int radix) {
        if (radix != DECIMAL && radix != HEXADECIMAL && radix != ASCII)
            throw new IllegalArgumentException("请选择字符、十进制或十六进制");
        byte[] bytes = commandBytes(command);
        if (radix == ASCII) {
            for (byte data : bytes) {
                if (data < 32 || data > 126)
                    throw new IllegalArgumentException("指令包含不可显示为字符的字节，请使用十进制或十六进制");
            }
            return "ASCII:" + new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
        StringBuilder text = new StringBuilder(radix == DECIMAL ? "DEC:" : "HEX:");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) text.append(' ');
            int value = bytes[i] & 0xFF;
            if (radix == DECIMAL) text.append(value);
            else {
                text.append("0123456789ABCDEF".charAt(value >>> 4));
                text.append("0123456789ABCDEF".charAt(value & 0x0F));
            }
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof LightConfig)) return false;
        LightConfig other = (LightConfig) value;
        return mode.equals(other.mode) && path.equals(other.path) && baud == other.baud
                && onCommand.equals(other.onCommand) && offCommand.equals(other.offCommand)
                && onLevel == other.onLevel && offLevel == other.offLevel && Arrays.equals(gpio, other.gpio);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, path, baud, onLevel, offLevel, onCommand, offCommand, Arrays.hashCode(gpio));
    }
}
