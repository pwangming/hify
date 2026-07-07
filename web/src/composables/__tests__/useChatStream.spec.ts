import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useChatStream } from '@/composables/useChatStream'

function sseResponse(frames: string[]): Response {
  const enc = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(c) { frames.forEach((f) => c.enqueue(enc.encode(f))); c.close() },
  })
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('useChatStream', () => {
  beforeEach(() => { localStorage.setItem('hify_token', 't') })

  it('解析 message → onDelta，done → onDone', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:message\ndata:{"delta":"你好，"}\n\n',
      'event:message\ndata:{"delta":"我是助手"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":12,"completionTokens":8}}\n\n',
    ])))
    const deltas: string[] = []
    let doneCid = ''
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onDelta: (t) => deltas.push(t),
      onDone: (cid) => { doneCid = cid },
      onError: () => {},
    })
    expect(deltas).toEqual(['你好，', '我是助手'])
    expect(doneCid).toBe('100')
  })

  it('meta → onMeta 先于 done 到达', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:meta\ndata:{"conversationId":"100"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":1,"completionTokens":1}}\n\n',
    ])))
    const order: string[] = []
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onMeta: (cid) => order.push(`meta:${cid}`),
      onDelta: () => {},
      onDone: (cid) => order.push(`done:${cid}`),
      onError: () => {},
    })
    expect(order).toEqual(['meta:100', 'done:100'])
  })

  it('未注册 onMeta 时 meta 事件静默忽略不炸', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:meta\ndata:{"conversationId":"100"}\n\n',
      'event:done\ndata:{"conversationId":"100","messageId":"200","usage":{"promptTokens":1,"completionTokens":1}}\n\n',
    ])))
    let doneCid = ''
    const { start } = useChatStream()
    await start('7', null, '你好', {
      onDelta: () => {}, onDone: (cid) => { doneCid = cid }, onError: () => {},
    })
    expect(doneCid).toBe('100')
  })

  it('error 事件 → onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:error\ndata:{"code":12003,"message":"模型供应商暂时不可用"}\n\n',
    ])))
    let err: { code: number } | null = null
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { err = e } })
    expect(err?.code).toBe(12003)
  })

  it('网络失败 → onError 用中文提示，不暴露原始英文', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    let msg = ''
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { msg = e.message } })
    expect(msg).toBe('网络异常，请稍后重试')
  })

  it('连接前非2xx → onError 解包 Result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 17001, message: '应用不可用', data: null }),
        { status: 400, headers: { 'Content-Type': 'application/json' } })))
    let err: { code: number } | null = null
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { err = e } })
    expect(err?.code).toBe(17001)
  })

  it('abort() 静默退出——不触发 onError', async () => {
    // 用可控 reader mock：read() 返回受控 promise，让测试手动注入 AbortError
    let rejectRead!: (reason: unknown) => void
    const readPromise = new Promise<ReadableStreamReadResult<Uint8Array>>((_, r) => { rejectRead = r })
    const mockReader = { read: vi.fn().mockReturnValue(readPromise) }

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      body: { getReader: () => mockReader },
    }))

    const errFn = vi.fn()
    const { start, abort } = useChatStream()

    // start() 会在 read() await 处挂住
    const started = start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: errFn })

    // 给 start 一个 tick 进入 read() await
    await Promise.resolve()
    abort()

    // 模拟浏览器行为：fetch 被 abort 后 reader.read() 拒绝为 AbortError
    rejectRead(new DOMException('The operation was aborted.', 'AbortError'))

    await started
    expect(errFn).not.toHaveBeenCalled()
  })
})
