# Admin 用户管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** identity 模块新增 admin 专属的用户管理后端：创建、列表、停用/启用、重置密码、改角色、删除（软删）共 7 个接口。

**Architecture:** 一个 `AdminUserController`（协议层）+ 一个 `AdminUserService`（业务层），挂在 `/api/v1/admin/identity/users` 下，复用已落地的 `sys_user` 表、`SysUserMapper`（MyBatis-Plus BaseMapper）、infra 的 `PasswordEncoder` 与 JWT 安全栈。一条统一护栏 `assertNotLastEnabledAdmin` 防止停用/降级/删除掉系统里最后一个启用的 admin。零 DDL、零新依赖、不连真库（纯 mock 单元测试 + MockMvc 安全切片测试）。

**Tech Stack:** Spring Boot 3.5 / Java 21 / Spring Security（JWT）/ MyBatis-Plus / JUnit5 + Mockito + MockMvc。

**关联设计稿：** `docs/superpowers/specs/2026-06-23-admin-user-management-design.md`

## Global Constraints

- 路由前缀 `/api/v1/admin/identity/users`（admin 路由族带模块段；落 SecurityConfig 既有 `/api/v1/admin/**` → `hasRole("ADMIN")`，**不动 SecurityConfig**）。
- **不用 PATCH**：停用/启用是动作子资源 `POST .../enable`、`POST .../disable`；改角色/重置密码用 `PUT .../role`、`PUT .../password`（单字段子资源全量替换）。
- Long 一律由 infra 全局 Jackson 序列化为 JSON **字符串**；DTO 上**禁止**写 `@JsonFormat`/`@JsonInclude`。
- 时间用 `OffsetDateTime`（ISO-8601 带时区，infra 全局序列化）。
- 错误码优先复用通用段：用户名重复 `CommonError.CONFLICT`(10006)、用户不存在 `CommonError.NOT_FOUND`(10005)；本轮只新增 identity 段 `CANNOT_REMOVE_LAST_ADMIN`(11003)。
- 校验注解只写在请求 DTO；service 不重复 Web 层校验。
- 响应里**绝不**出现 `passwordHash`。
- 业务失败一律 `throw new BizException(错误码[, 自定义 message])`；Controller 不写 try-catch、不手写失败 Result。
- 写操作 service 方法加 `@Transactional`（单表写；BCrypt 是纯 CPU 非外部 IO，可在事务内）。
- 角色取值常量用 `CurrentUser.ROLE_ADMIN` / `CurrentUser.ROLE_MEMBER`（"admin"/"member"）；状态用 `UserStatus.ENABLED` / `UserStatus.DISABLED`（值 "enabled"/"disabled"）。
- 后端测试命令用 `mvn -f server/pom.xml ...`，**不要加 `-q`**（会静音测试摘要，判定结果看 surefire 汇总 `Tests run / Failures / Errors`）。

---

### Task 1: 错误码 + `UserView` DTO + `AdminUserService` 的 create/list

**Files:**
- Modify: `server/src/main/java/com/hify/identity/constant/IdentityError.java`
- Create: `server/src/main/java/com/hify/identity/dto/UserView.java`
- Create: `server/src/main/java/com/hify/identity/service/AdminUserService.java`
- Test: `server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java`

**Interfaces:**
- Consumes: `SysUserMapper extends BaseMapper<SysUser>`；`SysUser`（getter/setter：id/username/passwordHash/role/status/createTime）；`PasswordEncoder`（infra）；`CurrentUser.ROLE_ADMIN/ROLE_MEMBER`；`UserStatus.ENABLED/DISABLED`；`BizException(ErrorCode[, String])`；`CommonError.CONFLICT`。
- Produces:
  - `IdentityError.CANNOT_REMOVE_LAST_ADMIN`（11003 / 409）。
  - `UserView(Long id, String username, String role, String status, OffsetDateTime createTime)`，静态工厂 `UserView.from(SysUser)`。
  - `AdminUserService`，方法 `UserView create(String username, String rawPassword, String role)`、`List<UserView> list()`。

