<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { StartInputDecl } from '@/types/workflow'

const props = defineProps<{
  visible: boolean
  decls: StartInputDecl[]
  initial: Record<string, string>
}>()
const emit = defineEmits<{ 'update:visible': [v: boolean]; submit: [values: Record<string, string>] }>()

const model = reactive<Record<string, string>>({})
const missing = ref<string[]>([])

// 每次打开按声明重建模型：上次输入预填（调试高频重跑），声明变了以声明为准
watch(
  () => props.visible,
  (v) => {
    if (!v) return
    missing.value = []
    Object.keys(model).forEach((k) => delete model[k])
    for (const d of props.decls) model[d.name] = props.initial[d.name] ?? ''
  },
  { immediate: true },
)

function onSubmit() {
  missing.value = props.decls
    .filter((d) => d.required && model[d.name].trim() === '')
    .map((d) => d.name)
  if (missing.value.length > 0) return
  emit('submit', { ...model })
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="运行输入"
    width="480"
    @update:model-value="emit('update:visible', $event)"
  >
    <el-form label-position="top" @submit.prevent>
      <el-form-item
        v-for="d in decls"
        :key="d.name"
        :label="d.name"
        :required="d.required ?? false"
        :error="missing.includes(d.name) ? '必填项不能为空' : ''"
      >
        <div :data-test="`run-input-${d.name}`" class="run-input-dialog__field">
          <el-input v-model="model[d.name]" />
        </div>
        <div v-if="missing.includes(d.name)" class="run-input-dialog__error">必填项不能为空</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button data-test="run-cancel" @click="emit('update:visible', false)">取消</el-button>
      <el-button data-test="run-submit" type="primary" @click="onSubmit">运行</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.run-input-dialog__field {
  width: 100%;
}
.run-input-dialog__error {
  margin-top: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
}
</style>
