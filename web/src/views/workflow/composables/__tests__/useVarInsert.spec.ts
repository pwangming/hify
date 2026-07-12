import { describe, it, expect } from 'vitest'
import { useVarInsert } from '@/views/workflow/composables/useVarInsert'

function makeTarget(initial: string) {
  let value = initial
  return {
    target: { get: () => value, set: (v: string) => { value = v } },
    read: () => value,
  }
}

describe('useVarInsert', () => {
  it('未聚焦过：插入默认字段末尾', () => {
    const { register, insert } = useVarInsert(() => 'url')
    const a = makeTarget('https://a.com?q=')
    register('url', a.target)
    insert('{{start.q}}')
    expect(a.read()).toBe('https://a.com?q={{start.q}}')
  })

  it('聚焦过 body：插入 body 而非默认字段', () => {
    const { register, onFocus, insert } = useVarInsert(() => 'url')
    const url = makeTarget('u')
    const body = makeTarget('x')
    register('url', url.target)
    register('body', body.target)
    onFocus('body')
    insert('{{llm_1.text}}')
    expect(url.read()).toBe('u')
    expect(body.read()).toBe('x{{llm_1.text}}')
  })

  it('提供 el 时按光标位置插入', () => {
    const { register, insert } = useVarInsert(() => 'left')
    const t = makeTarget('ab')
    register('left', { ...t.target, el: () => ({ selectionStart: 1 }) as HTMLInputElement })
    insert('{{kb_1.text}}')
    expect(t.read()).toBe('a{{kb_1.text}}b')
  })

  it('聚焦的字段已注销（如行被删）：回落默认字段', () => {
    const { register, onFocus, insert } = useVarInsert(() => 'left')
    const t = makeTarget('')
    register('left', t.target)
    onFocus('value_5')
    insert('{{start.q}}')
    expect(t.read()).toBe('{{start.q}}')
  })

  it('默认字段也不存在：静默忽略', () => {
    const { insert } = useVarInsert(() => 'nope')
    expect(() => insert('{{x.y}}')).not.toThrow()
  })
})
