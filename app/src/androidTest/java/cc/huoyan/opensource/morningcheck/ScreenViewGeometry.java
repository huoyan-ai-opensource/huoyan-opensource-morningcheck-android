package cc.huoyan.opensource.morningcheck;

import android.graphics.Rect;
import android.view.View;

/** 真实触摸和裁切断言统一使用屏幕坐标，兼容居中的业务弹窗。 */
final class ScreenViewGeometry {
    private ScreenViewGeometry() { }

    static boolean visibleOnScreen(View view, Rect result) {
        if (!view.getGlobalVisibleRect(result)) return false;
        // getGlobalVisibleRect 位于根视图坐标系；注入触摸和 getLocationOnScreen 位于屏幕坐标系。
        int[] origin = new int[2];
        view.getRootView().getLocationOnScreen(origin);
        result.offset(origin[0], origin[1]);
        return true;
    }

    static Rect bounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }
}
