package cc.huoyan.opensource.morningcheck.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 真机执行系统检测器，不以 SDK 的 matched 字段制造预览框。 */
@RunWith(AndroidJUnit4.class)
public final class PreviewFaceDetectorTest {
    @Test public void emptyFrameNeverProducesFaceBox() {
        assertEquals(0, PreviewFaceDetector.detect(new CameraSource.Frame(new byte[640 * 480 * 3], 640, 480)).length());
        assertEquals(0, PreviewFaceDetector.detect(new CameraSource.Frame(new byte[639 * 480 * 3], 639, 480)).length());
        assertEquals(0, PreviewFaceDetector.detect(new CameraSource.Frame(new byte[3], 1, 1)).length());
    }

    @Test public void realFaceBoxesUseImageCoordinatesWithoutIdentityLabels() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("previewFacePath", "");
        assumeTrue("真实人脸素材只通过本次设备测试参数传入，不随 APK 发布", !path.isEmpty());
        Bitmap bitmap = BitmapFactory.decodeFile(path);
        assertNotNull("应读取本次测试的人脸素材", bitmap);
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] rgb = new byte[pixels.length * 3];
            for (int i = 0; i < pixels.length; i++) {
                rgb[i * 3] = (byte) (pixels[i] >> 16);
                rgb[i * 3 + 1] = (byte) (pixels[i] >> 8);
                rgb[i * 3 + 2] = (byte) pixels[i];
            }
            JSONArray boxes = PreviewFaceDetector.detect(new CameraSource.Frame(rgb, width, height));
            assertTrue("正脸素材应得到真实检测框", boxes.length() > 0);
            for (int i = 0; i < boxes.length(); i++) {
                JSONObject box = boxes.getJSONObject(i);
                double x = box.getDouble("x"), y = box.getDouble("y");
                double w = box.getDouble("w"), h = box.getDouble("h");
                assertTrue(x >= 0 && y >= 0 && w > 0 && h > 0);
                assertTrue(x + w <= 1.00001 && y + h <= 1.00001);
                assertEquals("框只含坐标，不携带姓名或人员编号", 4, box.length());
            }
        } finally {
            bitmap.recycle();
        }
    }
}
