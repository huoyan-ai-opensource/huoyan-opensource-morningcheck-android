package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import static org.junit.Assert.*;

public final class CheckVoiceTextTest {
    @Test public void capturePromptsUseTheSpecifiedShortActionsWithoutTerminalPunctuation() {
        assertEquals("请正视摄像头", CheckVoiceText.stage(CheckPipeline.Stage.IDENTITY, true, true));
        assertEquals("请伸出双手手背", CheckVoiceText.stage(CheckPipeline.Stage.HAND_BACK, true, true));
        assertEquals("请翻转双手手心", CheckVoiceText.stage(CheckPipeline.Stage.HAND_PALM, true, true));
    }

    @Test public void headPromptMatchesBothRequiredItems() {
        assertEquals("请佩戴口罩、工作帽检测", CheckVoiceText.stage(CheckPipeline.Stage.HEAD, true, true));
    }

    @Test public void headPromptOnlyAsksForItemsRequiredByThisCheck() {
        assertEquals("请佩戴口罩检测", CheckVoiceText.stage(CheckPipeline.Stage.HEAD, true, false));
        assertEquals("请佩戴工作帽检测", CheckVoiceText.stage(CheckPipeline.Stage.HEAD, false, true));
    }

    @Test public void neitherItemRequiredDoesNotAskTheEmployeeToWearEither() {
        assertEquals("请进行头部检测", CheckVoiceText.stage(CheckPipeline.Stage.HEAD, false, false));
    }

    @Test public void savingDoesNotAnnounceAnUnsavedResult() {
        assertEquals("", CheckVoiceText.stage(CheckPipeline.Stage.RESULT, true, true));
    }

    @Test public void savedResultOnlyGivesItsShortConclusion() {
        assertEquals("晨检正常", CheckVoiceText.result(true));
        assertEquals("晨检异常，请查看结果", CheckVoiceText.result(false));
    }
}
