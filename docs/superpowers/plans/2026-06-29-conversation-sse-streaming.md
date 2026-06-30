# ⑤ conversation SSE 流式输出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 conversation「发消息」从一次性 `.call()` 升级为流式 `.stream()`，模型逐 token 吐字，前端打字机增量渲染。

**Architecture:** provider 的 `ResilientChatModel` 新增 `stream()` 重写（够用子集韧性）；conversation 新增 `ConversationService.sendStream()` 返回 `Flux<StreamEvent>`（delta 流 + 尾部 `concatWith` 落库 Mono，保住「事务A→LLM无事务→事务B」铁律、半截不落）；新增 `POST /messages/stream` 控制器返回 `Flux<ServerSentEvent>`；前端 `useChatStream`（fetch+ReadableStream）驱动 store 增量追加助手气泡。

**Tech Stack:** Spring Boot 3 + Spring AI 1.0.1（`ChatModel.stream`/Flux）+ Resilience4j 2.2（reactor 算子，来自 `resilience4j-all`）+ Reactor + MyBatis-Plus；Vue 3 + TS + Pinia + Vitest。

## Global Constraints

- 事务边界铁律：`ConversationService` 无 `@Transactional`；LLM 调用（delta 流）不被任何事务包裹；落库收口在 `ConversationStore`（`openTurn` 事务A / `appendAssistant` 事务B）。
- 流式韧性「够用子集」：Bulkhead + 三层超时（首 token 30s / token 间隔 60s / 总 10min）+ CircuitBreaker；**不做 Retry**。
- 失败/断开：半截不落库；客户端断开取消上游 Flux 止血；孤儿会话保持现状（D2 延后）。
- 契约只增不改：成员族路由 `/api/v1/conversation/**`；错误码 **17xxx 段不新增**；现有 `POST /messages`(JSON)/`GET /messages`/`GET /conversations` 全保留。
- SSE 四事件（api-standards §3.3）：`message{delta}` / `error{code,message,traceId}` / `done{conversationId,messageId,usage}`；连接前错误走 JSON，连接后走 `error` 事件再关流；Long/token 经全局 Jackson 序列化为 string；15s 发 `: ping`。
- 前端最小档：打字机增量 + sending 禁输入 + 错误内联；**无**停止/重新生成按钮；SSE 用 fetch+ReadableStream，非 EventSource（frontend-standards §3.4）；优先 Element Plus（§5.9）。
- 测试：后端 mock + StepVerifier + MockMvc，连库测试仍延后；前端 vitest，测试放 `__tests__/`。判 mvn 结果看 Surefire `Tests run/Failures/Errors`，不 grep BUILD SUCCESS、不加 `-q`。

> **对 spec 的实现细化**：spec §3 用 `SseEmitter` 手写桥接示意；本计划改为控制器返回 `Flux<ServerSentEvent<String>>`（Spring MVC 经 `ReactiveTypeHandler` 适配为流式 SSE）——外部行为一致（text/event-stream、四事件、断开取消上游），但取消语义更干净、可被 MockMvc 异步测试。

---

## File Structure

**后端 · provider（Task 1）**
- Modify `server/.../provider/service/resilience/ResilienceBundle.java` — record 增 3 个流式 Duration 字段
- Modify `server/.../provider/service/resilience/ResilientChatModel.java` — 重写 `stream(Prompt)`
- Modify `server/.../provider/api/ProviderFacade.java` — 仅 javadoc
- Modify `server/.../test/.../resilience/ResilientChatModelTest.java`、`ResilienceBundleTest.java` — 补流式字段 + 新增 stream 用例

**后端 · conversation（Task 2、3）**
- Create `server/.../conversation/service/StreamEvent.java` — 密封事件类型
- Modify `server/.../conversation/service/ChatInvoker.java` — 新增 `invokeStream`
- Modify `server/.../conversation/service/ConversationService.java` — 新增 `sendStream`
- Create `server/.../conversation/dto/StreamPayloads.java` — SSE 载荷 record（message/done）
- Modify `server/.../conversation/controller/ConversationController.java` — 新增 `POST /messages/stream`
- Modify tests: `ConversationServiceTest.java`、`ConversationControllerTest.java`

**后端 · pom（Task 1 Step 0）**
- Modify `server/pom.xml` — 加 `reactor-test`（test scope，供全模块 StepVerifier）

**前端（Task 4、5、6）**
- Create `web/src/composables/useChatStream.ts` + `__tests__/useChatStream.spec.ts`
- Modify `web/src/stores/conversation.ts` + `__tests__/conversation.spec.ts`
- Modify `web/src/views/conversation/ChatView.vue` + `__tests__/ChatView.spec.ts`
- Modify `web/src/api/conversation.ts`、`web/src/config/index.ts`、`web/src/api/__tests__/conversation.spec.ts`（删 workaround）

