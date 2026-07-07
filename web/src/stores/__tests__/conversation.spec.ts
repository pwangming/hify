import { describe, it, expect, beforeEach, afterEach, vi, type Mock } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { getMessages, listConversations, deleteConversation, renameConversation } from '@/api/conversation'
import { useChatStream } from '@/composables/useChatStream'
import { useConversationStore } from '@/stores/conversation'

vi.mock('@/api/conversation', () => ({
  getMessages: vi.fn(),
  listConversations: vi.fn(),
  deleteConversation: vi.fn(),
  renameConversation: vi.fn(),
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

  it('renameConversation 调后端并本地回显标题', async () => {
    vi.mocked(renameConversation).mockResolvedValue(undefined)
    const store = useConversationStore()
    store.conversations = [{ id: '1', title: '旧', updateTime: 'x' }]
    await store.renameConversation('1', '新')
    expect(renameConversation).toHaveBeenCalledWith('1', '新')
    expect(store.conversations[0].title).toBe('新')
  })

  it('deleteConversation 移除列表项；删当前会话回到空白态', async () => {
    vi.mocked(deleteConversation).mockResolvedValue(undefined)
    const store = useConversationStore()
    store.conversations = [{ id: '1', title: 'a', updateTime: 'x' }]
    store.currentId = '1'
    store.messages = [assistant]
    await store.deleteConversation('1')
    expect(deleteConversation).toHaveBeenCalledWith('1')
    expect(store.conversations).toHaveLength(0)
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
    expect(last.error).toBe('模型供应商暂时不可用') // 错误进 error 字段（红块渲染）
    expect(store.sending).toBe(false)
  })

  it('meta 先到即记 currentId——断流重发不再新建会话', async () => {
    const start = vi.fn(async (
      _a: unknown,
      _c: unknown,
      _t: unknown,
      h: { onMeta?: (cid: string) => void; onError: (e: { code: number; message: string }) => void },
    ) => {
      h.onMeta?.('100')
      h.onError({ code: -1, message: '网络异常' })
    })
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    await store.send('7', '你好').catch(() => {})

    expect(store.currentId).toBe('100')
  })

  // Fix 1: start() 自身 reject（未调用任何回调）时 send() 也应 reject，sending 复位，气泡内联错误
  it('send：start() reject 时 send() 拒绝、sending 复位、气泡内联错误', async () => {
    const start = vi.fn(() => Promise.reject(new Error('fetch 失败')))
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    await expect(store.send('7', '你好')).rejects.toMatchObject({ message: 'fetch 失败' })
    expect(store.sending).toBe(false)
    const last = store.messages[store.messages.length - 1]
    expect(last.error).toBe('fetch 失败') // 错误进独立 error 字段（红色高亮渲染），不再拼进 content
  })

  // Fix 2: 已有增量内容再报错，错误应追加而非丢弃
  it('send：部分增量后 error → 内容追加错误文本', async () => {
    const start = vi.fn(async (_a: unknown, _c: unknown, _t: unknown, h: { onDelta: (t: string) => void; onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void; onError: (e: { code: number; message: string }) => void }) => {
      h.onDelta('部分内容')
      h.onError({ code: 12003, message: '服务中断' })
    })
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    await store.send('7', '你好').catch(() => {})

    const last = store.messages[store.messages.length - 1]
    expect(last.content).toBe('部分内容') // 已生成正文原样保留
    expect(last.error).toBe('服务中断')   // 错误单列，不污染正文
    expect(store.sending).toBe(false)
  })

  // Fix 3: abort() 调用 useChatStream 的 abort 并重置 sending
  it('abort() 调用 chat.abort 并重置 sending', () => {
    const abortFn = vi.fn()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start: vi.fn(), abort: abortFn })

    const store = useConversationStore()
    store.sending = true
    store.abort()

    expect(abortFn).toHaveBeenCalledOnce()
    expect(store.sending).toBe(false)
  })

  // Fix 4: 续聊路径 — currentId 已有值时，send() 把它传给 chat.start 的第二个参数
  it('send：currentId 已有值时作为 conversationId 传给 chat.start', async () => {
    const start = vi.fn(async (_a: unknown, _c: unknown, _t: unknown, h: { onDelta: (t: string) => void; onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void; onError: (e: { code: number; message: string }) => void }) => {
      h.onDone('existing-id', '201', { promptTokens: 5, completionTokens: 3 })
    })
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    store.currentId = 'existing-id'
    await store.send('7', '续聊消息')

    // 第二个参数应是已有的 currentId
    expect(start).toHaveBeenCalledWith('7', 'existing-id', '续聊消息', expect.any(Object))
  })
})

