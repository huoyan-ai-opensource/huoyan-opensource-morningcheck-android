package cc.huoyan.opensource.morningcheck;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.deployment.SdkShell;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.sdk.SdkInstallationPolicy;

/**
 * 手动入口和权限拒绝回执共用前台授权弹窗。系统设置的授权对象是本机 SDK 壳。
 */
public final class SdkPermissionGuide extends DialogFragment {
    private static final String TAG = "sdkPermissionGuide";
    private static final String CONNECTION_GENERATION = "sdkPermissionGuide.connectionGeneration";

    private long settingsGeneration = -1;
    private boolean settingsOpen;
    private final ActivityResultLauncher<Intent> settings = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> onSettingsReturned());

    static boolean open(BaseMorningActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (activity.isFinishing() || activity.isDestroyed() || manager.isStateSaved()
                || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            return false;
        if (manager.findFragmentByTag(TAG) == null)
            new SdkPermissionGuide().showNow(manager, TAG);
        return true;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.containsKey(CONNECTION_GENERATION)) {
            settingsGeneration = state.getLong(CONNECTION_GENERATION);
            settingsOpen = true;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle state) {
        BaseMorningActivity activity = (BaseMorningActivity) requireActivity();
        ProjectDialog.Builder builder = new ProjectDialog.Builder(activity)
                .setTitle(R.string.sdk_permissions_title)
                .setPositiveButton(R.string.i18n_a_close, null);
        if (!usesLocalShell(activity))
            return builder.setMessage(R.string.sdk_permissions_remote).create();
        PackageInfo sdkPackage = SdkShell.installedVersion(activity.getPackageManager());
        if (sdkPackage == null)
            return builder.setMessage(R.string.sdk_permissions_missing_sdk).create();

        View paths = activity.getLayoutInflater().inflate(R.layout.dialog_sdk_permission_actions, null);
        TextView overlay = paths.findViewById(R.id.sdk_permission_overlay);
        TextView install = paths.findViewById(R.id.sdk_permission_install);
        View overlayPath = paths.findViewById(R.id.sdk_permission_overlay_path);
        View installPath = paths.findViewById(R.id.sdk_permission_install_path);
        overlay.setVisibility(Build.VERSION.SDK_INT >= 23 ? View.VISIBLE : View.GONE);
        overlayPath.setVisibility(overlay.getVisibility());
        install.setVisibility(Build.VERSION.SDK_INT >= 26 ? View.VISIBLE : View.GONE);
        installPath.setVisibility(install.getVisibility());
        overlay.setOnClickListener(v -> openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        install.setOnClickListener(v -> openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
        CharSequence sdkName = sdkPackage.applicationInfo == null
                ? getText(R.string.sdk_permissions_app_label)
                : sdkPackage.applicationInfo.loadLabel(activity.getPackageManager());
        return builder.setMessage(Build.VERSION.SDK_INT >= 23
                        ? getString(R.string.sdk_permissions_summary, sdkName)
                        : getString(R.string.sdk_permissions_not_needed))
                .setView(paths).create();
    }

    @Override
    public void onStart() {
        super.onStart();
        ImmersiveUi.hideSystemBars(requireDialog().getWindow());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        if (settingsOpen) state.putLong(CONNECTION_GENERATION, settingsGeneration);
        super.onSaveInstanceState(state);
    }

    private static boolean usesLocalShell(BaseMorningActivity activity) {
        try {
            return SdkInstallationPolicy.requiresLocalShell(MorningSdkSettings.read(activity.prefs),
                    Build.VERSION.SDK_INT);
        } catch (IllegalArgumentException invalidConfig) {
            return false;
        }
    }

    private void openSettings(String action) {
        if (settingsOpen) return;
        BaseMorningActivity activity = (BaseMorningActivity) requireActivity();
        Uri sdkPackage = Uri.fromParts("package", SdkShell.PACKAGE_NAME, null);
        Intent[] destinations = {
                new Intent(action, sdkPackage),
                new Intent(action),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, sdkPackage),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent destination : destinations) {
            try {
                Intent systemPage = SdkInstallIntents.explicitSystemActivity(activity.getPackageManager(), destination);
                settingsGeneration = activity.sdkGeneration();
                settingsOpen = true;
                settings.launch(systemPage);
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                settingsOpen = false;
            }
        }
        activity.notice(getString(R.string.sdk_permissions_settings_unavailable));
    }

    private void onSettingsReturned() {
        if (!settingsOpen || !isAdded()) return;
        settingsOpen = false;
        BaseMorningActivity activity = (BaseMorningActivity) requireActivity();
        long generation = settingsGeneration;
        if (activity.sdkGeneration() != generation || !usesLocalShell(activity)
                || !activity.sdk.isConnected()) return;

        // 设置页不返回其他包的授权结果。证书查询可区分仍缺权限和已有权限但 SDK 尚未初始化。
        activity.sdkSession.read("getCertInfo", (code, reason, data) -> {
            if (activity.sdkGeneration() != generation || MorningSdkSession.missingPermission(code, reason)
                    || code == HuoyanClient.ERROR_RESULT_UNKNOWN || code == HuoyanClient.ERROR_BUSY
                    || code == HuoyanClient.ERROR_NOT_CONNECTED || code == HuoyanClient.ERROR_CANCELLED
                    || code == HuoyanClient.ERROR_INVALID_REQUEST) return;
            activity.sdkSession.read("init", (initCode, initReason, initData) -> {
                if (activity.sdkGeneration() == generation) activity.sdkSession.refreshDetails();
            });
        });
    }
}
