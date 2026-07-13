package com.hify.app.api.dto;

/**
 * 对话型应用的运行配置（jsonb 落库）。systemPrompt=系统提示词（可空）；
 * agentEnabled=是否启用 Agent 工具调用（默认 false；T1 由 tool 注册表全启用内置工具，per-app 选择留 T2）。
 * 跨模块 record：conversation 读 app 时消费。新增字段向后兼容（老 jsonb 缺 agentEnabled → Jackson 置 false）。
 */
public record AppConfig(String systemPrompt, boolean agentEnabled) {
}
