package com.hify.conversation.dto;

import java.time.OffsetDateTime;

/** 会话列表项（成员族响应）：id 为 Long（infra 序列化为 string）；title 取首条消息截断；updateTime 为最近活跃时间。 */
public record ConversationView(Long id, String title, OffsetDateTime updateTime) {
}
