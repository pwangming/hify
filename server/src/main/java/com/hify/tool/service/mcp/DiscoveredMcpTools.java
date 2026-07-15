package com.hify.tool.service.mcp;

import java.util.List;

/** 发现中间结果（仿 openapi 的 ParsedOpenApi）：只带工具清单，落库时再补 url/transport/密文头/时间。 */
public record DiscoveredMcpTools(List<McpToolSpec.McpTool> tools) {}
