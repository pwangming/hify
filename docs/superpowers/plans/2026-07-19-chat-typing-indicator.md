# 聊天打字指示器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聊天等待首个 token 期间，把空的 assistant 气泡渲染成三点跳动的打字指示器，正文一出即消失。

**Architecture:** 纯前端渲染层改动。store 发送时本就 push 了一条空 assistant 消息，本轮只在 `ChatView` 模板里给「空内容 + 发送中 + 最后一条」这个组合加一个渲染分支，配一段 CSS 关键帧动画。不动 store、composable、后端。

**Tech Stack:** Vue 3 `<script setup>` + SCSS（scoped）+ vitest。

**Spec:** `docs/superpowers/specs/2026-07-19-chat-typing-indicator-design.md`

## Global Constraints

- 前端 TDD：先写失败测试、亲眼看红，再实现看绿；测试放 `__tests__/`（web-tdd-vitest）。
- 不动 `stores/conversation.ts`、`composables/useChatStream.ts`、后端任何文件。
- 不写魔法色值：颜色用 EP CSS 变量（frontend-standards §8.2）。
- 不用 `!important`（§8.9）。
- 组件样式一律 `scoped`（§8.1）。
- 自写动画属 §5.9「库无此组件时才自己实现」的例外，**提交说明里必须点明「EP 无打字指示器组件」**。
- 不测 CSS 动画本身（jsdom 不跑动画，断言 keyframes 是假测试——testing-standards 三类病之「假测试」）。
- 每 Task 一次 commit，中文 commit message（feat/docs 前缀）。

---

### Task 1: 打字指示器渲染分支 + 三点动画

**Files:**
- Modify: `web/src/views/conversation/ChatView.vue`（script 加 `isTyping`；template 第 148 行附近加分支；style 加 `__typing` + keyframes + reduced-motion）
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`

**Interfaces:**
- Produces: 模板节点 `[data-test="typing-indicator"]`；script 内 `function isTyping(m: MessageView, i: number): boolean`。Task 2 不依赖代码接口，只补文档。

- [ ] **Step 1: 写失败测试**

在 `ChatView.spec.ts` 最后一个 `it(...)` 之后、`})` 结束之前插入三条测试。
沿用本文件既有的「push 消息 + 直接设 `store.sending` + `await nextTick()`」范式
（与既有测试「AI 气泡流式中不显示复制、结束后显示」同款）：

```ts
  it('等待首个 token：空的 AI 气泡显示打字指示器', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const store = useConversationStore()
    store.messages.push({ id: 'u1', role: 'user', content: '问', promptTokens: null, completionTokens: null, createTime: '' })
    store.messages.push({ id: 'a1', role: 'assistant', content: '', promptTokens: null, completionTokens: null, createTime: '' })
    store.sending = true
    await nextTick()
    expect(wrapper.find('[data-test="typing-indicator"]').exists()).toBe(true)
  })

  it('首个 token 到达后指示器消失（正文接管）', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const store = useConversationStore()
    store.messages.push({ id: 'a1', role: 'assistant', content: '', promptTokens: null, completionTokens: null, createTime: '' })
    store.sending = true
    await nextTick()
    expect(wrapper.find('[data-test="typing-indicator"]').exists()).toBe(true)
    store.messages[store.messages.length - 1].content = '答'
    await nextTick()
    expect(wrapper.find('[data-test="typing-indicator"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('答')
  })

  it('sending 结束仍空内容（如首 token 前出错）：不显示指示器', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const store = useConversationStore()
    store.messages.push({ id: 'a1', role: 'assistant', content: '', promptTokens: null, completionTokens: null, createTime: '', error: '网络异常，请稍后重试' })
    store.sending = false
    await nextTick()
    expect(wrapper.find('[data-test="typing-indicator"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="msg-error"]').exists()).toBe(true)
  })
```

- [ ] **Step 2: 跑测试看红**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 3 条新测试中至少 2 条 FAIL（`expects true, received false`——`typing-indicator` 节点不存在）。贴输出。

- [ ] **Step 3: 实现——script 加判定函数**

在 `ChatView.vue` 的 `<script setup>` 内，紧跟既有的 `canCopy` 函数之后加入：

```ts
// 等待首个 token：store 发送时已 push 空 assistant 气泡，此间显示打字指示器。
// 正文一出即消失（正文本身就是「还在动」的证据）；Agent 调工具期间 content 恒空，
// 故指示器会与工具轨迹面板并存，正确表达「在调工具、还没开始答」。
// sending 是唯一闸门：store 的 onDone/onError/abort 三个出口都会置回 false，不会卡住。
function isTyping(m: MessageView, i: number): boolean {
  return sending.value && m.role === 'assistant' && m.content === '' && i === messages.value.length - 1
}
```

（`MessageView` 类型与 `sending` / `messages` 已在本文件顶部导入/解构，无需新增 import。）

- [ ] **Step 4: 实现——template 加渲染分支**

把 `ChatView.vue` 中这一行（现约第 148 行）：

```vue
            <div v-else class="chat__bubble-text">{{ m.content }}</div>
