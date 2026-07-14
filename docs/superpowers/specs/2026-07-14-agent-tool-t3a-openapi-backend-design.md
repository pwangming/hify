# T3a 设计：OpenAPI 自定义工具（后端）

> ② Agent/tool 模块第三子轮的**后端半**。前置轮：T1（tool 地基 + 2 内置工具 + 同步 function-calling
> 闭环）、T2（app_tool_rel + per-app 工具配置 + 前端 Agent 配置页/轨迹 + 流式 Agent）均已合并 main。
> 本轮只做后端；前端 admin 注册表页拆到 **T3b**。MCP 工具接入是独立的 **T4**。

## 1. 背景与目标

### ② 的 4 子轮拆分（回顾）
- **T1**（已完成）：`tool` 表（V23）+ `ToolRegistry` + `ToolFacade`（暴露 Spring AI `ToolCallback`）+ 2 内置工具（HTTP/代码）+ conversation 手动 tool-calling 同步循环。
- **T2**（已完成）：`app_tool_rel`（V24）+ Agent 配置 + 前端配置页/轨迹 + 流式 Agent（方案一）+ `GET /api/v1/tool/tools`（成员族只读）。
- **T3**（本轮，拆成两半）：OpenAPI 自定义工具。**T3a=后端**（本文档）、**T3b=前端 admin 注册表页**。
- **T4**（未来）：MCP 工具接入（`mcp_server` 表 + MCP 客户端 + 工具发现 source=mcp）。

### T3a 目标
让**管理员**粘贴一份 OpenAPI 文档，系统解析出其中的接口操作，注册进 tool 统一注册表
（`source=openapi`）。Agent 用的时候，注册表把这条注册**展开成多个工具**（每条操作一个），
对 Agent 与 conversation 编排层**完全透明**——复用 T2 已有的 `ToolFacade.getToolCallbacks(ids)`，
conversation/workflow **一行不用改**。

### 非目标（明确排除）
- 前端页面（T3b）。
- MCP 接入（T4）。
- 按 OpenAPI `securitySchemes` 分类型建模鉴权（YAGNI，见 §决策 4）。
- 支持内网/元数据地址的自定义 API——`OutboundHttpClient` 的 SSRF 防护按 deployment.md §5 一律禁内网，
  自定义工具**仅能调公网可达的 http/https**，这是既定安全姿态，本轮不改。
- 单独启停一条注册里的**某一个操作**（Model D 下整条注册一起开关，见 §决策 1）。

## 2. 关键决策（已与用户逐条拍板）

| # | 决策 | 结论 | 理由 |
|---|---|---|---|
| 1 | 操作→工具映射 | **Model D：1 注册 = tool 表 1 行，读时展开成 N 个 ToolCallback** | 零加表、编辑=改 1 行、per-app 选择=选整条注册（够用）、Agent 仍看到每条独立操作 |
| 2 | spec 解析 | **引入 `swagger-parser-v3` 依赖** | OpenAPI 官方事实标准；自动处理 `$ref` 解引用 / JSON+YAML / 合法性校验；手写解析是 bug 农场 |
| 3 | 凭据加密 cipher 位置 | **提到 `infra.crypto` 共享**（provider 与 tool 共用） | tool 模块依赖白名单为「无」，不能 import provider；加密是安全敏感代码，一份实现不分叉；第 2 个真实使用方，提取时机合理 |
| 4 | 鉴权方式 | **通用静态注入请求头**（key→value，value 加密存） | 内部/公网 API 90% 就是请求头带 key；一个机制覆盖 API-Key-header 与 Bearer（admin 自填 `Authorization: Bearer xxx`）；不解读 spec 的 `security` 段（真实 spec 常写不准） |
| 5 | 轮次粒度 | **拆 T3a 后端 + T3b 前端** | 后端重（swagger-parser + 执行 + cipher 提取 + admin CRUD），先用测试/curl 验通再做 UI，出错面小 |
| 6 | 主密钥 .env 变量名 | **保留 `HIFY_PROVIDER_MASTER_KEY` / 前缀 `hify.provider.crypto`** | 用户拍板：零 .env/yml 改动、零解密破坏风险。命名上略"provider 味"，加注释说明为历史兼容保留 |
| 7 | admin 列表含内置工具 | **含**（builtin + openapi 都列出，含已停用） | 一处看全所有工具、可停用内置；但内置**不可删改**（PUT/DELETE 拒绝） |

