package cc.huoyan.opensource.morningcheck.camera;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;

/** 验证跨帧异常的照片归属，不能把旧坐标画到最后一张正常图。 */
public final class HandEvidenceCaptureTest {
    @Test public void earlyAbnormalityKeepsItsOwnFrameAfterNormalSamples() throws Exception {
        HandEvidenceCapture capture = new HandEvidenceCapture();
        CameraSource.Frame ring = frame(10), normal = frame(20);
        JSONArray boxes = new JSONArray().put(box(DetectionClasses.HAND_RING, .2, .3, .1, .2));
        capture.accept(ring, boxes);
        boxes.getJSONObject(0).put("x", .8);
        capture.accept(normal, new JSONArray());
        HandEvidenceCapture.Evidence saved = capture.evidence();
        assertSame(ring, saved.primary.frame);
        assertEquals(.2f, saved.primary.abnormalities.get(0).x, .0001f);
        assertEquals(Arrays.asList("戒指"), saved.primary.labels());
        assertTrue(saved.extras.isEmpty());
    }

    @Test public void separateAbnormalitiesKeepSeparatePhotosAndSnapshotsSurviveReset() throws Exception {
        HandEvidenceCapture capture = new HandEvidenceCapture();
        CameraSource.Frame ring = frame(10), wound = frame(20);
        capture.accept(ring, new JSONArray().put(box(DetectionClasses.HAND_RING, .2, .3, .1, .2)));
        capture.accept(wound, new JSONArray().put(box(DetectionClasses.HAND_WOUND, .6, .2, .1, .1)));
        HandEvidenceCapture.Evidence saved = capture.evidence();
        assertSame(wound, saved.primary.frame);
        assertEquals(1, saved.extras.size());
        assertSame(ring, saved.extras.get(0).frame);
        capture.reset();
        assertNull(capture.evidence().primary);
        assertEquals(Arrays.asList(DetectionClasses.HAND_WOUND), saved.primary.classIds());
        assertEquals(Arrays.asList(DetectionClasses.HAND_RING), saved.extras.get(0).classIds());
    }

    @Test public void mostCompleteFrameIsPrimaryAndNormalGroupUsesLastFrame() throws Exception {
        HandEvidenceCapture capture = new HandEvidenceCapture();
        CameraSource.Frame complete = frame(30), ringOnly = frame(40), normal = frame(50);
        capture.accept(complete, new JSONArray().put(box(DetectionClasses.HAND_RING, .2, .3, .1, .2))
                .put(box(DetectionClasses.HAND_NAIL, .4, .4, .1, .1)));
        capture.accept(ringOnly, new JSONArray().put(box(DetectionClasses.HAND_RING, .8, .3, .1, .2)));
        capture.accept(normal, new JSONArray());
        assertSame(complete, capture.evidence().primary.frame);
        assertTrue(capture.evidence().extras.isEmpty());
        capture.reset();
        capture.accept(complete, new JSONArray());
        capture.accept(normal, new JSONArray());
        assertSame(normal, capture.evidence().primary.frame);
    }

    @Test public void invalidBoxesCannotInventLocationsAndEdgesAreClipped() throws Exception {
        HandEvidenceCapture capture = new HandEvidenceCapture();
        JSONArray boxes = new JSONArray().put(box(DetectionClasses.HAND_BACK, .1, .1, .2, .2))
                .put(box(DetectionClasses.HAND_RING, .1, .1, .2, .2).put("score", 0))
                .put(box(DetectionClasses.HAND_WOUND, 2, .1, .2, .2))
                .put(box(DetectionClasses.HAND_NAIL, .1, .1, -1, .2))
                .put(box(DetectionClasses.HAND_PLASTER, -.1, .9, .3, .3));
        capture.accept(frame(10), boxes);
        HandEvidenceCapture.Photo saved = capture.evidence().primary;
        assertEquals(Arrays.asList(DetectionClasses.HAND_PLASTER), saved.classIds());
        assertEquals(1, saved.abnormalities.size());
        HandEvidenceCapture.Abnormality clipped = saved.abnormalities.get(0);
        assertEquals(0f, clipped.x, .0001f);
        assertEquals(.2f, clipped.w, .0001f);
        assertEquals(.1f, clipped.h, .0001f);
    }

    private static CameraSource.Frame frame(int value) {
        return new CameraSource.Frame(new byte[]{(byte) value, 0, 0}, 1, 1);
    }

    private static JSONObject box(int classId, double x, double y, double w, double h) throws Exception {
        return new JSONObject().put("class_id", classId).put("score", .95)
                .put("x", x).put("y", y).put("w", w).put("h", h);
    }
}
