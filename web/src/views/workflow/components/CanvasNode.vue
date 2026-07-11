<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import {
  ChatDotRound, CircleCheck, Collection, Link, Switch, VideoPlay,
} from '@element-plus/icons-vue'

// Vue Flow 经 nodeTypes 注入的自定义节点：只声明用到的 props，其余（data/selected 等）忽略
const props = defineProps<{ id: string; type: string }>()

const META: Record<string, { label: string; icon: Component }> = {
  start: { label: '开始', icon: VideoPlay },
  llm: { label: 'LLM', icon: ChatDotRound },
  'knowledge-retrieval': { label: '知识检索', icon: Collection },
  condition: { label: '条件分支', icon: Switch },
  http: { label: 'HTTP 请求', icon: Link },
  end: { label: '结束', icon: CircleCheck },
}
const meta = computed(() => META[props.type] ?? { label: props.type, icon: Link })
</script>

<template>
  <div class="canvas-node" :class="`canvas-node--${type}`">
    <Handle v-if="type !== 'start'" type="target" :position="Position.Left" />
    <el-icon class="canvas-node__icon"><component :is="meta.icon" /></el-icon>
    <div class="canvas-node__text">
      <div class="canvas-node__label">{{ meta.label }}</div>
      <div class="canvas-node__id">{{ id }}</div>
    </div>
    <template v-if="type === 'condition'">
      <Handle id="true" type="source" :position="Position.Right" class="canvas-node__true" />
      <Handle id="false" type="source" :position="Position.Right" class="canvas-node__false" />
      <span class="canvas-node__branch canvas-node__branch--true">真</span>
      <span class="canvas-node__branch canvas-node__branch--false">假</span>
    </template>
    <Handle v-else-if="type !== 'end'" type="source" :position="Position.Right" />
  </div>
</template>

<style scoped lang="scss">
// 画布几何均为视觉初值，待实测微调
.canvas-node {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
  min-width: 140px;
  padding: $spacing-sm $spacing-md;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-lighter);
}
.canvas-node__icon {
  font-size: 18px;
  color: var(--el-color-primary);
}
.canvas-node__label {
  font-size: 13px;
  font-weight: 600;
}
.canvas-node__id {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
// condition 双出口：true 上、false 下（与 Handle top 定位一致）
.canvas-node__true {
  top: 30%;
}
.canvas-node__false {
  top: 70%;
}
.canvas-node__branch {
  position: absolute;
  right: -18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.canvas-node__branch--true {
  top: calc(30% - 8px);
}
.canvas-node__branch--false {
  top: calc(70% - 8px);
}
</style>
