import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
import { useConversationStore } from '@/stores/conversation'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

const assistant = {
  id: '200', role: 'assistant' as const, content: '你好，我是助手',
  promptTokens: 12, completionTokens: 8, createTime: '2026-06-29T10:00:00+08:00',
}

describe('useConversationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loadConversations 填充列表', async () => {
    vi.mocked(listConversations).mockResolvedValue([
      { id: '1', title: '会话一', updateTime: '2026-06-29T10:00:00+08:00' },
    ])
    const store = useConversationStore()
    await store.loadConversations('7')
    expect(listConversations).toHaveBeenCalledWith('7')
    expect(store.conversations).toHaveLength(1)
  })

  it('loadMessages 设当前 id 并载入历史', async () => {
    vi.mocked(getMessages).mockResolvedValue([assistant])
    const store = useConversationStore()
    await store.loadMessages('100')
    expect(getMessages).toHaveBeenCalledWith('100')
    expect(store.currentId).toBe('100')
    expect(store.messages).toEqual([assistant])
  })

  it('newConversation 清空当前会话与消息', () => {
    const store = useConversationStore()
    store.currentId = '100'
    store.messages = [assistant]
    store.newConversation()
    expect(store.currentId).toBeNull()
    expect(store.messages).toEqual([])
  })

  it('send 新会话：先渲染用户气泡再追加助手回复，回写 currentId 并返回 cid', async () => {
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
    const store = useConversationStore()
    const cid = await store.send('7', '你好')
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好') // 新会话 currentId 为 null
    expect(cid).toBe('100')
    expect(store.currentId).toBe('100')
    expect(store.messages).toHaveLength(2) // user + assistant
    expect(store.messages[0].role).toBe('user')
    expect(store.messages[1]).toEqual(assistant)
  })

  it('send 续聊：复用 currentId', async () => {
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
    const store = useConversationStore()
    store.currentId = '100'
    await store.send('7', '第二句')
    expect(sendMessage).toHaveBeenCalledWith('7', '100', '第二句')
  })
})
