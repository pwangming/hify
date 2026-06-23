# Admin 用户管理 · 前端页 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `/admin/identity` 实现一个用户管理页，覆盖后端 7 个接口（列表/新建/启用/禁用/重置密码/改角色/删除），先用 mock 数据跑通，最后一任务换真后端。

**Architecture:** 单文件 Vue 组件 `UserList.vue`（表格 + 内联新建对话框）依赖一个薄 API 层 `api/admin/user.ts`。API 层第一版是内存 mock 实现，便于无后端即可在浏览器中开发与验收；最后一个任务把实现替换为真正的 `request` 调用，函数签名不变、组件与测试零改动。两道护栏（不能操作自己、需保留至少一个启用管理员）在前端用列表数据提前算出并把对应按钮置灰，后端 11003 作最终兜底。

**Tech Stack:** Vue 3 `<script setup lang="ts">` + Element Plus（el-table / el-dialog / el-form / el-tag / ElMessage / ElMessageBox）+ Pinia（`useUserStore` 取当前登录用户）+ vitest + @vue/test-utils（happy-dom）。

## Global Constraints

- **id 为字符串**：后端把 Long 序列化为 string 防 JS 精度丢失；前端全程当 `string`，不做数值运算。
- **role 复用类型**：`'admin' | 'member'` 复用 `@/types/user` 的 `UserRole`，不重复定义。`status` 取值 `'enabled' | 'disabled'`（对齐后端 `UserStatus` 枚举）。
- **API 层薄**：只拼 URL + 类型；解包信封、登录失效跳转、参数校验标红由 `@/api/request` 的 `request` 拦截器统一处理（业务/网络错误已由拦截器弹 toast，组件内吞掉避免未处理拒绝）。
- **admin 专属路由**：`meta.roles: ['admin']`；后端 `hasRole('ADMIN')` 双保险。
- **危险操作**（禁用 / 删除 / 降级）一律 `ElMessageBox.confirm` 二次确认。
- **TDD**：每个行为先写失败测试，测试放各目录 `__tests__/` 下；用 `vi.mock('@/api/admin/user')` 提供 mock 数据。
- **不新增依赖**：仅用 package.json 现有库。
- **测试命令**：`pnpm -C web run test`（vitest run，全量）；单文件 `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`。
- **el-table 需 ResizeObserver 桩**：happy-dom 未实现，测试文件顶部补桩（见 Task 1）。

---

## 文件结构

| 文件 | 职责 | 涉及任务 |
|---|---|---|
| `web/src/types/admin-user.ts` | 类型：`AdminUser`、`CreateUserRequest` | T1 创建 |
| `web/src/api/admin/user.ts` | API 层 7 函数（先 mock 实现，T5 换真调用） | T1 创建，T5 改写 |
| `web/src/views/admin/identity/UserList.vue` | 页面：表格 + 护栏 + 新建对话框 + 行动作 | T1 创建，T2/T3/T4 增补 |
| `web/src/views/admin/identity/__tests__/UserList.spec.ts` | 组件测试 | T1 创建，T2/T3/T4 增补 |
| `web/src/api/admin/__tests__/user.spec.ts` | 真 API 层契约测试 | T5 创建 |
| `web/src/router/index.ts` | 增一条 `/admin/identity` 路由 | T1 修改 |

---

## Task 1: 类型 + mock API + 列表表格 + 路由

**Files:**
- Create: `web/src/types/admin-user.ts`
- Create: `web/src/api/admin/user.ts`
- Create: `web/src/views/admin/identity/UserList.vue`
- Create: `web/src/views/admin/identity/__tests__/UserList.spec.ts`
- Modify: `web/src/router/index.ts`

**Interfaces:**
- Produces:
  - `interface AdminUser { id: string; username: string; role: UserRole; status: 'enabled'|'disabled'; createTime: string }`
  - `interface CreateUserRequest { username: string; password: string; role: UserRole }`
  - `listUsers(): Promise<AdminUser[]>`、`createUser(body: CreateUserRequest): Promise<AdminUser>`、`enableUser(id: string): Promise<AdminUser>`、`disableUser(id: string): Promise<AdminUser>`、`resetPassword(id: string, password: string): Promise<void>`、`changeRole(id: string, role: UserRole): Promise<AdminUser>`、`deleteUser(id: string): Promise<void>`

