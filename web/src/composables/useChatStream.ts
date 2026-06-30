import { config } from '@/config'
import { TOKEN_KEY } from '@/api/request'

export interface ChatStreamHandlers {
  onDelta: (text: string) => void
  onDone: (conversationId: string, messageId: string,
           usage: { promptTokens: number; completionTokens: number }) => void
  onError: (err: { code: number; message: string }) => void
}

/** 对话流式：fetch + ReadableStream 读 SSE（非 EventSource——需带 JWT 头、POST 消息体）。 */
export function useChatStream() {
  let controller: AbortController | null = null

  async function start(
    appId: string, conversationId: string | null, content: string, h: ChatStreamHandlers,
  ): Promise<void> {
    controller = new AbortController()
    let res: Response
    try {
      res = await fetch(`${config.apiBaseUrl}/conversation/messages/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem(TOKEN_KEY) ?? ''}`,
        },
        body: JSON.stringify({ appId, conversationId, content }),
        signal: controller.signal,
      })
    } catch {
      h.onError({ code: -1, message: '网络异常，请稍后重试' })
      return
    }

    // 连接前错误：非 2xx，body 为 Result 失败信封
    if (!res.ok || !res.body) {
      const body = await res.json().catch(() => null)
      h.onError({ code: body?.code ?? -1, message: body?.message ?? '请求失败' })
      return
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        dispatch(block, h)
      }
    }
  }

  function dispatch(block: string, h: ChatStreamHandlers) {
    let event = 'message'
    let data = ''
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) return // 心跳注释行
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).trim()
    }
    if (!data) return
    const payload = JSON.parse(data)
    if (event === 'message') h.onDelta(payload.delta)
    else if (event === 'done') h.onDone(payload.conversationId, payload.messageId, payload.usage)
    else if (event === 'error') h.onError({ code: payload.code, message: payload.message })
  }

  function abort() {
    controller?.abort()
    controller = null
  }

  return { start, abort }
}