- [ ] **Step 1: 加错误码 11003**

`server/src/main/java/com/hify/identity/constant/IdentityError.java`，在 `ACCOUNT_DISABLED` 那条后面追加一个枚举常量（注意把上一条的结尾分号改成逗号）：

```java
    /** 账号已停用：用户存在且密码可能正确，但被管理员停用，禁止登录。 */
    ACCOUNT_DISABLED(11002, HttpStatus.FORBIDDEN, "账号已停用"),
    /** 不能移除最后一个启用的管理员（停用/降级/删除三处共用）：系统须至少保留一个可用 admin。 */
    CANNOT_REMOVE_LAST_ADMIN(11003, HttpStatus.CONFLICT, "系统至少保留一个启用的管理员");
```

- [ ] **Step 2: 写 `UserView` DTO**

`server/src/main/java/com/hify/identity/dto/UserView.java`：

```java
package com.hify.identity.dto;

import com.hify.identity.entity.SysUser;

import java.time.OffsetDateTime;

/**
 * admin 用户管理的统一响应视图。仅本模块用，禁止被其他模块 import。
 *
 * <p>刻意<b>不含</b> passwordHash——密码哈希绝不出响应。id 为 Long，经 infra Jackson 全局配置
 * 序列化为 JSON 字符串；createTime 为 ISO-8601 带时区。字段均不加局部序列化注解（全局兜底）。
 */
public record UserView(Long id, String username, String role, String status, OffsetDateTime createTime) {

    /** 从实体投影为视图，集中"挑哪些字段对外"的决定（passwordHash 不在内）。 */
    public static UserView from(SysUser u) {
        return new UserView(u.getId(), u.getUsername(), u.getRole(), u.getStatus(), u.getCreateTime());
    }
}
```

- [ ] **Step 3: 写 create/list 的失败测试**

`server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java`（仿 `AuthServiceTest`：mock `SysUserMapper`，用真实 `BCryptPasswordEncoder`，不连库）：

```java
package com.hify.identity.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.identity.dto.UserView;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import com.hify.identity.constant.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserService 单元测试：mock SysUserMapper，用真实 BCryptPasswordEncoder，不连库。
 * 覆盖创建/列表/停用启用/重置/改角色/删除全部分支与「最后一个启用 admin」护栏。
 */
class AdminUserServiceTest {

    private SysUserMapper mapper;
    private PasswordEncoder encoder;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SysUserMapper.class);
        encoder = new BCryptPasswordEncoder();
        service = new AdminUserService(mapper, encoder);
    }

    private SysUser user(long id, String username, String role, UserStatus status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash(encoder.encode("init-pw-1234"));
        u.setRole(role);
        u.setStatus(status.value());
        u.setCreateTime(OffsetDateTime.now());
        return u;
    }

    @Test
    void 创建用户_密码被哈希且状态默认启用() {
        when(mapper.selectCount(any())).thenReturn(0L); // 无重名
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);

        UserView view = service.create("alice", "rawpw1234", CurrentUser.ROLE_MEMBER);

        verify(mapper).insert(captor.capture());
        SysUser saved = captor.getValue();
        assertEquals("alice", saved.getUsername());
        assertEquals(CurrentUser.ROLE_MEMBER, saved.getRole());
        assertEquals(UserStatus.ENABLED.value(), saved.getStatus());
        assertNotEquals("rawpw1234", saved.getPasswordHash());          // 不是明文
        assertTrue(encoder.matches("rawpw1234", saved.getPasswordHash())); // 是该明文的 BCrypt 哈希
        assertEquals("alice", view.username());
    }

    @Test
    void 创建用户_重名_抛CONFLICT() {
        when(mapper.selectCount(any())).thenReturn(1L); // 已存在同名

        BizException ex = assertThrows(BizException.class,
                () -> service.create("alice", "rawpw1234", CurrentUser.ROLE_MEMBER));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 列表_按返回投影且不含密码哈希() {
        when(mapper.selectList(any())).thenReturn(List.of(
                user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED),
                user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED)));

        List<UserView> list = service.list();

        assertEquals(2, list.size());
        assertEquals("alice", list.get(0).username());
        assertEquals(CurrentUser.ROLE_ADMIN, list.get(0).role());
        assertEquals(UserStatus.DISABLED.value(), list.get(1).status());
    }
}
```

