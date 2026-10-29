package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;


/**
 * 识别证据的跨进程载体；客户端副本只保留壳的线契约，不依赖壳内 JNI 投影类。
 *
 * <p>字段序冻结（手写对称）：{@code personId(J)} → {@code similarity(F)} →
 * {@code matched(Z 以 int 1/0 承载)}——与 bridge 构造器描述符同序，便于人审对照。</p>
 */
public final class McIdentityParcel implements Parcelable {

    /**
     * 命中人员的业务侧唯一 ID（业务域 long，非句柄）；未命中为 0。
     */
    public final long personId;
    /**
     * 命中相似度（余弦，域 [-1,1]）；未命中时为真实最优候选值（非 0 哨兵）。
     */
    public final float similarity;
    /**
     * true = 命中；false = 未命中或帧中无人脸（后者非错误）。
     */
    public final boolean matched;

    public McIdentityParcel(long personId, float similarity, boolean matched) {
        this.personId = personId;
        this.similarity = similarity;
        this.matched = matched;
    }

    /**
     * Parcel 构造器（包内可见：{@link IdentityResultParcel} 嵌套读共用，不经 CREATOR）。
     */
    McIdentityParcel(Parcel in) {
        personId = in.readLong();
        similarity = in.readFloat();
        matched = in.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(personId);
        out.writeFloat(similarity);
        out.writeInt(matched ? 1 : 0);
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
        if (!(o instanceof McIdentityParcel)) {
            return false;
        }
        McIdentityParcel that = (McIdentityParcel) o;
        return personId == that.personId && Float.floatToIntBits(similarity)
                == Float.floatToIntBits(that.similarity) && matched == that.matched;
    }

    @Override
    public int hashCode() {
        int h = (int) (personId ^ (personId >>> 32));
        h = 31 * h + Float.floatToIntBits(similarity);
        h = 31 * h + (matched ? 1 : 0);
        return h;
    }

    @Override
    public String toString() {
        return "McIdentityParcel{personId=" + personId + ", similarity=" + similarity
                + ", matched=" + matched + "}";
    }

    public static final Creator<McIdentityParcel> CREATOR = new Creator<McIdentityParcel>() {
        @Override
        public McIdentityParcel createFromParcel(Parcel in) {
            return new McIdentityParcel(in);
        }

        @Override
        public McIdentityParcel[] newArray(int size) {
            return new McIdentityParcel[size];
        }
    };
}
