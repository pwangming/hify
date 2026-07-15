import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { listTools } from '@/api/admin/tool'
import type { ToolAdminItem } from '@/types/tool'
import ToolList from '@/views/admin/tool/ToolList.vue'

vi.mock('@/api/admin/tool', () => ({
  listTools: vi.fn(),
  getTool: vi.fn(),
  createTool: vi.fn(),
  updateTool: vi.fn(),
  removeTool: vi.fn(),
  enableTool: vi.fn(),
  disableTool: vi.fn(),
  previewTool: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: ToolAdminItem[] = [
  {
    id: '1',
    name: 'http_request',
    description: '发起 HTTP 请求',
    source: 'builtin',
    enabled: true,
    operationCount: null,
    ownerId: null,
    createTime: '2026-07-01T10:00:00+08:00',
    updateTime: '2026-07-01T10:00:00+08:00',
  },
  {
    id: '9',
    name: 'petstore',
    description: '宠物商店',
    source: 'openapi',
    enabled: true,
    operationCount: 3,
    ownerId: '1',
    createTime: '2026-07-10T10:00:00+08:00',
    updateTime: '2026-07-10T10:00:00+08:00',
  },
]

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
})

function mountList() {
  return mount(ToolList, { global: { plugins: [ElementPlus, router] } })
}

describe('ToolList 列表渲染', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  it('渲染内置与自定义行：内置打「内置」标签且无操作按钮，自定义有编辑/删除', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('http_request')
    expect(wrapper.text()).toContain('petstore')
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="edit-9"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="delete-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="delete-9"]').exists()).toBe(true)
  })

  it('operationCount 内置显示 —，自定义显示数字', async () => {
    const wrapper = mountList()
    await flushPromises()
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).toContain('3')
  })
})
