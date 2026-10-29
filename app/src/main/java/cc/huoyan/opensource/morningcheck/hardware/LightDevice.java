package cc.huoyan.opensource.morningcheck.hardware;

import android.serialport.SerialPort;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅操作已配置的设备；不执行 su/chmod，不扫描并切换未知串口。
 */
final class LightDevice {

    static LightController.Device open(LightConfig config) throws IOException {
        if (LightConfig.GPIO.equals(config.mode)) return openGpio(config);
        File path = new SerialDeviceFile(config.path);
        if (!path.getCanonicalPath().startsWith("/dev/"))
            throw new IOException("所选路径不是串口设备");
        if (!path.exists()) throw new IOException("灯光串口不存在：" + config.path);
        path.canRead();
        path.canWrite();
        SerialPort serial = SerialPort.newBuilder(path, config.baud).dataBits(8).parity(0).stopBits(1)
                .flags(OsConstants.O_NONBLOCK).build();
        final byte[] onCommand = LightConfig.commandBytes(config.onCommand);
        final byte[] offCommand = LightConfig.commandBytes(config.offCommand);
        final OutputStream output;
        try {
            output = serial.getOutputStream();
        } catch (RuntimeException | LinkageError error) {
            serial.tryClose();
            throw error;
        }
        return new LightController.Device() {
            @Override
            public void setOn(boolean on) throws IOException {
                output.write(on ? onCommand : offCommand);
                output.flush();
            }

            @Override
            public void close() {
                serial.tryClose();
            }
        };
    }

    private static LightController.Device openGpio(LightConfig config) throws IOException {
        int[] pins = config.gpioIndexes();
        List<Integer> prepared = new ArrayList<>();
        try {
            for (int pin : pins) {
                File directory = new File("/sys/class/gpio/gpio" + pin);
                if (!directory.exists())
                    writeNode(new File("/sys/class/gpio/export"), String.valueOf(pin));
                prepared.add(pin);
                // sysfs 的 high/low 在设为输出时同时给出关灯电平，避免 out 的默认低电平点亮灯。
                writeNode(new File(directory, "direction"), config.offLevel == 1 ? "high" : "low");
                writeNode(new File(directory, "value"), String.valueOf(config.offLevel));
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            // 初始化只设置关电平；中途失败仍逐个回写已准备节点，保留所有失败证据。
            for (int pin : prepared) {
                try {
                    writeNode(new File("/sys/class/gpio/gpio" + pin + "/value"), String.valueOf(config.offLevel));
                } catch (IOException | RuntimeException | LinkageError cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("GPIO 初始化失败：" + error.getMessage(), error);
        }
        return new LightController.Device() {
            @Override
            public void setOn(boolean on) throws IOException {
                IOException failure = null;
                for (int pin : pins) {
                    try {
                        writeNode(new File("/sys/class/gpio/gpio" + pin + "/value"), String.valueOf(on ? config.onLevel : config.offLevel));
                    } catch (IOException | RuntimeException | LinkageError error) {
                        IOException detail = error instanceof IOException ? (IOException) error
                                : new IOException("GPIO " + pin + " 写入失败", error);
                        if (failure == null) failure = detail;
                        else failure.addSuppressed(detail);
                    }
                }
                if (failure != null) throw failure;
            }

            @Override
            public void close() {
            }
        };
    }

    private static void writeNode(File node, String value) throws IOException {
        if (!node.exists()) throw new IOException("GPIO 节点不存在：" + node.getPath());
        if (!node.canWrite())
            throw new IOException("没有 GPIO 写入权限：" + node.getPath() + "，请联系设备供应商配置");
        try (FileOutputStream output = new FileOutputStream(node)) {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }
    }

    /**
     * 拦住串口库的自动提权分支；native open 仍须通过设备权限与 SELinux。
     */
    private static final class SerialDeviceFile extends File {
        SerialDeviceFile(String path) {
            super(path);
        }

        @Override
        public boolean canRead() {
            if (!super.canRead()) throw new SecurityException("没有灯光串口读取权限");
            return true;
        }

        @Override
        public boolean canWrite() {
            if (!super.canWrite()) throw new SecurityException("没有灯光串口写入权限");
            return true;
        }
    }
}
