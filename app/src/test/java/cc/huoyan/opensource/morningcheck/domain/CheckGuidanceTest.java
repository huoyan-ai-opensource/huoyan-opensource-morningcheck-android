package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static cc.huoyan.opensource.morningcheck.domain.DetectionClasses.*;
import static org.junit.Assert.*;

public class CheckGuidanceTest {
    private final CheckPipeline.Rules rules = new CheckPipeline.Rules(4, 4, 5,
            true, true, true, 37.3);

    private List<CheckPipeline.Detection> boxes(int... classes) {
        List<CheckPipeline.Detection> boxes = new ArrayList<>();
        for (int classId : classes) boxes.add(new CheckPipeline.Detection(classId, 0.9f));
        return boxes;
    }

    private CheckPipeline head(CheckPipeline.Rules rules, double temperature) {
        CheckPipeline pipeline = new CheckPipeline(rules);
        for (int sample = 0; sample < rules.identitySamples; sample++) pipeline.onIdentity(21, true);
        pipeline.onTemperature(temperature);
        return pipeline;
    }

    private CheckPipeline hands() {
        CheckPipeline pipeline = head(rules, 36.6);
        for (int sample = 0; sample < rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        return pipeline;
    }

    private void finishHands(CheckPipeline pipeline) {
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        }
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        }
    }

    @Test public void identityGuidanceKeepsMeasurementInternalAndPreservesItsGate() {
        CheckPipeline pipeline = new CheckPipeline(rules);
        assertEquals("请正视摄像头", CheckGuidance.from(pipeline, rules).text);
        pipeline.onTemperature(36.6);
        assertEquals("请正视摄像头", CheckGuidance.from(pipeline, rules).text);
        pipeline.reset();
        for (int sample = 0; sample < rules.identitySamples; sample++) pipeline.onIdentity(21, true);
        assertEquals(CheckPipeline.Stage.IDENTITY, pipeline.getStage());
        assertEquals("身份已确认，请保持正视摄像头", CheckGuidance.from(pipeline, rules).text);
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
    }

    @Test public void missingAttireAppearsOnFirstFrameWithoutPrematureFinalFinding() {
        CheckPipeline pipeline = head(rules, 36.6);
        pipeline.onHeadDetections(Collections.emptyList(), true);
        assertEquals(0, pipeline.getProgress());
        assertEquals(Arrays.asList(CheckPipeline.Issue.HAT_MISSING, CheckPipeline.Issue.MASK_MISSING),
                pipeline.getCurrentIssues());
        assertEquals(Arrays.asList("未佩戴工作帽", "未佩戴口罩"), pipeline.getDisplayFindings());
        assertTrue(pipeline.getFindings().isEmpty());
        CheckGuidance.Prompt prompt = CheckGuidance.from(pipeline, rules);
        assertTrue(prompt.warning);
        assertTrue(prompt.text.contains("未戴工作帽"));
        assertTrue(prompt.text.contains("未戴口罩"));
        for (int sample = 0; sample < rules.headSamples * 2; sample++) {
            pipeline.onHeadDetections(Collections.emptyList(), true);
            assertEquals(CheckPipeline.Stage.HEAD, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
            assertEquals(prompt.text, CheckGuidance.from(pipeline, rules).text);
            assertTrue(CheckGuidance.from(pipeline, rules).warning);
            assertTrue(pipeline.getFindings().isEmpty());
        }
    }

    @Test public void correctedAttireClearsWarningAndNeedsFullConsecutiveSamples() {
        CheckPipeline pipeline = head(rules, 36.6);
        pipeline.onHeadDetections(Collections.emptyList(), true);
        for (int sample = 1; sample <= rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
            assertTrue(pipeline.getDisplayFindings().isEmpty());
            assertTrue(pipeline.getCurrentIssues().isEmpty());
            assertFalse(CheckGuidance.from(pipeline, rules).warning);
            assertEquals(sample < rules.headSamples ? CheckPipeline.Stage.HEAD
                    : CheckPipeline.Stage.HAND_BACK, pipeline.getStage());
        }
        assertEquals(CheckPipeline.Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
    }

    @Test public void headWarningClearsWhenCurrentEmployeeLeavesOrSamplingIsInterrupted() {
        CheckPipeline pipeline = head(rules, 36.6);
        pipeline.onHeadDetections(Collections.emptyList(), true);
        pipeline.onHeadDetections(Collections.emptyList(), false);
        assertEquals(0, pipeline.getProgress());
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        pipeline.onHeadDetections(Collections.emptyList(), true);
        pipeline.interruptSamples();
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
    }

    @Test public void headRulesTreatDisabledAttireAsUnexpectedRatherThanIgnoringIt() {
        CheckPipeline.Rules noAttire = new CheckPipeline.Rules(4, 4, 5,
                true, false, false, 37.3);
        CheckPipeline pipeline = head(noAttire, 36.6);
        pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        assertEquals(Arrays.asList(CheckPipeline.Issue.HAT_UNEXPECTED, CheckPipeline.Issue.MASK_UNEXPECTED),
                pipeline.getCurrentIssues());
        assertEquals(Arrays.asList("不应佩戴工作帽", "不应佩戴口罩"), pipeline.getDisplayFindings());
        assertTrue(CheckGuidance.from(pipeline, noAttire).text.contains("请摘下工作帽"));
        assertTrue(CheckGuidance.from(pipeline, noAttire).text.contains("请摘下口罩"));
        for (int sample = 0; sample < noAttire.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
            assertEquals(CheckPipeline.Stage.HEAD, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
            assertTrue(CheckGuidance.from(pipeline, noAttire).warning);
        }
        for (int sample = 0; sample < noAttire.headSamples; sample++) {
            pipeline.onHeadDetections(Collections.emptyList(), true);
            assertFalse(CheckGuidance.from(pipeline, noAttire).warning);
        }
        assertEquals(CheckPipeline.Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void headWarningsBlockHandsUntilCorrectedAndNeverCarryIntoHands() {
        CheckPipeline pipeline = head(rules, 36.6);
        for (int sample = 0; sample < rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT), true);
            assertEquals(CheckPipeline.Stage.HEAD, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
        }
        assertEquals(Collections.singletonList(CheckPipeline.Issue.MASK_MISSING),
                pipeline.getCurrentIssues());
        assertEquals(Collections.singletonList("未佩戴口罩"), pipeline.getDisplayFindings());
        CheckGuidance.Prompt prompt = CheckGuidance.from(pipeline, rules);
        assertTrue(prompt.warning);
        assertTrue(prompt.text.contains("未戴口罩"));
        assertFalse(prompt.text.contains("手背"));
        for (int sample = 0; sample < rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        assertEquals(CheckPipeline.Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertTrue(pipeline.getFindings().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
        assertTrue(CheckGuidance.from(pipeline, rules).text.contains("手背"));
        pipeline.interruptSamples();
        assertTrue(pipeline.getDisplayFindings().isEmpty());
    }

    @Test public void qualifiedHandAbnormalitiesAppearImmediatelyButAreNotYetFinal() {
        CheckPipeline pipeline = hands();
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING, HAND_WOUND));
        assertEquals(1, pipeline.getProgress());
        assertTrue(pipeline.getFindings().isEmpty());
        assertEquals(Arrays.asList("手背：戒指", "手背：伤口"), pipeline.getDisplayFindings());
        assertTrue(CheckGuidance.from(pipeline, rules).warning);
        assertTrue(CheckGuidance.from(pipeline, rules).text.contains("手背：戒指、伤口"));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        assertEquals(Arrays.asList("手背：戒指", "手背：伤口"), pipeline.getDisplayFindings());
    }

    @Test public void rejectedHandFrameDropsPendingWarningsAndNeverReportsRejectedAbnormality() {
        CheckPipeline pipeline = hands();
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING));
        pipeline.onDetections(boxes(HAND_BACK, HAND_RING, HAND_WOUND));
        assertEquals(Collections.singletonList(CheckPipeline.Issue.HANDS_INCOMPLETE), pipeline.getCurrentIssues());
        assertEquals(0, pipeline.getProgress());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertEquals("请将双手完整放入检测区域", CheckGuidance.from(pipeline, rules).text);
        finishHands(pipeline);
        assertTrue(pipeline.getFindings().isEmpty());
        assertEquals("检测已完成，正在生成结果", CheckGuidance.from(pipeline, rules).text);
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
    }

    @Test public void gloveAndPoseCorrectionsTakePriorityAndClearAfterAcceptedFrame() {
        CheckPipeline pipeline = hands();
        pipeline.onDetections(boxes(HAND_GLOVE, HAND_PALM, HAND_RING));
        assertEquals(Collections.singletonList(CheckPipeline.Issue.GLOVES), pipeline.getCurrentIssues());
        assertEquals("请摘下手套", CheckGuidance.from(pipeline, rules).text);
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_INCORRECT));
        assertEquals(Collections.singletonList(CheckPipeline.Issue.HAND_POSE), pipeline.getCurrentIssues());
        assertEquals("请将双手手背朝向摄像头", CheckGuidance.from(pipeline, rules).text);
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
        for (int sample = 1; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        }
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        assertEquals("请将双手手心朝向摄像头", CheckGuidance.from(pipeline, rules).text);
    }

    @Test public void interruptDropsPendingHandAbnormalitiesAndTypedCorrections() {
        CheckPipeline pipeline = hands();
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_NAIL));
        pipeline.interruptSamples();
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        pipeline.onDetections(boxes(HAND_GLOVE));
        pipeline.interruptSamples();
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
    }

    @Test public void committedHandAbnormalitiesPersistAcrossStageAndStopOnReset() {
        CheckPipeline pipeline = hands();
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING));
        }
        assertEquals(CheckPipeline.Stage.HAND_PALM, pipeline.getStage());
        assertEquals(Collections.singletonList("手背：戒指"), pipeline.getDisplayFindings());
        assertTrue(CheckGuidance.from(pipeline, rules).text.contains("手心"));
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_PALM, HAND_PALM, HAND_PLASTER));
        }
        assertEquals(Arrays.asList("手背：戒指", "手心：创可贴"), pipeline.getFindings());
        assertEquals("检测已完成，正在生成结果", CheckGuidance.from(pipeline, rules).text);
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
        pipeline.reset();
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertEquals("请正视摄像头", CheckGuidance.from(pipeline, rules).text);
    }

    @Test public void invalidConfidenceNeverCreatesDisplayedHandAbnormality() {
        CheckPipeline pipeline = hands();
        pipeline.onDetections(Arrays.asList(
                new CheckPipeline.Detection(HAND_BACK, 0.9f),
                new CheckPipeline.Detection(HAND_BACK, 0.9f),
                new CheckPipeline.Detection(HAND_RING, Float.NaN),
                new CheckPipeline.Detection(HAND_WOUND, Float.POSITIVE_INFINITY),
                new CheckPipeline.Detection(HAND_NAIL, 0),
                new CheckPipeline.Detection(HAND_PLASTER, 2)));
        assertEquals(1, pipeline.getProgress());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
    }

    @Test public void displaySnapshotsAreDetachedAndCannotModifyPipeline() {
        CheckPipeline pipeline = head(rules, 36.6);
        pipeline.onHeadDetections(Collections.emptyList(), true);
        List<String> display = pipeline.getDisplayFindings();
        List<CheckPipeline.Issue> issues = pipeline.getCurrentIssues();
        try {
            display.add("伪造异常");
            fail("显示快照不可修改");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            issues.clear();
            fail("问题快照不可修改");
        } catch (UnsupportedOperationException expected) {
        }
        pipeline.reset();
        assertEquals(2, display.size());
        assertEquals(2, issues.size());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void temperatureFindingsRemainInResultButNotInOperatorGuidance() {
        CheckPipeline pipeline = head(rules, 38.1);
        assertEquals(Collections.singletonList("体温偏高：38.1°C"), pipeline.getFindings());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertFalse(CheckGuidance.from(pipeline, rules).text.contains("体温"));
        for (int sample = 0; sample < rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        finishHands(pipeline);
        assertEquals("检测已完成，正在生成结果", CheckGuidance.from(pipeline, rules).text);
        assertFalse(CheckGuidance.from(pipeline, rules).warning);
        assertTrue(pipeline.getDisplayFindings().isEmpty());
        assertEquals(38.1, pipeline.getTemperature(), 0.001);
    }

    @Test public void manyAbnormalitiesUseTwoItemSummaryWithoutDiscardingEvidence() {
        CheckPipeline pipeline = head(rules, 38.1);
        for (int sample = 0; sample < rules.headSamples; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK,
                    HAND_NAIL, HAND_PLASTER, HAND_RING, HAND_WOUND));
        }
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM,
                HAND_NAIL, HAND_PLASTER, HAND_RING, HAND_WOUND));
        CheckGuidance.Prompt prompt = CheckGuidance.from(pipeline, rules);
        assertEquals("手心：指甲油、创可贴等8项\n请翻转双手手心", prompt.text);
        assertTrue(prompt.warning);
        assertEquals(8, pipeline.getDisplayFindings().size());
        assertEquals(5, pipeline.getFindings().size());
        for (int sample = 1; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        }
        assertEquals(9, pipeline.getFindings().size());
        assertEquals(8, pipeline.getDisplayFindings().size());
        assertTrue(pipeline.getFindings().contains("体温偏高：38.1°C"));
        assertTrue(pipeline.getFindings().contains("手心：伤口"));
        assertTrue(pipeline.getFindings().contains("手背：创可贴"));
    }

    @Test public void currentPalmFindingsAppearBeforePreviouslyConfirmedBackFindings() {
        CheckPipeline pipeline = hands();
        for (int sample = 0; sample < rules.handSamples; sample++) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING, HAND_WOUND));
        }
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM, HAND_NAIL, HAND_PLASTER));
        CheckGuidance.Prompt prompt = CheckGuidance.from(pipeline, rules);
        assertEquals("手心：指甲油、创可贴等4项\n请翻转双手手心", prompt.text);
        assertTrue(prompt.warning);
        assertEquals(Arrays.asList("手背：戒指", "手背：伤口", "手心：指甲油", "手心：创可贴"),
                pipeline.getDisplayFindings());
        assertEquals(Arrays.asList("手背：戒指", "手背：伤口"), pipeline.getFindings());
    }
}
