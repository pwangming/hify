# 留账清理轮实现计划（①配额切表废 daily_usage + ②llm_call_log 观测列）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 配额检查从 daily_usage 切到 usage_stat_daily 并 drop 旧表；llm_call_log 补耗时/成败/错误码观测列，失败调用也落流水（不进配额与看板）。

**Architecture:** `TokenUsedEvent` 扩 durationMs/success/errorCode 三字段（成功/失败双静态工厂）；成功发布点 2 处不动位置（ConversationStore 事务 B / LlmNodeExecutor），失败发布点新增 4 处且**全部在事务外**（AFTER_COMMIT 回滚即丢，监听器 fallbackExecution=true 接住）；监听器落库分支：流水每事件都插，聚合仅成功累加。

**Tech Stack:** Spring Boot 3 + MyBatis 注解 SQL + Flyway + PostgreSQL；前端 Vue 3 + Element Plus + vitest。

**Spec:** `docs/superpowers/specs/2026-07-18-usage-cleanup-round-design.md`（③日期组件调查不在本计划，由 Claude 终审阶段执行）

## Global Constraints

- 已合并迁移脚本（V1–V26）一字不动；本轮只新增 `V27__usage_cleanup_observability.sql`。
- mvn 结果只看退出码（`mvn -q verify; echo EXIT=$?`），禁止 grep BUILD SUCCESS。
- TDD：每个 Task 先写失败测试、亲眼看红、再实现、看绿；勾选步骤必须有实录（红灯输出要贴）。
- 改方法签名的 Task 必须在**同一 Task 内改完全部调用点**（含测试），禁止跨 Task 留编译断点。
- 错误码/对外 API 只增不改：CallLogItem 响应只加字段；不新增路由，无新错误码。
- DTO 禁 import entity（ArchUnit 拦）；跨模块依赖白名单不变（本轮无新增跨模块依赖）。
- 前端优先 Element Plus 组件（el-tag 等），禁自造；测试放 `__tests__/`。
- 每 Task 一次 commit，中文 commit message 按仓库惯例（feat/test/docs 前缀）。

---

### Task 1: TokenUsedEvent 扩字段 + 计时 + 四处失败发布点

**Files:**
- Modify: `server/src/main/java/com/hify/common/event/TokenUsedEvent.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java:101-127`（两个 appendAssistant 重载签名 +durationMs）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（注入 publisher、计时、4 处 appendAssistant 调用点、3 处失败发布）
- Modify: `server/src/main/java/com/hify/workflow/service/engine/LlmNodeExecutor.java:49-58`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java`
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/LlmNodeExecutorTest.java`
- Test（仅机械改构造调用，行为断言 Task 2 再改）: `UsageServiceTest.java` / `UsageEventListenerTest.java` / `UsageRecordDbTest.java` / `UsageEventFlowDbTest.java` / `ConversationUsageFlowDbTest.java`

**Interfaces:**
- Produces: `TokenUsedEvent(Long userId, Long appId, Long modelId, int promptTokens, int completionTokens, String source, long durationMs, boolean success, String errorCode)`；工厂 `TokenUsedEvent.success(userId, appId, modelId, promptTokens, completionTokens, source, durationMs)` 与 `TokenUsedEvent.failure(userId, appId, modelId, source, durationMs, Throwable cause)`；`appendAssistant(Long conversationId, String content, int promptTokens, int completionTokens, long durationMs, Long userId, Long appId, Long modelId, List<MessageSource> sources[, List<MessageToolCall> toolCalls])`（durationMs 插在 completionTokens 之后）。
- Task 2 依赖事件的三个新访问器 `durationMs()` / `success()` / `errorCode()`。

- [x] **Step 1: 写失败测试（三个文件）**

ConversationStoreTest 改既有测试 `appendAssistant_落assistant消息含token_并touch会话_发TokenUsedEvent`：调用改为 9 参 `store.appendAssistant(100L, "你好，我是助手", 12, 8, 345L, 42L, 7L, 5L, List.of())`，事件断言追加：

```java
assertEquals(345L, e.durationMs());
assertTrue(e.success());
assertNull(e.errorCode());
```

ConversationServiceTest 新增（沿用本文件既有 `stubRunnableApp`/`stubTurnAndReply` 脚手架；`publisher` 为 Step 3 加入的 `ApplicationEventPublisher` mock 字段）：

