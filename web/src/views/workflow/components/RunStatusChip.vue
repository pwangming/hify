<script setup lang="ts">
import { computed } from 'vue'
import type { RunResponse } from '@/types/workflow'

const props = defineProps<{ run: RunResponse | null }>()

const ok = computed(() => props.run?.status === 'succeeded')
const elapsed = computed(() =>
  props.run?.elapsedMs != null ? `${(Number(props.run.elapsedMs) / 1000).toFixed(1)}s` : '',
)
const outputsJson = computed(() => JSON.stringify(props.run?.outputs ?? {}, null, 2))
</script>

<template>
  <el-popover v-if="run" placement="bottom-end" :width="360" trigger="click">
    <template #reference>
      <el-tag
        data-test="run-chip"
        class="run-chip"
        :type="ok ? 'success' : 'danger'"
        effect="light"
      >
        {{ ok ? `成功 ${elapsed}` : '失败' }}
      </el-tag>
    </template>
    <template v-if="ok">
      <div class="run-chip__title">最终输出</div>
      <pre class="run-chip__json" data-test="run-outputs">{{ outputsJson }}</pre>
    </template>
    <template v-else>
      <div class="run-chip__title">运行失败</div>
      <div class="run-chip__error" data-test="run-error">{{ run.errorMessage }}</div>
    </template>
  </el-popover>
</template>

<style scoped lang="scss">
.run-chip {
  cursor: pointer;
}
.run-chip__title {
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.run-chip__json {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.run-chip__error {
  color: var(--el-color-danger);
  font-size: 13px;
  word-break: break-all;
}
</style>