---

## Task 1: provider — `ResilientChatModel.stream()` 够用子集韧性

**Files:**
- Modify: `server/src/main/java/com/hify/provider/service/resilience/ResilienceBundle.java`
- Modify: `server/src/main/java/com/hify/provider/service/resilience/ResilientChatModel.java`
- Modify: `server/src/main/java/com/hify/provider/api/ProviderFacade.java`
- Test: `server/src/test/java/com/hify/provider/service/resilience/ResilientChatModelTest.java`
- Test: `server/src/test/java/com/hify/provider/service/resilience/ResilienceBundleTest.java`

**Interfaces:**
- Produces: `ResilienceBundle` 新增访问器 `Duration firstTokenTimeout()` / `Duration tokenGapTimeout()` / `Duration streamMaxDuration()`；`ResilientChatModel.stream(Prompt) -> Flux<ChatResponse>`（错误归一为 `BizException`，与 `call()` 同）。

- [ ] **Step 0: pom 加 `reactor-test`（本任务起就用到 StepVerifier；server 单 pom，加一次全模块可用）**

`server/pom.xml` 测试依赖区加（版本由 Spring Boot BOM 管理，不写 version）：

```xml
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 1: 先扩 `ResilienceBundle` 携带流式 Duration（否则 stream 测试无字段可读）**

把 record 头与 `build()` 改为（保留原有四件套构建逻辑，仅追加 3 个 Duration）：

```java
public record ResilienceBundle(TimeLimiter timeLimiter, Bulkhead bulkhead,
                               CircuitBreaker circuitBreaker, Retry retry,
                               Duration firstTokenTimeout, Duration tokenGapTimeout,
                               Duration streamMaxDuration) {

    public static ResilienceBundle build(ModelProvider p) {
        String name = "llm-provider-" + p.getId();
        // ……（TimeLimiter / Bulkhead / CircuitBreaker / Retry 构建保持原样不动）……
        Duration firstTokenTimeout = Duration.ofSeconds(p.getFirstTokenTimeoutSec());
        Duration tokenGapTimeout = Duration.ofSeconds(p.getTokenGapTimeoutSec());
        Duration streamMaxDuration = Duration.ofSeconds(p.getStreamMaxDurationSec());
        return new ResilienceBundle(timeLimiter, bulkhead, circuitBreaker, retry,
                firstTokenTimeout, tokenGapTimeout, streamMaxDuration);
    }
}
```

- [ ] **Step 2: 修既有测试的 provider 工厂（补 3 个流式字段，避免 `build()` 读到 null NPE）**

`ResilientChatModelTest.provider(int retries, int timeoutSec)` 末尾补三行；`ResilienceBundleTest` 里构造 `ModelProvider` 的地方同样补：

```java
p.setFirstTokenTimeoutSec(30);
p.setTokenGapTimeoutSec(60);
p.setStreamMaxDurationSec(600);
```

- [ ] **Step 3: 写流式韧性的失败测试（首 token 超时 / 总时长两条）**

在 `ResilientChatModelTest` 增（含一个能自定义 `stream()` 的委托）：

```java
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
        public ChatResponse call(Prompt p) { return new ChatResponse(java.util.List.of()); }
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt p) {
            return reactor.core.publisher.Flux.never();      // 永不吐第一个 token
        }
    };
    ResilientChatModel m = wrap(delegate, streamProvider(1, 60, 600)); // 首 token 1s

    reactor.test.StepVerifier.create(m.stream(new Prompt("hi")))
            .expectErrorSatisfies(e -> assertEquals(ProviderError.PROVIDER_UNAVAILABLE,
                    ((BizException) e).errorCode()))
            .verify(java.time.Duration.ofSeconds(3));
}

@Test
void 流式_正常吐字_全部透传() {
    ChatResponse c1 = new ChatResponse(java.util.List.of(
            new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("你好"))));
    ChatModel delegate = new ChatModel() {
        public ChatResponse call(Prompt p) { return c1; }
        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt p) {
            return reactor.core.publisher.Flux.just(c1, c1);
        }
    };
    ResilientChatModel m = wrap(delegate, streamProvider(30, 60, 600));

    reactor.test.StepVerifier.create(m.stream(new Prompt("hi")))
            .expectNext(c1).expectNext(c1).verifyComplete();
}
```

> `StepVerifier` 来自 Step 0 已加的 `reactor-test`。

- [ ] **Step 4: 运行测试，确认失败**

Run: `cd server && ./mvnw test -Dtest=ResilientChatModelTest`
Expected: 编译失败 / 新用例 FAIL（`stream` 尚未重写，走默认实现不触发超时）。看 Surefire `Tests run / Failures / Errors`。

- [ ] **Step 5: 重写 `ResilientChatModel.stream()`**

类头补导入并新增方法（`call()` 与其它不动）：

```java
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.concurrent.TimeoutException;

