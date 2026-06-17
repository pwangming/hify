package com.hify.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AsyncConfig} 单元测试：直接拿它产出的 executor 跑一个任务，验证两件硬指标——
 * 跑在虚拟线程上、且发起线程的 traceId 被传了过去。不启动 Spring、不连数据库。
 */
class AsyncConfigTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 异步任务_跑在虚拟线程且带上traceId() throws Exception {
        Executor executor = new AsyncConfig(8).getAsyncExecutor();
        MDC.put("traceId", "t-xyz");

        CompletableFuture<Boolean> isVirtual = new CompletableFuture<>();
        CompletableFuture<String> traceId = new CompletableFuture<>();
        executor.execute(() -> {
            isVirtual.complete(Thread.currentThread().isVirtual());
            traceId.complete(MDC.get("traceId"));
        });

        assertTrue(isVirtual.get(5, TimeUnit.SECONDS), "异步任务应在虚拟线程上执行");
        assertEquals("t-xyz", traceId.get(5, TimeUnit.SECONDS), "应把发起线程的 traceId 传到异步线程");
    }
}
