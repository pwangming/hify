import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { getEmbeddingSetting, saveEmbeddingSetting } from '@/api/admin/provider'
import { reembedAll } from '@/api/admin/knowledge'
import { listEmbeddingModels } from '@/api/provider'
import type { EmbeddingSetting } from '@/types/model'
import SystemSettings from '@/views/admin/system/SystemSettings.vue'

vi.mock('@/api/admin/provider', () => ({
  getEmbeddingSetting: vi.fn(),
  saveEmbeddingSetting: vi.fn(),
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  enableProvider: vi.fn(),
  disableProvider: vi.fn(),
  deleteProvider: vi.fn(),
}))
vi.mock('@/api/admin/knowledge', () => ({ reembedAll: vi.fn() }))
vi.mock('@/api/provider', () => ({ listChatModels: vi.fn(), listEmbeddingModels: vi.fn() }))

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SETTING: EmbeddingSetting = { modelId: '6', modelName: '千问 v4' }
const EMPTY: EmbeddingSetting = { modelId: null, modelName: null }
const MODELS = [{ id: '6', name: '千问 v4', type: 'embedding', providerName: '阿里云' }]

async function mountPage() {
  const wrapper = mount(SystemSettings, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('SystemSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listEmbeddingModels).mockResolvedValue(MODELS)
    vi.mocked(getEmbeddingSetting).mockResolvedValue(SETTING)
  })

  it('挂载：拉取设置与可用 embedding 模型', async () => {
    const wrapper = await mountPage()
    expect(getEmbeddingSetting).toHaveBeenCalled()
    expect(listEmbeddingModels).toHaveBeenCalled()
    expect(wrapper.text()).toContain('embedding')
  })

  it('保存：调用 saveEmbeddingSetting 并提示成功', async () => {
    vi.mocked(saveEmbeddingSetting).mockResolvedValue(SETTING)
    const wrapper = await mountPage()
    await wrapper.find('[data-test="save-embedding"]').trigger('click')
    await flushPromises()
    expect(saveEmbeddingSetting).toHaveBeenCalledWith('6')
  })

  it('保存失败：无成功提示且不产生未处理 rejection（拦截器已 toast）', async () => {
    vi.mocked(saveEmbeddingSetting).mockRejectedValue(new Error('12005'))
    const { ElMessage } = await import('element-plus')
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="save-embedding"]').trigger('click')
    await flushPromises()
    expect(saveEmbeddingSetting).toHaveBeenCalledWith('6')
    expect(success).not.toHaveBeenCalled()
  })

  it('未配置且未选择：保存按钮禁用、重嵌按钮禁用', async () => {
    vi.mocked(getEmbeddingSetting).mockResolvedValue(EMPTY)
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="save-embedding"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="reembed-all"]').attributes('disabled')).toBeDefined()
  })

  it('全量重嵌：确认后调用并提示', async () => {
    vi.mocked(reembedAll).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="reembed-all"]').trigger('click')
    await flushPromises()
    expect(reembedAll).toHaveBeenCalled()
  })

  it('全量重嵌失败：无成功提示且不产生未处理 rejection（拦截器已 toast）', async () => {
    vi.mocked(reembedAll).mockRejectedValue(new Error('15003'))
    const { ElMessageBox, ElMessage } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="reembed-all"]').trigger('click')
    await flushPromises()
    expect(reembedAll).toHaveBeenCalled()
    expect(success).not.toHaveBeenCalled()
  })

  it('全量重嵌：取消确认则不调用', async () => {
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="reembed-all"]').trigger('click')
    await flushPromises()
    expect(reembedAll).not.toHaveBeenCalled()
  })
})
