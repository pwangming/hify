/** 工具选项（对齐后端 ToolView）。id 为 string（Long）。 */
export interface ToolOption {
  id: string
  name: string
  description: string
  source: string
}

/** admin 列表项（对齐后端 ToolAdminResponse）。id/ownerId 为 string(Long)。 */
export interface ToolAdminItem {
  id: string
  name: string
  description: string
  source: string
  enabled: boolean
  operationCount: number | null
  ownerId: string | null
  createTime: string
  updateTime: string
}

/** 操作摘要（对齐后端 OperationView）。 */
export interface ToolOperation {
  opName: string
  method: string
  pathTemplate: string
  description: string
}

/** admin 详情（对齐后端 ToolAdminDetailResponse）。openapi 行填 baseUrl/operations/rawSpec；mcp 行填 url/transport/tools/discoveredAt；另一边为 null / []。 */
export interface ToolAdminDetail {
  id: string
  name: string
  description: string
  source: string
  enabled: boolean
  baseUrl: string | null
  operations: ToolOperation[]
  authHeaderNames: string[]
  rawSpec: string | null
  url: string | null
  transport: string | null
  tools: McpToolItem[]
  discoveredAt: string | null
}

/** MCP 工具摘要（对齐后端 McpToolView）。 */
export interface McpToolItem {
  toolName: string
  description: string
}

/** 鉴权头输入（value 明文；编辑时留空=不改）。 */
export interface AuthHeaderInput {
  name: string
  value: string
}

/** 注册/编辑表单状态（抽屉本地状态；提交 body 用 buildBody 构造，见 ToolUpsertBody）。 */
export interface ToolForm {
  name: string
  description: string
  type: 'openapi' | 'mcp'
  specText: string
  url: string
  transport: string
  authHeaders: AuthHeaderInput[]
}

/** 创建/更新请求 body（对齐后端 Create/UpdateToolRequest）。openapi 不传 type——与 T3b 上线请求字节级一致，顺带回归后端 type 缺省兼容路径。 */
export interface ToolUpsertBody {
  name: string
  description: string
  type?: 'mcp'
  specText?: string
  url?: string
  transport?: string
  authHeaders: AuthHeaderInput[]
}

/** 预览请求 body（对齐后端 PreviewToolRequest）。openapi 只传 specText；mcp 传 type/url/transport/authHeaders。 */
export interface ToolPreviewBody {
  type?: 'mcp'
  specText?: string
  url?: string
  transport?: string
  authHeaders?: AuthHeaderInput[]
}

/** 预览结果（对齐后端 ToolPreviewResponse）。openapi 回 baseUrl+operations；mcp 回 tools；未用到的那边为 null / []。 */
export interface ToolPreview {
  baseUrl: string | null
  operations: ToolOperation[]
  tools: McpToolItem[]
}
