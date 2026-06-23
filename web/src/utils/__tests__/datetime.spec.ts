import { describe, it, expect } from 'vitest'
import { formatDateTime } from '@/utils/datetime'

describe('formatDateTime', () => {
  // 用本地时间分量构造 Date，断言也按本地时间——避免依赖运行环境时区，纯验「格式化/补零」逻辑。
  it('格式化为 年-月-日 时:分:秒', () => {
    const d = new Date(2026, 5, 20, 10, 0, 0) // 月份 0 起算：5=六月
    expect(formatDateTime(d)).toBe('2026-06-20 10:00:00')
  })

  it('个位数的月/日/时/分/秒补零', () => {
    const d = new Date(2026, 0, 5, 9, 3, 7) // 0=一月
    expect(formatDateTime(d)).toBe('2026-01-05 09:03:07')
  })

  it('接受 ISO-8601 字符串（后端 createTime 的形态）', () => {
    // 同一时刻用本地分量构造一个等价 Date 作为期望，规避时区差异
    const local = new Date(2026, 5, 20, 10, 0, 0)
    const iso = local.toISOString() // 带 Z 的 UTC 串，parse 回来是同一时刻
    expect(formatDateTime(iso)).toBe('2026-06-20 10:00:00')
  })

  it('空值或非法时间返回占位符 -', () => {
    expect(formatDateTime('')).toBe('-')
    expect(formatDateTime('not-a-date')).toBe('-')
  })
})
