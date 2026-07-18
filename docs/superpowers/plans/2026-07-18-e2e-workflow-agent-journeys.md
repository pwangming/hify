# E2E 旅程扩充（workflow + agent 黄金旅程）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 复用既有 E2E 地基，扩展假 LLM 桩支持同步 JSON 与 tool_calls，新增 workflow（画布真拖拽搭 RAG 流并运行）与 agent（MCP 工具调用出轨迹）两条黄金旅程，并补 e2e datasource fail-fast 守卫。

**Architecture:** 全部改动集中在 `web/e2e/`（桩 + 两条新旅程 + 共用助手）与两处配置（playwright webServer 加 mcp-demo、application-e2e.yml 加 MCP 白名单），外加唯一一处 server 产品代码（`@Profile("e2e")` 守卫类）。桩只按协议形状分支（stream 有无 / tools 有无 / role:tool 有无），Agent 工具走 mcp-demo（端口 3100，Bearer 鉴权），经 MCP 白名单放行 localhost。

**Tech Stack:** Playwright（已有）、Node 原生 http 桩（已有）、mcp-demo（仓库内既有工程）、Spring Boot（守卫类）。

**Spec:** `docs/superpowers/specs/2026-07-18-e2e-workflow-agent-journeys-design.md`

## Global Constraints

- 已合并 Flyway 迁移脚本禁改；本轮**零数据库变更**。
- mvn 结果只看退出码（`mvn -q ...; echo $?`），禁止 grep BUILD SUCCESS。
- 写 Java 前读 `docs/architecture/coding-standards.md`；E2E 相关标准见 `docs/architecture/testing-standards.md` 第五节与 `docs/e2e-guide.md`。
- 桩铁律「傻」：只按协议形状（字段有无）分支，不看内容、无测试特判；同形状请求永远同响应。
- **不改 `SsrfValidator`**；内网白名单仅 MCP、仅 yml（`hify.tool.mcp.allowed-private-hosts`）。
- 既有 KB 旅程（`golden-journey.spec.ts`）必须保持绿；桩扩展不得改变「stream:true 无 tools」请求的响应。
- 前端页面**只允许按需补 `data-test`**，不碰产品逻辑（现场核实：本计划所需钩子已全部存在，预计零改动）。
- `pnpm e2e -- --flag` 无效（pnpm 透传坑）；带参数跑单文件用直调：`node e2e/support/reset-db.mjs && pnpm exec playwright test <file>`。
- 前提：Docker 的 postgres 起着；8080/5173/8090/3100 端口空闲；`mcp-demo` 已 `pnpm install`（Task 5 写进文档）。
- **Task 3 是 spike 关卡**：若画布 drop 事件注入调不通（节点不出现），停止执行并上报，降级方案（API 播种）需用户重新拍板，不得自行降级。

## 关键现场事实（写用例前先读，全部已核实）

- 画布 drop 端只读 `event.dataTransfer.getData('application/hify-node')` 得节点类型，再用 `event.clientX/clientY` 定位（`WorkflowEditor.vue` onDrop）→ 无需模拟完整拖放链，**直接对 `.wf-editor__canvas` dispatch 一个带 DataTransfer 的 drop 事件即可**。
- start/end 节点画布预置、id 字面固定 `start`/`end`、不可删；palette 只有其余 5 类。新节点 id 确定性生成：知识检索第一个=`kb_1`，LLM 第一个=`llm_1`（`graphTransform.ts` ID_PREFIX）。
- 变量语法 `{{nodeId.field}}`；检索节点输出 `text`/`count`，LLM 节点输出 `text`；end 节点按 `outputs: [{name, value模板}]` 渲染最终输出。
- 连线是 Vue Flow handle 间鼠标拖动（非 HTML5 DnD），Playwright `page.mouse` 原生支持；节点 DOM 有 `data-id` 属性。
- 工作流应用类型在建应用表单选「工作流应用」radio（`form-type`）；此时无模型/知识库字段；行操作按钮 `design-<id>` 进 `/apps/:appId/workflow` 画布。
- MCP 工具暴露给 LLM 的名字 = `sanitize(工具行名) + "__" + 远端工具名`（`ToolRegistry.expandMcp`）。**工具行名必须固定为 `mcpdemo`**（纯小写字母，sanitize 恒等），桩的固定 tool_call 名 `mcpdemo__get_current_time` 才对得上。
- mcp-demo：`http://localhost:3100/mcp`，鉴权头 `Authorization: Bearer hify-demo-token`（默认 token），工具 `get_current_time`/`roll_dice`，启动 `pnpm dev`（无 /health，用端口等待）。
- Agent 链路（`AgentChatService`）与 workflow LLM 节点（`LlmCaller`）都是**同步** `.call()`（stream:false）；非 Agent 聊天走 SSE（stream:true 无 tools）。
- ChatView 轨迹：`data-test="tool-trace"` 折叠卡，渲染 `m.toolCalls`（name/args/result）；`MessageView` 含 toolCalls → 历史读回也渲染 → 刷新断言可行。
- ToolDrawer（admin 工具注册表）钩子齐全：`type-mcp`/`form-name`/`form-url`/`form-transport`(默认 streamable_http)/`add-header`/`header-name-0`/`header-value-0`/`form-preview`/`mcp-tool-<toolName>`/`form-submit`。
- MCP 白名单配置键：`hify.tool.mcp.allowed-private-hosts`（application.yml:163 有 env 默认空写法可参照）。

