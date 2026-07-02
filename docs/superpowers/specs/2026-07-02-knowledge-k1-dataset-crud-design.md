# knowledge K1 知识库管理（dataset CRUD）— 设计文档

> 日期：2026-07-02
> 模块：`knowledge`（前后端一体；首个落地功能，此前仅 package-info 空壳）
> 前置：conversation ③～⑦ 已合并 main；provider 已支持 embedding 类型模型（CRUD + Anthropic 协议守卫），但 `getEmbeddingModel` 未实现——**K1 不需要它**。
> 范围口径：knowledge 整体拆 5 子轮（K1 库管理 → K2 文档上传/分段 → K3 向量化 → K4 检索+命中测试【引 Testcontainers】→ 【E2E 基建轮】→ K5 接进对话流）。本轮只做 **K1：`dataset` 表 + CRUD 接口 + 前端知识库列表页**。
> 明确**不做**：文档上传、分段、向量化、检索、应用绑定知识库（`app_dataset_rel` 属 app 模块，K5 做）、分段策略字段（K2 用新迁移补列）、Testcontainers、E2E。

---

## 0. 决策摘要（拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | knowledge 拆轮 | 纵向切片 5 子轮（见上），每轮前后端闭环可验收；E2E 基建轮插在 K4 与 K5 之间（动对话流前先锁住聊天旅程） |
| 2 | 分段策略 | 全程只做「固定长度 + 重叠」一种（K2 实现），不做 Dify 的父子分段等高级模式 |
| 3 | 知识库重名 | 团队内唯一：部分唯一索引 `where deleted = false`（软删后可同名重建），重名 → 409/10006。与 app 表做法一致 |
| 4 | 错误码 | K1 **零新增 15xxx**，全部复用通用段（10001/10004/10005/10006）。模块段留给 K2+ 的特有语义（如 15004 文档格式不支持） |
| 5 | owner 展示 | `DatasetResponse` 只带 `ownerId`，前端对比当前用户显示「我创建/其他成员」——照 app 模块现状，**不做 ownerName 联查** |
| 6 | 删除语义 | 软删、幂等（删不存在的也 200）。K1 无文档，无级联问题；有文档时的级联删除留 K2 定 |
| 7 | 前端形态 | el-table 表格页（照 `AppList.vue`），不用 Dify 卡片式——与 app 列表同一套交互语言；卡片留到有文档数等元信息后再议 |
| 8 | 应用参数配置 | temperature / 记忆轮数按应用配置记为候补小轮，排 RAG 之后；配额维持 application.yml 全局配置不做前端化 |

---

## 1. 数据库：V13 迁移

`V13__create_dataset.sql`（照 V7 app 表约定：identity 主键、text+check 限长、软删、timestamptz）：

```sql
-- V13：知识库表（knowledge 模块）。团队共享制带 owner_id；文档/分段表留 K2。
-- 跨模块 owner_id 只存 id、不建外键（data-model.md 第 3 条）。

create table dataset (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    owner_id    bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table dataset is '知识库（knowledge 模块）：团队共享制带 owner_id；文档与分段见 kb_document/kb_chunk（K2）';

-- 知识库名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index dataset_name_uq on dataset (name) where deleted = false;
```

不加分段策略列（chunk 大小/重叠）——K2 实现分段时按需用新迁移补，避免现在猜字段（旧脚本合并后不可改，宁缺毋滥）。

## 2. 后端（knowledge 模块内）

### 2.1 端点一览

`DatasetController`，类级 `@RequestMapping("/api/v1/knowledge/datasets")`（成员族，JWT，`anyRequest().authenticated()` 已覆盖，无需改 SecurityConfig）：

| 方法 | 映射 | 语义 | 权限 | 失败码 |
|---|---|---|---|---|
| GET | `` （类级路径） | 分页列表 `?keyword=&page=1&size=20`，PageResult | 全员 | 10001（分页参数非法） |
| GET | `/{id}` | 详情 | 全员 | 10005/404 |
| POST | `` | 创建，返回完整资源 | 全员 | 10001、10006/409（重名） |
| PUT | `/{id}` | 全量更新（name + description，未传视为置空） | owner/Admin | 10001、10004/403、10005/404、10006/409 |
| DELETE | `/{id}` | 软删，幂等（不存在也 200） | owner/Admin | 10004/403 |

路由核对（api-standards §1/§2.1）：成员族 `/api/v1/<module>/**` 带模块段 `knowledge`；资源名复数 kebab-case `datasets`；纯 CRUD 无动作子资源；不用 PATCH；查询/分页走 query 参数。

删除的幂等与 403 并存口径：**资源存在但无权限 → 10004/403**（团队共享资源不需要隐藏存在性，与 app 一致；区别于 conversation 私有数据的 404 掩盖）；**资源不存在/已删 → 200**（幂等）。

### 2.2 DTO（`knowledge/dto`，模块内自用，不进 api 顶层包）

