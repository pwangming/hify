import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeRunPanel from '@/views/workflow/components/NodeRunPanel.vue'
import type { NodeRunView } from '@/types/workflow'

function nodeRun(over: Partial<NodeRunView> = {}): NodeRunView {
  return {
    id: '1', nodeId: 'llm_1', nodeType: 'llm', status: 'succeeded',
    inputs: { prompt: '你好' }, outputs: { text: '答案' },
    errorMessage: null, elapsedMs: '1234', createTime: '2026-07-12T10:00:00+08:00',
    ...over,
  }
}

function mountPanel(nr: NodeRunView) {
  return mount(NodeRunPanel, { props: { nodeRun: nr }, global: { plugins: [ElementPlus] } })
}

describe('NodeRunPanel', () => {
  it('成功：展示输入/输出 JSON 与耗时', () => {
    const w = mountPanel(nodeRun())
    expect(w.find('[data-test="node-run-inputs"]').text()).toContain('"prompt": "你好"')
    expect(w.find('[data-test="node-run-outputs"]').text()).toContain('"text": "答案"')
    expect(w.text()).toContain('1.2s')
    expect(w.find('[data-test="node-run-error"]').exists()).toBe(false)
  })

  it('失败：额外展示错误信息', () => {
    const w = mountPanel(nodeRun({ status: 'failed', outputs: null, errorMessage: '模型不存在' }))
    expect(w.find('[data-test="node-run-error"]').text()).toContain('模型不存在')
  })

  it('skipped：仅空态文案，不渲染输入输出', () => {
    const w = mountPanel(nodeRun({ status: 'skipped', inputs: null, outputs: null, elapsedMs: '0' }))
    expect(w.text()).toContain('未命中分支，已跳过')
    expect(w.find('[data-test="node-run-inputs"]').exists()).toBe(false)
  })
})
