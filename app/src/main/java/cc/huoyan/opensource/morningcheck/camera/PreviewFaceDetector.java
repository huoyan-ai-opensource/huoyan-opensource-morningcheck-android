package cc.huoyan.opensource.morningcheck.camera;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 仅为预览定位真实人脸；身份与晨检准入仍以 SDK 回执为准。调用方在后台线程执行。
 */
public final class PreviewFaceDetector {
    private static final int MAX_FACES = 4;

    private PreviewFaceDetector() {
    }

    public static final class Result {
        public final boolean available;
        public final JSONArray boxes;

        private Result(boolean available, JSONArray boxes) {
            this.available = available;
            this.boxes = boxes;
        }
    }

    public static JSONArray detect(CameraSource.Frame frame) {
        return detectResult(frame).boxes;
    }

    /**
     * 检测不可用与有效空框分开，调用方不能把系统检测失败当作员工未入镜。
     */
    public static Result detectResult(CameraSource.Frame frame) {
        JSONArray boxes = new JSONArray();
        // 系统检测器要求 RGB_565 和偶数宽度。右边裁去的至多一列不改变原图坐标。
        if (frame == null) return new Result(false, boxes);
        int width = frame.width & ~1;
        if (width < 2 || frame.height < 2) return new Result(false, boxes);
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, frame.height, Bitmap.Config.RGB_565);
            int[] colors = new int[width * frame.height];
            for (int y = 0; y < frame.height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * frame.width + x) * 3;
                    colors[y * width + x] = 0xff000000 | (frame.rgb[offset] & 255) << 16
                            | (frame.rgb[offset + 1] & 255) << 8 | (frame.rgb[offset + 2] & 255);
                }
            }
            bitmap.setPixels(colors, 0, width, 0, 0, width, frame.height);
            FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            int count = new FaceDetector(width, frame.height, MAX_FACES).findFaces(bitmap, faces);
            PointF midpoint = new PointF();
            for (int i = 0; i < count; i++) {
                FaceDetector.Face face = faces[i];
                if (face == null || face.confidence() < FaceDetector.Face.CONFIDENCE_THRESHOLD)
                    continue;
                face.getMidPoint(midpoint);
                float eyeDistance = face.eyesDistance();
                if (!Float.isFinite(eyeDistance) || eyeDistance <= 0) continue;
                // 检测器提供双眼中心与眼距；据此定位脸部外接区域，而不是固定引导框。
                float left = Math.max(0, midpoint.x - eyeDistance * 1.6f);
                float top = Math.max(0, midpoint.y - eyeDistance * 1.5f);
                float right = Math.min(frame.width, midpoint.x + eyeDistance * 1.6f);
                float bottom = Math.min(frame.height, midpoint.y + eyeDistance * 2.1f);
                if (right <= left || bottom <= top) continue;
                boxes.put(new JSONObject().put("x", left / frame.width).put("y", top / frame.height)
                        .put("w", (right - left) / frame.width).put("h", (bottom - top) / frame.height));
            }
        } catch (RuntimeException | LinkageError | JSONException | OutOfMemoryError ignored) {
            // 部分设备不提供系统检测器；不影响 SDK 身份流程，也不能画假框替代。
            return new Result(false, new JSONArray());
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
        return new Result(true, boxes);
    }
}
