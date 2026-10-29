package cc.huoyan.opensource.morningcheck.domain;

import java.util.concurrent.atomic.AtomicReference;

/**
 * TTS 完成回调来自 Binder 线程，按 ID 内容核对，并原子清除实际读到的那一条。
 */
public final class VoiceUtteranceTracker {
    private final AtomicReference<Utterance> current = new AtomicReference<>();

    public void start(String id, long now) {
        current.set(new Utterance(id, now));
    }

    public void finish(String callbackId) {
        Utterance accepted = current.get();
        if (accepted != null && callbackId != null && callbackId.equals(accepted.id))
            current.compareAndSet(accepted, null);
    }

    public boolean pending(long now, long timeoutMs) {
        Utterance accepted = current.get();
        return accepted != null && now - accepted.startedAt < timeoutMs;
    }

    public void clear() {
        current.set(null);
    }

    private static final class Utterance {
        final String id;
        final long startedAt;

        Utterance(String id, long startedAt) {
            this.id = id;
            this.startedAt = startedAt;
        }
    }
}
