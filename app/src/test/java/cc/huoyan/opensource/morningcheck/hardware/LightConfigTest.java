package cc.huoyan.opensource.morningcheck.hardware;

import static org.junit.Assert.*;
import org.junit.Test;

public final class LightConfigTest {
    @Test public void editableCommandsKeepLegacyAsciiAndAcceptBinaryProtocols() {
        assertArrayEquals("A00101A0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                LightConfig.commandBytes(LightConfig.DEFAULT_ON_COMMAND));
        assertArrayEquals("A00100A0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                LightConfig.commandBytes(LightConfig.DEFAULT_OFF_COMMAND));
        assertArrayEquals(LightConfig.commandBytes("ASCII:A00101A0"),
                LightConfig.commandBytes(LightConfig.DEFAULT_ON_COMMAND));
        assertArrayEquals(LightConfig.commandBytes("ASCII:A00100A0"),
                LightConfig.commandBytes(LightConfig.DEFAULT_OFF_COMMAND));
        assertArrayEquals(new byte[]{(byte) 0xA5, 1, (byte) 0xFF}, LightConfig.commandBytes("HEX:a5 01 FF"));
        assertArrayEquals(new byte[]{(byte) 0xA5, 0}, LightConfig.commandBytes("hex:A500"));
        assertArrayEquals(new byte[]{(byte) 0xA5, 1, (byte) 0xFF}, LightConfig.commandBytes("a5 01 FF"));
        assertArrayEquals(new byte[]{(byte) 0xA5, 0}, LightConfig.commandBytes("A500"));
        for (String invalid : new String[]{"", "A", "GG", "HEX:", "HEX:A", "HEX:GG", "ASCII:", "ASCII:中文"})
            assertThrows(IllegalArgumentException.class, () -> LightConfig.commandBytes(invalid));
        assertThrows(IllegalArgumentException.class, () -> new LightConfig(LightConfig.SERIAL,
                "/dev/ttyUSB0", 115200, "", 0, 1, "A5 01", "a501"));
    }

    @Test
    public void decimalCommandsAcceptUnsignedBytesAndRejectInvalidValues() {
        assertArrayEquals(new byte[]{0, 1, 127, (byte) 128, (byte) 255},
                LightConfig.commandBytes("DEC:0 1 127 128 255"));
        assertArrayEquals(new byte[]{65, 48, 49}, LightConfig.commandBytes(" dec: 65\t48\n49 "));
        for (String invalid : new String[]{"DEC:", "DEC:256", "DEC:-1", "DEC:+1", "DEC:1.5",
                "DEC:1,2", "DEC:0x41", "DEC:1 A", "DEC:999999999999999999999"})
            assertThrows(IllegalArgumentException.class, () -> LightConfig.commandBytes(invalid));
        assertThrows(IllegalArgumentException.class, () -> new LightConfig(LightConfig.SERIAL,
                "/dev/ttyUSB0", 115200, "", 0, 1, "ASCII:A", "DEC:65"));
    }

    @Test
    public void switchingRepresentationKeepsDeviceBytes() {
        assertEquals("DEC:65 48 48 49 48 49 65 48",
                LightConfig.formatCommand(LightConfig.DEFAULT_ON_COMMAND, LightConfig.DECIMAL));
        assertEquals("HEX:41 30 30 31 30 31 41 30",
                LightConfig.formatCommand(LightConfig.DEFAULT_ON_COMMAND, LightConfig.HEXADECIMAL));
        String hexadecimal = LightConfig.formatCommand("DEC:0 10 15 128 255", LightConfig.HEXADECIMAL);
        assertEquals("HEX:00 0A 0F 80 FF", hexadecimal);
        assertEquals("DEC:0 10 15 128 255", LightConfig.formatCommand(hexadecimal, LightConfig.DECIMAL));
        assertEquals(LightConfig.DEFAULT_ON_COMMAND, LightConfig.formatCommand("41 30 30 31 30 31 41 30", LightConfig.ASCII));
        for (int radix : new int[]{LightConfig.ASCII, LightConfig.DECIMAL, LightConfig.HEXADECIMAL})
            assertArrayEquals(LightConfig.commandBytes(LightConfig.DEFAULT_OFF_COMMAND),
                    LightConfig.commandBytes(LightConfig.formatCommand(LightConfig.DEFAULT_OFF_COMMAND, radix)));
        assertThrows(IllegalArgumentException.class, () -> LightConfig.formatCommand("HEX:41", 2));
        assertThrows(IllegalArgumentException.class, () -> LightConfig.formatCommand("DEC:256", LightConfig.HEXADECIMAL));
    }

    @Test
    public void characterConversionPreservesSpacesAndRejectsNonPrintableBytes() {
        String text = LightConfig.formatCommand("DEC:32 65 126 32", LightConfig.ASCII);
        assertEquals("ASCII: A~ ", text);
        assertArrayEquals(new byte[]{32, 65, 126, 32}, LightConfig.commandBytes(text));
        LightConfig config = new LightConfig(LightConfig.SERIAL, "/dev/ttyUSB0", 115200,
                "", 0, 1, text, "ASCII:B");
        assertEquals(text, config.onCommand);
        for (String invalid : new String[]{"DEC:0", "DEC:31", "DEC:127", "DEC:128", "DEC:255"})
            assertThrows(IllegalArgumentException.class, () -> LightConfig.formatCommand(invalid, LightConfig.ASCII));
        assertEquals("HEX:00 1F 7F 80 FF",
                LightConfig.formatCommand("DEC:0 31 127 128 255", LightConfig.HEXADECIMAL));
    }

    @Test
    public void allRepresentationsLimitCommandLengthTo64Bytes() {
        String decimal = "DEC:" + String.join(" ", java.util.Collections.nCopies(64, "255"));
        String hexadecimal = LightConfig.formatCommand(decimal, LightConfig.HEXADECIMAL);
        String text = "ASCII:" + String.join("", java.util.Collections.nCopies(64, "A"));
        assertEquals(64, LightConfig.commandBytes(decimal).length);
        assertEquals(64, LightConfig.commandBytes(hexadecimal).length);
        assertEquals(64, LightConfig.commandBytes(text).length);
        assertArrayEquals(LightConfig.commandBytes(decimal), LightConfig.commandBytes(hexadecimal));
        assertThrows(IllegalArgumentException.class, () -> LightConfig.commandBytes(decimal + " 0"));
        assertThrows(IllegalArgumentException.class, () -> LightConfig.commandBytes(hexadecimal + " 00"));
        assertThrows(IllegalArgumentException.class, () -> LightConfig.commandBytes(text + "A"));
    }

    @Test public void serialRejectsNonDeviceAndNestedPath() {
        assertThrows(IllegalArgumentException.class, () -> serial("/sdcard/light"));
        assertThrows(IllegalArgumentException.class, () -> serial("/dev/../other"));
        assertEquals("/dev/ttyUSB0", serial(" /dev/ttyUSB0 " ).path);
    }
    @Test public void gpioDeduplicatesPinsAndAcceptsChineseComma() {
        LightConfig config = gpio("122，148,122", 0, 1);
        assertArrayEquals(new int[]{122,148}, config.gpioIndexes());
        assertEquals("122,148", config.gpioSelections());
        int[] exposed = config.gpioIndexes();
        exposed[0] = 100;
        assertEquals(122, config.gpioIndexes()[0]);
    }
    @Test public void gpioRejectsInvalidPinsAndEqualLevels() {
        assertThrows(IllegalArgumentException.class, () -> gpio("", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gpio("122,", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gpio("-1", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gpio("65536", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gpio("122", 1, 1));
    }
    private LightConfig serial(String path) { return new LightConfig(LightConfig.SERIAL, path, 115200, "", 0, 1); }
    private LightConfig gpio(String pins, int on, int off) { return new LightConfig(LightConfig.GPIO, "", 115200, pins, on, off); }
}
