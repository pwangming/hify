<script setup lang="ts">
import type { ConversationView } from '@/types/conversation'

defineProps<{
  conversations: ConversationView[]
  currentId: string | null
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'new'): void
}>()
</script>

<template>
  <aside class="sidebar">
    <el-button class="sidebar__new" type="primary" data-test="conv-new" @click="emit('new')">
      新建会话
    </el-button>
    <ul class="sidebar__list">
      <li
        v-for="c in conversations"
        :key="c.id"
        :class="['sidebar__item', { 'sidebar__item--active': c.id === currentId }]"
        data-test="conv-item"
        :title="c.title ?? '未命名会话'"
        @click="emit('select', c.id)"
      >
        {{ c.title ?? '未命名会话' }}
      </li>
    </ul>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 220px;
  padding: 12px;
  border-right: 1px solid var(--el-border-color-light);

  &__new {
    width: 100%;
  }

  &__list {
    flex: 1;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
  }

  &__item {
    padding: 8px 10px;
    border-radius: 6px;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;

    &:hover {
      background: var(--el-fill-color-light);
    }

    &--active {
      background: var(--el-color-primary-light-8);
    }
  }
}
</style>
