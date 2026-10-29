package cc.huoyan.opensource.morningcheck.domain;

/**
 * 保存新鲜的人脸在场证据，用于入镜提示和测温启动；不替代 SDK 身份准入，由主线程串行更新。
 */
public final class FacePresencePolicy {
    public static final long MAX_AGE_NANOS = 1_500_000_000L;
    public static final long MAX_MATCH_FRAME_AGE_NANOS = 5_000_000_000L;
    private static final long NEGATIVE_RESULT_AGE_NANOS = 5_000_000_000L;

    private boolean observed, available, present;
    private int faceCount;
    private long observedAtNanos;
    private long presenceExpiresAtNanos;
    private boolean matchObserved, matchAvailable, matched;
    private long matchedAtNanos, matchReceivedAtNanos;

    public boolean observe(long frameNanos, boolean present, long nowNanos) {
        return observeFaces(frameNanos, present ? 1 : 0, nowNanos);
    }

    public boolean observeFaces(long frameNanos, int count, long nowNanos) {
        return observeFaces(frameNanos, count, nowNanos, MAX_AGE_NANOS, frameNanos + MAX_AGE_NANOS);
    }

    /**
     * 推理结果单独限制采集时效与展示时效，不能用接收时间无限续期旧帧。
     */
    public boolean observeDetection(long frameNanos, int count, long nowNanos) {
        return observeFaces(frameNanos, count, nowNanos, MAX_MATCH_FRAME_AGE_NANOS,
                Math.min(frameNanos + MAX_MATCH_FRAME_AGE_NANOS, nowNanos + MAX_AGE_NANOS));
    }

    private boolean observeFaces(long frameNanos, int count, long nowNanos, long maxAge, long expiresAt) {
        if (count < 0) return false;
        boolean present = count > 0;
        long age = nowNanos - frameNanos;
        if (age < 0 || age >= maxAge || observed && frameNanos < observedAtNanos) return false;
        // 同一帧 SDK 匹配到人脸时，即使系统检测器漏检，也不能再用空框覆盖肯定证据。
        if (observed && frameNanos == observedAtNanos && available && this.present && !present)
            return false;
        observed = available = true;
        observedAtNanos = frameNanos;
        presenceExpiresAtNanos = expiresAt;
        this.present = present;
        faceCount = count;
        return true;
    }

    public boolean isMultiple(long nowNanos) {
        return faceCount > 1 && isPresent(nowNanos);
    }

    public boolean unknown(long frameNanos) {
        if (observed && (frameNanos < observedAtNanos
                || frameNanos == observedAtNanos && available && present)) return false;
        observed = true;
        observedAtNanos = frameNanos;
        available = false;
        return true;
    }

    public boolean isAbsent(long nowNanos) {
        long age = nowNanos - observedAtNanos;
        return observed && available && !present && age >= 0 && nowNanos < presenceExpiresAtNanos;
    }

    public boolean isPresent(long nowNanos) {
        long age = nowNanos - observedAtNanos;
        return observed && available && present && age >= 0 && nowNanos < presenceExpiresAtNanos;
    }

    public boolean match(long frameNanos, boolean matched, long nowNanos) {
        long age = nowNanos - frameNanos;
        if (age < 0 || age >= MAX_MATCH_FRAME_AGE_NANOS
                || matchObserved && frameNanos < matchedAtNanos) return false;
        // 迟到的正匹配能撤销旧负匹配，但不能延长头部框的采集帧时效。
        if (matched && age >= MAX_AGE_NANOS) {
            matchingUnknown(frameNanos);
            return false;
        }
        if (matchObserved && frameNanos == matchedAtNanos && matchAvailable && this.matched && !matched)
            return false;
        matchObserved = matchAvailable = true;
        matchedAtNanos = frameNanos;
        // 推理耗时不应消耗未匹配提醒的有效期；采集时间仍用于过滤过期、逆序回包。
        matchReceivedAtNanos = nowNanos;
        this.matched = matched;
        if (matched) observe(frameNanos, true, nowNanos);
        return true;
    }

    public boolean matchingUnknown(long frameNanos) {
        if (matchObserved && frameNanos < matchedAtNanos) return false;
        matchObserved = true;
        matchedAtNanos = frameNanos;
        matchAvailable = false;
        return true;
    }

    public boolean isMatched(long nowNanos) {
        return matchAvailable && matched && matchFresh(nowNanos);
    }

    public boolean isUnmatched(long nowNanos) {
        return isPresent(nowNanos) && matchAvailable && !matched && matchFresh(nowNanos);
    }

    private boolean matchFresh(long nowNanos) {
        long age = nowNanos - (matched ? matchedAtNanos : matchReceivedAtNanos);
        return matchObserved && age >= 0 && age < (matched ? MAX_AGE_NANOS : NEGATIVE_RESULT_AGE_NANOS);
    }

    public long presenceExpiresAtNanos() {
        return observed && available ? presenceExpiresAtNanos : 0;
    }

    public long expiresAtNanos() {
        if (!observed || !available) return 0;
        long expiry = presenceExpiresAtNanos;
        return present && matchAvailable && !matched
                ? Math.min(expiry, matchReceivedAtNanos + NEGATIVE_RESULT_AGE_NANOS) : expiry;
    }

    public void reset() {
        observed = available = present = false;
        faceCount = 0;
        observedAtNanos = 0;
        presenceExpiresAtNanos = 0;
        matchObserved = matchAvailable = matched = false;
        matchedAtNanos = matchReceivedAtNanos = 0;
    }
}
