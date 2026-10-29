package cc.huoyan.opensource.morningcheck.hardware;

/** 回执可拆包或与温度帧混在一起，只有独立的 success 才能确认模式切换。 */
final class TemperatureModeReply {
    enum Result {
        PENDING, SUCCESS, FAILURE
    }

    private final StringBuilder token = new StringBuilder();
    private boolean inTemperatureFrame;
    private Result result = Result.PENDING;

    Result accept(byte[] bytes, int length) {
        for (int index = 0; index < length && result == Result.PENDING; index++) {
            int value = bytes[index] & 255;
            if (inTemperatureFrame) {
                if (value == '}') inTemperatureFrame = false;
                continue;
            }
            if (value == '{') {
                finishToken();
                inTemperatureFrame = true;
            } else if (value == '}') {
                // 打开串口时可能只排空了半帧，剩余数字与右括号不属于模式回执。
                if (isTemperatureTail()) token.setLength(0);
                else finishToken();
            } else if (value <= ' ') {
                finishToken();
            } else {
                if (token.length() >= 64) result = Result.FAILURE;
                else token.append((char) value);
            }
        }
        return result;
    }

    /** 部分固件只回 success 而没有换行；串口空闲后才把完整词视作结束。 */
    Result finishWhenIdle() {
        if (!inTemperatureFrame && !isTemperatureTail()) finishToken();
        return result;
    }

    private boolean isTemperatureTail() {
        boolean digit = false;
        boolean decimal = false;
        for (int index = 0; index < token.length(); index++) {
            char value = token.charAt(index);
            if (value >= '0' && value <= '9') digit = true;
            else if (value == '.' && !decimal) decimal = true;
            else if ((value == '+' || value == '-') && index == 0) continue;
            else return false;
        }
        return digit;
    }

    private void finishToken() {
        if (result != Result.PENDING || token.length() == 0) return;
        result = "success".contentEquals(token) ? Result.SUCCESS : Result.FAILURE;
        token.setLength(0);
    }
}