@Override
public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prompt)
            // 首 token 超时 + 逐 token 间隔超时（卡住不吐字快速判死）
            .timeout(Mono.delay(bundle.firstTokenTimeout()),
                     cr -> Mono.delay(bundle.tokenGapTimeout()))
            // 总时长硬上限：到点即以 TimeoutException 终止（防节点级泄漏）
            .takeUntilOther(Mono.delay(bundle.streamMaxDuration())
                     .then(Mono.error(new TimeoutException("stream total exceeded"))))
            .transformDeferred(BulkheadOperator.of(bundle.bulkhead()))       // 订阅占名额、终止释放
            .transformDeferred(CircuitBreakerOperator.of(bundle.circuitBreaker())) // 成/败计入同一熔断窗口
            // 不挂 Retry —— 吐字后中断不自动重试（llm-resilience §3）
            .onErrorMap(ResilienceExceptions::toBizException);
}
```

并更新类注释：把「仅非流式（call）；流式 stream 留待后续轮次」改为「call 四件套 + stream 够用子集（Bulkhead+三层超时+CircuitBreaker，不重试）」。

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd server && ./mvnw test -Dtest=ResilientChatModelTest,ResilienceBundleTest`
Expected: `Failures: 0, Errors: 0`，含新 stream 用例。

- [ ] **Step 7: 更新 `ProviderFacade.getChatClient` javadoc**

把「自带非流式韧性：重试/熔断/信号量/超时」改为「自带韧性：call 全四件套 + stream 够用子集（信号量/三层超时/熔断，不重试）」。

- [ ] **Step 8: 提交**

```bash
cd server && git add src/main/java/com/hify/provider src/test/java/com/hify/provider
git commit -m "feat(provider): ResilientChatModel.stream() 够用子集韧性（Bulkhead+三层超时+熔断，不重试）"
```

---

## Task 2: conversation — `sendStream` 返回 `Flux<StreamEvent>`

**Files:**
- Create: `server/src/main/java/com/hify/conversation/service/StreamEvent.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ChatInvoker.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Modify: `server/pom.xml`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`

**Interfaces:**
- Produces:
  - `sealed interface StreamEvent permits StreamEvent.Delta, StreamEvent.Done`，`record Delta(String text)`、`record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)`。
  - `ChatInvoker.invokeStream(ChatClient, String systemPrompt, List<Message> window) -> Flux<ChatResponse>`。
  - `ConversationService.sendStream(Long appId, Long conversationId, String content, CurrentUser) -> Flux<StreamEvent>`。
- Consumes: `ProviderFacade.getChatClient`、`ConversationStore.openTurn/appendAssistant`、`AppFacade.findRunnableChatApp`、`QuotaGuard.check`（全部已存在）。

- [ ] **Step 1: 写 `sendStream` 的失败测试（StepVerifier：正常吐字 + 半截不落）**

> `reactor-test` 已在 Task 1 Step 0 加入；本任务直接用 `StepVerifier`。

在 `ConversationServiceTest` 增（复用既有 mock 字段；补两个 chunk 构造助手）：

```java
private org.springframework.ai.chat.model.ChatResponse chunk(String text) {
    return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
            new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(text))));
}
private org.springframework.ai.chat.model.ChatResponse chunkWithUsage(String text, int p, int c) {
    return org.springframework.ai.chat.model.ChatResponse.builder()
            .generations(java.util.List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(text))))
            .metadata(org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                    .usage(new org.springframework.ai.chat.metadata.DefaultUsage(p, c, p + c)).build())
            .build();
}

@Test
void sendStream_增量吐字_结束落全文与usage_发Done() {
    stubRunnableApp("你是客服");
    java.util.List<Message> window = java.util.List.of(userMsg("你好"));
    when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
            .thenReturn(new TurnContext(100L, window));
    when(chatInvoker.invokeStream(eq(chatClient), eq("你是客服"), eq(window)))
            .thenReturn(reactor.core.publisher.Flux.just(chunk("你好，"), chunkWithUsage("我是助手", 12, 8)));
    when(store.appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8)))
            .thenReturn(savedAssistant());

    reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
            .expectNext(new StreamEvent.Delta("你好，"))
            .expectNext(new StreamEvent.Delta("我是助手"))
            .expectNextMatches(e -> e instanceof StreamEvent.Done d
                    && d.conversationId() == 100L && d.messageId() == 200L
                    && d.promptTokens() == 12 && d.completionTokens() == 8)
            .verifyComplete();
    verify(store).appendAssistant(100L, "你好，我是助手", 12, 8);
}

@Test
void sendStream_流报错_半截不落assistant() {
    stubRunnableApp(null);
    when(store.openTurn(any(), any(), any(), any()))
            .thenReturn(new TurnContext(100L, java.util.List.of(userMsg("你好"))));
    when(chatInvoker.invokeStream(any(), any(), any()))
            .thenReturn(reactor.core.publisher.Flux.concat(
                    reactor.core.publisher.Flux.just(chunk("半")),
                    reactor.core.publisher.Flux.error(new BizException(ProviderError.PROVIDER_UNAVAILABLE))));

    reactor.test.StepVerifier.create(service.sendStream(7L, null, "你好", member))
            .expectNext(new StreamEvent.Delta("半"))
            .expectErrorMatches(e -> e instanceof BizException b
                    && b.errorCode() == ProviderError.PROVIDER_UNAVAILABLE)
            .verify();
    verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt());
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd server && ./mvnw test -Dtest=ConversationServiceTest`
Expected: 编译失败（`StreamEvent` / `invokeStream` / `sendStream` 未定义）。

