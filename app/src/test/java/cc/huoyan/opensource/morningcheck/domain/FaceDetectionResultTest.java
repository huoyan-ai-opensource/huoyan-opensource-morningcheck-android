package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class FaceDetectionResultTest {
    @Test public void zeroOneTwoAndThreeFacesHaveDistinctAdmission() throws Exception {
        for (int count = 0; count <= 3; count++) {
            JSONArray faces = new JSONArray();
            for (int i = 0; i < count; i++) faces.put(face());
            FaceDetectionResult result = FaceDetectionResult.parse(new JSONObject().put("faces", faces));
            assertEquals(count, result.count);
            assertEquals(count == 1, result.isSingle());
            assertEquals(count, result.boxes.length());
        }
    }

    @Test public void smallOrLowScoreBackgroundFaceStillMakesItMultiple() throws Exception {
        JSONArray faces = new JSONArray().put(face())
                .put(face().put("w", .005).put("h", .005).put("score", .01));
        FaceDetectionResult result = FaceDetectionResult.parse(new JSONObject().put("faces", faces));
        assertEquals(2, result.count);
        assertFalse(result.isSingle());
    }

    @Test public void missingMalformedOrIncompleteResultsCannotEnableSaving() throws Exception {
        assertInvalid(new JSONObject());
        assertInvalid(new JSONObject().put("faces", "1"));
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(JSONObject.NULL)));
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(face().put("w", 0))));
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(face().put("x", "0.1"))));
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(face().put("landmarks", new JSONArray()))));
        JSONObject missingScore = face();
        missingScore.remove("score");
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(missingScore)));
        assertInvalid(new JSONObject().put("faces", new JSONArray().put(face()).put(face()).put(face()).put(face())));
    }

    @Test public void parsedSnapshotDoesNotChangeWhenCallerReusesResponse() throws Exception {
        JSONObject source = face();
        FaceDetectionResult result = FaceDetectionResult.parse(new JSONObject()
                .put("faces", new JSONArray().put(source)));
        source.put("w", 0);
        assertEquals(.3, result.boxes.getJSONObject(0).getDouble("w"), .001);
    }

    private static JSONObject face() throws Exception {
        JSONArray points = new JSONArray();
        for (int i = 0; i < 10; i++) points.put(.2);
        return new JSONObject().put("x", .1).put("y", .1).put("w", .3).put("h", .4)
                .put("score", .9).put("landmarks", points);
    }

    private static void assertInvalid(JSONObject response) {
        try { FaceDetectionResult.parse(response); fail("损坏的检测结果不得放行"); }
        catch (IllegalArgumentException expected) { }
    }
}
