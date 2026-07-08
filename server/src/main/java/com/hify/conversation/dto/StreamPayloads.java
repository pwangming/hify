package com.hify.conversation.dto;

/** SSE 事件载荷（全局 Jackson：Long→string；Integer/int 保持数字，与 MessageView 一致）。 */
public final class StreamPayloads {

    private StreamPayloads() {}

    public record Meta(Long conversationId) {}

    public record Sources(java.util.List<MessageSource> sources) {}

    public record Delta(String delta) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Done(Long conversationId, Long messageId, Usage usage) {}

    public record Error(int code, String message, String traceId) {}
}
