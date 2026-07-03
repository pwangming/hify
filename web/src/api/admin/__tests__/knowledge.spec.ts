import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { reembedAll } from '@/api/admin/knowledge'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('admin knowledge api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reembedAll → POST /admin/knowledge/documents/reembed', () => {
    reembedAll()
    expect(request.post).toHaveBeenCalledWith('/admin/knowledge/documents/reembed')
  })
})
