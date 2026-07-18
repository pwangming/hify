import { test, expect } from '@playwright/test'
import { login, pickOption, uniqSuffix } from './support/ui'

const uniq = uniqSuffix()
const N = {
  provider: `e2e-ag-prov-${uniq}`,
  chatModel: `e2e-ag-chat-${uniq}`,
  app: `e2e-ag-app-${uniq}`,
}
const TOOL_ROW = 'mcpdemo'

test('Agent 黄金旅程：接入MCP工具→建Agent应用→聊天出轨迹与终答→刷新轨迹仍在', async ({ page }) => {
  test.setTimeout(120_000)
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

  await page.goto('/admin/tool')
  await page.locator('[data-test="create-open"]').click()
  await page.locator('[data-test="type-mcp"]').click()
  await page.locator('[data-test="form-name"]').fill(TOOL_ROW)
  await page.locator('[data-test="form-description"]').fill('mcp demo tools')
  await page.locator('[data-test="form-url"]').fill('http://localhost:3100/mcp')
  await page.locator('[data-test="add-header"]').click()
  await page.locator('[data-test="header-name-0"]').fill('Authorization')
  await page.locator('[data-test="header-value-0"]').fill('Bearer hify-demo-token')
  await page.locator('[data-test="form-preview"]').click()
  await expect(page.locator('[data-test="mcp-tool-get_current_time"]')).toBeVisible({ timeout: 15_000 })
  await page.locator('[data-test="form-submit"]').click()
  await expect(page.locator('[data-test="tool-table"]')).toContainText(TOOL_ROW)

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

  await appRow.locator('[data-test^="chat-"]').click()
  await expect(page).toHaveURL(/\/apps\/.*\/chat/)
  await page.locator('[data-test="chat-input"] textarea').fill('现在几点了？')
  await page.locator('[data-test="chat-send"]').click()
  const trace = page.locator('[data-test="tool-trace"]')
  await expect(trace).toBeVisible({ timeout: 20_000 })
  await trace.locator('.el-collapse-item__header').click()
  await expect(trace).toContainText('mcpdemo__get_current_time')
  await expect(page.getByText(/这是最终回答/).first()).toBeVisible({ timeout: 20_000 })
  await page.reload()
  const traceAfter = page.locator('[data-test="tool-trace"]')
  await expect(traceAfter).toBeVisible({ timeout: 20_000 })
  await traceAfter.locator('.el-collapse-item__header').click()
  await expect(traceAfter).toContainText('mcpdemo__get_current_time')
})
