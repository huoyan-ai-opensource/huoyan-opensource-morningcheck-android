package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** 下载只访问本机夹具，安装器与未知来源授权全部拦截，不安装真实 SDK。 */
@RunWith(AndroidJUnit4.class)
public final class SdkInstallUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger downloads = new AtomicInteger();
    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();
    private final FakePlatform platform = new FakePlatform();
    private final Set<File> generatedFiles = new HashSet<>();
    private final ArrayList<SdkInstallController> peerControllers = new ArrayList<>();
    private MorningApplication app;
    private SharedPreferences device;
    private Map<String, Object> originalDevice;
    private MockWebServer endpoint;
    private ActivityScenario<SystemSettingsActivity> scenario;
    private ActivityScenario<HomeActivity> homeScenario;
    private HomeActivity home;
    private SdkInstallController controller;
    private byte[] sdkApk;
    private volatile int responseCode = 200;
    private volatile byte[] responseBody;
    private volatile CountDownLatch responseRelease;
    private boolean isolated;

    @Before public void isolateEndpointsAndHost() throws Exception {
        boolean x86 = false;
        for (String abi : Build.SUPPORTED_ABIS) x86 |= abi.startsWith("x86");
        String hardware = Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
        assumeTrue("安装流程夹具仅使用 x86 模拟器，不中断现场设备",
                x86 && (hardware.contains("ranchu") || hardware.contains("goldfish")));
        app = (MorningApplication) instrumentation.getTargetContext().getApplicationContext();
        assumeTrue("不能中断已有门店绑定",
                onMain(() -> !app.shopLogin().getState().isRunning()));
        device = app.getSharedPreferences("device", Context.MODE_PRIVATE);
        originalDevice = new HashMap<>(device.getAll());
        onMain(() -> {
            assertFalse("测试不能中断门店登录", app.shopLogin().getState().isRunning());
            isolated = true;
            finishPages();
            app.sdkSession().disconnect();
            return null;
        });
        instrumentation.waitForIdleSync();
        app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        try (InputStream input = instrumentation.getContext().getAssets().open("sdk-install-fixture.apk")) {
            sdkApk = readBytes(input);
        }
        responseBody = sdkApk;
        endpoint = new MockWebServer();
        endpoint.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (!"/morningcheck_sdk_lastest.apk".equals(request.getPath()))
                    return new MockResponse().setResponseCode(503);
                downloads.incrementAndGet();
                CountDownLatch release = responseRelease;
                if (release != null && !release.await(10, TimeUnit.SECONDS))
                    return new MockResponse().setResponseCode(504);
                return new MockResponse().setResponseCode(responseCode)
                        .setHeader("Content-Type", "application/vnd.android.package-archive")
                        .setBody(new Buffer().write(responseBody));
            }
        });
        endpoint.start();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY,
                        endpoint.url("/sdk").toString().replaceFirst("http", "ws"))
                .putString("cloud_url", endpoint.url("/cloud").toString())
                .putString("cloud_token", "").putBoolean("voice", false).commit());
        scenario = ActivityScenario.launch(new Intent(app, SystemSettingsActivity.class));
        scenario.onActivity(activity -> controller = new SdkInstallController(activity, null,
                reconnects::incrementAndGet, connections::incrementAndGet,
                endpoint.url("/morningcheck_sdk_lastest.apk").toString(), platform));
    }

    @After public void releaseHostAndRestorePreferences() throws Exception {
        if (responseRelease != null) responseRelease.countDown();
        try {
            onMain(() -> {
                rememberPending();
                if (controller != null) controller.onDestroy();
                for (SdkInstallController peer : peerControllers) peer.onDestroy();
                return null;
            });
            if (scenario != null) scenario.close();
            if (homeScenario != null) homeScenario.close();
        } finally {
            try {
                if (app != null && isolated) onMain(() -> {
                    finishPages();
                    app.sdkSession().disconnect();
                    return null;
                });
                instrumentation.waitForIdleSync();
                if (app != null && isolated) app.io.submit(() -> { }).get(10, TimeUnit.SECONDS);
            } finally {
                if (isolated && device != null && originalDevice != null) restore(device, originalDevice);
                if (endpoint != null) endpoint.shutdown();
                generatedFiles.addAll(platform.installedFiles);
                for (File file : generatedFiles) {
                    if (file != null && file.exists()) assertTrue("删除本次下载夹具失败", file.delete());
                }
            }
        }
    }

    @Test public void missingShellCancelAndManualDownloadDoNotForceNetworkOnResume() {
        resume(true);
        onMain(() -> {
            Dialog prompt = prompt();
            assertNotNull(prompt);
            assertTrue(prompt.isShowing());
            prompt.findViewById(android.R.id.button2).performClick();
            assertFalse(prompt.isShowing());
            controller.onResume(true);
            controller.onResume(true);
            assertFalse("取消后本次页面不能反复弹出", showing(prompt()));
            return null;
        });
        assertEquals("缺壳提示本身不能下载", 0, downloads.get());
        startDownload();
        await(() -> platform.installs.get() == 1, "手动下载应打开一次拦截安装器");
        assertEquals(1, downloads.get());
        assertEquals(0, reconnects.get());
        assertEquals(0, platform.permissions.get());
        assertDownloadedPackage();
    }

    @Test public void connectionEntryAllowsRemoteConfigurationWithoutDownloading() {
        resume(true);
        onMain(() -> {
            prompt().findViewById(android.R.id.button3).performClick();
            return null;
        });
        assertEquals(1, connections.get());
        assertEquals(0, downloads.get());
        assertEquals(0, platform.installs.get());
    }

    @Test public void failedHttpCanRetryAndWrongPackageNeverReachesInstaller() throws Exception {
        responseCode = 503;
        resume(false);
        startDownload();
        awaitFailurePrompt(1);
        assertEquals(0, platform.installs.get());
        responseCode = 200;
        try (InputStream input = new FileInputStream(app.getApplicationInfo().sourceDir)) {
            responseBody = readBytes(input);
        }
        retryPrompt();
        awaitFailurePrompt(2);
        assertEquals("其他包名的 APK 不能进入安装器", 0, platform.installs.get());
        assertEquals(0, platform.permissions.get());
        responseBody = sdkApk;
        retryPrompt();
        await(() -> platform.installs.get() == 1, "恢复正确 SDK APK 后应该允许重试");
        assertEquals(3, downloads.get());
        assertDownloadedPackage();
    }

    @Test public void permissionReturnUsesSameApkAndResumeDoesNotRepeatDownload() {
        platform.allowPackages = false;
        resume(false);
        startDownload();
        await(() -> platform.permissions.get() == 1, "下载完成应请求未知来源授权");
        assertEquals(0, platform.installs.get());
        File pending = onMain(this::rememberPending);
        assertNotNull(pending);
        onMain(() -> { controller.onPause(); return null; });
        platform.allowPackages = true;
        resume(false);
        await(() -> platform.installs.get() == 1, "授权返回后应接着安装");
        assertEquals(pending, platform.installedFiles.get(0));
        resume(false);
        resume(false);
        instrumentation.waitForIdleSync();
        assertEquals(1, downloads.get());
        assertEquals("重复恢复不能再拉起系统安装器", 1, platform.installs.get());
        assertEquals(1, platform.permissions.get());
    }

    @Test public void canceledInstallationCanRetryExistingApkAndSuccessReconnectsOnce() {
        resume(false);
        startDownload();
        await(() -> platform.installs.get() == 1, "首次下载应进入安装器");
        File apk = platform.installedFiles.get(0);
        onMain(() -> { controller.onPause(); return null; });
        resume(false);
        await(() -> onMain(() -> showing(prompt())), "安装取消后应该提供继续安装入口");
        retryPrompt();
        await(() -> platform.installs.get() == 2, "取消安装后可以再次继续安装");
        assertEquals(apk, platform.installedFiles.get(1));
        assertEquals("继续安装无需重复下载", 1, downloads.get());
        platform.installed = true;
        onMain(() -> { controller.onPause(); return null; });
        resume(false);
        resume(true);
        instrumentation.waitForIdleSync();
        assertEquals("确认 SDK 已安装后只重连一次", 1, reconnects.get());
        assertFalse(onMain(() -> showing(prompt())));
    }

    @Test public void backgroundCompletionWaitsForForegroundBeforePermissionOrInstaller() {
        responseRelease = new CountDownLatch(1);
        platform.allowPackages = false;
        resume(false);
        startDownload();
        await(() -> downloads.get() == 1, "下载请求应该已进入夹具");
        onMain(() -> { controller.onPause(); return null; });
        responseRelease.countDown();
        await(() -> onMain(() -> read(controller, "pendingApk") != null
                && read(controller, "download") == null), "后台下载应完成并保存 APK");
        assertEquals("后台不能拉起授权页", 0, platform.permissions.get());
        assertEquals("后台不能拉起安装器", 0, platform.installs.get());
        resume(false);
        await(() -> platform.permissions.get() == 1, "恢复前台后应继续授权");
        assertEquals(1, downloads.get());
    }

    @Test public void savedPendingApkSurvivesControllerRecreationWithoutRedownload() {
        platform.allowPackages = false;
        resume(false);
        startDownload();
        await(() -> platform.permissions.get() == 1, "重建前应已有完整待安装 APK");
        Bundle state = new Bundle();
        File pending = onMain(() -> {
            File file = rememberPending();
            controller.onPause();
            controller.saveState(state);
            controller.onDestroy();
            controller = null;
            return file;
        });
        scenario.recreate();
        scenario.onActivity(activity -> controller = new SdkInstallController(activity, state,
                reconnects::incrementAndGet, connections::incrementAndGet,
                endpoint.url("/morningcheck_sdk_lastest.apk").toString(), platform));
        platform.allowPackages = true;
        resume(false);
        await(() -> platform.installs.get() == 1, "重建后应使用原待安装 APK");
        assertEquals(pending, platform.installedFiles.get(0));
        assertEquals(1, downloads.get());
        assertEquals(1, platform.permissions.get());
    }

    @Test public void cancelActiveDownloadDoesNotInstallItsLateResponse() {
        responseRelease = new CountDownLatch(1);
        resume(false);
        startDownload();
        await(() -> downloads.get() == 1, "取消前应已有下载请求");
        onMain(() -> {
            Dialog progress = read(controller, "progress");
            assertNotNull(progress);
            progress.findViewById(R.id.sdk_install_cancel).performClick();
            return null;
        });
        responseRelease.countDown();
        await(() -> onMain(() -> read(controller, "download") == null), "取消下载必须解除正在下载状态");
        responseRelease = null;
        startDownload();
        await(() -> platform.installs.get() == 1, "取消后应该允许重新下载");
        assertEquals(2, downloads.get());
        assertEquals("被取消的响应不能启动第二次安装", 1, platform.installs.get());
    }

    @Test public void corruptedSavedApkIsRejectedBeforeInstallerAndCanDownloadAgain() throws Exception {
        platform.allowPackages = false;
        resume(false);
        startDownload();
        await(() -> platform.permissions.get() == 1, "破坏待包前应已完成原始下载");
        Bundle state = new Bundle();
        File pending = onMain(() -> {
            File file = rememberPending();
            controller.onPause();
            controller.saveState(state);
            controller.onDestroy();
            controller = null;
            return file;
        });
        assertNotNull(pending);
        // 模拟缓存文件被截断；只改本次夹具下载得到的文件，不触碰其他缓存。
        try (FileOutputStream output = new FileOutputStream(pending, false)) {
            output.write(new byte[]{0x50, 0x4b, 0x03, 0x04});
        }
        scenario.recreate();
        scenario.onActivity(activity -> controller = new SdkInstallController(activity, state,
                reconnects::incrementAndGet, connections::incrementAndGet,
                endpoint.url("/morningcheck_sdk_lastest.apk").toString(), platform));
        platform.allowPackages = true;
        resume(false);
        resume(false);
        await(() -> onMain(() -> read(controller, "pendingApk") == null
                && showing(prompt())), "恢复损坏待包必须先结束校验并给出重新下载入口");
        assertEquals("损坏的待包不能交给安装器", 0, platform.installs.get());
        assertEquals("坏包校验本身不能自动重新下载", 1, downloads.get());
        assertEquals("坏包不应重复打开授权页", 1, platform.permissions.get());
        assertFalse("损坏待包必须删除，避免继续使用", pending.exists());
        onMain(() -> {
            TextView action = prompt().findViewById(android.R.id.button1);
            assertEquals(app.getString(R.string.sdk_install_download), action.getText().toString());
            action.performClick();
            // 第一次点击已发起异步下载，后续主操作不能再建第二条相同请求。
            controller.downloadAndInstall();
            return null;
        });
        await(() -> platform.installs.get() == 1, "损坏待包应允许重新下载合法 SDK");
        assertEquals(2, downloads.get());
        assertEquals(0, reconnects.get());
        assertDownloadedPackage();
    }

    @Test public void privateApkProviderAndSystemIntentsKeepReadGrantWithoutLaunchingPages() throws Exception {
        resume(false);
        startDownload();
        await(() -> platform.installs.get() == 1, "元信息验证应取得下载后的 APK");
        File apk = platform.installedFiles.get(0);
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".sdk-files", apk);
        assertEquals("content", uri.getScheme());
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            assertNotNull("安装器使用的 URI 应能读取 APK", input);
            assertArrayEquals(sdkApk, readBytes(input));
        }
        Intent request = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent installer = SdkInstallIntents.explicitSystemActivity(app.getPackageManager(), request);
        assertSystemComponent(installer);
        assertEquals(request.getAction(), installer.getAction());
        assertEquals(request.getData(), installer.getData());
        assertEquals(request.getType(), installer.getType());
        assertEquals("显式选定系统组件不能丢失临时读取授权", Intent.FLAG_GRANT_READ_URI_PERMISSION,
                installer.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) {
            Intent permission = SdkInstallIntents.explicitSystemActivity(app.getPackageManager(),
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + app.getPackageName())));
            assertSystemComponent(permission);
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.getAction());
            assertEquals("package:" + app.getPackageName(), permission.getDataString());
        }
        File outside = new File(app.getCacheDir(), "sdk-install-outside.apk");
        assertThrows("安装目录外的文件不能被分享", IllegalArgumentException.class,
                () -> FileProvider.getUriForFile(app, app.getPackageName() + ".sdk-files", outside));
        assertThrows("目录穿越不能扩大私有文件授权范围", IllegalArgumentException.class,
                () -> FileProvider.getUriForFile(app, app.getPackageName() + ".sdk-files",
                        new File(apk.getParentFile(), "../sdk-install-outside.apk")));
    }

    @Test public void missingLocalShellHomeActionStartsDownloadAfterGuideWasCanceled() throws Exception {
        assumeTrue("真实首页缺壳入口只在未安装本机 SDK 的模拟器验证", !shellInstalled());
        closeSystemHost();
        homeScenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        homeScenario.onActivity(activity -> home = activity);
        await(() -> onMain(() -> (Boolean) read(home, "localSdkMissing")
                && showing(read(read(home, "sdkInstall"), "prompt"))), "本机缺壳首页应该显示安装引导");
        onMain(() -> {
            TextView action = home.findViewById(R.id.home_start);
            assertTrue("缺壳主按钮应保持可操作", action.isEnabled());
            assertEquals("→  立即安装", action.getText().toString());
            return null;
        });
        assertInstallDialogGeometry(onMain(() -> read(read(home, "sdkInstall"), "prompt")), false);
        screenshotIfRequested("home-install-prompt");
        onMain(() -> {
            SdkInstallController original = read(home, "sdkInstall");
            Dialog guide = read(original, "prompt");
            guide.findViewById(android.R.id.button2).performClick();
            assertFalse("取消安装引导必须关闭原提示", guide.isShowing());
            original.onDestroy();
            controller = new SdkInstallController(home, null,
                    reconnects::incrementAndGet, connections::incrementAndGet,
                    endpoint.url("/morningcheck_sdk_lastest.apk").toString(), platform);
            write(home, "sdkInstall", controller);
            controller.onResume(false);
            return null;
        });
        screenshotIfRequested("home-local-missing");
        responseRelease = new CountDownLatch(1);
        onMain(() -> {
            assertTrue("真正点击首页主按钮应被处理", home.findViewById(R.id.home_start).performClick());
            return null;
        });
        await(() -> downloads.get() == 1 && onMain(() -> showing(read(controller, "progress"))),
                "取消引导后主按钮仍应启动下载并显示真实进度弹窗");
        assertInstallDialogGeometry(onMain(() -> read(controller, "progress")), true);
        screenshotIfRequested("home-install-progress");
        responseRelease.countDown();
        await(() -> platform.installs.get() == 1, "首页下载完成应该进入拦截安装器");
        assertEquals(1, downloads.get());
        assertEquals(0, reconnects.get());
        assertEquals(0, platform.permissions.get());
        assertDownloadedPackage();
    }

    @Test public void remoteWsHomeDoesNotOfferLocalShellInstallation() {
        assumeTrue("远端连接场景需要本机确实未安装 SDK", !shellInstalled());
        closeSystemHost();
        assertTrue(device.edit().putString(MorningSdkSettings.TRANSPORT_KEY, "ws")
                .putString(MorningSdkSettings.ENDPOINT_KEY, "ws://192.0.2.1:18087/sdk").commit());
        onMain(() -> {
            app.sdkSession().disconnect();
            // 仅让进程会话匹配示例配置；connect() 因配置相同直接返回，避免实际访问外部示例地址。
            write(app.sdkSession(), "config", MorningSdkSettings.read(device));
            return null;
        });
        homeScenario = ActivityScenario.launch(new Intent(app, HomeActivity.class));
        homeScenario.onActivity(activity -> home = activity);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            assertFalse("远端 WS 不依赖本机是否安装 SDK", (Boolean) read(home, "localSdkMissing"));
            SdkInstallController original = read(home, "sdkInstall");
            assertFalse("远端 WS 首页不能自动弹出本机安装提示", showing(read(original, "prompt")));
            assertFalse(showing(read(original, "progress")));
            assertFalse("远端连接主操作不能误报安装 SDK", app.getString(R.string.sdk_install_guide_title)
                    .equals(((TextView) home.findViewById(R.id.home_start)).getText().toString()));
            assertFalse(app.sdkSession().client().isConnected());
            return null;
        });
        assertEquals(0, downloads.get());
        assertEquals(0, platform.installs.get());
        assertEquals(0, platform.permissions.get());
    }

    @Test public void multipleMissingObserversNotifyInstallOnceUntilShellReallyBecomesMissingAgain() {
        AtomicReference<SdkInstallController> peer = new AtomicReference<>();
        scenario.onActivity(activity -> {
            SdkInstallController observer = new SdkInstallController(activity, null,
                    reconnects::incrementAndGet, connections::incrementAndGet,
                    endpoint.url("/morningcheck_sdk_lastest.apk").toString(), platform);
            peerControllers.add(observer);
            peer.set(observer);
        });
        onMain(() -> {
            controller.onResume(false);
            peer.get().onResume(false);
            return null;
        });
        assertEquals(0, reconnects.get());
        platform.installed = true;
        onMain(() -> {
            controller.onResume(false);
            peer.get().onResume(false);
            controller.onResume(false);
            peer.get().onResume(false);
            return null;
        });
        assertEquals("多个缺壳观察者看到同次安装时只能通知一次重连", 1, reconnects.get());
        platform.installed = false;
        onMain(() -> {
            peer.get().onResume(false);
            controller.onResume(false);
            return null;
        });
        platform.installed = true;
        onMain(() -> {
            // 下一次实际缺失→安装也必须通知；交换首个观察者，避免只测实例内去重。
            peer.get().onResume(false);
            controller.onResume(false);
            return null;
        });
        assertEquals("SDK 再次确实缺失后安装，应允许产生新一次重连通知", 2, reconnects.get());
        assertEquals(0, downloads.get());
        assertEquals(0, platform.installs.get());
        assertEquals(0, platform.permissions.get());
    }

    private void closeSystemHost() {
        onMain(() -> {
            rememberPending();
            if (controller != null) controller.onDestroy();
            controller = null;
            return null;
        });
        if (scenario != null) scenario.close();
        scenario = null;
        instrumentation.waitForIdleSync();
        onMain(() -> { app.sdkSession().disconnect(); return null; });
    }

    private boolean shellInstalled() {
        try {
            app.getPackageManager().getApplicationInfo("cc.huoyan.morningcheck", 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }

    private void screenshotIfRequested(String label) throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("sdkInstallScreenshots"))) return;
        settleWindowFrames();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("截图应取得实际设备画面", image);
        File directory = new File(app.getExternalFilesDir(null), "sdk-install-qa");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String orientation = image.getWidth() > image.getHeight() ? "landscape" : "portrait";
        try (FileOutputStream stream = new FileOutputStream(new File(directory, label + "-" + orientation + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }

    private void assertInstallDialogGeometry(Dialog dialog, boolean progressDialog) throws Exception {
        assertNotNull("应该存在待验收的安装弹窗", dialog);
        await(() -> onMain(() -> {
            if (!showing(dialog) || dialog.getWindow() == null) return false;
            View decor = dialog.getWindow().getDecorView();
            return decor.isAttachedToWindow() && decor.getWidth() > 0 && decor.getHeight() > 0
                    && textLayoutsReady(decor);
        }), "安装弹窗应完成实际窗口测量和文字排版");
        settleWindowFrames();
        onMain(() -> {
            View activityDecor = home.getWindow().getDecorView();
            View dialogDecor = dialog.getWindow().getDecorView();
            Rect screen = screenBounds(activityDecor), bounds = screenBounds(dialogDecor);
            assertTrue("安装弹窗宽度不能退化到 1px：" + bounds,
                    bounds.width() >= Math.min(screen.width(), screen.height()) / 2);
            assertTrue("整个安装弹窗必须位于当前设备窗口内：弹窗=" + bounds + "，设备=" + screen,
                    screen.contains(bounds));
            View body = dialog.findViewById(android.R.id.content);
            assertNotNull("安装弹窗应有正文容器", body);
            assertContainedAndVisible(body, screen, bounds);
            assertContainedAndVisible(dialog.findViewById(progressDialog ? R.id.sdk_install_cancel : android.R.id.button2), screen, bounds);
            if (progressDialog) {
                assertContainedAndVisible(dialog.findViewById(R.id.sdk_install_progress), screen, bounds);
                assertContainedAndVisible(dialog.findViewById(R.id.sdk_install_progress_text), screen, bounds);
            } else {
                assertContainedAndVisible(dialog.findViewById(R.id.confirmation_message), screen, bounds);
                assertContainedAndVisible(dialog.findViewById(android.R.id.button1), screen, bounds);
                assertContainedAndVisible(dialog.findViewById(android.R.id.button3), screen, bounds);
            }
            assertTextsFit(body, screen, bounds);
            return null;
        });
    }

    private void settleWindowFrames() throws Exception {
        instrumentation.waitForIdleSync();
        // Accessibility 空闲和绘制帧分别收尾，避免截图仍落在旧窗口的退出/变暗动画中。
        instrumentation.getUiAutomation().waitForIdle(250, 3_000);
        CountDownLatch frames = new CountDownLatch(1);
        onMain(() -> {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                private int remaining = 3;
                @Override public void doFrame(long frameTimeNanos) {
                    if (--remaining == 0) frames.countDown();
                    else Choreographer.getInstance().postFrameCallback(this);
                }
            });
            return null;
        });
        assertTrue("截图前设备应完成三帧绘制", frames.await(2, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
    }

    private static boolean textLayoutsReady(View view) {
        if (!view.isShown()) return true;
        if (view instanceof TextView && ((TextView) view).getText().length() > 0
                && ((TextView) view).getLayout() == null) return false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                if (!textLayoutsReady(group.getChildAt(index))) return false;
        }
        return true;
    }

    private static Rect screenBounds(View view) {
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }

    private static void assertContainedAndVisible(View view, Rect screen, Rect dialog) {
        assertNotNull("安装弹窗应保留所有正文和操作", view);
        Rect bounds = screenBounds(view), visible = new Rect();
        assertTrue("安装控件应有可见尺寸：" + bounds, bounds.width() > 0 && bounds.height() > 0);
        assertTrue("安装控件必须保持可见", view.isShown() && view.getGlobalVisibleRect(visible));
        int[] rootOrigin = new int[2];
        view.getRootView().getLocationOnScreen(rootOrigin);
        visible.offset(rootOrigin[0], rootOrigin[1]);
        assertEquals("安装控件不能被祖先容器裁切", bounds, visible);
        assertTrue("安装控件不能超出当前屏幕：" + bounds, screen.contains(bounds));
        assertTrue("安装控件不能超出弹窗边界：" + bounds, dialog.contains(bounds));
    }

    private static void assertTextsFit(View view, Rect screen, Rect dialog) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertContainedAndVisible(text, screen, dialog);
            if (text.getText().length() > 0) {
                Layout layout = text.getLayout();
                assertNotNull("弹窗文字必须已完成排版", layout);
                int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
                int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                assertTrue("弹窗文字行不能在垂直方向被截断：" + text.getText(), layout.getHeight() <= availableHeight + 1);
                assertTrue(layout.getLineCount() > 0);
                assertEquals("弹窗必须显示完整正文和按钮文案", text.getText().length(),
                        layout.getLineEnd(layout.getLineCount() - 1));
                for (int line = 0; line < layout.getLineCount(); line++) {
                    assertEquals("弹窗文字不应省略：" + text.getText(), 0, layout.getEllipsisCount(line));
                    assertTrue("弹窗文字行不能横向裁切：" + text.getText(),
                            layout.getLineWidth(line) <= availableWidth + 2);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                assertTextsFit(group.getChildAt(index), screen, dialog);
        }
    }

    private void resume(boolean promptIfMissing) {
        onMain(() -> { controller.onResume(promptIfMissing); return null; });
    }

    private void startDownload() {
        onMain(() -> { controller.downloadAndInstall(); return null; });
    }

    private void retryPrompt() {
        onMain(() -> {
            Dialog prompt = prompt();
            assertTrue("错误或取消后应显示重试提示", showing(prompt));
            prompt.findViewById(android.R.id.button1).performClick();
            return null;
        });
    }

    private void awaitFailurePrompt(int expectedDownloads) {
        await(() -> downloads.get() == expectedDownloads && onMain(() ->
                read(controller, "download") == null && showing(prompt())), "失败下载应显示可重试提示");
    }

    private void assertDownloadedPackage() {
        File apk = platform.installedFiles.get(0);
        assertTrue(apk.isFile());
        assertEquals("cc.huoyan.morningcheck",
                app.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0).packageName);
    }

    private void assertSystemComponent(Intent intent) {
        assertNotNull("系统入口应已转成显式组件", intent.getComponent());
        ResolveInfo resolved = app.getPackageManager().resolveActivity(intent, 0);
        assertNotNull("设备上的系统入口必须可解析", resolved);
        assertNotNull(resolved.activityInfo);
        assertTrue(resolved.activityInfo.exported);
        assertTrue(resolved.activityInfo.enabled);
        assertTrue("入口必须来自系统应用", (resolved.activityInfo.applicationInfo.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
    }

    private Dialog prompt() { return controller == null ? null : read(controller, "prompt"); }
    private static boolean showing(Dialog dialog) { return dialog != null && dialog.isShowing(); }

    private File rememberPending() {
        File file = controller == null ? null : read(controller, "pendingApk");
        if (file != null) generatedFiles.add(file);
        return file;
    }

    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("主线程测试操作失败", failure.get());
        return value.get();
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertTrue(message, condition.getAsBoolean());
    }

    @SuppressWarnings("unchecked") private static <T> T read(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError("读取安装流程状态失败", error); }
    }

    private static void write(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError("注入安装流程夹具失败", error); }
    }

    private static byte[] readBytes(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        return output.toByteArray();
    }

    private static void restore(SharedPreferences preferences, Map<String, Object> state) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, Object> entry : state.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                editor.putStringSet(entry.getKey(), new HashSet<>(values));
            } else throw new AssertionError("未知偏好类型");
        }
        assertTrue("恢复测试前设置失败", editor.commit());
    }

    private static void finishPages() {
        for (Stage stage : Stage.values()) {
            if (stage == Stage.DESTROYED) continue;
            for (Activity activity : new ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(stage))) activity.finish();
        }
    }

    private final class FakePlatform implements SdkInstallController.Platform {
        final AtomicInteger installs = new AtomicInteger();
        final AtomicInteger permissions = new AtomicInteger();
        final java.util.concurrent.CopyOnWriteArrayList<File> installedFiles =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean installed;
        volatile boolean allowPackages = true;

        @Override public boolean isInstalled() { return installed; }
        @Override public boolean canInstallPackages() { return allowPackages; }
        @Override public void openPermission() { permissions.incrementAndGet(); }
        @Override public void install(File apk) {
            installedFiles.add(apk);
            installs.incrementAndGet();
        }
    }
}
