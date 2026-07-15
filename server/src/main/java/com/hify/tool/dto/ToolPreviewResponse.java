package com.hify.tool.dto;

import java.util.List;

/** 预览结果：baseUrl + 解析出的操作摘要（不含鉴权/inputSchema 细节）。 */
public record ToolPreviewResponse(String baseUrl, List<OperationView> operations) {}
