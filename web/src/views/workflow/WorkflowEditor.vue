<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { VueFlow, useVueFlow, type NodeTypesObject } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { getApp } from '@/api/app'
import type { App } from '@/types/app'
import type { WorkflowNodeType } from '@/types/workflow'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import CanvasNode from './components/CanvasNode.vue'
import NodeConfigDrawer from './components/NodeConfigDrawer.vue'
import NodePalette from './components/NodePalette.vue'
import { useWorkflowGraph } from './composables/useWorkflowGraph'

/** 与 NodePalette 共用的拖拽数据键。 */
const DRAG_KEY = 'application/hify-node'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appId = String(route.params.appId)

const app = ref<App | null>(null)
const graph = useWorkflowGraph(appId)
const { screenToFlowCoordinate } = useVueFlow()

// 六类节点共用一个渲染组件，type 原样透传（spec §3：类型零转换）
const nodeTypes = {
  start: markRaw(CanvasNode),
  llm: markRaw(CanvasNode),
  'knowledge-retrieval': markRaw(CanvasNode),
  condition: markRaw(CanvasNode),
  http: markRaw(CanvasNode),
  end: markRaw(CanvasNode),
} as NodeTypesObject

/** 团队共享制：读全员，改仅 owner/Admin（与后端 10004 双保险，同 AppList.canModify）。 */
const canEdit = computed(
  () => userStore.isAdmin || app.value?.ownerId === userStore.user?.id,
)

/** 抽屉选中态：id 存 ref，节点从 nodes 现取——节点被删时自动回落 null 关抽屉。 */
const selectedId = ref<string | null>(null)
const selectedNode = computed(
  () => graph.nodes.value.find((n) => n.id === selectedId.value) ?? null,
)
function onNodeClick(e: { node: { id: string } }) {
  selectedId.value = e.node.id
}

onMounted(async () => {
  try {
    const loaded = await getApp(appId)
    if (loaded.type !== 'workflow') {
      router.push('/app')
      return
    }
    app.value = loaded
    await graph.load()
  } catch {
    /* 失败由 request 拦截器统一 toast */
  }
})

async function onSave() {
  try {
    await graph.save()
    ElMessage.success('已保存')
  } catch {
    /* 拦截器已 toast（含 18001 结构损坏兜底）*/
  }
}

/** Vue Flow 连线事件：写回自有 edges（受控模式）。 */
function onConnect(c: { source: string; target: string; sourceHandle?: string | null }) {
  graph.connect(c)
}

function onDrop(event: DragEvent) {
  const type = event.dataTransfer?.getData(DRAG_KEY) as WorkflowNodeType | ''
  if (!type || !canEdit.value) return
  const position = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  graph.addNode(type, position)
}

onBeforeRouteLeave(async () => {
  if (!graph.dirty.value) return true
  try {
    await ElMessageBox.confirm('有未保存的修改，确定离开？', '离开确认', { type: 'warning' })
    return true
  } catch {
    return false
  }
})
</script>

<template>
  <div class="wf-editor">
    <div class="wf-editor__toolbar">
      <el-page-header @back="router.push('/app')">
        <template #content>
          <span class="wf-editor__name">{{ app?.name ?? '' }}</span>
          <el-tag size="small" class="wf-editor__tag">工作流</el-tag>
        </template>
      </el-page-header>
      <div class="wf-editor__actions">
        <span v-if="graph.savedAt.value" class="wf-editor__saved" data-test="wf-saved-at">
          上次保存 {{ formatDateTime(graph.savedAt.value) }}
        </span>
        <el-tooltip :disabled="canEdit" content="仅创建者或管理员可编辑" placement="bottom">
          <span>
            <el-button
              type="primary"
              data-test="wf-save"
              :disabled="!canEdit"
              :loading="graph.saving.value"
              @click="onSave"
              >保存</el-button
            >
          </span>
        </el-tooltip>
      </div>
    </div>

    <div class="wf-editor__body">
      <NodePalette />
      <div class="wf-editor__canvas" @dragover.prevent @drop="onDrop">
        <VueFlow
          v-model:nodes="graph.nodes.value"
          v-model:edges="graph.edges.value"
          :node-types="nodeTypes"
          @connect="onConnect"
          @node-click="onNodeClick"
          @pane-click="selectedId = null"
        >
          <Background />
          <Controls />
        </VueFlow>
        <NodeConfigDrawer
          :node="selectedNode"
          :nodes="graph.nodes.value"
          :edges="graph.edges.value"
          :can-edit="canEdit"
          @close="selectedId = null"
          @update="graph.updateNodeData"
        />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.wf-editor {
  display: flex;
  flex-direction: column;
  // 视觉初值待实测微调：占满主布局内容区高度
  height: calc(100vh - 140px);
}
.wf-editor__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: $spacing-md;
}
.wf-editor__name {
  font-weight: 600;
}
.wf-editor__tag {
  margin-left: $spacing-sm;
}
.wf-editor__actions {
  display: flex;
  align-items: center;
  gap: $spacing-md;
}
.wf-editor__saved {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wf-editor__body {
  display: flex;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}
.wf-editor__canvas {
  flex: 1;
  min-width: 0;
}
</style>
