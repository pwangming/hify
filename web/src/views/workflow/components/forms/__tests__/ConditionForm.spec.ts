import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConditionForm from '@/views/workflow/components/forms/ConditionForm.vue'
import type { ConditionNodeData } from '@/types/workflow'

function mountForm(data: ConditionNodeData = {}, disabled = false) {
  return mount(ConditionForm, {
    props: { data, disabled },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConditionForm', () => {
  it('回显 left/right', () => {
    const w = mountForm({ left: '{{kb_1.count}}', operator: '>', right: '0' })
    expect((w.find('[data-test="cond-left"] input').element as HTMLInputElement).value)
      .toBe('{{kb_1.count}}')
    expect((w.find('[data-test="cond-right"] input').element as HTMLInputElement).value).toBe('0')
  })

  it('改左值 → emit update 补丁', async () => {
    const w = mountForm()
    await w.find('[data-test="cond-left"] input').setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ left: '{{start.q}}' }])
  })

  it('insertVar 默认插入 left 末尾', () => {
    const w = mountForm({ left: 'a=' })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ left: 'a={{kb_1.text}}' }])
  })

  it('disabled 时输入框禁用', () => {
    const w = mountForm({}, true)
    expect(w.find('[data-test="cond-left"] input').attributes('disabled')).toBeDefined()
  })
})
