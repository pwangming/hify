package com.hify.common.event;

/**
 * 一次模型调用的 Token 用量已产生（过去时语义）。由运行时入口（conversation 收消息 / workflow 触发）
 * 在**一轮成功完成后**发布；usage 模块用 {@code @TransactionalEventListener(AFTER_COMMIT) + @Async}
 * 监听，落 {@code llm_call_log} 流水并 UPSERT 累加 {@code daily_usage}（code-organization.md §4 条3）。
 *
 * <p><b>为何放 common 而非发布方 api/event（规则3例外）：</b>发布方 conversation 已依赖 {@code usage::api}
 * (QuotaGuard→checkQuota)，事件若放 conversation.api 则 usage 监听会反向依赖 conversation，形成
 * conversation⇄usage 环（违反 DAG 规则7）。故这类「喂给 usage 的计量事件」统一放 common 共享内核，
 * 两侧都不新增模块依赖、无环。
 *
 * <p>失败/取消的轮不发本事件（token 可能为 0 或部分），即不计入配额。单次调用 token 不会溢出 int。
 */
public record TokenUsedEvent(
        Long userId,
        Long appId,
        Long modelId,
        int promptTokens,
        int completionTokens
) {
}