```

替换为：

```vue
            <div
              v-else-if="isTyping(m, i)"
              class="chat__typing"
              data-test="typing-indicator"
              aria-label="正在生成回答"
            >
              <span></span><span></span><span></span>
            </div>
            <div v-else class="chat__bubble-text">{{ m.content }}</div>
```

分支插在行内编辑 `v-if` 之后、正文 `v-else` 之前——编辑态优先级最高，与现状一致。

- [ ] **Step 5: 实现——样式与动画**

在 `ChatView.vue` 的 `<style scoped lang="scss">` 内，`.chat { ... }` **块内部**、
紧跟既有的 `&__bubble-error { ... }` 之后加入：

```scss
  // 等待首个 token 的打字指示器：三点错峰跳动。
  // EP 无打字指示器组件，属 frontend-standards §5.9「库无此组件才自实现」的例外。
  &__typing {
    display: flex;
    align-items: center;
    gap: 4px;
    height: 20px; // 与单行正文等高：首 token 到达时气泡不会从矮跳到高

    span {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--el-text-color-placeholder);
      animation: chat-typing-bounce 1.2s infinite ease-in-out;

      &:nth-child(2) {
        animation-delay: 0.2s;
      }

      &:nth-child(3) {
        animation-delay: 0.4s;
      }
    }
  }

  // 无障碍基线：系统开启「减少动态效果」时不跳动，只留静态圆点
  @media (prefers-reduced-motion: reduce) {
    &__typing span {
      animation: none;
      opacity: 0.5;
    }
  }
```

再在 `.chat { ... }` 闭合大括号**之后**（style 块的根层级）加入关键帧——
`@keyframes` 嵌在选择器内部会输出无效 CSS，必须放根层级；
Vue 的 scoped 编译会把 `@keyframes` 名与 `animation` 引用一起加作用域后缀，两者同在此 style 块内即可对上：

```scss
@keyframes chat-typing-bounce {
  0%,
  60%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }

  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
```

- [ ] **Step 6: 跑测试看绿**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: 全部 PASS（含既有全部用例）。

- [ ] **Step 7: Commit**

```bash
git add web/src/views/conversation/ChatView.vue web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 聊天等待首个token显示打字指示器（EP 无此组件，按 §5.9 例外自实现）"
```

---

### Task 2: 动画规范入档 + 全量回归

**Files:**
- Modify: `docs/architecture/frontend-standards.md`（§8 样式规范加第 10 条）

**Interfaces:**
- Consumes: Task 1 落地的 `@keyframes` 与 reduced-motion 写法（本条规范即由它确立）。

- [ ] **Step 1: 加动画约定**

在 `docs/architecture/frontend-standards.md` 的「## 8. 样式规范」小节，第 9 条
（`9. **禁止**：...`）**之后**新增第 10 条：

```markdown
10. **动画**：仅在能传达状态变化时使用（加载/等待/进出场），不做装饰性动效。自写 `@keyframes` 时：
    ① 必须同时提供 `@media (prefers-reduced-motion: reduce)` 降级（关动画、保留静态可读状态），
    这是无障碍基线；② `@keyframes` 放 `<style scoped>` 块的根层级（嵌在选择器内会输出无效 CSS），
    与 `animation` 引用同处一个 scoped 块，Vue 编译会给两者加同一作用域后缀。
    首例见 `ChatView.vue` 的打字指示器（EP 无对应组件，属 §5.9 例外）。
```

（§5.9 已有「库无此组件时才自己实现、须在提交说明点明」的例外条款，不重复立规。）

- [ ] **Step 2: 全量回归（逐条贴实录）**

```bash
cd web && pnpm test          # Expected: 全绿（413 tests：既有 410 + 新增 3）
cd web && pnpm typecheck     # Expected: EXIT=0
```

E2E 不跑也不加断言：等待态是瞬时状态，E2E 断言它会引入时序脆弱性（spec §6）。

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/frontend-standards.md
git commit -m "docs(frontend-standards): 补动画规范——reduced-motion 降级与 keyframes 放置"
```

---

## 计划外事项（执行者禁做）

- 停止生成按钮、骨架屏、超时文案切换（spec §0 已否，YAGNI）。
- 改 store / composable / 后端。
- 给 E2E 套件加等待态断言。
- push 到远程（由用户决定）。
