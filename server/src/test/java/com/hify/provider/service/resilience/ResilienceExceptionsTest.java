package com.hify.provider.service.resilience;

import com.hify.provider.constant.ProviderError;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceExceptionsTest {

    @Test
    void 服务端5xx_可重试且计入熔断() {
        Throwable e = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null);
        assertTrue(ResilienceExceptions.isRetryable(e));
        assertTrue(ResilienceExceptions.isProviderFault(e));
    }

    @Test
    void 客户端400_不重试不计熔断() {
        Throwable e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", null, null, null);
        assertFalse(ResilienceExceptions.isRetryable(e));
        assertFalse(ResilienceExceptions.isProviderFault(e));
    }

    @Test
    void 限流429_可重试但不计熔断() {
        Throwable e = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "429", null, null, null);
        assertTrue(ResilienceExceptions.isRetryable(e));
        assertFalse(ResilienceExceptions.isProviderFault(e));
    }

    @Test
    void 连接失败_可重试且计熔断() {
        Throwable e = new ResourceAccessException("connect refused");
        assertTrue(ResilienceExceptions.isRetryable(e));
        assertTrue(ResilienceExceptions.isProviderFault(e));
    }

    @Test
    void 读超时_不重试但计熔断_映射503() {
        Throwable e = new TimeoutException("read timeout");
        assertFalse(ResilienceExceptions.isRetryable(e));
        assertTrue(ResilienceExceptions.isProviderFault(e));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ResilienceExceptions.toBizException(e).errorCode());
    }

    @Test
    void 信号量满_映射429且不计熔断() {
        Throwable e = BulkheadFullException.createBulkheadFullException(
                io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("t"));
        assertFalse(ResilienceExceptions.isProviderFault(e));
        assertEquals(ProviderError.PROVIDER_BUSY, ResilienceExceptions.toBizException(e).errorCode());
    }

    @Test
    void 包装在cause链里的5xx也能识别() {
        Throwable wrapped = new RuntimeException("wrapper",
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "502", null, null, null));
        assertTrue(ResilienceExceptions.isRetryable(wrapped));
    }
}
