package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;


/**
 * Created by feng
 * Created on 2020/10/19
 */
public class ToastEx {
    public static Toast toast;
    private static final String LOG_TAG = "ToastEx";

    private static void logDebugToast(String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "[Toast] " + msg);
        }
    }

    /**
     * 显示带成功图标的短提示，用于保存、连接等操作成功。
     */
    public static void showSuccessToast(Context context, String msg) {
        showSuccessToast(context, msg, R.drawable.ic_toast_success);
    }

    /**
     * 读取当前语言资源并显示成功提示。
     */
    public static void showSuccessToast(Context context, int stringId) {
        showSuccessToast(context, context.getString(stringId), R.drawable.ic_toast_success);
    }

    /**
     * 默认显示错误图标；成功场景应调用 showSuccessToast。
     */
    public static void createToast(Context context, String msg) {
        if (context == null) {
            return;
        }
        logDebugToast(msg);
        if (toast != null) {
            toast.cancel();
        }
        toast = new Toast(context);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.view_toast, null);
        TextView t = view.findViewById(R.id.toast_text);
        t.setText(msg);
        toast.setView(view);
        toast.show();
    }

    public static void showSuccessToast(Context context, String msg, int drawableId) {
        if (context == null) {
            return;
        }
        logDebugToast(msg);
        if (toast != null) {
            toast.cancel();
        }
        toast = new Toast(context);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(Toast.LENGTH_SHORT);

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.view_toast, null);
        TextView t = view.findViewById(R.id.toast_text);
        t.setText(msg);
        ImageView icon = view.findViewById(R.id.toast_image);
        icon.setImageResource(drawableId);
        toast.setView(view);
        toast.show();
    }

    public static void showFailureToast(Context context, String msg) {
        if (context == null) {
            return;
        }
        logDebugToast(msg);
        if (toast != null) {
            toast.cancel();
        }
        toast = new Toast(context);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.setDuration(Toast.LENGTH_LONG);

        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.view_toast, null);
        TextView t = view.findViewById(R.id.toast_text);
        t.setText(msg);
        toast.setView(view);
        toast.show();
    }
}
