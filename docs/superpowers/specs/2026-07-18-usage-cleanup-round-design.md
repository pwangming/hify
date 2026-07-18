# 留账清理轮设计：配额切表废弃 daily_usage + llm_call_log 观测列 + 日期组件风源调查

日期：2026-07-18
状态：已与用户逐项确认
背景：看板轮（V26）与 E2E 旅程扩充轮的留账清理。V26 建了 `usage_stat_daily` 并全量回填后，
`daily_usage` 成为明知的冗余（当时 spec 已留账「配额切过去后废弃」）；V12 建 `llm_call_log`
时留账「耗时/失败观测列待后续 additive 加」；看板轮验收时日期组件全宽拉伸风源未定位，
现场留着三保险 `!important` 补丁（UsageDashboard.vue:210）。本轮三账一起清。

## 0. 范围拍板（与用户逐项确认）

| 决策点 | 结论 | 为什么 |
|---|---|---|
| ① daily_usage 处置 | **V27 直接 drop**，代码删 DailyUsageMapper，三写变两写 | 历史数据无损（V26 已从流水全量回填 usage_stat_daily，daily_usage 无独有数据）；停写保留=僵尸表易误读，双写观察=对 20-50 人内部系统过度谨慎 |
| ② 失败调用记录 | **成功+失败都落流水**；聚合/配额仅成功累加 | 失败调用目前完全不留痕迹只能翻应用日志，排障供应商故障是观测列的主要价值；只补成功耗时=观测只剩一半 |
| ② 粒度 | 维持「一行 = 一轮」（Agent 一轮内多次底层调用记整轮耗时） | 与 llm_call_log 既有语义一致；provider 层全量观测（每次底层调用一行）与现表语义冲突、超出留账范围，被否 |
| ③ 执行方式 | **Claude 终审阶段本 session 查，timebox 30 分钟**，不进 Codex 提示词 | 交互式取证调查与 Codex 批量执行模式不匹配，Codex 无浏览器取证能力大概率产出猜测性结论 |
| 轮次组织 | ①② 合一份 spec/plan 交 Codex；③ 独立收尾 | ①② 都动 `UsageService.recordUsage` 同一处事务，拆开要两次碰同一文件 |

## 1. 数据模型与迁移（V27 单脚本，旧脚本一字不动）

```sql
-- ② llm_call_log 观测列（additive；分区父表 alter 自动传播到子分区）
alter table llm_call_log
    add column duration_ms integer,
    add column status      text not null default 'success'
        check (status in ('success', 'failed')),
    add column error_code  text;

-- ① 废弃 daily_usage（配额检查已切 usage_stat_daily；历史已在 V26 回填，无独有数据）
drop table daily_usage;
```

- `duration_ms` **可空**：历史行为 null（与 V26 `source` 列同样处理），前端显示「—」。
  integer 足够（单次轮耗时远小于 2^31 ms）。
- `status` **not null default 'success'**：V27 前只有成功轮落行，default 回填历史行语义正确。
- `error_code` 可空：成功行为 null；失败行存**异常类简名**（如 `TimeoutException`、
  `CallNotPermittedException`），`BizException` 则存其错误码数字（如 `12002`）。
  **不存异常 message**——防供应商返回体中的敏感信息进库。
- drop 表安全性已清点：`daily_usage` 全库引用面 = usage 模块自身（DailyUsageMapper、
  UsageService）+ 4 个测试文件；E2E reset 脚本、scripts/ 均不碰它。

## 2. 配额读切换（①）

`UsageService.checkQuota` 从 `daily_usage.sumTodayByUser` 改为查 `usage_stat_daily`：

```sql
select coalesce(sum(prompt_tokens + completion_tokens), 0)
from usage_stat_daily where user_id = #{userId} and stat_date = #{statDate}
```

- 新 `@Select` 加在 `UsageStatDailyMapper`（写侧 UPSERT 已在此，读写同 Mapper）；
  `DailyUsageMapper` 整文件删除。
- 语义完全等价：配额 = 用户当日跨应用全部 token 合计（prompt+completion）。
- **不加新索引**：`usage_stat_daily_dim_uq (user_id, app_id, model_id, stat_date)` 首列即
  user_id，按用户前缀扫描的行数 = 该用户当天的应用×模型组合，量级个位数到几十，足够。
- 归日口径不变：两表的 stat_date 都由 `recordUsage` 里同一个 `LocalDate.now()`（北京时间）写入，
  切换无时区漂移。

## 3. 事件与发布点（②）

### 3.1 TokenUsedEvent 扩字段（内部事件，可改）

```java
public record TokenUsedEvent(
        Long userId, Long appId, Long modelId,
        int promptTokens, int completionTokens, String source,
        long durationMs, boolean success, String errorCode) { ... }
```

- 注释语义从「Token 用量已产生」改为「一次 LLM 调用轮已结束（成或败）」。
- 加两个静态工厂增可读性：`success(...)`（errorCode=null）与
  `failure(userId, appId, modelId, source, durationMs, errorCode)`（token 记 0——失败轮的
  部分消耗无法可靠取得，且本就不进账）。

