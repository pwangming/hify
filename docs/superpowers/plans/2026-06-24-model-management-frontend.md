# 模型管理前端 UI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在供应商详情页 `/admin/provider/:id` 管理该供应商下的模型（chat/embedding 的增删改启停），接已上线的后端 6 端点。

**Architecture:** 纯前端三层落地——新增类型 `types/model.ts` 与 API 层 `api/admin/model.ts`（封装 6 端点）；`ProviderList` 每行加「管理模型」按钮钻入新路由；新建 `ProviderDetail.vue` 整页承载模型表格 + 新增/编辑对话框 + 启停删。详情页靠 `listProviders()` 按 id 找当前供应商（后端这轮无单查端点），用其 `protocol` 驱动 anthropic 下 embedding 选项禁用。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus 2.9 + Vue Router 4 + axios（封装在 `@/api/request`）+ vitest + @vue/test-utils（happy-dom）。

## Global Constraints

- 所有命令在 `web/` 目录下执行；测试用 `pnpm test`（即 `vitest run`）。
- 后端契约固定（见 `docs/superpowers/specs/2026-06-24-ai-model-backend-design.md`）：
  - GET/POST `/api/v1/admin/provider/providers/{providerId}/models`；PUT/DELETE/enable/disable `/api/v1/admin/provider/models/{id}`。
  - `request` 的 baseURL 已含 `/api/v1`，API 层只拼模块内路径，前缀 `/admin/provider`。
- `id`/`providerId` 是 Long → 一律 string（防精度丢失）。
- 校验对齐后端：`name` 必填 ≤50，`modelKey` 必填 ≤100，`type` ∈ {chat, embedding} 必选。
- type 创建时定死、编辑不可改、不进更新请求体；更新只传 `{name, modelKey}`。
- anthropic 供应商下 embedding 选项前端置灰（后端 12001 兜底）。
- 失败（重名 10006 等）由 `@/api/request` 拦截器统一 toast，弹窗保持打开——业务 catch 块留空注释即可。
- happy-dom 下 `el-form.validate()` 对空必填会误判通过，提交前需手动兜底判断（照 `ProviderList.vue`）。
- 每完成一个 Task 追加自检到 `docs/self-check.md`。

---

## 文件结构

- 新建 `web/src/types/model.ts` — 模型相关类型（ModelType / AiModel / ModelForm）。
- 新建 `web/src/api/admin/model.ts` — 6 个 API 函数。
- 新建 `web/src/api/admin/__tests__/model.spec.ts` — API 层规格。
- 改 `web/src/router/index.ts` — 注册 `/admin/provider/:id` 路由（不进菜单）。
- 改 `web/src/views/admin/provider/ProviderList.vue` — 每行加「管理模型」按钮。
- 改 `web/src/views/admin/provider/__tests__/ProviderList.spec.ts` — 加导航测试。
- 新建 `web/src/views/admin/provider/ProviderDetail.vue` — 详情页主体。
- 新建 `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts` — 详情页规格。

---

### Task 1: 类型与 API 层

**Files:**
- Create: `web/src/types/model.ts`
- Create: `web/src/api/admin/model.ts`
- Test: `web/src/api/admin/__tests__/model.spec.ts`

**Interfaces:**
- Consumes: `request` from `@/api/request`（`get/post/put/delete`，已解包 Result，返回 `Promise<T>`）。
- Produces:
  - `types/model.ts`：`ModelType = 'chat' | 'embedding'`；`AiModel { id, providerId, type, name, modelKey, status, createTime }`（id/providerId 为 string，status `'enabled'|'disabled'`）；`ModelForm { type: ModelType, name: string, modelKey: string }`。
  - `api/admin/model.ts`：`listModels(providerId: string): Promise<AiModel[]>`、`createModel(providerId: string, body: ModelForm): Promise<AiModel>`、`updateModel(id: string, body: Pick<ModelForm,'name'|'modelKey'>): Promise<AiModel>`、`deleteModel(id: string): Promise<void>`、`enableModel(id: string): Promise<void>`、`disableModel(id: string): Promise<void>`。

