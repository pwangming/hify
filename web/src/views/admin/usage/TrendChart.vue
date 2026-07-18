<script setup lang="ts">
// 全站唯一直接触碰 ECharts 的组件（frontend-standards 登记：按需引入）
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{
  dates: string[]
  tokens: number[]
  costs: number[]
}>()

const el = ref<HTMLDivElement>()
let chart: ReturnType<typeof echarts.init> | null = null

function buildOption() {
  return {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['Token', '费用（元）'] },
    grid: { left: 48, right: 56, top: 40, bottom: 24 },
    xAxis: { type: 'category' as const, data: props.dates },
    yAxis: [
      { type: 'value' as const, name: 'Token' },
      { type: 'value' as const, name: '费用（元）' },
    ],
    series: [
      { name: 'Token', type: 'line' as const, smooth: true, data: props.tokens },
      {
        name: '费用（元）',
        type: 'line' as const,
        smooth: true,
        yAxisIndex: 1,
        data: props.costs,
      },
    ],
  }
}

function render() {
  chart?.setOption(buildOption())
}

const onResize = () => chart?.resize()

onMounted(() => {
  chart = echarts.init(el.value!)
  render()
  window.addEventListener('resize', onResize)
})

watch(() => [props.dates, props.tokens, props.costs], render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="trend-chart" data-test="trend-chart" />
</template>

<style scoped lang="scss">
.trend-chart {
  width: 100%;
  height: 320px;
}
</style>
