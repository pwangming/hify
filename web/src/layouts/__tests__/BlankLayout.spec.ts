import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BlankLayout from '@/layouts/BlankLayout.vue'

describe('BlankLayout', () => {
  it('渲染默认插槽内容（无壳，给登录页等使用）', () => {
    const wrapper = mount(BlankLayout, {
      slots: { default: '<div class="login-page">登录</div>' },
    })
    expect(wrapper.find('.login-page').exists()).toBe(true)
    expect(wrapper.text()).toContain('登录')
  })
})
