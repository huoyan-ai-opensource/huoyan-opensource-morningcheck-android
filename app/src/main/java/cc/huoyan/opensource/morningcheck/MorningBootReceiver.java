package cc.huoyan.opensource.morningcheck;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 系统开机后按操作员设置进入晨检，不在广播中等待网络或 SDK 初始化。
 */
public final class MorningBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !SecuritySettings.isAutoCheckEnabled(context)) return;
        try {
            // Android 10 起后台启动还受系统策略约束；此调用不代表界面必定被允许前置。
            context.startActivity(new Intent(context, CheckActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Log.w("晨检开机", "系统未允许开机进入晨检", unavailable);
        }
    }
}
