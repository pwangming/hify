# 用量与成本看板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理控制台「用量与成本看板」：Token/费用总览+按天趋势+三维排行+调用日志游标查询；模型可配单价（元/百万 token）。

**Architecture:** 新建看板聚合表 `usage_stat_daily`（日×用户×应用×模型），UsageEventListener 同事务三步双写；费用后端 BigDecimal 现算（usage 依赖白名单新开 provider::api 取单价）；名称解析前端拼装；趋势图 ECharts 按需引入。Spec：`docs/superpowers/specs/2026-07-17-admin-usage-dashboard-design.md`。

**Tech Stack:** Spring Boot 3 + MyBatis(-Plus) 注解 SQL + Flyway + Testcontainers；Vue 3 + Element Plus + ECharts + vitest。

## Global Constraints

- 判定 mvn 测试结果**只看退出码**（`echo exit=${PIPESTATUS[0]}` 或 `$?`），禁止 grep BUILD SUCCESS（`-q` 会静音）。
- 前端 TDD：新代码先写失败测试，测试放同目录 `__tests__/`；命令在 `web/` 下跑 `pnpm test` / `pnpm typecheck`。
- 后端连库测试类名以 `Test` 结尾（随 `mvn verify` 跑，`*IT` 不会被 surefire 默认拾取），继承 `com.hify.support.PgIntegrationTest`，运行前提本机 Docker 已启动。
- JSON：Long 全局序列化为 string（前端类型写 `string`）；**金额合计（estimatedCost）DTO 字段直接用 String**（服务端 `toPlainString()`），模型单价（inputPrice/outputPrice）用 JSON number（表单要数字输入，数值小无精度问题）。
- DTO 禁止 import entity（ArchUnit 守护）；跨模块共享 DTO 放 `api` 顶层包（Modulith 1.4.1 不暴露 api/dto 子包）。
- admin 路由 `/api/v1/admin/usage/**`（带模块段）；不用 PATCH；错误码只复用通用段（10001/10004/10005），不新增 14xxx。
- 已合并迁移脚本禁改，本轮只新增 `V26__usage_dashboard.sql`。
- Controller 禁 try-catch/手写失败 Result；校验注解只写在 Request DTO / 参数上。
- 前端优先 Element Plus 组件；ECharts 只允许 `TrendChart.vue` 一个组件直接触碰。
- 时间口径：`stat_date` 按北京时间归日（生产容器 `TZ=Asia/Shanghai`，`LocalDate.now()` 与现有 daily_usage 口径一致；回填 SQL 显式 `at time zone 'Asia/Shanghai'`）。
- 费用口径：`两个单价都非空`才算已配价；未配价（含只配一半、模型已删）的流量费用计 0 且 `costIncomplete=true`。计算 `prompt×input/1e6 + completion×output/1e6`，BigDecimal scale=4 HALF_UP。

---

### Task 1: V26 迁移脚本 + 数据文档补账

**Files:**
- Create: `server/src/main/resources/db/migration/V26__usage_dashboard.sql`
- Create: `server/src/test/java/com/hify/usage/mapper/UsageDashboardMigrationTest.java`
- Modify: `docs/architecture/data-model.md`（表清单加 `usage_stat_daily`，18→19 张；ai_model/llm_call_log 字段变更备注）
- Modify: `docs/architecture/er-diagram.dot`（usage 簇加 `usage_stat_daily` 节点；跨模块弱引用虚线 user/app/model）

**Interfaces:**
- Produces: 表 `usage_stat_daily(id, stat_date, user_id, app_id, model_id, prompt_tokens, completion_tokens, call_count, create_time, update_time)`，唯一索引 `usage_stat_daily_dim_uq(user_id, app_id, model_id, stat_date)`；`ai_model.input_price/output_price numeric(10,4)`；`llm_call_log.source text`。

- [ ] **Step 1: 写失败的迁移验证测试**

```java
package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V26 迁移验证（连库）：表/列/约束真实存在 + 回填 SQL 语义正确。
 * 回填断言用「与 V26 相同的 insert-select 文本」在测试内重跑（迁移在空库执行时回填 0 行，
 * 语义只能这样验证；两处 SQL 保持一字不差，改一处必须同步另一处）。
 */
class UsageDashboardMigrationTest extends PgIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void usage_stat_daily表与唯一索引存在() {
        Integer cols = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'usage_stat_daily' "
                        + "and column_name in ('stat_date','user_id','app_id','model_id',"
                        + "'prompt_tokens','completion_tokens','call_count')", Integer.class);
        assertThat(cols).isEqualTo(7);
        Integer uq = jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'usage_stat_daily' "
                        + "and indexname = 'usage_stat_daily_dim_uq'", Integer.class);
        assertThat(uq).isEqualTo(1);
    }

    @Test
    void ai_model加了单价列_llm_call_log加了source列() {
        Integer priceCols = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'ai_model' "
                        + "and column_name in ('input_price','output_price')", Integer.class);
        assertThat(priceCols).isEqualTo(2);
        Integer sourceCol = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'llm_call_log' "
                        + "and column_name = 'source'", Integer.class);
        assertThat(sourceCol).isEqualTo(1);
    }

    @Test
    void 回填SQL按北京时间归日聚合() {
        // 造两天边界数据：北京时间 2026-07-16 23:30 与 2026-07-17 00:30（UTC 表示）
        jdbc.update("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, create_time) "
                + "values (1, 2, 3, 100, 50, '2026-07-16 15:30:00+00'), "
                + "       (1, 2, 3, 200, 80, '2026-07-16 16:30:00+00')");
        // 与 V26 回填一字不差的 SQL（目标改临时表以免与监听器写入混淆）
        jdbc.execute("create temp table backfill_check as "
                + "select (create_time at time zone 'Asia/Shanghai')::date as stat_date, "
                + "user_id, app_id, model_id, sum(prompt_tokens) as prompt_tokens, "
                + "sum(completion_tokens) as completion_tokens, count(*) as call_count "
                + "from llm_call_log group by 1, 2, 3, 4");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from backfill_check order by stat_date");
        assertThat(rows).hasSize(2); // 跨北京日界拆成两行
        assertThat(rows.get(0).get("stat_date").toString()).isEqualTo("2026-07-16");
        assertThat(rows.get(1).get("stat_date").toString()).isEqualTo("2026-07-17");
    }
}
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageDashboardMigrationTest | tail -15; echo exit=${PIPESTATUS[0]}`
Expected: exit=1，失败原因是 `usage_stat_daily` 不存在 / 列数不符。

- [ ] **Step 3: 写 V26 迁移脚本**

```sql
-- V26：用量与成本看板（usage/provider 模块）。spec：2026-07-17-admin-usage-dashboard-design.md。
-- ① ai_model 单价（元/百万 token，可空=未配价，0=免费）；② llm_call_log 补来源列（历史行留 null）；
-- ③ 看板聚合表（database-standards §聚合表代替扫流水：看板查此表，不扫流水）；④ 存量回填。

-- ① 模型单价（provider 模块）
alter table ai_model
    add column input_price  numeric(10,4) check (input_price >= 0),
    add column output_price numeric(10,4) check (output_price >= 0);
comment on column ai_model.input_price is '输入单价，元/百万token；null=未配置（费用计0并标注不完整）';
comment on column ai_model.output_price is '输出单价，元/百万token；null=未配置';

-- ② 调用来源（usage 模块；分区父表 alter 自动传播到子分区）
alter table llm_call_log
    add column source text check (source in ('conversation', 'workflow'));
comment on column llm_call_log.source is '调用来源；历史行为 null（V26 前无此列）';

-- ③ 看板聚合表：日×用户×应用×模型。prompt/completion 拆开存（输入输出单价不同才算得了钱）。
--    call_count=成功轮次数（一条 TokenUsedEvent 计 1，Agent 一轮内部多次 LLM 调用算 1 次）。
--    普通表不分区（行数=活跃维度组合，量级小）；stat_date 按北京时间归日（与 daily_usage 口径一致）。
create table usage_stat_daily (
    id                bigint      generated always as identity primary key,
    stat_date         date        not null,
    user_id           bigint      not null,
    app_id            bigint      not null,
    model_id          bigint      not null,
    prompt_tokens     bigint      not null default 0,
    completion_tokens bigint      not null default 0,
    call_count        bigint      not null default 0,
    create_time       timestamptz not null default now(),
    update_time       timestamptz not null default now()
);
comment on table usage_stat_daily is '看板聚合表（usage 模块）：日×用户×应用×模型；监听器与流水同事务 UPSERT；看板只查此表';
create unique index usage_stat_daily_dim_uq
    on usage_stat_daily (user_id, app_id, model_id, stat_date);
create index usage_stat_daily_date_idx on usage_stat_daily (stat_date);

-- ④ 存量回填：历史流水一行不丢（UsageDashboardMigrationTest 有同文本 SQL 验证语义，改此处必须同步测试）
insert into usage_stat_daily (stat_date, user_id, app_id, model_id,
                              prompt_tokens, completion_tokens, call_count)
select (create_time at time zone 'Asia/Shanghai')::date as stat_date,
       user_id, app_id, model_id,
       sum(prompt_tokens) as prompt_tokens,
       sum(completion_tokens) as completion_tokens,
       count(*) as call_count
from llm_call_log
group by 1, 2, 3, 4;
```

- [ ] **Step 4: 跑测试确认绿灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageDashboardMigrationTest | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0，`Tests run: 3, Failures: 0`。

- [ ] **Step 5: 补数据文档**

`docs/architecture/data-model.md`：
- 表清单（§1）usage 模块段加一行：`usage_stat_daily | 看板聚合：日×用户×应用×模型，UPSERT 累加 | 监听 TokenUsedEvent 与流水同事务双写`；文档内所有「N 张表」计数处 +1（先 grep 现值再改，不要凭记忆写数字）。
- llm_call_log 行备注补「V26 加 source 列（conversation/workflow，历史 null）」；ai_model 行备注补「V26 加 input_price/output_price（元/百万 token，可空）」。
- 「刻意不存在的表」若列了「按模型聚合表」需删除该条（现在存在了）；没列则不动。

