# provider C2 设计（写侧地基：getChatClient + ChatClientFactory + 四层韧性，纯后端）

> 日期：2026-06-25　范围：provider 模块 C 轮的后半（C2，写侧/调用侧），**纯后端，无前端**
> 配套规范：llm-resilience.md（线程/超时/重试/容错，本轮主依据）、code-organization.md（模块边界/Facade/分层）、
> api-standards.md（路由族/Result/错误码/HTTP 语义）、database-standards.md（DDL 迁移安全）、data-model.md。
> 前置：本文承接 `2026-06-25-provider-c1-design.md` 第 6 节「与 C2 的衔接」清单。

## 1. 背景与排期定位

排期：`app ① → provider C ② → conversation ③`。C 已拆 C1（读侧）+ C2（写侧/调用侧）：

- **C1（已完成）**：`findUsableChatModel` / `getModelNames` / `filterUsableChatModelIds` 读侧校验，成员侧列模型接口，app 接 Facade 校验 `model_id`。**完全不碰 ChatClient / LLM / 韧性**。
- **C2（本轮）**：`ProviderFacade.getChatClient(modelId)` —— 按协议构建一个**自带全套（非流式）韧性**的 `ChatClient`，附 admin「测试连通」端点做真实冒烟。C2 解锁下一轮 conversation 单轮聊天（③）。

C2 是**纯 provider 模块**那一轮，**无任何前端改动**；conversation 是再下一轮。

### 本轮做什么
- `ProviderFacade` 新增 `getChatClient(Long modelId)`，返回 Spring AI `ChatClient`（带韧性）。
- `ChatClientFactory`：按 `protocol`（openai / anthropic）构建原始 `ChatModel`，注入 `baseUrl` + `ApiKeyCipher.decrypt` 后的 Key + 模型 `modelKey`。
- 四层非流式韧性 `Retry( CircuitBreaker( Bulkhead( TimeLimiter( 真实调用 ))))`，封装在 `ResilientChatModel` 装饰器。
- `ResilienceRegistry`：双键缓存（四件套按 `providerId`、ChatClient 按 `modelId`）+ 模块内 `invalidate` 热生效。
- `model_provider` 新增 10 个韧性配置字段（Flyway `V8`，均带默认值），本轮消费 6 个。
- provider 段新增 3 个运行时错误码（12002 / 12003 / 12004）。
- admin「测试连通」端点 `POST /api/v1/admin/provider/models/{id}/test`。

### 本轮不做（推迟，均有触发点）
| 推迟项 | 触发点 |
|---|---|
| 流式（Flux / SseEmitter / 首token+逐元素+总时长超时 / 首token前重试 / resilience4j-reactor） | chat UI 要打字机效果那轮 |
| 观测事件 `LlmCallCompletedEvent` → usage 落库 | usage 模块那轮（现无监听方，发了没人接） |
| embedding 批量池（消费 `batch_concurrency`）+ `getEmbeddingModel` | knowledge 轮 |
| admin **编辑** 10 个韧性参数的 UI / 接口 | 真要调参时（C2 只入库 + 默认值 + 生效逻辑，默认值够跑） |
| 前端「测试」按钮 + 韧性参数编辑页 | 后续碰 admin 模型管理页时（C2 用 Postman 验测试端点） |

### 关键决策（brainstorm 2026-06-25 拍板）
1. **仅非流式**：C2 只做阻塞式单次调用（`TimeLimiter` 总超时 + 整体重试），不做 Flux/SSE。装饰器 `ResilientChatModel` 留好口子，流式作为后续独立切片接入，**不动 Facade 契约与 conversation 代码**。
2. **四层韧性全做**：Timeout / Retry / CircuitBreaker / Bulkhead 四层一次到位（贴合 llm-resilience.md「韧性只写一遍」）。brainstorm 中明确把「砍掉熔断器只做三层」作为可选更简方案摆出，用户选择保留四层。
3. **观测推迟**：`LlmCallCompletedEvent` 等 usage 模块那轮再发（现无监听方）。
4. **缓存失效 = 模块内直接 evict**：provider 自己的 admin 服务更新成功后直接调 `ResilienceRegistry.invalidate(...)`，不跨模块、不引入事件机制。
5. **验证 = admin「测试连通」端点**：C2 无业务调用方，用一个本来迟早要做的运维端点（admin 配好供应商点测试）做真实 e2e 冒烟，而非一次性脚手架。
6. **Facade 返回 `ChatClient`**（非 `ChatModel`）：fluent API，③ 里 `.prompt().user(text).call().content()` 最省心。Facade 签名允许 Spring AI 类型（provider 例外，`ProviderFacade` 注释已写明）。
7. **10 字段一次性入库**：避免日后补第二次表结构迁移；C2 只消费 6 个，另 4 个（流式 3 + 批量 1）入库占位、默认值就位。

