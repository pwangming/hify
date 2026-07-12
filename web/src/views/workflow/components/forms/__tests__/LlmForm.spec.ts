import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listChatModels } from '@/api/provider'
import LlmForm from '@/views/workflow/components/forms/LlmForm.vue'
import type { LlmNodeData } from '@/types/workflow'

vi.mock('@/api/provider', () => ({ listChatModels: vi.fn() }))

const MODELS = [
  { id: '3', name: 'qwen-max', type: 'chat', providerName: '通义' },
  { id: '5', name: 'claude', type: 'chat', providerName: 'Anthropic' },
]

function mountForm(data: LlmNodeData = {}, disabled = false) {
  return mount(LlmForm, { props: { data, disabled }, global: { plugins: [ElementPlus] } })
}

describe('LlmForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listChatModels).mockResolvedValue(MODELS)
  })

  it('挂载拉取模型列表作为选项', async () => {
    mountForm()
    await flushPromises()
    expect(listChatModels).toHaveBeenCalled()
  })

  it('所选模型已失效 → 禁用兜底项，不裸露数字 id', async () => {
    const w = mountForm({ modelId: '99' })
    await flushPromises()
    const vm = w.vm as unknown as {
      selectOptions: { value: string; label: string; disabled: boolean }[]
    }
    expect(vm.selectOptions[0]).toEqual({
      value: '99',
      label: '已失效模型（已停用）',
      disabled: true,
    })
  })

  it('改用户提示词 → emit update', async () => {
    const w = mountForm()
    await flushPromises()
    await w.find('[data-test="llm-user-prompt"] textarea').setValue('总结：{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ userPrompt: '总结：{{kb_1.text}}' }])
  })

  it('insertVar 默认插入 userPrompt 末尾', async () => {
    const w = mountForm({ userPrompt: '内容：' })
    await flushPromises()
    ;(w.vm as unknown as { insertVar: (t: string) => void }).insertVar('{{kb_1.text}}')
    expect(w.emitted('update')?.at(-1)).toEqual([{ userPrompt: '内容：{{kb_1.text}}' }])
  })
})
