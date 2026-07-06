import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  enableProvider,
  disableProvider,
  deleteProvider,
} from '@/api/admin/provider'
import type { Provider } from '@/types/provider'
import ProviderList from '@/views/admin/provider/ProviderList.vue'

vi.mock('@/api/admin/provider', () => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  enableProvider: vi.fn(),
  disableProvider: vi.fn(),
  deleteProvider: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: Provider[] = [
  {
    id: '1',
    name: 'OpenAI 官方',
    protocol: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    status: 'enabled',
    apiKeyTail: '7890',
    createTime: '2026-06-20T10:00:00+08:00',
    lastTestStatus: null,
    lastTestAt: null,
    lastTestError: null,
  },
  {
    id: '3',
    name: 'Anthropic Claude',
    protocol: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    status: 'enabled',
    apiKeyTail: 'wxyz',
    createTime: '2026-06-22T09:05:00+08:00',
    lastTestStatus: null,
    lastTestAt: null,
    lastTestError: null,
  },
]

describe('ProviderList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProviders).mockResolvedValue(SAMPLE)
  })

  it('挂载时拉取提供商并渲染协议标签与 API Key 掩码', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listProviders).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('OpenAI 官方')
    expect(wrapper.text()).toContain('Anthropic Claude')
    expect(wrapper.text()).toContain('OpenAI 兼容') // 协议标签
    expect(wrapper.text()).toContain('Anthropic')
    expect(wrapper.text()).toContain('••••7890') // API Key 掩码列
  })

  it('点新增弹出对话框', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="form-name"]').exists()).toBe(true)
  })

  it('新建表单：名称为空时拦截，不调 createProvider', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createProvider).not.toHaveBeenCalled()
  })

  // 新增时 API Key 必填：填了名称和 Base URL、独缺 API Key 也不应提交。
  // （真实浏览器下 el-form 规则会在输入框下显示「请输入 API Key」红字；happy-dom 不渲染内联校验消息，
  //  故此处只断言"不提交"，与「名称为空时拦截」用例同口径。）
  it('新建：API Key 为空时拦截，不调 createProvider', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('New')
    await wrapper.get('[data-test="form-baseurl"]').setValue('https://x.com/v1')
    // 不填 API Key
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createProvider).not.toHaveBeenCalled()
  })

  it('新建成功：调 createProvider(body 带 protocol) 后重拉列表', async () => {
    vi.mocked(createProvider).mockResolvedValue({
      id: '9',
      name: 'New',
      protocol: 'openai',
      baseUrl: 'https://x.com/v1',
      status: 'enabled',
      apiKeyTail: 'xxx0',
      createTime: '2026-06-24T08:00:00+08:00',
      lastTestStatus: null,
      lastTestAt: null,
      lastTestError: null,
    })
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listProviders).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('New')
    await wrapper.get('[data-test="form-apikey"]').setValue('sk-xxx')
    await wrapper.get('[data-test="form-baseurl"]').setValue('https://x.com/v1')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()

    expect(createProvider).toHaveBeenCalledWith({
      name: 'New',
      protocol: 'openai',
      apiKey: 'sk-xxx',
      baseUrl: 'https://x.com/v1',
    })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('点编辑：弹窗预填名称且 apiKey 留空', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe(
      'OpenAI 官方',
    )
    expect((wrapper.get('[data-test="form-apikey"]').element as HTMLInputElement).value).toBe('')
  })

  it('编辑成功：调 updateProvider(id, body 带 protocol) 后重拉', async () => {
    vi.mocked(updateProvider).mockResolvedValue({
      id: '1',
      name: 'OpenAI 改名',
      protocol: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      status: 'enabled',
      apiKeyTail: '7890',
      createTime: '2026-06-20T10:00:00+08:00',
      lastTestStatus: null,
      lastTestAt: null,
      lastTestError: null,
    })
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('OpenAI 改名')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateProvider).toHaveBeenCalledWith('1', {
      name: 'OpenAI 改名',
      protocol: 'openai',
      apiKey: '',
      baseUrl: 'https://api.openai.com/v1',
    })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('删除：确认后调 deleteProvider 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(deleteProvider).mockResolvedValue(undefined)
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteProvider).toHaveBeenCalledWith('1')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('删除：取消则不调 deleteProvider', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteProvider).not.toHaveBeenCalled()
  })

  it('启用行显示「禁用」按钮：确认后调 disableProvider 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(disableProvider).mockResolvedValue(undefined)
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="enable-1"]').exists()).toBe(false)
    await wrapper.get('[data-test="disable-1"]').trigger('click')
    await flushPromises()
    expect(disableProvider).toHaveBeenCalledWith('1')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('禁用行显示「启用」按钮：直接调 enableProvider（无需确认）', async () => {
    vi.mocked(listProviders).mockResolvedValue([
      {
        id: '4',
        name: '通义千问',
        protocol: 'openai',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        status: 'disabled',
        apiKeyTail: '4321',
        createTime: '2026-06-22T15:40:00+08:00',
        lastTestStatus: null,
        lastTestAt: null,
        lastTestError: null,
      },
    ])
    vi.mocked(enableProvider).mockResolvedValue(undefined)
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="disable-4"]').exists()).toBe(false)
    await wrapper.get('[data-test="enable-4"]').trigger('click')
    await flushPromises()
    expect(enableProvider).toHaveBeenCalledWith('4')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('点「管理模型」跳转到该供应商详情页', async () => {
    const Stub = { template: '<div />' }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin/provider', component: Stub },
        { path: '/admin/provider/:id', component: Stub },
      ],
    })
    await router.push('/admin/provider')
    await router.isReady()

    const wrapper = mount(ProviderList, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="manage-1"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/provider/1')
  })
})
