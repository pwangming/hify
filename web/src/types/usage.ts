/** 用量看板类型（对齐后端 usage/dto）。token 数为 string（Long 全局序列化），金额为 string（后端定长小数）。 */
export type RankDimension = 'app' | 'user' | 'model'

export interface UsageOverview {
  promptTokens: string
  completionTokens: string
  totalTokens: string
  callCount: string
  estimatedCost: string
  costIncomplete: boolean
}

export interface DailyUsagePoint {
  date: string
  promptTokens: string
  completionTokens: string
  callCount: string
  estimatedCost: string
}

export interface UsageRankingItem {
  targetId: string
  promptTokens: string
  completionTokens: string
  totalTokens: string
  callCount: string
  estimatedCost: string
}

export interface CallLogItem {
  id: string
  userId: string
  appId: string
  modelId: string
  promptTokens: string
  completionTokens: string
  source: 'conversation' | 'workflow' | null
  durationMs: number | null
  status: 'success' | 'failed'
  errorCode: string | null
  createTime: string
}

export interface CallLogPage {
  list: CallLogItem[]
  nextCursor: string | null
  hasMore: boolean
}

export interface CallLogQuery {
  startTime: string
  endTime: string
  userId?: string
  appId?: string
  modelId?: string
  source?: string
  cursor?: string
  limit?: number
}
