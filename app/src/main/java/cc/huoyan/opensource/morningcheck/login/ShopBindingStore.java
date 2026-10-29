package cc.huoyan.opensource.morningcheck.login;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/**
 * SDK 云端归属独立存储；与旧业务服务的门店标识、登录凭据不是同一套数据。
 */
public final class ShopBindingStore {
    static final String SNAPSHOT_KEY = "binding_snapshot";
    private final SharedPreferences preferences;

    public ShopBindingStore(Context context) {
        Context app = Objects.requireNonNull(context, "应用 Context 不能为空").getApplicationContext();
        preferences = app.getSharedPreferences("sdk_binding", Context.MODE_PRIVATE);
    }

    ShopBindingStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences);
    }

    /**
     * 返回独立快照，空对象表示还没有完整保存过绑定。
     */
    public JSONObject read() {
        synchronized (preferences) {
            String encoded = preferences.getString(SNAPSHOT_KEY, null);
            if (encoded != null) {
                try {
                    JSONObject snapshot = new JSONObject(encoded);
                    return hasScope(snapshot) ? snapshot : new JSONObject();
                } catch (JSONException corrupt) {
                    return new JSONObject();
                }
            }
            // 兼容已安装调试版的分散字段；新绑定只写完整 JSON 快照。
            JSONObject result = new JSONObject();
            String accountId = preferences.getString("account_id", "");
            String shopId = preferences.getString("shop_id", "");
            if (accountId == null || accountId.trim().isEmpty()
                    || shopId == null || shopId.trim().isEmpty()) return result;
            try {
                result.put("account_id", accountId);
                result.put("shop_id", shopId);
                result.put("external_shop_id", preferences.getString("external_shop_id", ""));
                result.put("account_name", preferences.getString("account_name", ""));
                result.put("shop_name", preferences.getString("shop_name", ""));
                result.put("shop_no", preferences.getString("shop_no", ""));
                result.put("endpoint", preferences.getString("endpoint", ""));
                result.put("channel", preferences.getString("channel", ""));
                result.put("generation", preferences.getLong("generation", 0));
                result.put("bound_at", preferences.getLong("bound_at", 0));
            } catch (JSONException impossible) {
                throw new IllegalStateException("读取 SDK 门店归属失败", impossible);
            }
            return result;
        }
    }

    /**
     * 必须在后台调用；完整回执与连接配置一次提交，避免读到新旧归属混合值。
     */
    boolean save(ShopLoginCoordinator.Binding binding) {
        synchronized (preferences) {
            String previous = preferences.getString(SNAPSHOT_KEY, null);
            JSONObject oldSnapshot = read();
            JSONObject next = new JSONObject();
            try {
                next.put("account_id", binding.accountId);
                next.put("shop_id", binding.shopId);
                next.put("external_shop_id", binding.externalShopId);
                next.put("account_name", binding.accountName);
                next.put("shop_name", binding.shopName);
                next.put("shop_no", binding.shopNo);
                next.put("endpoint", binding.config.getWsEndpoint());
                next.put("channel", binding.config.getChannel().name());
                next.put("generation", oldSnapshot.optLong("generation", 0) + 1);
                next.put("bound_at", System.currentTimeMillis());
            } catch (JSONException impossible) {
                return false;
            }
            try {
                if (preferences.edit().putString(SNAPSHOT_KEY, next.toString()).commit())
                    return true;
            } catch (RuntimeException failure) {
                // commit 抛异常也不能假定内存未修改，按同样路径恢复上一份完整快照。
            }
            // Android 的 commit=false 仍会更新内存；锁内恢复后才允许其他页面读取。
            // apply 同步恢复内存、异步落盘，不再将这次云端绑定视为本地保存成功。
            SharedPreferences.Editor rollback = preferences.edit();
            if (previous == null) rollback.remove(SNAPSHOT_KEY);
            else rollback.putString(SNAPSHOT_KEY, previous);
            rollback.apply();
            return false;
        }
    }

    private static boolean hasScope(JSONObject value) {
        Object accountId = value.opt("account_id"), shopId = value.opt("shop_id");
        return accountId instanceof String && !((String) accountId).trim().isEmpty()
                && shopId instanceof String && !((String) shopId).trim().isEmpty();
    }

    /**
     * 云地址变更后的归属仅保留供核对；在业务存储队列调用，不删除原始绑定记录。
     */
    public boolean markNeedsVerification() {
        synchronized (preferences) {
            JSONObject snapshot = read();
            if (snapshot.length() == 0) return true;
            try {
                snapshot.put("needs_verification", true);
                snapshot.put("verification_marked_at", System.currentTimeMillis());
            } catch (JSONException impossible) {
                return false;
            }
            String encoded = snapshot.toString();
            try {
                if (preferences.edit().putString(SNAPSHOT_KEY, encoded).commit()) return true;
            } catch (RuntimeException failure) {
                // SDK 地址已经改变，磁盘失败也不能回退成“旧门店仍有效”。
            }
            preferences.edit().putString(SNAPSHOT_KEY, encoded).apply();
            return false;
        }
    }

    /**
     * 主线程成功回执先失效内存归属，磁盘由 apply 异步保存，堵住换址后的误开启窗口。
     */
    public boolean markNeedsVerificationAsync() {
        synchronized (preferences) {
            JSONObject snapshot = read();
            if (snapshot.length() == 0) return true;
            try {
                snapshot.put("needs_verification", true);
                snapshot.put("verification_marked_at", System.currentTimeMillis());
                preferences.edit().putString(SNAPSHOT_KEY, snapshot.toString()).apply();
                return true;
            } catch (JSONException | RuntimeException failure) {
                return false;
            }
        }
    }

    /**
     * 只有当前 SDK 配置对应的归属才展示为已登录。
     */
    public static String summary(Context context, SdkConfig config) {
        JSONObject binding = new ShopBindingStore(context).read();
        if (binding.length() == 0) return "未登录门店";
        if (!matchesConnection(binding, config)) {
            return "SDK 连接已变更，请重新登录门店";
        }
        return "已登录门店 · " + binding.optString("shop_id");
    }

    public static boolean matchesConnection(JSONObject scope, SdkConfig config) {
        return scope != null && config != null
                && !Boolean.TRUE.equals(scope.opt("needs_verification"))
                && config.getWsEndpoint().equals(scope.optString("endpoint"))
                && config.getChannel().name().equals(scope.optString("channel"));
    }
}
