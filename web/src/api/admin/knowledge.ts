import { request } from '@/api/request'

/** 全量重嵌入（含存量补嵌，花钱动作）。后端：POST /api/v1/admin/knowledge/documents/reembed */
export function reembedAll() {
  return request.post<void>('/admin/knowledge/documents/reembed')
}
