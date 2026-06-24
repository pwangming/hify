import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { listProviders } from '@/api/admin/provider'
import {
  listModels, createModel, updateModel,
  deleteModel, enableModel, disableModel,
} from '@/api/admin/model'
import type { Provider } from '@/types/provider'
import type { AiModel } from '@/types/model'
import ProviderDetail from '@/views/admin/provider/ProviderDetail.vue'

vi.mock('@/api/admin/provider', () => ({ listProviders: vi.fn() }))
vi.mock('@/api/admin/model', () => ({
  listModels: vi.fn(),
  createModel: vi.fn(),
  updateModel: vi.fn(),
  deleteModel: vi.fn(),
  enableModel: vi.fn(),
  disableModel: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const OPENAI_PROVIDER: Provider = {
  id: '1', name: '通义千问', protocol: 'openai',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  status: 'enabled', apiKeyTail: '4321', createTime: '2026-06-22T15:40:00+08:00',
}
const ANTHROPIC_PROVIDER: Provider = {
  id: '3', name: 'Anthropic Claude', protocol: 'anthropic',
  baseUrl: 'https://api.anthropic.com',
  status: 'enabled', apiKeyTail: 'wxyz', createTime: '2026-06-22T09:05:00+08:00',
}
const MODELS: AiModel[] = [
  { id: '10', providerId: '1', type: 'chat', name: 'GPT-4o', modelKey: 'gpt-4o',
    status: 'enabled', createTime: '2026-06-23T10:00:00+08:00' },
  { id: '11', providerId: '1', type: 'embedding', name: 'BGE', modelKey: 'bge-large',
    status: 'disabled', createTime: '2026-06-23T11:00:00+08:00' },
]

// 挂载 ProviderDetail 到指定供应商 id 的真实 memory router
async function mountAt(id: string): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const Stub = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/provider', component: Stub },
      { path: '/admin/provider/:id', component: ProviderDetail },
      { path: '/404', component: Stub },
    ],
  })
  await router.push(`/admin/provider/${id}`)
  await router.isReady()
  const wrapper = mount(ProviderDetail, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

describe('ProviderDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProviders).mockResolvedValue([OPENAI_PROVIDER, ANTHROPIC_PROVIDER])
    vi.mocked(listModels).mockResolvedValue(MODELS)
  })

  it('挂载时按 id 找供应商、拉模型、渲染表头与模型行', async () => {
    const { wrapper } = await mountAt('1')
    expect(listProviders).toHaveBeenCalledOnce()
    expect(listModels).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('通义千问')
    expect(wrapper.text()).toContain('GPT-4o')
    expect(wrapper.text()).toContain('gpt-4o')      // modelKey 完整显示
    expect(wrapper.text()).toContain('bge-large')
  })

  it('找不到供应商 → 跳 /404，不拉模型', async () => {
    const { router } = await mountAt('999')
    expect(router.currentRoute.value.path).toBe('/404')
    expect(listModels).not.toHaveBeenCalled()
  })

  it('空列表正常渲染（不报错）', async () => {
    vi.mocked(listModels).mockResolvedValue([])
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-table"]').exists()).toBe(true)
  })

  it('openai 供应商：embedding 选项可选', async () => {
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    const embRadio = wrapper.find('[data-test="form-type"] input[value="embedding"]')
    expect((embRadio.element as HTMLInputElement).disabled).toBe(false)
  })

  it('anthropic 供应商：embedding 选项被禁用', async () => {
    const { wrapper } = await mountAt('3')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    const embRadio = wrapper.find('[data-test="form-type"] input[value="embedding"]')
    expect((embRadio.element as HTMLInputElement).disabled).toBe(true)
  })

  it('新建成功：调 createModel(providerId, body) 后重拉', async () => {
    vi.mocked(createModel).mockResolvedValue({
      id: '12', providerId: '1', type: 'chat', name: 'New', modelKey: 'new-key',
      status: 'enabled', createTime: '2026-06-24T08:00:00+08:00',
    })
    const { wrapper } = await mountAt('1')
    expect(listModels).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('New')
    await wrapper.get('[data-test="form-modelkey"]').setValue('new-key')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createModel).toHaveBeenCalledWith('1', { type: 'chat', name: 'New', modelKey: 'new-key' })
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('新建表单：名称为空时拦截，不调 createModel', async () => {
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createModel).not.toHaveBeenCalled()
  })

  it('编辑：预填 name/modelKey，提交只调 updateModel(id, {name,modelKey})', async () => {
    vi.mocked(updateModel).mockResolvedValue({ ...MODELS[0], name: 'GPT-4o 改' })
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-edit-10"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe('GPT-4o')
    expect((wrapper.get('[data-test="form-modelkey"]').element as HTMLInputElement).value).toBe('gpt-4o')
    await wrapper.get('[data-test="form-name"]').setValue('GPT-4o 改')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateModel).toHaveBeenCalledWith('10', { name: 'GPT-4o 改', modelKey: 'gpt-4o' })
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('删除：确认后调 deleteModel 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(deleteModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-delete-10"]').trigger('click')
    await flushPromises()
    expect(deleteModel).toHaveBeenCalledWith('10')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('删除：取消则不调 deleteModel', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-delete-10"]').trigger('click')
    await flushPromises()
    expect(deleteModel).not.toHaveBeenCalled()
  })

  it('启用行（status=enabled）显示「禁用」：确认后调 disableModel', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(disableModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-enable-10"]').exists()).toBe(false)
    await wrapper.get('[data-test="model-disable-10"]').trigger('click')
    await flushPromises()
    expect(disableModel).toHaveBeenCalledWith('10')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('禁用行（status=disabled）显示「启用」：直接调 enableModel（无确认）', async () => {
    vi.mocked(enableModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-disable-11"]').exists()).toBe(false)
    await wrapper.get('[data-test="model-enable-11"]').trigger('click')
    await flushPromises()
    expect(enableModel).toHaveBeenCalledWith('11')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('点「返回」跳回供应商列表', async () => {
    const { wrapper, router } = await mountAt('1')
    await wrapper.get('[data-test="back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/provider')
  })
})
