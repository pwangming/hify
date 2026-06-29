# ⑤ conversation SSE 流式输出 — 设计

> 轮次：conversation 模块第 5 轮（单轮③ → 多轮④ → **流式⑤** → usage 配额 → …）。
> 前置状态：④ 多轮记忆已合并 main（28e8be2），后端 256 绿 / 前端 138 绿。
> 必读规范：api-standards §3.3（SSE）、frontend-standards §3.4（SSE）/§5.9（优先 Element Plus）、
> llm-resilience.md §1/§2/§3（流式线程/超时/重试）、code-organization.md、testing-standards.md。

## 1. 目标与背景

当前"发消息"是同步阻塞：`ChatInvoker.invoke()` 用 `.call().chatResponse()` 一次性返回整段答案。
前端为防慢模型被截断，给 axios 版 `sendMessage` 加了 125s 独立超时（`config.chatApiTimeout`）——
这是 workaround，SSE 流式才是正解。

本轮把"发消息"从一次性 `.call()` 升级成流式 `.stream()`：模型逐 token 吐字，前端打字机增量渲染。
**事务边界铁律不变**（事务A 落库 → LLM 无事务 → 事务B 落库），**已发布契约只增不改**（17xxx 段、成员族路由）。

不在本轮：usage 配额（QuotaGuard 仍空锚点）、E2E 基建、停止/重新生成按钮、D2 孤儿清理。

## 2. 决策记录（brainstorm 拍板）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 流式韧性深度 | **够用子集**：Bulkhead + 三层超时（首 token 30s / token 间隔 60s / 总 10min）+ CircuitBreaker；**不做 Retry**（吐字后重试会内容错乱，§3）。砍掉"首 token 前可重试"这个低收益边角。 |
| 2 | 端点形态 | **新增** `POST /messages/stream`（SSE）；现有 `POST /messages`(JSON)、`GET /messages`、`GET /conversations` 全部保留不动。 |
| 3 | 失败/断开落库 | **半截不落**（不污染多轮窗口）；客户端断开→取消上游 Flux **止血**（停止烧 token）；只有提问的**孤儿会话保持现状（D2 延后）**。本轮不引入跨事务删除。 |
| 4 | token/usage 来源 | 拼接 `message` 增量得全文，流正常结束时 `appendAssistant(全文)`；usage 取流中最后一个非空值，**取不到记 0**（同非流式兜底）。 |
| 5 | 前端交互范围 | **最小档**：打字机增量 + sending 禁输入 + 错误内联显示。**无**停止/重新生成按钮。 |
| 6 | workaround 收尾 | **删**前端 `sendMessage` 包装 + `config.chatApiTimeout` + 对应测试；后端 `POST /messages` 端点保留。 |

## 3. 后端编排与时序（核心）

新增 `ConversationService.sendStream(appId, conversationId, content, current)` → 返回 `Flux<StreamEvent>`。

`StreamEvent` 为 conversation 内部密封类型（仅 service↔controller，不跨模块）：
- `Delta(String text)` — 一段增量正文
- `Done(Long messageId, int promptTokens, int completionTokens)` — 落库完成的终态

时序（与现有同步 `send` 同骨架，第 5 步起改流式）：

```
1) quotaGuard.check(userId, appId)                         // 同步锚点，本轮放行
2) appFacade.findRunnableChatApp(appId)                    // 读，无事务；空→17001
3) store.openTurn(appId, cid, userId, content)             // 事务A：建/取会话+落user+读窗口
4) providerFacade.getChatClient(app.modelId())             // 取带韧性的 ChatClient
5) 构造并返回 Flux<StreamEvent>：
     chatInvoker.invokeStream(client, sysPrompt, window)   // Flux<ChatResponse>，真实流，无事务
       .doOnNext( 累加 StringBuilder + 记最后非空 usage )   // 副作用累积
       .mapNotNull( 取 delta 文本 → Delta 事件 )            // 空 delta 块跳过
     .concatWith( Mono.fromCallable {
         Message m = store.appendAssistant(全文, pTok, cTok) // 事务B：仅在 delta 流正常完成后才执行
         → Done(m.id, pTok, cTok)
     } )
```

