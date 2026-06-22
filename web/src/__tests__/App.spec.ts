import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import App from '@/App.vue'

const Stub = { template: '<div class="page-stub" />' }

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/home', component: Stub, meta: { title: '首页' } },
      { path: '/login', component: Stub, meta: { layout: 'blank', title: '登录' } },
    ],
  })
}

describe('App 布局选择（按 meta.layout）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('普通路由用 DefaultLayout（有顶栏退出），内容经 RouterView 渲染', async () => {
    const router = makeRouter()
    await router.push('/home')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })

    expect(wrapper.find('[data-test="logout"]').exists()).toBe(true)
    expect(wrapper.find('.page-stub').exists()).toBe(true)
  })

  it("meta.layout='blank' 用 BlankLayout（无顶栏退出）", async () => {
    const router = makeRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })

    expect(wrapper.find('[data-test="logout"]').exists()).toBe(false)
    expect(wrapper.find('.page-stub').exists()).toBe(true)
  })
})
