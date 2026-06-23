import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from '@/components/PageHeader.vue'

describe('PageHeader', () => {
  it('渲染标题与描述', () => {
    const wrapper = mount(PageHeader, {
      props: { title: '用户管理', description: '管理团队成员账号与角色' },
    })
    expect(wrapper.text()).toContain('用户管理')
    expect(wrapper.text()).toContain('管理团队成员账号与角色')
  })

  it('无 description 时不渲染描述节点', () => {
    const wrapper = mount(PageHeader, { props: { title: '应用管理' } })
    expect(wrapper.find('.page-header__desc').exists()).toBe(false)
  })

  it('默认插槽渲染到操作区', () => {
    const wrapper = mount(PageHeader, {
      props: { title: 'X' },
      slots: { default: '<button class="act">新建</button>' },
    })
    expect(wrapper.find('.page-header__actions .act').exists()).toBe(true)
  })
})
