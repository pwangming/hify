# E2E 地基 + KB 黄金旅程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 本地起「真前端+真后端+真库+假 LLM 桩」的全栈，用 Playwright 跑一条穿 5 模块的 KB 黄金旅程（登录→配模型→设 embedding→建库→传文档→建应用→聊天→看引用→刷新仍在），并用「故意搞错」证明它真在守门。

**Architecture:** 新增集中在 `web/e2e/` + 一份后端 `application-e2e.yml`，不碰 server 产品代码（除加一个 `data-test` 钩子）。`pnpm e2e` = 先 `reset-db.mjs` drop/recreate `hify_e2e` 库 → 再 `playwright test`；Playwright 的 webServer 拉起后端(e2e profile,指 hify_e2e)+假 LLM 桩+前端 vite，按健康 URL 逐个等 ready。假桩说 OpenAI 兼容协议、返回固定 1024 维向量 + 固定 SSE 答案，令检索必命中、断言确定。

**Tech Stack:** Playwright（@playwright/test，chromium 单浏览器）+ Node 原生 http（桩）+ 现有 Spring Boot 后端 + Vite + docker compose(postgres)。

## Global Constraints

- 只做本地，`pnpm e2e` 一条命令绿；CI 留下一轮，本轮不碰。
- 不真打 LLM/embedding API：全部走本地假桩（端口 8090，baseUrl `http://localhost:8090/v1`）。
- 假桩必须「傻」：只吐通用定值，无任何针对断言的特判分支。
- E2E 只跑独立库 `hify_e2e`，绝不碰 dev 库 `hify`。
- 固定向量维度精确 **1024**（对齐 V15 `vector(1024)`；保存 embedding 设置时后端会探测维度）。
- 断言钉在后端/DB 产出（上传的真实文件名 + `[0,100]%` 数字分数 + 刷新后仍在），不钉桩喂的答案字面。
- 不用固定 `sleep`，用 Playwright web-first 断言（自动等待+重试）。
- 后端启动命令用系统 `mvn`（无 maven wrapper）；判定 mvn 结果不 grep `BUILD SUCCESS`（`-q` 会静音）。
- 库重置必须早于后端启动（后端启动才 Flyway 迁移 + 种 admin），故重置在 Playwright 之外的 wrapper，不放 globalSetup。
- 不新增后端运行时依赖；`@playwright/test` 仅 devDependency。
- 端口：postgres 5432 / 后端 8080 / 桩 8090 / 前端 5173。跑 E2E 前确保没有 dev 后端/前端占用 8080/5173。

---

### Task 1: 假 LLM 桩（独立可测，先立叶子）

**Files:**
- Create: `web/e2e/stub/llm-stub.mjs`
- Test: `web/e2e/stub/llm-stub.selftest.mjs`

**Interfaces:**
- Produces: 一个 Node http 服务，监听 `process.env.STUB_PORT || 8090`，暴露：
  - `GET /health` → 200 `ok`
  - `POST /v1/embeddings` → `{object:"list",data:[{object:"embedding",index:0,embedding:<1024 floats>}],model,usage}`
  - `POST /v1/chat/completions` → SSE 流（`data: {chunk}\n\n` ... `data: [DONE]\n\n`），固定答案文本常量 `STUB_ANSWER`
- 常量：`STUB_ANSWER = "这是知识库助手的固定测试回答。"`；`STUB_VEC = Array(1024).fill(0.1)`

- [ ] **Step 1: 写桩自测（先红）**

`web/e2e/stub/llm-stub.selftest.mjs`：