- [ ] **Step 3: 建 `StreamEvent`**

```java
package com.hify.conversation.service;

/** 流式编排的内部事件（仅 service↔controller，不跨模块）。 */
public sealed interface StreamEvent permits StreamEvent.Delta, StreamEvent.Done {

    /** 一段增量正文。 */
    record Delta(String text) implements StreamEvent {}

    /** 流正常结束、assistant 已落库的终态（含新会话 id 供前端写回 URL）。 */
    record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)
            implements StreamEvent {}
}
```

- [ ] **Step 4: `ChatInvoker` 增 `invokeStream`**

复用既有 `toMessages`（纯映射，已单测）；真实 IO 不单测（同 `invoke`，进手验单）：

```java
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/** 流式调用：把 ChatClient 的逐块响应原样吐出（韧性已在 provider 的 ResilientChatModel.stream 内）。 */
public Flux<ChatResponse> invokeStream(ChatClient chatClient, String systemPrompt, List<Message> window) {
    return chatClient.prompt()
            .messages(toMessages(systemPrompt, window))
            .stream()
            .chatResponse();
}
```

- [ ] **Step 5: `ConversationService` 增 `sendStream`**

`send` 不动；新增（注意：累积副作用在 `doOnNext`，发射在 `mapNotNull`，落库在尾部 `concatWith` 的 `Mono.fromCallable`）：

```java
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public Flux<StreamEvent> sendStream(Long appId, Long conversationId, String content, CurrentUser current) {
    quotaGuard.check(current.userId(), appId);
    AppRuntimeView app = appFacade.findRunnableChatApp(appId)
            .orElseThrow(() -> new BizException(ConversationError.APP_NOT_RUNNABLE));
    TurnContext turn = store.openTurn(appId, conversationId, current.userId(), content);
    Long cid = turn.conversationId();
    ChatClient chatClient = providerFacade.getChatClient(app.modelId());

    StringBuilder buf = new StringBuilder();
    int[] usage = {0, 0}; // [0]=promptTokens [1]=completionTokens，取流中最后一个非空值

    Flux<StreamEvent> deltas = chatInvoker.invokeStream(chatClient, app.systemPrompt(), turn.window())
            .doOnNext(cr -> {
                Usage u = cr.getMetadata() != null ? cr.getMetadata().getUsage() : null;
                if (u != null) {
                    if (u.getPromptTokens() != null) usage[0] = u.getPromptTokens();
                    if (u.getCompletionTokens() != null) usage[1] = u.getCompletionTokens();
                }
                String t = textOf(cr);
                if (t != null && !t.isEmpty()) buf.append(t);
            })
            .mapNotNull(cr -> {
                String t = textOf(cr);
                return (t == null || t.isEmpty()) ? null : new StreamEvent.Delta(t);
            });

    Mono<StreamEvent> done = Mono.fromCallable(() -> {
        Message saved = store.appendAssistant(cid, buf.toString(), usage[0], usage[1]); // 事务B
        return new StreamEvent.Done(cid, saved.getId(), usage[0], usage[1]);
    });

    return deltas.concatWith(done);
}

private static String textOf(ChatResponse cr) {
    return cr.getResults().isEmpty() ? null : cr.getResult().getOutput().getText();
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd server && ./mvnw test -Dtest=ConversationServiceTest`
Expected: `Failures: 0, Errors: 0`（含两条 sendStream 用例与原有 send 用例）。

- [ ] **Step 7: 提交**

```bash
cd server && git add pom.xml src/main/java/com/hify/conversation src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(conversation): ConversationService.sendStream 流式编排（concatWith 尾部落库，半截不落）"
```

---

## Task 3: conversation — `POST /messages/stream` 控制器（`Flux<ServerSentEvent>`）

