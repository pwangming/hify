<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import type { StartInputDecl, StartNodeData } from '@/types/workflow'

const props = defineProps<{ data: StartNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: StartNodeData] }>()

/** 名字须匹配后端变量正则 [\w-]+，否则下游 {{start.名字}} 引用不上；只警告不阻断（草稿语义）。 */
const NAME_RE = /^[\w-]+$/

function rows(): StartInputDecl[] {
  return props.data.inputs ?? []
}
function updateRow(i: number, patch: Partial<StartInputDecl>) {
  emit('update', { inputs: rows().map((r, idx) => (idx === i ? { ...r, ...patch } : r)) })
}
function addRow() {
  emit('update', { inputs: [...rows(), { name: '' }] })
}
function removeRow(i: number) {
  emit('update', { inputs: rows().filter((_, idx) => idx !== i) })
}
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-start">
    <el-form-item label="输入声明">
      <div class="start-form__tip">运行时由触发方传入，声明后下游可引用</div>
      <div v-for="(row, i) in data.inputs ?? []" :key="i" class="start-form__row">
        <div data-test="start-input-name" class="start-form__name">
          <el-input
            :model-value="row.name"
            placeholder="变量名，如 city"
            @update:model-value="updateRow(i, { name: $event })"
          />
        </div>
        <el-checkbox
          data-test="start-input-required"
          :model-value="row.required ?? false"
          label="必填"
          @update:model-value="updateRow(i, { required: $event === true })"
        />
        <el-button data-test="start-input-remove" :icon="Delete" text @click="removeRow(i)" />
        <div
          v-if="row.name !== '' && !NAME_RE.test(row.name)"
          class="start-form__warn"
          data-test="start-input-warn"
        >
          仅限字母、数字、下划线或中划线
        </div>
      </div>
      <el-button data-test="start-input-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输入</el-button
      >
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
.start-form__tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-sm;
}
.start-form__row {
  display: flex;
  flex-wrap: wrap;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.start-form__name {
  flex: 1;
}
.start-form__warn {
  width: 100%;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
