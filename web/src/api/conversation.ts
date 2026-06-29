import { request } from '@/api/request'
import { config } from '@/config'
import type { MessageView, SendMessageResponse, ConversationView } from '@/types/conversation'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根。
const BASE = '/conversation/messages'

/**
 * 发消息（本轮一次性返回）。conversationId 传 null 表示新建会话。后端：POST /api/v1/conversation/messages
 *
 * 用独立的 chatApiTimeout（≥ 后端单次 LLM 预算 120s），不走全局 30s：否则真实模型较慢时前端会在
 * 后端跑完前 abort，而 openTurn 已先把会话落库 → 用户重试又建一条 → 侧边栏出现重复会话。
 */
export function sendMessage(appId: string, conversationId: string | null, content: string) {
  return request.post<SendMessageResponse>(
    BASE,
    { appId, conversationId, content },
    { timeout: config.chatApiTimeout },
  )
}

/** 拉某会话历史消息。后端：GET /api/v1/conversation/messages?conversationId= */
export function getMessages(conversationId: string) {
  return request.get<MessageView[]>(BASE, { params: { conversationId } })
}

/** 拉本人在某 app 下最近会话（不分页，最近 N 条）。后端：GET /api/v1/conversation/conversations?appId= */
export function listConversations(appId: string) {
  return request.get<ConversationView[]>('/conversation/conversations', { params: { appId } })
}
