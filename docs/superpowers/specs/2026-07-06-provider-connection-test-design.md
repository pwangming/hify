# 供应商试连接 · 设计文档

> 日期：2026-07-06　状态：已与用户逐段确认
> 背景：模型供应商配置好后，没有任何手段验证 Key / baseUrl / 网络是否真的通，
> 只能等到真实对话或文档向量化失败才发现。后端已有模型级测试端点
> `POST /api/v1/admin/provider/models/{id}/test`（发一句 "ping" 真实调用），但前端从未接入。

## 1. 需求与拍板结论

| 决策点 | 结论 |
|---|---|
| 测试粒度 | **两级都做**：供应商列表页「试连接」+ 详情页模型行「测试」 |
| 状态列语义 | 展示**最近一次手动测试的结果**（真实测试烧钱耗时，不做后台实时监控/定时轮询） |
| 状态存储 | **落库**（`model_provider` 加字段），带时间戳，刷新/换人可见 |
| embedding 模型 | 模型级测试**支持**（chat 发 ping 聊天，embedding 把 ping 转向量） |
| 实现方案 | 供应商级测试由**后端新端点全包**（挑模型、调用、落库、返回），前端一次调用 |

边界约定：
- **模型级测试不回写供应商状态**——modelKey 写错是模型自身问题，不代表供应商连接坏了；
  供应商状态只由供应商级试连接更新，语义干净。
- **仅启用中的供应商/模型可测试**（禁用行按钮置灰），与韧性层现有校验口径一致。
- 测试成功或失败**都落库**；因「无启用模型」而根本没发出请求时**不落库**。

## 2. 数据库变更（V17）

`V17__alter_model_provider_last_test.sql`，给 `model_provider` 加 3 个可空字段：

| 字段 | 类型 | 含义 |
|---|---|---|
| `last_test_status` | varchar，check in (`ok`,`fail`)，可空 | NULL = 从未测试 |
| `last_test_at` | timestamptz，可空 | 上次测试时间 |
| `last_test_error` | text，可空 | 失败原因（成功时置 NULL） |

不建历史表：状态与供应商一对一，只关心最近一次，加字段是最简单做法（YAGNI）。

## 3. 后端

### 3.1 新端点

`POST /api/v1/admin/provider/providers/{id}/test`（动作子资源 POST，与既有
`/models/{id}/test` 同款式；已现场核对 api-standards.md）。
响应 `Result<ProviderTestResponse>`，含测试用的模型名与样例文本。

### 3.2 执行逻辑（`ModelConnectionService.testProvider`）

1. 校验供应商存在且启用；
2. 挑该供应商下一个**启用**模型：优先 chat（ping 聊天便宜），无 chat 则用 embedding；
3. 一个启用模型都没有 → 抛既有 12002，message「该供应商下暂无启用的模型，无法试连接」，**不落库**；
4. 经 ResilienceRegistry 真实调用（超时/重试/熔断自动继承供应商韧性配置）；
5. 成功：写 `ok` + 时间，清 `last_test_error`，返回结果；
   失败：写 `fail` + 时间 + 原因，然后把原异常继续抛出（前端立即看到错误提示，刷新后状态列也正确）。

服务不加 `@Transactional`（真实外部 IO，事务内禁外部调用——llm-resilience.md §1）；
落库是单行 UPDATE，无需事务包裹。

### 3.3 模型级测试扩展 embedding

`ModelConnectionService.test(modelId)` 按模型类型分流：
- chat：现有逻辑不变（ping 聊天，返回模型回复样例）；
- embedding：`registry.getEmbeddingModel(id)` 把 "ping" 转向量，样例文本形如「已返回 1024 维向量」。

`ModelTestResponse(sample)` 结构不变（已发布契约只增不改）。

### 3.4 错误码

不发明新码。失败按既有韧性映射：12002（模型不可用）/ 12003（供应商不可用）/
12004（繁忙）；无启用模型复用 12002 + 自定义 message。

### 3.5 DTO

`ProviderResponse` 增加 `lastTestStatus`（`"ok"`/`"fail"`/null）、`lastTestAt`（ISO-8601/null）、
`lastTestError`（string/null）。新增字段不算破坏性变更。

## 4. 前端

### 4.1 API 层与超时

- `api/admin/provider.ts` 加 `testProvider(id)`；`api/admin/model.ts` 加 `testModel(id)`。
- `config/index.ts` 加 `llmTestTimeoutMs = 130_000`：试连接是真实 LLM 调用，后端非流式
  预算最长 120s，前端超时必须 ≥ 后端预算（会话模块踩过的坑），两个测试请求都带此专用超时。

### 4.2 供应商列表页 `ProviderList.vue`

- 新增「连接」列，`el-tag` 三态：绿「通过」/ 红「失败」/ 灰「未测试」；
  通过/失败的 tag 用 tooltip 展示测试时间，失败的另展示 `lastTestError`。
- 操作列加「试连接」按钮：点击转 loading（防连点重复烧钱），完成后刷新列表；
  成功 `ElMessage.success`，失败由全局拦截器统一弹错（既有机制，不重复处理）。
- 供应商禁用时按钮置灰。

### 4.3 供应商详情页 `ProviderDetail.vue`

- 每个模型行操作里加「测试」按钮（loading + 专用超时，禁用模型置灰）；
- 成功弹提示并展示返回样例；失败走全局错误提示；
- 模型表**不加**状态列（不落库，即时看结果即可）。

## 5. 测试策略（TDD）

- 后端单测（mock ResilienceRegistry / Mapper）：优先挑 chat；仅 embedding 时用 embedding；
  无启用模型抛 12002 且不落库；成功落库 `ok`；失败落库 `fail` 后原异常继续抛；
  embedding 模型分流调用 EmbeddingModel。跑通 ModularityTests / ArchUnit。
- 前端 vitest（`__tests__/`）：API 函数的 URL 与超时参数；列表页三态渲染与 tooltip；
  试连接按钮 loading 与完成后刷新；禁用行按钮置灰；详情页模型测试按钮行为。

## 6. 不做什么

- 后台定时自动测试（持续烧 token，内部平台手动足够）；
- 测试历史记录表；
- 模型级测试结果落库/展示列。
