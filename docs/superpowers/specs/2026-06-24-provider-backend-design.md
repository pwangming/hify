# Provider 模型提供商管理后端设计（第 1 轮：Provider CRUD + Key 加密）

> 状态：已认可（2026-06-24）。本轮只做 Provider 实例的增删改查、启用/禁用、API Key 加密存储。
> Model 管理（`ai_model`）、韧性装饰器 + `ProviderFacade`、连通性校验**均不在本轮**，见末尾「后续轮次」。

## 0. 范围与边界

**本轮做（方案 1）**

- `model_provider` 表（V5 迁移）
- Admin CRUD：列表 / 创建 / 全量更新 / 逻辑删除
- 启用 / 禁用（动作子资源 POST）
- API Key 对称加密存储（AES-256-GCM，主密钥在 `.env`），列表回掩码尾巴

**本轮刻意不做（YAGNI / 已切到后续轮次）**

- `ai_model` 表与"一个供应商下多个模型"的管理 → 子系统 B
- 韧性字段（`max_concurrency` 等 10 个）、`ResilientChatModel`、`ResilienceRegistry`、`ProviderFacade` → 子系统 C
- 运行健康态（熔断/连通）的呈现 → 子系统 C（不落库，见 §1.3）
- 保存时连通性校验 → 子系统 D
- `GET /{id}` 详情端点（前端用列表行数据预填编辑表单，无需）

## 1. 数据模型

### 1.1 三个设计要点的结论（先讲为什么，再给表）

**① 多种鉴权方式的差异 → 不做"统一存储"，差异由 `protocol` 推导，属代码而非数据**

| 协议 | 鉴权 | 需存的秘密 |
|---|---|---|
| OpenAI 兼容 | `Authorization: Bearer <key>` | 一个 API key |
| Anthropic | `x-api-key: <key>` + `anthropic-version` 头 | 一个 API key |

两者本质都是"一个 header 塞一个 key"，差异（header 名、version 头）完全由协议决定、不随单个供应商变化（`anthropic-version` 是 Spring AI `AnthropicApi` 的协议常量）。因此存储只需 `{base_url, 一个加密 key}`，鉴权"长什么样"由代码按 `protocol` 推导，**不引入 polymorphic 鉴权配置结构**。这是"按协议建模"的红利。未来若接 Azure / Bedrock 这类异构鉴权，再加 `extra_config jsonb` 兜底——超出 CLAUDE.md 一期范围，现在做即过度设计。

**② 一个供应商下多个模型 → 独立的 `ai_model` 表（子系统 B），本轮刻意不做**

`data-model.md` 已定 `model_provider 1──N ai_model`，两表同属 provider 模块，故 `ai_model.provider_id` 是**模块内 FK**（可建真外键、可级联删除）。本轮 `model_provider` 表无需为将来的 model 做任何特殊设计；加 model 表是纯增量迁移，不回头改本表。推论：删除供应商在其有 model 时应拦截（防悬空引用），但本轮尚无 model 表，故该守卫属 B 轮；本轮删除无条件（逻辑删除）。

**③ 供应商状态 → 区分"管理态"与"运行健康态"，后者绝不落库**

| | 含义 | 谁改 | 存哪 | 本轮 |
|---|---|---|---|---|
| 管理态 `status` | admin 手动启用/禁用 | admin 点按钮 | `model_provider.status` 列 | ✅ |
| 运行健康态 | 此刻调得通/是否熔断 | 系统运行时观测 | **不进库**（Resilience4j 内存 Registry + Actuator 指标） | ❌ C 轮 |

运行健康态若落库会立刻过期、需要写入方、且与熔断器真相打架——经典反模式。本轮 `status` 只表达管理态。

### 1.2 `model_provider` 表（V5 迁移）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | bigint | PK | 对外序列化为 string（防精度丢失） |
| `name` | varchar(50) | not null，部分唯一 | 显示名；区分"通义-生产""Claude-海外"等 |
| `protocol` | varchar(20) | not null | `openai` / `anthropic` |
| `base_url` | varchar(255) | not null | 端点地址 |
| `api_key_cipher` | text | not null | AES-256-GCM 密文（主密钥在 `.env`） |
| `api_key_tail` | varchar(8) | not null | 明文后 4 位，仅供掩码展示；本轮永不解密读取 |
| `status` | smallint | not null default 1 | 管理态：1=启用 0=禁用 |
| `created_at` | timestamptz | not null | BaseEntity 自动填充 |
| `updated_at` | timestamptz | not null | BaseEntity 自动填充 |
| `deleted` | smallint | not null default 0 | 逻辑删除（`@TableLogic`） |

