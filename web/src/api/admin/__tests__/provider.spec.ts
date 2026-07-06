import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { config } from '@/config'
import { getEmbeddingSetting, saveEmbeddingSetting, testProvider } from '@/api/admin/provider'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('admin provider settings api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getEmbeddingSetting → GET /admin/provider/settings/embedding-model', () => {
    getEmbeddingSetting()
    expect(request.get).toHaveBeenCalledWith('/admin/provider/settings/embedding-model')
  })

  it('saveEmbeddingSetting → PUT + body', () => {
    saveEmbeddingSetting('6')
    expect(request.put).toHaveBeenCalledWith('/admin/provider/settings/embedding-model', { modelId: '6' })
  })

  it('testProvider → POST /{id}/test 且带 LLM 专用超时', () => {
    testProvider('7')
    expect(request.post).toHaveBeenCalledWith('/admin/provider/providers/7/test', undefined, {
      timeout: config.llmTestTimeoutMs,
    })
  })
})
