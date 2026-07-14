# Agent/tool 模块（②）T2：app_tool_rel + Agent 配置 + 前端配置页/轨迹展示 + 流式 Agent

> 设计日期：2026-07-14　状态：已定稿，待写实现计划
> 前置阅读：`docs/architecture/code-organization.md`、`api-standards.md`、`database-standards.md`、`frontend-standards.md`、`llm-resilience.md`
> 前置轮次：T1（已合并 push，见 `2026-07-13-agent-tool-t1-foundation-design.md`）

## 0. 上下文：② 的 4 子轮拆分与本轮定位

② 是多轮子项目。T1 已交付 tool 地基（tool 表 V23 + 2 内置工具 + 注册表 + `ToolFacade`）与 conversation 的
**同步** function-calling 循环（`AgentChatService`）。本轮 T2 让 Agent **在界面上真正可用**：

| 子轮 | 交付 | 状态 |
|---|---|---|
| T1 | tool 表 + 注册表 + ToolFacade + 2 内置工具 + Agent 同步循环 | ✅ 已 push |
| **T2（本文）** | app_tool_rel + Agent 配置（开关+选工具）+ 前端配置页 + 聊天区工具轨迹 + **流式 Agent** | 本轮 |
| T3 | OpenAPI 自定义工具 + admin 注册 CRUD + 前端 admin 工具页 | T1 |
| T4 | MCP 工具接入 | T1 |

本轮不新增应用类型：**Agent 是对话型应用的一种配置形态**（守 CLAUDE.md「仅 2 种应用类型」，延续 T1 拍板）。

## 1. T2 目标与验收

**目标**：应用创建者能在配置页勾选「启用 Agent 工具调用」并选择启用哪些工具；被配成 Agent 的应用能在
**聊天区（走 SSE 流式）**正常对话，工具调用轨迹实时展示并落库、刷新历史仍可见。

**验收**：
1. 打开某 chat 应用编辑框 → 开启「启用 Agent 工具调用」→ 工具多选框出现 → 勾选 http_request / code_executor → 保存。
2. 进该应用「试聊」，提一个需要外呼/算数的问题。**聊天区不再报 17002**，能正常出答案。
3. 聊天过程中：每调用一个工具，聊天区出现该工具的调用轨迹卡片（工具名/参数/结果）；最终答案以打字机式渲染。
4. 刷新页面 / 切回该会话：历史消息仍带工具轨迹卡片（读 `message.tool_calls`）。
5. 只勾选一个工具时，模型只能调该工具；把工具全部取消勾选并保存后再聊，模型无工具可用（退化为普通作答）。
6. 关闭「启用 Agent 工具调用」后再聊：行为回到普通聊天（含知识检索、逐字流式），完全不受影响。
7. `mvn clean test` 全绿（含 ModularityTests / ArchUnit）；前端 vitest 全绿。

## 2. 已拍板的大决策：流式 Agent 用「方案一」

| 决策 | 结论 | 理由 |
|---|---|---|
| 流式 Agent 编排 | **方案一：复用 T1 同步循环 + 工具事件** | 复用已终审的 `AgentChatService` 与 `callModel` 测试缝，无反应式递归，实现/测试最简、风险最低 |

**方案一机制**：`sendStream` 里去掉 17002 守卫；Agent 应用改走新的流式编排 —— 用 `Flux.create` +
`Schedulers.boundedElastic()` 在工作线程上跑 **T1 那个同步循环**，给循环加一个「每调完一个工具回调一次」的钩子，
每个工具调完往 SSE sink 推一个 `tool_call` 事件；循环出最终答案后，把答案作为**一条 `message` 增量**推出，
再落库（复用 `store.appendAssistant(..., toolCalls)`，事务内发一次 `TokenUsedEvent`），发 `done`。

