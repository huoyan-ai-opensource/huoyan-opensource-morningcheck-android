package cc.huoyan.opensource.morningcheck;

import android.Manifest;
import android.content.pm.PackageManager;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.concurrent.atomic.AtomicBoolean;

import cc.huoyan.opensource.morningcheck.camera.CameraSource;

/**
 * 身份采集页面共享相机预览和权限；离开页面立即释放相机。
 */
public abstract class CameraMorningActivity extends BaseMorningActivity {
    protected CameraSource camera;
    protected DetectionOverlay overlay;
    protected volatile CameraSource.Frame latestFrame;
    protected volatile int session, cameraGeneration;
    protected final AtomicBoolean postedFrame = new AtomicBoolean();
    protected String cameraError = "";

    protected String cameraForStage() {
        return setting("face_camera", "");
    }

    protected String cameraRole() {
        return "face";
    }

    protected void onCameraFrame(CameraSource.Frame frame) {
    }

    protected void onCameraError() {
    }

    @Override
    protected void beforeRender() {
        session++;
        stopCamera();
    }

    @Override
    protected void onStop() {
        session++;
        stopCamera();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopCamera();
        super.onDestroy();
    }

    protected FrameLayout previewFrame() {
        return previewFrame(R.layout.view_camera_preview);
    }

    protected FrameLayout previewFrame(int layout) {
        final int previewGeneration = ++cameraGeneration;
        FrameLayout preview = (FrameLayout) getLayoutInflater().inflate(layout, content, false);
        TextureView texture = preview.findViewById(R.id.camera_texture);
        overlay = preview.findViewById(R.id.camera_overlay);
        camera = new CameraSource(this, texture, new CameraSource.Listener() {
            @Override
            public void onFrame(CameraSource.Frame frame) {
                if (previewGeneration != cameraGeneration) return;
                int frameSession = session;
                if (postedFrame.compareAndSet(false, true)) main.post(() -> {
                    postedFrame.set(false);
                    if (previewGeneration == cameraGeneration && frameSession == session) {
                        latestFrame = frame;
                        onCameraFrame(frame);
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (previewGeneration != cameraGeneration) return;
                latestFrame = null;
                cameraError = message;
                onCameraError();
            }
        });
        return preview;
    }

    protected void ensureCamera(String id) {
        if (!foreground || camera == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 20);
            return;
        }
        camera.start(id, cc.huoyan.opensource.morningcheck.camera.CameraImageOptions.read(prefs, cameraRole()));
    }

    protected void stopCamera() {
        cameraGeneration++;
        latestFrame = null;
        if (camera != null) {
            camera.close();
            camera = null;
        }
        overlay = null;
    }

    public void onRequestPermissionsResult(int code, @NonNull String[] permissions, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 20) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
                ensureCamera(cameraForStage());
            else {
                cameraError = getString(R.string.i18n_a_camera_permission_is_denied_allow_it_in_system_app_settings);
                onCameraError();
                notice(cameraError);
            }
        }
    }
}
