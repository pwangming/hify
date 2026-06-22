# identity 模块 · 登录闭环 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 identity 模块落地"登录闭环"——`sys_user` 建表 + 登录接口（验密码→签 JWT）+ 以 `.env` 引导的首个 admin 账号。

**Architecture:** identity 是纯叶子模块（无 `api/` 包、无 Facade，其他模块不依赖它，"当前用户"统一从 infra `CurrentUserHolder` 取）。登录复用 infra 既有的 `JwtService` 签发令牌、`CurrentUser` 承载身份；密码哈希用新增于 infra 的 `BCryptPasswordEncoder` Bean。测试沿用项目现有约定（Mockito 单元测 + MockMvc 切片测，**不连真数据库**，详见设计稿决策）。

**Tech Stack:** Spring Boot 3.5.6（Java 21）、MyBatis-Plus 3.5.12、Spring Security（BCrypt）、jjwt、Spring Modulith、JUnit5 + Mockito + spring-security-test、PostgreSQL 16 + Flyway。

**关联设计稿：** `docs/superpowers/specs/2026-06-22-identity-login-design.md`

## Global Constraints

- 基础包名 `com.hify`；identity 模块依赖白名单仅 `{"common","infra"}`（`code-organization.md` 第 1 节）。
- 跨模块只能 import 对方 `api/`；本计划中 identity 仅 import infra（OPEN 模块，允许直接注入）与 common。
- 角色取值 `admin` / `member`，与 `com.hify.infra.security.CurrentUser.ROLE_ADMIN/ROLE_MEMBER` 一致（小写）。
- 错误码段 identity = **11xxx**（`api-standards.md` 第 5.2 节已登记）；新增码一旦发布只增不改。
- 实体继承 `com.hify.common.BaseEntity`（id/createTime/updateTime/deleted 自动填充，填充逻辑在 infra `MetaObjectHandler`）；MyBatis-Plus 逻辑删除值为 `true/false`（已在 application.yml 配置）。
- Controller 不写业务逻辑/不写 `@Transactional`/不注入 Mapper；失败一律抛 `BizException`，由 infra `GlobalExceptionHandler` 转信封。
- 配置外化到 `application.yml`；敏感项（admin 初始密码）走 `.env` 环境变量引用，不写明文。
- 数据库变更只新增 Flyway 脚本（下一个版本号 **V4**），禁止改旧脚本。
- 日志禁止出现密码/JWT（`coding-standards.md` 第 17 条）。
- 测试运行：`mvn -f server/pom.xml test`（单测：`mvn -f server/pom.xml -Dtest=类名 test`）。注意不要用 `-q`（会静音测试摘要）。

---

### Task 1: 模块骨架 + 错误码枚举

**Files:**
- Create: `server/src/main/java/com/hify/identity/package-info.java`
- Create: `server/src/main/java/com/hify/identity/constant/IdentityError.java`
- Test: `server/src/test/java/com/hify/identity/constant/IdentityErrorTest.java`

**Interfaces:**
- Consumes: `com.hify.common.exception.ErrorCode`（接口：`int code()`、`HttpStatus status()`、`String defaultMessage()`）。
- Produces: 枚举 `IdentityError`，成员 `BAD_CREDENTIALS(11001, 401)`、`ACCOUNT_DISABLED(11002, 403)`，供后续 Task 4/5 抛 `BizException(IdentityError.X)` 使用。

- [ ] **Step 1: 写错误码约束测试（先失败）**

`server/src/test/java/com/hify/identity/constant/IdentityErrorTest.java`：

