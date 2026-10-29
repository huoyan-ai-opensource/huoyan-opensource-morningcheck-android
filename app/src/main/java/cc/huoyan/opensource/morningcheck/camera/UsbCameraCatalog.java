package cc.huoyan.opensource.morningcheck.camera;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * USB 视频设备枚举；不依赖系统是否把 UVC 镜头映射为 Camera2。
 */
final class UsbCameraCatalog {
    static final String PREFIX = "usb:";

    static final class Entry {
        final UsbDevice device;
        final String id;
        final String label;

        Entry(UsbDevice device, boolean repeatedProduct) {
            this.device = device;
            String key = productKey(device);
            // 不同产品的 ID 不包含随重插变化的总线地址。同型号多台必须保留各自地址，
            // Android 未授权时不能可靠读取序列号，地址失效时要求重选，不能猜测镜头。
            id = key + (repeatedProduct ? ":" + Uri.encode(device.getDeviceName()) : "");
            String product = device.getProductName();
            if (product == null || product.trim().isEmpty()) product = "USB 摄像头";
            label = product + " · " + key.substring(PREFIX.length())
                    + (repeatedProduct ? " · " + device.getDeviceName() : "");
        }
    }

    static List<Entry> list(Context context) {
        List<UsbDevice> devices = new ArrayList<>();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager != null) {
            for (UsbDevice device : manager.getDeviceList().values()) {
                if (isVideoDevice(device)) devices.add(device);
            }
        }
        devices.sort(Comparator.comparing(UsbCameraCatalog::productKey)
                .thenComparing(UsbDevice::getDeviceName));
        List<Entry> entries = new ArrayList<>();
        for (UsbDevice device : devices) {
            int sameProduct = 0;
            for (UsbDevice other : devices)
                if (productKey(device).equals(productKey(other))) sameProduct++;
            entries.add(new Entry(device, sameProduct > 1));
        }
        return entries;
    }

    static Entry resolve(Context context, String id) {
        List<Entry> entries = list(context);
        if (id.isEmpty()) return entries.isEmpty() ? null : entries.get(0);
        for (Entry entry : entries) {
            if (entry.id.equals(id)) return entry;
            // 同型号的另一支被拔掉后，当前条目会恢复短 ID；已保存的完整地址仍可准确定位。
            String addressedId = productKey(entry.device) + ":" + Uri.encode(entry.device.getDeviceName());
            if (addressedId.equals(id)) return entry;
        }
        for (Entry entry : entries) {
            if (productKey(entry.device).equals(id))
                throw new IllegalStateException("同型号 USB 摄像头有多台，请在设备设置中重新选择具体镜头");
        }
        throw new IllegalStateException("选定 USB 摄像头未连接或连接位置已变化，请在设备设置中重新选择");
    }

    private static String productKey(UsbDevice device) {
        return String.format(Locale.ROOT, PREFIX + "%04x:%04x", device.getVendorId(), device.getProductId());
    }

    private static boolean isVideoDevice(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            if (device.getInterface(index).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO)
                return true;
        }
        return false;
    }
}
