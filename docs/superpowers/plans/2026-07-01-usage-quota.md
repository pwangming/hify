# ⑥ usage 配额 Implementation Plan

> 配套设计：`docs/superpowers/specs/2026-07-01-usage-quota-design.md`。
> 执行方式：逐 Task、TDD（先红后绿），每 Task 完给自检并追加到 `docs/self-check-usage-quota.md`。
> Task 1、2 在补写本计划前已完成（下方标 [x] 并回填）。

## Global Constraints

- 事务铁律：`ConversationService` 无 @Transactional；LLM 调用不被事务包裹；用量落库经**事件异步**（禁同步调 usage 写）。
- 配额只在入口查（conversation 收消息；workflow 本轮不接）、只查 `daily_usage`，禁扫 `llm_call_log`。
- 事件设计：单事件 `TokenUsedEvent`(common)；conversation 在**事务B(`appendAssistant`)内**发布；usage `@Async @TransactionalEventListener(AFTER_COMMIT)` 监听。
- 配额口径：按用户/天，限额来自 `application.yml` 全局默认；`used >= limit` 拦，抛 `UsageError.QUOTA_EXCEEDED(14001/429)`。
- 模块边界：usage 依赖仍 `{common, infra}`，无环；同步走 `UsageFacade`、通知走事件。
- 自定义 SQL：Mapper 注解 `@Insert`/`@Select`（首处），不引 XML；`@MapperScan("com.hify.**.mapper")` 已覆盖。
- 契约只增不改：14001/429 已登记；SSE 连接前配额错误走 JSON 信封。
- 判 mvn：看 Surefire `Tests run/Failures/Errors`，不 grep BUILD SUCCESS、不加 `-q`（系统 mvn，pom 在 server/）。连库测试延后；自定义 SQL 用 `hify-postgres` 干跑验语法。

---

## File Structure

**已完成**
- [x] Create `server/.../resources/db/migration/V12__create_usage_tables.sql`（Task 1）
- [x] Create `server/.../common/event/TokenUsedEvent.java`（Task 2）

**usage 模块（Task 3-5、7）**
- Create `server/.../usage/constant/UsageError.java` — 14001/429
- Create `server/.../usage/config/UsageProperties.java` — `hify.usage.daily-token-limit-per-user`
- Create `server/.../usage/config/UsageConfig.java` — `@EnableConfigurationProperties` + `@EnableScheduling`
- Create `server/.../usage/mapper/DailyUsageMapper.java` — `sumTodayByUser` + `upsertAccumulate`（注解 SQL）
- Create `server/.../usage/mapper/LlmCallLogMapper.java` — `insertLog`（注解 SQL）
- Create `server/.../usage/service/UsageService.java` — `checkQuota` + `recordUsage`
- Create `server/.../usage/service/UsageEventListener.java` — 监听 `TokenUsedEvent`
- Create `server/.../usage/service/PartitionMaintainer.java` — 月度补建 `llm_call_log` 分区（Task 7）
- Create `server/.../usage/api/UsageFacade.java` — `checkQuota(Long userId, Long appId)`
- Create `server/.../usage/service/UsageFacadeImpl.java` — 实现，委托 UsageService
- Modify `server/.../resources/application.yml` — 加 `hify.usage.*`
- Test: `usage/service/UsageServiceTest.java`、`UsageEventListenerTest.java`

**conversation 接线（Task 6）**
- Modify `server/.../conversation/service/QuotaGuard.java` — 注入 `UsageFacade`，委托 checkQuota
- Modify `server/.../conversation/service/ConversationStore.java` — `appendAssistant` 加 userId/appId/modelId 参数 + 注入 `ApplicationEventPublisher` 发 `TokenUsedEvent`
- Modify `server/.../conversation/service/ConversationService.java` — send/sendStream 传 userId/appId/modelId 给 appendAssistant
- Modify tests: `ConversationServiceTest.java`、`ConversationStoreTest.java`（若存在）、`QuotaGuardTest.java`（新增）

**文档（Task 7）**
- Modify `docs/architecture/code-organization.md` — §4 条3 补事件放 common 例外

---

## Task 1: Flyway V12 建两表（含月分区）  ✅ 已完成

- [x] 建 `V12__create_usage_tables.sql`：`llm_call_log`(月 RANGE 分区，2026-07~12) + `daily_usage`(唯一索引+查询索引)。
- [x] 验证：`BEGIN…ROLLBACK` 灌 `hify-postgres` 干跑，8×建表+2×索引全过、EXIT=0。（见自检 step1）

## Task 2: common `TokenUsedEvent`  ✅ 已完成

- [x] 建 `common/event/TokenUsedEvent(Long userId, Long appId, Long modelId, int promptTokens, int completionTokens)` 纯数据 record。（见自检 step2）

---

## Task 3: usage 错误码 + 配置

**Files:** `usage/constant/UsageError.java`、`usage/config/UsageProperties.java`、`usage/config/UsageConfig.java`、`application.yml`

**Interfaces:** `UsageProperties.dailyTokenLimitPerUser() -> long`；`UsageError.QUOTA_EXCEEDED`。

