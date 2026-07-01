# 自检 · ⑥ usage 配额

本轮目标：把 `QuotaGuard.check` 接上真身——conversation/workflow 入口按「每日 Token 配额」拦截失控调用，
用量经事件异步落库聚合。范围/决策见对话：A1 单事件(conversation 发 `TokenUsedEvent`)、B1 按用户/天、
C1 两表齐建、D 实时查 `daily_usage` 不上缓存、E 失败/取消不计量、无前端。

被迫偏离已报备：`TokenUsedEvent` 放 `common`（非发布方 api/event），因 conversation 已依赖 usage::api
(checkQuota)，事件放 conversation 会成环。待 step6 补进 code-organization.md 规则3 例外。

---

## 步骤 1：Flyway V12 建 `daily_usage` + `llm_call_log`（含月分区）

**做了什么**
- 新增 `server/src/main/resources/db/migration/V12__create_usage_tables.sql`。
- `llm_call_log`：按 `create_time` 月 RANGE 分区，初始建 2026-07 ~ 2026-12 共 6 个分区；
  分区表主键必含分区键 → `pk=(id, create_time)`；token 列 `bigint`；本轮只落 token，观测列(耗时/失败)后补。
- `daily_usage`：`id` bigint identity 主键 + `(user_id, app_id, stat_date)` 唯一索引(作 UPSERT 冲突目标)
  + `(user_id, stat_date)` 索引(支撑「某用户今日跨应用合计」配额查询)；`total_tokens bigint`。
- 跨模块 user_id/app_id/model_id 只存 id、不建外键。

**为什么这么设计**
- 分区：db-standards「高水位只增表第一天就按月分区」，清理=drop 分区，不引 pg_partman。
- 聚合表 + UPSERT：db-standards「配额永远查 daily_usage，禁扫流水」「插或改一律 UPSERT，禁先查后插(竞态)」。
- bigint token：累计值会超 21 亿。

**怎么验证的**
- 无 psql 客户端；将整段 DDL 包在 `BEGIN … ROLLBACK` 里灌进正在运行的 `hify-postgres`(PG16) 干跑：
  ```
  { echo BEGIN;; cat V12…sql; echo ROLLBACK;; } | docker exec -i hify-postgres psql -U hify -d hify -v ON_ERROR_STOP=1
  ```
  结果：8×CREATE TABLE(父表+6分区+daily_usage) + 2×CREATE INDEX 全过，ROLLBACK 干净，EXIT=0。
  → 语法在真 PG16 校验通过，且未污染现有库（真建表交给应用启动时 Flyway）。
- 连库数据行为(UPSERT 累加正确性)按既有约定推迟到 Testcontainers；本步只保证 DDL 可建。

**遗留/TODO**
- 月度补建下月分区的 `@Scheduled`(step5)；不建则 2027-01 起插入会失败。
- `llm_call_log` 观测列与看板查询索引：待观测事件/看板轮设计。

---

## 流程补正：补写 spec + plan（本应在 step1 前）

本轮 brainstorm 定案后**漏了惯例**——直接从 brainstorm 跳进写码(step1/2)，没先写设计与实现计划。
经用户指出后补齐（step1/2 小且可逆，重头 Task 未开工，补写代价低）：
- 设计：`docs/superpowers/specs/2026-07-01-usage-quota-design.md`
- 实现计划：`docs/superpowers/plans/2026-07-01-usage-quota.md`（Task1/2 标 [x] 回填）
已存记忆 `superpowers-spec-plan-workflow` 防再犯。后续 Task 3-7 照 plan 执行。

---

## 步骤 2：`common` 里加 `TokenUsedEvent` record

**做了什么**
- 新增 `server/src/main/java/com/hify/common/event/TokenUsedEvent.java`：
  `(Long userId, Long appId, Long modelId, int promptTokens, int completionTokens)`，纯数据 record 无行为。

**为什么这么设计**
- 放 `common` 而非发布方 conversation 的 api/event：避免 conversation⇄usage 环（详见文档顶部报备）。common 是 OPEN 模块，子包对全模块开放。
- token 用 `int`：单次调用 token 不会溢出 int；`bigint` 只用于 `daily_usage` 的累计值。
- 不加 `totalTokens()` 等派生方法：code-organization「Event 用 record，不可变，不携带行为」，累加由监听器算。

**怎么验证的**
- 无行为的纯数据 record，testing-standards 将「测 record getter」列为假测试/冗余，故本步不单独写测试；
  真实约束在 step3/4（监听器把事件映射成写库、仅成功轮发事件）处测。
- 编译校验并入 step3（监听器 import 本 record 时连编）。