```java
package com.hify.identity.constant;

import com.hify.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdentityError} 的约束测试：验证整张枚举满足不变量（落在 11xxx 段、code 不重复、
 * 状态与提示齐全），防止以后新增码时手滑。参照 CommonErrorTest 的写法。
 */
class IdentityErrorTest {

    @Test
    void 所有码都落在identity段11xxx() {
        for (IdentityError e : IdentityError.values()) {
            assertTrue(e.code() >= 11000 && e.code() <= 11999,
                    () -> e.name() + " 的 code=" + e.code() + " 超出 identity 段 11xxx");
        }
    }

    @Test
    void code不得重复() {
        Set<Integer> seen = new HashSet<>();
        for (IdentityError e : IdentityError.values()) {
            assertTrue(seen.add(e.code()), () -> "重复的 code: " + e.code());
        }
    }

    @Test
    void status与提示都不为空() {
        for (IdentityError e : IdentityError.values()) {
            ErrorCode ec = e;
            assertNotNull(ec.status(), () -> e.name() + " 缺少 HTTP 状态");
            assertNotNull(ec.defaultMessage(), () -> e.name() + " 缺少默认提示");
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败（IdentityError 不存在）**

Run: `mvn -f server/pom.xml -Dtest=IdentityErrorTest test`
Expected: 编译失败 / 测试无法运行（`IdentityError` 找不到符号）。

- [ ] **Step 3: 写模块声明 package-info**

`server/src/main/java/com/hify/identity/package-info.java`：

```java
/**
 * identity —— 用户、角色、登录。
 *
 * <p>纯叶子模块：code-organization.md 第 1 节规定<b>禁止任何模块依赖 identity</b>，"当前用户"统一从
 * infra 的 {@code CurrentUserHolder.current()} 取。故 identity 刻意<b>不设 api/ 包、不设 Facade</b>——
 * 它对其他 Java 模块不暴露任何东西，只对外暴露 HTTP 接口（登录）。后人勿惯性给它造空 Facade。
 *
 * <p>依赖白名单仅 common、infra（infra 为 OPEN 模块，允许直接注入 JwtService / CurrentUser /
 * PasswordEncoder 等技术组件）。
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "infra"}
)
package com.hify.identity;
```

- [ ] **Step 4: 写 IdentityError 枚举**

`server/src/main/java/com/hify/identity/constant/IdentityError.java`：

```java
package com.hify.identity.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * identity 模块特有错误码（11xxx 段，api-standards.md 第 5.2 节登记）。
 *
 * <p>只放本模块特有语义；"资源不存在"等通用情形复用 {@code CommonError}。
 * 通用的"未认证/已过期"（10002/10003）由 infra 安全层处理，本枚举不重复定义。
 */
public enum IdentityError implements ErrorCode {

