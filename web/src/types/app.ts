/** 应用类型（对齐后端 app.type）。本轮仅 chat 可创建。 */
export type AppType = 'chat' | 'workflow'

/** 应用启停状态。 */
export type AppStatus = 'enabled' | 'disabled'

/** 对话型运行配置（对齐后端 AppConfig，jsonb）。本轮仅系统提示词。 */
export interface AppConfig {
  systemPrompt: string | null
}

/** 应用视图（对齐后端 AppResponse）。id/modelId/ownerId 为 string（Long 序列化防精度丢失）。 */
export interface App {
  id: string
  name: string
  description: string | null
  type: AppType
  modelId: string | null
  modelName: string | null
  config: AppConfig
  ownerId: string
  status: AppStatus
  createTime: string
  updateTime: string
}

/** 创建/编辑共用表单。type 本轮固定 chat（api 层补）。modelId 选填（C1 起支持模型选择器）。 */
export interface AppForm {
  name: string
  description: string
  modelId: string | null
  config: AppConfig
}

/** 页码分页结果（对齐后端 PageResult）。total/page/size 后端以 string 下发（long 也序列化为 string）。 */
export interface PageResult<T> {
  list: T[]
  total: string
  page: string
  size: string
}
