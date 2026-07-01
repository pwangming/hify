# conversation 会话管理 + 对话入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给基础对话收尾——补齐会话**删除/重命名**、**对话入口**（顶部「对话」菜单 + 应用选择页、默认落地页改 `/chat`）、消息**复制/轻量编辑/AI 免责提示**，以及 app 管理页「试聊」按钮改样式。不做真编辑重新生成、列表分页、LLM 标题、知识库、Agent。

**Architecture:** 后端在既有 conversation 链路上加两个成员端点（`DELETE /conversations/{id}` 软删+级联软删消息、幂等；`POST /conversations/{id}/rename` 动作子资源）；事务收口在 `ConversationStore`，`ConversationService` 仍无 `@Transactional`。前端：api+store 接线删除/重命名，侧边栏加操作下拉，ChatView 气泡加复制/编辑+免责提示，新增 `ChatHome.vue` 应用选择页并挂菜单、默认落地页改 `/chat`，AppList 试聊按钮改实心蓝底同排。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + PostgreSQL；Vue 3 `<script setup>` + Pinia + vue-router + Element Plus + `@element-plus/icons-vue`；JUnit5 + Mockito（后端）/ vitest + @vue/test-utils（前端）。

## Global Constraints

- 错误码 **17xxx 段不增不改**；复用 `CommonError` 的 10001（校验）/ 10005（NOT_FOUND）。
- `ConversationService` **类与方法均无 `@Transactional`**；`@Transactional` 全部收口在 `ConversationStore`。
- **删除幂等**（api-standards §2.2）：删不存在/非本人 → 仍 200，按 `user_id` 作用域软删；**重命名**非本人/不存在 → 404（`assertOwned`）。
- 软删走 `@TableLogic`（`BaseEntity.deleted`），**不物理删、不手写 `deleted=false`**。
- **无表结构变更、无新 Flyway 脚本**（`deleted`/`title` 列已就绪，title 列长 ≥100）。
- 标题上限 **100**（`@Size(max=100)`），与后端自动标题 `TITLE_MAX=100` 一致。
- Long 由 infra 全局 Jackson 序列化为 **string**；删除/改名成功返回 `Result<Void>`（`data:null`）。
- 成员族路由 `/api/v1/conversation/**`；既有 messages/conversations/stream 路由 URL 与行为不变。
- 前端优先 Element Plus + icons-vue，不自造组件（[[prefer-element-plus-components]]）。
- 测试放 `__tests__/`（前端）/ 同包 `src/test`（后端）；判 mvn 结果看 Surefire `Tests run/Failures/Errors`，**不 grep BUILD SUCCESS**（[[mvn-quiet-verify-pitfall]]）。前端新代码先写失败测试（[[web-tdd-vitest]]）。

**命令速查**
- 后端单类：`cd server && mvn test -Dtest=<ClassName>`（看 `Tests run: X, Failures: 0, Errors: 0`）
- 后端全量：`cd server && mvn test`
- 前端单文件：`cd web && pnpm vitest run <path>`
- 前端全量：`cd web && pnpm test && pnpm typecheck && pnpm build`

## File Structure

| 文件 | 责任 | 动作 |
|---|---|---|
| `conversation/service/ConversationStore.java` | `deleteConversation` / `renameConversation`（事务收口） | 改 |
| `conversation/service/ConversationService.java` | 编排薄委托（无事务） | 改 |
| `conversation/dto/RenameConversationRequest.java` | 改名请求体（`@NotBlank @Size(max=100)`） | 新增 |
| `conversation/controller/ConversationController.java` | `DELETE /conversations/{id}` + `POST /conversations/{id}/rename` | 改 |
| `web/src/api/conversation.ts` | `deleteConversation` / `renameConversation` | 改 |
| `web/src/stores/conversation.ts` | store 两个 action + 本地回显/移除 | 改 |
| `web/src/views/conversation/ConversationSidebar.vue` | 会话条目操作下拉（重命名/删除） | 改 |
| `web/src/views/conversation/ChatView.vue` | 接线删除/重命名 + 气泡复制/编辑 + 免责提示 | 改 |
| `web/src/views/conversation/ChatHome.vue` | 应用选择页（对话入口） | 新增 |
| `web/src/router/index.ts` | 「对话」菜单路由 + 默认落地页 `/chat` | 改 |
| `web/src/views/app/AppList.vue` | 试聊按钮改实心蓝底、同排 | 改 |

后端测试：`ConversationStoreTest`、`ConversationServiceTest`、`ConversationControllerTest`（均改）。
前端测试：`api/__tests__/conversation.spec.ts`、`stores/__tests__/conversation.spec.ts`、`views/conversation/__tests__/ConversationSidebar.spec.ts`、`views/conversation/__tests__/ChatView.spec.ts`、`views/conversation/__tests__/ChatHome.spec.ts`（新）、`views/app/__tests__/AppList.spec.ts`。

