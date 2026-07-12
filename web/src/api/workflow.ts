import { request } from '@/api/request'
import { config } from '@/config'
import type { DraftResponse, GraphDef, RunResponse } from '@/types/workflow'

// baseURL 已含 /api/v1（见 api/request.ts）。draft 是单例子资源：GET 读 / PUT 全量写。
const BASE = '/workflow/apps'

/** 读草稿；未保存过时后端 data=null。后端：GET /api/v1/workflow/apps/{appId}/draft */
export function getDraft(appId: string) {
  return request.get<DraftResponse | null>(`${BASE}/${appId}/draft`)
}

/** 全量保存草稿（半成品可存，完整校验在触发运行时）。后端：PUT .../draft */
export function saveDraft(appId: string, graph: GraphDef) {
  return request.put<DraftResponse>(`${BASE}/${appId}/draft`, { graph })
}

/** 同步触发一次运行，跑完才返回（含 nodeRuns 明细）。后端：POST .../runs */
export function runWorkflow(appId: string, inputs: Record<string, unknown>) {
  return request.post<RunResponse>(`${BASE}/${appId}/runs`, { inputs }, {
    timeout: config.workflowRunTimeoutMs,
  })
}
