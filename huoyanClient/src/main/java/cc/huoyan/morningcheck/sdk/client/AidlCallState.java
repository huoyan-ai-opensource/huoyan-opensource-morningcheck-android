package cc.huoyan.morningcheck.sdk.client;

/**
 * 调度器与 Binder 工作线程共享的一次性提交门；超时后排队任务不得再发送。
 */
final class AidlCallState {
    private boolean started;
    private boolean finished;

    /**
     * 紧邻 Binder 调用执行；与超时竞争时只有先取得锁的一侧能决定请求是否发出。
     */
    synchronized boolean begin() {
        if (started || finished) return false;
        started = true;
        return true;
    }

    /**
     * 真实回执、超时、断连只有一个能完成请求。
     */
    synchronized boolean finish() {
        if (finished) return false;
        finished = true;
        return true;
    }

    synchronized boolean hasStarted() {
        return started;
    }

    synchronized boolean isFinished() {
        return finished;
    }
}
