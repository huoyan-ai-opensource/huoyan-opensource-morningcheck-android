package cc.huoyan.opensource.morningcheck.domain;

import java.util.Objects;

/**
 * 一次晨检只接受同一连接、设备身份和就绪状态下采集的证据。
 */
public final class CaptureSession {
    private boolean active;
    private long generation;
    private String deviceId;

    public void begin(long generation, String deviceId) {
        this.generation = generation;
        this.deviceId = deviceId;
        active = true;
    }

    public boolean accepts(boolean ready, long generation, String deviceId) {
        return active && ready && this.generation == generation && Objects.equals(this.deviceId, deviceId);
    }

    public void clear() {
        active = false;
        deviceId = null;
    }
}