- [ ] **Step 4: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 编译失败（`AdminUserService` 不存在）。

- [ ] **Step 5: 写 `AdminUserService` 的 create/list**

`server/src/main/java/com/hify/identity/service/AdminUserService.java`：

```java
package com.hify.identity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.identity.constant.UserStatus;
import com.hify.identity.dto.UserView;
import com.hify.identity.entity.SysUser;
import com.hify.identity.mapper.SysUserMapper;
import com.hify.infra.security.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * admin 用户管理业务逻辑（具体类 + @Service，不拆接口——code-organization.md 第 2 节）。
 * 注入 SysUserMapper 与 infra 的 PasswordEncoder；失败一律抛 BizException 交全局处理器转信封。
 */
@Service
public class AdminUserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(SysUserMapper sysUserMapper, PasswordEncoder passwordEncoder) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView create(String username, String rawPassword, String role) {
        long sameName = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (sameName > 0) {
            throw new BizException(CommonError.CONFLICT, "用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setRole(role);
        u.setStatus(UserStatus.ENABLED.value());
        sysUserMapper.insert(u);
        return UserView.from(u);
    }

    public List<UserView> list() {
        List<SysUser> users = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getCreateTime));
        return users.stream().map(UserView::from).toList();
    }
}
```

> 注：`@TableLogic` 使上述 selectCount/selectList 自动带 `where deleted=false`，无需手写。
> `insert` 后 MyBatis-Plus 回填自增 id 到实体（`useGeneratedKeys`），故 `UserView.from(u)` 能拿到 id；
> createTime 由 infra MetaObjectHandler 在 insert 时填充。

- [ ] **Step 6: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 3 个测试 PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/identity/constant/IdentityError.java \
        server/src/main/java/com/hify/identity/dto/UserView.java \
        server/src/main/java/com/hify/identity/service/AdminUserService.java \
        server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java
git commit -m "identity：AdminUserService 创建/列表 + UserView + 错误码 11003"
```

---

### Task 2: 停用/启用 + 统一护栏 `assertNotLastEnabledAdmin`

**Files:**
- Modify: `server/src/main/java/com/hify/identity/service/AdminUserService.java`
- Test: `server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `AdminUserService`、`UserView`、`IdentityError.CANNOT_REMOVE_LAST_ADMIN`；`CommonError.NOT_FOUND`。
- Produces: `AdminUserService` 新增 `UserView disable(Long id)`、`UserView enable(Long id)`，私有 `assertNotLastEnabledAdmin(SysUser target)`（后续 Task 3 的 changeRole/delete 复用）。

- [ ] **Step 1: 追加停用/启用的失败测试**

在 `AdminUserServiceTest` 类内追加（沿用 `user(...)` 辅助方法）：

