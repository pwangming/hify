import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { TOKEN_KEY } from '@/api/request'
import { getCurrentUser } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { UserInfo } from '@/types/user'

// 隔离网络：loadCurrentUser 依赖的 getCurrentUser 用 mock，不发真实请求。
vi.mock('@/api/auth', () => ({
  getCurrentUser: vi.fn(),
}))

const adminUser: UserInfo = { id: '1', username: 'alice', role: 'admin' }
const memberUser: UserInfo = { id: '2', username: 'bob', role: 'member' }

describe('useUserStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('token 初值从 localStorage 读回（刷新后登录态不丢）', () => {
    localStorage.setItem(TOKEN_KEY, 'persisted.jwt')
    const store = useUserStore()
    expect(store.token).toBe('persisted.jwt')
    expect(store.isLoggedIn).toBe(true)
  })

  it('无 token 时未登录', () => {
    const store = useUserStore()
    expect(store.token).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('setToken 同时写入内存态与 localStorage（同一个键）', () => {
    const store = useUserStore()
    store.setToken('new.jwt')
    expect(store.token).toBe('new.jwt')
    expect(localStorage.getItem(TOKEN_KEY)).toBe('new.jwt')
    expect(store.isLoggedIn).toBe(true)
  })

  it('logout 清空 token、user 与 localStorage', () => {
    const store = useUserStore()
    store.setToken('x')
    store.user = adminUser
    store.logout()
    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('isAdmin 仅在角色为 admin 时为真（小写）', () => {
    const store = useUserStore()
    expect(store.isAdmin).toBe(false) // user 未加载
    store.user = memberUser
    expect(store.isAdmin).toBe(false)
    store.user = adminUser
    expect(store.isAdmin).toBe(true)
  })

  it('loadCurrentUser 拉取并缓存用户信息', async () => {
    vi.mocked(getCurrentUser).mockResolvedValue(adminUser)
    const store = useUserStore()
    const result = await store.loadCurrentUser()
    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(store.user).toEqual(adminUser)
    expect(result).toEqual(adminUser)
  })
})
