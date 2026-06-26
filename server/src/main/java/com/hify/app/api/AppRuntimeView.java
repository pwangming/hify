package com.hify.app.api;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * 位于 api 顶层包（@NamedInterface("api")），不引用 app.api.dto——避免跨模块消费未暴露的子包类型。
 * modelId 必非空；systemPrompt 为应用人设（可空）。config 增长时本视图再加字段。
 */
public record AppRuntimeView(Long appId, Long modelId, String systemPrompt) {
}