- [ ] **Step 1: `UsageError`（照 ConversationError 范式，implements ErrorCode）**
```java
public enum UsageError implements ErrorCode {
    QUOTA_EXCEEDED(14001, HttpStatus.TOO_MANY_REQUESTS, "今日 Token 配额已用尽");
    // code/status/defaultMessage 三字段 + 构造 + 三 override（同 ConversationError）
}
```
- [ ] **Step 2: `UsageProperties`（@ConfigurationProperties("hify.usage")）**
```java
@ConfigurationProperties("hify.usage")
public record UsageProperties(long dailyTokenLimitPerUser) {}
```
- [ ] **Step 3: `UsageConfig` 开启配置与调度**
```java
@Configuration
@EnableConfigurationProperties(UsageProperties.class)
@EnableScheduling   // 供 Task7 PartitionMaintainer；应用内首次开启调度
class UsageConfig {}
```
- [ ] **Step 4: `application.yml` 加限额（外化、给个宽松默认，如 200 万/人/天）**
```yaml
hify:
  usage:
    daily-token-limit-per-user: ${HIFY_USAGE_DAILY_TOKEN_LIMIT_PER_USER:2000000}
```
- [ ] **Verify:** 编译过（`mvn -pl . test-compile` 或随 Task5 一起编）。纯配置无单测。

## Task 4: usage Mapper（注解自定义 SQL）+ 真 PG 干跑

**Files:** `usage/mapper/DailyUsageMapper.java`、`usage/mapper/LlmCallLogMapper.java`

无实体：两个 plain 接口（非 BaseMapper），由 `@MapperScan` 注册；scalar/参数化即可。

- [ ] **Step 1: `DailyUsageMapper`**
```java
public interface DailyUsageMapper {
    @Select("select coalesce(sum(total_tokens),0) from daily_usage "
          + "where user_id=#{userId} and stat_date=#{statDate}")
    long sumTodayByUser(@Param("userId") Long userId, @Param("statDate") LocalDate statDate);

    @Insert("insert into daily_usage(user_id,app_id,stat_date,total_tokens) "
          + "values(#{userId},#{appId},#{statDate},#{tokens}) "
          + "on conflict(user_id,app_id,stat_date) "
          + "do update set total_tokens = daily_usage.total_tokens + excluded.total_tokens, update_time = now()")
    int upsertAccumulate(@Param("userId") Long userId, @Param("appId") Long appId,
                         @Param("statDate") LocalDate statDate, @Param("tokens") long tokens);
}
```
- [ ] **Step 2: `LlmCallLogMapper`（id/create_time 走 DB 默认）**
```java
public interface LlmCallLogMapper {
    @Insert("insert into llm_call_log(user_id,app_id,model_id,prompt_tokens,completion_tokens) "
          + "values(#{userId},#{appId},#{modelId},#{promptTokens},#{completionTokens})")
    int insertLog(@Param("userId") Long userId, @Param("appId") Long appId, @Param("modelId") Long modelId,
                  @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens);
}
```
- [ ] **Verify（SQL 语法 + UPSERT 二次累加，真 PG 干跑）:** `BEGIN` → insert 一条 daily_usage → 再 upsert 同键 → `select total_tokens` 应为两次之和 → llm_call_log insert 一条 → `ROLLBACK`。灌 `hify-postgres -v ON_ERROR_STOP=1`，确认累加正确、EXIT=0。连库对象级测试延后 Testcontainers。

## Task 5: usage Service + Facade + 监听器

**Files:** `usage/service/UsageService.java`、`usage/api/UsageFacade.java`、`usage/service/UsageFacadeImpl.java`、`usage/service/UsageEventListener.java`；Test: `UsageServiceTest`、`UsageEventListenerTest`

**Interfaces:** `UsageFacade.checkQuota(Long userId, Long appId)`（api，签名只 JDK 类型）。

- [ ] **Step 1（RED）: `UsageServiceTest`**（mock 两 Mapper + UsageProperties）
  - checkQuota：`sumTodayByUser` 返回 `limit-1`→放行；`limit`→抛 `BizException(QUOTA_EXCEEDED)`；`limit+1`→抛。（边界 `>=`）
  - recordUsage(event p=300,c=180)：验 `llmCallLogMapper.insertLog(...,300,180)` 调 1 次 + `dailyUsageMapper.upsertAccumulate(userId,appId,today,480)` 调 1 次。
