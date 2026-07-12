import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import EndForm from '@/views/workflow/components/forms/EndForm.vue'
import type { EndNodeData } from '@/types/workflow'

function mountForm(data: EndNodeData = {}, disabled = false) {
  return mount(EndForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('EndForm', () => {
  it('回显输出行并可编辑', async () => {
    const w = mountForm({ outputs: [{ name: 'answer', value: '{{llm_1.text}}' }] })
    expect((w.find('[data-test="end-output-name"] input').element as HTMLInputElement).value)
      .toBe('answer')
    await w.find('[data-test="end-output-value"] input').setValue('{{http_1.body}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'answer', value: '{{http_1.body}}' }] },
    ])
  })

  it('添加/删除行', async () => {
    const w = mountForm({ outputs: [{ name: 'a', value: '1' }] })
    await w.find('[data-test="end-output-add"]').trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'a', value: '1' }, { name: '', value: '' }] },
    ])
    await w.findAll('[data-test="end-output-remove"]')[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ outputs: [] }])
  })

  it('insertVar 无行时自动新增一行再插入 value（spec §4）', () => {
    const w = mountForm({})
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{llm_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: '', value: '{{llm_1.text}}' }] },
    ])
  })

  it('insertVar 有行且未聚焦 → 插入最后一行 value 末尾', () => {
    const w = mountForm({ outputs: [{ name: 'a', value: 'x' }, { name: 'b', value: 'y' }] })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.count}}')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { outputs: [{ name: 'a', value: 'x' }, { name: 'b', value: 'y{{kb_1.count}}' }] },
    ])
  })
})
