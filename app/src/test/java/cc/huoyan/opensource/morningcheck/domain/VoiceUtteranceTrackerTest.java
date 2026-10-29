package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;

import static org.junit.Assert.*;

public final class VoiceUtteranceTrackerTest {
    @Test public void binderCallbackWithEqualContentDifferentStringInstanceCompletesUtterance() {
        VoiceUtteranceTracker tracker = new VoiceUtteranceTracker();
        tracker.start("morning-1", 0);
        assertTrue(tracker.pending(1, 60000));
        tracker.finish(new String("morning-1"));
        assertFalse(tracker.pending(2, 60000));
    }

    @Test public void lateCancelledCallbackCannotFinishNewerUtterance() {
        VoiceUtteranceTracker tracker = new VoiceUtteranceTracker();
        tracker.start("morning-1", 0);
        tracker.start("morning-2", 10);
        tracker.finish(new String("morning-1"));
        assertTrue(tracker.pending(20, 60000));
        tracker.finish(new String("morning-2"));
        assertFalse(tracker.pending(30, 60000));
    }

    @Test public void engineThatNeverCompletesHasFiniteWaitWindow() {
        VoiceUtteranceTracker tracker = new VoiceUtteranceTracker();
        tracker.start("morning-1", 100);
        assertTrue(tracker.pending(60099, 60000));
        assertFalse(tracker.pending(60100, 60000));
    }

    @Test public void leavingPageClearsQueuedOrPlayingUtterance() {
        VoiceUtteranceTracker tracker = new VoiceUtteranceTracker();
        tracker.start("morning-1", 0);
        tracker.clear();
        tracker.finish("morning-1");
        assertFalse(tracker.pending(1, 60000));
    }
}
