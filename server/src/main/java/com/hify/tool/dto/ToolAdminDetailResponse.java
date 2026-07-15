package com.hify.tool.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * admin 详情（供 T3b/T4b 编辑表单）。authHeaderNames 只回头名，绝不回明文值。
 * openapi 行填 baseUrl/operations/rawSpec；mcp 行填 url/transport/tools/discoveredAt；另一边为 null 或 []。
 */
public record ToolAdminDetailResponse(
        Long id, String name, String description, String source, boolean enabled,
        String baseUrl, List<OperationView> operations, List<String> authHeaderNames, String rawSpec,
        String url, String transport, List<McpToolView> tools, OffsetDateTime discoveredAt) {}
