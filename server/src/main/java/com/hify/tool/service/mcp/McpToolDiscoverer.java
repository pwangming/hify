package com.hify.tool.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.tool.config.ToolSpecTypeHandler;
import com.hify.tool.constant.ToolError;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 连远端 listTools 拿工具清单（注册 / 刷新 / 预览三处共用）。慢路径，允许连网。
 * 失败一律 13002；但 SSRF/非法 url 的 10001 原样冒泡，不吞不包（T4a spec §7.1）。
 */
@Service
public class McpToolDiscoverer {

    /** 序列化 inputSchema 用：NON_NULL，避免把 "defs":null 之类塞进发给模型的 schema。 */
    private static final ObjectMapper SCHEMA_MAPPER = ToolSpecTypeHandler.specMapper();

    private final McpClientFactory factory;

    public McpToolDiscoverer(McpClientFactory factory) {
        this.factory = factory;
    }

    /** headers 须是解密后的明文。 */
    public DiscoveredMcpTools discover(String url, String transport, Map<String, String> headers) {
        // create() 里的 url 校验/SSRF 抛的是 10001，放在 try 外，避免被下面的 catch 吞掉重包成 13002
        McpSyncClient client = factory.create(url, transport, headers);
        try (client) {
            client.initialize();
            McpSchema.ListToolsResult result = client.listTools();
            return new DiscoveredMcpTools(toSnapshot(result.tools()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ToolError.MCP_CONNECT_FAILED,
                    "MCP 服务器连接或工具发现失败：" + e.getMessage());
        }
    }

    private static List<McpToolSpec.McpTool> toSnapshot(List<McpSchema.Tool> tools) {
        List<McpToolSpec.McpTool> out = new ArrayList<>(tools.size());
        for (McpSchema.Tool t : tools) {
            out.add(new McpToolSpec.McpTool(t.name(), t.description(), schemaJson(t)));
        }
        return out;
    }

    /** SDK 给的 inputSchema 是类型化的 JsonSchema record，ToolDefinition 要字符串——此处序列化掉。 */
    private static String schemaJson(McpSchema.Tool t) {
        if (t.inputSchema() == null) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        try {
            return SCHEMA_MAPPER.writeValueAsString(t.inputSchema());
        } catch (Exception e) {
            throw new BizException(ToolError.MCP_CONNECT_FAILED,
                    "MCP 工具「" + t.name() + "」的 inputSchema 无法序列化：" + e.getMessage());
        }
    }
}
