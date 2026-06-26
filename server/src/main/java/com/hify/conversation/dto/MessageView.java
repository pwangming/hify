package com.hify.conversation.dto;

import java.time.OffsetDateTime;

/**
 * 消息视图（成员族响应）。id 为 Long（infra 全局序列化为 string）；token 数同样。
 * role 取 'user'/'assistant'。
 */
public record MessageView(
        Long id,
        String role,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        OffsetDateTime createTime) {
}
