package cc.huoyan.opensource.morningcheck.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录照片按采集顺序读取；旧记录没有数组时仍可查看原来的三类证据。
 */
public final class RecordPhotos {
    private RecordPhotos() {
    }

    public static List<Photo> read(JSONObject record) {
        if (record == null) return Collections.emptyList();
        List<Photo> photos = new ArrayList<>();
        JSONArray captured = record.optJSONArray("captured_photos");
        if (captured != null && captured.length() > 0) {
            for (int i = 0; i < captured.length(); i++) {
                JSONObject item = captured.optJSONObject(i);
                String label = item == null ? "" : item.optString("label").trim();
                // 即使某一项文件名缺失也保留位置，不能把后续照片下标接到另一张图上。
                photos.add(new Photo(item == null ? "" : item.optString("file").trim(),
                        label.isEmpty() ? "采集照片 " + (i + 1) : label));
            }
        } else {
            String[] keys = {"face_img_file", "back_hand_img_file", "front_hand_img_file"};
            String[] labels = {"人脸与头部", "手背", "手心"};
            for (int i = 0; i < keys.length; i++)
                photos.add(new Photo(record.optString(keys[i]).trim(), labels[i]));
        }
        return Collections.unmodifiableList(photos);
    }

    public static final class Photo {
        public final String file, label;

        private Photo(String file, String label) {
            this.file = file;
            this.label = label;
        }
    }
}
