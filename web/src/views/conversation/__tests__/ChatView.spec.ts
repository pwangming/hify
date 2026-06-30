import { describe, it, expect, beforeEach, afterEach, vi, type Mock } from 'vitest'
import { nextTick, reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { getMessages, listConversations } from '@/api/conversation'
import { useChatStream } from '@/composables/useChatStream'
import { useConversationStore } from '@/stores/conversation'
import ChatView from '@/views/conversation/ChatView.vue'

vi.mock('@/api/conversation', () => ({
  sendMessage: vi.fn(),
  getMessages: vi.fn(),
  listConversations: vi.fn(),
}))

vi.mock('@/composables/useChatStream', () => ({ useChatStream: vi.fn() }))

const routeQuery = reactive<{ c?: string }>({})
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

// 共享 wrapper，确保每次测试后及时卸载，避免残留组件响应共享 routeQuery 变化
let wrapper: ReturnType<typeof mount>

describe('ChatView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    delete routeQuery.c
    vi.mocked(listConversations).mockResolvedValue([])
    vi.mocked(getMessages).mockResolvedValue([])
    // 默认 useChatStream mock：模拟一次成功的流式发送
    ;(useChatStream as unknown as Mock).mockReturnValue({
      start: vi.fn(async (_a: unknown, _c: unknown, _t: unknown, h: { onDelta: (t: string) => void; onDone: (cid: string, mid: string, u: { promptTokens: number; completionTokens: number }) => void; onError: (e: { code: number; message: string }) => void }) => {
        h.onDelta('你好，我是助手')
        h.onDone('100', '200', { promptTokens: 12, completionTokens: 8 })
      }),
      abort: vi.fn(),
    })
  })

  afterEach(() => {
    wrapper?.unmount()
  })

  it('挂载即拉会话列表', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listConversations).toHaveBeenCalledWith('7')
  })

  it('发送后渲染用户气泡与助手回复', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="msg"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('你好')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('新会话首发后把 conversationId 写回 URL（replace）', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    expect(replace).toHaveBeenCalledWith({ query: { c: '100' } })
  })

  it('URL 带 c：挂载即载入该会话历史（刷新恢复）', async () => {
    routeQuery.c = '100'
    vi.mocked(getMessages).mockResolvedValue([assistant])
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(getMessages).toHaveBeenCalledWith('100')
    expect(wrapper.text()).toContain('你好，我是助手')
  })

  it('点击侧边栏会话切换（push query.c）', async () => {
    vi.mocked(listConversations).mockResolvedValue([
      { id: '5', title: '旧会话', updateTime: '2026-06-29T09:00:00+08:00' },
    ])
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="conv-item"]').trigger('click')
    expect(push).toHaveBeenCalledWith({ query: { c: '5' } })
  })

  it('空白输入不触发发送', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('   ')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    // store.send 不应被触发（start 不应被调用）
    const store = useConversationStore()
    expect(store.messages).toHaveLength(0)
  })

  it('新会话首发后 URL 写回当前会话 id 不触发重复 loadMessages', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="chat-input"] textarea').setValue('你好')
    await wrapper.find('[data-test="chat-send"]').trigger('click')
    await flushPromises()
    // 模拟生产中 router.replace 写回 query（mock 的 replace 不会自动改 routeQuery）
    routeQuery.c = '100'
    await flushPromises()
    // store.send 已把 currentId 设为 '100'，watch 守卫应跳过 loadMessages
    expect(getMessages).not.toHaveBeenCalled()
  })

  it('助手气泡随 store 增量更新（打字机）', async () => {
    wrapper = mount(ChatView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const store = useConversationStore()
    store.messages.push({ id: 'a', role: 'assistant', content: '你好', promptTokens: null, completionTokens: null, createTime: '' })
    await nextTick()
    expect(wrapper.findAll('[data-test="msg"]').at(-1)!.text()).toContain('你好')
    store.messages[store.messages.length - 1].content += '世界'
    await nextTick()
    expect(wrapper.findAll('[data-test="msg"]').at(-1)!.text()).toContain('你好世界')
  })
})
