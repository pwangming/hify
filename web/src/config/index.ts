// 环境变量集中读取并导出类型化常量：类型转换、改名都只在这一处。
// 业务代码不要直接读 import.meta.env.*（见 frontend-standards.md 第 9 节）。
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
}
