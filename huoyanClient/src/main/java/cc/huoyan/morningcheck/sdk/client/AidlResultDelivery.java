package cc.huoyan.morningcheck.sdk.client;

import androidx.annotation.RequiresApi;

import org.json.JSONObject;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 终结回调的代次规则；与可直接丢弃的状态广播分开，防止宿主请求永久等待。
 */
@RequiresApi(27)
final class AidlResultDelivery {
    private AidlResultDelivery() {
    }

    static void post(Executor deliveries, LongSupplier generation, BooleanSupplier destroyed,
                     HuoyanClient.Callback callback, int code, String message, JSONObject data) {
        if (callback == null || destroyed.getAsBoolean()) return;
        long epoch = generation.getAsLong();
        deliveries.execute(() -> {
            if (destroyed.getAsBoolean()) return;
            if (epoch == generation.getAsLong()) callback.onResult(code, message, data);
            else callback.onResult(HuoyanClient.ERROR_RESULT_UNKNOWN,
                    "交付回执前连接已切换，结果需核对", new JSONObject());
        });
    }
}
