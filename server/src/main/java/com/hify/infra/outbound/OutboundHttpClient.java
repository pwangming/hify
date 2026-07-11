package com.hify.infra.outbound;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出站 HTTP 统一收口（deployment.md §5：tool/workflow 禁止自建客户端）。
 * Redirect.NEVER（3xx 原样返回，封死重定向绕过 SSRF，spec 拍板）；连接/读取双超时外化；
 * 响应体按 max-response-bytes 硬截断（UTF-8 多字节字符可能截出替换符，可接受）。
 * 发请求前必过 SsrfValidator。虚拟线程环境下同步阻塞调用即可。
 */
@Component
public class OutboundHttpClient {

    private final SsrfValidator ssrfValidator;
    private final OutboundProperties props;
    private final HttpClient client;

    public OutboundHttpClient(SsrfValidator ssrfValidator, OutboundProperties props) {
        this.ssrfValidator = ssrfValidator;
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    /** method 由调用方保证在白名单；任何 HTTP 响应都正常返回，只有网络/校验失败才抛。 */
    public OutboundResponse send(String method, String url, Map<String, String> headers, String body) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 非法：" + url);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 仅支持 http/https：" + url);
        }
        if (uri.getHost() == null) {
            throw new BizException(CommonError.PARAM_INVALID, "URL 缺少主机名：" + url);
        }
        ssrfValidator.validate(uri.getHost());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.getReadTimeoutMs()));
        try {
            if (headers != null) {
                headers.forEach(builder::header);
            }
        } catch (IllegalArgumentException e) {
            // JDK 拒绝受限头（Host/Connection/Content-Length 等）
            throw new BizException(CommonError.PARAM_INVALID, "请求头不允许设置：" + e.getMessage());
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        HttpRequest request = builder.method(method, publisher).build();

        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "HTTP 请求被中断");
        }
        return new OutboundResponse(response.statusCode(), truncate(response.body()), flatten(response));
    }

    private String truncate(byte[] raw) {
        int limit = Math.min(raw.length, props.getMaxResponseBytes());
        return new String(raw, 0, limit, StandardCharsets.UTF_8);
    }

    /** 响应头拍平：键小写（HttpHeaders 本就规整为小写）、多值逗号连接。 */
    private static Map<String, String> flatten(HttpResponse<byte[]> response) {
        Map<String, String> flat = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> flat.put(k.toLowerCase(), String.join(",", v)));
        return flat;
    }
}
