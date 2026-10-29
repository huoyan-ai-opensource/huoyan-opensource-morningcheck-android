package cc.huoyan.opensource.morningcheck;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.json.JSONArray;
import org.json.JSONObject;

import cc.huoyan.opensource.morningcheck.sdk.MorningSdkSession;
import cc.huoyan.opensource.morningcheck.domain.CheckPipeline;
import cc.huoyan.opensource.morningcheck.domain.DetectionClasses;
import cc.huoyan.opensource.morningcheck.domain.FacePresencePolicy;
import cc.huoyan.opensource.morningcheck.domain.CaptureSession;
import cc.huoyan.opensource.morningcheck.camera.CameraSource;

import static org.junit.Assert.*;

/** 假语音引擎验证实际页面语音出口的初始化、开关及生命周期，不发声、不打开硬件。 */
@RunWith(AndroidJUnit4.class)
public final class MorningVoiceLifecycleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final FakeSpeaker engine = new FakeSpeaker();
    private MorningVoice.ReadyListener ready;
    private MorningVoice voice;
    private final List<ResultActivity> resultFixtures = new ArrayList<>();
    private final List<CheckActivity> checkFixtures = new ArrayList<>();
    private final String preferenceName = "voice_stage_test_" + SystemClock.elapsedRealtimeNanos();

    @Before public void createVoice() {
        onMain(() -> {
            voice = new MorningVoice(new Handler(Looper.getMainLooper()), enabled::get, listener -> {
                ready = listener;
                return engine;
            });
            voice.resume();
        });
    }

    @After public void closeVoice() {
        onMain(() -> {
            for (CheckActivity check : checkFixtures) {
                try {
                    Field expiry = CheckActivity.class.getDeclaredField("facePresenceExpiry");
                    expiry.setAccessible(true);
                    check.main.removeCallbacks((Runnable) expiry.get(check));
                } catch (Exception error) { throw new AssertionError(error); }
                if (check.sdkSession != null) check.sdkSession.client().destroy();
                check.overlay = null;
                check.foreground = false;
                check.destroyed = true;
            }
            for (ResultActivity result : resultFixtures) {
                try { removeAutomaticNext(result); }
                catch (Exception error) { throw new AssertionError(error); }
                if (result.sdkSession != null) result.sdkSession.client().destroy();
                result.foreground = false;
            }
            voice.close();
            instrumentation.getTargetContext().getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        });
        instrumentation.waitForIdleSync();
        assertTrue(engine.closed);
    }

    @Test public void delayedInitializationOnlyAnnouncesCurrentStageWithoutOldFaceReminders() {
        onMain(() -> {
            voice.enterStage("identity", "请正视摄像头");
            voice.update("identity_absent", "请入镜摄像头", 0, 1000);
            voice.enterStage("head", "请佩戴口罩、工作帽检测");
        });
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        onMain(() -> assertTrue("结果等待异步初始化期间不能直接自动下一位", voice.awaitingOrSpeaking()));
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("请佩戴口罩、工作帽检测", engine.spoken.get(0));
        int stops = engine.stopped;
        onMain(() -> { for (int frame = 0; frame < 100; frame++) voice.enterStage("head", "请佩戴口罩、工作帽检测"); });
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("同阶段每帧不得停止已开始的提示", stops, engine.stopped);
    }

    @Test public void initializationOrChineseLanguageFailureNeverSpeaksOrBlocksCaller() {
        onMain(() -> voice.update("identity", "正视镜头", 0, 0));
        ready.ready(TextToSpeech.ERROR);
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        onMain(() -> assertFalse("引擎明确失败不能锁住自动下一位", voice.awaitingOrSpeaking()));
        engine.language = TextToSpeech.LANG_NOT_SUPPORTED;
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        onMain(() -> voice.update("head", "检查头部", 0, 0));
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
    }

    @Test public void disabledVoiceRejectsInitialAndLaterPromptsAndStopsCurrentSpeech() {
        enabled.set(false);
        onMain(() -> voice.update("identity", "正视镜头", 0, 0));
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        enabled.set(true);
        onMain(() -> voice.update("head", "检查头部", 0, 0));
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        enabled.set(false);
        onMain(() -> voice.update("back", "检查手背", 0, 0));
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertTrue(engine.stopped > 0);
    }

    @Test public void postureBlipIsDiscardedWhenCurrentPhaseChanges() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            voice.update("glove", "请摘下手套", 150, 0);
            voice.update("palm", "翻转双手，检查手心", 0, 0);
        });
        SystemClock.sleep(220);
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("翻转双手，检查手心", engine.spoken.get(0));
    }

    @Test public void postureBlipReturningToAlreadySpokenStageDoesNotReplayIt() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> voice.update("head", "检查头部", 0, 15000));
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        onMain(() -> {
            voice.update("posture", "请保持面部在画面内", 150, 10000);
            voice.update("head", "检查头部", 0, 15000);
        });
        SystemClock.sleep(220);
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
    }

    @Test public void pauseBeforeEngineReadyAndDestroyCancelPendingAnnouncements() {
        onMain(() -> {
            voice.update("head", "检查头部", 0, 0);
            voice.pause();
        });
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        onMain(() -> {
            voice.update("back", "检查手背", 0, 0);
            voice.resume();
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("检查手背", engine.spoken.get(0));
        onMain(() -> {
            voice.update("later", "不应播报", 150, 0);
            voice.close();
        });
        SystemClock.sleep(220);
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
    }

    @Test public void sameIdentityStageRemindersWaitForCurrentSentenceAndNeverInterrupt() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            engine.simulateSpeech = true;
            voice.enterStage("identity", "请正视摄像头");
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
        assertEquals(1, engine.spoken.size());
        int stops = engine.stopped;
        onMain(() -> voice.update("identity_absent", "请入镜摄像头", 0, 50));
        instrumentation.waitForIdleSync();
        assertEquals("同阶段缺脸提醒不能抢断人脸起始提示", 1, engine.spoken.size());
        assertEquals(stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        awaitSpeechCount(2);
        assertEquals("请入镜摄像头", engine.spoken.get(1));
        onMain(() -> {
            for (int frame = 0; frame < 100; frame++) {
                voice.enterStage("identity", "请正视摄像头");
                voice.update("identity_absent", "请入镜摄像头", 0, 50);
            }
        });
        SystemClock.sleep(350);
        assertEquals("缺脸持续时重复提醒也必须等待上一次结束", 2, engine.spoken.size());
        assertEquals(stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        awaitSpeechCount(3);
        assertEquals("请入镜摄像头", engine.spoken.get(2));
        onMain(voice::pause);
        assertFalse(engine.speaking);
    }

    @Test public void enteringNewStageInterruptsOldSentenceAndDiscardsItsQueuedReminder() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            engine.simulateSpeech = true;
            voice.enterStage("face", "请正视摄像头");
        });
        awaitSpeechCount(1);
        onMain(() -> {
            voice.sayOnce("face_extra", "旧阶段待播，不应读出");
            voice.update("face_absent", "请入镜摄像头", 150, 5000);
            voice.enterStage("head", "请佩戴口罩、工作帽检测");
        });
        awaitSpeechCount(2);
        int stops = engine.stopped;
        assertTrue("进入头部阶段必须停止旧人脸语音", stops > 0);
        onMain(() -> {
            for (int frame = 0; frame < 100; frame++) voice.enterStage("head", "请佩戴口罩、工作帽检测");
            engine.speaking = false;
        });
        SystemClock.sleep(350);
        assertEquals("旧阶段初始句和缺脸预约都不能在头部提示后补播", 2, engine.spoken.size());
        assertEquals("同阶段进入不打断，也不重播起始提示", stops, engine.stopped);
        onMain(() -> voice.enterStage("back", "请伸出双手手背"));
        awaitSpeechCount(3);
        assertTrue(engine.stopped > stops);
        onMain(() -> voice.enterStage("palm", "请翻转双手手心"));
        awaitSpeechCount(4);
        int beforeResult = engine.stopped;
        onMain(() -> {
            voice.enterStage("result", "");
            assertFalse("结果空阶段只撤销旧语音，不应残留待播", voice.awaitingOrSpeaking());
        });
        assertTrue("结果完成也应立即停止旧手心提示", engine.stopped > beforeResult);
        assertEquals(java.util.Arrays.asList("请正视摄像头", "请佩戴口罩、工作帽检测",
                "请伸出双手手背", "请翻转双手手心"), engine.spoken);
    }

    @Test public void absenceChangingToUnmatchedWithinSameStageKeepsOnlyLatestWithoutInterrupting() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            engine.simulateSpeech = true;
            voice.enterStage("identity", "请正视摄像头");
        });
        awaitSpeechCount(1);
        int stops = engine.stopped;
        onMain(() -> {
            voice.update("identity_absent", "请入镜摄像头", 0, 5000);
            voice.enterStage("identity", "请正视摄像头");
            voice.update("identity_unmatched", "未匹配员工", 0, 5000);
        });
        SystemClock.sleep(350);
        assertEquals("同阶段未匹配提示不能抢断人脸起始句", 1, engine.spoken.size());
        assertEquals(stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        awaitSpeechCount(2);
        assertEquals("人脸已经入镜，旧缺脸提示不应残留", "未匹配员工", engine.spoken.get(1));
        onMain(() -> {
            voice.update("identity_absent", "请入镜摄像头", 0, 5000);
            voice.clearStatus();
        });
        assertEquals("状态恢复不应停止正在播报的未匹配提示", stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        SystemClock.sleep(350);
        assertEquals("已清除的旧缺脸预约不能在未匹配提示后补播", 2, engine.spoken.size());
    }

    @Test public void identityEntryBeforeFirstFrameWaitsForTtsAndDoesNotReplayAfterReturning() {
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            try {
                CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 1, 1, true, true, true, 37.3);
                check[0] = createCheckFixture(new CheckPipeline(rules), rules);
                // 自动下一位尚待旧员工离场、相机也未产出帧，首句仍应排入实际页面语音出口。
                setCheckField(check[0], "waitingDeparture", true);
                check[0].cameraError = "相机仍在启动";
                invokeCheckVoice(check[0]);
                assertTrue("进入身份流程应先排入提示，不等首帧或身份回包", voice.awaitingOrSpeaking());
                assertFalse("TTS 尚未就绪不能把待播误记为已播", voice.hasAnnounced("stage:IDENTITY"));
                assertFalse(checkPipeline(check[0]).isIdentityConfirmed());
                voice.pause();
                setCheckField(check[0], "resumed", false);
                invokeResetCheckVoice(check[0]);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertTrue("后台引擎就绪也不能发声", engine.spoken.isEmpty());
        onMain(() -> {
            try {
                setCheckField(check[0], "resumed", true);
                voice.resume();
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        assertEquals("请正视摄像头", engine.spoken.get(0));
        onMain(() -> {
            try {
                voice.pause();
                invokeResetCheckVoice(check[0]);
                voice.resume();
                for (int refresh = 0; refresh < 10; refresh++) invokeCheckVoice(check[0]);
                setCheckField(check[0], "waitingDeparture", false);
                check[0].cameraError = "";
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        instrumentation.waitForIdleSync();
        assertEquals("返回同一晨检页、布局刷新或相机恢复不能重复首句", 1, engine.spoken.size());
        assertFalse("不等帧播报不能绕过真实身份识别", checkPipeline(check[0]).isIdentityConfirmed());
    }

    @Test public void actualCheckPipelineOnlyAnnouncesFaceHeadBackAndPalmOnce() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 1, 1, true, true, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            try {
                // 直接经过真实页面的语音分支及流程状态，不启动相机、数据库或 SDK 连接。
                engine.simulateSpeech = true;
                check[0] = createCheckFixture(pipeline, rules);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        int initialStops = engine.stopped;
        onMain(() -> {
            try {
                pipeline.onIdentity(1, true);
                invokeCheckVoice(check[0]);
                assertEquals("姓名确认及等待读数不新增语音", 1, engine.spoken.size());
                assertEquals("仍在人脸识别阶段，不允许打断初始提示", initialStops, engine.stopped);
                pipeline.onTemperature(36.6);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(2);
        assertTrue("头部首句应停止正在播的人脸句", engine.stopped > initialStops);
        int headStops = engine.stopped;
        onMain(() -> {
            try {
                pipeline.onHeadDetections(java.util.Arrays.asList(
                        new CheckPipeline.Detection(DetectionClasses.HEAD_HAT, .9f),
                        new CheckPipeline.Detection(DetectionClasses.HEAD_MASK, .9f)), true);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(3);
        assertTrue("手背首句应停止正在播的头部句", engine.stopped > headStops);
        int backStops = engine.stopped;
        onMain(() -> {
            try {
                pipeline.onDetections(Collections.emptyList());
                invokeCheckVoice(check[0]);
                pipeline.onDetections(java.util.Arrays.asList(
                        new CheckPipeline.Detection(DetectionClasses.HAND_BACK, .9f),
                        new CheckPipeline.Detection(DetectionClasses.HAND_BACK, .9f)));
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(4);
        assertTrue("手心首句应停止正在播的手背句", engine.stopped > backStops);
        int palmStops = engine.stopped;
        onMain(() -> {
            try {
                pipeline.onDetections(java.util.Arrays.asList(
                        new CheckPipeline.Detection(DetectionClasses.HAND_PALM, .9f),
                        new CheckPipeline.Detection(DetectionClasses.HAND_PALM, .9f)));
                invokeCheckVoice(check[0]);
                assertEquals(CheckPipeline.Stage.RESULT, pipeline.getStage());
                assertFalse(voice.awaitingOrSpeaking());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertTrue("实际结果状态应停止旧手心句", engine.stopped > palmStops);
        assertEquals(java.util.Arrays.asList("请正视摄像头", "请佩戴口罩、工作帽检测",
                "请伸出双手手背", "请翻转双手手心"), engine.spoken);
    }

    @Test public void actualHeadAbsenceNeedsContinuousEvidenceAndWaitsForFirstSentence() {
        verifyActualFaceReminderWaitsForFirstSentence(true, false);
    }

    @Test public void actualIdentityAbsenceNeedsContinuousEvidenceAndWaitsForFirstSentence() {
        verifyActualFaceReminderWaitsForFirstSentence(false, false);
    }

    @Test public void actualHeadSingleFaceNeverQueuesAnotherIdentityMatchReminder() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            try {
                CheckActivity check = createOverlayCheckFixture(true);
                long now = System.nanoTime();
                checkPresence(check).observeDetection(now, 1, now);
                checkPresence(check).match(now, false, now);
                invokeCheckVoice(check);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        SystemClock.sleep(350);
        assertEquals("头检单脸不应再播未匹配员工", Collections.singletonList("请佩戴口罩、工作帽检测"), engine.spoken);
        onMain(() -> assertFalse("单脸头检不应预约身份未匹配提醒", voice.awaitingOrSpeaking()));
    }

    @Test public void actualHeadMultipleFacesWaitForCurrentSentenceAndKeepFiveSecondCooldown() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            try {
                engine.simulateSpeech = true;
                check[0] = createOverlayCheckFixture(true);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        int stops = engine.stopped;
        onMain(() -> {
            for (int frame = 0; frame < 100; frame++) observeHeadCount(check[0], 2);
        });
        SystemClock.sleep(250);
        assertEquals("多人提醒不能抢断头检首句", 1, engine.spoken.size());
        assertEquals(stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        awaitSpeechCount(2);
        assertEquals("检测到多人入镜", engine.spoken.get(1));
        long firstMultipleAt = SystemClock.elapsedRealtime();
        onMain(() -> {
            engine.speaking = false;
            for (int frame = 0; frame < 100; frame++) observeHeadCount(check[0], 3);
            observeHeadCount(check[0], 1);
            observeHeadCount(check[0], 2);
        });
        long quietUntil = firstMultipleAt + 4500;
        while (SystemClock.elapsedRealtime() < quietUntil) {
            onMain(() -> observeHeadCount(check[0], 2));
            SystemClock.sleep(200);
            assertEquals("逐帧更新和短暂恢复单脸不能抹掉五秒冷却", 2, engine.spoken.size());
        }
        while (SystemClock.elapsedRealtime() < firstMultipleAt + 5200) {
            onMain(() -> observeHeadCount(check[0], 2));
            SystemClock.sleep(100);
        }
        awaitSpeechCount(3);
        assertEquals("五秒后仍多人时才允许再次播报", "检测到多人入镜", engine.spoken.get(2));
        assertEquals("同阶段状态更新不能反复 stop", stops, engine.stopped);
    }

    @Test public void actualIdentityUnmatchedEmployeeIsDistinctFromMissingFace() {
        verifyActualFaceReminderWaitsForFirstSentence(false, true);
    }

    @Test public void actualIdentityDelayedSdkReplyAnnouncesUnmatchedWithFreshFacePreview() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final CameraSource.Frame[] identifyFrame = new CameraSource.Frame[1];
        final int request = 41;
        onMain(() -> {
            try {
                check[0] = createIdentityReplyFixture(request);
                identifyFrame[0] = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], identifyFrame[0], boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        long replyAt = SystemClock.elapsedRealtime() + 2000;
        // 经过真正预览回包入口刷新不同时间戳的人脸，不把 SDK 匹配回包理想化为当前采集时刻。
        while (SystemClock.elapsedRealtime() < replyAt) {
            onMain(() -> {
                try {
                    CameraSource.Frame freshPreview = new CameraSource.Frame(new byte[12], 2, 2);
                    invokeFacePreview(check[0], freshPreview, boxAt(.2), true,
                            check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            SystemClock.sleep(200);
        }
        onMain(() -> {
            try {
                CameraSource.Frame freshPreview = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], freshPreview, boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                invokeIdentityReply(check[0], request, false, true, identifyFrame[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(2);
        assertEquals("SDK 回包虽耗时两秒，仍应播实际未匹配结论", "未匹配员工", engine.spoken.get(1));
        onMain(() -> assertTrue("业务仍停留身份阶段，不能因为提示放宽身份准入",
                checkPipeline(check[0]).getStage() == CheckPipeline.Stage.IDENTITY));
    }

    @Test public void actualUnknownFacePreviewCancelsPendingUnmatchedAndRecoveryKeepsRepeatCooldown() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final int request = 42;
        onMain(() -> {
            try {
                engine.simulateSpeech = true;
                check[0] = createIdentityReplyFixture(request);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        int stops = engine.stopped;
        onMain(() -> {
            try {
                CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], frame, boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                invokeIdentityReply(check[0], request, false, true, frame);
                CameraSource.Frame unavailable = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], unavailable, new JSONArray(), false,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                engine.speaking = false;
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(350);
        assertEquals("预览事实未知时应撤销尚未播出的未匹配提示", 1, engine.spoken.size());
        assertEquals("同阶段预览恢复不能打断当前句", stops, engine.stopped);
        onMain(() -> {
            try {
                CameraSource.Frame recovered = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], recovered, boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(2);
        assertEquals("当前人脸恢复后应采用仍有效的真实未匹配回包", "未匹配员工", engine.spoken.get(1));
        onMain(() -> {
            try {
                CameraSource.Frame unavailable = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], unavailable, new JSONArray(), false,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                CameraSource.Frame recovered = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], recovered, boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                engine.speaking = false;
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(350);
        assertEquals("预览短暂抖动不能清除已播未匹配提示的五秒冷却", 2, engine.spoken.size());
        assertEquals(stops, engine.stopped);
    }

    @Test public void actualNoFaceAndMissingMatchedFieldCannotInventUnmatchedEmployee() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final int request = 43;
        onMain(() -> {
            try {
                check[0] = createIdentityReplyFixture(request);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        onMain(() -> {
            try {
                CameraSource.Frame empty = new CameraSource.Frame(new byte[12], 2, 2);
                invokeFacePreview(check[0], empty, new JSONArray(), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                invokeIdentityReply(check[0], request, false, true, empty);
                assertFalse("有效未匹配回包也不能把空镜头当作在场员工", checkPresence(check[0]).isUnmatched(System.nanoTime()));
                CameraSource.Frame present = new CameraSource.Frame(new byte[12], 2, 2);
                // 当前 SDK 回包缺少 matched 字段，不能沿用上一次有效未匹配结论。
                invokeIdentityReply(check[0], request, false, false, present);
                invokeFacePreview(check[0], present, boxAt(.2), true,
                        check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                assertFalse("缺少 matched 的回包必须是未知，不应伪造未匹配", checkPresence(check[0]).isUnmatched(System.nanoTime()));
                assertFalse("未知回包也应撤销旧缺脸预约", voice.awaitingOrSpeaking());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(350);
        assertEquals("无人和匹配未知两种真实回包都不应播未匹配员工", 1, engine.spoken.size());
    }

    private void verifyActualFaceReminderWaitsForFirstSentence(boolean enterHead, boolean unmatched) {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 1, 1, true, false, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        if (enterHead) {
            pipeline.onIdentity(1, true);
            pipeline.onTemperature(36.6);
        }
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            try {
                engine.simulateSpeech = true;
                check[0] = createCheckFixture(pipeline, rules);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        assertEquals(enterHead ? "请佩戴口罩检测" : "请正视摄像头", engine.spoken.get(0));
        int stops = engine.stopped;
        long deadline = SystemClock.elapsedRealtime() + 5300;
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain(() -> {
                try {
                    FacePresencePolicy presence = checkPresence(check[0]);
                    long now = System.nanoTime();
                    if (enterHead) presence.observeDetection(now, 0, now);
                    else {
                        presence.observe(now, unmatched, now);
                        if (unmatched) presence.match(now, false, now);
                    }
                    invokeCheckVoice(check[0]);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            SystemClock.sleep(200);
        }
        assertEquals("实际缺脸或未匹配提醒不能截断同阶段首句", 1, engine.spoken.size());
        assertEquals("连续空帧也不能反复stop", stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        awaitSpeechCount(2);
        assertEquals(unmatched ? "未匹配员工" : "请入镜摄像头", engine.spoken.get(1));
        onMain(() -> {
            try {
                long now = System.nanoTime();
                if (enterHead) checkPresence(check[0]).observeDetection(now, 1, now);
                else {
                    checkPresence(check[0]).observe(now, true, now);
                    checkPresence(check[0]).match(now, true, now);
                }
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals("人脸重新入镜只能取消待播提醒，不能截断正在播的同阶段句", stops, engine.stopped);
        onMain(() -> engine.speaking = false);
        SystemClock.sleep(350);
        assertEquals(2, engine.spoken.size());
    }

    @Test public void actualFaceWithUnknownMatchOrInferenceErrorNeverQueuesUnmatchedEmployee() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 1, 1, true, true, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        final CheckActivity[] check = new CheckActivity[1];
        onMain(() -> {
            try {
                check[0] = createCheckFixture(pipeline, rules);
                invokeCheckVoice(check[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        awaitSpeechCount(1);
        onMain(() -> {
            try {
                FacePresencePolicy presence = checkPresence(check[0]);
                long now = System.nanoTime();
                presence.observe(now, true, now);
                invokeCheckVoice(check[0]);
                assertFalse("系统看到人脸但SDK尚无匹配回包，不等于未匹配", voice.awaitingOrSpeaking());
                now = System.nanoTime();
                presence.observe(now, false, now);
                invokeCheckVoice(check[0]);
                assertTrue("有效无人帧应预约入镜提醒", voice.awaitingOrSpeaking());
                now = System.nanoTime();
                presence.observe(now, true, now);
                presence.matchingUnknown(now);
                invokeCheckVoice(check[0]);
                assertFalse("已入镜且等待SDK时应撤销旧缺脸提醒", voice.awaitingOrSpeaking());
                now = System.nanoTime();
                presence.match(now, false, now);
                invokeCheckVoice(check[0]);
                assertTrue("有效未匹配回包才预约未匹配提示", voice.awaitingOrSpeaking());
                presence.matchingUnknown(System.nanoTime());
                invokeCheckVoice(check[0]);
                assertFalse("匹配事实变未知应撤销旧未匹配提醒", voice.awaitingOrSpeaking());
                now = System.nanoTime();
                presence.observe(now, true, now);
                presence.match(now, false, now);
                invokeCheckVoice(check[0]);
                setCheckField(check[0], "inferenceIssue", "夹具识别失败");
                invokeCheckVoice(check[0]);
                assertFalse("SDK识别异常不能伪造未匹配或追加错误口播", voice.awaitingOrSpeaking());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals("未知、等待和错误状态均不能新增语音", 1, engine.spoken.size());
    }

    @Test public void actualFacePreviewShowsRealCoordinatesDuringIdentityAndHeadWithoutHeadMatch() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            try {
                for (boolean enterHead : new boolean[]{false, true}) {
                    CheckActivity check = createOverlayCheckFixture(enterHead);
                    CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                    JSONArray realBoxes = new JSONArray().put(new JSONObject()
                            .put("x", .2).put("y", .25).put("w", .4).put("h", .5));
                    if (enterHead) checkPresence(check).observeDetection(frame.receivedAtNanos, 1, System.nanoTime());
                    else checkPresence(check).match(frame.receivedAtNanos, false, System.nanoTime());
                    invokeFacePreview(check, frame, realBoxes, true, check.session,
                            enterHead ? CheckPipeline.Stage.HEAD : CheckPipeline.Stage.IDENTITY, 1);
                    assertEquals("检测到的人脸应显示真实坐标，头检不依赖身份匹配", realBoxes.toString(), overlayBoxes(check).toString());
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void actualEmptyFacePreviewNeverInventsUnmatchedEmployeeOrFaceBoxes() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            try {
                for (boolean enterHead : new boolean[]{false, true}) {
                    CheckActivity check = createOverlayCheckFixture(enterHead);
                    CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                    if (enterHead) checkPresence(check).observeDetection(frame.receivedAtNanos, 0, System.nanoTime());
                    else checkPresence(check).match(frame.receivedAtNanos, false, System.nanoTime());
                    invokeFacePreview(check, frame, new JSONArray(), true, check.session,
                            enterHead ? CheckPipeline.Stage.HEAD : CheckPipeline.Stage.IDENTITY, 1);
                    assertEquals("没有检测到脸时不能画固定假框", 0, overlayBoxes(check).length());
                    assertFalse("只有SDK未匹配而没有实际人脸，不应播未匹配员工", checkPresence(check).isUnmatched(System.nanoTime()));
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void lateFaceCallbacksCannotReplaceCurrentSdkDetectionBoxes() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final CameraSource.Frame[] oldFrame = new CameraSource.Frame[1];
        final JSONArray headBoxes = new JSONArray();
        onMain(() -> {
            try {
                check[0] = createOverlayCheckFixture(true);
                headBoxes.put(new JSONObject().put("x", .1).put("y", .1).put("w", .2).put("h", .2));
                CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                JSONArray faceBoxes = new JSONArray().put(new JSONObject()
                        .put("x", .3).put("y", .3).put("w", .4).put("h", .4));
                checkPresence(check[0]).observeDetection(frame.receivedAtNanos, 1, System.nanoTime());
                invokeFacePreview(check[0], frame, faceBoxes, true, check[0].session, CheckPipeline.Stage.HEAD, 1);
                assertEquals(faceBoxes.toString(), overlayBoxes(check[0]).toString());
                invokeDetectionOverlay(check[0], frame, CheckPipeline.Stage.HEAD, headBoxes);
                assertEquals("单脸头检应显示SDK帽口罩框，无需身份匹配", headBoxes.toString(), overlayBoxes(check[0]).toString());
                invokeFacePreview(check[0], frame, faceBoxes, true, check[0].session, CheckPipeline.Stage.IDENTITY, 1);
                invokeFacePreview(check[0], frame, faceBoxes, true, check[0].session + 1, CheckPipeline.Stage.HEAD, 1);
                invokeFacePreview(check[0], frame, faceBoxes, true, check[0].session, CheckPipeline.Stage.HEAD, 0);
                assertEquals("旧阶段、旧会话及旧generation回包均不能覆盖当前检测框", headBoxes.toString(), overlayBoxes(check[0]).toString());
                oldFrame[0] = new CameraSource.Frame(new byte[12], 2, 2);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(1600);
        onMain(() -> {
            try {
                invokeFacePreview(check[0], oldFrame[0], new JSONArray().put(new JSONObject()
                                .put("x", .4).put("y", .4).put("w", .2).put("h", .2)),
                        true, check[0].session, CheckPipeline.Stage.HEAD, 1);
                assertEquals("过期采集帧不能恢复已到期的人脸或头检框", 0, overlayBoxes(check[0]).length());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void delayedHeadDetectionCannotReplaceFreshMissingOrMultipleFacePreview() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final CameraSource.Frame[] oldFrame = new CameraSource.Frame[1];
        onMain(() -> {
            try {
                check[0] = createOverlayCheckFixture(true);
                oldFrame[0] = new CameraSource.Frame(new byte[12], 2, 2);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(1600);
        onMain(() -> {
            try {
                JSONArray faceBoxes = boxAt(.3), sdkBoxes = boxAt(.1);
                CameraSource.Frame preview = new CameraSource.Frame(new byte[12], 2, 2);
                checkPresence(check[0]).observeDetection(preview.receivedAtNanos, 1, System.nanoTime());
                invokeFacePreview(check[0], preview, faceBoxes, true, check[0].session, CheckPipeline.Stage.HEAD, 1);
                invokeDetectionOverlay(check[0], oldFrame[0], CheckPipeline.Stage.HEAD, sdkBoxes);
                assertEquals("过期SDK回包不能覆盖新的人脸预览框", faceBoxes.toString(), overlayBoxes(check[0]).toString());
                CameraSource.Frame detection = new CameraSource.Frame(new byte[12], 2, 2);
                long now = System.nanoTime();
                checkPresence(check[0]).observeFaces(now, 0, now);
                invokeDetectionOverlay(check[0], detection, CheckPipeline.Stage.HEAD, sdkBoxes);
                assertEquals("当前人已离开时，迟到SDK框不能覆盖当前画面", faceBoxes.toString(), overlayBoxes(check[0]).toString());
                now = System.nanoTime();
                checkPresence(check[0]).observeDetection(now, 2, now);
                invokeDetectionOverlay(check[0], detection, CheckPipeline.Stage.HEAD, sdkBoxes);
                assertEquals("当前多人入镜时，迟到SDK框不能覆盖人脸框", faceBoxes.toString(), overlayBoxes(check[0]).toString());
                CameraSource.Frame single = new CameraSource.Frame(new byte[12], 2, 2);
                checkPresence(check[0]).observeDetection(single.receivedAtNanos, 1, System.nanoTime());
                checkPresence(check[0]).match(single.receivedAtNanos, false, System.nanoTime());
                invokeDetectionOverlay(check[0], single, CheckPipeline.Stage.HEAD, sdkBoxes);
                assertEquals("单脸即使未匹配身份仍可显示同帧着装框", sdkBoxes.toString(), overlayBoxes(check[0]).toString());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void headDetectionAndFacePreviewExpiryOnlyClearTheirOwnGeneration() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final CheckActivity[] check = new CheckActivity[1];
        final JSONArray[] previewBoxes = new JSONArray[1], newestSdkBoxes = new JSONArray[1];
        onMain(() -> {
            try {
                check[0] = createOverlayCheckFixture(true);
                CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                checkPresence(check[0]).observeDetection(frame.receivedAtNanos, 1, System.nanoTime());
                JSONArray firstSdkBoxes = boxAt(.1);
                invokeDetectionOverlay(check[0], frame, CheckPipeline.Stage.HEAD, firstSdkBoxes);
                assertEquals("新鲜且单脸的SDK框应正常显示", firstSdkBoxes.toString(), overlayBoxes(check[0]).toString());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(850);
        onMain(() -> {
            try {
                CameraSource.Frame preview = new CameraSource.Frame(new byte[12], 2, 2);
                previewBoxes[0] = boxAt(.3);
                checkPresence(check[0]).observeDetection(preview.receivedAtNanos, 1, System.nanoTime());
                invokeFacePreview(check[0], preview, previewBoxes[0], true,
                        check[0].session, CheckPipeline.Stage.HEAD, 1);
                assertEquals(previewBoxes[0].toString(), overlayBoxes(check[0]).toString());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(850);
        onMain(() -> {
            try {
                assertEquals("旧SDK清框预约不能清掉更新的人脸框", previewBoxes[0].toString(), overlayBoxes(check[0]).toString());
                CameraSource.Frame frame = new CameraSource.Frame(new byte[12], 2, 2);
                newestSdkBoxes[0] = boxAt(.5);
                checkPresence(check[0]).observeDetection(frame.receivedAtNanos, 1, System.nanoTime());
                invokeDetectionOverlay(check[0], frame, CheckPipeline.Stage.HEAD, newestSdkBoxes[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(850);
        onMain(() -> {
            try {
                assertEquals("旧人脸清框预约不能清掉更新的SDK框", newestSdkBoxes[0].toString(), overlayBoxes(check[0]).toString());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(850);
        onMain(() -> {
            try { assertEquals("断流后SDK框必须在采集帧过期时消失", 0, overlayBoxes(check[0]).length()); }
            catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test public void resultPendingBeforeInitializationIsRequeuedAfterBackgroundAndAcceptedOnlyOnce() {
        onMain(() -> {
            voice.enterStage("result", "晨检正常");
            assertFalse(voice.hasAnnounced("result"));
            voice.pause();
        });
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        onMain(() -> {
            voice.resume();
            voice.enterStage("result", "晨检正常");
        });
        awaitSpeechCount(1);
        onMain(() -> {
            assertTrue(voice.hasAnnounced("result"));
            voice.pause();
            voice.resume();
            voice.enterStage("result", "晨检正常");
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
    }

    @Test public void resultCanBeRestoredAsAnnouncedAndFailedEngineSubmissionIsNotSavedAsSpoken() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        onMain(() -> {
            voice.restoreAnnounced("old-result");
            voice.enterStage("old-result", "晨检正常");
        });
        instrumentation.waitForIdleSync();
        assertTrue(engine.spoken.isEmpty());
        engine.speakResult = TextToSpeech.ERROR;
        onMain(() -> voice.enterStage("new-result", "晨检异常，请查看结果"));
        instrumentation.waitForIdleSync();
        onMain(() -> assertFalse("引擎拒绝不算实际受理", voice.hasAnnounced("new-result")));
        engine.speakResult = TextToSpeech.SUCCESS;
        onMain(() -> voice.enterStage("new-result", "晨检异常，请查看结果"));
        awaitSpeechCount(2);
        onMain(() -> assertTrue(voice.hasAnnounced("new-result")));
    }

    @Test public void resultUploadRefreshDoesNotReannounceOrAddExtraVoice() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final ResultActivity[] result = new ResultActivity[1];
        onMain(() -> {
            try {
                // 只验证真实结果播报入口的门控，不启动页面、数据库、自动计时或 SDK。
                result[0] = new ResultActivity();
                result[0].foreground = true;
                result[0].currentRecord = new JSONObject().put("id", "VOICE-RESULT")
                        .put("status", 0).put("staff_name", "测试员工")
                        .put("findings", new JSONArray().put("手背：戒指"));
                Field output = ResultActivity.class.getDeclaredField("speech");
                output.setAccessible(true);
                output.set(result[0], voice);
                invokeResultAnnouncement(result[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("晨检异常，请查看结果", engine.spoken.get(0));
        onMain(() -> {
            try {
                result[0].currentRecord.put("upload", "已上传");
                invokeResultAnnouncement(result[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        instrumentation.waitForIdleSync();
        assertEquals("上传元信息刷新不应重放检测结论或追加姓名等内容", 1, engine.spoken.size());
    }

    @Test public void disconnectedSdkDoesNotReplaceResultWaitingForVoiceInitialization() {
        final ResultActivity[] result = new ResultActivity[1];
        onMain(() -> {
            try {
                engine.simulateSpeech = true;
                result[0] = createDisconnectedResult();
                invokeResultAnnouncement(result[0]);
                attemptAutomaticNext(result[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(1300);
        assertTrue(engine.spoken.isEmpty());
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        assertEquals("SDK 等待不能取代尚未播出的实际结论", "晨检异常，请查看结果", engine.spoken.get(0));
        onMain(() -> {
            try {
                restoreReadySdk(result[0]);
                attemptAutomaticNext(result[0]);
                assertTrue("SDK 恢复后仍要等待实际结论结束", voice.awaitingOrSpeaking());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals(1, engine.spoken.size());
    }

    @Test public void disconnectedSdkDoesNotInterruptLongAbnormalResultAlreadySpeaking() {
        ready.ready(TextToSpeech.SUCCESS);
        instrumentation.waitForIdleSync();
        final ResultActivity[] result = new ResultActivity[1];
        onMain(() -> {
            try {
                engine.simulateSpeech = true;
                result[0] = createDisconnectedResult();
                invokeResultAnnouncement(result[0]);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, engine.spoken.size());
        onMain(() -> {
            try { attemptAutomaticNext(result[0]); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        SystemClock.sleep(1300);
        instrumentation.waitForIdleSync();
        assertEquals("SDK 中断不能 QUEUE_FLUSH 截断异常结论", 1, engine.spoken.size());
        assertEquals("晨检异常，请查看结果", engine.spoken.get(0));
        onMain(() -> {
            try {
                restoreReadySdk(result[0]);
                attemptAutomaticNext(result[0]);
                assertTrue(voice.awaitingOrSpeaking());
            } catch (Exception error) { throw new AssertionError(error); }
        });
        assertEquals(1, engine.spoken.size());
    }

    private ResultActivity createDisconnectedResult() throws Exception {
        // 独立未连接客户端仅用于状态事实；不 attach/connect，不产生 IPC、网络或数据库请求。
        ResultActivity result = new ResultActivity();
        resultFixtures.add(result);
        result.foreground = true;
        result.sdkSession = new MorningSdkSession(instrumentation.getTargetContext());
        result.currentRecord = new JSONObject().put("id", "VOICE-RESULT-SDK-DISCONNECTED")
                .put("status", 0).put("staff_name", "测试员工")
                .put("findings", new JSONArray().put("体温偏高：38.1°C").put("未佩戴工作帽")
                        .put("未佩戴口罩").put("手背：戒指").put("手心：伤口").put("健康证过期或未登记"));
        Field output = ResultActivity.class.getDeclaredField("speech");
        output.setAccessible(true);
        output.set(result, voice);
        Field resumed = ResultActivity.class.getDeclaredField("resumed");
        resumed.setAccessible(true);
        resumed.setBoolean(result, true);
        return result;
    }

    private CheckActivity createCheckFixture(CheckPipeline pipeline, CheckPipeline.Rules rules) throws Exception {
        CheckActivity check = new CheckActivity();
        checkFixtures.add(check);
        check.foreground = true;
        // 使用独立偏好文件，测试不读写设备的真实晨检规则。
        check.prefs = instrumentation.getTargetContext().getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        check.sdkSession = new MorningSdkSession(instrumentation.getTargetContext());
        check.sdkSession.onConnection(true, "本机语音状态夹具");
        check.sdkSession.onStatus(new JSONObject().put("sdk_state", "READY")
                .put("certificate_valid", true).put("model_ready", true));
        check.sdk = check.sdkSession.client();
        setCheckField(check, "speech", voice);
        setCheckField(check, "resumed", true);
        setCheckField(check, "pipeline", pipeline);
        setCheckField(check, "checkRules", rules);
        Field captured = CheckActivity.class.getDeclaredField("captureSession");
        captured.setAccessible(true);
        ((CaptureSession) captured.get(check)).begin(check.sdkGeneration(), check.sdk.getStatus().optString("device_id"));
        return check;
    }

    private CheckActivity createOverlayCheckFixture(boolean enterHead) throws Exception {
        CheckPipeline.Rules rules = new CheckPipeline.Rules(1, 1, 1, true, true, true, 37.3);
        CheckPipeline pipeline = new CheckPipeline(rules);
        if (enterHead) {
            pipeline.onIdentity(1, true);
            pipeline.onTemperature(36.6);
        }
        CheckActivity check = createCheckFixture(pipeline, rules);
        check.overlay = new DetectionOverlay(instrumentation.getTargetContext());
        setCheckField(check, "facePreviewGeneration", 1);
        invokeCheckVoice(check);
        return check;
    }

    private CheckActivity createIdentityReplyFixture(int request) throws Exception {
        CheckActivity check = createOverlayCheckFixture(false);
        setCheckField(check, "checkHint", new TextView(instrumentation.getTargetContext()));
        setCheckField(check, "frameRequest", request);
        return check;
    }

    private static void invokeIdentityReply(CheckActivity check, int request, boolean matched,
                                             boolean matchAvailable, CameraSource.Frame frame) throws Exception {
        Method accept = CheckActivity.class.getDeclaredMethod("acceptIdentity", int.class, int.class, long.class,
                boolean.class, boolean.class, JSONObject.class, CameraSource.Frame.class);
        accept.setAccessible(true);
        accept.invoke(check, request, check.session, 0L, matched, matchAvailable, null, frame);
    }

    private static CheckPipeline checkPipeline(CheckActivity check) {
        try {
            Field pipeline = CheckActivity.class.getDeclaredField("pipeline");
            pipeline.setAccessible(true);
            return (CheckPipeline) pipeline.get(check);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void invokeFacePreview(CheckActivity check, CameraSource.Frame frame, JSONArray boxes,
                                          boolean available, int expectedSession, CheckPipeline.Stage expectedStage,
                                          int expectedGeneration) throws Exception {
        Method accept = CheckActivity.class.getDeclaredMethod("acceptFacePreview", CameraSource.Frame.class,
                JSONArray.class, boolean.class, int.class, CheckPipeline.Stage.class, int.class, boolean.class);
        accept.setAccessible(true);
        accept.invoke(check, frame, boxes, available, expectedSession, expectedStage, expectedGeneration, false);
    }

    private static void invokeDetectionOverlay(CheckActivity check, CameraSource.Frame frame,
                                                CheckPipeline.Stage stage, JSONArray boxes) throws Exception {
        Method show = CheckActivity.class.getDeclaredMethod("showDetectionOverlay", CameraSource.Frame.class,
                CheckPipeline.Stage.class, JSONArray.class);
        show.setAccessible(true);
        show.invoke(check, frame, stage, boxes);
    }

    private static JSONArray boxAt(double x) throws Exception {
        return new JSONArray().put(new JSONObject().put("x", x).put("y", .1).put("w", .2).put("h", .3));
    }

    private static JSONArray overlayBoxes(CheckActivity check) throws Exception {
        Field boxes = DetectionOverlay.class.getDeclaredField("boxes");
        boxes.setAccessible(true);
        return (JSONArray) boxes.get(check.overlay);
    }

    private static void setCheckField(CheckActivity check, String name, Object value) throws Exception {
        Field field = CheckActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(check, value);
    }

    private static FacePresencePolicy checkPresence(CheckActivity check) throws Exception {
        Field presence = CheckActivity.class.getDeclaredField("facePresence");
        presence.setAccessible(true);
        return (FacePresencePolicy) presence.get(check);
    }

    private static void observeHeadCount(CheckActivity check, int count) {
        try {
            long now = System.nanoTime();
            checkPresence(check).observeDetection(now, count, now);
            invokeCheckVoice(check);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static void invokeCheckVoice(CheckActivity check) throws Exception {
        Method update = CheckActivity.class.getDeclaredMethod("updateVoice");
        update.setAccessible(true);
        update.invoke(check);
    }

    private static void invokeResetCheckVoice(CheckActivity check) throws Exception {
        Method reset = CheckActivity.class.getDeclaredMethod("resetVoiceState");
        reset.setAccessible(true);
        reset.invoke(check);
    }

    private static void restoreReadySdk(ResultActivity result) throws Exception {
        result.sdkSession.onConnection(true, "本机状态夹具恢复");
        result.sdkSession.onStatus(new JSONObject().put("sdk_state", "READY")
                .put("certificate_valid", true).put("model_ready", true));
        assertTrue(result.sdkReady());
    }

    private static void attemptAutomaticNext(ResultActivity result) throws Exception {
        Field next = ResultActivity.class.getDeclaredField("nextPerson");
        next.setAccessible(true);
        Runnable scheduled = (Runnable) next.get(result);
        try { scheduled.run(); }
        finally { result.main.removeCallbacks(scheduled); }
    }

    private static void removeAutomaticNext(ResultActivity result) throws Exception {
        Field next = ResultActivity.class.getDeclaredField("nextPerson");
        next.setAccessible(true);
        result.main.removeCallbacks((Runnable) next.get(result));
    }

    private static void invokeResultAnnouncement(ResultActivity result) throws Exception {
        Method announce = ResultActivity.class.getDeclaredMethod("announceResult");
        announce.setAccessible(true);
        announce.invoke(result);
    }

    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }

    private void awaitSpeechCount(int expected) {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (engine.spoken.size() < expected && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        assertEquals(expected, engine.spoken.size());
        instrumentation.waitForIdleSync();
    }

    private static final class FakeSpeaker implements MorningVoice.Speaker {
        final List<String> spoken = Collections.synchronizedList(new ArrayList<>());
        volatile boolean speaking, closed;
        boolean simulateSpeech;
        int stopped;
        int language = TextToSpeech.LANG_AVAILABLE;
        int speakResult = TextToSpeech.SUCCESS;
        @Override public int setLanguage() { return language; }
        @Override public int speak(String text) { spoken.add(text); speaking = simulateSpeech && speakResult == TextToSpeech.SUCCESS; return speakResult; }
        @Override public boolean isSpeaking() { return speaking; }
        @Override public void stop() { stopped++; speaking = false; }
        @Override public void shutdown() { closed = true; }
    }
}
