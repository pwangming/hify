import type { AdminUser, CreateUserRequest } from '@/types/admin-user'
import type { UserRole } from '@/types/user'

// —— 临时 mock 实现：先用 mock 数据把前端页跑通，最后一轮（T5）换成真后端 request 调用 ——
// 模块级可变数组：增删改会反映到后续 listUsers，浏览器里手动操作能看到一致变化。
let mockUsers: AdminUser[] = [
  { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
  { id: '3', username: 'bob', role: 'member', status: 'disabled', createTime: '2026-06-22T14:15:00+08:00' },
]
let nextId = 4

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), 150))
}
function find(id: string): AdminUser {
  const u = mockUsers.find((x) => x.id === id)
  if (!u) throw new Error(`mock: user ${id} not found`)
  return u
}

export function listUsers(): Promise<AdminUser[]> {
  return delay(mockUsers.map((u) => ({ ...u })))
}
export function createUser(body: CreateUserRequest): Promise<AdminUser> {
  const u: AdminUser = {
    id: String(nextId++),
    username: body.username,
    role: body.role,
    status: 'enabled',
    createTime: new Date().toISOString(),
  }
  mockUsers = [u, ...mockUsers]
  return delay({ ...u })
}
export function enableUser(id: string): Promise<AdminUser> {
  const u = find(id)
  u.status = 'enabled'
  return delay({ ...u })
}
export function disableUser(id: string): Promise<AdminUser> {
  const u = find(id)
  u.status = 'disabled'
  return delay({ ...u })
}
export function resetPassword(id: string, _password: string): Promise<void> {
  find(id)
  return delay(undefined)
}
export function changeRole(id: string, role: UserRole): Promise<AdminUser> {
  const u = find(id)
  u.role = role
  return delay({ ...u })
}
export function deleteUser(id: string): Promise<void> {
  mockUsers = mockUsers.filter((x) => x.id !== id)
  return delay(undefined)
}
