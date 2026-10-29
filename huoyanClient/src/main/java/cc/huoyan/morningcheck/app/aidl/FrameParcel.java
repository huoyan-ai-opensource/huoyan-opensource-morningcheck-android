package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * 帧载体（Issue #10 §3 裁决二，三选一冻结）：identify/checkHead/checkHand/
 * register 的入参——RGB888 HWC 紧凑帧（{@code data.length == w*h*3}）经三种
 * 载体之一进入服务：
 *
 * <ul>
 *   <li><b>{@link #CARRIER_SHM}（主路径，API 21）</b>：{@code ParcelFileDescriptor}
 *       （共享内存/文件 fd；{@code Parcel.writeFileDescriptor} 是 API 1，minSdk 21
 *       可用——不引 {@code SharedMemory}，其仅 API 27+ 可选加速，#12 真机验证）；</li>
 *   <li><b>{@link #CARRIER_BYTES}</b>：内联字节（≤1 MiB，{@code 640×480×3=0.9 MiB}
 *       可承载；超限壳层拒绝，见 {@code FramePayloadPolicy}）；</li>
 *   <li><b>{@link #CARRIER_FILE}</b>：绝对路径（发送方<b>单侧全权</b>：创建/写/
 *       负责删除——不依赖 Java finally（SIGKILL 不跑）；接收方只读不删，
 *       超时/拒绝路径同样不删；服务端自有落点与孤儿清扫见 {@code FrameFileStore}）。</li>
 * </ul>
 *
 * <p><b>硬上限 32 MiB</b>（任一载体的声明帧尺寸 {@code w*h*3} / fd 尺寸 / 文件
 * 尺寸，唯一收敛点 {@code FramePayloadPolicy#HARD_LIMIT_BYTES}）。</p>
 *
 * <p><b>fd 所有权（Issue #10 §3）</b>：发送端持有并 close 自己的原句柄；接收端
 * Stub 在<b>请求返回时统一 close</b> 反序列化所得（含校验失败/权限拒绝/异常路径）
 * ——同一 fd 载体请求只处理一次，关闭后不得复用（复用会在读取期自然失败）。
 * 因此本类 {@link #describeContents()} 在 fd 载体下返回
 * {@link Parcelable#CONTENTS_FILE_DESCRIPTOR}。</p>
 *
 * <p><b>线序（手写对称冻结）</b>：{@code carrier(I)} → 载体成员（按 carrier 恰写
 * 其一：fd / bytes / filePath）→ {@code width(I)} → {@code height(I)}。</p>
 */
public final class FrameParcel implements Parcelable {

    /**
     * 载体标记：非法/未设置（拒绝）。
     */
    public static final int CARRIER_NONE = 0;
    /**
     * 载体标记：shmKey（ParcelFileDescriptor，API 21 主路径）。
     */
    public static final int CARRIER_SHM = 1;
    /**
     * 载体标记：bytes（≤1 MiB 内联）。
     */
    public static final int CARRIER_BYTES = 2;
    /**
     * 载体标记：filePath（发送方单侧全权的绝对路径文件）。
     */
    public static final int CARRIER_FILE = 3;

    /**
     * 载体标记（恰其一；见类注释 CARRIER_*）。
     */
    public final int carrier;
    /**
     * fd 载体成员（carrier=CARRIER_SHM 时非 null；接收端请求返回时统一 close）。
     */
    public final ParcelFileDescriptor shmKey;
    /**
     * bytes 载体成员（carrier=CARRIER_BYTES 时非 null 且 ≤1 MiB）。
     */
    public final byte[] bytes;
    /**
     * filePath 载体成员（carrier=CARRIER_FILE 时非 null 绝对路径）。
     */
    public final String filePath;
    /**
     * 帧宽（&gt;0；{@code bytes.length == width*height*3}）。
     */
    public final int width;
    /**
     * 帧高（&gt;0）。
     */
    public final int height;

    private FrameParcel(int carrier, ParcelFileDescriptor shmKey, byte[] bytes,
                        String filePath, int width, int height) {
        this.carrier = carrier;
        this.shmKey = shmKey;
        this.bytes = bytes;
        this.filePath = filePath;
        this.width = width;
        this.height = height;
    }

    /**
     * fd 载体构造（发送端持原句柄；本 Parcel 跨进程后接收端得到独立 fd）。
     */
    public static FrameParcel ofShmKey(ParcelFileDescriptor shmKey, int width, int height) {
        return new FrameParcel(CARRIER_SHM, shmKey, null, null, width, height);
    }

    /**
     * bytes 载体构造（≤1 MiB 由 {@code FramePayloadPolicy} 校验侧强制）。
     */
    public static FrameParcel ofBytes(byte[] bytes, int width, int height) {
        return new FrameParcel(CARRIER_BYTES, null, bytes, null, width, height);
    }

    /**
     * filePath 载体构造（绝对路径；发送方单侧全权）。
     */
    public static FrameParcel ofFilePath(String filePath, int width, int height) {
        return new FrameParcel(CARRIER_FILE, null, null, filePath, width, height);
    }

    private FrameParcel(Parcel in) {
        carrier = in.readInt();
        ParcelFileDescriptor shm = null;
        byte[] inline = null;
        String path = null;
        switch (carrier) {
            case CARRIER_SHM:
                shm = in.readFileDescriptor();
                break;
            case CARRIER_BYTES:
                inline = in.createByteArray();
                break;
            case CARRIER_FILE:
                path = in.readString();
                break;
            default:
                // 读出后回溯断言（Issue #10 §2）：载体标记必须已知，否则静默错配
                throw new IllegalStateException(
                        "FrameParcel 载体标记非法（线序错位或契约漂移）：" + carrier);
        }
        shmKey = shm;
        bytes = inline;
        filePath = path;
        width = in.readInt();
        height = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(carrier);
        switch (carrier) {
            case CARRIER_SHM:
                out.writeFileDescriptor(shmKey.getFileDescriptor());
                break;
            case CARRIER_BYTES:
                out.writeByteArray(bytes);
                break;
            case CARRIER_FILE:
                out.writeString(filePath);
                break;
            default:
                throw new IllegalStateException("FrameParcel 载体标记非法：" + carrier);
        }
        out.writeInt(width);
        out.writeInt(height);
    }

    @Override
    public int describeContents() {
        return carrier == CARRIER_SHM ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    /**
     * 载体成员是否恰设置其一（构造侧与校验侧共用的结构判据）。
     */
    public boolean isCarrierConsistent() {
        switch (carrier) {
            case CARRIER_SHM:
                return shmKey != null && bytes == null && filePath == null;
            case CARRIER_BYTES:
                return bytes != null && shmKey == null && filePath == null;
            case CARRIER_FILE:
                return filePath != null && shmKey == null && bytes == null;
            default:
                return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FrameParcel)) {
            return false;
        }
        FrameParcel that = (FrameParcel) o;
        if (carrier != that.carrier || width != that.width || height != that.height) {
            return false;
        }
        switch (carrier) {
            case CARRIER_BYTES:
                return Arrays.equals(bytes, that.bytes);
            case CARRIER_FILE:
                return Objects.equals(filePath, that.filePath);
            case CARRIER_SHM:
                return shmKey == that.shmKey; // fd 无值语义，只比引用（同实例往返）
            default:
                return false;
        }
    }

    @Override
    public int hashCode() {
        int h = carrier;
        h = 31 * h + width;
        h = 31 * h + height;
        h = 31 * h + Objects.hashCode(filePath);
        return h;
    }

    @Override
    public String toString() {
        return "FrameParcel{carrier=" + carrier + ", width=" + width + ", height=" + height
                + (filePath != null ? ", filePath=" + filePath : "")
                + (bytes != null ? ", bytes=" + bytes.length + "B" : "")
                + (shmKey != null ? ", shmKey=fd" : "") + "}";
    }

    public static final Creator<FrameParcel> CREATOR = new Creator<FrameParcel>() {
        @Override
        public FrameParcel createFromParcel(Parcel in) {
            return new FrameParcel(in);
        }

        @Override
        public FrameParcel[] newArray(int size) {
            return new FrameParcel[size];
        }
    };
}
