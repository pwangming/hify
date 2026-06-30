import { request } from '@/api/request'
import type { MessageView, ConversationView } from '@/types/conversation'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根。
const BASE = '/conversation/messages'

/** 拉某会话历史消息。后端：GET /api/v1/conversation/messages?conversationId= */
export function getMessages(conversationId: string) {
  return request.get<MessageView[]>(BASE, { params: { conversationId } })
}

/** 拉本人在某 app 下最近会话（不分页，最近 N 条）。后端：GET /api/v1/conversation/conversations?appId= */
export function listConversations(appId: string) {
  return request.get<ConversationView[]>('/conversation/conversations', { params: { appId } })
}
