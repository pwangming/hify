import { request } from '@/api/request'
import type { AiModel, ModelForm } from '@/types/model'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/provider'

/** 列某供应商下的模型。后端：GET .../providers/{providerId}/models */
export function listModels(providerId: string) {
  return request.get<AiModel[]>(`${BASE}/providers/${providerId}/models`)
}

/** 在该供应商下建模型。后端：POST .../providers/{providerId}/models */
export function createModel(providerId: string, body: ModelForm) {
  return request.post<AiModel>(`${BASE}/providers/${providerId}/models`, body)
}

/** 改模型（只改 name+modelKey；type 不可改）。后端：PUT .../models/{id} */
export function updateModel(id: string, body: Pick<ModelForm, 'name' | 'modelKey'>) {
  return request.put<AiModel>(`${BASE}/models/${id}`, body)
}

/** 删除模型（逻辑删除）。后端：DELETE .../models/{id} */
export function deleteModel(id: string) {
  return request.delete<void>(`${BASE}/models/${id}`)
}

/** 启用模型。后端：POST .../models/{id}/enable */
export function enableModel(id: string) {
  return request.post<void>(`${BASE}/models/${id}/enable`)
}

/** 禁用模型。后端：POST .../models/{id}/disable */
export function disableModel(id: string) {
  return request.post<void>(`${BASE}/models/${id}/disable`)
}
