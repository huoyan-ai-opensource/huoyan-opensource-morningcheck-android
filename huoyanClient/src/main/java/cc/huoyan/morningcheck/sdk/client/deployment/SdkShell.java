package cc.huoyan.morningcheck.sdk.client.deployment;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/**
 * 本机晨检 SDK 的部署元信息；安装权限和交互由宿主负责。
 */
public final class SdkShell {
    public static final String PACKAGE_NAME = SdkConfig.SDK_PACKAGE;
    public static final String DEFAULT_APK_URL = "http://f.huoyan.cc/apk/morningcheck_sdk_lastest.apk";

    private SdkShell() {
    }

    /**
     * 返回已安装版本；宿主需声明包可见性，未安装时返回 null。
     */
    public static PackageInfo installedVersion(PackageManager packages) {
        try {
            return packages.getPackageInfo(PACKAGE_NAME, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }
}
