# 修缮轮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清偿三项欠账——provider BaseURL 拼接通用化（任意版本前缀网关可接入）、D2 孤儿会话两口子收尾（同步端点失败清理 + SSE meta 事件）、ArchUnit 规则归位（不再误伤测试类）。

**Architecture:** 无新表无新依赖。provider 侧 `ChatClientFactory` 显式设 OpenAI 资源路径并翻转 baseUrl 约定（V19 迁移存量补 `/v1`）；conversation 侧同步端点复用既有 `cleanupFailedTurn`、SSE 在首个 delta 前新增 `meta` 事件（只增不改）；测试侧 `LayerRulesTest` 排除测试类后收拢 K4 两处绕行。spec：`docs/superpowers/specs/2026-07-07-repair-round-design.md`。

**Tech Stack:** Spring AI 1.0.1（`OpenAiApi.Builder.completionsPath/embeddingsPath`，已用 javap 核实存在）+ Reactor（Flux.concat 前插 meta）+ Vue 3 + vitest。

## Global Constraints

- 起点：main（含 K4，`c3179ed` 之后），新建分支 `fix/repair-round`，每 Task 结尾提交一次。
- TDD 先红后绿；**mvn 判定看退出码/失败摘要，禁止 `grep BUILD SUCCESS`**。后端 `cd /home/wang/playlab/hify/server && mvn test -Dtest=<类名>`；前端 `cd /home/wang/playlab/hify/web && pnpm test <文件路径>`。
- 连库测试（继承 `PgIntegrationTest`）需本机 Docker 运行中；环境已按 testing-standards.md 第四节持久化配置，**裸命令即可跑**。
- Long 一律序列化为 string（全局 Jackson，SSE payload 同样生效，前端断言按 string 写）。
- 错误码零新增；SSE 事件只增不改（新增 `meta`，`message/done/error` 不动）。
- 不改旧迁移，只新增 V19；不引任何新依赖。
- Task 6（挪包）后必须 `mvn clean test`（K4 偏差 4 教训：target 旧 class 残留致 ArchUnit 误报）。

## File Map（全轮改动一览）

| 项 | 新建 | 修改 |
|---|---|---|
| ① BaseURL | `V19__provider_base_url_add_version_segment.sql` | `provider/service/ChatClientFactory.java`、`docs/architecture/llm-resilience.md` §6.1、`web/src/views/admin/provider/ProviderList.vue`、测试 `ChatClientFactoryTest.java` |
| ② D2 | — | `conversation/service/ConversationService.java`、`service/StreamEvent.java`、`dto/StreamPayloads.java`、`controller/ConversationController.java`、`web/src/composables/useChatStream.ts`、`web/src/stores/conversation.ts` + 对应测试 |
| ③ ArchUnit | — | `test/.../LayerRulesTest.java`、`test/.../support/PgIntegrationTest.java`；删 `test/.../support/service/TransactionalPgIntegrationTest.java`；挪 `KbChunkRetrievalTest.java` → `knowledge/mapper/` |

---

### Task 0: 建分支

- [ ] **Step 1:**

```bash
cd /home/wang/playlab/hify && git checkout main && git checkout -b fix/repair-round
```

---

### Task 1: ChatClientFactory 显式路径 + baseUrl 归一化

**Files:**
- Modify: `server/src/main/java/com/hify/provider/service/ChatClientFactory.java`
- Test: `server/src/test/java/com/hify/provider/service/ChatClientFactoryTest.java`（追加）

**Interfaces:**
- Consumes: 既有 `ChatClientFactory` 结构（cipher/noRetryTemplate 构造、`openAi`/`buildEmbeddingModel`）。
- Produces: `static String normalizeBaseUrl(String)`（包级）、`OpenAiApi buildOpenAiApi(ModelProvider p, String apiKey)`（包级，openai 协议统一装配入口）。Task 2 的迁移与文档、手动验收依赖本 Task 的新约定生效。

- [ ] **Step 1: 写失败测试（ChatClientFactoryTest 追加）**

