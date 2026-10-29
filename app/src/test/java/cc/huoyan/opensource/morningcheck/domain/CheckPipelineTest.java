package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import cc.huoyan.opensource.morningcheck.domain.CheckPipeline.Stage;
import static cc.huoyan.opensource.morningcheck.domain.DetectionClasses.*;
import static org.junit.Assert.*;

public class CheckPipelineTest {
    private CheckPipeline pipeline(boolean headEnabled) {
        return new CheckPipeline(new CheckPipeline.Rules(4, 4, 2, headEnabled, true, true, 37.3));
    }

    private void identify(CheckPipeline pipeline) {
        for (int i = 0; i < 4; i++) pipeline.onIdentity(21, true);
    }

    private List<CheckPipeline.Detection> boxes(int... classes) {
        CheckPipeline.Detection[] result = new CheckPipeline.Detection[classes.length];
        for (int i = 0; i < classes.length; i++) {
            result[i] = new CheckPipeline.Detection(classes[i], 0.9f);
        }
        return Arrays.asList(result);
    }

    private List<CheckPipeline.Detection> attire(boolean hat, boolean mask) {
        if (hat && mask) return boxes(HEAD_HAT, HEAD_MASK);
        if (hat) return boxes(HEAD_HAT);
        if (mask) return boxes(HEAD_MASK);
        return Collections.emptyList();
    }

    @Test public void identityRequiresConsecutiveSamplesOfSamePerson() {
        CheckPipeline pipeline = pipeline(true);
        pipeline.onIdentity(10, true);
        pipeline.onIdentity(10, true);
        pipeline.onIdentity(20, true);
        assertEquals(1, pipeline.getProgress());
        pipeline.onIdentity(20, true);
        pipeline.onIdentity(0, false);
        assertEquals(0, pipeline.getProgress());
        identify(pipeline);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertEquals(21, pipeline.getPersonId());
        assertTrue(pipeline.isIdentityConfirmed());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
    }

