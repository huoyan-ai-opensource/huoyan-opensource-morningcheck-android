package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;


/**
 * 检测项的 comm 投影（Issue #10 §2）：{@code McDetection}（bridge 包）的
 * 条目级线形态。<b>bridge 类不得实现 Parcelable</b>（构造器描述符
 * {@code (FFFFIF)V} 是上游 {@code mc_jni.c} 的 {@code GetMethodID + NewObject}
 * JNI 硬依赖——同 {@code McIdentityParcel} 类注释），故 comm 包另建本投影。
 *
 * <p><b>与 {@link DetectionListParcel} 的关系</b>：回执级线形态是
 * <b>平行数组 + 条数</b>（Issue #10 §2 禁裸 List 的落地），本类是条目级投影
 * （bridge 数组 ↔ 平行数组的转换载体，自身也手写对称 Parcelable 供条目级往返
 * 审计）。字段序冻结：{@code x/y/w/h(F×4)} → {@code classId(I)} →
 * {@code score(F)}——与 bridge 构造器描述符同序。</p>
 */
public final class McDetectionParcel implements Parcelable {

    /**
     * 框左上角 x（相对输入帧归一化，0..1）。
     */
    public final float x;
    /**
     * 框左上角 y（相对输入帧归一化，0..1）。
     */
    public final float y;
    /**
     * 框宽（相对输入帧归一化，0..1）。
     */
    public final float w;
    /**
     * 框高（相对输入帧归一化，0..1）。
     */
    public final float h;
    /**
     * 检测类别（head: 0=hat 1=mask；hand: 8 类见 {@code McDetection#classId}）。
     */
    public final int classId;
    /**
     * 置信度（模型已 sigmoid，0..1）。
     */
    public final float score;

    public McDetectionParcel(float x, float y, float w, float h, int classId, float score) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.classId = classId;
        this.score = score;
    }

    /**
     * Parcel 构造器（包内可见：条目级嵌套读共用，不经 CREATOR）。
     */
    McDetectionParcel(Parcel in) {
        x = in.readFloat();
        y = in.readFloat();
        w = in.readFloat();
        h = in.readFloat();
        classId = in.readInt();
        score = in.readFloat();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeFloat(x);
        out.writeFloat(y);
        out.writeFloat(w);
        out.writeFloat(h);
        out.writeInt(classId);
        out.writeFloat(score);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof McDetectionParcel)) {
            return false;
        }
        McDetectionParcel that = (McDetectionParcel) o;
        return Float.floatToIntBits(x) == Float.floatToIntBits(that.x)
                && Float.floatToIntBits(y) == Float.floatToIntBits(that.y)
                && Float.floatToIntBits(w) == Float.floatToIntBits(that.w)
                && Float.floatToIntBits(h) == Float.floatToIntBits(that.h)
                && classId == that.classId
                && Float.floatToIntBits(score) == Float.floatToIntBits(that.score);
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        result = 31 * result + Float.floatToIntBits(w);
        result = 31 * result + Float.floatToIntBits(h);
        result = 31 * result + classId;
        result = 31 * result + Float.floatToIntBits(score);
        return result;
    }

    @Override
    public String toString() {
        return "McDetectionParcel{x=" + x + ", y=" + y + ", w=" + w + ", h=" + h
                + ", classId=" + classId + ", score=" + score + "}";
    }

    public static final Creator<McDetectionParcel> CREATOR = new Creator<McDetectionParcel>() {
        @Override
        public McDetectionParcel createFromParcel(Parcel in) {
            return new McDetectionParcel(in);
        }

        @Override
        public McDetectionParcel[] newArray(int size) {
            return new McDetectionParcel[size];
        }
    };
}
