# 前端登录接通后端 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐后端 `GET /api/v1/identity/me`（前端登录链路依赖、上一轮缺的那块），让已写好的前端登录端到端跑通。

**Architecture:** identity 模块新增一个受保护的只读接口 `/me`，直接回显 `infra.CurrentUserHolder.current()`（JWT 解析结果），零 DB、无 service、无 SecurityConfig 改动。前端代码不改，仅核对契约并重跑测试，最后真机端到端冒烟。

**Tech Stack:** 后端 Spring Boot 3.5.6 / Java 21 / Spring Security（JWT）/ JUnit5+MockMvc 切片测；前端 Vue 3 + TS + Vite + Pinia + vitest（pnpm）。

**关联设计稿：** `docs/superpowers/specs/2026-06-22-frontend-login-wiring-design.md`

## Global Constraints

- `/me` 数据取自 `com.hify.infra.security.CurrentUserHolder.current()`（返回 `CurrentUser(Long userId, String username, String role)`），**不查库、不加 service**。
- `MeResponse` 为 record `(Long id, String username, String role)`；`id = CurrentUser.userId()`、`username = .username()`、`role = .role()`。
- `id` 是 Long，经 infra `JacksonConfig` 全局序列化为 **JSON 字符串**（防 JS 精度丢失），对齐前端 `UserInfo { id: string }`。
- `/me` 是受保护路由（不在 `SecurityConfig` permitAll 名单内，默认 `authenticated()`）——**不得改 SecurityConfig**。
- Controller 只做协议层：读安全上下文 → 组装 `Result<MeResponse>` 返回；无业务逻辑、无 `@Transactional`、不注入 mapper。
- 不新增任何依赖；**前端不改代码**。
- 后端测试：`mvn -f server/pom.xml ...`（不要用 `-q`，会静音测试摘要）。前端：`cd web && pnpm ...`。

---

### Task 1: 后端 `GET /api/v1/identity/me`

**Files:**
- Create: `server/src/main/java/com/hify/identity/dto/MeResponse.java`
- Create: `server/src/main/java/com/hify/identity/controller/CurrentUserController.java`
- Test: `server/src/test/java/com/hify/identity/controller/CurrentUserControllerTest.java`

**Interfaces:**
- Consumes: infra `CurrentUserHolder.current() → CurrentUser`；`CurrentUser(Long userId, String username, String role)`，常量 `ROLE_ADMIN="admin"`/`ROLE_MEMBER="member"`；infra 安全栈（`SecurityConfig`/`JwtService`/`SecurityResponseWriter`/`RestAuthenticationEntryPoint`/`RestAccessDeniedHandler`）；infra `JacksonConfig`；`com.hify.common.Result`。
- Produces: `GET /api/v1/identity/me` → `Result<MeResponse>`，`MeResponse(Long id, String username, String role)`。

- [ ] **Step 1: 写切片测试（先失败）**

`server/src/test/java/com/hify/identity/controller/CurrentUserControllerTest.java`：

```java
package com.hify.identity.controller;

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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/identity/me 的安全切片测试（仿 AuthLoginSecurityTest）：装 Web+Security 切片并导入 infra
 * 安全栈与 JacksonConfig（后者保证 Long→字符串的全局序列化，验证前端依赖的 id 为字符串这一契约）。
 * 用真实 JwtService 签 token，验证「带令牌→回显身份」「无令牌→401/10002」。不连数据库。
 */
@WebMvcTest(CurrentUserController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class CurrentUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void 带有效令牌_回显当前用户身份() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(7L, "alice", CurrentUser.ROLE_ADMIN));

        mockMvc.perform(get("/api/v1/identity/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("7"))            // Long 序列化为字符串（前端契约）
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.role").value("admin"));
    }

    @Test
    void 无令牌_401且10002() throws Exception {
        mockMvc.perform(get("/api/v1/identity/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `mvn -f server/pom.xml -Dtest=CurrentUserControllerTest test`
Expected: 编译失败（`CurrentUserController`、`MeResponse` 不存在）。

- [ ] **Step 3: 写 MeResponse**

`server/src/main/java/com/hify/identity/dto/MeResponse.java`：

```java
package com.hify.identity.dto;

/**
 * GET /api/v1/identity/me 的响应：当前登录用户身份。仅本模块用，禁止被其他模块 import。
 *
 * <p>{@code id} 来自 JWT 里的 userId（Long），经 infra JacksonConfig 全局序列化为 JSON 字符串
 * （防 JS 2^53 精度丢失），对齐前端 UserInfo{ id: string, username, role }。
 */
public record MeResponse(Long id, String username, String role) {
}
```

- [ ] **Step 4: 写 CurrentUserController**

`server/src/main/java/com/hify/identity/controller/CurrentUserController.java`：

```java
package com.hify.identity.controller;

import com.hify.common.Result;
import com.hify.identity.dto.MeResponse;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户接口。GET /api/v1/identity/me：回显「我是谁」。
 *
 * <p>纯协议层：直接读安全上下文里的当前用户（CurrentUserHolder.current()，由 JWT 解析而来）组装响应，
 * 无业务逻辑、无 service、无 DB（身份信息本就在令牌里）。与 AuthController（/login，匿名）分开：
 * 本接口是受保护路由（不在 SecurityConfig permitAll 内，默认要求已认证）。
 */
