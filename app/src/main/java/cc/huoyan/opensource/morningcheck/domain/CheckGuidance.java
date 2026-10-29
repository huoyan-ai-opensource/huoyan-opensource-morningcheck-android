package cc.huoyan.opensource.morningcheck.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 现场晨检的简短文字提示；只读取本轮采样，不参与准入和异常判定。
 */
public final class CheckGuidance {
    public static final class Prompt {
        public final String text;
        public final boolean warning;

        public Prompt(String text, boolean warning) {
            this.text = text;
            this.warning = warning;
        }
    }

    private CheckGuidance() {
    }

    public static Prompt from(CheckPipeline pipeline, CheckPipeline.Rules rules) {
        if (pipeline == null || rules == null) {
            throw new IllegalArgumentException("晨检流程和本轮规则不能为空");
        }
        CheckPipeline.Stage stage = pipeline.getStage();
        if (stage == CheckPipeline.Stage.IDENTITY) {
            return new Prompt(pipeline.isIdentityConfirmed()
                    ? "身份已确认，请保持正视摄像头" : "请正视摄像头", false);
        }
        if (stage == CheckPipeline.Stage.RESULT) {
            // 检测结束后仍需保存记录并核验健康证；最终结论由记录策略统一生成。
            return new Prompt("检测已完成，正在生成结果", false);
        }

        List<CheckPipeline.Issue> issues = pipeline.getCurrentIssues();
        if (issues.contains(CheckPipeline.Issue.GLOVES)) {
            return new Prompt("请摘下手套", true);
        }
        if (issues.contains(CheckPipeline.Issue.HAND_POSE)) {
            return new Prompt(stage == CheckPipeline.Stage.HAND_BACK
                    ? "请将双手手背朝向摄像头" : "请将双手手心朝向摄像头", true);
        }
        if (issues.contains(CheckPipeline.Issue.HANDS_INCOMPLETE)) {
            return new Prompt("请将双手完整放入检测区域", true);
        }

        String instruction = CheckVoiceText.stage(stage, rules.requireMask, rules.requireHat);
        List<String> findings = pipeline.getDisplayFindings();
        if (findings.isEmpty()) return new Prompt(instruction, false);
        return new Prompt(summarize(findings, stage) + "\n" + instruction, true);
    }

    private static String summarize(List<String> findings, CheckPipeline.Stage stage) {
        String currentPrefix = stage == CheckPipeline.Stage.HAND_BACK ? "手背："
                : stage == CheckPipeline.Stage.HAND_PALM ? "手心：" : "";
        List<String> selected = new ArrayList<>(2);
        // 当前步骤优先占用两项摘要名额，避免早先头部异常遮住正在检测的手部问题。
        if (!currentPrefix.isEmpty()) {
            for (String finding : findings) {
                if (finding.startsWith(currentPrefix)) selected.add(finding);
                if (selected.size() == 2) break;
            }
        }
        for (String finding : findings) {
            if (selected.size() == 2) break;
            if (!selected.contains(finding)) selected.add(finding);
        }
        String summary = shortHeadFinding(selected.get(0));
        if (selected.size() > 1) {
            String second = selected.get(1);
            boolean sameHand = (summary.startsWith("手背：") && second.startsWith("手背："))
                    || (summary.startsWith("手心：") && second.startsWith("手心："));
            summary += sameHand ? "、" + second.substring(3) : "；" + shortHeadFinding(second);
        }
        // 只压缩现场提示，完整异常仍由流程快照提供给结果页和详情。
        return findings.size() > 2 ? summary + "等" + findings.size() + "项" : summary;
    }

    private static String shortHeadFinding(String finding) {
        if ("未佩戴工作帽".equals(finding)) return "未戴工作帽";
        if ("未佩戴口罩".equals(finding)) return "未戴口罩";
        if ("不应佩戴工作帽".equals(finding)) return "请摘下工作帽";
        if ("不应佩戴口罩".equals(finding)) return "请摘下口罩";
        return finding;
    }
}
