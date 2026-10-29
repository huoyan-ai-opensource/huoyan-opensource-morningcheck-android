package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * AIDL 反馈 goods[] 单项载体。
 * Issue #113：新增几何字段 width/height（原图坐标系中的检测框宽高）与 x/y（检测框
 * 左上角坐标），业务端透传、选填（未传 has 标志 false）。
 */
public final class FeedbackGoodsParcel implements Parcelable {

    public static final int UN_SELECTED = 0;
    public static final int SELECTED = 1;

    private final String goodsId;
    private final String goodsName;
    private final String goodsPlu;
    private final int selected;
    private final boolean hasSelected;
    private final double distance;
    private final boolean hasDistance;
    private final String source;
    private final double width;
    private final boolean hasWidth;
    private final double height;
    private final boolean hasHeight;
    private final double x;
    private final boolean hasX;
    private final double y;
    private final boolean hasY;

    public FeedbackGoodsParcel(String goodsId, String goodsName, String goodsPlu,
                               Integer selected, Double distance, String source) {
        this(goodsId, goodsName, goodsPlu, selected, distance, source,
                null, null, null, null);
    }

    public FeedbackGoodsParcel(String goodsId, String goodsName, String goodsPlu,
                               Integer selected, Double distance, String source,
                               Double width, Double height, Double x, Double y) {
        this.goodsId = goodsId;
        this.goodsName = goodsName;
        this.goodsPlu = goodsPlu;
        this.selected = selected == null ? 0 : selected.intValue();
        this.hasSelected = selected != null;
        this.distance = distance == null ? 0D : distance.doubleValue();
        this.hasDistance = distance != null;
        this.source = source;
        this.width = width == null ? 0D : width.doubleValue();
        this.hasWidth = width != null;
        this.height = height == null ? 0D : height.doubleValue();
        this.hasHeight = height != null;
        this.x = x == null ? 0D : x.doubleValue();
        this.hasX = x != null;
        this.y = y == null ? 0D : y.doubleValue();
        this.hasY = y != null;
    }

    public String getGoodsId() {
        return goodsId;
    }

    public String getGoodsName() {
        return goodsName;
    }

    public String getGoodsPlu() {
        return goodsPlu;
    }

    public int getSelected() {
        return selected;
    }

    public boolean hasSelected() {
        return hasSelected;
    }

    public double getDistance() {
        return distance;
    }

    public boolean hasDistance() {
        return hasDistance;
    }

    public String getSource() {
        return source;
    }

    public double getWidth() {
        return width;
    }

    public boolean hasWidth() {
        return hasWidth;
    }

    public double getHeight() {
        return height;
    }

    public boolean hasHeight() {
        return hasHeight;
    }

    public double getX() {
        return x;
    }

    public boolean hasX() {
        return hasX;
    }

    public double getY() {
        return y;
    }

    public boolean hasY() {
        return hasY;
    }

    private FeedbackGoodsParcel(Parcel parcel) {
        goodsId = parcel.readString();
        goodsName = parcel.readString();
        goodsPlu = parcel.readString();
        selected = parcel.readInt();
        hasSelected = parcel.readByte() != 0;
        distance = parcel.readDouble();
        hasDistance = parcel.readByte() != 0;
        source = parcel.readString();
        width = parcel.readDouble();
        hasWidth = parcel.readByte() != 0;
        height = parcel.readDouble();
        hasHeight = parcel.readByte() != 0;
        x = parcel.readDouble();
        hasX = parcel.readByte() != 0;
        y = parcel.readDouble();
        hasY = parcel.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(goodsId);
        parcel.writeString(goodsName);
        parcel.writeString(goodsPlu);
        parcel.writeInt(selected);
        parcel.writeByte((byte) (hasSelected ? 1 : 0));
        parcel.writeDouble(distance);
        parcel.writeByte((byte) (hasDistance ? 1 : 0));
        parcel.writeString(source);
        parcel.writeDouble(width);
        parcel.writeByte((byte) (hasWidth ? 1 : 0));
        parcel.writeDouble(height);
        parcel.writeByte((byte) (hasHeight ? 1 : 0));
        parcel.writeDouble(x);
        parcel.writeByte((byte) (hasX ? 1 : 0));
        parcel.writeDouble(y);
        parcel.writeByte((byte) (hasY ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FeedbackGoodsParcel)) return false;
        FeedbackGoodsParcel that = (FeedbackGoodsParcel) other;
        return selected == that.selected
                && hasSelected == that.hasSelected
                && Double.doubleToLongBits(distance) == Double.doubleToLongBits(that.distance)
                && hasDistance == that.hasDistance
                && Double.doubleToLongBits(width) == Double.doubleToLongBits(that.width)
                && hasWidth == that.hasWidth
                && Double.doubleToLongBits(height) == Double.doubleToLongBits(that.height)
                && hasHeight == that.hasHeight
                && Double.doubleToLongBits(x) == Double.doubleToLongBits(that.x)
                && hasX == that.hasX
                && Double.doubleToLongBits(y) == Double.doubleToLongBits(that.y)
                && hasY == that.hasY
                && Objects.equals(goodsId, that.goodsId)
                && Objects.equals(goodsName, that.goodsName)
                && Objects.equals(goodsPlu, that.goodsPlu)
                && Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(goodsId, goodsName, goodsPlu, selected, hasSelected,
                distance, hasDistance, source, width, hasWidth, height, hasHeight,
                x, hasX, y, hasY);
    }

    public static final Creator<FeedbackGoodsParcel> CREATOR =
            new Creator<FeedbackGoodsParcel>() {
                @Override
                public FeedbackGoodsParcel createFromParcel(Parcel parcel) {
                    return new FeedbackGoodsParcel(parcel);
                }

                @Override
                public FeedbackGoodsParcel[] newArray(int size) {
                    return new FeedbackGoodsParcel[size];
                }
            };
}