**为何 UX 可接受**：前端本就有「逐字节奏渲染」的 drain 定时器（`stores/conversation.ts`），所以后端把最终答案
一次性发来，客户端**照样打字机式逐字显示**。方案一与全流式（方案二）的真实差距，仅是「最终答案第一个字出现的时机」
稍晚，内部团队够用。方案二（每轮 `.stream()` + 分片聚合工具调用 + 反应式递归）复杂且难测，**推迟**，将来想升级
可单独替换本轮编排、不改契约。

## 3. 小决策

1. **`tool_call` 事件 = 工具调完发一次**（带 `{toolName, args, result, ok}`）。**不加**「工具开始调用」的 running
   事件（拍板：保持方案一最简，调完即显已够用；将来要实时转圈再加）。
2. **直播轨迹与历史轨迹同结构、同渲染器**：`tool_call` 事件推的元素结构与落库 `message.tool_calls`（`{name,args,result}`）
   一致，前端把它 append 到当前助手消息的 `toolCalls` 数组；直播 append 出来的数组与历史接口返回的数组一模一样，
   **前端用同一个折叠卡片渲染**，零重复。（`ok` 仅用于失败态的可选样式，非落库字段。）
3. **toolIds 校验**：镜像 datasets —— `AppService` 保存前调 `ToolFacade.validateToolIds(toolIds)` 保证勾的都是
   **现存且 enabled** 的工具，否则报 `CommonError.PARAM_INVALID`（**不新增错误码**）。运行时 `getToolCallbacks` 再对
   未知/停用 id 做二次跳过兜底（防"配置后工具被停用"）。
4. **`agentEnabled` 开关 vs `toolIds` 选择**：开关（布尔）继续在 `AppConfig` jsonb；选择（多对多）在 `app_tool_rel`。
   与「datasets 走关系表、systemPrompt 走 jsonb」同套路。开关开但一个工具没勾是合法配置（退化为普通作答，见验收 5）。
5. **Agent 路径仍不做知识检索**（sources 恒空），延续 T1。工具与知识检索的融合不在本轮。
6. **同步 `/messages` 的 Agent 路径保留不动**：流式是**新增**（去守卫 + 新编排），不删既有同步闭环
   （它是 T1 已验证的路径，也是将来对外 API 的基础）。
7. **工具列表接口归属**：成员族只读 `GET /api/v1/tool/tools`（tool 模块段），返回已启用工具。工具注册表的
   增删改（admin）是 T3/T4，本轮不做。

## 4. 已核对的现状事实（决定改动面）

- **最新 Flyway = V23**（`V23__create_tool.sql`）→ 本轮新迁移 = **V24**。
- **`app_dataset_rel` 建表模板已在**（`V18`）：`id / app_id(references app) / dataset_id / deleted / create_time / update_time`
  + 部分唯一索引 `(app_id, dataset_id) where deleted=false` + 从表列索引。`app_tool_rel` 照抄。
- **`AppService` 的 datasets 读写手法完整可镜像**：`replaceDatasetBindings`（全量替换：软删旧+插新去重）、
  `datasetIdsOf`（单应用）、`datasetIdsByApp`（列表页防 N+1 一页一查）、`toResponse(..., datasetIds)`；
  `create/update` 均 `@Transactional`，先 `validateDatasetIds` 再写关系。toolIds 全部照此镜像。
- **`Message` 实体已有 `List<MessageToolCall> toolCalls`**（T1，V10 列 + `MessageToolCallsTypeHandler`）——
  `MessageView` 只需加字段并在 `toView` 映射 `m.getToolCalls()`，**无需新迁移**。
- **`AppConfig(String systemPrompt, boolean agentEnabled)` 已含 agentEnabled**（T1）——本轮 config 侧零改动。
- **`AppRuntimeView` 已含 `agentEnabled`**；本轮加 `toolIds`。`ConversationService.send`（同步）当前对 Agent 传的是
  `toolFacade.getBuiltinToolCallbacks()`（全量）——本轮改为 `toolFacade.getToolCallbacks(app.toolIds())`（per-app）。
