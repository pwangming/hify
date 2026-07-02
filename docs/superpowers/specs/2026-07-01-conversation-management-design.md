# conversation 会话管理 + 对话入口 — 设计文档（⑦）

> 日期：2026-07-01
> 模块：`conversation`（前后端一体，含 `app` 前端一处按钮改样式、路由新增菜单）
> 前置：③④⑤⑥ 已合并 main —— 单轮 / 多轮 / SSE 流式 / Token 配额闭环。基础对话链路已通。
> 范围口径：给「基础对话」收尾——补齐**会话管理**（删除/重命名）、**对话入口**（顶部菜单 + 应用选择页，修复层级过深）、**消息级交互**（复制 / 轻量编辑 / AI 免责提示）、以及 app 管理页「试聊」按钮改样式。
> 明确**不做**：真·编辑重新生成、会话列表分页/查看全部、LLM 自动标题、知识库(RAG)、Agent 工具调用。

---

## 0. 决策摘要（拍板结果）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 删除会话 | `DELETE /conversations/{id}`，**软删** + **级联软删该会话消息**，**幂等**（不存在/非本人 → 仍 200，不泄露存在性）。权限**仅本人**（按 user_id 作用域） |
| 2 | 重命名会话 | **动作子资源** `POST /conversations/{id}/rename`（body `{title}`），非 PUT 全量。权限仅本人（assertOwned → 404）。标题 `@NotBlank @Size(max=100)`（对齐 store `TITLE_MAX=100` 与自动标题） |
| 3 | 对话入口 | 顶部新增「对话」菜单 → 极简**应用选择页** `ChatHome.vue`（列已启用对话应用 → 点进 `/apps/:appId/chat`）。app 管理页「试聊」按钮**保留**作快捷入口，两路同终点 |
| 4 | 复制消息 | 用户气泡 hover → **右下角**复制图标；AI 气泡**流式结束后** → **左下角**复制图标（流式中不显示）。纯前端 `navigator.clipboard`，无后端 |
| 5 | 编辑用户消息 | **轻量 B**：点「编辑」把原文**回填输入框**，改完当**新消息**发；历史不动、无后端。按钮语义标「重新编辑后发送」。真编辑+重新生成(A) 留后 |
| 6 | AI 免责提示 | 输入框下方灰色小字「本回答由 AI 生成，请谨慎甄别」。静态文案 |
| 7 | 试聊按钮样式 | 去 `link` → `type="primary"` 实心蓝底 + `size="small"`，与其它三按钮同排同尺寸；**仍对全体成员可见**（不挪进 canModify 门控） |
| 8 | 不破契约（约束） | 17xxx 段不增不改（复用 CommonError）；`ConversationService` 仍无 `@Transactional`；无表结构变更、无新 Flyway；Long→string；conversation 族按当前用户过滤 |

---

## 1. 后端（全部在 conversation 模块内）

### 1.1 新增端点一览

类级 `@RequestMapping("/api/v1/conversation")`（已存在），本轮新增两个方法：

| 方法 | 映射 | 语义 | 失败码 |
|---|---|---|---|
| `DELETE` | `/conversations/{id}` | 软删会话 + 级联软删其消息；幂等 | 无（幂等恒 200；参数非法 10001） |
| `POST` | `/conversations/{id}/rename` | 重命名会话标题 | 404（非本人/不存在）、10001（title 空/超长） |

- 均落**成员族** `/api/v1/conversation/**`，JWT 认证，`anyRequest().authenticated()` 放行成员，无需改 SecurityConfig。
- 路由核对（api-standards §2.1）：资源名复数 `conversations`、嵌套一级 `/conversations/{id}`、rename 为动作子资源（动词原形、一接口一动作）。

### 1.2 为什么 rename 用动作子资源而非 PUT（决策 2 依据，补进本 spec 作结论）

- api-standards §2.2：更新一律 **PUT 全量**（未传字段视为置空）；单字段特例走动作子资源。
- 会话对用户**唯一可改字段就是 title**（appId/userId/时间戳均不可改）。若用 `PUT /conversations/{id}` 传 `{title}`，"全量"语义形同虚设，且"未传字段置空"在此是危险噪音。
- 故按 §2.1「无法干净映射为 CRUD 全量更新的定向单字段变更」用动作子资源 `POST .../rename`，语义显式、与库内既有 `POST /apps/{id}/enable` 风格一致。**此结论记入本 spec，避免再议。**

