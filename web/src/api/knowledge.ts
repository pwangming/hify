import { request } from '@/api/request'
import type { Dataset, DatasetForm } from '@/types/knowledge'
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
