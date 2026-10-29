package cc.huoyan.morningcheck.app.aidl;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * 人脸检测回执：条数 + 五个框/置信度数组 + 每人脸连续 10 值的关键点数组。
 */
public final class FaceListParcel implements Parcelable {
    public final int code;
    public final String reason;
    public final int count;
    private final float[] boxX;
    private final float[] boxY;
    private final float[] boxW;
    private final float[] boxH;
    private final float[] score;
    private final float[] landmarks;

    public FaceListParcel(int code, String reason, int count, float[] boxX, float[] boxY,
                          float[] boxW, float[] boxH, float[] score, float[] landmarks) {
        if (count < 0 || count > Integer.MAX_VALUE / 10
                || !hasLength(boxX, count) || !hasLength(boxY, count)
                || !hasLength(boxW, count) || !hasLength(boxH, count)
                || !hasLength(score, count) || !hasLength(landmarks, count * 10)) {
            throw new IllegalArgumentException("人脸检测平行数组长度与条数不符");
        }
        this.code = code;
        this.reason = reason;
        this.count = count;
        this.boxX = boxX.clone();
        this.boxY = boxY.clone();
        this.boxW = boxW.clone();
        this.boxH = boxH.clone();
        this.score = score.clone();
        this.landmarks = landmarks.clone();
    }

    private static boolean hasLength(float[] values, int length) {
        return values != null && values.length == length;
    }

    public float[] getBoxX() {
        return boxX.clone();
    }

    public float[] getBoxY() {
        return boxY.clone();
    }

    public float[] getBoxW() {
        return boxW.clone();
    }

    public float[] getBoxH() {
        return boxH.clone();
    }

    public float[] getScore() {
        return score.clone();
    }

    public float[] getLandmarks() {
        return landmarks.clone();
    }

    public static FaceListParcel emptyOk() {
        return err(0, null);
    }

    public static FaceListParcel err(int code, String reason) {
        return new FaceListParcel(code, reason, 0, new float[0], new float[0], new float[0],
                new float[0], new float[0], new float[0]);
    }

    public McFaceParcel[] toItems() {
        McFaceParcel[] items = new McFaceParcel[count];
        for (int i = 0; i < count; i++) {
            items[i] = new McFaceParcel(boxX[i], boxY[i], boxW[i], boxH[i], score[i],
                    Arrays.copyOfRange(landmarks, i * 10, (i + 1) * 10));
        }
        return items;
    }

    private FaceListParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        count = in.readInt();
        // 每条至少占 15 个 float，先以剩余载荷约束 count，防止恶意长度分配。
        if (count < 0 || (long) count * 60 + 24 > in.dataAvail()) {
            throw new BadParcelableException("人脸检测条数超出实际载荷");
        }
        boxX = McFaceParcel.readArray(in, count, "boxX");
        boxY = McFaceParcel.readArray(in, count, "boxY");
        boxW = McFaceParcel.readArray(in, count, "boxW");
        boxH = McFaceParcel.readArray(in, count, "boxH");
        score = McFaceParcel.readArray(in, count, "score");
        landmarks = McFaceParcel.readArray(in, count * 10, "landmarks");
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(reason);
        out.writeInt(count);
        out.writeFloatArray(boxX);
        out.writeFloatArray(boxY);
        out.writeFloatArray(boxW);
        out.writeFloatArray(boxH);
        out.writeFloatArray(score);
        out.writeFloatArray(landmarks);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FaceListParcel)) return false;
        FaceListParcel that = (FaceListParcel) other;
        return code == that.code && count == that.count && Objects.equals(reason, that.reason)
                && Arrays.equals(boxX, that.boxX) && Arrays.equals(boxY, that.boxY)
                && Arrays.equals(boxW, that.boxW) && Arrays.equals(boxH, that.boxH)
                && Arrays.equals(score, that.score) && Arrays.equals(landmarks, that.landmarks);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(code, reason, count);
        result = 31 * result + Arrays.hashCode(boxX);
        result = 31 * result + Arrays.hashCode(boxY);
        result = 31 * result + Arrays.hashCode(boxW);
        result = 31 * result + Arrays.hashCode(boxH);
        result = 31 * result + Arrays.hashCode(score);
        return 31 * result + Arrays.hashCode(landmarks);
    }

    public static final Creator<FaceListParcel> CREATOR = new Creator<FaceListParcel>() {
        @Override
        public FaceListParcel createFromParcel(Parcel in) {
            return new FaceListParcel(in);
        }

        @Override
        public FaceListParcel[] newArray(int size) {
            return new FaceListParcel[size];
        }
    };
}
