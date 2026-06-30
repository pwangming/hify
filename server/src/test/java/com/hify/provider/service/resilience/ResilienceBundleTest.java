package com.hify.provider.service.resilience;

import com.hify.provider.entity.ModelProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResilienceBundleTest {

    private ModelProvider provider() {
        ModelProvider p = new ModelProvider();
        p.setId(7L);
        p.setMaxConcurrency(4);
        p.setRetryMaxAttempts(3);
        p.setCbFailureRate(50);
        p.setCbWaitOpenSec(30);
        p.setResponseTimeoutSec(120);
        p.setFirstTokenTimeoutSec(30);
        p.setTokenGapTimeoutSec(60);
        p.setStreamMaxDurationSec(600);
        return p;
    }

    @Test
    void 字段映射到四件套配置() {
        ResilienceBundle b = ResilienceBundle.build(provider());

        assertEquals(java.time.Duration.ofSeconds(120),
                b.timeLimiter().getTimeLimiterConfig().getTimeoutDuration());
        assertEquals(4, b.bulkhead().getBulkheadConfig().getMaxConcurrentCalls());
        assertEquals(50f, b.circuitBreaker().getCircuitBreakerConfig().getFailureRateThreshold());
        assertEquals(20, b.circuitBreaker().getCircuitBreakerConfig().getSlidingWindowSize());
        assertEquals(3, b.retry().getRetryConfig().getMaxAttempts());
    }
}