```java
public record CreateDatasetRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {}

public record UpdateDatasetRequest(   // PUT 全量：description 传 null 即置空
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {}

public record DatasetResponse(
        Long id, String name, String description, Long ownerId,
        OffsetDateTime createTime, OffsetDateTime updateTime) {}
```

- Long 由 infra 全局序列化为 string；时间 ISO-8601 带时区（既有 Jackson 配置，零新配）。
- DTO 不 import entity（ArchUnit 规矩）；entity→DTO 投影放 service 私有方法。
- 无跨模块消费者 → 不涉及 api 顶层包问题；`knowledge/api` 的 Facade 本轮**不建**（YAGNI，K5 应用绑定校验时再暴露 `assertDatasetExists` 之类）。

### 2.3 Service / Mapper / Entity

- `Dataset` entity（`knowledge/entity`）：继承 `BaseEntity`（id/createTime/updateTime/deleted 在基类，软删由基类 `@TableLogic` 生效），`@TableName("dataset")`，自有字段仅 name/description/ownerId——照 App entity 现状。
- `DatasetMapper`（`knowledge/mapper`）：继承 BaseMapper，K1 无手写 SQL。
- `DatasetService`（`knowledge/service`）：
  - `page(keyword, page, size)`：keyword 只模糊搜 name（照 AppService 现状，不搜 description），按 create_time 倒序；
  - `create(request, currentUser)`：直接插入，**不做插入前重名预查**——靠部分唯一索引 + 捕获 `DuplicateKeyException` → `BizException(CommonError.CONFLICT, "知识库名已存在")`（照 AppService 现状，天然无并发窗口）；
  - `update(id, request, currentUser)`：存在性（10005）→ 权限 assertCanModify（owner==current 或 Admin，否则 10004）→ 全量覆盖，重名同样靠 DuplicateKeyException → 10006；
  - `delete(id, currentUser)`：查无 → 直接返回（幂等）；查到 → 权限 → 软删。
  - `@Transactional` 只在写方法；无外部 IO。
- Controller 协议层零业务：`@Valid` → `CurrentUserHolder.current()` → service → `Result.ok(...)`；无 try-catch。

## 3. 前端（web/）

### 3.1 改动面

| 文件 | 动作 |
|---|---|
| `web/src/api/knowledge.ts` | 新增：`listDatasets / getDataset / createDataset / updateDataset / deleteDataset`，类型 `Dataset`、分页返回复用既有 `PageResult` 类型 |
| `web/src/views/knowledge/KnowledgeList.vue` | 占位页做实（路由与侧边栏入口**已存在**，无路由改动） |
| `web/src/views/knowledge/__tests__/` | 新增 vitest 测试 |

### 3.2 KnowledgeList 页面（照 AppList.vue 模式）

- 顶部：搜索框（keyword，防抖）+ 「新建知识库」按钮。
- el-table 列：名称、描述、归属（el-tag「我创建/其他成员」，对比 `ownerId === userStore.user?.id`）、创建时间、操作（编辑 / 删除）。
- 创建/编辑共用 el-dialog 表单：name（必填 ≤50）、description（≤200）；前端校验与后端注解同刻度。
- 删除走 ElMessageBox 确认；操作按钮权限禁用：`canModify = isAdmin || ownerId === 当前用户`（与后端 10004 双保险，照 AppList 现状写法）。
- 分页：el-pagination，page/size 对接 PageResult。
- Element Plus 组件与 icons-vue 图标优先，不自造轮子（frontend-standards §5.9）。

## 4. 测试与验收（TDD 先红后绿）

**后端**（沿用 mock 约定，不连库）：
- `DatasetServiceTest`（Mockito，mock Mapper）：创建成功 / DuplicateKeyException→10006；更新的 10005/10004/10006；删除幂等（不存在→不抛）/无权限 10004/软删调用；分页参数透传。
- `DatasetControllerTest`（MockMvc 切片）：路由与方法映射、`@Valid` 校验（name 空→10001 字段数组）、Result 信封、Long→string 序列化。
- ModularityTests / ArchUnit 通过（knowledge 依赖白名单只有 provider，本轮实际零跨模块依赖，只用 common/infra）。

**前端**（vitest，`__tests__/`，data-test + 行为断言）：
- 列表渲染与空态；搜索触发重查；创建表单校验与提交；编辑回填；删除确认后调 API；非 owner 非 Admin 时编辑/删除禁用。

**手动验收**：登录 → 侧边栏「知识库」→ 新建 → 重名再建（报 409 提示）→ 改名 → 用另一账号看禁用态 → 删除 → 同名重建成功。判 mvn 结果不用 `-q`；前端 `web/` 跑、后端 `server/` 跑。自检追加 `docs/self-check.md`。

## 5. 不破契约（约束清单）

- 已发布错误码与对外 API 不动；本轮零新码、零 SecurityConfig 改动、零既有表变更（只新增 V13）。
- 禁改已合并迁移脚本；`@Transactional` 内无 LLM/外部 IO；列表查询不 `select *`。
- knowledge 模块此前空壳，无既有契约可破；`app_dataset_rel` 严格留给 K5（属 app 模块）。
