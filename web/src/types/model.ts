/** 模型用途（对齐后端 ai_model.type）。 */
export type ModelType = 'chat' | 'embedding'

/** 模型列表视图（对齐后端 ModelResponse）。id/providerId 为 string（Long 序列化防精度丢失）。 */
export interface AiModel {
  id: string
  providerId: string
  type: ModelType
  name: string
  modelKey: string
  status: 'enabled' | 'disabled'
  createTime: string
}

/** 新增请求体（对齐后端 CreateModelRequest）。编辑时只取 name+modelKey（type 不可改）。 */
export interface ModelForm {
  type: ModelType
  name: string
  modelKey: string
}
