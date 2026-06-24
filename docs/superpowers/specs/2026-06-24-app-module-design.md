# app 模块设计（第一轮：对话型应用元数据 + 团队共享权限，前后端一体）

> 日期：2026-06-24　范围：`app` 模块第一轮（后端 + 前端一起做、一起验证）
> 配套规范：code-organization.md（模块边界/分层）、api-standards.md（路由/Result/错误码）、
> data-model.md（表与跨模块不建外键）、database-standards.md（建表/分页/索引）、frontend-standards.md。

## 1. 背景与排期定位

app 模块是 conversation 运行时的前置：排期③ 单轮聊天时，conversation 读 app 拿到「用哪个模型 + 系统提示词」。
本轮排在 ① 位（最独立，自己不调 LLM），其后是 ② provider C 地基（ProviderFacade + ChatClientFactory + decrypt），
再到 ③ conversation 最小态。

### 本轮做什么
- 建 `app` 一张表。
- 对话型（`chat`）应用的元数据 CRUD：创建 / 列表（分页）/ 详情 / 全量更新 / 软删 / 启用 / 停用。
- **团队共享制权限**首次落地：全员可见可用，改/删/启停仅 owner + Admin。这套判定以后 knowledge/tool 照抄。
- 前端：成员侧「应用管理」页（列表 + 创建/编辑弹窗 + 权限门控按钮 + 服务端分页），立起前端首个分页范式。

### 本轮不做（推迟，均有明确触发点）
| 推迟项 | 触发点 |
|---|---|
| `app_api_key`（对外 API Key） | 对外 API `/v1/apps/**` 运行时（conversation/workflow） |
| `app_dataset_rel`（↔知识库） | knowledge 模块有 `dataset` 表 |
| `app_tool_rel`（↔工具） | tool 模块有 `tool` 表 |
| `published_def_id`、工作流型应用 | workflow 模块（含发布机制） |
| **model_id 存在性校验** | ② ProviderFacade.existsChatModel 落地 |
| **前端模型选择器** | ② 提供成员侧「列可用 chat 模型」接口 |

### 依赖
本轮**实际只用 common + infra**。provider/knowledge/tool 虽在 app 的依赖白名单内，本轮一个都不 import
（引用校验全部推迟）。`package-info.java` 的 `allowedDependencies` 保持现状不动。

## 2. 数据模型

### 2.1 `app` 表（Flyway：`V7__create_app.sql`）

```sql
create table app (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    type        text        not null check (type in ('chat', 'workflow')),
    model_id    bigint,      -- 对话型绑的 chat 模型；弱引用 ai_model，无 FK；本轮存而不校
    config      jsonb       not null default '{}',  -- 对话型运行配置（systemPrompt 等），整存整取
    owner_id    bigint      not null,  -- 弱引用 sys_user，团队共享制的归属人
    status      text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app is '应用（app 模块）：type 分对话型/工作流型；对话型绑 model_id + config(jsonb)；团队共享制带 owner_id';

-- 应用名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index app_name_uq on app (name) where deleted = false;
```

字段决策与「为什么」：
1. `type` 的 check 含 `chat`/`workflow` 两值（表结构未来就绪，省一次改 check 的迁移），但本轮 CRUD 只受理 `chat`。
2. `model_id` 可空、**无外键**（data-model.md 第 3 条：跨模块只存 id，引用完整性由目标模块 Facade 校验）。对话型才填，本轮存而不校。
3. `config jsonb`，默认 `'{}'`（不用 null，消灭 null/空对象双轨）；**整存整取、不建 GIN**（database-standards 2.4）。本轮内部约定 `{ "systemPrompt": string }`，将来加温度/记忆窗口往里塞，不改表不破坏接口契约。
4. `owner_id` 弱引用 `sys_user`，非空。
5. **索引只建 `app_name_uq`**。`owner_id` / `model_id` 本轮无查询路径（权限校验是先按 id 加载再比 owner_id，不是 WHERE 过滤；app 是常规小表）——遵 database-standards 原则 5（不推测性加索引），真出现「查某模型被哪些 app 用」等查询路径再加。
6. 唯一索引用「部分唯一索引 + where deleted=false」（database-standards 2.3），否则软删后无法同名重建。

### 2.2 实现风险点：首个 jsonb 列

