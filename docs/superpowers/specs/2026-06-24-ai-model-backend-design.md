# ai_model 模型管理后端设计（Provider B 轮）

> 状态：已认可（2026-06-24）。在已上线的 provider 模块内扩展：管理"一个供应商下的多个具体模型"（chat / embedding）。
> 依赖 R1（`model_provider` 已上线）。本轮纯后端，前端"模型管理界面"留后续轮次。

## 0. 范围与边界

**本轮做**
- `ai_model` 表（V6 迁移），挂在 `model_provider` 下（模块内 FK）。
- 模型 CRUD + 启用/禁用（混合路由）。
- 创建时拦截"Anthropic 供应商下建 embedding 模型"。
- 改造 `ProviderService.delete`：供应商下有模型时拒绝删除。

**本轮不做（YAGNI）**
- 前端模型管理界面（下一轮）。
- 默认模型（`is_default`）、全局"所有模型"列表。
- app 绑定模型（属 app 模块）；`ResilientChatModel`/`ProviderFacade` 消费模型（属 C 轮）。
- 连通性/可用性校验（D 轮）。

## 1. 数据模型

### 1.1 `ai_model` 表（V6 迁移）

类型遵循 database-standards / V4-V5 惯例：text+check 枚举、boolean、timestamptz、bigint identity、公共四列由 `BaseEntity` 承载。

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | bigint | `generated always as identity` PK | 对外序列化为 string |
| `provider_id` | bigint | not null，`references model_provider(id)` | 所属供应商（模块内 FK） |
| `type` | text | not null，`check (type in ('chat','embedding'))` | 模型用途 |
| `name` | text | not null，`check (char_length(name) <= 50)` | 显示名（给人看） |
| `model_key` | text | not null，`check (char_length(model_key) <= 100)` | API 模型标识（传给 LLM，如 `gpt-4o`） |
| `status` | text | not null default `'enabled'`，`check (status in ('enabled','disabled'))` | 管理态 |
| `deleted` | boolean | not null default false | 逻辑删除（`@TableLogic`） |
| `create_time` | timestamptz | not null default now() | 自动填充 |
| `update_time` | timestamptz | not null default now() | 自动填充 |

**唯一索引**：`create unique index ai_model_provider_key_uq on ai_model (provider_id, model_key) where deleted = false`——同一供应商下同一模型标识不重复添加。`provider_id` 前缀也服务"按 provider 查/计数"，无需额外索引。

### 1.2 关键约束

- `provider_id` 与 `type` **创建时定死、更新不可改**（换供应商/换用途 = 删了重建，避免语义漂移）。`update` 只改 `name` + `model_key`；`status` 走启停动作。
- 真 FK 做插入完整性（模块内 FK，data-model 允许）。provider 用逻辑删除（`@TableLogic` 改 deleted 标志、不真删行），FK 级联不触发——"删 provider 拦有模型"由 service 层计数守卫实现（§3）。

## 2. API（混合路由）

新建 `provider/controller/AdminModelController`。列表/创建挂父级供应商下，单条操作走顶级（api-standards "子资源有了自己的 id 就升为顶级"）。

| 方法 | 路径 | 用途 | 成功返回 |
|---|---|---|---|
| GET | `/api/v1/admin/provider/providers/{providerId}/models` | 列某供应商的模型 | `Result<List<ModelResponse>>`，空则 `[]` |
| POST | `/api/v1/admin/provider/providers/{providerId}/models` | 在该供应商下建模型 | `Result<ModelResponse>` |
| PUT | `/api/v1/admin/provider/models/{id}` | 改（name + modelKey） | `Result<ModelResponse>` |
| DELETE | `/api/v1/admin/provider/models/{id}` | 逻辑删除 | `Result<Void>` |
| POST | `/api/v1/admin/provider/models/{id}/enable` | 启用 | `Result<Void>` |
| POST | `/api/v1/admin/provider/models/{id}/disable` | 禁用 | `Result<Void>` |

启停用动作子资源 POST（不用 PATCH）；删除/启停返回 `Void`（前端重拉列表刷新）。

### 2.1 DTO（`provider/dto/`，camelCase）

```
CreateModelRequest { type, name, modelKey }   // providerId 来自路径
UpdateModelRequest { name, modelKey }         // type/providerId 不可改，不在请求体
ModelResponse      { id, providerId, type, name, modelKey, status, createTime }
```

校验：`type` @NotBlank @Pattern(`chat|embedding`)；`name` @NotBlank @Size(max 50)；`modelKey` @NotBlank @Size(max 100)。

`modelKey` 非敏感，**完整返回**（不像 apiKey 需掩码）。`id`/`providerId` 为 Long → string；`createTime` ISO-8601。

## 3. 校验与错误码（只新增 1 个 12xxx）

| 场景 | 错误码 | HTTP |
|---|---|---|
| @Valid 失败 | `10001` 复用 | 400 |
| 供应商不存在（创建）/模型不存在（改删启停） | `10005` NOT_FOUND 复用 | 404 |
| 模型重名 `(provider_id, model_key)` 冲突 | `10006` CONFLICT 复用 | 409 |
| 删 provider 时其下有未删模型 | `10006` CONFLICT 复用，message"请先删除该供应商下的模型" | 409 |
| Anthropic 供应商下建 embedding 模型 | **`12001` 新增** | 400 |