---

### Task 1: 桩扩展——同步 JSON + tool_calls 两种协议形状

**Files:**
- Modify: `web/e2e/stub/llm-stub.mjs`
- Modify: `web/e2e/stub/llm-stub.selftest.mjs`

**Interfaces:**
- Produces（后续 Task 依赖的常量语义）：同步终答文本 `这是知识库助手的固定测试回答。`（沿用现有 STUB_ANSWER，Task 4 断言）；Agent 终答文本 `工具已调用完成，这是最终回答。`（Task 6 断言）；tool_call 固定名 `mcpdemo__get_current_time`（Task 6 的工具行名 `mcpdemo` 与之耦合）。

- [x] **Step 1: 先在 selftest 加三个失败用例**——在既有 `[DONE]` 断言之后、`console.log('STUB SELFTEST PASS')` 之前插入（既有 SSE 用例原样不动，它就是「KB 旅程不受影响」的守门）：

```js
  // 同步无工具 → JSON 终答（非 SSE）
  const syncRes = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'stub', messages: [{ role: 'user', content: 'hi' }] }),
  })
  assert.match(syncRes.headers.get('content-type'), /application\/json/, '同步请求必须回 JSON')
  const sync = await syncRes.json()
  assert.equal(sync.choices[0].message.content, '这是知识库助手的固定测试回答。', '同步须回固定答案')
  assert.equal(sync.choices[0].finish_reason, 'stop')
  assert.ok(sync.usage.total_tokens > 0, '同步响应须带 usage')

  // 带 tools 声明 → 固定 tool_calls
  const tc = await (await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: 'stub',
      messages: [{ role: 'user', content: 'hi' }],
      tools: [{ type: 'function' }],
    }),
  })).json()
  assert.equal(tc.choices[0].finish_reason, 'tool_calls')
  assert.equal(tc.choices[0].message.tool_calls[0].function.name, 'mcpdemo__get_current_time')
  assert.equal(tc.choices[0].message.tool_calls[0].function.arguments, '{"timezone":"Asia/Shanghai"}')

  // messages 已含 role:tool → 终答（即使仍带 tools，优先级在 tool_calls 分支之上）
  const fin = await (await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      model: 'stub',
      messages: [{ role: 'user', content: 'hi' }, { role: 'tool', content: 'x' }],
      tools: [{ type: 'function' }],
    }),
  })).json()
  assert.equal(fin.choices[0].message.content, '工具已调用完成，这是最终回答。', '带工具结果须回终答')
```

- [x] **Step 2: 跑 selftest 确认新用例红**

Run: `cd web && node e2e/stub/llm-stub.selftest.mjs`
Expected: FAIL（新分支不存在，同步请求收到 SSE 或断言失败）

- [x] **Step 3: 改桩实现三分支**（`/v1/chat/completions` 处理器整体替换为下述逻辑；`/v1/embeddings`、`/health` 不动）