    /** 用户名或密码错误。刻意不区分"用户不存在"与"密码错"，不泄露账号是否存在。 */
    BAD_CREDENTIALS(11001, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    /** 账号已停用：用户存在且密码可能正确，但被管理员停用，禁止登录。 */
    ACCOUNT_DISABLED(11002, HttpStatus.FORBIDDEN, "账号已停用");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    IdentityError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
```

- [ ] **Step 5: 运行测试 + 模块边界校验，确认通过**

Run: `mvn -f server/pom.xml -Dtest=IdentityErrorTest,ModularityTests test`
Expected: 全部 PASS（IdentityErrorTest 三条 + ModularityTests 识别出 identity 模块、依赖白名单通过）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/identity/package-info.java \
        server/src/main/java/com/hify/identity/constant/IdentityError.java \
        server/src/test/java/com/hify/identity/constant/IdentityErrorTest.java
git commit -m "identity：模块骨架 + 错误码枚举 11001/11002"
```

---

### Task 2: 建表迁移 + 实体 + Mapper + 状态枚举

**Files:**
- Create: `server/src/main/resources/db/migration/V4__create_sys_user.sql`
- Create: `server/src/main/java/com/hify/identity/constant/UserStatus.java`
- Create: `server/src/main/java/com/hify/identity/entity/SysUser.java`
- Create: `server/src/main/java/com/hify/identity/mapper/SysUserMapper.java`

**Interfaces:**
- Produces:
  - `SysUser`（继承 `BaseEntity`）：getter/setter `getUsername/setUsername`、`getPasswordHash/setPasswordHash`、`getRole/setRole`、`getStatus/setStatus`（均 `String`）。
  - `SysUserMapper extends BaseMapper<SysUser>`，供 Task 4/6 注入，按 `username` 用 `LambdaQueryWrapper` 查询。
  - `UserStatus` 枚举：`ENABLED.value()` → `"enabled"`、`DISABLED.value()` → `"disabled"`。

> 说明：按项目现行约定，建表 SQL 与 mapper 的真库往返**不做连库测试**（无 Testcontainers，与 demo 模块现状一致）。本 Task 的验证门是"全量构建 + ArchUnit 分层 + Modulith 边界"通过，确保 entity/mapper 放对包、未越界。SQL 正确性在首次启动应用、Task 6 引导 admin 时即被真实执行验证。

- [ ] **Step 1: 写 Flyway 建表脚本**

`server/src/main/resources/db/migration/V4__create_sys_user.sql`：

```sql
-- V4：系统用户表（identity 模块）。建表模板见 database-standards.md 第 1.1 节：
-- 四个公共列每张表强制；文本用 text + 长度 check，时间用 timestamptz，布尔用 boolean。
-- 角色/状态用 text + check 约束枚举值（与代码里的小写枚举值对齐，不单独建角色表）。

create table sys_user (
    id            bigint generated always as identity primary key,
    username      text        not null check (char_length(username) <= 50),
    password_hash text        not null,
    role          text        not null check (role in ('admin', 'member')),
    status        text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table sys_user is '系统用户（identity 模块）：Admin/Member 用 role 字段区分，不单独建角色表';

-- 未软删用户的 username 唯一（配合 @TableLogic：软删后允许同名重新创建）。
create unique index uk_sys_user_username on sys_user (username) where deleted = false;
```

- [ ] **Step 2: 写 UserStatus 枚举**

`server/src/main/java/com/hify/identity/constant/UserStatus.java`：

```java
package com.hify.identity.constant;

/**
 * 用户状态。存库为小写字符串（与 sys_user.status 的 check 约束一致），
 * 用枚举集中两个取值、避免散落的魔法字符串。
 */
public enum UserStatus {

    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
```

- [ ] **Step 3: 写 SysUser 实体**

`server/src/main/java/com/hify/identity/entity/SysUser.java`：

```java
package com.hify.identity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 系统用户表 {@code sys_user} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列（填充逻辑在 infra 的 MetaObjectHandler）。
 *
 * <p>{@code role} 取值 admin/member（与 infra CurrentUser.ROLE_* 一致），
 * {@code status} 取值 enabled/disabled（见 {@code UserStatus}）。
 * MyBatis-Plus 默认开启驼峰↔下划线映射：passwordHash ↔ password_hash。
 */
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;
    private String passwordHash;
    private String role;
    private String status;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

- [ ] **Step 4: 写 SysUserMapper**

`server/src/main/java/com/hify/identity/mapper/SysUserMapper.java`：

```java
package com.hify.identity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.identity.entity.SysUser;

/**
 * {@link SysUser} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查能力，
 * 被 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 * 按 username 查询用 service 层的 LambdaQueryWrapper，无需在此加自定义方法。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

- [ ] **Step 5: 全量构建 + 边界/分层校验**

Run: `mvn -f server/pom.xml -Dtest=ModularityTests,LayerRulesTest test`
Expected: PASS（entity 只在 `entity/`、mapper 只在 `mapper/`、无跨模块越界；identity 模块依赖白名单通过）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/resources/db/migration/V4__create_sys_user.sql \
        server/src/main/java/com/hify/identity/constant/UserStatus.java \
        server/src/main/java/com/hify/identity/entity/SysUser.java \
        server/src/main/java/com/hify/identity/mapper/SysUserMapper.java
git commit -m "identity：sys_user 建表迁移 + 实体 + Mapper + 状态枚举"
```

---

### Task 3: 登录服务 AuthService（含 infra 新增 PasswordEncoder Bean）

**Files:**
- Modify: `server/src/main/java/com/hify/infra/security/SecurityConfig.java`（新增 `PasswordEncoder` Bean）
- Create: `server/src/main/java/com/hify/identity/dto/LoginResponse.java`
- Create: `server/src/main/java/com/hify/identity/service/AuthService.java`
- Test: `server/src/test/java/com/hify/identity/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `SysUserMapper`（Task 2）、`UserStatus`（Task 2）、`IdentityError`（Task 1）、infra `JwtService.generateToken(CurrentUser)`、infra `CurrentUser(Long,String,String)`、Spring `PasswordEncoder`。
- Produces:
  - infra `PasswordEncoder` Bean（`BCryptPasswordEncoder`），供 Task 3/6 注入。
  - `LoginResponse(String token, Long userId, String username, String role)`（record）。
  - `AuthService.login(String username, String password) → LoginResponse`，失败抛 `BizException(IdentityError.*)`。

- [ ] **Step 1: 写 AuthService 单元测试（先失败）**

`server/src/test/java/com/hify/identity/service/AuthServiceTest.java`：

```java
package com.hify.identity.service;

import com.hify.common.exception.BizException;
import com.hify.identity.constant.IdentityError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtProperties;
import com.hify.infra.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthService 登录逻辑单元测试：mock SysUserMapper，用真实 BCryptPasswordEncoder 与真实
 * JwtService（不连数据库）。覆盖成功 / 用户不存在 / 密码错 / 账号停用 四条路径。
 */
class AuthServiceTest {

    private SysUserMapper sysUserMapper;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-test-secret-test-secret-0123456789");
        jwtService = new JwtService(props);
        authService = new AuthService(sysUserMapper, passwordEncoder, jwtService);
    }

    private SysUser user(String username, String rawPassword, String role, UserStatus status) {
        SysUser u = new SysUser();
        u.setId(7L);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setStatus(status.value());
        return u;
    }

    @Test
    void 登录成功_返回token且能解出正确身份() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED));

        LoginResponse resp = authService.login("alice", "pw123456");

        assertNotNull(resp.token());
        assertEquals(7L, resp.userId());
        assertEquals("alice", resp.username());
        assertEquals(CurrentUser.ROLE_MEMBER, resp.role());
        // token 能被同一 JwtService 解回正确身份
        CurrentUser parsed = jwtService.parseToken(resp.token());
        assertEquals(7L, parsed.userId());
        assertEquals("alice", parsed.username());
        assertEquals(CurrentUser.ROLE_MEMBER, parsed.role());
    }

    @Test
    void 用户不存在_抛11001() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> authService.login("ghost", "pw123456"));
        assertEquals(IdentityError.BAD_CREDENTIALS, ex.errorCode());
    }

    @Test
    void 密码错_抛11001() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED));

        BizException ex = assertThrows(BizException.class, () -> authService.login("alice", "wrong-pw"));
        assertEquals(IdentityError.BAD_CREDENTIALS, ex.errorCode());
    }

    @Test
    void 账号停用_抛11002() {
        when(sysUserMapper.selectOne(any()))
                .thenReturn(user("alice", "pw123456", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED));

        // 即便密码正确，停用也先被拦下
        BizException ex = assertThrows(BizException.class, () -> authService.login("alice", "pw123456"));
        assertEquals(IdentityError.ACCOUNT_DISABLED, ex.errorCode());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AuthServiceTest test`
Expected: 编译失败（`AuthService`、`LoginResponse` 不存在）。

- [ ] **Step 3: 在 infra 新增 PasswordEncoder Bean**

修改 `server/src/main/java/com/hify/infra/security/SecurityConfig.java`，在类内新增一个 `@Bean` 方法（与既有 `securityFilterChain` 同级）。先补 import：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
```

在类体内新增：

```java
    /**
     * 密码哈希器。安全技术组件归 infra（code-organization.md：业务模块只注入 infra 的技术组件）。
     * identity 的 AuthService / AdminBootstrapRunner 注入它做 BCrypt 加密与校验。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
```

- [ ] **Step 4: 写 LoginResponse**

`server/src/main/java/com/hify/identity/dto/LoginResponse.java`：

```java
package com.hify.identity.dto;

/**
 * 登录成功响应。仅本模块 controller 用，禁止被其他模块 import。
 *
 * <p>{@code userId} 用于前端的 owner 判断（canEdit = isAdmin || ownerId === currentUserId）；
 * 这是前端内部 API（/api/v1/**），可暴露站内自增 id（"不暴露自增主键"针对的是对外 /v1/apps/**）。
 * Long 序列化为 JSON 字符串由 infra 的 Jackson 全局配置处理（防 JS 精度丢失）。
 */
public record LoginResponse(String token, Long userId, String username, String role) {
}
```

- [ ] **Step 5: 写 AuthService**

`server/src/main/java/com/hify/identity/service/AuthService.java`：

```java
package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.identity.constant.IdentityError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录业务逻辑。具体类 + {@code @Service}（不拆接口——code-organization.md 第 2 节）。
 *
 * <p>纯读 + 无外部 IO，不需要 {@code @Transactional}。失败一律抛 {@code BizException} + 11xxx 错误码，
 * 由 infra 全局异常处理器统一转信封。密码哈希校验用 infra 的 PasswordEncoder，令牌签发用 infra 的 JwtService。
 */
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        // 用户不存在与密码错返回同一错误码，不泄露账号是否存在
        if (user == null) {
            throw new BizException(IdentityError.BAD_CREDENTIALS);
        }
        if (UserStatus.DISABLED.value().equals(user.getStatus())) {
            throw new BizException(IdentityError.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(IdentityError.BAD_CREDENTIALS);
        }
        CurrentUser current = new CurrentUser(user.getId(), user.getUsername(), user.getRole());
        String token = jwtService.generateToken(current);
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getRole());
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AuthServiceTest test`
Expected: 4 条全 PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/infra/security/SecurityConfig.java \
        server/src/main/java/com/hify/identity/dto/LoginResponse.java \
        server/src/main/java/com/hify/identity/service/AuthService.java \
        server/src/test/java/com/hify/identity/service/AuthServiceTest.java
git commit -m "identity：AuthService 登录逻辑 + infra 新增 BCrypt PasswordEncoder"
```

---

### Task 4: 登录接口 AuthController

**Files:**
- Create: `server/src/main/java/com/hify/identity/dto/LoginRequest.java`
- Create: `server/src/main/java/com/hify/identity/controller/AuthController.java`
- Test: `server/src/test/java/com/hify/identity/controller/AuthControllerTest.java`
- Test: `server/src/test/java/com/hify/identity/controller/AuthLoginSecurityTest.java`

**Interfaces:**
- Consumes: `AuthService.login(String,String)`（Task 3）、`LoginResponse`（Task 3）、infra `GlobalExceptionHandler`、infra 安全栈（`SecurityConfig`/`JwtService`/`SecurityResponseWriter`/`RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`）。
- Produces: `POST /api/v1/identity/login` 接口，入参 `LoginRequest{username,password}`，返回 `Result<LoginResponse>`。

> 闭环说明：本 Task 用 `AuthControllerTest`（standaloneSetup）验证接口逻辑与错误映射，用 `AuthLoginSecurityTest`（@WebMvcTest 安全切片）验证登录路径 permitAll 可达。"令牌→受保护路由放行"的另一半闭环已由 infra 既有的 `SecurityConfigTest` 用同源 JwtService 令牌证明（member 令牌打 /api/v1/probe 放行），二者组合即完整闭环，无需连库。

- [ ] **Step 1: 写控制器测试（先失败）**

`server/src/test/java/com/hify/identity/controller/AuthControllerTest.java`：

```java
package com.hify.identity.controller;

import com.hify.identity.constant.IdentityError;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import com.hify.common.exception.BizException;
import com.hify.infra.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 逻辑测试：standaloneSetup 轻量挂载控制器 + 全局异常处理器（不启容器、不连库、不过安全链）。
 * 验证成功信封、@Valid 校验失败(10001)、业务异常(11001)→HTTP 状态映射。AuthService 被 mock。
 */
class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 登录成功_返回200与token信封() throws Exception {
        when(authService.login(eq("alice"), eq("pw123456")))
                .thenReturn(new LoginResponse("tok-123", 7L, "alice", "member"));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("tok-123"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("member"));
    }

    @Test
    void 用户名为空_400且10001() throws Exception {
        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"pw123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 登录失败_业务异常11001映射为401() throws Exception {
        when(authService.login(eq("alice"), eq("wrong")))
                .thenThrow(new BizException(IdentityError.BAD_CREDENTIALS));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(11001))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
```

`server/src/test/java/com/hify/identity/controller/AuthLoginSecurityTest.java`：

```java
package com.hify.identity.controller;

import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证登录路径 {@code /api/v1/identity/login} 在安全链上是 permitAll——不带令牌也能进到控制器。
 * 用 @WebMvcTest 装 Web+Security 切片并 @Import infra 安全栈（参照 SecurityConfigTest）；AuthService 被 mock。
 * 这一半证明"无需令牌即可登录入口可达"；"令牌→受保护路由放行"的另一半由 SecurityConfigTest 覆盖。
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AuthLoginSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void 登录入口_无令牌_放行可达控制器() throws Exception {
        when(authService.login(any(), any()))
                .thenReturn(new LoginResponse("tok-123", 7L, "alice", "member"));

        mockMvc.perform(post("/api/v1/identity/login")
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())               // 不是 401：说明 permitAll 生效
                .andExpect(jsonPath("$.data.token").value("tok-123"));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AuthControllerTest,AuthLoginSecurityTest test`
Expected: 编译失败（`AuthController`、`LoginRequest` 不存在）。

- [ ] **Step 3: 写 LoginRequest**

`server/src/main/java/com/hify/identity/dto/LoginRequest.java`：

```java
package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。校验注解只写在这里；校验失败由 Controller 的 @Valid 触发，
 * 全局异常处理器统一转 10001 + 字段错误数组。仅本模块用，禁止被其他模块 import。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
```

- [ ] **Step 4: 写 AuthController**

`server/src/main/java/com/hify/identity/controller/AuthController.java`：

```java
package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.LoginRequest;
import com.hify.identity.dto.LoginResponse;
import com.hify.identity.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口。协议转换层：只做 @Valid 校验、调用本模块 AuthService、组装 Result 返回；
 * 不写业务逻辑、不写 try-catch、不写 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 路径 {@code /api/v1/identity/login} 在 SecurityConfig 中已配置 permitAll。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request.username(), request.password()));
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AuthControllerTest,AuthLoginSecurityTest test`
Expected: AuthControllerTest 3 条 + AuthLoginSecurityTest 1 条全 PASS。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/identity/dto/LoginRequest.java \
        server/src/main/java/com/hify/identity/controller/AuthController.java \
        server/src/test/java/com/hify/identity/controller/AuthControllerTest.java \
        server/src/test/java/com/hify/identity/controller/AuthLoginSecurityTest.java
git commit -m "identity：登录接口 POST /api/v1/identity/login"
```