**部分唯一索引**：`UNIQUE (name) WHERE deleted = 0`——逻辑删除后重名可复用。

**刻意不加**：`description`（前端类型无）、任何韧性字段（C 轮增量加）、任何 health 列、任何 auth-config jsonb。

## 2. API 接口

### 2.1 端点（admin 族，写在 `provider/controller/`）

| 方法 | 路径 | 用途 | 成功返回 |
|---|---|---|---|
| GET | `/api/v1/admin/provider/providers` | 列表（不分页） | `Result<List<ProviderResponse>>`，空则 `[]` |
| POST | `/api/v1/admin/provider/providers` | 创建 | `Result<ProviderResponse>`（含 id） |
| PUT | `/api/v1/admin/provider/providers/{id}` | 全量更新 | `Result<ProviderResponse>` |
| DELETE | `/api/v1/admin/provider/providers/{id}` | 逻辑删除 | `Result<Void>` |
| POST | `/api/v1/admin/provider/providers/{id}/enable` | 启用 | `Result<Void>` |
| POST | `/api/v1/admin/provider/providers/{id}/disable` | 禁用 | `Result<Void>` |

- 启停用动作子资源 POST（不用 PATCH，符合 CLAUDE.md / api-standards）。
- 删除/启停成功后前端**重拉列表**刷新，故返回 `Void`。
- 不做 `GET /{id}` 详情（前端用列表行预填编辑表单）。

### 2.2 DTO（`provider/dto/`，JSON camelCase）

```
CreateProviderRequest  { name, protocol, baseUrl, apiKey }   // 全部必填
UpdateProviderRequest  { name, protocol, baseUrl, apiKey }   // apiKey 可空=不改，其余必填
ProviderResponse       { id, name, protocol, baseUrl, status, apiKeyTail, createTime }
```

校验：`name` @NotBlank max 50；`protocol` ∈ {`openai`,`anthropic`}；`baseUrl` @NotBlank + http/https 前缀；`apiKey` 创建必填、更新可空。

响应字段：
- `id`：Long → string。
- `protocol`：`openai` / `anthropic`（取代前端现有 `type` 四值；新契约，前端后续轮次适配）。
- `status`：`'enabled'` / `'disabled'` 字符串（库内 smallint，DTO 转换层映射）。
- `apiKeyTail`：明文后 4 位（如 `a1b2`），前端渲染成 `****a1b2`；**密钥明文永不出响应**。
- `createTime`：ISO 字符串。

### 2.3 契约偏差：PUT 全量更新 + 写-only 密钥（已认可）

api-standards 规定 PUT 全量、未传字段置空。`apiKey` 是**只写密钥**——服务端无法把明文回传给前端整体提交，故 `apiKey` 是 PUT 全量规则的**明确例外**：留空 = 保留原密文，非空 = 重新加密覆盖。`name`/`protocol`/`baseUrl` 严格全量必填。代码注释须写明此例外。

## 3. 错误码（本轮全部复用通用段 10xxx，不新增 12xxx）

| 场景 | 错误码 | HTTP |
|---|---|---|
| 参数校验失败（@Valid） | `10001` | 400 |
| 名称重复 | `10006` | 409 |
| 供应商不存在 / 已删 | `10005` | 404 |
| Member 调 admin 接口 | `10004` | 403 |
| 加密/系统异常兜底 | `10000` | 500 |

- 遵循 api-standards 规则 1「优先复用通用段」：重名、不存在均为预定义通用码，不在 provider 段重发明。
- 12xxx 段留给 C 轮（文档已示例 `12002/503 供应商熔断中`）。
- 名称唯一：service 先查友好报 `10006`；DB 部分唯一索引作并发兜底（捕获唯一键冲突 → `10006`）。

## 4. 加密：ApiKeyCipher

