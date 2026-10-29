package cc.huoyan.opensource.morningcheck.camera;

import android.content.SharedPreferences;

/**
 * 每个采集角色独立保存画面参数，范围以归正、镜像后的画面归一化坐标表示。
 */
public final class CameraImageOptions {
    public final String resolution;
    public final boolean mirror;
    public final float left, top, right, bottom;

    public CameraImageOptions(String resolution, boolean mirror, float left, float top, float right, float bottom) {
        this.resolution = resolution == null ? "" : resolution;
        this.mirror = mirror;
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom)
                || left < 0 || top < 0 || right > 1 || bottom > 1 || right - left < .05f - 0.000001f || bottom - top < .05f - 0.000001f)
            throw new IllegalArgumentException("采集范围必须在画面内且宽高不少于 5%");
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public static CameraImageOptions defaults() {
        return new CameraImageOptions("", false, 0, 0, 1, 1);
    }

    public static CameraImageOptions read(SharedPreferences prefs, String role) {
        String key = role + "_image_";
        try {
            return new CameraImageOptions(prefs.getString(key + "resolution", ""), prefs.getBoolean(key + "mirror", false),
                    prefs.getFloat(key + "left", 0), prefs.getFloat(key + "top", 0),
                    prefs.getFloat(key + "right", 1), prefs.getFloat(key + "bottom", 1));
        } catch (IllegalArgumentException invalid) {
            return defaults();
        }
    }

    public void save(SharedPreferences.Editor edit, String role) {
        String key = role + "_image_";
        edit.putString(key + "resolution", resolution).putBoolean(key + "mirror", mirror)
                .putFloat(key + "left", left).putFloat(key + "top", top)
                .putFloat(key + "right", right).putFloat(key + "bottom", bottom);
    }

    public CameraImageOptions fullPreview() {
        return new CameraImageOptions(resolution, mirror, 0, 0, 1, 1);
    }

    /**
     * 左右翻转在原始缓冲的副本上完成，调用者持有的采集帧不会被修改。
     */
    public byte[] pixels(byte[] source, int width, int height) {
        int x0 = leftPixel(width), y0 = topPixel(height), w = width(width), h = height(height);
        if (!mirror && x0 == 0 && y0 == 0 && w == width && h == height) return source;
        byte[] output = new byte[w * h * 3];
        for (int y = 0; y < h; y++) {
            if (!mirror)
                System.arraycopy(source, ((y + y0) * width + x0) * 3, output, y * w * 3, w * 3);
            else for (int x = 0; x < w; x++) {
                int from = ((y + y0) * width + width - 1 - (x + x0)) * 3;
                int to = (y * w + x) * 3;
                System.arraycopy(source, from, output, to, 3);
            }
        }
        return output;
    }

    public int leftPixel(int width) {
        return Math.min(width - 1, (int) (left * width));
    }

    public int topPixel(int height) {
        return Math.min(height - 1, (int) (top * height));
    }

    public int width(int width) {
        return Math.max(1, Math.min(width, (int) (right * width)) - leftPixel(width));
    }

    public int height(int height) {
        return Math.max(1, Math.min(height, (int) (bottom * height)) - topPixel(height));
    }
}
