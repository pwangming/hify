# ⑥ usage 配额 — 设计

> 轮次：usage 模块第 1 轮（conversation 单轮③ → 多轮④ → 流式⑤ → **usage 配额⑥**）。
> 前置状态：⑤ SSE 流式已合并 main（18 提交，未 push），工作区干净；`QuotaGuard.check` 是 conversation
> 里的空锚点，已在 `send`/`sendStream` 最前调用（配额先行）。
> 必读规范：code-organization.md §1/§4（usage 边界、事件规则、配额只在入口）、data-model.md §1（两表）、
> database-standards.md（大表月分区、UPSERT 模板、bigint token、聚合表代替扫流水）、
> api-standards.md（14001/429、SSE 连接前错误走 JSON）、llm-resilience.md §4、testing-standards.md。

## 1. 目标与背景

把空锚点 `QuotaGuard.check` 接上真身：conversation 收消息入口按「每日 Token 配额」拦截失控调用
（**防异常 Agent 循环刷爆账单**，非精确计费）。用量经事件**异步**落库聚合，不拖慢对话主链路、
不被 LLM 调用的事务包裹。usage 是零依赖叶子模块（段 14xxx），只通过 Facade + 事件与外界交互。

不在本轮：workflow 入口接线（模块未建，留 TODO）；Admin 用量看板（前端页 + admin 只读接口 +
供应商维度聚合 + `LlmCallCompletedEvent` 观测数据）——单独一轮；per-app / per-model 配额；配额值热更新。

## 2. 决策记录（brainstorm 拍板）

| # | 决策点 | 结论 |
|---|---|---|
| A | 事件设计 | **单事件**：conversation 发 `TokenUsedEvent`（带 user/app/model/token）；usage 监听落两表。provider 的 `LlmCallCompletedEvent`（耗时/失败率，运维观测）留给后续看板轮，消除文档命名分叉。 |
| B | 配额口径 | **按用户/天**（`sum(total_tokens) where user_id=? and stat_date=today` ≥ 限额 → 拦）。限额值来自 `application.yml` 全局默认（不做 per-user/per-app 覆盖表，对齐「不做精细配额管理」）。per-app 以后加只是多一条 SQL。 |
| C | 建表范围 | **两表齐建**（`daily_usage` + `llm_call_log`）：同一事件顺手写两张，分区地基一次铺好。 |
| D | 「今日已用」读法 | **实时查 `daily_usage`**（小表、有索引、文档指定的快路径），**不上 Caffeine**。异步落库致 daily_usage 慢一拍 → 接受「软超额一点点」（防失控足够，非精确计费）。 |
| E | 失败/流式部分 | **仅成功轮计量**：事件在 `appendAssistant`（事务B）内发，失败/取消根本不到此，天然不计入。 |
| F | 前端 | **无**。看消耗属 Admin 看板，另一轮。 |
| G | 事件放哪（偏离规则3） | 放 **`common`** 而非发布方 conversation.api：conversation 已依赖 usage::api(checkQuota)，事件放 conversation 会致 usage 反向依赖成环（违反 DAG 规则7）。补进 code-organization §4 例外。 |
| H | 发布点 | 在 `ConversationStore.appendAssistant`（**事务B 内**）发 `publishEvent`，使 `@TransactionalEventListener(AFTER_COMMIT)` 正确触发（`ConversationService` 无 @Transactional，在那发会被丢弃）。透传 userId/appId/modelId 进 appendAssistant，send + sendStream 一处覆盖。 |
| I | 自定义 SQL 风格 | 全项目首处自定义 SQL：用 **Mapper 注解 `@Insert`/`@Select`**（UPSERT 是 PG 专属，Wrapper 表达不了），**不引 XML**，最小侵入。 |

## 3. 数据模型（V12，已建）

- `llm_call_log`（流水，高水位只增）：`(id, create_time)` 主键，按 `create_time` **月 RANGE 分区**，
  初始 2026-07~12 六分区；列 user_id/app_id/model_id/prompt_tokens/completion_tokens(bigint)。看板查此表勿大范围实时聚合。
- `daily_usage`（聚合）：`id` bigint identity 主键 + `(user_id,app_id,stat_date)` **唯一索引**（UPSERT 冲突目标）
  + `(user_id,stat_date)` 索引（撑配额查询）；`total_tokens bigint`。**配额只查本表**。

## 4. 后端编排与时序

**入口拦截**（同步，对话前）：
```
ConversationService.send / sendStream 首行：quotaGuard.check(userId, appId)
  └─ QuotaGuard.check → usageFacade.checkQuota(userId, appId)
       └─ used = dailyUsageMapper.sumTodayByUser(userId, LocalDate.now())
          if (used >= props.dailyTokenLimitPerUser())  throw BizException(UsageError.QUOTA_EXCEEDED /*14001/429*/)
```
- 连接建立**前**抛（sendStream 方法体同步执行到 check 才组装 Flux）→ 流式配额 429 走**普通 JSON 信封**（api-standards §3.3），非 SSE error 事件。✓

