# 前端 ProviderList 切真后端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Admin 模型供应商列表页从内存 mock 切到真实后端 6 端点，并对齐 `protocol`/`apiKeyTail` 契约、新增 API Key 掩码列。

**Architecture:** 纯前端改动，4 个紧耦合文件一次性改：类型层(`type`→`protocol`、加 `apiKeyTail`)→ api 层(mock 换 `request` 薄函数)→ 页面层(标签/列/下拉/错误处理)→ vitest 测试。`request.ts` 拦截器已解包 `Result` 并全局 toast 错误，api 层只写薄函数。

**Tech Stack:** Vue 3 `<script setup>` + TypeScript + Element Plus + Vitest + @vue/test-utils + pnpm。

## Global Constraints

- 纯前端，**不动后端**。后端 6 端点已上线：`/api/v1/admin/provider/providers`（GET 列表 / POST 创建 / PUT `{id}` / DELETE `{id}` / POST `{id}/enable` / POST `{id}/disable`）。
- `protocol` 仅 `openai` / `anthropic` 两值；展示标签 `openai`→「OpenAI 兼容」、`anthropic`→「Anthropic」。
- `apiKeyTail` 为明文后 4 位；列表掩码显示为 `••••` + 后 4 位。
- api 层照 `web/src/api/admin/user.ts` 写薄函数（`request.get/post/put/delete` + `const BASE`，`baseURL` 已含 `/api/v1`）。enable/disable/delete 返回 `void`。
- 错误处理全走 `request.ts` 既有拦截器；组件只在 `submitForm` 用 try/catch 保证失败时弹窗不关。
- TDD：先改测试到红，再改实现到绿。测试在 `__tests__/`。
- 不做：分页/搜索/排序、详情页、连通性校验。
- 命令在 `web/` 目录执行：`cd /home/wang/playlab/hify/web`。

---

### Task 1: 4 文件一次性切真后端（test-first）

**Files:**
- Test: `web/src/views/admin/provider/__tests__/ProviderList.spec.ts`（改）
- Modify: `web/src/types/provider.ts`
- Modify: `web/src/api/admin/provider.ts`
- Modify: `web/src/views/admin/provider/ProviderList.vue`

**Interfaces:**
- Consumes: `web/src/api/request.ts` 的 `request.get/post/put/delete`（已解包 `Result`，失败 reject `ApiError` 并全局 toast）。
- Produces（最终契约）：
  - `ProviderProtocol = 'openai' | 'anthropic'`
  - `Provider { id, name, protocol, baseUrl, status, apiKeyTail, createTime }`
  - `ProviderForm { name, protocol, apiKey, baseUrl }`
  - api：`listProviders()=>Promise<Provider[]>`、`createProvider(body)=>Promise<Provider>`、`updateProvider(id,body)=>Promise<Provider>`、`enableProvider(id)=>Promise<void>`、`disableProvider(id)=>Promise<void>`、`deleteProvider(id)=>Promise<void>`

- [ ] **Step 1: 改测试到红（新契约 + 新断言）**

