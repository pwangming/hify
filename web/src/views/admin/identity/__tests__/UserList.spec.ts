import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listUsers } from '@/api/admin/user'
import type { AdminUser } from '@/types/admin-user'
import UserList from '@/views/admin/identity/UserList.vue'

// 整个 API 层用 mock，组件测试只验证组件行为（不碰真实现/后端）
vi.mock('@/api/admin/user', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
  resetPassword: vi.fn(),
  changeRole: vi.fn(),
  deleteUser: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: AdminUser[] = [
  { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
]

describe('UserList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listUsers).mockResolvedValue(SAMPLE)
  })

  it('挂载时拉取用户并渲染各行', async () => {
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listUsers).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('alice')
  })
})