```java
@Test
void send_LLM调用失败_发failure事件_errorCode为异常类简名() {
    stubRunnableApp("你是客服");
    when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
            .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
    when(chatInvoker.invoke(any(), any(), any())).thenThrow(new IllegalStateException("LLM 超时"));

    assertThrows(IllegalStateException.class, () -> service.send(7L, null, "你好", member));

    ArgumentCaptor<TokenUsedEvent> ec = ArgumentCaptor.forClass(TokenUsedEvent.class);
    verify(publisher).publishEvent((Object) ec.capture());
    TokenUsedEvent e = ec.getValue();
    assertFalse(e.success());
    assertEquals("IllegalStateException", e.errorCode());
    assertEquals(0, e.promptTokens());
    assertEquals(TokenUsedEvent.SOURCE_CONVERSATION, e.source());
    assertEquals(42L, e.userId());
    assertEquals(5L, e.modelId());
}

@Test
void send_LLM抛BizException_failure事件errorCode为错误码数字() {
    stubRunnableApp("你是客服");
    when(store.openTurn(eq(7L), eq(null), eq(42L), eq("你好")))
            .thenReturn(new TurnContext(100L, List.of(userMsg("你好")), 300L, true));
    when(chatInvoker.invoke(any(), any(), any()))
            .thenThrow(new BizException(CommonError.PARAM_INVALID));

    assertThrows(BizException.class, () -> service.send(7L, null, "你好", member));

    ArgumentCaptor<TokenUsedEvent> ec = ArgumentCaptor.forClass(TokenUsedEvent.class);
    verify(publisher).publishEvent((Object) ec.capture());
    assertEquals("10001", ec.getValue().errorCode());
}
```

（流式失败路径：在既有「场景(a)：新建会话 + 流失败」测试里追加同样的 captor 断言 `assertFalse(e.success())`，不新开测试。）

LlmNodeExecutorTest：既有成功测试追加 `assertTrue(evt.success()); assertThat(evt.durationMs()).isGreaterThanOrEqualTo(0);`（或 JUnit 断言 `assertTrue(evt.durationMs() >= 0)`）；新增失败测试：

```java
@Test
void 调用失败_发failure事件后再抛NodeExecutionException() {
    ChatClient client = mock(ChatClient.class);
    when(providerFacade.getChatClient(3L)).thenReturn(client);
    when(llmCaller.call(any(), any(), any())).thenThrow(new IllegalStateException("provider 挂了"));

    assertThrows(NodeExecutionException.class, () -> executor.execute(nodeWith3(), ctx()));

    ArgumentCaptor<TokenUsedEvent> captor = ArgumentCaptor.forClass(TokenUsedEvent.class);
    verify(events).publishEvent(captor.capture());
    TokenUsedEvent evt = captor.getValue();
    assertFalse(evt.success());
    assertEquals("IllegalStateException", evt.errorCode());
    assertEquals(0, evt.promptTokens());
    assertEquals(TokenUsedEvent.SOURCE_WORKFLOW, evt.source());
}
```

（`nodeWith3()`/`ctx()` 用本文件既有的节点与上下文构造方式，名字以现文件为准。）

- [x] **Step 2: 跑测试看红**

Run: `cd server && mvn -q test -Dtest='ConversationStoreTest,ConversationServiceTest,LlmNodeExecutorTest'; echo EXIT=$?`
Expected: EXIT≠0（编译错：新签名/新访问器不存在）。贴输出。

- [x] **Step 3: 实现**

`TokenUsedEvent.java` 整体替换为：

```java
package com.hify.common.event;

import com.hify.common.exception.BizException;

/**
 * 一次 LLM 调用轮已结束（成或败，过去时语义）。由运行时入口在轮结束后发布；usage 模块用
 * {@code @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true) + @Async} 监听：
 * 流水 llm_call_log 每事件都插，聚合 usage_stat_daily 仅成功累加（配额/看板不被失败污染）。
 *
 * <p>为何放 common 而非发布方 api/event（规则3例外）：发布方 conversation 已依赖 usage::api
 * (QuotaGuard→checkQuota)，事件若放 conversation.api 则 usage 监听会反向依赖 conversation，
 * 形成环（违反 DAG 规则7）。这类「喂给 usage 的计量事件」统一放 common 共享内核。
 *
 * <p><b>失败事件必须在事务外发布</b>：AFTER_COMMIT 对回滚事务不触发，事务内发布会静默丢行
 * （看板轮计量黑洞的镜像教训）；fallbackExecution=true 使无事务发布立即执行。
 */
public record TokenUsedEvent(
        Long userId, Long appId, Long modelId,
        int promptTokens, int completionTokens, String source,
        long durationMs, boolean success, String errorCode) {

    /** source 合法值（与 llm_call_log.source check 约束一字不差）。 */
    public static final String SOURCE_CONVERSATION = "conversation";
    public static final String SOURCE_WORKFLOW = "workflow";

    /** 成功轮：errorCode 恒为 null。 */
    public static TokenUsedEvent success(Long userId, Long appId, Long modelId,
                                         int promptTokens, int completionTokens,
                                         String source, long durationMs) {
        return new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens,
                source, durationMs, true, null);
    }

    /** 失败轮：token 记 0（部分消耗取不可靠且不进账，spec §3.1）。 */
    public static TokenUsedEvent failure(Long userId, Long appId, Long modelId,
                                         String source, long durationMs, Throwable cause) {
        return new TokenUsedEvent(userId, appId, modelId, 0, 0,
                source, durationMs, false, errorCodeOf(cause));
    }

    /** BizException → 错误码数字；其余 → 异常类简名。不取 message，防供应商返回体敏感信息入库。 */
    private static String errorCodeOf(Throwable cause) {
        if (cause instanceof BizException be) {
            return String.valueOf(be.errorCode().code());
        }
        return cause.getClass().getSimpleName();
    }
}
```

