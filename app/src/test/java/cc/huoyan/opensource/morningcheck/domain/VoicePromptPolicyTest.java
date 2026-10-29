package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import static org.junit.Assert.*;

public final class VoicePromptPolicyTest {
    @Test public void renewedAbsenceStillNeedsFiveSecondsOfContinuousEvidence() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_absent:IDENTITY", "请入镜摄像头", 5000, 5000, 0);
        assertEquals("请入镜摄像头", policy.poll(5000));
        policy.clearCurrent();
        policy.offer("face_absent:IDENTITY", "请入镜摄像头", 5000, 5000, 12000);
        assertNull("前次冷却已结束也不能跳过新的缺脸稳定窗口", policy.poll(12000));
        policy.offer("face_absent:IDENTITY", "请入镜摄像头", 5000, 5000, 16000);
        assertNull(policy.poll(16999));
        assertEquals("请入镜摄像头", policy.poll(17000));
    }

    @Test public void repeatedFramesDoNotRestartStableWindowOrRepeatSpeech() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        for (int now = 0; now < 1200; now += 20) {
            policy.offer("posture", "请摘下手套", 1200, 10000, now);
            assertNull(policy.poll(now));
        }
        assertEquals("请摘下手套", policy.poll(1200));
        for (int now = 1220; now < 11200; now += 20) {
            policy.offer("posture", "请摘下手套", 1200, 10000, now);
            assertNull(policy.poll(now));
        }
        assertEquals("请摘下手套", policy.poll(11200));
    }

    @Test public void changingPoseDropsOldPendingPromptAndRequiresNewStablePeriod() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("glove", "请摘下手套", 1200, 10000, 0);
        policy.offer("palm", "请将手心朝向镜头", 1200, 10000, 600);
        assertNull(policy.poll(1200));
        assertEquals("请将手心朝向镜头", policy.poll(1800));
    }

    @Test public void phaseTransitionReplacesUnspokenFailureAndDoesNotQueueHistory() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("failure", "SDK 正忙", 1200, 15000, 0);
        policy.offer("head", "检查头部", 0, 0, 800);
        assertEquals("检查头部", policy.poll(800));
        assertNull(policy.poll(20000));
        assertEquals(Long.MAX_VALUE, policy.nextAt());
    }

    @Test public void transientPoseErrorReturningToSameStageDoesNotRestartAlreadySpokenStage() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("head", "检查头部", 0, 15000, 0);
        assertEquals("检查头部", policy.poll(0));
        for (int index = 1; index < 10; index++) {
            long now = index * 500;
            policy.offer("posture", "请正视镜头", 1200, 10000, now);
            assertNull(policy.poll(now));
            policy.offer("head", "检查头部", 0, 15000, now + 100);
            assertNull(policy.poll(now + 100));
        }
        assertEquals("检查头部", policy.poll(15000));
        policy.offer("posture", "请正视镜头", 1200, 10000, 16000);
        assertEquals("请正视镜头", policy.poll(17200));
        policy.offer("head", "检查头部", 0, 15000, 18000);
        assertNull(policy.poll(18000));
        assertEquals(30000, policy.nextAt());
        assertNull(policy.poll(29999));
        assertEquals("检查头部", policy.poll(30000));
    }

    @Test public void pendingInitializationKeepsOnlyLatestPhaseUntilActuallyPolled() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("identity", "正视镜头", 0, 0, 0);
        policy.offer("head", "检查头部", 0, 0, 500);
        assertFalse(policy.hasSpoken());
        assertEquals("检查头部", policy.poll(2000));
        assertTrue(policy.hasSpoken());
        assertNull(policy.poll(2001));
    }

    @Test public void pauseResetDiscardsPendingPromptAndReturnCanReannounceCurrentStep() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("back", "检查手背", 0, 0, 0);
        assertEquals("检查手背", policy.poll(0));
        policy.offer("old_error", "旧错误", 1200, 0, 100);
        policy.reset();
        assertNull(policy.poll(3000));
        policy.offer("back", "继续检查手背", 0, 0, 3000);
        assertEquals("继续检查手背", policy.poll(3000));
    }

    @Test public void stageEventsAreOrderedAndEachKeyIsAnnouncedOncePerRound() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offerOnce("face", "请正视镜头。");
        policy.offerOnce("head", "检查头部。");
        policy.offerOnce("back", "请伸出手背。");
        policy.offerOnce("palm", "请翻转手心。");
        policy.offerOnce("result", "晨检正常。");
        for (int frame = 0; frame < 100; frame++) policy.offerOnce("head", "检查头部。");
        for (String expected : new String[]{"请正视镜头。", "检查头部。", "请伸出手背。", "请翻转手心。", "晨检正常。"})
            assertEquals(expected, policy.poll(0));
        policy.offer("pose", "请调整手部位置。", 0, 0, 1);
        assertEquals("请调整手部位置。", policy.poll(1));
        policy.offerOnce("back", "请伸出手背。");
        policy.clearCurrent();
        assertNull("姿态恢复不能重复已播阶段", policy.poll(30000));
        policy.reset();
        policy.offerOnce("face", "请正视镜头。");
        assertEquals("新一轮晨检重新播第一步", "请正视镜头。", policy.poll(30001));
    }

    @Test public void cancelPendingPreservesAcceptedStagesAndAllowsUnspokenResultAgain() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offerOnce("back", "请伸出手背。");
        assertNotNull(policy.poll(0));
        policy.offerOnce("result", "晨检正常。");
        policy.cancelPending();
        assertTrue(policy.hasAnnounced("back"));
        assertFalse(policy.hasAnnounced("result"));
        policy.offerOnce("back", "请伸出手背。");
        policy.offerOnce("result", "晨检正常。");
        assertEquals("晨检正常。", policy.poll(1));
        assertNull(policy.poll(2));
    }

    @Test public void restoredResultIsNotAnnouncedAgainButRejectedSpeechCanBeRequestedAgain() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.restoreAnnounced("saved");
        policy.offerOnce("saved", "旧结论");
        assertNull(policy.poll(0));
        policy.offerOnce("new", "新结论");
        assertEquals("新结论", policy.poll(1));
        policy.speechRejected();
        assertFalse(policy.hasAnnounced("new"));
        policy.offerOnce("new", "新结论");
        assertEquals("新结论", policy.poll(2));
    }

    @Test public void oneTimeStatusDoesNotRepeatAfterOtherStatusWasSpoken() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("head", "头部检查", 0, 0, 0);
        assertEquals("头部检查", policy.poll(0));
        policy.offer("busy", "设备正忙", 0, 0, 1);
        assertEquals("设备正忙", policy.poll(1));
        policy.offer("head", "头部检查", 0, 0, 2);
        assertNull(policy.poll(2));
    }

    @Test public void rejectedStatusOnlyRetriesAfterNextOfferAndNewStableWindow() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("camera", "请检查摄像头。", 1200, 0, 0);
        assertNotNull(policy.poll(1200));
        policy.speechRejected();
        assertFalse(policy.hasAnnounced("camera"));
        assertEquals("拒绝后不能立即自旋重试", Long.MAX_VALUE, policy.nextAt());
        policy.offer("camera", "请检查摄像头。", 1200, 0, 1300);
        assertNull(policy.poll(2499));
        assertEquals("请检查摄像头。", policy.poll(2500));
    }

    @Test public void unmatchedEmployeeStartsImmediatelyWithoutAStableDelay() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 0);
        assertEquals("未匹配员工", policy.poll(0));
        for (int now = 100; now < 5000; now += 100) {
            policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, now);
            assertNull(policy.poll(now));
        }
        assertEquals("未匹配员工", policy.poll(5000));
    }

    @Test public void temporaryUnknownFrameCannotClearTheUnmatchedRepeatCooldown() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 0);
        assertEquals("未匹配员工", policy.poll(0));
        policy.clearCurrent();
        assertEquals(Long.MAX_VALUE, policy.nextAt());
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 800);
        assertNull(policy.poll(800));
        assertEquals(5000, policy.nextAt());
        policy.clearCurrent();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 4900);
        assertNull(policy.poll(4999));
        assertEquals("未匹配员工", policy.poll(5000));
    }

    @Test public void absentAndUnmatchedKeepTheirOwnCooldownWhenTheStatusAlternates() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_unmatched:HEAD", "未匹配员工", 0, 5000, 0);
        assertEquals("未匹配员工", policy.poll(0));
        policy.offer("face_absent:HEAD", "请入镜摄像头", 0, 5000, 1000);
        assertEquals("请入镜摄像头", policy.poll(1000));
        policy.offer("face_unmatched:HEAD", "未匹配员工", 0, 5000, 2000);
        assertNull(policy.poll(2000));
        assertEquals(5000, policy.nextAt());
        policy.offer("face_absent:HEAD", "请入镜摄像头", 0, 5000, 3000);
        assertNull(policy.poll(3000));
        assertEquals(6000, policy.nextAt());
        policy.offer("face_unmatched:HEAD", "未匹配员工", 0, 5000, 5000);
        assertEquals("未匹配员工", policy.poll(5000));
        policy.offer("face_absent:HEAD", "请入镜摄像头", 0, 5000, 5999);
        assertNull(policy.poll(5999));
        assertEquals("请入镜摄像头", policy.poll(6000));
    }

    @Test public void rejectedUnmatchedSpeechDoesNotConsumeTheFiveSecondCooldown() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 0);
        assertEquals("未匹配员工", policy.poll(0));
        policy.speechRejected();
        assertFalse(policy.hasAnnounced("face_unmatched:IDENTITY"));
        assertEquals("拒绝后等待下一次请求，不能在内部自旋", Long.MAX_VALUE, policy.nextAt());
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 100);
        assertEquals("未匹配员工", policy.poll(100));
        assertNull(policy.poll(5099));
        assertEquals("未匹配员工", policy.poll(5100));
    }

    @Test public void newCheckRoundClearsStatusCooldownAndCanAnnounceItsFirstMismatch() {
        VoicePromptPolicy policy = new VoicePromptPolicy();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 1000);
        assertEquals("未匹配员工", policy.poll(1000));
        policy.reset();
        policy.offer("face_unmatched:IDENTITY", "未匹配员工", 0, 5000, 1100);
        assertEquals("未匹配员工", policy.poll(1100));
        assertEquals(6100, policy.nextAt());
    }
}
