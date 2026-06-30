package com.hify.provider.service.resilience;

import com.hify.common.exception.BizException;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.ModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
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
        p.setFirstTokenTimeoutSec(30);
        p.setTokenGapTimeoutSec(60);
        p.setStreamMaxDurationSec(600);
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

    private ModelProvider streamProvider(int firstSec, int gapSec, int totalSec) {
        ModelProvider p = provider(1, 120);          // 复用现有，已含四件套字段
        p.setFirstTokenTimeoutSec(firstSec);
        p.setTokenGapTimeoutSec(gapSec);
        p.setStreamMaxDurationSec(totalSec);
        return p;
    }

    @Test
    void 流式_首token超时_映射503() {
        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return new ChatResponse(List.of()); }
            public Flux<ChatResponse> stream(Prompt p) {
                return Flux.never();      // 永不吐第一个 token
            }
        };
        ResilientChatModel m = wrap(delegate, streamProvider(1, 60, 600)); // 首 token 1s

        StepVerifier.create(m.stream(new Prompt("hi")))
                .expectErrorSatisfies(e -> assertEquals(ProviderError.PROVIDER_UNAVAILABLE,
                        ((BizException) e).errorCode()))
                .verify(java.time.Duration.ofSeconds(3));
    }

    @Test
    void 流式_正常吐字_全部透传() {
        ChatResponse c1 = new ChatResponse(List.of(
                new Generation(new AssistantMessage("你好"))));
        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return c1; }
            public Flux<ChatResponse> stream(Prompt p) {
                return Flux.just(c1, c1);
            }
        };
        ResilientChatModel m = wrap(delegate, streamProvider(30, 60, 600));

        StepVerifier.create(m.stream(new Prompt("hi")))
                .expectNext(c1).expectNext(c1).verifyComplete();
    }
}