`ConversationStore.java`：两个 appendAssistant 重载在 `completionTokens` 后插入 `long durationMs` 参数，8→9 参委托同步传递；发布改为：

```java
publisher.publishEvent(TokenUsedEvent.success(userId, appId, modelId,
        promptTokens, completionTokens, TokenUsedEvent.SOURCE_CONVERSATION, durationMs));
```

`ConversationService.java`：
1. 构造器**末尾**追加 `ApplicationEventPublisher publisher` 并存字段（import `org.springframework.context.ApplicationEventPublisher`）。
2. 加私有工具：`private static long elapsedMs(long startNanos) { return (System.nanoTime() - startNanos) / 1_000_000; }`
3. `send`：`Long cid = turn.conversationId();` 之后、`try` 之前加 `long startNanos = System.nanoTime(); // 轮耗时口径：进入 LLM 编排起，含取客户端/检索增强/工具执行`；两处 appendAssistant 调用在 completionTokens 实参后插入 `elapsedMs(startNanos)`；catch 块**第一行**（孤儿清理之前）加：

```java
publisher.publishEvent(TokenUsedEvent.failure(current.userId(), appId, app.modelId(),
        TokenUsedEvent.SOURCE_CONVERSATION, elapsedMs(startNanos), e));
```

4. `sendStream`：`Long cid = turn.conversationId();` 之后加同样的 `long startNanos`；done 回调里 appendAssistant 的 `usage[1]` 后插 `elapsedMs(startNanos)`；`onErrorResume` 的 `Mono.fromRunnable(...)` 改为块 lambda，failure 发布在 cleanupFailedTurn **之前**：

```java
.onErrorResume(err -> Mono.fromRunnable(() -> {
                    publisher.publishEvent(TokenUsedEvent.failure(current.userId(), appId, app.modelId(),
                            TokenUsedEvent.SOURCE_CONVERSATION, elapsedMs(startNanos), err));
                    store.cleanupFailedTurn(turn.conversationId(), turn.userMessageId(), turn.newConversation());
                })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(cleanupEx -> Mono.empty())
        .then(Mono.<StreamEvent>error(err)));
```

（注意：流式路径 `getChatClient` 在 Flux 之外抛出时不发失败事件——与既有孤儿清理的边界一致，本轮不扩。）
5. `sendStreamAgent`：`Long cid = turn.conversationId();` 之后加 `long startNanos`；成功 appendAssistant 的 completionTokens 后插 `elapsedMs(startNanos)`；catch 块第一行（cleanup 之前）加与 send 相同的 failure 发布（appId 用 `app.appId()`）。

`LlmNodeExecutor.java`：`try` 之前加 `long startNanos = System.nanoTime();`，成功发布改 `TokenUsedEvent.success(ctx.userId(), ctx.appId(), modelId, result.promptTokens(), result.completionTokens(), TokenUsedEvent.SOURCE_WORKFLOW, (System.nanoTime() - startNanos) / 1_000_000)`；catch 里 throw 之前加：

```java
events.publishEvent(TokenUsedEvent.failure(ctx.userId(), ctx.appId(), modelId,
        TokenUsedEvent.SOURCE_WORKFLOW, (System.nanoTime() - startNanos) / 1_000_000, e));
```