### 3.2 发布点全景（成功 2 处、失败 4 处）

计时口径：起点 = 拿到 ChatClient 之后、进入 LLM 调用编排之前；终点 = 发布事件时。
`System.nanoTime()` 差值换算 ms。

| 路径 | 成功发布 | 失败发布 | 事务上下文 |
|---|---|---|---|
| conversation 同步 `send` | ConversationStore.appendAssistant（事务 B 内，现状不动位置） | `send` 外层 catch（孤儿清理旁） | 失败点**无活跃事务** → fallbackExecution 立即执行 ✓ |
| conversation SSE 流式 | 同上（done 回调里的 appendAssistant） | `sendStream` 的 `onErrorResume` | 同上 ✓ |
| Agent 流式 | 同上 | `sendStreamAgent` 的 catch | 同上 ✓ |
| workflow LLM 节点 | LlmNodeExecutor（无事务，现状） | LlmNodeExecutor 的 catch（`NodeExecutionException` rethrow 之前） | 引擎禁事务，天然无事务 ✓ |

- **铁律回顾（看板轮黑洞的镜像教训）**：失败事件绝不能在会回滚的事务内发布——AFTER_COMMIT
  监听器在回滚时不触发，失败行会静默丢。上表四个失败点都在事务外，`fallbackExecution=true`
  正好接住。
- durationMs 传递：conversation 侧计时在 ConversationService/编排层，随参数传入
  `appendAssistant`（签名加 1 参）。**改签名必须同 Task 内改完全部调用点**
  （plan-atomic-signature-boundary 铁律；appendAssistant 有 8/9 参重载与自调用史，见看板轮记忆）。
- Agent 整轮多次底层调用 = 一行、durationMs = 整轮耗时（含工具执行时间，注释写明口径）。
- 边界案例：LLM 成功但事务 B 落库失败 → 成功事件随回滚丢弃（正确），外层 catch 补发失败事件，
  该轮记为 failed（可接受：用户视角这轮确实失败了）。

### 3.3 监听器与落库分支

`UsageService.recordUsage`：

- 流水 `llm_call_log`：**每个事件都插**（带 duration_ms/status/error_code）；
- 聚合 `usage_stat_daily`：**仅 success 时 UPSERT 累加**（失败不进配额、不进看板、
  不增 call_count，计费语义不变）。

## 4. Admin API 与前端（②）

- 调用日志接口响应条目 **additive** 加 `durationMs`（可空）、`status`、`errorCode`（可空）
  三字段（api-standards：已发布响应只增不改；Long 序列化为字符串的规则不涉及——这三个是
  int/string）。
- `CallLogList.vue` 加两列：**耗时**（null 显示「—」，数值显示如 `1234 ms`）、
  **状态**（el-tag：成功 success 色 / 失败 danger 色，失败时单元格并列展示 errorCode）。
  不加状态筛选器（YAGNI，先看列够不够用）。
- 看板总览/趋势/排行不动（聚合表本就只进成功数据）。

## 5. 测试与验收

- 后端 TDD（先红后绿，mvn 只看退出码）：
  - `UsageServiceTest`（mock）：checkQuota 改查 statDailyMapper；recordUsage 成功=两写、
    失败=只插流水不动聚合。
  - Db 测试（Testcontainers）：`UsageRecordDbTest` 改断言（流水含新列、聚合表累加、
    daily_usage 不复存在）；`UsageEventFlowDbTest` / `ConversationUsageFlowDbTest` 去掉
    daily_usage 探针、补失败事件链路断言（失败事件 → 流水 status='failed'，聚合零变化）。
  - 迁移脚本由 Flyway 在 Db 测试容器里自然执行验证。
- 前端 vitest TDD：CallLogList 两新列（含 null 耗时显示「—」、失败行红 tag）。
- 回归：全量 `mvn verify`（含 ModularityTests/ArchUnit）+ `pnpm test` + `pnpm e2e`（4 passed）。
- 人工验收：临时把某模型 BaseURL 改错发起对话 → 调用日志页出现红色失败行、有耗时与
  errorCode；改回后成功行耗时正常显示；配额/看板数字不被失败行污染。

## 6. ③ 日期组件风源调查（Claude 本 session，终审阶段）

- 对象：UsageDashboard.vue:210 三保险 `!important` 才压住的日期 range 组件全宽拉伸。
- 方法：Playwright 开真实页面，取计算样式与命中规则链，逐层排查看板轮已排除之外的假设
  （全局 SCSS、EP 版本级联、父容器 flex 上下文等）。
- timebox 30 分钟，两个出口：查到根因 → 拿掉 `!important` 改根治写法（补 vitest 或截图实证）；
  查不到 → 调查实录归档 `docs/`（已试假设+排除证据），永久收账。

## 7. 不做的（防将来重议）

- provider 层全量观测事件（每次底层调用一行）——粒度与现表冲突，等真实需要再议新表。
- 失败告警、状态筛选器、看板失败率图——先看列够不够用。
- 失败轮的部分 token 计量——取不可靠，且失败不进账是既定计费语义。
- daily_usage 停写观察期 / 双写过渡——已拍板直接 drop。
