<script setup lang="ts">
import { computed, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import type { ConversationView } from '@/types/conversation'

const props = defineProps<{
  conversations: ConversationView[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new'): void
}>()

// el-collapse 的展开项（name 列表）：默认四组全展开；用户折叠时 el-collapse 自行增删。仅内存态。
const active = ref(['today', 'week', 'month', 'older'])

interface Group {
  key: string
  label: string
  items: ConversationView[]
}

// 与“今天”的日历天差：今天=0、昨天=1……（按本地日历日切，忽略时分）。
function dayDiff(iso: string): number {
  const startOfDay = (x: Date) =>
    new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  return Math.floor((startOfDay(new Date()) - startOfDay(new Date(iso))) / 86_400_000)
}

// “更早”组条目前缀：本地 年-月-日。
function ymd(iso: string): string {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

// 按 updateTime 分桶：今天 / 7天内 / 30天内 / 更早。后端已按 update_time desc 返回，桶内保序。
const groups = computed<Group[]>(() => {
  const buckets: Record<string, ConversationView[]> = {
    today: [],
    week: [],
    month: [],
    older: [],
  }
  for (const c of props.conversations) {
    const d = dayDiff(c.updateTime)
    if (d <= 0) buckets.today.push(c)
    else if (d <= 7) buckets.week.push(c)
    else if (d <= 30) buckets.month.push(c)
    else buckets.older.push(c)
  }
  const defs = [
    { key: 'today', label: '今天' },
    { key: 'week', label: '7天内' },
    { key: 'month', label: '30天内' },
    { key: 'older', label: '更早' },
  ]
  return defs
    .map((g) => ({ ...g, items: buckets[g.key] }))
    .filter((g) => g.items.length > 0)
})
</script>

<template>
  <aside class="sidebar">
    <el-button
      class="sidebar__new"
      type="primary"
      :icon="Plus"
      data-test="conv-new"
      @click="emit('new')"
    >
      新建会话
    </el-button>

    <div class="sidebar__history-label" data-test="history-label">问答历史</div>

    <el-scrollbar class="sidebar__scroll">
      <el-collapse v-model="active" class="sidebar__collapse">
        <el-collapse-item v-for="g in groups" :key="g.key" :name="g.key">
          <template #title>
            <span :data-test="`group-${g.key}`">{{ g.label }}</span>
          </template>
          <ul class="sidebar__list">
            <li
              v-for="c in g.items"
              :key="c.id"
              :class="['sidebar__item', { 'sidebar__item--active': c.id === currentId }]"
              data-test="conv-item"
              :title="c.title ?? '未命名会话'"
              @click="emit('select', c.id)"
            >
              <span v-if="g.key === 'older'" class="sidebar__date">{{ ymd(c.updateTime) }}</span>
              <span class="sidebar__title">{{ c.title ?? '未命名会话' }}</span>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </el-scrollbar>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  display: flex;
  flex-direction: column;
  width: 220px;
  padding: 12px;
  border-right: 1px solid var(--el-border-color-light);

  &__new {
    width: 100%;
  }

  // 「新建会话」与「问答历史」之间留白加大
  &__history-label {
    margin-top: 20px;
    margin-bottom: 4px;
    padding: 0 2px;
    font-size: 12px;
    font-weight: 600;
    color: var(--el-text-color-secondary);
  }

  &__scroll {
    flex: 1;
    min-height: 0;
  }

  // 收紧 el-collapse 的默认样式，适配紧凑侧边栏
  &__collapse {
    border: none;

    :deep(.el-collapse-item__header) {
      height: 32px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      border-bottom: none;
    }

    :deep(.el-collapse-item__content) {
      padding-bottom: 4px;
    }
  }

  &__list {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__item {
    display: flex;
    gap: 6px;
    padding: 8px 10px;
    border-radius: 6px;
    cursor: pointer;

    &:hover {
      background: var(--el-fill-color-light);
    }

    &--active {
      background: var(--el-color-primary-light-8);
    }
  }

  &__date {
    flex: none;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
  }

  &__title {
    flex: 1;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
