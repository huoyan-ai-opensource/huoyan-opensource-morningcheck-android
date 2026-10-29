package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/** 授权可选模型，字段由 Core 原样返回。 */
public final class LicenseModelParcel implements Parcelable {
    private final String modelCode;
    private final String modelName;
    private final String chip;
    private final boolean current;

    public LicenseModelParcel(String modelCode, String modelName, String chip, boolean current) {
        this.modelCode = modelCode;
        this.modelName = modelName;
        this.chip = chip;
        this.current = current;
    }

    public String getModelCode() {
        return modelCode;
    }

    public String getModelName() {
        return modelName;
    }

    public String getChip() {
        return chip;
    }

    public boolean isCurrent() {
        return current;
    }

    private LicenseModelParcel(Parcel in) {
        this(in.readString(), in.readString(), in.readString(), in.readInt() != 0);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(modelCode);
        out.writeString(modelName);
        out.writeString(chip);
        out.writeInt(current ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LicenseModelParcel> CREATOR = new Creator<LicenseModelParcel>() {
        @Override
        public LicenseModelParcel createFromParcel(Parcel in) {
            return new LicenseModelParcel(in);
        }

        @Override
        public LicenseModelParcel[] newArray(int size) {
            return new LicenseModelParcel[size];
        }
    };
}