```java
    @Test
    void 停用_尚有其他启用admin_成功() {
        SysUser admin = user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED);
        when(mapper.selectById(1L)).thenReturn(admin);
        when(mapper.selectCount(any())).thenReturn(2L); // 启用 admin 共 2 个

        UserView view = service.disable(1L);

        assertEquals(UserStatus.DISABLED.value(), view.status());
        verify(mapper).updateById(any(SysUser.class));
    }

    @Test
    void 停用_最后一个启用admin_抛11003() {
        SysUser admin = user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED);
        when(mapper.selectById(1L)).thenReturn(admin);
        when(mapper.selectCount(any())).thenReturn(1L); // 它是最后一个启用 admin

        BizException ex = assertThrows(BizException.class, () -> service.disable(1L));
        assertEquals(com.hify.identity.constant.IdentityError.CANNOT_REMOVE_LAST_ADMIN, ex.errorCode());
    }

    @Test
    void 停用_member不触发护栏_成功() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        UserView view = service.disable(2L);

        assertEquals(UserStatus.DISABLED.value(), view.status());
    }

    @Test
    void 停用_已停用_幂等不报错() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        UserView view = service.disable(2L);

        assertEquals(UserStatus.DISABLED.value(), view.status());
    }

    @Test
    void 启用_成功() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.DISABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        UserView view = service.enable(2L);

        assertEquals(UserStatus.ENABLED.value(), view.status());
    }

    @Test
    void 停用_用户不存在_抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.disable(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 编译失败（`disable`/`enable` 不存在）。

- [ ] **Step 3: 实现停用/启用 + 护栏**

在 `AdminUserService` 类内追加。Task 1 已 import 的（LambdaQueryWrapper / BizException / CommonError / UserStatus / SysUser / Service / Transactional 等）继续沿用；本任务**新增两个 import**：

```java
import com.hify.identity.constant.IdentityError;
import com.hify.infra.security.CurrentUser;
```

方法体：

```java
    @Transactional
    public UserView disable(Long id) {
        SysUser user = require(id);
        if (UserStatus.DISABLED.value().equals(user.getStatus())) {
            return UserView.from(user); // 幂等：已停用直接返回，不触发护栏
        }
        assertNotLastEnabledAdmin(user);
        user.setStatus(UserStatus.DISABLED.value());
        sysUserMapper.updateById(user);
        return UserView.from(user);
    }

    @Transactional
    public UserView enable(Long id) {
        SysUser user = require(id);
        user.setStatus(UserStatus.ENABLED.value()); // 启用不破坏不变量，无护栏；已启用再设也幂等
        sysUserMapper.updateById(user);
        return UserView.from(user);
    }

    /** 按 id 取用户，不存在则 404。@TableLogic 保证 selectById 只命中未软删的记录。 */
    private SysUser require(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(CommonError.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 统一护栏：若 target 当前是「启用的 admin」且系统里启用 admin 仅剩它一个，拒绝（11003）。
     * 停用 / 降级（admin→member）/ 删除三处在执行前都先调它，保证至少保留一个可用 admin。
     */
    private void assertNotLastEnabledAdmin(SysUser target) {
        boolean isEnabledAdmin = CurrentUser.ROLE_ADMIN.equals(target.getRole())
                && UserStatus.ENABLED.value().equals(target.getStatus());
        if (!isEnabledAdmin) {
            return;
        }
        long enabledAdmins = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, CurrentUser.ROLE_ADMIN)
                .eq(SysUser::getStatus, UserStatus.ENABLED.value()));
        if (enabledAdmins <= 1) {
            throw new BizException(IdentityError.CANNOT_REMOVE_LAST_ADMIN);
        }
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 全部 PASS（Task 1 的 3 条 + 本任务 6 条 = 9 条）。

- [ ] **Step 5: 故意搞错验证护栏（手动，验完恢复）**

把 `assertNotLastEnabledAdmin` 的 `if (enabledAdmins <= 1)` 临时改成 `if (false)`，重跑：
Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: `停用_最后一个启用admin_抛11003` 变红（护栏被架空时会漏过）。**确认变红后改回 `<= 1`** 并重跑确认全绿。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/identity/service/AdminUserService.java \
        server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java
git commit -m "identity：AdminUserService 停用/启用 + 统一护栏 assertNotLastEnabledAdmin"
```

---

### Task 3: 重置密码 / 改角色 / 删除

**Files:**
- Modify: `server/src/main/java/com/hify/identity/service/AdminUserService.java`
- Test: `server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 `require(Long)`、`assertNotLastEnabledAdmin(SysUser)`。
- Produces: `AdminUserService` 新增 `void resetPassword(Long id, String rawPassword)`、`UserView changeRole(Long id, String role)`、`void delete(Long id)`。

- [ ] **Step 1: 追加失败测试**

在 `AdminUserServiceTest` 类内追加：

```java
    @Test
    void 重置密码_写入哈希值() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED);
        when(mapper.selectById(2L)).thenReturn(member);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);

        service.resetPassword(2L, "newpw5678");

        verify(mapper).updateById(captor.capture());
        assertTrue(encoder.matches("newpw5678", captor.getValue().getPasswordHash()));
    }

    @Test
    void 改角色_升级member为admin_无护栏成功() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        UserView view = service.changeRole(2L, CurrentUser.ROLE_ADMIN);

        assertEquals(CurrentUser.ROLE_ADMIN, view.role());
    }

    @Test
    void 改角色_降级最后一个启用admin_抛11003() {
        SysUser admin = user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED);
        when(mapper.selectById(1L)).thenReturn(admin);
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.changeRole(1L, CurrentUser.ROLE_MEMBER));
        assertEquals(com.hify.identity.constant.IdentityError.CANNOT_REMOVE_LAST_ADMIN, ex.errorCode());
    }

    @Test
    void 改角色_同角色_幂等不写库() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        UserView view = service.changeRole(2L, CurrentUser.ROLE_MEMBER);

        assertEquals(CurrentUser.ROLE_MEMBER, view.role());
        verify(mapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void 删除_最后一个启用admin_抛11003() {
        SysUser admin = user(1L, "alice", CurrentUser.ROLE_ADMIN, UserStatus.ENABLED);
        when(mapper.selectById(1L)).thenReturn(admin);
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(com.hify.identity.constant.IdentityError.CANNOT_REMOVE_LAST_ADMIN, ex.errorCode());
    }

    @Test
    void 删除_普通用户_走软删() {
        SysUser member = user(2L, "bob", CurrentUser.ROLE_MEMBER, UserStatus.ENABLED);
        when(mapper.selectById(2L)).thenReturn(member);

        service.delete(2L);

        verify(mapper).deleteById(2L);
    }

    @Test
    void 删除_用户不存在_幂等成功不抛() {
        when(mapper.selectById(99L)).thenReturn(null);

        service.delete(99L); // 不抛异常即通过

        verify(mapper, org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 编译失败（`resetPassword`/`changeRole`/`delete` 不存在）。

- [ ] **Step 3: 实现三个方法**

在 `AdminUserService` 类内追加：

```java
    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        SysUser user = require(id);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        sysUserMapper.updateById(user);
    }

    @Transactional
    public UserView changeRole(Long id, String role) {
        SysUser user = require(id);
        if (role.equals(user.getRole())) {
            return UserView.from(user); // 同角色幂等，不写库
        }
        assertNotLastEnabledAdmin(user); // 仅 admin→member 降级会命中护栏内部条件；member→admin 为 no-op
        user.setRole(role);
        sysUserMapper.updateById(user);
        return UserView.from(user);
    }

    @Transactional
    public void delete(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            return; // 幂等：删不存在/已软删的也算成功（api-standards 第 2.2 节）
        }
        assertNotLastEnabledAdmin(user);
        sysUserMapper.deleteById(id); // @TableLogic → update set deleted=true
    }
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AdminUserServiceTest test`
Expected: 全部 PASS（累计 16 条）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/hify/identity/service/AdminUserService.java \
        server/src/test/java/com/hify/identity/service/AdminUserServiceTest.java
git commit -m "identity：AdminUserService 重置密码/改角色/删除（复用护栏）"
```