**计量落库**（异步，对话后）：
```
ConversationStore.appendAssistant(cid, content, pTok, cTok, userId, appId, modelId)  // 事务B
  ├─ 落 assistant 消息 + touch 会话（原逻辑）
  └─ publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, pTok, cTok))   // 事务内发布

usage: UsageEventListener  @Async @TransactionalEventListener(AFTER_COMMIT)
  └─ usageService.recordUsage(event)   // usage 本地新事务
       ├─ llmCallLogMapper.insert(...)                       // 一行流水（id/create_time 默认）
       └─ dailyUsageMapper.upsertAccumulate(userId, appId, LocalDate.now(), pTok+cTok)  // ON CONFLICT 累加
```
- AFTER_COMMIT：只有事务B（assistant 消息）真提交后才落用量，与「成功轮」对齐；MDC 经 `MdcTaskDecorator` 传到异步线程。
- 0-token 事件（供应商未回 usage）照发：流水记一次调用、daily_usage +0，不影响配额。
- 午夜边界：check 与 record 各自 `LocalDate.now()`，跨零点极小误差，配额场景可忽略。

## 5. 模块边界与依赖（Modulith 必绿）

- `usage` 白名单仍 `{common, infra}`——不新增任何模块依赖。监听的 `TokenUsedEvent` 在 common，故 usage 不 import 任何模块 api。
- `conversation` → `usage::api`（checkQuota，已在白名单）+ 依赖 common 的事件类型。**无环**。
- `UsageFacade`（api，同步入口）+ `TokenUsedEvent`（common，通知）——同步走 Facade、通知走事件，符合 §4 条2/3。

## 6. 错误码与契约

- 新增 `UsageError.QUOTA_EXCEEDED = 14001`，HttpStatus **429（TOO_MANY_REQUESTS）**，msg「今日 Token 配额已用尽」（api-standards 已登记 14001/429，只增不改）。
- checkQuota 抛 `BizException` → 全局异常处理器转 `Result` 失败信封（复用现成）。

## 7. 测试计划

**后端（mock + ArchUnit/Modulith，连库测试延后；自定义 SQL 用真 PG 干跑验语法）**：
- `UsageServiceTest`：mock `DailyUsageMapper.sumTodayByUser` 返回 未超/刚好等于/已超 三态，断言 checkQuota
  分别放行/放行/抛 `BizException(QUOTA_EXCEEDED)`（边界 `used >= limit` 拦，`used < limit` 放）；
  `recordUsage(event)` 断言调用 `llmCallLogMapper.insert` 一次 + `dailyUsageMapper.upsertAccumulate(..., pTok+cTok)` 一次。
- `UsageEventListenerTest`：喂 `TokenUsedEvent` 断言委托 `usageService.recordUsage`。
- `ConversationServiceTest`：mock `usageFacade.checkQuota` 抛 14001 → send/sendStream 透传抛出（不落库、不调用 LLM）；
  正常路径断言 `appendAssistant` 收到 userId/appId/modelId（发布点参数透传）。
- `ConversationStoreTest`（若有）：`appendAssistant` 成功后 `publishEvent` 被调用一次、失败路径不发。
- **自定义 SQL 语法验证**：`sumTodayByUser` / `upsertAccumulate`(含二次累加) / `llm_call_log` insert 三条，
  包 `BEGIN…ROLLBACK` 灌 `hify-postgres`(PG16) 干跑（同 V12 验证法），确认可执行且 UPSERT 二次累加 total_tokens 正确。
- ArchUnit/Modulith：`ModularityTests` 全绿，`usage` 依赖仍 `{common,infra}`、无环。

**判 mvn 结果**：看 Surefire `Tests run/Failures/Errors`，不 grep BUILD SUCCESS、不加 `-q`（系统 mvn，pom 在 server/）。

## 8. 延后项与风险

**延后**：workflow 入口 checkQuota（模块未建，留 TODO 锚点）；Admin 用量看板（前端 + admin 只读接口 +
供应商维度聚合口径 + `LlmCallCompletedEvent` 观测事件）；per-app/per-model 配额；配额值热更新；
`daily_usage` UPSERT 累加正确性的 Testcontainers 回归。

**风险/手验点**（追加到 `docs/self-check-usage-quota.md`）：
1. 事件在事务B内发布 + AFTER_COMMIT + @Async 全链路实测触发（本地发一条消息后查 daily_usage/llm_call_log 有行）。
2. 月度 `@Scheduled` 补下月分区实际生效（否则 2027-01 起写入失败）。
3. 「软超额」量级可接受（异步落库延迟 vs 突发并发）——观察即可，非阻塞。