- [ ] **Step 1: 写失败的 API 规格**

创建 `web/src/api/admin/__tests__/model.spec.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listModels, createModel, updateModel,
  deleteModel, enableModel, disableModel,
} from '@/api/admin/model'
import type { ModelForm } from '@/types/model'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('admin model api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listModels → GET /admin/provider/providers/{providerId}/models', () => {
    listModels('5')
    expect(request.get).toHaveBeenCalledWith('/admin/provider/providers/5/models')
  })
  it('createModel → POST /admin/provider/providers/{providerId}/models + body', () => {
    const body: ModelForm = { type: 'chat', name: 'GPT-4o', modelKey: 'gpt-4o' }
    createModel('5', body)
    expect(request.post).toHaveBeenCalledWith('/admin/provider/providers/5/models', body)
  })
  it('updateModel → PUT /admin/provider/models/{id} + {name,modelKey}', () => {
    updateModel('8', { name: '改名', modelKey: 'gpt-4o-mini' })
    expect(request.put).toHaveBeenCalledWith('/admin/provider/models/8', {
      name: '改名', modelKey: 'gpt-4o-mini',
    })
  })
  it('deleteModel → DELETE /admin/provider/models/{id}', () => {
    deleteModel('8')
    expect(request.delete).toHaveBeenCalledWith('/admin/provider/models/8')
  })
  it('enableModel → POST .../{id}/enable', () => {
    enableModel('8')
    expect(request.post).toHaveBeenCalledWith('/admin/provider/models/8/enable')
  })
  it('disableModel → POST .../{id}/disable', () => {
    disableModel('8')
    expect(request.post).toHaveBeenCalledWith('/admin/provider/models/8/disable')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/api/admin/__tests__/model.spec.ts`
Expected: FAIL（`Failed to resolve import '@/api/admin/model'` 或 `@/types/model`）。

- [ ] **Step 3: 写类型**

创建 `web/src/types/model.ts`：

```ts
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
```

- [ ] **Step 4: 写 API 层**

创建 `web/src/api/admin/model.ts`：

