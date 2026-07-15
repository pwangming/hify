package com.hify.tool.service.mcp;

import com.hify.tool.service.ToolSpec;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * tool.spec(jsonb) 的 mcp 形状：连接配置 + 工具清单快照（T4a spec 决策 4/5）。
 * 凭据只存密文 valueEnc，任何响应 DTO 不回明文。
 */
public record McpToolSpec(
        String url,
        String transport,
        List<AuthHeader> authHeaders,
        List<McpTool> tools,
        OffsetDateTime discoveredAt) implements ToolSpec {

    public record AuthHeader(String name, String valueEnc) {}

    /**
     * 快照里的一个 MCP 工具。
     * @param toolName    MCP 远端的工具名（展开时会加注册名前缀防跨注册撞名）
     * @param description <b>给模型看的</b>说明（来自远端），不是 tool 行那个给人看的注册描述
     * @param inputSchema JSON Schema 字符串（发现时由 McpSchema.JsonSchema 序列化而来），直接进 ToolDefinition
     */
    public record McpTool(String toolName, String description, String inputSchema) {}
}