    @Test public void temperatureRejectsInvalidReadingsAndRecordsFeverAtThreshold() {
        CheckPipeline pipeline = pipeline(true);
        identify(pipeline);
        for (double value : new double[]{0, 33.9, 43.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            pipeline.onTemperature(value);
            assertEquals(Stage.IDENTITY, pipeline.getStage());
        }
        pipeline.onTemperature(37.3);
        assertEquals(Stage.HEAD, pipeline.getStage());
        assertEquals(37.3, pipeline.getTemperature(), 0.001);
        assertTrue(pipeline.getFindings().get(0).contains("体温偏高"));
    }

    @Test public void headCanBeDisabledWithoutFabricatingHeadFindings() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
        pipeline.onHeadDetections(Collections.emptyList(), true);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void identityAndTemperatureAdvanceTogetherInEitherArrivalOrder() {
        for (int temperatureAfterSample : new int[]{0, 2, 4}) {
            CheckPipeline pipeline = pipeline(true);
            for (int sample = 0; sample <= 4; sample++) {
                if (sample == temperatureAfterSample) {
                    pipeline.onTemperature(36.6);
                    assertEquals(36.6, pipeline.getTemperature(), 0.001);
                }
                if (sample < 4) {
                    assertEquals(Stage.IDENTITY, pipeline.getStage());
                    pipeline.onIdentity(21, true);
                }
            }
            assertEquals(Stage.HEAD, pipeline.getStage());
            assertEquals(21, pipeline.getPersonId());
        }
    }

    @Test public void confirmedIdentityKeepsCheckingSameEmployeeWhileWaitingForTemperature() {
        CheckPipeline pipeline = pipeline(true);
        identify(pipeline);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertEquals(4, pipeline.getProgress());
        pipeline.onIdentity(21, true);
        pipeline.onIdentity(21, true);
        assertEquals(4, pipeline.getProgress());
        assertEquals(21, pipeline.getCandidateId());
        assertEquals(21, pipeline.getPersonId());
        pipeline.onTemperature(36.6);
        assertEquals(Stage.HEAD, pipeline.getStage());
    }

    @Test public void changingCandidateClearsPreviousEmployeesTemperatureAndConfirmation() {
        CheckPipeline pipeline = pipeline(false);
        pipeline.onIdentity(10, true);
        pipeline.onTemperature(38.1);
        assertTrue(pipeline.getFindings().isEmpty());
        pipeline.onIdentity(21, true);
        assertEquals(21, pipeline.getCandidateId());
        assertEquals(1, pipeline.getProgress());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        identify(pipeline);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertTrue(pipeline.isIdentityConfirmed());
        pipeline.onIdentity(30, true);
        assertEquals(0, pipeline.getPersonId());
        assertFalse(pipeline.isIdentityConfirmed());
        pipeline.onTemperature(36.6);
        for (int sample = 0; sample < 3; sample++) pipeline.onIdentity(30, true);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertEquals(30, pipeline.getPersonId());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void unmatchedFrameClearsEarlyTemperatureAndConfirmedIdentity() {
        for (boolean confirmed : new boolean[]{false, true}) {
            CheckPipeline pipeline = pipeline(false);
            if (confirmed) identify(pipeline);
            else {
                pipeline.onTemperature(38.1);
                pipeline.onIdentity(21, true);
            }
            pipeline.onIdentity(0, false);
            assertEquals(0, pipeline.getCandidateId());
            assertEquals(0, pipeline.getPersonId());
            assertFalse(pipeline.isIdentityConfirmed());
            assertTrue(Double.isNaN(pipeline.getTemperature()));
            assertEquals(0, pipeline.getProgress());
            identify(pipeline);
            assertEquals(Stage.IDENTITY, pipeline.getStage());
            pipeline.onTemperature(36.6);
            assertEquals(Stage.HAND_BACK, pipeline.getStage());
            assertTrue(pipeline.getFindings().isEmpty());
        }
    }

    @Test public void latestValidTemperatureDeterminesFindingAfterIdentityFinishes() {
        CheckPipeline pipeline = pipeline(false);
        pipeline.onIdentity(21, true);
        pipeline.onTemperature(38.1);
        pipeline.onIdentity(21, true);
        pipeline.onTemperature(35.5);
        pipeline.onIdentity(21, true);
        pipeline.onTemperature(36.6);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertEquals(36.6, pipeline.getTemperature(), 0.001);
        assertTrue(pipeline.getFindings().isEmpty());
        pipeline.onIdentity(21, true);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void invalidTemperatureClearsEarlierReadingWhileIdentityIsPending() {
        CheckPipeline pipeline = pipeline(false);
        pipeline.onTemperature(36.6);
        pipeline.onIdentity(21, true);
        pipeline.onTemperature(Double.NaN);
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        identify(pipeline);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        pipeline.onTemperature(38.1);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertEquals(Collections.singletonList("体温偏高：38.1°C"), pipeline.getFindings());
    }

    @Test public void pendingTemperatureCannotSurviveResetForNextEmployee() {
        CheckPipeline pipeline = pipeline(false);
        pipeline.onTemperature(38.1);
        pipeline.onIdentity(10, true);
        pipeline.reset();
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        assertEquals(0, pipeline.getCandidateId());
        identify(pipeline);
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void normalTemperatureMatchesOldBusinessExclusiveBounds() {
        for (double value : new double[]{34.0, 35.9, 36.0, 37.3, 43.0}) {
            CheckPipeline pipeline = pipeline(false);
            identify(pipeline);
            pipeline.onTemperature(value);
            assertEquals(Stage.HAND_BACK, pipeline.getStage());
            assertEquals(1, pipeline.getFindings().size());
        }
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.1);
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void skippedTemperatureIsExplicitAndNeverNormalMeasurement() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.skipTemperature();
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.isTemperatureSkipped());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        assertTrue(pipeline.getHint().contains("体温未测"));
        pipeline.reset();
        assertFalse(pipeline.isTemperatureSkipped());
    }

    @Test public void skippedTemperatureWaitsForIdentityAndSurvivesCandidateLoss() {
        CheckPipeline pipeline = pipeline(false);
        pipeline.onTemperature(38.1);
        pipeline.skipTemperature();
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        pipeline.onTemperature(36.6);
        pipeline.onIdentity(10, true);
        pipeline.onIdentity(21, true);
        pipeline.onIdentity(0, false);
        pipeline.interruptSamples();
        assertTrue(pipeline.isTemperatureSkipped());
        identify(pipeline);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void interruptionClearsPendingIdentityAndTemperatureTogether() {
        for (boolean confirmed : new boolean[]{false, true}) {
            CheckPipeline pipeline = pipeline(false);
            if (confirmed) identify(pipeline);
            else {
                pipeline.onIdentity(21, true);
                pipeline.onTemperature(38.1);
            }
            pipeline.interruptSamples();
            assertEquals(Stage.IDENTITY, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
            assertEquals(0, pipeline.getCandidateId());
            assertEquals(0, pipeline.getPersonId());
            assertTrue(Double.isNaN(pipeline.getTemperature()));
            identify(pipeline);
            assertEquals(Stage.IDENTITY, pipeline.getStage());
            pipeline.onTemperature(36.6);
            assertEquals(Stage.HAND_BACK, pipeline.getStage());
        }
    }

    @Test public void interruptionRestartsHeadSamplesWithoutDiscardingCommittedTemperature() {
        CheckPipeline pipeline = pipeline(true);
        identify(pipeline);
        pipeline.onTemperature(38.1);
        for (int sample = 0; sample < 3; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        assertEquals(3, pipeline.getProgress());
        pipeline.interruptSamples();
        assertEquals(Stage.HEAD, pipeline.getStage());
        assertEquals(0, pipeline.getProgress());
        assertEquals(21, pipeline.getPersonId());
        assertEquals(38.1, pipeline.getTemperature(), 0.001);
        assertEquals(Collections.singletonList("体温偏高：38.1°C"), pipeline.getFindings());
        for (int sample = 1; sample < 4; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
            assertEquals(Stage.HEAD, pipeline.getStage());
            assertEquals(sample, pipeline.getProgress());
        }
        pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertEquals(Collections.singletonList("体温偏高：38.1°C"), pipeline.getFindings());
    }

    @Test public void interruptionClearsUncommittedHandFindingsWithoutDiscardingEmployee() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_WOUND));
        pipeline.interruptSamples();
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertEquals(0, pipeline.getProgress());
        assertEquals(21, pipeline.getPersonId());
        assertEquals(36.6, pipeline.getTemperature(), 0.001);
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        assertEquals(Stage.HAND_PALM, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void emptyAndUnknownHeadBoxesCannotAdvance() {
        CheckPipeline pipeline = pipeline(true);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        pipeline.onDetections(boxes(HEAD_HAT, HEAD_MASK));
        assertEquals(1, pipeline.getProgress());
        pipeline.onDetections(Collections.emptyList());
        pipeline.onDetections(boxes(123));
        pipeline.onDetections(null);
        assertEquals(Stage.HEAD, pipeline.getStage());
        assertEquals(0, pipeline.getProgress());
    }

    @Test public void missingHatMaskOrBothCannotAdvanceEvenWithValidFace() {
        List<List<CheckPipeline.Detection>> frames = Arrays.asList(
                boxes(HEAD_MASK), boxes(HEAD_HAT), Collections.emptyList());
        List<List<CheckPipeline.Issue>> expectedIssues = Arrays.asList(
                Collections.singletonList(CheckPipeline.Issue.HAT_MISSING),
                Collections.singletonList(CheckPipeline.Issue.MASK_MISSING),
                Arrays.asList(CheckPipeline.Issue.HAT_MISSING, CheckPipeline.Issue.MASK_MISSING));
        List<List<String>> expectedWarnings = Arrays.asList(
                Collections.singletonList("未佩戴工作帽"), Collections.singletonList("未佩戴口罩"),
                Arrays.asList("未佩戴工作帽", "未佩戴口罩"));
        for (int missing = 0; missing < frames.size(); missing++) {
            CheckPipeline pipeline = pipeline(true);
            identify(pipeline);
            pipeline.onTemperature(36.6);
            for (int sample = 0; sample < 8; sample++) {
                pipeline.onHeadDetections(frames.get(missing), true);
                assertEquals(Stage.HEAD, pipeline.getStage());
                assertEquals(0, pipeline.getProgress());
                assertEquals(expectedIssues.get(missing), pipeline.getCurrentIssues());
                assertEquals(expectedWarnings.get(missing), pipeline.getDisplayFindings());
                assertTrue(pipeline.getFindings().isEmpty());
            }
        }
    }

    @Test public void missingAttireRestartsFourConsecutiveSamplesAndCorrectionLeavesNoFinding() {
        CheckPipeline pipeline = pipeline(true);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        for (int sample = 0; sample < 3; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        }
        assertEquals(3, pipeline.getProgress());
        pipeline.onHeadDetections(boxes(HEAD_HAT), true);
        assertEquals(Stage.HEAD, pipeline.getStage());
        assertEquals(0, pipeline.getProgress());
        for (int sample = 1; sample < 4; sample++) {
            pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
            assertEquals(Stage.HEAD, pipeline.getStage());
            assertEquals(sample, pipeline.getProgress());
            assertTrue(pipeline.getDisplayFindings().isEmpty());
            assertTrue(pipeline.getCurrentIssues().isEmpty());
        }
        pipeline.onHeadDetections(boxes(HEAD_HAT, HEAD_MASK), true);
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        assertEquals(Stage.RESULT, pipeline.getStage());
        assertTrue(pipeline.getFindings().isEmpty());
        assertTrue(pipeline.getDisplayFindings().isEmpty());
    }

    @Test public void everyAttireRuleCombinationRejectsMismatchAndAcceptsOnlyMatchingSamples() {
        for (boolean requireHat : new boolean[]{false, true}) {
            for (boolean requireMask : new boolean[]{false, true}) {
                for (boolean hasHat : new boolean[]{false, true}) {
                    for (boolean hasMask : new boolean[]{false, true}) {
                        CheckPipeline pipeline = new CheckPipeline(new CheckPipeline.Rules(
                                4, 4, 2, true, requireHat, requireMask, 37.3));
                        identify(pipeline);
                        pipeline.onTemperature(36.6);
                        boolean matching = requireHat == hasHat && requireMask == hasMask;
                        for (int sample = 1; sample <= 4; sample++) {
                            pipeline.onHeadDetections(attire(hasHat, hasMask), true);
                            assertEquals(matching && sample == 4 ? Stage.HAND_BACK : Stage.HEAD,
                                    pipeline.getStage());
                            assertEquals(matching && sample < 4 ? sample : 0, pipeline.getProgress());
                            assertEquals(matching, pipeline.getCurrentIssues().isEmpty());
                            assertTrue(pipeline.getFindings().isEmpty());
                        }
                    }
                }
            }
        }
    }

    @Test public void attireRulesStillRequireCurrentFaceEvidenceWhenNoAttireIsExpected() {
        CheckPipeline pipeline = new CheckPipeline(new CheckPipeline.Rules(
                4, 4, 2, true, false, false, 37.3));
        identify(pipeline);
        pipeline.onTemperature(36.6);
        for (int sample = 0; sample < 3; sample++) {
            pipeline.onHeadDetections(Collections.emptyList(), true);
        }
        pipeline.onHeadDetections(Collections.emptyList(), false);
        assertEquals(Stage.HEAD, pipeline.getStage());
        assertEquals(0, pipeline.getProgress());
        assertTrue(pipeline.getCurrentIssues().isEmpty());
        assertTrue(pipeline.getFindings().isEmpty());
    }

    @Test public void handRequiresTwoCorrectHandsWithoutOppositeOrInvalidPose() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        List<List<CheckPipeline.Detection>> invalid = Arrays.asList(
                Collections.emptyList(), boxes(HAND_BACK), boxes(HAND_PALM, HAND_PALM),
                boxes(HAND_BACK, HAND_BACK, HAND_PALM),
                boxes(HAND_BACK, HAND_BACK, HAND_INCORRECT),
                boxes(HAND_BACK, HAND_BACK, HAND_GLOVE));
        for (List<CheckPipeline.Detection> frame : invalid) {
            pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
            assertEquals(1, pipeline.getProgress());
            pipeline.onDetections(frame);
            assertEquals(Stage.HAND_BACK, pipeline.getStage());
            assertEquals(0, pipeline.getProgress());
        }
    }

    @Test public void abnormalitiesOnlyComeFromQualifiedConsecutiveHandFrames() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        pipeline.onDetections(boxes(HAND_RING, HAND_NAIL));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_WOUND));
        pipeline.onDetections(Collections.emptyList());
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK, HAND_RING));
        assertEquals(Stage.HAND_PALM, pipeline.getStage());
        assertEquals(Collections.singletonList("手背：戒指"), pipeline.getFindings());
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM, HAND_PLASTER));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        assertEquals(Stage.RESULT, pipeline.getStage());
        assertEquals(Arrays.asList("手背：戒指", "手心：创可贴"), pipeline.getFindings());
    }

    @Test public void resultIsStableUntilResetAndFindingsAreSnapshot() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(38);
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_BACK, HAND_BACK));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM));
        List<String> findings = pipeline.getFindings();
        pipeline.onIdentity(99, true);
        pipeline.onTemperature(36.6);
        pipeline.onDetections(boxes(HAND_PALM, HAND_PALM, HAND_WOUND));
        pipeline.interruptSamples();
        assertEquals(Stage.RESULT, pipeline.getStage());
        assertEquals(21, pipeline.getPersonId());
        pipeline.reset();
        assertEquals(Stage.IDENTITY, pipeline.getStage());
        assertEquals(0, pipeline.getPersonId());
        assertEquals(0, pipeline.getProgress());
        assertTrue(Double.isNaN(pipeline.getTemperature()));
        assertTrue(pipeline.getFindings().isEmpty());
        assertEquals(1, findings.size());
    }

    @Test public void invalidConfidenceCannotProvidePoseEvidence() {
        CheckPipeline pipeline = pipeline(false);
        identify(pipeline);
        pipeline.onTemperature(36.6);
        pipeline.onDetections(Arrays.asList(
                new CheckPipeline.Detection(HAND_BACK, Float.NaN),
                new CheckPipeline.Detection(HAND_BACK, 0.9f),
                new CheckPipeline.Detection(HAND_BACK, 2f),
                new CheckPipeline.Detection(HAND_BACK, 0f)));
        assertEquals(0, pipeline.getProgress());
        assertEquals(Stage.HAND_BACK, pipeline.getStage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectZeroSampleConfiguration() {
        new CheckPipeline.Rules(0, 2, 2, true, true, true, 37.3);
    }
}
