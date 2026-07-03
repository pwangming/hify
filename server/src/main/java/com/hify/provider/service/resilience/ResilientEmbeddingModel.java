package com.hify.provider.service.resilience;

import io.github.resilience4j.decorators.Decorators;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Spring AI EmbeddingModel 装饰器：call 走批量池四件套
 * Retry(CircuitBreaker(Bulkhead(TimeLimiter(真实调用))))。
 */
public class ResilientEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final ResilienceBundle bundle;
    private final ExecutorService executor;

    public ResilientEmbeddingModel(EmbeddingModel delegate, ResilienceBundle bundle, ExecutorService executor) {
        this.delegate = delegate;
        this.bundle = bundle;
        this.executor = executor;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Supplier<EmbeddingResponse> timed = () -> callWithTimeout(request);
        Supplier<EmbeddingResponse> decorated = Decorators.ofSupplier(timed)
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

    private EmbeddingResponse callWithTimeout(EmbeddingRequest request) {
        try {
            return bundle.timeLimiter().executeFutureSupplier(
                    () -> executor.submit(() -> delegate.call(request)));
        } catch (Throwable t) {
            throw ResilienceExceptions.sneaky(t);
        }
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }
}
