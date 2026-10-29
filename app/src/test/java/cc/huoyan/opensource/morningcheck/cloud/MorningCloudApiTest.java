package cc.huoyan.opensource.morningcheck.cloud;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public class MorningCloudApiTest {
    private MockWebServer server;
    private MorningCloudApi api;

    @Before public void prepare() throws Exception {
        server = new MockWebServer();
        server.start();
        api = new MorningCloudApi(new OkHttpClient.Builder()
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .callTimeout(1500, TimeUnit.MILLISECONDS).build());
    }

    @After public void close() throws Exception {
        server.shutdown();
    }

    private String base() { return server.url("/").toString(); }

    @Test public void staffQueriesKeepRawResponseAndUseExpectedPaths() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"id\":\"7\",\"name\":\"员工\"}}"));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"records\":[]}}"));
        assertEquals("7", api.getStaff(base(), "demo-token", 7).getJSONObject("data").getString("id"));
        api.listStaff(base(), "demo-token", 2, 50);
        RecordedRequest detail = server.takeRequest();
        assertEquals("GET", detail.getMethod());
        assertEquals("/staff/7/", detail.getPath());
        assertEquals("demo-token", detail.getHeader("X-USER-TOKEN"));
        RecordedRequest list = server.takeRequest();
        assertEquals("/staff/?page=2&size=50", list.getPath());
        assertEquals("demo-token", list.getHeader("X-USER-TOKEN"));
    }

    @Test public void explicitBusinessRejectionPreservesBusinessCode() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":4001,\"message\":\"门店无效\"}"));
        try {
            api.listStaff(base(), "demo-token", 1, 20);
            fail("业务失败不能作为员工同步成功");
        } catch (MorningCloudApi.ApiException e) {
            assertEquals(Integer.valueOf(4001), e.businessCode);
            assertEquals("门店无效", e.getMessage());
        }
    }

    @Test public void malformedBusinessCodeCannotBecomeSuccessfulEmployeeSync() throws Exception {
        for (String response : new String[]{"{\"data\":{\"records\":[]}}", "{\"code\":0.5}"}) {
            server.enqueue(new MockResponse().setBody(response));
            try {
                api.listStaff(base(), "demo-token", 1, 20);
                fail("缺失或小数状态码不能转换成成功");
            } catch (IOException expected) {
                assertEquals("业务响应格式无效", expected.getMessage());
            }
        }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void invalidPaginationIsRejectedBeforeAnyNetworkRequest() throws Exception {
        for (int[] page : new int[][]{{0, 20}, {1, 0}, {1, 101}}) {
            try {
                api.listStaff(base(), "demo-token", page[0], page[1]);
                fail("无效分页不能发起员工同步");
            } catch (IllegalArgumentException expected) {
                assertEquals(0, server.getRequestCount());
            }
        }
    }
}
