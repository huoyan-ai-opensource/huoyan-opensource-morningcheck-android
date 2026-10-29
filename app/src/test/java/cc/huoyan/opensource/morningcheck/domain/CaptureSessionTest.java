package cc.huoyan.opensource.morningcheck.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureSessionTest {
    @Test public void onlyReadyFramesFromOriginalConnectionAndDeviceAreAccepted() {
        CaptureSession session = new CaptureSession();
        assertFalse(session.accepts(true, 8, "device-a"));
        session.begin(8, "device-a");
        assertTrue(session.accepts(true, 8, "device-a"));
        assertFalse(session.accepts(false, 8, "device-a"));
        assertFalse(session.accepts(true, 9, "device-a"));
        assertFalse(session.accepts(true, 8, "device-b"));
    }

    @Test public void interruptedPipelineCannotAcceptOldResultsAfterSdkRecovery() {
        CaptureSession session = new CaptureSession();
        session.begin(8, "device-a");
        session.clear();
        assertFalse(session.accepts(true, 8, "device-a"));
        session.begin(9, "device-a");
        assertFalse(session.accepts(true, 8, "device-a"));
        assertTrue(session.accepts(true, 9, "device-a"));
    }
}
