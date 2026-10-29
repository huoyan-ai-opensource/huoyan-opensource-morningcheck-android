package cc.huoyan.opensource.morningcheck.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 由实际采集结果驱动的单人晨检流程。调用方在同一线程串行投递结果，负责暂停、
 * 相机切换和过期回包过滤；流程本身不启动计时器，也不保存记录。
 */
public final class CheckPipeline {
    public enum Stage {IDENTITY, HEAD, HAND_BACK, HAND_PALM, RESULT}

    /**
     * 当前有效采样的显示问题；不代表已写入本轮的最终异常结论。
     */
    public enum Issue {
        HAT_MISSING, MASK_MISSING, HAT_UNEXPECTED, MASK_UNEXPECTED,
        GLOVES, HAND_POSE, HANDS_INCOMPLETE
    }

    public static final class Rules {
        public final int identitySamples;
        public final int headSamples;
        public final int handSamples;
        public final boolean headEnabled;
        public final boolean requireHat;
        public final boolean requireMask;
        public final double temperatureMinimum;
        public final double temperatureLimit;

        public Rules(int identitySamples, int headSamples, int handSamples,
                     boolean headEnabled, boolean requireHat, boolean requireMask,
                     double temperatureLimit) {
            this(identitySamples, headSamples, handSamples, headEnabled, requireHat, requireMask,
                    36.0, temperatureLimit);
        }

        public Rules(int identitySamples, int headSamples, int handSamples,
                     boolean headEnabled, boolean requireHat, boolean requireMask,
                     double temperatureMinimum, double temperatureLimit) {
            if (identitySamples < 1 || headSamples < 1 || handSamples < 1) {
                throw new IllegalArgumentException("连续采样次数必须大于零");
            }
            if (!Double.isFinite(temperatureMinimum) || !Double.isFinite(temperatureLimit)
                    || temperatureMinimum < 34 || temperatureLimit > 43
                    || temperatureMinimum >= temperatureLimit) {
                throw new IllegalArgumentException("体温异常阈值须满足 34 ≤ 下限 < 上限 ≤ 43°C");
            }
            this.identitySamples = identitySamples;
            this.headSamples = headSamples;
            this.handSamples = handSamples;
            this.headEnabled = headEnabled;
            this.requireHat = requireHat;
            this.requireMask = requireMask;
            this.temperatureMinimum = temperatureMinimum;
            this.temperatureLimit = temperatureLimit;
        }
    }

    public static final class Detection {
        public final int classId;
        public final float score;

        public Detection(int classId, float score) {
            this.classId = classId;
            this.score = score;
        }
    }

    private final Rules rules;
    private final Set<String> findings = new LinkedHashSet<>();
    private final Set<String> pendingHandFindings = new LinkedHashSet<>();
    private final Set<Issue> currentIssues = new LinkedHashSet<>();
    private final Set<String> currentHeadFindings = new LinkedHashSet<>();
    private Stage stage;
    private long personId;
    private long candidateId;
    private int progress;
    private double temperature;
    private boolean temperatureSkipped;
    private String hint;