**Files:**
- Create: `server/src/main/java/com/hify/conversation/dto/StreamPayloads.java`
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`
- Test: `server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java`

**Interfaces:**
- Consumes: `ConversationService.sendStream(...) -> Flux<StreamEvent>`、`StreamEvent.Delta/Done`、既有 `SendMessageRequest(appId, conversationId, content)`、全局 `ObjectMapper`（Long/数字→string）。
- Produces: `POST /api/v1/conversation/messages/stream`，`produces=text/event-stream`，返回 `Flux<ServerSentEvent<String>>`。

- [ ] **Step 1: 写控制器失败测试（MockMvc 异步：message + done 帧）**

在 `ConversationControllerTest` 增导入与用例：

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import com.hify.conversation.service.StreamEvent;

@Test
void 流式发消息_输出message与done事件() throws Exception {
    when(conversationService.sendStream(eq(7L), eq(null), eq("你好"), any()))
            .thenReturn(reactor.core.publisher.Flux.just(
                    new StreamEvent.Delta("你好，"),
                    new StreamEvent.Done(100L, 200L, 12, 8)));

    MvcResult res = mockMvc.perform(post("/api/v1/conversation/messages/stream")
                    .header("Authorization", "Bearer " + memberToken())
                    .contentType("application/json")
                    .content("{\"appId\":\"7\",\"content\":\"你好\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

    String body = mockMvc.perform(asyncDispatch(res))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn().getResponse().getContentAsString();

    assertThat(body)
            .contains("event:message").contains("\"delta\":\"你好，\"")
            .contains("event:done").contains("\"conversationId\":\"100\"")
            .contains("\"messageId\":\"200\"").contains("\"promptTokens\":\"12\"");
}

@Test
void 流式发消息_content为空_400JSON_不开流() throws Exception {
    mockMvc.perform(post("/api/v1/conversation/messages/stream")
                    .header("Authorization", "Bearer " + memberToken())
                    .contentType("application/json")
                    .content("{\"appId\":\"7\",\"content\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(10001));
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd server && ./mvnw test -Dtest=ConversationControllerTest`
Expected: 编译失败 / 404（端点未建）。

- [ ] **Step 3: 建 SSE 载荷 record**

```java
package com.hify.conversation.dto;

/** SSE 事件载荷（经全局 Jackson：Long/数字→string，与 MessageView 一致）。 */
public final class StreamPayloads {

    private StreamPayloads() {}

    public record Delta(String delta) {}

    public record Usage(Integer promptTokens, Integer completionTokens) {}

    public record Done(Long conversationId, Long messageId, Usage usage) {}

    public record Error(int code, String message, String traceId) {}
}
```

- [ ] **Step 4: 控制器新增流式端点**

在 `ConversationController` 注入 `ObjectMapper`，新增方法（其余端点不动）：

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.common.exception.BizException;
import com.hify.conversation.dto.StreamPayloads;
import com.hify.conversation.service.StreamEvent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import java.time.Duration;

// 构造器注入 ObjectMapper（全局 bean，Long/数字→string），字段保存。

@PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> sendStream(@Valid @RequestBody SendMessageRequest request) {
    Flux<ServerSentEvent<String>> events = conversationService.sendStream(
                    request.appId(), request.conversationId(), request.content(), CurrentUserHolder.current())
            .map(this::toSse)
            .onErrorResume(ex -> Flux.just(toErrorSse(ex)));   // 连接后错误→error 事件，再正常完成
    Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
            .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
    // 合并心跳，终态（done/error）一到即完成（取消心跳与上游）
    return Flux.merge(events, heartbeat)
            .takeUntil(sse -> "done".equals(sse.event()) || "error".equals(sse.event()));
}

private ServerSentEvent<String> toSse(StreamEvent e) {
    if (e instanceof StreamEvent.Delta d) {
        return sse("message", new StreamPayloads.Delta(d.text()));
    }
    StreamEvent.Done done = (StreamEvent.Done) e;
    return sse("done", new StreamPayloads.Done(done.conversationId(), done.messageId(),
            new StreamPayloads.Usage(done.promptTokens(), done.completionTokens())));
}

private ServerSentEvent<String> toErrorSse(Throwable ex) {
    int code = ex instanceof BizException b ? b.errorCode().code() : 10000;
    String msg = ex instanceof BizException b ? b.getMessage() : "系统繁忙";
    return sse("error", new StreamPayloads.Error(code, msg, org.slf4j.MDC.get("traceId")));
}