- [ ] **Step 1: 写类型文件**

`web/src/types/admin-user.ts`：

```ts
import type { UserRole } from './user'

/** admin 用户管理视图，对应后端 UserView。id 为 string（Long 序列化防精度丢失）。 */
export interface AdminUser {
  id: string
  username: string
  role: UserRole
  status: 'enabled' | 'disabled'
  createTime: string
}

/** 新建用户请求体。校验规则对齐后端 CreateUserRequest。 */
export interface CreateUserRequest {
  username: string
  password: string
  role: UserRole
}
```

- [ ] **Step 2: 写 mock API 层**

`web/src/api/admin/user.ts`（临时内存实现，T5 换真调用）：

```ts
import type { AdminUser, CreateUserRequest } from '@/types/admin-user'
import type { UserRole } from '@/types/user'

// —— 临时 mock 实现：先用 mock 数据把前端页跑通，最后一轮（T5）换成真后端 request 调用 ——
// 模块级可变数组：增删改会反映到后续 listUsers，浏览器里手动操作能看到一致变化。
let mockUsers: AdminUser[] = [
  { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
  { id: '3', username: 'bob', role: 'member', status: 'disabled', createTime: '2026-06-22T14:15:00+08:00' },
]
let nextId = 4

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), 150))
}
function find(id: string): AdminUser {
  const u = mockUsers.find((x) => x.id === id)
  if (!u) throw new Error(`mock: user ${id} not found`)
  return u
}

export function listUsers(): Promise<AdminUser[]> {
  return delay(mockUsers.map((u) => ({ ...u })))
}
export function createUser(body: CreateUserRequest): Promise<AdminUser> {
  const u: AdminUser = {
    id: String(nextId++),
    username: body.username,
    role: body.role,
    status: 'enabled',
    createTime: new Date().toISOString(),
  }
  mockUsers = [u, ...mockUsers]
  return delay({ ...u })
}
export function enableUser(id: string): Promise<AdminUser> {
  const u = find(id)
  u.status = 'enabled'
  return delay({ ...u })
}
export function disableUser(id: string): Promise<AdminUser> {
  const u = find(id)
  u.status = 'disabled'
  return delay({ ...u })
}
export function resetPassword(id: string, _password: string): Promise<void> {
  find(id)
  return delay(undefined)
}
export function changeRole(id: string, role: UserRole): Promise<AdminUser> {
  const u = find(id)
  u.role = role
  return delay({ ...u })
}
export function deleteUser(id: string): Promise<void> {
  mockUsers = mockUsers.filter((x) => x.id !== id)
  return delay(undefined)
}
```

- [ ] **Step 3: 写失败测试（表格渲染）**

`web/src/views/admin/identity/__tests__/UserList.spec.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listUsers } from '@/api/admin/user'
import type { AdminUser } from '@/types/admin-user'
import UserList from '@/views/admin/identity/UserList.vue'

// 整个 API 层用 mock，组件测试只验证组件行为（不碰真实现/后端）
vi.mock('@/api/admin/user', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
  resetPassword: vi.fn(),
  changeRole: vi.fn(),
  deleteUser: vi.fn(),
}))

// el-table 依赖 ResizeObserver，happy-dom 未实现，补桩
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
} as unknown as typeof ResizeObserver

const SAMPLE: AdminUser[] = [
  { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
  { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
]

describe('UserList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listUsers).mockResolvedValue(SAMPLE)
  })

  it('挂载时拉取用户并渲染各行', async () => {
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listUsers).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('alice')
  })
})
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: FAIL —— `UserList.vue` 尚不存在（Cannot find module）。

- [ ] **Step 5: 写组件（表格骨架）**

`web/src/views/admin/identity/UserList.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listUsers } from '@/api/admin/user'
import type { AdminUser } from '@/types/admin-user'

