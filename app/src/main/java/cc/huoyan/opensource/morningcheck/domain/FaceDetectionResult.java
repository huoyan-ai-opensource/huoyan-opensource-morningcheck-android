package cc.huoyan.opensource.morningcheck.domain;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 完整画面的人脸检测结果；仅校验回执，不按大小或分数过滤背景人脸。
 */
public final class FaceDetectionResult {
    public static final int MAX_FACES = 3;
    public final JSONArray boxes;
    public final int count;

    private FaceDetectionResult(JSONArray boxes) {
        this.boxes = boxes;
        count = boxes.length();
    }

    public boolean isSingle() {
        return count == 1;
    }

    /**
     * 缺失或损坏的回执必须明确失败，不能当成无人脸或沿用上次单脸结果。
     */
    public static FaceDetectionResult parse(JSONObject data) {
        JSONArray faces = data == null ? null : data.optJSONArray("faces");
        if (faces == null || faces.length() > MAX_FACES)
            throw new IllegalArgumentException("人脸检测回执缺少有效结果");
        try {
            for (int i = 0; i < faces.length(); i++) {
                JSONObject face = faces.optJSONObject(i);
                if (face == null) throw new IllegalArgumentException("人脸检测条目不完整");
                finiteNumber(face.opt("x"));
                finiteNumber(face.opt("y"));
                double width = finiteNumber(face.opt("w"));
                double height = finiteNumber(face.opt("h"));
                finiteNumber(face.opt("score"));
                if (width <= 0 || height <= 0)
                    throw new IllegalArgumentException("人脸检测框尺寸无效");
                JSONArray points = face.optJSONArray("landmarks");
                if (points == null || points.length() != 10)
                    throw new IllegalArgumentException("人脸检测关键点不完整");
                for (int point = 0; point < points.length(); point++)
                    finiteNumber(points.opt(point));
            }
            return new FaceDetectionResult(new JSONArray(faces.toString()));
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("人脸检测回执格式无效", invalid);
        }
    }

    private static double finiteNumber(Object value) {
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()))
            throw new IllegalArgumentException("人脸检测坐标或分数无效");
        return ((Number) value).doubleValue();
    }
}
