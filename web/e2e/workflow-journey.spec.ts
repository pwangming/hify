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
