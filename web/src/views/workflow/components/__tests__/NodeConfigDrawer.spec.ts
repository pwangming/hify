import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NodeConfigDrawer from '@/views/workflow/components/NodeConfigDrawer.vue'
import type { NodeRunView } from '@/types/workflow'
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

  const NODE_RUN: NodeRunView = {
    id: '1', nodeId: 'if_1', nodeType: 'condition', status: 'succeeded',
    inputs: { left: '3' }, outputs: { hit: true },
    errorMessage: null, elapsedMs: '5', createTime: '2026-07-12T10:00:00+08:00',
  }

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

  it('无 nodeRun：不渲染 tabs（现状不变）', async () => {
    const w = mountDrawer(NODES[2])
    await flushPromises()
    expect(w.find('[data-test="drawer-tabs"]').exists()).toBe(false)
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
  })

  it('有 nodeRun：渲染双 tab 且默认落「运行」，运行面板拿到记录', async () => {
    const w = mount(NodeConfigDrawer, {
      props: { node: NODES[2], nodes: NODES, edges: EDGES, canEdit: true, nodeRun: NODE_RUN },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub, transition: false } },
    })
    await flushPromises()
    expect(w.find('[data-test="drawer-tabs"]').exists()).toBe(true)
    expect(w.find('[data-test="node-run-panel"]').exists()).toBe(true)
    expect(w.find('[data-test="node-run-outputs"]').text()).toContain('"hit": true')
  })

  it('有 nodeRun 时切到「配置」tab 仍可编辑表单', async () => {
    const w = mount(NodeConfigDrawer, {
      props: { node: NODES[2], nodes: NODES, edges: EDGES, canEdit: true, nodeRun: NODE_RUN },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub, transition: false } },
    })
    await flushPromises()
    await w.findAll('.el-tabs__item')[0].trigger('click')
    expect(w.find('[data-test="form-condition"]').exists()).toBe(true)
  })
})
