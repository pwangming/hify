import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { getApp } from '@/api/app'
import { getDraft, runWorkflow, saveDraft } from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import WorkflowEditor from '@/views/workflow/WorkflowEditor.vue'
import type { App } from '@/types/app'
import type { DraftResponse, RunResponse } from '@/types/workflow'

vi.mock('@/api/app', () => ({ getApp: vi.fn() }))
vi.mock('@/api/workflow', () => ({ getDraft: vi.fn(), saveDraft: vi.fn(), runWorkflow: vi.fn() }))

const routerPush = vi.fn()
let leaveGuard: (() => Promise<boolean> | boolean) | null = null
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { appId: '42' } }),
  useRouter: () => ({ push: routerPush }),
  onBeforeRouteLeave: (fn: () => Promise<boolean> | boolean) => {
    leaveGuard = fn
  },
}))

// @vue-flow/* 全 mock 成具名 stub：本测试只验证编辑器自身逻辑（加载/保存/权限/守卫），不测库。
// 注意 vi.mock 工厂会被提升，不能引用外部 const，stub 直接内联在工厂里。
vi.mock('@vue-flow/core', () => ({
  VueFlow: {
    name: 'VueFlow',
    props: ['nodes', 'edges'],
    template: '<div data-test="vf-stub" />',
  },
  Handle: { name: 'Handle', template: '<span />' },
  Position: { Left: 'left', Right: 'right' },
  useVueFlow: () => ({ screenToFlowCoordinate: (p: { x: number; y: number }) => p }),
}))
vi.mock('@vue-flow/background', () => ({ Background: { name: 'Background', template: '<span />' } }))
vi.mock('@vue-flow/controls', () => ({ Controls: { name: 'Controls', template: '<span />' } }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

const WF_APP: App = {
  id: '42', name: '工单分类器', description: null, type: 'workflow', modelId: null,
  modelName: null, modelUsable: false, config: { systemPrompt: null }, datasetIds: [],
  ownerId: '7', status: 'enabled',
  createTime: '2026-07-11T09:00:00+08:00', updateTime: '2026-07-11T09:00:00+08:00',
}
const DRAFT: DraftResponse = {
  graph: {
    nodes: [
      { id: 'start', type: 'start', data: {}, position: { x: 80, y: 200 } },
      { id: 'end', type: 'end', data: {}, position: { x: 640, y: 200 } },
    ],
    edges: [{ source: 'start', target: 'end', sourceHandle: null }],
  },
  updateTime: '2026-07-11T10:00:00+08:00',
}
const RUN_OK: RunResponse = {
  id: '9', status: 'succeeded', inputs: {}, outputs: { answer: 'ok' },
  errorMessage: null, elapsedMs: '3200', createTime: '2026-07-12T10:00:00+08:00',
  nodeRuns: [{
    id: '1', nodeId: 'start', nodeType: 'start', status: 'succeeded', inputs: {}, outputs: {},
    errorMessage: null, elapsedMs: '0', createTime: '2026-07-12T10:00:00+08:00',
  }],
}

function mountEditor() {
  return mount(WorkflowEditor, {
    global: {
      plugins: [ElementPlus],
      stubs: { NodePalette: true, NodeConfigDrawer: true },
    },
  })
}

describe('WorkflowEditor', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    leaveGuard = null
    vi.mocked(getApp).mockResolvedValue(WF_APP)
    vi.mocked(getDraft).mockResolvedValue(DRAFT)
    useUserStore().user = { id: '7', username: 'bob', role: 'member' } // 默认 owner 本人
  })

  it('挂载：拉应用与草稿，画布收到转换后的 nodes/edges，工具栏显示应用名与保存时间', async () => {
    const w = mountEditor()
    await flushPromises()
    expect(getApp).toHaveBeenCalledWith('42')
    expect(getDraft).toHaveBeenCalledWith('42')
    expect(w.find('[data-test="vf-stub"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    expect(vf.props('nodes')).toHaveLength(2)
    expect(vf.props('edges')).toHaveLength(1)
    expect(w.text()).toContain('工单分类器')
  })

  it('chat 应用误入 → 跳回应用列表', async () => {
    vi.mocked(getApp).mockResolvedValue({ ...WF_APP, type: 'chat' })
    mountEditor()
    await flushPromises()
    expect(routerPush).toHaveBeenCalledWith('/app')
  })

  it('owner 点保存 → saveDraft 提交草稿并提示成功', async () => {
    vi.mocked(saveDraft).mockResolvedValue({ ...DRAFT, updateTime: '2026-07-11T11:00:00+08:00' })
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-save"]').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledWith('42', expect.objectContaining({
      nodes: expect.arrayContaining([expect.objectContaining({ id: 'start' })]),
    }))
  })

  it('非 owner 非 admin：保存按钮禁用', async () => {
    useUserStore().user = { id: '999', username: 'eve', role: 'member' }
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-save"]').attributes('disabled')).toBeDefined()
  })

  it('admin 可保存他人应用', async () => {
    useUserStore().user = { id: '1', username: 'root', role: 'admin' }
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-save"]').attributes('disabled')).toBeUndefined()
  })

  it('离开守卫：不 dirty 直接放行', async () => {
    mountEditor()
    await flushPromises()
    expect(leaveGuard).not.toBeNull()
    expect(await leaveGuard!()).toBe(true)
  })

  it('点节点 → 抽屉收到该节点；点空白 → 关闭', async () => {
    const w = mountEditor()
    await flushPromises()
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'start' } })
    await nextTick()
    const drawer = w.findComponent({ name: 'NodeConfigDrawer' })
    expect(drawer.props('node')).toMatchObject({ id: 'start', type: 'start' })
    expect(drawer.props('canEdit')).toBe(true)
    vf.vm.$emit('pane-click')
    await nextTick()
    expect(drawer.props('node')).toBeNull()
  })

  it('抽屉 update → 节点 data 写回，保存提交新配置', async () => {
    vi.mocked(saveDraft).mockResolvedValue(DRAFT)
    const w = mountEditor()
    await flushPromises()
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'start' } })
    await nextTick()
    const drawer = w.findComponent({ name: 'NodeConfigDrawer' })
    drawer.vm.$emit('update', 'start', { inputs: [{ name: 'q' }] })
    await nextTick()
    await w.find('[data-test="wf-save"]').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledWith('42', expect.objectContaining({
      nodes: expect.arrayContaining([
        expect.objectContaining({ id: 'start', data: { inputs: [{ name: 'q' }] } }),
      ]),
    }))
  })

  it('抽屉打开时删除选中节点 → node 回落 null（抽屉关闭）', async () => {
    const w = mountEditor()
    await flushPromises()
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'ghost' } })
    await nextTick()
    expect(w.findComponent({ name: 'NodeConfigDrawer' }).props('node')).toBeNull()
  })

  it('start 无声明输入：点运行 → 不弹窗直接触发，完成后状态条出现、抽屉能拿到 nodeRun', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalledWith('42', {})
    expect(w.find('[data-test="run-chip"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'start' } })
    await nextTick()
    expect(w.findComponent({ name: 'NodeConfigDrawer' }).props('nodeRun'))
      .toMatchObject({ nodeId: 'start', status: 'succeeded' })
  })

  it('start 声明了输入：点运行 → 先弹输入表单，提交后带值触发', async () => {
    vi.mocked(getDraft).mockResolvedValue({
      ...DRAFT,
      graph: {
        ...DRAFT.graph,
        nodes: [
          { id: 'start', type: 'start', data: { inputs: [{ name: 'city', required: true }] }, position: { x: 80, y: 200 } },
          { id: 'end', type: 'end', data: {}, position: { x: 640, y: 200 } },
        ],
      },
    })
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await nextTick()
    expect(runWorkflow).not.toHaveBeenCalled()
    const dialog = w.findComponent({ name: 'RunInputsDialog' })
    expect(dialog.props('visible')).toBe(true)
    dialog.vm.$emit('submit', { city: '北京' })
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalledWith('42', { city: '北京' })
  })

  it('dirty 时点运行 → 先自动保存再运行', async () => {
    vi.mocked(saveDraft).mockResolvedValue(DRAFT)
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    const drawerC = w.findComponent({ name: 'NodeConfigDrawer' })
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'end' } })
    await nextTick()
    drawerC.vm.$emit('update', 'end', { outputs: [{ name: 'a', value: '{{start.q}}' }] })
    await nextTick()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(saveDraft).toHaveBeenCalledTimes(1)
    expect(runWorkflow).toHaveBeenCalledTimes(1)
  })

  it('图再改动 → 状态条清空（结果过期）', async () => {
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-test="run-chip"]').exists()).toBe(true)
    const vf = w.findComponent({ name: 'VueFlow' })
    vf.vm.$emit('node-click', { node: { id: 'end' } })
    await nextTick()
    w.findComponent({ name: 'NodeConfigDrawer' }).vm.$emit('update', 'end', { outputs: [{ name: 'x', value: 'y' }] })
    await flushPromises()
    expect(w.find('[data-test="run-chip"]').exists()).toBe(false)
  })

  it('非 owner 也能运行（运行权限全员）', async () => {
    useUserStore().user = { id: '999', username: 'eve', role: 'member' }
    vi.mocked(runWorkflow).mockResolvedValue(RUN_OK)
    const w = mountEditor()
    await flushPromises()
    expect(w.find('[data-test="wf-run"]').attributes('disabled')).toBeUndefined()
    await w.find('[data-test="wf-run"]').trigger('click')
    await flushPromises()
    expect(runWorkflow).toHaveBeenCalled()
  })
})
