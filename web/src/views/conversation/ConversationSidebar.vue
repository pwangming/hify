<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ConversationView } from '@/types/conversation'

const props = defineProps<{
  conversations: ConversationView[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new'): void
}>()

// 折叠状态：记下被折叠的组 key（默认空 = 全展开），仅内存态，刷新复位。
const collapsed = reactive<Record<string, boolean>>({})
function toggle(key: string) {
  collapsed[key] = !collapsed[key]
}

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
    <el-button class="sidebar__new" type="primary" data-test="conv-new" @click="emit('new')">
      新建会话
    </el-button>

    <div class="sidebar__history-label" data-test="history-label">问答历史</div>

    <div class="sidebar__groups">
      <div v-for="g in groups" :key="g.key" class="sidebar__group">
        <button
          type="button"
          class="sidebar__group-header"
          :data-test="`group-${g.key}`"
          @click="toggle(g.key)"
        >
          <span class="sidebar__caret">{{ collapsed[g.key] ? '▶' : '▼' }}</span>
          {{ g.label }}
        </button>
        <ul v-show="!collapsed[g.key]" class="sidebar__list" :data-test="`list-${g.key}`">
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
      </div>
    </div>
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

  &__groups {
    flex: 1;
    overflow-y: auto;
  }

  &__group + &__group {
    margin-top: 4px;
  }

  &__group-header {
    display: flex;
    align-items: center;
    gap: 4px;
    width: 100%;
    padding: 6px 4px;
    border: none;
    background: transparent;
    cursor: pointer;
    font-size: 12px;
    color: var(--el-text-color-secondary);
    text-align: left;

    &:hover {
      color: var(--el-text-color-primary);
    }
  }

  &__caret {
    font-size: 10px;
    width: 12px;
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
