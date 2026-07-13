# Agent/tool 模块（②）T1：tool 地基 + 2 内置工具 + function-calling 同步闭环

> 设计日期：2026-07-13　状态：已定稿，待写实现计划
> 前置阅读：`docs/architecture/code-organization.md`、`api-standards.md`、`database-standards.md`、`llm-resilience.md`

## 0. 上下文：② Agent/tool 的子项目拆分

② 是多轮子项目（tool 模块此前全空、conversation 无任何 Agent/function-calling 地基，全绿地）。
拍板拆成 4 个有序子轮，后轮只往前轮的注册表/循环里加东西，不改已发布契约：

| 子轮 | 交付 | 依赖 |
|---|---|---|
| **T1（本文）** | tool 表 + 注册表 + ToolFacade + 2 内置工具（HTTP/代码）+ conversation Agent 同步循环 | — |
| T2 | app_tool_rel + Agent 配置 jsonb（选模型/提示词/工具）+ 前端 Agent 配置页 + 聊天区工具轨迹展示 + **流式 Agent** | T1 |
| T3 | OpenAPI 自定义工具（解析 spec→注册表 source=openapi）+ admin 注册 CRUD + 前端 admin 工具注册表页 | T1 |
| T4 | MCP 工具接入（mcp_server 表 + MCP 客户端 + 工具发现 source=mcp）+ admin MCP 配置 | T1 |

顺序 T1 → T2 → T3 → T4。T3/T4 都只依赖 T1 的注册表；T2 让产品先端到端可用。
「工作流注册为 Agent 工具」不在 ② 范围——它依赖 ①（应用对外 API），① 已拍板推迟到有外部调用方；
将来经 tool 模块绕行接入，属独立小轮（禁止 conversation→workflow 直接依赖，见 code-organization §4.7）。

## 1. T1 目标与验收

**目标**：在一次对话里，被标记为 Agent 的应用能让模型真实调用 HTTP / 代码两个内置工具，
用工具结果继续作答，工具调用轨迹落库。这是所有工具来源（OpenAPI/MCP）与前端展示的地基。

**验收（同步端点，curl/API）**：
1. 用一条 SQL 把某测试 chat 应用的 `config.agentEnabled` 翻成 `true`（沿用 admin 账号 seed-验收模式）。
2. 向该应用同步发消息，提问需要外呼的问题（如"用 HTTP 工具取某公开 JSON 接口并总结"）。
3. 观察：模型发起工具调用 → 服务端执行 → 回填 → 模型给出基于工具结果的终答。
4. 查 `message.tool_calls`：assistant 消息的该列记录了 `[{name,args,result}, ...]` 轨迹。
5. 代码工具同理：让 Agent 写一段 Python 计算并返回结果。
6. 非 agent 应用（agentEnabled=false）行为完全不变（回归现有单轮/多轮聊天）。

## 2. 四个已拍板的大决策

| 决策 | 结论 | 理由 |
|---|---|---|
| T1 工具启用范围 | **全启用内置工具**，per-app 选择留 T2 | T1 聚焦最硬的 function-calling 闭环，不被应用建模/前端拖大 |
| T1 是否流式 | **仅同步闭环**，流式 Agent 留 T2 | 多轮工具循环与 SSE 交织复杂，与前端展示一起放 T2 更自然 |
| tool→conversation 接口 | **方案 A：ToolFacade 返回 Spring AI `ToolCallback`** | 与 provider 暴露 ChatClient 对称；执行细节封在 tool 模块；例外已有 provider 先例 |
| Agent 触发方式 | **方案 2a：`AppConfig` 加 `agentEnabled` 布尔** | Agent 是对话型应用的配置形态（非新应用类型，守 CLAUDE.md「仅 2 种应用类型」）；不影响既有应用 |

## 3. 三个小决策

1. **agentEnabled 应用命中 SSE 端点**：显式拒绝（返 **17xxx**「Agent 应用暂不支持流式，见 T2」——
   守卫在 conversation 端抛，用 conversation 段，不是 tool 的 13xxx）。
   T1 流式未做，静默降级成无工具聊天会误导；一个 `if` 守卫即可。
2. **ToolInvokedEvent / 工具调用日志**：推迟。轨迹已进 message jsonb，无独立工具日志表；
   T1 只发计费关键的 `TokenUsedEvent`（每轮模型调用一次）。
