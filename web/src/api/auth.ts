import { request } from './request'
import type { UserInfo, LoginRequest, LoginResponse } from '@/types/user'

/**
 * 账号密码登录，成功返回 JWT。后端契约：POST /api/v1/identity/login。
 * 失败（账号密码错等业务码）由 request 拦截器默认弹 toast 提示。
 */
export function login(body: LoginRequest) {
  return request.post<LoginResponse>('/identity/login', body)
}

/**
 * 拉取当前登录用户（含角色），供路由守卫在持有 token 时初始化登录态。
 * 后端契约：GET /api/v1/identity/me（baseURL 已含 /api/v1）。
 * 未认证/过期时后端返回 10002/10003，request 拦截器统一清登录态并跳登录。
 */
export function getCurrentUser() {
  return request.get<UserInfo>('/identity/me')
}