```js
const AGENT_TOOL_NAME = 'mcpdemo__get_current_time'
const AGENT_FINAL = '工具已调用完成，这是最终回答。'

function sendJson(res, obj) {
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify(obj))
}
function completion(message, finishReason) {
  return {
    id: 'chatcmpl-stub', object: 'chat.completion', model: 'stub-chat',
    choices: [{ index: 0, message, finish_reason: finishReason }],
    usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 },
  }
}

  if (req.method === 'POST' && req.url === '/v1/chat/completions') {
    readBody(req).then((raw) => {
      let body = {}
      try { body = JSON.parse(raw) } catch { /* 傻：解析失败按空对象走默认分支 */ }
      const messages = Array.isArray(body.messages) ? body.messages : []
      // ① 已带工具结果 → 同步 JSON 终答（Agent 循环第二轮）。顺序在②前：第二轮请求仍带 tools
      if (messages.some((m) => m && m.role === 'tool')) {
        sendJson(res, completion({ role: 'assistant', content: AGENT_FINAL }, 'stop'))
        return
      }
      // ② 带工具声明 → 固定 tool_calls（Agent 循环第一轮）
      if (Array.isArray(body.tools) && body.tools.length > 0) {
        sendJson(res, completion({
          role: 'assistant', content: null,
          tool_calls: [{ id: 'call_stub_1', type: 'function',
            function: { name: AGENT_TOOL_NAME, arguments: '{"timezone":"Asia/Shanghai"}' } }],
        }, 'tool_calls'))
        return
      }
      // ③ 流式 → SSE（原逻辑逐字保留）；同步 → JSON 固定答案
      if (body.stream === true) {
        res.writeHead(200, {
          'content-type': 'text/event-stream',
          'cache-control': 'no-cache',
          connection: 'keep-alive',
        })
        const id = 'chatcmpl-stub'
        // 模拟流式：一个 delta 块送完整答案
        res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: { content: STUB_ANSWER }, finish_reason: null }] })}\n\n`)
        // 收尾块带 usage + finish_reason
        res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 } })}\n\n`)
        res.write('data: [DONE]\n\n')
        res.end()
        return
      }
      sendJson(res, completion({ role: 'assistant', content: STUB_ANSWER }, 'stop'))
    })
    return
  }
```

- [x] **Step 4: selftest 全绿**

Run: `cd web && node e2e/stub/llm-stub.selftest.mjs`
Expected: 全部用例 PASS（含既有 SSE 用例）

- [x] **Step 5: KB 旅程回归（证明桩扩展零影响）**

Run: `cd web && node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts`
Expected: 1 passed

- [x] **Step 6: Commit**

```bash
git add web/e2e/stub/
git commit -m "feat(e2e): 桩扩展协议形状三分支——同步JSON/tool_calls/SSE，KB旅程零影响"
```

---

### Task 2: e2e datasource fail-fast 守卫（唯一 server 产品代码）

**Files:**
- Create: `server/src/main/java/com/hify/infra/config/E2eDatasourceGuard.java`
- Test: `server/src/test/java/com/hify/infra/config/E2eDatasourceGuardTest.java`

**Interfaces:**
- Produces: `E2eDatasourceGuard.requireE2eDatabase(String url)`（static，包私有可见性供测试）。

- [x] **Step 1: 写失败测试**

```java
package com.hify.infra.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2eDatasourceGuardTest {

    @Test
    void url含hify_e2e时放行() {
        assertThatCode(() -> E2eDatasourceGuard.requireE2eDatabase(
                "jdbc:postgresql://localhost:5432/hify_e2e?reWriteBatchedInserts=true"))
                .doesNotThrowAnyException();
    }

    @Test
    void url不含hify_e2e时启动即失败() {
        assertThatThrownBy(() -> E2eDatasourceGuard.requireE2eDatabase(
                "jdbc:postgresql://localhost:5432/hify"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hify_e2e");
    }

    @Test
    void url为null时启动即失败() {
        assertThatThrownBy(() -> E2eDatasourceGuard.requireE2eDatabase(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [x] **Step 2: 跑测试确认编译失败（类不存在）**

Run: `cd server && mvn -q -Dtest=E2eDatasourceGuardTest test; echo $?`
Expected: 非 0（编译错误：找不到 E2eDatasourceGuard）

- [x] **Step 3: 写守卫实现**

```java
package com.hify.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * E2E 安全闸：e2e profile 下 reset-db 会 drop/recreate 目标库，
 * 数据源若被配置漂移指向非 hify_e2e 库，必须在启动阶段拦死而不是删完才发现。
 */
@Component
@Profile("e2e")
public class E2eDatasourceGuard {

    public E2eDatasourceGuard(@Value("${spring.datasource.url:}") String url) {
        requireE2eDatabase(url);
    }

    static void requireE2eDatabase(String url) {
        if (url == null || !url.contains("hify_e2e")) {
            throw new IllegalStateException(
                    "e2e profile 只允许连接 hify_e2e 库，当前 spring.datasource.url=" + url);
        }
    }
}
```

- [x] **Step 4: 测试绿 + 全量后端回归**

Run: `cd server && mvn -q -Dtest=E2eDatasourceGuardTest test; echo $?` → Expected: 0
Run: `cd server && mvn -q verify; echo $?` → Expected: 0（含 ModularityTests/ArchUnit：infra 是共享层，新增 config 类不越界）

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/infra/config/E2eDatasourceGuard.java server/src/test/java/com/hify/infra/config/E2eDatasourceGuardTest.java
git commit -m "feat(infra): e2e profile datasource fail-fast守卫——URL不含hify_e2e启动即失败(还E2E地基轮的账)"
```

