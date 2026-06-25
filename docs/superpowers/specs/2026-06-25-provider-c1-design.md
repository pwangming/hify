# provider C1 设计（读侧地基：模型校验 Facade + 成员侧模型列表，前后端一体）

> 日期：2026-06-25　范围：provider 模块 C 轮的前半（C1，读侧），后端 + 前端一起做、一起验证
> 配套规范：code-organization.md（模块边界/Facade/分层）、api-standards.md（路由族/Result/错误码）、
> data-model.md（跨模块不建外键、引用完整性由目标模块 Facade 校验）、frontend-standards.md。

## 1. 背景与排期定位

provider C 是「app ① → provider C ② → conversation ③」里的地基轮。brainstorm（2026-06-25）拍板把 C **拆成 C1 + C2**：

- **C1（本轮，读侧）**：让 app 的 `model_id` 不再「存而不校」，并给前端一个能选模型的数据源。**完全不碰 ChatClient / LLM / 韧性**。
- **C2（下一轮，地基）**：`ProviderFacade.getChatClient(modelId)` —— ChatClientFactory 按协议构建 ChatModel + `ApiKeyCipher.decrypt` 注入 Key + 整套韧性（Retry→CB→Bulkhead→Timeout + 按 providerId 缓存的 Registry）+ `model_provider` 新增 10 个配置字段的 Flyway 迁移。C2 解锁 conversation 单轮聊天。

C1 正好补上 app 第一轮 spec 第 7 节列的两个显式遗留钩子（原文提示 `ProviderFacade.existsChatModel` + 成员侧列可用 chat 模型接口）。

### 本轮做什么
- provider 的**首个对外门面** `ProviderFacade`，一个只读方法：按 id 校验一个 chat 模型是否「可用」。
- provider 的**成员侧只读接口**：列出全部「可用」chat 模型，供前端选择器。
- app 接 Facade：create/update 时若带了 `model_id` 就校验，不可用则报错。
- 前端：app 创建/编辑弹窗把 `model_id` 从「不提交」改为 `el-select` 下拉选择器。

### 本轮不做（推迟到 C2，有明确触发点）
| 推迟项 | 触发点 |
|---|---|
| `getChatClient(modelId)` / ChatClientFactory / `decrypt` 接入 | C2（conversation 单轮聊天临近） |
| 整套韧性（ResilientChatModel / ResilienceRegistry） | C2 |
| `model_provider` 韧性配置 10 字段 + Flyway 迁移 | C2 |
| embedding 模型的列举与校验 | knowledge 轮（接口已留 `type` 参数） |
| app 创建时**强制**必须有模型 | conversation 真正聊天时校验「无模型不能聊」 |

### 关键决策（brainstorm 拍板）
1. **拆 C1/C2**：C1 纯读侧、改动小、可独立验证；C2 才是重的 LLM 地基。
2. **model_id 选填**：建/改对话应用时 `model_id` 可空（草稿应用）；非空才校。不破坏 app 第一轮的可空契约与测试。
3. **「可用」连带供应商**：模型 `enabled` **且** 其所属供应商 `enabled` **且** `type=chat` 才算可用。admin 禁用整个供应商时，其下模型自动不可选/不可用。
4. **Facade 返回 `Optional<ModelView>`，不是 `existsChatModel` 布尔**：偏离 app spec 第 7 节的命名提示。理由：前端选择器与未来展示都需要模型的 name/providerName，返回 DTO 比布尔可复用；app 侧拿 `Optional.isEmpty()` 即可判错，不损失表达力。
5. **join 不写手写 SQL**：用两次 MyBatis-Plus wrapper 查询拼装（见 §2.2），避开手写 SQL（沿用「手写 SQL 留到 knowledge 轮」的约定），且 mock mapper 即可单测。
6. **前后端一体**：照 app 第一轮先例，spec + plan 一起出、前后端一起做、一起验证。

### 依赖与边界（无需改动）
- app 的 `allowedDependencies` **已含** `provider::api`；provider/api 已标 `@NamedInterface("api")`。**本轮不改任何 package-info**。
- C1 **不需要 Flyway 迁移**（不动表结构）。
- 韧性依赖（resilience4j / spring-ai starter / actuator）pom 里**已就位**，C1 不用、C2 才用。

## 2. 后端设计

### 2.1 模块内文件清单（均在 `com.hify.provider` 下）

```
api/ProviderFacade.java              // 首个门面接口；方法签名用 api/dto + JDK 类型
api/dto/ModelView.java               // 首个对外 DTO：record { Long id, String name, String type, String providerName }
service/ProviderFacadeImpl.java      // 实现门面，委托 ModelQueryService（@Component，不写业务分支、不注 Mapper）
service/ModelQueryService.java       // 只读查询：listUsableChatModels / findUsableChatModel；注入 AiModelMapper + ModelProviderMapper
controller/ModelQueryController.java // 成员侧只读端点 GET /api/v1/provider/models
```

