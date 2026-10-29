package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * 已注册人数回执（Issue #10）：{@code userCount()} 的载荷——
 * distinct personId 数（一人多向量计 1）。
 */
public final class UserCountParcel implements Parcelable {

    /**
     * 0 = 成功；非 0 = 错误码（mc 段 -1..-7 / 壳族 -1001..）。
     */
    public final int code;
    /**
     * 错误说明（code=0 时为 null）。
     */
    public final String reason;
    /**
     * 已注册人数（code=0 时 &ge;0；失败回执 0 占位）。
     */
    public final int count;

    public UserCountParcel(int code, String reason, int count) {
        this.code = code;
        this.reason = reason;
        this.count = count;
    }

    /**
     * 成功回执。
     */
    public static UserCountParcel ok(int count) {
        return new UserCountParcel(0, null, count);
    }

    /**
     * 失败回执。
     */
    public static UserCountParcel err(int code, String reason) {
        return new UserCountParcel(code, reason, 0);
    }

    private UserCountParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        count = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(reason);
        out.writeInt(count);
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
        if (!(o instanceof UserCountParcel)) {
            return false;
        }
        UserCountParcel that = (UserCountParcel) o;
        return code == that.code && count == that.count && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        int h = code;
        h = 31 * h + Objects.hashCode(reason);
        h = 31 * h + count;
        return h;
    }

    @Override
    public String toString() {
        return "UserCountParcel{code=" + code + ", count=" + count + ", reason=" + reason + "}";
    }

    public static final Creator<UserCountParcel> CREATOR = new Creator<UserCountParcel>() {
        @Override
        public UserCountParcel createFromParcel(Parcel in) {
            return new UserCountParcel(in);
        }

        @Override
        public UserCountParcel[] newArray(int size) {
            return new UserCountParcel[size];
        }
    };
}
