package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 门店登录从发行 JAR 的 raw/config 读取账号，二维码原文保持不变。
 */
final class ShopBindingRequest {
    private ShopBindingRequest() {
    }

    static JSONObject create(String externalShopId) {
        if (externalShopId == null || externalShopId.trim().isEmpty()) {
            throw new IllegalArgumentException("门店二维码内容不能为空");
        }
        try {
            // 这里是外部字符串标识，不能转成数值或去掉前导零、首尾空格。
            return new JSONObject().put("account_id", BundledAccount.getAccountId()).put("shop_id", externalShopId);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("无法构造门店绑定请求", invalid);
        }
    }
}
