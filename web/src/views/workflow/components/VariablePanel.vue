<script setup lang="ts">
import type { UpstreamVar } from '../composables/useUpstreamVars'

const props = defineProps<{ vars: UpstreamVar[]; disabled?: boolean }>()
const emit = defineEmits<{ insert: [varRef: string] }>()

function onClick(nodeId: string, field: string) {
  if (props.disabled) return
  emit('insert', `{{${nodeId}.${field}}}`)
}
</script>

<template>
  <div class="var-panel" data-test="var-panel">
    <div class="var-panel__title">可引用变量</div>
    <el-empty
      v-if="vars.length === 0"
      description="连线后这里会列出可引用的上游输出"
      :image-size="48"
    />
    <div v-for="v in vars" :key="v.nodeId" class="var-panel__group">
      <div class="var-panel__node">{{ v.nodeId }}</div>
      <template v-if="v.fields.length > 0">
        <el-tag
          v-for="f in v.fields"
          :key="f"
          class="var-panel__tag"
          :class="{ 'var-panel__tag--disabled': disabled }"
        >
          <span data-test="var-tag" @click="onClick(v.nodeId, f)">{{ f }}</span>
        </el-tag>
        >
      </template>
      <span v-else class="var-panel__hint">未声明输入</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.var-panel {
  margin-top: $spacing-md;
  padding-top: $spacing-md;
  border-top: 1px solid var(--el-border-color-lighter);
}
.var-panel__title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: $spacing-sm;
}
.var-panel__group {
  margin-bottom: $spacing-sm;
}
.var-panel__node {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.var-panel__tag {
  margin-right: $spacing-sm;
  cursor: pointer;
}
.var-panel__tag--disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.var-panel__hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
</style>
