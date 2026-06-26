import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { sendMessage } from '@/api/conversation'
import ChatView from '@/views/conversation/ChatView.vue'

vi.mock('@/api/conversation', () => ({ sendMessage: vi.fn(), getMessages: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { appId: '7' } }) }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

describe('ChatView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(sendMessage).mockResolvedValue({
      conversationId: '100',
      message: {
        id: '200', role: 'assistant', content: '你好，我是助手',
        promptTokens: '12', completionTokens: '8', createTime: '2026-06-26T10:00:00+08:00',
      },
    })
  })

  it('发送后渲染用户气泡与助手回复，并以 appId+conversationId 调用', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })

    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    // 首次发送：conversationId 为 null
    expect(sendMessage).toHaveBeenCalledWith('7', null, '你好')
    const bubbles = wrapper.findAll('[data-test="msg"]')
    expect(bubbles).toHaveLength(2)            // user + assistant
    expect(wrapper.text()).toContain('你好')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('续聊复用上一次返回的 conversationId', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })

    await wrapper.find('[data-test="chat-input"] textarea').setValue('第一句')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="chat-input"] textarea').setValue('第二句')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()

    expect(sendMessage).toHaveBeenNthCalledWith(1, '7', null, '第一句')
    expect(sendMessage).toHaveBeenNthCalledWith(2, '7', '100', '第二句') // 复用返回的会话 id
  })

  it('空白输入不触发发送', async () => {
    const wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await wrapper.find('[data-test="chat-input"] textarea').setValue('   ')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
  })
})