---

### Task 1: 后端 · 删除会话（软删 + 级联软删消息，幂等）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`
- Test: `ConversationStoreTest`、`ConversationServiceTest`、`ConversationControllerTest`（均改）

**Interfaces:**
- Produces:
  - `void ConversationStore.deleteConversation(Long conversationId, Long userId)`
  - `void ConversationService.deleteConversation(Long conversationId, CurrentUser current)`
  - `DELETE /api/v1/conversation/conversations/{id}` → `Result<Void>`

- [ ] **Step 1: 写失败测试**

`ConversationStoreTest` 末尾（最后一个 `}` 前）加：
```java
    @Test
    void deleteConversation_命中_会话软删并级联软删消息() {
        when(conversationMapper.delete(any())).thenReturn(1); // 1 行命中
        store.deleteConversation(100L, 42L);
        verify(conversationMapper).delete(any());
        verify(messageMapper).delete(any()); // 级联
    }

    @Test
    void deleteConversation_未命中_不级联_不抛错() {
        when(conversationMapper.delete(any())).thenReturn(0); // 非本人/已删
        store.deleteConversation(100L, 42L); // 幂等，不抛
        verify(messageMapper, never()).delete(any());
    }
```
> 注：`ConversationStoreTest.setUp` 已 `when(messageMapper.selectList(any()))...`；如未 stub `conversationMapper.delete`，Mockito 默认返回 0（int），故「未命中」用例可省 stub，但显式写更清晰。

`ConversationServiceTest` 末尾加：
```java
    @Test
    void deleteConversation_委托store_传当前用户() {
        service.deleteConversation(100L, member);
        verify(store).deleteConversation(100L, 42L);
    }
```

`ConversationControllerTest` 末尾加（顶部补 `import static ...MockMvcRequestBuilders.delete;`）：
```java
    @Test
    void 删除会话_成员_200_dataNull() throws Exception {
        mockMvc.perform(delete("/api/v1/conversation/conversations/100")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(conversationService).deleteConversation(eq(100L), any());
    }

    @Test
    void 删除会话_未登录_401() throws Exception {
        mockMvc.perform(delete("/api/v1/conversation/conversations/100"))
                .andExpect(status().isUnauthorized());
    }
```
> `ConversationControllerTest` 顶部补 `import static org.mockito.Mockito.verify;`（若尚未 import）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 编译失败（`deleteConversation` 未定义）。

- [ ] **Step 3: ConversationStore.deleteConversation**

在 `listMessages` 之后、`assertOwned` 之前加（`LambdaQueryWrapper`/`Message`/`Conversation` 已 import）：
```java
    /**
     * 软删会话（按 user_id 作用域，幂等）+ 级联软删其全部消息。
     * 0 行命中（非本人/已删）不报错、不级联——满足 DELETE 幂等（api-standards §2.2）且不泄露存在性。
     */
    @Transactional
    public void deleteConversation(Long conversationId, Long userId) {
        int rows = conversationMapper.delete(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)); // @TableLogic → UPDATE deleted=true WHERE ... AND deleted=false
        if (rows > 0) {
            messageMapper.delete(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId));
        }
    }
```

- [ ] **Step 4: ConversationService.deleteConversation**

在 `listConversations` 方法后加：
```java
    public void deleteConversation(Long conversationId, CurrentUser current) {
        store.deleteConversation(conversationId, current.userId());
    }
```

- [ ] **Step 5: ConversationController · DELETE 端点**

补 import `org.springframework.web.bind.annotation.DeleteMapping;`、`org.springframework.web.bind.annotation.PathVariable;`。在 `listConversations` 方法后加：
```java
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        conversationService.deleteConversation(id, CurrentUserHolder.current());
        return Result.ok(null);
    }
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 全绿（`Failures: 0, Errors: 0`）。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/conversation/ server/src/test/java/com/hify/conversation/
git commit -m "feat(conversation): 删除会话端点（软删+级联软删消息，幂等，仅本人）"
```

---

### Task 2: 后端 · 重命名会话（动作子资源 POST /rename）

