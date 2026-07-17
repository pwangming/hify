# 用量与成本看板轮设计：Token/费用统计 + 调用日志查询（管理控制台收尾）

日期：2026-07-17
状态：已与用户逐节确认
背景：运维补账轮之后的新功能轮。管理控制台承诺的板块中仅剩「用量与成本看板」未落地
（供应商/用户/系统设置/工具注册表/知识库全局视图均已就绪）。⑥ 配额轮铺好的
`llm_call_log` + `daily_usage` 是数据地基，但 admin 完全看不到——本轮把它变成看板。

## 0. 范围拍板（与用户逐项确认）

| 决策点 | 结论 | 为什么 |
|---|---|---|
| 成本口径 | **Token + 钱都要**；模型单价 admin 手填、**可不填**（不填只显示 token） | 「成本看板」名副其实，防异常 Agent 刷账单更直观；可选填对免费/未知价模型友好 |
| 看板板块 | ① 总览卡+按天趋势 ② 应用/用户/模型三维排行 ③ 调用日志明细查询 | 三块覆盖「花了多少、谁在花、每一笔是什么」 |
| 应用全局视图 | **砍掉不做** | 团队共享制下人人本就能看所有应用（`GET /api/v1/app/apps`），边际价值低 |
| 观测列 | 只补 `source`（conversation/workflow）一列 | 改动小、日志与排行都能按来源筛；耗时/成败留二期（失败轮不发事件，本就记不到） |
| 聚合数据源 | **方案 C：新建看板聚合表 `usage_stat_daily`** | 见 §1；database-standards 铁律「看板/配额永远查聚合表，禁止对 llm_call_log 实时聚合」 |
| 费用计算位置 | **后端算**：改依赖白名单 usage→provider::api 取单价，Java 层 BigDecimal | 金额口径（精度/舍入/未配价）收在一处；前端算会把钱的逻辑散进 JS 浮点 |
| 名称解析 | **前端拼装**：接口只出 id，前端拉用户/应用/模型列表建映射 | usage 白名单不依赖 app，identity 全局禁依赖（硬规则）；管理页数据量 ≤ 几十条，拉一次映射零架构改动 |
| 图表库 | **引入 ECharts**（按需引入，登记进 frontend-standards 技术栈） | 技术栈无图表库，经用户拍板新增；事实标准、后续看板可复用 |

被否/暂缓的方案，防止将来重议：
- 方案 A（改铁律实时扫流水）：省两张表，但旧分区将来 drop 后历史统计塌方——聚合表是唯一能长期留历史的地方。
- 方案 B（改造 daily_usage 加 model 维度）：动全站最关键的配额检查路径 + 存量迁移，为省一张表不值。
- 二期留账：配额检查切到 `usage_stat_daily` 后可废弃 `daily_usage`（两张聚合表冗余是本轮明知的代价）。

## 1. 数据模型与迁移（V26 单脚本四件事）

### 1.1 `ai_model` 加单价列（additive）

```sql
alter table ai_model
    add column input_price  numeric(10,4),
    add column output_price numeric(10,4);
```

- 单位：**元 / 百万 token**；币种固定人民币（通义千问为主的现实，不做多币种）。
- **可空**：null = 未配单价，该模型费用按 0 计并让接口置 `costIncomplete=true`（见 §3）。
- check 约束：两列各自 `>= 0`（允许 0 元表示免费模型，与 null「未配置」语义区分）。

### 1.2 `llm_call_log` 加 `source` 列

```sql
alter table llm_call_log
    add column source text check (source in ('conversation', 'workflow'));
```

- **可空**：历史流水填不出来源，留 null（前端显示「—」）；新流水必填。
- 分区父表 alter 自动传播到全部子分区，无需逐分区操作。

### 1.3 新建看板聚合表 `usage_stat_daily`

```sql
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
create unique index usage_stat_daily_dim_uq
    on usage_stat_daily (user_id, app_id, model_id, stat_date);  -- UPSERT 冲突目标
create index usage_stat_daily_date_idx on usage_stat_daily (stat_date);  -- 时间窗查询
```