---

### Task 5: 首个 admin 引导 AdminBootstrapRunner

**Files:**
- Create: `server/src/main/java/com/hify/identity/config/IdentityProperties.java`
- Create: `server/src/main/java/com/hify/identity/service/AdminBootstrapRunner.java`
- Test: `server/src/test/java/com/hify/identity/service/AdminBootstrapRunnerTest.java`

**Interfaces:**
- Consumes: `SysUserMapper`（Task 2）、`UserStatus`（Task 2）、infra `CurrentUser.ROLE_ADMIN`、Spring `PasswordEncoder`（Task 3）。
- Produces:
  - `IdentityProperties(String username, String password)`（`@ConfigurationProperties("hify.identity.bootstrap-admin")`）。
  - `AdminBootstrapRunner implements ApplicationRunner`：启动时按配置幂等创建首个 admin。

- [ ] **Step 1: 写引导器单元测试（先失败）**

`server/src/test/java/com/hify/identity/service/AdminBootstrapRunnerTest.java`：

```java
package com.hify.identity.service;

import com.hify.identity.config.IdentityProperties;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminBootstrapRunner 单元测试：mock SysUserMapper，真实 BCryptPasswordEncoder（不连库）。
 * 覆盖：已配置且无该用户→建 admin；已存在→不建；未配置→不建。
 */
class AdminBootstrapRunnerTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void 已配置且库中无该用户_创建admin() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        IdentityProperties props = new IdentityProperties("root", "secret-pw");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(mapper).insert(captor.capture());
        SysUser created = captor.getValue();
        assertEquals("root", created.getUsername());
        assertEquals(CurrentUser.ROLE_ADMIN, created.getRole());
        assertEquals(UserStatus.ENABLED.value(), created.getStatus());
        assertTrue(passwordEncoder.matches("secret-pw", created.getPasswordHash()));
    }

    @Test
    void 该用户已存在_不重复创建() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectOne(any())).thenReturn(new SysUser());
        IdentityProperties props = new IdentityProperties("root", "secret-pw");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        verify(mapper, never()).insert(any());
    }

    @Test
    void 未配置用户名或密码_不创建() throws Exception {
        SysUserMapper mapper = mock(SysUserMapper.class);
        IdentityProperties props = new IdentityProperties("", "");
        AdminBootstrapRunner runner = new AdminBootstrapRunner(props, mapper, passwordEncoder);

        runner.run(null);

        verify(mapper, never()).insert(any());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AdminBootstrapRunnerTest test`
