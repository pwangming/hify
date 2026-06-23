import type { RouteRecordRaw } from 'vue-router'
import type { UserRole } from '@/types/user'

/** 侧边菜单项（由路由表派生）。 */
export interface MenuItem {
  path: string
  title: string
  icon?: string
}

/**
 * 角色是否放行。
 * roles 未限定（undefined / 空数组）→ 任何登录用户可见；
 * 限定时，仅当用户角色已知且在允许列表内才放行。
 * 守卫（第 4 步）与菜单共用此判断，保证"看得到的=进得去的"一致。
 */
export function isRoleAllowed(roles: UserRole[] | undefined, role: UserRole | undefined): boolean {
  if (!roles || roles.length === 0) return true
  return role != null && roles.includes(role)
}

/**
 * 从路由表生成当前角色可见的菜单：取 meta.menu 为 true 且角色放行的路由，映射成 {path,title}。
 * 路由加一条带 meta.menu 的记录，菜单自动多一项——无需在布局里手维护。
 */
export function buildMenu(routes: readonly RouteRecordRaw[], role: UserRole | undefined): MenuItem[] {
  return routes
    .filter((route) => route.meta?.menu && isRoleAllowed(route.meta.roles, role))
    .map((route) => ({
      path: route.path,
      title: route.meta?.title ?? route.path,
      icon: route.meta?.icon,
    }))
}

/**
 * 由路由 meta 派生面包屑：meta.group（可选，作第一级）+ meta.title（页面名）。
 * 路由扁平，故最多两级；缺省项不进面包屑。
 */
export function buildBreadcrumb(route: {
  meta: { group?: string; title?: string }
}): string[] {
  const crumbs: string[] = []
  if (route.meta.group) crumbs.push(route.meta.group)
  if (route.meta.title) crumbs.push(route.meta.title)
  return crumbs
}
