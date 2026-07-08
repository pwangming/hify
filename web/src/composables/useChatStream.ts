import { config } from '@/config'
import { TOKEN_KEY } from '@/api/request'
import type { MessageSource } from '@/types/conversation'

export interface ChatStreamHandlers {
  /** 开场元信息：后端 openTurn 一落库即推送会话 id（先于任何增量）；断网重发据此走续聊不重复建会话 */
  onMeta?: (conversationId: string) => void
  /** 引用来源事件（Meta 后、首个 delta 前）；命中为空时后端不发本事件 */
  onSources?: (sources: MessageSource[]) => void
  onDelta: (text: string) => void
  onDone: (conversationId: string, messageId: string,
           usage: { promptTokens: number; completionTokens: number }) => void
  onError: (err: { code: number; message: string }) => void
}

/** 对话流式：fetch + ReadableStream 读 SSE（非 EventSource——需带 JWT 头、POST 消息体）。 */
export function useChatStream() {
  let controller: AbortController | null = null
  // 用户是否主动取消（切会话/卸载）。用显式标志区分「用户取消」与「网络断」，
  // 不靠 error.name==='AbortError'——网络断有时也报 AbortError，会被误当取消而静默吞掉。
  let aborted = false

  async function start(
    appId: string, conversationId: string | null, content: string, h: ChatStreamHandlers,
  ): Promise<void> {
    controller = new AbortController()
    aborted = false
    // 是否收到过 done/error 终态事件——决定「读到流尾」算正常结束还是中途断流。
    let terminated = false

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
      if (aborted) return
      // 网络层失败（fetch 抛 TypeError）的原始 message 是英文 "Failed to fetch"，对用户无意义——统一中文。
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
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let sep: number
        while ((sep = buffer.indexOf('\n\n')) !== -1) {
          const block = buffer.slice(0, sep)
          buffer = buffer.slice(sep + 2)
          if (dispatch(block, h)) terminated = true
        }
      }
    } catch {
      if (aborted) return
      h.onError({ code: -1, message: '网络异常，请稍后重试' })
      return
    }
    // 读到流尾但从未收到 done/error（中途断网导致的 EOF），且非用户主动取消 → 补一个网络错误。
    // 否则 store 的 send Promise 永不落定：气泡停在半截、发送态卡死转圈。
    if (!terminated && !aborted) {
      h.onError({ code: -1, message: '网络异常，请稍后重试' })
    }
  }

  /** 解析一个 SSE 事件块并分发；返回是否为终态事件（done/error）。 */
  function dispatch(block: string, h: ChatStreamHandlers): boolean {
    let event = 'message'
    let data = ''
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue // 心跳注释行
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data += line.slice(5).trim()
    }
    if (!data) return false
    const payload = JSON.parse(data)
    if (event === 'message') h.onDelta(payload.delta)
    else if (event === 'meta') h.onMeta?.(payload.conversationId)
    else if (event === 'sources') h.onSources?.(payload.sources)
    else if (event === 'done') { h.onDone(payload.conversationId, payload.messageId, payload.usage); return true }
    else if (event === 'error') { h.onError({ code: payload.code, message: payload.message }); return true }
    return false
  }

  function abort() {
    aborted = true
    controller?.abort()
    controller = null
  }

  return { start, abort }
}
