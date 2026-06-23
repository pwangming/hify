import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listUsers, createUser, enableUser, disableUser,
  resetPassword, changeRole, deleteUser,
} from '@/api/admin/user'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const BASE = '/admin/identity/users'

describe('admin user api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listUsers → GET /admin/identity/users', () => {
    listUsers()
    expect(request.get).toHaveBeenCalledWith(BASE)
  })
  it('createUser → POST /admin/identity/users + body', () => {
    const body = { username: 'dave', password: 'secret12', role: 'member' as const }
    createUser(body)
    expect(request.post).toHaveBeenCalledWith(BASE, body)
  })
  it('enableUser → POST .../{id}/enable', () => {
    enableUser('7')
    expect(request.post).toHaveBeenCalledWith(`${BASE}/7/enable`)
  })
  it('disableUser → POST .../{id}/disable', () => {
    disableUser('7')
    expect(request.post).toHaveBeenCalledWith(`${BASE}/7/disable`)
  })
  it('resetPassword → PUT .../{id}/password + {password}', () => {
    resetPassword('7', 'newpass12')
    expect(request.put).toHaveBeenCalledWith(`${BASE}/7/password`, { password: 'newpass12' })
  })
  it('changeRole → PUT .../{id}/role + {role}', () => {
    changeRole('7', 'admin')
    expect(request.put).toHaveBeenCalledWith(`${BASE}/7/role`, { role: 'admin' })
  })
  it('deleteUser → DELETE .../{id}', () => {
    deleteUser('7')
    expect(request.delete).toHaveBeenCalledWith(`${BASE}/7`)
  })
})