```js
// 独立自测：起桩 → 打两个接口 → 断言维度/SSE → 退出码表示成败。用 `node llm-stub.selftest.mjs` 跑。
import { spawn } from 'node:child_process'
import assert from 'node:assert/strict'

const port = 8091
const proc = spawn('node', [new URL('./llm-stub.mjs', import.meta.url).pathname], {
  env: { ...process.env, STUB_PORT: String(port) }, stdio: 'inherit',
})
const base = `http://localhost:${port}`
try {
  await waitHealth()
  // embeddings：必须 1024 维
  const emb = await (await fetch(`${base}/v1/embeddings`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ input: '任意文本', model: 'stub' }),
  })).json()
  assert.equal(emb.data[0].embedding.length, 1024, 'embedding 必须 1024 维')
  // chat：SSE 流含答案且以 [DONE] 收尾
  const res = await fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'stub', stream: true, messages: [{ role: 'user', content: 'hi' }] }),
  })
  const text = await res.text()
  assert.match(text, /data: /, 'chat 必须是 SSE')
  assert.match(text, /这是知识库助手的固定测试回答/, '必须含固定答案')
  assert.match(text, /\[DONE\]/, '必须以 [DONE] 收尾')
  console.log('STUB SELFTEST PASS')
} finally {
  proc.kill()
}

async function waitHealth() {
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`${base}/health`)).ok) return } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error('stub 未就绪')
}
```

- [ ] **Step 2: 跑自测确认失败**

Run: `cd web && node e2e/stub/llm-stub.selftest.mjs`
Expected: 报错（`llm-stub.mjs` 尚不存在 / 模块找不到）。

- [ ] **Step 3: 实现桩**

`web/e2e/stub/llm-stub.mjs`：

```js
// 假 LLM 桩：OpenAI 兼容 /v1/embeddings + /v1/chat/completions。
// 铁律「傻」：对任何输入返回同一定值——不看请求内容、无测试特判分支。
// 固定向量令「问题向量 == 分段向量」→ 余弦相似度=1 → 必过 K4 阈值 0.3。
import http from 'node:http'

const PORT = Number(process.env.STUB_PORT || 8090)
const STUB_ANSWER = '这是知识库助手的固定测试回答。'
const STUB_VEC = Array(1024).fill(0.1) // 精确 1024 维

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200).end('ok')
    return
  }
  if (req.method === 'POST' && req.url === '/v1/embeddings') {
    readBody(req).then(() => {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({
        object: 'list',
        data: [{ object: 'embedding', index: 0, embedding: STUB_VEC }],
        model: 'stub-embed',
        usage: { prompt_tokens: 1, total_tokens: 1 },
      }))
    })
    return
  }
  if (req.method === 'POST' && req.url === '/v1/chat/completions') {
    readBody(req).then(() => {
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      })
      const id = 'chatcmpl-stub'
      // 逐字符吐 delta，模拟流式
      for (const ch of STUB_ANSWER) {
        res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: { content: ch }, finish_reason: null }] })}\n\n`)
      }
      // 收尾块带 usage + finish_reason
      res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', model: 'stub-chat', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 12, total_tokens: 22 } })}\n\n`)
      res.write('data: [DONE]\n\n')
      res.end()
    })
    return
  }
  res.writeHead(404).end()
})

function readBody(req) {
  return new Promise((resolve) => {
    let b = ''
    req.on('data', (c) => { b += c })
    req.on('end', () => resolve(b))
  })
}

server.listen(PORT, () => console.log(`[llm-stub] listening on ${PORT}`))
```

- [ ] **Step 4: 跑自测确认通过**

Run: `cd web && node e2e/stub/llm-stub.selftest.mjs`
Expected: 打印 `STUB SELFTEST PASS`，退出码 0。

- [ ] **Step 5: Commit**

```bash
git add web/e2e/stub/llm-stub.mjs web/e2e/stub/llm-stub.selftest.mjs
git commit -m "test(e2e): 假 LLM 桩（OpenAI 兼容 /embeddings 1024维 + /chat SSE 固定答案）+ 自测"
```

---

### Task 2: Playwright 地基 + 起停编排 + 冒烟（登录）

**Files:**
- Create: `web/playwright.config.ts`
- Create: `web/e2e/support/reset-db.mjs`
- Create: `web/e2e/smoke.spec.ts`
- Create: `web/e2e/fixtures/kb-doc.txt`
- Create: `server/src/main/resources/application-e2e.yml`
- Modify: `web/package.json`（devDep `@playwright/test`；scripts 加 `e2e`）
- Modify: `web/.gitignore`（忽略 `test-results/`、`playwright-report/`）

**Interfaces:**
- Consumes: Task 1 的桩（webServer 之一）
- Produces: `pnpm e2e` 命令；`E2E_ADMIN_USER="admin"` / `E2E_ADMIN_PASS="e2e-admin-123"`（登录用，与 application-e2e.yml 的 bootstrap-admin 一致）；Playwright `use.baseURL="http://localhost:5173"`

- [ ] **Step 1: 后端 E2E profile**

`server/src/main/resources/application-e2e.yml`（只覆盖差异，其余继承 application.yml）：

```yaml
# E2E 专用：数据源指向独立库 hify_e2e（wrapper 已 drop/recreate），启动时 Flyway 全量迁移 + 引导 admin。
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/hify_e2e?reWriteBatchedInserts=true
    username: ${POSTGRES_USER:hify}
    password: ${POSTGRES_PASSWORD:hify}
