package com.hify.conversation.dto;

/**
 * Agent 单次工具调用轨迹（落 message.tool_calls jsonb 数组的元素）。
 * name=工具名；args=模型给出的 JSON 参数字符串；result=工具返回给模型的结果文本（成功或错误文本）。
 * 非 Agent 消息该列恒 []（DB 默认保证）。
 */
public record MessageToolCall(String name, String args, String result) {
}
