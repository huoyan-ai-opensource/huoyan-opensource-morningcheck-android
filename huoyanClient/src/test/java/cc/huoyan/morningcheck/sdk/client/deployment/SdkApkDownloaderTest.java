package cc.huoyan.morningcheck.sdk.client.deployment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

import static org.junit.Assert.*;

/** 使用本机 HTTP 服务检查下载字节、取消以及失败时的临时文件清理。 */
public class SdkApkDownloaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockWebServer server;
    private OkHttpClient http;

    @Before public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build();
    }

    @After public void tearDown() throws Exception {
        http.dispatcher().cancelAll();
        awaitDownloadWorker();
        http.connectionPool().evictAll();
        server.shutdown();
    }

    @Test public void completeAndUnknownLengthDownloadsDeliverExactBytesToSeparateFiles() throws Exception {
        byte[] bytes = new byte[150_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
        server.enqueue(new MockResponse().setChunkedBody(new Buffer().write(bytes), 8192));

        Result known = download(CancelMode.NONE);
        Result unknown = download(CancelMode.NONE);
        for (Result result : new Result[]{known, unknown}) {
            assertNull(result.error);
            assertNotNull(result.apk);
            assertArrayEquals(bytes, Files.readAllBytes(result.apk.toPath()));
            assertEquals(bytes.length, result.downloaded);
            assertEquals(bytes.length, result.total);
        }
        assertEquals(bytes.length, known.initialTotal);
        assertEquals(-1, unknown.initialTotal);
        assertNotEquals(known.apk.getAbsolutePath(), unknown.apk.getAbsolutePath());
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("GET", request.getMethod());
        assertEquals("/apk/morningcheck_sdk_lastest.apk", request.getPath());
    }

    @Test public void httpErrorEmptyAndTruncatedResponsesNeverDeliverAnApk() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("拒绝访问"));
        server.enqueue(new MockResponse().setBody(""));
        server.enqueue(new MockResponse().setBody("ab").setHeader("Content-Length", 10)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));

        for (int i = 0; i < 3; i++) {
            Result result = download(CancelMode.NONE);
            assertNotNull(result.error);
            assertNull(result.apk);
        }
        awaitDownloadWorker();
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void cancellationDuringTransferSuppressesResultAndRemovesPartialFile() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[150_000]))
                .throttleBody(4096, 1, TimeUnit.SECONDS));

        Result result = download(CancelMode.TRANSFER);
        awaitDownloadWorker();
        assertTrue(result.downloaded > 0);
        assertTrue(result.downloaded < 150_000);
        assertNull(result.apk);
        assertNull(result.error);
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void cancellationFromFinalProgressDoesNotDeliverCompletedFile() throws Exception {
        server.enqueue(new MockResponse().setChunkedBody(new Buffer().write(new byte[150_000]), 8192));

        Result result = download(CancelMode.FINAL_PROGRESS);
        awaitDownloadWorker();
        assertEquals(150_000, result.downloaded);
        assertEquals(150_000, result.total);
        assertNull(result.apk);
        assertNull(result.error);
        assertEquals(0, temporary.getRoot().list().length);
    }

    private Result download(CancelMode cancelMode) throws InterruptedException {
        SdkApkDownloader task = new SdkApkDownloader(
                server.url("/apk/morningcheck_sdk_lastest.apk").toString(), temporary.getRoot(), http);
        Result result = new Result();
        CountDownLatch callback = new CountDownLatch(1);
        task.start(new SdkApkDownloader.Listener() {
            @Override public void onProgress(long downloaded, long total) {
                if (downloaded == 0) result.initialTotal = total;
                result.downloaded = downloaded;
                result.total = total;
                if ((cancelMode == CancelMode.TRANSFER && downloaded > 0)
                        || (cancelMode == CancelMode.FINAL_PROGRESS && downloaded > 0 && total == downloaded)) {
                    task.cancel();
                    callback.countDown();
                }
            }
            @Override public void onComplete(File apk) {
                result.apk = apk;
                callback.countDown();
            }
            @Override public void onFailure(IOException error) {
                result.error = error;
                callback.countDown();
            }
        });
        assertTrue("下载应结束或被取消", callback.await(5, TimeUnit.SECONDS));
        return result;
    }

    /** 取消回调先返回，必须等待工作线程完成 finally 后再检查文件。 */
    private void awaitDownloadWorker() throws InterruptedException {
        http.dispatcher().executorService().shutdown();
        assertTrue("等待后台清理文件", http.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS));
    }

    private enum CancelMode { NONE, TRANSFER, FINAL_PROGRESS }

    private static final class Result {
        File apk;
        IOException error;
        long initialTotal;
        long downloaded;
        long total;
    }
}