hify:
  identity:
    bootstrap-admin:
      username: admin
      password: e2e-admin-123
  usage:
    # E2E 不测配额，放到极大，避免旅程被每日 Token 上限拦
    daily-token-limit-per-user: 999999999
```

- [ ] **Step 2: 库重置脚本**

`web/e2e/support/reset-db.mjs`：

```js
// 在 Playwright 启动之前跑：确保 postgres healthy，然后 drop+recreate hify_e2e（WITH FORCE 踢掉残留连接）。
// 必须早于后端启动——后端启动才跑 Flyway + 引导 admin。
import { execSync } from 'node:child_process'

const root = new URL('../../../', import.meta.url).pathname // 仓库根（docker-compose.yml 所在）
const run = (cmd) => execSync(cmd, { cwd: root, stdio: 'inherit' })

run('docker compose up -d --wait postgres')
run('docker compose exec -T postgres psql -U hify -d hify -c "DROP DATABASE IF EXISTS hify_e2e WITH (FORCE); CREATE DATABASE hify_e2e;"')
console.log('[reset-db] hify_e2e 已重建')
```

- [ ] **Step 3: 测试文档 fixture**

`web/e2e/fixtures/kb-doc.txt`：

```
Hify 端到端测试文档。ZEBRA-9137 是本文档的唯一标记短语，用于验证知识库问答旅程。
本段内容足以产生至少一个分段，供向量化与检索使用。
```

- [ ] **Step 4: Playwright 配置**

`web/playwright.config.ts`：

```ts
import { defineConfig, devices } from '@playwright/test'

// 全栈编排：webServer 逐个拉起 后端(e2e)+桩+前端，按健康 URL 等 ready。
// 库重置在 pnpm e2e 脚本里（reset-db.mjs）先跑，不放这里，保证「重置早于后端启动」。
export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: { baseURL: 'http://localhost:5173', trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'cd ../server && mvn -q spring-boot:run -Dspring-boot.run.profiles=e2e',
      url: 'http://localhost:8080/actuator/health',
      timeout: 180_000,
      reuseExistingServer: false,
      stdout: 'pipe',
    },
    {
      command: 'node e2e/stub/llm-stub.mjs',
      url: 'http://localhost:8090/health',
      timeout: 30_000,
      reuseExistingServer: false,
    },
    {
      command: 'pnpm dev',
      url: 'http://localhost:5173',
      timeout: 60_000,
      reuseExistingServer: false,
    },
  ],
})
```

- [ ] **Step 5: 装 Playwright + 加脚本**

Run:
```bash
cd web && pnpm add -D @playwright/test && pnpm exec playwright install chromium
```
在 `web/package.json` 的 `scripts` 加：
```json
    "e2e": "node e2e/support/reset-db.mjs && playwright test",
    "e2e:ui": "node e2e/support/reset-db.mjs && playwright test --ui"
```
在 `web/.gitignore` 追加：
```
test-results/
playwright-report/
```

- [ ] **Step 6: 冒烟用例（登录）**

`web/e2e/smoke.spec.ts`：

```ts
import { test, expect } from '@playwright/test'

const ADMIN = process.env.E2E_ADMIN_USER || 'admin'
const PASS = process.env.E2E_ADMIN_PASS || 'e2e-admin-123'