`app.config` 是全项目**第一个 jsonb 列**。MyBatis-Plus 侧用 `@TableField(typeHandler = JacksonTypeHandler.class)`，
entity 字段映射为类型化对象（见 4.2 的 `AppConfig`）。**PostgreSQL 注意**：jsonb 列写入时，JDBC 默认把 String 当
`varchar` 传会报 `column "config" is of type jsonb but expression is of type character varying`。实现时需确认现有
JDBC 连接串/类型处理能正确写 jsonb（常见解法：连接串 `stringtype=unspecified`，或类型处理器产出 `PGobject`）。
TDD 阶段加一条 config 往返用例（写入后读回结构一致）守这条。

## 3. 后端 API

路由族：apps 是**成员资源**（团队共享，非 admin 专属），落成员族 `/api/v1/app/apps`（api-standards 第 1 节示例即此路径）。

| 方法 | 路径 | 动作 | 谁能调 |
|---|---|---|---|
| GET | `/api/v1/app/apps?type=&keyword=&page=1&size=20` | 列表（页码分页 PageResult） | 全员 |
| GET | `/api/v1/app/apps/{id}` | 详情 | 全员 |
| POST | `/api/v1/app/apps` | 创建（创建者成为 owner） | 全员 |
| PUT | `/api/v1/app/apps/{id}` | 全量更新 | owner+Admin |
| DELETE | `/api/v1/app/apps/{id}` | 软删（幂等） | owner+Admin |
| POST | `/api/v1/app/apps/{id}/enable` | 启用 | owner+Admin |
| POST | `/api/v1/app/apps/{id}/disable` | 停用 | owner+Admin |

### 3.1 请求/响应 DTO（仅本模块 controller 用，放 `dto/`）

```
CreateAppRequest {
  name        @NotBlank, ≤50
  description  可选, ≤200
  type        @NotBlank（本轮仅 'chat' 放行；'workflow' → 16001）
  modelId     可选 Long（契约就位；本轮存而不校）
  config      可选，AppConfig{ systemPrompt 可选 }
}
UpdateAppRequest {                  // 全量更新（PUT 语义）；type 不可改，故无 type 字段
  name        @NotBlank, ≤50
  description  可选, ≤200
  modelId     可选 Long
  config      可选，AppConfig{ systemPrompt 可选 }
}
AppResponse {
  id, name, description, type, modelId, config{ systemPrompt },
  ownerId, status, createTime, updateTime
}
```

序列化（infra 全局，DTO 不写局部注解）：`id`/`modelId`/`ownerId` 等 Long 一律序列化为 string；时间 ISO-8601 带时区；
集合永不为 null；字符串入参全局 trim、空串按 null。

### 3.2 分页

- 入参 `page`（默认 1）、`size`（默认 20，最大 100）；强制 `page × size ≤ 10_000`，超出 → `10001`（database-standards 4）。
- 实现照 demo：`mapper.selectPage(Page.of(page, size), wrapper.orderByDesc(App::getId))`，以 id 结尾保证稳定排序；
  `@TableLogic` 自动加 `where deleted=false`。
- 过滤：`keyword` → name 模糊匹配（`like`）；`type` 可选等值（本轮只有 chat 数据，参数仍支持）。
- 返回 `PageResult<AppResponse>`（`common.PageResult`，已存在，demo 在用）。app 是常规表，正常 count，不关。

### 3.3 错误码（app 段 16xxx）

本轮 16xxx 段**只新增一个**，其余全复用通用段（api-standards 5.4 第 1 条）：

| code | HTTP | 含义 |
|---|---|---|
| 16001 | 400 | 暂仅支持创建对话型应用（创建/更新传 `type=workflow` 时） |

复用通用段：`10005/404` 应用不存在；`10006/409` 应用名已存在；`10004/403` 非 owner/admin 改删启停；`10001/400` 参数校验失败。
`ErrorCode` 实现见 provider 的 `ProviderError` 范式。

### 3.4 权限落地（团队共享制，data-model 第 3 节 + api-standards 第 6 节）

- 读/列表：全员放行，不按 owner 过滤（仅 `where deleted=false`，团队全可见）。
- 创建：任意登录成员；`owner_id = CurrentUserHolder.current().userId()`。
- 改/删/启/停：service 内先按 id 加载（不存在 → `10005/404`），再判
  `current.isAdmin() || app.ownerId.equals(current.userId())`，不满足 → `10004/403`。抽私有 `assertCanModify(app)` 复用。
