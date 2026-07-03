import { request } from '@/api/request'
import type { ModelOption } from '@/types/model'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
// 成员侧 provider 资源（非 admin），放 api/ 根、不进 admin/。

/** 列出可用 chat 模型（供 app 弹窗模型选择器）。后端：GET /api/v1/provider/models?type=chat */
export function listChatModels() {
  return request.get<ModelOption[]>('/provider/models', { params: { type: 'chat' } })
}

/** 列出可用 embedding 模型（系统设置页选择器）。后端：GET /api/v1/provider/models?type=embedding */
export function listEmbeddingModels() {
  return request.get<ModelOption[]>('/provider/models', { params: { type: 'embedding' } })
}
