import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { getEmbeddingSetting, saveEmbeddingSetting } from '@/api/admin/provider'

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
})
