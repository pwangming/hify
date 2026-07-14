package com.hify.tool.service.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hify.common.exception.BizException;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条 OpenAPI 操作适配成 Spring AI ToolCallback。call(argsJson)：按 parameters 把模型参数
 * 填进 path/query/body、并入鉴权头 → 走 OutboundHttpClient（自带 SSRF/双超时）→ 返回文本。
 * 任何失败返回「错误：…」文本、绝不抛（不中断 Agent 循环，与内置工具同契约）。
 */
public class OpenApiToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final OpenApiToolSpec.Operation op;
    private final String baseUrl;
    private final Map<String, String> authHeaders;
    private final OutboundHttpClient http;
    private final ObjectMapper mapper;

    public OpenApiToolCallback(ToolDefinition definition, OpenApiToolSpec.Operation op, String baseUrl,
                               Map<String, String> authHeaders, OutboundHttpClient http, ObjectMapper mapper) {
        this.definition = definition;
        this.op = op;
        this.baseUrl = baseUrl;
        this.authHeaders = authHeaders;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        JsonNode args;
        try {
            args = mapper.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        try {
            String path = op.pathTemplate();
            List<String> query = new ArrayList<>();
            Map<String, String> headers = new LinkedHashMap<>(authHeaders);
            ObjectNode body = mapper.createObjectNode();
            boolean hasBody = false;

            for (OpenApiToolSpec.Param p : op.parameters()) {
                JsonNode v = args.get(p.name());
                boolean missing = v == null || v.isNull();
                if (missing) {
                    if (p.required()) {
                        return "错误：缺少必填参数：" + p.name();
                    }
                    continue;
                }
                switch (p.in()) {
                    case "path" -> path = path.replace("{" + p.name() + "}", enc(v.asText()));
                    case "query" -> query.add(enc(p.name()) + "=" + enc(v.asText()));
                    case "header" -> headers.put(p.name(), v.asText());
                    case "body" -> { body.set(p.name(), v); hasBody = true; }
                    default -> { /* 未知 in 忽略 */ }
                }
            }

            String url = baseUrl + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
            String bodyStr = hasBody ? mapper.writeValueAsString(body) : null;
            OutboundResponse resp = http.send(op.method(), url, headers, bodyStr);
            return "HTTP " + resp.status() + "\n" + resp.body();
        } catch (BizException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            return "错误：调用失败：" + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
