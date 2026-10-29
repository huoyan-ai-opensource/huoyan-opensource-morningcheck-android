package cc.huoyan.opensource.morningcheck.camera;

import java.nio.ByteBuffer;

/**
 * 将相机平面按真实步长读取为紧密排列、方向归正的 RGB888。
 */
final class YuvFrameConverter {
    private YuvFrameConverter() {
    }

    static byte[] toRgb(ByteBuffer yPlane, int yRowStride, int yPixelStride,
                        ByteBuffer uPlane, int uRowStride, int uPixelStride,
                        ByteBuffer vPlane, int vRowStride, int vPixelStride,
                        int cropLeft, int cropTop, int width, int height, int rotation) {
        if (width <= 0 || height <= 0 || cropLeft < 0 || cropTop < 0
                || width > 4096 || height > 4096
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("相机画面尺寸或旋转角度不正确");
        }
        byte[] rgb = new byte[width * height * 3];
        int outputWidth = rotation == 90 || rotation == 270 ? height : width;
        int yStart = yPlane.position();
        int uStart = uPlane.position();
        int vStart = vPlane.position();
        for (int row = 0; row < height; row++) {
            int sourceY = cropTop + row;
            for (int col = 0; col < width; col++) {
                int sourceX = cropLeft + col;
                int y = (yPlane.get(yStart + sourceY * yRowStride + sourceX * yPixelStride) & 255) - 16;
                int u = (uPlane.get(uStart + (sourceY / 2) * uRowStride + (sourceX / 2) * uPixelStride) & 255) - 128;
                int v = (vPlane.get(vStart + (sourceY / 2) * vRowStride + (sourceX / 2) * vPixelStride) & 255) - 128;
                int scaledY = 298 * Math.max(0, y);
                int outX;
                int outY;
                if (rotation == 90) {
                    outX = height - row - 1;
                    outY = col;
                } else if (rotation == 180) {
                    outX = width - col - 1;
                    outY = height - row - 1;
                } else if (rotation == 270) {
                    outX = row;
                    outY = width - col - 1;
                } else {
                    outX = col;
                    outY = row;
                }
                int offset = (outY * outputWidth + outX) * 3;
                rgb[offset] = clamp((scaledY + 409 * v + 128) >> 8);
                rgb[offset + 1] = clamp((scaledY - 100 * u - 208 * v + 128) >> 8);
                rgb[offset + 2] = clamp((scaledY + 516 * u + 128) >> 8);
            }
        }
        return rgb;
    }

    static int uprightRotation(int sensorOrientation, int displayRotationDegrees, boolean frontFacing) {
        return (sensorOrientation + (frontFacing ? displayRotationDegrees : -displayRotationDegrees) + 360) % 360;
    }

    private static byte clamp(int value) {
        return (byte) Math.max(0, Math.min(255, value));
    }
}
