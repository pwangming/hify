import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HttpForm from '@/views/workflow/components/forms/HttpForm.vue'
import type { HttpNodeData } from '@/types/workflow'

function mountForm(data: HttpNodeData = {}, disabled = false) {
  return mount(HttpForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('HttpForm', () => {
  it('method 为空时挂载即写回默认 GET（可编辑态）', () => {
    const w = mountForm()
    expect(w.emitted('update')?.[0]).toEqual([{ method: 'GET' }])
  })

  it('disabled（非 owner 只读）不写回默认值——否则白造 dirty', () => {
    const w = mountForm({}, true)
    expect(w.emitted('update')).toBeUndefined()
  })

  it('headers 回显为行，编辑后写回对象（空 key 过滤）', async () => {
    const w = mountForm({ method: 'POST', headers: { 'X-Token': 'abc' } })
    const keys = w.findAll('[data-test="http-header-key"] input')
    expect((keys[0].element as HTMLInputElement).value).toBe('X-Token')
    await w.find('[data-test="http-header-add"]').trigger('click')
    await w.findAll('[data-test="http-header-value"] input')[1].setValue('v2')
    expect(w.emitted('update')?.at(-1)).toEqual([{ headers: { 'X-Token': 'abc' } }])
    await w.findAll('[data-test="http-header-key"] input')[1].setValue('X-Trace')
    expect(w.emitted('update')?.at(-1)).toEqual([
      { headers: { 'X-Token': 'abc', 'X-Trace': 'v2' } },
    ])
  })

  it('删行写回', async () => {
    const w = mountForm({ method: 'GET', headers: { A: '1', B: '2' } })
    await w.findAll('[data-test="http-header-remove"]')[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual([{ headers: { B: '2' } }])
  })

  it('insertVar 默认插入 url 末尾', () => {
    const w = mountForm({ method: 'GET', url: 'https://a.com?q=' })
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{start.q}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ url: 'https://a.com?q={{start.q}}' }])
  })
})