**Files:**
- Create: `server/src/main/java/com/hify/conversation/dto/RenameConversationRequest.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationStore.java`
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`
- Test: `ConversationStoreTest`、`ConversationServiceTest`、`ConversationControllerTest`（均改）

**Interfaces:**
- Produces:
  - `record RenameConversationRequest(String title)`（`@NotBlank @Size(max=100)`）
  - `void ConversationStore.renameConversation(Long conversationId, Long userId, String title)`
  - `void ConversationService.renameConversation(Long conversationId, String title, CurrentUser current)`
  - `POST /api/v1/conversation/conversations/{id}/rename` → `Result<Void>`

- [ ] **Step 1: 写失败测试**

`ConversationStoreTest` 末尾加：
```java
    @Test
    void renameConversation_owner_改title并strip() {
        Conversation existing = new Conversation();
        existing.setId(100L);
        existing.setUserId(42L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(existing);
        ArgumentCaptor<Conversation> cc = ArgumentCaptor.forClass(Conversation.class);

        store.renameConversation(100L, 42L, "  新标题  ");

        verify(conversationMapper).updateById((Conversation) cc.capture());
        assertEquals(100L, cc.getValue().getId());
        assertEquals("新标题", cc.getValue().getTitle());
    }

    @Test
    void renameConversation_他人会话_404_不更新() {
        Conversation other = new Conversation();
        other.setUserId(999L);
        when(conversationMapper.selectById(eq(100L))).thenReturn(other);

        BizException ex = assertThrows(BizException.class,
                () -> store.renameConversation(100L, 42L, "x"));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
        verify(conversationMapper, never()).updateById(any());
    }
```

`ConversationServiceTest` 末尾加：
```java
    @Test
    void renameConversation_委托store_传当前用户与标题() {
        service.renameConversation(100L, "新名", member);
        verify(store).renameConversation(100L, 42L, "新名");
    }
```

`ConversationControllerTest` 末尾加（顶部已有 `post` import）：
```java
    @Test
    void 重命名_成员_200() throws Exception {
        mockMvc.perform(post("/api/v1/conversation/conversations/100/rename")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(conversationService).renameConversation(eq(100L), eq("新标题"), any());
    }

    @Test
    void 重命名_空标题_400() throws Exception {
        mockMvc.perform(post("/api/v1/conversation/conversations/100/rename")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 编译失败（`RenameConversationRequest` / `renameConversation` 未定义）。

- [ ] **Step 3: RenameConversationRequest DTO**

`server/src/main/java/com/hify/conversation/dto/RenameConversationRequest.java`：
```java
package com.hify.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重命名会话请求体：仅标题。@NotBlank 空/纯空白 → 10001；@Size 对齐自动标题上限 100。 */
public record RenameConversationRequest(@NotBlank @Size(max = 100) String title) {
}
```

- [ ] **Step 4: ConversationStore.renameConversation**

在 `deleteConversation` 之后加：
```java
    /** 重命名：assertOwned（非本人/不存在 → 404）后改 title；update_time 由 MetaObjectHandler 自动 touch。 */
    @Transactional
    public void renameConversation(Long conversationId, Long userId, String title) {
        assertOwned(conversationId, userId);
        Conversation c = new Conversation();
        c.setId(conversationId);
        c.setTitle(title.strip());
        conversationMapper.updateById(c);
    }
```

- [ ] **Step 5: ConversationService.renameConversation**

在 `deleteConversation` 后加：
```java
    public void renameConversation(Long conversationId, String title, CurrentUser current) {
        store.renameConversation(conversationId, current.userId(), title);
    }
```

- [ ] **Step 6: ConversationController · POST /rename 端点**

补 import `com.hify.conversation.dto.RenameConversationRequest;`。在 `deleteConversation` 后加：
```java
    @PostMapping("/conversations/{id}/rename")
    public Result<Void> renameConversation(@PathVariable Long id,
                                           @Valid @RequestBody RenameConversationRequest req) {
        conversationService.renameConversation(id, req.title(), CurrentUserHolder.current());
        return Result.ok(null);
    }
```

- [ ] **Step 7: 运行测试，确认通过**

Run: `cd server && mvn test -Dtest=ConversationStoreTest,ConversationServiceTest,ConversationControllerTest`
Expected: 全绿。

- [ ] **Step 8: 后端全量回归（含 ModularityTests / LayerRulesTest）**

Run: `cd server && mvn test`
Expected: `Failures: 0, Errors: 0`（模块边界、DTO 不 import entity 等约束保持；[[dto-no-entity-import]]）。

- [ ] **Step 9: 提交**

```bash
git add server/src/main/java/com/hify/conversation/ server/src/test/java/com/hify/conversation/
git commit -m "feat(conversation): 重命名会话端点 POST /conversations/{id}/rename（动作子资源，仅本人）"
```

---

### Task 3: 前端 · api + store 接线（删除/重命名）

**Files:**
- Modify: `web/src/api/conversation.ts`
- Modify: `web/src/stores/conversation.ts`
- Test: `web/src/api/__tests__/conversation.spec.ts`、`web/src/stores/__tests__/conversation.spec.ts`（均改）

**Interfaces:**
- Produces:
  - `deleteConversation(id: string): Promise<void>`、`renameConversation(id: string, title: string): Promise<void>`（api）
  - store actions `deleteConversation(id)`（移除列表项；删当前 → newConversation）、`renameConversation(id, title)`（本地回显）

- [ ] **Step 1: 写失败测试（api）**

`api/__tests__/conversation.spec.ts` import 补 `deleteConversation, renameConversation`，describe 内加：
```ts
  it('renameConversation → POST /conversation/conversations/:id/rename', () => {
    renameConversation('100', '新名')
    expect(request.post).toHaveBeenCalledWith('/conversation/conversations/100/rename', {
      title: '新名',
    })
  })

  it('deleteConversation → DELETE /conversation/conversations/:id', () => {
    deleteConversation('100')
    expect(request.delete).toHaveBeenCalledWith('/conversation/conversations/100')
  })
```
> 若现有 mock 仅 stub `request.get`，为 `request` mock 补 `post`/`delete`（`vi.fn()`）。

- [ ] **Step 2: 写失败测试（store）**

`stores/__tests__/conversation.spec.ts`：给 `vi.mock('@/api/conversation', ...)` 补 `deleteConversation: vi.fn()`、`renameConversation: vi.fn()`，加：
```ts
  it('renameConversation 本地回显标题', async () => {
    const store = useConversationStore()
    store.conversations = [{ id: '1', title: '旧', updateTime: 'x' }]
    vi.mocked(renameConversation).mockResolvedValue()
    await store.renameConversation('1', '新')
    expect(renameConversation).toHaveBeenCalledWith('1', '新')
    expect(store.conversations[0].title).toBe('新')
  })

  it('deleteConversation 移除列表项；删当前会话回到空白态', async () => {
    const store = useConversationStore()
    store.conversations = [{ id: '1', title: 'a', updateTime: 'x' }]
    store.currentId = '1'
    store.messages = [{ id: 'm', role: 'assistant', content: 'x', promptTokens: null, completionTokens: null, createTime: 'x' }]
    vi.mocked(deleteConversation).mockResolvedValue()
    await store.deleteConversation('1')
    expect(store.conversations).toHaveLength(0)
    expect(store.currentId).toBeNull()
    expect(store.messages).toEqual([])
  })
```
（顶部 import 补 `deleteConversation, renameConversation`。）

- [ ] **Step 3: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts src/stores/__tests__/conversation.spec.ts`
Expected: 失败（函数/action 未定义）。

- [ ] **Step 4: api 加两个函数**

`web/src/api/conversation.ts` 末尾加：
```ts
/** 重命名会话。后端：POST /api/v1/conversation/conversations/{id}/rename */
export function renameConversation(id: string, title: string) {
  return request.post<void>(`/conversation/conversations/${id}/rename`, { title })
}

/** 删除会话（软删，幂等）。后端：DELETE /api/v1/conversation/conversations/{id} */
export function deleteConversation(id: string) {
  return request.delete<void>(`/conversation/conversations/${id}`)
}
```

- [ ] **Step 5: store 加两个 action**

`web/src/stores/conversation.ts`：import 补两个函数；在 `newConversation` 后加：
```ts
  /** 重命名会话：调后端后本地回显（不重拉整表）。 */
  async function renameConversation(id: string, title: string) {
    await apiRenameConversation(id, title)
    const c = conversations.value.find((x) => x.id === id)
    if (c) c.title = title
  }

  /** 删除会话：调后端后从列表移除；删的是当前会话则回到空白新会话态。 */
  async function deleteConversation(id: string) {
    await apiDeleteConversation(id)
    conversations.value = conversations.value.filter((x) => x.id !== id)
    if (id === currentId.value) newConversation()
  }
```
> import 用别名避免与 action 同名：`import { getMessages, listConversations, deleteConversation as apiDeleteConversation, renameConversation as apiRenameConversation } from '@/api/conversation'`。并把两个 action 加入 `return { ... }`。

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/api/__tests__/conversation.spec.ts src/stores/__tests__/conversation.spec.ts`
Expected: 全绿。

- [ ] **Step 7: 提交**

```bash
git add web/src/api/conversation.ts web/src/stores/conversation.ts web/src/api/__tests__/conversation.spec.ts web/src/stores/__tests__/conversation.spec.ts
git commit -m "feat(web): conversation api/store 接线删除与重命名（本地回显/移除）"
```

---

### Task 4: 前端 · 侧边栏会话操作（重命名/删除下拉）

`ConversationSidebar.vue` 每个会话条目 hover 出「更多」下拉（重命名/删除），走 emit；逻辑在 ChatView 接线。

**Files:**
- Modify: `web/src/views/conversation/ConversationSidebar.vue`
- Test: `web/src/views/conversation/__tests__/ConversationSidebar.spec.ts`（改）

**Interfaces:**
- Produces: emits 追加 `rename(payload: { id: string; title: string })`、`delete(id: string)`

- [ ] **Step 1: 写失败测试**

在 `ConversationSidebar.spec.ts` 加（与现有 mount 辅助一致；`ElMessageBox` 用 `vi.mock('element-plus', ...)` 或直接 stub）：
```ts
  it('触发重命名 emit rename 带 id 与新标题', async () => {
    // ElMessageBox.prompt 返回用户输入
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '新标题', action: 'confirm' } as never)
    const wrapper = mountSidebar()
    await wrapper.find('[data-test="conv-rename-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('rename')?.[0]).toEqual([{ id: '1', title: '新标题' }])
  })

  it('确认删除 emit delete 带 id', async () => {
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountSidebar()
    await wrapper.find('[data-test="conv-delete-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('delete')?.[0]).toEqual(['1'])
  })
```
> 顶部按需 import `flushPromises`（`@vue/test-utils`）、`vi`。`data-test="conv-rename-<id>"` / `conv-delete-<id>` 为下拉项锚点。若沿用现有 `confirmDanger` 工具而非直接 `ElMessageBox`，改为对该工具的 mock（与 AppList 测试一致）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ConversationSidebar.spec.ts`
Expected: 失败（无 rename/delete 锚点与 emit）。

- [ ] **Step 3: 组件加操作下拉**

`ConversationSidebar.vue`：
- `defineEmits` 追加 `(e: 'rename', payload: { id: string; title: string }): void` 与 `(e: 'delete', id: string): void`。
- import `MoreFilled`（icons-vue）、`ElMessageBox`（element-plus）。
- 会话条目 `<li>` 内，标题后加一个 hover 显现的 `el-dropdown`（`@click.stop` 防止冒泡触发 select）：
```vue
<el-dropdown trigger="click" class="sidebar__ops" @click.stop>
  <el-icon class="sidebar__ops-trigger" :data-test="`conv-ops-${c.id}`"><MoreFilled /></el-icon>
  <template #dropdown>
    <el-dropdown-menu>
      <el-dropdown-item :data-test="`conv-rename-${c.id}`" @click="onRename(c)">重命名</el-dropdown-item>
      <el-dropdown-item :data-test="`conv-delete-${c.id}`" divided @click="onDelete(c)">删除</el-dropdown-item>
    </el-dropdown-menu>
  </template>
</el-dropdown>
```
- 方法：
```ts
async function onRename(c: ConversationView) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话标题', '重命名', {
      inputValue: c.title ?? '',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 && v.length <= 100) || '标题需 1-100 字',
    })
    emit('rename', { id: c.id, title: value.trim() })
  } catch { /* 用户取消 */ }
}
async function onDelete(c: ConversationView) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${c.title ?? '未命名会话'}」？此操作不可恢复。`, '删除确认', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
    emit('delete', c.id)
  } catch { /* 用户取消 */ }
}
```
- 样式：`.sidebar__ops-trigger { opacity: 0 }`，`.sidebar__item:hover .sidebar__ops-trigger { opacity: 1 }`（hover 显现）。

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ConversationSidebar.spec.ts`
Expected: 全绿（原有渲染/select/new/高亮用例 + 新增 rename/delete）。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/conversation/ConversationSidebar.vue web/src/views/conversation/__tests__/ConversationSidebar.spec.ts
git commit -m "feat(web): 侧边栏会话操作下拉（重命名/删除，hover 显现）"
```

