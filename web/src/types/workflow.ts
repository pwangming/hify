/** 画布节点类型（对齐后端 NodeType 的 value）。 */
export type WorkflowNodeType =
  | 'start' | 'llm' | 'knowledge-retrieval' | 'condition' | 'http' | 'code' | 'end'

/** start 节点输入声明项。required 供运行前校验（后端 checkRequiredInputs 只认 required=true）。 */
export interface StartInputDecl {
  name: string
  required?: boolean
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

/** code 节点：code 为 Python 源（须含 def main）；inputs 为 形参名→模板 映射（值支持 {{nodeId.field}}）。 */
export interface CodeNodeData {
  code?: string
  inputs?: Record<string, string>
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
  code: CodeNodeData
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

/** 运行/节点状态（对齐后端 RunStatus；同步执行，响应内只会出现三个终态）。 */
export type RunStatus = 'running' | 'succeeded' | 'failed' | 'skipped'

/** 节点运行记录（对齐后端 NodeRunView；Long 一律序列化为字符串，故 id/elapsedMs 是 string）。 */
export interface NodeRunView {
  id: string
  nodeId: string
  nodeType: string
  status: RunStatus
  inputs: Record<string, unknown> | null
  outputs: Record<string, unknown> | null
  errorMessage: string | null
  elapsedMs: string | null
  createTime: string
}

/** 一次运行的完整视图（对齐后端 RunResponse）。运行失败是 HTTP 200 + status=failed（W1 拍板）。 */
export interface RunResponse {
  id: string
  status: RunStatus
  inputs: Record<string, unknown> | null
  outputs: Record<string, unknown> | null
  errorMessage: string | null
  elapsedMs: string | null
  createTime: string
  nodeRuns: NodeRunView[]
}
