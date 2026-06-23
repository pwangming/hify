# Provider 列表页（mock）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Admin「模型提供商管理」列表页（展示/新增/编辑/删除），基于内存 mock 数据。

**Architecture:** 三层对齐现有 UserList 范本：`types/provider.ts` 定义模型，`api/admin/provider.ts` 作为内存 mock「后端」（将来换真 API 只改此文件），`views/admin/provider/ProviderList.vue` 渲染。确认弹窗用内联 `confirmDanger`（包 `ElMessageBox.confirm`），不新建 useConfirm。

**Tech Stack:** Vue 3 `<script setup lang="ts">` + Element Plus + Vitest + @vue/test-utils + happy-dom。

## Global Constraints

- 所有命令在 `web/` 目录下执行（`cd web` 后运行 `pnpm ...`）。
- `id` 为 string（后端 Long 序列化约定）。
- `apiKey` 只进表单，绝不出现在列表 `Provider` 类型中。
- 编辑时 `apiKey` 传空字符串表示「不修改」。
- 测试用 `vi.mock('@/api/admin/provider')` 打桩 api，组件测试不依赖 mock 数组内部实现。
- el-table 在 happy-dom 下需补 `ResizeObserver` 桩（见 UserList.spec）。
- 代码风格、命名、`data-test` 约定一律向 `UserList.vue` / `UserList.spec.ts` 对齐。

---

### Task 1: 类型层

**Files:**
- Create: `web/src/types/provider.ts`

**Interfaces:**
- Produces: `ProviderType`、`Provider`、`ProviderForm`（供 Task 2/3 使用）。

- [ ] **Step 1: 写类型文件**

```ts
// web/src/types/provider.ts

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
```

- [ ] **Step 2: 类型检查**

Run: `cd web && pnpm typecheck`
Expected: PASS（无报错）

- [ ] **Step 3: Commit**

```bash
git add web/src/types/provider.ts
git commit -m "前端：新增 Provider 类型定义"
```

---

### Task 2: Mock api 层

**Files:**
- Create: `web/src/api/admin/provider.ts`

**Interfaces:**
- Consumes: `Provider`、`ProviderForm`（Task 1）。
- Produces:
  - `listProviders(): Promise<Provider[]>`
  - `createProvider(body: ProviderForm): Promise<Provider>`
  - `updateProvider(id: string, body: ProviderForm): Promise<Provider>`
  - `deleteProvider(id: string): Promise<void>`

- [ ] **Step 1: 写 mock api**

```ts
// web/src/api/admin/provider.ts
import type { Provider, ProviderForm } from '@/types/provider'

// 内存 mock「后端」。将来接真后端只改本文件：把下面函数体换成 request.get/post/... 即可，组件零改动。
let providers: Provider[] = [
  { id: '1', name: 'OpenAI 官方', type: 'openai', baseUrl: 'https://api.openai.com/v1', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '2', name: '通义千问（OpenAI 兼容）', type: 'openai', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', status: 'enabled', createTime: '2026-06-21T11:20:00+08:00' },
  { id: '3', name: 'Anthropic Claude', type: 'claude', baseUrl: 'https://api.anthropic.com', status: 'enabled', createTime: '2026-06-22T09:05:00+08:00' },
  { id: '4', name: 'Google Gemini', type: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta', status: 'disabled', createTime: '2026-06-22T15:40:00+08:00' },
  { id: '5', name: '本地 Ollama', type: 'ollama', baseUrl: 'http://127.0.0.1:11434/v1', status: 'disabled', createTime: '2026-06-23T08:30:00+08:00' },
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
  const updated: Provider = { ...providers[idx], name: body.name, type: body.type, baseUrl: body.baseUrl }
  providers = providers.map((p) => (p.id === id ? updated : p))
  return Promise.resolve({ ...updated })
}

/** 删除提供商。 */
export function deleteProvider(id: string): Promise<void> {
  providers = providers.filter((p) => p.id !== id)
  return Promise.resolve()
}
```

- [ ] **Step 2: 类型检查**

