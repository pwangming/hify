import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import { listTools, getTool, createTool, updateTool, previewTool } from '@/api/admin/tool'
import type { ToolAdminItem, ToolAdminDetail, ToolPreview } from '@/types/tool'
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
  {
    id: '12',
    name: 'deepwiki',
    description: 'DeepWiki MCP',
    source: 'mcp',
    enabled: true,
    operationCount: 3,
    ownerId: '1',
    createTime: '2026-07-15T10:00:00+08:00',
    updateTime: '2026-07-15T10:00:00+08:00',
  },
]

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
})

function mountList() {
  return mount(ToolList, { global: { plugins: [ElementPlus, router] } })
}

// 透传桩：内联渲染默认插槽 + footer 插槽，绕开 el-drawer 的 teleport
const drawerStub = {
  props: ['modelValue'],
  template: '<div v-if="modelValue" data-test="tool-drawer"><slot/><slot name="footer"/></div>',
}

function mountListWithDrawer() {
  return mount(ToolList, {
    global: { plugins: [ElementPlus, router], stubs: { 'el-drawer': drawerStub } },
  })
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

describe('ToolList 注册/编辑抽屉', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  it('注册：预览渲染操作列表，保存调 createTool', async () => {
    const preview: ToolPreview = {
      baseUrl: 'https://api.example.com',
      operations: [{ opName: 'getPet', method: 'GET', pathTemplate: '/pets/{id}', description: '查' }],
      tools: [],
    }
    vi.mocked(previewTool).mockResolvedValue(preview)
    vi.mocked(createTool).mockResolvedValue(SAMPLE[1])

    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="tool-drawer"]').exists()).toBe(true)

    await wrapper.get('[data-test="form-name"]').setValue('petstore')
    await wrapper.get('[data-test="form-description"]').setValue('宠物')
    await wrapper.get('[data-test="form-spec"]').setValue('openapi: 3.0.0')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(previewTool).toHaveBeenCalledWith({ specText: 'openapi: 3.0.0' })
    expect(wrapper.text()).toContain('getPet')

    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'petstore', description: '宠物', specText: 'openapi: 3.0.0' }),
    )
    const body = vi.mocked(createTool).mock.calls[0][0]
    expect(body).not.toHaveProperty('type')
    expect(body).not.toHaveProperty('url')
  })

  it('预览失败不关抽屉（后端 13001 由拦截器 toast）', async () => {
    vi.mocked(previewTool).mockRejectedValue(new Error('parse fail'))
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await wrapper.get('[data-test="form-spec"]').setValue('bad')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="tool-drawer"]').exists()).toBe(true)
  })

  it('编辑：回填详情，头名预填且值框空，保存调 updateTool', async () => {
    const detail: ToolAdminDetail = {
      id: '9',
      name: 'petstore',
      description: '宠物商店',
      source: 'openapi',
      enabled: true,
      baseUrl: 'https://api.example.com',
      operations: [{ opName: 'getPet', method: 'GET', pathTemplate: '/pets/{id}', description: '查' }],
      authHeaderNames: ['X-API-Key'],
      rawSpec: 'openapi: 3.0.0',
      url: null,
      transport: null,
      tools: [],
      discoveredAt: null,
    }
    vi.mocked(getTool).mockResolvedValue(detail)
    vi.mocked(updateTool).mockResolvedValue(SAMPLE[1])

    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="edit-9"]').trigger('click')
    await flushPromises()
    expect(getTool).toHaveBeenCalledWith('9')
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe('petstore')
    expect((wrapper.get('[data-test="header-name-0"]').element as HTMLInputElement).value).toBe('X-API-Key')
    expect((wrapper.get('[data-test="header-value-0"]').element as HTMLInputElement).value).toBe('')

    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateTool).toHaveBeenCalledWith('9', expect.objectContaining({ name: 'petstore' }))
  })
})

describe('ToolList MCP 注册/编辑', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listTools).mockResolvedValue(SAMPLE)
  })

  async function openCreateMcp() {
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    const typeGroup = wrapper.findComponent('[data-test="form-type"]')
    await typeGroup.vm.$emit('update:modelValue', 'mcp')
    await flushPromises()
    return wrapper
  }

  it('新建切到 MCP：出现 url/传输方式，OpenAPI 文本框与操作预览消失', async () => {
    const wrapper = await openCreateMcp()
    expect(wrapper.find('[data-test="form-url"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="form-transport"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="form-spec"]').exists()).toBe(false)
  })

  it('试连接：previewTool 收 mcp body，渲染发现的工具清单', async () => {
    vi.mocked(previewTool).mockResolvedValue({
      baseUrl: null,
      operations: [],
      tools: [{ toolName: 'read_wiki', description: '读 wiki 结构' }],
    })
    const wrapper = await openCreateMcp()
    await wrapper.get('[data-test="form-url"]').setValue('https://mcp.example.com/mcp')
    await wrapper.get('[data-test="form-preview"]').trigger('click')
    await flushPromises()
    expect(previewTool).toHaveBeenCalledWith({
      type: 'mcp',
      url: 'https://mcp.example.com/mcp',
      transport: 'streamable_http',
      authHeaders: [],
    })
    expect(wrapper.find('[data-test="mcp-tool-read_wiki"]').exists()).toBe(true)
  })

  it('mcp 提交：url 空被前端拦；有 url 时 body 带 type/url/transport 不带 specText', async () => {
    vi.mocked(createTool).mockResolvedValue(SAMPLE[2])
    const wrapper = await openCreateMcp()
    await wrapper.get('[data-test="form-name"]').setValue('deepwiki')
    await wrapper.get('[data-test="form-description"]').setValue('wiki 问答')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).not.toHaveBeenCalled()

    await wrapper.get('[data-test="form-url"]').setValue('https://mcp.example.com/mcp')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createTool).toHaveBeenCalledWith({
      name: 'deepwiki',
      description: 'wiki 问答',
      type: 'mcp',
      url: 'https://mcp.example.com/mcp',
      transport: 'streamable_http',
      authHeaders: [],
    })
  })

  it('编辑 mcp 行：回填 url、类型只读、展示快照与 discoveredAt、无试连接按钮', async () => {
    const detail: ToolAdminDetail = {
      id: '12',
      name: 'deepwiki',
      description: 'DeepWiki MCP',
      source: 'mcp',
      enabled: true,
      baseUrl: null,
      operations: [],
      authHeaderNames: ['Authorization'],
      rawSpec: null,
      url: 'https://mcp.deepwiki.com/mcp',
      transport: 'streamable_http',
      tools: [{ toolName: 'read_wiki_structure', description: '读结构' }],
      discoveredAt: '2026-07-15T10:00:00+08:00',
    }
    vi.mocked(getTool).mockResolvedValue(detail)
    const wrapper = mountListWithDrawer()
    await flushPromises()
    await wrapper.get('[data-test="edit-12"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-url"]').element as HTMLInputElement).value).toBe(
      'https://mcp.deepwiki.com/mcp',
    )
    expect(wrapper.find('[data-test="form-type"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="type-readonly"]').text()).toBe('MCP')
    expect(wrapper.find('[data-test="mcp-tool-read_wiki_structure"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="discovered-at"]').text()).toContain('上次发现于')
    expect(wrapper.find('[data-test="form-preview"]').exists()).toBe(false)
  })
})
