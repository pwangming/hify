<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { listChatModels } from '@/api/provider'
import type { ModelOption } from '@/types/model'
import type { LlmNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: LlmNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: LlmNodeData] }>()

const modelOptions = ref<ModelOption[]>([])
onMounted(async () => {
  try {
    modelOptions.value = await listChatModels()
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
})

/** 可用模型 + 所选但已失效的模型作禁用项，避免裸露 modelId 数字（同 AppList 手法）。 */
const selectOptions = computed(() => {
  const opts = modelOptions.value.map((m) => ({
    value: m.id,
    label: `${m.providerName} / ${m.name}`,
    disabled: false,
  }))
  if (props.data.modelId && !modelOptions.value.some((m) => m.id === props.data.modelId)) {
    opts.unshift({ value: props.data.modelId, label: '已失效模型（已停用）', disabled: true })
  }
  return opts
})

const userPromptRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'userPrompt')
register('userPrompt', {
  get: () => props.data.userPrompt ?? '',
  set: (v) => emit('update', { userPrompt: v }),
  el: () => userPromptRef.value?.textarea,
})
defineExpose({ insertVar: insert, selectOptions })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-llm">
    <el-form-item label="模型" required>
      <el-select
        data-test="llm-model"
        :model-value="data.modelId"
        placeholder="选择模型"
        @update:model-value="emit('update', { modelId: $event })"
      >
        <el-option
          v-for="o in selectOptions"
          :key="o.value"
          :value="o.value"
          :label="o.label"
          :disabled="o.disabled"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="系统提示词">
      <div data-test="llm-system-prompt">
        <el-input
          type="textarea"
          :rows="3"
          :model-value="data.systemPrompt ?? ''"
          placeholder="可选"
          @update:model-value="emit('update', { systemPrompt: $event })"
        />
      </div>
    </el-form-item>
    <el-form-item label="用户提示词" required>
      <div data-test="llm-user-prompt">
        <el-input
          ref="userPromptRef"
          type="textarea"
          :rows="6"
          :model-value="data.userPrompt ?? ''"
          placeholder="可引用变量，如 {{kb_1.text}}"
          @update:model-value="emit('update', { userPrompt: $event })"
          @focusin="onFocus('userPrompt')"
        />
      </div>
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="llm-system-prompt"],
[data-test="llm-user-prompt"] {
  width: 100%;
}
</style>
