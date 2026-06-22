// 当前登录用户相关类型（全局 DTO，集中放 types/，api/*.ts 引用）。

/** 用户角色。与后端 CurrentUser.role 对齐；路由 meta.roles 也用小写形式。 */
export type UserRole = 'admin' | 'member'

/**
 * 当前登录用户信息。
 * 对应后端 GET /api/v1/identity/me 的返回（后端 CurrentUser: userId / username / role）。
 * 注意：id 为 string —— 后端全局把 Long 序列化为 string，防 JS 2^53 精度丢失（见 frontend-standards 3.6）。
 */
export interface UserInfo {
  id: string
  username: string
  role: UserRole
}

/** 登录请求体（对应后端 POST /api/v1/identity/login）。 */
export interface LoginRequest {
  username: string
  password: string
}

/** 登录响应：后端签发的 JWT。 */
export interface LoginResponse {
  token: string
}
