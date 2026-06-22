# identity 模块 · 登录闭环 设计稿

> 日期：2026-06-22 ｜ 范围：server 端 identity 模块的「登录闭环」一轮
> 关联规范：`docs/architecture/code-organization.md`、`api-standards.md`、`database-standards.md`、
> `coding-standards.md`、`testing-standards.md`

## 0. 本轮范围（Scope）

**做**：`sys_user` 建表 + 登录接口（验密码 → 签 JWT）+ 以 `.env` 引导的首个 admin 账号。

**不做（留到后续轮次）**：
- admin 用户管理（增删改查、改角色、重置密码、停用/启用的接口）
- 自助接口（查我自己 / 改我自己密码）
- 前端登录页（属 `web/`，单独一轮）
- refresh token（一期单令牌，YAGNI；见第 3 节）

## 1. 架构与模块定位

identity 是一个**纯叶子模块**：

- `code-organization.md` 第 1 节规定**禁止任何模块依赖 identity**；"当前用户"统一从 `infra.CurrentUserHolder.current()` 取（JWT 解析结果 `CurrentUser`）。
- **推论**：identity **不需要 `api/` 包、不需要 Facade、不需要跨模块 DTO/事件**——它对其他 Java 模块不暴露任何东西，只对外暴露 HTTP 接口。这是相对模块模板的一处刻意简化，需在 `package-info.java` 注释中点明，避免后人惯性造空 Facade。

模块依赖声明：

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "common", "infra" }
)
package com.hify.identity;
```

infra 是 OPEN 模块，故 identity 可直接 import `infra.security` 下的 `JwtService`、`CurrentUser`、新增的 `PasswordEncoder` Bean。

## 2. 组件清单

| 组件 | 位置 | 职责 |
|---|---|---|
| `V4__create_sys_user.sql` | `server/src/main/resources/db/migration/` | 建 `sys_user` 表 |
| `SysUser` | `identity/entity/` | 表映射，`@TableName("sys_user")`，继承 `common.BaseEntity` |
| `SysUserMapper` | `identity/mapper/` | 继承 `BaseMapper<SysUser>`，提供按 username 查询 |
| `UserStatus` | `identity/constant/` | 枚举 `ENABLED/DISABLED`，存 varchar `enabled`/`disabled`（与 role 小写风格一致）|
| `AuthService` | `identity/service/` | 登录逻辑：查用户 → 查状态 → 验密码 → 签 token |
| `AdminBootstrapRunner` | `identity/service/` | 启动时按 `.env` 建首个 admin（幂等）|
| `IdentityProperties` | `identity/config/` | 绑定 `hify.identity.bootstrap-admin.*`，值来自环境变量 |
| `AuthController` | `identity/controller/` | `POST /api/v1/identity/login` |
| `LoginRequest` / `LoginResponse` | `identity/dto/` | 入参/出参，仅本模块用，禁止被其他模块 import |
| **`PasswordEncoder` Bean** | **`infra/security/`** | 新增 `BCryptPasswordEncoder`（安全技术组件归 infra），被 `AuthService` 与 `AdminBootstrapRunner` 注入 |

### 2.1 `sys_user` 表字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigserial PK | BaseEntity，站内用 |
| `username` | varchar | 登录名；`deleted=0` 上的部分唯一索引 |
| `password_hash` | varchar | BCrypt 哈希，绝不出响应/日志 |
| `role` | varchar | `admin` / `member`（取值与 `infra.CurrentUser.ROLE_*` 一致）|
| `status` | varchar | `enabled` / `disabled` |
| `create_time` | timestamptz | BaseEntity 自动填充 |
| `update_time` | timestamptz | BaseEntity 自动填充 |
| `deleted` | int/smallint | BaseEntity 逻辑删除标志 |

建表细节（类型精度、索引、注释、时区）按 `database-standards.md`，写实现计划时逐条核对。

## 3. 数据流

### 3.1 登录

```
POST /api/v1/identity/login {username, password}
  → SecurityConfig 已 permitAll 此路径（无需改动）
  → AuthController @Valid 校验 username/password 非空
  → AuthService.login(username, password):
       查 SysUser(username, deleted=0)
       ├─ 不存在            → BizException 11001（用户名或密码错误）
       ├─ status=disabled   → BizException 11002（账号已停用）
       └─ passwordEncoder.matches 失败 → 11001（与"不存在"同码，不泄露账号是否存在）
       成功 → new CurrentUser(id, username, role) → jwtService.generateToken(currentUser)
  → Result.ok(LoginResponse{token, userId, username, role})
