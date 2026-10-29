package cc.huoyan.morningcheck.sdk.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class SdkConfigTest {
    @Test public void automaticRoutesOnlyLocalDevicesToSupportedBinder() {
        for (String host : new String[]{"127.0.0.1", "localhost", "[::1]"}) {
            SdkConfig config = SdkConfig.automatic("ws://" + host + ":18087/");
            assertEquals(SdkConfig.Channel.AIDL, config.getResolvedChannel(27));
            assertEquals(SdkConfig.Channel.WS, config.getResolvedChannel(26));
        }
        assertEquals(SdkConfig.Channel.WS,
                SdkConfig.automatic("ws://192.168.1.99:18087/").getResolvedChannel(35));
        assertEquals(SdkConfig.Channel.WS,
                SdkConfig.automatic("wss://sdk.example.com/sdk").getResolvedChannel(35));
    }

    @Test public void explicitWsNeverTurnsIntoBinder() {
        assertEquals(SdkConfig.Channel.WS,
                SdkConfig.webSocket(SdkConfig.DEFAULT_WS_ENDPOINT).getResolvedChannel(35));
    }

    @Test public void oldAndroidCannotSilentlyAcceptExplicitAidl() {
        assertThrows(IllegalArgumentException.class, () -> SdkConfig.aidl().getResolvedChannel(26));
    }

    @Test public void validatesDestinationBeforeDroppingExistingConnection() {
        for (String address : new String[]{"", "http://localhost", "ws:///sdk", "ws://u:p@host/",
                "ws://localhost:0", "ws://localhost:65536", "ws://host/#fragment"}) {
            assertThrows(address, IllegalArgumentException.class, () -> SdkConfig.webSocket(address));
        }
    }
}