Run: `cd web && pnpm typecheck`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add web/src/api/admin/provider.ts
git commit -m "前端：新增 Provider mock api 层（内存增删改查）"
```

---

### Task 3: ProviderList 组件 + 测试（TDD）

**Files:**
- Create: `web/src/views/admin/provider/__tests__/ProviderList.spec.ts`
- Modify（替换 stub）: `web/src/views/admin/provider/ProviderList.vue`

**Interfaces:**
- Consumes: Task 1 类型、Task 2 api 函数、`@/utils/datetime` 的 `formatDateTime`、`@/components/PageHeader.vue`、`@/components/ContentCard.vue`。

- [ ] **Step 1: 写失败测试**

```ts
// web/src/views/admin/provider/__tests__/ProviderList.spec.ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { listProviders, createProvider, updateProvider, deleteProvider } from '@/api/admin/provider'
import type { Provider } from '@/types/provider'
import ProviderList from '@/views/admin/provider/ProviderList.vue'

vi.mock('@/api/admin/provider', () => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  deleteProvider: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: Provider[] = [
  { id: '1', name: 'OpenAI 官方', type: 'openai', baseUrl: 'https://api.openai.com/v1', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '3', name: 'Anthropic Claude', type: 'claude', baseUrl: 'https://api.anthropic.com', status: 'enabled', createTime: '2026-06-22T09:05:00+08:00' },
]

describe('ProviderList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProviders).mockResolvedValue(SAMPLE)
  })

  it('挂载时拉取提供商并渲染各行与类型标签', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listProviders).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('OpenAI 官方')
    expect(wrapper.text()).toContain('Anthropic Claude')
    expect(wrapper.text()).toContain('OpenAI')
    expect(wrapper.text()).toContain('Claude')
  })

  it('点新增弹出对话框', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="form-name"]').exists()).toBe(true)
  })

  it('新建表单：名称为空时拦截，不调 createProvider', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createProvider).not.toHaveBeenCalled()
  })

  it('新建成功：调 createProvider 后重拉列表', async () => {
    vi.mocked(createProvider).mockResolvedValue({
      id: '9', name: 'New', type: 'openai', baseUrl: 'https://x.com/v1', status: 'enabled', createTime: '2026-06-24T08:00:00+08:00',
    })
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listProviders).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('New')
    await wrapper.get('[data-test="form-apikey"]').setValue('sk-xxx')
    await wrapper.get('[data-test="form-baseurl"]').setValue('https://x.com/v1')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()

    expect(createProvider).toHaveBeenCalledWith({ name: 'New', type: 'openai', apiKey: 'sk-xxx', baseUrl: 'https://x.com/v1' })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('点编辑：弹窗预填名称且 apiKey 留空', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe('OpenAI 官方')
    expect((wrapper.get('[data-test="form-apikey"]').element as HTMLInputElement).value).toBe('')
  })

  it('编辑成功：调 updateProvider(id, body) 后重拉', async () => {
    vi.mocked(updateProvider).mockResolvedValue({
      id: '1', name: 'OpenAI 改名', type: 'openai', baseUrl: 'https://api.openai.com/v1', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00',
    })
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('OpenAI 改名')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateProvider).toHaveBeenCalledWith('1', { name: 'OpenAI 改名', type: 'openai', apiKey: '', baseUrl: 'https://api.openai.com/v1' })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('删除：确认后调 deleteProvider 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(deleteProvider).mockResolvedValue()
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteProvider).toHaveBeenCalledWith('1')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('删除：取消则不调 deleteProvider', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteProvider).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd web && pnpm test src/views/admin/provider`
Expected: FAIL（ProviderList 仍是 stub，找不到 `data-test` 元素 / 不调 api）

- [ ] **Step 3: 替换 ProviderList.vue**

```vue
<!-- web/src/views/admin/provider/ProviderList.vue -->
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
} from '@/api/admin/provider'
import type { Provider, ProviderForm, ProviderType } from '@/types/provider'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50

// 类型 → 展示标签 / el-tag 颜色
const TYPE_LABEL: Record<ProviderType, string> = {
  openai: 'OpenAI',
  claude: 'Claude',
  gemini: 'Gemini',
  ollama: 'Ollama',
}
const TYPE_TAG: Record<ProviderType, '' | 'success' | 'warning' | 'info'> = {
  openai: '',
  claude: 'success',
  gemini: 'warning',
  ollama: 'info',
}