- 维度 `stat_date × user_id × app_id × model_id`；prompt/completion **拆开存**（输入输出单价不同，合计列算不了钱）。
- `call_count`：**成功完成的轮次数 = 事件数**。Agent 一轮内部多次 LLM 调用算 1 次，与流水行数同口径。
- 普通表**不分区**：行数 = 活跃维度组合数，50 人团队一年量级也就几万行（db-standards 大表分级第一级）。
- `stat_date` 按**北京时间**归日，与 `daily_usage` 及日志表分区边界口径一致（运维补账轮刚修过时区漂移，此处不允许再犯）。

### 1.4 存量回填

```sql
insert into usage_stat_daily (stat_date, user_id, app_id, model_id,
                              prompt_tokens, completion_tokens, call_count)
select (create_time at time zone 'Asia/Shanghai')::date,
       user_id, app_id, model_id,
       sum(prompt_tokens), sum(completion_tokens), count(*)
from llm_call_log
group by 1, 2, 3, 4;
```

历史流水一行不丢进聚合表；跑在建表同一迁移里，天然只执行一次。

## 2. 事件与双写

- `TokenUsedEvent`（common 包 record）加 `String source` 字段，取值 `"conversation"` / `"workflow"`。
  两个发布点（conversation 收消息成功、workflow 触发成功）各自填值——record 加字段让所有构造点
  编译失败，逼着改齐，不会漏。
- `UsageEventListener` 在现有「落 `llm_call_log` 流水（补 source 列）+ UPSERT `daily_usage`」的
  **同一事务**里加第三步：UPSERT `usage_stat_daily`（db-standards「插或改一律 UPSERT」固定模板）。
- **配额路径零改动**：`checkQuota` 仍只查 `daily_usage`。

## 3. 后端接口（4 个 GET，`/api/v1/admin/usage/**`，仅 Admin，usage 模块 controller/ 下）

按 api-standards 现场重读核对：admin 路由带模块段 ✓、全 GET 无状态变更 ✓、Long/金额序列化为字符串 ✓、
错误码全部复用通用段（10001/10004）✓、游标分页用于运行日志族 ✓。

| 接口 | 入参 | 出参 |
|---|---|---|
| `GET /api/v1/admin/usage/stats/overview` | `startDate`、`endDate`（date，含当天） | `{promptTokens, completionTokens, totalTokens, callCount, estimatedCost, costIncomplete}` |
| `GET /api/v1/admin/usage/stats/daily` | 同上 | `[{date, promptTokens, completionTokens, callCount, estimatedCost}]`，按天升序，喂趋势图 |
| `GET /api/v1/admin/usage/stats/rankings` | 同上 + `dimension=app\|user\|model` + `limit`（默认 10，最大 50） | `[{targetId, promptTokens, completionTokens, totalTokens, callCount, estimatedCost}]`，按 totalTokens 降序；**只出 id**，前端拼名 |
| `GET /api/v1/admin/usage/call-logs` | `startTime`/`endTime`（timestamptz，**必填**，窗口 ≤ 31 天）+ 可选 `userId/appId/modelId/source` + `cursor/limit`（默认 20，最大 100） | 游标分页 `{list: [{id, userId, appId, modelId, promptTokens, completionTokens, source, createTime}], nextCursor, hasMore}`，不出 total |

- 三个 stats 接口查 `usage_stat_daily`；日期窗上限 **92 天**（一个季度），超限抛 10001。
- call-logs 查 `llm_call_log` 流水：必填时间窗保证分区裁剪；排序 `create_time desc, id desc`，
  游标 = Base64(create_time + id)，与流水表主键 `(id, create_time)` 天然对齐；游标对客户端不透明。
- **费用计算**：SQL 按 model_id 分组出 token → Java 层
  `cost = promptTokens × inputPrice / 1_000_000 + completionTokens × outputPrice / 1_000_000`，
  BigDecimal、scale 4、HALF_UP；未配单价的模型记 0 并置 `costIncomplete=true`。
  金额字段序列化为**字符串**（与 Long 全局转 string 同理，防 JS 精度）。
