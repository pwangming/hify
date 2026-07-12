<script setup lang="ts">
import { ref } from 'vue'
import type { InputInstance } from 'element-plus'
import type { ConditionNodeData } from '@/types/workflow'
import { CONDITION_OPERATORS } from '../../composables/useNodeIssues'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: ConditionNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: ConditionNodeData] }>()

const leftRef = ref<InputInstance>()
const rightRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'left')
register('left', {
  get: () => props.data.left ?? '',
  set: (v) => emit('update', { left: v }),
  el: () => leftRef.value?.input,
})
register('right', {
  get: () => props.data.right ?? '',
  set: (v) => emit('update', { right: v }),
  el: () => rightRef.value?.input,
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-condition">
    <el-form-item label="左值" required>
      <div data-test="cond-left">
        <el-input
          ref="leftRef"
          :model-value="data.left ?? ''"
          placeholder="可引用变量，如 {{kb_1.count}}"
          @update:model-value="emit('update', { left: $event })"
          @focus="onFocus('left')"
        />
      </div>
    </el-form-item>
    <el-form-item label="比较符" required>
      <el-select
        data-test="cond-operator"
        :model-value="data.operator"
        placeholder="选择比较符"
        @update:model-value="emit('update', { operator: $event })"
      >
        <el-option v-for="op in CONDITION_OPERATORS" :key="op" :value="op" :label="op" />
      </el-select>
    </el-form-item>
    <el-form-item label="右值" required>
      <div data-test="cond-right">
        <el-input
          ref="rightRef"
          :model-value="data.right ?? ''"
          placeholder="常量或变量"
          @update:model-value="emit('update', { right: $event })"
          @focus="onFocus('right')"
        />
      </div>
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="cond-left"],
[data-test="cond-right"] {
  width: 100%;
}
</style>