// 冒烟：证明「重置库→后端启动→Flyway→引导 admin→前端→登录」这条编排真的通。
test('seeded admin can log in', async ({ page }) => {
  await page.goto('/login')
  await page.locator('[data-test="username"]').fill(ADMIN)
  await page.locator('[data-test="password"]').fill(PASS)
  await page.locator('[data-test="submit"]').click()
  // 登录成功跳离 /login（根路径重定向到 /chat）
  await expect(page).not.toHaveURL(/\/login/)
})
```

- [ ] **Step 7: 跑冒烟（这是本 Task 的验收）**

Run: `cd web && pnpm e2e -- smoke.spec.ts`
Expected: 1 passed。若后端启动超时，确认没有 dev 后端占用 8080；首次启动 JVM 慢属正常（timeout 已给 180s）。

- [ ] **Step 8: Commit**

```bash
git add web/playwright.config.ts web/e2e/support/reset-db.mjs web/e2e/smoke.spec.ts \
        web/e2e/fixtures/kb-doc.txt server/src/main/resources/application-e2e.yml \
        web/package.json web/pnpm-lock.yaml web/.gitignore
git commit -m "test(e2e): Playwright 地基+全栈起停编排+库重置+登录冒烟通过"
```

---

### Task 3: 补 `data-test` 钩子（文档状态，供确定性等待就绪）

**Files:**
- Modify: `web/src/views/knowledge/DatasetDetail.vue`（状态列的 el-tag 加 `data-test`）
- Modify: `web/src/views/knowledge/__tests__/DatasetDetail.spec.ts`（若断言涉及状态列，补 data-test；否则加一条最小断言）

**Interfaces:**
- Produces: 文档状态标签选择器 `[data-test="doc-status-${id}"]`，文本为 `就绪/处理中/待处理/失败`

- [ ] **Step 1: 加钩子**

在 `DatasetDetail.vue` 状态列的 `<el-tag>`（约 243 行，`STATUS_LABEL` 那个）加 `:data-test`：

```html
            <el-tag v-else :type="STATUS_TAG[(row as KbDocument).status]"
                    :data-test="`doc-status-${(row as KbDocument).id}`">
              {{ STATUS_LABEL[(row as KbDocument).status] }}
            </el-tag>
```

- [ ] **Step 2: 补一条 vitest 断言（先红后绿）**

在 `DatasetDetail.spec.ts` 加/改一条断言，验证状态标签带新钩子（按该文件既有 mount + mock 手法，注入一条 `status:'ready'` 文档，断言 `[data-test="doc-status-<id>"]` 文本含「就绪」）。

```ts
it('渲染文档状态标签带 data-test 钩子', async () => {
  // 用文件既有 helper 注入一条 ready 文档（id 如 '77'），mount 后：
  expect(wrapper.find('[data-test="doc-status-77"]').text()).toContain('就绪')
})
```

- [ ] **Step 3: 跑 vitest 确认通过**

Run: `cd web && pnpm vitest run src/views/knowledge/__tests__/DatasetDetail.spec.ts`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add web/src/views/knowledge/DatasetDetail.vue web/src/views/knowledge/__tests__/DatasetDetail.spec.ts
git commit -m "test(web): DatasetDetail 文档状态列补 data-test 钩子（供 E2E 等就绪）"
```

---

### Task 4: KB 黄金旅程（穿 5 模块，含刷新持久化断言）

**Files:**
- Create: `web/e2e/golden-journey.spec.ts`

**Interfaces:**
- Consumes: Task 1 桩、Task 2 编排/登录、Task 3 `doc-status` 钩子
- 复用现有钩子：provider(`create-open/form-name/form-baseurl/form-apikey/form-submit/provider-table/manage-*`)、model(`model-create-open/form-name/form-type/form-modelkey/form-submit/model-table`)、settings(`embedding-select/save-embedding`)、knowledge(`create-open/form-name/form-submit/open-*`)、upload(`input[type=file]`)、app(`create-open/form-name/form-model/form-datasets/form-submit/chat-*`)、chat(`chat-input/chat-send/msg/msg-sources/source-card`)

