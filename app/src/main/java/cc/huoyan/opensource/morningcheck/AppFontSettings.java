package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 应用字体独立保存，不修改设备的系统字体、密度或原始尺寸表。
 */
public final class AppFontSettings {
    public static final String KEY = "app_font_size_v2";
    public static final int DEFAULT_INDEX = 1;
    private static final String LEGACY_KEY = "app_font_size";
    private static final int[] LEGACY_CHOICES = {0, 0, 1, 2};
    private static final float[] SCALES = {0.9775f, 1.15f, 1.3225f, 1.495f};

    private AppFontSettings() {
    }

    public static int readIndex(Context context) {
        try {
            SharedPreferences saved = preferences(context);
            if (saved.contains(KEY)) {
                int index = saved.getInt(KEY, DEFAULT_INDEX);
                return valid(index) ? index : DEFAULT_INDEX;
            }
            int legacy = saved.getInt(LEGACY_KEY, -1);
            // 新标准采用原大号的实际字号；旧设备按最接近的视觉大小映射，读取不写盘。
            return valid(legacy) ? LEGACY_CHOICES[legacy] : DEFAULT_INDEX;
        } catch (ClassCastException invalidValue) {
            return DEFAULT_INDEX;
        }
    }

    public static float scaleFor(int index) {
        return SCALES[valid(index) ? index : DEFAULT_INDEX];
    }

    public static boolean save(Context context, int index) {
        if (!valid(index)) throw new IllegalArgumentException("字体大小档位无效");
        if (readIndex(context) == index) return false;
        preferences(context).edit().putInt(KEY, index).apply();
        return true;
    }

    public static Context wrap(Context context) {
        // 独立预览 Context 必须继承传入 Context 的限定符，嵌套创建不会自动合并其覆盖配置。
        Configuration override = new Configuration(context.getResources().getConfiguration());
        override.fontScale = scaleFor(readIndex(context));
        return context.createConfigurationContext(override);
    }

    private static boolean valid(int index) {
        return index >= 0 && index < SCALES.length;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("device", Context.MODE_PRIVATE);
    }
}
