package com.hify.provider.service.resilience;

import com.hify.provider.entity.ModelProvider;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

import java.time.Duration;

/** 一个供应商实例的四件套韧性配置（按 model_provider 字段构建；固定常量写死）。 */
public record ResilienceBundle(TimeLimiter timeLimiter, Bulkhead bulkhead,
                               CircuitBreaker circuitBreaker, Retry retry,
                               Duration firstTokenTimeout, Duration tokenGapTimeout,
                               Duration streamMaxDuration, int retryMaxAttempts) {

    public static ResilienceBundle build(ModelProvider p) {
        String name = "llm-provider-" + p.getId();

        TimeLimiter timeLimiter = TimeLimiter.of(name, TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(p.getResponseTimeoutSec()))
                .cancelRunningFuture(true)
                .build());

        Bulkhead bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(p.getMaxConcurrency())
                .maxWaitDuration(Duration.ofSeconds(2))
                .build());

        CircuitBreaker circuitBreaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .failureRateThreshold(p.getCbFailureRate())
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(30))
                .waitDurationInOpenState(Duration.ofSeconds(p.getCbWaitOpenSec()))
                .permittedNumberOfCallsInHalfOpenState(5)
                .recordException(ResilienceExceptions::isProviderFault)
                .build());

        Retry retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(p.getRetryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofSeconds(1), 2.0, 0.5))
                .retryOnException(ResilienceExceptions::isRetryable)
                .build());

        Duration firstTokenTimeout = Duration.ofSeconds(p.getFirstTokenTimeoutSec());
        Duration tokenGapTimeout = Duration.ofSeconds(p.getTokenGapTimeoutSec());
        Duration streamMaxDuration = Duration.ofSeconds(p.getStreamMaxDurationSec());
        return new ResilienceBundle(timeLimiter, bulkhead, circuitBreaker, retry,
                firstTokenTimeout, tokenGapTimeout, streamMaxDuration, p.getRetryMaxAttempts());
    }
}
