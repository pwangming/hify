import { test, expect } from '@playwright/test'
import { login, pickOption, uniqSuffix, waitStreamDone } from './support/ui'

const uniq = uniqSuffix()
const N = {
  provider: `e2e-stub-${uniq}`,
  chatModel: `e2e-chat-${uniq}`,
  embedModel: `e2e-embed-${uniq}`,
  dataset: `e2e-kb-${uniq}`,
  app: `e2e-app-${uniq}`,
}

test('KB 黄金旅程：配模型→建库→传文档→聊天→看引用→刷新仍在', async ({ page }) => {
  // 1) 登录（identity）
  await login(page)

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
  // data-test="chat-input" 挂在包装 div 上，真正可编辑的是内部 el-input 渲染的 textarea
  await page.locator('[data-test="chat-input"] textarea').fill('ZEBRA-9137 是什么？')
  await page.locator('[data-test="chat-send"]').click()

  // 断言 A：出现引用折叠区，展开见「上传的真实文件名」+ [0,100]% 分数（钉后端产出，非桩答案字面）
  const sources = page.locator('[data-test="msg-sources"]')
  await expect(sources).toBeVisible({ timeout: 20_000 })
  await sources.locator('.el-collapse-item__header').click()
  const card = page.locator('[data-test="source-card"]').first()
  await expect(card).toContainText('kb-doc.txt')
  await expect(card).toContainText(/\d+%/)

  // 9) 刷新页面 → 引用仍在（证明 message.sources 真落库、history 读回渲染）
  //    先等流结束：落库在流末尾，不等就 reload 会读到还没写入的 history（详见 waitStreamDone）
  await waitStreamDone(page)
  await page.reload()
  const sourcesAfter = page.locator('[data-test="msg-sources"]')
  await expect(sourcesAfter).toBeVisible({ timeout: 20_000 })
  await sourcesAfter.locator('.el-collapse-item__header').click()
  await expect(page.locator('[data-test="source-card"]').first()).toContainText('kb-doc.txt')
})
