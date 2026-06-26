# ③ conversation 单轮聊天 · 设计文档

> 日期：2026-06-26
> 范围：第一个把 identity → app → provider → conversation 串起来、真正"能聊天"的功能，含前端聊天页。
> 前置：identity 登录闭环、app 第一轮、provider 全线（R1/B/C1/C2，`ProviderFacade.getChatClient` 已就绪）均已合并 main。

## 0. 本轮拍板结论（决策记录）

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | 返回方式（最大分叉） | **一次性 `.call()`**，SSE 留下一轮 | 复用 C2 现有"非流式"韧性；最小、最好测；落库/会话入口/prompt 组装两条路一致，几乎不返工。流式韧性（首 token 超时/重试）C2 未wired，SSE 那轮一并补。 |
| 2 | 建表 / "单轮"边界 | conversation + message **两表建全（多轮就绪）**，本轮**只跑单轮** | 符合 data-model.md；多轮 = 下一轮纯新增"加载历史进 prompt"，不改表不返工。 |
| 3 | 配额检查位置 | **先留单一调用点**（空实现 + TODO 指向未来 `UsageFacade`），不建 usage 表 | usage 模块当前为空；整模块设计值得单独 brainstorm，不提前塞进本轮。 |
| 4 | 前端范围 | **极简单会话页**（无会话列表/切换/删除） | 最贴合单轮；会话列表与多轮记忆同轮做。 |
| 5 | prompt 组装 + 事务规则（#2） | systemPrompt + 当前 user 消息；LLM 调用在事务外（见 §1） | CLAUDE.md 硬规则 6：`@Transactional` 内禁 LLM/外部 IO。 |
| 6 | 路由族归属 | **控制台成员族** `/api/v1/conversation/**`（按当前用户过滤） | 本轮是控制台内"试聊/调试"；对外 `/v1/apps/{appKey}/**`（需 app_api_key）留给"应用对外 API"专门那轮。 |

## 1. 总体架构与数据流

一次"发消息"分三段，**LLM 调用夹在两个事务中间、自己不在任何事务里**（CLAUDE.md 硬规则 6 / llm-resilience.md §1）：

```
事务A（落库）  →  调 LLM（无事务）  →  事务B（落库）
```

- **事务A**：校验应用可对话（经 `AppFacade`）→ 解析会话（无 `conversationId` 则新建一条 conversation）→ 落库这条 user 消息。提交。
- **调 LLM**：`ProviderFacade.getChatClient(app.modelId)` 拿到自带非流式韧性的 `ChatClient`；`prompt().system(systemPrompt).user(content).call().chatResponse()` 取回复正文 + token 用量。**不在事务里。**
- **事务B**：落库 assistant 消息（含 promptTokens/completionTokens）+ 刷新会话 updateTime。提交。

**事务边界落地（关键工程细节）**：Spring `@Transactional` 靠代理生效，**同类内 A 方法调 B 方法不走代理**、事务失效。故拆两个 bean：

- `ConversationService`（**编排，不带事务**）—— 中间那段 LLM 调用就发生在它这层，确保在事务外。
- `ConversationStore`（**带 `@Transactional`** 的落库/读取）—— 事务A、事务B 各是它上面一个独立事务方法。

两者都在 `service/` 包内，符合 ArchUnit"事务只在 service 层"。

**失败语义（本轮已知取舍）**：LLM 调用失败时，事务A 的 user 消息已落库（像 ChatGPT 先显示你的话再报错），assistant 消息不写，接口返回 provider 抛的错误码（12003/12004 等）透传。前端给"重新发送"。本轮**不做**自动重试/回滚补偿。

**"单轮"约束**：组 prompt 时**只放 systemPrompt + 当前 user 消息，不读历史**。systemPrompt 为 null/空白时跳过 system 段。多轮 = 下一轮纯新增"加载历史进 prompt"。

## 2. 数据库（Flyway `V10__create_conversation_message.sql`）

两张表，按多轮就绪建（data-model.md §1 已规定）；跨模块只存 id、不建外键（§3 规则 1）；模块内 `message.conversation_id` 建 FK 享级联删。字段、类型、注释遵循 database-standards.md（建表前现场重读）。

**`conversation`**
- `id`（BaseEntity）、`app_id`（弱引用 app）、`user_id`（弱引用 sys_user，会话归属人）、`title`（取首条 user 消息截断生成）、`create_time`、`update_time`、`deleted`

**`message`**
- `id`（BaseEntity）、`conversation_id`（模块内 FK → conversation，ON DELETE CASCADE）、`role`（check：`user` | `assistant`）、`content`、`prompt_tokens`、`completion_tokens`、`tool_calls`（jsonb，**本轮恒空**，预留 Agent 工具轨迹）、`create_time`、`update_time`、`deleted`

**索引**：`message(conversation_id, id)`（按会话取消息）；`conversation(user_id, update_time desc)`（个人会话列表，留给多轮轮，但索引现在就建）。

## 3. 模块契约

