import { request } from '@/api/request'
import { config } from '@/config'
import type { EmbeddingSetting } from '@/types/model'
import type { Provider, ProviderForm, ProviderTestResult } from '@/types/provider'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/provider/providers'

/** 列出全部提供商。后端：GET /api/v1/admin/provider/providers */
export function listProviders() {
  return request.get<Provider[]>(BASE)
}

/** 新建提供商。后端：POST /api/v1/admin/provider/providers */
export function createProvider(body: ProviderForm) {
  return request.post<Provider>(BASE, body)
}

/** 编辑提供商（全量；apiKey 留空=不改密钥）。后端：PUT .../{id} */
export function updateProvider(id: string, body: ProviderForm) {
  return request.put<Provider>(`${BASE}/${id}`, body)
}

/** 启用提供商。后端：POST .../{id}/enable */
export function enableProvider(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}

/** 禁用提供商。后端：POST .../{id}/disable */
export function disableProvider(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}

/** 删除提供商（逻辑删除）。后端：DELETE .../{id} */
export function deleteProvider(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}

/** 试连接：后端自动挑一个启用模型真实调用并落库。后端：POST .../{id}/test */
export function testProvider(id: string) {
  return request.post<ProviderTestResult>(`${BASE}/${id}/test`, undefined, {
    timeout: config.llmTestTimeoutMs,
  })
}

/** 查系统 embedding 模型设置。后端：GET /api/v1/admin/provider/settings/embedding-model */
export function getEmbeddingSetting() {
  return request.get<EmbeddingSetting>('/admin/provider/settings/embedding-model')
}

/** 设系统 embedding 模型。后端保存时真实探测验 1024 维。 */
export function saveEmbeddingSetting(modelId: string) {
  return request.put<EmbeddingSetting>('/admin/provider/settings/embedding-model', { modelId })
}