**铁律保持**：delta 流（LLM 真实 IO）不被任何事务包裹；落库在尾部 `Mono.fromCallable`，只有 delta
流正常 `onComplete` 后才执行（`concatWith` 语义）。流报错或被取消 → 尾部 Mono 不执行 → **半截天然不落**。
`ConversationService` 仍**无 `@Transactional`**；所有事务收口在 `ConversationStore`（`openTurn`/`appendAssistant`）。

`ChatInvoker.invokeStream(client, systemPrompt, window)`：薄适配，复用既有 `toMessages()`（纯映射，已单测），
返回 `client.prompt().messages(...).stream().chatResponse()` 即 `Flux<ChatResponse>`。真实 IO，不单测（同 `invoke`，进手验单）。

### Controller

新增 `POST /api/v1/conversation/messages/stream`，`produces=MediaType.TEXT_EVENT_STREAM_VALUE`，返回 `SseEmitter`。
协议层职责（无业务逻辑、无 `@Transactional`）：

```
SseEmitter emitter = new SseEmitter(总时长上限ms);     // 兜底超时
Disposable sub = service.sendStream(...)
  .subscribe(
     evt  -> emitter.send( evt instanceof Delta ? event("message",{delta}) : event("done",{messageId,usage}) ),
     err  -> { emitter.send(event("error", Result失败结构)); emitter.complete(); },  // §3.3：连接后错误走 error 事件再关流
     ()   -> emitter.complete()
  );
emitter.onCompletion(sub::dispose);   // 客户端正常关闭
emitter.onTimeout(()  -> { sub.dispose(); emitter.complete(); });
emitter.onError(t      -> sub.dispose());     // 断开止血：取消上游→取消底层 HTTP 流→停烧 token
// 15s 心跳：merge 一个 Flux.interval(15s) 发 ": ping" 注释行保活（§3.3）
```

`error` 事件的 data 即 `Result` 失败结构（同 `code/message/traceId`），由全局异常→`BizException` 复用。

## 4. provider 韧性层改动（够用子集）

`ResilientChatModel` 现仅实现 `call()`（四件套）；本轮 **override `stream(Prompt)`**：

```
delegate.stream(prompt)                              // 接上真实流式（现走默认实现，未加韧性）
  .transformDeferred(BulkheadOperator.of(bulkhead))  // 订阅占名额、终止释放；与非流式共用同一信号量池
  .timeout(首token Mono.delay(first), item -> Mono.delay(gap))   // Reactor 双参 timeout：首token + 逐项间隔
  .timeout(Mono.delay(总时长))                        // 总时长兜底（防节点级泄漏）
  .transformDeferred(CircuitBreakerOperator.of(cb))  // 成/败计入同一熔断窗口（与 call 共用）
  // 不挂 Retry —— 吐字后中断不自动重试（§3），失败交前端处理
  .onErrorMap(ResilienceExceptions::toBizException)  // 归一为 BizException（12002 等）
```

- 依赖：`resilience4j-all`（含 reactor 模块，已在 pom）提供 `BulkheadOperator`/`CircuitBreakerOperator`。
- 超时值复用 `model_provider` 已有字段：`first_token_timeout_sec`(30) / `token_gap_timeout_sec`(60) /
  `stream_max_duration_sec`(600)。`ResilienceBundle` 需暴露这三个 Duration（或新增流式专用读取）。
- `ResilienceExceptions::isProviderFault`/`isRetryable` 谓词复用，熔断按 provider fault 计数。
- `ProviderFacade.getChatClient` 签名/返回不变（已返回此 client），仅更新 javadoc："现含流式韧性"。

> 待实测风险（进手验单）：Reactor 双参 `timeout` 与总时长兜底的算子组合，需确认**不误杀正常的长生成**
> （总时长仅作硬上限，首/间隔超时只在"卡住不吐字"时触发）。

## 5. 端点与事件契约（只增不改）

- 路由：成员族 `POST /api/v1/conversation/messages/stream`。
- SSE 四事件（api-standards §3.3），本轮无 Agent 不发 `tool_call`：

```
event: message
data: {"delta":"你好，"}

event: error                       // 连接后出错：data 即 Result 失败结构
data: {"code":12002,"message":"模型供应商调用超时","traceId":"..."}

event: done                        // 正常结束
data: {"messageId":"98","usage":{"promptTokens":"320","completionTokens":"180"}}
```

