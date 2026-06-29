// 环境变量集中读取并导出类型化常量：类型转换、改名都只在这一处。
// 业务代码不要直接读 import.meta.env.*（见 frontend-standards.md 第 9 节）。
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
  // 发消息（同步等真实模型返回）专用超时：须 ≥ 后端单次 LLM 预算（provider response_timeout_sec 默认 120s），
  // 否则前端会在后端跑完前 abort，造成「前端超时但后端已落库 → 重试建重复会话」。默认 125s。
  chatApiTimeout: Number(import.meta.env.VITE_CHAT_API_TIMEOUT) || 125000,
  // 应用版本号（Vite define 从 package.json 注入，见 vite.config.ts）
  appVersion: __APP_VERSION__,
}