---

### Task 3: 共用助手 + workflow 旅程上半（画布拖拽 spike 关卡）

**Files:**
- Create: `web/e2e/support/ui.ts`
- Modify: `web/e2e/golden-journey.spec.ts`（改用助手，行为零变化）
- Create: `web/e2e/workflow-journey.spec.ts`（本 Task 写到「拖入两节点断言可见」为止）

**Interfaces:**
- Produces: `login(page)`、`pickOption(page, text)`、`uniqSuffix()`（Task 4/6 复用）；`dropNode(page, type, offsetX, offsetY)`（Task 4 复用，定义在 workflow-journey.spec.ts 内）。

- [x] **Step 1: 抽共用助手**

```ts
// web/e2e/support/ui.ts
import { expect, type Page } from '@playwright/test'

export const ADMIN = process.env.E2E_ADMIN_USER || 'admin'
export const PASS = process.env.E2E_ADMIN_PASS || 'e2e-admin-123'

export function uniqSuffix(): string {
  return Date.now().toString().slice(-6)
}

/** 登录并等待离开 /login。 */
export async function login(page: Page) {
  await page.goto('/login')
  await page.locator('[data-test="username"]').fill(ADMIN)
  await page.locator('[data-test="password"]').fill(PASS)
  await page.locator('[data-test="submit"]').click()
  await expect(page).not.toHaveURL(/\/login/)
}

/** Element Plus 下拉选项渲染在 body 的 teleport 层，按可见文本点。 */
export async function pickOption(page: Page, text: string) {
  await page.locator('.el-select-dropdown__item', { hasText: text }).first().click()
}
```

- [x] **Step 2: golden-journey.spec.ts 改用助手**——删掉文件内的 ADMIN/PASS 常量、登录四行、pickOption 函数，换成 `import { login, pickOption, uniqSuffix } from './support/ui'`，`const uniq = uniqSuffix()`，步骤 1) 整段换成 `await login(page)`。其余逐字不动。

- [x] **Step 3: 写 workflow 旅程上半**

