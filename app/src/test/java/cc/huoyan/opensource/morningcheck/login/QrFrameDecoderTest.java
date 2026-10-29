package cc.huoyan.opensource.morningcheck.login;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class QrFrameDecoderTest {
    private final QrFrameDecoder decoder = new QrFrameDecoder();

    @Test
    public void decodesOriginalShopValueWithoutTrimmingOrParsing() throws Exception {
        String raw = "  erp-shop-007  ";
        assertEquals(raw, decoder.decode(qr(raw, 320, 240), 320, 240));
        String url = "https://shops.example.test/bind?shopId=007";
        assertEquals(url, decoder.decode(qr(url, 320, 240), 320, 240));
    }

    @Test
    public void decodesUtf8ShopIdentifier() throws Exception {
        String raw = "门店-晨检-007";
        assertEquals(raw, decoder.decode(qr(raw, 320, 240), 320, 240));
    }

    @Test
    public void decodesEveryRightAngleRotation() throws Exception {
        String raw = "morning-store-rotation-123";
        byte[] rgb = qr(raw, 320, 240);
        int width = 320, height = 240;
        for (int turn = 0; turn < 4; turn++) {
            assertEquals("旋转 " + turn * 90 + " 度", raw, decoder.decode(rgb, width, height));
            rgb = rotateClockwise(rgb, width, height);
            int oldWidth = width;
            width = height;
            height = oldWidth;
        }
    }

    @Test
    public void decodesInvertedCode() throws Exception {
        byte[] rgb = qr("shop-inverted-001", 240, 240);
        for (int index = 0; index < rgb.length; index++) rgb[index] = (byte) ~rgb[index];
        assertEquals("shop-inverted-001", decoder.decode(rgb, 240, 240));
    }

    @Test
    public void ignoresWhitespaceAndFramesWithoutQr() throws Exception {
        assertNull(decoder.decode(qr("   ", 240, 240), 240, 240));
        assertNull(decoder.decode(new byte[320 * 240 * 3], 320, 240));
    }

    @Test
    public void rejectsMalformedDimensions() {
        assertNull(decoder.decode(null, 320, 240));
        assertNull(decoder.decode(new byte[3], 0, 1));
        assertNull(decoder.decode(new byte[3], 1, 2));
        assertNull(decoder.decode(new byte[3], Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static byte[] qr(String raw, int width, int height) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, width, height,
                Collections.singletonMap(EncodeHintType.CHARACTER_SET, "UTF-8"));
        byte[] rgb = new byte[width * height * 3];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int offset = (y * width + x) * 3;
            byte value = matrix.get(x, y) ? 0 : (byte) 255;
            rgb[offset] = value;
            rgb[offset + 1] = value;
            rgb[offset + 2] = value;
        }
        return rgb;
    }

    private static byte[] rotateClockwise(byte[] source, int width, int height) {
        byte[] target = new byte[source.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int from = (y * width + x) * 3;
            int to = (x * height + height - 1 - y) * 3;
            System.arraycopy(source, from, target, to, 3);
        }
        return target;
    }
}