private ServerSentEvent<String> sse(String event, Object payload) {
    try {
        return ServerSentEvent.<String>builder().event(event).data(objectMapper.writeValueAsString(payload)).build();
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new IllegalStateException(ex); // 载荷为内部 record，不应失败
    }
}
```

更新类注释：把「本轮一次性 .call() 返回（SSE 留下一轮）」改为「`POST /messages` 一次性 JSON；`POST /messages/stream` 流式 SSE（四事件）」。

- [ ] **Step 5: 运行测试，确认通过**

Run: `cd server && ./mvnw test -Dtest=ConversationControllerTest`
Expected: `Failures: 0, Errors: 0`（含两条流式用例与原有用例）。

> 若 `BizException.errorCode().code()` 或 `getMessage()` 取值方式与现有不符，照 `ResilienceExceptions`/全局异常处理器里的既有用法对齐（不新增 API）。

- [ ] **Step 6: 跑模块边界与全量后端测试**

Run: `cd server && ./mvnw test`
Expected: `Failures: 0, Errors: 0`；ArchUnit/Modulith 绿（`StreamEvent`/`StreamPayloads` 均在 conversation 模块内，不跨模块）。

- [ ] **Step 7: 提交**

```bash
cd server && git add src/main/java/com/hify/conversation src/test/java/com/hify/conversation/controller/ConversationControllerTest.java
git commit -m "feat(conversation): POST /messages/stream 流式 SSE 端点（四事件+15s心跳+断开取消）"
```

---

## Task 4: 前端 — `useChatStream` composable（fetch + ReadableStream）

**Files:**
- Create: `web/src/composables/useChatStream.ts`
- Test: `web/src/composables/__tests__/useChatStream.spec.ts`

**Interfaces:**
- Produces: `useChatStream()` 返回 `{ start, abort }`；`start(appId, conversationId, content, handlers)`，`handlers = { onDelta(text), onDone(conversationId, messageId, usage), onError({code,message}) }`；`abort()` 取消当前流。

- [ ] **Step 1: 写失败测试（mock fetch + ReadableStream 喂 SSE 分块）**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useChatStream } from '@/composables/useChatStream'

function sseResponse(frames: string[]): Response {
  const enc = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(c) { frames.forEach((f) => c.enqueue(enc.encode(f))); c.close() },
  })
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('useChatStream', () => {
  beforeEach(() => { localStorage.setItem('hify_token', 't') })

  it('解析 message → onDelta，done → onDone', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:message\ndata:{"delta":"你好，"}\n\n',
      'event:message\ndata:{"delta":"我是助手"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":"12","completionTokens":"8"}}\n\n',
    ])))
    const deltas: string[] = []
    let doneCid = ''
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onDelta: (t) => deltas.push(t),
      onDone: (cid) => { doneCid = cid },
      onError: () => {},
    })
    expect(deltas).toEqual(['你好，', '我是助手'])
    expect(doneCid).toBe('100')
  })

  it('error 事件 → onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:error\ndata:{"code":12003,"message":"模型供应商暂时不可用"}\n\n',
    ])))
    let err: { code: number } | null = null
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { err = e } })
    expect(err?.code).toBe(12003)
  })

  it('连接前非2xx → onError 解包 Result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 17001, message: '应用不可用', data: null }),
        { status: 400, headers: { 'Content-Type': 'application/json' } })))
    let err: { code: number } | null = null
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { err = e } })
    expect(err?.code).toBe(17001)
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现 `useChatStream`**

```ts
import { config } from '@/config'
import { TOKEN_KEY } from '@/api/request'

export interface ChatStreamHandlers {
  onDelta: (text: string) => void
  onDone: (conversationId: string, messageId: string,
           usage: { promptTokens: number; completionTokens: number }) => void
  onError: (err: { code: number; message: string }) => void
}

/** 对话流式：fetch + ReadableStream 读 SSE（非 EventSource——需带 JWT 头、POST 消息体）。 */
export function useChatStream() {
  let controller: AbortController | null = null

  async function start(
    appId: string, conversationId: string | null, content: string, h: ChatStreamHandlers,
  ): Promise<void> {
    controller = new AbortController()
    let res: Response
    try {
      res = await fetch(`${config.apiBaseUrl}/conversation/messages/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) ?? ''}`,
        },
        body: JSON.stringify({ appId, conversationId, content }),
        signal: controller.signal,
      })
    } catch {
      h.onError({ code: -1, message: '网络异常，请稍后重试' })
      return
    }

    // 连接前错误：非 2xx，body 为 Result 失败信封
    if (!res.ok || !res.body) {
      const body = await res.json().catch(() => null)
      h.onError({ code: body?.code ?? -1, message: body?.message ?? '请求失败' })
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        dispatch(block, h)
      }
    }
  }

  function dispatch(block: string, h: ChatStreamHandlers) {
    let event = 'message'
    let data = ''
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) return // 心跳注释行
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).trim()
    }
    if (!data) return
    const payload = JSON.parse(data)
    if (event === 'message') h.onDelta(payload.delta)
    else if (event === 'done') h.onDone(payload.conversationId, payload.messageId, payload.usage)
    else if (event === 'error') h.onError({ code: payload.code, message: payload.message })
  }

  function abort() {
    controller?.abort()
    controller = null
  }

  return { start, abort }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts`
Expected: 3 passed。

- [ ] **Step 5: 提交**

```bash
cd web && git add src/composables
git commit -m "feat(web): useChatStream 组合式（fetch+ReadableStream 解析 SSE 四事件）"
```

---

## Task 5: 前端 — store 流式 `send` + ChatView 断流止血

**Files:**
- Modify: `web/src/stores/conversation.ts`
- Modify: `web/src/views/conversation/ChatView.vue`
- Test: `web/src/stores/__tests__/conversation.spec.ts`
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`