@RestController
@RequestMapping("/api/v1/identity")
public class CurrentUserController {

    @GetMapping("/me")
    public Result<MeResponse> me() {
        CurrentUser current = CurrentUserHolder.current();
        return Result.ok(new MeResponse(current.userId(), current.username(), current.role()));
    }
}
```

- [ ] **Step 5: 运行测试 + 边界校验，确认通过**

Run: `mvn -f server/pom.xml -Dtest=CurrentUserControllerTest,ModularityTests,LayerRulesTest test`
Expected: 全 PASS（切片测 2 条；Modularity/ArchUnit 确认无越界、新 controller 放对层）。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/identity/dto/MeResponse.java \
        server/src/main/java/com/hify/identity/controller/CurrentUserController.java \
        server/src/test/java/com/hify/identity/controller/CurrentUserControllerTest.java
git commit -m "identity：补 GET /api/v1/identity/me（从 JWT 回显当前用户）"
```

---

### Task 2: 前端契约核对与回归

**Files:**
- 无代码改动（除非 Step 2 暴露契约不符才最小修正）。
- 涉及（只读核对）：`web/src/api/auth.ts`、`web/src/types/user.ts`、`web/src/stores/user.ts`、`web/src/views/login/LoginView.vue`。

**Interfaces:**
- Consumes: Task 1 的 `GET /api/v1/identity/me` 返回 `{ id, username, role }`（id 为字符串）。
- Produces: 确认前端登录链路（含 mock 测试）与真实后端契约一致、编译通过。

> 本任务是验证门：前端代码上一轮已写好。预期**无需改动**。`pnpm test` 是 mock 后端的单元/集成测试，`pnpm build` 顺带做 `vue-tsc` 类型检查（能抓出契约类型不符）。仅当出现真实失败时才做最小修正并提交，否则本任务只产出「绿」的确认、无提交。

- [ ] **Step 1: 核对契约（只读）**

确认以下三处与 Task 1 的 `/me` 契约一致（应当已经一致，无需改）：
- `web/src/types/user.ts` 的 `UserInfo { id: string; username: string; role: 'admin'|'member' }` ↔ 后端 `MeResponse{ id(字符串), username, role }`。
- `web/src/api/auth.ts` 的 `getCurrentUser()` 请求 `GET /identity/me`（baseURL 已含 `/api/v1`）。
- `web/src/views/login/LoginView.vue` 流程：`login()` 取 `{ token }` → `setToken` → `loadCurrentUser()`（即 `/me`）→ 跳转。

- [ ] **Step 2: 跑前端测试**

Run: `cd web && pnpm test`
Expected: 全部 vitest 用例 PASS（`LoginView.spec`、`stores/__tests__/user.spec`、`router/__tests__/guard.spec` 等）。

- [ ] **Step 3: 跑类型检查 + 构建**

Run: `cd web && pnpm build`
Expected: `vue-tsc --noEmit` 无类型错误，`vite build` 成功。

- [ ] **Step 4: （仅当 Step 2/3 失败时）最小修正并提交**

若 `pnpm test` 或 `pnpm build` 暴露真实契约不符（例如字段名/类型对不上），按报错做**最小**修正（不重构、不扩范围），然后：

```bash
git add web/src/<被修正的文件>
git commit -m "web：对齐 /me 契约修正（<一句话说明>）"
```

若全绿无需改动：本任务无提交，在执行记录里标注「前端契约已一致，test/build 全绿」。

---

## 完成标准（Definition of Done）

- `mvn -f server/pom.xml test` 全绿（含新增 `CurrentUserControllerTest`、Modularity/ArchUnit）。
- `cd web && pnpm test` 全绿；`cd web && pnpm build` 通过（含 `vue-tsc` 类型检查）。
- **手动 e2e 冒烟（人工，眼见为实——mock 测试覆盖不到的真前后端契约）：**
  1. 起后端（带引导 admin，命令行参数注入账号密码，避免环境变量坑）：
     ```bash
     # 确保库里有 admin（如已存在可跳过）；首次可清表后用参数引导
     mvn -f server/pom.xml spring-boot:run \
       -Dspring-boot.run.arguments="--hify.identity.bootstrap-admin.username=admin --hify.identity.bootstrap-admin.password=Qwer1234"
     ```
  2. 另开终端起前端：`cd web && pnpm dev`（Vite proxy 已把 `/api` 转 `localhost:8080`）。
  3. 浏览器开 dev 地址 → 用 `admin / Qwer1234` 登录，确认：
     - 登录成功跳转到首页/redirect 目标；
     - `localStorage` 有 `hify_token`（DevTools → Application）；
     - Network 里 `GET /api/v1/identity/me` 返回 200 且 body `data` 为 `{id,username,role}`；
     - 刷新页面仍保持登录（守卫用 token 调 `/me` 拿回角色）；
     - 退出登录回到登录页；
     - 错误密码登录 → 页面 toast 提示，不进入。
- 不含：admin 用户管理、改密、自动化 E2E 套件（推迟）。
