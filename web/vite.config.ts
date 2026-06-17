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
  }
})
