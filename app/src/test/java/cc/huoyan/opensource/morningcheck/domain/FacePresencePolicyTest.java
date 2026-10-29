package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import static org.junit.Assert.*;

public final class FacePresencePolicyTest {
    private static final long SECOND = 1_000_000_000L;
    private final FacePresencePolicy policy = new FacePresencePolicy();

    @Test public void twoFacesAreMultipleAndNewSingleFaceClearsWarning() {
        assertTrue(policy.observeDetection(SECOND, 2, SECOND));
        assertTrue(policy.isMultiple(SECOND));
        assertTrue(policy.observeDetection(2 * SECOND, 1, 2 * SECOND));
        assertFalse(policy.isMultiple(2 * SECOND));
        assertTrue(policy.isPresent(2 * SECOND));
        assertTrue(policy.observeDetection(3 * SECOND, 0, 3 * SECOND));
        assertTrue(policy.isAbsent(3 * SECOND));
    }

    @Test public void detectionReceiptHasShortGuidanceLifetimeAndBoundedSourceAge() {
        assertTrue(policy.observeDetection(SECOND, 3, 3 * SECOND));
        assertTrue(policy.isMultiple(3 * SECOND));
        assertFalse(policy.isMultiple(3 * SECOND + FacePresencePolicy.MAX_AGE_NANOS));
        assertFalse(policy.observeDetection(SECOND, 1, 6 * SECOND));
        assertTrue(policy.observeDetection(2 * SECOND, 2, 6 * SECOND));
        assertEquals(7 * SECOND, policy.expiresAtNanos());
        assertFalse(policy.isMultiple(7 * SECOND));
    }

    @Test public void onlyAvailableFreshEmptyFramesCanStartAbsenceGuidance() {
        assertFalse(policy.isAbsent(0));
        assertTrue(policy.observe(1_000, false, 2_000));
        assertTrue(policy.isAbsent(2_000));
        assertFalse(policy.isAbsent(1_000 + FacePresencePolicy.MAX_AGE_NANOS));
        assertFalse(policy.observe(3_000, false, 3_000 + FacePresencePolicy.MAX_AGE_NANOS));
        assertFalse(policy.observe(5_000, false, 4_000));
    }

    @Test public void sdkMatchedProofWinsAgainstSameFramePreviewEmptyOrFailure() {
        policy.observe(1_000, false, 2_000);
        assertTrue(policy.observe(1_000, true, 2_000));
        assertFalse(policy.observe(1_000, false, 3_000));
        assertFalse(policy.unknown(1_000));
        assertFalse(policy.isAbsent(3_000));
    }

    @Test public void olderAsyncResultCannotOverwriteNewFaceProof() {
        policy.observe(2_000, true, 3_000);
        assertFalse(policy.observe(1_000, false, 3_000));
        assertFalse(policy.unknown(1_000));
        assertFalse(policy.isAbsent(3_000));
    }

    @Test public void newUnavailableResultCancelsAbsenceUntilFreshEmptyEvidenceArrives() {
        policy.observe(1_000, false, 2_000);
        assertTrue(policy.unknown(2_000));
        assertFalse(policy.isAbsent(3_000));
        assertEquals(0, policy.expiresAtNanos());
        policy.observe(3_000, false, 4_000);
        assertTrue(policy.isAbsent(4_000));
        assertEquals(3_000 + FacePresencePolicy.MAX_AGE_NANOS, policy.expiresAtNanos());
    }

    @Test public void stageResetCannotCarryOldAbsenceIntoTheNextStage() {
        policy.observe(1_000, false, 2_000);
        policy.reset();
        assertFalse(policy.isAbsent(2_000));
        assertEquals(0, policy.expiresAtNanos());
        assertTrue(policy.observe(500, true, 2_000));
    }

