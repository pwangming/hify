# 单轮聊天自检（conversation 轮）

> 执行日期：2026-06-26  
> 分支：`feat/conversation-single-turn`  
> 后端回归：`Tests run: 250, Failures: 0, Errors: 0, Skipped: 0`（含 ModularityTests / LayerRulesTest）  
> 前端回归：`Test Files: 21 passed, Tests: 119 passed`  
> 类型检查：`vue-tsc --noEmit` 无报错，`vite build` 成功

---

## 一、6 个决策点最终落地

| # | 决策 | 落地位置 | 行为 |
|---|---|---|---|
| 1 | 一次性 `.call()`，不用 SSE | `ChatInvoker#invoke`（`server/…/conversation/service/ChatInvoker.java`）+ `ConversationController`（`controller/ConversationController.java`） | `chatClient.prompt().user(…).call().chatResponse()` 同步阻塞；Controller 直接返回 `Result<SendMessageResponse>` |
| 2 | 两表按多轮建、只跑单轮 | `V10__create_conversation_message.sql`（`db/migration/`）；`ConversationService#send` 只把当前 `content` 传给 `ChatInvoker.invoke`，不读历史 | conversation + message 两表含 `tool_calls/deleted` 等预留字段；`listMessages` 存在但 prompt 里不使用 |
| 3 | 配额锚点空实现 | `QuotaGuard#check`（`service/QuotaGuard.java`）| 方法体为空，`// TODO(usage 轮)` 标注改动点；`ConversationService.send` 首步调用，usage 就绪后只改这一处 |
| 4 | 极简聊天页，无会话列表 | `ChatView.vue`（`web/src/views/conversation/ChatView.vue`） | 仅输入框 + 气泡列表；先本地渲染用户气泡，再用返回的 `conversationId` 维持会话 ID，无侧边栏 |
| 5 | prompt = systemPrompt + 当前消息；LLM 调用在事务外 | `ConversationService#send`（`service/ConversationService.java`） | `openTurn`（事务A）→ `getChatClient` → `chatInvoker.invoke`（事务外）→ `appendAssistant`（事务B） |
| 6 | 成员族路由 | `ConversationController`（`/api/v1/conversation/messages`） | `POST /api/v1/conversation/messages`（发消息）、`GET /api/v1/conversation/messages?conversationId=`（拉历史） |

---

## 二、"`@Transactional` 内无 LLM 调用"结构保证

- **`ConversationService`**：类上无 `@Transactional`，方法上无 `@Transactional`——编排层物理上无事务。
- **`ConversationStore`**：所有 `@Transactional` 收口于此（`openTurn` 事务A、`appendAssistant` 事务B）。
- **调用顺序**：`store.openTurn` → `chatInvoker.invoke` → `store.appendAssistant`。

三步均为独立调用栈，LLM IO 发生时无数据库连接被占用，完全符合 CLAUDE.md 硬规则 6。

---

## 三、单轮保证

`ConversationService#send` 中传给 `chatInvoker.invoke` 的第三个参数为 `content`（当前消息文本），而非历史消息列表。`listMessages` 接口存在，但仅用于前端 GET 拉历史展示，不进 LLM prompt。

---

## 四、错误码

| 码 | 来源 | 语义 | 处理方式 |
|---|---|---|---|
| `17001 APP_NOT_RUNNABLE` | `ConversationError`（本轮新增） | 应用不存在 / 非 chat 型 / 已停用 / 未绑定模型 | `appFacade.findRunnableChatApp` 返回空时 conversation 层抛出，HTTP 400 |
| `12002`（模型不可用）/ `12003`（熔断）/ `12004`（并发满载） | provider 模块 `ResilienceExceptions` | LLM 调用前 `getChatClient` 或调用中韧性层触发 | 直接透传至全局异常处理器，不在 conversation 层重新包装 |
| `10005`（not found） | `CommonError.NOT_FOUND` | 会话不存在或非本人 | `ConversationStore#assertOwned` 抛出 |
| `10004`（forbidden） | `CommonError.FORBIDDEN` | 无权（admin 检查）| 复用 |
| `10001`（validation） | `CommonError.VALIDATION` | 请求字段校验失败 | `@Valid` + 全局处理器 |

---

## 五、已知取舍

1. **LLM 失败时 user 消息已落库，无补偿**：事务A（`openTurn`）提交后 LLM 失败，user 消息已持久化但无对应 assistant 回复。本轮无自动重试或事务回滚。
2. **前端已清空输入框**：`sendMessage` 前已执行 `input.value = ''`，发送失败时用户需重新输入（`finally` 仅恢复 `sending` 状态，不还原文本）。

---

## 六、与规划的偏差（建设中发现）

### a. `AppRuntimeView` 包位置与字段扁平化

规划在 `app.api.dto` 子包，实际落地于 `com.hify.app.api`（顶层 api 包）。原因：Spring Modulith 1.4.1 的 `@NamedInterface("api")` 仅暴露注解所在包，不自动暴露 `api.dto` 子包，conversation 模块无法跨模块引用 `app.api.dto.AppRuntimeView`。解决方案：将记录放到 `api` 根包，并将字段扁平化为 `(appId, Long modelId, String systemPrompt)`，去掉中间层包装。

### b. 试聊入口脱离 canModify 门控

初版将"试聊"按钮置于 `v-if="canModify(row as App)"` 块内，导致非 owner/Admin 成员看不到入口。修正后移至 `canModify` 块之外，所有成员均可进入试聊页，仅编辑/删除操作保留门控。`AppList.spec.ts` 补充了非 owner 可见试聊按钮的回归用例。

---

## 七、范围外（本轮不做，留后续轮次）

- SSE 流式返回（下一轮）
- 多轮记忆（历史消息进 prompt）
- 会话列表 / 切换 / 删除
- 配额计量（usage 轮补 `QuotaGuard.check` 实现）
- 对外 `/v1/apps/{appKey}` API（API 接入轮）
- Agent / 工具调用（tool 轮）
- 知识检索融入 prompt（RAG 轮）
- E2E 测试基建（推迟）

---

## 真实模型连通手验（待用户配置后补）

**状态：PENDING**

前次 deepseek 供应商已删除，需用户重新操作后补充此节。

**步骤（用户操作）：**

1. 登录管理后台（admin 账号已 seed）→ 供应商管理 → 新建供应商（DeepSeek / 通义千问 / 其他 OpenAI 兼容），填入有效 API Key，测试连通。
2. 在该供应商下创建一个可用的 chat 模型（type=chat，status=enabled）。
3. 应用管理 → 新建对话型应用，绑定上述模型（可设 systemPrompt）。
4. 在应用列表点"试聊"，发送"你好"，确认：
   - 气泡区出现 assistant 回复
   - 或 curl 验证：

```bash
curl -s -X POST http://localhost:8080/api/v1/conversation/messages \
  -H "Authorization: Bearer <member-token>" \
  -H "Content-Type: application/json" \
  -d '{"appId":"<chatAppId>","content":"你好"}'
```

   预期响应：`code=200`，`data.message.content` 为真实回复，`data.message.promptTokens/completionTokens` 非零。

5. 将结果（成功/失败 + 关键输出）追加至本节，并补提交：

```bash
git add docs/self-check-conversation.md
git commit -m "docs(conversation): 补真实模型连通验证结果"
```

---

*（本文档由 CI agent 在 Task 9 全量回归通过后生成，真实模型手验待用户完成后补充）*
