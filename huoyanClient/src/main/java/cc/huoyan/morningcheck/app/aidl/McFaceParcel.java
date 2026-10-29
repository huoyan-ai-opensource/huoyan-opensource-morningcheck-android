package cc.huoyan.morningcheck.app.aidl;

import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * 人脸检测条目：归一化框、置信度及五对归一化关键点，不包含员工身份。
 */
public final class McFaceParcel implements Parcelable {
    public final float x;
    public final float y;
    public final float w;
    public final float h;
    public final float score;
    private final float[] landmarks;

    public McFaceParcel(float x, float y, float w, float h, float score, float[] landmarks) {
        if (landmarks == null || landmarks.length != 10) {
            throw new IllegalArgumentException("人脸关键点必须包含 10 个坐标值");
        }
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.score = score;
        this.landmarks = landmarks.clone();
    }

    public float[] getLandmarks() {
        return landmarks.clone();
    }

    private McFaceParcel(Parcel in) {
        if (in.dataAvail() < 20) throw new BadParcelableException("人脸检测条目不完整");
        x = in.readFloat();
        y = in.readFloat();
        w = in.readFloat();
        h = in.readFloat();
        score = in.readFloat();
        landmarks = readArray(in, 10, "landmarks");
    }

    /**
     * 先校验声明长度和剩余字节，再分配固定预期长度，拒绝远端伪造超大数组。
     */
    static float[] readArray(Parcel in, int expected, String name) {
        if (in.dataAvail() < 4 || in.readInt() != expected
                || (long) expected * 4 > in.dataAvail()) {
            throw new BadParcelableException("人脸检测 " + name + " 长度与条数不符");
        }
        float[] values = new float[expected];
        for (int i = 0; i < expected; i++) values[i] = in.readFloat();
        return values;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(w);
        out.writeFloat(h);
        out.writeFloat(score);
        out.writeFloatArray(landmarks);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof McFaceParcel)) return false;
        McFaceParcel that = (McFaceParcel) other;
        return Float.compare(x, that.x) == 0 && Float.compare(y, that.y) == 0
                && Float.compare(w, that.w) == 0 && Float.compare(h, that.h) == 0
                && Float.compare(score, that.score) == 0 && Arrays.equals(landmarks, that.landmarks);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(new float[]{x, y, w, h, score});
        return 31 * result + Arrays.hashCode(landmarks);
    }

    public static final Creator<McFaceParcel> CREATOR = new Creator<McFaceParcel>() {
        @Override
        public McFaceParcel createFromParcel(Parcel in) {
            return new McFaceParcel(in);
        }

        @Override
        public McFaceParcel[] newArray(int size) {
            return new McFaceParcel[size];
        }
    };
}
