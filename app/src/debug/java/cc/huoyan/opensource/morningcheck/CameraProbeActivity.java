package cc.huoyan.opensource.morningcheck;

import android.app.Activity;
import android.os.Bundle;
import android.view.TextureView;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;

/** 设备端采集验收专用宿主，不创建登录、识别、上传或门店绑定流程。 */
public final class CameraProbeActivity extends Activity {
    public CameraSource source;
    public TextureView preview;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.view_camera_preview);
        preview = findViewById(R.id.camera_texture);
    }

    @Override protected void onPause() {
        // 被测页面恢复前先交还镜头，避免验收宿主本身持有相机直至 onDestroy。
        if (source != null) source.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (source != null) source.close();
        super.onDestroy();
    }
}
