/** 画布节点类型（对齐后端 NodeType 的 value）。 */
export type WorkflowNodeType = 'start' | 'llm' | 'knowledge-retrieval' | 'condition' | 'http' | 'end'

/** start 节点输入声明项。纯前端约定：引擎透传触发入参、不读 data；用途=下游变量提示+C3 运行表单预填。 */
export interface StartInputDecl {
  name: string
}

export interface StartNodeData {
  inputs?: StartInputDecl[]
}

export interface LlmNodeData {
  modelId?: string
  systemPrompt?: string
  userPrompt?: string
}

export interface KnowledgeNodeData {
  datasetIds?: string[]
  query?: string
}

export interface ConditionNodeData {
  left?: string
  operator?: string
  right?: string
}

export interface HttpNodeData {
  method?: string
  url?: string
  headers?: Record<string, string>
  body?: string
}

/** end 输出声明项：value 是模板，典型值 {{llm_1.text}}。 */
export interface EndOutputDecl {
  name: string
  value: string
}

export interface EndNodeData {
  outputs?: EndOutputDecl[]
}

export interface NodeDataMap {
  start: StartNodeData
  llm: LlmNodeData
  'knowledge-retrieval': KnowledgeNodeData
  condition: ConditionNodeData
  http: HttpNodeData
  end: EndNodeData
}

/** 各类型 data 的并集；字段全可选（草稿允许半成品，必填是运行时语义）。 */
export type WorkflowNodeData = NodeDataMap[WorkflowNodeType]

/** 画布坐标（对齐后端 GraphNode.position jsonb，引擎不读只保真）。 */
export interface GraphNodePosition {
  x: number
  y: number
}

/** 对齐后端 GraphNode record。data 为节点私有配置，按类型收窄（C2）。 */
export interface GraphNode {
  id: string
  type: WorkflowNodeType
  data: WorkflowNodeData
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
