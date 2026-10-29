package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.SdkInstallationPolicy;

/**
 * SDK 连接设置：独立 Activity 直接绑定对应 XML。
 */
public final class DevicesActivity extends BaseDeviceSettingsActivity {
    private SdkInstallController sdkInstall;
    private int selectedTransport = -1;
    private final java.util.ArrayList<String> discovered = new java.util.ArrayList<>();
    private java.util.concurrent.ExecutorService discoveryWorker;
    private java.util.concurrent.Future<?> discoveryTask;
    private AlertDialog loading;
    private int queryGeneration;
    private android.widget.ArrayAdapter<String> discoveryAdapter;

    @Override
    protected void setupSettings(Bundle state) {
        discoveryWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
        if (state != null && state.getStringArrayList("sdk.discovered") != null)
            discovered.addAll(state.getStringArrayList("sdk.discovered"));
        sdkInstall = new SdkInstallController(this, state, () -> {
            try {
                SdkConfig config = MorningSdkSettings.read(prefs);
                if (SdkInstallationPolicy.requiresLocalShell(config, Build.VERSION.SDK_INT) && !sdk.isConnected())
                    sdkSession.reconnect(config);
            } catch (IllegalArgumentException invalid) {
                notice(failure(invalid));
            }
        }, null);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (ensureAppFontCurrent()) sdkInstall.onResume(false);
    }

    @Override
    protected void onPause() {
        cancelDiscovery();
        sdkInstall.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putStringArrayList("sdk.discovered", new java.util.ArrayList<>(discovered));
        sdkInstall.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        cancelDiscovery();
        discoveryWorker.shutdownNow();
        sdkInstall.onDestroy();
        super.onDestroy();
    }

    @Override
    protected boolean designPanels() {
        return true;
    }

    @Override
    protected int settingsSection() {
        return 0;
    }

    @Override
    protected int regularFormLayout() {
        return R.layout.view_sdk_design;
    }

    @Override
    protected int compactFormLayout() {
        return R.layout.view_sdk_design;
    }

    @Override
    protected void bindRegular(View form) {
        content.setPadding(0, 0, 0, 0);
        if (selectedTransport < 0) selectedTransport = formState.getInt("sdk.transportDraft",
                MorningSdkSettings.transportIndex(prefs) == 2 ? 2 : 1);
        EditText ip = form.findViewById(R.id.settings_sdk_url);
        ip.setText(cc.huoyan.morningcheck.sdk.client.SdkDiscovery.ipFromEndpoint(
                prefs.getString(MorningSdkSettings.ENDPOINT_KEY, MorningSdkSettings.DEFAULT_WS_ENDPOINT)));
        settingsAction(form, R.id.settings_sdk_local, () -> selectConnectionTab(false));
        settingsAction(form, R.id.settings_sdk_lan, () -> selectConnectionTab(true));
        settingsAction(form, R.id.settings_connect, this::saveLanConnection);
        settingsAction(form, R.id.settings_sdk_test, () -> queryDevices(false));
        settingsAction(form, R.id.settings_sdk_discover, () -> queryDevices(true));
        settingsAction(form, R.id.settings_open_sdk, this::openSdk);
        android.widget.ListView list = form.findViewById(R.id.settings_sdk_discovery_list);
        discoveryAdapter = new android.widget.ArrayAdapter<String>(this, R.layout.item_sdk_discovery,
                R.id.discovery_address, discovered) {
            @Override
            public View getView(int position, View recycled, android.view.ViewGroup parent) {
                View row = super.getView(position, recycled, parent);
                boolean selected = getItem(position).equals(ip.getText().toString().trim());
                row.setBackgroundResource(selected ? R.drawable.mc_face_staff_selected : R.drawable.mc_field);
                ((TextView) row.findViewById(R.id.discovery_address)).setTextColor(selected ? Ui.BLUE : Ui.INK);
                TextView state = row.findViewById(R.id.discovery_selected);
                state.setText(selected ? getString(R.string.i18n_a_selected_2) : getString(R.string.i18n_a_select));
                state.setTextColor(selected ? Ui.BLUE : Ui.MUTED);
                return row;
            }
        };
        list.setAdapter(discoveryAdapter);
        list.setEmptyView(form.findViewById(R.id.settings_sdk_discovery_empty));
        list.setOnItemClickListener((parent, row, position, id) -> ip.setText(discovered.get(position)));
        ip.addTextChangedListener(watcher(() -> discoveryAdapter.notifyDataSetChanged()));
    }

    @Override
    protected void bindCompact(View form) {
        bindRegular(form);
    }

    @Override
    protected void releaseSettingsView() {
        cancelDiscovery();
    }

