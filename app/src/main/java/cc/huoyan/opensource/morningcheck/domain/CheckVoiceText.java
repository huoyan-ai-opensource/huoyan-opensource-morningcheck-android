package cc.huoyan.opensource.morningcheck.domain;

/**
 * 阶段提示使用本轮冻结的晨检规则；结论仅由已保存记录决定。
 */
public final class CheckVoiceText {
    private CheckVoiceText() {
    }

    public static String stage(CheckPipeline.Stage stage, boolean requireMask, boolean requireHat) {
        switch (stage) {
            case IDENTITY:
                return "请正视摄像头";
            case HEAD:
                if (requireMask && requireHat) return "请佩戴口罩、工作帽检测";
                if (requireMask) return "请佩戴口罩检测";
                if (requireHat) return "请佩戴工作帽检测";
                return "请进行头部检测";
            case HAND_BACK:
                return "请伸出双手手背";
            case HAND_PALM:
                return "请翻转双手手心";
            default:
                return "";
        }
    }

    public static String result(boolean normal) {
        // 异常明细保留在结果页面；口播只给结论，避免长句拖住流水线。
        return normal ? "晨检正常" : "晨检异常，请查看结果";
    }
}
