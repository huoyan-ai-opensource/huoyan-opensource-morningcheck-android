package cc.huoyan.opensource.morningcheck.sdk;

import org.json.JSONObject;

import java.math.BigInteger;

/**
 * 将 SDK 的当前事实转换为操作员提示；仅用于展示，不代替壳内的逐命令准入。
 */
public final class MorningSdkState {
    public enum Phase {
        DISCONNECTED, STATUS_UNKNOWN, NOT_INIT, INITIALIZING, REGISTERED, NOT_ACTIVATED,
        CERTIFICATE_EXPIRED, CERTIFICATE_INVALID, MODEL_PREPARING, MODEL_NOT_READY,
        READY, OFFLINE, BUSY, KEY_ROTATION_PENDING, KEY_ROTATION_REJECTED, KEY_ROTATION_RATE_LIMITED,
        KEY_ROTATION_RESTORING, KEY_LOST, DISABLED, CONFIGURATION_PENDING,
        BACKEND_UNAVAILABLE, STARTUP_FAILED, STOPPED
    }

    public final Phase phase;
    public final String title;
    public final String detail;
    /**
     * 仅表示推理和人员登记所需的授权、模型事实齐备；维护功能不能用此值统一禁用。
     */
    public final boolean ready;
    public final boolean needsActivation;
    /**
     * 表示 SDK 正在初始化、准备模型或等待身份恢复；不等于客户端应自动重试。
     */
    public final boolean recovering;
    /**
     * 下载总量未确定、字段非法或当前不在准备流程时为 -1。
     */
    public final int downloadPercent;

    private MorningSdkState(Phase phase, String title, String detail, boolean ready,
                            boolean needsActivation, boolean recovering, int downloadPercent) {
        this.phase = phase;
        this.title = title;
        this.detail = detail;
        this.ready = ready;
        this.needsActivation = needsActivation;
        this.recovering = recovering;
        this.downloadPercent = downloadPercent;
    }

