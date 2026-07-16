// 环境变量集中读取并导出类型化常量：类型转换、改名都只在这一处。
// 业务代码不要直接读 import.meta.env.*（见 frontend-standards.md 第 9 节）。
export const config = {
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL,
  apiTimeout: Number(import.meta.env.VITE_API_TIMEOUT),
  // 上传大文件（≤50MB）专用超时。
  uploadTimeoutMs: 120_000,
  // 试连接是真实 LLM 调用：后端非流式预算最长 120s，前端超时须 ≥ 后端预算（否则客户端先断）。
  llmTestTimeoutMs: 130_000,
  // workflow 调试运行可串多个 LLM 节点，比单次试连接更久；前端超时须 ≥ 后端预算（同 llmTestTimeoutMs 教训）。
  workflowRunTimeoutMs: 300_000,
  // MCP 注册/更新/试连接/刷新会在服务端现场连远端发现工具：
  // 后端预算 connect 5s + initialize 10s + listTools 30s ≈ 45s，前端超时须 ≥ 后端预算（同上教训）。
  mcpDiscoverTimeoutMs: 60_000,
  // 应用版本号（Vite define 从 package.json 注入，见 vite.config.ts）
  appVersion: __APP_VERSION__,
}