`docs/architecture/er-diagram.dot`：usage 簇内加 `usage_stat_daily` 节点，虚线弱引用到 sys_user/app/ai_model（与 llm_call_log 同款画法）。重新生成：
Run: `cd /home/wang/playlab/hify/docs/architecture && npx -y @hpcc-js/wasm-graphviz-cli -T svg er-diagram.dot > er-diagram.svg`

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/resources/db/migration/V26__usage_dashboard.sql server/src/test/java/com/hify/usage/mapper/UsageDashboardMigrationTest.java docs/architecture/data-model.md docs/architecture/er-diagram.dot docs/architecture/er-diagram.svg
git commit -m "feat(usage,provider): V26 看板聚合表+模型单价+调用来源列，含存量回填与连库验证"
```

---

### Task 2: TokenUsedEvent 加 source + 监听器三步双写（签名原子边界：事件字段+全部构造点一个 Task 内完成）

**Files:**
- Modify: `server/src/main/java/com/hify/common/event/TokenUsedEvent.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java:119`
- Modify: `server/src/main/java/com/hify/workflow/service/engine/LlmNodeExecutor.java:52-53`
- Modify: `server/src/main/java/com/hify/usage/mapper/LlmCallLogMapper.java`（insertLog 加 source 列）
- Create: `server/src/main/java/com/hify/usage/mapper/UsageStatDailyMapper.java`
- Modify: `server/src/main/java/com/hify/usage/service/UsageService.java`（recordUsage 第三步）
- Modify: `server/src/test/java/com/hify/usage/service/UsageServiceTest.java:60`、`server/src/test/java/com/hify/usage/service/UsageEventListenerTest.java:16`（构造点补 source）
- Modify: `server/src/test/java/com/hify/conversation/service/ConversationStoreTest.java:124-141`、`server/src/test/java/com/hify/workflow/service/engine/LlmNodeExecutorTest.java:63-66`（断言 source 值）
- Create: `server/src/test/java/com/hify/usage/service/UsageRecordDbTest.java`

**Interfaces:**
- Consumes: Task 1 的 `usage_stat_daily` 表与 `llm_call_log.source` 列。
- Produces: `TokenUsedEvent(Long userId, Long appId, Long modelId, int promptTokens, int completionTokens, String source)` + 常量 `TokenUsedEvent.SOURCE_CONVERSATION="conversation"` / `SOURCE_WORKFLOW="workflow"`；`UsageStatDailyMapper.upsertAccumulate(userId, appId, modelId, statDate, promptTokens, completionTokens)`。

- [ ] **Step 1: 改 TokenUsedEvent（record 加字段+常量），并修全部 4 个构造点**

`TokenUsedEvent.java`（javadoc 保留，record 签名与常量如下）：

```java
public record TokenUsedEvent(
        Long userId,
        Long appId,
        Long modelId,
        int promptTokens,
        int completionTokens,
        String source
) {
    /** source 合法值（与 llm_call_log.source check 约束一字不差）。 */
    public static final String SOURCE_CONVERSATION = "conversation";
    public static final String SOURCE_WORKFLOW = "workflow";
}
```

四个构造点（全量 grep 确认无第五处：`grep -rn "new TokenUsedEvent" server/src`）：

```java
// ConversationStore.java:119
publisher.publishEvent(new TokenUsedEvent(userId, appId, modelId, promptTokens, completionTokens,
        TokenUsedEvent.SOURCE_CONVERSATION));

// LlmNodeExecutor.java:52-53
events.publishEvent(new TokenUsedEvent(ctx.userId(), ctx.appId(), modelId,
        result.promptTokens(), result.completionTokens(), TokenUsedEvent.SOURCE_WORKFLOW));

// UsageServiceTest.java:60
service.recordUsage(new TokenUsedEvent(7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_CONVERSATION));

// UsageEventListenerTest.java:16
TokenUsedEvent event = new TokenUsedEvent(7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_CONVERSATION);
```

两个 captor 测试补断言（在既有断言后各加一行）：

```java
// ConversationStoreTest（appendAssistant 用例）
assertThat(e.source()).isEqualTo(TokenUsedEvent.SOURCE_CONVERSATION);
// LlmNodeExecutorTest（发事件用例）
assertThat(evt.source()).isEqualTo(TokenUsedEvent.SOURCE_WORKFLOW);
```

- [ ] **Step 2: 编译+相关单测确认绿灯（事件签名改动全量修齐）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageServiceTest,UsageEventListenerTest,ConversationStoreTest,LlmNodeExecutorTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0。

- [ ] **Step 3: 写连库双写失败测试**

```java
package com.hify.usage.service;

import com.hify.common.event.TokenUsedEvent;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** recordUsage 三步双写连库验证：流水（含 source）+ daily_usage + usage_stat_daily 同事务落准。 */
class UsageRecordDbTest extends PgIntegrationTest {

    @Autowired
    UsageService usageService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void recordUsage_三表落准_重复调用聚合累加() {
        usageService.recordUsage(new TokenUsedEvent(7L, 88L, 5L, 300, 180, TokenUsedEvent.SOURCE_WORKFLOW));
        usageService.recordUsage(new TokenUsedEvent(7L, 88L, 5L, 100, 20, TokenUsedEvent.SOURCE_WORKFLOW));

        Map<String, Object> log = jdbc.queryForMap(
                "select source, prompt_tokens from llm_call_log where user_id = 7 order by id limit 1");
        assertThat(log.get("source")).isEqualTo("workflow");
        assertThat(((Number) log.get("prompt_tokens")).longValue()).isEqualTo(300L);

        Long daily = jdbc.queryForObject(
                "select total_tokens from daily_usage where user_id = 7 and app_id = 88", Long.class);
        assertThat(daily).isEqualTo(600L); // 300+180+100+20

        Map<String, Object> stat = jdbc.queryForMap(
                "select prompt_tokens, completion_tokens, call_count from usage_stat_daily "
                        + "where user_id = 7 and app_id = 88 and model_id = 5");
        assertThat(((Number) stat.get("prompt_tokens")).longValue()).isEqualTo(400L);
        assertThat(((Number) stat.get("completion_tokens")).longValue()).isEqualTo(200L);
        assertThat(((Number) stat.get("call_count")).longValue()).isEqualTo(2L);
    }
}
```

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageRecordDbTest | tail -10; echo exit=${PIPESTATUS[0]}`
Expected: exit=1（source 列未写入 / usage_stat_daily 无行）。

- [ ] **Step 4: 实现三步双写**

`LlmCallLogMapper.insertLog` 改为（javadoc 同步提 source）：

```java
@Insert("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, source) "
        + "values (#{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, #{source})")
int insertLog(@Param("userId") Long userId, @Param("appId") Long appId, @Param("modelId") Long modelId,
              @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens,
              @Param("source") String source);
```

新建 `UsageStatDailyMapper.java`：

```java
package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * usage_stat_daily 看板聚合表访问（usage 模块）。写侧只有 UPSERT 累加
 * （database-standards §插或改一律 UPSERT，模板同 DailyUsageMapper）；读侧聚合查询见 UsageStatQueryMapper。
 */
public interface UsageStatDailyMapper {

    @Insert("insert into usage_stat_daily (stat_date, user_id, app_id, model_id, "
            + "prompt_tokens, completion_tokens, call_count) "
            + "values (#{statDate}, #{userId}, #{appId}, #{modelId}, #{promptTokens}, #{completionTokens}, 1) "
            + "on conflict (user_id, app_id, model_id, stat_date) "
            + "do update set prompt_tokens = usage_stat_daily.prompt_tokens + excluded.prompt_tokens, "
            + "completion_tokens = usage_stat_daily.completion_tokens + excluded.completion_tokens, "
            + "call_count = usage_stat_daily.call_count + 1, update_time = now()")
    int upsertAccumulate(@Param("userId") Long userId, @Param("appId") Long appId,
                         @Param("modelId") Long modelId, @Param("statDate") LocalDate statDate,
                         @Param("promptTokens") long promptTokens, @Param("completionTokens") long completionTokens);
}
```

`UsageService`：注入 `UsageStatDailyMapper statDailyMapper`（构造器加参），`recordUsage` 改为：

```java
/** 落流水 + UPSERT daily_usage（配额） + UPSERT usage_stat_daily（看板）。三写同一 usage 本地事务。 */
@Transactional
public void recordUsage(TokenUsedEvent e) {
    llmCallLogMapper.insertLog(e.userId(), e.appId(), e.modelId(),
            e.promptTokens(), e.completionTokens(), e.source());
    long total = (long) e.promptTokens() + e.completionTokens();
    LocalDate today = LocalDate.now();
    dailyUsageMapper.upsertAccumulate(e.userId(), e.appId(), today, total);
    statDailyMapper.upsertAccumulate(e.userId(), e.appId(), e.modelId(), today,
            e.promptTokens(), e.completionTokens());
}
```

`UsageServiceTest` 同步：构造 service 处补 mock 的 `UsageStatDailyMapper`；既有用例补 verify 第三步（`verify(statDailyMapper).upsertAccumulate(eq(7L), eq(88L), eq(5L), any(), eq(300L), eq(180L))`）。

