package com.hify.conversation.service;

import com.hify.conversation.entity.Message;

import java.util.List;

/** openTurn 的结果（模块内）：本次会话 id + 喂给模型的消息窗口（时间正序，末位为当前消息）。 */
public record TurnContext(Long conversationId, List<Message> window) {
}