- 判定写在 **service 层**（controller 不写业务分支）；`CurrentUser` 从 `infra` 的 `CurrentUserHolder` 取，不依赖 identity、不碰 Spring Security。

### 3.5 名称唯一性

靠 DB 部分唯一索引把关：插入/更新撞索引 → service catch Spring 的 `DuplicateKeyException` → 抛 `BizException(CommonError.CONFLICT, "应用名已存在")`。
**不先查后插**（database-standards 6.3：有唯一约束禁先查后插，必有竞态）。

## 4. 后端分层与文件清单

遵 code-organization 第 2 节模块内结构。本轮新增（均在 `com.hify.app` 下）：

```
controller/AppController.java          // 7 端点，协议层：@Valid → service → Result，无业务逻辑
dto/CreateAppRequest.java              // @Valid 注解
dto/UpdateAppRequest.java
dto/AppResponse.java
dto/AppConfig.java                     // record { String systemPrompt }；请求/响应/jsonb 三处共用
service/AppService.java                // 业务唯一所在地，@Transactional 仅此层；assertCanModify、toResponse
entity/App.java                        // @TableName("app") extends BaseEntity；config 用 JacksonTypeHandler
mapper/AppMapper.java                  // extends BaseMapper<App>
constant/AppType.java                  // enum chat/workflow（与 DB check 一致）
constant/AppStatus.java                // enum enabled/disabled
constant/AppError.java                 // 16001
resources/db/migration/V7__create_app.sql
```

- `HealthController` 现已在 app 模块，本轮不动。
- `package-info.java` 不改（本轮不 import 别模块）。
- entity 不出现在 api/controller/dto 签名；DTO 不 import entity（Entity↔DTO 转换在 service 的 `toResponse`）。

### 4.2 `AppConfig`
对话型运行配置，本轮仅 `systemPrompt`（可选）。作为 record 同时用于：请求体子对象、响应体子对象、entity 的 config 字段（经 JacksonTypeHandler 落 jsonb）。新增字段不算破坏性变更，向后兼容扩展。

## 5. 前端设计（对齐既有范式）

既有列表页范式（ProviderList / UserList）：`PageHeader + ContentCard + el-table + el-dialog 表单`，`data-test` 锚点，
`ElMessage` 成功提示，`confirmDanger` 二次确认，错误由 request 拦截器统一 toast。app 页照抄并**增加服务端分页**。

### 5.1 新增/修改文件
- `src/types/app.ts`：`AppType`、`AppStatus`、`AppConfig`、`App`（响应）、`AppForm`（创建/编辑共用）；并新增 `PageResult<T>` 类型（前端首次用）。`id/modelId/ownerId` 均 string。
- `src/api/app.ts`：成员资源放 `api/` 根（不进 `admin/`）。`BASE='/app/apps'`。封装 `listApps(params) / getApp / createApp / updateApp / deleteApp / enableApp / disableApp`。`listApps` 入参 `{ keyword?, type?, page, size }`，返回 `PageResult<App>`。
- `src/views/app/AppList.vue`：替换现占位 `<h2>`。

路由与菜单已就位（`/app` → AppList.vue，member 可见，无 roles），本轮不改路由。

### 5.2 列表页
- 列：名称、类型（tag「对话」）、状态（tag 启用/停用）、**归属**（`我创建` / `其他成员`，靠 `ownerId === userStore.user?.id` 判，不查 identity）、创建时间、操作。
- **权限门控**：`canModify(app) = userStore.isAdmin || app.ownerId === userStore.user?.id`。`编辑 / 启停 / 删除` 仅 canModify 时显示；他人应用只可见、本轮无操作按钮（「使用/聊天」是③的事）。与后端 10004 双保险——前端隐藏是体验，后端校验才是闸门。
- **分页**：`el-pagination`（前端首个），`page`/`size` 变动重拉 `listApps`。顶部名称关键字搜索（触发重拉，回到第 1 页）。类型筛选本轮不放（只有 chat）。

