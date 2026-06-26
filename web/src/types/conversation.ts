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