机械修复全部编译断点（**必须本 Task 内完成**）：
- `ConversationServiceTest`：`setUp` 加 `publisher = mock(ApplicationEventPublisher.class);` 并入构造器末参；所有 `appendAssistant` 的 stub/verify 在两个 `anyInt()` 后插 `anyLong()`；带具体值的 verify（如 `verify(store).appendAssistant(100L, "你好，我是助手", 12, 8, 42L, 7L, 5L, List.of())`）整体改 matcher 风格：`verify(store).appendAssistant(eq(100L), eq("你好，我是助手"), eq(12), eq(8), anyLong(), eq(42L), eq(7L), eq(5L), eq(List.of()))`（Mockito 禁止裸值与 matcher 混用）。
- `ConversationUsageFlowDbTest:49`：`store.appendAssistant(conversationId, "回答", 120, 60, 250L, PROBE_USER, 880L, 50L, List.of())`。
- 所有 `new TokenUsedEvent(a, b, c, p, c2, source)` 旧 6 参构造：成功语义的改 `TokenUsedEvent.success(a, b, c, p, c2, source, 5L)`（UsageServiceTest / UsageEventListenerTest / UsageRecordDbTest / UsageEventFlowDbTest）。

- [x] **Step 4: 全量测试看绿**

Run: `cd server && mvn -q verify; echo EXIT=$?`
Expected: EXIT=0（含 ModularityTests/ArchUnit；本 Task 结束时 recordUsage 尚未分支，失败事件会以 0 token 落旧三表——中间态，Task 2 收口）。

- [x] **Step 5: Commit**

```bash
git add -A server
git commit -m "feat(usage): TokenUsedEvent扩耗时/成败/错误码，四处失败发布点全在事务外"
```

---

### Task 2: V27 迁移 + 配额切表 + 观测列落库 + 删 DailyUsageMapper

**Files:**
- Create: `server/src/main/resources/db/migration/V27__usage_cleanup_observability.sql`
- Modify: `server/src/main/java/com/hify/usage/mapper/LlmCallLogMapper.java`（insertLog 扩参）
- Modify: `server/src/main/java/com/hify/usage/mapper/UsageStatDailyMapper.java`（+sumTodayByUser）
- Modify: `server/src/main/java/com/hify/usage/service/UsageService.java`
- Delete: `server/src/main/java/com/hify/usage/mapper/DailyUsageMapper.java`
- Test: `UsageServiceTest.java` / `UsageRecordDbTest.java` / `UsageEventFlowDbTest.java` / `ConversationUsageFlowDbTest.java`

**Interfaces:**
- Consumes: Task 1 的 `TokenUsedEvent.durationMs()/success()/errorCode()` 与双工厂。
- Produces: `LlmCallLogMapper.insertLog(userId, appId, modelId, promptTokens, completionTokens, source, durationMs, status, errorCode)`；`UsageStatDailyMapper.sumTodayByUser(Long userId, LocalDate statDate)`。Task 3 依赖 llm_call_log 新列已存在。

- [ ] **Step 1: 写失败测试**

`UsageServiceTest` 整体改（checkQuota mock 从 dailyUsageMapper 换 statDailyMapper；recordUsage 拆成功/失败两条）：

```java
class UsageServiceTest {

    private static final long LIMIT = 1000L;

    private LlmCallLogMapper llmCallLogMapper;
    private UsageStatDailyMapper statDailyMapper;
    private UsageService service;

    @BeforeEach
    void setUp() {
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        statDailyMapper = mock(UsageStatDailyMapper.class);
        service = new UsageService(llmCallLogMapper, statDailyMapper, new UsageProperties(LIMIT));
    }

    @Test
    void checkQuota_今日已用未达上限_放行() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT - 1);
        assertDoesNotThrow(() -> service.checkQuota(7L, 88L));
    }

    @Test
    void checkQuota_今日已用刚好等于上限_拦截并抛14001() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT);
        BizException ex = assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
        assertEquals(UsageError.QUOTA_EXCEEDED, ex.errorCode());
    }

    @Test
    void checkQuota_今日已用超过上限_拦截() {
        when(statDailyMapper.sumTodayByUser(eq(7L), any(LocalDate.class))).thenReturn(LIMIT + 1);
        assertThrows(BizException.class, () -> service.checkQuota(7L, 88L));
    }

    @Test
    void recordUsage_成功事件_插流水且聚合累加() {
        service.recordUsage(TokenUsedEvent.success(
                7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_CONVERSATION, 1234L));

        verify(llmCallLogMapper).insertLog(7L, 88L, 5L, 300L, 180L,
                TokenUsedEvent.SOURCE_CONVERSATION, 1234L, "success", null);
        verify(statDailyMapper).upsertAccumulate(
                eq(7L), eq(88L), eq(5L), any(LocalDate.class), eq(300L), eq(180L));
    }

    @Test
    void recordUsage_失败事件_只插流水_不动聚合不进配额() {
        service.recordUsage(TokenUsedEvent.failure(
                7L, 88L, 5L, TokenUsedEvent.SOURCE_WORKFLOW, 890L, new IllegalStateException("x")));

        verify(llmCallLogMapper).insertLog(7L, 88L, 5L, 0L, 0L,
                TokenUsedEvent.SOURCE_WORKFLOW, 890L, "failed", "IllegalStateException");
        verify(statDailyMapper, never()).upsertAccumulate(
                any(), any(), any(), any(), anyLong(), anyLong());
    }
}
```

