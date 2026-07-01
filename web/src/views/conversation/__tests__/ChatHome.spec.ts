import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { listApps } from '@/api/app'
import ChatHome from '@/views/conversation/ChatHome.vue'

vi.mock('@/api/app', () => ({ listApps: vi.fn() }))

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

function app(over: Record<string, unknown> = {}) {
  return {
    id: '1', name: '客服助手', description: '答疑', type: 'chat',
    modelId: '5', modelName: 'gpt', modelUsable: true, config: { systemPrompt: null },
    ownerId: '9', status: 'enabled', createTime: 'x', updateTime: 'x', ...over,
  }
}

function mountHome() {
  return mount(ChatHome, { global: { plugins: [ElementPlus] } })
}

describe('ChatHome', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    push.mockClear()
  })

  it('只渲染已启用应用', async () => {
    vi.mocked(listApps).mockResolvedValue({
      list: [app(), app({ id: '2', name: '停用的', status: 'disabled' })],
      total: '2', page: '1', size: '100',
    })
    const wrapper = mountHome()
    await flushPromises()
    expect(wrapper.findAll('[data-test="chat-app-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('客服助手')
    expect(wrapper.text()).not.toContain('停用的')
  })

  it('点击卡片进入该应用聊天页', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [app()], total: '1', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    await wrapper.find('[data-test="chat-app-card"]').trigger('click')
    expect(push).toHaveBeenCalledWith('/apps/1/chat')
  })

  it('模型不可用的卡片点击不跳转', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [app({ modelUsable: false })], total: '1', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    await wrapper.find('[data-test="chat-app-card"]').trigger('click')
    expect(push).not.toHaveBeenCalled()
  })

  it('无已启用应用显示空态', async () => {
    vi.mocked(listApps).mockResolvedValue({ list: [], total: '0', page: '1', size: '100' })
    const wrapper = mountHome()
    await flushPromises()
    expect(wrapper.find('[data-test="chat-empty"]').exists()).toBe(true)
  })
})
