package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 晨检壳 WS v2 的线格式与预算；不包含业务状态推断。
 */
final class MorningWsProtocol {
    static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

    private MorningWsProtocol() {
    }

    static String command(String command) {
        // 当前晨检壳的类别表是唯一保留蛇形名称的追加命令。
        return "getClassTable".equals(command) ? "get_class_table" : command;
    }

    static boolean requiresImage(String command) {
        return "identify".equals(command) || "detectFace".equals(command) || "checkHead".equals(command)
                || "checkHand".equals(command) || "register".equals(command)
                || "uploadPhoto".equals(command);
    }

    static String text(String id, String command, JSONObject params) throws JSONException {
        return new JSONObject().put("requestId", id).put("cmd", command)
                .put("params", params == null ? new JSONObject() : params).toString();
    }

    static byte[] binary(String id, byte[] image) {
        byte[] encoded = id.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > 4096) {
            throw new IllegalArgumentException("请求标识长度非法");
        }
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片必须非空且不超过 32 MiB");
        }
        return ByteBuffer.allocate(4 + encoded.length + image.length)
                .putInt(encoded.length).put(encoded).put(image).array();
    }

    static void validate(String command, JSONObject params, byte[] image) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("命令不能为空");
        }
        boolean required = requiresImage(command);
        if (!required && image != null) {
            throw new IllegalArgumentException("该命令不接受图片");
        }
        if (!required) return;
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片必须非空且不超过 32 MiB");
        }
        if ("uploadPhoto".equals(command)) {
            String format = params.optString("format", "");
            if (!"jpeg".equals(format) && !"png".equals(format)) {
                throw new IllegalArgumentException("图片上传需要 JPEG 或 PNG 格式");
            }
            return;
        }
        long width = positiveInteger(params, "width");
        long height = positiveInteger(params, "height");
        if (width > MAX_IMAGE_BYTES || height > MAX_IMAGE_BYTES
                || width * height * 3 != image.length) {
            throw new IllegalArgumentException("RGB888 字节数必须等于 width × height × 3");
        }
        if (!"rgb".equals(params.optString("format", "rgb"))) {
            throw new IllegalArgumentException("识别、检测和登记只接受 RGB888");
        }
        if ("checkHead".equals(command) || "checkHand".equals(command)) {
            long max = positiveInteger(params, "max_detections");
            if (max > 64) throw new IllegalArgumentException("检测框上限须为 1 至 64");
        }
        if ("detectFace".equals(command) && params.has("max_faces")) {
            long max = positiveInteger(params, "max_faces");
            if (max > Integer.MAX_VALUE)
                throw new IllegalArgumentException("max_faces 必须为正 int 整数");
        }
    }

    private static long positiveInteger(JSONObject object, String name) {
        Object value = object.opt(name);
        if (!(value instanceof Number)) throw new IllegalArgumentException(name + " 必须为正整数");
        Number number = (Number) value;
        long integer = number.longValue();
        if (integer <= 0 || number.doubleValue() != integer) {
            throw new IllegalArgumentException(name + " 必须为正整数");
        }
        return integer;
    }

    static long timeoutMs(String command) {
        // 比壳层预算多留 10 秒给传输；本地超时始终属于“结果未知”，不能重放写操作。
        // 绑定包含账号与门店两步云请求；其客户端等待预算不能沿用单次推理的 40 秒。
        if ("init".equals(command) || "activate".equals(command) || "bindDevice".equals(command))
            return 130_000;
        if ("uploadPhoto".equals(command) || "submitFeedback".equals(command)) return 70_000;
        return 40_000;
    }

    static long reconnectDelayMs(int attempt) {
        return Math.min(500L << Math.min(Math.max(attempt, 0), 6), 30_000L);
    }

    static JSONObject copy(JSONObject object) {
        if (object == null) return new JSONObject();
        try {
            return new JSONObject(object.toString());
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("JSON 对象无法序列化", invalid);
        }
    }
}
