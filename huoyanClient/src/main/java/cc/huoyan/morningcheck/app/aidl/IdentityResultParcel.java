package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * 识别回执（Issue #10）：{@code identify()} 的载荷——hasIdentity 位 +
 * {@code McIdentityParcel} 投影（手写嵌套：<b>不使用</b> {@code writeParcelable}，
 * 有位标 + 直接 {@code writeToParcel}，读侧镜像；{@code McIdentity}（bridge 类）
 * 不得直接上线，见 {@code McIdentityParcel} 类注释的 JNI 硬依赖理由）。
 *
 * <p>「无人脸 = matched false 非错误」：code=0 + hasIdentity=1 + matched=false
 * 是正常结果；hasIdentity=0 只出现在错误回执（identify 失败无证据可携）。</p>
 */
public final class IdentityResultParcel implements Parcelable {

    /**
     * 0 = 成功；非 0 = 错误码（mc 段 -1..-7 / 壳族 -1001..）。
     */
    public final int code;
    /**
     * 错误说明（code=0 时为 null）。
     */
    public final String reason;
    /**
     * 1 = 携带识别证据；0 = 无（错误回执）。
     */
    public final int hasIdentity;
    /**
     * 识别证据投影（hasIdentity=1 时非 null）。
     */
    public final McIdentityParcel identity;

    public IdentityResultParcel(int code, String reason, int hasIdentity,
                                McIdentityParcel identity) {
        this.code = code;
        this.reason = reason;
        this.hasIdentity = hasIdentity;
        this.identity = identity;
    }

    /**
     * 成功回执（携带证据）。
     */
    public static IdentityResultParcel ok(McIdentityParcel identity) {
        return new IdentityResultParcel(0, null, 1, identity);
    }

    /**
     * 失败回执（无证据）。
     */
    public static IdentityResultParcel err(int code, String reason) {
        return new IdentityResultParcel(code, reason, 0, null);
    }

    private IdentityResultParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        hasIdentity = in.readInt();
        McIdentityParcel parsed;
        if (hasIdentity != 0) {
            parsed = new McIdentityParcel(in); // 手写嵌套读（与写侧镜像）
        } else {
            parsed = null;
        }
        identity = parsed;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(reason);
        out.writeInt(hasIdentity);
        if (hasIdentity != 0) {
            identity.writeToParcel(out, flags); // 手写嵌套写（不经 writeParcelable）
        }
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
        if (!(o instanceof IdentityResultParcel)) {
            return false;
        }
        IdentityResultParcel that = (IdentityResultParcel) o;
        return code == that.code && hasIdentity == that.hasIdentity
                && Objects.equals(reason, that.reason)
                && Objects.equals(identity, that.identity);
    }

    @Override
    public int hashCode() {
        int h = code;
        h = 31 * h + Objects.hashCode(reason);
        h = 31 * h + hasIdentity;
        h = 31 * h + Objects.hashCode(identity);
        return h;
    }

    @Override
    public String toString() {
        return "IdentityResultParcel{code=" + code + ", hasIdentity=" + hasIdentity
                + ", identity=" + identity + ", reason=" + reason + "}";
    }

    public static final Creator<IdentityResultParcel> CREATOR = new Creator<IdentityResultParcel>() {
        @Override
        public IdentityResultParcel createFromParcel(Parcel in) {
            return new IdentityResultParcel(in);
        }

        @Override
        public IdentityResultParcel[] newArray(int size) {
            return new IdentityResultParcel[size];
        }
    };
}
