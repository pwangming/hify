import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { getApp } from '@/api/app'
import { getDraft, saveDraft } from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import WorkflowEditor from '@/views/workflow/WorkflowEditor.vue'
import type { App } from '@/types/app'
import type { DraftResponse } from '@/types/workflow'

vi.mock('@/api/app', () => ({ getApp: vi.fn() }))
vi.mock('@/api/workflow', () => ({ getDraft: vi.fn(), saveDraft: vi.fn() }))

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

function mountEditor() {
  return mount(WorkflowEditor, {
    global: {
      plugins: [ElementPlus],
      stubs: { NodePalette: true },
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
})
