import type { GraphDef, GraphNodePosition, WorkflowNodeData, WorkflowNodeType } from '@/types/workflow'

/**
 * GraphDef（后端 jsonb 形态）↔ Vue Flow nodes/edges 的纯转换层。
 * 硬约定：start/end 节点 id 字面固定（后端连通性校验从这两个 id 出发）且不可删除。
 */

/** 结构上可直接喂给 Vue Flow 的节点（避免直接依赖库类型，保持本层纯净）。 */
export interface FlowNode {
  id: string
  type: WorkflowNodeType
  position: GraphNodePosition
  data: WorkflowNodeData
  deletable: boolean
}

/** 结构上可直接喂给 Vue Flow 的边。 */
export interface FlowEdge {
  id: string
  source: string
  target: string
  sourceHandle?: string
}

// 网格兜底与预置坐标：纯视觉初值，待实测微调
const GRID = { originX: 80, originY: 80, stepX: 240, stepY: 160, cols: 4 }
const PRESET_Y = 200
const PRESET_END_X = 640

/** 后端不存边 id，前端确定性生成：同一条业务边永远算出同一个 id。 */
export function edgeId(e: { source: string; target: string; sourceHandle?: string | null }): string {
  return `e-${e.source}-${e.sourceHandle ?? '_'}-${e.target}`
}

/** 新画布预置：start/end 固定 id、不可删、不预连线（spec C1 拍板）。 */
export function presetGraph(): { nodes: FlowNode[]; edges: FlowEdge[] } {
  return {
    nodes: [
      { id: 'start', type: 'start', position: { x: GRID.originX, y: PRESET_Y }, data: {}, deletable: false },
      { id: 'end', type: 'end', position: { x: PRESET_END_X, y: PRESET_Y }, data: {}, deletable: false },
    ],
    edges: [],
  }
}

function gridPosition(index: number): GraphNodePosition {
  return {
    x: GRID.originX + (index % GRID.cols) * GRID.stepX,
    y: GRID.originY + Math.floor(index / GRID.cols) * GRID.stepY,
  }
}

/** 后端草稿 → 画布。null（未保存过）→ 预置图；无 position 的老草稿按数组序网格兜底。 */
export function toFlow(graph: GraphDef | null): { nodes: FlowNode[]; edges: FlowEdge[] } {
  if (!graph) return presetGraph()
  const nodes = graph.nodes.map((n, i) => ({
    id: n.id,
    type: n.type,
    position: n.position ?? gridPosition(i),
    data: n.data ?? {},
    deletable: n.type !== 'start' && n.type !== 'end',
  }))
  const edges = graph.edges.map((e) => ({
    id: edgeId(e),
    source: e.source,
    target: e.target,
    // 普通边不写 sourceHandle 键：Vue Flow 对 undefined 和 null 语义不同
    ...(e.sourceHandle != null ? { sourceHandle: e.sourceHandle } : {}),
  }))
  return { nodes, edges }
}

/** 画布 → 后端草稿：剥掉前端专属字段（边 id / targetHandle），坐标取整。 */
export function fromFlow(nodes: FlowNode[], edges: FlowEdge[]): GraphDef {
  return {
    nodes: nodes.map((n) => ({
      id: n.id,
      type: n.type,
      data: n.data,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    })),
    edges: edges.map((e) => ({
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle ?? null,
    })),
  }
}

/** 节点 id 前缀（与既有测试/Postman 集合命名一致）；start/end 不经此生成。 */
const ID_PREFIX: Partial<Record<WorkflowNodeType, string>> = {
  llm: 'llm',
  'knowledge-retrieval': 'kb',
  condition: 'if',
  http: 'http',
}

/** 类型前缀 + (同前缀最大序号+1)。只保证与现存节点不冲突，不追溯已删除的历史 id。 */
export function nextNodeId(existingIds: string[], type: WorkflowNodeType): string {
  const prefix = ID_PREFIX[type] ?? type
  const re = new RegExp(`^${prefix}_(\\d+)$`)
  let max = 0
  for (const id of existingIds) {
    const m = re.exec(id)
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `${prefix}_${max + 1}`
}