- **新增 `AppFacade`（app::api）** —— 本轮唯一新跨模块契约。conversation 要读应用的 modelId/systemPrompt/状态，必须经 app 门面（禁碰 app 的 service/entity）。
  - 签名：`Optional<AppRuntimeView> findRunnableChatApp(Long appId)`
  - `AppRuntimeView`（api/dto，record）：`Long appId, Long modelId, AppConfig config`（含 systemPrompt）。
  - 返回空的情形：应用不存在 / 非对话型 / 已停用 / 未绑定 modelId —— 由调用方据空抛 `17001`。
  - **注意**：`AppRuntimeView` 禁 import app 的 entity（ArchUnit `dto-no-entity-import`），投影在 app 的 service 私有方法里做。
- **复用 `ProviderFacade.getChatClient(modelId)`**（C2 已建），不改 provider。
- **不新建 `ConversationFacade`**：本轮无任何模块调用 conversation（workflow 本轮不动）。按"Facade 最多一个、按需才建"先不建，避免空壳。
- conversation 的 `package-info` `allowedDependencies` 本轮收窄为 `{ "app::api", "provider::api", "common", "infra" }`（usage 留到配额那轮再加）。

## 4. 后端 API（控制台成员族 `/api/v1/conversation/**`）

按当前用户过滤（会话是个人数据，data-model.md §3 / api-standards.md §6）。设计/新增前现场重读 api-standards.md 逐条核对。

- **`POST /api/v1/conversation/messages`** —— 发消息。
  - body：`{ appId, conversationId?(可空), content }`；`conversationId` 空则新建会话。
  - 返回：`Result<{ conversationId, message: { id, role:"assistant", content, promptTokens, completionTokens, createTime } }>`（Long 一律序列化为 string）。
- **`GET /api/v1/conversation/messages?conversationId=xxx`** —— 加载某会话历史（刷新页面用）。
  - 按当前用户过滤；非本人会话返回 `404`。
  - 返回：`Result<{ list: [...] }>`（空会话 = 成功 + `list: []`）。

**错误码（conversation = 17xxx 段，在 `conversation/constant/ConversationError.java` 登记）**：
- 新增 **`17001 APP_NOT_RUNNABLE (400)`** —— "应用未绑定可用模型或已停用，无法发起对话"。
- 复用通用段：会话/应用不存在 → `10005`；访问他人会话 → `10004`；参数校验失败 → `10001`。
- 模型不可用/熔断/繁忙：由 provider 抛的 `12002/12003/12004` **透传**，conversation 不重复发明。

## 5. 前端（极简单会话页，frontend-standards.md）

- **`src/api/conversation.ts`**：`sendMessage(appId, conversationId, content)`、`getMessages(conversationId)`；复用现有 `request.ts` 封装与类型约定。
- **`src/views/conversation/ChatView.vue`**：路由 `/apps/:appId/chat`。
  - 上方消息列表（user / assistant 气泡），下方输入框 + 发送按钮。
  - 发送中：禁用输入 + loading；失败：toast/inline 错误（展示后端 message）。
  - 组件内本地状态，**不引 Pinia**（单页足够，YAGNI）。
- **入口**：`AppList.vue` 每行加"试聊"动作 → 跳 `ChatView`。
- **路由/菜单**：在 `router/index.ts` 登记路由；菜单按需（试聊是从应用列表进，不一定进主菜单）。
- 无会话列表侧栏、无会话切换/删除（留给多轮轮）。

## 6. 测试策略（TDD；判 mvn 结果不 grep `BUILD SUCCESS`）

- **后端**：
  - `ConversationService` 用 **mock**：mock `AppFacade`、`ProviderFacade.getChatClient`（返回桩 `ChatClient`）单测——三段时序、单轮 prompt 只含 system+当前消息、错误码透传（应用不可用→17001、模型不可用→12002 透传、provider 故障→12003/12004 透传）、配额调用点被命中。
  - `ConversationStore` 落库/读取暂按既有"mock 约定"（连库 Testcontainers 仍按记忆 [[testing-defer-testcontainers]] 延后到 knowledge 手写 SQL 那轮）。
  - **ArchUnit / Modulith 边界测试必过**：新 `AppFacade`、conversation 依赖白名单、`AppRuntimeView` 不 import entity、事务只在 service 层。
- **前端**：**vitest + TDD**，先写失败测试，放 `__tests__/`：
  - api 层（请求拼装 / 响应转换）；
  - `ChatView` 组件（发送 → 渲染气泡、loading 态、错误提示）。
- 每铺完一步给自检，追加到 **`docs/self-check-conversation.md`**（沿用 C2 在 `docs/self-check-c2.md` 的习惯）。

## 7. 配额调用点（单一锚点，本轮空实现）

在 `ConversationService` 发消息入口、事务A 之前，固定一处：

```java
// TODO(usage 轮): UsageFacade.checkQuota(currentUserId, appId) —— 配额耗尽抛 14001/429。
// 本轮 usage 模块为空，先放行。
```

未来接 usage 只需把这一行换成真调用，不回头改 conversation 控制流。

## 8. 明确范围外（防蔓延）

SSE 流式、多轮记忆（历史回喂）、会话列表/切换/删除、usage 配额计量与落库、对外 `/v1/apps/{appKey}` API、Agent/工具调用、知识库检索注入 prompt、E2E 基建（本轮做完再单独 brainstorm 评估）。
