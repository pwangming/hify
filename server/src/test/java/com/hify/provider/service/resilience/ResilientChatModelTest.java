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

import java.time.Duration;
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
        ModelProvider p = provider(3, 120);          // retryMaxAttempts=3
        p.setFirstTokenTimeoutSec(firstSec);
        p.setTokenGapTimeoutSec(gapSec);
        p.setStreamMaxDurationSec(totalSec);
        return p;
    }

    /** 虚拟时间专用：retryMaxAttempts=3，首token/gap/total超大，不触发流式超时 */
    private ModelProvider streamProviderForRetry() {
        return streamProvider(90, 120, 600);
    }

    @Test
    void 流式_首token超时_映射503() {
        AtomicInteger subs = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return new ChatResponse(List.of()); }
            public Flux<ChatResponse> stream(Prompt p) {
                subs.incrementAndGet();
                return Flux.never();      // 永不吐第一个 token
            }
        };
        // firstTokenTimeoutSec=1，重试设3次，但超时不可重试 → counter 应为 1
        ResilientChatModel m = wrap(delegate, streamProvider(1, 60, 600));

        StepVerifier.create(m.stream(new Prompt("hi")))
                .expectErrorSatisfies(e -> assertEquals(ProviderError.PROVIDER_UNAVAILABLE,
                        ((BizException) e).errorCode()))
                .verify(java.time.Duration.ofSeconds(5));

        // 首token超时不可重试 → 只订阅 1 次
        assertEquals(1, subs.get());
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

    // =========== 新增：流式重试测试 ===========

    @Test
    void 流式_首token前503_重试后成功() {
        ChatResponse chunk = new ChatResponse(List.of(
                new Generation(new AssistantMessage("hello"))));
        AtomicInteger subs = new AtomicInteger();

        // 第1、2次订阅抛 503；第3次正常返回 chunk
        // 注意：delegate.stream() 在 assembly time 只调用一次，retryWhen 是 re-subscribe 同一 Flux；
        // 必须用 Flux.defer 让每次 subscribe 都重新执行 lambda，才能模拟 cold Flux 重试行为。
        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return new ChatResponse(List.of()); }
            public Flux<ChatResponse> stream(Prompt p) {
                return Flux.defer(() -> {
                    int attempt = subs.incrementAndGet();
                    if (attempt < 3) {
                        return Flux.<ChatResponse>error(HttpServerErrorException.create(
                                HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null));
                    }
                    return Flux.just(chunk);
                });
            }
        };
        ResilientChatModel m = wrap(delegate, streamProviderForRetry());

        // retryMaxAttempts=3 → 最多 3 次订阅；backoff 1s + jitter，用虚拟时间快进
        StepVerifier.withVirtualTime(() -> m.stream(new Prompt("hi")))
                .thenAwait(Duration.ofSeconds(10))
                .expectNext(chunk)
                .verifyComplete();

        assertEquals(3, subs.get());
    }

    @Test
    void 流式_已吐字后报错_不重试() {
        ChatResponse chunk = new ChatResponse(List.of(
                new Generation(new AssistantMessage("part"))));
        AtomicInteger subs = new AtomicInteger();

        // 吐一个 chunk 后立即报 503
        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return new ChatResponse(List.of()); }
            public Flux<ChatResponse> stream(Prompt p) {
                subs.incrementAndGet();
                return Flux.concat(
                        Flux.just(chunk),
                        Flux.error(HttpServerErrorException.create(
                                HttpStatus.SERVICE_UNAVAILABLE, "503", null, null, null))
                );
            }
        };
        ResilientChatModel m = wrap(delegate, streamProviderForRetry());

        StepVerifier.withVirtualTime(() -> m.stream(new Prompt("hi")))
                .thenAwait(Duration.ofSeconds(5))
                .expectNext(chunk)
                .expectErrorSatisfies(e -> assertEquals(ProviderError.PROVIDER_UNAVAILABLE,
                        ((BizException) e).errorCode()))
                .verify();

        // 吐字后不重试 → 只有 1 次订阅
        assertEquals(1, subs.get());
    }

    @Test
    void 流式_首token前400_不重试() {
        AtomicInteger subs = new AtomicInteger();

        ChatModel delegate = new ChatModel() {
            public ChatResponse call(Prompt p) { return new ChatResponse(List.of()); }
            public Flux<ChatResponse> stream(Prompt p) {
                subs.incrementAndGet();
                return Flux.error(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "400", null, null, null));
            }
        };
        ResilientChatModel m = wrap(delegate, streamProviderForRetry());

        StepVerifier.withVirtualTime(() -> m.stream(new Prompt("hi")))
                .thenAwait(Duration.ofSeconds(5))
                .expectErrorSatisfies(e -> assertEquals(ProviderError.PROVIDER_UNAVAILABLE,
                        ((BizException) e).errorCode()))
                .verify();

        // 400 不可重试 → 1 次
        assertEquals(1, subs.get());
    }
}
