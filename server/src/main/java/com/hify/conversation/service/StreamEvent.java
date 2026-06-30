package com.hify.conversation.service;

/** 流式编排的内部事件（仅 service↔controller，不跨模块）。 */
public sealed interface StreamEvent permits StreamEvent.Delta, StreamEvent.Done {

    /** 一段增量正文。 */
    record Delta(String text) implements StreamEvent {}

    /** 流正常结束、assistant 已落库的终态（含新会话 id 供前端写回 URL）。 */
    record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)
            implements StreamEvent {}
}
