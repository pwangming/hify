package com.hify.tool.service.mcp;

import com.hify.common.exception.BizException;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import com.hify.tool.constant.ToolError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolDiscovererTest {

    private McpToolDiscoverer discoverer() {
        return new McpToolDiscoverer(new McpClientFactory(TestSsrf.permissive(), new McpProperties()));
    }

    @Test
    void discover_returnsToolsWithSerializedJsonSchema() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.toolNames(List.of("search_docs", "fetch_page"));

            DiscoveredMcpTools found = discoverer().discover(
                    server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of());

            assertThat(found.tools()).extracting(McpToolSpec.McpTool::toolName)
                    .containsExactly("search_docs", "fetch_page");
            assertThat(found.tools().get(0).description()).isEqualTo("desc of search_docs");
            // inputSchema 必须是 JSON Schema 字符串（SDK 给的是 JsonSchema 对象，须序列化）
            assertThat(found.tools().get(0).inputSchema())
                    .contains("\"type\":\"object\"")
                    .contains("\"properties\"")
                    .doesNotContain("\"defs\":null");     // NON_NULL：不许把空字段塞进给模型的 schema
        }
    }

    /** 鉴权头必须真的发到远端——这是 McpClientFactory 的 customizeRequest 唯一能端到端验证的地方。 */
    @Test
    void discover_injectsAuthHeaders() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            discoverer().discover(server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                    Map.of("Authorization", "Bearer secret-token"));

            assertThat(server.seenAuthHeaders()).isNotEmpty();
            assertThat(server.seenAuthHeaders()).allMatch(h -> h.equals("Bearer secret-token"));
        }
    }

    @Test
    void discover_remoteError_throws13002() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.forceStatus(500);

            assertThatThrownBy(() -> discoverer().discover(
                    server.url(), McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                    .isInstanceOf(BizException.class)
                    .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                            .isEqualTo(ToolError.MCP_CONNECT_FAILED.code()));
        }
    }

    /** SSRF 拒绝必须原样冒泡成 10001，不得被 catch 吞掉重包成 13002（否则 admin 看不出真实原因）。 */
    @Test
    void discover_internalAddress_propagates10001NotWrapped() {
        McpToolDiscoverer d = new McpToolDiscoverer(
                new McpClientFactory(new SsrfValidator(), new McpProperties()));

        assertThatThrownBy(() -> d.discover("http://127.0.0.1:9/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(com.hify.common.exception.CommonError.PARAM_INVALID.code()));
    }
}