`UsageRecordDbTest` 整体改（去 daily_usage、补新列与失败分支、断言旧表已 drop）：

```java
/** recordUsage 双写连库验证：流水（含观测列）+ usage_stat_daily；失败事件只落流水；daily_usage 已废弃。 */
class UsageRecordDbTest extends PgIntegrationTest {

    @Autowired
    UsageService usageService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordUsage_成败分流_流水全记_聚合仅成功累加() {
        usageService.recordUsage(TokenUsedEvent.success(
                7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_WORKFLOW, 1500L));
        usageService.recordUsage(TokenUsedEvent.failure(
                7L, 88L, 5L, TokenUsedEvent.SOURCE_WORKFLOW, 800L, new IllegalStateException("x")));

        Map<String, Object> ok = jdbc.queryForMap(
                "select duration_ms, status, error_code from llm_call_log "
                        + "where user_id = 7 and status = 'success'");
        assertThat(((Number) ok.get("duration_ms")).intValue()).isEqualTo(1500);
        assertThat(ok.get("error_code")).isNull();

        Map<String, Object> failed = jdbc.queryForMap(
                "select duration_ms, status, error_code, prompt_tokens from llm_call_log "
                        + "where user_id = 7 and status = 'failed'");
        assertThat(((Number) failed.get("duration_ms")).intValue()).isEqualTo(800);
        assertThat(failed.get("error_code")).isEqualTo("IllegalStateException");
        assertThat(((Number) failed.get("prompt_tokens")).longValue()).isZero();

        // 聚合仅成功累加：call_count=1（失败不计），token 只含成功轮
        Map<String, Object> stat = jdbc.queryForMap(
                "select prompt_tokens, completion_tokens, call_count from usage_stat_daily "
                        + "where user_id = 7 and app_id = 88 and model_id = 5");
        assertThat(((Number) stat.get("prompt_tokens")).longValue()).isEqualTo(300L);
        assertThat(((Number) stat.get("call_count")).longValue()).isEqualTo(1L);

        // V27 已 drop daily_usage
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'daily_usage'",
                Integer.class);
        assertThat(tables).isZero();
    }
}
```

`UsageEventFlowDbTest`：cleanup 与断言中所有 daily_usage 行删除（`delete from daily_usage...`、`select count(*) from daily_usage...` 及其断言）；类注释「三表」改「两表」；新增失败链路测试：

```java
/** 失败事件无事务发布（conversation 失败 catch / workflow catch 均无活跃事务）：落流水但不进聚合。 */
@Test
void 失败事件无事务发布_落流水_聚合零变化() throws Exception {
    publisher.publishEvent(TokenUsedEvent.failure(PROBE_USER, 880L, 50L,
            TokenUsedEvent.SOURCE_CONVERSATION, 700L, new IllegalStateException("boom")));

    assertThat(awaitLogRows()).as("失败事件必须落流水").isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "select status from llm_call_log where user_id = " + PROBE_USER, String.class))
            .isEqualTo("failed");
    assertThat(jdbc.queryForObject(
            "select count(*) from usage_stat_daily where user_id = " + PROBE_USER, Integer.class))
            .as("失败不进聚合/配额").isZero();
}
```

（既有两条测试的构造已在 Task 1 换成 `TokenUsedEvent.success(...)`，此处只删 daily_usage 相关行。）

`ConversationUsageFlowDbTest`：cleanup 里删除 `jdbc.update("delete from daily_usage where user_id = ?", PROBE_USER);` 一行。

- [ ] **Step 2: 跑测试看红**

Run: `cd server && mvn -q test -Dtest='UsageServiceTest'; echo EXIT=$?`
Expected: EXIT≠0（编译错：UsageService 构造器还是 4 参、insertLog/sumTodayByUser 签名不存在）。贴输出。

- [ ] **Step 3: 实现**

新建 `V27__usage_cleanup_observability.sql`：

