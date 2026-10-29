package cc.huoyan.opensource.morningcheck.login;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.Map;

/**
 * 直接解析采集线程交付的 RGB 帧，不从 UI 线程截图。每次调用独立，支持旋转与反色码。
 */
public final class QrFrameDecoder {
    public String decode(byte[] rgb, int width, int height) {
        if (rgb == null || width <= 0 || height <= 0
                || (long) width * height * 3 != rgb.length) return null;
        byte[] luminance = new byte[width * height];
        for (int pixel = 0, offset = 0; pixel < luminance.length; pixel++, offset += 3) {
            int red = rgb[offset] & 255;
            int green = rgb[offset + 1] & 255;
            int blue = rgb[offset + 2] & 255;
            luminance[pixel] = (byte) ((red + green * 2 + blue) / 4);
        }
        LuminanceSource source = new GrayFrame(width, height, luminance);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        QRCodeReader reader = new QRCodeReader();
        for (int polarity = 0; polarity < 2; polarity++) {
            try {
                Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
                // 与零售登录保持一致：只检查非空，原样传递外部门店标识。
                String raw = result.getText();
                if (raw != null && !raw.trim().isEmpty()) return raw;
            } catch (ReaderException ignored) {
                // 连续预览中没有二维码是正常状态，下一帧再尝试。
            } finally {
                reader.reset();
            }
            source = source.invert();
        }
        return null;
    }

    private static final class GrayFrame extends LuminanceSource {
        private final byte[] pixels;

        GrayFrame(int width, int height, byte[] pixels) {
            super(width, height);
            this.pixels = pixels;
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int width = getWidth();
            if (row == null || row.length < width) row = new byte[width];
            System.arraycopy(pixels, y * width, row, 0, width);
            return row;
        }

        @Override
        public byte[] getMatrix() {
            return pixels;
        }
    }
}