- [ ] **Step 2（GREEN）: `UsageService`**
```java
@Service
public class UsageService {
    // ctor 注入 DailyUsageMapper, LlmCallLogMapper, UsageProperties
    public void checkQuota(Long userId, Long appId) {           // appId 本轮未用，保留供 per-app 扩展
        long used = dailyUsageMapper.sumTodayByUser(userId, LocalDate.now());
        if (used >= props.dailyTokenLimitPerUser())
            throw new BizException(UsageError.QUOTA_EXCEEDED);
    }
    @Transactional
    public void recordUsage(TokenUsedEvent e) {
        llmCallLogMapper.insertLog(e.userId(), e.appId(), e.modelId(), e.promptTokens(), e.completionTokens());
        dailyUsageMapper.upsertAccumulate(e.userId(), e.appId(), LocalDate.now(),
                (long) e.promptTokens() + e.completionTokens());
    }
}
```
- [ ] **Step 3: `UsageFacade`(api) + `UsageFacadeImpl`** 委托 `usageService.checkQuota`。
- [ ] **Step 4（RED→GREEN）: `UsageEventListenerTest` + `UsageEventListener`**
```java
@Component
public class UsageEventListener {
    @Async @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TokenUsedEvent e) { usageService.recordUsage(e); }
}
```
  测试：直接调 `on(event)` 断言委托 `recordUsage`（AFTER_COMMIT/@Async 行为进手验，不在单测证）。
- [ ] **Verify:** `mvn -pl . -Dtest=UsageServiceTest,UsageEventListenerTest test`，看 Surefire 计数全绿。

## Task 6: conversation 接线（拦截 + 发布点）

**Files:** `QuotaGuard.java`、`ConversationStore.java`、`ConversationService.java`；Test: `QuotaGuardTest`(新)、`ConversationServiceTest`(改)

- [ ] **Step 1（RED）: `QuotaGuardTest`** — mock `UsageFacade`，验 `check` 透传 `checkQuota(userId,appId)`；抛出时向上传播。
- [ ] **Step 2（GREEN）: `QuotaGuard`** 注入 `UsageFacade`，`check` 改 `usageFacade.checkQuota(userId, appId)`（删 TODO）。
- [ ] **Step 3（RED）: `ConversationServiceTest`** — ①mock checkQuota 抛 14001 → send/sendStream 抛出且**不调** chatInvoker/store.appendAssistant；②正常路径断言 `appendAssistant` 收到 `userId/appId/modelId`。
- [ ] **Step 4（GREEN）: `ConversationStore.appendAssistant` 扩参 + 发布**
```java
@Transactional
public Message appendAssistant(Long conversationId, String content, int pTok, int cTok,
                               Long userId, Long appId, Long modelId) {
    // …原落库逻辑…
    publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, pTok, cTok)); // 事务内发布
    return m;
}
```
  注入 `ApplicationEventPublisher`。`ConversationService.send`/`sendStream` 调用处补 `current.userId(), appId, app.modelId()`。
- [ ] **Verify:** `mvn -pl . -Dtest=QuotaGuardTest,ConversationServiceTest test` 全绿；跑全量 `mvn -pl . test` 确认既有 conversation 测试不回归。

## Task 7: 月度分区维护 + 边界校验 + 文档

**Files:** `usage/service/PartitionMaintainer.java`（+`LlmCallLogMapper` 加 DDL 方法）、`code-organization.md`

- [ ] **Step 1: `LlmCallLogMapper.createPartition`（DDL，值由 Java 计算、非用户输入，`${}` 安全）**
```java
@Update("create table if not exists ${name} partition of llm_call_log "
      + "for values from ('${from}') to ('${to}')")
void createPartition(@Param("name") String name, @Param("from") String from, @Param("to") String to);
```
- [ ] **Step 2: `PartitionMaintainer`（每月1日建未来 3 个月，幂等）**
```java
@Scheduled(cron = "0 30 0 1 * *")   // 每月1日 00:30
public void ensureFuturePartitions() {
    YearMonth base = YearMonth.now();
    for (int i = 1; i <= 3; i++) {
        YearMonth ym = base.plusMonths(i);
        mapper.createPartition("llm_call_log_" + ym.getYear() + "_" + String.format("%02d", ym.getMonthValue()),
            ym.atDay(1).toString(), ym.plusMonths(1).atDay(1).toString());
    }
}
```
  （启动时可另调一次兜底；`if not exists` 幂等。）
- [ ] **Step 3: 更新 `code-organization.md` §4 条3** — 补例外：「被 usage 消费的计量事件（TokenUsedEvent 等）放 common，因发布方(conversation)同时是 usage 下游(checkQuota)，事件放发布方 api 会成环」。
- [ ] **Verify:** `mvn -pl . -Dtest=ModularityTests test`（Modulith 边界+无环）全绿；`PartitionMaintainer` 生成的 DDL 语法用真 PG 干跑一条验证。

---

## 收尾（实现完成后，非计划步骤，手验，记入自检）

- 本地起服务发一条消息 → 查 `daily_usage`/`llm_call_log` 有行、total_tokens 正确（验事务B内发布+AFTER_COMMIT+@Async 全链路）。
- 把 `daily-token-limit-per-user` 调到极小 → 再发消息应得 `14001/429`（JSON 信封；流式亦 JSON 非 error 事件）。
- `mvn -pl . test` 全量绿（含 ModularityTests）。

## 延后项（不在本计划）

- workflow 入口 checkQuota（模块未建）；Admin 用量看板（前端+admin只读接口+供应商维度+LlmCallCompletedEvent 观测）；
  per-app/per-model 配额；配额值热更新；daily_usage UPSERT 的 Testcontainers 回归。
