# 多轮记忆自检（conversation ④ 轮）

> 执行日期：2026-06-29
> 分支：`feat/conversation-multi-turn`（基线 `0cca521`）
> 后端回归：`Tests run: 256, Failures: 0, Errors: 0`（含 ModularityTests / LayerRulesTest）
> 前端回归：`Test Files 23 passed, Tests 134 passed`；`vue-tsc --noEmit` 无报错；`vite build` 成功
> 执行方式：Subagent-Driven（7 任务，每任务 implementer + task-reviewer 双 verdict，2 处 Important 当轮捕获并修复，整支 opus review = Ready to merge）

---

## 一、决策落地（6 决策点）

| # | 决策 | 落地位置 | 行为 |
|---|---|---|---|
| 1 | 滑动窗口 N=10 | `ConversationProperties`（`config/`）`hify.conversation.memory.window-rounds`；`ConversationStore#readWindow` | 读最近 `2N+1=21` 条（N 轮历史 + 当前消息），`order by id desc limit 21` 后反转为时间正序 |
| 2 | openTurn 返窗口 + invoke 喂窗口 | `ConversationStore#openTurn`→`TurnContext(cid, window)`；`ChatInvoker#invoke(client, systemPrompt, window)` | 窗口读并入事务A；invoke 经 `toMessages` 映射为 Spring AI 消息列表 `.messages(...).call()` |
| 3 | 会话列表 A（列表/新建/切换/恢复，不分页 cap50） | `GET /api/v1/conversation/conversations?appId=`；`ConversationStore#listConversations` | `update_time desc`、`limit recent-limit(默认50)`、本人+本 app；前端侧边栏 + 切换 + 刷新恢复 |
| 4 | 标题保持截断 | `ConversationStore#titleFrom`（沿用）+ 前端 CSS 省略号 | 后端无改动；`ConversationSidebar` `text-overflow: ellipsis` 单行 |
| 5 | URL query 存 cid + Pinia + 两栏 | `ChatView.vue`（`?c=<cid>`）+ `useConversationStore` + `ConversationSidebar.vue` | watch queryCid 载历史/新建；首发后 `router.replace` 写回；watch 守卫 `cid===currentId` 防重复拉取 |
| 6 | 不破契约（约束） | 见下「契约核对」 | 17xxx 不增改；编排无事务；messages URL 不变；无新 Flyway |

---

## 二、`@Transactional` 内无 LLM 调用（硬规则 6）

- `ConversationService`：类与方法均无 `@Transactional`（编排层物理无事务）。
- `ConversationStore`：所有 `@Transactional` 收口于此——`openTurn`（事务A：落 user + 读窗口）、`appendAssistant`（事务B）。
- 调用顺序：`store.openTurn`（事务A，返回窗口）→ `providerFacade.getChatClient` → `chatInvoker.invoke`（**事务外**，喂窗口）→ `store.appendAssistant`（事务B）。
- 窗口读在事务A内完成并返回 detached POJO 列表，LLM IO 发生时无 DB 连接被占用。整支 opus review 已逐条核实通过。

---

## 三、契约核对（约束 6）

| 契约 | 处理 |
|---|---|
| 错误码 17xxx | 不增不改；复用 17001 / 10005 / 10001 |
| 编排无 `@Transactional` | 维持；窗口读并入事务A，LLM 调用仍夹在两事务之间 |
| 既有 messages 路由 | 类级前缀改 `/api/v1/conversation` + 方法级 `/messages`，对外 URL 字节级不变（既有控制器测试不改仍绿） |
| 跨模块 DTO | 本轮无新增跨模块 DTO；`ConversationView` 为模块内 DTO（`conversation/dto`），ModularityTests/LayerRulesTest 绿 |
| Long→string | `ConversationView.id` 输出 string（控制器测试断言 `"100"`） |
| 列表分页 | 不分页、cap50、`update_time desc`（mutable 排序键不宜游标，详见 spec §4） |
| 数据库变更 | 本轮无表结构变更、无新 Flyway（V10 建表时已多轮就绪） |

---

## 四、错误码

| 码 | 来源 | 语义 |
|---|---|---|
| `17001 APP_NOT_RUNNABLE` | `ConversationError`（沿用） | 应用不可对话 |
| `10005 NOT_FOUND` | `CommonError` | 会话不存在或非本人（`assertOwned`）——切换/拉历史/拉列表均经此 |
| `10001 VALIDATION` | `@Valid` + 全局处理器 | 请求字段校验失败 |
| `12002/12003/12004` | provider 模块透传 | 模型不可用/熔断/繁忙 |

> 会话列表端点 appId 无匹配 → 返回**空列表**（非错误）。

---

## 五、过程质量（当轮捕获并修复）

1. **Task 5 Important**：`loadMessages` 在 `getMessages` 抛错时 `currentId` 已改但 `messages` 仍旧 → 改为「成功后才更新两者」+ 补失败路径测试（commit `f971178`）。
2. **Task 7 Important**：新会话首发后 `router.replace` 写回 URL 触发 watch 重复 `loadMessages` → watch 加 `cid===currentId` 守卫 + reactive routeQuery 测试（commit `78489c1`）。
3. **整支 review Minor**：更正 `ConversationService`/`ChatInvoker` 陈旧类注释（commit `e15c303`）。