**遗留/TODO**
- 无。

---

## 步骤 3（plan Task 3）：usage 错误码 + 配置

**做了什么**
- `usage/constant/UsageError.java`：`QUOTA_EXCEEDED(14001, 429/TOO_MANY_REQUESTS, "今日 Token 配额已用尽")`，照 ConversationError 范式 implements ErrorCode。
- `usage/config/UsageProperties.java`：`@ConfigurationProperties("hify.usage")` record，字段 `dailyTokenLimitPerUser`。
- `usage/config/UsageConfig.java`：`@EnableConfigurationProperties(UsageProperties)` + `@EnableScheduling`（应用内首次开启调度，供 Task7 分区任务）。
- `application.yml`：`hify.usage.daily-token-limit-per-user`，默认 2_000_000，环境变量 `HIFY_USAGE_DAILY_TOKEN_LIMIT_PER_USER` 可覆盖。

**为什么这么设计**
- 14001/429：api-standards 已登记（只增不改）；错误码与 HTTP 状态在枚举处一次绑定，业务只抛 BizException。
- 限额外化到 yml + env：CLAUDE.md「配置外化、不硬编码」；限额值全局单一来源（不做 per-user 覆盖表，对齐「不做精细配额管理」）。
- @EnableScheduling 放 usage/config：月分区维护是 usage 自己的活。

**怎么验证的**
- `cd server && mvn -o compile` → BUILD SUCCESS，142 源文件全过（含 step2 的 TokenUsedEvent，确认其真编过）。
- 纯配置/枚举无单测（测 getter 属假测试）；真实约束在 Task5/6。

**遗留/TODO**
- 无。

---

## 步骤 4（plan Task 4）：usage 两个 Mapper（首处注解自定义 SQL）

**做了什么**
- `usage/mapper/DailyUsageMapper.java`：`@Select sumTodayByUser`（coalesce sum，走 (user_id,stat_date) 索引）
  + `@Insert upsertAccumulate`（on conflict 累加，database-standards 固定模板）。
- `usage/mapper/LlmCallLogMapper.java`：`@Insert insertLog`（id/create_time 走 DB 默认，create_time 决定分区路由，不显式写）。
- 均为 plain 接口（无实体），`@MapperScan("com.hify.**.mapper")` 自动注册。本项目首处注解式自定义 SQL，不引 XML。

**为什么这么设计**
- UPSERT 是 PG 专属，MyBatis-Plus Wrapper 表达不了 → 注解 SQL；不建实体（本轮不读对象，YAGNI，看板轮再加）。
- 配额只查 daily_usage、不扫流水（database-standards）。

**怎么验证的**
- 真 PG16 干跑（同一 `BEGIN…ROLLBACK` 里先建 V12 表再跑 mapper SQL，不落库）：
  upsert 480 → 再 upsert 300 → `total_tokens=780`（累加正确，两分支都覆盖）；`sumTodayByUser=780`；
  `llm_call_log` insert 成功路由分区、count=1；**EXIT=0**。
- `mvn -o compile` → BUILD SUCCESS（mapper 接口编译过）。
- 对象级连库测试（MyBatis 绑定/事务）延后 Testcontainers。

**遗留/TODO**
- 无。

---

## 步骤 5（plan Task 5）：usage Service + Facade + 监听器（首处真单测）

**做了什么**
- `UsageService`：`checkQuota(userId, appId)`（查 sum ≥ 上限→抛 14001；appId 保留 per-app 扩展）
  + `@Transactional recordUsage(event)`（insertLog + upsertAccumulate 总token，usage 本地事务）。
- `usage/api/UsageFacade` + `UsageFacadeImpl`：对外只暴露同步 checkQuota（写走事件不走 Facade）。
- `UsageEventListener`：`@Async @TransactionalEventListener(AFTER_COMMIT)` on TokenUsedEvent → recordUsage。

**为什么这么设计**
- checkQuota 只读聚合表、同步入口调；recordUsage 异步、AFTER_COMMIT 对齐「成功轮」、不拖主链路（code-organization §4）。
- 边界 `used >= limit` 拦（等于上限即拦，防再消费）。

**怎么验证的**
- `mvn -o -Dtest=UsageServiceTest,UsageEventListenerTest test` → **Tests run: 5, Failures: 0, Errors: 0**：
  - checkQuota 三态：未达(limit-1)放行 / 刚好等于(limit)抛 QUOTA_EXCEEDED / 超过(limit+1)抛。
  - recordUsage：insertLog(...,300,180) 调 1 次 + upsertAccumulate(...,480) 调 1 次（总 token=prompt+completion）。
  - 监听器：onTokenUsed 委托 recordUsage 一次。
