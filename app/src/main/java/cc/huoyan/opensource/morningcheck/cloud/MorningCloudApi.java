package cc.huoyan.opensource.morningcheck.cloud;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 晨检业务服务的员工查询接口，调用方负责在 IO 线程执行。
 */
public final class MorningCloudApi {
    private final OkHttpClient client;

    public MorningCloudApi() {
        this(new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build());
    }

    MorningCloudApi(OkHttpClient client) {
        // 员工同步不跟随重定向，避免业务令牌被转发到其他服务。
        this.client = client.newBuilder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build();
    }

    /**
     * 返回完整业务响应，调用方按业务系统实际字段读取 data。
     */
    public JSONObject getStaff(String baseUrl, String token, long staffId) throws IOException {
        if (staffId <= 0) throw new IllegalArgumentException("员工 ID 必须大于零");
        return executeJson(authenticated(endpoint(baseUrl, "staff/" + staffId + "/"), token)
                .get().build());
    }

    public JSONObject listStaff(String baseUrl, String token) throws IOException {
        return listStaff(baseUrl, token, 1, 20);
    }

    public JSONObject listStaff(String baseUrl, String token, int page, int size) throws IOException {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("员工分页参数无效");
        }
        HttpUrl url = endpoint(baseUrl, "staff/").newBuilder()
                .addQueryParameter("page", String.valueOf(page))
                .addQueryParameter("size", String.valueOf(size)).build();
        return executeJson(authenticated(url, token).get().build());
    }

    private JSONObject executeJson(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ApiException("业务请求失败：HTTP " + response.code(), response.code(), null);
            }
            ResponseBody body = response.body();
            JSONObject result;
            int businessCode;
            try {
                if (body == null) throw new JSONException("响应为空");
                result = new JSONObject(body.string());
                if (!result.has("code") || result.isNull("code")) {
                    throw new JSONException("缺少业务状态码");
                }
                try {
                    businessCode = Integer.parseInt(String.valueOf(result.get("code")));
                } catch (NumberFormatException invalidCode) {
                    throw new JSONException("业务状态码不是整数");
                }
            } catch (JSONException e) {
                throw new IOException("业务响应格式无效", e);
            }
            if (businessCode != 0) {
                String message = result.optString("message", result.optString("msg", "业务请求被拒绝"));
                throw new ApiException(message, response.code(), businessCode);
            }
            return result;
        }
    }

    private Request.Builder authenticated(HttpUrl url, String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("请先配置业务登录令牌");
        }
        return new Request.Builder().url(url).header("X-USER-TOKEN", token.trim())
                .header("Accept", "application/json");
    }

    private static HttpUrl endpoint(String baseUrl, String path) {
        HttpUrl base = parseHttpUrl(baseUrl);
        if (!base.username().isEmpty() || !base.password().isEmpty()
                || base.query() != null || base.fragment() != null) {
            throw new IllegalArgumentException("服务地址不能包含账号、查询参数或片段");
        }
        String basePath = base.encodedPath();
        if (!basePath.endsWith("/")) basePath += "/";
        return base.newBuilder().encodedPath(basePath + path).build();
    }

    private static HttpUrl parseHttpUrl(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value.trim());
        if (url == null)
            throw new IllegalArgumentException("服务地址必须是有效的 HTTP 或 HTTPS 地址");
        return url;
    }

    /**
     * 服务端明确拒绝或返回非成功 HTTP 状态。
     */
    public static final class ApiException extends IOException {
        public final int httpCode;
        public final Integer businessCode;

        ApiException(String message, int httpCode, Integer businessCode) {
            super(message);
            this.httpCode = httpCode;
            this.businessCode = businessCode;
        }
    }
}
