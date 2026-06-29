import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConversationSidebar from '@/views/conversation/ConversationSidebar.vue'

const convs = [
  { id: '1', title: '会话一', updateTime: '2026-06-29T10:00:00+08:00' },
  { id: '2', title: '会话二', updateTime: '2026-06-29T09:00:00+08:00' },
]

function mountSidebar(currentId: string | null = null) {
  return mount(ConversationSidebar, {
    props: { conversations: convs, currentId },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConversationSidebar', () => {
  it('渲染会话列表', () => {
    const wrapper = mountSidebar()
    expect(wrapper.findAll('[data-test="conv-item"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('会话一')
  })

  it('点击会话 emit select 带 id', async () => {
    const wrapper = mountSidebar()
    await wrapper.findAll('[data-test="conv-item"]')[1].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['2'])
  })

  it('点击新建 emit new', async () => {
    const wrapper = mountSidebar()
    await wrapper.find('[data-test="conv-new"]').trigger('click')
    expect(wrapper.emitted('new')).toHaveLength(1)
  })

  it('当前会话高亮', () => {
    const wrapper = mountSidebar('2')
    const active = wrapper.find('.sidebar__item--active')
    expect(active.text()).toBe('会话二')
  })
})
