import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { isRoleAllowed } from './menu'

/**
 * 全局前置守卫（规范 7.2 五步）。返回导航决策：true=放行，路由对象=重定向。
 * 注意：前端守卫只是体验层（提前拦、隐藏入口），真正鉴权在后端——拿到 10004 仍由 API 拦截器兜底。
 *
 * document.title 不在此设，交由 router.afterEach（导航确认后）统一处理。
 */
export async function authGuard(
  to: RouteLocationNormalized,
): Promise<boolean | RouteLocationRaw> {
  const userStore = useUserStore()

  // ① 显式声明不需登录（登录页、错误页）→ 直接放行
  if (to.meta.requiresAuth === false) return true

  // ② 无 token → 跳登录，带 redirect 记住原目标，登录后跳回
  if (!userStore.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // ③ 有 token 但 user 未加载（如刷新后）→ 拉 /me 拿角色；失败即清登录态跳登录
  if (!userStore.user) {
    try {
      await userStore.loadCurrentUser()
    } catch {
      userStore.logout()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // ④ 角色不符 → 跳 403（与菜单共用 isRoleAllowed，看得到=进得去）
  if (!isRoleAllowed(to.meta.roles, userStore.user?.role)) {
    return { path: '/403' }
  }

  // ⑤ 通过
  return true
}