- **SSE 事件已成体系**（`StreamEvent` sealed：Meta/Sources/Delta/Done；`StreamPayloads`；controller `toSse`）——
  本轮加 `StreamEvent.ToolCall` 与 `StreamPayloads.ToolCall` 各一，controller `toSse` 加一分支映射到 `event: tool_call`。
- **api-standards 已预留 `event: tool_call`**（§3.3，例子 `{"toolName","status"}`）——本轮把该例子更新为最终形状
  `{"toolName","args","result","ok"}`（未发布契约，允许调整）。
- **tool 模块错误码段 = 13xxx**；本轮不新增错误码（校验复用 10xxx 通用段）。
- **前端聊天区已有「参考来源」折叠卡片**（`ChatView.vue` 的 `el-collapse`）——工具轨迹卡片复用该样式与手法。
- **前端聊天只走 SSE**（`stores/conversation.ts` → `useChatStream`）——故本轮流式 Agent 是让 Agent 应用在界面
  可用的**唯一**接线点。

## 5. 数据与配置

### 5.1 新表 `app_tool_rel`（Flyway V24，app 模块）
照抄 `app_dataset_rel`：
```
id          bigint identity pk
app_id      bigint not null references app(id)
tool_id     bigint not null           -- 跨模块弱引用 tool(id)，不建外键
deleted     boolean not null default false
create_time / update_time timestamptz
unique index (app_id, tool_id) where deleted = false
index (tool_id)
```
`AppToolRel` 实体 + `AppToolRelMapper`，照 `AppDatasetRel` / `AppDatasetRelMapper`。

### 5.2 DTO / 视图加 toolIds
- `CreateAppRequest` / `UpdateAppRequest`：加 `@Size(max=20) List<Long> toolIds`（上限给足内部用；datasets 是 10）。
- `AppResponse`：加 `List<Long> toolIds`（永不为 null，空即 `[]`）。
- `AppRuntimeView`（跨模块 api）：加 `List<Long> toolIds`。
- `AppService`：镜像 datasets 的 `replaceToolBindings` / `toolIdsOf` / `toolIdsByApp`；`create`/`update` 里
  `validateToolIds` → 写关系 → `toResponse(..., datasetIds, toolIds)`。仅 chat 类型处理 toolIds（workflow 无）。
- `AppFacadeImpl.findRunnableChatApp`：像读 datasetIds 一样读 toolIds 装进 `AppRuntimeView`。

## 6. 后端接口与编排

### 6.1 工具列表接口（配置页选项来源）
- `GET /api/v1/tool/tools` → `Result<List<ToolView>>`，`ToolView{id(Long→string), name, description, source}`，
  仅返回 `enabled=true` 的工具，按 name 升序。
- 新增 `tool` 模块 `ToolController` + service 读方法（tool 模块此前无 controller）。成员族任意登录用户可读。

### 6.2 `ToolFacade` 扩展 + 校验
- `List<ToolCallback> getToolCallbacks(Collection<Long> toolIds)`：按 id 取 enabled 工具的 ToolCallback，
  未知/停用 id 跳过（`ToolRegistry` 加按 id 过滤的构建方法，复用 T1 builtin 绑定逻辑）。空集合 → 空列表。
- `void validateToolIds(Collection<Long> toolIds)`：任一 id 非「现存且 enabled」→ 抛 `PARAM_INVALID`。空集合直接通过。

### 6.3 流式 Agent 编排（方案一）
- `ConversationService.sendStream`：删去 `if (app.agentEnabled()) throw AGENT_STREAM_UNSUPPORTED`；
  改为 `if (app.agentEnabled()) return sendStreamAgent(...)` 分流。`AGENT_STREAM_UNSUPPORTED` 常量作废（本轮删除，
  未对外发布）。
