package com.hify.provider.service.resilience;

import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Spring AI ChatModel 装饰器：
 * call 四件套 Retry(CircuitBreaker(Bulkhead(TimeLimiter(真实调用))))；
 * stream 五件套（Bulkhead+三层超时+熔断+首token前重试+失败日志）。
 */
public class ResilientChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

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
        AtomicBoolean emitted = new AtomicBoolean(false); // 每次 stream() 调用独立；跨重试共享
        return delegate.stream(prompt)
                // 首 token 超时 + 逐 token 间隔超时（卡住不吐字快速判死）
                .timeout(Mono.delay(bundle.firstTokenTimeout()),
                         cr -> Mono.delay(bundle.tokenGapTimeout()))
                // 总时长硬上限：到点即以 TimeoutException 终止（防节点级泄漏）
                .takeUntilOther(Mono.delay(bundle.streamMaxDuration())
                         .then(Mono.error(new TimeoutException("stream total exceeded"))))
                .doOnNext(cr -> emitted.set(true))            // 真实 token 已流出 → 过了重试窗口
                .transformDeferred(BulkheadOperator.of(bundle.bulkhead()))       // 订阅占名额、终止释放
                .transformDeferred(CircuitBreakerOperator.of(bundle.circuitBreaker())) // 成/败计入同一熔断窗口
                // 仅首 token 前、且错误可重试(429/503/5xx/408/连接失败；超时/熔断/4xx 不重试)才自动重试；已吐字后绝不重试(防内容错乱)
                .retryWhen(Retry.backoff(Math.max(0, bundle.retryMaxAttempts() - 1), Duration.ofSeconds(1))
                        .jitter(0.5)
                        .filter(err -> !emitted.get() && ResilienceExceptions.isRetryable(err))
                        .onRetryExhaustedThrow((spec, rs) -> rs.failure()))  // 抛原始异常，不包成 RetryExhaustedException
                .doOnError(err -> log.warn("流式调用失败: {}", err.toString(), err))  // 记真实原因(超时/429/503/熔断)，便于诊断
                .onErrorMap(ResilienceExceptions::toBizException);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