import 区补 `org.springframework.ai.openai.api.OpenAiApi`。类内追加：

```java
    @Test
    void normalizeBaseUrl_去尾部斜杠_单个多个与首尾空白() {
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1"));
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1/"));
        assertEquals("https://a.com/v1", ChatClientFactory.normalizeBaseUrl("https://a.com/v1//"));
        assertEquals("https://a.com/v1beta/openai",
                ChatClientFactory.normalizeBaseUrl(" https://a.com/v1beta/openai/ "));
    }

    @Test
    void buildOpenAiApi_显式资源路径_任意版本前缀基址可接入() throws Exception {
        ModelProvider p = provider("openai");
        p.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3/");
        OpenAiApi api = factory.buildOpenAiApi(p, "sk-x");
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", baseUrlOf(api));
        assertEquals("/chat/completions", stringField(api, "completionsPath"));
        assertEquals("/embeddings", stringField(api, "embeddingsPath"));
    }

    // Spring AI 1.0.1 的 getter/字段为包私有——反射断言装配结果；升级 Spring AI 若更名，此测试显式红。
    private static String stringField(OpenAiApi api, String name) throws Exception {
        java.lang.reflect.Field f = OpenAiApi.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(api);
    }

    private static String baseUrlOf(OpenAiApi api) throws Exception {
        java.lang.reflect.Method m = OpenAiApi.class.getDeclaredMethod("getBaseUrl");
        m.setAccessible(true);
        return (String) m.invoke(api);
    }
```

- [ ] **Step 2: 运行确认失败（编译错：normalizeBaseUrl / buildOpenAiApi 不存在）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=ChatClientFactoryTest`
Expected: FAIL（compilation error）

- [ ] **Step 3: 写实现（ChatClientFactory）**

追加两个方法；`openAi(...)` 与 `buildEmbeddingModel(...)` 里的 `OpenAiApi.builder().baseUrl(...).apiKey(apiKey).build()` 均改为调 `buildOpenAiApi(p, apiKey)` / `buildOpenAiApi(provider, apiKey)`：

```java
    /** baseUrl 归一化：仅去首尾空白与尾部斜杠（spec 决策 2：不做更多防呆，填错由试连接暴露真实错误）。 */
    static String normalizeBaseUrl(String baseUrl) {
        String s = baseUrl.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * openai 协议统一装配（包级可见供测试断言）：显式资源路径 + 完整基址约定（修缮轮拍板，
     * llm-resilience.md §6.1）。baseUrl 照抄厂商文档完整基址（含版本段），此处只拼
     * /chat/completions 与 /embeddings——任意版本前缀（/v1、/api/v3、/api/paas/v4…）的网关均可接入。
     */
    OpenAiApi buildOpenAiApi(ModelProvider p, String apiKey) {
        return OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(p.getBaseUrl()))
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .build();
    }
```

- [ ] **Step 4: 运行测试通过 + provider 模块回归**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ChatClientFactoryTest,ModelConnectionServiceTest,ProviderFacadeImplTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src
git commit -m "fix(provider): OpenAI 兼容协议显式资源路径+baseUrl 归一化，任意版本前缀网关可接入（TDD）"
```

---

### Task 2: V19 迁移 + 文档 §6.1 重写 + 前端提示行

**Files:**
- Create: `server/src/main/resources/db/migration/V19__provider_base_url_add_version_segment.sql`
- Modify: `docs/architecture/llm-resilience.md`（§6.1）
- Modify: `web/src/views/admin/provider/ProviderList.vue`（提示行 + 样式）

**Interfaces:**
- Consumes: Task 1 的新约定（显式路径已生效，存量「不带版本段」的 baseUrl 若不迁移会 404）。
- Produces: 库中存量 openai 供应商 baseUrl 已补 `/v1`；文档与表单提示与新约定一致。

- [ ] **Step 1: 写迁移 V19**

`server/src/main/resources/db/migration/V19__provider_base_url_add_version_segment.sql`：

