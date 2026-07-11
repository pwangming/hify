<script setup lang="ts">
import type { Component } from 'vue'
import { ChatDotRound, Collection, Link, Switch } from '@element-plus/icons-vue'
import type { WorkflowNodeType } from '@/types/workflow'

/** 拖拽数据键：与 WorkflowEditor 的 drop 端共用同一字符串。 */
const DRAG_KEY = 'application/hify-node'

// start/end 不在栏内：画布预置且不可删（spec §4 硬约定）
const ITEMS: { type: WorkflowNodeType; label: string; icon: Component }[] = [
  { type: 'llm', label: 'LLM', icon: ChatDotRound },
  { type: 'knowledge-retrieval', label: '知识检索', icon: Collection },
  { type: 'condition', label: '条件分支', icon: Switch },
  { type: 'http', label: 'HTTP 请求', icon: Link },
]

function onDragStart(event: DragEvent, type: WorkflowNodeType) {
  event.dataTransfer?.setData(DRAG_KEY, type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
</script>

<template>
  <aside class="node-palette">
    <div class="node-palette__title">节点</div>
    <div
      v-for="item in ITEMS"
      :key="item.type"
      class="node-palette__item"
      :data-test="`palette-${item.type}`"
      draggable="true"
      @dragstart="onDragStart($event, item.type)"
    >
      <el-icon><component :is="item.icon" /></el-icon>
      <span>{{ item.label }}</span>
    </div>
    <div class="node-palette__hint">拖拽节点到画布</div>
  </aside>
</template>

<style scoped lang="scss">
.node-palette {
  width: 168px;
  flex-shrink: 0;
  padding: $spacing-md;
  border-right: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
}
.node-palette__title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.node-palette__item {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  padding: $spacing-sm;
  margin-bottom: $spacing-sm;
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  font-size: 13px;
  cursor: grab;
  &:hover {
    border-color: var(--el-color-primary);
    color: var(--el-color-primary);
  }
}
.node-palette__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
