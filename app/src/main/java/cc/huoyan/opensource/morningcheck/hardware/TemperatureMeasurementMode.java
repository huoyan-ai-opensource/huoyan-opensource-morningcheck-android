package cc.huoyan.opensource.morningcheck.hardware;

import java.nio.charset.StandardCharsets;

/** 海曼测温算法模式，与串口读取协议分别设置。 */
public enum TemperatureMeasurementMode {
    INSIDE("inside", "*inside"),
    OUTSIDE("outside", "*outside"),
    TRIGGER("trigger", "*trigger");

    private final String value;
    private final String command;

    TemperatureMeasurementMode(String value, String command) {
        this.value = value;
        this.command = command;
    }

    public String getValue() {
        return value;
    }

    /** 未确认过的设备模式保持未知，不推定模块出厂或上次状态。 */
    public static TemperatureMeasurementMode fromValue(String value) {
        for (TemperatureMeasurementMode mode : values()) {
            if (mode.value.equals(value)) return mode;
        }
        return null;
    }

    byte[] command() {
        return command.getBytes(StandardCharsets.US_ASCII);
    }
}
