package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * AIDL 批量反馈载体，字段与 POST /api/v2/feedback/ 对齐。
 * Issue #113 两段式：删 shopId（三端归属取验签设备）；photo_file_url 必填
 * http(s)（图片经 uploadPhoto 上传后返回 object URL 再提交）。
 */
public final class FeedbackParcel implements Parcelable {

    public static final int FEEDBACK_TYPE_DEFAULT = 0;
    public static final int FEEDBACK_TYPE_DETECT = 1;
    public static final int FEEDBACK_TYPE_STUDY = 2;

    private final String requestId;
    private final String photoFileUrl;
    private final int feedbackType;
    private final List<FeedbackGoodsParcel> goods;

    public FeedbackParcel(String requestId, String photoFileUrl,
                          int feedbackType, List<FeedbackGoodsParcel> goods) {
        this.requestId = requestId;
        this.photoFileUrl = photoFileUrl;
        this.feedbackType = feedbackType;
        this.goods = goods == null || goods.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(goods));
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPhotoFileUrl() {
        return photoFileUrl;
    }

    public int getFeedbackType() {
        return feedbackType;
    }

    public List<FeedbackGoodsParcel> getGoods() {
        return goods;
    }

    private FeedbackParcel(Parcel parcel) {
        requestId = parcel.readString();
        photoFileUrl = parcel.readString();
        feedbackType = parcel.readInt();
        List<FeedbackGoodsParcel> read = parcel.createTypedArrayList(FeedbackGoodsParcel.CREATOR);
        goods = read == null ? Collections.emptyList() : read;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(requestId);
        parcel.writeString(photoFileUrl);
        parcel.writeInt(feedbackType);
        parcel.writeTypedList(goods);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FeedbackParcel)) return false;
        FeedbackParcel that = (FeedbackParcel) other;
        return feedbackType == that.feedbackType
                && Objects.equals(requestId, that.requestId)
                && Objects.equals(photoFileUrl, that.photoFileUrl)
                && goods.equals(that.goods);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, photoFileUrl, feedbackType, goods);
    }

    public static final Creator<FeedbackParcel> CREATOR = new Creator<FeedbackParcel>() {
        @Override
        public FeedbackParcel createFromParcel(Parcel parcel) {
            return new FeedbackParcel(parcel);
        }

        @Override
        public FeedbackParcel[] newArray(int size) {
            return new FeedbackParcel[size];
        }
    };
}