```sql
-- V27：留账清理轮（usage 模块）。spec：2026-07-18-usage-cleanup-round-design.md。
-- ② llm_call_log 观测列（additive；分区父表 alter 自动传播到子分区）；
-- ① 配额检查已切 usage_stat_daily（V26 已从流水全量回填，daily_usage 无独有数据），废弃 daily_usage。

alter table llm_call_log
    add column duration_ms integer,
    add column status      text not null default 'success'
        check (status in ('success', 'failed')),
    add column error_code  text;
comment on column llm_call_log.duration_ms is '轮耗时毫秒；历史行为 null（V27 前无此列）';
comment on column llm_call_log.status is '调用结果；V27 前只有成功轮落行，default 回填历史行语义正确';
comment on column llm_call_log.error_code is '失败错误码：BizException 存错误码数字，其余存异常类简名；不存 message 防敏感信息入库';

drop table daily_usage;
```

`LlmCallLogMapper.insertLog` 替换为：

```java
/** 落一行调用流水（成功/失败轮的 TokenUsedEvent 均触发；失败行 token=0、status='failed'）。 */
@Insert("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, source, "
        + "duration_ms, status, error_code) "
        + "values (#{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, #{source}, "
        + "#{durationMs}, #{status}, #{errorCode})")
int insertLog(@Param("userId") Long userId, @Param("appId") Long appId, @Param("modelId") Long modelId,
              @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens,
              @Param("source") String source, @Param("durationMs") long durationMs,
              @Param("status") String status, @Param("errorCode") String errorCode);
```

`UsageStatDailyMapper` 追加（类注释「写侧只有 UPSERT」改为「写侧 UPSERT + 配额读」）：

```java
/** 某用户今日跨应用已用 Token 合计（配额检查专用；空结果 coalesce 为 0）。走 dim_uq 索引 user_id 前缀。 */
@Select("select coalesce(sum(prompt_tokens + completion_tokens), 0) from usage_stat_daily "
        + "where user_id = #{userId} and stat_date = #{statDate}")
long sumTodayByUser(@Param("userId") Long userId, @Param("statDate") LocalDate statDate);
```

`UsageService` 替换为（删 DailyUsageMapper 依赖，构造器 3 参）：

```java
/**
 * usage 模块核心：入口配额检查（读）+ 用量落库（写）。
 * checkQuota 只查 usage_stat_daily 聚合表（不扫流水，V27 起 daily_usage 已废弃）；
 * recordUsage 由事件监听器异步调用：流水每事件都插，聚合仅成功累加（失败不进配额/看板）。
 */
@Service
public class UsageService {

    private final LlmCallLogMapper llmCallLogMapper;
    private final UsageStatDailyMapper statDailyMapper;
    private final UsageProperties props;

    public UsageService(LlmCallLogMapper llmCallLogMapper,
                        UsageStatDailyMapper statDailyMapper, UsageProperties props) {
        this.llmCallLogMapper = llmCallLogMapper;
        this.statDailyMapper = statDailyMapper;
        this.props = props;
    }

    /** 按用户/天封顶：今日已用 Token 合计 >= 上限即拦，抛 14001/429。appId 保留供 per-app 扩展。 */
    public void checkQuota(Long userId, Long appId) {
        long used = statDailyMapper.sumTodayByUser(userId, LocalDate.now());
        if (used >= props.dailyTokenLimitPerUser()) {
            throw new BizException(UsageError.QUOTA_EXCEEDED);
        }
    }

    /** 流水 + （仅成功）UPSERT usage_stat_daily。两写同一 usage 本地事务。 */
    @Transactional
    public void recordUsage(TokenUsedEvent e) {
        llmCallLogMapper.insertLog(e.userId(), e.appId(), e.modelId(),
                e.promptTokens(), e.completionTokens(), e.source(),
                e.durationMs(), e.success() ? "success" : "failed", e.errorCode());
        if (e.success()) {
            statDailyMapper.upsertAccumulate(e.userId(), e.appId(), e.modelId(), LocalDate.now(),
                    e.promptTokens(), e.completionTokens());
        }
    }
}
```

删除 `DailyUsageMapper.java`；`grep -rn "daily_usage\|DailyUsage" server/src` 必须零命中（迁移脚本 V12/V26 的历史文本除外）。

- [ ] **Step 4: 全量测试看绿**

Run: `cd server && mvn -q verify; echo EXIT=$?`
Expected: EXIT=0（Testcontainers 跑 Flyway 会执行 V27，连库测试验证真实迁移）。

- [ ] **Step 5: Commit**

```bash
git add -A server
git commit -m "feat(usage): V27配额切usage_stat_daily废弃daily_usage，llm_call_log补观测三列"
```

---

### Task 3: 调用日志读侧 additive（selectPage → CallLogItem）

