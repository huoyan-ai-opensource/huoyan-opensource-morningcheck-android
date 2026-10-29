package cc.huoyan.opensource.morningcheck.sdk;

import cc.huoyan.morningcheck.sdk.client.SdkConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class SdkInstallationPolicyTest {
    @Test public void localAidlAndLegacyLoopbackWsNeedTheLocalShell() {
        assertTrue(SdkInstallationPolicy.requiresLocalShell(SdkConfig.aidl(), 30));
        assertTrue(SdkInstallationPolicy.requiresLocalShell(SdkConfig.automatic(SdkConfig.DEFAULT_WS_ENDPOINT), 21));
        for (String endpoint : new String[]{"ws://127.0.0.1:18087/", "ws://LOCALHOST:18087/", "ws://[::1]:18087/"})
            assertTrue(SdkInstallationPolicy.requiresLocalShell(SdkConfig.webSocket(endpoint), 30));
    }

    @Test public void remoteWsNeverRequiresInstallingAShellOnTheClientDevice() {
        for (SdkConfig config : new SdkConfig[]{SdkConfig.webSocket("ws://192.168.1.20:18087/"),
                SdkConfig.automatic("ws://192.168.1.20:18087/"), SdkConfig.webSocket("wss://sdk.example.test:18087/")}) {
            assertFalse(SdkInstallationPolicy.requiresLocalShell(config, 21));
            assertFalse(SdkInstallationPolicy.requiresLocalShell(config, 30));
        }
    }

    @Test public void unsupportedAidlIsConfigurationErrorInsteadOfMissingInstallation() {
        assertThrows(IllegalArgumentException.class,
                () -> SdkInstallationPolicy.requiresLocalShell(SdkConfig.aidl(), 21));
    }
}
