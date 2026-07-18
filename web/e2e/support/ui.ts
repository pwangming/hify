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
