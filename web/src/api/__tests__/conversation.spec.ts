import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { getMessages, listConversations } from '@/api/conversation'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('conversation api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('getMessages → GET /conversation/messages?conversationId', () => {
    getMessages('100')
    expect(request.get).toHaveBeenCalledWith('/conversation/messages', {
      params: { conversationId: '100' },
    })
  })

  it('listConversations → GET /conversation/conversations?appId', () => {
    listConversations('7')
    expect(request.get).toHaveBeenCalledWith('/conversation/conversations', {
      params: { appId: '7' },
    })
  })
})
