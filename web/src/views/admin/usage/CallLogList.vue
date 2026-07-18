<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchCallLogs } from '@/api/admin/usage'
import { useNameMaps } from '@/composables/useNameMaps'
import type { CallLogItem } from '@/types/usage'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'

const now = new Date()
const sevenDaysAgo = new Date(now)
sevenDaysAgo.setDate(now.getDate() - 7)

const dateRange = ref<[string, string]>([sevenDaysAgo.toISOString(), now.toISOString()])
const userId = ref<string>()
const appId = ref<string>()
const modelId = ref<string>()
const source = ref<string>()

const rows = ref<CallLogItem[]>([])
const nextCursor = ref<string | null>(null)
const hasMore = ref(false)
const loading = ref(false)

const nameMaps = useNameMaps()
const userOptions = computed(() => [...nameMaps.users.value.entries()])
const appOptions = computed(() => [...nameMaps.apps.value.entries()])
const modelOptions = computed(() => [...nameMaps.models.value.entries()])

const tokenText = (value: string) => Number(value).toLocaleString()
const sourceText = (value: CallLogItem['source']) =>
  value === 'conversation' ? '对话' : value === 'workflow' ? '工作流' : '—'

async function query(reset: boolean) {
  const [startTime, endTime] = dateRange.value
  const days = (new Date(endTime).getTime() - new Date(startTime).getTime()) / 86400000
  if (days > 31) {
    ElMessage.warning('时间范围不能超过 31 天')
    return
  }
  if (reset) {
    rows.value = []
    nextCursor.value = null
  }
  loading.value = true
  try {
    const page = await fetchCallLogs({
      startTime,
      endTime,
      userId: userId.value,
      appId: appId.value,
      modelId: modelId.value,
      source: source.value,
      cursor: nextCursor.value ?? undefined,
      limit: 20,
    })
    rows.value.push(...page.list)
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await Promise.all([nameMaps.load(), query(true)])
})
</script>

<template>
  <div class="call-log-list">
    <PageHeader title="调用日志" description="查询最近 31 天内的模型调用明细" />

    <el-form class="call-log-list__filters" inline>
      <el-form-item label="时间">
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="source" clearable placeholder="全部">
          <el-option label="对话" value="conversation" />
          <el-option label="工作流" value="workflow" />
        </el-select>
      </el-form-item>
      <el-form-item label="用户">
        <el-select v-model="userId" filterable clearable placeholder="全部">
          <el-option v-for="[id, name] in userOptions" :key="id" :label="name" :value="id" />
        </el-select>
      </el-form-item>
      <el-form-item label="应用">
        <el-select v-model="appId" filterable clearable placeholder="全部">
          <el-option v-for="[id, name] in appOptions" :key="id" :label="name" :value="id" />
        </el-select>
      </el-form-item>
      <el-form-item label="模型">
        <el-select v-model="modelId" filterable clearable placeholder="全部">
          <el-option v-for="[id, name] in modelOptions" :key="id" :label="name" :value="id" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="query(true)">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">
          {{ formatDateTime((row as CallLogItem).createTime) }}
        </template>
      </el-table-column>
      <el-table-column label="用户">
        <template #default="{ row }">{{ nameMaps.resolveUser((row as CallLogItem).userId) }}</template>
      </el-table-column>
      <el-table-column label="应用">
        <template #default="{ row }">{{ nameMaps.resolveApp((row as CallLogItem).appId) }}</template>
      </el-table-column>
      <el-table-column label="模型">
        <template #default="{ row }">{{ nameMaps.resolveModel((row as CallLogItem).modelId) }}</template>
      </el-table-column>
      <el-table-column label="来源">
        <template #default="{ row }">{{ sourceText((row as CallLogItem).source) }}</template>
      </el-table-column>
      <el-table-column label="输入 Token">
        <template #default="{ row }">{{ tokenText((row as CallLogItem).promptTokens) }}</template>
      </el-table-column>
      <el-table-column label="输出 Token">
        <template #default="{ row }">{{ tokenText((row as CallLogItem).completionTokens) }}</template>
      </el-table-column>
    </el-table>

    <div v-if="hasMore" class="call-log-list__more">
      <el-button data-test="load-more" :loading="loading" @click="query(false)">
        加载更多
      </el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.call-log-list {
  &__filters {
    padding: $spacing-lg;
    margin-bottom: $spacing-lg;
    border-radius: $radius-md;
    background: #fff;
  }

  &__more {
    display: flex;
    justify-content: center;
    margin-top: $spacing-lg;
  }
}
</style>