```ts
// web/e2e/workflow-journey.spec.ts
import { test, expect, type Page } from '@playwright/test'
import { login, pickOption, uniqSuffix } from './support/ui'

const uniq = uniqSuffix()
const N = {
  provider: `e2e-wf-prov-${uniq}`,
  chatModel: `e2e-wf-chat-${uniq}`,
  embedModel: `e2e-wf-embed-${uniq}`,
  dataset: `e2e-wf-kb-${uniq}`,
  app: `e2e-wf-app-${uniq}`,
}

/** 与 WorkflowEditor/NodePalette 共用的拖拽数据键。 */
const DRAG_KEY = 'application/hify-node'

/**
 * 画布 drop 端只读 dataTransfer + clientX/Y（onDrop 实现），
 * 故直接 dispatch 带 DataTransfer 的 drop 事件即可，无需模拟完整 HTML5 拖放链。
 */
async function dropNode(page: Page, type: string, offsetX: number, offsetY: number) {
  const canvas = page.locator('.wf-editor__canvas')
  const box = await canvas.boundingBox()
  if (!box) throw new Error('canvas not visible')
  const dataTransfer = await page.evaluateHandle(
    ({ key, value }) => {
      const dt = new DataTransfer()
      dt.setData(key, value)
      return dt
    },
    { key: DRAG_KEY, value: type },
  )
  await canvas.dispatchEvent('drop', {
    dataTransfer,
    clientX: box.x + offsetX,
    clientY: box.y + offsetY,
  })
}

test('workflow 黄金旅程：配模型→建库→画布拖拽搭RAG流→运行→看输出', async ({ page }) => {
  test.setTimeout(120_000)

  // 1) 登录
  await login(page)

  // 2) 建供应商（baseUrl 指桩）
  await page.goto('/admin/provider')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.provider)
  await page.locator('[data-test="form-baseurl"]').fill('http://localhost:8090/v1')
  await page.locator('[data-test="form-apikey"]').fill('stub-key')
  await page.locator('[data-test="form-submit"]').click()
  const providerRow = page.locator('[data-test="provider-table"] tr', { hasText: N.provider })
  await expect(providerRow).toBeVisible()

  // 3) 建 chat + embedding 两个模型
  await providerRow.locator('[data-test^="manage-"]').click()
  await page.locator('[data-test="model-create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.chatModel)
  await page.locator('[data-test="form-modelkey"]').fill('stub-chat')
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="model-table"]', { hasText: N.chatModel })).toBeVisible()
  await page.locator('[data-test="model-create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.embedModel)
  await page.locator('[data-test="form-type"]').getByText('embedding', { exact: true }).click()
  await page.locator('[data-test="form-modelkey"]').fill('stub-embed')
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="model-table"]', { hasText: N.embedModel })).toBeVisible()

  // 4) 系统 embedding 模型
  await page.goto('/admin/settings')
  await page.locator('[data-test="embedding-select"]').click()
  await pickOption(page, N.embedModel)
  await page.locator('[data-test="save-embedding"]').click()
  await expect(page.getByText(/已保存/)).toBeVisible()

  // 5) 建库 + 传文档等就绪
  await page.goto('/knowledge')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.dataset)
  await page.locator('[data-test="form-submit"]').click()
  const dsRow = page.locator('[data-test="dataset-table"] tr', { hasText: N.dataset })
  await expect(dsRow).toBeVisible()
  await dsRow.locator('[data-test^="open-"]').click()
  await page.locator('input[type="file"]').setInputFiles('e2e/fixtures/kb-doc.txt')
  await expect(page.locator('[data-test^="doc-status-"]').first()).toHaveText('就绪', { timeout: 30_000 })

  // 6) 建工作流应用 → 进画布
  await page.goto('/app')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-type"]').getByText('工作流应用').click()
  await page.locator('[data-test="form-name"]').fill(N.app)
  await page.locator('[data-test="form-submit"]').click()
  const appRow = page.locator('[data-test="app-table"] tr', { hasText: N.app })
  await expect(appRow).toBeVisible()
  await appRow.locator('[data-test^="design-"]').click()
  await expect(page).toHaveURL(/\/apps\/.*\/workflow/)
  // 画布预置 start/end
  await expect(page.locator('.vue-flow__node[data-id="start"]')).toBeVisible()
  await expect(page.locator('.vue-flow__node[data-id="end"]')).toBeVisible()

  // 7) 【SPIKE 关卡】拖入知识检索 + LLM 两节点
  await dropNode(page, 'knowledge-retrieval', 300, 120)
  await expect(page.locator('.vue-flow__node[data-id="kb_1"]')).toBeVisible()
  await dropNode(page, 'llm', 300, 320)
  await expect(page.locator('.vue-flow__node[data-id="llm_1"]')).toBeVisible()
})
```

- [x] **Step 4: 跑通（spike 判定点）**

Run: `cd web && node e2e/support/reset-db.mjs && pnpm exec playwright test workflow-journey.spec.ts`
Expected: 1 passed。
**若 kb_1/llm_1 断言过不了**（drop 注入无效）：先用 `--headed --debug` 排查 dataTransfer 是否送达 onDrop；确认注入机制本身走不通时**停止本 Task 并上报**，等待用户对降级方案重新拍板——不得擅自改成 API 播种。

- [x] **Step 5: KB 旅程回归（助手抽取无碰坏）**

Run: `cd web && node e2e/support/reset-db.mjs && pnpm exec playwright test golden-journey.spec.ts`
Expected: 1 passed

- [x] **Step 6: Commit**

```bash
git add web/e2e/support/ui.ts web/e2e/golden-journey.spec.ts web/e2e/workflow-journey.spec.ts
git commit -m "feat(e2e): 共用助手抽取+workflow旅程上半——画布drop事件注入拖入节点(spike通过)"
```

---

### Task 4: workflow 旅程下半——连线/配表单/运行断言 + 变异 DoD

**Files:**
- Modify: `web/e2e/workflow-journey.spec.ts`
- Modify: `docs/self-check.md`（变异实录）

**Interfaces:**
- Consumes: Task 1 的 STUB_ANSWER 文本、Task 3 的 dropNode/N 常量。

- [x] **Step 1: 追加连线助手与旅程后半**（接在 Task 3 步骤 7 之后）

```ts
/** Vue Flow 连线：从源 handle 拖到目标 handle（纯鼠标事件）。
 *  handle 类名若与实际 DOM 不符，用 --headed 检查后修正 selector（这不算降级）。 */
async function connectNodes(page: Page, fromId: string, toId: string) {
  const src = page.locator(`.vue-flow__node[data-id="${fromId}"] .vue-flow__handle.source`)
  const dst = page.locator(`.vue-flow__node[data-id="${toId}"] .vue-flow__handle.target`)
  const s = await src.boundingBox()
  const d = await dst.boundingBox()
  if (!s || !d) throw new Error(`handle not visible: ${fromId}->${toId}`)
  await page.mouse.move(s.x + s.width / 2, s.y + s.height / 2)
  await page.mouse.down()
  await page.mouse.move(d.x + d.width / 2, d.y + d.height / 2, { steps: 12 })
  await page.mouse.up()
}
```

