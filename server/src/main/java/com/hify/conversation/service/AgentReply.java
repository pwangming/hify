package com.hify.conversation.service;

import com.hify.conversation.dto.MessageToolCall;

import java.util.List;

/** Agent 循环产出：终答文本 + 累计 token（各轮之和）+ 工具调用轨迹。 */
public record AgentReply(String content, int promptTokens, int completionTokens,
                         List<MessageToolCall> toolCalls) {
}