3. **代码工具 Agent 接口**：只暴露 `code`（Python 字符串），不暴露 `inputs` map；
   Agent 直接把值写进代码，`SandboxClient.run(code, Map.of())` 传空 map。

## 4. 已核对的现状事实（决定改动面）

- **tool 模块错误码段 = 13xxx**（api-standards §5.2 已登记）。优先复用 10xxx 通用段，13xxx 只放特有语义。
- **`message.tool_calls` jsonb 列 DB 里已存在**（V10，默认 `'[]'`，实体注释「留待 Agent 轨迹」）——
  T1 只需给实体加字段 + TypeHandler 映射，**无需新迁移改这列**。
- **`app.config` jsonb 已在**（V7，默认 `'{}'`）——`agentEnabled` 塞进 jsonb，**零迁移**；
  `AppConfig` record 注释已声明「新增字段向后兼容」。
- **`tool` 表尚未建**（app_tool_rel/mcp_server 也未建）——T1 新建 `tool` 表（V23），
  app_tool_rel 留 T2、mcp_server 留 T4。
- 可复用地基就绪：`infra.outbound.OutboundHttpClient.send(method,url,headers,body)`（自带 SSRF、双超时、
  Redirect.NEVER、响应体截断）；`infra.outbound.SandboxClient.run(code, inputs)`（自带并发信号量、双超时、
  输出大小上限）；`provider.api.ProviderFacade.getChatClient(modelId)`（带韧性的 ChatClient）。
- conversation 现有链路：`ConversationService` 经 `AppFacade.findRunnableChatApp(appId)` 取
  `AppRuntimeView(appId, modelId, systemPrompt, datasetIds)`，经 `ProviderFacade.getChatClient` 取 ChatClient，
  `ChatInvoker.invoke(chatClient, systemPrompt, window)` 做同步调用并抽 usage。

## 5. 组件设计

### 5.1 tool 模块（从空到有）

遵循 code-organization §2 的固定模块内结构。

**数据层**
- `tool` 表（V23 迁移，遵循 database-standards 建表规范）。列：

  | 列 | 类型 | 说明 |
  |---|---|---|
  | id, create_time, update_time, deleted | — | BaseEntity 标准列 |
  | name | text, 唯一（未删范围） | 模型寻址工具的稳定标识，也是内置执行器的绑定键；须满足 Spring AI 工具命名（如 `http_request`） |
  | description | text | 给模型看的工具说明（影响模型是否/如何调用） |
  | source | text，check in ('builtin','openapi','mcp') | 工具来源 |
  | enabled | boolean, default true | 是否可用 |
  | spec | jsonb, null | openapi/mcp 的定义载体；builtin 恒 null |
  | owner_id | bigint, null | 团队共享制；builtin 为系统工具，null |

  播种 2 行内置工具：`http_request`、`code_executor`（description 写清入参语义）。
- `Tool` 实体（`@TableName("tool")`，继承 BaseEntity；spec 用 TypeHandler 映射 jsonb，本轮内置恒 null 可后置）。
- `ToolMapper extends BaseMapper<Tool>`。

**内置工具执行器**（`tool/service/builtin/`）
- 小内部契约 `BuiltinTool`：`name()` / `description()` / 输入 JSON schema / `execute(argsJson) -> String`。
  （仅 tool 模块内部使用，不进 api。）
- `HttpRequestTool implements BuiltinTool`：
  - 入参 schema：`method`(必填，白名单 GET/POST/PUT/DELETE/PATCH 由本工具校验)、`url`(必填)、
    `headers`(可选 object)、`body`(可选 string)。
  - 执行：调 `OutboundHttpClient.send(...)`；返回「状态码 + 响应体（已截断）」的字符串。
  - 失败（网络/SSRF/参数）：捕获 `BizException`，把错误文本作为工具结果返回给模型（**不中断整轮**）。
- `CodeExecutorTool implements BuiltinTool`：
  - 入参 schema：`code`(必填，Python 源码字符串)。
  - 执行：`SandboxClient.run(code, Map.of())`；返回沙箱 stdout/返回值/错误的字符串表示。
  - 沙箱业务失败（ok:false）与网络/超时失败：一律转成字符串结果回给模型，不中断整轮。