    @Test public void unmatchedNeedsBothRealFacePresenceAndAnExplicitFreshNegativeMatch() {
        policy.match(1_000, false, 2_000);
        assertFalse(policy.isUnmatched(2_000));
        policy.observe(1_100, true, 2_000);
        assertTrue(policy.isUnmatched(2_000));
        assertFalse(policy.isAbsent(2_000));
        assertEquals(1_100 + FacePresencePolicy.MAX_AGE_NANOS, policy.expiresAtNanos());
        assertFalse(policy.isUnmatched(1_100 + FacePresencePolicy.MAX_AGE_NANOS));
    }

    @Test public void absentAndUnmatchedRemainSeparateWhenThePersonLeavesTheImage() {
        policy.observe(1_000, true, 2_000);
        policy.match(1_000, false, 2_000);
        policy.observe(2_000, false, 3_000);
        assertTrue(policy.isAbsent(3_000));
        assertFalse(policy.isUnmatched(3_000));
    }

    @Test public void noMatchResponseOrFailedResponseCannotBecomeUnmatched() {
        policy.observe(1_000, true, 2_000);
        assertFalse(policy.isUnmatched(2_000));
        policy.match(1_000, false, 2_000);
        policy.matchingUnknown(2_000);
        assertFalse(policy.isUnmatched(3_000));
    }

    @Test public void validMatchCancelsUnmatchedAndOlderNegativeCannotRestoreIt() {
        policy.observe(1_000, true, 2_000);
        policy.match(1_000, false, 2_000);
        assertTrue(policy.match(2_000, true, 3_000));
        assertTrue(policy.isMatched(3_000));
        assertFalse(policy.isUnmatched(3_000));
        assertFalse(policy.match(1_000, false, 3_000));
        assertFalse(policy.matchingUnknown(1_000));
        assertFalse(policy.isUnmatched(3_000));
    }

    @Test public void twoSecondNegativeReceiptUsesFreshPreviewInsteadOfOldSourcePresence() {
        long source = SECOND;
        long completed = 3 * SECOND;
        assertTrue("正常两秒推理延迟不能丢掉有效未匹配回执", policy.match(source, false, completed));
        assertFalse("负匹配本身不证明当前画面有人脸", policy.isUnmatched(completed));
        assertTrue(policy.observe(completed, true, completed));
        assertTrue(policy.isUnmatched(completed));
        assertEquals(completed + FacePresencePolicy.MAX_AGE_NANOS, policy.expiresAtNanos());
    }

    @Test public void negativeSourceAtFiveSecondAgeOrFromFutureIsRejected() {
        long now = 6 * SECOND;
        policy.observe(now, true, now);
        assertFalse("五秒前的采集帧不能被新完成时间重新变成有效证据", policy.match(SECOND, false, now));
        assertFalse(policy.isUnmatched(now));
        assertFalse(policy.match(now + 1, false, now));
        assertFalse(policy.isUnmatched(now));
    }

    @Test public void negativeExpiresFiveSecondsAfterReceiptEvenWithContinuingFreshFaces() {
        long completed = 3 * SECOND;
        long expires = completed + 5 * SECOND;
        assertTrue(policy.match(SECOND, false, completed));
        policy.observe(expires - SECOND / 10, true, expires - SECOND / 10);
        assertTrue("有效期不能仍按两秒前的采集时间计算", policy.isUnmatched(expires - 1));
        assertEquals(expires, policy.expiresAtNanos());
        assertFalse("完成后满五秒即撤销负匹配", policy.isUnmatched(expires));
        policy.observe(expires + SECOND / 10, true, expires + SECOND / 10);
        assertFalse("更新预览不能续期已过期的负匹配", policy.isUnmatched(expires + SECOND / 10));
    }