整体替换 `web/src/views/admin/provider/__tests__/ProviderList.spec.ts` 为：
```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  enableProvider,
  disableProvider,
  deleteProvider,
} from '@/api/admin/provider'
import type { Provider } from '@/types/provider'
import ProviderList from '@/views/admin/provider/ProviderList.vue'

vi.mock('@/api/admin/provider', () => ({
  listProviders: vi.fn(),
  createProvider: vi.fn(),
  updateProvider: vi.fn(),
  enableProvider: vi.fn(),
  disableProvider: vi.fn(),
  deleteProvider: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: Provider[] = [
  {
    id: '1',
    name: 'OpenAI 官方',
    protocol: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    status: 'enabled',
    apiKeyTail: '7890',
    createTime: '2026-06-20T10:00:00+08:00',
  },
  {
    id: '3',
    name: 'Anthropic Claude',
    protocol: 'anthropic',
    baseUrl: 'https://api.anthropic.com',
    status: 'enabled',
    apiKeyTail: 'wxyz',
    createTime: '2026-06-22T09:05:00+08:00',
  },
]

describe('ProviderList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listProviders).mockResolvedValue(SAMPLE)
  })

  it('挂载时拉取提供商并渲染协议标签与 API Key 掩码', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listProviders).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('OpenAI 官方')
    expect(wrapper.text()).toContain('Anthropic Claude')
    expect(wrapper.text()).toContain('OpenAI 兼容') // 协议标签
    expect(wrapper.text()).toContain('Anthropic')
    expect(wrapper.text()).toContain('••••7890') // API Key 掩码列
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

  it('新建成功：调 createProvider(body 带 protocol) 后重拉列表', async () => {
    vi.mocked(createProvider).mockResolvedValue({
      id: '9',
      name: 'New',
      protocol: 'openai',
      baseUrl: 'https://x.com/v1',
      status: 'enabled',
      apiKeyTail: 'xxx0',
      createTime: '2026-06-24T08:00:00+08:00',
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

    expect(createProvider).toHaveBeenCalledWith({
      name: 'New',
      protocol: 'openai',
      apiKey: 'sk-xxx',
      baseUrl: 'https://x.com/v1',
    })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('点编辑：弹窗预填名称且 apiKey 留空', async () => {
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-test="form-name"]').element as HTMLInputElement).value).toBe(
      'OpenAI 官方',
    )
    expect((wrapper.get('[data-test="form-apikey"]').element as HTMLInputElement).value).toBe('')
  })

  it('编辑成功：调 updateProvider(id, body 带 protocol) 后重拉', async () => {
    vi.mocked(updateProvider).mockResolvedValue({
      id: '1',
      name: 'OpenAI 改名',
      protocol: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      status: 'enabled',
      apiKeyTail: '7890',
      createTime: '2026-06-20T10:00:00+08:00',
    })
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="edit-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="form-name"]').setValue('OpenAI 改名')
    await wrapper.get('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateProvider).toHaveBeenCalledWith('1', {
      name: 'OpenAI 改名',
      protocol: 'openai',
      apiKey: '',
      baseUrl: 'https://api.openai.com/v1',
    })
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('删除：确认后调 deleteProvider 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(deleteProvider).mockResolvedValue(undefined)
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

  it('启用行显示「禁用」按钮：确认后调 disableProvider 并重拉', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(disableProvider).mockResolvedValue(undefined)
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="enable-1"]').exists()).toBe(false)
    await wrapper.get('[data-test="disable-1"]').trigger('click')
    await flushPromises()
    expect(disableProvider).toHaveBeenCalledWith('1')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })

  it('禁用行显示「启用」按钮：直接调 enableProvider（无需确认）', async () => {
    vi.mocked(listProviders).mockResolvedValue([
      {
        id: '4',
        name: '通义千问',
        protocol: 'openai',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        status: 'disabled',
        apiKeyTail: '4321',
        createTime: '2026-06-22T15:40:00+08:00',
      },
    ])
    vi.mocked(enableProvider).mockResolvedValue(undefined)
    const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="disable-4"]').exists()).toBe(false)
    await wrapper.get('[data-test="enable-4"]').trigger('click')
    await flushPromises()
    expect(enableProvider).toHaveBeenCalledWith('4')
    expect(listProviders).toHaveBeenCalledTimes(2)
  })
})
```

- [ ] **Step 2: 跑测试，确认红**

Run: `cd /home/wang/playlab/hify/web && pnpm test ProviderList`
Expected: 编译/类型错误（`Provider.protocol`/`apiKeyTail` 尚不存在）或断言失败（无「OpenAI 兼容」「••••7890」）。

- [ ] **Step 3: 改类型层**