## 3. 数据模型（复用 tool 表，零加表）

`tool` 表（V23）已有 `source`（builtin/openapi/mcp）、`spec jsonb`、`owner_id` 三列——builtin 行这三个为空，
T3a 启用它们给 openapi 行用。**不新增迁移脚本，不改旧迁移。**

### 3.1 `Tool` 实体补映射
- 给 `Tool` 实体加 `spec` 字段（类型 `OpenApiToolSpec`，见下），挂 `OpenApiToolSpecTypeHandler`
  （仿现成 `app/config/AppConfigTypeHandler`，jsonb ↔ record 互转）。
- `ownerId` 字段已存在，openapi 行填**创建的管理员** id（`CurrentUser.userId()`）；builtin 行恒空。

### 3.2 `spec jsonb` 形状（自包含执行描述）
```jsonc
{
  "baseUrl": "https://api.example.com/v1",          // 从 spec 的 servers[0].url 取
  "authHeaders": [                                   // 静态注入的鉴权头，value 加密存
    { "name": "X-API-Key", "valueEnc": "<base64(AES-GCM 密文)>" }
  ],
  "operations": [
    {
      "opName": "getPetById",                        // 取 operationId；缺失则由 method+path 生成；已 sanitize
      "method": "GET",
      "pathTemplate": "/pets/{petId}",
      "description": "根据 id 查宠物",                // 取 operation 的 summary/description，给模型看
      "inputSchema": "{\"type\":\"object\", ...}",   // path/query/body 参数合并成的 JSON Schema 字符串
      "parameters": [                                 // 执行时拼请求用
        { "name": "petId", "in": "path", "required": true }
      ]
    }
  ],
  "rawSpec": "<管理员粘贴的原文；供 T3b 编辑回显；绝不含任何凭据>"
}
```
- **凭据只存密文（`valueEnc`），任何响应 DTO 都不回传明文**（对齐 provider 对 api key 的处理）。
- `parameters[].in` 取值 `path` / `query` / `header`；`body` 参数由 `requestBody` 展开进 inputSchema，
  执行时整体作 JSON body 发送（见 §5 执行）。

## 4. Admin CRUD 接口

路由 `/api/v1/admin/tool/tools`（带模块段 `tool`），由 `SecurityConfig` 的 `hasRole("ADMIN")`
统一拦截 `/api/v1/admin/**`，控制器类上无需再加注解。协议层无业务逻辑、无 `@Transactional`、不注入 Mapper。
一期不用 PATCH：启停走动作子资源。

| 方法 | 路径 | 作用 | 返回 |
|---|---|---|---|
| POST | `/tools` | 注册：解析 spec + 加密鉴权头 + 插行（source=openapi，owner=当前 admin） | `ToolAdminResponse` |
| GET | `/tools` | 列全部工具（builtin + openapi，含已停用），admin 注册表视图 | `List<ToolAdminResponse>` |
| GET | `/tools/{id}` | 详情：解析出的操作列表 + 鉴权头**名字**（不回明文值），供 T3b 编辑表单 | `ToolAdminDetailResponse` |
| PUT | `/tools/{id}` | 全量更新（重新解析 spec、替换鉴权头）——**仅 openapi** | `ToolAdminResponse` |
| DELETE | `/tools/{id}` | 软删——**仅 openapi** | `Void` |
| POST | `/tools/{id}/enable` | 启用 | `Void` |
| POST | `/tools/{id}/disable` | 停用 | `Void` |

### 4.1 请求/响应 DTO（camelCase，Long 序列化为字符串，集合永不为 null）
- `CreateToolRequest { name, description, specText, authHeaders: List<AuthHeaderInput> }`
- `UpdateToolRequest`（同 Create，全量替换语义）
- `AuthHeaderInput { name, value }`（`value` 是明文，服务端立即加密）
- `ToolAdminResponse { id, name, description, source, enabled, operationCount, ownerId, createTime, updateTime }`
  - `operationCount`：openapi 行 = 操作数；builtin 行 = `null`（前端据 source 隐藏该列）。
- `ToolAdminDetailResponse { id, name, description, source, enabled, baseUrl,
  operations: List<OperationView>, authHeaderNames: List<String>, rawSpec }`
  - `OperationView { opName, method, pathTemplate, description }`（**不含** inputSchema/parameters 细节，UI 够用即可）。
  - `authHeaderNames`：只回头名，**永不回明文值**。