---

### Task 5: 前端 · ChatView 接线删除/重命名 + 气泡复制/编辑 + 免责提示

**Files:**
- Modify: `web/src/views/conversation/ChatView.vue`
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`（改）

**Interfaces:**
- Consumes: store 的 `renameConversation`/`deleteConversation`；sidebar 的 `@rename`/`@delete`

- [ ] **Step 1: 写失败测试**

在 `ChatView.spec.ts` 追加（与现有 store mock/挂载方式一致）：
```ts
  it('复制用户消息写入剪贴板', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    // 挂载并渲染一条 user 消息后，点复制按钮
    // ...（沿用本文件既有挂载辅助，设 store.messages 含一条 user 消息）
    await wrapper.find('[data-test="copy-msg-<userMsgId>"]').trigger('click')
    expect(writeText).toHaveBeenCalledWith('用户说的话')
  })

  it('AI 气泡流式中不显示复制、done 后显示', async () => {
    // store.sending=true 且最后一条为 assistant → 无 copy 锚点
    // store.sending=false → 出现 copy 锚点
  })

  it('编辑用户消息回填输入框（不新增 message）', async () => {
    await wrapper.find('[data-test="edit-msg-<userMsgId>"]').trigger('click')
    expect((wrapper.find('[data-test="chat-input"] textarea').element as HTMLTextAreaElement).value)
      .toBe('用户说的话')
    // 不调用 store.send
  })

  it('免责提示存在', () => {
    expect(wrapper.find('[data-test="ai-disclaimer"]').text()).toContain('本回答由 AI 生成')
  })

  it('侧边栏 delete/rename 事件接到 store', async () => {
    // 触发 ConversationSidebar 的 @delete/@rename → 期望调用 store.deleteConversation/renameConversation
  })