Expected: 编译失败（`IdentityProperties`、`AdminBootstrapRunner` 不存在）。

- [ ] **Step 3: 写 IdentityProperties**

`server/src/main/java/com/hify/identity/config/IdentityProperties.java`：

```java
package com.hify.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * identity 模块配置（外化到 application.yml 的 {@code hify.identity.*}）。
 *
 * <p>{@code bootstrap-admin.username/password} 用于启动时引导首个 admin 账号；其值经 application.yml
 * 引用环境变量（{@code HIFY_ADMIN_USERNAME}/{@code HIFY_ADMIN_PASSWORD}），密码不写明文（CLAUDE.md 安全要点）。
 * 由 {@link AdminBootstrapRunner} 上的 {@code @EnableConfigurationProperties} 注册绑定。
 */
@ConfigurationProperties(prefix = "hify.identity.bootstrap-admin")
public record IdentityProperties(String username, String password) {
}
```

- [ ] **Step 4: 写 AdminBootstrapRunner**

`server/src/main/java/com/hify/identity/service/AdminBootstrapRunner.java`：

```java
package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.identity.config.IdentityProperties;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时引导首个 admin 账号。admin 手动建其他人，但第一个 admin 由本引导器按 .env 配置创建。
 *
 * <p>幂等：仅当配置了用户名+密码、且库中无该用户名时才创建；已存在则跳过；未配置则告警跳过，
 * 不创建空密码账号。密码用 BCrypt 加密入库，日志不打印密码。
 */
@Component
@EnableConfigurationProperties(IdentityProperties.class)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final IdentityProperties properties;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapRunner(IdentityProperties properties, SysUserMapper sysUserMapper,
                                PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = properties.username();
        String password = properties.password();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("未配置 hify.identity.bootstrap-admin.username/password，跳过初始 admin 引导");
            return;
        }
        SysUser existing = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            log.info("初始 admin [{}] 已存在，跳过引导", username);
            return;
        }
        SysUser admin = new SysUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setRole(CurrentUser.ROLE_ADMIN);
        admin.setStatus(UserStatus.ENABLED.value());
        sysUserMapper.insert(admin);
        log.info("已创建初始 admin 账号 [{}]", username);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AdminBootstrapRunnerTest test`
