<script setup lang="ts">
import { computed, ref } from 'vue'
import { Plus, MoreFilled } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import type { ConversationView } from '@/types/conversation'

const props = defineProps<{
  conversations: ConversationView[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new'): void
  (e: 'rename', payload: { id: string; title: string }): void
  (e: 'delete', id: string): void
}>()

/** 重命名：prompt 取新标题（非空 ≤100），确认后向上 emit；取消静默。 */
async function onRename(c: ConversationView) {
  try {
    const { value } = await ElMessageBox.prompt('输入新的会话标题', '重命名', {
      inputValue: c.title ?? '',
      inputValidator: (v: string) => (!!v && v.trim().length > 0 && v.length <= 100) || '标题需 1-100 字',
    })
    emit('rename', { id: c.id, title: value.trim() })
  } catch {
    /* 用户取消 */
  }
}

/** 删除：二次确认后向上 emit；取消静默。 */
async function onDelete(c: ConversationView) {
  try {
    await ElMessageBox.confirm(`确定删除会话「${c.title ?? '未命名会话'}」？此操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    emit('delete', c.id)
  } catch {
    /* 用户取消 */
  }
}

/** 3 点菜单分发。 */
function onCommand(cmd: string | number | object, c: ConversationView) {
  if (cmd === 'rename') onRename(c)
  else if (cmd === 'delete') onDelete(c)
}

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
              <span class="sidebar__ops" @click.stop>
                <el-dropdown trigger="click" @command="(cmd: string | number | object) => onCommand(cmd, c)">
                  <el-icon class="sidebar__op" :data-test="`conv-ops-${c.id}`" title="更多"><MoreFilled /></el-icon>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="rename" :data-test="`conv-rename-${c.id}`">重命名</el-dropdown-item>
                      <el-dropdown-item command="delete" divided :data-test="`conv-delete-${c.id}`">删除</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </span>
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
  // 白色面板：与右侧 #fafafa 聊天区分层（设计 token：白=容器，#fafafa=页面底色）
  background: var(--el-bg-color);
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

  // 收紧 el-collapse 的默认样式 + 透明化（让它继承侧栏背景，不再自带白底/边框）
  &__collapse {
    --el-collapse-header-bg-color: transparent;
    --el-collapse-content-bg-color: transparent;
    --el-collapse-border-color: transparent;
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
    align-items: center; // 3 点图标与标题文字垂直对齐
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

  // 操作图标：默认隐藏，条目 hover 时显现（当前会话恒显，便于随时改名/删除）
  &__ops {
    flex: none;
    display: flex;
    gap: 4px;
    opacity: 0;
    transition: opacity 0.15s;
  }

  &__item:hover &__ops,
  &__item--active &__ops {
    opacity: 1;
  }

  &__op {
    cursor: pointer;
    color: var(--el-text-color-secondary);

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
</style>
