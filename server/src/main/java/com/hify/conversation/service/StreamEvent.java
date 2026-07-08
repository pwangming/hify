package com.hify.conversation.service;

import com.hify.conversation.dto.MessageSource;

import java.util.List;

/** 流式编排的内部事件（仅 service↔controller，不跨模块）。 */
public sealed interface StreamEvent
        permits StreamEvent.Meta, StreamEvent.Sources, StreamEvent.Delta, StreamEvent.Done {

    /** 开场元信息（修缮轮 D2 其二）：首个 delta 前发出，前端立即拿到会话 id——断网重发进同一会话。 */
    record Meta(Long conversationId) implements StreamEvent {}

    /** 引用来源（本轮）：Meta 后、首个 Delta 前发；命中为空则不发此事件。 */
    record Sources(List<MessageSource> sources) implements StreamEvent {}

    /** 一段增量正文。 */
    record Delta(String text) implements StreamEvent {}

    /** 流正常结束、assistant 已落库的终态（含新会话 id 供前端写回 URL）。 */
    record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)
            implements StreamEvent {}
}