整体替换 `web/src/types/provider.ts` 为：
```ts
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
}

/** 创建/编辑共用请求体（对齐后端 Create/UpdateProviderRequest）。编辑时 apiKey 为空表示不修改。 */
export interface ProviderForm {
  name: string
  protocol: ProviderProtocol
  apiKey: string
  baseUrl: string
}
```

- [ ] **Step 4: 改 api 层（mock → 真实 request）**

整体替换 `web/src/api/admin/provider.ts` 为：
```ts
import { request } from '@/api/request'
import type { Provider, ProviderForm } from '@/types/provider'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/provider/providers'

/** 列出全部提供商。后端：GET /api/v1/admin/provider/providers */
export function listProviders() {
  return request.get<Provider[]>(BASE)
}
/** 新建提供商。后端：POST /api/v1/admin/provider/providers */
export function createProvider(body: ProviderForm) {
  return request.post<Provider>(BASE, body)
}
/** 编辑提供商（全量；apiKey 留空=不改密钥）。后端：PUT .../{id} */
export function updateProvider(id: string, body: ProviderForm) {
  return request.put<Provider>(`${BASE}/${id}`, body)
}
/** 启用提供商。后端：POST .../{id}/enable */
export function enableProvider(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}
/** 禁用提供商。后端：POST .../{id}/disable */
export function disableProvider(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}
/** 删除提供商（逻辑删除）。后端：DELETE .../{id} */
export function deleteProvider(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}
```

- [ ] **Step 5: 改页面层**

整体替换 `web/src/views/admin/provider/ProviderList.vue` 为：
```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listProviders,
  createProvider,
  updateProvider,
  enableProvider,
  disableProvider,
  deleteProvider,
} from '@/api/admin/provider'
import type { Provider, ProviderForm, ProviderProtocol } from '@/types/provider'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50

// 协议 → 展示标签 / el-tag 颜色。openai 覆盖 OpenAI/通义/Gemini 等兼容端点，故标「OpenAI 兼容」。
const PROTOCOL_LABEL: Record<ProviderProtocol, string> = {
  openai: 'OpenAI 兼容',
  anthropic: 'Anthropic',
}
const PROTOCOL_TAG: Record<ProviderProtocol, '' | 'success'> = {
  openai: '',
  anthropic: 'success',
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

async function onEnable(row: Provider) {
  try {
    await enableProvider(row.id)
    ElMessage.success('已启用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDisable(row: Provider) {
  if (!(await confirmDanger(`确定禁用提供商「${row.name}」？`, '禁用确认'))) return
  try {
    await disableProvider(row.id)
    ElMessage.success('已禁用')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onDelete(row: Provider) {
  if (!(await confirmDanger(`确定删除提供商「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 对话框（新增 / 编辑共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null) // null=新增，否则=编辑该 id
const formRef = ref<FormInstance>()
const form = reactive<ProviderForm>({ name: '', protocol: 'openai', apiKey: '', baseUrl: '' })