---

## 六、范围外 / fast-follow（本轮不做）

- **[fast-follow，建议]** `ChatView` 的 `appId` 在 setup 捕获一次 + store 全局单例：若 SPA 内从 `/apps/7/chat` 导航到 `/apps/9/chat`（复用组件实例），会向旧 app 发消息。当前产品流仅从应用列表「试聊」进入（每次全新挂载），**此路径不可达**；一行可修（`:key` 重挂载或 watch appId 重置）。
- 发送失败后乐观用户气泡残留（单轮即有，内部场景接受）。
- 切换到已删除会话的 404 静默（无删除 UI，暂不可达）。
- SSE 流式（下一轮）、配额计量实现（usage 轮）、删除/改名会话、LLM 生成标题、E2E 基建（三块齐后单独 brainstorm）。

---

## 七、真实模型多轮手验（活模型才能证，自动化用 mock 替过）

**状态：✅ 已通过（用户 2026-06-29 手动验证）** —— 连发「我叫小明…」→「我叫什么名字?」第二条答出「小明」（记忆生效），新建会话不串记忆，刷新可恢复。手验中暴露的「超时 + 重复会话」问题已查根因并修复（见 §八）。本轮已合并 main（merge commit `22c03ad`）并推送远程。

**复现步骤（供后续回归参考）：**

**复现步骤：**

1. 登录管理后台（admin 已 seed）→ 确保有一个可用 chat 模型 + 绑定该模型的对话型应用（可设 systemPrompt）。
2. 应用列表点「试聊」进入 `/apps/{appId}/chat`。
3. **多轮记忆验证**：连发两条——
   - 第 1 条：`我叫小明，记住我的名字`
   - 第 2 条：`我叫什么名字？`
   - 预期：第 2 条回复出现「小明」=> 历史已进 prompt（窗口生效）。
4. **会话隔离验证**：点「新建会话」，发 `我叫什么名字？`
   - 预期：模型答不知道 => 不同会话不串记忆。
5. **刷新恢复验证**：在某会话中 `F5` 刷新（URL 带 `?c=<cid>`）
   - 预期：历史消息自动恢复显示。
6. **侧边栏验证**：左侧出现历史会话列表，点击可切换续聊。

curl 多轮验证（同一 conversationId 连发两次）：
```bash
# 第 1 条（新会话，conversationId 不传）
curl -s -X POST http://localhost:8080/api/v1/conversation/messages \
  -H "Authorization: Bearer <member-token>" -H "Content-Type: application/json" \
  -d '{"appId":"<chatAppId>","content":"我叫小明，记住我的名字"}'
# 记下返回的 data.conversationId，作为第 2 条的 conversationId
curl -s -X POST http://localhost:8080/api/v1/conversation/messages \
  -H "Authorization: Bearer <member-token>" -H "Content-Type: application/json" \
  -d '{"appId":"<chatAppId>","conversationId":"<cid>","content":"我叫什么名字？"}'
# 预期：第 2 条 data.message.content 含「小明」
```

完成后将结果（成功/失败 + 关键输出）追加本节并提交。

---

## 八、手验发现的问题与修复（2026-06-29）

**现象**：用户首次发消息「超时」，重试成功，但侧边栏多出一条重复会话；续聊同样先超时再成功。

**根因（systematic-debugging 确证）**：
- 前端 axios 全局超时 **30s**（`VITE_API_TIMEOUT`），后端单次 LLM 预算 **120s**（provider `response_timeout_sec` 默认值，V8/V9）。真实模型 >30s 时前端先 `abort` → 用户见「超时」；后端不知客户端已断，继续把调用跑完并落库。
- `ConversationService.send` 先 `openTurn`（事务A：建会话+落 user 消息，毫秒级提交）再调 LLM。前端超时时会话已落库；`store.send` 抛错未拿到 cid → `currentId` 仍 null → 用户重发被当「新会话」→ 后端再建一条 → **侧边栏重复**。
- 两缺陷均**上几轮既有**（30s 全局超时、openTurn 先落库），本轮新增侧边栏使「孤儿会话」首次显形。

**修复（仅 D1，用户拍板）**：commit `22b874d` —— 发消息改用独立长超时 `config.chatApiTimeout`（默认 **125s** ≥ 后端 120s 预算，`VITE_CHAT_API_TIMEOUT` 可覆盖），前端不再提前 abort，「超时 + 重试建重复会话」一并消除。TDD 红→绿，全前端 134 绿 + typecheck + build。

**残留（用户选择本轮不做）**：
- D2：若后端**真实失败**（模型 120s 内真错），`openTurn` 已落的会话仍成孤儿——根治需「LLM 成功后才落库」或后端幂等，留后续。
- 长等待 UX（2 分钟转圈）的正解是 **SSE 流式**（下一轮）。
- 历史遗留的重复/孤儿会话：删除会话功能未做（本轮范围外），可手动 DB 清理或等删除功能。
