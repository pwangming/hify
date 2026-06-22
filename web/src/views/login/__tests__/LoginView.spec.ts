import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { login, getCurrentUser } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import LoginView from '@/views/login/LoginView.vue'

vi.mock('@/api/auth', () => ({ login: vi.fn(), getCurrentUser: vi.fn() }))

const Stub = { template: '<div />' }

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Stub },
      { path: '/login', component: LoginView },
      { path: '/knowledge', component: Stub },
    ],
  })
}

describe('LoginView', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登录成功：用账号密码调 login、写 token、拉用户、跳回 redirect 目标', async () => {
    vi.mocked(login).mockResolvedValue({ token: 'jwt' })
    vi.mocked(getCurrentUser).mockResolvedValue({ id: '1', username: 'alice', role: 'admin' })
    const router = makeRouter()
    await router.push('/login?redirect=/knowledge')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router, ElementPlus] } })
    await wrapper.get('[data-test="username"]').setValue('alice')
    await wrapper.get('[data-test="password"]').setValue('secret')
    await wrapper.get('[data-test="submit"]').trigger('click')
    await flushPromises()

    const store = useUserStore()
    expect(login).toHaveBeenCalledWith({ username: 'alice', password: 'secret' })
    expect(store.token).toBe('jwt')
    expect(store.user?.username).toBe('alice')
    expect(router.currentRoute.value.path).toBe('/knowledge')
  })

  it('无 redirect 参数时登录成功跳首页', async () => {
    vi.mocked(login).mockResolvedValue({ token: 'jwt' })
    vi.mocked(getCurrentUser).mockResolvedValue({ id: '1', username: 'alice', role: 'admin' })
    const router = makeRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router, ElementPlus] } })
    await wrapper.get('[data-test="username"]').setValue('alice')
    await wrapper.get('[data-test="password"]').setValue('secret')
    await wrapper.get('[data-test="submit"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/')
  })
})
