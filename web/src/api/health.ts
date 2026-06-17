import { request, type RequestConfig } from './request'

/**
 * 后端健康检查：GET /api/v1/health（统一 Result 信封，成功码 200，data 为纯文本提示）。
 * 走普通 request 实例，成功时 resolve 解包后的 data（如 "Hify is running"），失败 reject ApiError。
 * 调用方可传 { silent: true } 自行接管错误提示（见 request.ts）。
 */
export function getHealth(cfg?: RequestConfig) {
  return request.get<string>('/health', cfg)
}
