# knowledge K3 向量化 — 设计文档

> 日期：2026-07-03
> 模块：`knowledge` + `provider`（前后端一体）
> 前置：K2（文档上传/分段）已合并 main（71d4226）。knowledge 拆轮全景：K1 ✅ → K2 ✅ → **K3（本轮）** → K4 检索+命中测试（引 Testcontainers）→ E2E 基建轮 → K5 接进对话流。
> 范围口径：**kb_chunk 补 vector(1024)+HNSW → embedding 模型系统级设置（admin 可配、保存探测）→ 上传流水线整体异步化（提取→分段→向量化）→ 存量补嵌/换模型全量重嵌 → 失败重试（断点续嵌）+ 3 个 K2 跟进项顺路修**。
> 明确**不做**：向量检索（K4）、embedding Token 用量计量（后续小轮，TokenUsedEvent 绕 appId 设计需另议）、pdf/docx、嵌入进度百分比（只有四态状态）、多模型/多维度共存（架构已禁）、全局重嵌进度视图（去各库详情页看状态）。

---

## 0. 决策摘要（拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 流水线形态 | **整条链路异步**：上传请求只校验+存 bytea（status=pending）即返回；提取→分段→向量化全在异步任务跑。顺带解决 K2 跟进项「提取/解码在事务内占连接」与「50MB vs 30s 前端超时」 |
| 2 | 设置归属 | **provider 拥有** `system_setting` KV 表（data-model 预留名兑现）；选 embedding 模型是模型域的事，校验（存在/可用/维度）只有 provider 能做。knowledge 只调 `ProviderFacade.getEmbeddingModel()`（无参），不知设置存哪。将来真有其他系统设置再议归属迁移 |
| 3 | 维度校验 | **保存设置时探测**：admin 点保存，provider 事务外现场调一次该模型 embedding API，验返回维度==1024，不对拒绝保存并提示实际维度。构建时统一传 `dimensions=1024`（千问 v4 / OpenAI v3 支持可变维度） |
| 4 | 补嵌/重嵌触发 | **admin 显式「全量重嵌入」按钮**（系统设置页）：首次配好模型后点 = 存量补嵌；换模型后点 = 全量重嵌。一个机制两用，花钱动作显式发起 |
| 5 | 失败恢复 | failed 文档给**「重试」按钮 + 断点续嵌**（owner/admin 可点）：分段已在只嵌 `embedding is null` 的段（不重花钱）；分段不在从提取重跑 |
| 6 | 异步机制 | **事务提交后直发 @Async**（现成虚拟线程 AsyncConfig + MDC 透传，零新基建）+ **启动自愈**：应用就绪把残留 pending/processing 僵尸置 failed（只重置状态不自动重跑，与「花钱动作显式触发」一致，照 deployment.md 自愈定位） |
| 7 | 失败原因可见 | `kb_document` 加 `error_message text` 可空列，failed 时落人话原因，前端 tooltip 展示（否则排障只能翻日志） |
| 8 | 未配模型时上传 | **不拦上传**：提取分段照常成功，向量化一步 failed + error_message「系统未配置 embedding 模型…」；admin 配好后用户点重试续嵌。比拦上传体验好且不丢数据 |
| 9 | anthropic 协议 | 无 embedding API：工厂遇 anthropic 直接抛不支持；实际入口在保存设置的探测（必失败被拒），异常分支仅兜底 |
| 10 | embedding 计量 | K3 不发 TokenUsedEvent 不记看板（配额本就只在 conversation/workflow 入口查，无争议；计量要动事件结构，超范围） |
| 11 | K2 跟进项 | 顺路修 3 个：①上传 10001 前端静默 + 文件名≤200 预检；②uploadDocument 单独 axios 超时；③el-pagination `small`→`size="small"`。「50MB 文案硬编码+补测试」不修（与主线无关，留账） |
| 12 | 契约变化（站内可接受） | 上传响应 status 由 ready 变 **pending**、chunkCount 为 0（异步补）；**15001 空内容不再出现在上传响应**（判定随提取移入异步，表现为 failed+error_message，错误码本身保留不删）。前端同轮适配 |