---

### Task 4: 请求 DTO + `AdminUserController` + 安全切片测试

**Files:**
- Create: `server/src/main/java/com/hify/identity/dto/CreateUserRequest.java`
- Create: `server/src/main/java/com/hify/identity/dto/ResetPasswordRequest.java`
- Create: `server/src/main/java/com/hify/identity/dto/ChangeRoleRequest.java`
- Create: `server/src/main/java/com/hify/identity/controller/AdminUserController.java`
- Test: `server/src/test/java/com/hify/identity/controller/AdminUserControllerTest.java`

**Interfaces:**
- Consumes: Task 1-3 的 `AdminUserService`（create/list/disable/enable/resetPassword/changeRole/delete）、`UserView`；`Result.ok(...)`；infra 安全栈（`SecurityConfig`/`JwtService`/`SecurityResponseWriter`/`RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`）；`JacksonConfig`；`CurrentUser`。
- Produces: 7 个 REST 端点（见 Global Constraints 路由表）。

- [ ] **Step 1: 写三个请求 DTO**

`server/src/main/java/com/hify/identity/dto/CreateUserRequest.java`：

```java
package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建用户请求。校验只在此 DTO（api-standards 第 4 节）：用户名 ≤50（对齐 DB check）、
 * 密码 8~72（72 为 BCrypt 字节上限，超出会被静默截断，挡在入口）、角色限 admin|member。
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 50) String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Pattern(regexp = "admin|member") String role) {
}
```