**Files:**
- Modify: `server/src/main/java/com/hify/usage/mapper/LlmCallLogMapper.java`（CallLogRow + selectPage 列）
- Modify: `server/src/main/java/com/hify/usage/dto/CallLogItem.java`
- Modify: `server/src/main/java/com/hify/usage/service/UsageLogService.java`（映射）
- Create: `server/src/test/java/com/hify/usage/service/UsageLogServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 llm_call_log 新列。
- Produces: `CallLogItem(Long id, Long userId, Long appId, Long modelId, long promptTokens, long completionTokens, String source, Integer durationMs, String status, String errorCode, OffsetDateTime createTime)`——Task 4 前端按此字段名对齐（durationMs 是 Integer 序列化为数字，非 Long-as-string）。

- [ ] **Step 1: 写失败测试**

新建 `UsageLogServiceTest.java`（映射单测；游标/窗口校验已有 LogCursorTest 覆盖，不重复）：

```java
package com.hify.usage.service;

import com.hify.common.page.CursorResult;
import com.hify.usage.dto.CallLogItem;
import com.hify.usage.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageLogServiceTest {

    @Test
    void list_观测列透传_历史行null保留() {
        LlmCallLogMapper mapper = mock(LlmCallLogMapper.class);
        OffsetDateTime t = OffsetDateTime.parse("2026-07-18T10:00:00+08:00");
        when(mapper.selectPage(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new LlmCallLogMapper.CallLogRow(1L, 7L, 88L, 5L, 300, 180,
                                "conversation", 1500, "success", null, t),
                        new LlmCallLogMapper.CallLogRow(2L, 7L, 88L, 5L, 0, 0,
                                "workflow", null, "failed", "12002", t.minusMinutes(1))));

        CursorResult<CallLogItem> page = new UsageLogService(mapper)
                .list(t.minusDays(1), t.plusDays(1), null, null, null, null, null, 20);

        CallLogItem ok = page.list().get(0);
        assertThat(ok.durationMs()).isEqualTo(1500);
        assertThat(ok.status()).isEqualTo("success");
        assertThat(ok.errorCode()).isNull();
        CallLogItem failed = page.list().get(1);
        assertThat(failed.durationMs()).isNull();
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.errorCode()).isEqualTo("12002");
    }
}
```

- [ ] **Step 2: 跑测试看红**

Run: `cd server && mvn -q test -Dtest='UsageLogServiceTest'; echo EXIT=$?`
Expected: EXIT≠0（编译错：CallLogRow/CallLogItem 无新字段）。贴输出。

- [ ] **Step 3: 实现**

`CallLogRow` 改为（durationMs 用 Integer——历史行 null）：

```java
record CallLogRow(Long id, Long userId, Long appId, Long modelId, long promptTokens,
                  long completionTokens, String source, Integer durationMs, String status,
                  String errorCode, OffsetDateTime createTime) {
}
```

`selectPage` 的 select 列表改为 `select id, user_id, app_id, model_id, prompt_tokens, completion_tokens, source, duration_ms, status, error_code, create_time`（其余不动）。

`CallLogItem` 改为：

```java
public record CallLogItem(Long id, Long userId, Long appId, Long modelId, long promptTokens,
                          long completionTokens, String source, Integer durationMs, String status,
                          String errorCode, OffsetDateTime createTime) {
}
```

`UsageLogService.list` 的映射改为 `new CallLogItem(r.id(), r.userId(), r.appId(), r.modelId(), r.promptTokens(), r.completionTokens(), r.source(), r.durationMs(), r.status(), r.errorCode(), r.createTime())`。

（api-standards 核对：不新增路由/方法/错误码，响应仅 additive 加字段——合规；durationMs 为 Integer 不受 Long-序列化字符串规则约束。）

- [ ] **Step 4: 全量测试看绿**

Run: `cd server && mvn -q verify; echo EXIT=$?`
Expected: EXIT=0。

- [ ] **Step 5: Commit**

```bash
git add -A server
git commit -m "feat(usage): 调用日志接口additive透出耗时/状态/错误码"
```

---

### Task 4: 前端调用日志页耗时/状态两列（vitest TDD）

**Files:**
- Modify: `web/src/types/usage.ts`（CallLogItem）
- Modify: `web/src/views/admin/usage/CallLogList.vue`
- Test: `web/src/views/admin/usage/__tests__/CallLogList.spec.ts`

**Interfaces:**
- Consumes: Task 3 的响应字段 `durationMs: number | null`、`status: 'success' | 'failed'`、`errorCode: string | null`。

- [ ] **Step 1: 写失败测试**

`CallLogList.spec.ts`：page1 行追加 `durationMs: 1500, status: 'success', errorCode: null`；page2 行追加 `durationMs: null, status: 'failed', errorCode: '12002'`；既有测试末尾（`load-more` 消失断言之后）追加：

```ts
// 观测列：成功行绿 tag+耗时；失败行红 tag+错误码；历史行耗时显示「—」
expect(wrapper.text()).toContain('1500 ms')
expect(wrapper.find('.el-tag--success').exists()).toBe(true)
expect(wrapper.find('.el-tag--danger').exists()).toBe(true)
expect(wrapper.text()).toContain('失败')
expect(wrapper.text()).toContain('12002')
```

- [ ] **Step 2: 跑测试看红**

Run: `cd web && pnpm vitest run src/views/admin/usage/__tests__/CallLogList.spec.ts`
Expected: FAIL（页面无新列）。贴输出。

- [ ] **Step 3: 实现**

`types/usage.ts` 的 `CallLogItem` 追加：

```ts
  durationMs: number | null
  status: 'success' | 'failed'
  errorCode: string | null
