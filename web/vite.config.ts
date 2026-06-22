/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端代理目标用「非 VITE_ 前缀」变量读取，仅用于本地开发 proxy，
// 不会注入前端产物，避免泄漏（见 frontend-standards.md 第 9 节）。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.SERVER_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    css: {
      preprocessorOptions: {
        scss: {
          // 全局注入设计变量与 mixin，组件内无需重复 @use
          additionalData: `@use "@/styles/variables.scss" as *;\n@use "@/styles/mixins.scss" as *;\n`,
        },
      },
    },
    server: {
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/v1': { target: proxyTarget, changeOrigin: true },
      },
    },
    // 单元/组件测试（vitest）。复用上面的 alias 与 scss 注入，测试环境与构建一致。
    test: {
      // happy-dom 提供 localStorage / DOM，store 与组件测试都需要
      environment: 'happy-dom',
      // 测试就近放各目录的 __tests__/ 下（与 tsconfig 的 exclude 对齐）
      include: ['src/**/__tests__/**/*.{test,spec}.ts'],
    },
  }
})