```ts
  // 8) 连线 start→kb_1→llm_1→end
  await connectNodes(page, 'start', 'kb_1')
  await connectNodes(page, 'kb_1', 'llm_1')
  await connectNodes(page, 'llm_1', 'end')
  await expect(page.locator('.vue-flow__edge')).toHaveCount(3)

  // 9) 配置检索节点：点节点开抽屉 → 绑库 + 查询词（字面量，start 不声明输入省 RunInputsDialog）
  await page.locator('.vue-flow__node[data-id="kb_1"]').click()
  await page.locator('[data-test="kb-datasets"]').click()
  await pickOption(page, N.dataset)
  await page.keyboard.press('Escape') // 关多选下拉（第一次 Esc 被下拉吃掉，抽屉不关）
  await page.locator('[data-test="kb-query"] input').fill('ZEBRA-9137 是什么？')

  // 10) 配置 LLM 节点：选模型 + 用户提示词引用检索输出（变量插值走 {{kb_1.text}}）
  await page.locator('.vue-flow__node[data-id="llm_1"]').click()
  await page.locator('[data-test="llm-model"]').click()
  await pickOption(page, N.chatModel)
  await page.locator('[data-test="llm-user-prompt"] textarea').fill('请根据以下资料回答：{{kb_1.text}}')

  // 11) 配置 end 节点：声明最终输出 answer = {{llm_1.text}}
  await page.locator('.vue-flow__node[data-id="end"]').click()
  await page.locator('[data-test="end-output-add"]').click()
  await page.locator('[data-test="end-output-name"] input').fill('answer')
  await page.locator('[data-test="end-output-value"] input').fill('{{llm_1.text}}')

  // 12) 保存
  await page.locator('[data-test="wf-save"]').click()
  await expect(page.getByText(/已保存/)).toBeVisible()
  await expect(page.locator('[data-test="wf-saved-at"]')).toBeVisible()

  // 13) 运行（start 无输入声明 → 直接触发，不弹 RunInputsDialog）
  await page.locator('[data-test="wf-run"]').click()
  const chip = page.locator('[data-test="run-chip"]')
  await expect(chip).toContainText('成功', { timeout: 30_000 })

  // 14) 最终输出含桩固定答案（钉 end 渲染 + LLM 节点真调了桩）
  await chip.click()
  await expect(page.locator('[data-test="run-outputs"]')).toContainText('这是知识库助手的固定测试回答。')

  // 15) 检索节点运行面板：outputs 有命中（count ≥ 1，变异②的守门断言）
  await page.locator('.vue-flow__node[data-id="kb_1"]').click()
  await expect(page.locator('[data-test="node-run-outputs"]')).toContainText(/"count": [1-9]/)
```

- [x] **Step 2: 全旅程跑绿**

Run: `cd web && node e2e/support/reset-db.mjs && pnpm exec playwright test workflow-journey.spec.ts`
Expected: 1 passed

- [ ] **Step 3: 变异①——删连线证明运行断言在守门**
临时注释 `await connectNodes(page, 'kb_1', 'llm_1')` 与步骤 8 的 `toHaveCount(3)` 两行 → 重跑 → Expected: FAIL（图校验拒绝或运行失败，「成功」断言红）→ 还原两行 → 重跑绿。

- [ ] **Step 4: 变异②——桩零向量证明检索断言在守门**
临时把桩 `STUB_VEC` 改为 `Array(1024).fill(0)`（零向量 → 相似度跌破阈值 → 检索空命中）→ 重跑 → Expected: FAIL（步骤 15 的 count 正则红；若 LLM 输出因空上下文不变，恰证明必须有 count 断言）→ 还原 → 重跑绿。

- [ ] **Step 5: 两个变异的现象与结论追加到 `docs/self-check.md`**（沿用 KB 轮变异记录的格式：变异内容 / 预期红断言 / 实际输出摘要 / 还原后绿）。

- [ ] **Step 6: Commit**

```bash
git add web/e2e/workflow-journey.spec.ts docs/self-check.md
git commit -m "feat(e2e): workflow旅程下半——连线/配置/运行/输出断言，变异DoD双红实证"
```

---

