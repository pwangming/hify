import { computed, ref } from 'vue'
import { getDraft, saveDraft } from '@/api/workflow'
import type { GraphNodePosition, WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'
import { edgeId, fromFlow, nextNodeId, toFlow } from './graphTransform'
import type { FlowEdge, FlowNode } from './graphTransform'

/**
 * 画布状态与草稿读写。nodes/edges 是画布唯一数据源（Vue Flow 受控模式），
 * dirty 用「当前 fromFlow 序列化 vs 上次保存快照」判定——拖动位置也算改动（position 要持久化）。
 */
export function useWorkflowGraph(appId: string) {
  const nodes = ref<FlowNode[]>([])
  const edges = ref<FlowEdge[]>([])
  const savedAt = ref<string | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const snapshot = ref('')

  const dirty = computed(
    () => JSON.stringify(fromFlow(nodes.value, edges.value)) !== snapshot.value,
  )

  function takeSnapshot() {
    snapshot.value = JSON.stringify(fromFlow(nodes.value, edges.value))
  }

  async function load() {
    loading.value = true
    try {
      const draft = await getDraft(appId)
      const flow = toFlow(draft?.graph ?? null)
      nodes.value = flow.nodes
      edges.value = flow.edges
      savedAt.value = draft?.updateTime ?? null
      takeSnapshot()
    } finally {
      loading.value = false
    }
  }

  async function save() {
    saving.value = true
    try {
      const graph = fromFlow(nodes.value, edges.value)
      const resp = await saveDraft(appId, graph)
      savedAt.value = resp.updateTime
      takeSnapshot()
    } finally {
      saving.value = false
    }
  }

  function addNode(type: WorkflowNodeType, position: GraphNodePosition): string {
    const id = nextNodeId(nodes.value.map((n) => n.id), type)
    nodes.value.push({ id, type, position, data: {}, deletable: true })
    return id
  }

  function connect(c: { source: string; target: string; sourceHandle?: string | null }) {
    const id = edgeId(c)
    if (edges.value.some((e) => e.id === id)) return // 重复连线幂等
    edges.value.push({
      id,
      source: c.source,
      target: c.target,
      ...(c.sourceHandle != null ? { sourceHandle: c.sourceHandle } : {}),
    })
  }

  /** 抽屉表单即时写回：合并补丁到节点 data（不可变副本，dirty 由快照对比自动感知）。 */
  function updateNodeData(id: string, patch: WorkflowNodeData) {
    const node = nodes.value.find((n) => n.id === id)
    if (!node) return
    // 联合类型无法直接展开合并，收窄为普通对象拼接（运行时都是普通 jsonb 对象）
    node.data = {
      ...(node.data as Record<string, unknown>),
      ...(patch as Record<string, unknown>),
    } as WorkflowNodeData
  }

  return { nodes, edges, savedAt, loading, saving, dirty, load, save, addNode, connect, updateNodeData }
}
