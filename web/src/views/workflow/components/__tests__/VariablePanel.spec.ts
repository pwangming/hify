import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import VariablePanel from '@/views/workflow/components/VariablePanel.vue'
import type { UpstreamVar } from '@/views/workflow/composables/useUpstreamVars'

const VARS: UpstreamVar[] = [
  { nodeId: 'kb_1', type: 'knowledge-retrieval', fields: ['text', 'count'] },
  { nodeId: 'start', type: 'start', fields: [] },
]

function mountPanel(props: { vars: UpstreamVar[]; disabled?: boolean }) {
  return mount(VariablePanel, {
    props,
    // transition: false——el-tag 根是 <transition>，VTU 默认 stub 会截走 attrs/监听，点击测不到
    global: { plugins: [ElementPlus], stubs: { transition: false } },
  })
}

describe('VariablePanel', () => {
  it('按祖先分组列出字段标签', () => {
    const w = mountPanel({ vars: VARS })
    expect(w.text()).toContain('kb_1')
    const tags = w.findAll('[data-test="var-tag"]')
    expect(tags.map((t) => t.text())).toEqual(['text', 'count'])
  })

  it('点击标签 → emit insert 完整变量引用', async () => {
    const w = mountPanel({ vars: VARS })
    await w.findAll('[data-test="var-tag"]')[0].trigger('click')
    expect(w.emitted('insert')).toEqual([['{{kb_1.text}}']])
  })

  it('disabled 时点击不 emit', async () => {
    const w = mountPanel({ vars: VARS, disabled: true })
    await w.findAll('[data-test="var-tag"]')[0].trigger('click')
    expect(w.emitted('insert')).toBeUndefined()
  })

  it('无祖先 → 空态文案', () => {
    const w = mountPanel({ vars: [] })
    expect(w.text()).toContain('连线后这里会列出可引用的上游输出')
  })

  it('start 未声明输入 → 组内提示', () => {
    const w = mountPanel({ vars: VARS })
    expect(w.text()).toContain('未声明输入')
  })

  it('模板不渲染杂散字符（终审回归：曾有孤立 > 文本节点）', () => {
    const w = mountPanel({ vars: VARS })
    expect(w.text()).not.toContain('>')
  })
})
