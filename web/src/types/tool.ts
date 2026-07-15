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

/** admin 详情（对齐后端 ToolAdminDetailResponse）。 */
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
}

/** 鉴权头输入（value 明文；编辑时留空=不改）。 */
export interface AuthHeaderInput {
  name: string
  value: string
}

/** 注册/编辑表单（对齐后端 Create/UpdateToolRequest）。 */
export interface ToolForm {
  name: string
  description: string
  specText: string
  authHeaders: AuthHeaderInput[]
}

/** 预览结果（对齐后端 ToolPreviewResponse）。 */
export interface ToolPreview {
  baseUrl: string
  operations: ToolOperation[]
}
