# knowledge K2 文档上传与分段 — 设计文档

> 日期：2026-07-02
> 模块：`knowledge`（前后端一体）
> 前置：K1（dataset CRUD）已合并 main（221889f）。knowledge 拆轮全景见 K1 spec 决策摘要：K1 ✅ → **K2（本轮）** → K3 向量化 → K4 检索+命中测试（引 Testcontainers）→ E2E 基建轮 → K5 接进对话流。
> 范围口径：**上传 txt/md → 提取文本 → 固定长度分段 → 落库 + 前端知识库详情页（文档列表/上传/分段预览）**。
> 明确**不做**：向量化与 embedding 列（K3）、检索（K4）、pdf/docx 与 Apache Tika（独立小轮）、分段参数前端可调（全局 yml）、异步处理管道（K3 才需要）、重新分段功能（参数已记录在文档行，留后）。

---

## 0. 决策摘要（拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | embedding 维度 | **K2 的 kb_chunk 不建向量列**；K3 用新迁移补 `vector(1024)` 列 + HNSW 索引。已预拍板：列定 1024 维，「用哪个模型」做系统级设置（须支持 1024 输出维，如千问 v4 / OpenAI v3 的 dimensions 参数）；换模型 = admin 显式全量重嵌入。**不做多模型/多维度共存**（不同模型向量不在同一语义空间，混用无意义；database-standards §2.1 原有拍板维持） |
| 2 | DeepSeek | 无 embedding API，只能当对话模型（OpenAI 兼容协议接入）；向量化用千问/OpenAI 等 |
| 3 | 文档格式 | K2 只支持 **txt/md**（零新依赖）；pdf/docx 需引 Tika，留独立小轮（选裁剪组合+验证中文提取质量） |
| 4 | 分段参数 | **全局 yml 默认**（`hify.knowledge.chunk-size: 500` / `chunk-overlap: 50`），前端不暴露调参；实际参数记录在 kb_document 行（为将来重分段留底） |
| 5 | 处理时机 | **同步**：上传请求内完成提取+分段+落库（纯内存操作+一个写事务，事务内零外部 IO）；异步留给 K3 的向量化 |
| 6 | 状态机 | kb_document.status 四态 `pending/processing/ready/failed` 一次定全；K2 同步流程一步到 ready；K3 接手后升级语义（届时对存量 ready 文档一次性补嵌，K3 的事） |
| 7 | 删除语义（K1 决策#6 兑现） | `DELETE /datasets/{id}` 升级为**级联软删**（库→文档→分段，照 ⑦ 会话级联软删消息先例）；`DELETE /documents/{id}` 级联软删其分段。分段是派生数据但仍走软删（database-standards「四标准列每表强制」+ 与全库删除机制一致） |
| 8 | 错误码 | 首次启用 15xxx 段：**15004/400 文档格式不支持**（api-standards §5.3 已预定义此号，必须复用）、**15001/400 文档内容为空或解析失败**。其余复用通用段 |
| 9 | 文档重名 | 同库内**不约束**文档重名（文件名非资源标识） |
| 10 | chunk 冗余 dataset_id | kb_chunk 加 dataset_id 冗余列并建索引——K4 按知识库检索时免 join，属 database-standards 允许的读优化 |

---

## 1. 数据库：V14 迁移（两张表）

照 V7/V13 约定（identity 主键、text+check、四标准列、timestamptz）。**模块内 FK 允许建**（data-model §3：kb_chunk→kb_document、kb_document→dataset 同模块）：

```sql
-- V14：文档与分段表（knowledge 模块）。原始文件存 bytea（架构拍板：文件不是独立实体，随库备份）。
-- 模块内 FK 允许建（data-model 第 3 条）；跨模块无引用。

create table kb_document (
    id            bigint      generated always as identity primary key,
    dataset_id    bigint      not null references dataset(id),
    name          text        not null check (char_length(name) <= 200),
    file_type     text        not null check (file_type in ('txt', 'md')),
    file_size     bigint      not null,
    content       bytea       not null,
    status        text        not null default 'pending'
                  check (status in ('pending', 'processing', 'ready', 'failed')),
                  -- 默认 pending（安全侧：漏赋值时宁可显示待处理）；K2 同步流程插入时显式写 ready
    chunk_count   int         not null default 0,
    chunk_size    int         not null,
    chunk_overlap int         not null,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table kb_document is '知识库文档（knowledge 模块）：元数据+原始文件 bytea；status 四态为 K3 异步向量化预留';
create index kb_document_dataset_idx on kb_document (dataset_id);

create table kb_chunk (
    id          bigint      generated always as identity primary key,
    document_id bigint      not null references kb_document(id),
    dataset_id  bigint      not null,
    position    int         not null,
    content     text        not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table kb_chunk is '文档分段（knowledge 模块）：dataset_id 为检索用冗余列；embedding 向量列 K3 迁移补加';
create index kb_chunk_document_idx on kb_chunk (document_id);
create index kb_chunk_dataset_idx on kb_chunk (dataset_id);
```

