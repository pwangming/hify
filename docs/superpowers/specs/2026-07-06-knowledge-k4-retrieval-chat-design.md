# knowledge K4 检索接入对话 — 设计文档

> 日期：2026-07-06
> 模块：`knowledge` + `app` + `conversation`（前后端一体，provider 零改动）
> 前置：K3（向量化）已合并 main 并验收（71d4226..852d37e）。knowledge 拆轮全景：K1 ✅ → K2 ✅ → K3 ✅ → **K4（本轮，合并原 K4 检索+原 K5 接对话）** → 引用来源展示（后续小轮）。
> 范围口径：**向量检索能力（手写 SQL + Testcontainers 基建）→ 应用绑定知识库（app_dataset_rel）→ 对话注入检索结果（带降级）→ 知识库详情页命中测试工具**。
> 明确**不做**：聊天引用来源展示（下轮小轮，需动 message 表+SSE 协议）、检索/embedding Token 计量、Rerank/混合检索（二期）、应用级检索参数（全局 yml 够用）、query 改写/多轮问题合成、删知识库时提醒"有应用绑定"（留账）、K3 留账的 reembed 竞态与 50MB 文案（继续留账）。

---

## 0. 决策摘要（拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 轮次范围 | **合并成一轮**：检索能力 + 绑库 + 对话注入 + 命中测试。检索不接对话无法真实验收，合并更闭环 |
| 2 | 命中测试工具 | **做**：`POST /datasets/{id}/retrieve`（api-standards 原文预写的范式）+ DatasetDetail 弹窗。排障时区分「检索没命中」和「LLM 没用好上下文」 |
| 3 | 引用来源展示 | **本轮不做**：要动 message 表结构（jsonb）+ SSE 事件类型 + 前端渲染，独立工作量留下轮；命中测试页已能看检索结果，排障不盲 |
| 4 | 检索参数 | **全局固定、yml 可改**：topK 默认 4、相似度阈值默认 0.3（低于丢弃）。不进 app.config，不做应用级表单 |
| 5 | 降级策略 | **检索失败降级继续答**：对话链路上检索任何异常（未配模型 12006/供应商故障 12003/池满 12004）只记 warn、不注入上下文、照常回答。命中测试端点**不降级**（排障工具要暴露真实错误） |
| 6 | 技术路线 | **A：手写 SQL + 手动拼提示词**。Spring AI PgVectorStore 要求自有表结构（UUID 主键/metadata jsonb），与 kb_chunk 不兼容（方案 C 需推倒 K3 写入链路，排除）；自实现 VectorStore 接口套 Advisor（方案 B）一半方法不可用、降级与多库过滤被框架管控、最终提示词不可见，单一消费方下抽象无收益。**同步修正 database-standards §2.1 过时表述** |
| 7 | 绑定存储 | **app_dataset_rel 关系表**（data-model 预留席位兑现），不塞 app.config jsonb：反向查询（哪些应用绑了此库）关系表一个索引即达。更新语义=全量替换（软删旧行+插新行） |
| 8 | query embedding 池 | **复用批量池**（llm-resilience 定义 embedding 归批量池，K3 现成装饰链）。20-50 人检索 QPS 撑不满 batch_concurrency=3，不为交互检索新开池，真挤了再说 |
| 9 | 检索时机与范围 | 每轮只 embed **当前用户消息**；检索结果**不落库、不进历史窗口**，只影响本轮提示词；空 datasetIds 短路（不白跑 embedding API） |
| 10 | 阈值过滤位置 | SQL 只管 `order by <=> limit topK`，**阈值在 Java 层过滤**——写进 where 会干扰 HNSW 索引走法，Java 一行 filter 更可控 |
| 11 | Testcontainers | **本轮引入**（既定拍板：第一次手写向量 SQL 时一次性立基建）。单例容器 + 全量 Flyway 迁移，`support/` 基类供后续所有模块复用；顺带兑现 K3 留账 DocumentProcessStore 直测 |
| 12 | 错误码 | **零新增**：绑定校验/库不存在复用 10005，检索故障复用 12003/12004/12006，参数违规 10001 |

## 1. 数据库：V18（app 模块）

```sql
-- V18：应用↔知识库 多对多关系表（data-model.md 预留席位兑现）。
-- dataset_id 跨模块弱引用不建 FK（data-model 第 3 条）；app_id 模块内 FK 可建。

create table app_dataset_rel (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null references app(id),
    dataset_id  bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app_dataset_rel is '应用↔知识库多对多（app 模块）；dataset_id 跨模块弱引用';
create unique index app_dataset_rel_uq on app_dataset_rel (app_id, dataset_id) where deleted = false;
create index app_dataset_rel_dataset_idx on app_dataset_rel (dataset_id);
```

kb_chunk 侧零 DDL：`dataset_id` 冗余列+索引、`embedding vector(1024)`+HNSW 皆 V14/V15 现成。

## 2. knowledge 模块（本轮主角）

### 2.1 检索 SQL（KbChunkMapper 注解 SQL）

照 database-standards §2.1「先过滤后排序」模板：

