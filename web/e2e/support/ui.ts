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

/**
 * 等聊天流式结束——**刷新页面前必须先等它**。
 *
 * <p>聊天走 SSE：内容与来源/轨迹在流式过程中就推给前端并渲染，但**消息落库发生在流末尾**
 * （ConversationService 的事务 B）。断言完流式内容就立刻 reload，库里可能还没有这条消息，
 * history 读回为空 —— 表现为刷新后引用/轨迹「找不到元素」。本地够快压不出来，CI 慢机器会露头
 * （2026-07-19 KB 旅程首次 flaky 即此因）。
 *
 * <p>用发送按钮是否可用作信号：ChatView 里 `sending` 是唯一闸门（store 的 onDone/onError/abort
 * 三个出口都会置回 false），按钮 `:disabled="sending"`，所以按钮恢复可用 = 流真的结束了。
 */
export async function waitStreamDone(page: Page) {
  await expect(page.locator('[data-test="chat-send"]')).toBeEnabled({ timeout: 30_000 })
}

/** Element Plus 下拉选项渲染在 body 的 teleport 层，按可见文本点。 */
export async function pickOption(page: Page, text: string) {
  await page.locator('.el-select-dropdown__item', { hasText: text }).first().click()
}
