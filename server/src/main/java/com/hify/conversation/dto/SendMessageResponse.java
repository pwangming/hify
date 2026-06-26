package com.hify.conversation.dto;

/** 发消息响应：本次会话 id（前端续聊用）+ assistant 消息视图。 */
public record SendMessageResponse(Long conversationId, MessageView message) {
}
