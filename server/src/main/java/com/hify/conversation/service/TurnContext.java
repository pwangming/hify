package com.hify.conversation.service;

import com.hify.conversation.entity.Message;

import java.util.List;

/**
 * openTurn 的结果（模块内）：
 * <ul>
 *   <li>{@code conversationId} — 本次会话 id（新建或复用）。</li>
 *   <li>{@code window} — 喂给模型的消息窗口（时间正序，末位为本轮 user 消息）。</li>
 *   <li>{@code userMessageId} — 本轮落库的 user 消息 id，供失败清理路径软删。</li>
 *   <li>{@code newConversation} — 本轮是否新建了会话（入参 conversationId == null）；
 *       失败清理时：true → 同时软删会话；false → 只删本轮消息，不动历史。</li>
 * </ul>
 */
public record TurnContext(Long conversationId, List<Message> window, Long userMessageId, boolean newConversation) {
}