### 1.3 DTO（模块内，放 `conversation/dto`）

```java
// 仅 rename 需要请求体；delete 无 body
public record RenameConversationRequest(@NotBlank @Size(max = 100) String title) {}
```
- `@NotBlank`：空串/纯空白 → 10001（api-standards §4 入参校验）。
- `@Size(max=100)`：对齐 `ConversationStore.TITLE_MAX=100` 与自动标题（`titleFrom` 首条消息截断也是 100），手动改名与自动标题上限一致，无不一致。title 列长 ≥100，无越界风险（plan 阶段核对列定义）。
- 非跨模块消费 → **不涉及** api 顶层包问题（[[modulith-api-dto-not-consumable]]）。
- 无响应 DTO：删除/改名成功均返回 `Result<Void>`（`data: null`，api-standards §3）。

### 1.4 Controller（协议层，无业务/无事务/无 try-catch）

```java
@DeleteMapping("/conversations/{id}")
public Result<Void> deleteConversation(@PathVariable Long id) {
    conversationService.deleteConversation(id, CurrentUserHolder.current());
    return Result.ok(null);
}

@PostMapping("/conversations/{id}/rename")
public Result<Void> renameConversation(@PathVariable Long id,
                                        @Valid @RequestBody RenameConversationRequest req) {
    conversationService.renameConversation(id, req.title(), CurrentUserHolder.current());
    return Result.ok(null);
}
```

### 1.5 Service（编排层，仍**无 @Transactional**——守约束 6）

```java
public void deleteConversation(Long conversationId, CurrentUser current) {
    store.deleteConversation(conversationId, current.userId());   // 事务收口在 store
}
public void renameConversation(Long conversationId, String title, CurrentUser current) {
    store.renameConversation(conversationId, current.userId(), title);
}
```
- 无 LLM/外部 IO，薄委托；事务边界全在 store（与既有 send/sendStream 一致）。

### 1.6 Store（事务收口）

```java
/** 软删会话（按 user_id 作用域，幂等）+ 级联软删其消息。0 行命中（非本人/已删）不报错。 */
@Transactional
public void deleteConversation(Long conversationId, Long userId) {
    int rows = conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getId, conversationId)
            .eq(Conversation::getUserId, userId));          // @TableLogic → UPDATE deleted=true WHERE ... AND deleted=false
    if (rows > 0) {
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)); // 级联软删该会话全部消息
    }
}

/** 重命名：assertOwned（非本人/不存在 → 404）后改 title。 */
@Transactional
public void renameConversation(Long conversationId, Long userId, String title) {
    assertOwned(conversationId, userId);
    Conversation c = new Conversation();
    c.setId(conversationId);
    c.setTitle(title.strip());
    conversationMapper.updateById(c);   // update_time 由 MetaObjectHandler 自动 touch
}
```

- **删除幂等 vs 改名 404 的口径差异（有意为之）**：删除按 REST 幂等约定（§2.2「删不存在也返回成功」），用 `user_id` 作用域直接软删、0 行静默成功——既不误删他人数据、也不泄露"是否存在"。改名需要给用户明确反馈（改了个不存在/别人的会话应报错），故沿用 `assertOwned` 抛 404。两者都满足「conversation 族按当前用户过滤」（§6）。
- 级联用软删（`@TableLogic`），与 `cleanupFailedTurn` 既有软删风格一致；不物理删，保留 usage 计量已引用的历史消息。
- **无新 Flyway、无表结构变更**：`deleted` 列与 `@TableLogic` 早已就绪。

### 1.7 错误码核对（17xxx 段不增不改）

| 场景 | 码 | HTTP |
|---|---|---|
| title 空/纯空白/超长 | 10001 | 400 |
| 改名/删名——会话非本人或不存在（改名路径） | 10005 | 404 |
| 删除幂等成功 | 200 | 200 |

- 复用 `CommonError`（api-standards §5.4「优先复用通用段」），**无新增模块码**，17xxx 契约不动。

---

## 2. 前端

### 2.1 对话入口：菜单 + 应用选择页（决策 3）