- **Long/token 全序列化为 string**（messageId、token 数）。
- 连接建立**前**错误（app 不可用 17001、未认证 10002、未来配额 14001）走普通 JSON 信封；
  连接建立**后**错误（模型超时/熔断 12002 等）走 `error` 事件后关流。
- **错误码 17xxx 段不新增**；模型侧错误由 provider 12002/12003/12004 透传。
- nginx `proxy_buffering off`（deployment.md 已约定）；server 每 15s 发 `: ping`。

## 6. 前端（最小档 + 收尾）

- 新增 `web/src/composables/useChatStream.ts`：**fetch + ReadableStream**（frontend-standards §3.4，
  非 `EventSource`——EventSource 只支持 GET、不能带 Authorization 头）。解析四事件；暴露 `abort()` 供止血。
  `error` 事件复用 `ApiError`。SSE 不走 axios，不受全局超时限制。
- `web/src/stores/conversation.ts` 的 `send` 改流式：
  推用户气泡 → 推一个**空 assistant 占位气泡** → 每个 `message` 增量 append 到占位气泡（打字机）→
  `done` 用真 `messageId`+usage 替换占位 → `error` 在占位气泡位置内联显示错误文案。
  `sending` 全程 true，`done`/`error` 置 false。切会话/组件卸载时 `abort()` 止血。
- `web/src/views/conversation/ChatView.vue`：渲染照旧（`white-space: pre-wrap` 已支持增量追加）；
  **无**停止/重新生成按钮。继续遵守 §5.9 优先 Element Plus。
- **收尾**：删 `web/src/api/conversation.ts` 的 `sendMessage`、`web/src/config` 的 `chatApiTimeout`、
  以及对应测试（死代码，SSE 是正解）；后端 `POST /messages` 端点保留。

## 7. 测试计划

**后端**（mock + ArchUnit/Modulith，连库测试仍延后）：
- `ConversationServiceTest.sendStream`：mock `chatInvoker.invokeStream` 返回 `Flux.just(假ChatResponse…)`，
  StepVerifier 断言事件序列 `Delta×N → Done(含 messageId)`；验 `store.appendAssistant` 被调用一次、
  文本=增量拼接、usage 取最后非空；验流报错时 `appendAssistant` **不被调用**（半截不落）。
- `ResilientChatModelTest.stream`：用延迟 Flux 验首 token 超时 / token 间隔超时触发为 `BizException`；
  验 Bulkhead/CircuitBreaker 算子接入（成/败计数）。
- `ConversationControllerTest`：MockMvc async 验 `text/event-stream`、四事件帧、错误走 `error` 事件。
- `invokeStream` 真实 IO 不单测（同 `invoke`，进收尾手验单）。
- ArchUnit/Modulith 模块边界测试保持绿（`StreamEvent` 为 conversation 内部类型，不跨模块）。
- 可能新增 test 依赖 `reactor-test`（StepVerifier）——计划阶段确认。

**前端**（vitest，测试放 `__tests__/`）：
- `useChatStream.spec.ts`：mock `ReadableStream` 喂分块字节，断言解析出 message/done/error；`abort()` 取消。
- `stores/conversation.spec.ts`：流式 `send` 增量追加占位气泡、done 替换、error 内联。
- `ChatView.spec.ts`：打字机增量渲染、sending 禁输入。
- 删除 `api/__tests__/conversation.spec.ts` 中 `sendMessage` 相关用例。

## 8. 延后项与风险

**延后**：停止/重新生成按钮；D2 孤儿会话清理（SSE 已消除"客户端超时 abort"这一主因，真·失败孤儿留后续轮）；
usage 配额（QuotaGuard 空锚点）；E2E 基建。

**收尾手验风险点**（追加到 `docs/self-check-conversation-sse.md`）：
1. 供应商流式不一定回 usage（OpenAI 需 `include_usage`、Anthropic 走 message_delta）→ 取不到记 0，需实测。
2. Reactor 双参 timeout + 总时长兜底组合，实测**不误杀长生成**。
3. SseEmitter 与 Reactor 订阅在虚拟线程下的取消/生命周期（断开止血实际生效、不泄漏订阅）。
4. nginx `proxy_buffering off` 实际生效（否则 SSE 被缓冲、打字机失效）。