- 算法 **AES-256-GCM**。主密钥从 `application.yml` 的 `hify.provider.crypto.master-key` 读，值引用 `.env` 的 `${HIFY_PROVIDER_MASTER_KEY}`（32 字节，base64 编码）。
- 每次加密随机 12 字节 IV，密文格式 `base64(IV ‖ ciphertext ‖ GCM tag)` 整体存 `api_key_cipher`。
- **同时实现 `encrypt` 和 `decrypt`**（单向加密器无法测往返正确性）；但本轮**业务流程只调 `encrypt`**，`decrypt` 有单测覆盖、无任何 endpoint/service 触达。安全姿态不变（解密不可经接口到达）。
- 写入流程：`encrypt(apiKey)` → 存密文；`apiKey` 截后 4 位 → 存 `api_key_tail`。
- `.env.example`（deploy/）补 `HIFY_PROVIDER_MASTER_KEY` 占位说明。

## 5. 分层落地

```
provider/
├── controller/AdminProviderController.java   # @Valid + Request→service + Result，无业务/无 @Transactional/不注 mapper
├── service/
│   ├── ProviderService.java                  # @Service 具体类；CRUD + enable/disable；写操作 @Transactional
│   └── ApiKeyCipher.java                      # AES-256-GCM encrypt/decrypt
├── dto/{CreateProviderRequest,UpdateProviderRequest,ProviderResponse}.java
├── entity/ModelProvider.java                 # extends BaseEntity；@TableLogic deleted
├── mapper/ModelProviderMapper.java           # extends BaseMapper<ModelProvider>
├── constant/
│   ├── ProviderProtocol.java                 # enum { openai, anthropic }
│   └── ProviderStatus.java                   # enum { ENABLED(1), DISABLED(0) }
└── config/ProviderCryptoProperties.java      # @ConfigurationProperties 读主密钥
# 本轮不建 api/Facade（无消费方，留 C 轮）；api/package-info.java 保持。
```

迁移：`src/main/resources/db/migration/V5__create_model_provider.sql`。

**分层纪律**

- Controller 不碰 mapper、不写 `@Transactional`、不写业务分支；只校验 + 转参 + 包 `Result`，异常抛 `BizException`（infra 全局处理器转信封）。
- Service 是业务唯一所在；写操作 `@Transactional`（加密为本地 CPU，可在事务内；本轮无 LLM/外部 IO，不违反硬规则）。
- **entity→ProviderResponse 投影写成 service 私有方法，不写 `ProviderResponse.from(entity)`**（ArchUnit 禁 DTO import entity）；`status` 的 smallint→字符串映射也在此。

## 6. 测试策略

遵循 testing-standards + 本项目「Testcontainers 推迟到 knowledge 手写 SQL 轮」约定：本轮后端测试**全部 mock `ModelProviderMapper`（Mockito），不连真库**。TDD：先写红用例再实现。

- **ApiKeyCipher 单测**：`decrypt(encrypt(x)) == x`；同一明文两次加密因随机 IV 密文不同；tail 取后 4 位正确。
- **ProviderService 单测**（mock mapper）：
  - 创建：调 `encrypt`、写 tail；名称重复预检 → `10006`。
  - 更新：`apiKey` 空 → 保留原密文/tail；非空 → 重新加密；不存在 → `10005`。
  - 删除 / 启停：不存在 → `10005`；启停幂等（已启用再启用 no-op 200）。
  - 列表投影：响应**绝不含密文/明文 key**；`status` 正确映射。
- **AdminProviderController 测**（MockMvc + mock service，照 identity 的 `AdminUserControllerTest`）：6 路由通；`@Valid` 失败 → 400/10001；非 admin → 403/10004；`Result` 信封正确。
- **边界测试**：ModularityTests / ArchUnit 通过——provider 无跨模块依赖、DTO 不 import entity。

每铺完一步追加自检到 `docs/self-check.md`（项目既有节奏，实现计划阶段执行）。

## 7. 后续轮次（不在本轮，登记备忘）

- **B**：`ai_model` 表 + 一供应商下多模型（chat/embedding）管理；删除供应商需拦截"有模型"。
- **C**：韧性字段增量迁移 + `ResilientChatModel` + `ResilienceRegistry` + `ProviderFacade.getChatClient/getEmbeddingModel`；`ApiKeyCipher.decrypt` 在此接入真实调用；运行健康态经 Actuator 呈现。
- **D**：保存时连通性校验（SSRF 防护下试调端点）。
- **前端**：ProviderList 从 mock 切真后端，`type`(4 值) → `protocol`(2 值)，新增 `apiKeyTail` 展示。
