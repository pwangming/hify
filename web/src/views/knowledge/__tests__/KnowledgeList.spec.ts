import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listDatasets, createDataset, updateDataset, deleteDataset } from '@/api/knowledge'
import type { Dataset } from '@/types/knowledge'
import type { PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import KnowledgeList from '@/views/knowledge/KnowledgeList.vue'

vi.mock('@/api/knowledge', () => ({
  listDatasets: vi.fn(), getDataset: vi.fn(), createDataset: vi.fn(),
  updateDataset: vi.fn(), deleteDataset: vi.fn(),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page(list: Dataset[]): PageResult<Dataset> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const MINE: Dataset = {
  id: '1', name: '客服知识库', description: '售后答疑', ownerId: '7',
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const OTHERS: Dataset = { ...MINE, id: '2', name: '他人知识库', ownerId: '999' }

describe('KnowledgeList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listDatasets).mockResolvedValue(page([MINE, OTHERS]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户=bob(7)
  })

  it('挂载拉取并渲染知识库名', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listDatasets).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('客服知识库')
    expect(wrapper.text()).toContain('他人知识库')
  })

  it('canModify 门控：自己的有编辑/删除按钮，他人没有', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="delete-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="delete-2"]').exists()).toBe(false)
  })

  it('admin 对他人知识库也有编辑按钮', async () => {
    const store = useUserStore()
    store.user = { id: '1', username: 'admin', role: 'admin' }
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(true)
  })

  it('搜索回车触发重查且回到第一页', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="search"]').setValue('客服')
    await wrapper.find('[data-test="search"]').trigger('keyup.enter')
    await flushPromises()
    expect(listDatasets).toHaveBeenLastCalledWith({ keyword: '客服', page: 1, size: 20 })
  })

  it('创建：空名不提交', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createDataset).not.toHaveBeenCalled()
  })

  it('创建：填名提交调用 createDataset', async () => {
    vi.mocked(createDataset).mockResolvedValue(MINE)
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-name"]').setValue('新知识库')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createDataset).toHaveBeenCalledWith({ name: '新知识库', description: '' })
  })

  it('编辑：回填并调用 updateDataset', async () => {
    vi.mocked(updateDataset).mockResolvedValue(MINE)
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-1"]').trigger('click')
    const nameInput = wrapper.find('[data-test="form-name"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('客服知识库')
    await nameInput.setValue('改名后')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateDataset).toHaveBeenCalledWith('1', { name: '改名后', description: '售后答疑' })
  })

  it('删除：确认后调用 deleteDataset', async () => {
    vi.mocked(deleteDataset).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteDataset).toHaveBeenCalledWith('1')
  })

  it('空列表：表格空态渲染，不报错', async () => {
    vi.mocked(listDatasets).mockResolvedValue(page([]))
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listDatasets).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-test="dataset-table"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('客服知识库')
  })
})
