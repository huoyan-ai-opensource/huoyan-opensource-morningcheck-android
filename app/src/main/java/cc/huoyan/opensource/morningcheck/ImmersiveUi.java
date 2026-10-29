package cc.huoyan.opensource.morningcheck;

import android.app.Dialog;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 页面与弹窗共用的沉浸显示；边缘滑动可以临时唤出系统栏。
 */
final class ImmersiveUi {
    private ImmersiveUi() {
    }

    static void enable(Window window) {
        configureInput(window);
        WindowCompat.setDecorFitsSystemWindows(window, window.isFloating() && usesSystemInput(window));
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        hideSystemBars(window);
    }

    static void hideSystemBars(Window window) {
        if (window == null) return;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
        if (!usesSystemInput(window)) controller.hide(WindowInsetsCompat.Type.ime());
    }

    private static boolean usesSystemInput(Window window) {
        // 激活码窗口主动隔离系统输入法，继续保留它自己的输入区与窗口尺寸。
        return (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) == 0;
    }

    private static void configureInput(Window window) {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | (usesSystemInput(window) ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING));
    }

    static void bindInsets(View root) {
        int left = root.getPaddingLeft(), top = root.getPaddingTop();
        int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets cutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout());
            // 页面保持全屏，由输入法底部留白避让；浮动弹窗交给窗口自身缩放。
            int inputBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(left + cutout.left, top + cutout.top,
                    right + cutout.right, bottom + Math.max(cutout.bottom, inputBottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    static <T extends Dialog> T show(T dialog) {
        // 保留业务显示监听器；浮动表单使用系统窗口避让键盘。
        if (dialog.getWindow() != null) configureInput(dialog.getWindow());
        hideSystemBars(dialog.getWindow());
        dialog.show();
        if (dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            configureInput(window);
            if (window.isFloating() && usesSystemInput(window))
                WindowCompat.setDecorFitsSystemWindows(window, true);
        }
        hideSystemBars(dialog.getWindow());
        return dialog;
    }
}
