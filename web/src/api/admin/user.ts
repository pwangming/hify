import { request } from '../request'
import type { AdminUser, CreateUserRequest } from '@/types/admin-user'
import type { UserRole } from '@/types/user'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/identity/users'

/** 列出全部用户。后端：GET /api/v1/admin/identity/users */
export function listUsers() {
  return request.get<AdminUser[]>(BASE)
}
/** 新建用户。后端：POST /api/v1/admin/identity/users */
export function createUser(body: CreateUserRequest) {
  return request.post<AdminUser>(BASE, body)
}
/** 启用账号。后端：POST .../{id}/enable */
export function enableUser(id: string) {
  return request.post<AdminUser>(`${BASE}/${id}/enable`)
}
/** 停用账号。后端：POST .../{id}/disable */
export function disableUser(id: string) {
  return request.post<AdminUser>(`${BASE}/${id}/disable`)
}
/** 重置密码（admin 代设）。后端：PUT .../{id}/password */
export function resetPassword(id: string, password: string) {
  return request.put<void>(`${BASE}/${id}/password`, { password })
}
/** 改角色。后端：PUT .../{id}/role */
export function changeRole(id: string, role: UserRole) {
  return request.put<AdminUser>(`${BASE}/${id}/role`, { role })
}
/** 删除用户（软删）。后端：DELETE .../{id} */
export function deleteUser(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}
