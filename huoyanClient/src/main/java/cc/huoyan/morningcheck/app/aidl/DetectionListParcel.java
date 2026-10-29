package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;


/**
 * 头部/手部检测结果回执（Issue #10 §2 平行数组纪律的落地）：checkHead/checkHand
 * 的载荷——<b>条数 + 六个平行数组</b>（x/y/w/h/score 五 float 组 + classId int 组），
 * 不使用裸 {@code List}/{@code Map}，不使用 {@code writeTypedList} /
 * {@code writeParcelableArray}（API 门槛与反射开销双重理由，Issue #10 §2 禁令）。
 *
 * <p><b>读出后回溯断言</b>：私有构造器读完六个数组后断言
 * {@code 各数组长度 == count}——线序错位/条数漂移会静默错配（数组间下标失配），
 * 回溯断言把它变成显式失败而非错数据。</p>
 *
 * <p><b>条目语义</b>：坐标相对输入帧归一化 0..1；classId 语义随入口
 * （head: 0=hat 1=mask；hand: 8 类见 {@code McDetection#classId}）。
 * 无检出 = {@code count=0} + 六个空数组（正常空结果非错误）。</p>
 */
public final class DetectionListParcel implements Parcelable {

    /**
     * 0 = 成功；非 0 = 错误码（mc 段 -1..-7 / 壳族 -1001..）。
     */
    public final int code;
    /**
     * 错误说明（code=0 时为 null）。
     */
    public final String reason;
    /**
     * 条数（六数组公共长度；0 = 无检出）。
     */
    public final int count;
    /**
     * 框左上角 x 组（归一化 0..1）。
     */
    public final float[] boxX;
    /**
     * 框左上角 y 组（归一化 0..1）。
     */
    public final float[] boxY;
    /**
     * 框宽组（归一化 0..1）。
     */
    public final float[] boxW;
    /**
     * 框高组（归一化 0..1）。
     */
    public final float[] boxH;
    /**
     * 检测类别组（语义随入口，见类注释）。
     */
    public final int[] classId;
    /**
     * 置信度组（0..1）。
     */
    public final float[] score;

    public DetectionListParcel(int code, String reason, int count,
                               float[] boxX, float[] boxY, float[] boxW, float[] boxH,
                               int[] classId, float[] score) {
        this.code = code;
        this.reason = reason;
        this.count = count;
        this.boxX = boxX;
        this.boxY = boxY;
        this.boxW = boxW;
        this.boxH = boxH;
        this.classId = classId;
        this.score = score;
    }

    /**
     * 成功空结果（无检出）。
     */
    public static DetectionListParcel emptyOk() {
        return new DetectionListParcel(0, null, 0, new float[0], new float[0],
                new float[0], new float[0], new int[0], new float[0]);
    }

    /**
     * 失败回执（空数组占位，count=0）。
     */
    public static DetectionListParcel err(int code, String reason) {
        return emptyOkErr(code, reason);
    }

    private static DetectionListParcel emptyOkErr(int code, String reason) {
        return new DetectionListParcel(code, reason, 0, new float[0], new float[0],
                new float[0], new float[0], new int[0], new float[0]);
    }


    /**
     * 平行数组 → 条目级投影（{@code McDetectionParcel} 组；长度以 count 为准）。
     */
    public McDetectionParcel[] toItems() {
        McDetectionParcel[] out = new McDetectionParcel[count];
        for (int i = 0; i < count; i++) {
            out[i] = new McDetectionParcel(boxX[i], boxY[i], boxW[i], boxH[i],
                    classId[i], score[i]);
        }
        return out;
    }

    private DetectionListParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        count = in.readInt();
        boxX = in.createFloatArray();
        boxY = in.createFloatArray();
        boxW = in.createFloatArray();
        boxH = in.createFloatArray();
        classId = in.createIntArray();
        score = in.createFloatArray();
        // 读出后回溯断言（Issue #10 §2）：六数组长度必须与条数一致，
        // 否则下标间失配 = 静默错数据，显式炸优于错配。
        if (boxX.length != count || boxY.length != count || boxW.length != count
                || boxH.length != count || classId.length != count || score.length != count) {
            throw new IllegalStateException("DetectionListParcel 平行数组长度与条数不符：count="
                    + count + " 实得 x/y/w/h/classId/score=" + boxX.length + "/" + boxY.length
                    + "/" + boxW.length + "/" + boxH.length + "/" + classId.length
                    + "/" + score.length);
        }
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
        out.writeIntArray(classId);
        out.writeFloatArray(score);
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
        if (!(o instanceof DetectionListParcel)) {
            return false;
        }
        DetectionListParcel that = (DetectionListParcel) o;
        return code == that.code && count == that.count && Objects.equals(reason, that.reason)
                && Arrays.equals(boxX, that.boxX) && Arrays.equals(boxY, that.boxY)
                && Arrays.equals(boxW, that.boxW) && Arrays.equals(boxH, that.boxH)
                && Arrays.equals(classId, that.classId) && Arrays.equals(score, that.score);
    }

    @Override
    public int hashCode() {
        int h = code;
        h = 31 * h + Objects.hashCode(reason);
        h = 31 * h + count;
        h = 31 * h + Arrays.hashCode(boxX);
        h = 31 * h + Arrays.hashCode(classId);
        return h;
    }

    @Override
    public String toString() {
        return "DetectionListParcel{code=" + code + ", count=" + count + ", reason=" + reason + "}";
    }

    public static final Creator<DetectionListParcel> CREATOR = new Creator<DetectionListParcel>() {
        @Override
        public DetectionListParcel createFromParcel(Parcel in) {
            return new DetectionListParcel(in);
        }

        @Override
        public DetectionListParcel[] newArray(int size) {
            return new DetectionListParcel[size];
        }
    };
}