`server/src/main/java/com/hify/identity/dto/ResetPasswordRequest.java`：

```java
package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 重置密码请求。密码规则同创建。 */
public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String password) {
}
```

`server/src/main/java/com/hify/identity/dto/ChangeRoleRequest.java`：

```java
package com.hify.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 改角色请求。角色限 admin|member。 */
public record ChangeRoleRequest(@NotBlank @Pattern(regexp = "admin|member") String role) {
}
```

- [ ] **Step 2: 写 Controller 安全切片测试（先失败）**

`server/src/test/java/com/hify/identity/controller/AdminUserControllerTest.java`（仿 `CurrentUserControllerTest`：装 Web+Security 切片 + infra 安全栈 + JacksonConfig，真 `JwtService` 签 token，`AdminUserService` 用 `@MockitoBean`）：

```java
package com.hify.identity.controller;

import com.hify.identity.dto.UserView;
import com.hify.identity.service.AdminUserService;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AdminUserService adminUserService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private UserView sample() {
        return new UserView(7L, "alice", CurrentUser.ROLE_MEMBER, "enabled", OffsetDateTime.now());
    }

    @Test
    void 创建用户_admin_200且id为字符串且无密码哈希() throws Exception {
        when(adminUserService.create(eq("alice"), any(), eq("member"))).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"rawpw1234\",\"role\":\"member\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("7"))             // Long→字符串
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()); // 不泄露哈希
    }

    @Test
    void 列表_admin_200() throws Exception {
        when(adminUserService.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("alice"));
    }

    @Test
    void 停用_admin_200() throws Exception {
        when(adminUserService.disable(7L)).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 启用_admin_200() throws Exception {
        when(adminUserService.enable(7L)).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/identity/users/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 重置密码_admin_200() throws Exception {
        mockMvc.perform(put("/api/v1/admin/identity/users/7/password")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"password\":\"newpw5678\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 改角色_admin_200() throws Exception {
        when(adminUserService.changeRole(eq(7L), eq("admin"))).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/identity/users/7/role")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除_admin_200() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/identity/users/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 创建用户_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"rawpw1234\",\"role\":\"member\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 列表_无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/admin/identity/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void 创建用户_校验失败_400且10001带字段数组() throws Exception {
        mockMvc.perform(post("/api/v1/admin/identity/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"short\",\"role\":\"boss\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=AdminUserControllerTest test`
