package cc.huoyan.opensource.morningcheck;

import android.os.Build;

/**
 * 与零售客户端一致读取设备硬件标识；无法访问时明确显示不可用。
 */
final class DeviceIdentity {
    private DeviceIdentity() {
    }

    static String hardwareSerial(android.content.Context context) {
        for (String key : new String[]{"ro.serialno", "ro.boot.serialno"}) {
            try {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                String serial = (String) properties.getMethod("get", String.class).invoke(null, key);
                if (usable(serial)) return serial.trim();
            } catch (ReflectiveOperationException | SecurityException unavailable) {
            }
        }
        try {
            boolean allowed = context.checkCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT < 29 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED);
            if (Build.VERSION.SDK_INT >= 26 && allowed) {
                String serial = permittedSerial();
                if (usable(serial)) return serial.trim();
            }
        } catch (SecurityException unavailable) {
        }
        return usable(Build.SERIAL) ? Build.SERIAL.trim() : "系统未提供";
    }

    @android.annotation.SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(26)
    private static String permittedSerial() {
        // 调用方已验证特权/电话权限，系统仍可能因发行策略拒绝，由调用方捕获。
        return Build.getSerial();
    }

    private static boolean usable(String value) {
        return value != null && !value.trim().isEmpty() && !"unknown".equalsIgnoreCase(value.trim())
                && !"null".equalsIgnoreCase(value.trim());
    }
}
