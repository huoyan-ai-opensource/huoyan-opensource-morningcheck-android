package cc.huoyan.opensource.morningcheck;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.opensource.morningcheck.cloud.MorningCloudApi;
import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.login.ShopBindingStore;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkState;

/**
 * 共享连接、存储与页头导航；具体页面由各 Activity 的 XML 和绑定方法负责。
 */
public abstract class BaseMorningActivity extends AppFontActivity implements MorningSdkSession.Listener {
    protected SharedPreferences prefs;
    protected AppStore store;
    protected ExecutorService io;
    protected MorningApplication application;
    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final MorningCloudApi cloud = new MorningCloudApi();
    protected HuoyanClient sdk;
    protected MorningSdkSession sdkSession;
    protected boolean foreground, destroyed, loaded, wide, compact, navigating;
    protected String connection = "正在连接晨检 SDK";
    protected LinearLayout content;
    protected JSONObject currentRecord;
    protected List<JSONObject> staff = new ArrayList<>(), records = new ArrayList<>();

    protected abstract String pageKey();

    @LayoutRes
    protected abstract int activityLayout();

    protected abstract void bindPage();

    protected void setup(Bundle state) {
    }

    protected void beforeRender() {
    }

    protected void onDataLoaded() {
        render();
    }

    protected void onSdkStateChanged() {
    }

    protected void beforeNavigate(String target, Runnable action) {
        action.run();
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ImmersiveUi.enable(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("device", MODE_PRIVATE);
        application = (MorningApplication) getApplication();
        store = application.store;
        io = application.io;
        sdkSession = application.sdkSession();
        sdk = sdkSession.client();
        setup(state);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!ensureAppFontCurrent()) return;
        foreground = true;
        navigating = false;
        sdkSession.attach(this);
        connectSdk();
        updateHeaderShop();
        reload(false, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ensureAppFontCurrent()) return;
        // 快速切页返回可能只经历暂停，不再触发 onStart；恢复前台时解除导航防连点。
        navigating = false;
    }