### Task 5: mcp-demo 编排 + MCP 白名单

**Files:**
- Modify: `web/playwright.config.ts`
- Modify: `server/src/main/resources/application-e2e.yml`
- Modify: `docs/e2e-guide.md`（前提补 mcp-demo）

**Interfaces:**
- Produces: E2E 全栈多起一个进程 `mcp-demo`（端口 3100）；e2e profile 下 MCP 允许连 localhost。

- [ ] **Step 1: playwright.config.ts 的 webServer 数组追加**（放在前端 entry 之前；mcp-demo 无 /health，用端口等待）

```ts
    {
      command: 'cd ../mcp-demo && pnpm dev',
      port: 3100,
      timeout: 30_000,
      reuseExistingServer: false,
    },
```

- [ ] **Step 2: application-e2e.yml 的 `hify:` 下追加**（与 identity/usage 平级）

```yaml
  tool:
    mcp:
      # Agent 旅程走本机 mcp-demo：MCP 白名单是内网目标的唯一合法通道（T4b 决策 2）
      allowed-private-hosts: localhost
```

- [ ] **Step 3: `docs/e2e-guide.md` 前提一节补充**：mcp-demo 需先 `cd mcp-demo && pnpm install`（一次性）；端口清单 8080/5173/8090 增补 3100。

- [ ] **Step 4: 编排验证——既有旅程全绿且 mcp-demo 被拉起**

Run: `cd web && pnpm e2e`
Expected: 2 passed（golden + workflow）；输出可见 mcp-demo 启动日志（`mcp-demo listening on http://localhost:3100/mcp`）

- [ ] **Step 5: Commit**

```bash
git add web/playwright.config.ts server/src/main/resources/application-e2e.yml docs/e2e-guide.md
git commit -m "feat(e2e): 编排接入mcp-demo(端口等待)+e2e profile开MCP localhost白名单"
```

---

### Task 6: Agent 黄金旅程 + 变异 DoD

**Files:**
- Create: `web/e2e/agent-journey.spec.ts`
- Modify: `docs/self-check.md`（变异实录）

**Interfaces:**
- Consumes: Task 1 的 tool_call 名 `mcpdemo__get_current_time` 与 Agent 终答文本；Task 5 的 mcp-demo 编排与白名单；`support/ui.ts` 助手。

- [ ] **Step 1: 写 Agent 旅程**

