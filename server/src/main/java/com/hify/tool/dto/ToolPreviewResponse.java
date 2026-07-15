package com.hify.tool.dto;

import java.util.List;

/** 预览结果：openapi 回 baseUrl+operations；mcp 回 tools。未用到的那边为 null 或 []。 */
public record ToolPreviewResponse(String baseUrl, List<OperationView> operations, List<McpToolView> tools) {}