- [ ] **Step 5: 跑单测+连库测试确认绿灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageServiceTest,UsageRecordDbTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0。

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add -A server/src && git commit -m "feat(usage): TokenUsedEvent 加 source，监听器三步双写 usage_stat_daily（配额路径零改动）"
```

---

### Task 3: provider 模型单价（entity/DTO/service/Facade）

**Files:**
- Modify: `server/src/main/java/com/hify/provider/entity/AiModel.java`
- Modify: `server/src/main/java/com/hify/provider/dto/CreateModelRequest.java`、`UpdateModelRequest.java`、`ModelResponse.java`
- Modify: `server/src/main/java/com/hify/provider/service/AiModelService.java`（create/update/toResponse）
- Modify: `server/src/main/java/com/hify/provider/service/ModelQueryService.java`（getModelPrices）
- Modify: `server/src/main/java/com/hify/provider/api/ProviderFacade.java`、`server/src/main/java/com/hify/provider/service/ProviderFacadeImpl.java`
- Create: `server/src/main/java/com/hify/provider/api/ModelPrice.java`
- Modify: `server/src/test/java/com/hify/provider/service/AiModelServiceTest.java`、`ModelQueryServiceTest.java`、`ProviderFacadeImplTest.java`

**Interfaces:**
- Produces: `ProviderFacade.getModelPrices(Collection<Long> modelIds)` → `Map<Long, ModelPrice>`；`public record ModelPrice(BigDecimal inputPrice, BigDecimal outputPrice)`（**api 顶层包**，Modulith 子包坑）；`CreateModelRequest/UpdateModelRequest` 尾部加 `@DecimalMin("0") BigDecimal inputPrice, @DecimalMin("0") BigDecimal outputPrice`（可空）；`ModelResponse` 尾部加 `BigDecimal inputPrice, BigDecimal outputPrice`。

- [ ] **Step 1: 写失败测试（AiModelServiceTest 加单价存取用例，ModelQueryServiceTest 加 getModelPrices 用例）**

`AiModelServiceTest` 新增（沿用该类既有 mock 构造方式）：

```java
@Test
void create_带单价则落库_update_传null置空() {
    // create：单价随 entity insert
    // 按该测试类既有桩方式 stub providerMapper.selectById 返回 openai 协议 provider、
    // modelMapper.selectCount 返回 0L、modelMapper.insert 返回 1
    ModelResponse created = service.create(1L, new CreateModelRequest(
            "chat", "qwen", "qwen-max", new BigDecimal("2.0000"), new BigDecimal("6.0000")));
    assertThat(created.inputPrice()).isEqualByComparingTo("2");
    ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);
    verify(modelMapper).insert(captor.capture());
    assertThat(captor.getValue().getOutputPrice()).isEqualByComparingTo("6");
}
```

`ModelQueryServiceTest` 新增：

```java
@Test
void getModelPrices_返回两单价映射_空入参不查库() {
    AiModel m = new AiModel();
    m.setId(5L);
    m.setInputPrice(new BigDecimal("2.0000"));
    m.setOutputPrice(null); // 只配一半也原样返回，是否算「已配价」由 usage 判定
    when(modelMapper.selectBatchIds(List.of(5L))).thenReturn(List.of(m));
    Map<Long, ModelPrice> prices = service.getModelPrices(List.of(5L));
    assertThat(prices.get(5L).inputPrice()).isEqualByComparingTo("2");
    assertThat(prices.get(5L).outputPrice()).isNull();
    assertThat(service.getModelPrices(List.of())).isEmpty();
    verifyNoMoreInteractions(modelMapper);
}
```

- [ ] **Step 2: 跑测试确认红灯（编译失败：字段/方法不存在）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='AiModelServiceTest,ModelQueryServiceTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=1。

- [ ] **Step 3: 实现**

`AiModel` 加字段（**置空必须生效**：MyBatis-Plus 默认 NOT_NULL 更新策略会跳过 null 字段，PUT 全量「未传视为置空」会失效，故单价两列显式 ALWAYS）：

```java
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import java.math.BigDecimal;

// 单价（元/百万token，null=未配置）。updateStrategy=ALWAYS：PUT 全量语义下传 null 要真置空
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private BigDecimal inputPrice;
@TableField(updateStrategy = FieldStrategy.ALWAYS)
private BigDecimal outputPrice;
// + 常规 getter/setter
```

DTO（record 尾部追加，校验允许 null 只限非负）：

```java
// CreateModelRequest / UpdateModelRequest 各加两个组件
@DecimalMin(value = "0", message = "单价不能为负") BigDecimal inputPrice,
@DecimalMin(value = "0", message = "单价不能为负") BigDecimal outputPrice
// ModelResponse 尾部加
BigDecimal inputPrice, BigDecimal outputPrice
```

`AiModelService`：`create` 里 `entity.setInputPrice(request.inputPrice()); entity.setOutputPrice(request.outputPrice());`；`update` 同两行；`toResponse` 尾部补两参。

`ModelPrice.java`（api 顶层包）：

```java
package com.hify.provider.api;

import java.math.BigDecimal;

/**
 * 模型单价视图（元/百万 token；null=未配置）。跨模块 DTO 放 api 顶层包
 * （Modulith 1.4.1 不暴露 api/dto 子包）。是否「已配价」（两价均非空）由消费方判定。
 */
public record ModelPrice(BigDecimal inputPrice, BigDecimal outputPrice) {
}
```

`ModelQueryService` 加（模式抄 getModelNames，含 javadoc）：

```java
/** 批量取模型单价（id→ModelPrice），展示/计费用途，不管启停都返回；已删模型不在结果里。空/null 入参返回空 map。 */
public Map<Long, ModelPrice> getModelPrices(Collection<Long> modelIds) {
    if (modelIds == null || modelIds.isEmpty()) {
        return Map.of();
    }
    return modelMapper.selectBatchIds(modelIds).stream()
            .collect(Collectors.toMap(AiModel::getId,
                    m -> new ModelPrice(m.getInputPrice(), m.getOutputPrice())));
}
```

`ProviderFacade` 接口加方法（javadoc 说明同上）+ `ProviderFacadeImpl` 委托一行：`return modelQueryService.getModelPrices(modelIds);`，`ProviderFacadeImplTest` 补委托 verify 用例。

- [ ] **Step 4: 跑 provider 全部单测确认绿灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='com.hify.provider.**.*Test' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add -A server/src && git commit -m "feat(provider): 模型单价字段（元/百万token，可空）+ Facade 批量取价 getModelPrices"
```

---

### Task 4: usage 聚合统计接口（overview/daily/rankings）+ 依赖白名单变更

**Files:**
- Modify: `server/src/main/java/com/hify/usage/package-info.java`（allowedDependencies 加 `"provider::api"`）
- Modify: `docs/architecture/code-organization.md`（§1 表 usage 行「允许依赖的业务模块」：无 → provider）
- Create: `server/src/main/java/com/hify/usage/mapper/UsageStatQueryMapper.java`
- Create: `server/src/main/java/com/hify/usage/dto/UsageOverviewResponse.java`、`DailyUsagePoint.java`、`UsageRankingItem.java`
- Create: `server/src/main/java/com/hify/usage/service/UsageStatService.java`
- Create: `server/src/main/java/com/hify/usage/controller/AdminUsageController.java`
- Create: `server/src/test/java/com/hify/usage/service/UsageStatServiceTest.java`
- Create: `server/src/test/java/com/hify/usage/mapper/UsageStatQueryDbTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ProviderFacade.getModelPrices` / `ModelPrice`；Task 1 的 `usage_stat_daily`。
- Produces: `GET /api/v1/admin/usage/stats/overview|daily|rankings`；mapper 行记录 `UsageStatQueryMapper.ModelAgg(Long modelId, long promptTokens, long completionTokens, long callCount)`、`DailyModelAgg(LocalDate statDate, Long modelId, ...)`、`DimModelAgg(Long targetId, Long modelId, ...)`（MyBatis record 构造映射，先例 RetrievedChunk）。

- [ ] **Step 1: 写失败单测（费用计算与日期窗校验，mock mapper+facade）**

```java
package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.provider.api.ModelPrice;
import com.hify.provider.api.ProviderFacade;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.mapper.UsageStatQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageStatServiceTest {

    UsageStatQueryMapper mapper = mock(UsageStatQueryMapper.class);
    ProviderFacade providerFacade = mock(ProviderFacade.class);
    UsageStatService service;

    @BeforeEach
    void setUp() {
        service = new UsageStatService(mapper, providerFacade);
    }

    @Test
    void overview_已配价模型算费用_未配价计0并标注不完整() {
        when(mapper.aggregateByModel(any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.ModelAgg(5L, 1_000_000L, 500_000L, 3L),   // ¥2+¥3=5
                new UsageStatQueryMapper.ModelAgg(9L, 2_000_000L, 1_000_000L, 2L))); // 未配价
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of(
                5L, new ModelPrice(new BigDecimal("2"), new BigDecimal("6"))));
        UsageOverviewResponse r = service.overview(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-17"));
        assertThat(r.promptTokens()).isEqualTo(3_000_000L);
        assertThat(r.completionTokens()).isEqualTo(1_500_000L);
        assertThat(r.callCount()).isEqualTo(5L);
        assertThat(r.estimatedCost()).isEqualTo("5.0000"); // 1M×2/1M + 0.5M×6/1M
        assertThat(r.costIncomplete()).isTrue();
    }

    @Test
    void 只配一半单价的模型视为未配价() {
        when(mapper.aggregateByModel(any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.ModelAgg(5L, 1_000_000L, 0L, 1L)));
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of(
                5L, new ModelPrice(new BigDecimal("2"), null)));
        UsageOverviewResponse r = service.overview(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"));
        assertThat(r.estimatedCost()).isEqualTo("0.0000");
        assertThat(r.costIncomplete()).isTrue();
    }

    @Test
    void 日期窗校验_起晚于止或超92天_抛10001() {
        assertThatThrownBy(() -> service.overview(LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-01")))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.overview(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-07-01")))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rankings_dimension非法抛10001_合法按totalTokens降序截断limit() {
        when(mapper.aggregateByDimension(any(), any(), any())).thenReturn(List.of(
                new UsageStatQueryMapper.DimModelAgg(88L, 5L, 100L, 50L, 1L),
                new UsageStatQueryMapper.DimModelAgg(99L, 5L, 900L, 100L, 2L)));
        when(providerFacade.getModelPrices(any())).thenReturn(Map.of());
        var list = service.rankings("app", LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), 1);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).targetId()).isEqualTo(99L);
        assertThatThrownBy(() -> service.rankings("bogus",
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), 10))
                .isInstanceOf(BizException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认红灯（类不存在编译失败）**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest=UsageStatServiceTest | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=1。

- [ ] **Step 3: 白名单变更 + 实现**

`usage/package-info.java`：`allowedDependencies = {"provider::api", "common", "infra"}`，javadoc 补一句「白名单含 provider::api：看板费用计算取模型单价（2026-07-17 拍板，仅此一条，名称解析走前端拼装）」。`code-organization.md` §1 表 usage 行同步改为 `provider（仅单价，2026-07-17 看板轮）`。

`UsageStatQueryMapper.java`：

```java
package com.hify.usage.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * usage_stat_daily 看板聚合读侧。行记录用 record 构造映射（先例 knowledge RetrievedChunk）。
 * aggregateByDimension 的 ${dimCol} 由 service 内固定 switch 提供（app_id/user_id/model_id 三值），
 * 非用户输入，${} 拼接安全（同 LlmCallLogMapper.createMonthlyPartition 先例）。
 */
