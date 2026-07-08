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