- `sendStreamAgent(app, cid/turn, ...)`：
  1. 发 `Meta(cid)`（延续现有：openTurn 后立即给前端会话 id）。
  2. `Flux.create(sink -> {...}).subscribeOn(boundedElastic())`：在回调里跑
     `agentChatService.run(chatClient, systemPrompt, window, callbacks, onToolCall)`，其中 `onToolCall` 为
     `Consumer<StreamEvent.ToolCall>`，循环每调完一个工具就 `sink.next(toolCall)`。
  3. `run` 返回 `AgentReply` 后：`sink.next(Delta(reply.content()))` → `store.appendAssistant(cid, content,
     promptTokens, completionTokens, userId, appId, modelId, List.of()/*sources空*/, toolCalls)`（事务B，内发
     `TokenUsedEvent`）→ `sink.next(Done(cid, messageId, tokens))` → `sink.complete()`。
  4. 出错：`store.cleanupFailedTurn(...)`（清孤儿，语义同现有流式）后 `sink.error(e)`。
- `AgentChatService`：**加一个带 `Consumer<StreamEvent.ToolCall> onToolCall` 的 `run` 重载**；原无参 `run`
  委托给它传 no-op consumer（同步路径行为不变）。循环体在构造完 `MessageToolCall trace` 元素后，多调一次
  `onToolCall.accept(new StreamEvent.ToolCall(name, args, result, ok))`。`callModel` 测试缝保持。
  （`AgentChatService` 与 `StreamEvent` 同在 `conversation.service` 包，可直接引用，无跨模块问题。）

### 6.4 SSE 事件
- `StreamEvent` sealed 加 `record ToolCall(String toolName, String args, String result, boolean ok)`。
- `StreamPayloads` 加 `record ToolCall(String toolName, String args, String result, boolean ok)`。
- `ConversationController.toSse` 加分支：`StreamEvent.ToolCall → sse("tool_call", payload)`。
- 心跳/终态判定不变（`takeUntil` 仍看 done/error；tool_call 非终态）。

### 6.5 历史带轨迹
- `MessageView` 加 `List<MessageToolCall> toolCalls`（永不为 null，空即 `[]`）。
- `ConversationService.toView`：映射 `m.getToolCalls()`（null → `List.of()`）。同步 `send` 的响应与 GET 历史都带上。

## 7. 前端（Vue3 + TS + Element Plus + vitest TDD）

### 7.1 配置页（`views/app/AppList.vue` 对话框）
- chat 表单加「启用 Agent 工具调用」`el-switch`（绑 `form.config.agentEnabled`）。
- 开关为 true 时显示工具多选 `el-select multiple`（绑 `form.toolIds`），选项来自新 `api/tool.ts` 的 `listTools()`。
- 已勾但现已停用/不存在的工具 id → 显示为禁用项「已停用的工具」（镜像 datasets 的「已删除的知识库」手法）。
- 打开对话框时加载工具选项（同 `loadDatasetOptions`）。`openEdit` 回填 `form.toolIds = [...row.toolIds]`、
  `form.config.agentEnabled = row.config.agentEnabled ?? false`；`openCreate` 置默认（关、空）。
- 类型：`types/app.ts` 的 `App`/`AppForm` 加 `toolIds: string[]`，`config` 加 `agentEnabled: boolean`；
  新增 `types/tool.ts` 的 `ToolOption{id,name,description,source}`。

### 7.2 聊天区轨迹（`views/conversation/ChatView.vue`）
- assistant 气泡下渲染工具轨迹折叠卡片，**复用「参考来源」的 `el-collapse` 样式/结构**：标题「工具调用 (N)」，
  每条展示工具名 + 参数 + 结果（失败态 `ok=false` 可加红点/标记）。仅当 `m.toolCalls?.length` 时显示。
- `types/conversation.ts`：`MessageView` 加 `toolCalls?: MessageToolCall[]`（`{name,args,result}`）。