public interface UsageStatQueryMapper {

    record ModelAgg(Long modelId, long promptTokens, long completionTokens, long callCount) {
    }

    record DailyModelAgg(LocalDate statDate, Long modelId, long promptTokens, long completionTokens,
                         long callCount) {
    }

    record DimModelAgg(Long targetId, Long modelId, long promptTokens, long completionTokens, long callCount) {
    }

    @Select("select model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} group by model_id")
    List<ModelAgg> aggregateByModel(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("select stat_date, model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} "
            + "group by stat_date, model_id order by stat_date")
    List<DailyModelAgg> aggregateDaily(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Select("select ${dimCol} as target_id, model_id, sum(prompt_tokens) as prompt_tokens, "
            + "sum(completion_tokens) as completion_tokens, sum(call_count) as call_count "
            + "from usage_stat_daily where stat_date between #{start} and #{end} "
            + "group by ${dimCol}, model_id")
    List<DimModelAgg> aggregateByDimension(@Param("dimCol") String dimCol,
                                           @Param("start") LocalDate start, @Param("end") LocalDate end);
}
```

DTO 三个 record（`usage/dto/`，金额为 String）：

```java
public record UsageOverviewResponse(long promptTokens, long completionTokens, long totalTokens,
                                    long callCount, String estimatedCost, boolean costIncomplete) { }

public record DailyUsagePoint(LocalDate date, long promptTokens, long completionTokens,
                              long callCount, String estimatedCost) { }

public record UsageRankingItem(Long targetId, long promptTokens, long completionTokens, long totalTokens,
                               long callCount, String estimatedCost) { }
```

`UsageStatService.java`：

```java
package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.ModelPrice;
import com.hify.provider.api.ProviderFacade;
import com.hify.usage.dto.DailyUsagePoint;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.dto.UsageRankingItem;
import com.hify.usage.mapper.UsageStatQueryMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 看板聚合统计（只读）。查 usage_stat_daily（database-standards §聚合表代替扫流水）；
 * 费用 = token×单价（元/百万）后端 BigDecimal 现算——按当前单价估算，改价历史自动重算（spec §0）。
 * 「已配价」= 两单价均非空；否则该模型费用计 0 且 costIncomplete=true（含已删模型：取价结果里没有）。
 */
@Service
public class UsageStatService {

    private static final int MAX_RANGE_DAYS = 92;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);
    private static final Map<String, String> DIM_COLUMNS = Map.of(
            "app", "app_id", "user", "user_id", "model", "model_id");

    private final UsageStatQueryMapper mapper;
    private final ProviderFacade providerFacade;

    public UsageStatService(UsageStatQueryMapper mapper, ProviderFacade providerFacade) {
        this.mapper = mapper;
        this.providerFacade = providerFacade;
    }

    public UsageOverviewResponse overview(LocalDate start, LocalDate end) {
        validateRange(start, end);
        List<UsageStatQueryMapper.ModelAgg> rows = mapper.aggregateByModel(start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.ModelAgg::modelId).collect(Collectors.toSet()));
        long prompt = 0;
        long completion = 0;
        long calls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean incomplete = false;
        for (UsageStatQueryMapper.ModelAgg row : rows) {
            prompt += row.promptTokens();
            completion += row.completionTokens();
            calls += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                cost = cost.add(costOf(row.promptTokens(), row.completionTokens(), price));
            } else {
                incomplete = true;
            }
        }
        return new UsageOverviewResponse(prompt, completion, prompt + completion, calls,
                scale(cost), incomplete);
    }

    public List<DailyUsagePoint> daily(LocalDate start, LocalDate end) {
        validateRange(start, end);
        List<UsageStatQueryMapper.DailyModelAgg> rows = mapper.aggregateDaily(start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.DailyModelAgg::modelId).collect(Collectors.toSet()));
        // LinkedHashMap 保 mapper 的 stat_date 升序
        Map<LocalDate, long[]> acc = new LinkedHashMap<>(); // [prompt, completion, calls]
        Map<LocalDate, BigDecimal> costs = new LinkedHashMap<>();
        for (UsageStatQueryMapper.DailyModelAgg row : rows) {
            long[] a = acc.computeIfAbsent(row.statDate(), d -> new long[3]);
            a[0] += row.promptTokens();
            a[1] += row.completionTokens();
            a[2] += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                costs.merge(row.statDate(), costOf(row.promptTokens(), row.completionTokens(), price),
                        BigDecimal::add);
            }
        }
        return acc.entrySet().stream()
                .map(e -> new DailyUsagePoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        scale(costs.getOrDefault(e.getKey(), BigDecimal.ZERO))))
                .toList();
    }

    public List<UsageRankingItem> rankings(String dimension, LocalDate start, LocalDate end, int limit) {
        validateRange(start, end);
        String dimCol = DIM_COLUMNS.get(dimension);
        if (dimCol == null) {
            throw new BizException(CommonError.PARAM_INVALID, "dimension 仅支持 app|user|model");
        }
        List<UsageStatQueryMapper.DimModelAgg> rows = mapper.aggregateByDimension(dimCol, start, end);
        Map<Long, ModelPrice> prices = fetchPrices(rows.stream()
                .map(UsageStatQueryMapper.DimModelAgg::modelId).collect(Collectors.toSet()));
        Map<Long, long[]> acc = new LinkedHashMap<>();
        Map<Long, BigDecimal> costs = new LinkedHashMap<>();
        for (UsageStatQueryMapper.DimModelAgg row : rows) {
            long[] a = acc.computeIfAbsent(row.targetId(), t -> new long[3]);
            a[0] += row.promptTokens();
            a[1] += row.completionTokens();
            a[2] += row.callCount();
            ModelPrice price = prices.get(row.modelId());
            if (isPriced(price)) {
                costs.merge(row.targetId(), costOf(row.promptTokens(), row.completionTokens(), price),
                        BigDecimal::add);
            }
        }
        return acc.entrySet().stream()
                .map(e -> new UsageRankingItem(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] + e.getValue()[1], e.getValue()[2],
                        scale(costs.getOrDefault(e.getKey(), BigDecimal.ZERO))))
                .sorted(Comparator.comparingLong(UsageRankingItem::totalTokens).reversed())
                .limit(limit)
                .toList();
    }

    private Map<Long, ModelPrice> fetchPrices(Set<Long> modelIds) {
        return modelIds.isEmpty() ? Map.of() : providerFacade.getModelPrices(modelIds);
    }

    private static boolean isPriced(ModelPrice p) {
        return p != null && p.inputPrice() != null && p.outputPrice() != null;
    }

    private static BigDecimal costOf(long promptTokens, long completionTokens, ModelPrice p) {
        return BigDecimal.valueOf(promptTokens).multiply(p.inputPrice())
                .add(BigDecimal.valueOf(completionTokens).multiply(p.outputPrice()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private static String scale(BigDecimal cost) {
        return cost.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BizException(CommonError.PARAM_INVALID, "日期范围不合法");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BizException(CommonError.PARAM_INVALID, "日期范围不能超过 92 天");
        }
    }
}
```

`AdminUsageController.java`（call-logs 端点 Task 5 再加）：

```java
package com.hify.usage.controller;

import com.hify.common.Result;
import com.hify.usage.dto.DailyUsagePoint;
import com.hify.usage.dto.UsageOverviewResponse;
import com.hify.usage.dto.UsageRankingItem;
import com.hify.usage.service.UsageStatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * admin 用量看板接口（仅 Admin，/api/v1/admin/** 由 SecurityConfig hasRole 拦截）。
 * 全 GET 只读；参数校验（日期窗/维度）在 service 统一抛 10001。协议层不写业务。
 */
@RestController
@RequestMapping("/api/v1/admin/usage")
public class AdminUsageController {

    private final UsageStatService usageStatService;

    public AdminUsageController(UsageStatService usageStatService) {
        this.usageStatService = usageStatService;
    }

    @GetMapping("/stats/overview")
    public Result<UsageOverviewResponse> overview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(usageStatService.overview(startDate, endDate));
    }

    @GetMapping("/stats/daily")
    public Result<List<DailyUsagePoint>> daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(usageStatService.daily(startDate, endDate));
    }

    @GetMapping("/stats/rankings")
    public Result<List<UsageRankingItem>> rankings(
            @RequestParam String dimension,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(usageStatService.rankings(dimension, startDate, endDate, Math.min(limit, 50)));
    }
}
```

- [ ] **Step 4: 写连库聚合 SQL 测试并跑全绿**

```java
package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** usage_stat_daily 三个聚合查询连库验证（含窗口过滤与分组）。 */
class UsageStatQueryDbTest extends PgIntegrationTest {