```

（注意与其他 token 字段不同：durationMs 后端是 Integer，JSON 里是数字不是字符串。）

`CallLogList.vue`：script 加格式函数

```ts
const durationText = (v: number | null) => (v == null ? '—' : `${v} ms`)
```

template「来源」列之后插入状态列、「输出 Token」列之后插入耗时列：

```vue
<el-table-column label="状态" min-width="110">
  <template #default="{ row }">
    <el-tag :type="(row as CallLogItem).status === 'failed' ? 'danger' : 'success'" size="small">
      {{ (row as CallLogItem).status === 'failed' ? '失败' : '成功' }}
    </el-tag>
    <span v-if="(row as CallLogItem).errorCode" class="call-log-list__error-code">
      {{ (row as CallLogItem).errorCode }}
    </span>
  </template>
</el-table-column>
```

```vue
<el-table-column label="耗时">
  <template #default="{ row }">{{ durationText((row as CallLogItem).durationMs) }}</template>
</el-table-column>
```

style 追加：

```scss
  &__error-code {
    margin-left: 6px;
    color: var(--el-color-danger);
    font-size: 12px;
  }
```

- [ ] **Step 4: 跑测试看绿 + 全量前端测试**

Run: `cd web && pnpm test`
Expected: 全绿。

- [ ] **Step 5: Commit**

```bash
git add -A web
git commit -m "feat(web): 调用日志页补耗时与成败状态列"
```

---

### Task 5: 文档更新 + 全量回归（回归并入本 Task，不得跳过）

**Files:**
- Modify: `docs/architecture/data-model.md`
- Modify: `docs/architecture/er-diagram.dot`（+重新生成 er-diagram.svg）
- Modify: `CLAUDE.md`（架构文档索引行的表数量，如与 data-model.md 不符则同步）

- [ ] **Step 1: 更新 data-model.md**

- 删除表清单中 `daily_usage` 一行（现 :28）；文首/节内如有表总数（如「18 张」）核实后减一。
- `llm_call_log` 行描述追加观测列：「V27 起含 duration_ms/status/error_code，失败轮也落行（token=0），聚合仅成功累加」。
- :49 弱引用行 `llm_call_log / daily_usage / usage_stat_daily` 删去 `daily_usage`。
- 「刻意不存在的表」（或等价小节）追加一条：`daily_usage`——V12 建、V27 废弃，配额已切 usage_stat_daily（V26 全量回填后两张聚合表冗余，留账清理轮收账）。

- [ ] **Step 2: 更新 ER 图**

`er-diagram.dot` 删除 daily_usage 节点及其全部边；重新生成：

```bash
cd docs/architecture && npx -y @hpcc-js/wasm-graphviz-cli -T svg er-diagram.dot > er-diagram.svg
```

- [ ] **Step 3: 全量回归（三条命令逐一贴实录）**

```bash
cd server && mvn -q verify; echo EXIT=$?          # Expected: EXIT=0
cd ../web && pnpm test                             # Expected: 全绿
pnpm e2e                                           # Expected: 4 passed（KB/workflow/agent 旅程 + spike）
```

（`pnpm e2e` 前置按 E2E 地基轮既定流程：hify_e2e 库 + e2e profile；若 agent 旅程需 mcp-demo，按仓库 e2e 文档启动。）

- [ ] **Step 4: Commit**

```bash
git add -A docs CLAUDE.md
git commit -m "docs(data-model): 废弃daily_usage并登记llm_call_log观测列，ER图同步"
```

---

## 计划外事项（执行者禁做，留给终审/人工）

- ③ 日期组件全宽拉伸风源调查：Claude 终审阶段 Playwright 取证，timebox 30 分钟。
- 人工验收脚本（用户执行）：临时改错某模型 BaseURL 发起对话 → 调用日志页出现红色失败行（耗时+错误码）→ 改回验证成功行；确认看板/配额数字不被失败行污染。
- push 到远程：终审通过后由用户/终审流程决定。