```sql
-- V19：baseUrl 约定翻转（修缮轮拍板，llm-resilience.md §6.1）。
-- openai 协议 baseUrl 从「不带版本段」改为「照抄厂商文档完整基址（含版本段）」，
-- ChatClientFactory 显式拼 /chat/completions、/embeddings，不再由框架默认拼 /v1/...。
-- 存量按旧约定填的行补回 /v1（deepseek、千问 compatible-mode 网关均适用）；not like 守卫防重复补。
update model_provider
set base_url    = rtrim(base_url, '/') || '/v1',
    update_time = now()
where protocol = 'openai'
  and rtrim(base_url, '/') not like '%/v1';
```

- [ ] **Step 2: 连库跑通迁移链（V1..V19 全量 Flyway 真验）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=AppDatasetRelSchemaTest`
Expected: PASS（启动日志含 V19 应用；迁移语法错会直接起不来）

- [ ] **Step 3: 重写 llm-resilience.md §6.1**

将 §6.1 整节（从 `### 6.1` 到 `### 6.2` 之前）替换为：

```markdown
### 6.1 baseUrl 填法（openai 协议）

`ChatClientFactory` 显式指定 `completionsPath("/chat/completions")` / `embeddingsPath("/embeddings")`
（修缮轮拍板，2026-07-07）。**baseUrl 照抄厂商文档给的完整基址（含版本段）**，平台在其后只拼资源
路径，任意版本前缀的网关均可接入，零厂商代码：

| 厂商 | baseUrl 填法 |
|---|---|
| OpenAI / DeepSeek / Moonshot / 腾讯混元 / SiliconFlow / xAI / Ollama·vLLM 本地 | `https://.../v1` |
| 阿里百炼（compatible-mode 网关） | `https://<网关>/compatible-mode/v1` |
| 火山方舟 Ark | `https://ark.cn-beijing.volces.com/api/v3` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` |
| 百度千帆 v2 | `https://qianfan.baidubce.com/v2` |
| Google Gemini（OpenAI 兼容层） | `https://generativelanguage.googleapis.com/v1beta/openai` |

尾部斜杠自动去除；不做其他防呆——填错的症状是试连接报 404/401，看
`model_provider.last_test_error` 与后端「试连接失败」WARN 的 cause 链定位。
存量数据已由 V19 迁移补回版本段。

**anthropic 协议不同**：随 Anthropic 生态惯例填「不带版本段」基址
（如 `https://api.anthropic.com`），SDK 自动拼 `/v1/messages`。
```

- [ ] **Step 4: 前端提示行（ProviderList.vue）**

Base URL 表单项改为（placeholder 换成真实示例 + 提示行）：

```vue
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" data-test="form-baseurl" placeholder="https://api.deepseek.com/v1" />
          <div class="provider-list__hint">
            照抄厂商文档的完整基址（含版本段），如 https://api.deepseek.com/v1、https://ark.cn-beijing.volces.com/api/v3
          </div>
        </el-form-item>
```

style 区追加（照 AppList 的 `app-list__hint` 同款）：

```scss
.provider-list__hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
  margin-top: 4px;
}
```

- [ ] **Step 5: 前端回归（静态文案不加测试，靠既有用例不红 + 类型检查）**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderList.spec.ts && pnpm build`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/resources/db/migration/ docs/architecture/llm-resilience.md web/src
git commit -m "fix(provider): V19 存量 baseUrl 补版本段 + §6.1 填法表重写 + 表单提示（约定翻转配套）"
```

---

### Task 3: 同步端点失败清理（对齐流式语义）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（send 方法）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（改 2 个 + 增 2 个）

**Interfaces:**
- Consumes: 既有 `ConversationStore.cleanupFailedTurn(Long conversationId, Long userMessageId, boolean newConversation)`、`TurnContext(Long conversationId, List<Message> window, Long userMessageId, boolean newConversation)`。
- Produces: 无新接口；`send` 行为增强——openTurn 之后任何失败先清孤儿再原样抛错。

- [ ] **Step 1: 改/增测试（先红）**

改既有 `send_模型不可用_透传12002_user消息已落但不落assistant`——重命名为 `send_模型不可用_透传12002_清理孤儿turn`，断言从「user 消息已落」翻转为「已清理」：

```java
    @Test
    void send_模型不可用_透传12002_清理孤儿turn() {
        when(appFacade.findRunnableChatApp(eq(7L)))
                .thenReturn(Optional.of(new AppRuntimeView(7L, 5L, null, List.of())));
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(providerFacade.getChatClient(eq(5L)))
                .thenThrow(new BizException(ProviderError.MODEL_NOT_USABLE));

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(store).cleanupFailedTurn(100L, 300L, true); // 新会话：删会话+user消息，与流式语义一致
        verify(store, never()).appendAssistant(any(), any(), anyInt(), anyInt(), anyLong(), anyLong(), anyLong());
    }
