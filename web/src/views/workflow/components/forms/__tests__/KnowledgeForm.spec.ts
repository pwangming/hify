import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listDatasets } from '@/api/knowledge'
import KnowledgeForm from '@/views/workflow/components/forms/KnowledgeForm.vue'
import type { KnowledgeNodeData } from '@/types/workflow'

vi.mock('@/api/knowledge', () => ({ listDatasets: vi.fn() }))

const DATASETS = {
  list: [
    { id: '5', name: '产品手册' },
    { id: '8', name: 'FAQ' },
  ],
}

function mountForm(data: KnowledgeNodeData = {}, disabled = false) {
  return mount(KnowledgeForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('KnowledgeForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listDatasets).mockResolvedValue(DATASETS as never)
  })

  it('挂载拉取知识库列表（page/size 固定 1/100）', async () => {
    mountForm()
    await flushPromises()
    expect(listDatasets).toHaveBeenCalledWith({ page: 1, size: 100 })
  })

  it('已选但被删除的库 → 禁用兜底项', async () => {
    const w = mountForm({ datasetIds: ['99', '5'] })
    await flushPromises()
    const vm = w.vm as unknown as {
      selectOptions: { value: string; label: string; disabled: boolean }[]
    }
    expect(vm.selectOptions[0]).toEqual({ value: '99', label: '已删除的知识库', disabled: true })
  })

  it('改 query → emit update', async () => {
    const w = mountForm()
    await flushPromises()
    await w.find('[data-test="kb-query"] input').setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ query: '{{start.q}}' }])
  })

  it('insertVar 默认插入 query 末尾', async () => {
    const w = mountForm({ query: '搜索：' })
    await flushPromises()
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ query: '搜索：{{start.q}}' }])
  })
})
