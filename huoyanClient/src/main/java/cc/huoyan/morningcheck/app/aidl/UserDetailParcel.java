package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 人员录入图片明细：每个条目同时携带 UID、可用性、图片地址和 MIME。
 * wire 保留旧 code/reason/count/uids 前缀和 v1 地址尾块，兼容已有安装包；
 * 平行数组仅用于内部编解码，不对客户端公开。历史缺图保留 UID。
 */
public final class UserDetailParcel implements Parcelable {
    private static final int MAX_COUNT = 100_000;
    private static final int MAX_URL_BYTES = 4096;
    private static final int MAX_BLOCK_BYTES = 1_048_576;

    /**
     * 0 = 成功；非 0 = 错误码（mc 段 -1..-7 / 壳族 -1001..）。
     */
    public final int code;
    /**
     * 错误说明（code=0 时为 null）。
     */
    public final String reason;
    private final List<UserImageParcel> items;

    public UserDetailParcel(int code, String reason, List<UserImageParcel> items) {
        this.code = code;
        this.reason = reason;
        this.items = copyItems(items);
    }

    public UserDetailParcel(int code, String reason, int uidCount, long[] uids) {
        this(code, reason, uidCount, uids, null);
    }

    public UserDetailParcel(int code, String reason, int uidCount, long[] uids, String[] imageUrls) {
        this(code, reason, fromLegacy(uidCount, uids, imageUrls));
    }

    public int getCode() {
        return code;
    }

    public String getReason() {
        return reason;
    }

    public int getUidCount() {
        return items.size();
    }

    public List<UserImageParcel> getItems() {
        return items;
    }

    public static UserDetailParcel ok(List<UserImageParcel> items) {
        return new UserDetailParcel(0, null, items);
    }

    /**
     * 成功回执（uids 非null；count 以数组长度为准构造）。
     */
    public static UserDetailParcel ok(long[] uids) {
        return ok(uids, null);
    }

    public static UserDetailParcel ok(long[] uids, String[] imageUrls) {
        Objects.requireNonNull(uids, "UID 数组不能为空");
        return new UserDetailParcel(0, null, uids.length, uids, imageUrls);
    }

    /**
     * 失败回执保留错误码与原因，图片列表为空。
     */
    public static UserDetailParcel err(int code, String reason) {
        return new UserDetailParcel(code, reason, Collections.emptyList());
    }

    private UserDetailParcel(Parcel in) {
        code = in.readInt();
        reason = in.readString();
        int uidCount = in.readInt();
        if (uidCount < 0 || uidCount > MAX_COUNT) {
            throw new IllegalStateException("人员图片数量无效：" + uidCount);
        }
        long[] uids = in.createLongArray();
        if (uids == null || uids.length != uidCount) {
            throw new IllegalStateException("人员图片 UID 数量与声明条数不符：" + uidCount);
        }
        String[] urls = ImageUrlParcelExtension.read(in, uidCount);
        items = copyItems(fromLegacy(uidCount, uids, urls));
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        long[] uids = new long[items.size()];
        String[] urls = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            uids[i] = items.get(i).getUid();
            urls[i] = items.get(i).getImageUrl();
        }
        out.writeInt(code);
        out.writeString(reason);
        out.writeInt(items.size());
        out.writeLongArray(uids);
        ImageUrlParcelExtension.write(out, urls);
    }

    private static List<UserImageParcel> fromLegacy(int count, long[] uids, String[] urls) {
        if (count < 0 || count > MAX_COUNT || uids == null || uids.length != count) {
            throw new IllegalArgumentException("人员图片 UID 数量与声明条数不符");
        }
        String[] safeUrls = ImageUrlParcelExtension.normalize(urls, count);
        List<UserImageParcel> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new UserImageParcel(uids[i], safeUrls[i]));
        return result;
    }

    private static List<UserImageParcel> copyItems(List<UserImageParcel> values) {
        if (values == null) return Collections.emptyList();
        if (values.size() > MAX_COUNT) throw new IllegalArgumentException("人员图片数量超过上限");
        List<UserImageParcel> copy = new ArrayList<>(values.size());
        long blockBytes = 8;
        for (UserImageParcel item : values) {
            if (item == null) throw new IllegalArgumentException("人员图片条目不能为空");
            int urlBytes = item.getImageUrl().getBytes(StandardCharsets.UTF_8).length;
            if (urlBytes > MAX_URL_BYTES) throw new IllegalArgumentException("人员图片地址过长");
            blockBytes += 4L + urlBytes;
            if (blockBytes > MAX_BLOCK_BYTES)
                throw new IllegalArgumentException("人员图片地址尾块过大");
            copy.add(item);
        }
        return Collections.unmodifiableList(copy);
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
        if (!(o instanceof UserDetailParcel)) {
            return false;
        }
        UserDetailParcel that = (UserDetailParcel) o;
        return code == that.code && Objects.equals(reason, that.reason) && items.equals(that.items);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, reason, items);
    }

    @Override
    public String toString() {
        return "UserDetailParcel{code=" + code + ", uidCount=" + items.size()
                + ", reason=" + reason + "}";
    }

    public static final Creator<UserDetailParcel> CREATOR = new Creator<UserDetailParcel>() {
        @Override
        public UserDetailParcel createFromParcel(Parcel in) {
            return new UserDetailParcel(in);
        }

        @Override
        public UserDetailParcel[] newArray(int size) {
            return new UserDetailParcel[size];
        }
    };
}