**路由**（`web/src/router/index.ts` 新增一条，menu 自动多一项，见 `menu.ts`）：
```ts
{
  path: '/chat',
  name: 'ChatHome',
  component: () => import('@/views/conversation/ChatHome.vue'),
  meta: { requiresAuth: true, title: '对话', menu: true, icon: 'ChatDotRound' },
}
```
- 放在菜单**首位**（对话是最高频入口）——调整路由声明顺序即可（`menu.ts` 按路由顺序生成）。
- **`/` 首页重定向改为 `/chat`**（已确认：登录后默认落「对话」）。原 `redirect: '/knowledge'` → `redirect: '/chat'`。既有涉及默认落地页的路由/守卫测试若断言 `/knowledge`，同步改为 `/chat`。
- `/apps/:appId/chat` 聊天页路由**不变**（既有）。

**新视图 `ChatHome.vue`（应用选择页，极简）**：
- `onMounted` 调既有 `listApps({ page:1, size:100 })` 拉应用，前端过滤 `status==='enabled'` 的对话应用，渲染成卡片网格（应用名 + 描述 + 模型名）。
- 卡片可点 → `router.push('/apps/{id}/chat')`。`modelUsable===false` 的卡片置灰禁用 + 提示「模型不可用」（与「试聊」按钮 disabled 条件同源，口径一致）。
- 空态：无已启用应用时给引导文案（去「应用管理」创建/启用）。
- 不新增 store/api：复用 `@/api/app` 的 `listApps` 与 `App` 类型。

### 2.2 会话侧边栏：删除 / 重命名（`ConversationSidebar.vue`）

- 每个会话条目 hover → 尾部出现「更多」`el-dropdown`（`MoreFilled` 图标），下拉两项：**重命名**、**删除**。
- 重命名：`ElMessageBox.prompt`（预填当前标题，`inputValidator` 非空 + ≤100）→ `emit('rename', { id, title })`。
- 删除：`confirmDanger('确定删除会话「…」？此操作不可恢复。')` → `emit('delete', id)`。
- 保持既有「新建会话」「选择会话」「按时间分组」不变；操作项走 emit，逻辑落在 ChatView/store（组件只管交互）。

### 2.3 ChatView：接线 store 的删除/重命名 + 消息交互

**store（`stores/conversation.ts`）新增 action**：
```ts
async function renameConversation(id: string, title: string) {
  await apiRename(id, title)
  const c = conversations.value.find(x => x.id === id)
  if (c) c.title = title            // 本地即时回显，不重拉整表
}
async function deleteConversation(id: string) {
  await apiDelete(id)
  conversations.value = conversations.value.filter(x => x.id !== id)
  if (id === currentId.value) newConversation()   // 删的是当前会话 → 回到空白新会话态
}
```
- ChatView 接 sidebar 的 `@rename`/`@delete`；删当前会话后 `router.replace({ query: {} })` 清 URL 的 `?c=`。

**api（`api/conversation.ts`）新增**：
```ts
export const renameConversation = (id: string, title: string) =>
  request.post(`/conversation/conversations/${id}/rename`, { title })
export const deleteConversation = (id: string) =>
  request.delete(`/conversation/conversations/${id}`)
```

### 2.4 消息级交互（ChatView 气泡改造，决策 4/5/6）

当前气泡是裸 `{{ m.content }}`。改为气泡内含**内容 + hover 操作区**：

- **用户气泡**（`--user`）：hover 显示**右下角**操作区——`复制`（`DocumentCopy`）+ `编辑`（`EditPen`，tooltip「重新编辑后发送」）。
  - 复制：`navigator.clipboard.writeText(m.content)` + `ElMessage.success('已复制')`。
  - 编辑（轻量 B）：`input.value = m.content` + 聚焦输入框；**不改历史**，用户改完点发送即作为新消息。
- **AI 气泡**（`--assistant`）：**该条流式结束后**显示**左下角**复制图标。
  - "结束"判定：`!(sending && 是 messages 最后一条)`——流式进行中的最后一条 assistant 不显示复制，done 后 `sending=false` 自然出现。
- **免责提示**：`.chat__input` 下方加 `<p class="chat__disclaimer">本回答由 AI 生成，请谨慎甄别</p>`（灰色小字，`el-text type="info"` 或 scss）。

> 组件优先 Element Plus + `@element-plus/icons-vue`，不自造（[[prefer-element-plus-components]]）。操作区默认 `opacity:0`，气泡 `:hover` 显现（AI 复制按流式状态额外门控）。

### 2.5 app 管理页「试聊」按钮改样式（决策 7，`app/AppList.vue`）

