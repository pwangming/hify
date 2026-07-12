<script setup lang="ts">
import { computed } from 'vue'
import type { NodeRunView } from '@/types/workflow'

const props = defineProps<{ nodeRun: NodeRunView }>()

const STATUS_LABEL: Record<string, string> = {
  succeeded: '成功',
  failed: '失败',
  skipped: '已跳过',
  running: '运行中',
}
const elapsed = computed(() =>
  props.nodeRun.elapsedMs != null ? `${(Number(props.nodeRun.elapsedMs) / 1000).toFixed(1)}s` : '-',
)
const fmt = (v: Record<string, unknown> | null) => JSON.stringify(v ?? {}, null, 2)
</script>

<template>
  <div class="node-run" data-test="node-run-panel">
    <template v-if="nodeRun.status === 'skipped'">
      <el-empty description="未命中分支，已跳过" :image-size="48" />
    </template>
    <template v-else>
      <div class="node-run__meta">
        <el-tag :type="nodeRun.status === 'succeeded' ? 'success' : 'danger'" effect="light">
          {{ STATUS_LABEL[nodeRun.status] ?? nodeRun.status }}
        </el-tag>
        <span class="node-run__elapsed">耗时 {{ elapsed }}</span>
      </div>
      <div v-if="nodeRun.errorMessage" class="node-run__error" data-test="node-run-error">
        {{ nodeRun.errorMessage }}
      </div>
      <div class="node-run__title">输入</div>
      <pre class="node-run__json" data-test="node-run-inputs">{{ fmt(nodeRun.inputs) }}</pre>
      <div class="node-run__title">输出</div>
      <pre class="node-run__json" data-test="node-run-outputs">{{ fmt(nodeRun.outputs) }}</pre>
    </template>
  </div>
</template>

<style scoped lang="scss">
.node-run__meta {
  display: flex;
  align-items: center;
  gap: $spacing-md;
  margin-bottom: $spacing-md;
}
.node-run__elapsed {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.node-run__error {
  margin-bottom: $spacing-md;
  padding: $spacing-sm;
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
  word-break: break-all;
}
.node-run__title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-sm;
}
.node-run__json {
  margin: 0 0 $spacing-md;
  padding: $spacing-sm;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