    @Test public void positiveMatchStillNeedsSourceWithinPreviewFreshnessLimit() {
        long source = 10 * SECOND;
        long expires = source + FacePresencePolicy.MAX_AGE_NANOS;
        assertTrue(policy.match(source, true, expires - 1));
        assertTrue(policy.isMatched(expires - 1));
        policy.observe(expires, true, expires);
        assertFalse("正匹配的着装框不能因完成时间延长原帧时效", policy.isMatched(expires));
        FacePresencePolicy stalePositive = new FacePresencePolicy();
        assertFalse(stalePositive.match(source, true, expires));
        assertFalse(stalePositive.isMatched(expires));
    }

    @Test public void laterPositiveOrUnknownMatchCannotBeOverwrittenByLateOlderNegative() {
        policy.observe(3 * SECOND, true, 3 * SECOND);
        assertTrue(policy.match(SECOND, false, 3 * SECOND));
        assertTrue(policy.match(4 * SECOND, true, 4 * SECOND + SECOND / 10));
        assertFalse(policy.match(3 * SECOND, false, 4 * SECOND + SECOND / 5));
        assertTrue(policy.isMatched(4 * SECOND + SECOND / 5));
        assertFalse(policy.isUnmatched(4 * SECOND + SECOND / 5));

        assertTrue(policy.matchingUnknown(5 * SECOND));
        policy.observe(5 * SECOND, true, 5 * SECOND);
        assertFalse(policy.match(4 * SECOND, false, 5 * SECOND + SECOND / 10));
        assertFalse(policy.isUnmatched(5 * SECOND + SECOND / 10));
    }

    @Test public void unavailablePreviewOnlySuspendsUnmatchedUntilRealFaceReturns() {
        long completed = 3 * SECOND;
        policy.match(SECOND, false, completed);
        policy.observe(completed, true, completed);
        assertTrue(policy.isUnmatched(completed));
        assertTrue(policy.unknown(completed + SECOND / 10));
        assertFalse(policy.isUnmatched(completed + SECOND / 10));
        assertTrue(policy.observe(completed + SECOND / 5, true, completed + SECOND / 5));
        assertTrue("预览暂不可用不能抹掉仍有效的未匹配回执", policy.isUnmatched(completed + SECOND / 5));
    }

    @Test public void acceptedNegativeNeverMeansUnmatchedWhenCurrentPreviewHasNoFace() {
        long completed = 3 * SECOND;
        policy.observe(completed, false, completed);
        assertTrue(policy.match(SECOND, false, completed));
        assertTrue(policy.isAbsent(completed));
        assertFalse("无人脸的未命中属于未入镜，不能播未匹配员工", policy.isUnmatched(completed));
        assertEquals(completed + FacePresencePolicy.MAX_AGE_NANOS, policy.expiresAtNanos());
    }

    @Test public void delayedPositiveCancelsOlderNegativeWithoutExtendingFaceBoxFreshness() {
        policy.match(SECOND, false, 3 * SECOND);
        policy.observe(3 * SECOND, true, 3 * SECOND);
        assertTrue(policy.isUnmatched(3 * SECOND));
        policy.observe(5 * SECOND, true, 5 * SECOND);
        assertFalse("迟到正匹配不能作为新鲜头部框证据", policy.match(3 * SECOND, true, 5 * SECOND));
        assertFalse("但明确正匹配必须立即撤销此前的未匹配结论", policy.isUnmatched(5 * SECOND));
        assertFalse(policy.isMatched(5 * SECOND));
        assertFalse("旧负回包不能重新恢复已撤销结论", policy.match(2 * SECOND, false, 5 * SECOND));
        assertFalse(policy.isUnmatched(5 * SECOND));
    }

    @Test public void sameFrameNegativeCannotOverrideAcceptedPositive() {
        long source = SECOND;
        assertTrue(policy.match(source, true, source + SECOND / 10));
        assertTrue(policy.isMatched(source + SECOND / 10));
        assertFalse("同帧肯定匹配优先，负结果不得覆盖", policy.match(source, false, source + SECOND / 5));
        assertTrue(policy.isMatched(source + SECOND / 5));
        assertFalse(policy.isUnmatched(source + SECOND / 5));
    }
}
