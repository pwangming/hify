import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RunStatusChip from '@/views/workflow/components/RunStatusChip.vue'
import type { RunResponse } from '@/types/workflow'

const PopoverStub = {
  name: 'ElPopover',
  template:
    '<div><slot name="reference" /><div data-test="chip-popover"><slot /></div></div>',
}

const BASE: RunResponse = {
  id: '9', status: 'succeeded', inputs: {}, outputs: { answer: '42' },
  errorMessage: null, elapsedMs: '3210', createTime: '2026-07-12T10:00:00+08:00', nodeRuns: [],
}

function mountChip(run: RunResponse | null) {
  return mount(RunStatusChip, {
    props: { run },
    global: { plugins: [ElementPlus], stubs: { ElPopover: PopoverStub, transition: false } },
  })
}

describe('RunStatusChip', () => {
  it('run 为 null 不渲染', () => {
    expect(mountChip(null).find('[data-test="run-chip"]').exists()).toBe(false)
  })

  it('成功：绿 tag 含耗时，popover 展示最终输出 JSON', () => {
    const w = mountChip(BASE)
    const chip = w.find('[data-test="run-chip"]')
    expect(chip.text()).toContain('成功')
    expect(chip.text()).toContain('3.2s')
    expect(w.find('[data-test="chip-popover"]').text()).toContain('"answer": "42"')
  })

  it('失败：红 tag，popover 展示整体错误信息', () => {
    const w = mountChip({ ...BASE, status: 'failed', outputs: null, errorMessage: '节点 llm_1 失败：模型不存在' })
    expect(w.find('[data-test="run-chip"]').text()).toContain('失败')
    expect(w.find('[data-test="chip-popover"]').text()).toContain('模型不存在')
  })
})