- 空数据 = 空数组 / 零值，code 200；不新增 14xxx 错误码。

### 3.1 依赖白名单变更（正规流程）

usage 允许依赖的业务模块从「无」改为「provider」：
1. 改 code-organization.md §1 表格（usage 行）；
2. 改 `com.hify.usage` 的 `package-info.java`：`allowedDependencies` 加 `"provider::api"`；
3. `ProviderFacade` 加批量取单价方法：`Map<Long, ModelPrice> getModelPrices(Collection<Long> modelIds)`，
   `ModelPrice(BigDecimal inputPrice, BigDecimal outputPrice)` 为 api 顶层包 record
   （Modulith 1.4.1 不暴露 api/dto 子包的坑，放 api 顶层）。
4. ModularityTests / ArchUnit 回归守护。

**仅为单价开这一条**；名称解析统一前端做（不为 usage→app 再开口子，identity 更是全局禁依赖）。

### 3.2 模型管理页支持单价

- provider 模块既有 admin 模型接口的 Request/Response DTO 加 `inputPrice`/`outputPrice`（可空）；
  更新走 PUT 全量语义，未传视为置空（与 api-standards 一致，无特殊处理）。
- 前端供应商详情页模型表单加两个可选数字输入（单位提示「元/百万 token」）。

## 4. 前端（`web/src/views/admin/usage/`）

| 页面/部件 | 内容 |
|---|---|
| `UsageDashboard.vue` | 时间窗选择器（今日/近7天/近30天/自定义，自定义受 92 天上限）+ 3 张总览卡（总 token·附输入/输出拆分小字、调用次数、估算费用·含未计价角标）+ ECharts 按天趋势折线（**双 y 轴**：左 token、右费用）+ 三维排行 el-table 切 el-tabs |
| `CallLogList.vue` | 筛选表单（时间窗默认近 7 天 + 用户/应用/模型/来源下拉）+ el-table + 「加载更多」游标翻页（无总页数） |
| `TrendChart.vue` | 唯一直接触碰 ECharts 的组件：`echarts/core` 按需注册折线/柱状/tooltip/grid，**不引 vue-echarts 包装层**（少一层依赖） |
| `useNameMaps` composable | 并行拉 admin 用户列表、应用列表、模型列表建三个 `Map<id,name>`；解析不到显示 `#42（已删除）` |
| 导航/路由 | admin 侧边栏加「用量看板」；路由挂 admin 权限段（既有 meta 机制） |

- 金额展示保留 2 位小数；`costIncomplete=true` 时费用卡片加 el-tooltip 角标「存在未配单价的模型，费用不完整」。
- Element Plus 组件优先（既定纪律），图表是唯一例外场景。
- frontend-standards.md 技术栈段登记 ECharts（按需引入口径）。

## 5. 测试与守护

| 层 | 内容 |
|---|---|
| 后端单测 | 费用计算（精度/舍入/未配价/零 token）、日期窗校验（92 天/跨年）、游标编解码往返 |
| 后端连库测试 | 聚合 SQL（overview/daily/rankings 三查询）、listener 三步双写、V26 回填正确性——复用既有 Testcontainers 基建（手写 SQL 必须连真库验，K 轮以来既定口径） |
| 模块边界 | ModularityTests / ArchUnit：usage→provider::api 白名单生效、其余边界不破 |
| 事件回归 | TokenUsedEvent 加字段 → 两个发布点编译失败逼改齐；listener 测试断言流水行含 source |
| 前端 | vitest TDD、`__tests__/` 先红后绿；**jsdom 第四坑预案**：ECharts 依赖 canvas，测试 mock `echarts/core` |

## 6. 明确不做（本轮）

- 应用全局视图（砍掉，理由见 §0）
- 耗时/成功失败观测列（失败轮不发事件记不到；留二期与 provider 观测事件轮合并考虑）
- 多币种、单价历史快照（改单价历史费用跟着变，看板明示「按当前单价估算」口径）
- 配额检查迁移到新聚合表（留账二期，届时废弃 daily_usage）
- 导出 CSV / 报表订阅
