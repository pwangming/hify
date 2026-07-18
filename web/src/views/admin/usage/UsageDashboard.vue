<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDaily, fetchOverview, fetchRankings } from '@/api/admin/usage'
import { useNameMaps } from '@/composables/useNameMaps'
import type {
  DailyUsagePoint,
  RankDimension,
  UsageOverview,
  UsageRankingItem,
} from '@/types/usage'
import TrendChart from './TrendChart.vue'
import PageHeader from '@/components/PageHeader.vue'

type Preset = 'today' | '7d' | '30d'
const preset = ref<Preset>('7d')
const customRange = ref<[string, string] | null>(null)

function toDateStr(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const range = computed<[string, string]>(() => {
  if (customRange.value) return customRange.value
  const end = new Date()
  const start = new Date()
  if (preset.value === '7d') start.setDate(end.getDate() - 6)
  if (preset.value === '30d') start.setDate(end.getDate() - 29)
  return [toDateStr(start), toDateStr(end)]
})

const overview = ref<UsageOverview | null>(null)
const daily = ref<DailyUsagePoint[]>([])
const dimension = ref<RankDimension>('app')
const ranking = ref<UsageRankingItem[]>([])
const loading = ref(false)

const nameMaps = useNameMaps()
const resolveTarget = (id: string) =>
  dimension.value === 'app'
    ? nameMaps.resolveApp(id)
    : dimension.value === 'user'
      ? nameMaps.resolveUser(id)
      : nameMaps.resolveModel(id)

const fmt = (n: string) => Number(n).toLocaleString()
const fmtCost = (c: string) => Number(c).toFixed(2)

const chartDates = computed(() => daily.value.map((d) => d.date))
const chartTokens = computed(() =>
  daily.value.map((d) => Number(d.promptTokens) + Number(d.completionTokens)),
)
const chartCosts = computed(() => daily.value.map((d) => Number(d.estimatedCost)))

async function loadStats() {
  loading.value = true
  try {
    const [start, end] = range.value
    ;[overview.value, daily.value, ranking.value] = await Promise.all([
      fetchOverview(start, end),
      fetchDaily(start, end),
      fetchRankings(dimension.value, start, end, 10),
    ])
  } finally {
    loading.value = false
  }
}

async function loadRanking() {
  const [start, end] = range.value
  ranking.value = await fetchRankings(dimension.value, start, end, 10)
}

// v-model 已改 dimension，本回调只负责拉数（整个 tab 头可点，修复「点到 label 外无反应」）
async function onDimensionChange() {
  await loadRanking()
}

function selectPreset(value: Preset) {
  preset.value = value
  customRange.value = null
  loadStats()
}

function onCustomChange(val: [string, string] | null) {
  if (!val) return
  const days = (new Date(val[1]).getTime() - new Date(val[0]).getTime()) / 86400000 + 1
  if (days > 92) {
    ElMessage.warning('日期范围不能超过 92 天')
    customRange.value = null
    return
  }
  customRange.value = val
  loadStats()
}

onMounted(async () => {
  await Promise.all([nameMaps.load(), loadStats()])
})
</script>

<template>
  <div class="usage-dashboard">
    <PageHeader title="用量看板" description="Token 与费用按当前模型单价估算">
      <router-link to="/admin/usage/call-logs">
        <el-button>调用日志</el-button>
      </router-link>
    </PageHeader>

    <div class="usage-dashboard__filters">
      <el-radio-group :model-value="preset">
        <el-radio-button value="today" @click="selectPreset('today')">今日</el-radio-button>
        <el-radio-button value="7d" @click="selectPreset('7d')">近 7 天</el-radio-button>
        <el-radio-button value="30d" @click="selectPreset('30d')">近 30 天</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="customRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        @change="onCustomChange"
      />
    </div>

    <div class="usage-dashboard__cards" v-loading="loading">
      <el-card data-test="card-total-tokens">
        <div class="usage-dashboard__card-label">总 Token</div>
        <div class="usage-dashboard__card-value">{{ fmt(overview?.totalTokens ?? '0') }}</div>
        <div class="usage-dashboard__card-note">
          输入 {{ fmt(overview?.promptTokens ?? '0') }} /
          输出 {{ fmt(overview?.completionTokens ?? '0') }}
        </div>
      </el-card>
      <el-card data-test="card-calls">
        <div class="usage-dashboard__card-label">调用次数</div>
        <div class="usage-dashboard__card-value">{{ fmt(overview?.callCount ?? '0') }}</div>
      </el-card>
      <el-card data-test="card-cost">
        <div class="usage-dashboard__card-label">
          估算费用
          <el-tooltip
            v-if="overview?.costIncomplete"
            content="存在未配单价的模型，费用不完整"
          >
            <span data-test="cost-incomplete" class="usage-dashboard__warning">!</span>
          </el-tooltip>
        </div>
        <div class="usage-dashboard__card-value">
          ¥ {{ fmtCost(overview?.estimatedCost ?? '0') }}
        </div>
      </el-card>
    </div>

    <el-card class="usage-dashboard__section">
      <template #header>按天趋势</template>
      <TrendChart :dates="chartDates" :tokens="chartTokens" :costs="chartCosts" />
    </el-card>

    <el-card class="usage-dashboard__section">
      <template #header>用量排行</template>
      <el-tabs v-model="dimension" @tab-change="onDimensionChange">
        <el-tab-pane name="app">
          <template #label><span data-test="tab-app">应用</span></template>
        </el-tab-pane>
        <el-tab-pane name="user">
          <template #label><span data-test="tab-user">用户</span></template>
        </el-tab-pane>
        <el-tab-pane name="model">
          <template #label><span data-test="tab-model">模型</span></template>
        </el-tab-pane>
      </el-tabs>
      <el-table :data="ranking" v-loading="loading">
        <el-table-column label="名称">
          <template #default="{ row }">
            {{ resolveTarget((row as UsageRankingItem).targetId) }}
          </template>
        </el-table-column>
        <el-table-column label="Token">
          <template #default="{ row }">{{ fmt((row as UsageRankingItem).totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="调用次数">
          <template #default="{ row }">{{ fmt((row as UsageRankingItem).callCount) }}</template>
        </el-table-column>
        <el-table-column label="估算费用">
          <template #default="{ row }">
            ¥ {{ fmtCost((row as UsageRankingItem).estimatedCost) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.usage-dashboard {
  &__filters {
    display: flex;
    align-items: center;
    gap: $spacing-md;
    margin-bottom: $spacing-lg;

    // 属性透传对 range 编辑器不稳，宽度用 :deep 强制收窄（验收反馈：默认 ~350px 太宽）
    :deep(.el-date-editor--daterange) {
      width: 260px;
    }
  }

  &__cards {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: $spacing-lg;
    margin-bottom: $spacing-lg;
  }

  &__card-label {
    color: $color-text-secondary;
  }

  &__card-value {
    margin-top: $spacing-sm;
    font-size: 28px;
    font-weight: 600;
  }

  &__card-note {
    margin-top: $spacing-xs;
    color: $color-text-secondary;
    font-size: $font-size-sm;
  }

  &__warning {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 16px;
    height: 16px;
    margin-left: $spacing-xs;
    border-radius: 50%;
    color: #fff;
    background: var(--el-color-warning);
    font-size: 12px;
    cursor: help;
  }

  &__section {
    margin-bottom: $spacing-lg;
  }
}
</style>
