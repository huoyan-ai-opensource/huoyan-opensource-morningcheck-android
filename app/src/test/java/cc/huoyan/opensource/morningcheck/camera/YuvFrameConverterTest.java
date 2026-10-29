package cc.huoyan.opensource.morningcheck.camera;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class YuvFrameConverterTest {
    @Test public void readsPaddedRowsInterleavedChromaAndBufferOffsets() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{99, 16, 82, 99, 99, (byte) 145, (byte) 235});
        y.position(1);
        ByteBuffer u = ByteBuffer.wrap(new byte[]{99, (byte) 128});
        u.position(1);
        ByteBuffer v = ByteBuffer.wrap(new byte[]{99, (byte) 128});
        v.position(1);
        byte[] rgb = YuvFrameConverter.toRgb(y, 4, 1, u, 4, 2, v, 4, 2, 0, 0, 2, 2, 0);
        assertArrayEquals(new byte[]{0, 0, 0, 77, 77, 77, (byte) 150, (byte) 150, (byte) 150, (byte) 255, (byte) 255, (byte) 255}, rgb);
        assertEquals(1, y.position());
        assertEquals(1, u.position());
    }

    @Test public void rotatesClockwiseAndPreservesRgbChannelOrder() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{16, 82, (byte) 145, (byte) 235});
        ByteBuffer chroma = ByteBuffer.wrap(new byte[]{(byte) 128});
        byte[] rgb = YuvFrameConverter.toRgb(y, 2, 1, chroma, 1, 1, chroma, 1, 1, 0, 0, 2, 2, 90);
        assertArrayEquals(new byte[]{(byte) 150, (byte) 150, (byte) 150, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 77, 77, 77}, rgb);
        byte[] red = YuvFrameConverter.toRgb(ByteBuffer.wrap(new byte[]{82}), 1, 1,
                ByteBuffer.wrap(new byte[]{90}), 1, 1, ByteBuffer.wrap(new byte[]{(byte) 240}), 1, 1, 0, 0, 1, 1, 0);
        assertArrayEquals(new byte[]{(byte) 255, 1, 0}, red);
    }

    @Test public void honorsCropAndChromaPixelStride() {
        ByteBuffer y = ByteBuffer.wrap(new byte[]{16, 16, 82, 82, 16, 16, 82, 82});
        ByteBuffer u = ByteBuffer.wrap(new byte[]{(byte) 128, 99, 90});
        ByteBuffer v = ByteBuffer.wrap(new byte[]{(byte) 128, 99, (byte) 240});
        byte[] rgb = YuvFrameConverter.toRgb(y, 4, 1, u, 4, 2, v, 4, 2, 2, 0, 2, 2, 270);
        assertArrayEquals(new byte[]{(byte) 255, 1, 0, (byte) 255, 1, 0, (byte) 255, 1, 0, (byte) 255, 1, 0}, rgb);
    }

    @Test public void frontAndBackUseDifferentDisplayRotationSigns() {
        assertEquals(0, YuvFrameConverter.uprightRotation(90, 90, false));
        assertEquals(180, YuvFrameConverter.uprightRotation(90, 90, true));
        assertEquals(90, YuvFrameConverter.uprightRotation(90, 0, false));
        assertEquals(180, YuvFrameConverter.uprightRotation(90, 270, false));
    }
}
