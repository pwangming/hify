package com.hify.provider.service.resilience;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.TimeoutException;

/** LLM 调用异常分类与映射。所有判断走 cause 链，兼容 Spring AI 可能的包装。 */
public final class ResilienceExceptions {

    private ResilienceExceptions() {}

    /** 重试白名单：5xx(含529)/408/429/连接失败 → true。 */
    public static boolean isRetryable(Throwable t) {
        if (has(t, BulkheadFullException.class) || has(t, CallNotPermittedException.class)
                || has(t, TimeoutException.class)) {
            return false;
        }
        HttpStatusCodeException http = find(t, HttpStatusCodeException.class);
        if (http != null) {
            int s = http.getStatusCode().value();
            return s >= 500 || s == 408 || s == 429;
        }
        return has(t, ResourceAccessException.class);
    }

    /** 熔断只记供应商故障：5xx/连接失败/读超时 → true；4xx(含429)/信号量满/未知 → false。 */
    public static boolean isProviderFault(Throwable t) {
        if (has(t, BulkheadFullException.class)) {
            return false;
        }
        if (has(t, TimeoutException.class) || has(t, ResourceAccessException.class)) {
            return true;
        }
        HttpStatusCodeException http = find(t, HttpStatusCodeException.class);
        return http != null && http.getStatusCode().value() >= 500;
    }

    /** 装饰链最外层抛出的异常 → 业务异常。 */
    public static BizException toBizException(Throwable t) {
        if (has(t, BulkheadFullException.class)) {
            return new BizException(ProviderError.PROVIDER_BUSY);
        }
        // TimeoutException / CallNotPermittedException / 重试耗尽后的底层失败，统一对外 503
        return new BizException(ProviderError.PROVIDER_UNAVAILABLE,
                ProviderError.PROVIDER_UNAVAILABLE.defaultMessage(), t);
    }

    /** 把 checked 异常以原类型重新抛出，保留供分类器识别。 */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private static boolean has(Throwable t, Class<? extends Throwable> type) {
        return find(t, type) != null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return (T) c;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
