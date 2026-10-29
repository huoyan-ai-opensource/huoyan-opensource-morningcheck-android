package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;

/** 真实晨检壳双通道只读验证，不提交激活或变更人员底库。 */
@RunWith(AndroidJUnit4.class)
public final class RealLicenseModelsDeviceTest {
    @Test public void currentDeviceModelsCanBeQueriedWithoutActivationCodeOnBothChannels() throws Exception {
        String expectedCode = null;
        for (SdkConfig config : new SdkConfig[]{SdkConfig.aidl(), SdkConfig.webSocket(SdkConfig.DEFAULT_WS_ENDPOINT)}) {
            CountDownLatch connected = new CountDownLatch(1);
            HuoyanClient client = new HuoyanClient(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    new HuoyanClient.Listener() {
                        @Override public void onConnection(boolean online, String message) {
                            if (online) connected.countDown();
                        }
                        @Override public void onStatus(JSONObject status) { }
                    });
            try {
                client.connect(config);
                assertTrue("真实壳应建立连接: " + config.getChannel(), connected.await(20, TimeUnit.SECONDS));
                await(client::init);
                JSONObject certificate = await(client::getCertInfo);
                String activationCode = certificate.optString("activation_serial", "");
                assertTrue("测试设备应有已保存的激活码", !certificate.isNull("activation_serial") && !activationCode.isEmpty());
                JSONObject byCode = await(callback -> client.queryLicenseModels(activationCode, callback));
                JSONObject current = await(client::queryDeviceModels);
                assertEquals(current.getJSONArray("models").toString(), byCode.getJSONArray("models").toString());
                assertTrue("当前授权至少返回一个可用模型", current.getJSONArray("models").length() > 0);
                String modelCode = current.getJSONArray("models").getJSONObject(0).getString("model_code");
                assertFalse(modelCode.isEmpty());
                if (expectedCode != null) assertEquals(expectedCode, modelCode);
                expectedCode = modelCode;
                for (int i = 0; i < current.getJSONArray("models").length(); i++) {
                    JSONObject model = current.getJSONArray("models").getJSONObject(i);
                    assertFalse(model.getString("model_name").isEmpty());
                    assertTrue(model.has("chip"));
                    assertTrue(model.has("current"));
                }
            } finally { client.destroy(); }
        }
    }

    private interface Query { String run(HuoyanClient.Callback callback); }

    private static JSONObject await(Query query) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        int[] resultCode = {-1};
        AtomicReference<String> resultReason = new AtomicReference<>();
        query.run((code, reason, data) -> {
            resultCode[0] = code;
            resultReason.set(reason);
            result.set(data);
            done.countDown();
        });
        assertTrue("真实接口应返回回执", done.await(45, TimeUnit.SECONDS));
        assertEquals("真实接口返回码: " + resultReason.get(), 0, resultCode[0]);
        return result.get();
    }
}
