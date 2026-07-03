import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import {
  getDataset, listDocuments, uploadDocument, deleteDocument, listChunks, retryDocument,
} from '@/api/knowledge'
import type { Dataset, KbDocument, Chunk } from '@/types/knowledge'
import type { PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import DatasetDetail from '@/views/knowledge/DatasetDetail.vue'

vi.mock('@/api/knowledge', () => ({
  getDataset: vi.fn(), listDatasets: vi.fn(), createDataset: vi.fn(),
  updateDataset: vi.fn(), deleteDataset: vi.fn(),
  uploadDocument: vi.fn(), listDocuments: vi.fn(), deleteDocument: vi.fn(), listChunks: vi.fn(),
  retryDocument: vi.fn(),
}))

const routerPush = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '10' } }),
  useRouter: () => ({ push: routerPush }),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page<T>(list: T[]): PageResult<T> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const DATASET: Dataset = {
  id: '10', name: '客服知识库', description: '售后答疑', ownerId: '7',
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const DOC: KbDocument = {
  id: '20', datasetId: '10', name: 'faq.txt', fileType: 'txt', fileSize: '1024',
  status: 'ready', chunkCount: 3, errorMessage: null,
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const FAILED_DOC: KbDocument = {
  ...DOC, id: '21', name: 'bad.txt', status: 'failed', chunkCount: 0,
  errorMessage: '系统未配置 embedding 模型，请联系管理员在系统设置中配置',
}
const PROCESSING_DOC: KbDocument = { ...DOC, id: '22', name: 'wip.txt', status: 'processing' }
const CHUNK: Chunk = { id: '30', position: 1, content: '第一段内容' }

async function mountPage() {
  const wrapper = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

async function selectFile(wrapper: ReturnType<typeof mount>, name: string) {
  const input = wrapper.find('input[type="file"]')
  const file = new File(['hello world'], name, { type: 'text/plain' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('DatasetDetail', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(getDataset).mockResolvedValue(DATASET)
    vi.mocked(listDocuments).mockResolvedValue(page([DOC]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户 = owner
  })

  it('挂载：拉取库信息与文档列表并渲染', async () => {
    const wrapper = await mountPage()
    expect(getDataset).toHaveBeenCalledWith('10')
    expect(listDocuments).toHaveBeenCalledWith('10', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('客服知识库')
    expect(wrapper.text()).toContain('faq.txt')
  })

  it('owner 可见上传控件与删除按钮', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="doc-delete-20"]').exists()).toBe(true)
  })

  it('非 owner 非 admin：无上传控件、无删除按钮，但能看列表', async () => {
    const store = useUserStore()
    store.user = { id: '999', username: 'carol', role: 'member' }
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('faq.txt')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="doc-delete-20"]').exists()).toBe(false)
  })

  it('选择 txt 文件触发上传并刷新列表', async () => {
    vi.mocked(uploadDocument).mockResolvedValue(DOC)
    const wrapper = await mountPage()
    await selectFile(wrapper, 'new.txt')
    expect(uploadDocument).toHaveBeenCalledWith('10', expect.any(File))
    expect(listDocuments).toHaveBeenCalledTimes(2) // 挂载 1 次 + 上传成功后刷新 1 次
  })

  it('文件名超 200 字符：前端拦截不调上传', async () => {
    const wrapper = await mountPage()
    await selectFile(wrapper, 'n'.repeat(201) + '.txt')
    expect(uploadDocument).not.toHaveBeenCalled()
  })

  it('选择不支持的扩展名：前端拦截不调上传', async () => {
    const wrapper = await mountPage()
    await selectFile(wrapper, 'report.pdf')
    expect(uploadDocument).not.toHaveBeenCalled()
  })

  it('删除文档：确认后调用并刷新', async () => {
    vi.mocked(deleteDocument).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-delete-20"]').trigger('click')
    await flushPromises()
    expect(deleteDocument).toHaveBeenCalledWith('20')
  })

  it('failed 文档：owner 可见重试按钮，点击后调用并刷新', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([FAILED_DOC]))
    vi.mocked(retryDocument).mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-retry-21"]').trigger('click')
    await flushPromises()
    expect(retryDocument).toHaveBeenCalledWith('21')
    expect(listDocuments).toHaveBeenCalledTimes(2)
  })

  it('ready 文档：无重试按钮', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-retry-20"]').exists()).toBe(false)
  })

  it('非 owner 非 admin：failed 文档也无重试按钮', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([FAILED_DOC]))
    const store = useUserStore()
    store.user = { id: '999', username: 'carol', role: 'member' }
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-retry-21"]').exists()).toBe(false)
  })

  it('存在 processing 文档：启动轮询定时刷新，全部终态后停止', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(listDocuments)
        .mockResolvedValueOnce(page([PROCESSING_DOC]))
        .mockResolvedValueOnce(page([DOC]))
      const wrapper = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(2)

      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(2)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('全部终态：不启动轮询', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
      await flushPromises()
      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('查看分段：打开抽屉并分页拉取', async () => {
    vi.mocked(listChunks).mockResolvedValue(page([CHUNK]))
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-chunks-20"]').trigger('click')
    await flushPromises()
    expect(listChunks).toHaveBeenCalledWith('20', { page: 1, size: 10 })
    expect(document.body.textContent).toContain('第一段内容') // el-drawer 传送到 body
  })

  it('文档空列表：渲染空态不报错', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([]))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-table"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('faq.txt')
  })
})
