import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listApps, createApp, deleteApp } from '@/api/app'
import type { App, PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import AppList from '@/views/app/AppList.vue'

vi.mock('@/api/app', () => ({
  listApps: vi.fn(), getApp: vi.fn(), createApp: vi.fn(), updateApp: vi.fn(),
  deleteApp: vi.fn(), enableApp: vi.fn(), disableApp: vi.fn(),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page(list: App[]): PageResult<App> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const MINE: App = {
  id: '1', name: '我的助手', description: null, type: 'chat', modelId: null,
  config: { systemPrompt: null }, ownerId: '7', status: 'enabled',
  createTime: '2026-06-24T10:00:00+08:00', updateTime: '2026-06-24T10:00:00+08:00',
}
const OTHERS: App = { ...MINE, id: '2', name: '他人应用', ownerId: '999' }

describe('AppList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listApps).mockResolvedValue(page([MINE, OTHERS]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户=bob(7)
  })

  it('挂载拉取并渲染应用名', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listApps).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('我的助手')
    expect(wrapper.text()).toContain('他人应用')
  })

  it('canModify 门控：自己的应用有编辑按钮，他人没有', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(true)   // 我的
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(false)  // 他人
  })

  it('删除自己的应用调用 deleteApp', async () => {
    vi.mocked(deleteApp).mockResolvedValue(undefined)
    // 二次确认弹窗放行
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteApp).toHaveBeenCalledWith('1')
  })

  it('创建：空名不提交', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).not.toHaveBeenCalled()
  })
})