### 依赖与边界（无需改动）
- 韧性依赖 `resilience4j-spring-boot3`、Spring AI openai/anthropic starter、actuator **pom 里已就位**（C1 未用，C2 启用）。`resilience4j-reactor` 流式才需要，本轮不引入。
- app 的 `allowedDependencies` 已含 `provider::api`；`provider/api` 已标 `@NamedInterface("api")`。本轮不改任何 package-info。
- ArchUnit 已允许 `provider::api` 出现 Spring AI 类型（C1 的 `ProviderFacade` 注释已声明 provider 例外）；落 `getChatClient(...): ChatClient` 后需确认该规则仍绿（见 §6）。

## 2. 后端设计

### 2.1 模块内文件清单（均在 `com.hify.provider` 下）

```
api/ProviderFacade.java                      // +ChatClient getChatClient(Long modelId)
api/dto/ModelTestResponse.java               // 新建响应 DTO：record { String sample }（不含 entity）
service/ProviderFacadeImpl.java              // 改：getChatClient 薄委托给 ResilienceRegistry
service/ChatClientFactory.java               // 新建：按 protocol 建原始 ChatModel（baseUrl + decrypt key + modelKey）
service/resilience/ResilienceRegistry.java   // 新建：双键缓存 + invalidate / invalidateModel
service/resilience/ResilientChatModel.java   // 新建：装饰器 Retry(CB(Bulkhead(TimeLimiter(real))))，实现 ChatModel
service/resilience/ResilienceBundle.java     // 新建：一个 providerId 的四件套（TimeLimiter/Bulkhead/CB/Retry）持有者
service/AiModelService.java                  // 改：启停/改/删模型后调 registry.invalidateModel(id)
service/ProviderService.java                 // 改：改 key/baseUrl/protocol/启停/删供应商后调 registry.invalidate(id)
constant/ProviderError.java                  // +12002 / 12003 / 12004
entity/ModelProvider.java                    // +10 字段 getter/setter
controller/AdminModelController.java         // +POST /models/{id}/test
```

```
server/src/main/resources/db/migration/
└── V8__alter_model_provider_resilience.sql  // 10 字段，均 NOT NULL DEFAULT + 列注释
```

- `ProviderFacadeImpl`（service/）薄委托：`getChatClient` 转发给 `ResilienceRegistry`（或一个聚合 service）。Facade 不写业务逻辑。
- `ModelTestResponse` 放 `api/dto/`：是 admin 测试端点的响应体，亦不含 entity（ArchUnit 已禁 DTO import entity，转换在 service）。

### 2.2 `getChatClient(modelId)` 数据流

```
getChatClient(modelId)
  1. aiModelMapper.selectById(modelId)：null / deleted / status≠enabled / type≠chat
        └─→ 抛 BizException(ProviderError.MODEL_NOT_USABLE, 12002)   ← 兜底
  2. modelProviderMapper.selectById(providerId)：null / status≠enabled
        └─→ 抛 BizException(MODEL_NOT_USABLE, 12002)
  3. 从 ResilienceRegistry 拿/建并返回 ChatClient：
        ├─ ResilienceBundle（四件套）：按 providerId 缓存（Bulkhead/CB 供应商级共享）
        └─ ChatClient：按 modelId 缓存（每模型 modelKey 不同；缓存避免重复 decrypt）
```

- `getChatClient` 自身只**查库 + 建对象 + decrypt（纯 CPU）**，不发外部请求 → 被谁调都安全（不违反「事务内禁外部 IO」，但调用方真正 `.call()` 时必须在事务外）。
- 步骤 1/2 的重复校验是兜底：正常路径 ③ 入口已用 C1 `findUsableChatModel` 校验，这里防绕过。

