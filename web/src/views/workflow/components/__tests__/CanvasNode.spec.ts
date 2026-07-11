import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CanvasNode from '@/views/workflow/components/CanvasNode.vue'

// Handle 需要 VueFlow 注入的上下文，stub 掉——本测试只验证「按类型渲染哪几个连接点」
const HandleStub = {
  props: ['id', 'type', 'position'],
  template: '<span class="handle-stub" :data-type="type" :data-id="id ?? \'\'" />',
}

function mountNode(type: string, id = 'n1') {
  return mount(CanvasNode, {
    props: { id, type },
    global: { stubs: { Handle: HandleStub } },
  })
}

function handles(wrapper: ReturnType<typeof mountNode>) {
  return wrapper.findAll('.handle-stub').map((h) => ({
    type: h.attributes('data-type'),
    id: h.attributes('data-id'),
  }))
}

describe('CanvasNode', () => {
  it('展示类型标签与节点 id', () => {
    const w = mountNode('llm', 'llm_1')
    expect(w.text()).toContain('LLM')
    expect(w.text()).toContain('llm_1')
  })

  it('start：只出不进；end：只进不出', () => {
    expect(handles(mountNode('start', 'start'))).toEqual([{ type: 'source', id: '' }])
    expect(handles(mountNode('end', 'end'))).toEqual([{ type: 'target', id: '' }])
  })

  it('condition：一进两出，出口 handle id 为 true/false', () => {
    const hs = handles(mountNode('condition', 'if_1'))
    expect(hs).toContainEqual({ type: 'target', id: '' })
    expect(hs).toContainEqual({ type: 'source', id: 'true' })
    expect(hs).toContainEqual({ type: 'source', id: 'false' })
    expect(hs).toHaveLength(3)
  })

  it('普通节点（http）：一进一出', () => {
    const hs = handles(mountNode('http', 'http_1'))
    expect(hs.filter((h) => h.type === 'target')).toHaveLength(1)
    expect(hs.filter((h) => h.type === 'source')).toHaveLength(1)
  })
})
