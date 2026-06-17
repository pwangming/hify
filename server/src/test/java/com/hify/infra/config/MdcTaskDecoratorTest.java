package com.hify.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MdcTaskDecorator} 单元测试：跨线程传递 MDC、执行后清理。
 */
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 把发起线程的traceId带进另一个线程() throws Exception {
        MDC.put("traceId", "t-abc");
        AtomicReference<String> seenInWorker = new AtomicReference<>();

        // decorate 在"当下"（发起线程，MDC 有 t-abc）抓快照
        Runnable decorated = decorator.decorate(() -> seenInWorker.set(MDC.get("traceId")));

        // 真正在另一条线程执行，验证快照被灌了进去
        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertEquals("t-abc", seenInWorker.get(), "异步线程里应能看到发起线程的 traceId");
    }

    @Test
    void 任务执行完毕后清理MDC_避免线程复用串味() {
        MDC.put("traceId", "t-1");
        Runnable decorated = decorator.decorate(() -> {
            // 运行期间应能看到 traceId
            assertEquals("t-1", MDC.get("traceId"));
        });

        decorated.run(); // 同线程执行，便于断言 finally 的清理

        assertNull(MDC.get("traceId"), "任务结束后本线程 MDC 必须被清空");
    }
}