    private void selectConnectionTab(boolean lan) {
        if (!allowConnectionChange()) return;
        cancelDiscovery();
        selectedTransport = lan ? 2 : 1;
        formState.putInt("sdk.transportDraft", selectedTransport);
        if (!lan) {
            boolean changed = !sdkSession.matchesConfiguration(SdkConfig.aidl());
            prefs.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "aidl").apply();
            if (changed || !sdk.isConnected()) sdkSession.reconnect(SdkConfig.aidl());
        }
        refreshStatus();
    }

    private void saveLanConnection() {
        if (!allowConnectionChange()) return;
        EditText ip = formView.findViewById(R.id.settings_sdk_url);
        try {
            String endpoint = cc.huoyan.morningcheck.sdk.client.SdkDiscovery.endpointForIp(ip.getText().toString());
            cancelDiscovery();
            prefs.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                    .putString(MorningSdkSettings.ENDPOINT_KEY, endpoint).apply();
            formState.remove("sdk.transportDraft");
            sdkSession.reconnect(SdkConfig.webSocket(endpoint));
            toast(getString(R.string.i18n_a_connection_settings_saved));
        } catch (IllegalArgumentException invalid) {
            notice(invalid.getMessage());
        }
    }

    private void queryDevices(boolean scan) {
        if (discoveryTask != null) return;
        EditText field = formView.findViewById(R.id.settings_sdk_url);
        String ip = field.getText().toString().trim();
        final String endpoint;
        try {
            endpoint = scan && ip.isEmpty() ? "" : cc.huoyan.morningcheck.sdk.client.SdkDiscovery.endpointForIp(ip);
        } catch (IllegalArgumentException invalid) {
            notice(invalid.getMessage());
            return;
        }
        int expected = ++queryGeneration;
        View body = getLayoutInflater().inflate(R.layout.dialog_design_loading, null);
        ((TextView) body.findViewById(R.id.loading_message)).setText(scan ? getString(R.string.i18n_a_finding_lan_devices) : getString(R.string.i18n_a_testing_connection));
        loading = new AlertDialog.Builder(this).create();
        loading.setView(body, 0, 0, 0, 0);
        loading.setOnCancelListener(dialog -> cancelDiscovery());
        body.findViewById(R.id.loading_cancel).setOnClickListener(v -> cancelDiscovery());
        ImmersiveUi.show(loading);
        loading.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        loading.getWindow().setLayout(Math.min(getWindow().getDecorView().getWidth()
                        - getResources().getDimensionPixelSize(R.dimen.dp_24), getResources().getDimensionPixelSize(R.dimen.dp_239)),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        discoveryTask = discoveryWorker.submit(() -> {
            try {
                java.util.List<String> matches = scan
                        ? cc.huoyan.morningcheck.sdk.client.SdkDiscovery.discover(ip) : java.util.Collections.emptyList();
                boolean connected = !scan && cc.huoyan.morningcheck.sdk.client.SdkDiscovery.probe(endpoint);
                main.post(() -> {
                    if (expected != queryGeneration || !uiReady()) return;
                    finishDiscovery();
                    if (scan) {
                        discovered.clear();
                        discovered.addAll(matches);
                        discoveryAdapter.notifyDataSetChanged();
                        refreshStatus();
                        if (matches.isEmpty()) notice(getString(R.string.i18n_a_no_morning_check_sdk_found_check_the_device_and_network));
                        else toast(getString(R.string.i18n_a_devices_found, matches.size()));
                    } else {
                        if (connected) toast(getString(R.string.i18n_a_connection_test_passed));
                        else notice(getString(R.string.i18n_a_no_sdk_response_check_the_ip_address_and_network));
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (expected != queryGeneration || !uiReady()) return;
                    finishDiscovery();
                    notice(getString(R.string.i18n_a_device_scan_failed, failure(error)));
                });
            }
        });
    }

    private void finishDiscovery() {
        discoveryTask = null;
        if (loading != null) {
            loading.dismiss();
            loading = null;
        }
    }

    private void cancelDiscovery() {
        queryGeneration++;
        if (discoveryTask != null) discoveryTask.cancel(true);
        finishDiscovery();
    }

    @Override
    protected void refreshStatus() {
        super.refreshStatus();
        if (formView == null) return;
        boolean lan = selectedTransport == 2;
        formView.findViewById(R.id.settings_sdk_local).setSelected(!lan);
        formView.findViewById(R.id.settings_sdk_lan).setSelected(lan);
        formView.findViewById(R.id.settings_sdk_local_panel).setVisibility(lan ? View.GONE : View.VISIBLE);
        formView.findViewById(R.id.settings_sdk_lan_panel).setVisibility(lan ? View.VISIBLE : View.GONE);
        ((TextView) formView.findViewById(R.id.settings_sdk_discovery_title)).setText(getString(R.string.i18n_a_devices_found_title, discovered.size()));
        ((TextView) formView.findViewById(R.id.settings_connection)).setText(sdk.isConnected() ? getString(R.string.i18n_a_connected) : getString(R.string.i18n_a_disconnected));
        boolean missing;
        try {
            getPackageManager().getApplicationInfo("cc.huoyan.morningcheck", 0);
            missing = false;
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            missing = true;
        }
        formView.findViewById(R.id.settings_open_sdk).setVisibility(missing && !lan ? View.VISIBLE : View.GONE);
    }

    void openSdk() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("cc.huoyan.morningcheck");
        if (intent != null) {
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                notice(getString(R.string.i18n_a_the_sdk_is_installed_without_a_launchable_screen_connect_to_its_service_ins));
            }
            return;
        }
        try {
            getPackageManager().getApplicationInfo("cc.huoyan.morningcheck", 0);
            // 当前壳没有桌面入口，内部 MainActivity 也未导出，不能把它误报为未安装。
            notice(getString(R.string.i18n_a_the_sdk_runs_as_a_background_service_connect_or_reconnect_to_view_its_conne));
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) {
            sdkInstall.downloadAndInstall();
        }
    }

}
