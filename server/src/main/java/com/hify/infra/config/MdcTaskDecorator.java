package com.hify.infra.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 把发起线程的日志 MDC（含 traceId）复制到异步线程——coding-standards.md 第 20 条：
 * "跨线程传递 traceId 统一走 infra 提供的 MDC 装饰 executor，禁止业务代码手工搬运"。
 *
 * <p>没有它，{@code @Async} 任务（如监听 TokenUsedEvent 落库）会在一条全新的异步线程上执行，
 * 那条线程的 MDC 是空的，日志里就没有 traceId，整条调用链在异步这一段断掉、排障时 grep 不到。
 *
 * <p>装饰逻辑：在 {@link #decorate} 被调用的"当下"（仍是发起线程）抓一份 MDC 快照，
 * 任务真正运行时先把快照灌进异步线程，跑完在 finally 里清掉，避免线程被复用时串味。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextSnapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (contextSnapshot != null) {
                MDC.setContextMap(contextSnapshot);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
