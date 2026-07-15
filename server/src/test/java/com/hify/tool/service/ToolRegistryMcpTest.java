package com.hify.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.outbound.OutboundHttpClient;
import com.hify.infra.outbound.SsrfValidator;
import com.hify.tool.config.McpProperties;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.builtin.BuiltinTool;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryMcpTest {

    private ToolRegistry registry(ToolMapper mapper, SecretCipher cipher) {
        return new ToolRegistry(mapper, List.<BuiltinTool>of(), cipher,
                mock(OutboundHttpClient.class), new ObjectMapper(),
                new McpClientFactory(new SsrfValidator(), new McpProperties()));
    }

    private Tool mcpRow() {
        Tool row = new Tool();
        row.setId(7L);
        row.setName("wiki");
        row.setDescription("给人看的注册说明");
        row.setSource("mcp");
        row.setEnabled(true);
        row.setSpec(new McpToolSpec(
                "https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                List.of(new McpToolSpec.AuthHeader("Authorization", "ENC")),
                List.of(new McpToolSpec.McpTool("search_docs", "给模型看的说明", "{\"type\":\"object\"}"),
                        new McpToolSpec.McpTool("fetch_page", "抓页面", "{\"type\":\"object\"}")),
                OffsetDateTime.now()));
        return row;
    }

    @Test
    void mcpRow_expandsToOneCallbackPerTool_withRegistrationNamePrefix() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("Bearer secret");
        when(mapper.selectList(any())).thenReturn(List.of(mcpRow()));

        List<ToolCallback> callbacks = registry(mapper, cipher).getToolCallbacks(List.of(7L));

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).extracting(c -> c.getToolDefinition().name())
                .containsExactlyInAnyOrder("wiki__search_docs", "wiki__fetch_page");
    }

    /** 给模型看的 description 必须取远端那个，不是 tool 行的注册说明。 */
    @Test
    void toolDefinition_usesRemoteDescription_notRowDescription() {
        ToolMapper mapper = mock(ToolMapper.class);
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.decrypt("ENC")).thenReturn("Bearer secret");
        when(mapper.selectList(any())).thenReturn(List.of(mcpRow()));

        List<ToolCallback> callbacks = registry(mapper, cipher).getToolCallbacks(List.of(7L));

        assertThat(callbacks).extracting(c -> c.getToolDefinition().description())
                .contains("给模型看的说明")
                .doesNotContain("给人看的注册说明");
    }

    @Test
    void mcpRow_withNullSpecTools_isSkipped() {
        ToolMapper mapper = mock(ToolMapper.class);
        Tool row = mcpRow();
        row.setSpec(new McpToolSpec("https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP, List.of(), null, OffsetDateTime.now()));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertThat(registry(mapper, mock(SecretCipher.class)).getToolCallbacks(List.of(7L))).isEmpty();
    }
}