    @Autowired
    UsageStatQueryMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("insert into usage_stat_daily (stat_date, user_id, app_id, model_id, "
                + "prompt_tokens, completion_tokens, call_count) values "
                + "('2026-07-10', 1, 10, 5, 100, 50, 2), "
                + "('2026-07-11', 1, 10, 5, 200, 80, 1), "
                + "('2026-07-11', 2, 20, 6, 300, 90, 3), "
                + "('2026-06-01', 1, 10, 5, 999, 999, 9)"); // 窗口外
    }

    @Test
    void 三查询窗口过滤与分组正确() {
        LocalDate start = LocalDate.parse("2026-07-01");
        LocalDate end = LocalDate.parse("2026-07-31");

        List<UsageStatQueryMapper.ModelAgg> byModel = mapper.aggregateByModel(start, end);
        assertThat(byModel).hasSize(2);
        assertThat(byModel.stream().filter(r -> r.modelId() == 5L).findFirst().orElseThrow()
                .promptTokens()).isEqualTo(300L);

        List<UsageStatQueryMapper.DailyModelAgg> daily = mapper.aggregateDaily(start, end);
        assertThat(daily).hasSize(3); // (10日,m5) (11日,m5) (11日,m6)，日期升序
        assertThat(daily.get(0).statDate()).isEqualTo(LocalDate.parse("2026-07-10"));

        List<UsageStatQueryMapper.DimModelAgg> byUser = mapper.aggregateByDimension("user_id", start, end);
        assertThat(byUser).hasSize(2);
        assertThat(byUser.stream().filter(r -> r.targetId() == 2L).findFirst().orElseThrow()
                .callCount()).isEqualTo(3L);
    }
}
```

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='UsageStatServiceTest,UsageStatQueryDbTest,ModularityTests,LayerRulesTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0（含模块边界回归——白名单变更生效且无越界）。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add -A server/src docs/architecture/code-organization.md
git commit -m "feat(usage): 看板聚合统计三接口（overview/daily/rankings），白名单开 usage→provider::api 取单价"
```

---

### Task 5: 调用日志游标查询接口

**Files:**
- Create: `server/src/main/java/com/hify/usage/service/LogCursor.java`
- Modify: `server/src/main/java/com/hify/usage/mapper/LlmCallLogMapper.java`（加 selectPage 动态查询 + 行 record）
- Create: `server/src/main/java/com/hify/usage/dto/CallLogItem.java`
- Create: `server/src/main/java/com/hify/usage/service/UsageLogService.java`
- Modify: `server/src/main/java/com/hify/usage/controller/AdminUsageController.java`（加 call-logs 端点）
- Create: `server/src/test/java/com/hify/usage/service/LogCursorTest.java`
- Create: `server/src/test/java/com/hify/usage/mapper/CallLogQueryDbTest.java`

**Interfaces:**
- Consumes: Task 2 的 `llm_call_log.source` 写入。
- Produces: `GET /api/v1/admin/usage/call-logs` → `CursorResult<CallLogItem>`；`CallLogItem(Long id, Long userId, Long appId, Long modelId, long promptTokens, long completionTokens, String source, OffsetDateTime createTime)`。

- [ ] **Step 1: 写失败测试（游标往返 + 连库筛选/翻页）**

`LogCursorTest.java`（模式抄 workflow RunCursor 的语义）：

```java
package com.hify.usage.service;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogCursorTest {

    @Test
    void 编解码往返一致() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-17T10:30:00+08:00");
        String encoded = LogCursor.encode(t, 42L);
        LogCursor.Cursor decoded = LogCursor.decode(encoded);
        assertThat(decoded.createTime()).isEqualTo(t);
        assertThat(decoded.id()).isEqualTo(42L);
    }

    @Test
    void 非法游标抛10001() {
        assertThatThrownBy(() -> LogCursor.decode("不是游标")).isInstanceOf(BizException.class);
    }
}
```

`CallLogQueryDbTest.java`：

```java
package com.hify.usage.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** llm_call_log 游标分页查询连库验证：时间窗必选、可选过滤、(create_time,id) 双键降序翻页。 */
class CallLogQueryDbTest extends PgIntegrationTest {

    @Autowired
    LlmCallLogMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    OffsetDateTime start = OffsetDateTime.parse("2026-07-17T00:00:00+08:00");
    OffsetDateTime end = OffsetDateTime.parse("2026-07-18T00:00:00+08:00");

    @BeforeEach
    void seed() {
        jdbc.update("insert into llm_call_log (user_id, app_id, model_id, prompt_tokens, completion_tokens, source, create_time) values "
                + "(1, 10, 5, 100, 50, 'conversation', '2026-07-17 10:00:00+08'), "
                + "(1, 10, 5, 200, 60, 'workflow',     '2026-07-17 11:00:00+08'), "
                + "(2, 20, 6, 300, 70, 'conversation', '2026-07-17 12:00:00+08')");
    }

    @Test
    void 无游标首页降序_source过滤生效() {
        List<LlmCallLogMapper.CallLogRow> all = mapper.selectPage(start, end,
                null, null, null, null, null, null, 10);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).userId()).isEqualTo(2L); // 最新在前

        List<LlmCallLogMapper.CallLogRow> conv = mapper.selectPage(start, end,
                null, null, null, "conversation", null, null, 10);
        assertThat(conv).hasSize(2);
    }

    @Test
    void 游标翻页不重不漏() {
        List<LlmCallLogMapper.CallLogRow> page1 = mapper.selectPage(start, end,
                null, null, null, null, null, null, 2);
        LlmCallLogMapper.CallLogRow last = page1.get(1);
        List<LlmCallLogMapper.CallLogRow> page2 = mapper.selectPage(start, end,
                null, null, null, null, last.createTime(), last.id(), 2);
        assertThat(page2).hasSize(1);
        assertThat(page2.get(0).id()).isNotIn(page1.stream().map(LlmCallLogMapper.CallLogRow::id).toList());
    }
}
```

- [ ] **Step 2: 跑测试确认红灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='LogCursorTest,CallLogQueryDbTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=1（LogCursor/selectPage 不存在，编译失败）。

- [ ] **Step 3: 实现**

`LogCursor.java`：完整复制 `workflow/service/RunCursor.java` 的实现到 `com.hify.usage.service` 并改类名为 `LogCursor`（包级私有 final class，encode/decode/record Cursor 三成员一字不差；两模块无法共享 service 内部类，复制是既定取舍，javadoc 注明「模式同 workflow RunCursor」）。

`LlmCallLogMapper` 加（`<script>` 动态 SQL 先例 KbChunkMapper）：

```java
record CallLogRow(Long id, Long userId, Long appId, Long modelId, long promptTokens,
                  long completionTokens, String source, OffsetDateTime createTime) {
}

/**
 * 游标分页明细（管理后台调用日志）。时间窗必选（分区裁剪）；(create_time,id) 行值比较降序翻页
 * （database-standards §4 游标分页）。cursorTime/cursorId 同为 null=首页。
 */
@Select("""
        <script>
        select id, user_id, app_id, model_id, prompt_tokens, completion_tokens, source, create_time
        from llm_call_log
        where create_time &gt;= #{startTime} and create_time &lt; #{endTime}
        <if test="userId != null">and user_id = #{userId}</if>
        <if test="appId != null">and app_id = #{appId}</if>
        <if test="modelId != null">and model_id = #{modelId}</if>
        <if test="source != null">and source = #{source}</if>
        <if test="cursorTime != null">and (create_time, id) &lt; (#{cursorTime}, #{cursorId})</if>
        order by create_time desc, id desc
        limit #{limit}
        </script>
        """)
List<CallLogRow> selectPage(@Param("startTime") OffsetDateTime startTime,
                            @Param("endTime") OffsetDateTime endTime,
                            @Param("userId") Long userId, @Param("appId") Long appId,
                            @Param("modelId") Long modelId, @Param("source") String source,
                            @Param("cursorTime") OffsetDateTime cursorTime, @Param("cursorId") Long cursorId,
                            @Param("limit") int limit);
```

`CallLogItem.java`（dto，字段同 CallLogRow；投影在 service，DTO 不 import mapper 内部类）。

`UsageLogService.java`：

```java
package com.hify.usage.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.CursorResult;
import com.hify.usage.dto.CallLogItem;
import com.hify.usage.mapper.LlmCallLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 调用日志明细查询（只读，管理后台）。查流水表是「分页明细」不是聚合，不违反
 * database-standards §聚合表代替扫流水；时间窗必填 ≤31 天保证分区裁剪。
 */
@Service
public class UsageLogService {

    private static final int MAX_WINDOW_DAYS = 31;
    private static final int MAX_LIMIT = 100;

    private final LlmCallLogMapper mapper;

    public UsageLogService(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    public CursorResult<CallLogItem> list(OffsetDateTime startTime, OffsetDateTime endTime,
                                          Long userId, Long appId, Long modelId, String source,
                                          String cursor, int limit) {
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BizException(CommonError.PARAM_INVALID, "时间范围不合法");
        }
        if (Duration.between(startTime, endTime).toDays() > MAX_WINDOW_DAYS) {
            throw new BizException(CommonError.PARAM_INVALID, "时间范围不能超过 31 天");
        }
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        OffsetDateTime cursorTime = null;
        Long cursorId = null;
        if (StringUtils.hasText(cursor)) {
            LogCursor.Cursor c = LogCursor.decode(cursor);
            cursorTime = c.createTime();
            cursorId = c.id();
        }
        // 多取 1 条判 hasMore（惯例同 workflow listRuns）
        List<LlmCallLogMapper.CallLogRow> rows = mapper.selectPage(startTime, endTime,
                userId, appId, modelId, source, cursorTime, cursorId, size + 1);
        boolean hasMore = rows.size() > size;
        List<CallLogItem> list = rows.stream().limit(size)
                .map(r -> new CallLogItem(r.id(), r.userId(), r.appId(), r.modelId(),
                        r.promptTokens(), r.completionTokens(), r.source(), r.createTime()))
                .toList();
        String next = hasMore
                ? LogCursor.encode(list.get(list.size() - 1).createTime(), list.get(list.size() - 1).id())
                : null;
        return CursorResult.of(list, next, hasMore);
    }
}
```

`AdminUsageController` 加端点（注入 `UsageLogService`）：

```java
@GetMapping("/call-logs")
public Result<CursorResult<CallLogItem>> callLogs(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) Long appId,
        @RequestParam(required = false) Long modelId,
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit) {
    return Result.ok(usageLogService.list(startTime, endTime, userId, appId, modelId, source, cursor, limit));
}
```

