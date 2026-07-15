package com.hify.tool.dto;

/** 详情/预览里的 MCP 工具摘要（不含 inputSchema 细节，UI 够用即可；与 OperationView 同款克制）。 */
public record McpToolView(String toolName, String description) {}
