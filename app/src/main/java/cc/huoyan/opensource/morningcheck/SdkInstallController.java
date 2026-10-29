package cc.huoyan.opensource.morningcheck;

import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

import cc.huoyan.morningcheck.sdk.client.deployment.SdkApkDownloader;
import cc.huoyan.morningcheck.sdk.client.deployment.SdkShell;

/**
 * 页面拥有缺壳安装流程；后台只下载，前台才打开系统授权或安装器。
 */
final class SdkInstallController {
    // 两个页面可能同时记得缺壳；主线程共享一次安装通知，避免返回首页中断正在建立的连接。
    private static boolean installationNotified;
    private static final String STATE_APK = "sdkInstall.apk";
    private static final String STATE_PERMISSION = "sdkInstall.permission";
    private static final String STATE_MISSING = "sdkInstall.missing";
    private static final String STATE_CONTINUE = "sdkInstall.continue";
    private static final String STATE_PROMPTED = "sdkInstall.prompted";
    private final AppCompatActivity activity;
    private final Runnable installed, connectionSettings;
    private final String apkUrl;
    private final Platform platform;
    private final File directory;
    private Dialog prompt, progress;
    private SdkApkDownloader download;
    private File pendingApk, verifiedApk, validatingApk;
    private long verifiedLength, verifiedModified;
    private boolean installAfterValidation;
    private boolean resumed, destroyed, awaitingPermission, observedMissing;
    private boolean installWhenResumed, promptOffered;
    private long downloaded, total = -1;
    private int failureMessage;
    private final View.OnLayoutChangeListener boundsChanged = (view, left, top, right, bottom,
                                                               oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            if (prompt instanceof ProjectDialog) ((ProjectDialog) prompt).resizeToHost();
            if (progress != null) resizeDialog(progress, right - left);
        }
    };

    /**
     * 系统边界便于隔离安装测试，下载和 APK 元信息校验仍走真实实现。
     */
    interface Platform {
        boolean isInstalled();

        boolean canInstallPackages();

        void openPermission();

        void install(File apk);
    }

    SdkInstallController(AppCompatActivity activity, Bundle state, Runnable installed, Runnable connectionSettings) {
        this(activity, state, installed, connectionSettings, SdkShell.DEFAULT_APK_URL, new Platform() {
            @Override
            public boolean isInstalled() {
                return SdkShell.installedVersion(activity.getPackageManager()) != null;
            }

            @Override
            public boolean canInstallPackages() {
                return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
            }

            @Override
            public void openPermission() {
                activity.startActivity(SdkInstallIntents.explicitSystemActivity(activity.getPackageManager(),
                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()))));
            }

            @Override
            public void install(File apk) {
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".sdk-files", apk);
                activity.startActivity(SdkInstallIntents.explicitSystemActivity(activity.getPackageManager(),
                        new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
            }
        });
    }

    SdkInstallController(AppCompatActivity activity, Bundle state, Runnable installed, Runnable connectionSettings,
                         String apkUrl, Platform platform) {
        this.activity = activity;
        this.installed = installed;
        this.connectionSettings = connectionSettings;
        this.apkUrl = apkUrl;
        this.platform = platform;
        directory = new File(activity.getCacheDir(), "sdk-installs");
        activity.getWindow().getDecorView().addOnLayoutChangeListener(boundsChanged);
        observedMissing = !platform.isInstalled();
        if (state != null) {
            observedMissing |= state.getBoolean(STATE_MISSING);
            awaitingPermission = state.getBoolean(STATE_PERMISSION);
            installWhenResumed = state.getBoolean(STATE_CONTINUE);
            promptOffered = state.getBoolean(STATE_PROMPTED);
            String path = state.getString(STATE_APK);
            if (path != null) {
                File candidate = new File(path);
                try {
                    if (candidate.isFile() && candidate.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
                        pendingApk = candidate;
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 仅缺失到已安装的转换通知重连，普通切页和返回不重复初始化。
     */
    boolean onResume(boolean promptIfMissing) {
        if (destroyed) return platform.isInstalled();
        resumed = true;
        if (platform.isInstalled()) {
            cancelDownload();
            dismissDialogs();
            clearApk();
            awaitingPermission = false;
            installWhenResumed = false;
            if (observedMissing) {
                observedMissing = false;
                if (!installationNotified) {
                    installationNotified = true;
                    installed.run();
                }
            }
            return true;
        }
        observedMissing = true;
        installationNotified = false;
        if (pendingApk != null && !isVerified()) {
            boolean proceed = installWhenResumed || (awaitingPermission && platform.canInstallPackages());
            awaitingPermission = false;
            installWhenResumed = false;
            validatePendingApk(proceed);
            return false;
        }
        if (awaitingPermission) {
            awaitingPermission = false;
            if (platform.canInstallPackages()) installApk();
            else showPrompt(R.string.sdk_install_permission_required);
        } else if (installWhenResumed) {
            installWhenResumed = false;
            installApk();
        } else if (download != null) showProgress();
        else if (pendingApk != null) showPrompt(R.string.sdk_install_pending);
        else if (failureMessage != 0) showFailure();
        else if (promptIfMissing && !promptOffered) showPrompt(R.string.sdk_install_guide_message);
        return false;
    }

    /**
     * 隐藏界面，后台完成只留下待安装文件，不抢占其他页面。
     */
    void onPause() {
        resumed = false;
        dismissDialogs();
    }

    void saveState(Bundle state) {
        if (pendingApk != null) state.putString(STATE_APK, pendingApk.getAbsolutePath());
        state.putBoolean(STATE_PERMISSION, awaitingPermission);
        state.putBoolean(STATE_MISSING, observedMissing);
        state.putBoolean(STATE_CONTINUE, installWhenResumed);
        state.putBoolean(STATE_PROMPTED, promptOffered);
    }

    void onDestroy() {
        destroyed = true;
        resumed = false;
        activity.getWindow().getDecorView().removeOnLayoutChangeListener(boundsChanged);
        cancelDownload();
        dismissDialogs();
        if (activity.isFinishing()) clearApk();
    }

    /**
     * 首页主操作或连接页主动请求；已有完整文件优先继续安装。
     */
    void downloadAndInstall() {
        if (!resumed || destroyed || download != null || platform.isInstalled()) return;
        dismissDialogs();
        if (pendingApk != null) installApk();
        else startDownload();
    }

    private void showPrompt(int message) {
        if (!canShow() || prompt != null) return;
        promptOffered = true;
        ProjectDialog.Builder builder = new ProjectDialog.Builder(activity)
                .setTitle(R.string.sdk_install_guide_title)
                .setMessage(message)
                .setNegativeButton(R.string.sdk_install_guide_later, (dialog, which) -> {
                    failureMessage = 0;
                    dismissDialogs();
                })
                .setPositiveButton(pendingApk == null ? R.string.sdk_install_download : R.string.sdk_install_continue,
                        (dialog, which) -> downloadAndInstall());
        if (connectionSettings != null)
            builder.setNeutralButton(activity.getText(R.string.sdk_install_guide_settings),
                    (dialog, which) -> {
                        dismissDialogs();
                        connectionSettings.run();
                    });
        prompt = builder.create();
        Dialog shown = prompt;
        shown.setCanceledOnTouchOutside(false);
        shown.setOnDismissListener(dialog -> {
            if (prompt == shown) prompt = null;
        });
        ImmersiveUi.show(shown);
    }

    private void startDownload() {
        failureMessage = 0;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            fail(R.string.sdk_install_download_failed);
            return;
        }
        downloaded = 0;
        total = -1;
        SdkApkDownloader task = new SdkApkDownloader(apkUrl, directory);
        download = task;
        showProgress();
        task.start(new SdkApkDownloader.Listener() {
            @Override
            public void onProgress(long bytes, long length) {
                activity.runOnUiThread(() -> {
                    if (download != task || destroyed) return;
                    downloaded = bytes;
                    total = length;
                    renderProgress();
                });
            }

            @Override
            public void onComplete(File apk) {
                // APK 解析读取文件，在下载线程校验包名，不把错误发行包交给系统安装器。
                boolean valid = validApk(apk);
                activity.runOnUiThread(() -> {
                    if (download != task || destroyed) {
                        apk.delete();
                        return;
                    }
                    download = null;
                    dismissDialogs();
                    if (!valid) {
                        apk.delete();
                        fail(R.string.sdk_install_invalid_apk);
                        return;
                    }
                    pendingApk = apk;
                    rememberVerified(apk);
                    if (resumed) installApk();
                    else installWhenResumed = true;
                });
            }

            @Override
            public void onFailure(IOException error) {
                activity.runOnUiThread(() -> {
                    if (download != task || destroyed) return;
                    download = null;
                    dismissDialogs();
                    fail(R.string.sdk_install_download_failed);
                });
            }
        });
    }

    private boolean validApk(File apk) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info != null && SdkShell.PACKAGE_NAME.equals(info.packageName);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean isVerified() {
        return pendingApk != null && pendingApk.equals(verifiedApk) && pendingApk.isFile()
                && pendingApk.length() == verifiedLength && pendingApk.lastModified() == verifiedModified;
    }

    private void rememberVerified(File apk) {
        verifiedApk = apk;
        verifiedLength = apk.length();
        verifiedModified = apk.lastModified();
    }

    /**
     * 重建后不信任缓存文件的旧状态；解析完成再允许继续安装。
     */
    private void validatePendingApk(boolean proceed) {
        installAfterValidation |= proceed;
        if (validatingApk != null) return;
        File candidate = pendingApk;
        validatingApk = candidate;
        new Thread(() -> {
            boolean valid = validApk(candidate);
            activity.runOnUiThread(() -> {
                if (destroyed || !candidate.equals(pendingApk)) return;
                validatingApk = null;
                boolean continueInstall = installAfterValidation;
                installAfterValidation = false;
                if (!valid) {
                    clearApk();
                    dismissDialogs();
                    fail(R.string.sdk_install_invalid_apk);
                } else {
                    rememberVerified(candidate);
                    if (!resumed) installWhenResumed |= continueInstall;
                    else if (continueInstall) installApk();
                    else showPrompt(R.string.sdk_install_pending);
                }
            });
        }, "晨检SDK安装包校验").start();
    }

    private void showProgress() {
        if (!canShow() || progress != null) return;
        progress = createDialog(R.layout.dialog_sdk_install_progress);
        progress.setCancelable(false);
        progress.findViewById(R.id.sdk_install_cancel).setOnClickListener(view -> {
            cancelDownload();
            failureMessage = 0;
            dismissDialogs();
        });
        renderProgress();
        showDialog(progress);
    }

    private void renderProgress() {
        if (progress == null || !resumed) return;
        ProgressBar bar = progress.findViewById(R.id.sdk_install_progress);
        bar.setIndeterminate(total <= 0);
        int percent = total > 0 ? (int) Math.min(100, downloaded * 100d / total) : 0;
        bar.setProgress(percent);
        ((TextView) progress.findViewById(R.id.sdk_install_progress_text)).setText(total > 0
                ? activity.getString(R.string.sdk_install_progress_value, percent)
                : activity.getString(R.string.sdk_install_downloading));
    }

    private void installApk() {
        if (!canShow()) return;
        if (pendingApk == null || !pendingApk.isFile()) {
            clearApk();
            fail(R.string.sdk_install_download_failed);
            return;
        }
        if (!isVerified()) {
            validatePendingApk(true);
            return;
        }
        try {
            if (!platform.canInstallPackages()) {
                awaitingPermission = true;
                platform.openPermission();
            } else platform.install(pendingApk);
        } catch (RuntimeException unavailable) {
            awaitingPermission = false;
            fail(R.string.sdk_install_open_failed);
        }
    }

    private Dialog createDialog(int layout) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.smallestScreenWidthDp = 1;
        ContextThemeWrapper context = new ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme);
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(LayoutInflater.from(context).inflate(layout, null));
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    private void showDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            ImmersiveUi.show(dialog);
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = activity.getWindow().getDecorView().getWidth();
        // 首次 postResume 可能早于首页测量；使用当前窗口配置，不能把安装引导压成一条线。
        if (width <= 0) {
            android.content.res.Resources resources = activity.getResources();
            int configuredWidth = Math.round(resources.getConfiguration().screenWidthDp
                    * resources.getDisplayMetrics().density);
            width = configuredWidth > 0 ? configuredWidth : resources.getDisplayMetrics().widthPixels;
        }
        window.setGravity(Gravity.CENTER);
        window.setDimAmount(.35f);
        resizeDialog(dialog, width);
        ImmersiveUi.show(dialog);
    }

    /**
     * 横竖屏或分屏改变可用宽度时同步收窄弹窗；不重新创建下载任务。
     */
    private void resizeDialog(Dialog dialog, int width) {
        Window window = dialog.getWindow();
        if (window == null || width <= 0) return;
        android.content.res.Resources base = dialog.findViewById(R.id.sdk_install_cancel).getResources();
        int margin = base.getDimensionPixelSize(R.dimen.dp_24);
        int maxWidth = base.getDimensionPixelSize(R.dimen.dp_472) + base.getDimensionPixelSize(R.dimen.dp_8);
        window.setLayout(Math.max(1, Math.min(width - margin, maxWidth)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private boolean canShow() {
        return resumed && !destroyed && !activity.isFinishing() && !activity.getSupportFragmentManager().isStateSaved();
    }

    private void fail(int message) {
        failureMessage = message;
        if (canShow()) showFailure();
    }

    private void showFailure() {
        ToastEx.showFailureToast(activity, activity.getString(failureMessage));
        failureMessage = 0;
        showPrompt(pendingApk == null ? R.string.sdk_install_guide_message : R.string.sdk_install_pending);
    }

    private void cancelDownload() {
        if (download != null) download.cancel();
        download = null;
    }

    private void clearApk() {
        if (pendingApk != null) pendingApk.delete();
        pendingApk = null;
        verifiedApk = null;
    }

    private void dismissDialogs() {
        if (prompt != null) prompt.dismiss();
        if (progress != null) progress.dismiss();
        prompt = null;
        progress = null;
    }
}
