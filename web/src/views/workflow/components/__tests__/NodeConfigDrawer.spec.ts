import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeConfigDrawer from '@/views/workflow/components/NodeConfigDrawer.vue'
import type { FlowEdge, FlowNode } from '@/views/workflow/composables/graphTransform'

vi.mock('@/api/provider', () => ({ listChatModels: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/knowledge', () => ({
  listDatasets: vi.fn().mockResolvedValue({ list: [] }),
}))

const DrawerStub = {
  name: 'ElDrawer',
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<div v-if="modelValue" data-test="drawer"><div data-test="drawer-header"><slot name="header" /></div><slot /></div>',
}

function n(id: string, type: FlowNode['type'], data: FlowNode['data'] = {}): FlowNode {
  return { id, type, data, position: { x: 0, y: 0 }, deletable: true }
}

const NODES: FlowNode[] = [
  n('start', 'start', { inputs: [{ name: 'q' }] }),
  n('kb_1', 'knowledge-retrieval'),
  n('if_1', 'condition'),
]
const EDGES: FlowEdge[] = [
  { id: 'e-start-_-kb_1', source: 'start', target: 'kb_1' },
  { id: 'e-kb_1-_-if_1', source: 'kb_1', target: 'if_1' },
]

function mountDrawer(node: FlowNode | null, canEdit = true) {
  return mount(NodeConfigDrawer, {
    props: { node, nodes: NODES, edges: EDGES, canEdit },
    // transition: false——el-tag 根是 <transition>，VTU 默认 stub 会截走 attrs/监听，点击测不到
    global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub, transition: false } },
  })
}

describe('NodeConfigDrawer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('node 为 null 不渲染', () => {
    expect(mountDrawer(null).find('[data-test="drawer"]').exists()).toBe(false)
  })

  it('按类型挂对表单，标题含类型名与节点 id', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
    expect(w.find('[data-test="drawer-header"]').text()).toContain('条件分支')
    expect(w.find('[data-test="drawer-header"]').text()).toContain('if_1')
  })

  it('表单 update → 转发为 (nodeId, patch)', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    await w.find('[data-test="cond-left"] input').setValue('{{kb_1.count}}')
    expect(w.emitted('update')?.at(-1)).toEqual(['if_1', { left: '{{kb_1.count}}' }])
  })

  it('变量面板列出祖先，点击 → 插入当前表单默认字段', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    const tags = w.findAll('[data-test="var-tag"]')
    expect(tags.map((t) => t.text())).toEqual(['text', 'count', 'q'])
    await tags[0].trigger('click')
    expect(w.emitted('update')?.at(-1)).toEqual(['if_1', { left: '{{kb_1.text}}' }])
  })

  it('start 节点不展示变量面板（无变量字段）', async () => {
    const w = mountDrawer(NODES[0])
    await flushPromises()
    expect(w.find('[data-test="form-start"]').exists()).toBe(true)
    expect(w.find('[data-test="var-panel"]').exists()).toBe(false)
  })

  it('canEdit=false → 表单控件禁用', async () => {
    const w = mountDrawer(NODES[2], false)
    await flushPromises()
    expect(w.find('[data-test="cond-left"] input').attributes('disabled')).toBeDefined()
  })
})
