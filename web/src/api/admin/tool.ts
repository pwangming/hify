import { request } from '@/api/request'
import type { ToolAdminItem, ToolAdminDetail, ToolForm, ToolPreview } from '@/types/tool'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/tool/tools'

/** 列出全部工具（含内置+自定义、含停用）。后端：GET /api/v1/admin/tool/tools */
export function listTools() {
  return request.get<ToolAdminItem[]>(BASE)
}

/** 工具详情（编辑回填）。后端：GET .../{id}（authHeaderNames 只回头名，绝不回明文值） */
export function getTool(id: string) {
  return request.get<ToolAdminDetail>(`${BASE}/${id}`)
}

/** 注册自定义工具。后端：POST .../ */
export function createTool(body: ToolForm) {
  return request.post<ToolAdminItem>(BASE, body)
}

/** 全量更新（头值留空=不改）。后端：PUT .../{id} */
export function updateTool(id: string, body: ToolForm) {
  return request.put<ToolAdminItem>(`${BASE}/${id}`, body)
}

/** 删除自定义工具。后端：DELETE .../{id} */
export function removeTool(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}

/** 启用。后端：POST .../{id}/enable */
export function enableTool(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}

/** 停用。后端：POST .../{id}/disable */
export function disableTool(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}

/** 预览解析（不落库）。后端：POST .../preview */
export function previewTool(specText: string) {
  return request.post<ToolPreview>(`${BASE}/preview`, { specText })
}
