package com.hify.conversation.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 消息视图（成员族响应）。id/token 为 Long/Integer（Long -> string、Integer 保持 number）。
 * sources：引用来源快照数组，未绑库/降级/无命中为空数组（非 null）。
 * toolCalls：Agent 工具调用轨迹，非 Agent 消息为空数组（非 null）。
 */
public record MessageView(
        Long id,
        String role,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        OffsetDateTime createTime,
        List<MessageSource> sources,
        List<MessageToolCall> toolCalls) {
}
