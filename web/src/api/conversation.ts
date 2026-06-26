import { request } from '@/api/request'
import type { MessageView, SendMessageResponse } from '@/types/conversation'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根。
const BASE = '/conversation/messages'

/** 发消息（本轮一次性返回）。conversationId 传 null 表示新建会话。后端：POST /api/v1/conversation/messages */
export function sendMessage(appId: string, conversationId: string | null, content: string) {
  return request.post<SendMessageResponse>(BASE, { appId, conversationId, content })
}

/** 拉某会话历史消息。后端：GET /api/v1/conversation/messages?conversationId= */
export function getMessages(conversationId: string) {
  return request.get<MessageView[]>(BASE, { params: { conversationId } })
}
