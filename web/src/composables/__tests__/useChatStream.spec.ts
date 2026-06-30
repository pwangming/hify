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

  it('error 事件 → onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
      'event:error\ndata:{"code":12003,"message":"模型供应商暂时不可用"}\n\n',
    ])))
    let err: { code: number } | null = null
    const { start } = useChatStream()
    await start('7', null, '你好', { onDelta: () => {}, onDone: () => {}, onError: (e) => { err = e } })
    expect(err?.code).toBe(12003)
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
})