**注册表（可扩展的缝）**
- `ToolRegistry`（service）：读 DB 中 enabled 的工具行；对 `source=builtin` 的行，按 `name` 绑定对应
  `BuiltinTool` 执行器；产出 Spring AI `ToolCallback` 列表。
  - Spring AI 适配：用一个薄 `ToolCallback` 实现（`BuiltinToolCallback`）包住 `ToolDefinition`
    （name+description 来自 DB 行、inputSchema 来自执行器）与 `BuiltinTool` 执行器；
    `call(argsJson)` 即 `BuiltinTool.execute(argsJson)`。直接实现接口避开 `FunctionToolCallback` 的泛型入参反序列化。
  - T1 只处理 builtin；`source=openapi/mcp` 的行 T3/T4 再补构造分支（本轮无此类行）。

**对外门面（api）**
- `ToolFacade`（接口）+ `ToolFacadeImpl`（service）：
  - `List<ToolCallback> getBuiltinToolCallbacks()` —— 返回全部 enabled 的内置工具 ToolCallback。
  - **治理例外**：Facade 签名默认禁用 Spring AI 类型，本轮把 provider 的例外扩展到 tool
    （拍板结论，落地后补进 `code-organization.md` §2「api/」例外条目与 §4.2）。
  - `tool` 的 `package-info.java` 保持 `allowedDependencies` 现状（依赖 common、infra；不依赖其他业务模块）。
  - T2 追加 `getToolCallbacks(Collection<Long> toolIds)` 做 per-app 选择（本轮不做）。

### 5.2 conversation 模块（新增 Agent 同步循环）

- `AppRuntimeView` 增 `agentEnabled` 字段（见 5.3），conversation 据此分流。
- Agent 同步编排（新 `AgentChatService`，或 `ConversationService` 内分支——实现计划再定粒度）：
  1. 取 `AppRuntimeView`；`agentEnabled=false` → 走**现有**普通聊天路径（行为不变）。
  2. `agentEnabled=true` → 取 `ChatClient`（provider）+ `List<ToolCallback>`（`ToolFacade.getBuiltinToolCallbacks()`）。
  3. **手动 tool-calling 循环**（上限 `hify.agent.max-tool-iterations`，默认 5）：
     - 调 ChatClient，挂 tools，`internalToolExecutionEnabled(false)`（Spring AI 不自动执行，把控制权交回我们）。
     - 响应无工具调用 → 即终答，break。
     - 有工具调用 → 逐个：按 name 匹配 ToolCallback → 执行 → 记录 `(name, args, result)` 进轨迹 →
       追加工具结果消息进对话窗口。
     - **累计**各轮 prompt/completion tokens（不在循环里发事件）。
  4. 超上限 → 返回最后一次模型文本，并附「已达工具调用步数上限」提示。
  5. 落库 assistant 消息：`content`（终答文本）+ `tool_calls`（轨迹 jsonb 数组）；
     在该落库事务（`appendAssistant`，事务B）内发**一次** `TokenUsedEvent`，携带累计总量。
     （事件必须在事务方法内发布，AFTER_COMMIT 监听才触发；编排层无事务，不能在循环里发——code-organization §4.3。
     总量=各轮之和，对 usage 每日聚合计费等价准确。）
- **为什么手动循环而非 Spring AI 自动执行**：需要拿到每一轮 usage 累计计费（自动执行只回最后一轮 usage，
  会漏计中间轮 token）、需要捕获每次工具调用轨迹落 message jsonb。手动循环是 function-calling 的标准做法。
- **端点接入**：Agent 路径只接**同步**发消息端点。SSE 端点若命中 `agentEnabled=true` 应用 →
  返 **17xxx**（conversation 段）「Agent 应用暂不支持流式，见 T2」（小决策 1）。
- `Message` 实体：新增 `tool_calls` 字段 + TypeHandler（比照现有 `sources` 的 `MessageSourcesTypeHandler`），
  `@TableName(autoResultMap=true)` 已具备。轨迹元素结构（本模块 DTO/内部类型）：`{name, args, result}`，
  非 agent 消息恒 `[]`（DB 默认已保证）。

### 5.3 app 模块（最小改动）