### 5.3 创建/编辑弹窗（共用）
- 字段：名称（必填 ≤50）+ 描述（≤200）+ 系统提示词（textarea，可选）。
- 类型固定「对话应用」展示、不可选（本轮只 chat）；编辑时 type 不可改（与后端 PUT 一致）。
- **无模型选择器**（成员侧无列模型接口，整体推迟到②，与后端 model_id 存而不校对称）。`modelId` 本轮前端不提交。
- happy-dom 下 el-form.validate 对空必填会误判通过——提交前按后端约束再兜底校验一次（照 UserList 第 138 行做法）。

## 6. 测试策略

后端连库测试（Testcontainers）仍推迟（到 knowledge 手写 SQL 那轮），本轮用 mock。前端 vitest + TDD，先写失败测试。

### 6.1 后端
- `AppService` 单测（Mockito mock `AppMapper`）：
  - 创建：owner_id 取当前用户；`type=workflow` → 16001；config/modelId 透传落库。
  - 重名：mapper 抛 `DuplicateKeyException` → 翻成 10006/409。
  - 更新/删除/启停：owner 放行、admin 放行、他人 → 10004/403、不存在 → 10005/404。
  - 列表/详情：团队全可见（不按 owner 过滤）。
  - config 往返：写入后读回 systemPrompt 一致（守 jsonb 类型处理）。
  - `CurrentUserHolder` 在单测里需注入安全上下文或对取当前用户处做可测设计（照 identity/provider 既有测试做法）。
- `AppController` 测试（MockMvc，mock service）：路由通；`@Valid` 拦截（name 空 → 10001 + 字段数组）；Result 信封；Long 序列化为 string；分页参数透传。
- 既有 `ModularityTests` + `LayerRulesTest`（ArchUnit）保持绿：新增类落进各自包即被规则覆盖。

### 6.2 前端
- `AppList` 组件测试（vitest + happy-dom，mock `@/api/app`，放 `__tests__/`）：
  - 列表渲染 + 分页交互（翻页触发 listApps）。
  - canModify 门控：owner 见编辑/删除、他人不见。
  - 创建表单校验（空名不提交的兜底）+ 提交调 createApp。
  - 启停/删除调对应 API + 成功 toast + 重拉。

判定测试结果**不 grep BUILD SUCCESS**（`-q` 会静音），看测试计数/退出码。

## 7. 已知缺口与排期衔接（显式遗留项）

| 缺口 | 补在哪 | 具体动作 |
|---|---|---|
| model_id 不校验存在性 | ② | AppService 创建/更新里：modelId 非空时调 `ProviderFacade.existsChatModel(modelId)`，不存在/非 chat/停用则抛错；真正 import `provider::api`。 |
| 前端无模型选择器 | ② | 提供成员侧「列可用 chat 模型」接口；编辑表单加模型下拉，提交 modelId。 |
| dataset/tool 绑定 | knowledge/tool 有表后 | 建 `app_dataset_rel` / `app_tool_rel` + 绑定 API + 校验。 |
| 工作流型应用、发布、对外 API Key | workflow 模块 / 对外 API 轮 | 放开 type=workflow、加 published_def_id、建 app_api_key。 |

## 8. 端到端验证

前后端一起做，验证也一起：
1. 后端 `mvn test`（service + controller + Modulith + ArchUnit）全绿。
2. 前端 `pnpm test`（AppList 组件测试）全绿、`pnpm build` 通过。
3. 起服务（本地 dev 或 compose），以 admin/member 两个账号分别登录，走查：member 建 app → 列表见到 → 编辑自己的 app（能改）→ 看到他人 app（无操作按钮）→ admin 能改任何人的 app；分页/搜索/启停/删除逐项点。

## 9. 决策记录（brainstorm 拍板）

1. **建表范围**：本轮只建 `app`，其余 3 张表推迟（见 1.2）。
2. **model_id**：存列、本轮不校验，② 接 ProviderFacade 补校验。
3. **应用类型**：只做 `chat` 端到端；type 列 check 含两值但 CRUD 只受理 chat，workflow → 16001。
4. **索引**：只建 `app_name_uq`，owner_id/model_id 不建（无查询路径）。
5. **列表分页**：服务端分页 PageResult（非客户端数组），立起前端首个分页范式；理由：api-standards 即以 app 端点举例分页、app 是最可能增长的资源、避免将来 List→PageResult 破坏性变更。
6. **前后端一体**：app 是首个成员资源，本轮前后端一起做、一起验证，确立「成员资源列表页 + 团队共享门控」范式供 knowledge/tool 照抄。