    public static MorningSdkState from(boolean connected, JSONObject status, JSONObject download) {
        if (!connected)
            return state(Phase.DISCONNECTED, "SDK 未连接", "正在连接晨检服务，请检查 SDK 壳和连接设置。");
        String name = text(status, "sdk_state");
        if ("STOPPED".equals(name))
            return state(Phase.STOPPED, "SDK 已停止", "请重新连接晨检服务并初始化。");
        if ("DISABLED".equals(name))
            return state(Phase.DISABLED, "设备已禁用", "请联系管理员核对设备状态；查询与初始化复核仍可使用。");
        // 轮换阶段优先于附带的旧私钥事实，避免把待审、拒绝和限频统一显示成私钥丢失。
        switch (name) {
            case "KEY_ROTATION_PENDING":
                return new MorningSdkState(Phase.KEY_ROTATION_PENDING, "设备身份待审核",
                        "密钥轮换申请正在等待审核，晨检暂不可用；可刷新查看结果。", false, false, true, -1);
            case "KEY_ROTATION_REJECTED":
                return state(Phase.KEY_ROTATION_REJECTED, "设备身份审核未通过", "请联系管理员核对原因，处理后再初始化复核。");
            case "KEY_ROTATION_RATE_LIMITED":
                return state(Phase.KEY_ROTATION_RATE_LIMITED, "身份恢复请求过于频繁", "请稍后再初始化复核；当前服务未提供准确等待时长。");
            case "KEY_ROTATION_RESTORING":
                return new MorningSdkState(Phase.KEY_ROTATION_RESTORING, "正在恢复设备身份",
                        "审核已通过，正在恢复证书和模型；完成前请稍候。", false, false, true, -1);
            default:
                break;
        }
        if (isTrue(status, "key_lost")) {
            return state(Phase.KEY_LOST, "设备身份需要恢复", "设备私钥不可用，请初始化恢复身份；恢复完成前晨检暂不可用。");
        }
        int percent = percent(download);
        switch (name) {
            case "NOT_INIT":
                return state(Phase.NOT_INIT, "等待初始化", "已连接晨检服务，等待初始化结果。");
            case "INITIALIZING":
                return new MorningSdkState(Phase.INITIALIZING, "SDK 正在初始化", preparingDetail(percent), false, false, true, percent);
            case "REGISTERED":
                return new MorningSdkState(Phase.REGISTERED, "设备已注册", "正在核对证书与模型，请等待初始化结果。", false, false, true, -1);
            case "NOT_ACTIVATED":
                return new MorningSdkState(Phase.NOT_ACTIVATED, "SDK 待激活", "请在设备授权中输入激活码；门店登录与设备激活分别完成。", false, true, false, -1);
            case "CERTIFICATE_EXPIRED":
                return new MorningSdkState(Phase.CERTIFICATE_EXPIRED, "SDK 授权已到期",
                        "请重新激活；身份识别、头手检测和人员登记暂停，人员维护与其他功能仍可使用。", false, true, false, -1);
            case "CERTIFICATE_INVALID":
                return new MorningSdkState(Phase.CERTIFICATE_INVALID, "SDK 授权校验未通过", "请核对设备授权，重新激活后再查看状态。", false, true, false, -1);
            case "MODEL_PREPARING":
                return new MorningSdkState(Phase.MODEL_PREPARING, percent >= 0 && percent < 100 ? "正在下载晨检模型" : "正在准备晨检模型",
                        preparingDetail(percent), false, false, true, percent);
            case "MODEL_NOT_READY":
                return state(Phase.MODEL_NOT_READY, "晨检模型未就绪", "请检查网络和存储空间，再初始化重试；授权成功后仍需模型加载完成。");
            case "BACKEND_UNAVAILABLE":
                return state(Phase.BACKEND_UNAVAILABLE, "初始化所需网络不可用", "请检查网络与 SDK 云地址，恢复后初始化重试。");
            case "STARTUP_FAILED":
                return state(Phase.STARTUP_FAILED, "SDK 初始化未完成", "请查看设备授权及权限提示，处理后初始化重试。");
            case "READY":
            case "OFFLINE":
                return readyState(status);
            default:
                return state(Phase.STATUS_UNKNOWN, "正在确认 SDK 状态", "尚未取得完整的设备状态，暂不可开始晨检；可刷新查看结果。");
        }
    }

    /**
     * 配置保存失败没有独立的 wire 状态。由调用方根据本次写操作的明确回执叠加提示，
     * 不解析 recent_error 中文，也不把旧配置告警推断为授权失效。
     */
    public MorningSdkState withConfigurationPending(String operation) {
        if (phase == Phase.DISCONNECTED || phase == Phase.STOPPED || phase == Phase.DISABLED
                || phase == Phase.KEY_LOST || phase.name().startsWith("KEY_ROTATION_")) return this;
        String guidance = "setBaseUrl".equals(operation)
                ? "SDK 云地址尚未确认保存，请重新提交同一地址；完成前初始化、激活和门店绑定可能受限。"
                : "设备配置尚未确认保存，请在设备授权中核对本次操作结果，再按提示重试。";
        return new MorningSdkState(Phase.CONFIGURATION_PENDING, "设备配置待保存", guidance,
                ready, needsActivation, false, downloadPercent);
    }

    /**
     * 激活尚未收到终态回执时，旧 READY 快照不能开放晨检。
     */
    public MorningSdkState withActivationProgress(String message, int percent, boolean downloading) {
        return new MorningSdkState(downloading ? Phase.MODEL_PREPARING : Phase.INITIALIZING,
                downloading ? "正在准备晨检模型" : "正在完成激活授权", message,
                false, false, true, percent);
    }

