package com.hify.tool.dto;

/** 工具列表项（成员族响应）。id 为 Long（infra 全局序列化为 string）。 */
public record ToolView(Long id, String name, String description, String source) {
}
