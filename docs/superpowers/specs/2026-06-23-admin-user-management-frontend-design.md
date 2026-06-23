# Admin 用户管理 · 前端页设计稿

> identity 模块第二轮的**前端**承接：把已落地的 7 个 admin 用户管理接口接成一个可用的管理页。
> 后端契约见 [2026-06-23-admin-user-management-design](2026-06-23-admin-user-management-design.md)。
> 这是前端第一个真正的「增删改查」admin 页，定位为后续 provider / tool 等 admin 页的范本。

## 范围

**本轮做**：`/admin/identity` 用户管理页，一页覆盖后端全部 7 个动作：
1. 列出全部用户（表格）
2. 新建用户（对话框：用户名 / 密码 / 角色）
3. 启用 / 禁用账号（按当前状态二选一）
4. 重置密码（admin 代设新密码）
5. 改角色（admin ↔ member）
6. 删除用户（软删）

> 一次做全而非分批：7 个动作共用同一个列表页与同一套护栏 UX，分批反而要反复改同一文件。

**本轮不做（推迟）**：分页 / 搜索（20-50 人量级，后端一次返回全部，要过滤可本地做）、用户自助改密、批量操作。

## 关键决策（brainstorm 结论）

| 议题 | 结论 | 理由 |
|---|---|---|
| 实现方式 | **单文件页面** `UserList.vue`，内联 `el-dialog` 新建对话框 | 符合 CLAUDE.md「最简单直接 / 不过度抽象」。等真有第二个 admin 页要抄时，再按依据提炼组件 |
| 自己那一行的危险操作 | **置灰禁止**（禁用 / 删除 / 把自己降为 member） | 防脚枪——后端允许但会把自己踢下线。重置自己密码、启用等安全操作仍允许 |
| 最后一个启用 admin | 前端用列表数据**算出启用 admin 数**，≤1 时该行禁用/删除/降级**置灰** | 提前拦，不等后端 11003 才报错；后端护栏仍是最终兜底 |
| 新建后列表刷新 | **整列表重新拉一次** | 简单、不会和后端不同步；省去手工维护本地列表 |
| 角色 / 状态展示 | Element Plus `el-tag` 上色（状态：启用绿 / 禁用灰） | 与既有前端风格一致，一眼可辨 |
| 错误兜底 | 复用 `request` 拦截器 | 业务码默认弹 toast，登录失效自动跳登录，参数校验 10001 交表单逐项标红 |

## 架构

### 文件结构（4 个新文件 + 1 处路由改动）

```
web/src/
├── api/admin/user.ts                          # API 层：7 个函数对应后端 7 接口
├── types/admin-user.ts                        # 类型：AdminUser、请求体
├── views/admin/identity/
│   ├── UserList.vue                           # 页面本体（表格 + 新建对话框）
│   └── __tests__/UserList.spec.ts             # vitest 测试
└── router/index.ts                            # 加一条 /admin/identity 路由（改动）
```

### ① 类型层 `types/admin-user.ts`

```ts
// 对应后端 UserView；id 为 string —— 后端把 Long 序列化为 string 防 JS 精度丢失
export interface AdminUser {
  id: string
  username: string
  role: 'admin' | 'member'
  status: 'enabled' | 'disabled'
  createTime: string            // ISO-8601 带时区
}

export interface CreateUserRequest {
  username: string
  password: string
  role: 'admin' | 'member'
}
```

> 角色复用 `types/user.ts` 的 `UserRole`（`'admin' | 'member'`），不重复定义。
> `status` 字面量已对齐后端 `UserStatus` 枚举：`enabled` / `disabled`。

### ② API 层 `api/admin/user.ts`

7 个函数，仅负责拼 URL + 类型，解包 / 错误处理由 `request` 拦截器统一完成。baseURL 已含 `/api/v1`：

