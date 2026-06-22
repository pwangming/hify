import { describe, it, expect } from 'vitest'
import type { RouteRecordRaw } from 'vue-router'
import { buildMenu, isRoleAllowed } from '@/router/menu'

describe('isRoleAllowed', () => {
  it('未限定角色（undefined / 空）→ 任何登录用户放行', () => {
    expect(isRoleAllowed(undefined, 'member')).toBe(true)
    expect(isRoleAllowed([], 'member')).toBe(true)
  })

  it('限定 admin：仅 admin 放行', () => {
    expect(isRoleAllowed(['admin'], 'admin')).toBe(true)
    expect(isRoleAllowed(['admin'], 'member')).toBe(false)
  })

  it('限定角色但用户角色未知（未加载）→ 不放行', () => {
    expect(isRoleAllowed(['admin'], undefined)).toBe(false)
  })
})

describe('buildMenu', () => {
  // 仅读 path 与 meta，用最小路由记录即可（cast 规避 component/redirect 必填）
  const routes = [
    { path: '/', redirect: '/knowledge' }, // 无 meta.menu → 不进菜单
    { path: '/knowledge', meta: { menu: true, title: '知识库管理' } },
    { path: '/app', meta: { menu: true, title: '应用管理' } },
    { path: '/admin/provider', meta: { menu: true, title: '模型提供商管理', roles: ['admin'] } },
    { path: '/profile', meta: { title: '个人设置', requiresAuth: true } }, // 有 title 无 menu → 不进菜单
  ] as unknown as RouteRecordRaw[]

  it('只收 meta.menu 为 true 的路由，映射成 {path,title}', () => {
    const items = buildMenu(routes, 'admin')
    expect(items).toEqual([
      { path: '/knowledge', title: '知识库管理' },
      { path: '/app', title: '应用管理' },
      { path: '/admin/provider', title: '模型提供商管理' },
    ])
  })

  it('Member 看不到 admin 专属项', () => {
    const items = buildMenu(routes, 'member')
    expect(items.map((i) => i.path)).toEqual(['/knowledge', '/app'])
  })

  it('菜单项缺 title 时 title 退回 path', () => {
    const noTitle = [{ path: '/dangling', meta: { menu: true } }] as unknown as RouteRecordRaw[]
    expect(buildMenu(noTitle, 'admin')).toEqual([{ path: '/dangling', title: '/dangling' }])
  })
})
