import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import CodeForm from '../CodeForm.vue'

function mountForm(data = {}) {
  return mount(CodeForm, {
    props: { data, disabled: false },
    global: { plugins: [ElementPlus] },
  })
}

describe('CodeForm', () => {
  it('编辑代码框 → emit update 带 code', async () => {
    const w = mountForm({ code: '' })
    const ta = w.find('[data-test="code-source"] textarea')
    await ta.setValue('def main():\n    return {}')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ code: 'def main():\n    return {}' })
  })

  it('添加映射行并填形参名+值 → emit update 带 inputs', async () => {
    const w = mountForm({ code: 'def main(x): return {}', inputs: {} })
    await w.find('[data-test="code-input-add"]').trigger('click')
    const name = w.find('[data-test="code-input-name"] input')
    const value = w.find('[data-test="code-input-value"] input')
    await name.setValue('x')
    await value.setValue('{{start.q}}')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ inputs: { x: '{{start.q}}' } })
  })

  it('删除映射行 → emit update 里该键消失', async () => {
    const w = mountForm({ code: 'def main(x): return {}', inputs: { x: '{{start.q}}' } })
    await w.find('[data-test="code-input-remove"]').trigger('click')
    expect(w.emitted('update')?.at(-1)?.[0]).toMatchObject({ inputs: {} })
  })

  it('disabled 时表单只读', () => {
    const w = mount(CodeForm, {
      props: { data: { code: 'x' }, disabled: true },
      global: { plugins: [ElementPlus] },
    })
    expect(w.find('[data-test="form-code"]').attributes('class')).toBeDefined()
    expect(w.find('[data-test="code-source"] textarea').attributes('disabled')).toBeDefined()
  })
})
