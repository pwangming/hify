# conversation 多轮记忆 — 设计文档（④）

> 日期：2026-06-29
> 模块：`conversation`（前后端一体）
> 前置：③ 单轮聊天已合并 main（commit 03aa963）。本轮在其链路上叠加「历史进 prompt」+「多会话管理」。
> 范围口径：让该会话的历史消息进入 LLM prompt（滑动窗口），并让前端能管理多个会话（列表/新建/切换/刷新恢复）。

---

## 0. 决策摘要（6 点拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 历史注入策略 | **滑动窗口**：最近 N 轮进 prompt，N 外化配置，默认 **N=10** |
| 2 | ChatInvoker 改造 | **方案①**：窗口读并入 `openTurn` 事务，返回 `(cid, window)`；`invoke(client, systemPrompt, window)` 单列表 |
| 3 | 会话列表 UI 范围 | **A**：列表 + 新建 + 切换续聊 + 刷新恢复；不含删除/改名。列表**不分页**，返最近 N 条（cap 50） |
| 4 | 会话标题 | **A**：保持首条消息截断（已实现），前端 CSS 省略号显示；不动后端 |
| 5 | 前端 cid/状态 | **URL query 存 cid + 新建 `useConversationStore` Pinia + ChatView 左右两栏** |
| 6 | 不破契约（约束） | 17xxx 段不增不改；`ConversationService` 仍无 `@Transactional`，LLM 调用夹在两事务之间；跨模块 DTO 走 api 顶层包（本轮无新增跨模块 DTO） |

---

## 1. 配置（新增一项）

```yaml
hify:
  conversation:
    memory:
      window-rounds: 10   # 最近 N 轮（N 条 user + N 条 assistant）进 prompt
    list:
      recent-limit: 50    # 会话侧边栏返回的最近会话条数上限（不分页）
```

- 不硬编码；外化到 `application.yml`（CLAUDE.md 配置外化规则）。
- `window-rounds`：store 据此读最近 **`2 * N + 1`** 条消息（N 轮历史 + 刚落库的当前消息）。二期若想升级到 token 预算截断（决策点 1 的方案 C），只动 store 的窗口计算这一处。
- `recent-limit`：会话列表端点返回的最近会话条数上限（见第 4 节，本轮不分页）。

---

## 2. 后端数据流（改造点全在 conversation 模块内）

```
ConversationService.send  （无 @Transactional 编排）
  ① quotaGuard.check(userId, appId)                  不变（usage 轮才填实现）
  ② appFacade.findRunnableChatApp(appId)             不变（读，无事务）
  ③ TurnContext turn = store.openTurn(appId, conversationId, userId, content)
       事务A：建/取会话 + 落 user 消息 + 同事务内 select 窗口，返回 (cid, window)
  ④ ChatClient client = providerFacade.getChatClient(app.modelId())   不变
  ⑤ LlmReply reply = chatInvoker.invoke(client, app.systemPrompt(), turn.window())
       ← 事务外，喂历史窗口（窗口已在事务A读完返回，此刻无 DB 连接占用）
  ⑥ Message saved = store.appendAssistant(cid, ...)   事务B，不变
  返回 SendMessageResponse(cid, toView(saved))
```

**守约束 6（事务边界）**：
- `ConversationService` 类与方法均**无** `@Transactional`（编排层物理无事务）。
- 窗口读并入事务A，在 LLM 调用（⑤）之前已完成并返回 detached POJO 列表。
- LLM 调用仍夹在事务A与事务B之间、不被任何事务包裹 —— CLAUDE.md 硬规则 6 不破。

### 2.1 `ConversationStore.openTurn` 改造

- 返回类型从 `Long` 改为 `TurnContext(Long conversationId, List<Message> window)`（service 包内小 record，模块内）。
- 窗口查询：`where conversation_id = ? and deleted = false order by id desc limit (2N+1)`，取出后在 Java 反转为**时间正序**（命中 `message_conversation_idx (conversation_id, id)`）。
- 新会话场景：openTurn 建会话 + 落当前 user 消息后，窗口即 `[当前 user]`，行为与今日单轮一致（路径统一，无分支）。

### 2.2 不动的部分

