package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.ZoneId;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;
import cc.huoyan.opensource.morningcheck.sdk.SdkInstallationPolicy;

/**
 * 首页工作台。
 */
public final class HomeActivity extends BaseMorningActivity {
    public static final String EXTRA_SKIP_AUTO_CHECK = "skip_auto_check";
    private static final String STATE_INSTALLATION_GUIDE = "home.sdk_installation_guide";
    private static final String STATE_SHORTCUT_SCROLL_Y = "home.shortcut_scroll_y";
    private boolean localSdkMissing, installationGuideShown;
    private boolean autoCheckPending;
    private SdkInstallController sdkInstall;
    private CloudServiceUi cloudService;
    private long staffCount, checkedTodayCount, normalTodayCount, abnormalTodayCount;
    private int summaryRequest;
    private int shortcutScrollY;
    private int scrollRestoreVersion;
    private ScrollView pendingScrollRestore;
    private final Runnable summaryChanged = () -> reload(false, null);
    private final Runnable nextDaySummary = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !foreground) return;
            reload(false, null);
            scheduleNextDaySummary();
        }
    };

    @Override
    protected void setup(Bundle state) {
        Intent launch = getIntent();
        autoCheckPending = state == null && launch != null
                && Intent.ACTION_MAIN.equals(launch.getAction())
                && launch.hasCategory(Intent.CATEGORY_LAUNCHER)
                && !launch.getBooleanExtra(EXTRA_SKIP_AUTO_CHECK, false);
        installationGuideShown = state != null && state.getBoolean(STATE_INSTALLATION_GUIDE);
        shortcutScrollY = state == null ? 0 : state.getInt(STATE_SHORTCUT_SCROLL_Y, 0);
        cloudService = new CloudServiceUi(this);
        sdkInstall = new SdkInstallController(this, state, this::onSdkInstalled,
                () -> navigate("settings", 0));
    }

    @Override
    protected String pageKey() {
        return "home";
    }

    @Override
    protected int activityLayout() {
        return R.layout.activity_home_design;
    }

    @Override
    protected void bindPage() {
        View view = content;
        int iconDp = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp < 1100 ? 24 : 28;
        for (int id : new int[]{R.id.home_staff, R.id.home_records, R.id.home_connection,
                R.id.home_lighting, R.id.home_authorization, R.id.home_camera,
                R.id.home_items, R.id.home_temperature, R.id.home_system}) {
            android.view.ViewGroup tile = view.findViewById(id);
            View icon = tile.getChildAt(0);
            int size = Math.round(iconDp * getResources().getDisplayMetrics().density);
            icon.getLayoutParams().width = size;
            icon.getLayoutParams().height = size;
        }
        ((TextView) findViewById(R.id.home_settings)).setText(getString(R.string.i18n_a_about_device));
        android.widget.LinearLayout.LayoutParams trailing =
                (android.widget.LinearLayout.LayoutParams) findViewById(R.id.header_trailing).getLayoutParams();
        trailing.width = 0;
        trailing.weight = 0;
        findViewById(R.id.header_trailing).setLayoutParams(trailing);
        updateHeaderShop();
        view.findViewById(R.id.home_start).setOnClickListener(v -> {
            updateInstallationState();
            if (localSdkMissing) sdkInstall.downloadAndInstall();
            else if (sdkSession.viewState().needsActivation) ActivationFlowFragment.open(this);
            else navigate("check");
        });
        view.findViewById(R.id.home_staff).setOnClickListener(v -> navigate("staff"));
        view.findViewById(R.id.home_records).setOnClickListener(v -> navigate("records"));
        findViewById(R.id.home_settings).setOnClickListener(v -> navigate("settings", 3));
        view.findViewById(R.id.home_camera).setOnClickListener(v -> navigate("settings", 4));
        view.findViewById(R.id.home_items).setOnClickListener(v -> navigate("settings", 5));
        view.findViewById(R.id.home_authorization).setOnClickListener(v -> navigate("settings", 3));
        view.findViewById(R.id.home_connection).setOnClickListener(v -> navigate("settings", 0));
        view.findViewById(R.id.home_start).setOnLongClickListener(v -> {
            ImmersiveUi.show(new ProjectDialog.Builder(this)
                    .setTitle(getString(R.string.i18n_a_today_s_checks))
                    .setMessage(getString(R.string.i18n_a_home_stats, Math.max(0, staffCount - checkedTodayCount),
                            checkedTodayCount, normalTodayCount, abnormalTodayCount))
                    .setPositiveButton(getString(R.string.i18n_a_close), null).create());
            return true;
        });
        view.findViewById(R.id.home_temperature).setOnClickListener(v -> navigate("settings", 6));
        view.findViewById(R.id.home_system).setOnClickListener(v -> navigate("settings", 7));
        view.findViewById(R.id.home_lighting).setOnClickListener(v -> navigate("lighting"));
        updateHomeStatus();
        cloudService.bind();
        restoreShortcutScroll();
    }

    @Override
    protected void onDataLoaded() {
        // 统计由长按主卡查看，数据返回时不重建入口列表，保留滚动和焦点。
    }

    @Override
    protected void updateHeaderShop() {
        TextView shop = findViewById(R.id.header_shop);
        if (shop == null) return;
        String name = currentShopName();
        shop.setVisibility(View.VISIBLE);
        shop.setText(name.isEmpty() ? getString(R.string.i18n_a_no_workplace_set) : name);
    }

    @Override
    protected void beforeRender() {
        captureShortcutScroll();
    }

    private void captureShortcutScroll() {
        if (content == null) return;
        ScrollView scroll = content.findViewById(R.id.home_shortcut_scroll);
        // 首次布局和恢复尚未完成时的零偏移不能覆盖已保存的位置。
        if (scroll != null && scroll != pendingScrollRestore && scroll.getHeight() > 0) {
            shortcutScrollY = scroll.getScrollY();
        }
    }

    private void restoreShortcutScroll() {
        ScrollView scroll = content.findViewById(R.id.home_shortcut_scroll);
        int offset = shortcutScrollY;
        int version = ++scrollRestoreVersion;
        pendingScrollRestore = scroll;
        if (scroll == null) return;
        // 等容器和网格测量完成再恢复，否则 ScrollView 会把偏移裁成零。
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (destroyed || pendingScrollRestore != scroll || version != scrollRestoreVersion)
                    return;
                scroll.scrollTo(0, offset);
                pendingScrollRestore = null;
            }
        });
    }

    @Override
    protected void reload(boolean recover, Runnable done) {
        int request = ++summaryRequest;
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        runIo(() -> {
            try {
                AppStore.HomeSummary summary = store.homeSummary(start, end);
                main.post(() -> {
                    if (destroyed || !foreground || request != summaryRequest) return;
                    staffCount = summary.staffCount;
                    checkedTodayCount = summary.checkedTodayCount;
                    normalTodayCount = summary.normalTodayCount;
                    abnormalTodayCount = summary.abnormalTodayCount;
                    loaded = true;
                    if (done != null) done.run();
                    else onDataLoaded();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (request == summaryRequest) notice(getString(R.string.i18n_a_home_stats_failed, failure(error)));
                });
            }
        });
    }

    /**
     * 首页持续停留也在本地零点换日，不新增后台线程；离页撤销预约。
     */
    private void scheduleNextDaySummary() {
        main.removeCallbacks(nextDaySummary);
        long nextDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        main.postDelayed(nextDaySummary, Math.max(1, nextDay - System.currentTimeMillis()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        scheduleNextDaySummary();
        application.addStaffChangeListener(summaryChanged);
        application.addRecordChangeListener(summaryChanged);
        updateInstallationState();
        updateHomeStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        // 扫码快速返回时首页可能只经历暂停、没有停止，不能只靠 onStart 刷新新门店。
        updateHeaderShop();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (!ensureAppFontCurrent()) return;
        // 仅消费首次桌面启动；返回首页、复用实例和重建都不再次进入晨检。
        if (autoCheckPending) {
            autoCheckPending = false;
            if (SecuritySettings.isAutoCheckEnabled(this)) {
                startActivity(new Intent(this, CheckActivity.class));
                return;
            }
        }
        updateInstallationState();
        // 自动引导一次；主动点击及从安装授权页返回仍可继续已有下载。
        boolean prompt = localSdkMissing && !installationGuideShown;
        if (prompt) installationGuideShown = true;
        sdkInstall.onResume(prompt);
        updateInstallationState();
        updateHomeStatus();
    }

    @Override
    protected void onPause() {
        cloudService.stop();
        sdkInstall.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        captureShortcutScroll();
        summaryRequest++;
        main.removeCallbacks(nextDaySummary);
        application.removeStaffChangeListener(summaryChanged);
        application.removeRecordChangeListener(summaryChanged);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        captureShortcutScroll();
        state.putBoolean(STATE_INSTALLATION_GUIDE, installationGuideShown);
        state.putInt(STATE_SHORTCUT_SCROLL_Y, shortcutScrollY);
        sdkInstall.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        sdkInstall.onDestroy();
        super.onDestroy();
    }

    private void onSdkInstalled() {
        try {
            SdkConfig config = MorningSdkSettings.read(prefs);
            if (SdkInstallationPolicy.requiresLocalShell(config, Build.VERSION.SDK_INT) && !sdk.isConnected())
                sdkSession.reconnect(config);
        } catch (IllegalArgumentException invalid) {
            notice(failure(invalid));
        }
    }

    /**
     * 只查当前配置需要的本机包；远端设备不依赖本机是否安装 SDK。
     */
    private void updateInstallationState() {
        localSdkMissing = false;
        // 本地代理、ADB 转发也可能提供有效 WS；真实连接优先于客户端的安装查询。
        if (sdk.isConnected()) {
            return;
        }
        try {
            if (SdkInstallationPolicy.requiresLocalShell(MorningSdkSettings.read(prefs), Build.VERSION.SDK_INT))
                getPackageManager().getApplicationInfo(SdkConfig.SDK_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException missing) {
            localSdkMissing = true;
        } catch (IllegalArgumentException invalidConfiguration) {
            // 无效地址或旧 Android 强选 AIDL 由连接页解释，不能误报成未安装。
        }
    }

    @Override
    protected void onSdkStateChanged() {
        // 支持外部安装工具在首页可见时装壳；真实服务连上后撤下缺壳提示。
        if (localSdkMissing && sdk.isConnected()) {
            updateInstallationState();
            sdkInstall.onPause();
        }
        updateHomeStatus();
    }

    private void updateHomeStatus() {
        if (cloudService != null) cloudService.refresh();
        showHomeStatus(sdkSession.viewState(), localSdkMissing);
    }

    private void showHomeStatus(MorningSdkState state, boolean missing) {
        if (content == null) return;
        boolean ready = state.ready && !missing;
        String title = missing ? getString(R.string.sdk_install_missing_title) : UiMessages.text(this, state.title);
        String detail = missing ? getString(R.string.i18n_a_install_the_sdk_to_start_checking)
                : state.needsActivation ? getString(R.string.i18n_a_activate_this_device_to_start)
                  : ready ? getString(R.string.i18n_a_identity_temperature_head_hands) : UiMessages.text(this, state.detail);
        int background = Color.parseColor(missing ? "#C93535" : state.needsActivation ? "#F5C842"
                                                                : ready ? "#3F3EED" : "#64748B");
        content.findViewById(R.id.home_hero_panel).setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(background));
        ((TextView) content.findViewById(R.id.home_hero_detail)).setText(detail);
        TextView badge = content.findViewById(R.id.home_status_badge);
        String sdkVersion = sdkSession.certificate().optString("sdk_version", "");
        badge.setText(getString(R.string.i18n_a_app_sdk_version, BuildConfig.VERSION_NAME, (sdkVersion.isEmpty() ? title.replace("SDK ", "") : sdkVersion)));
        badge.setOnLongClickListener(v -> {
            notice(title + "\n" + detail);
            return true;
        });
        badge.setTextColor(ContextCompat.getColor(this, R.color.mc_muted));
        badge.setBackgroundTintList(null);
        ((TextView) content.findViewById(R.id.home_status_message)).setText(
                getString(R.string.i18n_a_device_connection, UiMessages.text(this, DeviceIdentity.hardwareSerial(this)),
                        MorningSdkSettings.transportIndex(prefs) == 2 ? getString(R.string.i18n_a_lan)
                                : getString(R.string.i18n_a_local)));
        TextView start = content.findViewById(R.id.home_start);
        boolean blocked = !ready && !missing && !state.needsActivation;
        start.setEnabled(!blocked);
        start.setText(missing ? getString(R.string.i18n_a_install_now) : state.needsActivation ? getString(R.string.i18n_a_activate_now)
                                                : blocked ? getString(R.string.i18n_a_loading_sdk) : getString(R.string.i18n_a_start_check));
        start.setTextColor(ready ? Ui.BLUE : Color.WHITE);
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor(missing ? "#AA2626" : state.needsActivation ? "#AF7D13"
                                                       : ready ? "#EBEEFF" : "#4F5D73")));
        start.setContentDescription(start.getText() + "，" + title + "，" + detail);
    }
}