    @Override
    protected void onStop() {
        foreground = false;
        sdkSession.detach(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        sdkSession.detach(this);
        // 共享存储队列与数据库归进程所有，页面退出时不关闭。
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ImmersiveUi.hideSystemBars(getWindow());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        render();
    }

    @Override
    public void onBackPressed() {
        if (pageKey().equals("home")) {
            // 明确退出时结束客户端会话，避免进程保留后重开跳过 init 和壳更新检查。
            sdkSession.disconnect();
            finishAffinity();
        } else navigate("home");
    }

    protected void render() {
        if (destroyed) return;
        beforeRender();
        wide = getResources().getBoolean(R.bool.mc_wide);
        compact = getResources().getBoolean(R.bool.mc_compact);
        setContentView(activityLayout());
        View root = findViewById(R.id.main_root);
        content = root.findViewById(R.id.page_content);
        ImmersiveUi.bindInsets(root);
        bindHeader(root);
        bindPage();
    }

    protected void bindHeader(View root) {
        boolean home = pageKey().equals("home");
        View leading = root.findViewById(R.id.header_leading);
        LinearLayout.LayoutParams leadingLayout = (LinearLayout.LayoutParams) leading.getLayoutParams();
        boolean phone = getResources().getBoolean(R.bool.mc_phone);
        // 手机返回键按触控宽度布局，把剩余空间交给标题；平板保留对称页头。
        leadingLayout.width = home ? LinearLayout.LayoutParams.WRAP_CONTENT
                : (Math.round(((TextView) root.findViewById(R.id.header_back_label)).getTextSize() * 4) + getResources().getDimensionPixelSize(R.dimen.dp_30));
        leadingLayout.weight = 0;
        leading.setLayoutParams(leadingLayout);
        View trailing = root.findViewById(R.id.header_trailing);
        LinearLayout.LayoutParams trailingLayout = (LinearLayout.LayoutParams) trailing.getLayoutParams();
        if (!home || phone) {
            trailingLayout.width = home ? 0 : (Math.round(((TextView) root.findViewById(R.id.header_back_label)).getTextSize() * 4) + getResources().getDimensionPixelSize(R.dimen.dp_30));
            trailingLayout.weight = home ? 1 : 0;
            trailing.setLayoutParams(trailingLayout);
        }
        root.findViewById(R.id.header_brand).setVisibility(home ? View.VISIBLE : View.GONE);
        TextView about = root.findViewById(R.id.home_settings);
        about.setVisibility(home ? View.VISIBLE : View.GONE);
        about.setText(phone ? "" : getString(R.string.mc_home_design_about));
        about.setContentDescription(getString(R.string.mc_home_design_about));
        View back = root.findViewById(R.id.header_home);
        back.setVisibility(home ? View.GONE : View.VISIBLE);
        // 与设计稿保持一致：子页从页头返回首页，仍经过采集中的离开确认。
        back.setOnClickListener(v -> navigate("home"));
        root.findViewById(R.id.header_back_label).setVisibility(home ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.header_workspace).setVisibility(wide && !phone ? View.VISIBLE : View.GONE);
        TextView pageTitle = root.findViewById(R.id.header_page_title);
        pageTitle.setVisibility(home ? View.GONE : View.VISIBLE);
        switch (pageKey()) {
            case "result":
                pageTitle.setText("检查结果");
                break;
            case "staff":
                pageTitle.setText("员工管理");
                break;
            case "staff_edit":
                pageTitle.setText("编辑员工");
                break;
            case "departments":
                pageTitle.setText("部门管理");
                break;
            case "record_photo":
                pageTitle.setText("采集照片");
                break;
            case "sdk_details":
                pageTitle.setText("设备详情");
                break;
            case "enroll":
                pageTitle.setText(R.string.face_enrollment_entry);
                break;
            case "records":
                pageTitle.setText("晨检记录");
                break;
            case "record_detail":
                pageTitle.setText("记录详情");
                break;
            case "settings":
                pageTitle.setText("设备与 SDK");
                break;
            case "lighting":
                pageTitle.setText(R.string.mc_home_design_lighting);
                break;
            default:
                pageTitle.setText("");
        }
        updateHeaderShop();
    }

    /**
     * 场所页保存的展示名称优先；未填写时采用当前连接对应的登录名称，归属标识仍独立存储。
     */
    protected String currentShopName() {
        String name = setting("shop_name", "").trim();
        if (!name.isEmpty()) return name;
        JSONObject scope = new ShopBindingStore(this).read();
        try {
            if (ShopBindingStore.matchesConnection(scope, MorningSdkSettings.read(prefs))) {
                // 二维码原文与门店 ID 都不能作为展示名称。
                return scope.optString("shop_name", "").trim();
            }
        } catch (IllegalArgumentException invalidConfig) {
            // 地址错误由连接页解释；这里保留真实填写的门店名称，不展示旧 SDK 归属。
        }
        return name;
    }

    protected void updateHeaderShop() {
        TextView shop = findViewById(R.id.header_shop);
        if (shop == null) return;
        // 所有页头暂时隐藏门店信息，也移除扫码登录的点击入口。
        shop.setVisibility(View.GONE);
        shop.setText("");
        shop.setContentDescription(null);
        shop.setOnClickListener(null);
        shop.setClickable(false);
        shop.setFocusable(false);
    }

    protected void navigate(String target) {
        navigate(target, -1);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        super.startActivity(intent, options);
        overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    protected void navigate(String target, int section) {
        if (pageKey().equals(target) && section < 0 && !target.equals("settings")) return;
        if (navigating || isFinishing() || !foreground) return;
        beforeNavigate(target, () -> {
            if (navigating || isFinishing() || !foreground) return;
            navigating = true;
            Class<?> type;
            switch (target) {
                case "check":
                    type = CheckActivity.class;
                    break;
                case "staff":
                    type = StaffActivity.class;
                    break;
                case "enroll":
                    type = FaceEnrollmentActivity.class;
                    break;
                case "departments":
                    type = DepartmentsActivity.class;
                    break;
                case "records":
                    type = RecordsActivity.class;
                    break;
                case "settings":
                    type = SettingsNavigation.activityForSection(section);
                    break;
                case "lighting":
                    type = LightingSettingsActivity.class;
                    break;
                default:
                    type = HomeActivity.class;
            }
            if (type == getClass()) {
                navigating = false;
                return;
            }
            Intent intent = new Intent(this, type);
            // 首页和各设置页复用实例，保留输入草稿。
            if (type == HomeActivity.class || BaseDeviceSettingsActivity.class.isAssignableFrom(type))
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            if (!pageKey().equals("home") && !pageKey().equals("settings")) finish();
        });
    }

    protected void reload(boolean recover, Runnable done) {
        // 设置页没有列表数据；列表、统计和单对象页面按各自需要重载，切页不读全库。
        if (destroyed || !foreground) return;
        loaded = true;
        if (done != null) done.run();
        else onDataLoaded();
    }

    protected void runIo(Runnable task) {
        io.execute(task);
    }

    protected void toast(String value) {
        if (!destroyed && foreground) ToastEx.showSuccessToast(this, value);
    }

    protected void notice(String value) {
        if (!destroyed && foreground && !isFinishing())
            ToastEx.showFailureToast(this, value);
    }

    @Override
    public void onConnection(boolean connected, String message) {
        connection = message == null || message.isEmpty() ? "SDK 未连接" : message;
        onSdkStateChanged();
    }

    @Override
    public void onStatus(JSONObject status) {
        if (destroyed) return;
        MorningSdkState current = sdkSession.viewState();
        connection = "●  " + current.title + " · " + current.detail;
        onSdkStateChanged();
    }

    @Override
    public void onSdkDetailsChanged() {
        if (destroyed || !foreground) return;
        MorningSdkState state = sdkSession.viewState();
        connection = "●  " + state.title + " · " + state.detail;
        onSdkStateChanged();
    }

    protected long sdkGeneration() {
        return sdkSession.generation();
    }

    /**
     * 激活回执未到或真实状态尚不可用时，不能用旧 READY 开放检测和人脸学习。
     */
    protected final boolean sdkReady() {
        return sdkSession.viewState().ready;
    }

    protected String setting(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    protected void connectSdk() {
        try {
            sdkSession.connect(MorningSdkSettings.read(prefs));
        } catch (IllegalArgumentException invalid) {
            // 配置错误不伪装成正在重试，操作员可在设备页修正通道或地址。
            sdkSession.configurationError("SDK 连接配置无效：" + failure(invalid));
        }
    }

    protected boolean rule(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    protected String failure(Throwable error) {
        return error.getMessage() == null ? "操作失败，请重试" : error.getMessage();
    }

    protected String date(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(millis));
    }

    protected boolean validHttp(String url) {
        try {
            okhttp3.HttpUrl parsed = okhttp3.HttpUrl.get(url);
            return parsed.username().isEmpty() && parsed.password().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    protected TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                action.run();
            }

            public void afterTextChanged(Editable e) {
            }
        };
    }

    // 员工、记录和设备设置均独立于模型可用性，证书过期时仍可正常管理业务资料。

    protected interface TextAction {
        void accept(String value);
    }

}
