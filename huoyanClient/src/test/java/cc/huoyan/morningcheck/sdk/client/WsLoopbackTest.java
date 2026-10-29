package cc.huoyan.morningcheck.sdk.client;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 只访问本机 MockWebServer，检查真实 OkHttp 的文本/二进制线格式及回执关联。 */
public class WsLoopbackTest {
    @Test public void lateNotActivatedSnapshotCannotReplaceNewReadySnapshot() throws Exception {
        assertReverseStatusResponses("NOT_ACTIVATED", false, "READY", true);
    }

    @Test public void lateReadySnapshotWithOldEvidenceCannotReplaceNewReadySnapshot() throws Exception {
        assertReverseStatusResponses("READY", false, "READY", true);
    }

    @Test public void lateReadySnapshotCannotEnableRecognitionAfterNewNotActivatedSnapshot() throws Exception {
        assertReverseStatusResponses("READY", true, "NOT_ACTIVATED", false);
    }

    /** 本机 WS 将同连接两个状态查询逆序回执，验证缓存及独立回调的不同职责。 */
    private void assertReverseStatusResponses(String oldState, boolean oldReady,
            String newState, boolean newReady) throws Exception {
        MockWebServer server = new MockWebServer();
        OkHttpClient http = new OkHttpClient();
        ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1);
        CountDownLatch initialized = new CountDownLatch(1), replies = new CountDownLatch(2);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> oldId = new AtomicReference<>();
        AtomicReference<JSONObject> oldResult = new AtomicReference<>(), newResult = new AtomicReference<>();
        CopyOnWriteArrayList<String> observed = new CopyOnWriteArrayList<>();
        AtomicInteger statusQueries = new AtomicInteger();
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject request = new JSONObject(text);
                    String id = request.getString("requestId");
                    if ("getStatus".equals(request.getString("cmd"))) {
                        int query = statusQueries.incrementAndGet();
                        if (query == 2) {
                            oldId.set(id);
                            return;
                        }
                        if (query == 3) {
                            sendStatus(socket, id, newState, newReady, "fresh");
                            sendStatus(socket, oldId.get(), oldState, oldReady, "old");
                            return;
                        }
                        sendStatus(socket, id, "READY", true, "initial");
                    } else {
                        socket.send(new JSONObject().put("requestId", id).put("code", 0)
                                .put("data", new JSONObject()).toString());
                    }
                } catch (Throwable failure) {
                    error.set(failure); initialized.countDown();
                    replies.countDown(); replies.countDown();
                }
            }

            private void sendStatus(WebSocket socket, String id, String state, boolean ready,
                    String version) throws Exception {
                socket.send(new JSONObject().put("requestId", id).put("code", 0)
                        .put("data", new JSONObject().put("sdk_state", state).put("model_ready", ready)
                                .put("certificate_valid", ready).put("model_version", version)).toString());
            }
        }));
        server.start();
        WsSdkClient client = new WsSdkClient("1.2.3", new HuoyanClient.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) {
                String version = status.optString("model_version");
                observed.add(version);
                if ("initial".equals(version)) initialized.countDown();
            }
        }, Runnable::run, io, (request, listener) -> http.newWebSocket(request, listener));
        try {
            client.connect(server.url("/sdk").toString().replaceFirst("http", "ws"));
            assertTrue("初始快照未到达", initialized.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            client.request("getStatus", null, null, (code, message, data) -> {
                oldResult.set(data); replies.countDown();
            });
            client.request("getStatus", null, null, (code, message, data) -> {
                newResult.set(data); replies.countDown();
            });
            assertTrue("两个状态查询回执未到达", replies.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals("old", oldResult.get().getString("model_version"));
            assertEquals("fresh", newResult.get().getString("model_version"));
            assertEquals(newState, client.getStatus().getString("sdk_state"));
            assertEquals("fresh", client.getStatus().getString("model_version"));
            assertEquals(newReady, client.isReady());
            assertFalse("旧快照不得通知共享监听器", observed.contains("old"));
            assertEquals(2, observed.size());
        } finally {
            client.destroy();
            io.awaitTermination(3, TimeUnit.SECONDS);
            http.dispatcher().cancelAll();
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
            server.shutdown();
        }
    }

    @Test public void realSocketInitializesAndSendsRgbWithMatchingRequestId() throws Exception {
        MockWebServer server = new MockWebServer();
        OkHttpClient http = new OkHttpClient();
        ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1);
        CountDownLatch ready = new CountDownLatch(1), identified = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<String> declaredId = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        byte[] rgb = {1, 2, 3, 4, 5, 6};
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject request = new JSONObject(text);
                    String id = request.getString("requestId");
                    String command = request.getString("cmd");
                    if ("identify".equals(command)) {
                        declaredId.set(id);
                        assertEquals(2, request.getJSONObject("params").getInt("width"));
                        return;
                    }
                    JSONObject data = new JSONObject();
                    if ("getStatus".equals(command)) {
                        data.put("sdk_state", "READY").put("model_ready", true).put("certificate_valid", true);
                    } else if ("get_class_table".equals(command)) {
                        data.put("head", new org.json.JSONArray()).put("hand", new org.json.JSONArray());
                    }
                    socket.send(new JSONObject().put("requestId", id).put("code", 0).put("data", data).toString());
                } catch (Throwable failure) { error.set(failure); ready.countDown(); identified.countDown(); }
            }
            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                try {
                    ByteBuffer buffer = bytes.asByteBuffer();
                    byte[] id = new byte[buffer.getInt()];
                    buffer.get(id);
                    assertEquals(declaredId.get(), new String(id, StandardCharsets.UTF_8));
                    byte[] pixels = new byte[buffer.remaining()];
                    buffer.get(pixels);
                    received.set(pixels);
                    socket.send(new JSONObject().put("requestId", declaredId.get()).put("code", 0)
                            .put("data", new JSONObject().put("person_id", 9007199254740991L)
                                    .put("matched", true).put("similarity", 0.98)).toString());
                } catch (Throwable failure) { error.set(failure); identified.countDown(); }
            }
        }));
        server.start();
        WsSdkClient client = new WsSdkClient("1.2.3", new HuoyanClient.Listener() {
            @Override public void onConnection(boolean connected, String message) { }
            @Override public void onStatus(JSONObject status) {
                if ("READY".equals(status.optString("sdk_state"))) ready.countDown();
            }
        }, Runnable::run, io, new WsSdkClient.Connector() {
            @Override public WebSocket open(Request request, WebSocketListener listener) {
                return http.newWebSocket(request, listener);
            }
        });
        try {
            client.connect(server.url("/sdk").toString().replaceFirst("http", "ws"));
            assertTrue("初始化状态回执超时", ready.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue(client.isReady());
            client.request("identify", new JSONObject().put("width", 2).put("height", 1), rgb,
                    (code, message, data) -> {
                        try {
                            assertEquals(0, code);
                            assertEquals(9007199254740991L, data.getLong("person_id"));
                            assertTrue(data.getBoolean("matched"));
                        } catch (Throwable failure) { error.set(failure); }
                        finally { identified.countDown(); }
                    });
            assertTrue("识别回执超时", identified.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertArrayEquals(rgb, received.get());
        } finally {
            client.destroy();
            io.awaitTermination(3, TimeUnit.SECONDS);
            http.dispatcher().cancelAll();
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
            server.shutdown();
        }
    }
}
