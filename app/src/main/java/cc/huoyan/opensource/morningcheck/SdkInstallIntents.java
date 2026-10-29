package cc.huoyan.opensource.morningcheck;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * 安装与授权只交给系统导出组件，保留临时文件读取授权。
 */
final class SdkInstallIntents {
    private SdkInstallIntents() {
    }

    static Intent explicitSystemActivity(PackageManager packages, Intent request) {
        for (ResolveInfo match : packages.queryIntentActivities(request,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY)) {
            ActivityInfo page = match.activityInfo;
            if (page != null && page.exported && page.enabled)
                return new Intent(request).setComponent(new ComponentName(page.packageName, page.name));
        }
        throw new ActivityNotFoundException("没有可用的系统安装或授权页面");
    }
}
