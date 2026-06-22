import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'
import { getCurrentUser } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { authGuard } from '@/router/guard'

// loadCurrentUser 依赖的 /me 请求用 mock，不发真实网络
vi.mock('@/api/auth', () => ({ getCurrentUser: vi.fn() }))

// 守卫只读 to.meta 与 to.fullPath，用最小对象即可（cast 规避其余必填字段）
function toRoute(meta: Record<string, unknown>, fullPath = '/target'): RouteLocationNormalized {
  return { meta, fullPath, path: fullPath } as unknown as RouteLocationNormalized
}

const admin = { id: '1', username: 'alice', role: 'admin' as const }
const member = { id: '2', username: 'bob', role: 'member' as const }

describe('authGuard', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('① 不需登录的页面直接放行', async () => {
    const result = await authGuard(toRoute({ requiresAuth: false }))
    expect(result).toBe(true)
  })

  it('② 需登录但无 token → 跳登录并带 redirect 原目标', async () => {
    const result = await authGuard(toRoute({}, '/knowledge'))
    expect(result).toEqual({ path: '/login', query: { redirect: '/knowledge' } })
  })

  it('③ 有 token 但 user 未加载 → 拉 /me；成功且角色放行 → 放行', async () => {
    vi.mocked(getCurrentUser).mockResolvedValue(admin)
    const store = useUserStore()
    store.setToken('jwt')

    const result = await authGuard(toRoute({}))

    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(store.user).toEqual(admin)
    expect(result).toBe(true)
  })

  it('③b 有 token 但拉 /me 失败 → 清登录态并跳登录', async () => {
    vi.mocked(getCurrentUser).mockRejectedValue(new Error('token expired'))
    const store = useUserStore()
    store.setToken('bad')

    const result = await authGuard(toRoute({}, '/app'))

    expect(store.token).toBeNull()
    expect(result).toEqual({ path: '/login', query: { redirect: '/app' } })
  })

  it('④ 角色不符 → 跳 403', async () => {
    const store = useUserStore()
    store.setToken('jwt')
    store.user = member

    const result = await authGuard(toRoute({ roles: ['admin'] }))

    expect(result).toEqual({ path: '/403' })
  })

  it('⑤ 角色放行 → 放行', async () => {
    const store = useUserStore()
    store.setToken('jwt')
    store.user = admin

    const result = await authGuard(toRoute({ roles: ['admin'] }))

    expect(result).toBe(true)
  })
})
