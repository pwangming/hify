package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.tool.config.McpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCallbackTest {

    private McpToolCallback callback(FakeMcpServer server) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name("wiki__search_docs").description("查文档")
                .inputSchema("{\"type\":\"object\"}").build();
        return new McpToolCallback(def, "search_docs", server.url(),
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, Map.of(),
                new McpClientFactory(TestSsrf.permissive(), new McpProperties()), new ObjectMapper());
    }

    @Test
    void call_returnsRemoteText() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("检索到 3 条结果", false);

            String out = callback(server).call("{\"q\":\"hify\"}");

            assertThat(out).isEqualTo("检索到 3 条结果");
        }
    }

    /** 远端标记 isError 时要点明，让模型知道调用失败可换思路，而不是把错误当正常结果。 */
    @Test
    void call_remoteIsError_prefixesMarker() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("rate limited", true);

            String out = callback(server).call("{}");

            assertThat(out).startsWith("错误：").contains("rate limited");
        }
    }

    /** 契约：任何失败返回错误文本、绝不抛——不能中断 Agent 循环。 */
    @Test
    void call_remoteFailure_returnsTextNotThrow() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.forceStatus(500);

            String out = callback(server).call("{}");

            assertThat(out).startsWith("错误：");
        }
    }

    @Test
    void call_invalidArgsJson_returnsTextNotThrow() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            String out = callback(server).call("not-json");

            assertThat(out).startsWith("错误：").contains("JSON");
        }
    }

    @Test
    void call_blankArgs_treatedAsEmptyObject() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer()) {
            server.callToolReturns("ok", false);

            assertThat(callback(server).call("")).isEqualTo("ok");
        }
    }
}
