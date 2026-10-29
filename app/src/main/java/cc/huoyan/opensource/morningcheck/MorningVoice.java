package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.function.BooleanSupplier;

import cc.huoyan.opensource.morningcheck.domain.VoicePromptPolicy;
import cc.huoyan.opensource.morningcheck.domain.VoiceUtteranceTracker;

/**
 * 页面持有的语音出口；同阶段等上一句结束，切换阶段立即取消旧阶段语音。
 */
final class MorningVoice implements AutoCloseable {
    private final Handler main;
    private final BooleanSupplier enabled;
    private final VoicePromptPolicy policy = new VoicePromptPolicy();
    private final Speaker engine;
    private final Runnable pump = this::speakCurrent;
    private boolean ready, active, closed, wasEnabled, initializationFinished;
    private final long createdAt = SystemClock.elapsedRealtime();
    private String desiredKey, desiredText;
    private String stageKey;
    private long desiredDelay, desiredRepeat;

    MorningVoice(Context context, Handler main, BooleanSupplier enabled) {
        this(main, enabled, listener -> new AndroidSpeaker(context, listener));
    }

    MorningVoice(Handler main, BooleanSupplier enabled, SpeakerFactory factory) {
        this.main = main;
        this.enabled = enabled;
        engine = factory.create(status -> main.post(() -> initialized(status)));
    }

    private void initialized(int status) {
        // 回调再投递一次，保证构造赋值完成；后台/销毁时不启动任何播报。
        if (closed) return;
        initializationFinished = true;
        ready = false;
        if (status == TextToSpeech.SUCCESS) {
            int language = engine.setLanguage();
            ready = language != TextToSpeech.LANG_MISSING_DATA
                    && language != TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (!ready) Log.w("MorningVoice", "当前语言的语音不可用，请检查系统语音引擎及对应语音包");
        reconcile();
    }

    void update(String key, String text, long stableDelayMs, long repeatMs) {
        desiredKey = key;
        desiredText = text;
        desiredDelay = stableDelayMs;
        desiredRepeat = repeatMs;
        reconcile();
    }

    void sayOnce(String key, String text) {
        if (closed) return;
        desiredKey = desiredText = null;
        policy.clearCurrent();
        policy.offerOnce(key, text);
        reconcile();
    }

    void enterStage(String key, String firstText) {
        if (closed || key == null) return;
        if (!key.equals(stageKey)) {
            // 只有业务阶段改变才允许抢断；旧阶段尚未播出的提醒也不能带入新阶段。
            stageKey = key;
            desiredKey = desiredText = null;
            main.removeCallbacks(pump);
            policy.cancelPending();
            if (ready) engine.stop();
        }
        // 相同阶段的逐帧更新不能清除入镜/未匹配提醒的稳定窗口，也不能重播阶段首句。
        policy.offerOnce(key, firstText);
        reconcile();
    }

    void clearStatus() {
        desiredKey = desiredText = null;
        policy.clearCurrent();
        reconcile();
    }

    boolean hasAnnounced(String key) {
        return policy.hasAnnounced(key);
    }

    void restoreAnnounced(String key) {
        policy.restoreAnnounced(key);
    }

    void resume() {
        if (closed) return;
        active = true;
        reconcile();
    }

    void pause() {
        active = false;
        desiredKey = desiredText = null;
        main.removeCallbacks(pump);
        policy.cancelPending();
        if (ready) engine.stop();
    }

    void reset() {
        desiredKey = desiredText = null;
        stageKey = null;
        main.removeCallbacks(pump);
        policy.reset();
        if (ready) engine.stop();
    }

    boolean isSpeaking() {
        return ready && active && enabled.getAsBoolean() && engine.isSpeaking();
    }

    boolean awaitingOrSpeaking() {
        if (closed || !active || !enabled.getAsBoolean()) return false;
        // 结果页给系统语音初始化留出有限窗口；引擎失败或不回调不能锁住自动下一位。
        if (!initializationFinished) return policy.nextAt() != Long.MAX_VALUE
                && SystemClock.elapsedRealtime() - createdAt < 10000;
        return ready && (engine.isSpeaking() || policy.nextAt() != Long.MAX_VALUE);
    }

    private void reconcile() {
        main.removeCallbacks(pump);
        boolean allowed = !closed && active && enabled.getAsBoolean();
        if (!allowed) {
            // 尚未进入前台时保留初始化前收到的阶段；显式 pause 已负责撤销待播。
            if (!enabled.getAsBoolean() || closed) policy.cancelPending();
            if (ready && wasEnabled) engine.stop();
            wasEnabled = false;
            return;
        }
        wasEnabled = true;
        if (desiredKey != null)
            policy.offer(desiredKey, desiredText, desiredDelay, desiredRepeat, SystemClock.elapsedRealtime());
        if (ready) schedule();
    }

    private void schedule() {
        long due = policy.nextAt();
        if (due != Long.MAX_VALUE)
            main.postDelayed(pump, Math.max(0, due - SystemClock.elapsedRealtime()));
    }

    private void speakCurrent() {
        if (closed || !active || !ready || !enabled.getAsBoolean()) {
            reconcile();
            return;
        }
        // 同阶段的入镜、未匹配提醒不能互相抢断；切阶段由 enterStage 主动取消旧句。
        if (engine.isSpeaking()) {
            main.postDelayed(pump, 300);
            return;
        }
        String text = policy.poll(SystemClock.elapsedRealtime());
        if (text != null && engine.speak(text) == TextToSpeech.ERROR) {
            policy.speechRejected();
            Log.w("MorningVoice", "系统语音引擎未接受本次晨检提示");
        }
        schedule();
    }

    @Override
    public void close() {
        if (closed) return;
        pause();
        closed = true;
        engine.shutdown();
    }

    interface ReadyListener {
        void ready(int status);
    }

    interface SpeakerFactory {
        Speaker create(ReadyListener listener);
    }

    interface Speaker {
        int setLanguage();

        int speak(String text);

        boolean isSpeaking();

        void stop();

        void shutdown();
    }

    private static final class AndroidSpeaker implements Speaker {
        private final Context context;
        private final TextToSpeech speech;
        private final VoiceUtteranceTracker utterance = new VoiceUtteranceTracker();
        private long utteranceSequence;

        AndroidSpeaker(Context context, ReadyListener listener) {
            this.context = context;
            speech = new TextToSpeech(context, listener::ready);
            speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    finish(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    finish(utteranceId);
                }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    finish(utteranceId);
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    finish(utteranceId);
                }

                private void finish(String utteranceId) {
                    // 已结束或主动取消的旧句回调，不能清除正在播放的新句。
                    utterance.finish(utteranceId);
                }
            });
        }

        @Override
        public int setLanguage() {
            return speech.setLanguage(UiMessages.isEnglish(context) ? Locale.US : Locale.CHINA);
        }

        @Override
        public int speak(String text) {
            String id = "晨检提示-" + (++utteranceSequence);
            utterance.start(id, SystemClock.elapsedRealtime());
            int result = speech.speak(UiMessages.text(context, text), TextToSpeech.QUEUE_ADD, null, id);
            if (result == TextToSpeech.ERROR) utterance.finish(id);
            return result;
        }

        @Override
        public boolean isSpeaking() {
            // 已接受到真正开始发声之间同样算未完成；故障引擎漏回调也不会永久拖住流水线。
            return utterance.pending(SystemClock.elapsedRealtime(), 60000);
        }

        @Override
        public void stop() {
            utterance.clear();
            speech.stop();
        }

        @Override
        public void shutdown() {
            speech.shutdown();
        }
    }
}
