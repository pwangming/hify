<script setup lang="ts">
import { onMounted, ref, watchEffect } from 'vue'
import type { InputInstance } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import type { HttpNodeData } from '@/types/workflow'
import { HTTP_METHODS } from '../../composables/useNodeIssues'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: HttpNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: HttpNodeData] }>()

// headers 本地行状态：key 编辑中允许空/重复，写回时过滤空 key、后写赢（spec §8）。
// 依赖抽屉侧 :key="node.id" 保证切换节点时本组件重建、行状态不串。
const rows = ref<{ key: string; value: string }[]>(
  Object.entries(props.data.headers ?? {}).map(([key, value]) => ({ key, value })),
)

function syncHeaders() {
  const headers: Record<string, string> = {}
  for (const r of rows.value) {
    if (r.key.trim() !== '') headers[r.key.trim()] = r.value
  }
  emit('update', { headers })
}
function addRow() {
  rows.value.push({ key: '', value: '' })
}
function removeRow(i: number) {
  rows.value.splice(i, 1)
  syncHeaders()
}
function updateKey(i: number, v: string) {
  rows.value[i].key = v
  syncHeaders()
}
function updateValue(i: number, v: string) {
  rows.value[i].value = v
  syncHeaders()
}

onMounted(() => {
  // 新节点默认 GET；只读态不写（非 owner 打开抽屉不应产生 dirty）
  if (!props.disabled && !props.data.method) emit('update', { method: 'GET' })
})

const urlRef = ref<InputInstance>()
const bodyRef = ref<InputInstance>()
const { register, unregister, onFocus, insert } = useVarInsert(() => 'url')
register('url', {
  get: () => props.data.url ?? '',
  set: (v) => emit('update', { url: v }),
  el: () => urlRef.value?.input,
})
register('body', {
  get: () => props.data.body ?? '',
  set: (v) => emit('update', { body: v }),
  el: () => bodyRef.value?.textarea,
})
// 注册随行数同步：多余的注销，防删行后 insert 命中陈旧闭包静默失效
let registeredRows = 0
watchEffect(() => {
  const len = rows.value.length
  for (let i = 0; i < len; i++) {
    register(`hv_${i}`, {
      get: () => rows.value[i]?.value ?? '',
      set: (v) => {
        if (rows.value[i]) {
          rows.value[i].value = v
          syncHeaders()
        }
      },
    })
  }
  for (let i = len; i < registeredRows; i++) unregister(`hv_${i}`)
  registeredRows = len
})
defineExpose({ insertVar: insert })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-http">
    <el-form-item label="请求方法" required>
      <el-select
        data-test="http-method"
        :model-value="data.method ?? 'GET'"
        @update:model-value="emit('update', { method: $event })"
      >
        <el-option v-for="m in HTTP_METHODS" :key="m" :value="m" :label="m" />
      </el-select>
    </el-form-item>
    <el-form-item label="URL" required>
      <div data-test="http-url">
        <el-input
          ref="urlRef"
          :model-value="data.url ?? ''"
          placeholder="https://…，可引用变量，如 https://api.example.com?q={{start.q}}"
          @update:model-value="emit('update', { url: $event })"
          @focusin="onFocus('url')"
        />
      </div>
    </el-form-item>
    <el-form-item label="Headers">
      <div v-for="(row, i) in rows" :key="i" class="http-form__header-row">
        <div data-test="http-header-key" class="http-form__header-key">
          <el-input
            :model-value="row.key"
            placeholder="名称"
            @update:model-value="updateKey(i, $event)"
          />
        </div>
        <div data-test="http-header-value" class="http-form__header-value">
          <el-input
            :model-value="row.value"
            placeholder="值，可引用变量"
            @update:model-value="updateValue(i, $event)"
            @focusin="onFocus(`hv_${i}`)"
          />
        </div>
        <el-button
          data-test="http-header-remove"
          :icon="Delete"
          text
          @click="removeRow(i)"
        />
      </div>
      <el-button data-test="http-header-add" :icon="Plus" text type="primary" @click="addRow"
        >添加 Header</el-button
      >
    </el-form-item>
    <el-form-item label="Body">
      <div data-test="http-body">
        <el-input
          ref="bodyRef"
          type="textarea"
          :rows="4"
          :model-value="data.body ?? ''"
          placeholder="可选，可引用变量"
          @update:model-value="emit('update', { body: $event })"
          @focusin="onFocus('body')"
        />
      </div>
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="http-url"],
[data-test="http-body"] {
  width: 100%;
}
.http-form__header-row {
  display: flex;
  gap: $spacing-sm;
  width: 100%;
  margin-bottom: $spacing-sm;
}
.http-form__header-key {
  width: 40%;
}
.http-form__header-value {
  flex: 1;
}
</style>
