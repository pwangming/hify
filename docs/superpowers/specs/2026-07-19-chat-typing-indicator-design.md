# 聊天等待态打字指示器设计

日期：2026-07-19
状态：已与用户确认
背景：`ChatView` 发送消息后，store 立即 push 一条空的 assistant 消息（`content: ''`），
在首个 token 到达前，界面上是一个**空的灰色气泡**干等着。这段等待在普通对话是 LLM 首字延迟
（数百毫秒到数秒），在 Agent 应用里还要叠加工具调用耗时（可达十几秒），期间用户没有任何
「系统还活着」的反馈。本轮补上打字指示器。

## 0. 范围拍板

| 决策点 | 结论 | 为什么 |
|---|---|---|
| 动画实现 | **自写三点 CSS 动画**（`@keyframes` 错峰跳动） | EP 无打字指示器组件；旋转菊花在聊天气泡里是「加载页面」语义，三点才是「对方在打字」。属 `prefer-element-plus-components` 规范的合理例外，需入档登记 |
| 显示时机 | 仅「等待首个 token」期间，正文一出即消失 | 正文本身就是「还在动」的证据，全程显示是冗余噪声 |
| Agent 工具期 | 点继续跳，与工具轨迹面板并存 | content 期间恒为空，条件天然覆盖；正好表达「在调工具、还没开始答」 |
| 无障碍 | `prefers-reduced-motion: reduce` 时停动画，并写进 frontend-standards | 这是全仓库第一个 CSS 动画，顺手把约定立起来，后续动画照此办理 |
| 数据流 | 不动 store、不动后端 | 空气泡已由 store 在发送时 push，只需把「空内容」渲染成指示器 |

被否/不做（防将来重议）：
- **停止生成按钮**：另一个功能，YAGNI，先看指示器够不够。
- **骨架屏 / 灰条占位**：气泡尺寸未知，骨架屏会造成尺寸跳动，不如三点稳定。
- **超时后切换文案**（如「仍在思考…」）：增加状态与计时器，先不做。
- **同步（非流式）路径**：UI 只有 `ChatView` 一条链路且走 SSE，无第二处需要覆盖。

## 1. 显示条件

指示器出现的充要条件：

```
sending === true  &&  m.role === 'assistant'  &&  m.content === ''  &&  i === messages.length - 1
```

- `sending` 是唯一闸门，安全性已核对：store 的三个出口 `onDone` / `onError` / `abort()`
  **全部**会 `sending.value = false`（`stores/conversation.ts`），因此不存在「点卡住不消失」。
- 最后一条判定复用 `ChatView` 既有的 `i === messages.value.length - 1` 口径（`canCopy` 同款），
  避免历史消息里恰好存在空内容行时误显示。
- 出错路径：若首 token 前就失败，`content` 仍为空但 `sending` 转 false → 点消失，
  气泡下方红色 `chat__bubble-error` 块照常渲染，两者不会同时出现。

## 2. 渲染位置与结构

替换气泡内当前那个会渲染成空白的 `chat__bubble-text`：

```vue
<div v-if="editingId === m.id" class="chat__edit"> ... </div>
<div v-else-if="isTyping(m, i)" class="chat__typing" data-test="typing-indicator" aria-label="正在生成回答">
  <span></span><span></span><span></span>
</div>
<div v-else class="chat__bubble-text">{{ m.content }}</div>
```

- 分支插在行内编辑之后、正文之前（编辑态优先级最高，与现状一致）。
- `data-test="typing-indicator"` 供测试选取。
- `aria-label` 让读屏软件能播报状态（三个空 span 本身无语义）。
- 三个 `<span>` 由 CSS 画成圆点，不放任何文本内容。

## 3. 样式与动画

在 `ChatView.vue` 的 `<style scoped lang="scss">` 内新增，沿用文件既有 BEM 与 EP 变量风格：

```scss
&__typing {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 20px;            // 与单行正文等高，避免首 token 到达时气泡跳动

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--el-text-color-placeholder);
    animation: chat-typing-bounce 1.2s infinite ease-in-out;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

@keyframes chat-typing-bounce {
  0%, 60%, 100% { opacity: 0.3; transform: translateY(0); }
  30%           { opacity: 1;   transform: translateY(-3px); }
}

// 无障碍基线：用户系统开启「减少动态效果」时不跳动，仅保留静态圆点
@media (prefers-reduced-motion: reduce) {
  &__typing span { animation: none; opacity: 0.5; }
}
```

- 固定 `height: 20px` 是刻意的：与单行正文行高对齐，首 token 到达时气泡不会从「矮」跳到「高」。
- 颜色用 `--el-text-color-placeholder`，跟随 EP 主题，不写死色值（index.scss 已统一换肤）。

## 4. 测试（vitest，TDD 先红后绿）

`ChatView.spec.ts` 补三条断言（沿用该文件既有的 store mock 方式）：

1. `sending=true` 且最后一条 assistant 消息 `content=''` → `[data-test="typing-indicator"]` 存在；
2. 同样 `sending=true` 但该消息已有内容 → 指示器不存在（正文接管）；
3. `sending=false`（含出错落地态）→ 指示器不存在。

不测 CSS 动画本身（jsdom 不跑动画，断言 keyframes 属于假测试）。

## 5. 文档登记

- `docs/architecture/frontend-standards.md` 样式小节补两条：
  ① 动画一律附 `prefers-reduced-motion: reduce` 降级（本项首次确立）；
  ② `prefer-element-plus-components` 的例外登记：EP 无对应组件时（如打字指示器）可自写，
     需在此登记，避免下次重复讨论。

## 6. 改动面

| 文件 | 改动 |
|---|---|
| `web/src/views/conversation/ChatView.vue` | 模板加 `isTyping` 分支与 `isTyping()` 判定函数；样式加 `__typing` + keyframes + reduced-motion |
| `web/src/views/conversation/__tests__/ChatView.spec.ts` | 补 3 条断言 |
| `docs/architecture/frontend-standards.md` | 动画与 EP 例外两条约定 |

不动：store、composable、后端、E2E 套件（等待态是瞬时状态，E2E 断言它会引入时序脆弱性）。
