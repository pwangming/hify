package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientFactoryTest {

    private final McpClientFactory factory =
            new McpClientFactory(new SsrfValidator(), new McpProperties());

    @Test
    void create_rejectsNonHttpScheme() {
        assertThatThrownBy(() -> factory.create("ftp://example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅支持 http/https");
    }

    @Test
    void create_rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> factory.create("http:///mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少主机名");
    }

    /** SSRF：内网地址必须被拒，且错误码是 10001（SsrfValidator 原样抛出，不被包装成 13002）。 */
    @Test
    void create_rejectsInternalAddress_with10001() {
        assertThatThrownBy(() -> factory.create("http://127.0.0.1:8080/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(CommonError.PARAM_INVALID.code()));
    }

    @Test
    void create_buildsClientForPublicUrl_bothTransports() {
        // 用 TEST-NET-1（192.0.2.0/24，RFC5737 文档保留段）——是公网地址不会被 SSRF 拒，
        // 但不可路由，绝不会真发出请求。create() 只造对象不连网。
        try (McpSyncClient a = factory.create("https://192.0.2.1/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of("Authorization", "Bearer t"));
             McpSyncClient b = factory.create("https://192.0.2.1/sse",
                     McpClientFactory.TRANSPORT_SSE, Map.of())) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }
}