**Interfaces:**
- Consumes: `useChatStream()`（Task 4）。
- Produces: store `send(appId, content) -> Promise<string>`（流式，增量追加占位气泡，done 解析为会话 id）；store `abort()`。

- [ ] **Step 1: 写 store 失败测试（mock useChatStream，断言增量追加 + done 替换）**

把 `stores/__tests__/conversation.spec.ts` 里依赖 `sendMessage` 的发送用例改为 mock `useChatStream`：

```ts
import { useChatStream } from '@/composables/useChatStream'
vi.mock('@/composables/useChatStream', () => ({ useChatStream: vi.fn() }))

it('send：增量追加助手气泡，done 用真 id 替换并返回会话 id', async () => {
  const start = vi.fn(async (_a, _c, _t, h) => {
    h.onDelta('你好，'); h.onDelta('我是助手')
    h.onDone('100', '200', { promptTokens: 12, completionTokens: 8 })
  })
  ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

  const store = useConversationStore()
  const cid = await store.send('7', '你好')

  expect(cid).toBe('100')
  expect(store.currentId).toBe('100')
  // 末气泡是拼接后的助手全文、id 为真值
  const last = store.messages[store.messages.length - 1]
  expect(last.role).toBe('assistant')
  expect(last.content).toBe('你好，我是助手')
  expect(last.id).toBe('200')
  expect(store.sending).toBe(false)
})

it('send：error 时占位气泡内联错误、sending 复位', async () => {
  const start = vi.fn(async (_a, _c, _t, h) => h.onError({ code: 12003, message: '模型供应商暂时不可用' }))
  ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

  const store = useConversationStore()
  await store.send('7', '你好').catch(() => {})

  const last = store.messages[store.messages.length - 1]
  expect(last.content).toContain('模型供应商暂时不可用')
  expect(store.sending).toBe(false)
})
```

（保留 `loadConversations`/`loadMessages`/`newConversation` 既有用例不变。）

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: FAIL（`send` 仍调 `sendMessage`）。

- [ ] **Step 3: 改 store `send` 为流式 + 加 `abort`**

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMessages, listConversations } from '@/api/conversation'
import { useChatStream } from '@/composables/useChatStream'
import type { MessageView, ConversationView } from '@/types/conversation'

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<ConversationView[]>([])
  const messages = ref<MessageView[]>([])
  const currentId = ref<string | null>(null)
  const loadingList = ref(false)
  const sending = ref(false)
  const chat = useChatStream()

  // …loadConversations / loadMessages / newConversation 保持不变…

  /** 流式发送：推用户气泡 + 空助手占位 → 增量追加 → done 替换为真值。返回会话 id。 */
  function send(appId: string, content: string): Promise<string> {
    messages.value.push({
      id: `local-${Date.now()}`, role: 'user', content,
      promptTokens: null, completionTokens: null, createTime: new Date().toISOString(),
    })
    const idx = messages.value.push({
      id: `local-asst-${Date.now()}`, role: 'assistant', content: '',
      promptTokens: null, completionTokens: null, createTime: new Date().toISOString(),
    }) - 1
    sending.value = true

    return new Promise<string>((resolve, reject) => {
      void chat.start(appId, currentId.value, content, {
        onDelta: (t) => { messages.value[idx].content += t },        // 经代理触发响应式
        onDone: (conversationId, messageId, usage) => {
          messages.value[idx].id = messageId
          messages.value[idx].promptTokens = usage.promptTokens
          messages.value[idx].completionTokens = usage.completionTokens
          currentId.value = conversationId
          sending.value = false
          resolve(conversationId)
        },
        onError: (err) => {
          messages.value[idx].content = messages.value[idx].content || `⚠️ ${err.message}`
          sending.value = false
          reject(err)
        },
      })
    })
  }

  /** 切会话/卸载时止血：取消在途流。 */
  function abort() {
    chat.abort()
    sending.value = false
  }

  return {
    conversations, messages, currentId, loadingList, sending,
    loadConversations, loadMessages, newConversation, send, abort,
  }
})
```

- [ ] **Step 4: 运行 store 测试，确认通过**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts`
Expected: all passed。

- [ ] **Step 5: ChatView 接断流止血（切会话/卸载 abort）**

