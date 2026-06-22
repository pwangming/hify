import { describe, it, expect } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import type { Component } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import ForbiddenView from '@/views/error/ForbiddenView.vue'
import NotFoundView from '@/views/error/NotFoundView.vue'

const Stub = { template: '<div class="home" />' }

async function mountErrorView(view: Component) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: Stub },
      { path: '/x', component: view },
    ],
  })
  await router.push('/x')
  await router.isReady()
  const wrapper = mount(view, { global: { plugins: [router, ElementPlus] } })
  return { router, wrapper }
}

describe('错误页', () => {
  it('403 页：点“返回首页”跳首页', async () => {
    const { router, wrapper } = await mountErrorView(ForbiddenView)
    await wrapper.get('[data-test="home"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('404 页：点“返回首页”跳首页', async () => {
    const { router, wrapper } = await mountErrorView(NotFoundView)
    await wrapper.get('[data-test="home"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })
})
