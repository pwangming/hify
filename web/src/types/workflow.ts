/** 画布节点类型（对齐后端 NodeType 的 value）。 */
export type WorkflowNodeType = 'start' | 'llm' | 'knowledge-retrieval' | 'condition' | 'http' | 'end'

/** 画布坐标（对齐后端 GraphNode.position jsonb，引擎不读只保真）。 */
export interface GraphNodePosition {
  x: number
  y: number
}

/** 对齐后端 GraphNode record。data 为节点私有配置，C1 不编辑（C2 收窄为按类型的联合）。 */
export interface GraphNode {
  id: string
  type: WorkflowNodeType
  data: Record<string, unknown>
  /** API 手拼的老草稿可能无坐标，加载时网格兜底。 */
  position?: GraphNodePosition | null
}

/** 对齐后端 GraphEdge record；后端不存边 id，前端确定性生成（见 graphTransform.edgeId）。 */
export interface GraphEdge {
  source: string
  target: string
  /** condition 节点出口标记 "true"/"false"，普通边为 null。 */
  sourceHandle?: string | null
}

/** 画布定义（workflow_def.graph jsonb）。 */
export interface GraphDef {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** 草稿视图。updateTime 供「上次保存时间」展示。 */
export interface DraftResponse {
  graph: GraphDef
  updateTime: string
}
