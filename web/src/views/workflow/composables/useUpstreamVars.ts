import type { StartNodeData, WorkflowNodeType } from '@/types/workflow'
import type { FlowEdge, FlowNode } from './graphTransform'

/** 一个祖先节点及其可引用输出字段。 */
export interface UpstreamVar {
  nodeId: string
  type: WorkflowNodeType
  fields: string[]
}

/** 各类型节点的输出字段，对齐各 NodeExecutor 的 NodeResult outputs 键；start 特殊=声明的 inputs。 */
const OUTPUT_FIELDS: Record<WorkflowNodeType, string[]> = {
  start: [],
  llm: ['text'],
  'knowledge-retrieval': ['text', 'count'],
  condition: ['result'],
  http: ['status', 'body', 'headers'],
  end: [],
}

/**
 * 沿入边反向 BFS 收集祖先（近者在前，visited 防环）。
 * 与后端「只能引用拓扑序更早的节点」在已连线图上等价——只提示合法可引用项。
 */
export function upstreamVars(nodeId: string, nodes: FlowNode[], edges: FlowEdge[]): UpstreamVar[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const result: UpstreamVar[] = []
  const visited = new Set<string>([nodeId])
  const queue = [nodeId]
  while (queue.length > 0) {
    const cur = queue.shift()!
    for (const edge of edges) {
      if (edge.target !== cur || visited.has(edge.source)) continue
      visited.add(edge.source)
      queue.push(edge.source)
      const node = byId.get(edge.source)
      if (!node) continue
      const fields =
        node.type === 'start'
          ? ((node.data as StartNodeData).inputs ?? []).map((i) => i.name).filter((name) => name !== '')
          : OUTPUT_FIELDS[node.type]
      result.push({ nodeId: node.id, type: node.type, fields })
    }
  }
  return result
}