### 4.2 写侧规则
- **仅 openapi 可 PUT/DELETE**；对 builtin 行调 PUT/DELETE → `BizException(CommonError.PARAM_INVALID, "内置工具不可修改/删除")`。
- `enable`/`disable` 允许作用于任意行（admin 可停用内置工具）。
- 重名（`name` 与现存未删工具冲突）→ `BizException(CommonError.CONFLICT, ...)`（10006）。
  依赖 V23 已有的 `tool_name_uq` 部分唯一索引；服务层先查重给友好消息，DB 唯一约束兜底。
- 不存在/已删 → `CommonError.NOT_FOUND`（10005）。
- `owner_id` 记创建 admin，但**改删只校验 `isAdmin()`**（admin 专属面，全 admin 平权）；owner 仅作审计。

## 5. 工具执行（对 Agent 透明）

### 5.1 `ToolRegistry` 加 openapi 分支
现有 `getToolCallbacks(ids)` 只查 `source=builtin`，**改为两种 source 都处理**：
1. 按 id 查 enabled 行（不再限定 source）。
2. builtin 行 → 建 `BuiltinToolCallback`（不变）。
3. openapi 行 → 解密 `authHeaders` → 把 `operations` 展开成 N 个 `OpenApiToolCallback`，
   工具名 = `sanitize(注册名) + "__" + opName`（加注册名前缀防跨注册撞名；注册名本身由 `tool_name_uq` 保证唯一）。
4. `getBuiltinToolCallbacks()`（全 builtin）保留不动。`filterEnabledIds(ids)` 已与 source 无关，无需改。

如此 T2 的 per-app 选择自动生效：app 勾选一条 openapi 注册（1 个 tool id）→ `getToolCallbacks([id])`
展开出该 API 的全部操作 → Agent 看到 N 个工具。**conversation/workflow 零改动。**

`ToolRegistry` 现需注入 `ObjectMapper`、`SecretCipher`、`OutboundHttpClient`（构造 OpenApiToolCallback 用）。

### 5.2 `OpenApiSpecParser`
`specText`（JSON 或 YAML）→ swagger-parser 解析（自动解 `$ref`）→ 抽出：
- `baseUrl`：`servers[0].url`（缺失或相对路径 → 解析失败或要求 admin 补全，抛 13001 附友好消息）。
- `operations[]`：遍历 paths × methods，每个 operation 取 method/path/operationId/summary，
  合并 path+query+header+requestBody 参数成一份 `inputSchema`（JSON Schema 字符串）。
- 解析异常、无任何操作、无 baseUrl → `BizException(ToolError.SPEC_PARSE_FAILED, 具体原因)`（13001/400）。

### 5.3 `OpenApiToolCallback`（实现 Spring AI `ToolCallback`，仿 `BuiltinToolCallback`）
持有**一条操作** + `baseUrl` + **解密后**的鉴权头 + `OutboundHttpClient` + `ObjectMapper`。
`call(argsJson)`：
1. 解析模型给的 `argsJson`。
2. 按 `parameters` 拼请求：path 参数替换进 `pathTemplate`；query 参数拼到 URL；header 参数进请求头；
   其余（body）字段整体作 JSON body。
3. 并入解密后的鉴权头。
4. 走 `OutboundHttpClient.send(method, url, headers, body)`（**自带 SSRF/双超时/禁重定向/响应截断**）。
5. 返回 `"HTTP " + status + "\n" + body`。
6. **任何失败（参数非法 JSON、缺必填、SSRF 拒绝、网络错误）都返回错误文本、绝不抛**
   （与 `BuiltinTool` 同契约，不中断 Agent 循环）。

### 5.4 服务层组织
- `ToolAdminService`：create/list/get/update/delete/enable/disable。持有 `OpenApiSpecParser`、`SecretCipher`、
  `ToolMapper`。create/update 内：解析 specText → 加密 authHeaders → 组装 `OpenApiToolSpec` → 落库。
- `AdminToolController`：协议层，controller 经 `CurrentUserHolder` 取 `CurrentUser` 传入 service（本层不读安全上下文，便于单测）。
- 现成 `ToolService.listEnabled()`（成员族 `GET /api/v1/tool/tools`）**不改**：openapi 注册作为一个 enabled 行
  自然出现在成员配置页的可选项里，`ToolView` 已带 `source`。

