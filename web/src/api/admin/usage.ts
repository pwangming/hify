import { request } from '@/api/request'
import type {
  CallLogPage,
  CallLogQuery,
  DailyUsagePoint,
  RankDimension,
  UsageOverview,
  UsageRankingItem,
} from '@/types/usage'

const BASE = '/admin/usage'

export function fetchOverview(startDate: string, endDate: string) {
  return request.get<UsageOverview>(`${BASE}/stats/overview`, { params: { startDate, endDate } })
}

export function fetchDaily(startDate: string, endDate: string) {
  return request.get<DailyUsagePoint[]>(`${BASE}/stats/daily`, { params: { startDate, endDate } })
}

export function fetchRankings(
  dimension: RankDimension,
  startDate: string,
  endDate: string,
  limit = 10,
) {
  return request.get<UsageRankingItem[]>(`${BASE}/stats/rankings`, {
    params: { dimension, startDate, endDate, limit },
  })
}

export function fetchCallLogs(query: CallLogQuery) {
  return request.get<CallLogPage>(`${BASE}/call-logs`, { params: query })
}
