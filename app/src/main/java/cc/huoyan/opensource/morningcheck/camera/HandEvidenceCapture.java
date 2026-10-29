package cc.huoyan.opensource.morningcheck.camera;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;

/**
 * 只接收当前连续合格采样的帧。姿态拒绝、暂停或 SDK 失败时，宿主须同步 reset。
 * 每种异常至多保留一个候选帧及最后一个正常帧，避免长期采集积累完整 RGB 图像。
 */
public final class HandEvidenceCapture {
    private final Map<Integer, Photo> candidates = new LinkedHashMap<>();
    private Photo latestNormal;
    private long sequence;

    public void accept(CameraSource.Frame frame, JSONArray boxes) {
        if (frame == null) throw new IllegalArgumentException("手部证据缺少原始画面");
        Photo photo = new Photo(frame, parseAbnormalities(boxes), ++sequence);
        if (photo.classIds.isEmpty()) {
            latestNormal = photo;
            return;
        }
        for (Integer classId : photo.classIds) {
            Photo previous = candidates.get(classId);
            // 优先保留同帧异常更完整的证据；数量相同使用新帧。
            if (previous == null || photo.classIds.size() >= previous.classIds.size())
                candidates.put(classId, photo);
        }
    }

    public void reset() {
        candidates.clear();
        latestNormal = null;
        sequence = 0;
    }

    /**
     * 快照与来源帧绑定，保存到后台后继续采集不会改写该照片的坐标。
     */
    public Evidence evidence() {
        if (candidates.isEmpty()) return new Evidence(latestNormal, Collections.emptyList());
        List<Photo> available = new ArrayList<>(new LinkedHashSet<>(candidates.values()));
        Photo primary = bestCovering(available, null);
        Set<Integer> uncovered = new LinkedHashSet<>(candidates.keySet());
        uncovered.removeAll(primary.classIds);
        List<Photo> extras = new ArrayList<>();
        while (!uncovered.isEmpty()) {
            Photo extra = bestCovering(available, uncovered);
            if (extra == null) break;
            extras.add(extra);
            uncovered.removeAll(extra.classIds);
        }
        return new Evidence(primary, extras);
    }

    private static Photo bestCovering(List<Photo> photos, Set<Integer> needed) {
        Photo best = null;
        int bestCount = 0;
        for (Photo photo : photos) {
            int count = 0;
            for (Integer classId : photo.classIds)
                if (needed == null || needed.contains(classId)) count++;
            if (count > bestCount || (count > 0 && count == bestCount
                    && (best == null || photo.sequence > best.sequence))) {
                best = photo;
                bestCount = count;
            }
        }
        return best;
    }

    private static List<Abnormality> parseAbnormalities(JSONArray boxes) {
        List<Abnormality> parsed = new ArrayList<>();
        if (boxes != null) for (int index = 0; index < boxes.length(); index++) {
            JSONObject box = boxes.optJSONObject(index);
            if (box == null) continue;
            int classId = box.optInt("class_id", -1);
            String label = labelFor(classId);
            double score = box.optDouble("score", Double.NaN);
            double x = box.optDouble("x", Double.NaN), y = box.optDouble("y", Double.NaN);
            double w = box.optDouble("w", Double.NaN), h = box.optDouble("h", Double.NaN);
            if (label == null || !Double.isFinite(score) || score <= 0 || score > 1
                    || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(w) || !Double.isFinite(h) || w <= 0 || h <= 0) continue;
            // AIDL/WS 返回相对发送 RGB 帧的归一化左上坐标，不使用预览的镜像变换。
            double left = Math.max(0, x), top = Math.max(0, y);
            double right = Math.min(1, x + w), bottom = Math.min(1, y + h);
            if (!Double.isFinite(right) || !Double.isFinite(bottom)
                    || right <= left || bottom <= top) continue;
            parsed.add(new Abnormality(classId, (float) score, (float) left, (float) top,
                    (float) (right - left), (float) (bottom - top), label));
        }
        return parsed;
    }

    private static String labelFor(int classId) {
        switch (classId) {
            case DetectionClasses.HAND_NAIL:
                return "指甲油";
            case DetectionClasses.HAND_PLASTER:
                return "创可贴";
            case DetectionClasses.HAND_RING:
                return "戒指";
            case DetectionClasses.HAND_WOUND:
                return "伤口";
            default:
                return null;
        }
    }

    public static final class Abnormality {
        public final int classId;
        public final float score, x, y, w, h;
        public final String label;

        private Abnormality(int classId, float score, float x, float y, float w, float h, String label) {
            this.classId = classId;
            this.score = score;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
        }
    }

    public static final class Photo {
        public final CameraSource.Frame frame;
        public final List<Abnormality> abnormalities;
        private final List<Integer> classIds;
        private final long sequence;

        private Photo(CameraSource.Frame frame, List<Abnormality> abnormalities, long sequence) {
            this.frame = frame;
            this.abnormalities = Collections.unmodifiableList(new ArrayList<>(abnormalities));
            Set<Integer> ids = new LinkedHashSet<>();
            for (Abnormality abnormality : abnormalities) ids.add(abnormality.classId);
            this.classIds = Collections.unmodifiableList(new ArrayList<>(ids));
            this.sequence = sequence;
        }

        public List<Integer> classIds() {
            return classIds;
        }

        public List<String> labels() {
            List<String> labels = new ArrayList<>();
            for (Integer classId : classIds) labels.add(labelFor(classId));
            return Collections.unmodifiableList(labels);
        }
    }

    public static final class Evidence {
        /**
         * 未接收过有效采样时为 null；无异常时为最后一帧正常画面。
         */
        public final Photo primary;
        public final List<Photo> extras;

        private Evidence(Photo primary, List<Photo> extras) {
            this.primary = primary;
            this.extras = Collections.unmodifiableList(new ArrayList<>(extras));
        }
    }
}
