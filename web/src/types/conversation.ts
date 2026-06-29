/** 消息角色（对齐后端 message.role）。 */
export type MessageRole = 'user' | 'assistant'

/** 消息视图（对齐后端 MessageView）。id 为 string（Long）；token 为 number（Integer）。 */
export interface MessageView {
  id: string
  role: MessageRole
  content: string
  promptTokens: number | null
  completionTokens: number | null
  createTime: string
}

/** 发消息响应（对齐后端 SendMessageResponse）。 */
export interface SendMessageResponse {
  conversationId: string
  message: MessageView
}

/** 会话列表项（对齐后端 ConversationView）。id 为 string（Long）；title 可空；updateTime 为最近活跃时间。 */
export interface ConversationView {
  id: string
  title: string | null
  updateTime: string
}
