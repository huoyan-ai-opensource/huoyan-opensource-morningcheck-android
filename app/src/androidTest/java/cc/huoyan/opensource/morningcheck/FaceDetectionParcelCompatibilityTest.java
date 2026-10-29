package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import android.os.BadParcelableException;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import cc.huoyan.morningcheck.app.aidl.CapabilityParcel;
import cc.huoyan.morningcheck.app.aidl.FaceListParcel;
import cc.huoyan.morningcheck.app.aidl.McFaceParcel;

/** 使用系统 Parcel 验证人脸框线序、关键点分组和能力尾字段，不连接真实 SDK。 */
@RunWith(AndroidJUnit4.class)
public final class FaceDetectionParcelCompatibilityTest {
    private static final int OUTER_SENTINEL = 0x1E2D3C4B;
    private static final String OUTER_TEXT = "外层字段：人脸检测";

    @Test public void emptyFaceListRoundTripKeepsSuccessAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            FaceListParcel source = FaceListParcel.emptyOk();
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            FaceListParcel restored = FaceListParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals(0, restored.code);
            assertNull(restored.reason);
            assertEquals(0, restored.count);
            assertEquals(0, restored.toItems().length);
            assertEquals(payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    @Test public void threeFacesRoundTripKeepsEveryBoxAndItsOwnTenLandmarks() {
        Parcel parcel = Parcel.obtain();
        try {
            float[] points = landmarks(30);
            FaceListParcel source = new FaceListParcel(0, null, 3,
                    new float[]{0.1f, 0.4f, 0.7f}, new float[]{0.2f, 0.3f, 0.4f},
                    new float[]{0.2f, 0.15f, 0.1f}, new float[]{0.3f, 0.25f, 0.2f},
                    new float[]{0.98f, 0.95f, 0.91f}, points);
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            FaceListParcel restored = FaceListParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals(3, restored.count);
            assertArrayEquals(source.getBoxX(), restored.getBoxX(), 0f);
            assertArrayEquals(source.getBoxY(), restored.getBoxY(), 0f);
            assertArrayEquals(source.getBoxW(), restored.getBoxW(), 0f);
            assertArrayEquals(source.getBoxH(), restored.getBoxH(), 0f);
            assertArrayEquals(source.getScore(), restored.getScore(), 0f);
            assertArrayEquals(points, restored.getLandmarks(), 0f);
            McFaceParcel[] faces = restored.toItems();
            assertEquals(3, faces.length);
            for (int face = 0; face < faces.length; face++) {
                assertEquals(source.getBoxX()[face], faces[face].x, 0f);
                assertEquals(source.getBoxY()[face], faces[face].y, 0f);
                assertEquals(source.getBoxW()[face], faces[face].w, 0f);
                assertEquals(source.getBoxH()[face], faces[face].h, 0f);
                assertEquals(source.getScore()[face], faces[face].score, 0f);
                float[] actual = faces[face].getLandmarks();
                assertEquals(10, actual.length);
                for (int point = 0; point < actual.length; point++)
                    assertEquals("关键点不能串到另一张人脸", points[face * 10 + point], actual[point], 0f);
            }
            assertEquals("人脸数组只能读取自己的字段", payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    @Test public void malformedFaceArrayLengthIsRejectedBeforeAllocationAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(0);
            parcel.writeString(null);
            parcel.writeInt(0);
            // 前五个数组与 count=0 一致，最后一个伪造超大长度但没有任何数组载荷。
            for (int i = 0; i < 5; i++) parcel.writeFloatArray(new float[0]);
            parcel.writeInt(Integer.MAX_VALUE);
            int invalidLengthEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            assertThrows(BadParcelableException.class,
                    () -> FaceListParcel.CREATOR.createFromParcel(parcel));
            assertEquals("拒绝长度后不能尝试分配或读取外层字段", invalidLengthEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    @Test public void impossibleFaceCountIsRejectedWithoutAllocatingArrays() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(0);
            parcel.writeString(null);
            parcel.writeInt(Integer.MAX_VALUE);
            parcel.setDataPosition(0);
            assertThrows(BadParcelableException.class,
                    () -> FaceListParcel.CREATOR.createFromParcel(parcel));
        } finally { parcel.recycle(); }
    }

    @Test public void singleFaceRoundTripKeepsLandmarksAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            McFaceParcel source = new McFaceParcel(0.2f, 0.3f, 0.4f, 0.5f, 0.99f, landmarks(10));
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            McFaceParcel restored = McFaceParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertEquals(source.x, restored.x, 0f);
            assertEquals(source.y, restored.y, 0f);
            assertEquals(source.w, restored.w, 0f);
            assertEquals(source.h, restored.h, 0f);
            assertEquals(source.score, restored.score, 0f);
            assertArrayEquals(source.getLandmarks(), restored.getLandmarks(), 0f);
            assertEquals(payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    @Test public void oldCapabilityPrefixWithoutVersionDefaultsToUnknownAndNoFaceDetection() {
        Parcel parcel = Parcel.obtain();
        try {
            writeOldCapabilityPrefix(parcel);
            parcel.setDataPosition(0);

            CapabilityParcel restored = CapabilityParcel.CREATOR.createFromParcel(parcel);
            assertOriginalCapabilities(restored);
            assertEquals(CapabilityParcel.UNKNOWN_SDK_VERSION_NAME, restored.getSdkVersionName());
            assertEquals(CapabilityParcel.UNKNOWN_SDK_VERSION_CODE, restored.getSdkVersionCode());
            assertFalse("旧壳缺少此能力，不能据此调用新事务", restored.isDetectFace());
            assertEquals(parcel.dataSize(), parcel.dataPosition());
        } finally { parcel.recycle(); }
    }

    @Test public void capabilityVersionTailWithoutFaceFlagKeepsVersionAndDefaultsToFalse() {
        Parcel parcel = Parcel.obtain();
        try {
            writeOldCapabilityPrefix(parcel);
            parcel.writeString("1.0.5");
            parcel.writeInt(105);
            parcel.setDataPosition(0);

            CapabilityParcel restored = CapabilityParcel.CREATOR.createFromParcel(parcel);
            assertOriginalCapabilities(restored);
            assertEquals("1.0.5", restored.getSdkVersionName());
            assertEquals(105, restored.getSdkVersionCode());
            assertFalse(restored.isDetectFace());
            assertEquals(parcel.dataSize(), parcel.dataPosition());
        } finally { parcel.recycle(); }
    }

    @Test public void currentCapabilityRoundTripKeepsVersionFaceFlagAndOuterFields() {
        Parcel parcel = Parcel.obtain();
        try {
            CapabilityParcel source = new CapabilityParcel(0, null, 3,
                    true, true, true, false, true, false, "1.0.6", 106, true);
            source.writeToParcel(parcel, 0);
            int payloadEnd = parcel.dataPosition();
            writeOuterFields(parcel);
            parcel.setDataPosition(0);

            CapabilityParcel restored = CapabilityParcel.CREATOR.createFromParcel(parcel);
            assertEquals(source, restored);
            assertOriginalCapabilities(restored);
            assertEquals("1.0.6", restored.getSdkVersionName());
            assertEquals(106, restored.getSdkVersionCode());
            assertTrue(restored.isDetectFace());
            assertEquals("新能力位不能吞掉外层字段", payloadEnd, parcel.dataPosition());
            assertOuterFields(parcel);
        } finally { parcel.recycle(); }
    }

    private static float[] landmarks(int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) values[i] = (i + 1) / 100f;
        return values;
    }

    private static void writeOldCapabilityPrefix(Parcel parcel) {
        parcel.writeInt(0);
        parcel.writeString(null);
        parcel.writeInt(3);
        parcel.writeInt(1);
        parcel.writeInt(1);
        parcel.writeInt(1);
        parcel.writeInt(0);
        parcel.writeInt(1);
        parcel.writeInt(0);
    }

    private static void assertOriginalCapabilities(CapabilityParcel value) {
        assertEquals(0, value.getCode());
        assertNull(value.getReason());
        assertEquals(3, value.getApiVersion());
        assertTrue(value.isSharedMemoryImage());
        assertTrue(value.isCheck());
        assertTrue(value.isIdentify());
        assertFalse(value.isRegister());
        assertTrue(value.isForget());
        assertFalse(value.isUserQuery());
    }

    private static void writeOuterFields(Parcel parcel) {
        parcel.writeInt(OUTER_SENTINEL);
        parcel.writeString(OUTER_TEXT);
    }

    private static void assertOuterFields(Parcel parcel) {
        assertEquals(OUTER_SENTINEL, parcel.readInt());
        assertEquals(OUTER_TEXT, parcel.readString());
        assertEquals(parcel.dataSize(), parcel.dataPosition());
    }
}