```ts
import { request } from '@/api/request'
import type { AiModel, ModelForm } from '@/types/model'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/provider'

/** 列某供应商下的模型。后端：GET .../providers/{providerId}/models */
export function listModels(providerId: string) {
  return request.get<AiModel[]>(`${BASE}/providers/${providerId}/models`)
}

/** 在该供应商下建模型。后端：POST .../providers/{providerId}/models */
export function createModel(providerId: string, body: ModelForm) {
  return request.post<AiModel>(`${BASE}/providers/${providerId}/models`, body)
}

/** 改模型（只改 name+modelKey；type 不可改）。后端：PUT .../models/{id} */
export function updateModel(id: string, body: Pick<ModelForm, 'name' | 'modelKey'>) {
  return request.put<AiModel>(`${BASE}/models/${id}`, body)
}

/** 删除模型（逻辑删除）。后端：DELETE .../models/{id} */
export function deleteModel(id: string) {
  return request.delete<void>(`${BASE}/models/${id}`)
}

/** 启用模型。后端：POST .../models/{id}/enable */
export function enableModel(id: string) {
  return request.post<void>(`${BASE}/models/${id}/enable`)
}

/** 禁用模型。后端：POST .../models/{id}/disable */
export function disableModel(id: string) {
  return request.post<void>(`${BASE}/models/${id}/disable`)
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `pnpm test src/api/admin/__tests__/model.spec.ts`
Expected: PASS（6 通过）。

- [ ] **Step 6: 追加自检并提交**

向 `docs/self-check.md` 追加本步自检要点（类型与 API 层、URL 对齐后端、Long→string）。

```bash
git add web/src/types/model.ts web/src/api/admin/model.ts web/src/api/admin/__tests__/model.spec.ts docs/self-check.md
git commit -m "feat(web)：模型管理 API 层与类型（6 端点封装）"
```

---

### Task 2: 路由注册 + ProviderList 入口按钮

**Files:**
- Modify: `web/src/router/index.ts`（在 ProviderList 路由后插入详情页路由）
- Modify: `web/src/views/admin/provider/ProviderList.vue`（操作列加「管理模型」按钮 + useRouter）
- Test: `web/src/views/admin/provider/__tests__/ProviderList.spec.ts`（加导航测试）

**Interfaces:**
- Consumes: `useRouter` from `vue-router`；`Provider` from `@/types/provider`。
- Produces: 路由 `name: 'ProviderDetail'`，`path: '/admin/provider/:id'`，组件 `ProviderDetail.vue`（Task 3 创建，本任务先引用其懒加载路径）；ProviderList 每行 `data-test="manage-${row.id}"` 按钮，点击 `router.push('/admin/provider/' + id)`。

- [ ] **Step 1: 写失败的导航测试**

在 `web/src/views/admin/provider/__tests__/ProviderList.spec.ts` 顶部 import 区加入 vue-router：

```ts
import { createRouter, createMemoryHistory } from 'vue-router'
```

在 `describe('ProviderList', ...)` 内末尾追加用例（`SAMPLE`、`listProviders` mock 已在文件中）：

```ts
  it('点「管理模型」跳转到该供应商详情页', async () => {
    const Stub = { template: '<div />' }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/admin/provider', component: Stub },
        { path: '/admin/provider/:id', component: Stub },
      ],
    })
    await router.push('/admin/provider')
    await router.isReady()

    const wrapper = mount(ProviderList, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="manage-1"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/provider/1')
  })
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/admin/provider/__tests__/ProviderList.spec.ts`
Expected: FAIL（找不到 `[data-test="manage-1"]`）。

- [ ] **Step 3: ProviderList 加 useRouter 与按钮**

`web/src/views/admin/provider/ProviderList.vue`：在 `<script setup>` import 区加：

```ts
import { useRouter } from 'vue-router'
```

在 `const providers = ref<Provider[]>([])` 上方加：

```ts
const router = useRouter()
```

操作列宽度由 `240` 改为 `320`（`<el-table-column label="操作" width="240">` → `width="320"`），并在该列「编辑」按钮之前插入：

```vue
              <el-button
                :data-test="`manage-${(row as Provider).id}`"
                size="small"
                type="primary"
                @click="router.push('/admin/provider/' + (row as Provider).id)"
                >管理模型</el-button
              >
```

- [ ] **Step 4: 注册路由**

`web/src/router/index.ts`：在 `name: 'ProviderList'` 那条路由对象之后、`/admin/identity` 之前插入：

```ts
  {
    path: '/admin/provider/:id',
    name: 'ProviderDetail',
    component: () => import('@/views/admin/provider/ProviderDetail.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin'],
      title: '模型管理',
    },
  },