---

## 1. 数据库（两个新迁移）

### V15（knowledge）：向量列 + HNSW + 失败原因

```sql
-- V15：kb_chunk 补 embedding 向量列 + HNSW 索引（兑现 K2 spec 决策 1）；kb_document 补失败原因列。
-- 向量列可空：分段落库时必然无向量（异步补嵌）；「embedding is null」同时是断点续嵌的选段依据。

alter table kb_chunk add column embedding vector(1024);
comment on column kb_chunk.embedding is '1024 维向量（database-standards §2.1：全库统一模型与维度，换模型=全量重嵌）；null=未嵌入';

-- HNSW 余弦索引，m/ef_construction 用默认（database-standards §2.1 原文建法）
create index kb_chunk_embedding_idx on kb_chunk using hnsw (embedding vector_cosine_ops);

alter table kb_document add column error_message text;
comment on column kb_document.error_message is 'status=failed 时的用户可读原因；其余状态为 null';
```

### V16（provider）：system_setting KV 表

照 §1.1 建表模板（四标准列强制），业务唯一性用部分唯一索引（§2 条 3）：

```sql
-- V16：系统设置 KV 表（data-model 预留席位兑现），当前归 provider 模块管理。
-- K3 仅一个键 embedding_model_id；后续系统设置复用本表，归属届时再议。

create table system_setting (
    id            bigint      generated always as identity primary key,
    setting_key   text        not null check (char_length(setting_key) <= 100),
    setting_value text,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table system_setting is '系统设置 KV（admin 可改）；K3 起用，键：embedding_model_id';
create unique index system_setting_key_uq on system_setting (setting_key) where deleted = false;
```

## 2. provider 模块

### 2.1 EmbeddingModel 构建与韧性

- **工厂**（并入现有 `ChatClientFactory` 加 `buildEmbeddingModel(provider, model)`，不另起类）：
  `openai` 协议 → Spring AI `OpenAiEmbeddingModel`，options **固定 `dimensions(1024)`** + 单次 RetryTemplate（关 Spring AI 自带重试，同 chat 先例）；`anthropic` 协议 → 抛不支持（决策 9）。
- **`ResilientEmbeddingModel` 装饰器**（`service/resilience/`）：照 llm-resilience.md 既定方案——**批量池**信号量（与交互池分开，批量向量化不吃对话并发额度）、超时、重试、熔断。**韧性参数不新增配置**：`model_provider` 表 V8 起已有 `batch_concurrency`（默认 3）/`response_timeout_sec`/`retry_max_attempts`/熔断参数，admin 界面可改，装饰器直接读 provider 行（llm-resilience 允许嵌入放宽到 5 次尝试，是否对 embedding 单独放宽由 plan 对照 ResilientChatModel 现状定，缺省复用行值）。
- 实例获取模式（是否 Caffeine 缓存）与现有 `getChatClient` 保持同款，plan 阶段照抄现状。

### 2.2 设置服务（EmbeddingSettingService，新建）

- `get()`：读 `system_setting['embedding_model_id']`，返回 `{modelId, modelName}`（未配置时 modelId=null——空设置是成功不是错误）。
- `save(modelId)`：校验链 **模型存在（10005）→ type=embedding 且模型/供应商 enabled（复用「可用」口径，违反 → 现有 `12002 MODEL_NOT_USABLE`）→ 事务外探测**：构建 EmbeddingModel 嵌一个短文本常量，返回维度 ≠1024 → 拒绝（提示实际维度）；网络失败 → 复用现有 `12003 PROVIDER_UNAVAILABLE`（503）。全过才 UPSERT 落库。
- 探测调用走批量池韧性装饰器（超时/熔断同享），不占 Web 事务。

### 2.3 ProviderFacade 增量（兑现 Javadoc 预留席位）

```java
/** 取系统设置的 embedding 模型（带韧性装饰）。未配置或模型已不可用抛 BizException。 */
EmbeddingModel getEmbeddingModel();
```

