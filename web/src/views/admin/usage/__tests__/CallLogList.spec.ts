import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'

const { page1, page2 } = vi.hoisted(() => ({
  page1: {
    list: [
      {
        id: '1',
        userId: '1',
        appId: '10',
        modelId: '5',
        promptTokens: '100',
        completionTokens: '50',
        source: 'conversation',
        durationMs: 1500,
        status: 'success',
        errorCode: null,
        createTime: '2026-07-17T10:00:00+08:00',
      },
    ],
    nextCursor: 'CURSOR1',
    hasMore: true,
  },
  page2: {
    list: [
      {
        id: '2',
        userId: '2',
        appId: '20',
        modelId: '6',
        promptTokens: '200',
        completionTokens: '60',
        source: null,
        durationMs: null,
        status: 'failed',
        errorCode: '12002',
        createTime: '2026-07-17T09:00:00+08:00',
      },
    ],
    nextCursor: null,
    hasMore: false,
  },
}))

vi.mock('@/api/admin/usage', () => ({
  fetchCallLogs: vi.fn().mockResolvedValueOnce(page1).mockResolvedValueOnce(page2),
}))
vi.mock('@/composables/useNameMaps', async () => {
  const { ref } = await import('vue')
  return {
    useNameMaps: () => ({
      load: vi.fn().mockResolvedValue(undefined),
      resolveUser: (id: string) => `用户${id}`,
      resolveApp: (id: string) => `应用${id}`,
      resolveModel: (id: string) => `模型${id}`,
      users: ref(new Map()),
      apps: ref(new Map()),
      models: ref(new Map()),
    }),
  }
})

import { fetchCallLogs } from '@/api/admin/usage'
import CallLogList from '../CallLogList.vue'

// 组件里有 <router-link to="/admin/usage">（返回看板入口），不装 router 会报
// Failed to resolve component: router-link。装真 router 而非 stub，顺带验证链接真能解析。
const RouteStub = { template: '<div />' }

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/usage', component: RouteStub },
      { path: '/admin/usage/logs', component: RouteStub },
    ],
  })
}

describe('CallLogList', () => {
  beforeEach(() => vi.clearAllMocks())

  it('默认近7天首查，加载更多带游标追加，无更多后按钮消失，历史行来源显示「—」', async () => {
    const wrapper = mount(CallLogList, {
      global: {
        plugins: [createTestRouter(), ElementPlus],
        stubs: { transition: false },
      },
    })
    await flushPromises()
    // 页头须有返回看板入口（验收反馈：无返回按钮）
    expect(wrapper.find('[data-test="back-to-dashboard"]').exists()).toBe(true)
    expect(fetchCallLogs).toHaveBeenCalledTimes(1)
    const first = vi.mocked(fetchCallLogs).mock.calls[0][0]
    expect(first.cursor).toBeUndefined()
    // 默认窗口上界=当天 23:59:59（不能冻结在挂载瞬间，否则进页后新产生的调用永远查不到）
    expect(first.endTime).toContain('23:59:59')
    expect(wrapper.text()).toContain('用户1')

    await wrapper.find('[data-test="load-more"]').trigger('click')
    await flushPromises()
    const second = vi.mocked(fetchCallLogs).mock.calls[1][0]
    expect(second.cursor).toBe('CURSOR1')
    expect(wrapper.text()).toContain('用户2')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.find('[data-test="load-more"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('1500 ms')
    expect(wrapper.find('.el-tag--success').exists()).toBe(true)
    expect(wrapper.find('.el-tag--danger').exists()).toBe(true)
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('12002')
  })
})
