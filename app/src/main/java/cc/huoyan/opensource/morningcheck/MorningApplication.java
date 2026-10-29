package cc.huoyan.opensource.morningcheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cc.huoyan.opensource.morningcheck.data.AppStore;
import cc.huoyan.opensource.morningcheck.domain.FaceOperations;
import cc.huoyan.opensource.morningcheck.hardware.LightConfig;
import cc.huoyan.opensource.morningcheck.login.ShopLoginCoordinator;
import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;

/**
 * 进程内共用存储队列和页面数据变更通知。
 */
public final class MorningApplication extends android.app.Application {
    AppStore store;
    final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "晨检-业务存储"));
    // 云端慢请求不能占用本地保存队列，否则下一位已完成的晨检迟迟不能落盘。
    final ExecutorService networkIo = Executors.newSingleThreadExecutor(r -> new Thread(r, "晨检-业务网络"));
    final FaceOperations faceOperations = new FaceOperations();
    private final Set<Runnable> staffChangeListeners = new HashSet<>();
    private final Set<Runnable> recordChangeListeners = new HashSet<>();
    private final Set<Runnable> departmentChangeListeners = new HashSet<>();
    private ShopLoginCoordinator shopLogin;
    private MorningSdkSession sdkSession;

    // 主线程通知前台员工页；学习页退出后到达的回执也必须及时刷新列表。
    void addStaffChangeListener(Runnable listener) {
        staffChangeListeners.add(listener);
    }

    void removeStaffChangeListener(Runnable listener) {
        staffChangeListeners.remove(listener);
    }

    void notifyStaffChanged() {
        for (Runnable listener : new ArrayList<>(staffChangeListeners)) listener.run();
        notifyDepartmentsChanged();
    }

    void addDepartmentChangeListener(Runnable listener) {
        departmentChangeListeners.add(listener);
    }

    void removeDepartmentChangeListener(Runnable listener) {
        departmentChangeListeners.remove(listener);
    }

    void notifyDepartmentsChanged() {
        for (Runnable listener : new ArrayList<>(departmentChangeListeners)) listener.run();
    }

    // 写入归进程所有；原结果页退出后，前台列表仍需按自己的筛选条件刷新。
    void addRecordChangeListener(Runnable listener) {
        recordChangeListeners.add(listener);
    }

    void removeRecordChangeListener(Runnable listener) {
        recordChangeListeners.remove(listener);
    }

    void notifyRecordsChanged() {
        for (Runnable listener : new ArrayList<>(recordChangeListeners)) listener.run();
    }

    /**
     * 所有页面与门店登录复用同一通道，切页不重跑 SDK 初始化。
     */
    public synchronized MorningSdkSession sdkSession() {
        if (sdkSession == null) sdkSession = new MorningSdkSession(this);
        return sdkSession;
    }

    /**
     * 已提交的门店绑定随进程收尾，页面退出只解除界面观察。
     */
    public synchronized ShopLoginCoordinator shopLogin() {
        if (shopLogin == null) shopLogin = new ShopLoginCoordinator(this);
        return shopLogin;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LightConfig.restoreDefaultCommandsOnce(getSharedPreferences("device", MODE_PRIVATE));
        store = new AppStore(this);
        // 只在进程启动时恢复中断状态，页面切换不能把仍在上传的记录误判为中断。
        io.execute(() -> {
            try {
                store.recoverInterruptedUploads();
            } catch (Exception error) {
                android.util.Log.e("晨检存储", "恢复上传状态失败", error);
            }
        });
    }
}
