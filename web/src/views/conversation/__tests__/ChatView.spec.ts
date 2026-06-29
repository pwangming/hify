import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { sendMessage, getMessages, listConversations } from '@/api/conversation'
import ChatView from '@/views/conversation/ChatView.vue'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

const routeQuery: { c?: string } = {}
const push = vi.fn()
const replace = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { appId: '7' }, query: routeQuery }),
  useRouter: () => ({ push, replace }),
}))

globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const assistant = {
  id: '200', role: 'assistant' as const, content: '你好，我是助手',
  promptTokens: 12, completionTokens: 8, createTime: '2026-06-29T10:00:00+08:00',
}

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    delete routeQuery.c
    vi.mocked(listConversations).mockResolvedValue([])
    vi.mocked(getMessages).mockResolvedValue([])
    vi.mocked(sendMessage).mockResolvedValue({ conversationId: '100', message: assistant })
  })

  it('挂载即拉会话列表', async () => {
    mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listConversations).toHaveBeenCalledWith('7')
  })

  it('发送后渲染用户气泡与助手回复，新会话 conversationId 为 null', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好')
    expect(wrapper.findAll('[data-test="msg"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('新会话首发后把 conversationId 写回 URL（replace）', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(replace).toHaveBeenCalledWith({ query: { c: '100' } })
  })

  it('URL 带 c：挂载即载入该会话历史（刷新恢复）', async () => {
    routeQuery.c = '100'
    vi.mocked(getMessages).mockResolvedValue([assistant])
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(getMessages).toHaveBeenCalledWith('100')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('点击侧边栏会话切换（push query.c）', async () => {
    vi.mocked(listConversations).mockResolvedValue([
      { id: '5', title: '旧会话', updateTime: '2026-06-29T09:00:00+08:00' },
    ])
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="conv-item"]').trigger('click')
    expect(push).toHaveBeenCalledWith({ query: { c: '5' } })
  })

  it('空白输入不触发发送', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('   ')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
