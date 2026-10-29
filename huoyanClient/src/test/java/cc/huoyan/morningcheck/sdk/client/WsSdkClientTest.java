package cc.huoyan.morningcheck.sdk.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.junit.Assert.*;

/** 通过可控传输验证关联、断线和取消，不访问真实 SDK 或业务服务。 */
public class WsSdkClientTest {
    private ScheduledThreadPoolExecutor io;
    private FakeConnector connector;
    private WsSdkClient client;
    private boolean fastTimeout;
    private boolean fastRetries;

    @Before public void createClient() {
        io = new ScheduledThreadPoolExecutor(1) {
            @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                if (fastTimeout && unit.toMillis(delay) == 40_000) {
                    return super.schedule(command, 30, TimeUnit.MILLISECONDS);
                }
                if (fastRetries && unit == TimeUnit.MILLISECONDS && delay <= 30_000) {
                    return super.schedule(command, 10, TimeUnit.MILLISECONDS);
                }
                return super.schedule(command, delay, unit);
            }
        };
        io.setRemoveOnCancelPolicy(true);
        connector = new FakeConnector();
        client = new WsSdkClient("1.2.3", null, Runnable::run, io, connector);
    }

    @After public void shutdown() throws Exception {
        if (!io.isShutdown()) {
            client.close();
            idle();
            io.shutdownNow();
        }
    }

    @Test public void connectsWithExplicitInitAndNeverAssumesReadyFromInitSuccess() throws Exception {
        FakeSocket socket = connect();
        assertEquals("init", command(socket.frames.get(0)));
        assertEquals("1.2.3", new JSONObject((String) socket.frames.get(0))
                .getJSONObject("params").getString("app_version"));
        assertEquals("getStatus", command(socket.frames.get(1)));
        assertEquals("getCapabilities", command(socket.frames.get(2)));
        assertEquals("get_class_table", command(socket.frames.get(3)));
        assertFalse(client.isReady());
        reply(socket, requestId(socket, "init"), 0, new JSONObject());
        assertFalse(client.isReady());
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "MODEL_PREPARING").put("model_ready", true));
        assertFalse(client.isReady());
        String statusId = client.request("getStatus", null, null, null);
        idle();
        reply(socket, statusId, 0, new JSONObject().put("sdk_state", "READY")
                .put("model_ready", true).put("certificate_valid", true));
        assertTrue(client.isReady());
        client.getStatus().put("sdk_state", "DISABLED");
        assertTrue(client.isReady());
    }

    @Test public void offlineReadinessAndOnlineRecoveryUseStatusWithoutRepeatingInitialization() throws Exception {
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "init"), 0, new JSONObject());
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "OFFLINE").put("model_ready", true)
                        .put("certificate_valid", true).put("key_lost", false).put("backend_connected", false));
        assertTrue(client.isReady());
        assertEquals("OFFLINE", client.getStatus().getString("sdk_state"));
        String id = client.request("getStatus", null, null, null);
        idle();
        reply(socket, id, 0, new JSONObject().put("sdk_state", "READY")
                .put("model_ready", true).put("certificate_valid", true).put("key_lost", false));
        assertTrue(client.isReady());
        assertEquals(1, countCommands(socket, "init"));
        client.close();
        idle();
        assertFalse(client.isReady());
    }

    @Test public void offlineCannotOverrideMissingOrInvalidReadinessEvidence() throws Exception {
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "OFFLINE"));
        assertFalse(client.isReady());
        JSONObject valid = new JSONObject().put("sdk_state", "OFFLINE").put("model_ready", true)
                .put("certificate_valid", true).put("key_lost", false);
        for (String field : new String[]{"model_ready", "certificate_valid"}) {
            for (Object value : new Object[]{false, JSONObject.NULL, "true"}) {
                String id = client.request("getStatus", null, null, null);
                idle();
                reply(socket, id, 0, new JSONObject(valid.toString()).put(field, value));
                assertFalse(field, client.isReady());
            }
        }
        String id = client.request("getStatus", null, null, null);
        idle();
        reply(socket, id, 0, new JSONObject(valid.toString()).put("key_lost", true));
        assertFalse(client.isReady());
        for (String state : new String[]{"MODEL_PREPARING", "CERTIFICATE_EXPIRED", "KEY_ROTATION_PENDING", "DISABLED", "UNKNOWN"}) {
            id = client.request("getStatus", null, null, null);
            idle();
            reply(socket, id, 0, new JSONObject(valid.toString()).put("sdk_state", state));
            assertFalse(state, client.isReady());
        }
    }

    @Test public void initializationCompletionDoesNotRequireCertificateOrReady() throws Exception {
        List<Boolean> completions = new CopyOnWriteArrayList<>();
        client.setInitializationListener(() -> completions.add(true));
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "init"), -3, new JSONObject());
        assertTrue(completions.isEmpty());
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "NOT_ACTIVATED").put("certificate_valid", false));
        assertEquals(1, completions.size());
        assertFalse(client.isReady());
        assertEquals(1, countCommands(socket, "init"));
    }

    @Test public void initializationCompletionWaitsForBusyRetryAndAcceptsTerminalFailure() throws Exception {
        fastRetries = true;
        List<Boolean> completions = new CopyOnWriteArrayList<>();
        client.setInitializationListener(() -> completions.add(true));
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "INITIALIZING"));
        reply(socket, requestId(socket, "init"), WsSdkClient.ERROR_BUSY, new JSONObject());
        assertTrue(completions.isEmpty());
        awaitCommands(socket, "init", 2);
        reply(socket, lastRequestId(socket, "init"), -110, new JSONObject());
        assertEquals(1, completions.size());
        assertFalse(client.isReady());
        // 后续快照不应让已经结束的自动初始化再次启动。
        reply(socket, lastRequestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "KEY_ROTATION_PENDING"));
        assertEquals(1, completions.size());
        assertEquals(2, countCommands(socket, "init"));
    }

    @Test public void unknownInitializationDoesNotPretendTheServerFinished() throws Exception {
        List<Boolean> completions = new CopyOnWriteArrayList<>();
        client.setInitializationListener(() -> completions.add(true));
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "init"), WsSdkClient.ERROR_RESULT_UNKNOWN, new JSONObject());
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "INITIALIZING"));
        assertTrue(completions.isEmpty());
    }

    @Test public void explicitBusyAndNotReadyReturnOnceWithoutHiddenStatusTriggeredRetries() throws Exception {
        fastRetries = true;
        List<Boolean> completions = new CopyOnWriteArrayList<>();
        List<Integer> results = new CopyOnWriteArrayList<>();
        client.setInitializationListener(() -> completions.add(true));
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "init"), 0, new JSONObject());
        reply(socket, requestId(socket, "getStatus"), 0, new JSONObject().put("sdk_state", "READY"));
        assertEquals(1, completions.size());
        for (int code : new int[]{WsSdkClient.ERROR_BUSY, -3}) {
            int previous = countCommands(socket, "init");
            String id = client.request("init", null, null, (value, message, data) -> results.add(value));
            idle();
            reply(socket, id, code, new JSONObject());
            reply(socket, lastRequestId(socket, "getStatus"), 0,
                    new JSONObject().put("sdk_state", "MODEL_PREPARING"));
            Thread.sleep(80);
            idle();
            assertEquals(previous + 1, countCommands(socket, "init"));
            assertEquals(Integer.valueOf(code), results.get(results.size() - 1));
        }
        assertEquals(2, results.size());
        // 确定失败仍报告初始化调用结束，不将待绑定请求永久留在客户端队列。
        assertEquals(3, completions.size());
    }

    @Test public void explicitInitializationCancelsPreviouslyScheduledConnectionRetry() throws Exception {
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "INITIALIZING"));
        reply(socket, requestId(socket, "init"), WsSdkClient.ERROR_BUSY, new JSONObject());
        String id = client.request("init", null, null, (code, message, data) -> { });
        idle();
        reply(socket, id, -3, new JSONObject());
        reply(socket, lastRequestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "INITIALIZING"));
        Thread.sleep(650);
        idle();
        assertEquals(2, countCommands(socket, "init"));
    }

    @Test public void explicitInitializationSuccessReportsCompletionButUnknownDoesNot() throws Exception {
        fastRetries = true;
        List<Boolean> completions = new CopyOnWriteArrayList<>();
        client.setInitializationListener(() -> completions.add(true));
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "init"), 0, new JSONObject());
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "NOT_ACTIVATED"));
        String success = client.request("init", null, null, (code, message, data) -> { });
        idle();
        reply(socket, success, 0, new JSONObject());
        assertEquals(2, completions.size());
        String unknown = client.request("init", null, null, (code, message, data) -> { });
        idle();
        reply(socket, unknown, WsSdkClient.ERROR_RESULT_UNKNOWN, new JSONObject());
        reply(socket, lastRequestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "INITIALIZING"));
        Thread.sleep(80);
        idle();
        assertEquals(2, completions.size());
        assertEquals(3, countCommands(socket, "init"));
    }

    @Test public void sendsTextThenBinaryAndRejectsSecondInFlightImage() throws Exception {
        FakeSocket socket = connect();
        byte[] pixels = {1, 2, 3, 4, 5, 6};
        List<Integer> secondResult = new CopyOnWriteArrayList<>();
        String first = client.request("identify", rgbParams(), pixels, null);
        String second = client.request("identify", rgbParams(), pixels,
                (code, message, data) -> secondResult.add(code));
        idle();
        assertNotEquals(first, second);
        assertEquals("identify", command(socket.frames.get(4)));
        ByteBuffer frame = ((ByteString) socket.frames.get(5)).asByteBuffer();
        int idLength = frame.getInt();
        byte[] idBytes = new byte[idLength];
        frame.get(idBytes);
        assertEquals(first, new String(idBytes, StandardCharsets.UTF_8));
        byte[] received = new byte[frame.remaining()];
        frame.get(received);
        assertArrayEquals(pixels, received);
        assertEquals(6, socket.frames.size());
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_BUSY), secondResult.get(0));
        reply(socket, first, 0, new JSONObject().put("matched", false));
        client.request("identify", rgbParams(), pixels, null);
        idle();
        assertEquals(8, socket.frames.size());
    }

    @Test public void cancelDeliversOnceButKeepsImageSlotUntilServerFinishes() throws Exception {
        FakeSocket socket = connect();
        List<Integer> results = new CopyOnWriteArrayList<>();
        String id = client.request("identify", rgbParams(), new byte[6],
                (code, message, data) -> results.add(code));
        idle();
        client.cancel(id);
        idle();
        assertEquals(1, results.size());
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_CANCELLED), results.get(0));
        List<Integer> next = new CopyOnWriteArrayList<>();
        client.request("identify", rgbParams(), new byte[6],
                (code, message, data) -> next.add(code));
        idle();
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_BUSY), next.get(0));
        reply(socket, id, 0, new JSONObject());
        assertEquals(1, results.size());
        client.request("identify", rgbParams(), new byte[6], null);
        idle();
        assertEquals(8, socket.frames.size());
    }

    @Test public void faceDetectionUsesImageSlotAndReturnsFacesFromMatchingRequest() throws Exception {
        FakeSocket socket = connect();
        List<JSONObject> results = new CopyOnWriteArrayList<>();
        List<Integer> blocked = new CopyOnWriteArrayList<>();
        byte[] pixels = {1, 2, 3, 4, 5, 6};
        String id = client.request("detectFace", rgbParams().put("max_faces", 3), pixels,
                (code, message, data) -> results.add(data));
        client.request("checkHead", rgbParams().put("max_detections", 8), pixels,
                (code, message, data) -> blocked.add(code));
        idle();
        JSONObject declaration = new JSONObject((String) socket.frames.get(4));
        assertEquals("detectFace", declaration.getString("cmd"));
        assertEquals(3, declaration.getJSONObject("params").getInt("max_faces"));
        ByteBuffer frame = ((ByteString) socket.frames.get(5)).asByteBuffer();
        byte[] requestId = new byte[frame.getInt()];
        frame.get(requestId);
        assertEquals(id, new String(requestId, StandardCharsets.UTF_8));
        byte[] received = new byte[frame.remaining()];
        frame.get(received);
        assertArrayEquals(pixels, received);
        assertEquals(List.of(WsSdkClient.ERROR_BUSY), blocked);
        JSONArray faces = new JSONArray().put(new JSONObject().put("x", 0.1).put("y", 0.2)
                .put("w", 0.3).put("h", 0.4).put("score", 0.9)
                .put("landmarks", new JSONArray().put(0.1).put(0.2).put(0.3).put(0.4)
                        .put(0.5).put(0.6).put(0.7).put(0.8).put(0.9).put(1.0)));
        reply(socket, id, 0, new JSONObject().put("faces", faces));
        assertEquals(1, results.size());
        assertEquals(0.3, results.get(0).getJSONArray("faces").getJSONObject(0).getDouble("w"), 0.000001);
        assertEquals(10, results.get(0).getJSONArray("faces").getJSONObject(0).getJSONArray("landmarks").length());
        client.request("checkHead", rgbParams().put("max_detections", 8), pixels, null);
        idle();
        assertEquals(8, socket.frames.size());
    }

    @Test public void faceDetectionRejectsInvalidLimitBeforeSendingAnyImage() throws Exception {
        FakeSocket socket = connect();
        List<Integer> results = new CopyOnWriteArrayList<>();
        for (Object limit : new Object[]{0, -1, 1.5, "3", JSONObject.NULL, 2147483648L}) {
            client.request("detectFace", rgbParams().put("max_faces", limit), new byte[6],
                    (code, message, data) -> results.add(code));
        }
        idle();
        assertEquals(6, results.size());
        for (Integer result : results) assertEquals(Integer.valueOf(WsSdkClient.ERROR_INVALID_REQUEST), result);
        assertEquals(4, socket.frames.size());
        String id = client.request("detectFace", rgbParams().put("max_faces", 65), new byte[6], null);
        idle();
        assertEquals(65, new JSONObject((String) socket.frames.get(4)).getJSONObject("params").getInt("max_faces"));
        reply(socket, id, 0, new JSONObject().put("faces", new JSONArray()));
        client.request("detectFace", rgbParams(), new byte[6], null);
        idle();
        assertEquals(8, socket.frames.size());
    }

    @Test public void reconnectNeverReplaysUncertainWriteAndIgnoresOldSocketCallbacks() throws Exception {
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "NOT_ACTIVATED"));
        String newerStatus = client.request("getStatus", null, null, null);
        idle();
        reply(socket, newerStatus, 0, new JSONObject().put("sdk_state", "NOT_ACTIVATED"));
        List<Integer> results = new CopyOnWriteArrayList<>();
        String id = client.request("register", rgbParams().put("person_id", 9), new byte[6],
                (code, message, data) -> results.add(code));
        idle();
        socket.listener.onFailure(socket, new IOException("测试断线"), null);
        idle();
        assertFalse(client.isConnected());
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_RESULT_UNKNOWN), results.get(0));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (connector.sockets.size() < 2 && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(2, connector.sockets.size());
        FakeSocket replacement = connector.sockets.get(1);
        replacement.listener.onOpen(replacement, null);
        idle();
        assertEquals(4, replacement.frames.size());
        for (Object frame : replacement.frames) assertNotEquals("register", command(frame));
        reply(socket, id, 0, new JSONObject().put("uid", 123));
        assertEquals(1, results.size());
        assertTrue(client.isConnected());
        // 新连接的首个快照序号重新从头开始，不能被旧连接的较大序号丢弃。
        reply(replacement, requestId(replacement, "getStatus"), 0,
                new JSONObject().put("sdk_state", "READY").put("model_ready", true)
                        .put("certificate_valid", true));
        assertTrue(client.isReady());
    }

    @Test public void localTimeoutIsUnknownAndLateResponseDoesNotDeliverTwice() throws Exception {
        FakeSocket socket = connect();
        fastTimeout = true;
        List<Integer> results = new CopyOnWriteArrayList<>();
        String id = client.request("forget", new JSONObject().put("person_id", 9), null,
                (code, message, data) -> results.add(code));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (results.isEmpty() && System.nanoTime() < until) Thread.sleep(5);
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_RESULT_UNKNOWN), results.get(0));
        reply(socket, id, 0, new JSONObject());
        assertEquals(1, results.size());
    }

    @Test public void closeCancelsReconnectAndFailsPendingOnlyOnce() throws Exception {
        FakeSocket socket = connect();
        List<Integer> results = new CopyOnWriteArrayList<>();
        client.request("forget", new JSONObject().put("person_id", 1), null,
                (code, message, data) -> results.add(code));
        idle();
        socket.listener.onFailure(socket, new IOException("测试断线"), null);
        client.close();
        idle();
        Thread.sleep(600);
        assertEquals(1, connector.sockets.size());
        assertFalse(client.isConnected());
        assertEquals(1, results.size());
        assertEquals(0, client.getStatus().length());
    }

    @Test public void invalidRgbNeverSendsDeclaration() throws Exception {
        FakeSocket socket = connect();
        List<Integer> results = new CopyOnWriteArrayList<>();
        client.request("identify", rgbParams(), new byte[5],
                (code, message, data) -> results.add(code));
        idle();
        assertEquals(4, socket.frames.size());
        assertEquals(Integer.valueOf(WsSdkClient.ERROR_INVALID_REQUEST), results.get(0));
    }

    @Test public void classTableAliasMatchesActualSdkCommandAndBackoffIsCapped() {
        assertEquals("get_class_table", MorningWsProtocol.command("getClassTable"));
        assertEquals(500, MorningWsProtocol.reconnectDelayMs(0));
        assertEquals(1000, MorningWsProtocol.reconnectDelayMs(1));
        assertEquals(30_000, MorningWsProtocol.reconnectDelayMs(100));
    }

    @Test public void initialBusyRetriesOnlySixTimesWithFreshIds() throws Exception {
        fastRetries = true;
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0, new JSONObject().put("sdk_state", "NOT_INIT"));
        String previous = requestId(socket, "init");
        for (int attempt = 0; attempt < 6; attempt++) {
            reply(socket, previous, WsSdkClient.ERROR_BUSY, new JSONObject());
            awaitCommands(socket, "init", attempt + 2);
            String next = lastRequestId(socket, "init");
            assertNotEquals(previous, next);
            previous = next;
        }
        reply(socket, previous, WsSdkClient.ERROR_BUSY, new JSONObject());
        Thread.sleep(80);
        idle();
        assertEquals(7, countCommands(socket, "init"));
        assertFalse(client.isReady());
    }

    @Test public void notReadyOnlyRetriesTemporaryStateAndStopsForExpiredCertificate() throws Exception {
        fastRetries = true;
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0, new JSONObject().put("sdk_state", "MODEL_NOT_READY"));
        reply(socket, requestId(socket, "init"), -3, new JSONObject());
        awaitCommands(socket, "init", 2);
        // 已知授权过期必须停下来，不能因相同 -3 而反复执行初始化。
        reply(socket, lastRequestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "CERTIFICATE_EXPIRED").put("certificate_valid", false));
        reply(socket, lastRequestId(socket, "init"), -3, new JSONObject());
        Thread.sleep(80);
        idle();
        assertEquals(2, countCommands(socket, "init"));
    }

    @Test public void readyStateWithExplicitInvalidCertificateNeverEnablesRecognition() throws Exception {
        FakeSocket socket = connect();
        reply(socket, requestId(socket, "getStatus"), 0,
                new JSONObject().put("sdk_state", "READY").put("certificate_valid", false));
        assertFalse(client.isReady());
        String id = client.request("getStatus", null, null, null);
        idle();
        reply(socket, id, 0, new JSONObject().put("sdk_state", "READY")
                .put("model_ready", true).put("certificate_valid", true));
        assertTrue(client.isReady());
    }

    @Test public void destroyShutsDownSchedulerAndSuppressesAlreadyQueuedCallbacks() throws Exception {
        List<Runnable> deliveries = new CopyOnWriteArrayList<>();
        List<Integer> results = new CopyOnWriteArrayList<>();
        client = new WsSdkClient("1.2.3", null, deliveries::add, io, connector);
        FakeSocket socket = connect();
        String id = client.request("forget", new JSONObject().put("person_id", 9), null,
                (code, message, data) -> results.add(code));
        idle();
        reply(socket, id, 0, new JSONObject());
        assertEquals(1, deliveries.size());
        client.destroy();
        assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(connector.destroyed);
        for (Runnable queued : deliveries) queued.run();
        assertTrue(results.isEmpty());
        assertFalse(client.isConnected());
        // 网络层末尾回调不得投递到已关闭的执行器，也不得再连接。
        socket.listener.onFailure(socket, new IOException("销毁后迟到回调"), null);
        client.connect("ws://127.0.0.1:18087/");
        assertEquals(1, connector.sockets.size());
    }

    @Test public void explicitInitAndReconnectCarryHostVersion() throws Exception {
        FakeSocket socket = connect();
        client.request("init", null, null, (code, message, data) -> { });
        idle();
        assertEquals(2, countCommands(socket, "init"));
        client.close();
        idle();
        client.connect("ws://127.0.0.1:18087/");
        idle();
        FakeSocket reconnected = connector.sockets.get(1);
        reconnected.listener.onOpen(reconnected, null);
        idle();
        assertEquals(1, countCommands(reconnected, "init"));
    }

    private void awaitCommands(FakeSocket socket, String command, int expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (countCommands(socket, command) < expected && System.nanoTime() < until) Thread.sleep(5);
        idle();
        assertEquals(expected, countCommands(socket, command));
    }

    private int countCommands(FakeSocket socket, String command) throws Exception {
        int count = 0;
        for (Object frame : socket.frames) {
            if (frame instanceof String && command.equals(command(frame))) {
                if ("init".equals(command)) {
                    assertEquals("1.2.3", new JSONObject((String) frame)
                            .getJSONObject("params").getString("app_version"));
                }
                count++;
            }
        }
        return count;
    }

    private String lastRequestId(FakeSocket socket, String command) throws Exception {
        String last = null;
        for (Object frame : socket.frames) {
            if (frame instanceof String) {
                JSONObject object = new JSONObject((String) frame);
                if (command.equals(object.optString("cmd"))) last = object.getString("requestId");
            }
        }
        assertNotNull(last);
        return last;
    }

    private FakeSocket connect() throws Exception {
        client.connect("ws://127.0.0.1:18087/");
        idle();
        FakeSocket socket = connector.sockets.get(0);
        socket.listener.onOpen(socket, null);
        idle();
        return socket;
    }

    private void reply(FakeSocket socket, String id, int code, JSONObject data) throws Exception {
        socket.listener.onMessage(socket, new JSONObject().put("requestId", id)
                .put("code", code).put("data", data).toString());
        idle();
    }

    private void idle() throws Exception {
        io.submit(() -> { }).get(3, TimeUnit.SECONDS);
    }

    private JSONObject rgbParams() throws Exception {
        return new JSONObject().put("width", 2).put("height", 1).put("format", "rgb");
    }

    private String command(Object frame) throws Exception {
        return new JSONObject((String) frame).getString("cmd");
    }

    private String requestId(FakeSocket socket, String command) throws Exception {
        for (Object frame : socket.frames) {
            if (frame instanceof String) {
                JSONObject object = new JSONObject((String) frame);
                if (command.equals(object.optString("cmd"))) return object.getString("requestId");
            }
        }
        throw new AssertionError("缺少请求：" + command);
    }

    private static final class FakeConnector implements WsSdkClient.Connector {
        final List<FakeSocket> sockets = new CopyOnWriteArrayList<>();
        boolean destroyed;
        @Override public WebSocket open(Request request, WebSocketListener listener) {
            FakeSocket socket = new FakeSocket(request, listener);
            sockets.add(socket);
            return socket;
        }
        @Override public void destroy() { destroyed = true; }
    }

    private static final class FakeSocket implements WebSocket {
        final Request request;
        final WebSocketListener listener;
        final List<Object> frames = new CopyOnWriteArrayList<>();
        boolean cancelled;

        FakeSocket(Request request, WebSocketListener listener) {
            this.request = request;
            this.listener = listener;
        }

        @Override public Request request() { return request; }
        @Override public long queueSize() { return 0; }
        @Override public boolean send(String text) { frames.add(text); return !cancelled; }
        @Override public boolean send(ByteString bytes) { frames.add(bytes); return !cancelled; }
        @Override public boolean close(int code, String reason) { cancelled = true; return true; }
        @Override public void cancel() { cancelled = true; }
    }
}
