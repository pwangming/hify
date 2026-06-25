package com.hify.provider.service.resilience;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ResilientChatModelTest {

    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() { exec.shutdownNow(); }

    private ModelProvider provider(int retries, int timeoutSec) {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setMaxConcurrency(2);
        p.setRetryMaxAttempts(retries);
        p.setCbFailureRate(50);
        p.setCbWaitOpenSec(30);
        p.setResponseTimeoutSec(timeoutSec);
        return p;
    }

    private ResilientChatModel wrap(ChatModel delegate, ModelProvider p) {
        return new ResilientChatModel(delegate, ResilienceBundle.build(p), exec);
    }

    @Test
    void 正常调用_直接返回() {
        ChatResponse resp = new ChatResponse(java.util.List.of());
        ResilientChatModel m = wrap(prompt -> resp, provider(3, 120));
        assertSame(resp, m.call(new Prompt("hi")));
    }

    @Test
    void 服务端503_重试到上限后映射503() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = prompt -> {
            calls.incrementAndGet();
            throw HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null);
        };
        ResilientChatModel m = wrap(delegate, provider(3, 120));

        BizException ex = assertThrows(BizException.class, () -> m.call(new Prompt("hi")));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        assertEquals(3, calls.get()); // 3 次尝试（重试 2 次）
    }

    @Test
    void 客户端400_不重试_映射503() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = prompt -> {
            calls.incrementAndGet();
            throw HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "400", null, null, null);
        };
        ResilientChatModel m = wrap(delegate, provider(3, 120));

        assertThrows(BizException.class, () -> m.call(new Prompt("hi")));
        assertEquals(1, calls.get()); // 不重试
    }

    @Test
    void 超时_映射503() {
        ChatModel delegate = prompt -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            return new ChatResponse(java.util.List.of());
        };
        ResilientChatModel m = wrap(delegate, provider(1, 1)); // 1s 超时，1 次尝试

        BizException ex = assertThrows(BizException.class, () -> m.call(new Prompt("hi")));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
    }
}
