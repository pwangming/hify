# Admin 用户管理设计稿

> identity 模块第二轮：admin 专属的用户管理（创建 / 列表 / 停用启用 / 重置密码）。
> 承接登录闭环（[2026-06-22-identity-login-design](2026-06-22-identity-login-design.md)），
> 复用其已落地的 `sys_user` 表、JWT 安全栈、`CurrentUserHolder`、`PasswordEncoder`。

## 范围

**本轮做**：admin 用户管理的**后端** REST 接口 + service + 测试。六类操作：
1. 创建用户（admin 填用户名/角色/初始密码）
2. 列出全部用户
3. 停用 / 启用账号
4. 重置密码（admin 代设新密码）
5. 改角色（admin↔member 升降级）
6. 删除用户（软删）

**本轮不做（推迟）**：用户自助改密、前端管理页、Testcontainers 连库测试。

> 把改角色、删除一并纳入，是因为它们与「至少保留一个启用 admin」这条不变量**强内聚**：
> 停用、降级（admin→member）、删除三个操作都能破坏它。一起做才能把护栏一次设计成统一规则、
> 一处实现一次测全；拆开做则需回头重改护栏代码。

## 关键决策（brainstorm 结论）

| 议题 | 结论 | 理由 |
|---|---|---|
| 范围 | 仅后端 | 接口稳下来、契约明确后下一轮再接前端页 |
| 停用/重置后旧 token | **接受窗口，不做即时失效** | JWT 无状态；停用/改密只挡新登录，旧 token 自然过期（最长 24h）。20-50 人内部场景够用；不动 infra 安全栈、不增每请求 DB 开销。二期要紧可缩短有效期或引入黑名单 |
| 用户列表 | **一次返回全部，不分页/不搜索** | 20-50 人量级，分页是过度设计；前端要搜索可本地过滤 |
| 密码来源 | **admin 手动输入明文**，后端 BCrypt 哈希存 | 最简单；明文走 HTTPS（Nginx 前置）、不落日志 |
| 防自锁护栏 | **拦「会导致启用 admin 归零」的操作** | 停用 / 降级（admin→member）/ 删除三者共用一条规则：目标当前是启用的 admin 且启用 admin 计数==1 → 拒绝。天然覆盖「唯一 admin 停/降/删自己」 |
| 类结构 | 一个 `AdminUserController` + 一个 `AdminUserService` | 内聚单一职责；与登录的 `AuthController`/`AuthService`（匿名入口）分开，语义不同不混 |

## 第 1 节：接口契约

全部挂 `/api/v1/admin/identity/users`（admin 路由族 `/api/v1/admin/<module>/**`，api-standards 第 1 节）。
落 SecurityConfig 既有规则 `/api/v1/admin/**` → `hasRole("ADMIN")`，**不动 SecurityConfig**。

| # | 方法 + 路径 | 作用 | 请求体 | 成功响应 `data` |
|---|---|---|---|---|
| 1 | `POST /api/v1/admin/identity/users` | 创建用户 | `{username, password, role}` | `UserView` |
| 2 | `GET /api/v1/admin/identity/users` | 列出全部用户 | — | `[UserView, …]`（createTime 倒序） |
| 3 | `POST /api/v1/admin/identity/users/{id}/enable` | 启用 | — | `UserView` |
| 4 | `POST /api/v1/admin/identity/users/{id}/disable` | 停用 | — | `UserView` |
| 5 | `PUT /api/v1/admin/identity/users/{id}/password` | 重置密码 | `{password}` | 空（`Result.ok()`） |
| 6 | `PUT /api/v1/admin/identity/users/{id}/role` | 改角色 | `{role}` | `UserView` |
| 7 | `DELETE /api/v1/admin/identity/users/{id}` | 删除用户（软删） | — | 空（`Result.ok()`） |

合规要点（对照 api-standards）：
- **路由带模块段**：`/api/v1/admin/identity/users`（非 `/api/v1/admin/users`）。
- **不用 PATCH**（规范第 2.2 节）：停用/启用拆成两个动作子资源 `POST .../enable`、`POST .../disable`。
- 重置密码 `PUT .../password`、改角色 `PUT .../role`：PUT 全量替换，单字段子资源无「null=置空 vs 不改」歧义。
- 删除 `DELETE /{id}`：**软删**（`deleted=true`），幂等（删不存在的也返回成功，规范第 2.2 节）。软删后该用户名因唯一索引 `where deleted=false` 释放，可被重新创建。
- `id` 路径参为 Long；响应 `id` 全局序列化为**字符串**（Long→string 铁律，第 4 节）。
- 列表不分页，`data` 直接是数组（第 3.2 节：空也是 `[]`）。
- `passwordHash` **绝不出现在任何响应**。
- 时间 ISO-8601 带时区；JSON 字段 camelCase——均由 infra 全局 Jackson 配置兜底。

