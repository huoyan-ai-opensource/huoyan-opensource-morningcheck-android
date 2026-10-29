package cc.huoyan.opensource.morningcheck.camera;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraImageOptionsTest {
    @Test public void mirrorAndCropShareTheDisplayedCoordinatesAndPreserveInput() {
        byte[] image = new byte[4 * 2 * 3];
        for (int i = 0; i < 8; i++) for (int c = 0; c < 3; c++) image[i * 3 + c] = (byte) i;
        byte[] original = image.clone();
        CameraImageOptions options = new CameraImageOptions("4x2", true, .25f, 0, .75f, 1);
        assertArrayEquals(new byte[]{2,2,2,1,1,1,6,6,6,5,5,5}, options.pixels(image, 4, 2));
        assertArrayEquals(original, image);
        assertEquals(2, options.width(4)); assertEquals(2, options.height(2));
    }

    @Test public void verticalCropUsesWholePixelsAndFullPreviewKeepsOnlyMirrorAndResolution() {
        byte[] image = new byte[3 * 3 * 3];
        for (int i = 0; i < 9; i++) for (int c = 0; c < 3; c++) image[i * 3 + c] = (byte) i;
        CameraImageOptions options = new CameraImageOptions("640x480", false, 0, .34f, 1, 1);
        assertEquals(1, options.topPixel(3)); assertEquals(2, options.height(3));
        assertEquals(3, options.pixels(image, 3, 3)[0]);
        assertSame(image, options.fullPreview().pixels(image, 3, 3));
        assertEquals("640x480", options.fullPreview().resolution);
    }

    @Test public void invalidOrInvertedRangesAreRejected() {
        for (float[] range : new float[][]{{Float.NaN,0,1,1},{0,0,2,1},{.8f,0,.2f,1},{0,0,1,.01f}}) {
            try { new CameraImageOptions("", false, range[0], range[1], range[2], range[3]); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
