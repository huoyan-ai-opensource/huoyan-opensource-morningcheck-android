package cc.huoyan.morningcheck.app.aidl;

import android.os.Parcel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 图片地址放在带标识、独立长度的尾块中；旧回执之后的其他字段不得被当成扩展吞掉。
 */
final class ImageUrlParcelExtension {
    static final int MAGIC = 0x4D434955;
    private static final int VERSION = 1;
    private static final int MAX_COUNT = 100_000;
    private static final int MAX_URL_BYTES = 4096;
    private static final int MAX_BLOCK_BYTES = 1_048_576;

    private ImageUrlParcelExtension() {
    }

    static String[] empty(int count) {
        String[] values = new String[count];
        Arrays.fill(values, "");
        return values;
    }

    static String[] normalize(String[] values, int count) {
        if (values == null) return empty(count);
        if (values.length != count) throw new IllegalArgumentException("图片地址与 UID 数量不一致");
        String[] copy = values.clone();
        for (int i = 0; i < count; i++) if (copy[i] == null) copy[i] = "";
        return copy;
    }

    static void write(Parcel out, String[] urls) {
        if (urls.length > MAX_COUNT) throw new IllegalArgumentException("图片地址数量超过上限");
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(buffer);
            data.writeInt(VERSION);
            data.writeInt(urls.length);
            for (String url : urls) {
                byte[] bytes = (url == null ? "" : url).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_URL_BYTES)
                    throw new IllegalArgumentException("图片地址过长");
                data.writeInt(bytes.length);
                data.write(bytes);
                if (buffer.size() > MAX_BLOCK_BYTES)
                    throw new IllegalArgumentException("图片地址尾块过大");
            }
            out.writeInt(MAGIC);
            out.writeByteArray(buffer.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("图片地址尾块编码失败", impossible);
        }
    }

    static String[] read(Parcel in, int count) {
        if (count < 0 || count > MAX_COUNT) throw new IllegalStateException("图片地址数量无效");
        int position = in.dataPosition();
        if (position >= in.dataSize()) return empty(count);
        // 仅探测标识，未命中时恢复游标；dataSize 非空并不表示还有本 Parcelable 的字段。
        try {
            if (in.readInt() != MAGIC) {
                in.setDataPosition(position);
                return empty(count);
            }
        } catch (RuntimeException missing) {
            in.setDataPosition(position);
            return empty(count);
        }
        byte[] block = in.createByteArray();
        if (block == null || block.length > MAX_BLOCK_BYTES)
            throw new IllegalStateException("图片地址尾块长度无效");
        try {
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(block));
            if (data.readInt() != VERSION || data.readInt() != count)
                throw new IOException("尾块版本或条数不符");
            String[] urls = new String[count];
            for (int i = 0; i < count; i++) {
                int length = data.readInt();
                if (length < 0 || length > MAX_URL_BYTES || length > data.available())
                    throw new IOException("图片地址长度无效");
                byte[] bytes = new byte[length];
                data.readFully(bytes);
                urls[i] = new String(bytes, StandardCharsets.UTF_8);
            }
            if (data.available() != 0) throw new IOException("尾块含未声明数据");
            return urls;
        } catch (IOException invalid) {
            throw new IllegalStateException("图片地址尾块损坏", invalid);
        }
    }
}
