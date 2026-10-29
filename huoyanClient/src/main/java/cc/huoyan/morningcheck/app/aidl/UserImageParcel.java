package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * 人员的单张录入图片；缺图仍保留 UID，当前图片服务只发布 JPEG。
 */
public final class UserImageParcel implements Parcelable {
    private final long uid;
    private final String imageUrl;

    public UserImageParcel(long uid, String imageUrl) {
        this.uid = uid;
        this.imageUrl = imageUrl == null ? "" : imageUrl;
    }

    public long getUid() {
        return uid;
    }

    public boolean isImageAvailable() {
        return !imageUrl.isEmpty();
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getMimeType() {
        return isImageAvailable() ? "image/jpeg" : "";
    }

    private UserImageParcel(Parcel in) {
        this(in.readLong(), in.readString());
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(uid);
        out.writeString(imageUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserImageParcel)) return false;
        UserImageParcel that = (UserImageParcel) other;
        return uid == that.uid && imageUrl.equals(that.imageUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, imageUrl);
    }

    @Override
    public String toString() {
        return "UserImageParcel{uid=" + uid + ", imageAvailable=" + isImageAvailable()
                + ", imageUrl=" + imageUrl + ", mimeType=" + getMimeType() + "}";
    }

    public static final Creator<UserImageParcel> CREATOR = new Creator<UserImageParcel>() {
        @Override
        public UserImageParcel createFromParcel(Parcel in) {
            return new UserImageParcel(in);
        }

        @Override
        public UserImageParcel[] newArray(int size) {
            return new UserImageParcel[size];
        }
    };
}