- `appendAssistant`（事务B）、`assertOwned`、`titleFrom`、`history`/`listMessages` 维持原样。
- 数据库表结构不变（V10 建表时已按多轮就绪，无新 Flyway 脚本）。

---

## 3. ChatInvoker 改造（退化为纯映射 + 薄调用）

- 新签名：`LlmReply invoke(ChatClient client, String systemPrompt, List<Message> window)`。
- 抽出**纯映射方法** `List<org.springframework.ai.chat.messages.Message> toMessages(String systemPrompt, List<Message> window)`：
  - `systemPrompt` 有文本 → 列表首位 `SystemMessage(systemPrompt)`。
  - 按 window 时间正序：`role=user → UserMessage`，`role=assistant → AssistantMessage`。
  - 末尾即当前消息（window 最后一条）。
- 调用：`client.prompt().messages(toMessages(...)).call().chatResponse()`，usage 解析逻辑不变。
- **单一职责**：windowing（取多少）在 store；mapping（领域消息→Spring AI 消息）在 ChatInvoker；编排在 service。三者互不越界。

---

## 4. 新增端点：会话列表

**`GET /api/v1/conversation/conversations?appId=X`**（成员族路由，守约束 6）

- 语义：返回**本人在该 app 下**最近活跃的会话，按 `update_time desc`，**最多 `recent-limit`（默认 50）条**（命中 `conversation_user_idx (user_id, update_time desc) where deleted=false`）。
- **不分页**：会话侧边栏是有界列表，按"最近活跃置顶"（聊天应用标配 UX）排序。`update_time` 是会变的排序键，做游标分页本质不稳（同一会话翻页间会跳页/重复/漏掉），且与现有 `CursorResult`（按 `create_time+id` 的只追加流约定）不匹配——故本轮直接 cap 最近 N 条返回，不引游标。
  ```json
  { "code": 200, "data": [ {"id":"...", "title":"...", "updateTime":"..."} ], "traceId": "..." }
  ```
  - SQL：`where user_id=? and app_id=? and deleted=false order by update_time desc limit <recent-limit>`。
  - 已知取舍：超出 `recent-limit` 的更旧会话本轮看不到。单人单 app 会话数本就小，可接受；二期如需"加载更多"再按创建序游标补。
- 响应直接是 `Result<List<ConversationView>>`（与 `history` 端点同形，无分页包装）。
- 新 DTO `ConversationView(String id, String title, OffsetDateTime updateTime)`（id 序列化为 string）放 `conversation/dto`（模块内 DTO，非跨模块消费，**不涉及** api 顶层包问题）。
- **无新错误码**：appId 无匹配 → 返回空列表（非错误）；切到非本人会话仍由 `assertOwned` 抛 `10005 NOT_FOUND`。**17xxx 段不增不改**。

### 4.1 Controller

`ConversationController` 新增方法（`/api/v1/conversation` 前缀下，新增 `/conversations` 子路径）：
```java
@GetMapping("/conversations")  // 实际映射 /api/v1/conversation/conversations
Result<List<ConversationView>> listConversations(@RequestParam Long appId)
```
> 注：现有 `ConversationController` 的 `@RequestMapping` 为 `/api/v1/conversation/messages`。落地时将类级 `@RequestMapping` 调整为 `/api/v1/conversation`，方法级分别标注 `/messages`（发消息 POST、拉历史 GET）、`/conversations`（列表 GET），保持既有 messages 路由对外 URL 不变（= 不破契约）。

---

## 5. 前端（URL query 存 cid + Pinia store + 左右两栏）

### 5.1 路由
- `/apps/:appId/chat?c=<conversationId>`。
- 无 `c` → 新会话，空白页；首次发消息拿到 cid 后 `router.replace` 写入 `?c=<cid>`（不新增历史栈条目）。
- 刷新：读 `route.query.c` → 自动 `loadMessages(cid)` 恢复历史（GET 历史端点已存在）。
- 切换会话：点侧边栏某会话 → `router.push({ query: { c } })` → 监听 query 变化加载该会话。