const rules: FormRules<ProviderForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
  protocol: [{ required: true, message: '请选择协议', trigger: 'change' }],
  baseUrl: [
    { required: true, message: '请输入 Base URL', trigger: 'blur' },
    { pattern: /^https?:\/\//, message: 'Base URL 需以 http:// 或 https:// 开头', trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.protocol = 'openai'
  form.apiKey = ''
  form.baseUrl = ''
  dialogVisible.value = true
}

function openEdit(row: Provider) {
  editingId.value = row.id
  form.name = row.name
  form.protocol = row.protocol
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
  if (!form.protocol) return
  if (!/^https?:\/\//.test(form.baseUrl)) return
  // 新增时 apiKey 必填；编辑可空（表示不修改）
  if (editingId.value === null && !form.apiKey) return

  try {
    if (editingId.value === null) {
      await createProvider({ ...form })
      ElMessage.success('提供商已创建')
    } else {
      await updateProvider(editingId.value, { ...form })
      ElMessage.success('提供商已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开让用户改 */
  }
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
        <el-table-column label="协议">
          <template #default="{ row }">
            <el-tag :type="PROTOCOL_TAG[(row as Provider).protocol]">{{
              PROTOCOL_LABEL[(row as Provider).protocol]
            }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" />
        <el-table-column label="API Key">
          <template #default="{ row }">••••{{ (row as Provider).apiKeyTail }}</template>
        </el-table-column>
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
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <div class="provider-list__ops">
              <el-button
                v-if="(row as Provider).status === 'enabled'"
                :data-test="`disable-${(row as Provider).id}`"
                size="small"
                @click="onDisable(row as Provider)"
                >禁用</el-button
              >
              <el-button
                v-else
                :data-test="`enable-${(row as Provider).id}`"
                size="small"
                type="success"
                @click="onEnable(row as Provider)"
                >启用</el-button
              >
              <el-button
                :data-test="`edit-${(row as Provider).id}`"
                size="small"
                @click="openEdit(row as Provider)"
                >编辑</el-button
              >
              <el-button
                :data-test="`delete-${(row as Provider).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as Provider)"
                >删除</el-button
              >
            </div>
          </template>
        </el-table-column>
      </el-table>
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增提供商' : '编辑提供商'"
      width="480"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="协议" prop="protocol">
          <el-select v-model="form.protocol" data-test="form-protocol">
            <el-option label="OpenAI 兼容" value="openai" />
            <el-option label="Anthropic" value="anthropic" />
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

- [ ] **Step 6: 跑测试，确认绿**

Run: `cd /home/wang/playlab/hify/web && pnpm test ProviderList`
Expected: ProviderList 测试套件全绿（10 个用例通过）。

- [ ] **Step 7: 类型检查 + lint**

Run: `cd /home/wang/playlab/hify/web && pnpm typecheck && pnpm lint`
Expected: `vue-tsc --noEmit` 无错误；`eslint . --fix` 无残留错误（无 `type`/未用导入残留）。

- [ ] **Step 8: 全量前端测试 + 提交**

Run: `cd /home/wang/playlab/hify/web && pnpm test`
Expected: 整个前端测试套件全绿（ProviderList + 既有 UserList 等）。
```bash
cd /home/wang/playlab/hify
git add web/src/types/provider.ts web/src/api/admin/provider.ts \
        web/src/views/admin/provider/ProviderList.vue \
        web/src/views/admin/provider/__tests__/ProviderList.spec.ts
git commit -m "feat(web)：ProviderList 切真后端（protocol/apiKeyTail + API Key 列）"
```

---

### Task 2: 手动冒烟（端到端，可选但建议）

**Files:** 无（运行验证）。

- [ ] **Step 1: 起后端 + 前端**

后端：`cd /home/wang/playlab/hify/server && mvn spring-boot:run`（Postgres 须在线，Flyway 已建 model_provider 表）。
前端：另开终端 `cd /home/wang/playlab/hify/web && pnpm dev`，浏览器开 Vite 提示的地址，用 admin 账号登录后进入「模型提供商管理」。

- [ ] **Step 2: 走查**

确认：列表拉取成功、协议列显示「OpenAI 兼容/Anthropic」、API Key 列显示 `••••尾巴`；新增（含 apiKey）→ 列表出现新行；编辑改名 + apiKey 留空 → 名称变、尾巴不变；禁用/启用切换；删除消失；用已存在的名字新增 → 弹窗不关且提示「供应商名称已存在」。

---

## 完成标准（Definition of Done）

- `pnpm test` 前端全绿（ProviderList 10 用例 + 既有用例）。
- `pnpm typecheck`、`pnpm lint` 通过。
- 列表展示协议标签与 API Key 掩码；6 端点 CRUD + 启停在真实后端上生效；重名时弹窗不关并提示。

## 后续（不在本计划）

- 后端 B（ai_model）落地后补"模型管理"界面；后端 D（连通性）落地后表单加"测试连接"。