const users = ref<AdminUser[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div class="user-list">
    <div class="user-list__header">
      <h2>用户管理</h2>
    </div>
    <el-table v-loading="loading" :data="users" data-test="user-table">
      <el-table-column prop="username" label="用户名" />
      <el-table-column label="角色">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
            {{ row.role === 'admin' ? '管理员' : '成员' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.status === 'enabled' ? 'success' : 'info'">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" />
    </el-table>
  </div>
</template>

<style scoped lang="scss">
.user-list__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: PASS。

- [ ] **Step 7: 注册路由**

`web/src/router/index.ts`：照抄 provider 路由模式，在 provider 那条之后加：

```ts
  {
    path: '/admin/identity',
    name: 'UserList',
    component: () => import('@/views/admin/identity/UserList.vue'),
    meta: { requiresAuth: true, roles: ['admin'], title: '用户管理', menu: true },
  },
```

- [ ] **Step 8: 类型检查 + 提交**

Run: `pnpm -C web run typecheck`
Expected: 无错误。

```bash
git add web/src/types/admin-user.ts web/src/api/admin/user.ts web/src/views/admin/identity/UserList.vue web/src/views/admin/identity/__tests__/UserList.spec.ts web/src/router/index.ts
git commit -m "前端：admin 用户管理页——类型/mock API/列表表格/路由（先用 mock 数据）"
```

---

## Task 2: 操作列 + 双护栏置灰

**Files:**
- Modify: `web/src/views/admin/identity/UserList.vue`
- Modify: `web/src/views/admin/identity/__tests__/UserList.spec.ts`

**Interfaces:**
- Consumes: `useUserStore().user`（含 `id: string`、`role`）、`AdminUser[]`
- Produces: 模板按钮 `data-test` 约定：`disable-<id>` / `enable-<id>` / `role-<id>` / `reset-<id>` / `delete-<id>`；判定函数 `dangerDisabledReason(row): string | null`

- [ ] **Step 1: 写失败测试（护栏置灰）**

在 `UserList.spec.ts` 顶部 import 增加：

```ts
import { useUserStore } from '@/stores/user'
```

在 `describe('UserList', ...)` 内追加两个用例（当前登录用户 = id '1' 的 admin）。注意：先设当前用户再 mount。

```ts
  it('自己那一行：禁用/降级/删除按钮置灰', async () => {
    const store = useUserStore()
    store.user = { id: '1', username: 'admin', role: 'admin' }
    // 两个启用 admin，排除「最后一个 admin」护栏干扰，单测「不能操作自己」
    vi.mocked(listUsers).mockResolvedValue([
      { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
      { id: '9', username: 'carol', role: 'admin', status: 'enabled', createTime: '2026-06-21T10:00:00+08:00' },
    ])
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.get('[data-test="disable-1"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="role-1"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="delete-1"]').attributes('disabled')).toBeDefined()
    // 别人那行不置灰
    expect(wrapper.get('[data-test="disable-9"]').attributes('disabled')).toBeUndefined()
  })

  it('最后一个启用 admin：其禁用/降级/删除置灰', async () => {
    const store = useUserStore()
    store.user = { id: '2', username: 'alice', role: 'admin' } // 当前用户是别人，排除「自己」护栏
    vi.mocked(listUsers).mockResolvedValue([
      { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
      { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
    ])
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    // id '1' 是唯一启用 admin
    expect(wrapper.get('[data-test="disable-1"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="delete-1"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="role-1"]').attributes('disabled')).toBeDefined()
  })
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: FAIL —— 找不到 `[data-test="disable-1"]`（操作列尚未实现）。

- [ ] **Step 3: 组件增补护栏逻辑与操作列**

`UserList.vue` `<script setup>` 增补（import 与 computed/helper）：

```ts
import { computed, onMounted, ref } from 'vue'
import { useUserStore } from '@/stores/user'
import type { AdminUser } from '@/types/admin-user'

const userStore = useUserStore()

const enabledAdminCount = computed(
  () => users.value.filter((u) => u.role === 'admin' && u.status === 'enabled').length,
)
function isSelf(row: AdminUser): boolean {
  return row.id === userStore.user?.id
}
function isLastEnabledAdmin(row: AdminUser): boolean {
  return row.role === 'admin' && row.status === 'enabled' && enabledAdminCount.value <= 1
}
/** 危险操作（禁用/降级/删除）是否禁用 + 原因；null 表示放行。 */
function dangerDisabledReason(row: AdminUser): string | null {
  if (isSelf(row)) return '不能对自己做此操作'
  if (isLastEnabledAdmin(row)) return '需至少保留一个启用的管理员'
  return null
}
```

模板里 `createTime` 列后增加操作列（动作处理函数 T4 接线，本任务先放占位 `@click` 不接 API，仅为渲染按钮与置灰）：

```vue
      <el-table-column label="操作" width="320">
        <template #default="{ row }">
          <el-tooltip
            v-if="dangerDisabledReason(row) && row.status === 'enabled'"
            :content="dangerDisabledReason(row)!"
          >
            <span>
              <el-button :data-test="`disable-${row.id}`" size="small" disabled>停用</el-button>
            </span>
          </el-tooltip>
          <el-button
            v-else-if="row.status === 'enabled'"
            :data-test="`disable-${row.id}`"
            size="small"
            @click="onDisable(row)"
          >停用</el-button>
          <el-button
            v-else
            :data-test="`enable-${row.id}`"
            size="small"
            type="success"
            @click="onEnable(row)"
          >启用</el-button>

          <el-button
            :data-test="`role-${row.id}`"
            size="small"
            :disabled="!!dangerDisabledReason(row)"
            @click="onChangeRole(row)"
          >{{ row.role === 'admin' ? '降为成员' : '升为管理员' }}</el-button>

          <el-button :data-test="`reset-${row.id}`" size="small" @click="onResetPassword(row)">重置密码</el-button>

          <el-button
            :data-test="`delete-${row.id}`"
            size="small"
            type="danger"
            :disabled="!!dangerDisabledReason(row)"
            @click="onDelete(row)"
          >删除</el-button>
        </template>
      </el-table-column>
```

> 说明：停用按钮置灰用 `el-tooltip` 包一层 `<span>`（disabled 按钮不触发原生 hover 事件，需外层 span 承接 tooltip）。改角色/删除按钮直接用 `:disabled` 即可。本任务先声明 `onDisable/onEnable/onChangeRole/onResetPassword/onDelete` 为空函数占位，T4 实现：

```ts
function onDisable(_row: AdminUser) {}
function onEnable(_row: AdminUser) {}
function onChangeRole(_row: AdminUser) {}
function onResetPassword(_row: AdminUser) {}
function onDelete(_row: AdminUser) {}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: PASS（3 个用例）。

- [ ] **Step 5: 类型检查 + 提交**

Run: `pnpm -C web run typecheck`

```bash
git add web/src/views/admin/identity/UserList.vue web/src/views/admin/identity/__tests__/UserList.spec.ts
git commit -m "前端：用户管理——操作列 + 双护栏（不能操作自己/保留最后一个 admin）置灰"
```

---

## Task 3: 新建用户对话框 + 表单校验

**Files:**
- Modify: `web/src/views/admin/identity/UserList.vue`
- Modify: `web/src/views/admin/identity/__tests__/UserList.spec.ts`

**Interfaces:**
- Consumes: `createUser(body: CreateUserRequest): Promise<AdminUser>`、`listUsers()`
- Produces: `data-test` 约定：`create-open`（打开按钮）、`create-username` / `create-password` / `create-role`（表单项）、`create-submit`（提交）

- [ ] **Step 1: 写失败测试（校验拦截 + 成功新建后重拉）**

在 `UserList.spec.ts` import 增加 `createUser`：

```ts
import { listUsers, createUser } from '@/api/admin/user'
```

`describe` 内追加：

```ts
  it('新建表单：用户名为空时拦截，不调 createUser', async () => {
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    // 不填用户名直接提交
    await wrapper.get('[data-test="create-submit"]').trigger('click')
    await flushPromises()
    expect(createUser).not.toHaveBeenCalled()
  })

  it('新建成功：调 createUser 后重新拉列表', async () => {
    vi.mocked(createUser).mockResolvedValue({
      id: '5', username: 'dave', role: 'member', status: 'enabled', createTime: '2026-06-23T08:00:00+08:00',
    })
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listUsers).toHaveBeenCalledTimes(1)

    await wrapper.get('[data-test="create-open"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="create-username"] input').setValue('dave')
    await wrapper.get('[data-test="create-password"] input').setValue('secret12')
    await wrapper.get('[data-test="create-submit"]').trigger('click')
    await flushPromises()

    expect(createUser).toHaveBeenCalledWith({ username: 'dave', password: 'secret12', role: 'member' })
    expect(listUsers).toHaveBeenCalledTimes(2) // 新建后重拉
  })
```

> `create-role` 默认值为 `member`，第二个用例不改它，直接提交即可。

- [ ] **Step 2: 运行测试，确认失败**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: FAIL —— 找不到 `[data-test="create-open"]`。

- [ ] **Step 3: 组件增补新建对话框**

`UserList.vue` `<script setup>` 增补：

```ts
import { reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createUser } from '@/api/admin/user'
import type { AdminUser, CreateUserRequest } from '@/types/admin-user'

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<CreateUserRequest>({ username: '', password: '', role: 'member' })
const rules: FormRules<CreateUserRequest> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 50, message: '用户名不超过 50 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 72, message: '密码长度需为 8~72 个字符', trigger: 'blur' },
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

function openCreate() {
  form.username = ''
  form.password = ''
  form.role = 'member'
  dialogVisible.value = true
}
async function submitCreate() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  await createUser({ ...form })
  ElMessage.success('用户已创建')
  dialogVisible.value = false
  await load()
}
```

模板 `header` 内加打开按钮：

```vue
    <div class="user-list__header">
      <h2>用户管理</h2>
      <el-button type="primary" data-test="create-open" @click="openCreate">新建用户</el-button>
    </div>
```

`el-table` 之后加对话框：

```vue
    <el-dialog v-model="dialogVisible" title="新建用户" width="480">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" data-test="create-username" maxlength="50" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" data-test="create-password" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" data-test="create-role">
            <el-option label="成员" value="member" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" data-test="create-submit" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: PASS。

> 若 `[data-test="create-username"] input` 选不中：el-input 把 `data-test` 放在外层 wrapper，内层有真正的 `<input>`，故用后代选择器 ` input` 定位。提交按钮的 `data-test` 在 el-button 根元素上，直接选中即可。

- [ ] **Step 5: 类型检查 + 提交**

Run: `pnpm -C web run typecheck`

```bash
git add web/src/views/admin/identity/UserList.vue web/src/views/admin/identity/__tests__/UserList.spec.ts
git commit -m "前端：用户管理——新建用户对话框 + 表单校验（对齐后端规则），成功后重拉列表"
```

---

## Task 4: 行内动作接线（启/禁/重置/改角色/删 + 确认框）

**Files:**
- Modify: `web/src/views/admin/identity/UserList.vue`
- Modify: `web/src/views/admin/identity/__tests__/UserList.spec.ts`

**Interfaces:**
- Consumes: `enableUser/disableUser/resetPassword/changeRole/deleteUser`、`ElMessageBox.confirm`、`ElMessageBox.prompt`

- [ ] **Step 1: 写失败测试（动作调 API + 确认取消则不调）**

`UserList.spec.ts` import 增补 API 与 ElMessageBox：

```ts
import { listUsers, createUser, disableUser, enableUser, deleteUser, changeRole, resetPassword } from '@/api/admin/user'
import { ElMessageBox } from 'element-plus'
```

`describe` 内追加（当前用户设为别人，避免自己那行置灰干扰）：

```ts
  it('停用：确认后调 disableUser 并重拉', async () => {
    const store = useUserStore()
    store.user = { id: '99', username: 'root', role: 'admin' }
    vi.mocked(listUsers).mockResolvedValue([
      { id: '1', username: 'admin', role: 'admin', status: 'enabled', createTime: '2026-06-20T10:00:00+08:00' },
      { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
    ])
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(disableUser).mockResolvedValue({
      id: '2', username: 'alice', role: 'member', status: 'disabled', createTime: '2026-06-21T09:30:00+08:00',
    })
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="disable-2"]').trigger('click')
    await flushPromises()
    expect(disableUser).toHaveBeenCalledWith('2')
    expect(listUsers).toHaveBeenCalledTimes(2)
  })

  it('删除：确认框取消则不调 deleteUser', async () => {
    const store = useUserStore()
    store.user = { id: '99', username: 'root', role: 'admin' }
    vi.mocked(listUsers).mockResolvedValue([
      { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
    ])
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="delete-2"]').trigger('click')
    await flushPromises()
    expect(deleteUser).not.toHaveBeenCalled()
  })

  it('升为管理员：确认后调 changeRole(id, "admin")', async () => {
    const store = useUserStore()
    store.user = { id: '99', username: 'root', role: 'admin' }
    vi.mocked(listUsers).mockResolvedValue([
      { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
    ])
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    vi.mocked(changeRole).mockResolvedValue({
      id: '2', username: 'alice', role: 'admin', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00',
    })
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="role-2"]').trigger('click')
    await flushPromises()
    expect(changeRole).toHaveBeenCalledWith('2', 'admin')
  })

  it('重置密码：输入新密码后调 resetPassword', async () => {
    const store = useUserStore()
    store.user = { id: '99', username: 'root', role: 'admin' }
    vi.mocked(listUsers).mockResolvedValue([
      { id: '2', username: 'alice', role: 'member', status: 'enabled', createTime: '2026-06-21T09:30:00+08:00' },
    ])
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: 'newpass12', action: 'confirm' })
    vi.mocked(resetPassword).mockResolvedValue()
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="reset-2"]').trigger('click')
    await flushPromises()
    expect(resetPassword).toHaveBeenCalledWith('2', 'newpass12')
  })

  it('启用：直接调 enableUser（无需确认）', async () => {
    const store = useUserStore()
    store.user = { id: '99', username: 'root', role: 'admin' }
    vi.mocked(listUsers).mockResolvedValue([
      { id: '3', username: 'bob', role: 'member', status: 'disabled', createTime: '2026-06-22T14:15:00+08:00' },
    ])
    vi.mocked(enableUser).mockResolvedValue({
      id: '3', username: 'bob', role: 'member', status: 'enabled', createTime: '2026-06-22T14:15:00+08:00',
    })
    const wrapper = mount(UserList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.get('[data-test="enable-3"]').trigger('click')
    await flushPromises()
    expect(enableUser).toHaveBeenCalledWith('3')
  })
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: FAIL —— 占位空函数不调任何 API。

- [ ] **Step 3: 组件实现动作处理函数**

`UserList.vue` `<script setup>`：import 增补 `ElMessageBox` 与各 API 函数，删除 T2 的 5 个占位空函数，替换为：

```ts
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listUsers, createUser, enableUser, disableUser,
  resetPassword, changeRole, deleteUser,
} from '@/api/admin/user'
import type { UserRole } from '@/types/user'

/** 执行一个动作并在成功后提示 + 重拉。业务/网络错误已由 request 拦截器弹 toast，这里吞掉避免未处理拒绝。 */
async function runAction(action: () => Promise<unknown>, successMsg: string) {
  try {
    await action()
    ElMessage.success(successMsg)
    await load()
  } catch {
    /* 已由 request 拦截器统一处理 */
  }
}

/** 危险操作二次确认；用户取消返回 false。 */
async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function onDisable(row: AdminUser) {
  if (!(await confirmDanger(`确定停用用户「${row.username}」？`, '停用确认'))) return
  await runAction(() => disableUser(row.id), '已停用')
}
async function onEnable(row: AdminUser) {
  await runAction(() => enableUser(row.id), '已启用')
}
async function onChangeRole(row: AdminUser) {
  const target: UserRole = row.role === 'admin' ? 'member' : 'admin'
  const label = target === 'admin' ? '管理员' : '成员'
  if (!(await confirmDanger(`确定将「${row.username}」改为${label}？`, '改角色确认'))) return
  await runAction(() => changeRole(row.id, target), '角色已修改')
}
async function onResetPassword(row: AdminUser) {
  try {
    const { value } = await ElMessageBox.prompt(`为用户「${row.username}」设置新密码`, '重置密码', {
      inputType: 'password',
      inputPattern: /^.{8,72}$/,
      inputErrorMessage: '密码长度需为 8~72 个字符',
    })
    await runAction(() => resetPassword(row.id, value), '密码已重置')
  } catch {
    /* 取消 */
  }
}
async function onDelete(row: AdminUser) {
  if (!(await confirmDanger(`确定删除用户「${row.username}」？此操作不可恢复。`, '删除确认'))) return
  await runAction(() => deleteUser(row.id), '已删除')
}
```

> 注意：确保 `<script setup>` 顶部把 T1 的 `import { listUsers } from '@/api/admin/user'` 合并进这条多函数 import，避免重复 import 同一模块；`createUser` 已在 T3 引入，一并合并到这条 import。

- [ ] **Step 4: 运行测试，确认通过**

Run: `pnpm -C web run test -- src/views/admin/identity/__tests__/UserList.spec.ts`
Expected: PASS（全部用例）。

- [ ] **Step 5: 全量测试 + 类型检查 + 提交**

Run: `pnpm -C web run typecheck && pnpm -C web run test`
Expected: 全绿。

```bash
git add web/src/views/admin/identity/UserList.vue web/src/views/admin/identity/__tests__/UserList.spec.ts
git commit -m "前端：用户管理——行内动作接线（启/禁/重置/改角色/删）+ 危险操作二次确认"
```

---

## Task 5: mock API 换真后端调用 + 契约测试

**Files:**
- Modify: `web/src/api/admin/user.ts`（mock 实现整体替换为真 `request` 调用）
- Create: `web/src/api/admin/__tests__/user.spec.ts`

**Interfaces:**
- Consumes: `@/api/request` 的 `request`（`get/post/put/delete<T>`）
- Produces: 函数签名与 T1 完全一致（组件与组件测试零改动）

- [ ] **Step 1: 写失败测试（API 层契约：URL/方法/body）**

`web/src/api/admin/__tests__/user.spec.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listUsers, createUser, enableUser, disableUser,
  resetPassword, changeRole, deleteUser,
} from '@/api/admin/user'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const BASE = '/admin/identity/users'

describe('admin user api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listUsers → GET /admin/identity/users', () => {
    listUsers()
    expect(request.get).toHaveBeenCalledWith(BASE)
  })
  it('createUser → POST /admin/identity/users + body', () => {
    const body = { username: 'dave', password: 'secret12', role: 'member' as const }
    createUser(body)
    expect(request.post).toHaveBeenCalledWith(BASE, body)
  })
  it('enableUser → POST .../{id}/enable', () => {
    enableUser('7')
    expect(request.post).toHaveBeenCalledWith(`${BASE}/7/enable`)
  })
  it('disableUser → POST .../{id}/disable', () => {
    disableUser('7')
    expect(request.post).toHaveBeenCalledWith(`${BASE}/7/disable`)
  })
  it('resetPassword → PUT .../{id}/password + {password}', () => {
    resetPassword('7', 'newpass12')
    expect(request.put).toHaveBeenCalledWith(`${BASE}/7/password`, { password: 'newpass12' })
  })
  it('changeRole → PUT .../{id}/role + {role}', () => {
    changeRole('7', 'admin')
    expect(request.put).toHaveBeenCalledWith(`${BASE}/7/role`, { role: 'admin' })
  })
  it('deleteUser → DELETE .../{id}', () => {
    deleteUser('7')
    expect(request.delete).toHaveBeenCalledWith(`${BASE}/7`)
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `pnpm -C web run test -- src/api/admin/__tests__/user.spec.ts`
Expected: FAIL —— 当前 mock 实现不调用 `request`。

- [ ] **Step 3: 把 mock 实现替换为真 `request` 调用**

`web/src/api/admin/user.ts` 整体替换为：

```ts
import { request } from '../request'
import type { AdminUser, CreateUserRequest } from '@/types/admin-user'
import type { UserRole } from '@/types/user'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。
const BASE = '/admin/identity/users'

/** 列出全部用户。后端：GET /api/v1/admin/identity/users */
export function listUsers() {
  return request.get<AdminUser[]>(BASE)
}
/** 新建用户。后端：POST /api/v1/admin/identity/users */
export function createUser(body: CreateUserRequest) {
  return request.post<AdminUser>(BASE, body)
}
/** 启用账号。后端：POST .../{id}/enable */
export function enableUser(id: string) {
  return request.post<AdminUser>(`${BASE}/${id}/enable`)
}
/** 停用账号。后端：POST .../{id}/disable */
export function disableUser(id: string) {
  return request.post<AdminUser>(`${BASE}/${id}/disable`)
}
/** 重置密码（admin 代设）。后端：PUT .../{id}/password */
export function resetPassword(id: string, password: string) {
  return request.put<void>(`${BASE}/${id}/password`, { password })
}
/** 改角色。后端：PUT .../{id}/role */
export function changeRole(id: string, role: UserRole) {
  return request.put<AdminUser>(`${BASE}/${id}/role`, { role })
}
/** 删除用户（软删）。后端：DELETE .../{id} */
export function deleteUser(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}
```

- [ ] **Step 4: 运行全量测试 + 类型检查，确认通过**

Run: `pnpm -C web run typecheck && pnpm -C web run test`
Expected: 全绿（API 契约测试 7 条 + UserList 组件测试全部）。组件测试本就 mock API 层，不受实现替换影响。

- [ ] **Step 5: 提交**

```bash
git add web/src/api/admin/user.ts web/src/api/admin/__tests__/user.spec.ts
git commit -m "前端：用户管理——API 层换真后端 request 调用 + 契约测试（mock→真）"
```

- [ ] **Step 6: 对真后端手动验收（需后端运行）**

启动后端（或 `docker compose up`），`pnpm -C web run dev`，以 admin 登录后访问 `/admin/identity`：
1. 列表能加载真实用户；角色/状态 tag 正确
2. 新建用户 → 列表出现新行；重名 → 后端 409，页面弹 toast
3. 自己那行的停用/降级/删除置灰；最后一个启用 admin 的对应按钮置灰
4. 停用/删除弹确认框；重置密码弹输入框；改角色生效
5. 尝试停用「最后一个启用 admin」（前端置灰拦住）；若绕过，后端 11003 弹 toast

> 自检：把以上结果按 `self-check-per-step` 约定追加到 `docs/self-check.md`。

---

## Self-Review（计划自检）

- **Spec 覆盖**：列表(T1)、角色/状态 tag(T1)、新建+校验(T3)、启用/禁用(T2 渲染+T4 接线)、重置密码(T4)、改角色(T4)、删除(T4)、自己那行护栏(T2)、最后一个 admin 护栏(T2)、整列表重拉(T3/T4)、路由 roles:['admin'](T1)、真后端兜底 11003(T5 手验)——均有对应任务。
- **占位符扫描**：无 TBD/TODO；T2 的空函数是刻意占位，T4 明确替换。
- **类型一致**：`AdminUser`/`CreateUserRequest`/7 个函数签名（id:string）在 T1 定义，T2–T5 一致引用；`runAction`/`confirmDanger`/`dangerDisabledReason` 命名前后一致；`data-test` 命名（`disable-/enable-/role-/reset-/delete-/create-*`）测试与模板一致。