```

返回 `userId` 的理由：前端 `canEdit = isAdmin || resource.ownerId === currentUserId`（`frontend-standards.md`）需要当前用户 id。`/api/v1/**` 是前端内部 API，非对外 `/v1/apps/**`，自增 id 可用（`api-standards.md` 关于"不暴露自增主键"针对的是对外 API）。

前端拿到后存 token + user，后续请求由已就绪的 `JwtAuthenticationFilter` 验票；token 过期返回 `10003`，前端据此触发重新登录。

### 3.2 首个 admin 引导（启动时）

`AdminBootstrapRunner`（`ApplicationRunner`）读取 `hify.identity.bootstrap-admin.{username,password}`，其值经 application.yml 引用环境变量 `HIFY_ADMIN_USERNAME` / `HIFY_ADMIN_PASSWORD`（`.env`，不入库不入镜像）。逻辑：

- 若 username 与 password 均非空，且库中无该 username 的用户 → bcrypt 加密 password 后插入一条 `role=admin, status=enabled` 记录。
- 幂等：该 username 已存在则跳过。
- 未配置（任一为空）→ 记 warn 日志并跳过，**不创建空密码账号**。

## 4. 错误处理与安全

- 新增错误码，登记进 `api-standards.md` 第 5.2 节的 **11xxx（identity）** 段：
  - `11001` / HTTP 401 — 用户名或密码错误
  - `11002` / HTTP 403 — 账号已停用
  - token 无效/过期复用 infra 既有的 `10002` / `10003`，本模块不重复定义。
- 业务失败一律抛 `BizException` + 错误码，由 infra 全局异常处理器转响应；Controller 内不写 try-catch。
- `password_hash` 绝不进任何响应体、绝不进日志（`coding-standards.md`：日志禁出现密码/JWT）。登录失败日志只记 username 与失败原因，不记密码原文。
- 错误码一旦发布只增不改（`api-standards.md` 第 5 节）。

## 5. 测试（遵循 TDD，先写失败测试）

- **AuthService 单元测**：成功 / 用户不存在 / 密码错 / 账号停用 四条路径。mock `SysUserMapper`，使用真实 `BCryptPasswordEncoder`。断言成功路径返回的 token 用 `JwtService.parseToken` 能解出正确的 `userId/username/role`。
- **AdminBootstrapRunner 测**：空库 → 建出 admin 且能登录；重复运行幂等不重复建；未配置 → 不建并告警。
- **登录闭环整合测**（沿用现有 `@SpringBootTest` 测试基建，与 `SecurityConfigTest`/`JwtServiceTest` 一致）：登录拿 token → 带 token 请求一个受保护路径断言**非 401** → 不带 token 断言 **401**。证明"登录后能进受保护区"这一闭环。
- 测试质量按 `testing-standards.md` 自检（三类病、AI 测试 7 坑），避免假测试。

## 6. 需在实现计划阶段核对的开放项

- `sys_user` 建表脚本的字段类型/索引/注释精确写法 → 对照 `database-standards.md`。
- 现有 `@SpringBootTest` 整合测试如何接入 PostgreSQL（Testcontainers / 其他）→ 沿用既有测试基建的同一方式。
- `status` 与 `role` 在 entity 中用枚举还是 String 字段映射（MyBatis-Plus 枚举处理）→ 写实现时定，保持与既有约定一致。
