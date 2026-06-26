package com.hify.app.api.dto;

/**
 * 应用运行时视图（跨模块）：conversation 取它发起对话。
 * modelId 必非空（findRunnableChatApp 已保证可运行才返回）；config 含 systemPrompt。
 */
public record AppRuntimeView(Long appId, Long modelId, AppConfig config) {
}
