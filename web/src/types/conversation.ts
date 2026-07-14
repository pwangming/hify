/** 消息角色（对齐后端 message.role）。 */
export type MessageRole = 'user' | 'assistant'

/** 引用来源快照（对齐后端 MessageSource）。id 类为 string（Long）；score 为 number（0~1）。 */
export interface MessageSource {
  chunkId: string
  documentId: string
  documentName: string
  score: number
  preview: string
}

/** Agent 工具调用轨迹（对齐后端 MessageToolCall / 落库 message.tool_calls）。 */
export interface MessageToolCall {
  name: string
  args: string
  result: string
}

/** 消息视图（对齐后端 MessageView）。id 为 string（Long）；token 为 number（Integer）。 */
export interface MessageView {
  id: string
  role: MessageRole
  content: string
  promptTokens: number | null
  completionTokens: number | null
  createTime: string
  /** 引用来源；未绑库/降级/无命中为空数组或缺省。 */
  sources?: MessageSource[]
  /** Agent 工具调用轨迹；普通聊天为空数组或缺省。 */
  toolCalls?: MessageToolCall[]
  /** 客户端专用：流式失败时的错误文案（红色高亮渲染），后端不返回。 */
  error?: string
}

/** 会话列表项（对齐后端 ConversationView）。id 为 string（Long）；title 可空；updateTime 为最近活跃时间。 */
export interface ConversationView {
  id: string
  title: string | null
  updateTime: string
}
