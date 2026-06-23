# Provider 列表页（mock 数据）设计

> 状态：已认可（2026-06-24）。本期用 mock 数据，不接真实后端 API。

## 目标

实现 Admin「模型提供商管理」列表页，覆盖：列表展示、新增、编辑、删除，全部基于内存 mock 数据。
将来接真后端时只需替换 api 层一个文件，页面组件零改动。

## 背景与约束

- 现状：`web/src/views/admin/provider/ProviderList.vue` 当前只是后端健康检查 stub，本期替换为真正的列表页。
- 参照范本：`web/src/views/admin/identity/UserList.vue`（PageHeader + ContentCard + el-table + el-tag + el-dialog 表单）。代码风格、分层、命名一律向它对齐。
- 确认弹窗：沿用 UserList 的**内联 `confirmDanger`**（包一层 `ElMessageBox.confirm`），**不**新建 `useConfirm` 组合式函数（项目约定 rule-of-three 再提取，当前仅第 2 处用到）。
- Mock 数据放 **api 层**（`src/api/admin/provider.ts`），函数返回 `Promise.resolve(...)`，与 `api/admin/user.ts` 同形。
- 类型显示标签 OpenAI / Claude / Gemini / Ollama 仅为 mock UI 展示，不严格等同 CLAUDE.md 中「OpenAI 兼容 + Anthropic」两种协议接入模型。

## 设计

### 1. 类型层 `web/src/types/provider.ts`

```ts
type ProviderType = 'openai' | 'claude' | 'gemini' | 'ollama'

interface Provider {
  id: string            // 对齐项目约定：后端 Long 序列化为 string 防精度丢失
  name: string
  type: ProviderType
  baseUrl: string
  status: 'enabled' | 'disabled'
  createTime: string
}

interface ProviderForm {  // 创建/编辑共用请求体
  name: string
  type: ProviderType
  apiKey: string          // 仅进表单，不进列表（敏感字段）
  baseUrl: string
}
```

### 2. Mock「后端」`web/src/api/admin/provider.ts`

- 模块内持有 5 条 mock 数组，类型分布：openai ×2、claude ×1、gemini ×1、ollama ×1；状态混合启用/禁用。
- 导出函数，全部返回 Promise，在内存数组上增删改：
  - `listProviders(): Promise<Provider[]>`
  - `createProvider(body: ProviderForm): Promise<Provider>`
  - `updateProvider(id: string, body: ProviderForm): Promise<Provider>`
  - `deleteProvider(id: string): Promise<void>`
- 为什么：将来接真后端只改此文件，组件零改动；函数签名与 `api/admin/user.ts` 一致。
- `apiKey` 不出现在 `Provider`（列表）中；编辑时传空字符串表示「不修改」。

### 3. 页面 `web/src/views/admin/provider/ProviderList.vue`

替换现有健康检查 stub。

- `PageHeader` 标题「模型提供商管理」+ 描述文字 + 右侧「新增提供商」按钮。
- `ContentCard` 内 `el-table`（`v-loading`）列：
  - 名称 `prop="name"`
  - 类型：中文/标签映射（openai→OpenAI、claude→Claude、gemini→Gemini、ollama→Ollama），用 `el-tag` 展示
  - Base URL `prop="baseUrl"`
  - 状态：`el-tag`，启用 `success`、禁用 `info`
  - 创建时间：`formatDateTime(row.createTime)`
  - 操作：编辑 / 删除（flex gap 间隔，照 UserList `__ops` 写法）
- 单个 `el-dialog` 同时管创建与编辑：
  - 标题随模式切换（新增提供商 / 编辑提供商）。
  - 编辑预填 name/type/baseUrl；apiKey 留空 + placeholder「留空表示不修改」。
  - 字段：名称（必填，maxlength 50）、类型（el-select 下拉，必填）、API Key（el-input，创建必填、编辑可空）、Base URL（必填 + 简单 URL 前缀校验 http/https）。
- 删除：内联 `confirmDanger(message, title)` → `deleteProvider` → 成功 toast → 重拉。
- 提交沿用 UserList 的兜底校验（happy-dom 下 el-form.validate 对空必填误判通过，提交前再按约束手动校验一次）。

### 4. 测试（TDD，先写失败用例）`web/src/views/admin/provider/__tests__/ProviderList.spec.ts`

`vi.mock('@/api/admin/provider')` 打桩 api 函数，断言：

- 挂载后渲染 5 行（listProviders 被调用）。
- 点「新增提供商」弹出对话框。
- 填表提交后调用 `createProvider` 且重新 `listProviders`。
- 点编辑弹出对话框并预填。
- 点删除 → 确认 → 调用 `deleteProvider` 且重拉。

组件只依赖 api 函数契约，不依赖 mock 数组内部实现。

## 实现顺序

1. `types/provider.ts`
2. `ProviderList.spec.ts`（先写，红）
3. `api/admin/provider.ts`（mock）
4. `ProviderList.vue`（替换 stub）
5. 测试转绿
6. lint + typecheck

## 不做（YAGNI）

- 不新建 useConfirm 组合式函数。
- 不做分页 / 搜索 / 排序（5 条 mock，列表直出）。
- 不做启用/禁用切换动作（列定义只要求显示状态 + 编辑/删除）。
- 不接真实后端、不做 SSRF/连通性校验（mock 期）。
