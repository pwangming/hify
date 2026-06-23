import type { Provider, ProviderForm } from '@/types/provider'

// 内存 mock「后端」。将来接真后端只改本文件：把下面函数体换成 request.get/post/... 即可，组件零改动。
let providers: Provider[] = [
  {
    id: '1',
    name: 'OpenAI 官方',
    type: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    status: 'enabled',
    createTime: '2026-06-20T10:00:00+08:00',
  },
  {
    id: '2',
    name: '通义千问（OpenAI 兼容）',
    type: 'openai',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    status: 'enabled',
    createTime: '2026-06-21T11:20:00+08:00',
  },
  {
    id: '3',
    name: 'Anthropic Claude',
    type: 'claude',
    baseUrl: 'https://api.anthropic.com',
    status: 'enabled',
    createTime: '2026-06-22T09:05:00+08:00',
  },
  {
    id: '4',
    name: 'Google Gemini',
    type: 'gemini',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
    status: 'disabled',
    createTime: '2026-06-22T15:40:00+08:00',
  },
  {
    id: '5',
    name: '本地 Ollama',
    type: 'ollama',
    baseUrl: 'http://127.0.0.1:11434/v1',
    status: 'disabled',
    createTime: '2026-06-23T08:30:00+08:00',
  },
]

let nextId = 6

/** 列出全部提供商。 */
export function listProviders(): Promise<Provider[]> {
  return Promise.resolve(providers.map((p) => ({ ...p })))
}

/** 新建提供商。apiKey 仅用于「后端」存储，不回显到列表。 */
export function createProvider(body: ProviderForm): Promise<Provider> {
  const created: Provider = {
    id: String(nextId++),
    name: body.name,
    type: body.type,
    baseUrl: body.baseUrl,
    status: 'enabled',
    createTime: new Date().toISOString(),
  }
  providers = [...providers, created]
  return Promise.resolve({ ...created })
}

/** 编辑提供商。apiKey 为空表示不修改（mock 不存 key，这里仅更新可见字段）。 */
export function updateProvider(id: string, body: ProviderForm): Promise<Provider> {
  const idx = providers.findIndex((p) => p.id === id)
  if (idx === -1) return Promise.reject(new Error('not found'))
  const updated: Provider = {
    ...providers[idx],
    name: body.name,
    type: body.type,
    baseUrl: body.baseUrl,
  }
  providers = providers.map((p) => (p.id === id ? updated : p))
  return Promise.resolve({ ...updated })
}

/** 启用提供商。后端对应：POST .../{id}/enable */
export function enableProvider(id: string): Promise<Provider> {
  return setStatus(id, 'enabled')
}

/** 禁用提供商。后端对应：POST .../{id}/disable */
export function disableProvider(id: string): Promise<Provider> {
  return setStatus(id, 'disabled')
}

function setStatus(id: string, status: Provider['status']): Promise<Provider> {
  const idx = providers.findIndex((p) => p.id === id)
  if (idx === -1) return Promise.reject(new Error('not found'))
  const updated: Provider = { ...providers[idx], status }
  providers = providers.map((p) => (p.id === id ? updated : p))
  return Promise.resolve({ ...updated })
}

/** 删除提供商。 */
export function deleteProvider(id: string): Promise<void> {
  providers = providers.filter((p) => p.id !== id)
  return Promise.resolve()
}