### 2.3 双键缓存与失效（ResilienceRegistry）

**为何两层键（正确性，非性能）**：
- `Bulkhead`（信号量）与 `CircuitBreaker`（失败率滑动窗口）**必须按 providerId 共享一份**——同供应商下多个模型合用并发额度、合并统计失败率（打的是同一供应商、同一速率限制）。缓存键 = `providerId`。
- `ChatModel`/`ChatClient` 带 `modelKey`，按 `modelId` 区分；缓存它避免每次调用重复 `decrypt`。缓存键 = `modelId`。
- Registry 内部两个 `ConcurrentHashMap`：`Map<Long, ResilienceBundle>`（providerId→四件套）、`Map<Long, ChatClient>`（modelId→client，值里记其 providerId 以便连带清）。

**失效触发与范围（模块内直接调用，决策4）**：
| 触发动作（provider 模块内既有方法） | 调用 | 清除范围 |
|---|---|---|
| `ProviderService` 改 key / baseUrl / protocol / enable / disable / delete | `invalidate(providerId)` | 该 provider 的 `ResilienceBundle` **+** 名下所有 modelId 的 ChatClient（key/超时/连接都可能变） |
| `AiModelService` 改 / enable / disable / delete 某模型 | `invalidateModel(modelId)` | 仅该 modelId 的 ChatClient（供应商级四件套不动） |

- 线程安全：`computeIfAbsent` 构建；`invalidate` 原子 remove，下次调用重建。resilience4j 实例本身线程安全。

### 2.4 四层韧性（非流式）

装饰由内到外 `Retry( CircuitBreaker( Bulkhead( TimeLimiter( real ))))`：

| 层 | 关键参数 | 来源 |
|---|---|---|
| **TimeLimiter** | 总超时 = `response_timeout_sec`(120)；连接超时 = `connect_timeout_sec`(5，设在 RestClient) | DB |
| **Bulkhead**(SEMAPHORE) | 许可 = `max_concurrency`(10)；`maxWaitDuration`=2s 即失败 | DB + 固定 |
| **CircuitBreaker** | 失败率阈值 = `cb_failure_rate`(50%)；半开等待 = `cb_wait_open_sec`(30s)；窗口=20(COUNT_BASED)、半开试探=5、慢调用率=80%、慢调用阈值=30s | DB + 固定 |
| **Retry**(最外) | 次数 = `retry_max_attempts`(3=重试2次)；退避 1→2→4s + 全抖动；429 按 `Retry-After` | DB + 固定 |

**两个分类谓词（必须明确，否则误伤）**：
1. **Retry 白名单**（`retryOnException`）：重试 → 连接失败/建连超时、408、429、500/502/503、529(Anthropic overloaded)。**不重试** → 400/401/403、上下文超长、内容安全拦截、**非流式读超时**（已等满 120s，重试只翻倍延迟）、熔断拒绝（`CallNotPermittedException`）。
2. **CircuitBreaker 只记供应商故障**（`recordException`）：5xx / 超时 / 连接失败**计入**失败率；**4xx 客户端错误（400/401/403）不计入**——配置/用户问题不该把整个供应商熔断（防「key 填错→一直 400→误熔断」）。

**实现技术点**：
- HTTP 客户端统一 `RestClient`（底层 JDK HttpClient，虚拟线程友好），connect timeout 注入 Spring AI 的 `OpenAiApi`/`AnthropicApi`。
- `TimeLimiter` 需异步执行：用虚拟线程 executor（`Executors.newVirtualThreadPerTaskExecutor()` 或 `SimpleAsyncTaskExecutor` 虚拟线程模式）把同步 `ChatModel.call` 包成 `CompletableFuture` 加超时。
- **关掉 Spring AI 自带重试**：`application.yml` 设 `spring.ai.retry.max-attempts=1`，防 `3×3=9` 次重试风暴（全链路只此一处自动重试）。
- 固定常量（窗口20 / 半开5 / 慢调用率80% / 慢调用阈值30s / Bulkhead 等待2s / 退避1-2-4s）**不外化为配置**（llm-resilience.md 当固定值，外化只增噪音）。

### 2.5 协议 → Spring AI 模型映射

