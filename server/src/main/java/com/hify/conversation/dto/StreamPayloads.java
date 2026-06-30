package com.hify.conversation.dto;

/** SSE 事件载荷（经全局 Jackson：Long/数字→string，与 MessageView 一致）。 */
public final class StreamPayloads {

    private StreamPayloads() {}

    public record Delta(String delta) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Done(Long conversationId, Long messageId, Usage usage) {}

    public record Error(int code, String message, String traceId) {}
}