```

改既有 `send_模型调用故障_透传12003_不落assistant`——末尾追加一行断言：

```java
        verify(store).cleanupFailedTurn(100L, 300L, true);
```

新增两个用例：

```java
    @Test
    void send_续聊失败_只删user消息不删会话() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("继续")), 300L, false));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        assertThrows(BizException.class, () -> service.send(7L, 100L, "继续", member));
        verify(store).cleanupFailedTurn(100L, 300L, false);
    }

    @Test
    void send_清理孤儿失败_不吞原始异常() {
        stubRunnableApp(null);
        when(store.openTurn(any(), any(), any(), any()))
                .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
        when(chatInvoker.invoke(any(), any(), any()))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));
        doThrow(new RuntimeException("db down")).when(store).cleanupFailedTurn(any(), any(), anyBoolean());

        BizException ex = assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));
        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode()); // 原始错误必须原样透传
    }
```

（`doThrow` 走 `org.mockito.Mockito.doThrow` 静态导入，本文件若未导入则补。）

- [ ] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=ConversationServiceTest`
Expected: FAIL（cleanupFailedTurn 未被调用，4 个新旧用例红）

- [ ] **Step 3: 写实现（send 方法）**

`send` 中 openTurn 之后的四步包进 try-catch（catch RuntimeException——链路上无受检异常；清理自身失败仅记 warn 不得盖住原始异常，与流式 onErrorResume 同款语义）：

```java
        // 3) 事务A：建/取会话 + 落 user 消息 + 读窗口
        TurnContext turn = store.openTurn(appId, conversationId, current.userId(), content);
        Long cid = turn.conversationId();
        try {
            // 4) 取 ChatClient（不可用抛 12002）并调用——事务外，喂历史窗口
            ChatClient chatClient = providerFacade.getChatClient(app.modelId());
            String effectivePrompt = augmentWithKnowledge(app, content);
            LlmReply reply = chatInvoker.invoke(chatClient, effectivePrompt, turn.window());
            // 5) 事务B：落 assistant 消息（同事务内发 TokenUsedEvent 计量）
            Message saved = store.appendAssistant(cid, reply.content(), reply.promptTokens(), reply.completionTokens(),
                    current.userId(), appId, app.modelId());
            return new SendMessageResponse(cid, toView(saved));
        } catch (RuntimeException e) {
            // 修缮轮 D2：失败清孤儿（新会话删会话+user消息；续聊只删user消息），与 sendStream 语义对齐
            try {
                store.cleanupFailedTurn(cid, turn.userMessageId(), turn.newConversation());
            } catch (RuntimeException cleanupEx) {
                log.warn("同步端点孤儿清理失败 conversationId={}", cid, cleanupEx);
            }
            throw e;
        }
```

- [ ] **Step 4: 运行测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=ConversationServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src
git commit -m "fix(conversation): 同步端点失败清理孤儿 turn，与流式语义对齐（D2 其一，TDD）"
```

---

### Task 4: SSE 开场 meta 事件（后端）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/StreamEvent.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（sendStream）
- Modify: `server/src/main/java/com/hify/conversation/dto/StreamPayloads.java`
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`（toSse）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（5 个流式用例插断言）
- Test: `server/src/test/java/com/hify/conversation/controller/ConversationControllerTest.java`（事件断言追加）

