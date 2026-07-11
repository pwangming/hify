import { request } from '@/api/request'
import type { App, AppForm, AppType, PageResult } from '@/types/app'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根（不进 admin/）。
const BASE = '/app/apps'

/** 列表（页码分页）。后端：GET /api/v1/app/apps?keyword=&type=&page=&size= */
export function listApps(params: { keyword?: string; type?: AppType; page: number; size: number }) {
  return request.get<PageResult<App>>(BASE, { params })
}

/** 详情。后端：GET .../{id} */
export function getApp(id: string) {
  return request.get<App>(`${BASE}/${id}`)
}

/** 新建。type 缺省 chat；画布轮起支持 workflow。后端：POST /api/v1/app/apps */
export function createApp(body: AppForm, type: AppType = 'chat') {
  return request.post<App>(BASE, { ...body, type })
}

/** 全量更新。后端：PUT .../{id} */
export function updateApp(id: string, body: AppForm) {
  return request.put<App>(`${BASE}/${id}`, body)
}

/** 删除（逻辑删除）。后端：DELETE .../{id} */
export function deleteApp(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}

/** 启用。后端：POST .../{id}/enable */
export function enableApp(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}

/** 停用。后端：POST .../{id}/disable */
export function disableApp(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}
