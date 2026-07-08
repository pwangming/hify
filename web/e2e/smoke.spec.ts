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