| protocol | 构建 | 备注 |
|---|---|---|
| `openai` | `OpenAiApi`(baseUrl, decryptedKey) + `OpenAiChatModel`，options.model = `modelKey` | 覆盖通义/Gemini/Ollama 等 openai 兼容 |
| `anthropic` | `AnthropicApi`(baseUrl, decryptedKey) + `AnthropicChatModel`，options.model = `modelKey` | Claude |

- 真实 ChatModel 的构造在 `ChatClientFactory` 内**留一个可注入/可覆盖的缝**（如按 protocol 分发到独立的构建方法），便于单测在不触网的前提下验证「选对分支 + 参数传对」。

### 2.6 `model_provider` 新增 10 字段（Flyway `V8`）

`ALTER TABLE model_provider ADD COLUMN ...`，均 `NOT NULL DEFAULT` + 列注释，小写蛇形命名：

| 字段 | 类型 | 默认 | C2 消费 | 字段 | 类型 | 默认 | C2 消费 |
|---|---|---|---|---|---|---|---|
| `max_concurrency` | int | 10 | ✅ | `connect_timeout_sec` | int | 5 | ✅ |
| `retry_max_attempts` | int | 3 | ✅ | `response_timeout_sec` | int | 120 | ✅ |
| `cb_failure_rate` | int | 50 | ✅ | `batch_concurrency` | int | 3 | ⛔（knowledge 轮，embedding 批量池） |
| `cb_wait_open_sec` | int | 30 | ✅ | `first_token_timeout_sec` | int | 30 | ⛔（流式轮） |
| | | | | `token_gap_timeout_sec` | int | 60 | ⛔（流式轮） |
| | | | | `stream_max_duration_sec` | int | 600 | ⛔（流式轮） |

- 实体 `ModelProvider` 补 10 字段 getter/setter。`ProviderResponse` 是否回显这些字段：**本轮不加**（admin 编辑 UI 推迟，DB 默认值生效即可）。
- DDL 安全（database-standards）：加列带默认值不锁表风险小（PG 11+ 带默认值加列为元数据操作）；不改既有列、不动既有迁移脚本。

### 2.7 运行时错误码（provider 12xxx 段，新增 3 个）

| code | HTTP | 含义（message） | 触发 |
|---|---|---|---|
| `12002` MODEL_NOT_USABLE | 400 | 所选模型不存在或不可用 | `getChatClient` 传入不存在/停用/非 chat/供应商停用（兜底） |
| `12003` PROVIDER_UNAVAILABLE | 503 | 模型供应商暂时不可用，请稍后重试 | 熔断打开(`CallNotPermitted`)、超时、重试耗尽后的 5xx/连接失败 |
| `12004` PROVIDER_BUSY | 429 | 当前使用人数较多，请稍后重试 | 信号量满(`BulkheadFullException`) |

- **异常映射只在 `ResilientChatModel.call` 一处做**：捕获 resilience4j 异常 → 转 `BizException(ProviderError.*)` → 全局处理器转 Result 信封。conversation / admin 测试端点都拿到干净业务异常，不各自 try-catch。
- 503/429 分法贴合 api-standards：熔断/超时=依赖不可用(503)，并发满=限流(429)。
- 已发布错误码只增不改：12001（EMBEDDING_NOT_SUPPORTED）不动。

### 2.8 admin 测试连通端点

```
POST /api/v1/admin/provider/models/{id}/test     // 仅 Admin；无请求体
  → service 调 getChatClient(id) 发最短 prompt（"ping"，输出 token 上限设很小，省钱）
  → 成功：Result<ModelTestResponse{ sample }>
  → 失败：BizException 透传 → 错误信封（12002 / 12003 / 12004）
```

- 挂既有 `AdminModelController`（前缀 `/api/v1/admin/provider`，与 model 启停删同族；动作子资源 POST，符合 api-standards「无法映射 CRUD 的动作用子资源 POST」）。
- 对应 service 方法**不加 `@Transactional`**（真实外部 IO，硬规则）。
- 仅 Admin：靠 `SecurityConfig` 的 `/api/v1/admin/**` 角色匹配器（无需端点内再判角色）。

## 3. 前端设计

**无前端改动。** admin 测试端点本轮用 Postman 验证；前端「测试」按钮与韧性参数编辑页推迟（见 §1 不做清单）。

