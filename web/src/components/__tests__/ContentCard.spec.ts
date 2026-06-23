import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ContentCard from '@/components/ContentCard.vue'

describe('ContentCard', () => {
  it('渲染默认插槽内容并带卡片根类', () => {
    const wrapper = mount(ContentCard, {
      slots: { default: '<div class="inner">表格</div>' },
    })
    expect(wrapper.find('.content-card').exists()).toBe(true)
    expect(wrapper.find('.content-card .inner').exists()).toBe(true)
    expect(wrapper.text()).toContain('表格')
  })
})
