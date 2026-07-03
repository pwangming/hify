import { request } from '@/api/request'
import { config } from '@/config'
import type { Dataset, DatasetForm, KbDocument, Chunk } from '@/types/knowledge'
import type { PageResult } from '@/types/app'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根（不进 admin/）。
const BASE = '/knowledge/datasets'

/** 列表（页码分页）。后端：GET /api/v1/knowledge/datasets?keyword=&page=&size= */
export function listDatasets(params: { keyword?: string; page: number; size: number }) {
  return request.get<PageResult<Dataset>>(BASE, { params })
}

/** 详情。后端：GET .../{id} */
export function getDataset(id: string) {
  return request.get<Dataset>(`${BASE}/${id}`)
}

/** 新建。后端：POST /api/v1/knowledge/datasets */
export function createDataset(body: DatasetForm) {
  return request.post<Dataset>(BASE, body)
}

/** 全量更新。后端：PUT .../{id} */
export function updateDataset(id: string, body: DatasetForm) {
  return request.put<Dataset>(`${BASE}/${id}`, body)
}

/** 删除（逻辑删除）。后端：DELETE .../{id} */
export function deleteDataset(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}

const DOC_BASE = '/knowledge/documents'

/** 上传文档（multipart，字段名 file）。后端：POST /api/v1/knowledge/datasets/{id}/documents */
export function uploadDocument(datasetId: string, file: File) {
  const fd = new FormData()
  fd.append('file', file)
  return request.post<KbDocument>(`${BASE}/${datasetId}/documents`, fd, { timeout: config.uploadTimeoutMs })
}

/** 文档分页列表。后端：GET .../datasets/{id}/documents */
export function listDocuments(datasetId: string, params: { page: number; size: number }) {
  return request.get<PageResult<KbDocument>>(`${BASE}/${datasetId}/documents`, { params })
}

/** 删除文档（级联软删分段）。后端：DELETE /api/v1/knowledge/documents/{id} */
export function deleteDocument(id: string) {
  return request.delete<void>(`${DOC_BASE}/${id}`)
}

/** 重试 failed 文档（断点续嵌）。后端：POST /api/v1/knowledge/documents/{id}/retry */
export function retryDocument(id: string) {
  return request.post<void>(`${DOC_BASE}/${id}/retry`)
}

/** 分段分页列表（预览）。后端：GET .../documents/{id}/chunks */
export function listChunks(documentId: string, params: { page: number; size: number }) {
  return request.get<PageResult<Chunk>>(`${DOC_BASE}/${documentId}/chunks`, { params })
}