## 4. 测试策略

后端连库测试（Testcontainers）仍推迟，全部 Mockito mock。真实 LLM 调用不进自动化（需真实 Key），仅人工走查（§5）。判定结果**不 grep `BUILD SUCCESS`**（`-q` 会静音），看测试计数/退出码。

### 4.1 后端单测
- `ChatClientFactory`：protocol=openai / anthropic 各走对分支；`ApiKeyCipher.decrypt` 被调；baseUrl / modelKey / 解密后 key 传对（构造真实 ChatModel 处用 §2.5 的缝 mock，不触网）。
- `ResilientChatModel`（包一个会抛错/拖延的 stub `ChatModel`，阈值调小）：
  - 超时：stub 拖过 `response_timeout` → `TimeLimiter` 触发 → 映射 12003。
  - 重试白名单：stub 抛 503 → 重试到上限；抛 400 → **不重试**、不计入熔断。
  - 熔断：连续失败超阈值 → CB 打开 → 后续 `CallNotPermitted` → 映射 12003（毫秒级）。
  - 信号量：占满 `max_concurrency` 且超 `maxWaitDuration` → `BulkheadFull` → 映射 12004。
  - 异常映射：各 resilience4j 异常 → 正确的 `BizException(ProviderError.*)`。
- `ResilienceRegistry`：同 providerId 返同 `ResilienceBundle`（缓存命中）；`invalidate(providerId)` 重建四件套并连带清名下 client；`invalidateModel(modelId)` 只清单条、四件套不动；四件套参数读自 10 字段。
- `ProviderService` / `AiModelService` 增量：改/启停/删后 `invalidate(...)` / `invalidateModel(...)` 被以正确入参调用（mock Registry 验证）。
- `AdminModelController` 测试端点（MockMvc，mock service）：ok 路径返回 `ModelTestResponse`（含 sample）；异常路径错误信封（503/429/400）；路由通、`Result` 信封正确。

### 4.2 模块边界
- `ModularityTests` + ArchUnit 保持绿；重点确认新增 `getChatClient(...): ChatClient`、`api/dto/ModelTestResponse` 不触发 DTO→entity 或 Spring AI 类型越界规则（provider api 例外已声明，落地后再跑一次确认）。

## 5. 端到端验证（人工走查）

1. `mvn test` 全绿（看测试计数/退出码，不 grep `BUILD SUCCESS`）。
2. admin 配一个真实 enabled 的 openai 兼容供应商（如通义）+ 一个 enabled chat 模型 → Postman `POST /api/v1/admin/provider/models/{id}/test` → 拿到真实回复 `sample`。
3. 把该供应商 Key 改错 → 立即 `/test`（不重启）→ 返回 `12003/503`（验证 `invalidate` 热生效）。
4. 禁用该供应商 → `/test` → `12002/400`。
5. （可选）连续打错 Key 触发熔断 → 后续 `/test` 毫秒级 503（CB 打开）。
6. Postman：新建 `hify-provider-c2.postman_collection.json`，覆盖 test 成功 + 12002 / 12003 / 12004。
7. 每步追加自检到 `docs/self-check.md`。

## 6. 与后续轮次的衔接（显式遗留）

| 缺口 | 补在哪 | 动作 |
|---|---|---|
| 流式（Flux/SSE/逐元素超时/首token前重试） | 流式轮 | `ResilientChatModel` 加 `StreamingChatModel` 路径 + `resilience4j-reactor` + `SseEmitter` 桥接；消费 `first_token/token_gap/stream_max` 三字段。 |
| 观测 `LlmCallCompletedEvent` → usage 落库 | usage 轮 | 调用结束发事件；usage 监听落库做用量看板。 |
| embedding 列举/校验 + 批量池 | knowledge 轮 | `getEmbeddingModel`；按 `batch_concurrency` 建批量 Bulkhead。 |
| admin 编辑 10 韧性参数 | 需调参时 | `Update/CreateProviderRequest` + `ProviderResponse` 放开字段；前端编辑页。 |
| 「对话应用必须有模型才能聊」+ 真实聊天 | conversation ③ | 入口校 `app.modelId` 非空且可用；调 `getChatClient` 发真实单轮对话。 |