/** ─── Render-pacing tests (fake timers) ─────────────────────────────────── */
describe('send render-pacing (throttle)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start: vi.fn(), abort: vi.fn() })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** 辅助：返回一个永不 resolve 的 start mock，并通过 capturedH 让外部手动触发回调 */
  function makeControlledStart() {
    let capturedH: {
      onDelta: (t: string) => void
      onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void
      onError: (e: { code: number; message: string }) => void
    }
    const start = vi.fn(
      (
        _a: unknown,
        _c: unknown,
        _t: unknown,
        h: typeof capturedH,
      ) => {
        capturedH = h
        return new Promise<void>(() => {}) // never resolves; we drive it via capturedH
      },
    )
    return { start, getH: () => capturedH! }
  }

  it('onDelta 入缓冲区，不立即渲染到气泡', () => {
    vi.useFakeTimers()
    const { start, getH } = makeControlledStart()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    void store.send('7', '你好').catch(() => {})

    getH().onDelta('你好，我是助手')
    const bubbleIdx = store.messages.length - 1
    // Immediately after onDelta: buffer holds text, bubble is still empty
    expect(store.messages[bubbleIdx].content).toBe('')
  })

  it('timer tick 后气泡逐渐累积，最终含全部增量文本', () => {
    vi.useFakeTimers()
    const { start, getH } = makeControlledStart()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    void store.send('7', '你好').catch(() => {})

    getH().onDelta('你好，我是助手') // 7 chars total
    const bubbleIdx = store.messages.length - 1

    // Pre-drain: bubble empty
    expect(store.messages[bubbleIdx].content).toBe('')

    // After one 30ms tick: some chars drain but not all (take = max(2, ceil(7/8)) = 2 < 7)
    vi.advanceTimersByTime(30)
    const afterOneTick = store.messages[bubbleIdx].content
    expect(afterOneTick.length).toBeGreaterThan(0)
    expect(afterOneTick.length).toBeLessThan('你好，我是助手'.length)

    // After enough ticks: fully drained
    vi.advanceTimersByTime(500)
    expect(store.messages[bubbleIdx].content).toBe('你好，我是助手')
  })

  it('onDone 立即冲刷缓冲并替换 messageId，不等 timer', async () => {
    vi.useFakeTimers()
    const { start, getH } = makeControlledStart()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    const sendPromise = store.send('7', '你好')
    const bubbleIdx = store.messages.length - 1

    getH().onDelta('你好，我是助手')
    // Still buffered — timer hasn't fired
    expect(store.messages[bubbleIdx].content).toBe('')

    // onDone must flush synchronously before setting id/usage
    getH().onDone('100', '200', { promptTokens: 12, completionTokens: 8 })
    expect(store.messages[bubbleIdx].content).toBe('你好，我是助手')
    expect(store.messages[bubbleIdx].id).toBe('200')
    expect(store.sending).toBe(false)

    const cid = await sendPromise
    expect(cid).toBe('100')
  })

  it('onError 立即冲刷缓冲并单列错误，之后 timer 不再改变气泡', async () => {
    vi.useFakeTimers()
    const { start, getH } = makeControlledStart()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })

    const store = useConversationStore()
    const sendPromise = store.send('7', '你好').catch((e) => e)
    const bubbleIdx = store.messages.length - 1

    getH().onDelta('你好，我是助手')
    // 仍在缓冲，timer 未触发
    expect(store.messages[bubbleIdx].content).toBe('')

    // onError 必须先冲刷缓冲（正文完整保留），错误进 error 字段（红块渲染）
    getH().onError({ code: 500, message: '服务器错误' })
    expect(store.messages[bubbleIdx].content).toBe('你好，我是助手')
    expect(store.messages[bubbleIdx].error).toBe('服务器错误')
    expect(store.sending).toBe(false)

    // timer 已清除：继续推进不再改变气泡
    const contentAfterError = store.messages[bubbleIdx].content
    vi.advanceTimersByTime(1000)
    expect(store.messages[bubbleIdx].content).toBe(contentAfterError)

    await sendPromise // 应 settle(reject) 而不挂起
  })

  it('abort() 清除 drain timer，之后 tick 不再改变气泡', () => {
    vi.useFakeTimers()
    const abortFn = vi.fn()
    const { start, getH } = makeControlledStart()
    ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: abortFn })

    const store = useConversationStore()
    void store.send('7', '你好').catch(() => {})
    const bubbleIdx = store.messages.length - 1

    getH().onDelta('你好，我是助手')
    expect(store.messages[bubbleIdx].content).toBe('')

    // Abort — must clear the drain timer
    store.abort()
    expect(abortFn).toHaveBeenCalledOnce()
    expect(store.sending).toBe(false)

    // Advancing timers must NOT mutate the bubble further
    const contentAfterAbort = store.messages[bubbleIdx].content
    vi.advanceTimersByTime(1000)
    expect(store.messages[bubbleIdx].content).toBe(contentAfterAbort)
  })
})
