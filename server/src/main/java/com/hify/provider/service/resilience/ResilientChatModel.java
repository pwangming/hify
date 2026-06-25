package com.hify.provider.service.resilience;

import io.github.resilience4j.decorators.Decorators;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Spring AI ChatModel 装饰器：包一层 Retry(CircuitBreaker(Bulkhead(TimeLimiter(真实调用))))。
 * 仅非流式（call）；流式 stream 留待后续轮次，当前委托给底层（未加韧性）。
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
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
