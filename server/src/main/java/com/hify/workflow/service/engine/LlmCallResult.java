package com.hify.workflow.service.engine;

/** 一次 LLM 同步调用的收敛结果（模块内自用，不跨模块，与 conversation.LlmReply 同型异地）。 */
public record LlmCallResult(String text, int promptTokens, int completionTokens) {
}
