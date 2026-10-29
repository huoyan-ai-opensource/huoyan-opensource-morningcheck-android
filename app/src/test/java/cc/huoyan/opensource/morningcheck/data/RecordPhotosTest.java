package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class RecordPhotosTest {
    @Test public void capturedArrayKeepsEveryPhotoAndItsOrderInsteadOfLimitingToThree() throws Exception {
        JSONArray captured = new JSONArray();
        for (int i = 0; i < 8; i++) captured.put(new JSONObject().put("file", "photo-" + i + ".jpg").put("label", "照片 " + i));
        List<RecordPhotos.Photo> result = RecordPhotos.read(new JSONObject().put("captured_photos", captured).put("face_img_file", "legacy.jpg"));
        assertEquals(8, result.size());
        assertEquals("photo-7.jpg", result.get(7).file);
        assertEquals("照片 7", result.get(7).label);
    }

    @Test public void missingArrayAndEmptyArrayReadLegacyEvidence() throws Exception {
        JSONObject record = new JSONObject().put("face_img_file", "face.jpg").put("back_hand_img_file", "back.jpg");
        for (int run = 0; run < 2; run++) {
            List<RecordPhotos.Photo> result = RecordPhotos.read(record);
            assertEquals(3, result.size());
            assertEquals("face.jpg", result.get(0).file);
            assertEquals("手背", result.get(1).label);
            assertEquals("", result.get(2).file);
            record.put("captured_photos", new JSONArray());
        }
    }

    @Test public void malformedPhotoKeepsPositionAndUsesReadableLabel() throws Exception {
        JSONArray captured = new JSONArray().put(new JSONObject().put("file", "first.jpg"))
                .put(JSONObject.NULL).put(new JSONObject().put("file", " last.jpg "));
        List<RecordPhotos.Photo> result = RecordPhotos.read(new JSONObject().put("captured_photos", captured));
        assertEquals(3, result.size());
        assertEquals("", result.get(1).file);
        assertEquals("采集照片 2", result.get(1).label);
        assertEquals("last.jpg", result.get(2).file);
        assertTrue(RecordPhotos.read(null).isEmpty());
    }
}
