package com.hify.workflow.service.engine;

import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.OutboundResponse;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 节点：渲染 url/headers/body → infra 出站客户端（内含 SSRF 校验/超时/截断/不跟随重定向）
 * → 输出 {status, body, headers}。拿到任何 HTTP 响应都算节点成功（spec 拍板：非 2xx 交给
 * condition 节点分流）；仅 SSRF 拦截/URL 非法/网络失败/超时才是节点失败。
 * 敏感请求头落 node_run.inputs 前脱敏（token 不进数据库）。
 */
@Component
public class HttpNodeExecutor implements NodeExecutor {

    /** 落库前脱敏的请求头（小写比较）。 */
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "cookie", "proxy-authorization", "x-api-key");

    private final OutboundHttpClient http;

    public HttpNodeExecutor(OutboundHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return NodeType.HTTP.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String method = String.valueOf(node.data().get("method")).toUpperCase();   // validator 已保证白名单
        String url = ctx.render(String.valueOf(node.data().get("url")));
        Map<String, String> headers = renderHeaders(node.data().get("headers"), ctx);
        Object rawBody = node.data().get("body");
        boolean bodyAllowed = "POST".equals(method) || "PUT".equals(method);
        String body = bodyAllowed && rawBody != null ? ctx.render(String.valueOf(rawBody)) : null;

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("method", method);
        inputs.put("url", url);
        inputs.put("headers", mask(headers));
        inputs.put("body", body);

        try {
            OutboundResponse resp = http.send(method, url, headers, body);
            return new NodeResult(inputs,
                    Map.of("status", resp.status(), "body", resp.body(), "headers", resp.headers()));
        } catch (Exception e) {
            // 渲染已成功、请求才失败：脱敏后的实际请求随异常落 node_run.inputs 供排障
            throw new NodeExecutionException(inputs, e);
        }
    }

    private Map<String, String> renderHeaders(Object raw, RunContext ctx) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {   // validator 已保证类型
            map.forEach((k, v) -> rendered.put(String.valueOf(k), ctx.render(String.valueOf(v))));
        }
        return rendered;
    }

    /** 敏感头掩码（排障能看到发了哪些头，token 不进库）。 */
    private static Map<String, String> mask(Map<String, String> headers) {
        Map<String, String> masked = new LinkedHashMap<>();
        headers.forEach((k, v) ->
                masked.put(k, SENSITIVE_HEADERS.contains(k.toLowerCase()) ? "***" : v));
        return masked;
    }
}
