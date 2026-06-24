# 模型管理前端 UI 设计（Provider B 轮·前端）

> 状态：已认可（2026-06-24）。在已上线的 ProviderList 之上扩展：在「供应商详情页」管理其下的具体模型（chat / embedding）的增删改启停。
> 依赖：后端 ai_model 模型管理 6 端点已上线验证（见 `2026-06-24-ai-model-backend-design.md`）。本轮纯前端，不动后端。

## 0. 范围与边界

**本轮做**
- 新增供应商详情页 `/admin/provider/:id`，整页管理该供应商下的模型。
- 模型 CRUD + 启用/禁用（接已就绪的后端 6 端点）。
- chat / embedding 类型区分；type 创建时定、编辑不可改。
- Anthropic 供应商下前端禁用 embedding 选项（后端 12001 仍兜底）。
- 新增 API 层 `api/admin/model.ts` 与类型 `types/model.ts`。
- TDD：API 层与视图各一份 vitest 规格。

**本轮不做（YAGNI）**
- 后端任何改动（这轮无「查单个供应商」端点，详情页靠拉全量列表按 id 找）。
- 默认模型（is_default）、连通性/可用性校验（属后端 D 轮）。
- app 绑定模型（属 app 模块）。
- 全局「所有模型」聚合视图。

## 1. 路由与入口

- **新路由**：`/admin/provider/:id`，name `ProviderDetail`，组件 `views/admin/provider/ProviderDetail.vue`。
  `meta: { requiresAuth: true, roles: ['admin'], title: '模型管理' }`，**不设 `menu`**（不进侧边菜单，仅从列表页钻入）。
- **入口**：`ProviderList` 每行操作区新增 **「管理模型」** 按钮 → `router.push('/admin/provider/' + row.id)`。
  操作列宽度从 240 加宽到约 320 容纳第 4 个按钮（`data-test="manage-${row.id}"`）。
- **详情页头部**：`PageHeader`，标题为「供应商名 + 协议标签」，附 **「← 返回」** 按钮回 `/admin/provider`。

## 2. 详情页数据流

`onMounted` 顺序：
1. `listProviders()` → 在结果中按 `:id`（来自 `route.params.id`，string）找当前供应商，取其 `name` / `protocol` / `status`。
   - 找不到 → `router.replace('/404')`（直达不存在 id 的兜底）。
2. `listModels(id)` → 拉该供应商的模型列表。

`protocol` 存为响应式状态，驱动新增表单里 embedding 选项的禁用。两次请求独立，可并行发起；provider 未命中时不渲染模型区。

## 3. 模型表格（详情页主体，置于 `ContentCard` 内）

| 列 | 说明 |
|---|---|
| 名称 | `name` |
| 模型标识 | `modelKey`，**完整显示**（非敏感，不掩码） |
| 类型 | `type`，el-tag：chat / embedding |
| 状态 | `status`，el-tag：启用(success) / 禁用(info) |
| 创建时间 | `formatDateTime(createTime)` |
| 操作 | 编辑 · 启用｜禁用(互斥) · 删除 |

- 右上角 **「新增模型」** 按钮（`data-test="model-create-open"`）。
- 空列表用 el-table 默认空态。
- 每行操作 `data-test`：`model-edit-${id}` / `model-enable-${id}` / `model-disable-${id}` / `model-delete-${id}`。

## 4. 模型表单对话框（新增 / 编辑共用，仿 ProviderList 的 dialog）

| 字段 | 新增 | 编辑 |
|---|---|---|
| 类型 type | el-radio-group（chat / embedding）；**provider.protocol === 'anthropic' 时 embedding 选项 `disabled`**，附提示「该协议不支持 embedding 模型」 | **只读展示**（type 创建定死、不可改，不在更新请求体） |
| 名称 name | @NotBlank，≤50 | 同 |
| 模型标识 modelKey | @NotBlank，≤100 | 同 |

- 校验规则对齐后端：name 必填且 ≤50、modelKey 必填且 ≤100、type 必选。
  happy-dom 下 el-form.validate 对空必填会误判通过，需如 ProviderList 那样加手动兜底判断后再提交。
- 提交：
  - 新增 → `createModel(providerId, { type, name, modelKey })`，成功 toast「模型已创建」。
  - 编辑 → `updateModel(id, { name, modelKey })`，成功 toast「模型已更新」。
- 失败（重名 10006、anthropic+embedding 12001 兜底等）由请求拦截器统一 toast，弹窗保持打开让用户改。

## 5. 启停 / 删除（镜像 ProviderList 语义）

- 启用：`enableModel(id)` 直接调用（无确认）。
- 禁用：`confirmDanger` 二次确认后 `disableModel(id)`。
- 删除：`confirmDanger` 二次确认后 `deleteModel(id)`（不可恢复提示）。
- 三者成功后重拉模型列表刷新（后端返回 `Void`）。

## 6. API 层与类型（新增文件）

### 6.1 `web/src/api/admin/model.ts`
路径前缀 `const BASE = '/admin/provider'`（baseURL 已含 `/api/v1`）。

| 函数 | 后端端点 |
|---|---|
| `listModels(providerId)` | GET `/admin/provider/providers/{providerId}/models` |
| `createModel(providerId, body)` | POST `/admin/provider/providers/{providerId}/models` |
| `updateModel(id, body)` | PUT `/admin/provider/models/{id}` |
| `deleteModel(id)` | DELETE `/admin/provider/models/{id}` |
| `enableModel(id)` | POST `/admin/provider/models/{id}/enable` |
| `disableModel(id)` | POST `/admin/provider/models/{id}/disable` |

### 6.2 `web/src/types/model.ts`
```ts
export type ModelType = 'chat' | 'embedding'

// 对齐后端 ModelResponse。id/providerId 为 Long → string（防精度丢失）。
export interface AiModel {
  id: string
  providerId: string
  type: ModelType
  name: string
  modelKey: string
  status: 'enabled' | 'disabled'
  createTime: string
}

// 新增请求体（type 仅新增用；编辑时只取 name+modelKey）。
export interface ModelForm {
  type: ModelType
  name: string
  modelKey: string
}
```

## 7. 测试（TDD，vitest，放 `__tests__/`）

- `web/src/api/admin/__tests__/model.spec.ts`（API 层）：6 函数各验证 URL / HTTP 方法 / body 正确，仿 `api/admin/__tests__/user.spec.ts`。
- `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts`（视图）：mock API 模块，覆盖
  - 渲染模型列表、空态；
  - anthropic 供应商时 embedding 选项 `disabled`；非 anthropic 时可选；
  - 新增 / 编辑 / 删除 / 启用 / 禁用 流程（断言调用对应 API 并重拉）；
  - 找不到 provider → 跳 `/404`。
  仿 `ProviderList.spec.ts`（含 happy-dom 校验兜底写法）。
- `menu.spec.ts`：确认 `/admin/provider/:id` 不进侧边菜单（若该测试遍历全路由表）。

每完成一步追加自检到 `docs/self-check.md`（实现计划阶段执行）。

## 8. 后续（不在本轮）

- 后端 C 轮：`ProviderFacade` 消费 model→provider 构建带韧性的 ChatClient，接入 `ApiKeyCipher.decrypt`。
- 后端 D 轮：连通性/可用性校验（详情页可加「测试连通」入口）。
- 详情页将来可承载默认模型标记、用量等扩展（独立路由已留出空间）。