注意：FK 用默认 no action（不用 on delete cascade）——删除走软删，物理级联永不触发，留 FK 只为引用完整性兜底。

## 2. 后端

### 2.1 端点一览（DocumentController，新建）

| 方法 | 路径 | 语义 | 权限 | 失败码 |
|---|---|---|---|---|
| POST | `/api/v1/knowledge/datasets/{id}/documents` | multipart 上传（字段名 `file`），同步提取+分段，返回文档完整资源 | dataset 的 owner/Admin | 10005（库不存在）、10004、15004（格式）、15001（空内容）、10001（超 50MB/缺文件） |
| GET | `/api/v1/knowledge/datasets/{id}/documents?page=&size=` | 文档分页列表 | 全员 | 10005、10001 |
| DELETE | `/api/v1/knowledge/documents/{id}` | 级联软删文档+分段；幂等 | dataset 的 owner/Admin | 10004 |
| GET | `/api/v1/knowledge/documents/{id}/chunks?page=&size=` | 分段分页列表（position 正序） | 全员 | 10005、10001 |

路由核对（api-standards §2.1）：嵌套一级 `/datasets/{id}/documents` 合规；documents 有自己的 id 升顶级（`/documents/{id}/chunks` 正是规范原文示例）。分页守卫同 K1（page≥1、size 1..100、page×size≤10000）。

- 上传大小限制：application.yml 新增 `spring.servlet.multipart.max-file-size: 50MB` / `max-request-size: 50MB`（与 nginx client_max_body_size 对齐，api-standards §6）；超限由全局异常处理器转 10001。
- 格式判定按**文件扩展名**（.txt/.md，大小写不敏感），不猜 MIME；其余扩展名 → 15004。
- 列表查询**不 select content 列**（bytea 几十 MB 级，database-standards §5 禁大列进列表）；`DocumentResponse` 不含文件内容。
- `DELETE /datasets/{id}`（K1 既有，DatasetService 内改）：级联软删该库文档+分段，接口签名与幂等语义不变。

### 2.2 错误码（knowledge/constant/KnowledgeError 枚举，新建）

```java
DOCUMENT_FORMAT_UNSUPPORTED(15004, HttpStatus.BAD_REQUEST, "文档格式不支持，当前仅支持 txt/md"),
DOCUMENT_CONTENT_EMPTY(15001, HttpStatus.BAD_REQUEST, "文档内容为空或无法解析");
```

15004 的号是 api-standards §5.3 预定义的，不可改。发布后只增不改。

### 2.3 DTO（knowledge/dto，模块内自用）

```java
public record DocumentResponse(
        Long id, Long datasetId, String name, String fileType, Long fileSize,
        String status, Integer chunkCount,
        OffsetDateTime createTime, OffsetDateTime updateTime) {}

public record ChunkResponse(Long id, Integer position, String content) {}
```

上传无 JSON 请求体（multipart 的 `file` 字段），无 Create DTO。

### 2.4 Service 与分段器

- **`TextChunker`（knowledge/service，纯函数类，无 Spring 依赖）**：
  `List<String> split(String text, int chunkSize, int overlap)` —— 按字符窗口滑动切分（步长 = chunkSize − overlap），每段 trim，空段跳过；入参守卫 chunkSize>0、0≤overlap<chunkSize。TDD 最佳标的。
- **`DocumentService`（新建）**：
  - `upload(datasetId, MultipartFile, current)`：库存在性（10005）→ 权限（复用 dataset 的 owner/Admin 判定）→ 扩展名校验（15004）→ UTF-8 读文本（md 按原文，不渲染）→ 空白判定（15001）→ `TextChunker.split`（参数取 yml）→ **事务**：insert kb_document（status=ready、chunk_count、实际参数）+ `saveBatch` 分段（每批 ≤1000）→ 返回 DocumentResponse。提取与分段在事务外的内存中完成，事务内零外部 IO。
  - `pageDocuments(datasetId, page, size)`：分页守卫 → 列表投影（无 content 列）。
  - `deleteDocument(id, current)`：查无直接返回（幂等）→ 按所属 dataset 判权限 → 软删文档 + 软删其分段。
  - `pageChunks(documentId, page, size)`：文档存在性（10005）→ position 正序分页。