### 7.3 流式接线
- `composables/useChatStream.ts`：`dispatch` 加 `else if (event === 'tool_call') h.onToolCall?.(payload)`；
  `ChatStreamHandlers` 加可选 `onToolCall?(tc: {toolName,args,result,ok})`。
- `stores/conversation.ts`：`send` 里 handler 加 `onToolCall`，把事件 append 到当前助手消息的 `toolCalls` 数组
  （占位助手消息初始化 `toolCalls: []`）。直播 append 的元素 `{name:toolName, args, result}` 与历史同结构 → 同渲染。

### 7.4 API 层
- 新增 `api/tool.ts`：`listTools()` → `GET /tool/tools`。
- `api/app.ts` 无需改（create/update 整体发 form，已含新字段）。

## 8. 测试与边界

**后端**（沿用 T1「真实 IO 不单测」，`callModel` 测试缝）：
- `ToolMapperIT` 补 app_tool_rel（或独立）—— 关系读写。
- `getToolCallbacks(ids)` 过滤（含未知/停用 id 跳过）、`validateToolIds` 抛错。
- `AppService` create/update toolIds 读写 + 校验（镜像现有 datasets 测试）。
- `AgentChatServiceTest` 补带 `onToolCall` 的 `run` 重载：脚本化 ChatResponse 验证每工具触发一次事件、事件内容正确。
- `ConversationController.toSse` 的 tool_call 分支（可在现有 controller 测里补）。
- `MessageView.toolCalls` 映射（history 带轨迹）。

**前端**（vitest + TDD，新代码先写失败测试，测试放 `__tests__/`）：
- `AppList.spec`：agent 开关切换显隐工具多选、勾选进表单、提交带 toolIds/agentEnabled、停用工具禁用项回填。
- `ChatView.spec`：有 toolCalls 时渲染折叠卡片、无则不渲染。
- store / `useChatStream` 的 tool_call 分发与 append。

**本轮不做**：admin 工具注册表 CRUD（T3/T4）、OpenAPI/MCP 工具（T3/T4）、工具级参数/凭证配置、
「工具开始调用」running 事件、Agent 路径知识检索、方案二逐字流式、删除同步 Agent 路径。

## 9. 改动文件清单（约）

**后端**
- 新增：`V24__create_app_tool_rel.sql`、`app/entity/AppToolRel.java`、`app/mapper/AppToolRelMapper.java`、
  `tool/controller/ToolController.java`、`tool/dto/ToolView.java`（或 api 下按用途）、`tool` 读 service 方法。
- 改：`app/api/dto` 无（AppConfig 不动）；`app/api/AppRuntimeView.java`(+toolIds)、`app/api/AppFacade`? 无新方法、
  `app/service/AppFacadeImpl.java`、`app/service/AppService.java`、`app/dto/{Create,Update}AppRequest.java`、
  `app/dto/AppResponse.java`；`tool/api/ToolFacade.java`(+getToolCallbacks/+validateToolIds)、
  `tool/service/{ToolFacadeImpl,ToolRegistry}.java`；`conversation/service/{ConversationService,AgentChatService,
  StreamEvent}.java`、`conversation/dto/{MessageView,StreamPayloads}.java`、`conversation/controller/ConversationController.java`、
  `conversation/constant/ConversationError.java`(删 AGENT_STREAM_UNSUPPORTED)。
- 文档：`docs/architecture/api-standards.md`（tool_call 例子形状）、`data-model.md`（app_tool_rel 落地状态）。

**前端**
- 新增：`api/tool.ts`、`types/tool.ts`、相应 `__tests__`。
- 改：`views/app/AppList.vue`、`types/app.ts`、`views/conversation/ChatView.vue`、`types/conversation.ts`、
  `composables/useChatStream.ts`、`stores/conversation.ts` 及各自 `__tests__`。
</content>
</invoke>