- `AppConfig` record 增 `agentEnabled`（boolean，缺省 false）。落 `app.config` jsonb，向后兼容，零迁移。
- `AppRuntimeView` record 增 `agentEnabled`；app 服务在装配 view 时把 `config.agentEnabled` 透传。
- `findRunnableChatApp` 语义不变（仍要求 type=chat + enabled + 有 modelId）——Agent 是 chat 的一种配置，非新类型。

### 5.4 配置

- `application.yml`：`hify.agent.max-tool-iterations: 5`（外化，遵循「所有阈值外化」）。
- 工具执行超时/并发：复用现有 `OutboundProperties` / `SandboxProperties`，不新增。

## 6. 数据流（同步 Agent 一轮）

```
用户消息
  → ConversationService 分流(agentEnabled) → AgentChatService
    → 循环(≤maxToolIterations):
        ChatClient.call(messages, tools, internalToolExecutionEnabled=false)
        ├─ 无工具调用 → 终答, break
        └─ 有工具调用 → 逐个 ToolCallback.execute → 记录轨迹 → 追加工具消息 → 继续
        每轮: 发 TokenUsedEvent
    → 落 assistant 消息(content + tool_calls jsonb)
    → 返回终答
```

## 7. 错误处理

| 情形 | 处理 |
|---|---|
| 工具执行失败（网络/SSRF/沙箱/参数） | 执行器捕获 BizException，错误文本作为工具结果回给模型，循环继续（受步数上限约束），不中断整轮 |
| 超工具调用步数上限 | 返回最后一次模型文本 + 「已达步数上限」提示，正常落库 |
| 模型不可用 / 供应商停用 | 复用现有 `12002/503`（经 ProviderFacade.getChatClient） |
| agentEnabled 应用命中 SSE 端点 | 返 17xxx（conversation 段）「Agent 应用暂不支持流式」 |
| 非 agent 应用 | 走现有聊天路径，行为不变 |
| 配额 | 入口 `QuotaGuard.checkQuota` 一次（现有）；循环内每轮发 TokenUsedEvent；步数上限防跑飞刷账单 |

事务纪律（code-organization §4.5/§4.6）：循环内是模型调用 + 工具外呼（外部 IO），**全程无事务**；
仅「落 assistant 消息」这一步进事务。计费/日志走事件，不同步写 usage。

## 8. 测试

遵循 testing-standards（避免冗余/遗漏/假测试）与既有 memory 口径。

- **单元测试**：
  - `ToolRegistry`：给定 DB 中 2 行 builtin，产出 2 个 ToolCallback，name/description 正确、能匹配执行器。
  - 内置工具入参解析：HTTP 工具对 method 白名单校验、缺 url 报错文本；代码工具取 `code`。
  - 循环控制逻辑：mock ChatClient 先返「工具调用」再返「终答」，断言轨迹记录、TokenUsedEvent 发次数、
    超上限行为。（ChatClient 较难 mock，实现计划评估用薄封装隔离，比照 ChatInvoker.toMessages 的可测切分。）
- **Testcontainers**（沿用 knowledge 轮已趟平环境）：tool 表 CRUD + 注册表从真库读 2 行内置工具。
- **手动 self-check（curl）**：真实模型的 tool-calling 端到端（外部 IO 不做单测，比照 `ChatInvoker.invoke` 先例）。
  按 §1 验收 6 步跑，结果入档 `docs/self-check.md`。

## 9. T1 明确不做（YAGNI 边界）

per-app 工具选择 / `app_tool_rel`（T2）· 流式 Agent（T2）· 前端（T2）· OpenAPI 工具（T3）·
MCP 工具 / `mcp_server` 表（T4）· admin 工具 CRUD（T3）· ToolInvokedEvent / 独立工具日志表（推迟）·
工作流注册为 Agent 工具（依赖 ①，独立小轮）。

## 10. 落地前须补的文档（拍板结论回写，避免同一问题问两次）

- `code-organization.md`：把「Facade 签名可用 Spring AI 类型」的例外由 provider 扩展到 **tool**
  （§2「api/」例外条目 + §4.2 Facade 签名约束处各加一句）。
- `data-model.md`：`tool` 表落地后，确认清单与实际列一致（本轮先建最小列集）。
