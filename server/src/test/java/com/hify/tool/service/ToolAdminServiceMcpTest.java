package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.crypto.SecretCipher;
import com.hify.infra.security.CurrentUser;
import com.hify.tool.dto.AuthHeaderInput;
import com.hify.tool.dto.CreateToolRequest;
import com.hify.tool.dto.ToolAdminDetailResponse;
import com.hify.tool.dto.ToolAdminResponse;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import com.hify.tool.service.mcp.DiscoveredMcpTools;
import com.hify.tool.service.mcp.McpClientFactory;
import com.hify.tool.service.mcp.McpToolDiscoverer;
import com.hify.tool.service.mcp.McpToolSpec;
import com.hify.tool.service.openapi.OpenApiSpecParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolAdminServiceMcpTest {

    private ToolMapper mapper;
    private SecretCipher cipher;
    private McpToolDiscoverer discoverer;
    private ToolAdminService service;
    private final CurrentUser admin = new CurrentUser(1L, "admin", "admin");

    @BeforeEach
    void setup() {
        mapper = mock(ToolMapper.class);
        cipher = mock(SecretCipher.class);
        discoverer = mock(McpToolDiscoverer.class);
        service = new ToolAdminService(mapper, mock(OpenApiSpecParser.class), cipher, discoverer);
    }

    private DiscoveredMcpTools discovered() {
        return new DiscoveredMcpTools(List.of(
                new McpToolSpec.McpTool("search_docs", "查文档", "{\"type\":\"object\"}")));
    }

    private Tool mcpRow() {
        Tool row = new Tool();
        row.setId(7L);
        row.setName("wiki");
        row.setDescription("维基");
        row.setSource("mcp");
        row.setEnabled(true);
        row.setOwnerId(1L);
        row.setSpec(new McpToolSpec("https://mcp.example.com/mcp",
                McpClientFactory.TRANSPORT_STREAMABLE_HTTP,
                List.of(new McpToolSpec.AuthHeader("Authorization", "OLDENC")),
                List.of(new McpToolSpec.McpTool("search_docs", "查文档", "{}")),
                OffsetDateTime.now().minusDays(1)));
        return row;
    }

    @Test
    void create_mcp_discoversEncryptsAndInserts() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(discoverer.discover(eq("https://mcp.example.com/mcp"), eq("streamable_http"), any()))
                .thenReturn(discovered());
        when(cipher.encrypt("Bearer t")).thenReturn("ENC");

        CreateToolRequest req = new CreateToolRequest("wiki", "维基", "mcp", null,
                "https://mcp.example.com/mcp", "streamable_http",
                List.of(new AuthHeaderInput("Authorization", "Bearer t")));
        ToolAdminResponse resp = service.create(req, admin);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        verify(mapper).insert(saved.capture());
        Tool row = saved.getValue();
        assertThat(row.getSource()).isEqualTo("mcp");
        assertThat(row.getOwnerId()).isEqualTo(1L);
        McpToolSpec spec = (McpToolSpec) row.getSpec();
        assertThat(spec.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(spec.transport()).isEqualTo("streamable_http");
        assertThat(spec.authHeaders().get(0).valueEnc()).isEqualTo("ENC");
        assertThat(spec.tools()).extracting(McpToolSpec.McpTool::toolName).containsExactly("search_docs");
        assertThat(spec.discoveredAt()).isNotNull();
        assertThat(resp.source()).isEqualTo("mcp");
        assertThat(resp.operationCount()).isEqualTo(1);   // mcp 行 operationCount = 工具数
    }

    @Test
    void create_mcp_defaultsTransportToStreamableHttp() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(discoverer.discover(any(), eq("streamable_http"), any())).thenReturn(discovered());

        service.create(new CreateToolRequest("wiki", "维基", "mcp", null,
                "https://mcp.example.com/mcp", null, List.of()), admin);

        verify(discoverer).discover(eq("https://mcp.example.com/mcp"), eq("streamable_http"), any());
    }

    /** 刷新：重新发现覆盖快照，鉴权头密文保留（admin 没重填密码，不能把凭据弄丢）。 */
    @Test
    void refresh_reDiscoversAndKeepsEncryptedHeaders() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());
        when(cipher.decrypt("OLDENC")).thenReturn("Bearer old");
        when(discoverer.discover(any(), any(), any())).thenReturn(new DiscoveredMcpTools(List.of(
                new McpToolSpec.McpTool("search_docs", "查文档", "{}"),
                new McpToolSpec.McpTool("new_tool", "新工具", "{}"))));

        service.refresh(7L);

        ArgumentCaptor<Tool> saved = ArgumentCaptor.forClass(Tool.class);
        verify(mapper).updateById(saved.capture());
        McpToolSpec spec = (McpToolSpec) saved.getValue().getSpec();
        assertThat(spec.tools()).extracting(McpToolSpec.McpTool::toolName)
                .containsExactly("search_docs", "new_tool");
        assertThat(spec.authHeaders().get(0).valueEnc()).isEqualTo("OLDENC");
    }

    @Test
    void refresh_onOpenApiRow_rejected() {
        Tool row = new Tool();
        row.setId(9L);
        row.setSource("openapi");
        when(mapper.selectById(9L)).thenReturn(row);

        assertThatThrownBy(() -> service.refresh(9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("MCP");
    }

    /** T4a 起 delete 对 mcp 放开——守卫从 assertOpenApi 改成 assertNotBuiltin。 */
    @Test
    void delete_mcpRow_allowed() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());

        service.delete(7L);

        verify(mapper).deleteById(7L);
    }

    @Test
    void delete_builtinRow_stillRejected() {
        Tool row = new Tool();
        row.setId(1L);
        row.setSource("builtin");
        when(mapper.selectById(1L)).thenReturn(row);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode().code())
                        .isEqualTo(CommonError.PARAM_INVALID.code()))
                .hasMessageContaining("内置工具");
    }

    @Test
    void get_mcpRow_returnsUrlTransportToolsAndNeverPlainSecret() {
        when(mapper.selectById(7L)).thenReturn(mcpRow());

        ToolAdminDetailResponse detail = service.get(7L);

        assertThat(detail.source()).isEqualTo("mcp");
        assertThat(detail.url()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(detail.transport()).isEqualTo("streamable_http");
        assertThat(detail.tools()).extracting(com.hify.tool.dto.McpToolView::toolName)
                .containsExactly("search_docs");
        assertThat(detail.authHeaderNames()).containsExactly("Authorization");
        assertThat(detail.discoveredAt()).isNotNull();
        assertThat(detail.operations()).isEmpty();
        assertThat(detail.baseUrl()).isNull();
    }
}
