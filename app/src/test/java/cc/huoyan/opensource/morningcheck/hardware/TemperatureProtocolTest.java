package cc.huoyan.opensource.morningcheck.hardware;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TemperatureProtocolTest {
    @Test public void acceptsSplitAndJoinedAsciiPacketsAndRecoversFromNoise() {
        TemperatureProtocol protocol = new TemperatureProtocol(false);
        List<Double> values = new ArrayList<>();
        feed(protocol, "OK\r\n{36.", values);
        assertTrue(values.isEmpty());
        feed(protocol, "65}{ 37.2 }noise{bad}{0}{NaN}{Infinity}{36.7}", values);
        assertEquals(Arrays.asList(36.65, 37.2, 36.7), values);
    }

    @Test public void waitsForEntireBinaryFrameAndRejectsInvalidFraction() {
        TemperatureProtocol protocol = new TemperatureProtocol(true);
        List<Double> values = new ArrayList<>();
        byte[] frame = new byte[]{'C', 'T', 36, 65, 0, 0, 0, 0, 0, 0};
        protocol.accept(Arrays.copyOf(frame, 5), 5, values::add);
        assertTrue(values.isEmpty());
        protocol.accept(Arrays.copyOfRange(frame, 5, 10), 5, values::add);
        assertEquals(Arrays.asList(36.65), values);
        frame[3] = (byte) 120;
        protocol.accept(frame, frame.length, values::add);
        assertEquals(1, values.size());
    }

    @Test public void passiveModeDoesNotTreatCtBytesAsTemperature() {
        TemperatureProtocol protocol = new TemperatureProtocol(false);
        List<Double> values = new ArrayList<>();
        byte[] frame = new byte[]{'C', 'T', 36, 65, 0, 0, 0, 0, 0, 0};
        protocol.accept(frame, frame.length, values::add);
        assertTrue(values.isEmpty());
        feed(protocol, "{36.8}", values);
        assertEquals(Arrays.asList(36.8), values);
    }

    @Test public void boundedParserRecoversFromTruncatedAndNestedPackets() {
        TemperatureProtocol protocol = new TemperatureProtocol(true);
        List<Double> values = new ArrayList<>();
        feed(protocol, "{" + "x".repeat(5000) + "{bad{36.9}", values);
        assertEquals(Arrays.asList(36.9), values);
    }

    @Test public void separateSessionCannotReusePartialPreviousReading() {
        List<Double> values = new ArrayList<>();
        TemperatureProtocol old = new TemperatureProtocol(false);
        feed(old, "{39.", values);
        TemperatureProtocol next = new TemperatureProtocol(false);
        feed(next, "9}{36.5}", values);
        assertEquals(Arrays.asList(36.5), values);
    }

    private static void feed(TemperatureProtocol protocol, String value, List<Double> values) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        protocol.accept(bytes, bytes.length, values::add);
    }
}