签名用 Spring AI 类型——provider Facade 既有例外（getChatClient 同款）。knowledge 仅新增对此方法的调用，模块依赖白名单 knowledge→provider 本就允许。

### 2.4 admin API（对照 api-standards 逐条核对过）

| 方法 | 路径 | 语义 | 失败码 |
|---|---|---|---|
| GET | `/api/v1/admin/provider/settings/embedding-model` | 回显 `{modelId, modelName}`（Long→string；未配置 modelId=null） | — |
| PUT | `/api/v1/admin/provider/settings/embedding-model` | 全量更新 `{modelId}`（@NotNull），走 2.2 校验链 | 10005、12005 维度不匹配（400）、12003 探测网络失败（503）、12002 模型不可用（400） |

不用 PATCH（规范禁）；设置是单例资源，`settings` 集合 + 键名作标识，PUT 全量语义成立。

### 2.5 错误码（现有枚举已到 12004，新码顺延）

- `EMBEDDING_DIMENSION_MISMATCH`（**12005**/400）：探测维度 ≠1024，message 含实际维度。
- `EMBEDDING_MODEL_NOT_CONFIGURED`（**12006**/409）：`getEmbeddingModel()` 时系统未配置 embedding 模型。主要在异步流水线内抛（表现为文档 failed），HTTP 面上见于全量重嵌入口的前置校验。
- 复用现有：`12001` anthropic 建 embedding 已拦、`12002 MODEL_NOT_USABLE`（设置指向的模型停用/非 embedding）、`12003 PROVIDER_UNAVAILABLE`（探测/嵌入网络失败、熔断）、`12004 PROVIDER_BUSY`（批量池满）。

## 3. knowledge 模块：异步流水线

### 3.1 上传改造（DocumentService.upload）

请求内只留：库存在/权限/扩展名（15004）/大小校验 → 存 `kb_document`（bytea、status=**pending**、chunk_count=0）→ 事务提交后派发 `DocumentProcessJob.process(docId)` → 返回 DocumentResponse。空内容判定随提取移入异步（决策 12）。派发用 `@TransactionalEventListener(AFTER_COMMIT) + @Async` 或事务同步回调，plan 定（与 usage 落库先例同款优先）。

### 3.2 DocumentProcessJob（新建，跑现有 AsyncConfig 虚拟线程）

1. **认领**：条件更新 `status: pending→processing`（`where status='pending'`，0 行即别人在跑，直接返回——防并发重复处理）。
2. **提取+分段**：读 bytea → UTF-8 解码 → 空白判定（失败原因=15001 语义文案）→ `TextChunker.split`（K2 复用）→ 一个事务落全部 kb_chunk + 更新 chunk_count。
3. **分批嵌入**：取 `ProviderFacade.getEmbeddingModel()`；把 `embedding is null` 的段按 `hify.knowledge.embedding-batch-size`（默认 10，千问 v4 单次上限）分批：**先事务外调 API 拿向量，再开小事务写库**（每批一个小事务；事务内零外部 IO 红线）。
4. **收尾**：全部成功 → `ready`（error_message 置 null）；任何一步异常 → `failed` + error_message（用户可读：未配模型/供应商超时/内容为空等）。
- 向量写库：KbChunkMapper 自定义 update，向量以 `'[0.1,0.2,…]'` 字面量 `::vector` 写入（MyBatis 无 pgvector 类型，字符串 cast 是最简通路；K4 真库测试兜底验真伪）。

### 3.3 重试 `POST /api/v1/knowledge/documents/{id}/retry`

- 权限：dataset 的 owner/Admin（10004）；文档不存在 10005。
- 状态闸门：条件更新 `failed→processing`（0 行 → **15002/409 文档当前状态不允许该操作**）。
- 续跑语义：分段不存在（提取阶段就挂过）→ 从 3.2 步骤 2 全流程重跑；分段已在 → 只跑步骤 3（断点续嵌，`embedding is null` 天然选段）。
- 与 3.2 的衔接：重试/重嵌路径**各自的状态闸门已把文档置为 processing**，任务入口跳过步骤 1 的 pending 认领（步骤 1 仅上传直发路径用）——Job 提供「从已认领状态进入」的入口，plan 阶段定方法拆分。