- AFTER_COMMIT/@Async 的真实触发时机进 Task6 后手验（全链路发消息查库）。

**遗留/TODO**
- 无。

---

## 步骤 6（plan Task 6）：conversation 接线（拦截 + 发布点）

**做了什么**
- `QuotaGuard`：注入 `UsageFacade`，`check` 委托 `checkQuota`（删空锚点 TODO）。conversation 白名单已含 usage::api。
- `ConversationStore`：注入 `ApplicationEventPublisher`；`appendAssistant` 扩参 `(…, userId, appId, modelId)`，
  **事务B 内** `publishEvent(new TokenUsedEvent(...))`——AFTER_COMMIT 才会触发；失败/取消不到此处，天然不计量。
- `ConversationService`：send / sendStream 两处 appendAssistant 调用补 `current.userId(), appId, app.modelId()`。

**为什么这么设计**
- 发布点选 appendAssistant（事务B）而非编排层：编排层无 @Transactional，在那 publish 会被 AFTER_COMMIT 丢弃；
  且 send + sendStream 都过此处，一处覆盖同步+流式，失败/取消天然不计（决策 E/H）。

**怎么验证的**
- `mvn -o -Dtest=ConversationServiceTest,ConversationStoreTest,UsageServiceTest,UsageEventListenerTest test`
  → **Tests run: 28, Failures: 0, Errors: 0**。
  - 新增 `send_配额超额_14001先行_不落库不调模型`：checkQuota 抛出 → 不 findRunnableChatApp/openTurn/invoke。
  - `ConversationStoreTest.appendAssistant…发TokenUsedEvent`：捕获事件断言 userId/appId/modelId + token 透传。
  - 既有 conversation 测试全部更新签名后不回归（含流式半截不落、取消不落等）。
- **QuotaGuardTest 故意不写**：单行转发属冗余/假测试（testing-standards）；行为已被上述两测双向覆盖。
- AFTER_COMMIT + @Async 全链路真实触发进收尾手验。

**遗留/TODO**
- ConversationControllerTest 未在本步单独跑（签名改在 service/store 内部，控制器未动）→ Task7 全量跑覆盖。

---

## 步骤 7（plan Task 7）：月分区维护 + 边界校验 + 文档

**做了什么**
- `LlmCallLogMapper.createMonthlyPartition`（`@Update`，`${}` DDL，name/from/to 由日期算、非用户输入）。
- `PartitionMaintainer`：`@Scheduled(cron "0 30 0 1 * *")` 每月补建 + `@EventListener(ApplicationReadyEvent)` 启动兜底；
  每次向前建当月起 4 个月（MONTHS_AHEAD=3，冗余覆盖漏跑/停机），`if not exists` 幂等。
- `code-organization.md §4 条3`：补「被 usage 消费的计量事件放 common（防成环）」+「须在发布方事务内发（否则 AFTER_COMMIT 不触发）」两条例外。

**为什么这么设计**
- V12 只建到 2026-12，不补建则 2027-01 起写入无落点 → 定时+启动双保险；向前多建几月抗漏跑。
- DDL 无法用 `#{}` 占位符（标识符/建表），值全由服务端日期计算，`${}` 安全。

**怎么验证的**
- 分区 DDL 真 PG 干跑（2027-01 例）：建成 + 幂等重建仅 NOTICE 跳过不报错 + insert 按 create_time 正确路由；EXIT=0。
- **全量 `mvn -o test` → Tests run: 277, Failures: 0, Errors: 0, BUILD SUCCESS**（含 ConversationControllerTest 7、LayerRulesTest 5）。
- **`ModularityTests` 单跑通过** → 模块边界（usage 依赖仍 {common,infra}）+ 无环得证：事件放 common 的决策正确。

**遗留/TODO（收尾手验，需起服务+真供应商 key，非自动化）**
- 全链路：登录→发一条消息→查 daily_usage/llm_call_log 有行、total_tokens 正确（验事务B内发布 + AFTER_COMMIT + @Async 真实触发）。
- 把限额调极小→再发应得 14001/429（JSON 信封；流式亦 JSON 非 error 事件）。

---

## 本轮完成情况

⑥ usage 配额后端闭环完成：Task 1-7 全绿，全量 277 测试通过、ModularityTests 无环。
**未做（延后，spec §8 有记）**：workflow 入口接线（模块未建）、Admin 用量看板（前端+admin只读接口+供应商维度+
LlmCallCompletedEvent 观测）、per-app/per-model 配额、daily_usage UPSERT 的 Testcontainers 回归、上述收尾手验。
工作区改动未提交（按惯例等用户确认后再 commit/push）。