```

（不设 `menu`，故不进侧边菜单；`ProviderDetail.vue` 在 Task 3 创建，懒加载在运行到该路由时才解析，本任务的单测不触达。）

- [ ] **Step 5: 跑测试确认通过**

Run: `pnpm test src/views/admin/provider/__tests__/ProviderList.spec.ts`
Expected: PASS（含新增导航用例；原有用例不受影响——它们不点 manage，`useRouter()` 在无 router 时返回 undefined 但未被调用）。

- [ ] **Step 6: 跑路由菜单测试确认不破坏**

Run: `pnpm test src/router/__tests__/menu.spec.ts`
Expected: PASS（新路由无 `menu:true`，不进菜单列表）。

- [ ] **Step 7: 追加自检并提交**

向 `docs/self-check.md` 追加（详情页路由不进菜单、操作列加宽、manage 按钮导航）。

```bash
git add web/src/router/index.ts web/src/views/admin/provider/ProviderList.vue web/src/views/admin/provider/__tests__/ProviderList.spec.ts docs/self-check.md
git commit -m "feat(web)：ProviderList 加「管理模型」入口 + 注册详情页路由"
```

---

### Task 3: ProviderDetail 详情页（表格 + 对话框 + 启停删）

**Files:**
- Create: `web/src/views/admin/provider/ProviderDetail.vue`
- Test: `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts`

**Interfaces:**
- Consumes: `listProviders` from `@/api/admin/provider`；`listModels/createModel/updateModel/deleteModel/enableModel/disableModel` from `@/api/admin/model`（Task 1）；`useRoute/useRouter` from `vue-router`；`Provider/ProviderProtocol` from `@/types/provider`；`AiModel/ModelForm/ModelType` from `@/types/model`；`PageHeader`、`ContentCard`、`formatDateTime`。
- Produces: 路由 `/admin/provider/:id` 渲染的页面组件（被 Task 2 注册的路由懒加载）。

- [ ] **Step 1: 写失败的详情页规格**

创建 `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { listProviders } from '@/api/admin/provider'
import {
  listModels, createModel, updateModel,
  deleteModel, enableModel, disableModel,
} from '@/api/admin/model'
import type { Provider } from '@/types/provider'
import type { AiModel } from '@/types/model'
import ProviderDetail from '@/views/admin/provider/ProviderDetail.vue'

vi.mock('@/api/admin/provider', () => ({ listProviders: vi.fn() }))
vi.mock('@/api/admin/model', () => ({
  listModels: vi.fn(),
  createModel: vi.fn(),
  updateModel: vi.fn(),
  deleteModel: vi.fn(),
  enableModel: vi.fn(),
  disableModel: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const OPENAI_PROVIDER: Provider = {
  id: '1', name: '通义千问', protocol: 'openai',
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  status: 'enabled', apiKeyTail: '4321', createTime: '2026-06-22T15:40:00+08:00',
}
const ANTHROPIC_PROVIDER: Provider = {
  id: '3', name: 'Anthropic Claude', protocol: 'anthropic',
  baseUrl: 'https://api.anthropic.com',
  status: 'enabled', apiKeyTail: 'wxyz', createTime: '2026-06-22T09:05:00+08:00',
}
const MODELS: AiModel[] = [
  { id: '10', providerId: '1', type: 'chat', name: 'GPT-4o', modelKey: 'gpt-4o',
    status: 'enabled', createTime: '2026-06-23T10:00:00+08:00' },
  { id: '11', providerId: '1', type: 'embedding', name: 'BGE', modelKey: 'bge-large',
    status: 'disabled', createTime: '2026-06-23T11:00:00+08:00' },
]

// 挂载 ProviderDetail 到指定供应商 id 的真实 memory router
async function mountAt(id: string): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const Stub = { template: '<div />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/provider', component: Stub },
      { path: '/admin/provider/:id', component: ProviderDetail },
      { path: '/404', component: Stub },
    ],
  })
  await router.push(`/admin/provider/${id}`)
  await router.isReady()
  const wrapper = mount(ProviderDetail, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return { wrapper, router }
}

