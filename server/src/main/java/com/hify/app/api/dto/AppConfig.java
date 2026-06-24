package com.hify.app.api.dto;

/**
 * 对话型应用的运行配置（jsonb 落库）。本轮仅 systemPrompt（系统提示词，可空）。
 * 跨模块 record：③ conversation 读 app 时消费它取人设。新增字段向后兼容，不算破坏性变更。
 */
public record AppConfig(String systemPrompt) {
}
