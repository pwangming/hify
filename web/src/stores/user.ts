import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { TOKEN_KEY } from '@/api/request'
import { getCurrentUser } from '@/api/auth'
import type { UserInfo } from '@/types/user'

/**
 * 当前用户与登录态。全站唯一的登录态来源（见 frontend-standards 第 6 节）。
 *
 * 分工：本 store 是 token 的「持有/写入方」，持久化到 localStorage；
 * request.ts 只「直接读」localStorage（不 import 本 store），借此打断 request ←→ store 循环依赖。
 * 因此本 store 复用 request 导出的 TOKEN_KEY 常量，保证两边读写同一个键。
 *
 * 持久化策略：只持久化 token；user 信息为内存态，刷新后由守卫重新 loadCurrentUser 拉回。
 */
export const useUserStore = defineStore('user', () => {
  // ── state ──
  // token 初值从 localStorage 读回：刷新页面后登录态不丢。
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  // 当前用户信息，登录后或刷新后由 loadCurrentUser 填充；未加载时为 null。
  const user = ref<UserInfo | null>(null)

  // ── getters ──
  // 仅凭「有无 token」判断是否已登录；token 是否仍有效由后端 /me 校验。
  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.role === 'admin')

  // ── actions ──
  /** 登录成功后写入 token（登录页拿到后端签发的 jwt 时调用）。 */
  function setToken(newToken: string) {
    token.value = newToken
    localStorage.setItem(TOKEN_KEY, newToken)
  }

  /** 拉取并缓存当前用户信息（守卫在持有 token 但 user 未加载时调用）。 */
  async function loadCurrentUser() {
    user.value = await getCurrentUser()
    return user.value
  }

  /** 退出登录：清空内存态与持久化 token。登录态失效的统一收口（守卫与 API 拦截器都调它）。 */
  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, isLoggedIn, isAdmin, setToken, loadCurrentUser, logout }
})
