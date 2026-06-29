import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConversationSidebar from '@/views/conversation/ConversationSidebar.vue'

// 用相对“今天”的日期构造数据，保证分桶断言与运行日期无关。
function daysAgo(n: number): string {
  const d = new Date()
  d.setHours(12, 0, 0, 0)
  d.setDate(d.getDate() - n)
  return d.toISOString()
}

function ymd(iso: string): string {
  const d = new Date(iso)
  const p = (x: number) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const convs = [
  { id: '1', title: '今天的会话', updateTime: daysAgo(0) },
  { id: '2', title: '三天前', updateTime: daysAgo(3) },
  { id: '3', title: '半月前', updateTime: daysAgo(15) },
  { id: '4', title: '很久前', updateTime: daysAgo(100) },
]

function mountSidebar(
  opts: { conversations?: typeof convs; currentId?: string | null } = {},
) {
  return mount(ConversationSidebar, {
    props: {
      conversations: opts.conversations ?? convs,
      currentId: opts.currentId ?? null,
    },
    global: { plugins: [ElementPlus] },
  })
}

describe('ConversationSidebar', () => {
  it('显示「问答历史」标题与四个时间分组', () => {
    const wrapper = mountSidebar()
    expect(wrapper.find('[data-test="history-label"]').text()).toBe('问答历史')
    expect(wrapper.find('[data-test="group-today"]').text()).toContain('今天')
    expect(wrapper.find('[data-test="group-week"]').text()).toContain('7天内')
    expect(wrapper.find('[data-test="group-month"]').text()).toContain('30天内')
    expect(wrapper.find('[data-test="group-older"]').text()).toContain('更早')
    expect(wrapper.findAll('[data-test="conv-item"]')).toHaveLength(4)
  })

  it('空的时间组不渲染', () => {
    const wrapper = mountSidebar({ conversations: [convs[0]] }) // 仅今天
    expect(wrapper.find('[data-test="group-today"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="group-week"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="group-month"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="group-older"]').exists()).toBe(false)
  })

  it('「更早」组条目前缀年月日', () => {
    const wrapper = mountSidebar()
    expect(wrapper.find('[data-test="group-older"]').exists()).toBe(true)
    const olderItem = wrapper
      .findAll('[data-test="conv-item"]')
      .find((li) => li.text().includes('很久前'))!
    expect(olderItem.text()).toContain(ymd(daysAgo(100)))
  })

  it('其他分组条目不带日期前缀', () => {
    const wrapper = mountSidebar()
    const todayItem = wrapper
      .findAll('[data-test="conv-item"]')
      .find((li) => li.text().includes('今天的会话'))!
    expect(todayItem.text()).not.toMatch(/\d{4}-\d{2}-\d{2}/)
  })

  it('点击分组头可折叠/展开该组（默认展开）', async () => {
    const wrapper = mountSidebar({
      conversations: [convs[0], { id: '5', title: '今天2', updateTime: daysAgo(0) }],
    })
    const list = () => wrapper.find('[data-test="list-today"]')
    const header = () => wrapper.find('[data-test="group-today"]')
    // 默认展开：caret ▼，list 未被 v-show 隐藏
    expect(header().text()).toContain('▼')
    expect(list().attributes('style') ?? '').not.toContain('display: none')
    await header().trigger('click') // 折叠
    expect(header().text()).toContain('▶')
    expect(list().attributes('style')).toContain('display: none')
    await header().trigger('click') // 再展开
    expect(header().text()).toContain('▼')
    expect(list().attributes('style') ?? '').not.toContain('display: none')
  })

  it('点击会话 emit select 带 id', async () => {
    const wrapper = mountSidebar()
    const item = wrapper
      .findAll('[data-test="conv-item"]')
      .find((li) => li.text().includes('三天前'))!
    await item.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['2'])
  })

  it('点击新建 emit new', async () => {
    const wrapper = mountSidebar()
    await wrapper.find('[data-test="conv-new"]').trigger('click')
    expect(wrapper.emitted('new')).toHaveLength(1)
  })

  it('当前会话高亮', () => {
    const wrapper = mountSidebar({ currentId: '2' })
    const active = wrapper.find('.sidebar__item--active')
    expect(active.text()).toContain('三天前')
  })
})
