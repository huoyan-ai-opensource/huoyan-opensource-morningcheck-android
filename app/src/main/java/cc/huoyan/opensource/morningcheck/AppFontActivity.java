package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 普通页面和独立扫码页使用同一字体配置，后台页面返回时恢复已有状态。
 */
public abstract class AppFontActivity extends AppCompatActivity {
    private int appliedFontIndex;
    private boolean fontRecreationRequested;

    @Override
    protected void attachBaseContext(Context base) {
        appliedFontIndex = AppFontSettings.readIndex(base);
        // Activity 只覆盖字体，屏幕配置继续由系统更新，避免把初始横竖屏尺寸固定在包装 Context 中。
        Configuration override = new Configuration();
        override.fontScale = AppFontSettings.scaleFor(appliedFontIndex);
        applyOverrideConfiguration(override);
        super.attachBaseContext(base);
    }

    protected final boolean ensureAppFontCurrent() {
        if (appliedFontIndex == AppFontSettings.readIndex(this)) return !fontRecreationRequested;
        if (!fontRecreationRequested) {
            fontRecreationRequested = true;
            // 在开始相机、串口和页面订阅前重建，沿用各页保存的草稿与列表位置。
            recreate();
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        Configuration appConfiguration = new Configuration(configuration);
        appConfiguration.fontScale = AppFontSettings.scaleFor(appliedFontIndex);
        super.onConfigurationChanged(appConfiguration);
    }
}
