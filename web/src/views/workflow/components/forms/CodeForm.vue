<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { CodeNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: CodeNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: CodeNodeData] }>()

// inputs 本地行状态：name 编辑中允许空/重复，写回时过滤空 name、后写赢（同 HttpForm headers）。
// 依赖抽屉侧 :key="node.id" 保证切换节点时本组件重建、行状态不串。
const rows = ref<{ name: string; value: string }[]>(
  Object.entries(props.data.inputs ?? {}).map(([name, value]) => ({ name, value })),
)

function syncInputs() {
  const inputs: Record<string, string> = {}
  for (const r of rows.value) {
    if (r.name.trim() !== '') inputs[r.name.trim()] = r.value
  }
  emit('update', { inputs })
}
function addRow() {
  rows.value.push({ name: '', value: '' })
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
  syncInputs()
}
function updateName(i: number, v: string) {
  rows.value[i].name = v
  syncInputs()
}
function updateValue(i: number, v: string) {
  rows.value[i].value = v
  syncInputs()
}

// 变量面板只注入到「当前聚焦的映射值输入框」；代码框本身不吃 {{}}（变量经形参进 main）。
const { register, unregister, onFocus, insert } = useVarInsert(() =>
  rows.value.length > 0 ? `mv_${rows.value.length - 1}` : 'none',
)
let registeredRows = 0
watchEffect(() => {
  const len = rows.value.length
  for (let i = 0; i < len; i++) {
    register(`mv_${i}`, {
      get: () => rows.value[i]?.value ?? '',
      set: (v) => {
        if (rows.value[i]) {
          rows.value[i].value = v
          syncInputs()
        }
      },
    })
  }
  for (let i = len; i < registeredRows; i++) unregister(`mv_${i}`)
  registeredRows = len
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-code">
    <el-form-item label="输入映射">
      <div v-for="(row, i) in rows" :key="i" class="code-form__row">
        <div data-test="code-input-name" class="code-form__name">
          <el-input
            :model-value="row.name"
            placeholder="形参名，如 text"
            @update:model-value="updateName(i, $event)"
          />
        </div>
        <div data-test="code-input-value" class="code-form__value">
          <el-input
            :model-value="row.value"
            placeholder="变量，如 {{start.question}}"
            @update:model-value="updateValue(i, $event)"
            @focusin="onFocus(`mv_${i}`)"
          />
        </div>
        <el-button data-test="code-input-remove" :icon="Delete" text @click="removeRow(i)" />
      </div>
      <el-button data-test="code-input-add" :icon="Plus" text type="primary" @click="addRow"
        >添加输入</el-button
      >
    </el-form-item>
    <el-form-item label="Python 代码" required>
      <div data-test="code-source" class="code-form__source">
        <el-input
          type="textarea"
          :rows="10"
          :input-style="{ fontFamily: 'monospace' }"
          :model-value="data.code ?? ''"
          placeholder="def main(形参...):&#10;    return {&quot;key&quot;: 值}"
          @update:model-value="emit('update', { code: $event })"
        />
      </div>
    </el-form-item>
    <div class="code-form__hint">
      写 <code>def main(形参)</code>，形参对应上面的输入映射；<code>return</code> 一个 dict，
      其 key 即下游可引用的输出变量（如 <code v-text="'{{code_1.key}}'" />）。仅 Python 标准库。
    </div>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="code-source"] {
  width: 100%;
}
.code-form__row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.code-form__name {
  width: 40%;
}
.code-form__value {
  flex: 1;
}
.code-form__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  code {
    background: var(--el-fill-color-light);
    padding: 0 4px;
    border-radius: 3px;
  }
}
</style>