### 5.2 状态：新增 `useConversationStore`（Pinia）
- state：`conversations: ConversationView[]`、`messages: MessageView[]`、`currentId: string | null`、`loadingList: boolean`、`sending: boolean`。
- actions：
  - `loadConversations(appId)` —— 拉侧边栏列表（不分页，返最近 N 条）。
  - `loadMessages(cid)` —— 切换/刷新时拉历史填 `messages`。
  - `send(appId, content)` —— 发消息（沿用 `sendMessage`），返回新 cid 时把 cid 写回并刷新列表。
  - `newConversation()` —— 清空 `currentId`/`messages`，进入空白新会话态。
- 理由：状态从单个 `messages` ref 增至「列表 + 当前消息 + 当前 id + 多个 loading」，且侧边栏与聊天区两组件共享，组件内 ref 会很快退化为 props/emit 面条。Pinia 是技术栈内标准解。

### 5.3 布局：ChatView 拆左右两栏
- 左 `ConversationSidebar.vue`（本页局部组件，**不进全局公共组件**，守 rule-of-three）：会话列表（标题 `text-overflow: ellipsis` 单行）+「新建会话」按钮 + 高亮当前会话。
- 右：沿用现有气泡列表 + 输入框（从 ChatView 现有模板平移）。

### 5.4 API / 类型
- `api/conversation.ts` 新增 `listConversations(appId)`（返回 `ConversationView[]`）。
- `types/conversation.ts` 新增 `ConversationView { id: string; title: string; updateTime: string }`。

---

## 6. 测试（TDD，先红后绿；测试放 `__tests__/`）

### 后端（mock + 边界，判结果看 Surefire `Tests run/Failures/Errors`）
- `ChatInvoker.toMessages` 映射单测：systemPrompt 存在/为空、user/assistant 角色正确、时间正序、末位为当前消息。
- `ConversationStoreTest`：openTurn 返回 `TurnContext`（cid + window）、窗口上限 `2N+1`、正序、`deleted=false` 过滤；会话列表查询（`update_time desc`、cap `recent-limit`、app 过滤、仅本人）。
- `ConversationServiceTest`：`send` 把 store 返回的 window 原样传给 `chatInvoker.invoke`（mock invoker，验参数）。
- `ConversationControllerTest`：`GET /conversations` 端点（appId 参数、空列表、`List<ConversationView>` 响应结构）；既有 messages 路由回归不变。
- `ModularityTests` / `LayerRulesTest`：继续绿（模块边界、DTO 不 import entity 等）。

### 前端（vitest）
- `useConversationStore` 单测：loadConversations / loadMessages / send（含新 cid 写回）/ newConversation。
- `ConversationSidebar` 组件测：渲染列表、标题省略、新建按钮 emit、当前会话高亮。
- `ChatView` 测：随 `route.query.c` 变化触发 loadMessages；新建流程（空白 → 首发 → cid 写回 URL）。
- `api/conversation` 新函数 `listConversations` 测（参数拼装）。

---

## 7. 范围外（本轮不做，留后续轮次）

- SSE 流式返回（下一轮）。
- 配额计量实现（usage 轮补 `QuotaGuard.check`）。
- 删除 / 改名会话（列表跑顺后单独做）。
- LLM 自动生成标题。
- 对外 `/v1/apps/{appKey}` API（API 接入轮）。
- E2E 测试基建（conversation 三块——单轮/多轮/流式齐后单独 brainstorm）。

---

## 8. 不破契约核对（约束 6 逐条）

| 契约 | 本轮处理 |
|---|---|
| 错误码 17xxx 段 | 不增不改；复用 17001 / 10005 / 10001 |
| `ConversationService` 无 `@Transactional` | 维持；窗口读并入事务A，LLM 调用仍在两事务之间 |
| 既有 messages 路由 | URL 不变（类级前缀调为 `/api/v1/conversation`，方法级 `/messages` 保持对外路径一致） |
| 跨模块 DTO 走 api 顶层包 | 本轮无新增跨模块 DTO（`ConversationView` 为模块内 DTO） |
| Long 序列化为 string | `ConversationView.id` 输出 string |
| 列表分页 | 会话侧边栏不分页、cap 最近 N 条（mutable 排序键不宜游标，详见第 4 节） |
| 数据库变更只新增 Flyway | 本轮无表结构变更，无新脚本 |
