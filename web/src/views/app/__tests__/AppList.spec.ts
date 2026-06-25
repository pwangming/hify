import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElSelect, ElOption } from 'element-plus'
import { listApps, createApp, deleteApp } from '@/api/app'
import { listChatModels } from '@/api/provider'
import type { App, PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import AppList from '@/views/app/AppList.vue'

vi.mock('@/api/app', () => ({
  listApps: vi.fn(), getApp: vi.fn(), createApp: vi.fn(), updateApp: vi.fn(),
  deleteApp: vi.fn(), enableApp: vi.fn(), disableApp: vi.fn(),
}))
vi.mock('@/api/provider', () => ({ listChatModels: vi.fn() }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page(list: App[]): PageResult<App> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const MINE: App = {
  id: '1', name: '我的助手', description: null, type: 'chat', modelId: null, modelName: null,
  modelUsable: false, config: { systemPrompt: null }, ownerId: '7', status: 'enabled',
  createTime: '2026-06-24T10:00:00+08:00', updateTime: '2026-06-24T10:00:00+08:00',
}
const OTHERS: App = { ...MINE, id: '2', name: '他人应用', ownerId: '999' }
// 选了模型且模型已失效（其供应商被停用）：modelName 仍有值、modelUsable=false、不在可用列表里
const WITH_MODEL: App = { ...MINE, id: '3', name: '带模型应用', modelId: '5', modelName: 'GPT-4o', modelUsable: false }
const NAMED: App = { ...MINE, id: '4', name: '命名应用', modelId: '5', modelName: 'GPT-4o', modelUsable: true }

describe('AppList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listApps).mockResolvedValue(page([MINE, OTHERS]))
    vi.mocked(listChatModels).mockResolvedValue([
      { id: '5', name: 'GPT-4o', type: 'chat', providerName: '通义千问' },
    ])
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

  it('打开创建弹窗：拉取可用模型、渲染选择器', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await flushPromises()
    expect(listChatModels).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-test="form-model"]').exists()).toBe(true)
  })

  it('创建：选中模型 → createApp body 含 modelId', async () => {
    vi.mocked(createApp).mockResolvedValue(MINE)
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await flushPromises()
    wrapper.find('[data-test="form-name"]').setValue('新应用')
    wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', '5')
    await flushPromises()
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).toHaveBeenCalledWith(expect.objectContaining({ modelId: '5' }))
  })

  it('创建：不选模型 → createApp body modelId 为 null', async () => {
    vi.mocked(createApp).mockResolvedValue(MINE)
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await flushPromises()
    wrapper.find('[data-test="form-name"]').setValue('无模型应用')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).toHaveBeenCalledWith(expect.objectContaining({ modelId: null }))
  })

  it('编辑：回填已选模型 id', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WITH_MODEL]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-3"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(ElSelect).props('modelValue')).toBe('5')
  })

  it('列表：有模型显示模型名，无模型显示未配置', async () => {
    vi.mocked(listApps).mockResolvedValue(page([NAMED, MINE]))
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('GPT-4o') // NAMED 的模型名
    expect(wrapper.text()).toContain('未配置') // MINE 无模型
  })

  it('列表：模型已停用 → 名字后加（已停用）；可用模型不加', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WITH_MODEL])) // modelUsable=false
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('GPT-4o（已停用）')
  })

  it('列表：可用模型不加（已停用）后缀', async () => {
    vi.mocked(listApps).mockResolvedValue(page([NAMED])) // modelUsable=true
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('GPT-4o')
    expect(wrapper.text()).not.toContain('（已停用）')
  })

  it('编辑：所选模型已失效 → 注入「名字（已停用）」禁用选项，不裸露 id', async () => {
    vi.mocked(listApps).mockResolvedValue(page([WITH_MODEL]))
    vi.mocked(listChatModels).mockResolvedValue([]) // 该模型不在可用列表（供应商停用）
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-3"]').trigger('click')
    await flushPromises()
    const injected = wrapper.findAllComponents(ElOption).find((o) => o.props('value') === '5')
    expect(injected).toBeTruthy()
    expect(injected!.props('label')).toBe('GPT-4o（已停用）')
    expect(injected!.props('disabled')).toBe(true)
  })
})