```
> `<userMsgId>` 用 store 里 user 消息的 id 占位；实现时对应 `:data-test="`copy-msg-${m.id}`"`。测试细节按本文件既有挂载辅助补全（本 plan 给锚点与断言意图，执行者对齐现有 setup）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 失败（无复制/编辑/免责锚点、未接 store）。

- [ ] **Step 3: ChatView 模板与逻辑改造**

- import `DocumentCopy`、`EditPen`（icons-vue）、`ElMessage`（element-plus）；从 store 取 `renameConversation`、`deleteConversation`。
- `storeToRefs` 已有 `sending`；气泡循环用带 index：`v-for="(m, i) in messages"`。
- 气泡结构改为「内容 + 操作区」：
```vue
<div v-for="(m, i) in messages" :key="m.id" :class="['chat__bubble', `chat__bubble--${m.role}`]" data-test="msg">
  <div class="chat__bubble-text">{{ m.content }}</div>
  <div class="chat__bubble-ops" :class="`chat__bubble-ops--${m.role}`">
    <el-icon v-if="canCopy(m, i)" class="chat__op" :data-test="`copy-msg-${m.id}`" @click="copyMsg(m)"><DocumentCopy /></el-icon>
    <el-tooltip v-if="m.role === 'user'" content="重新编辑后发送" placement="top">
      <el-icon class="chat__op" :data-test="`edit-msg-${m.id}`" @click="editMsg(m)"><EditPen /></el-icon>
    </el-tooltip>
  </div>