```sql
select c.id, c.document_id, d.name as document_name, c.content,
       1 - (c.embedding <=> #{qvec}::vector) as score
from kb_chunk c
join kb_document d on d.id = c.document_id
where c.dataset_id = any(#{datasetIds}) and c.deleted = false
      and c.embedding is not null
order by c.embedding <=> #{qvec}::vector
limit #{topK}
```

- `<=>` 为余弦距离，`1 - 距离` = 相似度分数（越接近 1 越相关）。
- `embedding is null`（未嵌完/嵌失败的段）天然排除；join kb_document 取文档名供命中测试展示。
- 向量入参用 K3 同款 `'[0.1,…]'` 字面量 `::vector` cast。
- 上线前过 EXPLAIN ANALYZE（database-standards §2 条 6），Testcontainers 测试兜行为正确性。

### 2.2 KnowledgeFacade（新建，api 顶层包）

knowledge 首个 Facade。跨模块 DTO `RetrievedChunk(chunkId, documentId, documentName, content, score)` 放 **api 顶层包**（Modulith 1.4.1 不暴露 api/dto 子包的既有教训）：

```java
/** 向量检索（topK/阈值用全局配置）：返回按分数降序、已过阈值的命中段。
 *  未配 embedding 模型/供应商故障抛 BizException（12006/12003/12004），降级由调用方决定。 */
List<RetrievedChunk> retrieve(List<Long> datasetIds, String query);

/** 绑定校验：datasetIds 全部存在且未删则通过，否则抛 10005（app 创建/编辑时调）。 */
void validateDatasetIds(List<Long> datasetIds);
```

Facade 刻意**只暴露两参版本**：topK/阈值的全局配置在 knowledge 模块内部消化，conversation 不需要（也不该）看见配置类——否则它得 import knowledge 内部包，越模块边界。带显式 topK/阈值的四参入口是 knowledge 模块内部方法（`RetrievalService`），仅供同模块的命中测试端点覆写参数用。

内部 `RetrievalService`：空 datasetIds → 返回空列表（短路，决策 9）→ `providerFacade.getEmbeddingModel().embed(query)`（批量池，决策 8）→ 拼向量字面量查 Mapper → Java 层按阈值过滤（决策 10）。无事务（外部 IO + 只读查询）。

### 2.3 命中测试端点

| 方法 | 路径 | 语义 | 失败码 |
|---|---|---|---|
| POST | `/api/v1/knowledge/datasets/{id}/retrieve` | 请求 `{query（必填，≤1000 字）, topK?, scoreThreshold?}`，可选参缺省用全局配置、给了本次生效（调参用）；响应 `[{chunkId, documentId, documentName, content, score}]`（Long→string） | 10005 库不存在；12006/12003/12004 原样抛（**不降级**，决策 5） |

权限：团队共享读操作，登录即可（同知识库详情口径）。topK 上限 20、阈值 [0,1]（10001 校验）。

## 3. app 模块

- `CreateAppRequest`/`UpdateAppRequest` 加 `datasetIds`（可空=不绑；`@Size(max=10)`）；保存前调 `knowledgeFacade.validateDatasetIds`（白名单 app→knowledge「仅校验引用」兑现）。仅 chat 型生效，workflow 型忽略。
- 全量替换语义：软删该 app 全部关系行 + 插入新勾选（同事务）。
- `AppResponse` 加 `datasetIds`（回显；Long→string）。
- `AppRuntimeView` 加 `List<Long> datasetIds`（该 record「config 增长再加字段」预告兑现；无绑定=空列表非 null）；`AppFacadeImpl.findRunnableChatApp` 顺带查关系表。

## 4. conversation 模块

`send`/`sendStream` 在 openTurn（事务A）之后、LLM 调用之前插入共用私有方法：

```java
String effectivePrompt = augmentWithKnowledge(app, content);
// datasetIds 空 → 原样返回 app.systemPrompt()；
// 否则 try { knowledgeFacade.retrieve(ids, content) 命中则拼参考资料 }   // topK/阈值在 knowledge 内部取全局配置
//      catch (Exception e) { log.warn 带 appId/原因，降级返回原 systemPrompt }
// 命中为空（阈值全滤掉）同样原样返回——不拼空壳"参考资料"段。
```

拼法（常量在 conversation 内，中文）：

```
{原 systemPrompt（可空，空则省略此段）}

请优先依据下列参考资料回答用户问题；资料未覆盖时可依据自身知识回答。
参考资料：
[1] {content1}
[2] {content2}
```

- 位置在两事务间隙，与 LLM 调用同区——不违反「事务内禁外部 IO」红线。
- 检索耗时占同步端点总预算（embedding 数百毫秒级，可接受）；SSE 端点在流建立前发生，无协议影响。
- 检索结果不落库不进窗口（决策 9），message 表零 DDL。

## 5. 配置增量（application.yml）

```yaml
hify:
  knowledge:
    retrieval:
      top-k: ${HIFY_KNOWLEDGE_RETRIEVAL_TOP_K:4}
      score-threshold: ${HIFY_KNOWLEDGE_RETRIEVAL_SCORE_THRESHOLD:0.3}
```