- [ ] **Step 1: 写旅程用例**

`web/e2e/golden-journey.spec.ts`：

```ts
import { test, expect } from '@playwright/test'

const ADMIN = process.env.E2E_ADMIN_USER || 'admin'
const PASS = process.env.E2E_ADMIN_PASS || 'e2e-admin-123'
const uniq = Date.now().toString().slice(-6)
const N = {
  provider: `e2e-stub-${uniq}`,
  chatModel: `e2e-chat-${uniq}`,
  embedModel: `e2e-embed-${uniq}`,
  dataset: `e2e-kb-${uniq}`,
  app: `e2e-app-${uniq}`,
}

// Element Plus 下拉选项渲染在 body 的 teleport 层，按可见文本点。
async function pickOption(page, text) {
  await page.locator('.el-select-dropdown__item', { hasText: text }).first().click()
}

test('KB 黄金旅程：配模型→建库→传文档→聊天→看引用→刷新仍在', async ({ page }) => {
  // 1) 登录（identity）
  await page.goto('/login')
  await page.locator('[data-test="username"]').fill(ADMIN)
  await page.locator('[data-test="password"]').fill(PASS)
  await page.locator('[data-test="submit"]').click()
  await expect(page).not.toHaveURL(/\/login/)

  // 2) 建供应商（provider）：协议默认 openai，baseUrl 指桩
  await page.goto('/admin/provider')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.provider)
  await page.locator('[data-test="form-baseurl"]').fill('http://localhost:8090/v1')
  await page.locator('[data-test="form-apikey"]').fill('stub-key')
  await page.locator('[data-test="form-submit"]').click()
  const providerRow = page.locator('[data-test="provider-table"] tr', { hasText: N.provider })
  await expect(providerRow).toBeVisible()

  // 3) 进供应商详情建两个模型（provider）
  await providerRow.locator('[data-test^="manage-"]').click()
  // 3a) 对话模型（type 默认 chat）
  await page.locator('[data-test="model-create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.chatModel)
  await page.locator('[data-test="form-modelkey"]').fill('stub-chat')
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="model-table"]', { hasText: N.chatModel })).toBeVisible()
  // 3b) embedding 模型（type 选 embedding 单选）
  await page.locator('[data-test="model-create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.embedModel)
  await page.locator('[data-test="form-type"]').getByText('embedding', { exact: true }).click()
  await page.locator('[data-test="form-modelkey"]').fill('stub-embed')
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="model-table"]', { hasText: N.embedModel })).toBeVisible()

  // 4) 系统设置：选该 embedding 模型并保存（后端探测桩，必须 1024 维通过）
  await page.goto('/admin/settings')
  await page.locator('[data-test="embedding-select"]').click()
  await pickOption(page, N.embedModel)
  await page.locator('[data-test="save-embedding"]').click()
  await expect(page.getByText(/已保存/)).toBeVisible()

  // 5) 建知识库（knowledge）
  await page.goto('/knowledge')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.dataset)
  await page.locator('[data-test="form-submit"]').click()
  const dsRow = page.locator('[data-test="dataset-table"] tr', { hasText: N.dataset })
  await expect(dsRow).toBeVisible()

  // 6) 进库传文档，等状态就绪（经桩 embedding 向量化入库）
  await dsRow.locator('[data-test^="open-"]').click()
  await page.locator('input[type="file"]').setInputFiles('e2e/fixtures/kb-doc.txt')
  // 文档行出现 + 状态变「就绪」（web-first 断言自动重试，直到向量化完成）
  await expect(page.locator('[data-test^="doc-status-"]').first()).toHaveText('就绪', { timeout: 30_000 })

  // 7) 建对话应用（app）：选对话模型、绑库
  await page.goto('/app')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="form-name"]').fill(N.app)
  await page.locator('[data-test="form-model"]').click()
  await pickOption(page, N.chatModel)
  await page.locator('[data-test="form-datasets"]').click()
  await pickOption(page, N.dataset)
  await page.keyboard.press('Escape') // 关多选下拉
  await page.locator('[data-test="form-submit"]').click()
  const appRow = page.locator('[data-test="app-table"] tr', { hasText: N.app })
  await expect(appRow).toBeVisible()

  // 8) 进聊天发问（conversation）
  await appRow.locator('[data-test^="chat-"]').click()
  await expect(page).toHaveURL(/\/apps\/.*\/chat/)
  await page.locator('[data-test="chat-input"]').fill('ZEBRA-9137 是什么？')
  await page.locator('[data-test="chat-send"]').click()

  // 断言 A：出现引用折叠区，展开见「上传的真实文件名」+ [0,100]% 分数（钉后端产出，非桩答案字面）
  const sources = page.locator('[data-test="msg-sources"]')
  await expect(sources).toBeVisible({ timeout: 20_000 })
  await sources.locator('.el-collapse-item__header').click()
  const card = page.locator('[data-test="source-card"]').first()
  await expect(card).toContainText('kb-doc.txt')
  await expect(card).toContainText(/\d+%/)

  // 9) 刷新页面 → 引用仍在（证明 message.sources 真落库、history 读回渲染）
  await page.reload()
  const sourcesAfter = page.locator('[data-test="msg-sources"]')
  await expect(sourcesAfter).toBeVisible({ timeout: 20_000 })
  await sourcesAfter.locator('.el-collapse-item__header').click()
  await expect(page.locator('[data-test="source-card"]').first()).toContainText('kb-doc.txt')
})
```