**Interfaces:**
- Consumes: Task 3 后的 `ConversationService`。
- Produces: `StreamEvent.Meta(Long conversationId)`；SSE 线上新增 `event: meta`、`data: {"conversationId":"100"}`（Long→string 由全局 Jackson 保证）。Task 5 前端依赖此事件名与载荷字段。

- [ ] **Step 1: 改测试（先红）**

`ConversationServiceTest` 5 个流式用例（`sendStream_增量吐字_结束落全文与usage_发Done`、`sendStream_流报错_半截不落assistant`、`sendStream_新会话_流报错_清理孤儿并抛错且不落assistant`、`sendStream_续聊_流报错_只删消息不删会话`、`sendStream_成功完成_不清理孤儿`）：在各自 `StepVerifier.create(...)` 之后紧跟的第一个 `.expectNext(...)` / `.expectNextMatches(...)` 之前插入一行：

```java
                .expectNext(new StreamEvent.Meta(100L))
```

`ConversationControllerTest` 的 `流式发消息_输出message与done事件` 断言链追加：

```java
                .contains("event:meta")
```

- [ ] **Step 2: 运行确认失败（编译错：StreamEvent.Meta 不存在）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ConversationServiceTest,ConversationControllerTest'`
Expected: FAIL（compilation error）

- [ ] **Step 3: 写实现**

`StreamEvent.java`（permits 补 Meta）：

```java
/** 流式编排的内部事件（仅 service↔controller，不跨模块）。 */
public sealed interface StreamEvent permits StreamEvent.Meta, StreamEvent.Delta, StreamEvent.Done {

    /** 开场元信息（修缮轮 D2 其二）：首个 delta 前发出，前端立即拿到会话 id——断网重发进同一会话。 */
    record Meta(Long conversationId) implements StreamEvent {}

    /** 一段增量正文。 */
    record Delta(String text) implements StreamEvent {}

    /** 流正常结束、assistant 已落库的终态（含新会话 id 供前端写回 URL）。 */
    record Done(Long conversationId, Long messageId, int promptTokens, int completionTokens)
            implements StreamEvent {}
}
```

`ConversationService.sendStream` 的 return 改为（meta 在订阅即刻发出，先于 LLM 首包；onErrorResume 链原样保留）：

```java
        return Flux.concat(Mono.<StreamEvent>just(new StreamEvent.Meta(cid)), deltas, done)
                // 真失败(onError)即清理孤儿；取消(用户切会话)是 cancel 信号、不进 onErrorResume，故不会误删。
                // 清理是阻塞 JDBC，放 boundedElastic。
                .onErrorResume(err -> Mono.fromRunnable(() ->
                                store.cleanupFailedTurn(turn.conversationId(), turn.userMessageId(), turn.newConversation()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(cleanupEx -> Mono.empty())   // 清理失败不可盖住原始 LLM 错误
                        .then(Mono.<StreamEvent>error(err)));
```

`StreamPayloads.java` 追加：

```java
    public record Meta(Long conversationId) {}
```

`ConversationController.toSse` 开头追加分支：

```java
        if (e instanceof StreamEvent.Meta m) {
            return sse("meta", new StreamPayloads.Meta(m.conversationId()));
        }
```

