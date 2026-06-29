import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('conversation api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sendMessage → POST /conversation/messages + body', () => {
    sendMessage('7', null, '你好')
    expect(request.post).toHaveBeenCalledWith('/conversation/messages', {
      appId: '7', conversationId: null, content: '你好',
    })
  })

  it('sendMessage 续聊带 conversationId', () => {
    sendMessage('7', '100', '继续')
    expect(request.post).toHaveBeenCalledWith('/conversation/messages', {
      appId: '7', conversationId: '100', content: '继续',
    })
  })

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
