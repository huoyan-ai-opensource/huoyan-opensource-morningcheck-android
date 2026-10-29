package cc.huoyan.opensource.morningcheck.hardware;

import java.nio.charset.StandardCharsets;
import java.util.function.DoubleConsumer;

/**
 * 兼容旧晨检机的花括号文本协议和主动查询 CT 十字节协议。
 */
final class TemperatureProtocol {
    private final boolean activeMode;
    private final byte[] pending = new byte[4096];
    private int count;

    TemperatureProtocol(boolean activeMode) {
        this.activeMode = activeMode;
    }

    void accept(byte[] bytes, int length, DoubleConsumer consumer) {
        if (bytes == null || length < 0 || length > bytes.length)
            throw new IllegalArgumentException("测温数据长度错误");
        for (int index = 0; index < length; index++) {
            if (count == pending.length) count = 0;
            pending[count++] = bytes[index];
            parse(consumer);
        }
    }

    private void parse(DoubleConsumer consumer) {
        int consumed = 0;
        while (consumed < count) {
            int remain = count - consumed;
            if (activeMode && pending[consumed] == 'C') {
                if (remain < 2) break;
                if (pending[consumed + 1] == 'T') {
                    if (remain < 10) break;
                    int whole = pending[consumed + 2] & 255;
                    int fraction = pending[consumed + 3] & 255;
                    if (fraction < 100) emit(whole + fraction / 100d, consumer);
                    // 旧协议仅定义帧长与温度字段，不能凭空假设剩余六字节是校验和。
                    consumed += 10;
                    continue;
                }
            }
            if (pending[consumed] == '{') {
                int end = consumed + 1;
                boolean nested = false;
                while (end < count && pending[end] != '}') {
                    if (pending[end] == '{') {
                        consumed = end;
                        nested = true;
                        break;
                    }
                    end++;
                }
                if (nested) continue;
                if (end == count) {
                    if (remain <= 64) break;
                    consumed++;
                    continue;
                }
                String value = new String(pending, consumed + 1, end - consumed - 1, StandardCharsets.US_ASCII).trim();
                try {
                    emit(Double.parseDouble(value), consumer);
                } catch (NumberFormatException ignored) {
                }
                consumed = end + 1;
                continue;
            }
            consumed++;
        }
        if (consumed > 0) {
            System.arraycopy(pending, consumed, pending, 0, count - consumed);
            count -= consumed;
        }
    }

    private static void emit(double temperature, DoubleConsumer consumer) {
        // 0 度通常表示无人，异常或非有限值不能推进晨检流水线。
        if (Double.isFinite(temperature) && temperature > 10 && temperature < 60)
            consumer.accept(temperature);
    }
}