Expected: 3 条全 PASS。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/identity/config/IdentityProperties.java \
        server/src/main/java/com/hify/identity/service/AdminBootstrapRunner.java \
        server/src/test/java/com/hify/identity/service/AdminBootstrapRunnerTest.java
git commit -m "identity：首个 admin 启动引导（.env 驱动，幂等）"
```

---

### Task 6: 配置、文档与全量回归收尾

**Files:**
- Modify: `server/src/main/resources/application.yml`（新增 `hify.identity.bootstrap-admin`）
- Modify: `deploy/.env.example`（新增 admin 引导环境变量）
- Modify: `docs/architecture/api-standards.md`（5.3 节模块段示例补 11001/11002）
- Modify: `docs/self-check.md`（追加本轮自检条目）

- [ ] **Step 1: application.yml 新增 identity 引导配置**

在 `server/src/main/resources/application.yml` 的 `hify:` 节点下（与 `api`/`cache`/`security`/`async` 同级）新增：

```yaml
  identity:
    bootstrap-admin:
      # 首个 admin 账号引导：值走 .env，不配则启动时告警跳过（不创建空密码账号）。
      username: ${HIFY_ADMIN_USERNAME:}
      password: ${HIFY_ADMIN_PASSWORD:}
```

- [ ] **Step 2: deploy/.env.example 新增 admin 变量**

在 `deploy/.env.example` 末尾追加：

```bash

