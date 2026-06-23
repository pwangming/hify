/** 模型提供商类型（mock UI 展示标签）。 */
export type ProviderType = 'openai' | 'claude' | 'gemini' | 'ollama'

/** 提供商列表视图。id 为 string（后端 Long 序列化防精度丢失）。apiKey 敏感，不进列表。 */
export interface Provider {
  id: string
  name: string
  type: ProviderType
  baseUrl: string
  status: 'enabled' | 'disabled'
  createTime: string
}

/** 创建/编辑共用请求体。编辑时 apiKey 为空表示不修改。 */
export interface ProviderForm {
  name: string
  type: ProviderType
  apiKey: string
  baseUrl: string
}
