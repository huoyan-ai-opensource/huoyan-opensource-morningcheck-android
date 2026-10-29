package cc.huoyan.opensource.morningcheck.domain;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 当前阶段首句仅播一次；阶段内事件依次播报，可变状态只保留当前提示。
 */
public final class VoicePromptPolicy {
    private final ArrayDeque<Prompt> stages = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>(), announcedKeys = new HashSet<>();
    private final Map<String, Long> statusSpokenAt = new HashMap<>();
    private String key = "", text = "";
    private String polledKey = "";
    private String rejectedStatusKey = "";
    private long stableAt, repeatMs, spokenAt;
    private boolean spoken;

    public void offer(String nextKey, String nextText, long delayMs, long repeatMs, long now) {
        if (nextKey == null || nextText == null || nextText.trim().isEmpty()) {
            clearCurrent();
            return;
        }
        if (!key.equals(nextKey) || nextKey.equals(rejectedStatusKey)) {
            boolean rejected = nextKey.equals(rejectedStatusKey);
            rejectedStatusKey = "";
            key = nextKey;
            stableAt = now + Math.max(0, delayMs);
            // 预览短暂未知或空框只取消待播，不能抹掉每种提醒已经播过的冷却时间。
            spoken = !rejected && (statusSpokenAt.containsKey(nextKey)
                    || repeatMs == 0 && announcedKeys.contains(nextKey));
            if (spoken) spokenAt = statusSpokenAt.getOrDefault(nextKey, 0L);
        }
        text = nextText;
        this.repeatMs = Math.max(0, repeatMs);
    }

    public void offerOnce(String key, String text) {
        if (key == null || text == null || text.trim().isEmpty()
                || announcedKeys.contains(key) || !queuedKeys.add(key)) return;
        stages.addLast(new Prompt(key, text));
    }

    /**
     * 只有实际可以交给 TTS 时才调用，初始化或后台等待不能提前消耗提示。
     */
    public String poll(long now) {
        if (!stages.isEmpty()) {
            Prompt next = stages.removeFirst();
            queuedKeys.remove(next.key);
            announcedKeys.add(next.key);
            polledKey = next.key;
            return next.text;
        }
        long due = nextAt();
        if (due == Long.MAX_VALUE || now < due) return null;
        spoken = true;
        spokenAt = now;
        statusSpokenAt.put(key, now);
        announcedKeys.add(key);
        polledKey = key;
        return text;
    }

    public long nextAt() {
        if (!stages.isEmpty()) return 0;
        if (key.isEmpty()) return Long.MAX_VALUE;
        if (key.equals(rejectedStatusKey)) return Long.MAX_VALUE;
        if (!spoken) return stableAt;
        return repeatMs == 0 ? Long.MAX_VALUE : Math.max(stableAt, spokenAt + repeatMs);
    }

    public boolean hasSpoken() {
        return spoken;
    }

    public boolean hasAnnounced(String key) {
        return announcedKeys.contains(key);
    }

    /**
     * 引擎未受理不能保存成已播；不立即自旋重试，留给后续页面状态再次请求。
     */
    public void speechRejected() {
        announcedKeys.remove(polledKey);
        statusSpokenAt.remove(polledKey);
        if (polledKey.equals(key)) rejectedStatusKey = key;
    }

    public void restoreAnnounced(String key) {
        if (key == null) return;
        announcedKeys.add(key);
        stages.removeIf(prompt -> key.equals(prompt.key));
        queuedKeys.remove(key);
    }

    public void clearCurrent() {
        key = text = "";
        rejectedStatusKey = "";
        spoken = false;
        stableAt = spokenAt = repeatMs = 0;
    }

    /**
     * 后台取消未播提示，保留已经交给引擎的阶段，返回前台不能重复同一阶段。
     */
    public void cancelPending() {
        clearCurrent();
        stages.clear();
        queuedKeys.clear();
    }

    public void reset() {
        cancelPending();
        announcedKeys.clear();
        statusSpokenAt.clear();
    }

    private static final class Prompt {
        final String key, text;

        Prompt(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }
}
