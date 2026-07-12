<script setup lang="ts">
import { watchEffect } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { EndNodeData, EndOutputDecl } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: EndNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: EndNodeData] }>()

function rows(): EndOutputDecl[] {
  return props.data.outputs ?? []
}
function updateRow(i: number, patch: Partial<EndOutputDecl>) {
  emit('update', { outputs: rows().map((r, idx) => (idx === i ? { ...r, ...patch } : r)) })
}
function addRow() {
  emit('update', { outputs: [...rows(), { name: '', value: '' }] })
}
function removeRow(i: number) {
  emit('update', { outputs: rows().filter((_, idx) => idx !== i) })
}

// 默认插入目标=最后一行 value；行动态增删，注册随行数同步（多余的注销，防陈旧闭包静默失效）
const { register, unregister, onFocus, insert } = useVarInsert(
  () => `value_${rows().length - 1}`,
)
let registeredRows = 0
watchEffect(() => {
  const len = rows().length
  for (let i = 0; i < len; i++) {
    register(`value_${i}`, {
      get: () => rows()[i]?.value ?? '',
      set: (v) => updateRow(i, { value: v }),
    })
  }
  for (let i = len; i < registeredRows; i++) unregister(`value_${i}`)
  registeredRows = len
})

/** 无行时自动新增一行再插入其 value（spec §4 拍板）。 */
function insertVar(text: string) {
  if (rows().length === 0) {
    emit('update', { outputs: [{ name: '', value: text }] })
    return
  }
  insert(text)
}
defineExpose({ insertVar })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-end">
    <el-form-item label="输出声明">
      <div class="end-form__tip">运行的最终输出，按行渲染</div>
      <div v-for="(row, i) in data.outputs ?? []" :key="i" class="end-form__row">
        <div data-test="end-output-name" class="end-form__name">
          <el-input
            :model-value="row.name"
            placeholder="名称"
            @update:model-value="updateRow(i, { name: $event })"
          />
        </div>
        <div data-test="end-output-value" class="end-form__value">
          <!-- focusin（原生冒泡）而非 el-input 的 focus emit：后者在 jsdom 测不到，见终审记录 -->
          <el-input
            :model-value="row.value"
            placeholder="值，可引用变量，如 {{llm_1.text}}"
            @update:model-value="updateRow(i, { value: $event })"
            @focusin="onFocus(`value_${i}`)"
          />
        </div>
        <el-button data-test="end-output-remove" :icon="Delete" text @click="removeRow(i)" />
      </div>
      <el-button data-test="end-output-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输出</el-button
      >
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
.end-form__tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: $spacing-sm;
}
.end-form__row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.end-form__name {
  width: 40%;
}
.end-form__value {
  flex: 1;
}
</style>
