package cc.huoyan.opensource.morningcheck;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cc.huoyan.morningcheck.sdk.client.HuoyanClient;
import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 显式开启的真实日志驱动，只初始化和查询，不修改授权或人员数据。 */
@RunWith(AndroidJUnit4.class)
public final class ClsBusinessFlowDeviceTest {
    @Test
    public void initializeAndQueryOnBothChannels() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("clsE2e")));
        for (SdkConfig config : new SdkConfig[]{SdkConfig.aidl(), SdkConfig.webSocket(SdkConfig.DEFAULT_WS_ENDPOINT)}) {
            CountDownLatch connected = new CountDownLatch(1);
            HuoyanClient client = new HuoyanClient(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    new HuoyanClient.Listener() {
                        @Override
                        public void onConnection(boolean online, String message) {
                            if (online) connected.countDown();
                        }

                        @Override
                        public void onStatus(JSONObject status) {
                        }
                    });
            try {
                client.connect(config);
                assertTrue("真实壳应建立连接", connected.await(20, TimeUnit.SECONDS));
                await(client::init);
                long deadline = SystemClock.elapsedRealtime() + 60000;
                JSONObject status;
                do {
                    status = await(client::refreshStatus);
                    if (!status.optString("device_id").isEmpty()
                            && !"INITIALIZING".equals(status.optString("sdk_state"))) break;
                    SystemClock.sleep(200);
                } while (SystemClock.elapsedRealtime() < deadline);
                assertFalse("注册后必须具有设备身份", status.optString("device_id").isEmpty());
                Log.i("ClsBusinessFlow", "channel=" + config.getChannel()
                        + " deviceId=" + status.optString("device_id")
                        + " state=" + status.optString("sdk_state") + " timeMs=" + System.currentTimeMillis());
            } finally {
                client.destroy();
            }
        }
    }

    /** 通过客户端公开异步回执等待，不访问 SDK 私有状态。 */
    private interface Query {
        String run(HuoyanClient.Callback callback);
    }

    private static JSONObject await(Query query) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        int[] code = {-1};
        query.run((value, reason, data) -> {
            code[0] = value;
            result.set(data);
            done.countDown();
        });
        assertTrue("真实接口应返回回执", done.await(75, TimeUnit.SECONDS));
        assertEquals("真实接口返回码", 0, code[0]);
        assertNotNull(result.get());
        return result.get();
    }
}
