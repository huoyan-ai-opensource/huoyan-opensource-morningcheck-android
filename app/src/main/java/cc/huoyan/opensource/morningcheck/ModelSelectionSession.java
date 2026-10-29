package cc.huoyan.opensource.morningcheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;

/**
 * 激活和切换共用选模会话；状态在主线程访问，重建页面不重发查询或写操作。
 */
final class ModelSelectionSession {
    enum Stage { IDLE, INPUT, QUERYING, CHOOSING, ACTIVATING, FINISHED }

    interface Data {
        long generation();

        void query(String activationCode, boolean switching, HuoyanClient.Callback callback);

        void certificate(HuoyanClient.Callback callback);

        boolean activate(String activationCode, String modelCode, HuoyanClient.Callback callback);
    }

    static final class Model {
        final String code, name;
        final boolean current;

        Model(String code, String name, boolean current) {
            this.code = code;
            this.name = name;
            this.current = current;
        }
    }

    private final Data data;
    private Runnable observer;
    private long request, connection;
    private String activationCode, selectedModelCode;
    private boolean destroyed;
    Stage stage = Stage.IDLE;
    List<Model> models = Collections.emptyList();
    boolean switching, submitted, needsActivationCode;
    int resultCode;
    String resultMessage = "";

    ModelSelectionSession(Data data) {
        this.data = data;
    }

    void attach(Runnable observer) {
        if (destroyed) return;
        this.observer = observer;
        changed();
    }

    void detach() {
        observer = null;
    }

    void open(boolean switching) {
        if (destroyed || stage != Stage.IDLE) return;
        this.switching = switching;
        submitted = false;
        needsActivationCode = false;
        clearResult();
        models = Collections.emptyList();
        activationCode = null;
        selectedModelCode = null;
        if (switching) query(null);
        else {
            stage = Stage.INPUT;
            changed();
        }
    }

    void input(String code) {
        if (destroyed || stage != Stage.INPUT) return;
        if (switching && !validateConnection()) return;
        if (!nonBlankString(code)) {
            finish(HuoyanClient.ERROR_INVALID_REQUEST, "请输入有效激活码");
            return;
        }
        // 手输弹窗负责十六位分组，扫码得到的授权序列号只去首尾空白。
        if (switching && needsActivationCode) {
            activationCode = code.trim();
            activateSelected();
        } else query(code.trim());
    }