### 3.4 全量重嵌 `POST /api/v1/admin/knowledge/documents/reembed`（admin）

- 前置校验：embedding 模型已配置（否则 12xxx NOT_CONFIGURED/409）；进程内互斥闸（AtomicBoolean）防重复触发（已在跑 → **15003/409 重嵌入任务已在进行中**）。校验过即返 200，任务异步跑。
- 任务：一条 UPDATE 清空全部未删分段的 embedding → 按 id 序逐文档处理（仅 status ∈ {ready, failed} 的未删文档；pending/processing 的自有流水线在跑，跳过）：条件更新置 processing → 跑 3.2 步骤 3 → ready/failed（单文档失败记 error_message 继续下一个，不中断整体）。
- 顺序单线程跑（不并发多文档）：批量池信号量之上再自限，避免全库重嵌挤占交互池外的供应商 RPM。
- 已知边界（接受）：换模型瞬间恰有文档在 processing，其在途向量按旧模型写入且被本次清空错过——20-50 人内网概率极低，操作约定「重嵌前避开上传高峰」，不做代码级防护。

### 3.5 启动自愈（ApplicationReadyEvent 监听，knowledge 内）

`update kb_document set status='failed', error_message='服务重启，处理中断，请重试' where status in ('pending','processing') and deleted=false`。只重置状态，不自动重跑。

### 3.6 DTO 增量

`DocumentResponse` 追加 `errorMessage`（可空；新增字段不算破坏性变更）。分段预览接口投影**不含 embedding 列**（database-standards §5 大列禁入列表，K2 的「不 select 大列」口径延续）。

## 4. 前端

### 4.1 改动面

| 文件 | 动作 |
|---|---|
| `web/src/types/provider.ts`（或既有类型文件，plan 核对） | 追加 `EmbeddingSetting` 类型 |
| `web/src/api/admin/provider.ts` | 追加 `getEmbeddingSetting` / `saveEmbeddingSetting` |
| `web/src/api/admin/knowledge.ts` | **新文件**：`reembedAll`（admin knowledge 域首个接口） |
| `web/src/api/knowledge.ts` | 追加 `retryDocument`；`uploadDocument` 单独超时（决策 11②） |
| `web/src/types/knowledge.ts` | `Document` 追加 `errorMessage` |
| `web/src/router/index.ts` | 新增 admin 路由 `/admin/settings` → `SystemSettings.vue` |
| 侧边栏组件 | admin 区新增「系统设置」菜单项 |
| `web/src/views/admin/system/SystemSettings.vue` | **新页面**（admin 视图按域分目录的既有惯例） |
| `web/src/views/knowledge/DatasetDetail.vue` | 状态列四态 el-tag、轮询、重试按钮、error tooltip、上传文案 |
| `web/src/utils/request.ts`（拦截器） | 10001 静默修复（决策 11①） |
| 对应 `__tests__/` | vitest 测试（TDD） |

### 4.2 SystemSettings 页面（admin）

- embedding 模型 `el-select`：只列「可用」的 embedding 模型（复用现有模型列表接口按 type=embedding 过滤，plan 核对现有接口形态）+ 当前值回显。
- 「保存」：调 PUT，探测期间按钮 loading；失败（维度不匹配/探测超时）由拦截器 toast 规范 message。
- 「全量重嵌入」按钮：`ElMessageBox` 确认弹窗**明示会调外部 API 产生费用**；成功 toast「已开始，去知识库详情页查看文档状态」；15003 提示已在进行中。未配置模型时按钮禁用。

### 4.3 DatasetDetail 增强

- 状态列四态 el-tag（pending 灰 / processing 蓝 / ready 绿 / failed 红）；failed 行 el-tooltip 展示 errorMessage，操作列出现「重试」（owner/Admin 门控，同删除口径）。
- **轮询**：列表存在 pending/processing 文档时 `setInterval` 定时重拉当前页（间隔前端常量 3000ms），全部到终态或离开页面即停（onUnmounted 清理）。
- 上传成功提示改「已上传，正在处理」，成功后立即刷新并触发轮询。
- 上传前置预检：文件名 ≤200 字符（决策 11①附带）。
- el-pagination `small` → `size="small"`（决策 11③）。