```ts
// web/e2e/agent-journey.spec.ts
import { test, expect } from '@playwright/test'
import { login, pickOption, uniqSuffix } from './support/ui'

const uniq = uniqSuffix()
const N = {
  provider: `e2e-ag-prov-${uniq}`,
  chatModel: `e2e-ag-chat-${uniq}`,
  app: `e2e-ag-app-${uniq}`,
}
// 工具行名必须固定：桩的 tool_call 名 mcpdemo__get_current_time = sanitize(行名)+"__"+远端工具名。
// 因此绕过 reset-db 直跑本用例第二次会撞名报错——pnpm e2e 自带重置，属预期约束。
const TOOL_ROW = 'mcpdemo'

test('Agent 黄金旅程：接入MCP工具→建Agent应用→聊天出轨迹与终答→刷新轨迹仍在', async ({ page }) => {
  test.setTimeout(120_000)

  // 1) 登录 + 建供应商/chat 模型（Agent 不需要 embedding）
  await login(page)
  await page.goto('/admin/provider')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.provider)
  await page.locator('[data-test="form-baseurl"]').fill('http://localhost:8090/v1')
  await page.locator('[data-test="form-apikey"]').fill('stub-key')
  await page.locator('[data-test="form-submit"]').click()
  const providerRow = page.locator('[data-test="provider-table"] tr', { hasText: N.provider })
  await expect(providerRow).toBeVisible()
  await providerRow.locator('[data-test^="manage-"]').click()
  await page.locator('[data-test="model-create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.chatModel)
  await page.locator('[data-test="form-modelkey"]').fill('stub-chat')
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="model-table"]', { hasText: N.chatModel })).toBeVisible()

  // 2) admin 工具注册表：接入 mcp-demo（发现成功 = 白名单放行 + MCP 握手互通）
  await page.goto('/admin/tool')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="type-mcp"]').click()
  await page.locator('[data-test="form-name"]').fill(TOOL_ROW)
  await page.locator('[data-test="form-url"]').fill('http://localhost:3100/mcp')
  // transport 保持默认 streamable_http；鉴权头 = mcp-demo 默认 token
  await page.locator('[data-test="add-header"]').click()
  await page.locator('[data-test="header-name-0"] input').fill('Authorization')
  await page.locator('[data-test="header-value-0"] input').fill('Bearer hify-demo-token')
  await page.locator('[data-test="form-preview"]').click()
  await expect(page.locator('[data-test="mcp-tool-get_current_time"]')).toBeVisible({ timeout: 15_000 })
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="tool-table"] tr', { hasText: TOOL_ROW })).toBeVisible()

  // 3) 建对话应用：选模型 + 开 Agent + 勾 MCP 工具
  await page.goto('/app')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.app)
  await page.locator('[data-test="form-model"]').click()
  await pickOption(page, N.chatModel)
  await page.locator('[data-test="form-agent"]').click()
  await page.locator('[data-test="form-tools"]').click()
  await pickOption(page, TOOL_ROW)
  await page.keyboard.press('Escape')
  await page.locator('[data-test="form-submit"]').click()
  const appRow = page.locator('[data-test="app-table"] tr', { hasText: N.app })
  await expect(appRow).toBeVisible()

  // 4) 聊天：桩第一轮回 tool_calls → Hify 真调 mcp-demo → 第二轮回终答
  await appRow.locator('[data-test^="chat-"]').click()
  await expect(page).toHaveURL(/\/apps\/.*\/chat/)
  await page.locator('[data-test="chat-input"] textarea').fill('现在几点了？')
  await page.locator('[data-test="chat-send"]').click()

  // 断言 A：轨迹卡片出现，展开含完整工具名（钉 registry 命名链 + MCP 真执行）
  const trace = page.locator('[data-test="tool-trace"]')
  await expect(trace).toBeVisible({ timeout: 20_000 })
  await trace.locator('.el-collapse-item__header').click()
  await expect(trace).toContainText('mcpdemo__get_current_time')

  // 断言 B：终答出现（桩 role:tool 分支的固定文本）
  await expect(page.getByText(/这是最终回答/).first()).toBeVisible({ timeout: 20_000 })

  // 5) 刷新 → 轨迹仍在（钉 message.tool_calls 落库 + 历史读回渲染）
  await page.reload()
  const traceAfter = page.locator('[data-test="tool-trace"]')
  await expect(traceAfter).toBeVisible({ timeout: 20_000 })
  await traceAfter.locator('.el-collapse-item__header').click()
  await expect(traceAfter).toContainText('mcpdemo__get_current_time')
})
```

- [ ] **Step 2: 跑绿**

Run: `cd web && pnpm e2e`
Expected: 3 passed（golden + workflow + agent）

- [ ] **Step 3: 变异③——不开 Agent 证明轨迹断言在守门**
临时注释步骤 3 中 `form-agent`/`form-tools`/`pickOption(TOOL_ROW)`/`Escape` 四行 → 重跑 agent 旅程 → Expected: FAIL（无工具调用，`tool-trace` 断言红）→ 还原 → 绿。

- [ ] **Step 4: 变异④——停 mcp-demo 证明互通不是假的**
临时注释 playwright.config.ts 的 mcp-demo webServer 条目 → 重跑 agent 旅程 → Expected: FAIL（form-preview 发现失败，`mcp-tool-get_current_time` 断言红）→ 还原 → 绿。

- [ ] **Step 5: 变异③④实录追加 `docs/self-check.md`**。

- [ ] **Step 6: Commit**

```bash
git add web/e2e/agent-journey.spec.ts docs/self-check.md
git commit -m "feat(e2e): Agent黄金旅程——MCP接入/轨迹/终答/刷新持久化断言，变异DoD双红实证"
```

---

### Task 7: 收尾——文档更新 + 全量回归

**Files:**
- Modify: `docs/e2e-guide.md`（三旅程清单、Agent 旅程的固定工具行名约束说明）
- Modify: `docs/self-check.md`（本轮汇总条目）

- [ ] **Step 1: `docs/e2e-guide.md` 更新**：旅程清单从 1 条改 3 条（各一句话说明覆盖面）；说明桩的三分支协议形状（含「傻」铁律表述）；注明 agent-journey 的工具行名固定 `mcpdemo`、绕过重置直跑两次会撞名。

- [ ] **Step 2: 全量回归三连**

Run: `cd web && pnpm test` → Expected: vitest 全绿
Run: `cd server && mvn -q verify; echo $?` → Expected: 0
Run: `cd web && pnpm e2e` → Expected: 3 passed

- [ ] **Step 3: Commit**

```bash
git add docs/e2e-guide.md docs/self-check.md
git commit -m "docs(e2e): 三旅程使用手册更新+本轮自检汇总；全量回归三连通过"
```