    public CheckPipeline(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("晨检规则不能为空");
        }
        this.rules = rules;
        reset();
    }

    public void reset() {
        personId = 0;
        candidateId = 0;
        temperature = Double.NaN;
        temperatureSkipped = false;
        findings.clear();
        pendingHandFindings.clear();
        enter(Stage.IDENTITY);
    }

    public void onIdentity(long personId, boolean matched) {
        if (stage != Stage.IDENTITY) {
            return;
        }
        if (!matched || personId <= 0) {
            clearIdentitySamples();
            updateIdentityHint();
            return;
        }
        if (candidateId != personId) {
            // 首位候选可使用本次采集先到的读数；换人后必须重新测温，不能沿用上一人。
            if (candidateId > 0) temperature = Double.NaN;
            candidateId = personId;
            this.personId = 0;
            progress = 0;
        }
        if (progress < rules.identitySamples) progress++;
        if (progress >= rules.identitySamples) {
            this.personId = personId;
        }
        finishIdentityAndTemperature();
    }

    /**
     * 当前采集连接的设备读数可先于身份回包到达，旧连接回包由调用方过滤。
     */
    public void onTemperature(double value) {
        if (stage != Stage.IDENTITY || temperatureSkipped) {
            return;
        }
        if (!Double.isFinite(value) || value < 34 || value > 43) {
            temperature = Double.NaN;
            hint = "体温读数无效，请重新测量（34 至 43°C）";
            return;
        }
        temperature = value;
        finishIdentityAndTemperature();
    }

    /**
     * 仅在门店明确关闭测温检查时调用；保留未测状态，不能代入正常体温。
     */
    public void skipTemperature() {
        if (stage != Stage.IDENTITY) {
            return;
        }
        temperatureSkipped = true;
        temperature = Double.NaN;
        finishIdentityAndTemperature();
    }

    private void finishIdentityAndTemperature() {
        if (personId <= 0 || (!temperatureSkipped && !Double.isFinite(temperature))) {
            updateIdentityHint();
            return;
        }
        // 身份和读数同时锁定后才产生异常结论，提前到达或被替换的读数不属于记录。
        if (!temperatureSkipped) {
            if (temperature <= rules.temperatureMinimum) {
                findings.add(String.format(Locale.CHINA, "体温偏低：%.1f°C", temperature));
            } else if (temperature >= rules.temperatureLimit) {
                findings.add(String.format(Locale.CHINA, "体温偏高：%.1f°C", temperature));
            }
        }
        enter(rules.headEnabled ? Stage.HEAD : Stage.HAND_BACK);
    }

    private void clearIdentitySamples() {
        personId = 0;
        candidateId = 0;
        progress = 0;
        temperature = Double.NaN;
    }

    private void updateIdentityHint() {
        if (personId > 0) {
            hint = "身份已确认，请靠近测温设备等待有效体温读数";
        } else if (Double.isFinite(temperature)) {
            hint = "已读取体温，请保持面部在画面内完成身份核验";
        } else {
            hint = temperatureSkipped ? "请正视镜头，等待识别员工身份"
                    : "请正视镜头，同时靠近测温设备";
        }
    }

    /**
     * 暂停或采集失败会打断连续样本；身份与测温尚未锁定时清除整组人员证据，
     * 已进入头手检查时仅清除当前组样本，保留本人的已完成检查。
     */
    public void interruptSamples() {
        if (stage == Stage.RESULT) return;
        if (stage == Stage.IDENTITY) clearIdentitySamples();
        pendingHandFindings.clear();
        enter(stage);
    }

    /**
     * 帽子或口罩框可提供头部存在的证据。头部空框既可能无人，也可能缺少全部着装，
     * 因而此入口不会让空框推进；已有同帧人脸检测证据时使用 onHeadDetections。
     */
    public void onDetections(List<Detection> detections) {
        if (stage == Stage.HEAD) {
            boolean headPresent = contains(detections, DetectionClasses.HEAD_HAT)
                    || contains(detections, DetectionClasses.HEAD_MASK);
            onHeadDetections(detections, headPresent);
        } else if (stage == Stage.HAND_BACK || stage == Stage.HAND_PALM) {
            processHands(detections);
        }
    }

    /**
     * personPresent 必须来自当前采集帧，不能仅凭上一阶段已识别到员工就传 true。
     */
    public void onHeadDetections(List<Detection> detections, boolean personPresent) {
        if (stage != Stage.HEAD) {
            return;
        }
        if (!personPresent) {
            progress = 0;
            clearCurrentIssues();
            hint = "请保持面部在画面内，等待着装检测";
            return;
        }
        clearCurrentIssues();
        boolean hasHat = contains(detections, DetectionClasses.HEAD_HAT);
        boolean hasMask = contains(detections, DetectionClasses.HEAD_MASK);
        if (rules.requireHat && !hasHat) {
            currentIssues.add(Issue.HAT_MISSING);
            currentHeadFindings.add("未佩戴工作帽");
        } else if (!rules.requireHat && hasHat) {
            currentIssues.add(Issue.HAT_UNEXPECTED);
            currentHeadFindings.add("不应佩戴工作帽");
        }
        if (rules.requireMask && !hasMask) {
            currentIssues.add(Issue.MASK_MISSING);
            currentHeadFindings.add("未佩戴口罩");
        } else if (!rules.requireMask && hasMask) {
            currentIssues.add(Issue.MASK_UNEXPECTED);
            currentHeadFindings.add("不应佩戴口罩");
        }
        if (!currentIssues.isEmpty()) {
            // 着装不合规即打断本组；更正后须重新连续采样，不能带着异常进入手检。
            progress = 0;
            hint = "头部着装不符合要求，请调整后继续检测";
            return;
        }
        progress++;
        hint = "正在检查工作帽和口罩，请保持姿势";
        if (progress >= rules.headSamples) {
            enter(Stage.HAND_BACK);
        }
    }

    private void processHands(List<Detection> detections) {
        boolean back = stage == Stage.HAND_BACK;
        int expectedClass = back ? DetectionClasses.HAND_BACK : DetectionClasses.HAND_PALM;
        int oppositeClass = back ? DetectionClasses.HAND_PALM : DetectionClasses.HAND_BACK;
        if (contains(detections, DetectionClasses.HAND_GLOVE)) {
            rejectHandFrame(Issue.GLOVES, "请摘下手套，露出双手接受检测");
            return;
        }
        if (contains(detections, oppositeClass)
                || contains(detections, DetectionClasses.HAND_INCORRECT)) {
            rejectHandFrame(Issue.HAND_POSE,
                    back ? "请将双手手背朝向镜头" : "请将双手手心朝向镜头");
            return;
        }
        if (count(detections, expectedClass) < 2) {
            rejectHandFrame(Issue.HANDS_INCOMPLETE, "请将双手完整放入检测区域");
            return;
        }
        clearCurrentIssues();
        String prefix = back ? "手背：" : "手心：";
        appendHandFinding(detections, DetectionClasses.HAND_NAIL, prefix + "指甲油");
        appendHandFinding(detections, DetectionClasses.HAND_PLASTER, prefix + "创可贴");
        appendHandFinding(detections, DetectionClasses.HAND_RING, prefix + "戒指");
        appendHandFinding(detections, DetectionClasses.HAND_WOUND, prefix + "伤口");
        progress++;
        hint = back ? "正在采集双手手背，请保持姿势" : "正在采集双手手心，请保持姿势";
        if (progress >= rules.handSamples) {
            findings.addAll(pendingHandFindings);
            pendingHandFindings.clear();
            enter(back ? Stage.HAND_PALM : Stage.RESULT);
        }
    }

    private void appendHandFinding(List<Detection> detections, int classId, String label) {
        if (contains(detections, classId)) {
            pendingHandFindings.add(label);
        }
    }

    private void rejectHandFrame(Issue issue, String reason) {
        // 被姿态错误打断的一组采样不能混入下一组，异常也只属于连续合格的采集帧。
        progress = 0;
        pendingHandFindings.clear();
        clearCurrentIssues();
        currentIssues.add(issue);
        hint = reason;
    }

    private void clearCurrentIssues() {
        currentIssues.clear();
        currentHeadFindings.clear();
    }

    private static boolean contains(List<Detection> detections, int classId) {
        return count(detections, classId) > 0;
    }

    private static int count(List<Detection> detections, int classId) {
        int count = 0;
        if (detections != null) {
            for (Detection detection : detections) {
                if (detection != null && detection.classId == classId
                        && Float.isFinite(detection.score) && detection.score > 0
                        && detection.score <= 1) {
                    count++;
                }
            }
        }
        return count;
    }

    private void enter(Stage next) {
        stage = next;
        progress = 0;
        clearCurrentIssues();
        switch (next) {
            case IDENTITY:
                updateIdentityHint();
                break;
            case HEAD:
                hint = "请保持面部在画面内，检查工作帽和口罩";
                break;
            case HAND_BACK:
                hint = "请将双手手背朝向镜头";
                break;
            case HAND_PALM:
                hint = "请翻转双手，将手心朝向镜头";
                break;
            case RESULT:
                progress = 1;
                hint = findings.isEmpty()
                        ? (temperatureSkipped ? "晨检完成，已检项目正常；体温未测" : "晨检完成，各项检查正常")
                        : "晨检完成，请查看异常项目";
                break;
            default:
                throw new IllegalStateException("未知晨检阶段");
        }
    }

    public Stage getStage() {
        return stage;
    }

    /**
     * 身份连续样本达标即提供员工编号，仍可能处于等待测温的合并步骤。
     */
    public long getPersonId() {
        return personId;
    }

    public long getCandidateId() {
        return candidateId;
    }

    public boolean isIdentityConfirmed() {
        return personId > 0;
    }

    public int getProgress() {
        return progress;
    }

    public String getHint() {
        return hint;
    }

    public double getTemperature() {
        return temperature;
    }

    public boolean isTemperatureSkipped() {
        return temperatureSkipped;
    }

    public int getRequiredSamples() {
        switch (stage) {
            case IDENTITY:
                return rules.identitySamples;
            case HEAD:
                return rules.headSamples;
            case HAND_BACK:
            case HAND_PALM:
                return rules.handSamples;
            default:
                return 1;
        }
    }

    public List<String> getFindings() {
        return Collections.unmodifiableList(new ArrayList<>(findings));
    }

    public List<Issue> getCurrentIssues() {
        return Collections.unmodifiableList(new ArrayList<>(currentIssues));
    }

    /**
     * 现场文字使用已确认异常和当前有效样本的预判，不修改保存结论；采样中断后预判即清除。
     * 体温由独立数值区域展示，不混入头手操作提示。
     */
    public List<String> getDisplayFindings() {
        Set<String> display = new LinkedHashSet<>();
        for (String finding : findings) {
            if (!finding.startsWith("体温")) display.add(finding);
        }
        if (stage == Stage.HEAD) display.addAll(currentHeadFindings);
        if (stage == Stage.HAND_BACK || stage == Stage.HAND_PALM) {
            display.addAll(pendingHandFindings);
        }
        return Collections.unmodifiableList(new ArrayList<>(display));
    }
}
