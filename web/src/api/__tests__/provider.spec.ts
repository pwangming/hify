import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { listChatModels } from '@/api/provider'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn() },
}))

describe('provider member api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listChatModels → GET /provider/models?type=chat', () => {
    listChatModels()
    expect(request.get).toHaveBeenCalledWith('/provider/models', { params: { type: 'chat' } })
  })
})
