package cc.huoyan.morningcheck.sdk.client;

import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import cc.huoyan.morningcheck.app.aidl.FrameParcel;
import cc.huoyan.morningcheck.app.aidl.ImageRequestParcel;

/**
 * 单次 Binder 调用拥有的图像载体；仅由工作线程创建、使用和关闭。
 */
@RequiresApi(27)
final class AidlImage implements AutoCloseable {
    final FrameParcel frame;
    final ImageRequestParcel photo;
    private final ParcelFileDescriptor descriptor;
    private final SharedMemory memory;

    private AidlImage(FrameParcel frame, ImageRequestParcel photo,
                      ParcelFileDescriptor descriptor, SharedMemory memory) {
        this.frame = frame;
        this.photo = photo;
        this.descriptor = descriptor;
        this.memory = memory;
    }

    /**
     * 晨检帧 fd 在壳内通过 FileInputStream 读到 EOF，不能照识物壳直接传 ashmem。
     * 私有空文件先打开读写双方句柄并删除路径，再写帧；进程退出也不会遗留已写入的照片。
     * 只有写入结束后才向 Binder 提交只读 fd，接收方看不到未写完的帧。
     */
    static AidlImage rgb(File cache, byte[] bytes, int width, int height) throws IOException {
        File path = File.createTempFile("huoyan-frame-", ".rgb", cache);
        ParcelFileDescriptor fd = null;
        try {
            try (FileOutputStream output = new FileOutputStream(path)) {
                fd = ParcelFileDescriptor.open(path, ParcelFileDescriptor.MODE_READ_ONLY);
                if (!path.delete()) throw new IOException("无法移除图像临时路径");
                output.write(bytes);
            }
            return new AidlImage(FrameParcel.ofShmKey(fd, width, height), null, fd, null);
        } catch (IOException | RuntimeException failure) {
            if (fd != null) try {
                fd.close();
            } catch (IOException ignored) {
            }
            throw failure;
        } finally {
            if (path.exists()) path.delete();
        }
    }

    /**
     * uploadPhoto 使用壳真实支持的 SharedMemory，映射写完后转为只读。
     */
    static AidlImage photo(byte[] bytes, int format) throws ErrnoException, IOException {
        SharedMemory memory = SharedMemory.create("huoyan-photo", bytes.length);
        try {
            ByteBuffer buffer = memory.mapReadWrite();
            try {
                buffer.put(bytes);
            } finally {
                SharedMemory.unmap(buffer);
            }
            if (!memory.setProtect(OsConstants.PROT_READ))
                throw new IOException("无法保护图片共享内存");
            return new AidlImage(null, new ImageRequestParcel(memory, null,
                    bytes.length, 0, 0, format), null, memory);
        } catch (ErrnoException | IOException | RuntimeException failure) {
            memory.close();
            throw failure;
        }
    }

    /**
     * 客户端超时无法撤回 Binder；必须等真实调用退出后才能关发送端句柄。
     */
    @Override
    public void close() {
        if (descriptor != null) try {
            descriptor.close();
        } catch (IOException ignored) {
        }
        if (memory != null) memory.close();
    }
}