# --- 首个 admin 账号引导（仅空库首次启动时创建一次；之后由 admin 在后台建其他账号）---
# 不配则跳过引导。生产务必改成强密码。
HIFY_ADMIN_USERNAME=admin
HIFY_ADMIN_PASSWORD=change-me-on-first-deploy
```

- [ ] **Step 3: api-standards.md 登记 11xxx 错误码**

在 `docs/architecture/api-standards.md` 第 5.3 节"模块段示例"段落中补一行 identity 示例（紧接 `14001/...` 那段，保持风格一致）：

```
`11001/401` 用户名或密码错误；`11002/403` 账号已停用；
```

- [ ] **Step 4: 追加自检条目到 docs/self-check.md**

在 `docs/self-check.md` 末尾追加本轮（identity 登录闭环）的自检小节：覆盖"建表迁移 V4 / 登录接口 / 首个 admin 引导"三件事，列出如何手动冒烟验证（配 `.env` 的 admin 变量→启动→`POST /api/v1/identity/login` 拿 token→带 token 打一个受保护路径非 401）。按 `docs/self-check.md` 既有小节的格式书写。

- [ ] **Step 5: 全量回归**

Run: `mvn -f server/pom.xml test`
Expected: 全量 PASS（含 ModularityTests、LayerRulesTest、CommonErrorTest 及本轮新增的 identity 全部测试）。注意看测试摘要 `Tests run: N, Failures: 0, Errors: 0`，不要用 `-q`。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/resources/application.yml \
        deploy/.env.example \
        docs/architecture/api-standards.md \
        docs/self-check.md
git commit -m "identity：登录闭环配置/文档收尾 + 全量回归"
```

---

## 完成标准（Definition of Done）

- `mvn -f server/pom.xml test` 全绿，含模块边界（Modulith）、分层（ArchUnit）。
- 配置 `.env` 的 `HIFY_ADMIN_USERNAME/PASSWORD` 后启动应用，`sys_user` 表自动建好、首个 admin 自动创建。
- `POST /api/v1/identity/login` 用该 admin 凭据返回 `Result<LoginResponse>{token,...}`；错误凭据返回 401/11001；停用账号返回 403/11002。
- 该 token 可通过 infra `JwtAuthenticationFilter` 验票进入受保护路由（由 SecurityConfigTest 同源令牌机制保证）。
- 本轮不含：admin 用户管理接口、自助查询/改密、前端登录页、Testcontainers 连库测试（均留待后续轮次）。
