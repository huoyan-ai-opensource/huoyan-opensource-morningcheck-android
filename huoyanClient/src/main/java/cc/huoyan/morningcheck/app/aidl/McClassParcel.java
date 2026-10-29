package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 类别表的 comm 线形态（{@code getClassTable()} 的条目）。
 *
 * <p>类别语义的单一真源是 bridge 包的 {@code McClass}（对应上游
 * docs/model.md §2 类别表）；本类只做线形态投影，字段序冻结：
 * {@code modelType(String)} → {@code classId(I)} → {@code name(String)}
 * → {@code nameEn(String)}。</p>
 */
public final class McClassParcel implements Parcelable {

    /**
     * 模型域：{@code "hand"} 或 {@code "head"}。
     */
    public final String modelType;
    /**
     * 类别 id（模型输出原值）。
     */
    public final int classId;
    /**
     * 中文名。
     */
    public final String name;
    /**
     * 英文名。
     */
    public final String nameEn;

    public McClassParcel(String modelType, int classId, String name, String nameEn) {
        this.modelType = modelType;
        this.classId = classId;
        this.name = name;
        this.nameEn = nameEn;
    }

    private McClassParcel(Parcel in) {
        this.modelType = in.readString();
        this.classId = in.readInt();
        this.name = in.readString();
        this.nameEn = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(modelType);
        dest.writeInt(classId);
        dest.writeString(name);
        dest.writeString(nameEn);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<McClassParcel> CREATOR = new Creator<McClassParcel>() {
        @Override
        public McClassParcel createFromParcel(Parcel in) {
            return new McClassParcel(in);
        }

        @Override
        public McClassParcel[] newArray(int size) {
            return new McClassParcel[size];
        }
    };

    @Override
    public String toString() {
        return "McClassParcel{modelType=" + modelType + ", classId=" + classId
                + ", name=" + name + ", nameEn=" + nameEn + "}";
    }
}