- 新增 `provider/constant/ProviderError.java`（`implements com.hify.common.exception.ErrorCode`）：
  `EMBEDDING_NOT_SUPPORTED(12001, HttpStatus.BAD_REQUEST, "该协议不支持 embedding 模型")`。
  依据 api-standards：模块段放该模块特有业务语义、可绑 400（参照示例 `15004/400 文档格式不支持`）。
- "删 provider 拦有模型"是教科书式资源状态冲突 → 复用通用 `CONFLICT`，不自造码。
- `12002` 不占用（api-standards 已示例 `12002/503 供应商熔断中`，留 C 轮）。
- embedding 协议规则只在**创建**时校验（创建时读所属 provider 的 `protocol`；`anthropic` + `embedding` → 抛 `EMBEDDING_NOT_SUPPORTED`）。`type` 不可改，更新无需复查。
- 创建/重名并发兜底：service 先 `selectCount` 预检报 `10006`；`insert` try-catch `DuplicateKeyException` → `10006`（对齐 R1 写法）。

## 4. 分层落地（provider 模块内扩展）

```
provider/
├── controller/AdminModelController.java     # 6 端点（混合路由）；@Valid + 调 service + Result
├── service/AiModelService.java              # CRUD + 启停 + embedding 协议守卫
│                                             #   注入 AiModelMapper + ModelProviderMapper（同模块）
├── dto/{CreateModelRequest,UpdateModelRequest,ModelResponse}.java
├── entity/AiModel.java                       # extends BaseEntity；@TableName("ai_model")；@TableLogic
├── mapper/AiModelMapper.java                 # extends BaseMapper<AiModel>
└── constant/
    ├── ModelType.java                        # enum { CHAT("chat"), EMBEDDING("embedding") }
    └── ProviderError.java                     # enum { EMBEDDING_NOT_SUPPORTED(12001,...) } implements ErrorCode
# 改：ProviderService 注入 AiModelMapper；delete() 删前 selectCount 该 provider 未删模型，>0 抛 CONFLICT。
```
迁移 `server/src/main/resources/db/migration/V6__create_ai_model.sql`。

**分层纪律（同 R1）**：Controller 不碰 mapper/不写事务/不写业务分支；Service 写操作 `@Transactional`、是业务唯一所在、无 LLM/外部 IO；entity→DTO 投影写在 service 私有方法（dto 禁 import entity）。`AiModelService` 读 `ModelProviderMapper` 取 protocol 属同模块内调用，合规。

### 4.1 ProviderService.delete 守卫（行为变更）

R1 的 `delete` 是无条件逻辑删除。本轮改为：

```java
@Transactional
public void delete(Long id) {
    long models = aiModelMapper.selectCount(
        new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, id));
    if (models > 0) {
        throw new BizException(CommonError.CONFLICT, "请先删除该供应商下的模型");
    }
    providerMapper.deleteById(id);
}
```
（`@TableLogic` 使 `selectCount` 只数未软删模型。）这是 R1 既有契约的收紧——R1 时无模型表故无条件删；现在有了子资源，必须拦悬空引用。

## 5. 测试策略（mock mapper，不连库；TDD）

- **AiModelService 单测**（mock `AiModelMapper` + `ModelProviderMapper`）：
  - 创建 chat（openai provider）✓：写库、status 默认 enabled、投影正确。
  - 创建 embedding（openai provider）✓。
  - 创建 embedding（anthropic provider）→ 抛 `12001`。
  - 创建时 providerId 不存在 → `10005`。
  - 创建重名（selectCount>0）→ `10006`；并发 DuplicateKey → `10006`。
  - 更新 name/modelKey ✓；不存在 → `10005`。
  - 启用/禁用：幂等（已是目标态不写库）；不存在 → `10005`。
  - 删除走软删；不存在幂等不抛。
  - 按 provider 列表投影。
- **ProviderService.delete 守卫单测**：该 provider 有模型（selectCount>0）→ `10006`；无模型 → 调 `providerMapper.deleteById`。
- **AdminModelController 测**（@WebMvcTest + mock service，照 `AdminProviderControllerTest`）：6 路由通；`@Valid` 失败 → 400/10001；非 admin → 403/10004；id 字符串。
- **边界**：ModularityTests / ArchUnit 仍绿（同模块扩展、DTO 不 import entity）。

每完成一步追加自检到 `docs/self-check.md`（实现计划阶段执行）。

## 6. 后续（不在本轮）

- 前端：模型管理界面（按供应商展开 → 增删改启停模型）。
- C 轮：`ProviderFacade.getChatClient(modelId)` / `getEmbeddingModel(modelId)` 解析 model→provider→构建带韧性的 ChatClient，并接入 `ApiKeyCipher.decrypt`。
- app 模块：对话型 app 绑定 chat 模型（弱引用 model_id）。
