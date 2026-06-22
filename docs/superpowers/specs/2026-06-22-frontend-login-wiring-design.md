# 前端登录接通后端 设计稿（补 `/identity/me` + 端到端验证）

> 日期：2026-06-22 ｜ 关联：identity 登录闭环（已合并 main，commit d3d05f9）
> 关联规范：`docs/architecture/code-organization.md`、`api-standards.md`、`frontend-standards.md`

## 0. 背景与范围

前端登录链路（`web/src` 下的 `api/auth.ts`、`api/request.ts`、`stores/user.ts`、`router/guard.ts`、
`views/login/LoginView.vue`）在「前端地基 4」已**完整实现并有 vitest 测试**，且按后端契约书写；
Vite dev proxy 已把 `/api`、`/v1` 转发到 `http://localhost:8080`。

唯一缺口在**后端**：前端的 `getCurrentUser()` 与路由守卫依赖 **`GET /api/v1/identity/me`**
（持有 token 时拉当前用户拿角色——登录后与刷新后都要调），而该接口上一轮未做（自助查询被推迟）。
没有它，登录走到 `loadCurrentUser()` 即 404，刷新也保不住登录态。

**本轮范围**：
- 后端 identity 模块新增受保护接口 `GET /api/v1/identity/me`，数据取自 JWT（不查库）。
- 前端**不改代码**，仅核对契约、重跑测试。
- 真机端到端冒烟验证（mock 测试覆盖不到的真前后端契约）。

**不做**：admin 用户管理、改密、`/me` 的 DB 实时查询（角色变更需重新登录才生效，内部工具可接受）、
自动化 E2E 套件（Playwright 等——与 Testcontainers 同理推迟，见 [[testing-defer-testcontainers]] 的同类决策）。

## 1. 架构与定位

`GET /api/v1/identity/me` 返回「我是谁」，数据直接取自 `infra.CurrentUserHolder.current()`（JWT 解析结果），
**零 DB 查询**。它是受保护路由——不在 `SecurityConfig` 的 permitAll 名单内，落入 `anyRequest().authenticated()`，
默认即要求已认证，**无需改 SecurityConfig**。

与 `AuthController`（`/login`，匿名 permitAll）分开为独立 controller：`/me` 是「已认证的当前用户」语义，
职责与登录不同。

## 2. 组件（后端，identity 模块）

| 组件 | 位置 | 职责 |
|---|---|---|
| `MeResponse` | `identity/dto/` | record `(Long id, String username, String role)`。`id` 由 infra Jackson 全局序列化为字符串，对齐前端 `UserInfo{id,username,role}` |
| `CurrentUserController` | `identity/controller/` | `GET /api/v1/identity/me`：读 `CurrentUserHolder.current()` → 组装 `MeResponse` → `Result.ok`。纯协议层，无业务逻辑、无 service、无 DB |

**为什么 `/me` 不设 service**：它只是把安全上下文里的当前用户回显，无业务规则、无数据访问，加 service 只是无意义转发。
`CurrentUserHolder` 是 infra 的静态工具（静态调用，非注入其他模块的类），ArchUnit（不碰 mapper/entity）与 Modulith
（identity→infra 允许）均合规。

`MeResponse` 字段映射：`id = CurrentUser.userId()`（Long）、`username = CurrentUser.username()`、`role = CurrentUser.role()`。

## 3. 数据流

**登录：**
```
LoginView 提交 → login() 得 {token,...} → setToken 存 localStorage
  → loadCurrentUser() 调 GET /api/v1/identity/me（请求拦截器注入 Bearer）
  → 后端从 JWT 回显 {id,username,role} → store.user 填充
  → router.push(redirect 目标或 /)
```

**刷新页面：** 守卫③ 见有 token、无 user → `loadCurrentUser()` → `/me` 拿回角色 → 放行。

**未登录/过期：** `/me` 无有效 token → 后端 401（10002）→ 前端 request 拦截器统一清登录态并跳登录（已实现）。

## 4. 前端

**不改代码。** 契约核对一致：
- 前端 `UserInfo { id: string, username, role }` ↔ 后端 `/me` 返回 `{ id, username, role }`（id 为序列化后的字符串）。
- 前端 `login` 仅解构 `{ token }`；后端 `LoginResponse` 多返回的 `userId/username/role` 被忽略，无害。

仅重跑现有 vitest 确认全绿。

## 5. 测试

- **后端 `/me` 切片测**（仿 `AuthLoginSecurityTest`：`@WebMvcTest(CurrentUserController.class)` + `@Import` 安全栈
  + `@TestPropertySource` jwt secret + 真 `JwtService` 签 token）：
  - 带有效 token → HTTP 200，且 `$.data.id`/`$.data.username`/`$.data.role` 等于 token 中的身份；
  - 无 token → HTTP 401 + `code 10002`。
- **前端**：现有 `LoginView.spec` / `stores/__tests__/user.spec` / `router/__tests__/guard.spec`（mock api）重跑确认绿；
  若发现登录整流程未覆盖再补一条，否则不新增。
- **手动 e2e 冒烟**（真契约，眼见为实）：
  1. 起后端（带引导 admin，命令行参数注入账号密码）；
  2. `cd web && pnpm dev`；
  3. 浏览器开 dev 地址 → 用 admin 登录 → 确认：localStorage 有 `hify_token`、`/me` 返回 200、跳转到首页/redirect、
     刷新后仍保持登录、登出后回登录页；
  4. 错误密码 → 页面提示（拦截器 toast），不进入。

## 6. 完成标准

- `mvn -f server/pom.xml test` 全绿（含新增 `/me` 切片测）。
- `cd web && pnpm test`（vitest）全绿、`pnpm build` 通过（类型检查）。
- 手动 e2e：能用引导出的 admin 账号在浏览器端到端登录、刷新保持、登出，全部符合预期。