仅此两键（决策 4）。配置类放 knowledge 模块内部，唯一消费方是 `RetrievalService`（Facade 两参入口与命中测试端点缺省值共用）；conversation 不接触配置（见 §2.2）。

## 6. 前端

### 6.1 改动面

| 文件 | 动作 |
|---|---|
| `web/src/types/`（app/knowledge 既有类型文件，plan 核对实名） | App 相关类型加 `datasetIds: string[]`；加 `RetrievedChunk`、命中测试请求类型 |
| `web/src/api/knowledge.ts` | 加 `retrieveTest(datasetId, {query, topK?, scoreThreshold?})` |
| app 的 api 层文件（plan 核对实名） | 创建/更新载荷带 `datasetIds` |
| `web/src/views/app/AppList.vue` | 编辑弹窗加「关联知识库」`el-select multiple` |
| `web/src/views/knowledge/DatasetDetail.vue` | 顶部「命中测试」按钮 + `el-dialog` |
| 对应 `__tests__/` | vitest（TDD） |

### 6.2 应用弹窗多选

- 选项来自现有知识库列表接口；帮助文案「绑定后，该应用回答会参考所选知识库内容」。
- 回显沿用「失效模型作禁用项」既有手法（AppList 模型下拉同款）：已绑但已删的库以禁用项显示，避免裸露 id。

### 6.3 命中测试弹窗（DatasetDetail）

- 输入框（必填）+「测试」按钮（loading）；topK/阈值小输入框缺省留空走全局。
- 结果区每段一张卡片：分数 `el-tag`、文档名、内容（超长折叠）。空结果提示「无命中分段（可尝试降低阈值）」。
- 错误不吞：12006/12003 由拦截器 toast 原样提示（排障工具价值所在）。
- 用弹窗不用新路由：是工具不是页面。

## 7. 测试（TDD 先红后绿）

### 7.1 Testcontainers 基建（决策 11，本轮立起）

- 依赖 `org.testcontainers:postgresql` + `junit-jupiter`（test scope，Spring Boot BOM 管版本）。
- 镜像 **`pgvector/pgvector:pg16`**（官方 postgres 无 vector 扩展；与生产 compose 镜像对齐，plan 核对）。
- **单例容器 + `@DynamicPropertySource`** 基类（测试源码 `support/PgIntegrationTest`）：整个测试运行一个容器，Flyway 全量跑 V1~V18（首次真验全部迁移脚本，白赚），各测试类共享容器、独立数据隔离。
- 连库测试用例：
  - 检索 SQL：3 个已知向量分段 → 排序/分数/跨库 `any` 过滤/软删与 null 向量排除/topK 截断。
  - K3 留账兑现：DocumentProcessStore `vectorLiteral`/`saveChunks` 直测（写真库读回验维度）。
  - app_dataset_rel 唯一索引与全量替换语义。

### 7.2 常规 mock/切片测试

- RetrievalService：空 datasetIds 短路（不调 embedding）、阈值过滤边界、embedding 异常原样透传。
- conversation：绑库命中→提示词含参考资料段；未绑→提示词原样；retrieve 抛异常→降级正常回答+warn；命中为空→不拼空壳段。
- app：datasetIds 校验链（不存在 10005、>10 个 10001）、全量替换、AppRuntimeView 带 datasetIds、workflow 型忽略。
- Controller 切片：命中测试端点信封/Long→string/参数校验/未登录 401。
- ModularityTests/ArchUnit 回归（白名单本就允许，应零修改通过）。

### 7.3 前端 vitest

api 层新函数（路径/method/载荷）；弹窗多选回显（含失效库禁用项）与提交载荷；命中测试弹窗 loading/结果渲染/空态/错误路径。

### 7.4 手动验收

建知识库传文档（ready）→ 应用绑该库 → 聊天问文档内问题（回答引用资料内容）→ 问无关问题（正常回答不硬凑）→ 命中测试输入同款问题（看到命中段与分数，调阈值观察增减）→ 解绑后再问（回答不再引用）→ 停用 embedding 模型后聊天（降级照常答，server 日志有 warn；命中测试页报错可见）→ member 账号全流程同权限口径。

## 8. 文档更新（拍板入档）

- database-standards §2.1：「通过 Spring AI `VectorStore` 的 filter 表达，不手写」→ 改为手写注解 SQL 口径（决策 6 理由入档）。
- data-model.md：`app_dataset_rel` 从规划转已建。

## 9. 不破契约（约束清单）

- 既有端点签名/错误码零改动；本轮新增仅：命中测试端点、App 请求/响应可选字段 `datasetIds`（新增字段向后兼容）。
- 只新增 V18，不改旧迁移；`@Transactional` 内零外部 IO（检索在事务间隙）。
- 不引运行时新依赖（Testcontainers 为 test scope）；SecurityConfig 零改动（路由前缀既有规则覆盖）。
- 错误码零新增（决策 12），发布后只增不改原则无涉。
- 引用来源、计量、Rerank、应用级参数一律不碰（见「明确不做」）。
