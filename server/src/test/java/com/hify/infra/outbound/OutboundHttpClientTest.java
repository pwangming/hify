package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OutboundHttpClientTest {

    private static HttpServer server;
    private static String base;
    private static OutboundHttpClient client;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "{\"weather\":\"晴\"}"));
        server.createContext("/missing", ex -> respond(ex, 404, "not found"));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1/target");
            respond(ex, 302, "");
        });
        server.createContext("/big", ex -> respond(ex, 200, "x".repeat(1000)));
        server.createContext("/slow", ex -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            respond(ex, 200, "late");
        });
        server.createContext("/echo", ex -> {
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, ex.getRequestMethod() + ":" + reqBody);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        OutboundProperties props = new OutboundProperties();
        props.setReadTimeoutMs(1000);       // /slow 睡 3s 必超时
        props.setMaxResponseBytes(100);     // /big 返回 1000 字节必截断
        client = new OutboundHttpClient(mock(SsrfValidator.class), props);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void GET成功_status与body如实返回() {
        OutboundResponse resp = client.send("GET", base + "/ok", Map.of(), null);
        assertEquals(200, resp.status());
        assertEquals("{\"weather\":\"晴\"}", resp.body());
    }

    @Test
    void 四百四_不抛异常_原样输出() {
        OutboundResponse resp = client.send("GET", base + "/missing", Map.of(), null);
        assertEquals(404, resp.status());
        assertEquals("not found", resp.body());
    }

    @Test
    void 三零二_不跟随_原样输出Location() {
        OutboundResponse resp = client.send("GET", base + "/redirect", Map.of(), null);
        assertEquals(302, resp.status());
        assertEquals("http://127.0.0.1/target", resp.headers().get("location"));
    }

    @Test
    void 响应超上限_截断到maxResponseBytes() {
        OutboundResponse resp = client.send("GET", base + "/big", Map.of(), null);
        assertEquals(100, resp.body().length());
    }

    @Test
    void 读超时_抛Biz依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", base + "/slow", Map.of(), null));
        assertEquals(10008, ex.errorCode().code());
    }

    @Test
    void POST带body_如实发出() {
        OutboundResponse resp = client.send("POST", base + "/echo", Map.of(), "{\"a\":1}");
        assertEquals("POST:{\"a\":1}", resp.body());
    }

    @Test
    void scheme非http_拒绝() {
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", "ftp://example.com/x", Map.of(), null));
        assertEquals(10001, ex.errorCode().code());
    }

    @Test
    void 受限头_拒绝并转Biz() {   // JDK HttpClient 禁止设置 Host 等受限头
        BizException ex = assertThrows(BizException.class,
                () -> client.send("GET", base + "/ok", Map.of("Host", "fake.com"), null));
        assertEquals(10001, ex.errorCode().code());
        assertTrue(ex.getMessage().contains("Host"));
    }
}
