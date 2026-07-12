<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import type { WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'
import type { FlowEdge, FlowNode } from '../composables/graphTransform'
import { upstreamVars } from '../composables/useUpstreamVars'
import VariablePanel from './VariablePanel.vue'
import StartForm from './forms/StartForm.vue'
import LlmForm from './forms/LlmForm.vue'
import KnowledgeForm from './forms/KnowledgeForm.vue'
import ConditionForm from './forms/ConditionForm.vue'
import HttpForm from './forms/HttpForm.vue'
import EndForm from './forms/EndForm.vue'

const props = defineProps<{
  node: FlowNode | null
  nodes: FlowNode[]
  edges: FlowEdge[]
  canEdit: boolean
}>()
const emit = defineEmits<{ close: []; update: [id: string, patch: WorkflowNodeData] }>()

const FORMS: Record<WorkflowNodeType, Component> = {
  start: StartForm,
  llm: LlmForm,
  'knowledge-retrieval': KnowledgeForm,
  condition: ConditionForm,
  http: HttpForm,
  end: EndForm,
}
const TITLES: Record<WorkflowNodeType, string> = {
  start: '开始',
  llm: 'LLM',
  'knowledge-retrieval': '知识检索',
  condition: '条件分支',
  http: 'HTTP 请求',
  end: '结束',
}

const visible = computed({
  get: () => props.node != null,
  set: (v) => {
    if (!v) emit('close')
  },
})
const vars = computed(() =>
  props.node ? upstreamVars(props.node.id, props.nodes, props.edges) : [],
)
/** start 无变量字段，不展示面板；其余类型（含 end 的 outputs value）都可插入。 */
const showVars = computed(() => props.node != null && props.node.type !== 'start')

const formRef = ref<{ insertVar?: (text: string) => void } | null>(null)

function onUpdate(patch: WorkflowNodeData) {
  if (props.node) emit('update', props.node.id, patch)
}
function onInsert(text: string) {
  formRef.value?.insertVar?.(text)
}
</script>

<template>
  <el-drawer v-model="visible" :modal="false" :size="380" :with-header="true">
    <template #header>
      <span class="node-drawer__title">
        {{ node ? TITLES[node.type] : '' }} · {{ node?.id }}
      </span>
    </template>
    <!-- template 包一层 v-if：同元素上 v-if 对自身绑定不做类型收窄（vue-tsc 会报 node 可能为 null） -->
    <template v-if="node">
      <!-- :key=node.id：切换节点时强制重建表单实例（HttpForm 本地行状态、模型选项等不串台） -->
      <component
        :is="FORMS[node.type]"
        :key="node.id"
        ref="formRef"
        :data="node.data"
        :disabled="!canEdit"
        @update="onUpdate"
      />
      <VariablePanel v-if="showVars" :vars="vars" :disabled="!canEdit" @insert="onInsert" />
    </template>
  </el-drawer>
</template>

<style scoped lang="scss">
.node-drawer__title {
  font-weight: 600;
}
</style>