- **`DatasetService.delete` 增强**：软删库后级联软删其文档与分段（调 DocumentService 或同模块直接操作 mapper，实现时从简）。
- 配置：`hify.knowledge.chunk-size: ${HIFY_KNOWLEDGE_CHUNK_SIZE:500}`、`chunk-overlap: ${HIFY_KNOWLEDGE_CHUNK_OVERLAP:50}`。

### 2.5 JDBC 批量写

检查连接串是否已有 `reWriteBatchedInserts=true`（database-standards §2.1 要求），没有则本轮补上（application.yml 数据源 url）。

## 3. 前端

### 3.1 改动面

| 文件 | 动作 |
|---|---|
| `web/src/types/knowledge.ts` | 追加 `Document`、`Chunk` 类型 |
| `web/src/api/knowledge.ts` | 追加 `uploadDocument`（FormData）/`listDocuments`/`deleteDocument`/`listChunks` |
| `web/src/router/index.ts` | 新增 `/knowledge/:id` → `DatasetDetail.vue` |
| `web/src/views/knowledge/KnowledgeList.vue` | 名称列变链接进详情页 |
| `web/src/views/knowledge/DatasetDetail.vue` | **新页面** |
| 对应 `__tests__/` | vitest 测试 |

### 3.2 DatasetDetail 页面

- 顶部：库名 + 描述（`getDataset` 取）+ 返回列表链接 + 「上传文档」按钮（owner/Admin 门控，同 K1 canModify 口径）。
- 上传：`el-upload`（手动触发、`accept=".txt,.md"`、前端校验扩展名与 50MB，双保险）；上传中按钮 loading；成功刷新列表，失败由 request 拦截器统一 toast（15004/15001 的 message 用户可读）。
- 文档 el-table：名称 / 格式 / 大小（可读格式化）/ 分段数 / 状态（el-tag：ready 绿）/ 上传时间 / 操作（查看分段、删除——删除走 ElMessageBox 确认 + owner/Admin 门控）。
- 「查看分段」：`el-drawer` 抽屉，分页展示 `position + content`（content 长文本折行展示）。
- 分页均对接 PageResult，照 K1 写法。

## 4. 测试与验收（TDD 先红后绿）

**后端**（mock 约定，不连库；Testcontainers 仍留 K4）：
- `TextChunkerTest`（纯函数）：正常切分长度与段数、重叠正确性（相邻段共享 overlap 字符）、末段短于 chunkSize、文本短于一段、全空白→空列表、trim 生效、参数守卫。
- `DocumentServiceTest`（Mockito）：上传成功字段落库（status=ready/chunk_count/参数记录）、库不存在 10005、非 owner 10004、扩展名不支持 15004、空内容 15001、删除幂等/级联软删调用、分段分页。
- `DatasetServiceTest` 增量：删库级联软删文档+分段。
- `DocumentControllerTest`（MockMvc）：multipart 上传路由、未登录 401、分页参数、Result 信封与 Long→string。
- ModularityTests/ArchUnit 回归（knowledge 仍只依赖 common/infra）。

**前端**（vitest）：api 层四函数 spec（FormData/路径/params）；DatasetDetail——加载渲染库信息与文档列表、上传成功触发刷新、非 owner 上传/删除按钮隐藏、删除确认调 API、抽屉分页拉取分段、空态。

**手动验收**：进详情页 → 传 .txt（看分段数/状态）→ 传 .md → 传 .pdf（报「格式不支持」）→ 传空文件（报 15001）→ 查看分段（预览+翻页）→ member 账号看门控 → 删文档 → 删整库（K1 列表确认没了）。Postman 集合可在 `docs/verify/knowledge-k1.postman_collection.json` 基础上续加。

## 5. 不破契约（约束清单）

- K1 五端点与行为不变（`DELETE /datasets/{id}` 仅内部增强级联，签名/幂等/状态码不变）；已发布错误码不动，15xxx 首发两个号。
- 只新增 V14，不改旧迁移；`@Transactional` 内零外部 IO；列表不 select bytea/大列。
- 不引新依赖（Tika 明确排除）；不改 SecurityConfig。
- kb_chunk 无 embedding 列（K3 迁移补），K3 前不建任何向量相关索引。