## 6. 错误码（尽量复用通用段）

- 复用通用段：`10001` 参数校验失败、`10005` 资源不存在、`10006` 资源冲突（重名）。
- **只新增一个 tool 段码**（放 `tool/constant/ToolError`，实现 `ErrorCode` 接口）：
  - `13001 / 400` — OpenAPI 文档解析失败（前端可能单独展示解析错误，值得独立成码）。
- `ToolError` 是 tool 模块首个错误码枚举（T1/T2 全走通用段与 conversation 段），本轮建。

## 7. 凭据加密：cipher 提到 infra 共享

- 新建 `infra.crypto.SecretCipher`（= 现 `provider.service.ApiKeyCipher` **原样搬过来**，AES-256-GCM，
  `base64(IV‖密文‖tag)`，SHA-256 派生 32 字节密钥）。
- 新建 `infra.crypto.CryptoProperties`（`@ConfigurationProperties(prefix = "hify.provider.crypto")`——
  **前缀保持不变**，历史兼容，加类注释说明）。application.yml、.env、.env.example **全不动**。
- provider 改用共享 `SecretCipher`：删 `provider.service.ApiKeyCipher` 与 `provider.config.ProviderCryptoProperties`，
  注入点换成 `infra.crypto.SecretCipher`（`ProviderConfig` 的 `@EnableConfigurationProperties` 相应改指向）。
- provider→infra 依赖合法（infra 是共享地基）；provider 现有 api key 加解密行为字节不变（同算法、同主密钥、同默认值）。
- 迁移安全：**主密钥默认值字符串一字不改**，本地 `hify` 库里 provider 已加密的 api key 仍能解开。

## 8. 测试

后端 JUnit（vitest 是 T3b 前端的事）：
- `OpenApiSpecParser`：正常 spec → 操作数/baseUrl/inputSchema 正确；含 `$ref` 的 spec 能解引用；YAML 格式；
  非法/空/无 baseUrl → 13001。
- `OpenApiToolCallback`：path 替换、query 拼接、body 组装、鉴权头注入——用 **stub 的 `OutboundHttpClient`**
  断言拼出的 method/url/headers/body；失败路径返回错误文本不抛。
- `ToolRegistry`：openapi 行展开成 N 个 callback、名字前缀防撞、builtin+openapi 混合。
- `ToolAdminService`：create（解析+加密+落库）、list（含 builtin）、get（不回明文）、update、delete、
  builtin 拒绝 PUT/DELETE、重名冲突。
- `SecretCipher`：加解密往返（搬迁后测试跟到 infra）。
- `ModularityTests` / `LayerRules`（ArchUnit）仍绿——特别核对 tool 无对 provider 的依赖、DTO 不 import entity。

## 9. 落地顺序（详见 plan）

大致 Task 顺序（TDD，Codex 外部执行 + 终审）：
1. cipher 提到 infra + provider 迁移（先做，隔离风险；跑通 provider 现有测试）。
2. swagger-parser 依赖引入 + `OpenApiSpecParser` + 测试。
3. `OpenApiToolSpec` record + TypeHandler + `Tool` 实体补 spec 映射。
4. `OpenApiToolCallback` + 测试。
5. `ToolRegistry` openapi 展开分支 + `getToolCallbacks` 改造 + 测试。
6. `ToolError`（13001）+ `ToolAdminService` CRUD + 测试。
7. `AdminToolController` + DTO + 集成校验。
8. 回归（全量 `mvn clean test` 含 Modularity/ArchUnit）+ self-check 入档。

## 10. 风险与留账

- **swagger-parser 依赖体积**：连带 swagger-core/snakeyaml。可接受（换来 `$ref`/YAML 的正确性）。
- **baseUrl 相对路径**：部分 spec 的 servers 用相对 url 或缺失 → 本轮要求 admin 提供的 spec 带绝对 baseUrl，
  否则 13001 提示补全（不猜测拼接）。
- **自定义 API 仅公网**：SSRF 防护禁内网，团队内网 API 调不通——既定安全姿态，若成需求另立轮次评估（deployment.md §5）。
- **T3b 依赖的详情契约**：`ToolAdminDetailResponse` 的 operations/authHeaderNames 形状本轮定死，T3b 照此对接。