现（约 229–236 行）：`link` + `type="primary"` 纯文字链接、独立在 canModify 之外。改为：
```html
<el-button
  size="small" type="primary"
  :data-test="`chat-${(row as App).id}`"
  :disabled="!(row as App).modelUsable || (row as App).status === 'disabled'"
  @click="openChat(row as App)">试聊</el-button>
```
- 去 `link` → 实心蓝底；加 `size="small"` → 与启用停用/编辑/删除同尺寸。
- 版式让「试聊」与其它三按钮**排同一行、同一操作容器**（调整模板：试聊移入同一 flex 行，但**不加 canModify 门控**——试聊全员可见）。落地时于 plan 阶段定具体 DOM 结构，保 `chat-<id>` data-test 锚点不变（现有测试依赖）。

---

## 3. 测试（TDD，先红后绿；[[web-tdd-vitest]] / [[mvn-quiet-verify-pitfall]]）

### 后端（mock + 边界，判定看 Surefire `Tests run/Failures/Errors`）
- `ConversationStoreTest`：
  - `deleteConversation`：命中 → 会话软删 + 消息级联软删（verify messageMapper.delete 被调）；0 行命中（非本人）→ **不**级联（verify never）、不抛错（幂等）。
  - `renameConversation`：owner → updateById 带 strip 后 title（ArgumentCaptor 断言）；非本人/不存在 → 抛 `CommonError.NOT_FOUND`（assertOwned）。
- `ConversationServiceTest`：`deleteConversation`/`renameConversation` 薄委托 store（verify 传参、透传 userId）。
- `ConversationControllerTest`：
  - `DELETE /conversations/{id}` → 200 + `data:null`；未登录 401。
  - `POST /conversations/{id}/rename` 空 title → 400/10001；正常 → 200；未登录 401。
  - 既有 messages/conversations 路由回归不变。
- `ModularityTests` / `LayerRulesTest`：继续绿（DTO 不 import entity、无新跨模块依赖）。

### 前端（vitest）
- `api/conversation` 新函数 `renameConversation`/`deleteConversation`：断言 url/method/body。
- `stores/conversation`：`renameConversation`（本地回显）、`deleteConversation`（移除列表项；删当前 → newConversation）。
- `ConversationSidebar`：hover 出现操作、rename emit（含新标题）、delete emit。
- `ChatView`：复制用户消息调用 clipboard；AI 气泡流式中不显示复制、done 后显示；编辑回填输入框（不新增 message）；免责文案存在。
- `ChatHome`：渲染已启用应用卡片、过滤停用应用、卡片点击 push 到 `/apps/:id/chat`、modelUsable=false 禁用、空态文案。
- `AppList`：`chat-<id>` 按钮仍存在且 disabled 条件不变（改样式不破现有断言）。
- 路由/菜单：`/chat` 出现在菜单（若有 menu 派生测试则补一例）。

---

## 4. 范围外（本轮不做，留后续）

- 真·编辑并重新生成（A）：改历史 + 作废下游 + 重新生成，独立中型功能。
- 会话列表「加载更多/分页」：现 cap 最近 N 条，超出看不到（内部小团队可接受）。
- LLM 自动生成会话标题：保持首条消息截断 + 本轮的手动改名。
- 知识库(RAG)、Agent 工具调用：knowledge/tool 模块仅空壳，后续独立轮次。
- 对外 `/v1/apps/{appKey}` API。
- 删除「正在流式中」的当前会话的竞态加固：切换/删除前 `store.abort()` 已止血，深度加固留后。

---

## 5. 不破契约核对（约束 8 逐条）

| 契约 | 本轮处理 |
|---|---|
| 错误码 17xxx 段 | **不增不改**；复用 CommonError 10001/10005 |
| `ConversationService` 无 `@Transactional` | 维持；delete/rename 事务收口在 store，无 LLM/IO |
| 既有路由（messages/conversations GET、stream） | URL/行为不变，仅新增两个 conversations 子路由 |
| DELETE 幂等 | 按 §2.2，删不存在/非本人 → 200，不泄露存在性 |
| 跨模块 DTO 走 api 顶层包 | 本轮无新增跨模块 DTO（RenameConversationRequest 模块内） |
| Long 序列化为 string | 无新 id 出参（删除/改名返回 data:null） |
| 数据库变更只新增 Flyway | **无表结构变更、无新脚本**（软删/改名用现有列） |
| 前端优先 Element Plus | 复制/编辑/下拉/提示均用 el-* + icons-vue |
| conversation 族按当前用户过滤 | delete 按 user_id 作用域、rename 走 assertOwned |
```
