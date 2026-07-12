import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StartForm from '@/views/workflow/components/forms/StartForm.vue'
import type { StartNodeData } from '@/types/workflow'

function mountForm(data: StartNodeData = {}, disabled = false) {
  return mount(StartForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('StartForm', () => {
  it('回显声明行', () => {
    const w = mountForm({ inputs: [{ name: 'city' }, { name: 'q' }] })
    const names = w.findAll('[data-test="start-input-name"] input')
    expect(names.map((i) => (i.element as HTMLInputElement).value)).toEqual(['city', 'q'])
  })

  it('添加行 → emit 整个 inputs 数组', async () => {
    const w = mountForm({ inputs: [{ name: 'city' }] })
    await w.find('[data-test="start-input-add"]').trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'city' }, { name: '' }] }])
  })

  it('改名/删行 → emit 不可变副本', async () => {
    const w = mountForm({ inputs: [{ name: 'city' }, { name: 'q' }] })
    await w.findAll('[data-test="start-input-name"] input')[0].setValue('town')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'town' }, { name: 'q' }] }])
    await w.findAll('[data-test="start-input-remove"]')[1].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ inputs: [{ name: 'city' }] }])
  })

  it('非法名字显示警告（不阻断）', async () => {
    const w = mountForm({ inputs: [{ name: '中文名' }] })
    expect(w.find('[data-test="start-input-warn"]').exists()).toBe(true)
    const ok = mountForm({ inputs: [{ name: 'city_1' }] })
    expect(ok.find('[data-test="start-input-warn"]').exists()).toBe(false)
  })
})
