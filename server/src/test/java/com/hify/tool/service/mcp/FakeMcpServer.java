package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用最小 MCP 服务器桩（Streamable HTTP）：只实现 initialize / 通知 / tools/list / tools/call，
 * POST 进来、JSON 回去，不做 SSE（Streamable HTTP 允许单个 JSON 响应）。
 * 记录收到的 Authorization 头供断言鉴权头注入。
 */
final class FakeMcpServer implements AutoCloseable {

    private final HttpServer http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> seenAuthHeaders = new CopyOnWriteArrayList<>();

    private volatile int forcedStatus = 0;
    private volatile String callToolText = "OK";
    private volatile boolean callToolIsError = false;
    private volatile List<String> toolNames = List.of("search_docs");

    FakeMcpServer() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", this::handle);
        http.start();
    }

    String url() {
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    List<String> seenAuthHeaders() { return seenAuthHeaders; }

    /** >0 时所有请求直接返回该状态码（测失败路径）。 */
    void forceStatus(int status) { forcedStatus = status; }

    void callToolReturns(String text, boolean isError) {
        callToolText = text;
        callToolIsError = isError;
    }

    void toolNames(List<String> names) { toolNames = names; }

    private void handle(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            seenAuthHeaders.add(auth);
        }
        if (forcedStatus > 0) {
            ex.sendResponseHeaders(forcedStatus, -1);
            ex.close();
            return;
        }
        JsonNode req = mapper.readTree(ex.getRequestBody());
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) {        // 通知（notifications/initialized）无需响应体
            ex.sendResponseHeaders(202, -1);
            ex.close();
            return;
        }
        String result = switch (req.path("method").asText()) {
            case "initialize" -> initializeResult(req);
            case "tools/list" -> toolsListResult();
            case "tools/call" -> callToolResult();
            default -> null;
        };
        String body = result == null
                ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"method not found\"}}"
                : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** 原样回显客户端请求的协议版本，避免版本协商失败。 */
    private String initializeResult(JsonNode req) {
        String pv = req.path("params").path("protocolVersion").asText("2024-11-05");
        return "{\"protocolVersion\":\"" + pv + "\","
                + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                + "\"serverInfo\":{\"name\":\"fake-mcp\",\"version\":\"1.0.0\"}}";
    }

    private String toolsListResult() {
        List<String> items = new ArrayList<>();
        for (String n : toolNames) {
            items.add("{\"name\":\"" + n + "\",\"description\":\"desc of " + n + "\","
                    + "\"inputSchema\":{\"type\":\"object\","
                    + "\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}");
        }
        return "{\"tools\":[" + String.join(",", items) + "]}";
    }

    private String callToolResult() {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + callToolText + "\"}],"
                + "\"isError\":" + callToolIsError + "}";
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
