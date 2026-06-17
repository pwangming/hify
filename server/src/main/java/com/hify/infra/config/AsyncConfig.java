package com.hify.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

/**
 * 异步执行基础设施（llm-resilience.md 第 1 节 / code-organization.md 第 4.3 条）。
 *
 * <p>{@code @EnableAsync} 打开 {@code @Async} 支持；本类实现 {@link AsyncConfigurer}，把所有
 * {@code @Async} 方法（典型用途：{@code @TransactionalEventListener(AFTER_COMMIT)} 的事件监听落库）
 * 统一调度到这里定义的 executor 上。
 *
 * <p>executor 三个关键点：
 * <ul>
 *   <li><b>虚拟线程</b>（{@code setVirtualThreads(true)}）：异步任务多为"写库"等阻塞 IO，
 *       虚拟线程零成本扩展，不需要也不应该用平台线程池（coding-standards.md 第 16 条）；</li>
 *   <li><b>有界并发</b>（{@code setConcurrencyLimit}）：防病态堆积（如事件风暴）。真正的瓶颈通常是
 *       数据库连接池（20），这里是再加一道闸；上限外化为配置；</li>
 *   <li><b>MDC 装饰</b>（{@link MdcTaskDecorator}）：把 traceId 带进异步线程，日志链路不断。</li>
 * </ul>
 *
 * <p>注意：文档里提到的"后台重任务专用有界 executor"（文档批量向量化、workflow 异步执行）等
 * knowledge / workflow 模块落地时再按需增设，本块只铺通用的事件/异步地基，不提前抽象。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private final int concurrencyLimit;

    public AsyncConfig(@Value("${hify.async.concurrency-limit:64}") int concurrencyLimit) {
        this.concurrencyLimit = concurrencyLimit;
    }

    @Override
    public Executor getAsyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("hify-async-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(concurrencyLimit);
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
