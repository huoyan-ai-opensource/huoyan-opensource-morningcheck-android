package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.junit.Assert.*;

public class SdkDiscoveryTest {
    @Test public void ipUsesMorningcheckEndpointAndRejectsUrls() {
        assertEquals("ws://192.168.1.2:18087/", SdkDiscovery.endpointForIp(" 192.168.001.002 "));
        assertEquals("192.168.1.2", SdkDiscovery.ipFromEndpoint("ws://192.168.1.2:18087/"));
        for (String value : new String[]{"", "hostname", "256.0.0.1", "1.2.3", "ws://1.2.3.4", "1.2.3.4:18087"}) {
            try { SdkDiscovery.endpointForIp(value); fail(value); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals("", SdkDiscovery.ipFromEndpoint("garbage"));
    }

    @Test public void probeOnlyReadsStatusAndRequiresMatchingReceipt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject request = new JSONObject(text);
                        assertEquals("getStatus", request.getString("cmd"));
                        requests.incrementAndGet();
                        socket.send(new JSONObject().put("requestId", "unrelated").put("code", 0)
                                .put("data", new JSONObject().put("sdk_state", "READY").put("device_id", "test")).toString());
                        socket.send(new JSONObject().put("requestId", request.getString("requestId")).put("code", 0)
                                .put("data", new JSONObject().put("sdk_state", "WAIT_ACTIVATION").put("device_id", "test")).toString());
                    } catch (Exception error) { throw new AssertionError(error); }
                }
            }));
            server.start();
            assertTrue(SdkDiscovery.probe(server.url("/").toString().replace("http://", "ws://")));
            assertEquals(1, requests.get());
        }
    }

    @Test public void handshakeOrIncompleteStatusIsNotASdk() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject request = new JSONObject(text);
                        socket.send(new JSONObject().put("requestId", request.getString("requestId"))
                                .put("code", 0).put("data", new JSONObject().put("sdk_state", "READY")).toString());
                    } catch (Exception error) { throw new AssertionError(error); }
                }
            }));
            server.start();
            assertFalse(SdkDiscovery.probe(server.url("/").toString().replace("http://", "ws://")));
        }
    }
}