## 第 2 节：数据层与迁移

**零 DDL，无新 Flyway 脚本。** `sys_user`（V4）已有全部列：`username / password_hash / role / status` +
公共四列；`status` 默认 `enabled`；username 在 `deleted=false` 下唯一索引。

`SysUserMapper` 现有 MyBatis-Plus `BaseMapper` 方法即够，本轮**不写自定义 SQL/XML**：
- 创建 → `insert`
- 列表 → `selectList`（create_time 倒序）
- 单查 → `selectById`
- 改状态/改密/改角色 → `updateById`
- 删除 → `deleteById`（`@TableLogic` 自动转 `update ... set deleted=true`，软删）
- 重名预检 → `selectCount`（username 且 deleted=false）
- 启用 admin 计数（护栏）→ `selectCount`（role=admin 且 status=enabled 且 deleted=false）

`@TableLogic` 自动给查询加 `where deleted=false`，以上天然只看未删用户。

## 第 3 节：Service 层（`AdminUserService`）

一个 `@Service` 具体类（不拆接口，code-organization 第 2 节），注入 `SysUserMapper` + infra `PasswordEncoder`。
写方法（建/停/启/重置/改角色/删除）加 `@Transactional`（单表写；BCrypt 是纯 CPU 计算非外部 IO，可在事务内）。

**统一护栏（私有方法）`assertNotLastEnabledAdmin(SysUser target)`**：
若 `target` 当前是「启用的 admin」（role=admin 且 status=enabled）且「启用 admin 计数」==1（它是最后一个）
→ 抛 `BizException(IdentityError.CANNOT_REMOVE_LAST_ADMIN)`（11003/409，message「系统至少保留一个启用的管理员」）。
**停用、降级（admin→member）、删除**三处在执行前都先调它。天然覆盖「唯一 admin 停/降/删自己」。

1. **`create(username, password, role)` → `UserView`**
   - 重名预检命中 → `BizException(CommonError.CONFLICT, "用户名已存在")`（409）。
   - BCrypt 哈希 → `insert`（status 默认 enabled）。
   - 兜底：并发漏过预检时 DB 唯一索引拦下，捕获 `DuplicateKeyException` 同样转 CONFLICT（双保险）。

2. **`list()` → `List<UserView>`**：`selectList` create_time 倒序，映射为 `UserView`（不含 passwordHash）。

3. **`disable(id)` → `UserView`**
   - `selectById` 找不到 → `BizException(CommonError.NOT_FOUND, "用户不存在")`（404）。
   - 已是 disabled → 幂等返回当前状态（不触发护栏：停一个已停的不改变启用 admin 数）。
   - 否则先过 `assertNotLastEnabledAdmin`，再置 status=disabled。
   - **不传 currentUserId**：护栏只依赖「启用 admin 计数」一条规则，无需比对是否本人，更简单。

4. **`enable(id)` → `UserView`**：找不到 → NOT_FOUND；否则置 enabled；已启用 → 幂等。（启用不会破坏不变量，无护栏。）

5. **`resetPassword(id, newPassword)`**：找不到 → NOT_FOUND；否则 BCrypt 哈希 → update password_hash。

6. **`changeRole(id, role)` → `UserView`**
   - 找不到 → NOT_FOUND。
   - 目标角色与当前相同 → 幂等返回。
   - 若是 admin→member 降级，先过 `assertNotLastEnabledAdmin`；再 update role。
   - （member→admin 升级不破坏不变量，无护栏。）

7. **`delete(id)`**
   - `selectById` 找不到 → 幂等返回成功（规范第 2.2 节：删不存在的也成功；`@TableLogic` 下软删已删的同样无副作用）。
   - 找到则先过 `assertNotLastEnabledAdmin`，再 `deleteById`（软删）。

并发说明：「启用 admin 计数」与写操作之间存在理论并发窗口（两请求同时停/降/删掉两个 admin）。
内部小团队、admin 通常一两个，窗口可忽略；不上行锁（过度设计）。

