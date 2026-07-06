// 环境变量集中读取并导出类型化常量：类型转换、改名都只在这一处。
// 业务代码不要直接读 import.meta.env.*（见 frontend-standards.md 第 9 节）。
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
  // 上传大文件（≤50MB）专用超时。
  uploadTimeoutMs: 120_000,
  // 试连接是真实 LLM 调用：后端非流式预算最长 120s，前端超时须 ≥ 后端预算（否则客户端先断）。
  llmTestTimeoutMs: 130_000,
  // 应用版本号（Vite define 从 package.json 注入，见 vite.config.ts）
  appVersion: __APP_VERSION__,
}
