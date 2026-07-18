import { describe, expect, it, vi } from 'vitest'
import { useNameMaps } from '../useNameMaps'

vi.mock('@/api/admin/user', () => ({
  listUsers: vi.fn().mockResolvedValue([{ id: '1', username: '张三' }]),
}))
vi.mock('@/api/app', () => ({
  listApps: vi.fn().mockResolvedValue({
    list: [{ id: '10', name: '客服机器人' }],
    total: 1,
    page: 1,
    size: 100,
  }),
}))
vi.mock('@/api/admin/provider', () => ({
  listProviders: vi.fn().mockResolvedValue([{ id: '100', name: '通义' }]),
}))
vi.mock('@/api/admin/model', () => ({
  listModels: vi.fn().mockResolvedValue([{ id: '5', name: 'qwen-max' }]),
}))

describe('useNameMaps', () => {
  it('load 后三类 id 可解析，未知 id 回退「#id（已删除）」', async () => {
    const maps = useNameMaps()
    await maps.load()
    expect(maps.resolveUser('1')).toBe('张三')
    expect(maps.resolveApp('10')).toBe('客服机器人')
    expect(maps.resolveModel('5')).toBe('qwen-max')
    expect(maps.resolveModel('999')).toBe('#999（已删除）')
  })
})