| 函数 | 方法 + 路径 | 返回 |
|---|---|---|
| `listUsers()` | `GET    /admin/identity/users` | `AdminUser[]` |
| `createUser(body)` | `POST   /admin/identity/users` | `AdminUser` |
| `enableUser(id)` | `POST   /admin/identity/users/{id}/enable` | `AdminUser` |
| `disableUser(id)` | `POST   /admin/identity/users/{id}/disable` | `AdminUser` |
| `resetPassword(id, password)` | `PUT    /admin/identity/users/{id}/password` | `void` |
| `changeRole(id, role)` | `PUT    /admin/identity/users/{id}/role` | `AdminUser` |
| `deleteUser(id)` | `DELETE /admin/identity/users/{id}` | `void` |

### ③ 页面 `views/admin/identity/UserList.vue`

**布局**
- 顶部：标题「用户管理」+「新建用户」按钮
- 表格列：用户名 / 角色（`el-tag`）/ 状态（`el-tag` 启用绿、禁用灰）/ 创建时间 / 操作
- 操作列按钮：
  - 启用 ↔ 禁用（按 `status` 二选一显示）
  - 改角色（admin↔member，下拉或确认切换）
  - 重置密码（弹输入新密码的对话框）
  - 删除

**新建对话框**（`el-dialog` + `el-form`）
- 字段：用户名、密码、角色下拉
- 前端校验对齐后端：用户名 `NotBlank` 且 ≤50；密码 `NotBlank` 且 8~72；角色必选
- 提交成功 → 关对话框 + `ElMessage.success` + 重新 `listUsers()`

**危险操作二次确认**：禁用 / 删除 / 降级走 `ElMessageBox.confirm`。

### ④ 护栏 UX（两道，前端提前置灰）

当前用户来自 `useUserStore().user`（含 `id` / `role`）。页面加载与每次列表刷新后重算：

```
enabledAdminCount = users.filter(u => u.role === 'admin' && u.status === 'enabled').length
```

对每一行：
- **自己那一行**（`row.id === currentUser.id`）：禁用、删除、降级按钮置灰，tooltip「不能对自己做此操作」
- **最后一个启用 admin**（`row.role==='admin' && row.status==='enabled' && enabledAdminCount <= 1`）：禁用、删除、降级置灰，tooltip「需至少保留一个启用的管理员」
- 兜底：前端若算漏，后端仍以 11003 等拦截，`request` 拦截器照常弹错误 toast

### ⑤ 路由 `router/index.ts`

照抄占位的 provider 路由模式，新增：

```ts
{
  path: '/admin/identity',
  name: 'UserList',
  component: () => import('@/views/admin/identity/UserList.vue'),
  meta: { requiresAuth: true, roles: ['admin'], title: '用户管理', menu: true },
}
```

非 admin 由既有路由守卫挡在 `/403`。

## 测试（vitest + TDD，先写失败测试）

mock 掉 `api/admin/user.ts` 与 `useUserStore`，覆盖：

1. 挂载时调 `listUsers()` 并渲染各行（用户名 / 角色 tag / 状态 tag）
2. **自己那一行**的禁用/删除/降级按钮置灰
3. **最后一个启用 admin** 的禁用/删除/降级按钮置灰
4. 新建对话框表单校验（空用户名、密码长度越界、未选角色 → 拦截，不发请求）
5. 新建成功 → 调 `createUser` 且随后重新 `listUsers()`
6. 各行动作点击 → 调对应 API（启用/禁用/重置/改角色/删除）
7. 危险操作触发 `ElMessageBox.confirm`，取消则不发请求

## 数据流

```
UserList.vue ──(onMounted / 刷新)──> listUsers() ──> request(GET) ──> 后端
            <── AdminUser[] ──────────────────────────────────────────┘
  │
  ├─ 计算 enabledAdminCount + 标记自己那一行 → 决定每行按钮置灰
  └─ 动作（建/启/禁/重置/改角色/删）── 对应 API ──> 后端
       成功 → toast + 重新 listUsers()
       失败 → request 拦截器统一处理（toast / 跳登录 / 表单标红）
```

## 不变量与边界

- 密码绝不进前端展示 / 日志；明文仅在新建/重置请求体里走 HTTPS 传一次
- `id` 全程当 `string` 处理，不做数值运算
- 列表无分页：20-50 人量级足够；要搜索可本地 `filter`
- 本页仅 admin 可达（路由 `roles: ['admin']` + 后端 `hasRole('ADMIN')` 双重保证）
