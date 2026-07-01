package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听 {@link TokenUsedEvent} 落用量。{@code AFTER_COMMIT}：只有发布方事务（conversation 事务B，
 * assistant 消息落库）真提交后才落用量，与「成功轮」对齐；{@code @Async}：异步，不拖慢对话主链路
 * （code-organization §4 条3；MDC 经 infra MdcTaskDecorator 传到异步线程）。
 */
@Component
public class UsageEventListener {

    private final UsageService usageService;

    public UsageEventListener(UsageService usageService) {
        this.usageService = usageService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTokenUsed(TokenUsedEvent event) {
        usageService.recordUsage(event);
    }
}
