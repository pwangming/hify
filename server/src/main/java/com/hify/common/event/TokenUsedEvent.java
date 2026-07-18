package com.hify.common.event;

import com.hify.common.exception.BizException;

/**
 * 一次 LLM 调用轮已结束（成或败，过去时语义）。由运行时入口在轮结束后发布；usage 模块用
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true) + @Async} 监听：
 * 流水 llm_call_log 每事件都插，聚合 usage_stat_daily 仅成功累加（配额/看板不被失败污染）。
 *
 * <p>为何放 common 而非发布方 api/event（规则3例外）：发布方 conversation 已依赖 usage::api
 * (QuotaGuard→checkQuota)，事件若放 conversation.api 则 usage 监听会反向依赖 conversation，
 * 形成环（违反 DAG 规则7）。这类「喂给 usage 的计量事件」统一放 common 共享内核。
 *
 * <p><b>失败事件必须在事务外发布</b>：AFTER_COMMIT 对回滚事务不触发，事务内发布会静默丢行
 * （看板轮计量黑洞的镜像教训）；fallbackExecution=true 使无事务发布立即执行。
 * 只允许经 {@link #success} / {@link #failure} 工厂构造，防止调用方漏填成败语义。
 */
public record TokenUsedEvent(Long userId, Long appId, Long modelId,
                             int promptTokens, int completionTokens, String source,
                             long durationMs, boolean success, String errorCode) {
    /** source 合法值（与 llm_call_log.source check 约束一字不差）。 */
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
