import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { listChatModels, listEmbeddingModels } from '@/api/provider'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn() },
}))

describe('provider member api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listChatModels → GET /provider/models?type=chat', () => {
    listChatModels()
    expect(request.get).toHaveBeenCalledWith('/provider/models', { params: { type: 'chat' } })
  })
  it('listEmbeddingModels → GET /provider/models?type=embedding', () => {
    listEmbeddingModels()
    expect(request.get).toHaveBeenCalledWith('/provider/models', { params: { type: 'embedding' } })
  })
})