- [ ] **Step 4: 跑测试确认绿灯**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='LogCursorTest,CallLogQueryDbTest' | tail -8; echo exit=${PIPESTATUS[0]}`
Expected: exit=0。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add -A server/src && git commit -m "feat(usage): 调用日志游标分页查询（时间窗必选+四维过滤+双键游标）"
```

---

### Task 6: 前端地基：类型 + usage api + useNameMaps + 路由

**Files:**
- Create: `web/src/types/usage.ts`
- Create: `web/src/api/admin/usage.ts`
- Create: `web/src/composables/useNameMaps.ts`
- Create: `web/src/composables/__tests__/useNameMaps.spec.ts`
- Modify: `web/src/router/index.ts`（`/admin/usage` 与 `/admin/usage/call-logs` 两条路由）

**Interfaces:**
- Produces: 类型 `UsageOverview/DailyUsagePoint/UsageRankingItem/CallLogItem/CallLogPage/RankDimension`；api 函数 `fetchOverview/fetchDaily/fetchRankings/fetchCallLogs`；`useNameMaps()` → `{ load, resolveUser, resolveApp, resolveModel }`（resolve 均 `(id: string) => string`，未命中返回 `#${id}（已删除）`）。

- [ ] **Step 1: 写失败测试 useNameMaps**

```ts
// web/src/composables/__tests__/useNameMaps.spec.ts
import { describe, expect, it, vi } from 'vitest'
import { useNameMaps } from '../useNameMaps'

vi.mock('@/api/admin/user', () => ({
  listUsers: vi.fn().mockResolvedValue([{ id: '1', username: '张三' }]),
}))
vi.mock('@/api/app', () => ({
  listApps: vi.fn().mockResolvedValue({ list: [{ id: '10', name: '客服机器人' }], total: 1, page: 1, size: 100 }),
}))
vi.mock('@/api/admin/provider', () => ({
  listProviders: vi.fn().mockResolvedValue([{ id: '100', name: '通义' }]),
}))
vi.mock('@/api/admin/model', () => ({
  listModels: vi.fn().mockResolvedValue([{ id: '5', name: 'qwen-max' }]),
}))

describe('useNameMaps', () => {
  it('load 后三类 id 可解析，未知 id 回退「#id（已删除）」', async () => {
    const maps = useNameMaps()
    await maps.load()
    expect(maps.resolveUser('1')).toBe('张三')
    expect(maps.resolveApp('10')).toBe('客服机器人')
    expect(maps.resolveModel('5')).toBe('qwen-max')
    expect(maps.resolveModel('999')).toBe('#999（已删除）')
  })
})
```

Run: `cd /home/wang/playlab/hify/web && pnpm test src/composables/__tests__/useNameMaps.spec.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 2: 实现类型/api/composable/路由**

`web/src/types/usage.ts`：

```ts
/** 用量看板类型（对齐后端 usage/dto）。token 数为 string（Long 全局序列化），金额为 string（后端定长小数）。 */
export type RankDimension = 'app' | 'user' | 'model'

export interface UsageOverview {
  promptTokens: string
  completionTokens: string
  totalTokens: string
  callCount: string
  estimatedCost: string
  costIncomplete: boolean
}

export interface DailyUsagePoint {
  date: string
  promptTokens: string
  completionTokens: string
  callCount: string
  estimatedCost: string
}

export interface UsageRankingItem {
  targetId: string
  promptTokens: string
  completionTokens: string
  totalTokens: string
  callCount: string
  estimatedCost: string
}

export interface CallLogItem {
  id: string
  userId: string
  appId: string
  modelId: string
  promptTokens: string
  completionTokens: string
  source: 'conversation' | 'workflow' | null
  createTime: string
}

export interface CallLogPage {
  list: CallLogItem[]
  nextCursor: string | null
  hasMore: boolean
}

export interface CallLogQuery {
  startTime: string
  endTime: string
  userId?: string
  appId?: string
  modelId?: string
  source?: string
  cursor?: string
  limit?: number
}
```

`web/src/api/admin/usage.ts`：

```ts
import { request } from '@/api/request'
import type {
  CallLogPage,
  CallLogQuery,
  DailyUsagePoint,
  RankDimension,
  UsageOverview,
  UsageRankingItem,
} from '@/types/usage'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/usage'

/** 总览卡。后端：GET /api/v1/admin/usage/stats/overview */
export function fetchOverview(startDate: string, endDate: string) {
  return request.get<UsageOverview>(`${BASE}/stats/overview`, { params: { startDate, endDate } })
}

/** 按天趋势。后端：GET .../stats/daily */
export function fetchDaily(startDate: string, endDate: string) {
  return request.get<DailyUsagePoint[]>(`${BASE}/stats/daily`, { params: { startDate, endDate } })
}

/** 排行（app|user|model）。后端：GET .../stats/rankings */
export function fetchRankings(dimension: RankDimension, startDate: string, endDate: string, limit = 10) {
  return request.get<UsageRankingItem[]>(`${BASE}/stats/rankings`, {
    params: { dimension, startDate, endDate, limit },
  })
}

/** 调用日志（游标分页）。后端：GET .../call-logs */
export function fetchCallLogs(query: CallLogQuery) {
  return request.get<CallLogPage>(`${BASE}/call-logs`, { params: query })
}
```

`web/src/composables/useNameMaps.ts`：

```ts
import { ref } from 'vue'
import { listUsers } from '@/api/admin/user'
import { listApps } from '@/api/app'
import { listProviders } from '@/api/admin/provider'
import { listModels } from '@/api/admin/model'

/**
 * 看板名称解析：usage 接口只出 id（模块白名单限制，名称在前端拼装，见 spec §0）。
 * 并行拉用户/应用/模型三份列表建映射；解析不到（已删资源）回退「#id（已删除）」。
 */
export function useNameMaps() {
  const users = ref(new Map<string, string>())
  const apps = ref(new Map<string, string>())
  const models = ref(new Map<string, string>())

  async function loadApps() {
    const map = new Map<string, string>()
    let page = 1
    // 应用量级几十个；size=100 循环翻页，防御性封顶 5 页
    for (; page <= 5; page++) {
      const res = await listApps({ page, size: 100 })
      res.list.forEach((a) => map.set(a.id, a.name))
      if (res.list.length < 100) break
    }
    apps.value = map
  }

  async function loadModels() {
    const providers = await listProviders()
    const lists = await Promise.all(providers.map((p) => listModels(p.id)))
    const map = new Map<string, string>()
    lists.flat().forEach((m) => map.set(m.id, m.name))
    models.value = map
  }

  async function load() {
    await Promise.all([
      listUsers().then((list) => {
        users.value = new Map(list.map((u) => [u.id, u.username]))
      }),
      loadApps(),
      loadModels(),
    ])
  }

  const fallback = (id: string) => `#${id}（已删除）`
  const resolveUser = (id: string) => users.value.get(id) ?? fallback(id)
  const resolveApp = (id: string) => apps.value.get(id) ?? fallback(id)
  const resolveModel = (id: string) => models.value.get(id) ?? fallback(id)

  return { load, resolveUser, resolveApp, resolveModel }
}
```

> 注意：`listApps` 返回结构与 `AdminUser` 的 username 字段名以 `web/src/api/app.ts`、`web/src/types/admin-user.ts` 现有定义为准，动手前先读这两个文件核对（返回 PageResult 的字段是 `list/total/page/size`）。

`web/src/router/index.ts` 在 SystemSettings 路由后插入：

```ts
{
  path: '/admin/usage',
  name: 'UsageDashboard',
  component: () => import('@/views/admin/usage/UsageDashboard.vue'),
  meta: {
    requiresAuth: true,
    roles: ['admin'],
    title: '用量看板',
    menu: true,
    icon: 'DataAnalysis',
    group: '管理控制台',
  },
},
{
  path: '/admin/usage/call-logs',
  name: 'CallLogList',
  component: () => import('@/views/admin/usage/CallLogList.vue'),
  meta: {
    requiresAuth: true,
    roles: ['admin'],
    title: '调用日志',
    group: '管理控制台',
  },
},
```

（页面组件 Task 8/9 才建；本 Task 先建两个最小占位 SFC 防路由 import 悬空：`<template><div /></template>`，Task 8/9 覆盖。）

- [ ] **Step 3: 跑测试与 typecheck 确认绿灯**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/composables/__tests__/useNameMaps.spec.ts && pnpm typecheck`
Expected: PASS + typecheck 退出码 0。

- [ ] **Step 4: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src && git commit -m "feat(web): 用量看板地基——类型/usage api/useNameMaps 名称解析/路由菜单"
```

---

### Task 7: ECharts 引入 + TrendChart 组件

**Files:**
- Modify: `web/package.json`（`pnpm add echarts`）
- Create: `web/src/views/admin/usage/TrendChart.vue`
- Create: `web/src/views/admin/usage/__tests__/TrendChart.spec.ts`
- Modify: `docs/architecture/frontend-standards.md`（技术栈段登记 ECharts：按需引入，仅 TrendChart.vue 触碰）

**Interfaces:**
- Produces: `<TrendChart :dates="string[]" :tokens="number[]" :costs="number[]" />`，双 y 轴折线（左 token、右费用）。

- [ ] **Step 1: 安装 echarts**

Run: `cd /home/wang/playlab/hify/web && pnpm add echarts`
Expected: package.json dependencies 出现 `"echarts"`。

- [ ] **Step 2: 写失败测试（mock echarts/core——jsdom 无 canvas，前端第四个 jsdom 坑）**

```ts
// web/src/views/admin/usage/__tests__/TrendChart.spec.ts
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'

