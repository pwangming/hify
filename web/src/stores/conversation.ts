import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMessages, listConversations } from '@/api/conversation'
import { useChatStream } from '@/composables/useChatStream'
import type { MessageView, ConversationView } from '@/types/conversation'

/**
 * 会话运行态：侧边栏列表 + 当前会话消息 + 当前会话 id。个人数据，仅本人可见。
 * 当前会话 id 的「真相」在 URL query（见 ChatView），本 store 只持运行态，不做持久化。
 */
export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref<ConversationView[]>([])
  const messages = ref<MessageView[]>([])
  const currentId = ref<string | null>(null)
  const loadingList = ref(false)
  const sending = ref(false)
  const chat = useChatStream()

  // ── Render-pacing state ──────────────────────────────────────────────────
  // Buffer SSE deltas; drain timer moves a chunk each tick for smooth typewriter effect.
  let pendingText = ''
  let drainTimer: ReturnType<typeof setInterval> | null = null

  /** Clears the drain timer (no-op if already cleared). */
  function clearDrainTimer() {
    if (drainTimer !== null) {
      clearInterval(drainTimer)
      drainTimer = null
    }
  }

  /** 拉侧边栏会话列表（最近 N 条，不分页）。 */
  async function loadConversations(appId: string) {
    loadingList.value = true
    try {
      conversations.value = await listConversations(appId)
    } finally {
      loadingList.value = false
    }
  }

  /** 载入某会话历史到聊天区（切换/刷新恢复时调用）。 */
  async function loadMessages(conversationId: string) {
    const loaded = await getMessages(conversationId)
    currentId.value = conversationId
    messages.value = loaded
  }

  /** 进入「新会话」空白态（不发请求）。 */
  function newConversation() {
    currentId.value = null
    messages.value = []
  }

  /**
   * 流式发送：推用户气泡 + 空助手占位 → 增量追加（打字机） → done 替换为真值。
   * 返回本次会话 id（新会话由后端生成），交调用方写回 URL 并刷新列表。
   *
   * Render-pacing: SSE deltas are buffered in `pendingText` and drained into the
   * visible bubble by a 30ms interval timer so text appears at a smooth readable
   * cadence rather than in raw network bursts.  `onDone` / `onError` / `abort()`
   * all flush + clear the timer so there are no leaks.
   */
  function send(appId: string, content: string): Promise<string> {
    messages.value.push({
      id: `local-${Date.now()}`, role: 'user', content,
      promptTokens: null, completionTokens: null, createTime: new Date().toISOString(),
    })
    const idx = messages.value.push({
      id: `local-asst-${Date.now()}`, role: 'assistant', content: '',
      promptTokens: null, completionTokens: null, createTime: new Date().toISOString(),
    }) - 1
    sending.value = true

    // Reset pacing state for this send
    clearDrainTimer()
    pendingText = ''

    // Adaptive drain: take a proportional slice each tick so a long fast response
    // still finishes promptly while short ones type out visibly (~60–120 chars/s).
    drainTimer = setInterval(() => {
      if (pendingText.length === 0) return
      const take = Math.max(2, Math.ceil(pendingText.length / 8))
      messages.value[idx].content += pendingText.slice(0, take)  // array proxy → reactive
      pendingText = pendingText.slice(take)
    }, 30)

    return new Promise<string>((resolve, reject) => {
      const onError = (err: { code: number; message: string }) => {
        // Flush remaining buffer first so partial content is visible before the ⚠️
        if (pendingText.length > 0) {
          messages.value[idx].content += pendingText
          pendingText = ''
        }
        clearDrainTimer()
        const cur = messages.value[idx].content
        messages.value[idx].content = cur ? `${cur}\n⚠️ ${err.message}` : `⚠️ ${err.message}`
        sending.value = false
        reject(err)
      }
      chat.start(appId, currentId.value, content, {
        onDelta: (t) => { pendingText += t },   // buffer; drain timer writes to bubble
        onDone: (conversationId, messageId, usage) => {
          // Flush all remaining buffered text before committing the final id/usage
          if (pendingText.length > 0) {
            messages.value[idx].content += pendingText
            pendingText = ''
          }
          clearDrainTimer()
          messages.value[idx].id = messageId
          messages.value[idx].promptTokens = usage.promptTokens
          messages.value[idx].completionTokens = usage.completionTokens
          currentId.value = conversationId
          sending.value = false
          resolve(conversationId)
        },
        onError,
      }).catch((e: unknown) => onError({ code: -1, message: (e as Error)?.message ?? '网络异常，请稍后重试' }))
    })
  }

  /** 切会话/卸载时止血：取消在途流，同时清除 drain timer 防止泄漏。 */
  function abort() {
    chat.abort()
    clearDrainTimer()
    sending.value = false
  }

  return {
    conversations,
    messages,
    currentId,
    loadingList,
    sending,
    loadConversations,
    loadMessages,
    newConversation,
    send,
    abort,
  }
})
