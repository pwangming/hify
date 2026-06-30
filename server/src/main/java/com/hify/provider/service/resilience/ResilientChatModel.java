package com.hify.provider.service.resilience;

import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Spring AI ChatModel 装饰器：
 * call 四件套 Retry(CircuitBreaker(Bulkhead(TimeLimiter(真实调用))))；
 * stream 够用子集（Bulkhead+三层超时+熔断，不重试）。
 */
public class ResilientChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ResilienceBundle bundle;
    private final ExecutorService executor;

    public ResilientChatModel(ChatModel delegate, ResilienceBundle bundle, ExecutorService executor) {
        this.delegate = delegate;
        this.bundle = bundle;
        this.executor = executor;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Supplier<ChatResponse> timed = () -> callWithTimeout(prompt);
        Supplier<ChatResponse> decorated = Decorators.ofSupplier(timed)
                .withBulkhead(bundle.bulkhead())
                .withCircuitBreaker(bundle.circuitBreaker())
                .withRetry(bundle.retry())
                .decorate();
        try {
            return decorated.get();
        } catch (Throwable t) {
            throw ResilienceExceptions.toBizException(t);
        }
    }

    private ChatResponse callWithTimeout(Prompt prompt) {
        try {
            return bundle.timeLimiter().executeFutureSupplier(
                    () -> executor.submit(() -> delegate.call(prompt)));
        } catch (Throwable t) {
            // 以原异常类型重抛，供 Retry/CircuitBreaker 的谓词识别真实异常
            // （resilience4j TimeLimiter 会把 Future.get() 抛出的 ExecutionException 解包为 cause）
            throw ResilienceExceptions.sneaky(t);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
                // 首 token 超时 + 逐 token 间隔超时（卡住不吐字快速判死）
                .timeout(Mono.delay(bundle.firstTokenTimeout()),
                         cr -> Mono.delay(bundle.tokenGapTimeout()))
                // 总时长硬上限：到点即以 TimeoutException 终止（防节点级泄漏）
                .takeUntilOther(Mono.delay(bundle.streamMaxDuration())
                         .then(Mono.error(new TimeoutException("stream total exceeded"))))
                .transformDeferred(BulkheadOperator.of(bundle.bulkhead()))       // 订阅占名额、终止释放
                .transformDeferred(CircuitBreakerOperator.of(bundle.circuitBreaker())) // 成/败计入同一熔断窗口
                // 不挂 Retry —— 吐字后中断不自动重试（llm-resilience §3）
                .onErrorMap(ResilienceExceptions::toBizException);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
