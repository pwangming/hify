# 前端 ProviderList 切真后端设计

> 状态：已认可（2026-06-24）。把 Admin 模型供应商列表页从内存 mock 切到真实后端（Provider 后端第 1 轮已上线）。
> 后端契约见 `2026-06-24-provider-backend-design.md`；本轮纯前端，不动后端。

## 0. 范围与边界

**本轮做**
- `web/` 下 ProviderList 列表页对接真实 6 端点（list/create/update/enable/disable/delete）。
- 类型契约对齐后端：`type`(4 值厂商标签) → `protocol`(2 值协议)，新增 `apiKeyTail`。
- 列表新增「API Key」掩码列。

**本轮不做（YAGNI）**
- 分页 / 搜索 / 排序（admin 量小，列表直出）。
- 连通性校验入口（后端 D 轮）、详情页。
- 任何后端改动。

## 1. 背景与既有约定

- `web/src/api/request.ts` 拦截器**已解包 `Result` 信封**（成功 resolve `data`），并统一处理错误：10002/10003 跳登录、10001 静默（字段错误交表单）、其余 `ElMessage.error(message)` 全局 toast。故 api 层只写薄函数。
- 范本：`web/src/api/admin/user.ts`（`request.get/post/put/delete` + `const BASE`）。`baseURL` 已含 `/api/v1`，函数内只拼模块路径。
- 现状文件：`web/src/types/provider.ts`、`web/src/api/admin/provider.ts`（mock）、`web/src/views/admin/provider/ProviderList.vue`、`web/src/views/admin/provider/__tests__/ProviderList.spec.ts`。
- ProviderList.vue 已用 6 个 api 函数、每次写操作后 `await load()` 重拉、enable/disable/delete 按钮与二次确认齐备——本轮只调字段名与展示，不重构交互。

## 2. 设计

### 2.1 类型层 `types/provider.ts`

```ts
/** 供应商接入协议（对齐后端 model_provider.protocol）。 */
export type ProviderProtocol = 'openai' | 'anthropic'

export interface Provider {
  id: string
  name: string
  protocol: ProviderProtocol
  baseUrl: string
  status: 'enabled' | 'disabled'
  apiKeyTail: string          // 明文后 4 位，仅供掩码展示
  createTime: string
}

/** 创建/编辑共用请求体（对齐后端 Create/UpdateProviderRequest）。编辑时 apiKey 为空=不修改。 */
export interface ProviderForm {
  name: string
  protocol: ProviderProtocol
  apiKey: string
  baseUrl: string
}
```

变化：`ProviderType`→`ProviderProtocol`（4→2 值）；`Provider.type`→`protocol`；`Provider` 新增 `apiKeyTail`；`ProviderForm.type`→`protocol`。

### 2.2 API 层 `api/admin/provider.ts`

整体替换 mock 为真实 `request` 调用（照 `api/admin/user.ts`）：

```ts
import { request } from '@/api/request'
import type { Provider, ProviderForm } from '@/types/provider'

const BASE = '/admin/provider/providers'

export function listProviders() { return request.get<Provider[]>(BASE) }
export function createProvider(body: ProviderForm) { return request.post<Provider>(BASE, body) }
export function updateProvider(id: string, body: ProviderForm) { return request.put<Provider>(`${BASE}/${id}`, body) }
export function enableProvider(id: string) { return request.post<void>(`${BASE}/${id}/enable`) }
export function disableProvider(id: string) { return request.post<void>(`${BASE}/${id}/disable`) }
export function deleteProvider(id: string) { return request.delete<void>(`${BASE}/${id}`) }
```

删除：内存 `providers` 数组、`nextId`、`setStatus` 辅助。enable/disable/delete 返回类型由 `Promise<Provider>`/`Promise<void>` 统一为后端真实的 `void`（组件不使用返回值，仅 `await load()` 重拉，无影响）。

### 2.3 页面层 `ProviderList.vue`

- 映射表改名并收敛到 2 项：
  ```ts
  const PROTOCOL_LABEL: Record<ProviderProtocol, string> = { openai: 'OpenAI 兼容', anthropic: 'Anthropic' }
  const PROTOCOL_TAG: Record<ProviderProtocol, '' | 'success'> = { openai: '', anthropic: 'success' }
  ```
- 类型列与下拉改用 `row.protocol` / `form.protocol`；下拉 4 选项 → 2 选项（值 `openai`/`anthropic`）。
- 表单：`form.type`→`form.protocol`（默认 `'openai'`）；`rules` 的 `type` 键 → `protocol`；`openCreate`/`openEdit` 同步。
- **新增「API Key」列**（Base URL 之后、状态之前）：`••••{{ (row as Provider).apiKeyTail }}`。
- `submitForm`：兜底校验 `!form.protocol`；create/update 包 try/catch——成功才 `dialogVisible=false`+`load()`，失败时（拦截器已 toast，如重名）**弹窗保持打开**让用户改：
  ```ts
  try {
    if (editingId.value === null) { await createProvider({ ...form }); ElMessage.success('提供商已创建') }
    else { await updateProvider(editingId.value, { ...form }); ElMessage.success('提供商已更新') }
    dialogVisible.value = false
    await load()
  } catch { /* 拦截器已统一 toast；弹窗保持打开 */ }
  ```
- 不动：编辑留空不改密钥、enable/disable/delete 的二次确认与 `load()` 重拉、`data-test` 钩子（沿用，新增列/选项按需补钩子）。

### 2.4 测试 `__tests__/ProviderList.spec.ts`（TDD，先改红）

- `vi.mock('@/api/admin/provider')` 的桩数据换新形：含 `protocol`、`apiKeyTail`，去掉 `type`。
- 断言调整：
  - 类型列渲染「OpenAI 兼容」/「Anthropic」。
  - 新「API Key」列渲染 `••••` + 后 4 位。
  - 类型下拉为 2 项。
  - 创建提交时 `createProvider` 收到的 body 带 `protocol`（非 `type`）。
- 保留原覆盖：挂载渲染行数（listProviders 调用）、新增弹窗、提交调 create 且重拉、编辑预填、删除确认调 delete。
- 组件只依赖 api 函数契约，不依赖其实现。

## 3. 错误处理

全部走 `request.ts` 既有拦截器：重名(10006)/系统错误等自动 toast 后端 message；10001 字段错误静默（本轮表单先做客户端校验，正常不触发）；401/登录态失效跳登录页。组件层仅在 `submitForm` 用 try/catch 保证失败时弹窗不关，无自定义错误码处理。

## 4. 验收

- `pnpm test`（vitest）全绿；`pnpm lint` + `pnpm type-check` 通过。
- 手测：server 在线时，列表正确拉取并展示协议标签 + `••••尾巴`；新增/编辑/启停/删除均生效并重拉；重名时弹窗不关并提示。

## 5. 后续（不在本轮）

- 后端 B（ai_model 多模型管理）落地后，前端补"模型管理"界面。
- 后端 D（连通性校验）落地后，前端表单加"测试连接"按钮。