- [ ] **Step 4: 运行测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ConversationServiceTest,ConversationControllerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src
git commit -m "feat(conversation): SSE 开场 meta 事件带会话 id（D2 其二后端，事件只增不改，TDD）"
```

---

### Task 5: 前端 meta 处理（useChatStream + store）

**Files:**
- Modify: `web/src/composables/useChatStream.ts`
- Modify: `web/src/stores/conversation.ts`（send 内 handlers）
- Test: `web/src/composables/__tests__/useChatStream.spec.ts`（追加）
- Test: `web/src/stores/__tests__/conversation.spec.ts`（追加）

**Interfaces:**
- Consumes: Task 4 的 `event: meta` / `payload.conversationId`（string）。
- Produces: `ChatStreamHandlers.onMeta?: (conversationId: string) => void`（可选——既有调用点与测试 mock 不必全改）。

- [ ] **Step 1: 写失败测试**

`useChatStream.spec.ts` 追加（沿用文件内 `sseResponse` 辅助）：

```ts
  it('meta → onMeta 先于 done 到达', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:meta\ndata:{"conversationId":"100"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":1,"completionTokens":1}}\n\n',
    ])))
    const order: string[] = []
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onMeta: (cid) => order.push(`meta:${cid}`),
      onDelta: () => {},
      onDone: (cid) => order.push(`done:${cid}`),
      onError: () => {},
    })
    expect(order).toEqual(['meta:100', 'done:100'])
  })

  it('未注册 onMeta 时 meta 事件静默忽略不炸', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:meta\ndata:{"conversationId":"100"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":1,"completionTokens":1}}\n\n',
    ])))
    let doneCid = ''
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onDelta: () => {}, onDone: (cid) => { doneCid = cid }, onError: () => {},
    })
    expect(doneCid).toBe('100')
  })
```

`conversation.spec.ts` 追加（照本文件既有 `useChatStream` mock 模式——start 的第 4 参是 handlers）：

```ts
  it('meta 先到即记 currentId——断流重发不再新建会话', async () => {
    const start = vi.fn(async (_a: unknown, _c: unknown, _t: unknown,
        h: { onMeta?: (cid: string) => void; onError: (e: { code: number; message: string }) => void }) => {
      h.onMeta?.('100')                       // meta 已到
      h.onError({ code: -1, message: '网络异常' }) // 随后断流
    })
    // ↓ 本文件既有 mock 注入方式照抄（vi.mocked(useChatStream).mockReturnValue({ start, abort: vi.fn() }) 或同款）
    setChatMock(start)
    const store = useConversationStore()
    await store.send('7', '你好')
    expect(store.currentId).toBe('100') // 虽然失败，会话 id 已记住——重发走续聊
  })
```

（`setChatMock` 指本文件既有的 mock 注入辅助，实名以文件现状为准；若是内联 `vi.mock` 工厂则照既有用例改写注入，断言目标不变。）

- [ ] **Step 2: 运行确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/composables/__tests__/useChatStream.spec.ts src/stores/__tests__/conversation.spec.ts`
Expected: FAIL（onMeta 类型不存在 / meta 事件未分发 / currentId 仍 null）

- [ ] **Step 3: 写实现**

`useChatStream.ts`——`ChatStreamHandlers` 追加可选成员：

```ts
export interface ChatStreamHandlers {
  /** 开场元信息：后端 openTurn 一落库即推送会话 id（先于任何增量）；断网重发据此走续聊不重复建会话 */
  onMeta?: (conversationId: string) => void
  onDelta: (text: string) => void
  onDone: (conversationId: string, messageId: string,
           usage: { promptTokens: number; completionTokens: number }) => void
  onError: (err: { code: number; message: string }) => void
}
```

`dispatch` 的事件分支追加（放 `message` 分支之前或之后均可，保持 else-if 链）：

```ts
    else if (event === 'meta') h.onMeta?.(payload.conversationId)
```

`stores/conversation.ts`——`chat.start(appId, currentId.value, content, {...})` 的 handlers 对象里、`onDelta` 之前追加：

```ts
        onMeta: (conversationId) => {
          // 新会话开场即记 id：此后哪怕断流，重发也走续聊（D2 断网重复建会话的根治）
          if (currentId.value === null) currentId.value = conversationId
        },
```

- [ ] **Step 4: 运行测试通过 + 前端全量回归**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm build`
Expected: 全绿 + 类型检查通过

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src
git commit -m "feat(web): SSE meta 事件即记会话 id，断流重发不再重复建会话（D2 其二前端，TDD）"
```

---

### Task 6: ArchUnit 规则归位（纯测试侧）