### 4.4 拦截器 10001 修复（决策 11①）

现状：拦截器把 10001 一律按「表单字段错误数组」约定处理，上传等非表单场景吞掉提示。修复：10001 且调用方未按表单约定消费时兜底 toast message（具体机制 plan 阶段读现有拦截器代码后定，保持既有表单场景行为不变）。

## 5. 配置增量（application.yml）

```yaml
hify:
  knowledge:
    embedding-batch-size: ${HIFY_KNOWLEDGE_EMBEDDING_BATCH_SIZE:10}   # 千问 v4 单次上限 10
```

仅此一个新键。批量池信号量/超时/重试/熔断**不进 yml**——V8 起就是 `model_provider` 表字段（`batch_concurrency` 默认 3 等），admin 界面可改，装饰器读行值（见 §2.1），「外部调用必须有超时」由此满足。探测文本用代码常量（非配置）。前端轮询间隔 3000ms 也是代码常量。

## 6. 测试与验收（TDD 先红后绿）

**后端**（mock 约定维持，Testcontainers 留 K4；EmbeddingModel mock 返回 1024 维假向量）：
- `EmbeddingSettingServiceTest`：get 未配置返回空、save 校验链各分支（不存在 10005/非 embedding/停用/维度 768 拒绝且 message 带维度/探测网络失败 12002）、UPSERT 幂等。
- `ChatClientFactoryTest` 增量：anthropic 请求 embedding 抛不支持；openai 构建 options 含 dimensions=1024。
- `DocumentProcessJobTest`：pending 认领成功/非 pending 直接返回、提取失败置 failed+error_message、未配模型置 failed、分批边界（10/11/0 段）、批中途失败已写批保留+文档 failed、全成功 ready 且 error_message 清空。
- `DocumentRetryTest`：非 failed 15002、分段在只嵌空向量段、分段不在全重跑。
- `ReembedTest`：未配模型 409、互斥闸 15003、清空后逐文档、单文档失败不中断、跳过非终态文档。
- 启动自愈 listener test：僵尸置 failed。
- Controller 层：新路由、member 调 admin 403、Result 信封与 Long→string。
- ModularityTests / ArchUnit 回归（knowledge→provider 经 Facade，白名单已有）。

**前端**（vitest）：api 层新函数 spec（路径/method/超时参数）；SystemSettings——加载回显、保存 loading、重嵌确认弹窗与禁用态；DatasetDetail——四态 tag、轮询启停（fake timers）、重试按钮门控与调用、error tooltip；拦截器 10001 非表单场景 toast、表单场景回归不破。

**手动验收**：未配模型传文档（failed+原因可见）→ admin 配 DeepSeek 之类非 embedding 模型（保存被拒）→ 配千问 v4（保存成功）→ 点全量重嵌（存量文档 processing→ready，重复点报已进行中）→ 新传 txt（pending→processing→ready 轮询可见）→ 传空文件（failed+原因）→ 点重试 → member 账号看不到系统设置菜单与重试外权限 → psql 抽查 kb_chunk.embedding 非空且维度 1024。

## 7. 不破契约（约束清单）

- K1/K2 既有端点签名、错误码不动；契约变化仅决策 12 两条（status=pending 返回、15001 移出上传响应），前端同轮适配。
- 只新增 V15/V16，不改旧迁移；HNSW 建法照 database-standards §2.1 原文；`@Transactional` 内零外部 IO（嵌入先调后写）。
- 不引新依赖（Spring AI 的 OpenAiEmbeddingModel 在既有 starter 内）；不改 SecurityConfig 语义（admin 路由既有前缀规则覆盖）。
- 错误码新增：15002、15003、12005、12006 共四枚；发布后只增不改。
- 检索、Rerank、用量计量一律不碰（K4/后续轮）。
