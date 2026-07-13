import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodePalette from '@/views/workflow/components/NodePalette.vue'

describe('NodePalette', () => {
  it('提供 llm/知识检索/条件/http/code 五类可拖条目，不含 start/end', () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    const items = w.findAll('[data-test^="palette-"]')
    expect(items.map((i) => i.attributes('data-test'))).toEqual([
      'palette-llm', 'palette-knowledge-retrieval', 'palette-condition', 'palette-http', 'palette-code',
    ])
    expect(items.every((i) => i.attributes('draggable') === 'true')).toBe(true)
  })

  it('dragstart 把节点类型写进 dataTransfer（application/hify-node）', async () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    const setData = vi.fn()
    await w.find('[data-test="palette-llm"]').trigger('dragstart', {
      dataTransfer: { setData, effectAllowed: '' },
    })
    expect(setData).toHaveBeenCalledWith('application/hify-node', 'llm')
  })

  it('含代码执行节点可拖拽', () => {
    const w = mount(NodePalette, { global: { plugins: [ElementPlus] } })
    expect(w.find('[data-test="palette-code"]').exists()).toBe(true)
    expect(w.text()).toContain('代码执行')
  })
})
