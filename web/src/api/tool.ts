import { request } from '@/api/request'
import type { ToolOption } from '@/types/tool'

/** 列出可选（enabled）工具，供 Agent 配置页勾选。后端：GET /api/v1/tool/tools */
export function listTools() {
  return request.get<ToolOption[]>('/tool/tools')
}
