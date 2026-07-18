import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const { overview, daily, rankings } = vi.hoisted(() => ({
  overview: {
    promptTokens: '3000000',
    completionTokens: '1500000',
    totalTokens: '4500000',
    callCount: '42',
    estimatedCost: '5.0000',
    costIncomplete: true,
  },
  daily: [
    {
      date: '2026-07-16',
      promptTokens: '100',
      completionTokens: '50',
      callCount: '2',
      estimatedCost: '0.5000',
    },
  ],
  rankings: [
    {
      targetId: '10',
      promptTokens: '100',
      completionTokens: '50',
      totalTokens: '150',
      callCount: '2',
      estimatedCost: '0.5000',
    },
  ],
}))

vi.mock('@/api/admin/usage', () => ({
  fetchOverview: vi.fn().mockResolvedValue(overview),
  fetchDaily: vi.fn().mockResolvedValue(daily),
  fetchRankings: vi.fn().mockResolvedValue(rankings),
}))
vi.mock('@/composables/useNameMaps', () => ({
  useNameMaps: () => ({
    load: vi.fn().mockResolvedValue(undefined),
    resolveUser: (id: string) => `用户${id}`,
    resolveApp: (id: string) => `应用${id}`,
    resolveModel: (id: string) => `模型${id}`,
  }),
}))
vi.mock('../TrendChart.vue', () => ({
  default: {
    name: 'TrendChart',
    props: ['dates', 'tokens', 'costs'],
    template: '<div data-test="trend-chart" />',
  },
}))

import { fetchRankings } from '@/api/admin/usage'
import UsageDashboard from '../UsageDashboard.vue'

describe('UsageDashboard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('加载后渲染总览卡、费用不完整角标与默认应用排行', async () => {
    const wrapper = mount(UsageDashboard, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          transition: false,
          'router-link': { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="card-total-tokens"]').text()).toContain('4,500,000')
    expect(wrapper.find('[data-test="card-cost"]').text()).toContain('5.00')
    expect(wrapper.find('[data-test="cost-incomplete"]').exists()).toBe(true)
    expect(fetchRankings).toHaveBeenCalledWith('app', expect.any(String), expect.any(String), 10)
    expect(wrapper.text()).toContain('应用10')
  })

  it('切换排行 tab 重新拉对应维度', async () => {
    const wrapper = mount(UsageDashboard, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          transition: false,
          'router-link': { template: '<a><slot /></a>' },
        },
      },
    })
    await flushPromises()
    await wrapper.find('[data-test="tab-user"]').trigger('click')
    await flushPromises()
    expect(fetchRankings).toHaveBeenCalledWith('user', expect.any(String), expect.any(String), 10)
  })
})
