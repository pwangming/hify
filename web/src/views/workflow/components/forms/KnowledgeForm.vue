<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { InputInstance } from 'element-plus'
import { listDatasets } from '@/api/knowledge'
import type { Dataset } from '@/types/knowledge'
import type { KnowledgeNodeData } from '@/types/workflow'
import { useVarInsert } from '../../composables/useVarInsert'

const props = defineProps<{ data: KnowledgeNodeData; disabled: boolean }>()
const emit = defineEmits<{ update: [patch: KnowledgeNodeData] }>()

const datasetOptions = ref<Dataset[]>([])
onMounted(async () => {
  try {
    const res = await listDatasets({ page: 1, size: 100 })
    datasetOptions.value = res.list
  } catch {
    /* 失败由 request 拦截器统一 toast；下拉留空 */
  }
})

/** 现存知识库 + 已选但被删除的库作禁用项（同 AppList 手法）。 */
const selectOptions = computed(() => {
  const opts = datasetOptions.value.map((d) => ({ value: d.id, label: d.name, disabled: false }))
  for (const id of props.data.datasetIds ?? []) {
    if (!datasetOptions.value.some((d) => d.id === id)) {
      opts.unshift({ value: id, label: '已删除的知识库', disabled: true })
    }
  }
  return opts
})

const queryRef = ref<InputInstance>()
const { register, onFocus, insert } = useVarInsert(() => 'query')
register('query', {
  get: () => props.data.query ?? '',
  set: (v) => emit('update', { query: v }),
  el: () => queryRef.value?.input,
})
defineExpose({ insertVar: insert, selectOptions })
</script>

<template>
  <el-form label-position="top" :disabled="disabled" data-test="form-knowledge-retrieval">
    <el-form-item label="知识库" required>
      <el-select
        data-test="kb-datasets"
        multiple
        :model-value="data.datasetIds ?? []"
        placeholder="选择知识库（可多选）"
        @update:model-value="emit('update', { datasetIds: $event })"
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
    <el-form-item label="检索内容" required>
      <div data-test="kb-query">
        <el-input
          ref="queryRef"
          :model-value="data.query ?? ''"
          placeholder="可引用变量，如 {{start.q}}"
          @update:model-value="emit('update', { query: $event })"
          @focus="onFocus('query')"
        />
      </div>
    </el-form-item>
  </el-form>
</template>

<style scoped lang="scss">
[data-test="kb-query"] {
  width: 100%;
}
</style>