describe('ProviderDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProviders).mockResolvedValue([OPENAI_PROVIDER, ANTHROPIC_PROVIDER])
    vi.mocked(listModels).mockResolvedValue(MODELS)
  })

  it('挂载时按 id 找供应商、拉模型、渲染表头与模型行', async () => {
    const { wrapper } = await mountAt('1')
    expect(listProviders).toHaveBeenCalledOnce()
    expect(listModels).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('通义千问')
    expect(wrapper.text()).toContain('GPT-4o')
    expect(wrapper.text()).toContain('gpt-4o')      // modelKey 完整显示
    expect(wrapper.text()).toContain('bge-large')
  })

  it('找不到供应商 → 跳 /404，不拉模型', async () => {
    const { router } = await mountAt('999')
    expect(router.currentRoute.value.path).toBe('/404')
    expect(listModels).not.toHaveBeenCalled()
  })

  it('空列表正常渲染（不报错）', async () => {
    vi.mocked(listModels).mockResolvedValue([])
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-table"]').exists()).toBe(true)
  })

  it('openai 供应商：embedding 选项可选', async () => {
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    const embRadio = wrapper.find('[data-test="form-type"] input[value="embedding"]')
    expect((embRadio.element as HTMLInputElement).disabled).toBe(false)
  })

  it('anthropic 供应商：embedding 选项被禁用', async () => {
    const { wrapper } = await mountAt('3')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    const embRadio = wrapper.find('[data-test="form-type"] input[value="embedding"]')
    expect((embRadio.element as HTMLInputElement).disabled).toBe(true)
  })

  it('新建成功：调 createModel(providerId, body) 后重拉', async () => {
    vi.mocked(createModel).mockResolvedValue({
      id: '12', providerId: '1', type: 'chat', name: 'New', modelKey: 'new-key',
      status: 'enabled', createTime: '2026-06-24T08:00:00+08:00',
    })
    const { wrapper } = await mountAt('1')
    expect(listModels).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('New')
    await wrapper.get('[data-test="form-modelkey"]').setValue('new-key')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createModel).toHaveBeenCalledWith('1', { type: 'chat', name: 'New', modelKey: 'new-key' })
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('新建表单：名称为空时拦截，不调 createModel', async () => {
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createModel).not.toHaveBeenCalled()
  })

  it('编辑：预填 name/modelKey，提交只调 updateModel(id, {name,modelKey})', async () => {
    vi.mocked(updateModel).mockResolvedValue({ ...MODELS[0], name: 'GPT-4o 改' })
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-edit-10"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe('GPT-4o')
    expect((wrapper.get('[data-test="form-modelkey"]').element as HTMLInputElement).value).toBe('gpt-4o')
    await wrapper.get('[data-test="form-name"]').setValue('GPT-4o 改')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateModel).toHaveBeenCalledWith('10', { name: 'GPT-4o 改', modelKey: 'gpt-4o' })
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('删除：确认后调 deleteModel 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(deleteModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-delete-10"]').trigger('click')
    await flushPromises()
    expect(deleteModel).toHaveBeenCalledWith('10')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('删除：取消则不调 deleteModel', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const { wrapper } = await mountAt('1')
    await wrapper.get('[data-test="model-delete-10"]').trigger('click')
    await flushPromises()
    expect(deleteModel).not.toHaveBeenCalled()
  })

  it('启用行（status=enabled）显示「禁用」：确认后调 disableModel', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(disableModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-enable-10"]').exists()).toBe(false)
    await wrapper.get('[data-test="model-disable-10"]').trigger('click')
    await flushPromises()
    expect(disableModel).toHaveBeenCalledWith('10')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('禁用行（status=disabled）显示「启用」：直接调 enableModel（无确认）', async () => {
    vi.mocked(enableModel).mockResolvedValue(undefined)
    const { wrapper } = await mountAt('1')
    expect(wrapper.find('[data-test="model-disable-11"]').exists()).toBe(false)
    await wrapper.get('[data-test="model-enable-11"]').trigger('click')
    await flushPromises()
    expect(enableModel).toHaveBeenCalledWith('11')
    expect(listModels).toHaveBeenCalledTimes(2)
  })

  it('点「返回」跳回供应商列表', async () => {
    const { wrapper, router } = await mountAt('1')
    await wrapper.get('[data-test="back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/admin/provider')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts`
Expected: FAIL（`Failed to resolve import '@/views/admin/provider/ProviderDetail.vue'`）。

- [ ] **Step 3: 实现 ProviderDetail.vue**

创建 `web/src/views/admin/provider/ProviderDetail.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listProviders } from '@/api/admin/provider'
import {
  listModels,
  createModel,
  updateModel,
  deleteModel,
  enableModel,
  disableModel,
} from '@/api/admin/model'
import type { Provider, ProviderProtocol } from '@/types/provider'
import type { AiModel, ModelForm } from '@/types/model'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const KEY_MAX = 100

const PROTOCOL_LABEL: Record<ProviderProtocol, string> = {
  openai: 'OpenAI 兼容',
  anthropic: 'Anthropic',
}

const route = useRoute()
const router = useRouter()
const providerId = String(route.params.id)

const provider = ref<Provider | null>(null)
const models = ref<AiModel[]>([])
const loading = ref(false)

// Anthropic 协议不支持 embedding（后端 12001 兜底）；前端置灰该选项。
const embeddingDisabled = computed(() => provider.value?.protocol === 'anthropic')
const headerTitle = computed(() =>
  provider.value ? `${provider.value.name} · ${PROTOCOL_LABEL[provider.value.protocol]}` : '模型管理',
)

async function loadModels() {
  loading.value = true
  try {
    models.value = await listModels(providerId)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 后端这轮无「查单个供应商」端点：拉全量列表按 id 找。
  const all = await listProviders()
  const found = all.find((p) => p.id === providerId)
  if (!found) {
    router.replace('/404')
    return
  }
  provider.value = found
  await loadModels()
})

function goBack() {
  router.push('/admin/provider')
}

/** 危险操作二次确认；取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onEnable(row: AiModel) {
  try {
    await enableModel(row.id)
    ElMessage.success('已启用')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDisable(row: AiModel) {
  if (!(await confirmDanger(`确定禁用模型「${row.name}」？`, '禁用确认'))) return
  try {
    await disableModel(row.id)
    ElMessage.success('已禁用')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDelete(row: AiModel) {
  if (!(await confirmDanger(`确定删除模型「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await deleteModel(row.id)
    ElMessage.success('已删除')
    await loadModels()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 对话框（新增 / 编辑共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null) // null=新增，否则=编辑该 id
const formRef = ref<FormInstance>()
const form = reactive<ModelForm>({ type: 'chat', name: '', modelKey: '' })

const rules: FormRules<ModelForm> = {
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  modelKey: [
    { required: true, message: '请输入模型标识', trigger: 'blur' },
    { max: KEY_MAX, message: `模型标识不超过 ${KEY_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.type = 'chat'
  form.name = ''
  form.modelKey = ''
  dialogVisible.value = true
}

function openEdit(row: AiModel) {
  editingId.value = row.id
  form.type = row.type // 编辑时只读展示，不可改
  form.name = row.name
  form.modelKey = row.modelKey
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（见 ProviderList 同样处理）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (!form.modelKey || form.modelKey.length > KEY_MAX) return

  try {
    if (editingId.value === null) {
      await createModel(providerId, { type: form.type, name: form.name, modelKey: form.modelKey })
      ElMessage.success('模型已创建')
    } else {
      await updateModel(editingId.value, { name: form.name, modelKey: form.modelKey })
      ElMessage.success('模型已更新')
    }
    dialogVisible.value = false
    await loadModels()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开让用户改 */
  }
}
</script>

<template>
  <div class="provider-detail">
    <PageHeader :title="headerTitle" description="管理该供应商下的模型">
      <el-button data-test="back" @click="goBack">← 返回</el-button>
      <el-button type="primary" data-test="model-create-open" @click="openCreate"
        >新增模型</el-button
      >
    </PageHeader>

    <ContentCard v-if="provider">
      <el-table v-loading="loading" :data="models" data-test="model-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="modelKey" label="模型标识" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag>{{ (row as AiModel).type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as AiModel).status === 'enabled' ? 'success' : 'info'">
              {{ (row as AiModel).status === 'enabled' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as AiModel).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <div class="provider-detail__ops">
              <el-button
                :data-test="`model-edit-${(row as AiModel).id}`"
                size="small"
                @click="openEdit(row as AiModel)"
                >编辑</el-button
              >
              <el-button
                v-if="(row as AiModel).status === 'enabled'"
                :data-test="`model-disable-${(row as AiModel).id}`"
                size="small"
                @click="onDisable(row as AiModel)"
                >禁用</el-button
              >
              <el-button
                v-else
                :data-test="`model-enable-${(row as AiModel).id}`"
                size="small"
                type="success"
                @click="onEnable(row as AiModel)"
                >启用</el-button
              >
              <el-button
                :data-test="`model-delete-${(row as AiModel).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as AiModel)"
                >删除</el-button
              >
            </div>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增模型' : '编辑模型'"
      width="480"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type" :disabled="editingId !== null" data-test="form-type">
            <el-radio value="chat">chat</el-radio>
            <el-radio value="embedding" :disabled="embeddingDisabled">embedding</el-radio>
          </el-radio-group>
          <span v-if="embeddingDisabled" class="provider-detail__hint"
            >该协议不支持 embedding 模型</span
          >
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="模型标识" prop="modelKey">
          <el-input
            v-model="form.modelKey"
            data-test="form-modelkey"
            maxlength="100"
            placeholder="如 gpt-4o"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="form-submit" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.provider-detail__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}

.provider-detail__hint {
  margin-left: $spacing-sm;
  font-size: $font-size-sm;
  color: $color-text-secondary;
}
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts`
Expected: PASS（全部用例通过）。

- [ ] **Step 5: 跑类型检查与全量测试**

Run: `pnpm typecheck && pnpm test`
Expected: typecheck 无报错；全量 vitest 全绿。

- [ ] **Step 6: 追加自检并提交**

向 `docs/self-check.md` 追加（详情页数据流、anthropic embedding 禁用、CRUD/启停删流程、空态、404 兜底）。

```bash
git add web/src/views/admin/provider/ProviderDetail.vue web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts docs/self-check.md
git commit -m "feat(web)：供应商详情页模型管理（增删改启停 + anthropic embedding 禁用）"
```

---

## 验证方式

**自动化（每个 Task 内已含）：**
- `pnpm test`（全量 vitest）全绿；`pnpm typecheck` 无报错。

**手动端到端（实现完成后，需后端 + 前端 dev 同时起）：**
1. 启动后端 server（Flyway 自动 apply，admin 账号已 seed）与前端 `pnpm dev`，用 admin 登录。
2. 进「模型提供商管理」，任选一个 **openai** 供应商点「管理模型」→ 进详情页。
3. 新增模型：type 选 chat、填名称 + 模型标识（如 `gpt-4o`）→ 列表出现该模型。
4. 重复添加同 `modelKey` → 后端 10006，弹窗保持打开并 toast「冲突」。
5. 编辑该模型改名 → 列表更新；确认 type 选项在编辑态为只读不可改。
6. 禁用 → 二次确认后状态变「禁用」；再启用 → 直接生效。
7. 删除 → 二次确认后从列表消失。
8. 返回列表，进一个 **anthropic** 供应商详情页，点新增模型 → embedding 选项置灰且有「该协议不支持 embedding 模型」提示。
9. 浏览器直接访问不存在的 `/admin/provider/999999` → 跳「页面不存在」。

## Self-Review 记录

- **Spec 覆盖**：§1 路由/入口→Task 2；§2 数据流（listProviders 找 id、404）→Task 3 onMounted；§3 表格列→Task 3 模板；§4 表单（type 只读、anthropic 禁 embedding、校验）→Task 3；§5 启停删→Task 3；§6 API/类型→Task 1；§7 测试→各 Task 的 spec。无遗漏。
- **占位符**：无 TBD/TODO，所有代码完整。
- **类型/签名一致**：`listModels(providerId)`、`createModel(providerId, ModelForm)`、`updateModel(id, Pick<ModelForm,'name'|'modelKey'>)`、`deleteModel/enableModel/disableModel(id)` 在 Task 1 定义、Task 3 消费一致；`data-test` 命名（`manage-`/`model-edit-`/`model-disable-`/`model-enable-`/`model-delete-`/`form-type`/`form-modelkey`）前后一致。
