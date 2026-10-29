package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 授权模型查询回执；code 为 0 时 models 有效。 */
public final class LicenseModelsParcel implements Parcelable {
    private final int code;
    private final String reason;
    private final List<LicenseModelParcel> models;

    public LicenseModelsParcel(int code, String reason, List<LicenseModelParcel> models) {
        this.code = code;
        this.reason = reason;
        this.models = Collections.unmodifiableList(new ArrayList<>(models));
    }

    public int getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }

    public List<LicenseModelParcel> getModels() {
        return models;
    }

    private LicenseModelsParcel(Parcel in) {
        this(in.readInt(), in.readString(), in.createTypedArrayList(LicenseModelParcel.CREATOR));
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(code);
        out.writeString(reason);
        out.writeTypedList(models);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LicenseModelsParcel> CREATOR = new Creator<LicenseModelsParcel>() {
        @Override
        public LicenseModelsParcel createFromParcel(Parcel in) {
            return new LicenseModelsParcel(in);
        }

        @Override
        public LicenseModelsParcel[] newArray(int size) {
            return new LicenseModelsParcel[size];
        }
    };
}
