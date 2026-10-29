package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * 注册回执（Issue #10）：{@code register()} 的载荷——成功携带自增 uid
 * （业务域 long，&gt;0 为登记完成、0 为无检测框；native 的 -1 哨兵不外泄）。
 */
public final class RegisterResultParcel implements Parcelable {

    /**
     * 0 = 成功；非 0 = 错误码（mc 段 -1..-7 / 壳族 -1001..）。
     */
    public final int code;
    /**
     * 错误说明（code=0 时为 null）。
     */
    public final String reason;
    /**
     * 新向量 uid（&gt;0 登记完成、0 无检测框；失败回执也以 0 占位）。
     */
    public final long uid;
    /**
     * SDK 保存的录入帧 HTTP 地址；无框、存图失败或旧壳回执为空。
     */
    public final String imageUrl;

    public RegisterResultParcel(int code, String reason, long uid) {
        this(code, reason, uid, "");
    }

    public RegisterResultParcel(int code, String reason, long uid, String imageUrl) {
        this.code = code;
        this.reason = reason;
        this.uid = uid;
        this.imageUrl = imageUrl == null ? "" : imageUrl;
    }

    /**
     * 成功回执。
     */
    public static RegisterResultParcel ok(long uid) {
        return new RegisterResultParcel(0, null, uid);
    }

    public static RegisterResultParcel ok(long uid, String imageUrl) {
        return new RegisterResultParcel(0, null, uid, imageUrl);
    }

    /**
     * 失败回执。
     */
    public static RegisterResultParcel err(int code, String reason) {
        return new RegisterResultParcel(code, reason, 0L);
    }

    private RegisterResultParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        uid = in.readLong();
        imageUrl = ImageUrlParcelExtension.read(in, 1)[0];
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(reason);
        out.writeLong(uid);
        ImageUrlParcelExtension.write(out, new String[]{imageUrl});
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
        if (!(o instanceof RegisterResultParcel)) {
            return false;
        }
        RegisterResultParcel that = (RegisterResultParcel) o;
        return code == that.code && uid == that.uid && Objects.equals(reason, that.reason)
                && Objects.equals(imageUrl, that.imageUrl);
    }

    @Override
    public int hashCode() {
        int h = code;
        h = 31 * h + Objects.hashCode(reason);
        h = 31 * h + (int) (uid ^ (uid >>> 32));
        h = 31 * h + imageUrl.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "RegisterResultParcel{code=" + code + ", uid=" + uid + ", reason=" + reason + "}";
    }

    public static final Creator<RegisterResultParcel> CREATOR = new Creator<RegisterResultParcel>() {
        @Override
        public RegisterResultParcel createFromParcel(Parcel in) {
            return new RegisterResultParcel(in);
        }

        @Override
        public RegisterResultParcel[] newArray(int size) {
            return new RegisterResultParcel[size];
        }
    };
}
