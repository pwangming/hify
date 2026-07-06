/** 供应商接入协议（对齐后端 model_provider.protocol）。 */
export type ProviderProtocol = 'openai' | 'anthropic'

/** 提供商列表视图（对齐后端 ProviderResponse）。id 为 string（Long 序列化防精度丢失）。 */
export interface Provider {
  id: string
  name: string
  protocol: ProviderProtocol
  baseUrl: string
  status: 'enabled' | 'disabled'
  apiKeyTail: string
  createTime: string
  /** 最近一次试连接：null=从未测试（对齐 V17 三字段） */
  lastTestStatus: 'ok' | 'fail' | null
  lastTestAt: string | null
  lastTestError: string | null
}

/** 创建/编辑共用请求体（对齐后端 Create/UpdateProviderRequest）。编辑时 apiKey 为空表示不修改。 */
export interface ProviderForm {
  name: string
  protocol: ProviderProtocol
  apiKey: string
  baseUrl: string
}

/** 供应商试连接响应（对齐后端 ProviderTestResponse）。 */
export interface ProviderTestResult {
  modelName: string
  sample: string
}