</div>
```
- 判定与方法：
```ts
// AI 气泡：非「正在流式的最后一条」才可复制；用户气泡永远可复制
function canCopy(m: MessageView, i: number): boolean {
  if (m.role === 'user') return true
  return !(sending.value && i === messages.value.length - 1)
}
async function copyMsg(m: MessageView) {
  await navigator.clipboard.writeText(m.content)
  ElMessage.success('已复制')
}
function editMsg(m: MessageView) {
  input.value = m.content            // 轻量 B：回填输入框，历史不动，改完当新消息发
}
function onRenameConv(payload: { id: string; title: string }) {
  store.renameConversation(payload.id, payload.title)
}
async function onDeleteConv(id: string) {
  await store.deleteConversation(id)
  if (!store.currentId && queryCid.value) router.replace({ query: {} }) // 删的是当前会话 → 清 URL
}
```
- `<ConversationSidebar>` 上补 `@rename="onRenameConv"` `@delete="onDeleteConv"`。
- 输入框区下方加免责提示：
```vue
<p class="chat__disclaimer" data-test="ai-disclaimer">本回答由 AI 生成，请谨慎甄别</p>
```
- 样式：`.chat__bubble-ops { opacity: 0 }`；`.chat__bubble:hover .chat__bubble-ops { opacity: 1 }`；user 操作区右对齐（右下角）、assistant 左对齐（左下角）；`.chat__disclaimer` 灰色小字居中。

- [ ] **Step 4: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 全绿。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/conversation/ChatView.vue web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 气泡复制/轻量编辑 + AI 免责提示 + 接线会话删除/重命名"
```

---

### Task 6: 前端 · 对话入口（ChatHome 应用选择页 + 菜单 + 默认落地页）

**Files:**
- Create: `web/src/views/conversation/ChatHome.vue`
- Modify: `web/src/router/index.ts`
- Test: `web/src/views/conversation/__tests__/ChatHome.spec.ts`（新建）

**Interfaces:**
- Consumes: `listApps`（`@/api/app`）、`App` 类型
- Produces: 路由 `/chat`（name `ChatHome`，menu，icon `ChatDotRound`）；`/` 重定向改 `/chat`

- [ ] **Step 1: 写失败测试 `__tests__/ChatHome.spec.ts`**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listApps } from '@/api/app'
import ChatHome from '@/views/conversation/ChatHome.vue'