    private static MorningSdkState readyState(JSONObject status) {
        // JSON null 表示证据未知；READY 和 OFFLINE 都不能替代模型和证书事实。
        if (!isTrue(status, "certificate_valid")) {
            return state(Phase.STATUS_UNKNOWN, "正在确认 SDK 授权",
                    Boolean.FALSE.equals(value(status, "certificate_valid"))
                            ? "授权校验未通过，暂不可开始晨检；请查看设备授权并刷新状态。"
                            : "尚未取得有效的授权状态，暂不可开始晨检；请刷新设备状态。");
        }
        if (!isTrue(status, "model_ready")) {
            return Boolean.FALSE.equals(value(status, "model_ready"))
                    ? state(Phase.MODEL_NOT_READY, "晨检模型未就绪", "模型尚未加载完成，请稍后刷新或初始化重试。")
                    : state(Phase.STATUS_UNKNOWN, "正在确认晨检模型", "尚未取得模型就绪状态，暂不可开始晨检；请刷新设备状态。");
        }
        if (isTrue(status, "busy")) {
            return new MorningSdkState(Phase.BUSY, "SDK 正在处理任务",
                    "授权与模型已就绪，当前正在" + operationLabel(text(status, "busy_operation")) + "；请稍候。", true, false, false, -1);
        }
        if ("OFFLINE".equals(text(status, "sdk_state"))) {
            return new MorningSdkState(Phase.OFFLINE, "SDK 离线可用",
                    "授权与模型已就绪，可进行本地晨检；云端功能暂不可用，恢复后自动更新状态。", true, false, false, -1);
        }
        String detail = Boolean.FALSE.equals(value(status, "backend_connected"))
                ? "授权与模型已就绪，可进行本地晨检；云端当前未连接。" : "授权与模型已就绪，可以开始晨检。";
        return new MorningSdkState(Phase.READY, "SDK 已就绪", detail, true, false, false, -1);
    }

    private static MorningSdkState state(Phase phase, String title, String detail) {
        return new MorningSdkState(phase, title, detail, false, false, false, -1);
    }

    private static String preparingDetail(int percent) {
        if (percent == 100) return "模型文件已下载，正在完成加载；设备就绪后即可开始晨检。";
        if (percent >= 0) return "晨检模型已下载 " + percent + "% ，请保持网络连接并等待加载完成。";
        return "正在准备证书与模型，下载总量尚未确定，请稍候。";
    }

    static int percent(JSONObject download) {
        Object downloaded = value(download, "downloaded"), total = value(download, "total");
        if (!integer(downloaded) || !integer(total)) return -1;
        long bytes = ((Number) downloaded).longValue(), all = ((Number) total).longValue();
        if (bytes < 0 || all <= 0 || bytes > all) return -1;
        // 用整数比例避免大字节数乘 100 溢出或浮点舍入把未完成误报为 100%。
        return BigInteger.valueOf(bytes).multiply(BigInteger.valueOf(100))
                .divide(BigInteger.valueOf(all)).intValue();
    }

    private static boolean integer(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static boolean isTrue(JSONObject source, String key) {
        return Boolean.TRUE.equals(value(source, key));
    }

    private static Object value(JSONObject source, String key) {
        return source == null ? null : source.opt(key);
    }

    private static String text(JSONObject source, String key) {
        Object value = value(source, key);
        return value instanceof String ? (String) value : "";
    }

    private static String operationLabel(String operation) {
        switch (operation) {
            case "init":
                return "初始化";
            case "activate":
                return "激活授权";
            case "renewCert":
                return "续期授权";
            case "bindDevice":
                return "绑定门店";
            case "setBaseUrl":
                return "更新云地址";
            case "register":
                return "登记人员";
            case "forget":
            case "forgetByUid":
                return "维护人员底库";
            case "identify":
                return "核对人员身份";
            case "checkHead":
                return "检查头部";
            case "checkHand":
                return "检查手部";
            default:
                return "处理设备任务";
        }
    }
}
