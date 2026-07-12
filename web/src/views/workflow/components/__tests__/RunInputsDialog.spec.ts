import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import RunInputsDialog from '@/views/workflow/components/RunInputsDialog.vue'
import type { StartInputDecl } from '@/types/workflow'

const DialogStub = {
  name: 'ElDialog',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<div v-if="modelValue" data-test="dialog"><slot /><div data-test="dialog-footer"><slot name="footer" /></div></div>',
}

const DECLS: StartInputDecl[] = [{ name: 'city', required: true }, { name: 'note' }]

function mountDialog(initial: Record<string, string> = {}) {
  return mount(RunInputsDialog, {
    props: { visible: true, decls: DECLS, initial },
    global: { plugins: [ElementPlus], stubs: { ElDialog: DialogStub, transition: false } },
  })
}

describe('RunInputsDialog', () => {
  it('按声明渲染输入项，预填上次值', async () => {
    const w = mountDialog({ city: '北京' })
    await nextTick()
    expect((w.find('[data-test="run-input-city"] input').element as HTMLInputElement).value).toBe('北京')
    expect(w.find('[data-test="run-input-note"] input').exists()).toBe(true)
  })

  it('必填项为空提交 → 拦截并行内报错，不发 submit', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-submit"]').trigger('click')
    expect(w.emitted('submit')).toBeUndefined()
    // el-form-item 的错误文案经 100ms 防抖（refDebounced）才上 DOM，轮询等待而非 nextTick
    await vi.waitFor(() => expect(w.text()).toContain('必填项不能为空'))
  })

  it('填齐必填提交 → emit submit(values)；非必填可空', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-input-city"] input').setValue('上海')
    await w.find('[data-test="run-submit"]').trigger('click')
    expect(w.emitted('submit')![0][0]).toEqual({ city: '上海', note: '' })
  })

  it('取消 → update:visible false', async () => {
    const w = mountDialog()
    await nextTick()
    await w.find('[data-test="run-cancel"]').trigger('click')
    expect(w.emitted('update:visible')![0]).toEqual([false])
  })
})