**Files:**
- Modify: `server/src/test/java/com/hify/LayerRulesTest.java`
- Modify: `server/src/test/java/com/hify/support/PgIntegrationTest.java`
- Delete: `server/src/test/java/com/hify/support/service/TransactionalPgIntegrationTest.java`（连同空目录）
- Move: `server/src/test/java/com/hify/knowledge/service/KbChunkRetrievalTest.java` → `server/src/test/java/com/hify/knowledge/mapper/KbChunkRetrievalTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: 分层规则只扫生产代码；连库基类回归单文件；后续所有测试可按被测对象自然放包。

- [ ] **Step 1: LayerRulesTest 排除测试类**

import 区补 `com.tngtech.archunit.core.importer.ImportOption;`，类注解改为：

```java
@AnalyzeClasses(packages = "com.hify", importOptions = ImportOption.DoNotIncludeTests.class)
```

并在类 javadoc 补一句：`分层规则只约束生产代码（DoNotIncludeTests，修缮轮拍板）——测试代码无分层，按被测对象放包。`

- [ ] **Step 2: 收拢 PgIntegrationTest**

`PgIntegrationTest.java`：删 `extends TransactionalPgIntegrationTest` 与对应 import，类上直接加 `@Transactional`（import `org.springframework.transaction.annotation.Transactional`）：

```java
@SpringBootTest
@Transactional
public abstract class PgIntegrationTest {
```

删除文件与目录：

```bash
cd /home/wang/playlab/hify && git rm server/src/test/java/com/hify/support/service/TransactionalPgIntegrationTest.java
```

- [ ] **Step 3: KbChunkRetrievalTest 归位 mapper 包**

```bash
cd /home/wang/playlab/hify && git mv server/src/test/java/com/hify/knowledge/service/KbChunkRetrievalTest.java server/src/test/java/com/hify/knowledge/mapper/KbChunkRetrievalTest.java
```

文件内：`package com.hify.knowledge.service;` 改 `package com.hify.knowledge.mapper;`，删除 `import com.hify.knowledge.mapper.KbChunkMapper;`（已同包）。

- [ ] **Step 4: clean 全量回归（必须 clean——旧 class 残留会让 ArchUnit 误报）**

Run: `cd /home/wang/playlab/hify/server && mvn clean test`
Expected: 全绿（退出码 0；LayerRulesTest/ModularityTests/连库测试全过）

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add -A server/src/test
git commit -m "test(arch): 分层规则只扫生产代码，收拢 K4 两处绕行（基类归一+检索测试归位 mapper 包）"
```

---

### Task 7: 全量回归 + 手动验收

- [ ] **Step 1: 后端全量**

Run: `cd /home/wang/playlab/hify/server && mvn clean verify`
Expected: 退出码 0

- [ ] **Step 2: 前端全量**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm build`
Expected: 全绿 + 类型检查通过

- [ ] **Step 3: 手动验收清单（用户执行，照 spec）**

1. 迁移后查库：`select name, base_url from model_provider;` 存量两家已带 `/v1`。
2. deepseek / 千问 chat+embedding 试连接全绿（新拼接路径真调通）。
3. （可选加分）接一家非 `/v1` 前缀厂商（火山 Ark 或智谱）试连接通过。
4. 聊天发消息中途断网（DevTools offline）→ 重发 → 侧边栏不重复建会话。
5. 停用模型后用 curl 调同步端点发消息 → 报错后查库无孤儿会话/孤儿 user 消息。

---

## Self-Review 记录

- **Spec 覆盖**：决策 1/2→Task 1，决策 3+填法表→Task 2，决策 5①→Task 3，决策 5②→Task 4/5，决策 6→Task 6；「明确不做」无任务触碰。✅
- **占位符**：Task 5 store 测试的 mock 注入辅助名标注「以文件现状为准」——是核对指令非缺内容（既有 mock 模式只能现场确认）。✅
- **类型一致性**：`Meta(Long conversationId)` ↔ `StreamPayloads.Meta(Long)` ↔ 前端 `onMeta(conversationId: string)`（Long→string 全局契约）；`cleanupFailedTurn(Long, Long, boolean)` 与 `TurnContext` 字段序一致（已对照源码核实）。✅