vi.mock('@/api/app', () => ({ listApps: vi.fn() }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

const app = (over: Partial<Record<string, unknown>> = {}) => ({
  id: '1', name: '客服助手', description: '答疑', type: 'chat',
  modelId: '5', modelName: 'gpt', modelUsable: true, config: { systemPrompt: null },
  ownerId: '9', status: 'enabled', createTime: 'x', updateTime: 'x', ...over,
})

function mountHome() {
  return mount(ChatHome, { global: { plugins: [ElementPlus] } })
}

describe('ChatHome', () => {
  beforeEach(() => { vi.clearAllMocks(); push.mockClear() })

  it('只渲染已启用应用', async () => {
    vi.mocked(listApps).mockResolvedValue({
      list: [app(), app({ id: '2', name: '停用的', status: 'disabled' })],
      total: '2', page: '1', size: '100',
    })
    const wrapper = mountHome()
    await flushPromises()
    expect(wrapper.findAll('[data-test="chat-app-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('客服助手')
  })

  it('点击卡片进入该应用聊天页', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [app()], total: '1', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    await wrapper.find('[data-test="chat-app-card"]').trigger('click')
    expect(push).toHaveBeenCalledWith('/apps/1/chat')
  })

  it('模型不可用卡片禁用、点击不跳转', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [app({ modelUsable: false })], total: '1', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    await wrapper.find('[data-test="chat-app-card"]').trigger('click')
    expect(push).not.toHaveBeenCalled()
  })

  it('无已启用应用显示空态', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [], total: '0', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    expect(wrapper.find('[data-test="chat-empty"]').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatHome.spec.ts`
Expected: 失败（`ChatHome.vue` 不存在）。

- [ ] **Step 3: 实现 ChatHome.vue**

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listApps } from '@/api/app'
import type { App } from '@/types/app'

const router = useRouter()
const apps = ref<App[]>([])
const loading = ref(false)

// 只展示已启用的对话应用（停用的进不了对话）
const enabledApps = computed(() => apps.value.filter((a) => a.status === 'enabled'))

onMounted(async () => {
  loading.value = true
  try {
    const page = await listApps({ page: 1, size: 100 })
    apps.value = page.list
  } finally {
    loading.value = false
  }
})

function open(a: App) {
  if (!a.modelUsable) return // 模型不可用不可进（与「试聊」按钮 disabled 同源）
  router.push(`/apps/${a.id}/chat`)
}
</script>

<template>
  <div v-loading="loading" class="chat-home">
    <h2 class="chat-home__title">选择一个应用开始对话</h2>
    <div v-if="enabledApps.length" class="chat-home__grid">
      <el-card
        v-for="a in enabledApps"
        :key="a.id"
        shadow="hover"
        :class="['chat-home__card', { 'chat-home__card--disabled': !a.modelUsable }]"
        data-test="chat-app-card"
        @click="open(a)"
      >
        <div class="chat-home__name">{{ a.name }}</div>
        <div class="chat-home__desc">{{ a.description ?? '暂无描述' }}</div>
        <div class="chat-home__model">
          {{ a.modelName ?? '未配置模型' }}
          <span v-if="!a.modelUsable" class="chat-home__muted">（模型不可用）</span>
        </div>
      </el-card>
    </div>
    <el-empty v-else data-test="chat-empty" description="暂无可用的对话应用，请先到「应用管理」创建并启用" />
  </div>
</template>

<style scoped lang="scss">
.chat-home {
  padding: 24px;
  &__title { margin: 0 0 16px; font-size: 18px; }
  &__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
  &__card { cursor: pointer; &--disabled { cursor: not-allowed; opacity: 0.6; } }
  &__name { font-weight: 600; margin-bottom: 6px; }
  &__desc { color: var(--el-text-color-secondary); font-size: 13px; min-height: 20px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  &__model { margin-top: 10px; font-size: 12px; color: var(--el-text-color-secondary); }
  &__muted { color: var(--el-color-danger); }
}
</style>
```

- [ ] **Step 4: 路由 —— 加「对话」菜单 + 默认落地页 `/chat`**

`web/src/router/index.ts`：
- `{ path: '/', redirect: '/knowledge' }` 改为 `{ path: '/', redirect: '/chat' }`。
- 在 `routes` 数组**首位**（`/` 之后、`/knowledge` 之前）插入：
```ts
  {
    path: '/chat',
    name: 'ChatHome',
    component: () => import('@/views/conversation/ChatHome.vue'),
    meta: { requiresAuth: true, title: '对话', menu: true, icon: 'ChatDotRound' },
  },
```
> `/apps/:appId/chat` 路由不动（title 仍「试聊」）。菜单顺序由路由声明顺序决定（见 `menu.ts`），「对话」在最上。

- [ ] **Step 5: 核对是否有断言默认落地页的既有测试**

Run: `cd web && grep -rn "redirect\|'/knowledge'" src/router/__tests__ src/**/__tests__ 2>/dev/null || true`
- 若有测试断言默认落地为 `/knowledge`，同步改为 `/chat`（spec §2.1）。无则跳过。

- [ ] **Step 6: 运行测试，确认通过**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatHome.spec.ts`
Expected: 全绿（4 passed）。

- [ ] **Step 7: 提交**

```bash
git add web/src/views/conversation/ChatHome.vue web/src/router/index.ts web/src/views/conversation/__tests__/ChatHome.spec.ts
git commit -m "feat(web): 对话入口——ChatHome 应用选择页 + 顶部「对话」菜单 + 默认落地页 /chat"
```

---

### Task 7: 前端 · AppList 试聊按钮改样式

**Files:**
- Modify: `web/src/views/app/AppList.vue`
- Test: `web/src/views/app/__tests__/AppList.spec.ts`（回归，`chat-<id>` 锚点与 disabled 条件不变）

**Interfaces:** 无接口变化，纯样式/版式。

- [ ] **Step 1: 改试聊按钮**

`AppList.vue` 操作列（约 229–236 行）试聊按钮：去 `link`，加 `size="small"`，保持 `type="primary"` 与 disabled 条件、`chat-<id>` 锚点、`openChat` 不变：
```html
<el-button
  size="small"
  type="primary"
  :data-test="`chat-${(row as App).id}`"
  :disabled="!(row as App).modelUsable || (row as App).status === 'disabled'"
  @click="openChat(row as App)"
>试聊</el-button>
```
- 版式：让「试聊」与其它三按钮（启用停用/编辑/删除）排同一行、同尺寸。做法：把试聊按钮**移入**与 `app-list__ops` 同一 flex 容器的最前，但**不加 `canModify` 门控**（试聊全员可见）。示例结构：
```html
<div class="app-list__ops">
  <el-button size="small" type="primary" ...>试聊</el-button>
  <template v-if="canModify(row as App)">
    <!-- 启用/停用、编辑、删除 三按钮原样 -->
  </template>
</div>
```
- 删除原来包着试聊的 `link` 独立写法与 `app-list__readonly` 的 `—` 分支处理：他人应用现在也应显示「试聊」（全员可见），其余三按钮不显示。若原 `v-else` 显示 `—` 是针对整列，调整为：操作列恒显示试聊；`canModify` 才追加其余三按钮（无 `—` 占位或保留视觉留白，按现有视觉取舍）。

- [ ] **Step 2: 运行既有测试，确认不破**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts`
Expected: 全绿。若既有用例断言他人应用操作列为 `—`（无试聊），按新语义「试聊全员可见」更新该断言（试聊恒在，仅编辑/删除受 `canModify` 门控）。

- [ ] **Step 3: 提交**

```bash
git add web/src/views/app/AppList.vue web/src/views/app/__tests__/AppList.spec.ts
git commit -m "style(web): 应用管理「试聊」改实心蓝底 small、与操作按钮同排（全员可见）"
```

---

### Task 8: 全量回归 + 手验清单

- [ ] **Step 1: 后端全量**

Run: `cd server && mvn test`
Expected: `Failures: 0, Errors: 0`（含 ModularityTests / LayerRulesTest）。

- [ ] **Step 2: 前端全量 + 类型 + 构建**

Run: `cd web && pnpm test && pnpm typecheck && pnpm build`
Expected: 全绿、`vue-tsc` 无错、`vite build` 通过、`pnpm lint` 无问题（[[frontend-testing-strategy]]）。

- [ ] **Step 3: 追加自检到 docs/self-check.md（[[self-check-per-step]]）**

按既有格式追加本轮小节：改了什么、怎么自证（测试数）、反向验证点、已知遗留（真编辑/分页/LLM 标题）。

- [ ] **Step 4: 手验（登录后）**
  - 登录后默认落「对话」页 → 卡片选应用进聊天；停用/模型不可用应用不可进。
  - 侧边栏会话「重命名」改标题即时生效、刷新仍在；「删除」消失且刷新不回来；删当前会话回到空白态。
  - 用户气泡 hover 右下角复制/编辑：复制进剪贴板；编辑回填输入框、改完发出为新消息（旧消息还在）。
  - AI 回答流式中无复制图标、完成后左下角出现，复制成功。
  - 输入框下方显示「本回答由 AI 生成，请谨慎甄别」。
  - 应用管理「试聊」为实心蓝底、与其它按钮同排；他人应用仍可试聊、但无编辑/删除。

---

## 完成后（收尾）

- [ ] 全部任务测试绿、手验通过后，参考 [[superpowers-spec-plan-workflow]] 收尾：可用 superpowers:finishing-a-development-branch 决定合并/PR。
- [ ] 更新记忆 [[conversation-mgmt-round-planned]]：从「计划中」改为「已完成」并记录范围与遗留（真编辑/分页/LLM 标题、知识库/Agent 仍待做）。
