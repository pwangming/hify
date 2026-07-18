import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'

const setOption = vi.fn()
const dispose = vi.fn()
const resize = vi.fn()
// jsdom 无 canvas：echarts 必须整体 mock（第四个 jsdom 坑，入档 testing 策略）
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption, dispose, resize })),
}))
vi.mock('echarts/charts', () => ({ LineChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  TooltipComponent: {},
  LegendComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import TrendChart from '../TrendChart.vue'

describe('TrendChart', () => {
  beforeEach(() => {
    setOption.mockClear()
    dispose.mockClear()
  })

  it('挂载即 init+setOption，数据变化重新 setOption，卸载 dispose', async () => {
    const wrapper = mount(TrendChart, {
      props: { dates: ['2026-07-16'], tokens: [100], costs: [0.5] },
    })
    expect(setOption).toHaveBeenCalledTimes(1)
    const option = setOption.mock.calls[0][0]
    expect(option.xAxis.data).toEqual(['2026-07-16'])
    expect(option.series).toHaveLength(2)
    expect(option.yAxis).toHaveLength(2)
    // 图例固定在图底部（验收反馈：默认顶部与标题挤在一起）
    expect(option.legend.bottom).toBe(0)

    await wrapper.setProps({
      dates: ['2026-07-16', '2026-07-17'],
      tokens: [100, 200],
      costs: [0.5, 1],
    })
    await nextTick()
    expect(setOption).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(dispose).toHaveBeenCalled()
  })
})