- [ ] **Step 2: 跑旅程（本 Task 验收）**

Run: `cd web && pnpm e2e -- golden-journey.spec.ts`
Expected: 1 passed。常见卡点排查：
- embedding 保存报错 → 桩 `/v1/embeddings` 未返回 1024 维（回 Task 1）。
- 文档卡在「处理中」 → 系统 embedding 设置没保存成功，或桩没被调用（确认 baseUrl 与协议）。
- 选项点不中 → Element Plus 下拉是 teleport，用 `.el-select-dropdown__item`（已封装在 `pickOption`）。

- [ ] **Step 3: Commit**

```bash
git add web/e2e/golden-journey.spec.ts
git commit -m "test(e2e): KB 黄金旅程穿 5 模块，含刷新后引用持久化断言"
```

---

### Task 5: 反做假协议（DoD 变异实验 · 强制）

目的：证明旅程「真在守门」。逐项**真改坏源码→跑旅程→确认变红→改回**。**每项做完必须还原**，最终工作区干净。

**Files:**（实验期临时改，做完还原；不提交源码改动）
- 临时改：`web/e2e/stub/llm-stub.mjs`、`server/.../ConversationStore.java`、以及旅程绑库步骤

- [ ] **Step 1: 变异①——检索真在跑（正交向量）**

临时把桩 `STUB_VEC` 改成让相似度跌破阈值的向量：把 `Array(1024).fill(0.1)` 改为在 `/v1/embeddings` 里**按调用序号返回正交向量**（首次全 0.1、之后第 k 维为 1 其余 0）——最简单办法：改成对 embeddings 调用计数，query 与 chunk 落到不同基向量。
Run: `cd web && pnpm e2e -- golden-journey.spec.ts`
Expected: **FAIL**，断在「msg-sources 可见」——证明检索 SQL+阈值真在跑。记录后**还原桩**。

- [ ] **Step 2: 变异②——来源真落库（不写 sources）**

临时在 `ConversationStore.appendAssistant` 里把 `m.setSources(sources)` 注释掉。重启后端后：
Run: `cd web && pnpm e2e -- golden-journey.spec.ts`
Expected: **FAIL**，断在第 9 步「刷新后 msg-sources 可见」（live 阶段可能仍显示，但刷新读 history 时来源为空）——证明持久化链真被走。记录后**还原**。

- [ ] **Step 3: 变异③——绑定+注入真串起（解绑库）**

临时在旅程第 7 步**跳过 `form-datasets` 绑库**（注释那两行）。
Run: `cd web && pnpm e2e -- golden-journey.spec.ts`
Expected: **FAIL**，断在「msg-sources 可见」——证明 app↔dataset 绑定与 conversation 注入真串起。记录后**还原旅程**。

