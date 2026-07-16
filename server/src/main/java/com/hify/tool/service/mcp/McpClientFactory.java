package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * 造 McpSyncClient：SSRF 校验（白名单 host 豁免禁内网，见 McpProperties.allowedPrivateHosts）→ 选传输 → 注入鉴权头 → 禁重定向 → 双超时。
 * MCP 出站的<b>全部安全闸门收口于此</b>，禁止别处自建 MCP 客户端（deployment.md §5）。
 * 只支持远程 HTTP，不支持 stdio（T4a spec 决策 1）。调用方负责 close（try-with-resources）。
 */
@Component
public class McpClientFactory {

    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    public static final String TRANSPORT_SSE = "sse";

    private final SsrfValidator ssrfValidator;
    private final McpProperties props;

    public McpClientFactory(SsrfValidator ssrfValidator, McpProperties props) {
        this.ssrfValidator = ssrfValidator;
        this.props = props;
    }

    /** headers 须是<b>解密后的明文</b>。本方法只造对象、不连网（连网发生在 initialize()）。 */
    public McpSyncClient create(String url, String transport, Map<String, String> headers) {
        URI uri = validate(url);
        Map<String, String> h = headers == null ? Map.of() : headers;
        McpClientTransport t = TRANSPORT_SSE.equals(transport) ? sse(uri, h) : streamable(uri, h);
        return McpClient.sync(t)
                .requestTimeout(Duration.ofMillis(props.getRequestTimeoutMs()))
                .initializationTimeout(Duration.ofMillis(props.getInitializationTimeoutMs()))
                .clientInfo(new McpSchema.Implementation("hify", "1.0.0"))
                .build();
    }

    private URI validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址非法：" + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址仅支持 http/https：" + url);
        }
        if (uri.getHost() == null) {
            throw new BizException(CommonError.PARAM_INVALID, "MCP 地址缺少主机名：" + url);
        }
        if (!isAllowedPrivateHost(uri.getHost())) {
            ssrfValidator.validate(uri.getHost());   // 内网/回环/元数据 → BizException(10001)，原样抛出
        }
        return uri;
    }

    private McpClientTransport streamable(URI uri, Map<String, String> headers) {
        return HttpClientStreamableHttpTransport.builder(origin(uri))
                .endpoint(endpoint(uri))
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .customizeClient(b -> b.followRedirects(HttpClient.Redirect.NEVER))
                .customizeRequest(b -> headers.forEach(b::header))
                .build();
    }

    private McpClientTransport sse(URI uri, Map<String, String> headers) {
        return HttpClientSseClientTransport.builder(origin(uri))
                .sseEndpoint(endpoint(uri))
                .customizeClient(b -> b.followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs())))
                .customizeRequest(b -> headers.forEach(b::header))
                .build();
    }

    /**
     * 拆 origin：builder(baseUri) 只吃到 scheme://host:port。
     * <b>必须拆</b>——Builder 的 endpoint 默认 "/mcp"、sseEndpoint 默认 "/sse"（已反编译确认），
     * 整条 URL 当 baseUri 传会拼成 https://host/mcp/mcp。
     */
    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** 拆 path(+query) 作 endpoint；无 path 时给 "/"。 */
    private static String endpoint(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    /** T4b 白名单：只豁免禁内网这一条；followRedirects(NEVER) 与三重超时不豁免。 */
    private boolean isAllowedPrivateHost(String host) {
        return props.getAllowedPrivateHosts().stream().anyMatch(h -> h.equalsIgnoreCase(host));
    }
}
