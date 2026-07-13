package com.hify.tool.service.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 内置 HTTP 工具：复用 infra 的 OutboundHttpClient（自带 SSRF/双超时/Redirect.NEVER/响应截断）。
 * method 白名单在本工具校验；任何失败以错误文本返回给模型，不抛（不中断 Agent 循环）。
 */
@Component
public class HttpRequestTool implements BuiltinTool {

    private static final Set<String> ALLOWED = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");
    private static final String SCHEMA = """
            {"type":"object","properties":{
              "method":{"type":"string","description":"HTTP 方法：GET/POST/PUT/DELETE/PATCH"},
              "url":{"type":"string","description":"完整 http/https URL"},
              "headers":{"type":"object","description":"可选，请求头键值对（字符串→字符串）"},
              "body":{"type":"string","description":"可选，请求体字符串"}},
              "required":["method","url"]}""";

    private final OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpRequestTool(OutboundHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "http_request";
    }

    @Override
    public String inputSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(String argsJson) {
        JsonNode args;
        try {
            args = objectMapper.readTree(argsJson);
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        String method = args.path("method").asText("").toUpperCase();
        if (!ALLOWED.contains(method)) {
            return "错误：不支持的 HTTP 方法：" + method;
        }
        String url = args.path("url").asText("");
        if (url.isBlank()) {
            return "错误：缺少 url 参数";
        }
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode h = args.get("headers");
        if (h != null && h.isObject()) {
            h.fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
        }
        String body = args.hasNonNull("body") ? args.get("body").asText() : null;
        try {
            OutboundResponse resp = httpClient.send(method, url, headers, body);
            return "HTTP " + resp.status() + "\n" + resp.body();
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        }
    }
}