- `ProviderFacadeImpl` 放 `service/`、`ProviderFacade` 放 `api/`（code-organization：Facade 是唯一接口抽象，受 Modulith 强制校验）。Facade 不写业务逻辑/事务/Mapper 注入，只委托 service。
- `ModelQueryService` 是**只读** service，无 `@Transactional`（查询不需要）。与既有 `AiModelService`（admin CRUD、带 `@Transactional`）职责分离，不混。
- `ModelView` 是 provider 对外的第一个 DTO；ArchUnit 已禁 DTO import entity，转换在 service 内做。

### 2.2 「可用」查询的实现（两次 wrapper 查询，不写 SQL）

**`findUsableChatModel(Long modelId)`**：
1. `aiModelMapper.selectById(modelId)` → null / `deleted` / `status != enabled` / `type != chat` → 返回 `Optional.empty()`。
2. 取其 `providerId`，`modelProviderMapper.selectById(providerId)` → null / `status != enabled` → `Optional.empty()`。
3. 都通过 → 拼 `ModelView(id, name, "chat", provider.name)` 返回 `Optional.of(...)`。

**`listUsableChatModels(String type)`**（type 默认 `chat`，本轮只此一种有意义）：
1. `aiModelMapper.selectList(eq type, eq status=enabled)`（`@TableLogic` 自动加 `deleted=false`）。
2. 收集去重 `providerId` 集合；空集合直接返回空列表。
3. `modelProviderMapper.selectBatchIds(providerIds)`，过滤出 `status=enabled` 的，建 `id→name` 映射。
4. 过滤步骤 1 结果里供应商在映射中的模型，映射成 `List<ModelView>`，按模型 name 排序返回。

> 为何不 join：一期数据量小（供应商/模型各几条），两查可读、可单测（mock 两个 mapper）；手写 join SQL 留到 knowledge 轮统一引入（沿用 testing 约定）。真出现性能问题再换 join，不破坏 Facade 契约。

### 2.3 对外 API（成员族）

| 方法 | 路径 | 动作 | 谁能调 |
|---|---|---|---|
| GET | `/api/v1/provider/models?type=chat` | 列出可用模型（默认且本轮仅 chat） | 全员（任意登录用户） |

- 成员族路由 `/api/v1/provider/**`（**不是** admin 族 `/api/v1/admin/provider/**`）。需确认 `SecurityConfig` 的 admin 匹配器是 `/api/v1/admin/**`，故本路径仅 `authenticated()` 即可，无需 admin 角色。
- 返回 `Result<List<ModelView>>`；集合永不为 null；`id` 为 Long → 全局序列化为 string。
- `type` 入参可选、默认 `chat`；传其他值本轮返回空列表（不报错，为未来 embedding 预留）。

### 2.4 app 接 Facade（校验落地）

- `AppService` 注入 `ProviderFacade`（首次 import `provider::api`，边界已允许）。
- `create` / `update`：当 `req.modelId() != null` 时调 `providerFacade.findUsableChatModel(modelId)`，`isEmpty()` → 抛 `BizException(AppError.MODEL_NOT_USABLE)`。`modelId == null` 直接放行（选填，§决策2）。
- 校验在写库**之前**做；不放进与 LLM 无关的事务争议范围（这里只是一次内存内的跨模块只读调用，不违反「事务内禁外部 IO」——它不碰外部 IO）。

### 2.5 错误码（app 段 16xxx）

本轮在 app 段新增一个；provider 段（12xxx）本轮不新增（读接口无新增错误语义，空列表是正常返回）。

| code | HTTP | 含义 | 归属 |
|---|---|---|---|
| 16002 | 400 | 所选模型不存在或不可用（不存在/非 chat/已停用/供应商已停用） | app |

> 归属 app 而非 provider：错误发生在「app 引用了一个不可用模型」的业务语义里，由调用方模块定义错误码（api-standards 5.4）。Facade 只返回 `Optional`，不抛业务异常。

## 3. 前端设计

### 3.1 新增/修改文件
- `src/types/model.ts`：新增 `ModelOption`（`{ id: string; name: string; type: string; providerName: string }`，对齐后端 `ModelView`）。
- `src/api/provider.ts`（**新建**，成员侧 provider 资源，放 `api/` 根、不进 `admin/`）：`listChatModels()` → `request.get<ModelOption[]>('/provider/models', { params: { type: 'chat' } })`。
- `src/types/app.ts`：`AppForm` 增加 `modelId: string | null`（C1 解锁，app 第一轮注释「模型选择器推迟到②」本轮兑现）。
- `src/api/app.ts`：`createApp` 仍 `{ ...body, type: 'chat' }`，body 现含 `modelId`；`updateApp` 同样带 `modelId`。无需改函数签名（`AppForm` 扩字段即可）。
- `src/views/app/AppList.vue`：创建/编辑弹窗加模型选择器。

