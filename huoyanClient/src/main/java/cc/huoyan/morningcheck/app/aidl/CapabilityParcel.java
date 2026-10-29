package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * 能力快照回执：{@code getCapabilities()} 的载荷——接口版本与服务端各能力面
 * 的支持标志，业务 App 据此决定调用路径（如低版本设备走 bytes 降级而非
 * SharedMemory）。code=0 时字段有效；非 0 时 reason 携带说明。
 *
 * <p>{@link #API_VERSION} 为本 AIDL 契约的版本权威源（晨检面定稿 3：识物
 * detect/recognize/item 族移除，改晨检 identify/checkHead/checkHand/register
 * 族——破坏性变更，旧客户端须同步升级），{@code getApiVersion()} 与本快照的
 * apiVersion 字段同源。既有能力位为晨检面：check（头部/手部检查）、identify
 * （人脸身份比对）、register（人员登记）、forget（人员遗忘）、userQuery
 * （底库查询）。</p>
 *
 * <p>Issue #16 尾部追加 {@code sdkVersionName}/{@code sdkVersionCode}——壳
 * APK 应用版本（{@code AndroidSdkShellInfoSource} 同源，零 native/零激活依赖，
 * 未激活即可读；getCertInfo 的版本附带须证书查询成功，未激活不可达）。
 * 尾追加 = 向后兼容：旧客户端按序读旧字段不受影响；版本不可得（包不可查/
 * 旧写入端缺尾字段）兜底 {@link #UNKNOWN_SDK_VERSION_NAME}/
 * {@link #UNKNOWN_SDK_VERSION_CODE}，字段恒可序列化不抛；契约版本不 bump
 * （字段尾追加）。新增 detectFace 的能力位继续追加在版本字段之后；旧服务
 * 未提供此位时为 false，客户端先探测再调用末尾新增的 AIDL 方法。</p>
 */
public final class CapabilityParcel implements Parcelable {

    /**
     * 接口契约版本 4：新增无参 queryDeviceModels，保留按激活码 queryLicenseModels，客户端须同步新 AIDL。
     */
    public static final int API_VERSION = 4;

    /**
     * 壳版本不可得（包不可查/旧写入端缺尾字段）的一致兜底名（Issue #16）。
     */
    public static final String UNKNOWN_SDK_VERSION_NAME = "unknown";

    /**
     * 壳版本不可得的一致兜底码（Issue #16）。
     */
    public static final int UNKNOWN_SDK_VERSION_CODE = -1;

    private final int code;
    private final String reason;
    private final int apiVersion;
    private final boolean sharedMemoryImage;
    private final boolean check;
    private final boolean identify;
    private final boolean register;
    private final boolean forget;
    private final boolean userQuery;
    private final String sdkVersionName;
    private final int sdkVersionCode;
    private final boolean detectFace;

    public CapabilityParcel(int code, String reason, int apiVersion, boolean sharedMemoryImage,
                            boolean check, boolean identify, boolean register, boolean forget, boolean userQuery) {
        this(code, reason, apiVersion, sharedMemoryImage, check, identify, register, forget,
                userQuery, UNKNOWN_SDK_VERSION_NAME, UNKNOWN_SDK_VERSION_CODE);
    }

    /**
     * Issue #16 全量构造：尾两字段为壳 APK 版本；null/0（包不可查）兜底 unknown/-1。
     */
    public CapabilityParcel(int code, String reason, int apiVersion, boolean sharedMemoryImage,
                            boolean check, boolean identify, boolean register, boolean forget, boolean userQuery,
                            String sdkVersionName, int sdkVersionCode) {
        this(code, reason, apiVersion, sharedMemoryImage, check, identify, register, forget,
                userQuery, sdkVersionName, sdkVersionCode, false);
    }

    /**
     * 新能力只在版本字段之后追加，不改变旧构造器与既有 Parcel 线序。
     */
    public CapabilityParcel(int code, String reason, int apiVersion, boolean sharedMemoryImage,
                            boolean check, boolean identify, boolean register, boolean forget, boolean userQuery,
                            String sdkVersionName, int sdkVersionCode, boolean detectFace) {
        this.code = code;
        this.reason = reason;
        this.apiVersion = apiVersion;
        this.sharedMemoryImage = sharedMemoryImage;
        this.check = check;
        this.identify = identify;
        this.register = register;
        this.forget = forget;
        this.userQuery = userQuery;
        this.sdkVersionName = sdkVersionName != null ? sdkVersionName : UNKNOWN_SDK_VERSION_NAME;
        this.sdkVersionCode = sdkVersionCode != 0 ? sdkVersionCode : UNKNOWN_SDK_VERSION_CODE;
        this.detectFace = detectFace;
    }

    public int getCode() {
        return code;
    }

    /**
     * 错误说明；code=0 时为 null。
     */
    public String getReason() {
        return reason;
    }

    /**
     * 接口契约版本（与 {@code getApiVersion()} 同源）。
     */
    public int getApiVersion() {
        return apiVersion;
    }

    /**
     * SharedMemory 传图（API 27+）主路径是否可用。
     */
    public boolean isSharedMemoryImage() {
        return sharedMemoryImage;
    }

    /**
     * 头部/手部检查（checkHead/checkHand）是否可用。
     */
    public boolean isCheck() {
        return check;
    }

    /**
     * 人脸身份比对（identify）是否可用。
     */
    public boolean isIdentify() {
        return identify;
    }

    /**
     * 人员登记（register）是否可用。
     */
    public boolean isRegister() {
        return register;
    }

    /**
     * 人员遗忘（forget/forgetByUid）是否可用。
     */
    public boolean isForget() {
        return forget;
    }

    /**
     * 底库查询（userCount/userDetail）是否可用。
     */
    public boolean isUserQuery() {
        return userQuery;
    }

    /**
     * 独立的人脸检测接口是否已接线；旧服务快照默认为 false。
     */
    public boolean isDetectFace() {
        return detectFace;
    }

    /**
     * 壳 APK versionName（Issue #16，未激活可读）；不可得 = {@link #UNKNOWN_SDK_VERSION_NAME}。
     */
    public String getSdkVersionName() {
        return sdkVersionName;
    }

    /**
     * 壳 APK versionCode（Issue #16，未激活可读）；不可得 = {@link #UNKNOWN_SDK_VERSION_CODE}。
     */
    public int getSdkVersionCode() {
        return sdkVersionCode;
    }

    private CapabilityParcel(Parcel parcel) {
        code = parcel.readInt();
        reason = parcel.readString();
        apiVersion = parcel.readInt();
        sharedMemoryImage = parcel.readInt() != 0;
        check = parcel.readInt() != 0;
        identify = parcel.readInt() != 0;
        register = parcel.readInt() != 0;
        forget = parcel.readInt() != 0;
        userQuery = parcel.readInt() != 0;
        // 旧写入端没有尾字段时不越界读取；版本回 unknown/-1，新能力回 false。
        String tailName = parcel.dataAvail() > 0 ? parcel.readString() : null;
        sdkVersionName = tailName != null ? tailName : UNKNOWN_SDK_VERSION_NAME;
        int tailCode = parcel.dataAvail() >= 4 ? parcel.readInt() : 0;
        sdkVersionCode = tailCode != 0 ? tailCode : UNKNOWN_SDK_VERSION_CODE;
        detectFace = parcel.dataAvail() >= 4 && parcel.readInt() != 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(code);
        parcel.writeString(reason);
        parcel.writeInt(apiVersion);
        parcel.writeInt(sharedMemoryImage ? 1 : 0);
        parcel.writeInt(check ? 1 : 0);
        parcel.writeInt(identify ? 1 : 0);
        parcel.writeInt(register ? 1 : 0);
        parcel.writeInt(forget ? 1 : 0);
        parcel.writeInt(userQuery ? 1 : 0);
        parcel.writeString(sdkVersionName);
        parcel.writeInt(sdkVersionCode);
        parcel.writeInt(detectFace ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapabilityParcel)) {
            return false;
        }
        CapabilityParcel that = (CapabilityParcel) other;
        return code == that.code
                && apiVersion == that.apiVersion
                && sharedMemoryImage == that.sharedMemoryImage
                && check == that.check
                && identify == that.identify
                && register == that.register
                && forget == that.forget
                && userQuery == that.userQuery
                && detectFace == that.detectFace
                && sdkVersionCode == that.sdkVersionCode
                && Objects.equals(reason, that.reason)
                && Objects.equals(sdkVersionName, that.sdkVersionName);
    }

    @Override
    public int hashCode() {
        int result = code;
        result = 31 * result + Objects.hashCode(reason);
        result = 31 * result + apiVersion;
        result = 31 * result + (sharedMemoryImage ? 1231 : 1237);
        result = 31 * result + (check ? 1231 : 1237);
        result = 31 * result + (identify ? 1231 : 1237);
        result = 31 * result + (register ? 1231 : 1237);
        result = 31 * result + (forget ? 1231 : 1237);
        result = 31 * result + (userQuery ? 1231 : 1237);
        result = 31 * result + Objects.hashCode(sdkVersionName);
        result = 31 * result + sdkVersionCode;
        return 31 * result + (detectFace ? 1231 : 1237);
    }

    @Override
    public String toString() {
        return "CapabilityParcel(code=" + code + ", reason=" + reason + ", apiVersion=" + apiVersion
                + ", sharedMemoryImage=" + sharedMemoryImage + ", check=" + check
                + ", identify=" + identify + ", register=" + register
                + ", forget=" + forget + ", userQuery=" + userQuery
                + ", sdkVersionName=" + sdkVersionName + ", sdkVersionCode=" + sdkVersionCode
                + ", detectFace=" + detectFace + ")";
    }

    public static final Creator<CapabilityParcel> CREATOR = new Creator<CapabilityParcel>() {
        @Override
        public CapabilityParcel createFromParcel(Parcel parcel) {
            return new CapabilityParcel(parcel);
        }

        @Override
        public CapabilityParcel[] newArray(int size) {
            return new CapabilityParcel[size];
        }
    };
}
