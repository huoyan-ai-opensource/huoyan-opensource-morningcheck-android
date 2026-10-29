package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class ShopBindingRequestTest {
    @Test public void loginPreservesExternalIdentityAndUsesIssuingAccount() throws Exception {
        for (String raw : new String[]{"007", "erp-shop-007", "account:user_123", " 123 "}) {
            JSONObject request = ShopBindingRequest.create(raw);
            assertEquals(raw, request.getString("shop_id"));
            assertEquals(2097500510197125120L, request.getLong("account_id"));
            assertFalse(request.has("shop_name"));
        }
    }

    @Test public void emptyQrCannotStartBinding() {
        for (String raw : new String[]{null, "", " \n\t "}) {
            assertThrows(IllegalArgumentException.class, () -> ShopBindingRequest.create(raw));
        }
    }
}
