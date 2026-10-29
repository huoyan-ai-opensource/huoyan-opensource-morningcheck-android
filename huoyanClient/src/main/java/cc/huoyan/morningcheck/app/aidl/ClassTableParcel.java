package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 类别表回执（{@code getClassTable()}）：平行数组 + 条数。
 *
 * <p>语义真源 = bridge 包 {@code McClass}（对应上游 docs/model.md §2 类别表）。
 * 字段序冻结：{@code code(I)} → {@code count(I)} → 4 个等长数组
 * （{@code modelType/name/nameEn: String[]} · {@code classId: int[]}）。</p>
 */
public final class ClassTableParcel implements Parcelable {

    /**
     * 0 成功。
     */
    public final int code;
    /**
     * 条目数。
     */
    public final int count;
    public final String[] modelType;
    public final int[] classId;
    public final String[] name;
    public final String[] nameEn;

    public ClassTableParcel(int code, String[] modelType, int[] classId,
                            String[] name, String[] nameEn) {
        this.code = code;
        this.modelType = modelType;
        this.classId = classId;
        this.name = name;
        this.nameEn = nameEn;
        this.count = classId == null ? 0 : classId.length;
    }

    private ClassTableParcel(Parcel in) {
        this.code = in.readInt();
        this.count = in.readInt();
        this.modelType = in.createStringArray();
        this.classId = in.createIntArray();
        this.name = in.createStringArray();
        this.nameEn = in.createStringArray();
        if (this.classId == null || this.classId.length != count) {
            throw new IllegalStateException("ClassTableParcel 回溯断言失败: count=" + count);
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(code);
        dest.writeInt(count);
        dest.writeStringArray(modelType);
        dest.writeIntArray(classId);
        dest.writeStringArray(name);
        dest.writeStringArray(nameEn);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClassTableParcel> CREATOR = new Creator<ClassTableParcel>() {
        @Override
        public ClassTableParcel createFromParcel(Parcel in) {
            return new ClassTableParcel(in);
        }

        @Override
        public ClassTableParcel[] newArray(int size) {
            return new ClassTableParcel[size];
        }
    };
}