const providers = ref<Provider[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    providers.value = await listProviders()
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 危险操作二次确认；取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onDelete(row: Provider) {
  if (!(await confirmDanger(`确定删除提供商「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一处理（mock 期无网络错误） */
  }
}

// —— 对话框（新增 / 编辑共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null) // null=新增，否则=编辑该 id
const formRef = ref<FormInstance>()
const form = reactive<ProviderForm>({ name: '', type: 'openai', apiKey: '', baseUrl: '' })

const rules: FormRules<ProviderForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: 'Base URL 需以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.type = 'openai'
  form.apiKey = ''
  form.baseUrl = ''
  dialogVisible.value = true
}

function openEdit(row: Provider) {
  editingId.value = row.id
  form.name = row.name
  form.type = row.type
  form.apiKey = '' // 不回显密钥；留空表示不修改
  form.baseUrl = row.baseUrl
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（见 UserList 同样处理）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (!form.type) return
  if (!/^https?:\/\//.test(form.baseUrl)) return
  // 新增时 apiKey 必填；编辑可空（表示不修改）
  if (editingId.value === null && !form.apiKey) return

  if (editingId.value === null) {
    await createProvider({ ...form })
    ElMessage.success('提供商已创建')
  } else {
    await updateProvider(editingId.value, { ...form })
    ElMessage.success('提供商已更新')
  }
  dialogVisible.value = false
  await load()
}
</script>

<template>
  <div class="provider-list">
    <PageHeader title="模型提供商管理" description="配置模型供应商接入信息">
      <el-button type="primary" data-test="create-open" @click="openCreate">新增提供商</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="providers" data-test="provider-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default="{ row }">
            <el-tag :type="TYPE_TAG[(row as Provider).type]">{{ TYPE_LABEL[(row as Provider).type] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" />
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as Provider).status === 'enabled' ? 'success' : 'info'">
              {{ (row as Provider).status === 'enabled' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as Provider).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <div class="provider-list__ops">
              <el-button :data-test="`edit-${(row as Provider).id}`" size="small" @click="openEdit(row as Provider)">编辑</el-button>
              <el-button :data-test="`delete-${(row as Provider).id}`" size="small" type="danger" @click="onDelete(row as Provider)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-dialog v-model="dialogVisible" :title="editingId === null ? '新增提供商' : '编辑提供商'" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" data-test="form-type">
            <el-option label="OpenAI" value="openai" />
            <el-option label="Claude" value="claude" />
            <el-option label="Gemini" value="gemini" />
            <el-option label="Ollama" value="ollama" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            data-test="form-apikey"
            :placeholder="editingId === null ? '请输入 API Key' : '留空表示不修改'"
          />
        </el-form-item>
        <el-form-item label="Base URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" data-test="form-baseurl" placeholder="https://..." />
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
.provider-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}
</style>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd web && pnpm test src/views/admin/provider`
Expected: PASS（8 个用例全绿）

- [ ] **Step 5: 全量校验**

Run: `cd web && pnpm typecheck && pnpm test && pnpm lint`
Expected: 三者均 PASS

- [ ] **Step 6: Commit**

```bash
git add web/src/views/admin/provider/
git commit -m "前端：ProviderList 列表页（mock 数据，含新增/编辑/删除）"
```

---

## Self-Review

**Spec coverage:**
- 列表列（名称/类型/Base URL/状态 el-tag/创建时间/操作）→ Task 3 表格列 ✓
- 顶部标题/描述/新增按钮 → PageHeader + create-open 按钮 ✓
- 新增弹窗 + 字段（名称/类型下拉/API Key/Base URL）→ el-dialog 表单 ✓
- 删除用确认 → 内联 confirmDanger ✓（spec 已确认不用 useConfirm）
- 5 条 mock、类型分布开 → Task 2 数组（openai×2/claude/gemini/ollama）✓
- 编辑操作 → openEdit + updateProvider ✓
- mock 放 api 层 → Task 2 ✓
- TDD 先写失败测试 → Task 3 Step 1-2 ✓

**Placeholder scan:** 无 TBD/TODO；所有步骤含完整代码与命令。

**Type consistency:** `listProviders/createProvider/updateProvider/deleteProvider` 签名在 Task 2 定义、Task 3 测试与组件一致；`ProviderForm` 字段 `{name,type,apiKey,baseUrl}` 全程一致；`updateProvider(id, body)` 双参一致。