### 3.2 模型选择器（弹窗内）
- `el-select`（可清空 `clearable`），打开弹窗时调 `listChatModels()` 拉取选项；选项 label 用 `${providerName} / ${name}`，value 用 `id`。
- **选填**：不选 = `modelId: null`（草稿应用，对齐后端选填）。
- **编辑既有 app 的边角**（已知、本轮接受）：若该 app 选的模型后来被禁用/其供应商被禁用，选项列表里不再出现它，下拉显示为空；用户须重选一个可用模型或清空才能保存（因为后端 `modelId` 非空就校）。C1 不为此特殊处理，简单优先。
- 校验：`modelId` 选填，无前端必填校验。

## 4. 测试策略

后端连库测试（Testcontainers）仍推迟，全部 Mockito mock mapper / mock Facade。前端 vitest + TDD，先写失败测试。判定结果**不 grep `BUILD SUCCESS`**（`-q` 会静音），看测试计数/退出码。

### 4.1 后端
- `ModelQueryService` 单测（mock `AiModelMapper` + `ModelProviderMapper`）：
  - `findUsableChatModel`：正常可用 → 返回 ModelView（含 providerName）；模型不存在 → empty；模型停用 → empty；模型非 chat → empty；**供应商停用 → empty**（连带校验，§决策3）。
  - `listUsableChatModels`：过滤掉「供应商已停用」的模型；无可用 → 空列表（非 null）；providerName 正确映射。
- `ProviderFacadeImpl` 单测（mock `ModelQueryService`）：委托正确（薄封装，一两条即可）。
- `ModelQueryController` 测试（MockMvc，mock service）：路由通；`Result` 信封；`id` 序列化为 string；`type` 默认 chat 透传；集合非 null。
- `AppService` 单测**增量**（mock `ProviderFacade`）：
  - create：`modelId != null` 且 Facade 返回非空 → 落库成功；Facade 返回 empty → 16002。
  - create：`modelId == null` → **不调** Facade、放行（选填）。
  - update：同上两条。
  - 既有 app 测试需补 `ProviderFacade` mock 的默认桩（避免 NPE）。
- `ModularityTests` + ArchUnit 保持绿：首个 Facade/对外 DTO 落位即被边界规则覆盖。

### 4.2 前端
- `api/provider.ts` 测试（mock `@/api/request`）：`listChatModels` → `GET /provider/models` 带 `{ params: { type: 'chat' } }`。
- `AppList` 组件测试增量（mock `@/api/provider` + `@/api/app`）：
  - 打开创建弹窗 → 调 `listChatModels`、渲染选项。
  - 选中模型 → 提交 `createApp` 的 body 含 `modelId`。
  - 不选 → 提交 body `modelId: null`。

## 5. 端到端验证

1. 后端 `mvn test`（ModelQueryService/Facade/Controller/AppService + Modulith + ArchUnit）全绿。
2. 前端 `pnpm test` 全绿、`pnpm build` 通过。
3. 起前后端，走查：
   - 先在 admin 模型管理建至少 1 个 enabled 的 chat 模型（其供应商 enabled）。
   - app 创建弹窗的模型下拉能看到它；建一个选了模型的 app → 成功。
   - 不选模型建 app → 也成功（选填）。
   - admin 把该供应商禁用 → 重开 app 弹窗，下拉里该模型消失。
   - 直接构造请求传一个不存在/停用的 modelId（Postman）→ 16002/400。
4. Postman：扩 `hify-app` 集合或新建 `hify-provider` 成员侧用例，覆盖「列模型」+「app 带非法 modelId → 16002」。

## 6. 与 C2 的衔接（显式遗留）

| 缺口 | 补在哪 | 动作 |
|---|---|---|
| `getChatClient(modelId)` | C2 | `ProviderFacade` 加方法；`ChatClientFactory` 按 protocol 建 ChatModel + `decrypt` 注 Key；包 `ResilientChatModel`。 |
| 韧性（重试/熔断/信号量/超时） | C2 | `ResilientChatModel` + `ResilienceRegistry`（按 providerId 缓存）。 |
| `model_provider` 韧性 10 字段 | C2 | Flyway 迁移 `V8__alter_model_provider_resilience.sql`，均带默认值。 |
| embedding 模型列举/校验 | knowledge 轮 | `listUsableChatModels` 的 `type` 参数放开 embedding；Facade 加 `getEmbeddingModel`。 |
| 「对话应用必须有模型才能聊」 | conversation ③ | 入口处校验 app.modelId 非空且可用。 |
