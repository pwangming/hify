import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { getDraft, runWorkflow, saveDraft } from '@/api/workflow'
import { config } from '@/config'
import type { GraphDef } from '@/types/workflow'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const GRAPH: GraphDef = {
  nodes: [{ id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } }],
  edges: [],
}

describe('workflow api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getDraft → GET /workflow/apps/{appId}/draft', () => {
    getDraft('42')
    expect(request.get).toHaveBeenCalledWith('/workflow/apps/42/draft')
  })

  it('saveDraft → PUT /workflow/apps/{appId}/draft + graph 信封', () => {
    saveDraft('42', GRAPH)
    expect(request.put).toHaveBeenCalledWith('/workflow/apps/42/draft', { graph: GRAPH })
  })

  it('runWorkflow → POST /workflow/apps/{appId}/runs + inputs 信封 + 专用长超时', () => {
    runWorkflow('42', { city: '北京' })
    expect(request.post).toHaveBeenCalledWith(
      '/workflow/apps/42/runs',
      { inputs: { city: '北京' } },
      { timeout: config.workflowRunTimeoutMs },
    )
  })
})
