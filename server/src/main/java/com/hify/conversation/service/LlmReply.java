package com.hify.conversation.service;

/** 单次非流式模型调用结果（模块内）：正文 + token 用量。 */
public record LlmReply(String content, int promptTokens, int completionTokens) {
}