Expected: 编译失败（`AdminUserController` 不存在）。

- [ ] **Step 4: 写 `AdminUserController`**

`server/src/main/java/com/hify/identity/controller/AdminUserController.java`：

```java
package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.ChangeRoleRequest;
import com.hify.identity.dto.CreateUserRequest;
import com.hify.identity.dto.ResetPasswordRequest;
import com.hify.identity.dto.UserView;
import com.hify.identity.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * admin 用户管理接口（仅 Admin）。协议层：@Valid 校验 → 调 AdminUserService → 包 Result；
 * 无业务逻辑、无 try-catch、无 @Transactional、不注入 Mapper（code-organization.md 第 2 节）。
 * 路径在 /api/v1/admin/** 下，由 SecurityConfig 的 hasRole("ADMIN") 统一拦截，无需类上再加注解。
 */
@RestController
@RequestMapping("/api/v1/admin/identity/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @PostMapping
    public Result<UserView> create(@Valid @RequestBody CreateUserRequest req) {
        return Result.ok(adminUserService.create(req.username(), req.password(), req.role()));
    }

    @GetMapping
    public Result<List<UserView>> list() {
        return Result.ok(adminUserService.list());
    }

    @PostMapping("/{id}/enable")
    public Result<UserView> enable(@PathVariable Long id) {
        return Result.ok(adminUserService.enable(id));
    }

    @PostMapping("/{id}/disable")
    public Result<UserView> disable(@PathVariable Long id) {
        return Result.ok(adminUserService.disable(id));
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        adminUserService.resetPassword(id, req.password());
        return Result.ok(null);
    }

    @PutMapping("/{id}/role")
    public Result<UserView> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest req) {
        return Result.ok(adminUserService.changeRole(id, req.role()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminUserService.delete(id);
        return Result.ok(null);
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `mvn -f server/pom.xml -Dtest=AdminUserControllerTest test`
Expected: 全部 PASS（10 条）。

- [ ] **Step 6: 全量回归 + 边界校验**

Run: `mvn -f server/pom.xml test`
Expected: `BUILD SUCCESS`，`Failures: 0, Errors: 0`；含 `ModularityTests`、`LayerRulesTest` 全绿（确认新 controller/service/dto 放对层、无跨模块越界）。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/identity/dto/CreateUserRequest.java \
        server/src/main/java/com/hify/identity/dto/ResetPasswordRequest.java \
        server/src/main/java/com/hify/identity/dto/ChangeRoleRequest.java \
        server/src/main/java/com/hify/identity/controller/AdminUserController.java \
        server/src/test/java/com/hify/identity/controller/AdminUserControllerTest.java
git commit -m "identity：AdminUserController 7 接口 + 请求 DTO + 安全切片测试"
```

---

## Definition of Done

- 7 个接口按契约可用：`POST/GET /users`、`POST /users/{id}/enable|disable`、`PUT /users/{id}/password|role`、`DELETE /users/{id}`。
- 鉴权：admin 通、member 403/10004、匿名 401/10002；校验失败 400/10001 带字段数组。
- 统一护栏覆盖停用/降级/删除三处，「至少保留一个启用 admin」成立且测到（含故意搞错验证）。
- 响应不含 `passwordHash`；id 为字符串；时间 ISO-8601 带时区。
- 仅新增错误码 `IdentityError.CANNOT_REMOVE_LAST_ADMIN`(11003)，其余复用通用段。
- `mvn -f server/pom.xml test` 全量绿（含 Modularity/ArchUnit）。
- 不含：前端页、用户自助改密、Testcontainers 连库测试。

## 自检追加（self-check.md）

实现完成后，按既有惯例把本轮自检步骤追加到 `docs/self-check.md`（自动化：`mvn -f server/pom.xml test` 全绿；运行时冒烟：用 admin token `curl` 走通 7 接口，member token 验 403，停最后一个 admin 验 11003）。