    private void query(String code) {
        activationCode = code;
        connection = data.generation();
        long currentRequest = ++request;
        stage = Stage.QUERYING;
        changed();
        if (!current(currentRequest, Stage.QUERYING)) return;
        try {
            data.query(code, switching, (result, reason, payload) -> {
                if (!current(currentRequest, Stage.QUERYING)) return;
                if (result != 0) {
                    finish(result, message(reason, "读取模型列表失败（" + result + "）"));
                    return;
                }
                JSONArray values = payload == null ? null : payload.optJSONArray("models");
                if (values == null) {
                    finish(HuoyanClient.ERROR_INVALID_REQUEST, "模型列表信息不完整，请重试");
                    return;
                }
                if (values.length() == 0) {
                    finish(HuoyanClient.ERROR_INVALID_REQUEST, "暂无可选模型");
                    return;
                }
                List<Model> options = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.optJSONObject(i);
                    if (value == null || !nonBlankString(value.opt("model_code"))
                            || !nonBlankString(value.opt("model_name"))) {
                        finish(HuoyanClient.ERROR_INVALID_REQUEST, "模型列表信息不完整，请重试");
                        return;
                    }
                    options.add(new Model(value.optString("model_code"), value.optString("model_name"),
                            Boolean.TRUE.equals(value.opt("current"))));
                }
                models = Collections.unmodifiableList(options);
                if (switching) readCertificate();
                else choose();
            });
        } catch (RuntimeException failure) {
            if (current(currentRequest, Stage.QUERYING))
                finish(HuoyanClient.ERROR_INVALID_REQUEST, "读取模型列表失败，请重试");
        }
    }

    private void readCertificate() {
        long currentRequest = ++request;
        try {
            data.certificate((result, reason, payload) -> {
                if (!current(currentRequest, Stage.QUERYING)) return;
                if (result != 0) {
                    finish(result, message(reason, "读取激活信息失败（" + result + "）"));
                    return;
                }
                if (payload == null) {
                    finish(HuoyanClient.ERROR_INVALID_REQUEST, "激活信息不完整，请重试");
                    return;
                }
                Object serial = payload.opt("activation_serial");
                if (serial == null || serial == JSONObject.NULL
                        || serial instanceof String && ((String) serial).trim().isEmpty()) {
                    // 轮换可恢复有效证书，但不会补回壳本地保存的激活码；缺码不代表未激活。
                    needsActivationCode = true;
                    choose();
                    return;
                }
                if (!(serial instanceof String)) {
                    finish(HuoyanClient.ERROR_INVALID_REQUEST, "激活信息不完整，请重试");
                    return;
                }
                // 优先使用 SDK 返回的激活码；缺失时由操作员补充，不自动采用历史草稿。
                activationCode = ((String) serial).trim();
                choose();
            });
        } catch (RuntimeException failure) {
            if (current(currentRequest, Stage.QUERYING))
                finish(HuoyanClient.ERROR_INVALID_REQUEST, "读取激活信息失败，请重试");
        }
    }

    private void choose() {
        stage = Stage.CHOOSING;
        changed();
    }

    boolean select(int position) {
        if (destroyed || stage != Stage.CHOOSING || position < 0 || position >= models.size()) return false;
        if (!validateConnection()) return false;
        Model model = models.get(position);
        if (switching && model.current) {
            reset(Stage.IDLE);
            changed();
            return true;
        }
        selectedModelCode = model.code;
        if (switching && needsActivationCode) {
            stage = Stage.INPUT;
            changed();
            return true;
        }
        return activateSelected();
    }

    private boolean activateSelected() {
        String code = activationCode;
        String modelCode = selectedModelCode;
        long currentRequest = ++request;
        submitted = true;
        stage = Stage.ACTIVATING;
        changed();
        if (!current(currentRequest, Stage.ACTIVATING)) return false;
        try {
            boolean accepted = data.activate(code, modelCode, (result, reason, payload) -> {
                if (!current(currentRequest, Stage.ACTIVATING)) return;
                finish(result, message(reason, result == 0
                        ? switching ? "模型切换成功" : "设备激活成功"
                        : "操作失败（" + result + "），请查看当前模型后再操作"));
            });
            if (!accepted && current(currentRequest, Stage.ACTIVATING))
                finish(HuoyanClient.ERROR_BUSY, "当前无法激活，请确认连接并等待正在处理的操作结束");
            return accepted;
        } catch (RuntimeException failure) {
            if (current(currentRequest, Stage.ACTIVATING))
                finish(HuoyanClient.ERROR_RESULT_UNKNOWN, "操作结果未知，请查看当前模型后再操作");
            return false;
        }
    }

    boolean validateConnection() {
        if (destroyed) return false;
        if ((stage == Stage.QUERYING || stage == Stage.CHOOSING || stage == Stage.ACTIVATING
                || switching && needsActivationCode && stage == Stage.INPUT)
                && connection != data.generation()) {
            needsActivationCode = false;
            finish(submitted ? HuoyanClient.ERROR_RESULT_UNKNOWN : HuoyanClient.ERROR_NOT_CONNECTED,
                    submitted ? "连接已变更，操作结果未知，请查看当前模型后再操作" : "连接已变更，请重新选择模型");
            return false;
        }
        return true;
    }

    void cancel() {
        if (destroyed) return;
        if (stage == Stage.QUERYING || stage == Stage.CHOOSING)
            reset(switching ? Stage.IDLE : Stage.INPUT);
        else if (stage == Stage.INPUT) reset(Stage.IDLE);
        else return;
        changed();
    }

    void consumed() {
        if (destroyed || stage != Stage.FINISHED) return;
        reset(!submitted && !switching ? Stage.INPUT : Stage.IDLE);
        changed();
    }

    void destroy() {
        detach();
        destroyed = true;
        reset(Stage.IDLE);
    }

    private boolean current(long expectedRequest, Stage expectedStage) {
        return !destroyed && expectedRequest == request && stage == expectedStage && validateConnection();
    }

    private void finish(int code, String reason) {
        request++;
        activationCode = null;
        selectedModelCode = null;
        resultCode = code;
        resultMessage = reason;
        stage = Stage.FINISHED;
        changed();
    }

    private void reset(Stage next) {
        request++;
        stage = next;
        models = Collections.emptyList();
        activationCode = null;
        selectedModelCode = null;
        if (next == Stage.IDLE) needsActivationCode = false;
        clearResult();
    }

    private void clearResult() {
        resultCode = 0;
        resultMessage = "";
    }

    private void changed() {
        if (observer != null) observer.run();
    }

    private static String message(String reason, String fallback) {
        return nonBlankString(reason) ? reason : fallback;
    }

    private static boolean nonBlankString(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }
}
