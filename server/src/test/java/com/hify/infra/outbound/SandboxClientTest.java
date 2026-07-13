package com.hify.infra.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.sun.net.httpserver.HttpExchange;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxClientTest {

    private static HttpServer server;
    private static SandboxClient client;
    private static SandboxProperties props;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"slow\"")) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                respond(ex, 200, "{\"ok\":true,\"outputs\":{}}");
            } else if (body.contains("\"boom\"")) {
                respond(ex, 200, "{\"ok\":false,\"error\":\"执行出错：NameError: x\"}");
            } else if (body.contains("\"big\"")) {
                respond(ex, 200, "{\"ok\":true,\"outputs\":{\"v\":\"" + "x".repeat(2000) + "\"}}");
            } else {
                respond(ex, 200, "{\"ok\":true,\"outputs\":{\"count\":3}}");
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        props = new SandboxProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setReadTimeoutMs(1000);       // /slow 睡 3s 必超时
        props.setMaxOutputBytes(500);       // big 响应 >500 字节必判超限
        client = new SandboxClient(props, new ObjectMapper());
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void 正常执行_返回outputs() {
        SandboxResult r = client.run("def main(text): return {'count': 3}", Map.of("text", "a b c"));
        assertTrue(r.ok());
        assertEquals(3, r.outputs().get("count"));
    }

    @Test
    void 用户代码失败_ok为false原样返回() {
        SandboxResult r = client.run("boom", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("NameError"));
    }

    @Test
    void 读超时_抛依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.run("slow", Map.of()));
        assertEquals(10008, ex.errorCode().code());
    }

    @Test
    void 响应超上限_抛依赖不可用() {
        BizException ex = assertThrows(BizException.class,
                () -> client.run("big", Map.of()));
        assertEquals(10008, ex.errorCode().code());
    }
}
