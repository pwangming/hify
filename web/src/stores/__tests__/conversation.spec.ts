import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { getMessages, listConversations } from '@/api/conversation'
import { useChatStream } from '@/composables/useChatStream'
import { useConversationStore } from '@/stores/conversation'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

vi.mock('@/composables/useChatStream', () => ({ useChatStream: vi.fn() }))

const assistant = {
  id: '200', role: 'assistant' as const, content: '你好，我是助手',
  promptTokens: 12, completionTokens: 8, createTime: '2026-06-29T10:00:00+08:00',
}

describe('useConversationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    // 默认 useChatStream mock，确保非 send 测试中 chat 对象有效
    ;(useChatStream as unknown as Mock).mockReturnValue({ start: vi.fn(), abort: vi.fn() })
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

  it('loadMessages 抛错时保持 currentId 和 messages 一致', async () => {
    vi.mocked(getMessages).mockRejectedValue(new Error('fetch failed'))
    const store = useConversationStore()
    store.currentId = '1'
    store.messages = [assistant]
    await expect(store.loadMessages('999')).rejects.toThrow('fetch failed')
    expect(store.currentId).toBe('1')
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

  it('send：增量追加助手气泡，done 用真 id 替换并返回会话 id', async () => {
    const start = vi.fn(async (_a: unknown, _c: unknown, _t: unknown, h: { onDelta: (t: string) => void; onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void; onError: (e: { code: number; message: string }) => void }) => {
      h.onDelta('你好，'); h.onDelta('我是助手')
      h.onDone('100', '200', { promptTokens: 12, completionTokens: 8 })
    })
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    const cid = await store.send('7', '你好')

    expect(cid).toBe('100')
    expect(store.currentId).toBe('100')
    // 末气泡是拼接后的助手全文、id 为真值
    const last = store.messages[store.messages.length - 1]
    expect(last.role).toBe('assistant')
    expect(last.content).toBe('你好，我是助手')
    expect(last.id).toBe('200')
    expect(store.sending).toBe(false)
  })

  it('send：error 时占位气泡内联错误、sending 复位', async () => {
    const start = vi.fn(async (_a: unknown, _c: unknown, _t: unknown, h: { onDelta: (t: string) => void; onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void; onError: (e: { code: number; message: string }) => void }) => h.onError({ code: 12003, message: '模型供应商暂时不可用' }))
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    await store.send('7', '你好').catch(() => {})

    const last = store.messages[store.messages.length - 1]
    expect(last.content).toContain('模型供应商暂时不可用')
    expect(store.sending).toBe(false)
  })
})
