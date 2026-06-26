/** 消息角色（对齐后端 message.role）。 */
export type MessageRole = 'user' | 'assistant'

/** 消息视图（对齐后端 MessageView）。id/token 为 string（Long 序列化防精度丢失）。 */
export interface MessageView {
  id: string
  role: MessageRole
  content: string
  promptTokens: string | null
  completionTokens: string | null
  createTime: string
}

/** 发消息响应（对齐后端 SendMessageResponse）。 */
export interface SendMessageResponse {
  conversationId: string
  message: MessageView
}