const setOption = vi.fn()
const dispose = vi.fn()
const resize = vi.fn()
// jsdom 无 canvas：echarts 必须整体 mock（第四个 jsdom 坑，入档 testing 策略）
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption, dispose, resize })),
}))
vi.mock('echarts/charts', () => ({ LineChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {}, LegendComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import TrendChart from '../TrendChart.vue'

describe('TrendChart', () => {
  beforeEach(() => {
    setOption.mockClear()
    dispose.mockClear()
  })

  it('挂载即 init+setOption，数据变化重新 setOption，卸载 dispose', async () => {
    const wrapper = mount(TrendChart, {
      props: { dates: ['2026-07-16'], tokens: [100], costs: [0.5] },
    })
    expect(setOption).toHaveBeenCalledTimes(1)
    const option = setOption.mock.calls[0][0]
    expect(option.xAxis.data).toEqual(['2026-07-16'])
    expect(option.series).toHaveLength(2) // token + 费用双系列
    expect(option.yAxis).toHaveLength(2) // 双 y 轴

    await wrapper.setProps({ dates: ['2026-07-16', '2026-07-17'], tokens: [100, 200], costs: [0.5, 1] })
    await nextTick()
    expect(setOption).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(dispose).toHaveBeenCalled()
  })
})
```

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/TrendChart.spec.ts`
Expected: FAIL（TrendChart.vue 是 Task 6 的占位/不含图表逻辑）。

- [ ] **Step 3: 实现 TrendChart.vue**

```vue
<script setup lang="ts">
// 全站唯一直接触碰 ECharts 的组件（frontend-standards 登记：按需引入）
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{
  dates: string[]
  tokens: number[]
  costs: number[]
}>()

const el = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null

function buildOption() {
  return {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['Token', '费用（元）'] },
    grid: { left: 48, right: 56, top: 40, bottom: 24 },
    xAxis: { type: 'category' as const, data: props.dates },
    yAxis: [
      { type: 'value' as const, name: 'Token' },
      { type: 'value' as const, name: '费用（元）' },
    ],
    series: [
      { name: 'Token', type: 'line' as const, smooth: true, data: props.tokens },
      { name: '费用（元）', type: 'line' as const, smooth: true, yAxisIndex: 1, data: props.costs },
    ],
  }
}

function render() {
  chart?.setOption(buildOption())
}

const onResize = () => chart?.resize()

onMounted(() => {
  chart = echarts.init(el.value!)
  render()
  window.addEventListener('resize', onResize)
})

watch(() => [props.dates, props.tokens, props.costs], render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="trend-chart" data-test="trend-chart" />
</template>

<style scoped lang="scss">
.trend-chart {
  width: 100%;
  height: 320px;
}
</style>
```

- [ ] **Step 4: 跑测试确认绿灯 + 登记 frontend-standards**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/TrendChart.spec.ts && pnpm typecheck`
Expected: PASS。

`docs/architecture/frontend-standards.md` 技术栈清单加一行：`ECharts —— 图表（用量看板趋势图；按需引入 echarts/core+LineChart，全站仅 TrendChart.vue 直接触碰；测试须整体 mock，jsdom 无 canvas）（2026-07-17 拍板引入）`。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/package.json web/pnpm-lock.yaml web/src docs/architecture/frontend-standards.md
git commit -m "feat(web): 引入 ECharts（按需），封装双 y 轴趋势折线 TrendChart"
```

---

### Task 8: UsageDashboard 页面（总览卡+趋势+排行）

**Files:**
- Modify: `web/src/views/admin/usage/UsageDashboard.vue`（替换 Task 6 占位）
- Create: `web/src/views/admin/usage/__tests__/UsageDashboard.spec.ts`

**Interfaces:**
- Consumes: Task 6 的 api/类型/useNameMaps、Task 7 的 TrendChart。

- [ ] **Step 1: 写失败测试**

```ts
// web/src/views/admin/usage/__tests__/UsageDashboard.spec.ts
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const overview = {
  promptTokens: '3000000', completionTokens: '1500000', totalTokens: '4500000',
  callCount: '42', estimatedCost: '5.0000', costIncomplete: true,
}
const daily = [
  { date: '2026-07-16', promptTokens: '100', completionTokens: '50', callCount: '2', estimatedCost: '0.5000' },
]
const rankings = [
  { targetId: '10', promptTokens: '100', completionTokens: '50', totalTokens: '150', callCount: '2', estimatedCost: '0.5000' },
]

vi.mock('@/api/admin/usage', () => ({
  fetchOverview: vi.fn().mockResolvedValue(overview),
  fetchDaily: vi.fn().mockResolvedValue(daily),
  fetchRankings: vi.fn().mockResolvedValue(rankings),
}))
vi.mock('@/composables/useNameMaps', () => ({
  useNameMaps: () => ({
    load: vi.fn().mockResolvedValue(undefined),
    resolveUser: (id: string) => `用户${id}`,
    resolveApp: (id: string) => `应用${id}`,
    resolveModel: (id: string) => `模型${id}`,
  }),
}))
vi.mock('../TrendChart.vue', () => ({
  default: { name: 'TrendChart', props: ['dates', 'tokens', 'costs'], template: '<div data-test="trend-chart" />' },
}))

import { fetchRankings } from '@/api/admin/usage'
import UsageDashboard from '../UsageDashboard.vue'

describe('UsageDashboard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('加载后渲染总览卡、费用不完整角标与默认应用排行', async () => {
    const wrapper = mount(UsageDashboard, {
      global: { stubs: { transition: false } },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="card-total-tokens"]').text()).toContain('4,500,000')
    expect(wrapper.find('[data-test="card-cost"]').text()).toContain('5.00')
    expect(wrapper.find('[data-test="cost-incomplete"]').exists()).toBe(true)
    expect(fetchRankings).toHaveBeenCalledWith('app', expect.any(String), expect.any(String), 10)
    expect(wrapper.text()).toContain('应用10')
  })

  it('切换排行 tab 重新拉对应维度', async () => {
    const wrapper = mount(UsageDashboard)
    await flushPromises()
    await wrapper.find('[data-test="tab-user"]').trigger('click')
    await flushPromises()
    expect(fetchRankings).toHaveBeenCalledWith('user', expect.any(String), expect.any(String), 10)
  })
})
```

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/UsageDashboard.spec.ts`
Expected: FAIL（占位组件无内容）。

- [ ] **Step 2: 实现 UsageDashboard.vue**

要点（完整 SFC，风格对齐既有 admin 页如 ToolList/ProviderDetail：PageHeader + el-card + scss BEM）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDaily, fetchOverview, fetchRankings } from '@/api/admin/usage'
import { useNameMaps } from '@/composables/useNameMaps'
import type { DailyUsagePoint, RankDimension, UsageOverview, UsageRankingItem } from '@/types/usage'
import TrendChart from './TrendChart.vue'
import PageHeader from '@/components/PageHeader.vue'

// —— 时间窗：预设 今日/近7天/近30天 + 自定义（92 天上限与后端一致）——
type Preset = 'today' | '7d' | '30d'
const preset = ref<Preset>('7d')
const customRange = ref<[string, string] | null>(null)

function toDateStr(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const range = computed<[string, string]>(() => {
  if (customRange.value) return customRange.value
  const end = new Date()
  const start = new Date()
  if (preset.value === '7d') start.setDate(end.getDate() - 6)
  if (preset.value === '30d') start.setDate(end.getDate() - 29)
  return [toDateStr(start), toDateStr(end)]
})

const overview = ref<UsageOverview | null>(null)
const daily = ref<DailyUsagePoint[]>([])
const dimension = ref<RankDimension>('app')
const ranking = ref<UsageRankingItem[]>([])
const loading = ref(false)

const nameMaps = useNameMaps()
const resolveTarget = (id: string) =>
  dimension.value === 'app' ? nameMaps.resolveApp(id)
    : dimension.value === 'user' ? nameMaps.resolveUser(id)
      : nameMaps.resolveModel(id)

const fmt = (n: string) => Number(n).toLocaleString()
const fmtCost = (c: string) => Number(c).toFixed(2)

const chartDates = computed(() => daily.value.map((d) => d.date))
const chartTokens = computed(() =>
  daily.value.map((d) => Number(d.promptTokens) + Number(d.completionTokens)))
const chartCosts = computed(() => daily.value.map((d) => Number(d.estimatedCost)))

async function loadStats() {
  loading.value = true
  try {
    const [start, end] = range.value
    ;[overview.value, daily.value, ranking.value] = await Promise.all([
      fetchOverview(start, end),
      fetchDaily(start, end),
      fetchRankings(dimension.value, start, end, 10),
    ])
  } finally {
    loading.value = false
  }
}

async function loadRanking() {
  const [start, end] = range.value
  ranking.value = await fetchRankings(dimension.value, start, end, 10)
}

function onCustomChange(val: [string, string] | null) {
  if (!val) return
  const days = (new Date(val[1]).getTime() - new Date(val[0]).getTime()) / 86400000 + 1
  if (days > 92) {
    ElMessage.warning('日期范围不能超过 92 天')
    customRange.value = null
    return
  }
  customRange.value = val
  loadStats()
}

onMounted(async () => {
  await Promise.all([nameMaps.load(), loadStats()])
})
</script>
```

模板骨架（用 Element Plus：`el-radio-group` 预设切换、`el-date-picker type="daterange" value-format="YYYY-MM-DD"` 自定义、3 张 `el-card` 总览卡带 `data-test="card-total-tokens" / card-calls / card-cost"`、`costIncomplete` 时卡内 `el-tooltip content="存在未配单价的模型，费用不完整"` 包警告图标 `data-test="cost-incomplete"`、`TrendChart`、`el-tabs` 三个 tab `data-test="tab-app|tab-user|tab-model"`（`@tab-change` 改 `dimension` 并 `loadRanking()`）、排行 `el-table`（列：名称=`resolveTarget(row.targetId)`、totalTokens/callCount 用 `fmt`、estimatedCost 用 `fmtCost`）、右上角 `el-button` 链接到 `/admin/usage/call-logs`「调用日志」）。总 token 卡片副行小字显示 `输入 {fmt(promptTokens)} / 输出 {fmt(completionTokens)}`。

- [ ] **Step 3: 跑测试确认绿灯**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/UsageDashboard.spec.ts && pnpm typecheck`
Expected: PASS。若 el-tabs 的 tab 点击在 jsdom 触发不到 `@tab-change`，改为直接调用组件暴露方法或对 `el-tabs` stub（既有三个 jsdom 坑先例：transition/focus/防抖，处理方式记入测试注释）。

- [ ] **Step 4: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src && git commit -m "feat(web): 用量看板页——时间窗+总览卡+趋势图+三维排行"
```

---

### Task 9: CallLogList 页面（筛选+加载更多）

**Files:**
- Modify: `web/src/views/admin/usage/CallLogList.vue`（替换 Task 6 占位）
- Create: `web/src/views/admin/usage/__tests__/CallLogList.spec.ts`

**Interfaces:**
- Consumes: Task 6 的 `fetchCallLogs`/`CallLogPage`/useNameMaps。

- [ ] **Step 1: 写失败测试**

```ts
// web/src/views/admin/usage/__tests__/CallLogList.spec.ts
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const page1 = {
  list: [{
    id: '1', userId: '1', appId: '10', modelId: '5',
    promptTokens: '100', completionTokens: '50', source: 'conversation', createTime: '2026-07-17T10:00:00+08:00',
  }],
  nextCursor: 'CURSOR1',
  hasMore: true,
}
const page2 = {
  list: [{
    id: '2', userId: '2', appId: '20', modelId: '6',
    promptTokens: '200', completionTokens: '60', source: null, createTime: '2026-07-17T09:00:00+08:00',
  }],
  nextCursor: null,
  hasMore: false,
}

vi.mock('@/api/admin/usage', () => ({
  fetchCallLogs: vi.fn().mockResolvedValueOnce(page1).mockResolvedValueOnce(page2),
}))
vi.mock('@/composables/useNameMaps', () => ({
  useNameMaps: () => ({
    load: vi.fn().mockResolvedValue(undefined),
    resolveUser: (id: string) => `用户${id}`,
    resolveApp: (id: string) => `应用${id}`,
    resolveModel: (id: string) => `模型${id}`,
  }),
}))

import { fetchCallLogs } from '@/api/admin/usage'
import CallLogList from '../CallLogList.vue'

describe('CallLogList', () => {
  beforeEach(() => vi.clearAllMocks())

  it('默认近7天首查，加载更多带游标追加，无更多后按钮消失，历史行来源显示「—」', async () => {
    const wrapper = mount(CallLogList)
    await flushPromises()
    expect(fetchCallLogs).toHaveBeenCalledTimes(1)
    const first = vi.mocked(fetchCallLogs).mock.calls[0][0]
    expect(first.cursor).toBeUndefined()
    expect(wrapper.text()).toContain('用户1')

    await wrapper.find('[data-test="load-more"]').trigger('click')
    await flushPromises()
    const second = vi.mocked(fetchCallLogs).mock.calls[1][0]
    expect(second.cursor).toBe('CURSOR1')
    expect(wrapper.text()).toContain('用户2')
    expect(wrapper.text()).toContain('—') // source=null 的历史行
    expect(wrapper.find('[data-test="load-more"]').exists()).toBe(false)
  })
})
```

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/CallLogList.spec.ts`
Expected: FAIL。

- [ ] **Step 2: 实现 CallLogList.vue**

要点（完整 SFC）：
- 筛选区 `el-form inline`：`el-date-picker type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ssZ"`（默认近 7 天：`onMounted` 时算 `[now-7d, now]`）、来源 `el-select`（全部/conversation→「对话」/workflow→「工作流」）、用户/应用/模型三个 `el-select filterable clearable`（选项由 useNameMaps 的三个 Map 生成——给 composable 加 `userOptions/appOptions/modelOptions` computed 或在页面内自行由 resolve 前的 Map 构建；实现选简单者：composable 返回 `users/apps/models` 三个 `Map` ref 本身，页面 `[...map.entries()]` 转选项）、「查询」按钮（重置游标重查）。
- 状态：`rows: CallLogItem[]`、`nextCursor: string | null`、`hasMore: boolean`、`loading`。查询函数 `query(reset: boolean)`：reset 清 rows 与游标；带 `cursor: nextCursor ?? undefined`；结果 `rows.push(...res.list)`。
- `el-table`：时间（`createTime` 直显或用 `@/utils/datetime` 既有格式化函数，动手前读该文件选用现成导出）、用户/应用/模型（resolve）、来源（`conversation→对话，workflow→工作流，null→—`）、输入/输出 token（`Number(x).toLocaleString()`）。
- 表格下方 `hasMore` 时渲染 `el-button data-test="load-more"`「加载更多」→ `query(false)`。
- 时间窗前端预校验 >31 天 `ElMessage.warning('时间范围不能超过 31 天')` 并不发请求。

- [ ] **Step 3: 跑测试确认绿灯**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/usage/__tests__/CallLogList.spec.ts && pnpm typecheck`
Expected: PASS。（若 useNameMaps 返回值形状因选项需求调整，同步回改 Task 6 的 composable、其测试与 Task 8 的 mock——三处一起动。）

- [ ] **Step 4: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src && git commit -m "feat(web): 调用日志页——四维筛选+时间窗+游标加载更多"
```

---

### Task 10: 供应商详情页模型单价表单 + 全量回归（回归并入最后功能 Task，防跳过）

**Files:**
- Modify: `web/src/types/model.ts`（AiModel/ModelForm 加 `inputPrice/outputPrice: number | null`）
- Modify: `web/src/api/admin/model.ts`（updateModel 的 body 类型改为 `Pick<ModelForm, 'name' | 'modelKey' | 'inputPrice' | 'outputPrice'>`）
- Modify: `web/src/views/admin/provider/ProviderDetail.vue`（表单+表格列）
- Modify: `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts`（补单价用例）

**Interfaces:**
- Consumes: Task 3 后端 DTO（inputPrice/outputPrice 为 JSON number|null）。

- [ ] **Step 1: 写失败测试（在既有 ProviderDetail.spec.ts 追加，沿用该文件既有 mount/mock 方式）**

```ts
it('新增模型可填单价，提交体带单价；不填则为 null', async () => {
  // 沿用本文件既有的打开新增弹窗流程
  // 填 name/modelKey 后，设置单价输入 data-test="input-price" / "output-price" 为 2 与 6
  // 断言 createModel 被调时 body.inputPrice === 2 && body.outputPrice === 6
  // 再走一遍不填单价的分支：body.inputPrice === null
})

it('模型表格显示单价列，未配价显示「未配置」', async () => {
  // listModels mock 返回一行 inputPrice: 2, outputPrice: null
  // 断言表格文本含 "2" 与 "未配置"
})
```

（测试体按该 spec 文件既有工具函数写全——它已处理 el-dialog/transition 的 jsdom 坑，照抄同款交互方式；此处两用例的断言目标如上，不得省略。）

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts`
Expected: FAIL（新用例红）。

- [ ] **Step 2: 实现**

- `types/model.ts`：`AiModel` 与 `ModelForm` 各加 `inputPrice: number | null` 与 `outputPrice: number | null`（注释「元/百万 token；null=未配置」）。
- `ProviderDetail.vue`：
  - `form` reactive 初值加 `inputPrice: null, outputPrice: null`；打开新增弹窗重置为 null；编辑弹窗回填 `row.inputPrice/row.outputPrice`。
  - 弹窗 `el-form` 加两项：`<el-form-item label="输入单价"><el-input-number v-model="form.inputPrice" :min="0" :precision="4" :controls="false" placeholder="未配置" data-test="input-price" /><span class="unit">元/百万 token</span></el-form-item>`，输出单价同款 `data-test="output-price"`。
  - 提交体（create 与 update）都带 `inputPrice: form.inputPrice, outputPrice: form.outputPrice`。
  - 模型表格加「单价(元/百万)」列：`row.inputPrice == null || row.outputPrice == null ? '未配置' : `${row.inputPrice} / ${row.outputPrice}``。
- `api/admin/model.ts`：updateModel 入参类型如上（PUT 全量：null 就传 null，后端置空）。

- [ ] **Step 3: 跑该 spec 确认绿灯**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts`
Expected: PASS。

- [ ] **Step 4: 全量回归（后端+前端，谁挂修谁，修完重跑）**

Run:
```bash
cd /home/wang/playlab/hify/server && mvn verify > /tmp/claude-1000/-home-wang-playlab-hify/2413d3eb-7c4c-4c73-8b58-3cb1db4cff43/scratchpad/mvn-verify.log 2>&1; echo exit=$?
tail -20 /tmp/claude-1000/-home-wang-playlab-hify/2413d3eb-7c4c-4c73-8b58-3cb1db4cff43/scratchpad/mvn-verify.log
cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck && pnpm lint && pnpm build
```
Expected: mvn `exit=0`（含 ModularityTests/LayerRulesTest/三个连库 Test）；pnpm 四连全过退出码 0。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src && git commit -m "feat(web): 供应商详情页模型单价表单与单价列；全量回归通过"
```

---

### Task 11: 人工验收清单（执行者停在此，交还用户）

不写代码。重启本地服务（**重打包+换进程**：`make restart` 或既定跑法），然后由用户浏览器验收：

1. 供应商详情页给一个在用 chat 模型配单价（如 输入 2 / 输出 6），另一个模型不配。
2. 发起一轮普通对话 + 跑一次含 LLM 节点的工作流。
3. 打开「用量看板」：总览卡有数、费用卡带「不完整」角标（存在未配价模型）、趋势图双轴渲染、三个排行 tab 名称显示正常（非裸 id）。
4. 打开「调用日志」：能看到刚才两条流水，来源分别是「对话」「工作流」；历史旧流水来源显示「—」；按来源筛选生效；改小 limit 验证「加载更多」。
5. Member 账号直访 `/admin/usage` 应被拦（403 页）。
6. 自检结果追加 `docs/self-check.md`（含 mvn/pnpm 全量回归数字），随收尾 commit。
