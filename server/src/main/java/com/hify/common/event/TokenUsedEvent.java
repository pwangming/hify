package com.hify.common.event;

import com.hify.common.exception.BizException;

/** 一次 LLM 调用轮已结束（成功或失败）的内部计量事件。 */
public record TokenUsedEvent(Long userId, Long appId, Long modelId,
                             int promptTokens, int completionTokens, String source,
                             long durationMs, boolean success, String errorCode) {
    public TokenUsedEvent(Long userId, Long appId, Long modelId, int promptTokens,
                          int completionTokens, String source) {
        this(userId, appId, modelId, promptTokens, completionTokens, source, 0L, true, null);
    }
    public static final String SOURCE_CONVERSATION = "conversation";
    public static final String SOURCE_WORKFLOW = "workflow";

    public static TokenUsedEvent success(Long userId, Long appId, Long modelId,
                                         int promptTokens, int completionTokens,
                                         String source, long durationMs) {
        return new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens,
                source, durationMs, true, null);
    }

    public static TokenUsedEvent failure(Long userId, Long appId, Long modelId,
                                         String source, long durationMs, Throwable cause) {
        String code = cause instanceof BizException be
                ? String.valueOf(be.errorCode().code()) : cause.getClass().getSimpleName();
        return new TokenUsedEvent(userId, appId, modelId, 0, 0, source, durationMs, false, code);
    }
}