## 第 4 节：DTO 与校验

校验注解只写在请求 DTO（api-standards 第 4 节，service 不重复 Web 层校验）。

**请求 DTO（record）：**
- `CreateUserRequest(String username, String password, String role)`
  - `username`：`@NotBlank` + `@Size(max=50)`（对齐 DB `char_length<=50`）
  - `password`：`@NotBlank` + `@Size(min=8, max=72)`（min 8 为约定下限；max 72 是 BCrypt 字节上限，超过会被静默截断，挡在入口）
  - `role`：`@NotBlank` + `@Pattern(regexp="admin|member")`（与 DB check 一致）
- `ResetPasswordRequest(String password)`：同上 password 规则
- `ChangeRoleRequest(String role)`：`@NotBlank` + `@Pattern(regexp="admin|member")`
- enable/disable/delete 无请求体

**响应 DTO（record，绝不含 passwordHash）：**
- `UserView(Long id, String username, String role, String status, OffsetDateTime createTime)`
  —— 列表与单用户操作共用。DTO 上**不写** `@JsonFormat`/`@JsonInclude`（禁止局部覆盖序列化，第 4 节）。

**错误码（本轮只新增 11003，其余复用通用段）：**
| 场景 | 码 | HTTP | 来源 |
|---|---|---|---|
| 字段校验失败 | 10001 | 400 | infra 全局处理，带字段数组 |
| 非 admin 调用 | 10004 | 403 | SecurityConfig `hasRole` + RestAccessDeniedHandler |
| 用户名已存在 | 10006 | 409 | `CommonError.CONFLICT` |
| 用户不存在 | 10005 | 404 | `CommonError.NOT_FOUND` |
| 停用/降级/删除最后一个启用 admin | **11003** | 409 | 新增 `IdentityError.CANNOT_REMOVE_LAST_ADMIN` |

## 第 5 节：测试策略

不连真库（连库测试按既定计划推迟到 knowledge 手写 SQL 那轮）。

**1. `AdminUserServiceTest`（纯单元，mock `SysUserMapper` + `PasswordEncoder`）**——业务规则主战场：
- 创建：重名预检命中 → CONFLICT；正常 → insert 实体里 password 已哈希（非明文）、status=enabled。
- 统一护栏（停用/降级/删除三处）：目标是最后一个启用 admin → 11003；尚有别的启用 admin → 放行。
- 停用/启用幂等：已是目标状态再调 → 不报错。
- 改角色：admin→member 降级且是最后一个启用 admin → 11003；member→admin 升级无护栏正常；同角色 → 幂等。
- 删除：软删最后一个启用 admin → 11003；删不存在的用户 → 幂等成功；正常删除走 `deleteById`。
- 停用/启用/重置/改角色 找不到用户 → NOT_FOUND。
- 重置密码：update 写入的是哈希值。
- **故意搞错验证**：把护栏方法临时改成永远放过，「停/降/删最后一个 admin → 11003」三条应一起变红，证明护栏在守门。

**2. `AdminUserControllerTest`（MockMvc Web+Security 切片，仿 `CurrentUserControllerTest`）**——协议与鉴权：
- 真 JwtService 签 **admin** token → 7 个接口走通；响应结构正确、**不含 passwordHash**、id 是字符串。
- 签 **member** token → 403 + 10004（验 `hasRole("ADMIN")` 生效）。
- 无 token → 401 + 10002。
- 校验失败（空 username / 短 password / 非法 role）→ 400 + 10001 + 字段数组。
- service 用 `@MockitoBean` mock 掉（切片不碰 DB）。

**3. 回归**：`mvn -f server/pom.xml test` 全量绿（含 ModularityTests / LayerRulesTest，确认新类放对层、无跨模块越界）。

**不做**：Testcontainers 连库测试、前端测试（本轮无前端）。

## 完成标准（Definition of Done）

- 7 个接口按契约可用，鉴权（admin/member/匿名）、校验、错误码全部符合 api-standards。
- 统一护栏覆盖停用/降级/删除三处，「至少保留一个启用 admin」不变量成立且测到。
- `mvn -f server/pom.xml test` 全绿（含新增 service/controller 测试与 Modularity/ArchUnit）。
- 仅新增 `IdentityError.CANNOT_REMOVE_LAST_ADMIN`（11003）一个错误码；其余复用通用段。
- 不含：前端页、用户自助改密、连库测试。