`ChatView.vue` 的 `<script setup>`：在 `watch(queryCid, …)` 回调进入时、以及组件卸载时调用 `store.abort()`。补 `import { onBeforeUnmount } from 'vue'`，并：

```ts
watch(queryCid, async (cid) => {
  store.abort()                       // 切会话先止血在途流
  if (!cid) { store.newConversation(); return }
  if (cid === currentId.value) return
  await store.loadMessages(cid)
}, { immediate: true })

onBeforeUnmount(() => store.abort())
```

- [ ] **Step 6: ChatView 打字机渲染测试（增量更新可见）**

在 `ChatView.spec.ts` 增（mock store.send 期间 onDelta 逐步改 messages，断言 DOM 增量）。最小断言：发送后助手气泡文本随 store.messages 变化更新、`sending` 为真时输入禁用。沿用该文件既有挂载与 mock 模式：

```ts
it('助手气泡随 store 增量更新（打字机）', async () => {
  const wrapper = mountChatView() // 复用本文件既有挂载工具
  const store = useConversationStore()
  store.messages.push({ id: 'a', role: 'assistant', content: '你好', promptTokens: null, completionTokens: null, createTime: '' })
  await nextTick()
  expect(wrapper.findAll('[data-test="msg"]').at(-1)!.text()).toContain('你好')
  store.messages[store.messages.length - 1].content += '世界'
  await nextTick()
  expect(wrapper.findAll('[data-test="msg"]').at(-1)!.text()).toContain('你好世界')
})
```

- [ ] **Step 7: 运行前端相关测试，确认通过**

Run: `cd web && pnpm vitest run src/stores/__tests__/conversation.spec.ts src/views/conversation/__tests__/ChatView.spec.ts`
Expected: all passed。

- [ ] **Step 8: 提交**

```bash
cd web && git add src/stores/conversation.ts src/views/conversation/ChatView.vue src/stores/__tests__/conversation.spec.ts src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 会话 store 改流式发送（打字机增量+错误内联+切会话止血）"
```

---

## Task 6: 前端 — 清理 125s workaround 死代码

**Files:**
- Modify: `web/src/api/conversation.ts`
- Modify: `web/src/config/index.ts`
- Test: `web/src/api/__tests__/conversation.spec.ts`

**Interfaces:**
- Removes: `sendMessage(...)`、`config.chatApiTimeout`、`SendMessageResponse` 的前端用途（类型可留作对外 API 轮复用）。后端 `POST /messages` 端点保留不动。

- [ ] **Step 1: 删 api 层 `sendMessage` 与其测试用例**

`web/src/api/conversation.ts`：删除 `sendMessage` 函数与不再需要的 `import { config }`；保留 `getMessages`/`listConversations`（仍用 `request`）。
`web/src/api/__tests__/conversation.spec.ts`：删两条 `sendMessage` 用例与 `import { config }`、`import { sendMessage }`；保留 `getMessages`/`listConversations` 两条。

- [ ] **Step 2: 删 `config.chatApiTimeout`**

`web/src/config/index.ts`：删除 `chatApiTimeout` 行与其上方注释（SSE 不走 axios，不需要独立长超时）。

- [ ] **Step 3: 运行测试 + 类型检查 + 构建，确认无残留引用**

Run:
```bash
cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts && pnpm type-check && pnpm build
```
Expected: 测试通过；type-check 无 `chatApiTimeout`/`sendMessage` 未定义引用报错；build 成功。

- [ ] **Step 4: 全量前端测试**

Run: `cd web && pnpm vitest run`
Expected: all passed（含既有 138 条基线，删 2 条 + 新增若干）。

- [ ] **Step 5: 提交**

```bash
cd web && git add src/api/conversation.ts src/config/index.ts src/api/__tests__/conversation.spec.ts
git commit -m "chore(web): SSE 落地后移除 sendMessage 同步端点封装与 chatApiTimeout 125s workaround"
```

---

## 收尾（实现完成后，非本计划任务步骤）

- 真实模型流式手验，按 spec §8 把 4 个风险点逐一实测，结果追加 `docs/self-check-conversation-sse.md`：
  ① 供应商是否回 usage（OpenAI `include_usage` / Anthropic message_delta）；② 三层超时不误杀长生成；
  ③ 断开确实取消上游、不泄漏订阅；④ nginx `proxy_buffering off` 生效、打字机不被缓冲。
- 全量回归：`cd server && ./mvnw test`（看 Surefire 计数）、`cd web && pnpm vitest run && pnpm type-check && pnpm build`。
- 按 `superpowers:finishing-a-development-branch` 决定合并方式。

## 延后项（不在本计划）

停止/重新生成按钮；D2 孤儿会话清理；usage 配额（`QuotaGuard.check` 仍空锚点）；E2E 基建。
