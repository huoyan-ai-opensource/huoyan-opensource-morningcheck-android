package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import cc.huoyan.morningcheck.app.aidl.RegisterResultParcel;
import cc.huoyan.morningcheck.app.aidl.UserDetailParcel;
import cc.huoyan.morningcheck.app.aidl.UserImageParcel;

/** 使用系统 Parcel 验证图片尾块与旧回执的边界，不启动页面、不连接 SDK 或业务网络。 */
@RunWith(AndroidJUnit4.class)
public final class FaceImageParcelCompatibilityTest {
    private static final int OUTER_SENTINEL = 0x1E2D3C4B;
    private static final String OUTER_TEXT = "外层字段：员工资料";
    private static final long FIRST_UID = 77001L, SECOND_UID = 77002L;

    @Test public void registerImageUrlRoundTripKeepsOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            RegisterResultParcel source = RegisterResultParcel.ok(FIRST_UID,
                    "http://127.0.0.1:18087/images/77001.jpg?版本=一");
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            RegisterResultParcel restored = RegisterResultParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals("注册图片尾块只能消费自己的字段", payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Test public void userImageItemsRoundTripKeepsUidPhotoBindingAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            UserDetailParcel source = UserDetailParcel.ok(Arrays.asList(
                    new UserImageParcel(FIRST_UID, "http://127.0.0.1:18087/images/77001.jpg"),
                    new UserImageParcel(SECOND_UID, "")));
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            UserDetailParcel restored = UserDetailParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals(2, restored.getUidCount());
            assertEquals(FIRST_UID, restored.getItems().get(0).getUid());
            assertEquals("http://127.0.0.1:18087/images/77001.jpg", restored.getItems().get(0).getImageUrl());
            assertTrue(restored.getItems().get(0).isImageAvailable());
            assertEquals("image/jpeg", restored.getItems().get(0).getMimeType());
            assertEquals(SECOND_UID, restored.getItems().get(1).getUid());
            assertEquals("", restored.getItems().get(1).getImageUrl());
            assertFalse(restored.getItems().get(1).isImageAvailable());
            assertEquals("", restored.getItems().get(1).getMimeType());
            assertEquals("人员图片尾块不能吞掉外层字段", payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Test public void singleImageParcelRoundTripKeepsExactLongIdentityAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            UserImageParcel source = new UserImageParcel(2097500510197125121L, "http://127.0.0.1/actual.jpg");
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);
            UserImageParcel restored = UserImageParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals(2097500510197125121L, restored.getUid());
            assertEquals("http://127.0.0.1/actual.jpg", restored.getImageUrl());
            assertTrue(restored.isImageAvailable());
            assertEquals("image/jpeg", restored.getMimeType());
            assertEquals(payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    @Test public void oldRegisterPrefixWithoutImageTailKeepsOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            writeOldRegisterPrefix(parcel, FIRST_UID);
            int prefixEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            RegisterResultParcel restored = RegisterResultParcel.CREATOR.createFromParcel(parcel);
            assertEquals(0, restored.code);
            assertNull(restored.reason);
            assertEquals(FIRST_UID, restored.uid);
            assertEquals("旧 SDK 未提供图片地址时应为空", "", restored.imageUrl);
            assertEquals("旧回执后面的 sentinel 不属于图片扩展", prefixEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Test public void oldUserPrefixWithoutImageTailKeepsOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            writeOldUserPrefix(parcel, new long[]{FIRST_UID, SECOND_UID});
            int prefixEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            UserDetailParcel restored = UserDetailParcel.CREATOR.createFromParcel(parcel);
            assertEquals(0, restored.getCode());
            assertNull(restored.getReason());
            assertEquals(2, restored.getUidCount());
            assertEquals(FIRST_UID, restored.getItems().get(0).getUid());
            assertEquals(SECOND_UID, restored.getItems().get(1).getUid());
            for (UserImageParcel item : restored.getItems()) {
                assertEquals("历史缺图仍须保留该 UID 的条目", "", item.getImageUrl());
                assertFalse(item.isImageAvailable());
            }
            assertEquals(prefixEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Test public void oldRegisterReaderSeesOriginalUidInNewReply() {
        Parcel parcel = Parcel.obtain();
        try {
            RegisterResultParcel.ok(FIRST_UID, "http://127.0.0.1:18087/77001.jpg").writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            // 旧客户端只读取 code、reason、uid；新增图片尾块不得改变这个前缀。
            assertEquals(0, parcel.readInt());
            assertNull(parcel.readString());
            assertEquals(FIRST_UID, parcel.readLong());
        } finally {
            parcel.recycle();
        }
    }

    @Test public void oldUserReaderSeesOriginalUidArrayInNewReply() {
        Parcel parcel = Parcel.obtain();
        try {
            UserDetailParcel.ok(new long[]{FIRST_UID, SECOND_UID}, new String[]{"http://127.0.0.1/1.jpg", ""})
                    .writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            // 旧客户端只读取 code、reason、count、uids，不读取图片尾块。
            assertEquals(0, parcel.readInt());
            assertNull(parcel.readString());
            assertEquals(2, parcel.readInt());
            assertArrayEquals(new long[]{FIRST_UID, SECOND_UID}, parcel.createLongArray());
        } finally {
            parcel.recycle();
        }
    }

    @Test public void misalignedImageUrlArrayIsRejectedAtConstruction() {
        assertThrows("一个地址不能静默配给两个 UID", IllegalArgumentException.class,
                () -> UserDetailParcel.ok(new long[]{FIRST_UID, SECOND_UID},
                        new String[]{"http://127.0.0.1/1.jpg"}));
    }

    @Test public void misalignedImageTailIsRejectedDuringRead() throws Exception {
        Parcel parcel = Parcel.obtain();
        try {
            writeOldUserPrefix(parcel, new long[]{FIRST_UID, SECOND_UID});
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream tail = new DataOutputStream(buffer)) {
                // 模拟错误壳回执：尾块版本 1，地址数量 1，但旧前缀声明两个 UID。
                tail.writeInt(1);
                tail.writeInt(1);
                tail.writeInt(0);
            }
            parcel.writeInt(0x4D434955);
            parcel.writeByteArray(buffer.toByteArray());
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            assertThrows("错位地址尾块不能静默产生错误样本", IllegalStateException.class,
                    () -> UserDetailParcel.CREATOR.createFromParcel(parcel));
            assertEquals("损坏尾块仍有明确边界", payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static void writeOldRegisterPrefix(Parcel parcel, long uid) {
        parcel.writeInt(0);
        parcel.writeString(null);
        parcel.writeLong(uid);
    }

    private static void writeOldUserPrefix(Parcel parcel, long[] uids) {
        parcel.writeInt(0);
        parcel.writeString(null);
        parcel.writeInt(uids.length);
        parcel.writeLongArray(uids);
    }

    private static void writeOuterFields(Parcel parcel) {
        parcel.writeInt(OUTER_SENTINEL);
        parcel.writeString(OUTER_TEXT);
    }

    private static void assertOuterFields(Parcel parcel) {
        assertEquals("Parcelable 不能吞掉后续外层 sentinel", OUTER_SENTINEL, parcel.readInt());
        assertEquals(OUTER_TEXT, parcel.readString());
        assertEquals("外层字段读取结束应恰好位于 Parcel 末尾", parcel.dataSize(), parcel.dataPosition());
    }
}
