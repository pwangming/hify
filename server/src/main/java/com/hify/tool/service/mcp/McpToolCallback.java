package com.hify.tool.service.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 一个 MCP 远端工具适配成 Spring AI ToolCallback。
 *
 * <p>持有的是<b>连接配置 + 这一个工具的 name</b>，不是活着的连接——与 BuiltinToolCallback /
 * OpenApiToolCallback 保持一致的无状态契约，注册表可随时造/丢，无人需要负责关连接。
 * 每次 call 建连 → initialize → callTool → close（T4a spec 决策 6）。
 *
 * <p>任何失败返回「错误：…」文本、绝不抛（不中断 Agent 循环，与内置工具同契约）。
 */
public class McpToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final String toolName;
    private final String url;
    private final String transport;
    private final Map<String, String> authHeaders;
    private final McpClientFactory factory;
    private final ObjectMapper mapper;

    public McpToolCallback(ToolDefinition definition, String toolName, String url, String transport,
                           Map<String, String> authHeaders, McpClientFactory factory, ObjectMapper mapper) {
        this.definition = definition;
        this.toolName = toolName;
        this.url = url;
        this.transport = transport;
        this.authHeaders = authHeaders;
        this.factory = factory;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> args;
        try {
            args = toolInput == null || toolInput.isBlank()
                    ? Map.of()
                    : mapper.readValue(toolInput, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return "错误：参数不是合法 JSON：" + e.getMessage();
        }
        try (McpSyncClient client = factory.create(url, transport, authHeaders)) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, args));
            String text = textOf(result.content());
            return Boolean.TRUE.equals(result.isError()) ? "错误：MCP 工具返回失败：" + text : text;
        } catch (Exception e) {
            return "错误：MCP 工具调用失败：" + e.getMessage();
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return call(toolInput);
    }

    /** 取文本内容拼接；非文本（图片/嵌入资源）给占位说明——一期不做多模态。 */
    private static String textOf(List<McpSchema.Content> content) {
        if (content == null || content.isEmpty()) {
            return "（MCP 工具无返回内容）";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (McpSchema.Content c : content) {
            if (c instanceof McpSchema.TextContent t) {
                joiner.add(t.text());
            } else {
                joiner.add("（已省略非文本内容：" + c.getClass().getSimpleName() + "）");
            }
        }
        return joiner.toString();
    }
}
