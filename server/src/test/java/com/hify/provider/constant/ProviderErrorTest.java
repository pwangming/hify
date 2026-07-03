package com.hify.provider.constant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderErrorTest {
    @Test
    void 运行时错误码段与状态() {
        assertEquals(12002, ProviderError.MODEL_NOT_USABLE.code());
        assertEquals(HttpStatus.BAD_REQUEST, ProviderError.MODEL_NOT_USABLE.status());
        assertEquals(12003, ProviderError.PROVIDER_UNAVAILABLE.code());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ProviderError.PROVIDER_UNAVAILABLE.status());
        assertEquals(12004, ProviderError.PROVIDER_BUSY.code());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ProviderError.PROVIDER_BUSY.status());
        assertEquals(12005, ProviderError.EMBEDDING_DIMENSION_MISMATCH.code());
        assertEquals(HttpStatus.BAD_REQUEST, ProviderError.EMBEDDING_DIMENSION_MISMATCH.status());
        assertEquals(12006, ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED.code());
        assertEquals(HttpStatus.CONFLICT, ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED.status());
    }
}
