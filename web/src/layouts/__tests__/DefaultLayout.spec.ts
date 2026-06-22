import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { TOKEN_KEY } from '@/api/request'
import { useUserStore } from '@/stores/user'
import DefaultLayout from '@/layouts/DefaultLayout.vue'

const Stub = { template: '<div />' }

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'Home', component: Stub },
      { path: '/login', name: 'Login', component: Stub },
    ],
  })
}

describe('DefaultLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('顶栏显示当前登录用户名', async () => {
    const store = useUserStore()
    store.user = { id: '1', username: 'alice', role: 'admin' }
    const router = makeRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router, ElementPlus] },
    })

    expect(wrapper.text()).toContain('alice')
  })

  it('点击退出：清登录态（token + localStorage）并跳转登录页', async () => {
    const store = useUserStore()
    store.setToken('jwt')
    store.user = { id: '1', username: 'alice', role: 'admin' }
    const router = makeRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(DefaultLayout, {
      global: { plugins: [router, ElementPlus] },
    })

    await wrapper.get('[data-test="logout"]').trigger('click')
    await flushPromises()

    expect(store.token).toBeNull()
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })
})

function makeMenuRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: Stub },
      { path: '/knowledge', component: Stub, meta: { menu: true, title: '知识库管理' } },
      {
        path: '/admin/provider',
        component: Stub,
        meta: { menu: true, title: '模型提供商管理', roles: ['admin'] },
      },
    ],
  })
}

describe('DefaultLayout 菜单（按路由 + 角色生成）', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('Member 看不到 admin 专属项', async () => {
    const store = useUserStore()
    store.user = { id: '2', username: 'bob', role: 'member' }
    const router = makeMenuRouter()
    await router.push('/knowledge')
    await router.isReady()

    const wrapper = mount(DefaultLayout, { global: { plugins: [router, ElementPlus] } })

    expect(wrapper.text()).toContain('知识库管理')
    expect(wrapper.text()).not.toContain('模型提供商管理')
  })

  it('Admin 看得到 admin 专属项', async () => {
    const store = useUserStore()
    store.user = { id: '1', username: 'alice', role: 'admin' }
    const router = makeMenuRouter()
    await router.push('/knowledge')
    await router.isReady()

    const wrapper = mount(DefaultLayout, { global: { plugins: [router, ElementPlus] } })

    expect(wrapper.text()).toContain('知识库管理')
    expect(wrapper.text()).toContain('模型提供商管理')
  })
})