- [ ] **Step 4: 确认工作区干净 + 旅程复绿**

Run: `git status --short`（应无源码改动）；`cd web && pnpm e2e -- golden-journey.spec.ts`（应复 1 passed）。
Expected: 三处均已还原，旅程重新绿。

- [ ] **Step 5: 记录实验结果（无源码提交，仅记录进 Task 6 的文档）**

把三次变异的「改了什么 / 断在哪 / 已还原」记下来，供 Task 6 写入 self-check.md。

---

### Task 6: 文档入档 + 全量回归

**Files:**
- Modify: `docs/architecture/testing-standards.md`（新增「五、E2E（Playwright）」）
- Modify: `docs/self-check.md`（追加本轮 DoD 勾选 + 三次变异记录）

- [ ] **Step 1: testing-standards 补 E2E 节**

在 `testing-standards.md` 追加「五、E2E（Playwright）」小节，写清：本地 `pnpm e2e` 编排（reset-db 早于后端启动、webServer 三进程、健康 URL 等待）、假 LLM 桩约定（OpenAI 兼容 `/v1/embeddings` 固定 1024 维 + `/v1/chat/completions` SSE 固定答案、桩必须「傻」）、断言钉后端产出、反做假三尺子——作为 workflow/agent 后续 E2E 的标准模板。

- [ ] **Step 2: self-check 追加本轮记录**

在 `docs/self-check.md` 末尾追加：做了什么（Playwright 地基+桩+黄金旅程）、DoD 清单逐项勾选、三次故意搞错的「改坏→变红→还原」实测结论、以及验证命令（`pnpm e2e` 绿 / vitest 243+ 不受影响）。

- [ ] **Step 3: 前端单测回归（确认 e2e 未污染 vitest）**

Run: `cd web && pnpm vitest run`
Expected: 全绿（243 + Task 3 新增），`web/e2e/**` 不被 vitest 扫到。

- [ ] **Step 4: Commit**

```bash
git add docs/architecture/testing-standards.md docs/self-check.md
git commit -m "docs: E2E 入档 testing-standards + 本轮 DoD/变异实测记录"
```

---

## 手动验收（合并前）

`cd web && pnpm e2e` 一条命令：先重置 `hify_e2e`，Playwright 拉起全栈，冒烟 + 黄金旅程全绿（含刷新后引用仍在）。§5 三次变异均实测能变红、且已还原。dev 库 `hify` 未被触碰。

---

## Self-Review（对照 spec）

**Spec 覆盖**：§1 目录→Task 1/2/3；§2 桩→Task 1；§3 起停+重置→Task 2；§4 黄金旅程 8 步→Task 4（穿 identity/provider/knowledge/app/conversation + 系统设置 embedding 步）；§5 DoD+反做假三尺子→Task 5（变异实验）+ Task 4（断言钉后端产出/桩保持傻）；§6 范围/约束→Global Constraints；§7 文档→Task 6；§8 验收→手动验收节。全部有归属。

**补充说明**：spec §4 表格未显式列「设 embedding 系统设置」这一步，但正文机制要求（`getEmbeddingModel()` 读 system_setting `embedding_model_id`，不设则 `EMBEDDING_MODEL_NOT_CONFIGURED`）；plan Task 4 Step 1 第 4 步补齐，穿过 system 设置，属旅程必需、非新增范围。

**占位扫描**：无 TBD/TODO；桩/config/reset/journey 均给完整代码。变异实验①的「正交向量」给了实现方向（按调用计数返回不同基向量）+ 预期红点，属实验性临时改，不要求成品代码。

**类型/命名一致**：桩端口 8090、baseUrl `/v1`、向量 1024 维、`STUB_ANSWER`/`STUB_VEC` 常量贯穿一致；`data-test` 选择器均来自实测现有钩子，唯一新增 `doc-status-${id}` 在 Task 3 定义、Task 4 使用一致；admin 账密 `admin/e2e-admin-123` 在 application-e2e.yml、smoke、journey 三处一致。
