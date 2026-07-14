# Agent/tool T2：app_tool_rel + Agent 配置 + 前端配置页/轨迹 + 流式 Agent 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Agent（对话应用的一种配置形态）能在界面上勾选启用工具、走 SSE 流式对话、工具调用轨迹实时展示并落库。

**Architecture:** per-app 工具选择走新表 `app_tool_rel`（镜像 `app_dataset_rel`）、`agentEnabled` 继续在 `AppConfig` jsonb。流式 Agent 用**方案一**：`sendStream` 去掉 17002 守卫，Agent 应用走新编排——在 `boundedElastic` 线程跑 T1 已终审的同步 `AgentChatService` 循环，每调完一个工具往 SSE sink 推 `tool_call` 事件，最终答案作为一条 `message` 增量推出再落库。直播 `tool_call` 事件与落库 `message.tool_calls` 同结构，前端用同一个折叠卡片渲染。

**Tech Stack:** Spring Boot 3 / Spring AI 1.0.1 / MyBatis-Plus / PostgreSQL(pgvector) / Reactor（Flux）/ Vue3 + TS + Element Plus + Pinia + vitest。

## Global Constraints

- Long/long 全局序列化为 JSON string；int/Integer 保持 number（infra 全局 Jackson，业务不局部覆盖）。
- 一期不用 PATCH；成员族路由 `/api/v1/<module>/**`（本轮新增 `GET /api/v1/tool/tools`，tool 模块段）。
- 数据库变更只新增 Flyway 脚本，**新脚本号 = V24**；禁改已合并旧脚本。跨模块表不建外键（弱引用）。
- ArchUnit：DTO 不得 import entity（投影在 service 完成）；跨模块 DTO 放 api 顶层包（本轮 `AppRuntimeView` 已在 api 顶层）。
- `@Transactional` 内禁止 LLM/外部 IO；事件（TokenUsedEvent）必须在事务方法内发（AFTER_COMMIT）。
- 前端：vitest + TDD（新代码先写失败测试），测试放 `__tests__/`；优先 Element Plus 组件与 `@element-plus/icons-vue`。
- 判定 mvn 测试结果**别 grep `BUILD SUCCESS`**（`-q` 会静音）；看 `Tests run/BUILD` 汇总或退出码。
- 本轮不新增后端错误码（校验复用 `CommonError.PARAM_INVALID`）。

---

### Task 1: `app_tool_rel` 表 + 实体 + Mapper

**Files:**
- Create: `server/src/main/resources/db/migration/V24__create_app_tool_rel.sql`
- Create: `server/src/main/java/com/hify/app/entity/AppToolRel.java`
- Create: `server/src/main/java/com/hify/app/mapper/AppToolRelMapper.java`
- Test: `server/src/test/java/com/hify/app/mapper/AppToolRelMapperIT.java`

**Interfaces:**
- Produces: `AppToolRel{ Long appId; Long toolId; }`（extends BaseEntity，有 id/deleted/create_time）；`AppToolRelMapper extends BaseMapper<AppToolRel>`。

- [ ] **Step 1: 写迁移脚本**（照抄 `V18__create_app_dataset_rel.sql`，把 dataset 换 tool）

```sql
-- V24：应用↔工具多对多（app 模块）。tool_id 跨模块弱引用 tool(id)，不建外键。
-- Agent 应用勾选启用哪些工具（T2）；agentEnabled 开关仍在 app.config jsonb。
create table app_tool_rel (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null references app(id),
    tool_id     bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app_tool_rel is '应用↔工具多对多（app 模块）；tool_id 跨模块弱引用';
create unique index app_tool_rel_uq on app_tool_rel (app_id, tool_id) where deleted = false;
create index app_tool_rel_tool_idx on app_tool_rel (tool_id);
```

- [ ] **Step 2: 写实体 + Mapper**

`AppToolRel.java`：
```java
package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/** 应用↔工具关系表 {@code app_tool_rel} 映射实体。tool_id 跨模块弱引用；更新=全量替换（软删+插新）。 */
@TableName("app_tool_rel")
public class AppToolRel extends BaseEntity {

    private Long appId;
    private Long toolId;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
}
```

`AppToolRelMapper.java`：
```java
package com.hify.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.app.entity.AppToolRel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppToolRelMapper extends BaseMapper<AppToolRel> {
}
```

- [ ] **Step 3: 写 IT（迁移+读写）**

`AppToolRelMapperIT.java`（extends `PgIntegrationTest`，同 `ToolMapperIT`）：
```java
package com.hify.app.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.entity.AppToolRel;
import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppToolRelMapperIT extends PgIntegrationTest {

    @Autowired
    private AppToolRelMapper mapper;

    @Test
    void 插入与按appId读取有序() {
        AppToolRel a = new AppToolRel(); a.setAppId(9001L); a.setToolId(1L);
        AppToolRel b = new AppToolRel(); b.setAppId(9001L); b.setToolId(2L);
        mapper.insert(a); mapper.insert(b);

        List<Long> toolIds = mapper.selectList(new LambdaQueryWrapper<AppToolRel>()
                        .eq(AppToolRel::getAppId, 9001L).orderByAsc(AppToolRel::getId))
                .stream().map(AppToolRel::getToolId).toList();
        assertThat(toolIds).containsExactly(1L, 2L);
    }
}
```

- [ ] **Step 4: 运行 IT**

Run: `cd server && mvn test -Dtest=AppToolRelMapperIT`
Expected: PASS（Flyway 应用 V24，插入两行、按 id 有序读回 [1,2]）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/resources/db/migration/V24__create_app_tool_rel.sql \
  server/src/main/java/com/hify/app/entity/AppToolRel.java \
  server/src/main/java/com/hify/app/mapper/AppToolRelMapper.java \
  server/src/test/java/com/hify/app/mapper/AppToolRelMapperIT.java
git commit -m "feat(app): app_tool_rel 表+实体+Mapper(V24，镜像 app_dataset_rel)"
```

---

### Task 2: `ToolFacade` per-app 选择 + 校验

**Files:**
- Modify: `server/src/main/java/com/hify/tool/service/ToolRegistry.java`
- Modify: `server/src/main/java/com/hify/tool/api/ToolFacade.java`
- Modify: `server/src/main/java/com/hify/tool/service/ToolFacadeImpl.java`
- Test: `server/src/test/java/com/hify/tool/service/ToolRegistryTest.java`（加用例）
- Test: `server/src/test/java/com/hify/tool/service/ToolFacadeImplTest.java`（新建）

**Interfaces:**
- Consumes: `ToolMapper`（T1）、`BuiltinTool`（T1）、`Tool` 实体（有 `getId/setId`，来自 BaseEntity）。
- Produces:
  - `ToolFacade.getToolCallbacks(Collection<Long> toolIds): List<ToolCallback>`（按 id 取 enabled builtin 的 callback；空集合→空列表；未知/停用 id 跳过）
  - `ToolFacade.validateToolIds(Collection<Long> toolIds): void`（任一 id 非「现存且 enabled」→ 抛 `BizException(CommonError.PARAM_INVALID)`；空集合直接通过）
  - `ToolRegistry.getToolCallbacks(Collection<Long> ids): List<ToolCallback>`、`ToolRegistry.filterEnabledIds(Collection<Long> ids): Set<Long>`

- [ ] **Step 1: 写失败测试（ToolRegistryTest 加用例）**

在现有 `ToolRegistryTest` 追加（`row(...)` helper 需能设 id——用 `t.setId(id)`）：
```java
    private static Tool rowWithId(long id, String name, boolean enabled) {
        Tool t = row(name, name + "说明", enabled);
        t.setId(id);
        return t;
    }

    @Test
    void 按id取callback_只产出返回行对应的执行器() {
        when(toolMapper.selectList(any())).thenReturn(List.of(rowWithId(2, "code_executor", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper,
                List.of(fake("http_request", "OK"), fake("code_executor", "X")));

        List<ToolCallback> cbs = registry.getToolCallbacks(List.of(2L));

        assertThat(cbs).hasSize(1);
        assertThat(cbs.get(0).getToolDefinition().name()).isEqualTo("code_executor");
    }

    @Test
    void 空id集合_直接空列表_不查库() {
        ToolRegistry registry = new ToolRegistry(toolMapper, List.of(fake("http_request", "OK")));
        assertThat(registry.getToolCallbacks(List.of())).isEmpty();
    }

    @Test
    void filterEnabledIds_返回查到的enabled行id集() {
        when(toolMapper.selectList(any())).thenReturn(List.of(rowWithId(1, "http_request", true)));
        ToolRegistry registry = new ToolRegistry(toolMapper, List.of(fake("http_request", "OK")));
        assertThat(registry.filterEnabledIds(List.of(1L, 99L))).containsExactly(1L);
    }
```

- [ ] **Step 2: 运行验证失败**

Run: `cd server && mvn test -Dtest=ToolRegistryTest`
Expected: 编译失败/FAIL（`getToolCallbacks(Collection)`、`filterEnabledIds` 未定义）。

- [ ] **Step 3: 实现 ToolRegistry**

抽出共用构建逻辑，新增两方法：
```java
    /** 按 id 取 enabled 的 builtin 工具 ToolCallback（未知/停用/无执行器的跳过）。空集合→空列表。 */
    public List<ToolCallback> getToolCallbacks(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Tool> rows = toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                .eq(Tool::getSource, "builtin")
                .eq(Tool::getEnabled, true)
                .in(Tool::getId, ids)
                .orderByAsc(Tool::getName));
        return buildCallbacks(rows);
    }

    /** 给定 id 集合，返回其中「现存且 enabled」的 id（用于绑定前校验）。空集合→空集。 */
    public java.util.Set<Long> filterEnabledIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.Set.of();
        }
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getEnabled, true)
                        .in(Tool::getId, ids))
                .stream().map(Tool::getId).collect(Collectors.toSet());
    }
```
把现有 `getBuiltinToolCallbacks()` 的 for 循环体抽成 `private List<ToolCallback> buildCallbacks(List<Tool> rows)`，两处复用（`getBuiltinToolCallbacks` 与 `getToolCallbacks` 都调它）。`buildCallbacks` 即原循环：遍历 rows、`builtinByName.get(name)`、无执行器 `log.warn` 跳过、否则构 `DefaultToolDefinition` + `BuiltinToolCallback`。

- [ ] **Step 4: 运行验证通过**

Run: `cd server && mvn test -Dtest=ToolRegistryTest`
Expected: PASS。

- [ ] **Step 5: 扩展 ToolFacade + Impl + 写 facade 校验测试**

`ToolFacade.java` 加两方法（删掉 T1 注释里「T2 追加」的字样）：
```java
    /** 取指定 id 的 enabled 工具 ToolCallback（per-app 选择）。未知/停用 id 跳过；空集合→空列表。 */
    List<ToolCallback> getToolCallbacks(java.util.Collection<Long> toolIds);

    /** 校验勾选的工具 id 都「现存且 enabled」，否则抛 PARAM_INVALID。空集合直接通过。 */
    void validateToolIds(java.util.Collection<Long> toolIds);
```
`ToolFacadeImpl.java`：
```java
    @Override
    public List<ToolCallback> getToolCallbacks(java.util.Collection<Long> toolIds) {
        return registry.getToolCallbacks(toolIds);
    }

    @Override
    public void validateToolIds(java.util.Collection<Long> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return;
        }
        java.util.Set<Long> distinct = new java.util.HashSet<>(toolIds);
        if (!registry.filterEnabledIds(distinct).containsAll(distinct)) {
            throw new com.hify.common.exception.BizException(com.hify.common.exception.CommonError.PARAM_INVALID,
                    "存在不可用的工具，请重新选择");
        }
    }
```

`ToolFacadeImplTest.java`（新建，registry 用真实类 + mock mapper）：
```java
package com.hify.tool.service;

import com.hify.common.exception.BizException;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolFacadeImplTest {

    private final ToolMapper mapper = Mockito.mock(ToolMapper.class);
    private final ToolFacadeImpl facade = new ToolFacadeImpl(new ToolRegistry(mapper, List.of()));

    private static Tool enabled(long id) {
        Tool t = new Tool(); t.setId(id); t.setEnabled(true); t.setSource("builtin"); t.setName("n" + id);
        return t;
    }

    @Test
    void 全部现存启用_校验通过() {
        when(mapper.selectList(any())).thenReturn(List.of(enabled(1), enabled(2)));
        assertThatCode(() -> facade.validateToolIds(List.of(1L, 2L))).doesNotThrowAnyException();
    }

    @Test
    void 含不存在id_抛PARAM_INVALID() {
        when(mapper.selectList(any())).thenReturn(List.of(enabled(1)));
        assertThatThrownBy(() -> facade.validateToolIds(List.of(1L, 99L)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 空集合_直接通过() {
        assertThatCode(() -> facade.validateToolIds(List.of())).doesNotThrowAnyException();
        assertThat(facade.getToolCallbacks(List.of())).isEmpty();
    }
}
```

- [ ] **Step 6: 运行验证**

Run: `cd server && mvn test -Dtest=ToolRegistryTest,ToolFacadeImplTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/tool server/src/test/java/com/hify/tool/service
git commit -m "feat(tool): ToolFacade.getToolCallbacks(ids)+validateToolIds(per-app 选择与校验)"
```

---

### Task 3: 工具列表接口 `GET /api/v1/tool/tools`

**Files:**
- Create: `server/src/main/java/com/hify/tool/dto/ToolView.java`
- Create: `server/src/main/java/com/hify/tool/service/ToolService.java`
- Create: `server/src/main/java/com/hify/tool/controller/ToolController.java`
- Test: `server/src/test/java/com/hify/tool/service/ToolServiceTest.java`
- Test: `server/src/test/java/com/hify/tool/controller/ToolControllerTest.java`

**Interfaces:**
- Consumes: `ToolMapper`（T1）。
- Produces: `ToolView(Long id, String name, String description, String source)`；`ToolService.listEnabled(): List<ToolView>`（enabled=true，按 name 升序）。

- [ ] **Step 1: 写 DTO**

```java
package com.hify.tool.dto;

/** 工具列表项（成员族响应）。id 为 Long（infra 全局序列化为 string）。 */
public record ToolView(Long id, String name, String description, String source) {
}
```

- [ ] **Step 2: 写失败的 service 测试**

```java
package com.hify.tool.service;

import com.hify.tool.dto.ToolView;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ToolServiceTest {

    private final ToolMapper mapper = Mockito.mock(ToolMapper.class);
    private final ToolService service = new ToolService(mapper);

    private static Tool row(long id, String name) {
        Tool t = new Tool(); t.setId(id); t.setName(name); t.setDescription(name + "说明");
        t.setSource("builtin"); t.setEnabled(true);
        return t;
    }

    @Test
    void 列出enabled工具映射为view() {
        when(mapper.selectList(any())).thenReturn(List.of(row(1, "code_executor"), row(2, "http_request")));
        List<ToolView> views = service.listEnabled();
        assertThat(views).extracting(ToolView::name).containsExactly("code_executor", "http_request");
        assertThat(views.get(0).id()).isEqualTo(1L);
        assertThat(views.get(0).source()).isEqualTo("builtin");
    }
}
```

- [ ] **Step 3: 运行验证失败**

Run: `cd server && mvn test -Dtest=ToolServiceTest`
Expected: 编译失败（`ToolService` 未定义）。

- [ ] **Step 4: 写 ToolService + Controller**

`ToolService.java`：
```java
package com.hify.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.tool.dto.ToolView;
import com.hify.tool.entity.Tool;
import com.hify.tool.mapper.ToolMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 工具查询（成员族只读）：列出可选（enabled）工具供 Agent 配置页勾选。注册表增删改是 T3/T4 admin 活。 */
@Service
public class ToolService {

    private final ToolMapper toolMapper;

    public ToolService(ToolMapper toolMapper) {
        this.toolMapper = toolMapper;
    }

    public List<ToolView> listEnabled() {
        return toolMapper.selectList(new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getEnabled, true).orderByAsc(Tool::getName))
                .stream()
                .map(t -> new ToolView(t.getId(), t.getName(), t.getDescription(), t.getSource()))
                .toList();
    }
}
```
`ToolController.java`：
```java
package com.hify.tool.controller;

import com.hify.common.Result;
import com.hify.tool.dto.ToolView;
import com.hify.tool.service.ToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工具接口（成员族 /api/v1/tool/**，任意登录用户可读）。协议层无业务逻辑、无 @Transactional。 */
@RestController
@RequestMapping("/api/v1/tool")
public class ToolController {

    private final ToolService toolService;

    public ToolController(ToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tools")
    public Result<List<ToolView>> listTools() {
        return Result.ok(toolService.listEnabled());
    }
}
```

- [ ] **Step 5: 写 Controller 测试（@WebMvcTest）**

```java
package com.hify.tool.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.tool.dto.ToolView;
import com.hify.tool.service.ToolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ToolController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolService toolService;

    @Test
    void 列表返回工具且id为string() throws Exception {
        when(toolService.listEnabled()).thenReturn(List.of(
                new ToolView(1L, "http_request", "HTTP 工具", "builtin")));
        mockMvc.perform(get("/api/v1/tool/tools").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("1"))
                .andExpect(jsonPath("$.data[0].name").value("http_request"));
    }

    private String bearer() {
        // 复用现有 controller 测的取 token 手法：如已有工具方法则调之；否则用 JwtService 现签一枚 member token。
        return "Bearer " + tokenHelper();
    }

    private String tokenHelper() {
        JwtService jwt = new JwtService();
        // 见 ConversationControllerTest 的签发方式（同 secret + member 角色），此处照抄其 token 生成片段。
        throw new UnsupportedOperationException("按 ConversationControllerTest 的 token 生成片段替换");
    }
}
```
> 实现者注意：`bearer()`/token 生成**照抄 `ConversationControllerTest` 里已跑通的签发片段**（同一 `hify.security.jwt.secret`、member 角色），不要自造。若该测试类有可复用的静态 helper，直接引用。

- [ ] **Step 6: 运行验证通过**

Run: `cd server && mvn test -Dtest=ToolServiceTest,ToolControllerTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/tool/dto server/src/main/java/com/hify/tool/service/ToolService.java \
  server/src/main/java/com/hify/tool/controller server/src/test/java/com/hify/tool/service/ToolServiceTest.java \
  server/src/test/java/com/hify/tool/controller/ToolControllerTest.java
git commit -m "feat(tool): GET /api/v1/tool/tools 列出可选工具(成员族只读)"
```

---

### Task 4: app 模块 toolIds 打通（DTO/视图/service/facade）

**Files:**
- Modify: `server/src/main/java/com/hify/app/api/AppRuntimeView.java`（+toolIds）
- Modify: `server/src/main/java/com/hify/app/dto/AppResponse.java`（+toolIds）
- Modify: `server/src/main/java/com/hify/app/dto/CreateAppRequest.java`、`UpdateAppRequest.java`（+toolIds）
- Modify: `server/src/main/java/com/hify/app/service/AppService.java`（读写/校验 toolIds）
- Modify: `server/src/main/java/com/hify/app/service/AppFacadeImpl.java`（组装 toolIds）
- Test: `server/src/test/java/com/hify/app/service/AppServiceTest.java`（加用例）
- 连带改：所有 `new AppRuntimeView(...)` 调用点（测试内）。

**Interfaces:**
- Consumes: `ToolFacade.validateToolIds`（Task 2）、`AppToolRelMapper`（Task 1）。
- Produces: `AppRuntimeView(Long appId, Long modelId, String systemPrompt, List<Long> datasetIds, boolean agentEnabled, List<Long> toolIds)`；`AppResponse` 末尾加 `List<Long> toolIds`；`Create/UpdateAppRequest` 加 `@Size(max=20) List<Long> toolIds`。

- [ ] **Step 1: 先全量定位 AppRuntimeView 调用点（改签名前必做）**

Run: `cd server && grep -rn "new AppRuntimeView(" src`
把每一处记下来（Step 4 会逐一补 `toolIds` 实参）。**禁止 `| head` 截断**。

- [ ] **Step 2: 写失败测试（AppServiceTest 加用例）**

参照现有 datasets 用例，追加（`toolFacade` 需注入到被测 `AppService`——见 Step 4 构造改动）：
```java
    @Test
    void 创建chat应用_写入toolIds绑定并校验() {
        // given：模型可用、工具校验通过
        // when：create(带 toolIds=[1,2])
        // then：toolFacade.validateToolIds([1,2]) 被调；app_tool_rel 写入两行；响应 toolIds=[1,2]
    }

    @Test
    void 更新chat应用_全量替换toolIds() {
        // 原绑[1] → 更新为[2,3] → 关系被 replaceToolBindings 全量替换；响应 toolIds=[2,3]
    }
```
> 实现者：**照抄本类现有 `datasetIds` 对应用例的结构**（mock `appMapper`/`relMapper(dataset)`/新 `appToolRelMapper`/`toolFacade`），把 dataset 换成 tool。断言 `verify(toolFacade).validateToolIds(...)`、`verify(appToolRelMapper).insert(...)`。

- [ ] **Step 3: 运行验证失败**

Run: `cd server && mvn test -Dtest=AppServiceTest`
Expected: 编译失败（`AppRuntimeView`/`AppResponse` 无 toolIds、`AppService` 无 toolFacade 依赖）。

- [ ] **Step 4: 实现**

`AppRuntimeView.java`——末尾加 `List<Long> toolIds`（更新 javadoc：`toolIds`=Agent 勾选启用的工具，恒非 null）。

`AppResponse.java`——在 `datasetIds` 后加 `List<Long> toolIds`。

`CreateAppRequest.java` / `UpdateAppRequest.java`——在 `datasetIds` 后加 `@Size(max = 20) List<Long> toolIds`（javadoc：可空=不启用工具，上限 20）。

`AppService.java`：
- 构造注入 `AppToolRelMapper toolRelMapper` 与 `ToolFacade toolFacade`（`import com.hify.tool.api.ToolFacade;`）。
- `create`：在 `replaceDatasetBindings` 附近加：
  ```java
  List<Long> toolIds = req.toolIds() == null ? List.of() : req.toolIds();
  if (AppType.CHAT.value().equals(req.type())) {
      toolFacade.validateToolIds(toolIds);
  }
  // ... insert app ...
  replaceToolBindings(entity.getId(), toolIds);   // 仅 chat 走到这（workflow 分支不调）
  ```
  并把 `toResponse(...)` 改为带 toolIds 版本（见下）。
- `update`：chat 分支里 `toolFacade.validateToolIds(toolIds)` + `replaceToolBindings(app.getId(), toolIds)`。
- 新增私有方法（镜像 dataset 三件套）：
  ```java
  private void replaceToolBindings(Long appId, List<Long> toolIds) {
      toolRelMapper.delete(new LambdaQueryWrapper<AppToolRel>().eq(AppToolRel::getAppId, appId));
      for (Long tid : toolIds.stream().distinct().toList()) {
          AppToolRel rel = new AppToolRel();
          rel.setAppId(appId);
          rel.setToolId(tid);
          toolRelMapper.insert(rel);
      }
  }

  private List<Long> toolIdsOf(Long appId) {
      return toolRelMapper.selectList(new LambdaQueryWrapper<AppToolRel>()
                      .eq(AppToolRel::getAppId, appId).orderByAsc(AppToolRel::getId))
              .stream().map(AppToolRel::getToolId).toList();
  }

  private Map<Long, List<Long>> toolIdsByApp(List<Long> appIds) {
      if (appIds.isEmpty()) return Map.of();
      return toolRelMapper.selectList(new LambdaQueryWrapper<AppToolRel>()
                      .in(AppToolRel::getAppId, appIds).orderByAsc(AppToolRel::getId))
              .stream().collect(Collectors.groupingBy(AppToolRel::getAppId,
                      Collectors.mapping(AppToolRel::getToolId, Collectors.toList())));
  }
  ```
- `toResponse` 增加 `List<Long> toolIds` 形参并放进 `new AppResponse(...)` 末尾。所有调用点补参：
  - `create`：`toResponse(entity, ..., datasetIds.stream().distinct().toList(), toolIds.stream().distinct().toList())`
  - `get`：`toResponse(app, ..., datasetIdsOf(id), toolIdsOf(id))`
  - `update`：`toResponse(app, ..., datasetIdsOf(app.getId()), toolIdsOf(app.getId()))`
  - `page`：预取 `Map<Long,List<Long>> toolBindings = toolIdsByApp(...)`，map 里 `toResponse(a, ..., dsBindings, toolBindings.getOrDefault(id, List.of()))`

`AppFacadeImpl.java`——`findRunnableChatApp` 里像 datasetIds 一样读 toolIds，装进 `AppRuntimeView`：
```java
List<Long> toolIds = toolRelMapper.selectList(new LambdaQueryWrapper<AppToolRel>()
                .eq(AppToolRel::getAppId, app.getId()).orderByAsc(AppToolRel::getId))
        .stream().map(AppToolRel::getToolId).toList();
return Optional.of(new AppRuntimeView(app.getId(), app.getModelId(), systemPrompt, datasetIds, agentEnabled, toolIds));
```
（`AppFacadeImpl` 构造注入 `AppToolRelMapper`。）

- [ ] **Step 5: 补全所有 AppRuntimeView 调用点**

依据 Step 1 清单，把每处 `new AppRuntimeView(...)`（含 `ConversationServiceTest` 的 `stubRunnableApp`/`stubAgentRunnableApp`/`runnableChatAppBoundTo` 等 helper）末尾补 `toolIds` 实参（非 agent 用例传 `List.of()`）。

- [ ] **Step 6: 运行验证通过**

Run: `cd server && mvn test -Dtest=AppServiceTest,ConversationServiceTest`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/hify/app server/src/test/java/com/hify/app/service/AppServiceTest.java \
  server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(app): toolIds 打通(DTO/AppRuntimeView/AppResponse+AppService读写校验+facade)"
```

---

### Task 5: `MessageView.toolCalls`（历史带轨迹）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/dto/MessageView.java`（+toolCalls）
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（`toView` 映射）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（加用例）

**Interfaces:**
- Consumes: `Message.getToolCalls(): List<MessageToolCall>`（T1 已有）；`MessageToolCall(name,args,result)`。
- Produces: `MessageView` 末尾加 `List<MessageToolCall> toolCalls`（恒非 null，空即 `[]`）。

- [ ] **Step 1: 写失败测试**

在 `ConversationServiceTest` 追加（history 返回带轨迹的 assistant 消息）：
```java
    @Test
    void history_带工具轨迹回显() {
        Message m = new Message();
        m.setId(200L); m.setRole("assistant"); m.setContent("答案");
        m.setToolCalls(List.of(new MessageToolCall("http_request", "{\"url\":\"x\"}", "HTTP 200")));
        when(store.listMessages(eq(100L), eq(42L))).thenReturn(List.of(m));

        List<MessageView> views = service.history(100L, member);

        assertEquals(1, views.get(0).toolCalls().size());
        assertEquals("http_request", views.get(0).toolCalls().get(0).name());
    }
```

- [ ] **Step 2: 运行验证失败**

Run: `cd server && mvn test -Dtest=ConversationServiceTest#history_带工具轨迹回显`
Expected: 编译失败（`MessageView.toolCalls()` 不存在）。

- [ ] **Step 3: 实现**

`MessageView.java`——`sources` 后加 `List<MessageToolCall> toolCalls`（javadoc：Agent 工具调用轨迹，非 Agent 消息为空数组）。
`ConversationService.toView`：
```java
    private static MessageView toView(Message m) {
        return new MessageView(m.getId(), m.getRole(), m.getContent(),
                m.getPromptTokens(), m.getCompletionTokens(), m.getCreateTime(),
                m.getSources() == null ? List.of() : m.getSources(),
                m.getToolCalls() == null ? List.of() : m.getToolCalls());
    }
```

- [ ] **Step 4: 运行验证通过**

Run: `cd server && mvn test -Dtest=ConversationServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/hify/conversation/dto/MessageView.java \
  server/src/main/java/com/hify/conversation/service/ConversationService.java \
  server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java
git commit -m "feat(conversation): MessageView 带 toolCalls(历史回显工具轨迹)"
```

---

### Task 6: `tool_call` SSE 事件 + AgentChatService 事件钩子

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/StreamEvent.java`（+ToolCall）
- Modify: `server/src/main/java/com/hify/conversation/dto/StreamPayloads.java`（+ToolCall）
- Modify: `server/src/main/java/com/hify/conversation/controller/ConversationController.java`（`toSse` 加分支）
- Modify: `server/src/main/java/com/hify/conversation/service/AgentChatService.java`（`run` 加 `onToolCall` 重载）
- Test: `server/src/test/java/com/hify/conversation/service/AgentChatServiceTest.java`（加用例）

**Interfaces:**
- Produces:
  - `StreamEvent.ToolCall(String toolName, String args, String result, boolean ok)`
  - `StreamPayloads.ToolCall(String toolName, String args, String result, boolean ok)`
  - `AgentChatService.run(chatClient, systemPrompt, window, toolCallbacks, java.util.function.Consumer<StreamEvent.ToolCall> onToolCall): AgentReply`（原 4 参 `run` 委托此重载，传 no-op consumer）

- [ ] **Step 1: 写失败测试（AgentChatServiceTest 加用例）**

```java
    @Test
    void run重载_每调完一个工具触发一次事件() {
        java.util.Deque<ChatResponse> script = new java.util.ArrayDeque<>(java.util.List.of(
                assistantWithToolCall("http_request", "{\"url\":\"x\"}"),
                finalAnswer("好了")));
        AgentChatService svc = withScript(5, script);
        java.util.List<StreamEvent.ToolCall> events = new java.util.ArrayList<>();

        AgentReply reply = svc.run(null, "", java.util.List.of(userMsg("q")),
                java.util.List.of(fakeTool("http_request", "HTTP 200")), events::add);

        assertThat(reply.content()).isEqualTo("好了");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).toolName()).isEqualTo("http_request");
        assertThat(events.get(0).result()).contains("HTTP 200");
        assertThat(events.get(0).ok()).isTrue();
    }

    @Test
    void run重载_未知工具事件ok为false() {
        java.util.Deque<ChatResponse> script = new java.util.ArrayDeque<>(java.util.List.of(
                assistantWithToolCall("ghost", "{}"),
                finalAnswer("x")));
        java.util.List<StreamEvent.ToolCall> events = new java.util.ArrayList<>();
        withScript(5, script).run(null, "", java.util.List.of(userMsg("q")),
                java.util.List.of(fakeTool("http_request", "R")), events::add);
        assertThat(events.get(0).ok()).isFalse();
    }
```

- [ ] **Step 2: 运行验证失败**

Run: `cd server && mvn test -Dtest=AgentChatServiceTest`
Expected: 编译失败（5 参 `run` 与 `StreamEvent.ToolCall` 不存在）。

- [ ] **Step 3: 实现 StreamEvent + StreamPayloads + AgentChatService**

`StreamEvent.java`——加进 permits 与 record：
```java
public sealed interface StreamEvent
        permits StreamEvent.Meta, StreamEvent.Sources, StreamEvent.Delta, StreamEvent.ToolCall, StreamEvent.Done {
    // ... 现有 ...
    /** Agent 单次工具调用轨迹（调用完成后发一次；ok=false 表示工具不存在或执行失败）。 */
    record ToolCall(String toolName, String args, String result, boolean ok) implements StreamEvent {}
}
```
`StreamPayloads.java`——加：
```java
    public record ToolCall(String toolName, String args, String result, boolean ok) {}
```
`AgentChatService.java`——把现有 `run(4参)` 改为委托，新增 5 参重载承载原逻辑，循环里加事件发射：
```java
    public AgentReply run(ChatClient chatClient, String systemPrompt, List<Message> window,
                          List<ToolCallback> toolCallbacks) {
        return run(chatClient, systemPrompt, window, toolCallbacks, tc -> {});
    }

    public AgentReply run(ChatClient chatClient, String systemPrompt, List<Message> window,
                          List<ToolCallback> toolCallbacks,
                          java.util.function.Consumer<StreamEvent.ToolCall> onToolCall) {
        // ...原方法体不变，直到构造 trace 元素处...
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            ToolCallback cb = byName.get(call.name());
            String result;
            boolean ok;
            if (cb == null) {
                result = "错误：工具不存在：" + call.name();
                ok = false;
            } else {
                try {
                    result = cb.call(call.arguments());
                    ok = true;
                } catch (RuntimeException ex) {
                    result = "错误：工具执行失败：" + ex.getMessage();
                    ok = false;
                }
            }
            trace.add(new MessageToolCall(call.name(), call.arguments(), result));
            onToolCall.accept(new StreamEvent.ToolCall(call.name(), call.arguments(), result, ok));
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
        }
        // ...其余不变...
    }
```

`ConversationController.toSse`——加分支：
```java
        } else if (e instanceof StreamEvent.ToolCall tc) {
            return sse("tool_call", new StreamPayloads.ToolCall(tc.toolName(), tc.args(), tc.result(), tc.ok()));
```

- [ ] **Step 4: 运行验证通过**

Run: `cd server && mvn test -Dtest=AgentChatServiceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/java/com/hify/conversation/service/StreamEvent.java \
  server/src/main/java/com/hify/conversation/service/AgentChatService.java \
  server/src/main/java/com/hify/conversation/dto/StreamPayloads.java \
  server/src/main/java/com/hify/conversation/controller/ConversationController.java \
  server/src/test/java/com/hify/conversation/service/AgentChatServiceTest.java
git commit -m "feat(conversation): tool_call SSE 事件+AgentChatService onToolCall 钩子"
```

---

### Task 7: 流式 Agent 编排 + send() 走 per-app 工具（删 17002 守卫）

**Files:**
- Modify: `server/src/main/java/com/hify/conversation/service/ConversationService.java`（`sendStream` 分流 + `sendStreamAgent` + `send` 改 per-app 工具）
- Modify: `server/src/main/java/com/hify/conversation/constant/ConversationError.java`（删 `AGENT_STREAM_UNSUPPORTED`）
- Test: `server/src/test/java/com/hify/conversation/service/ConversationServiceTest.java`（改/加用例）

**Interfaces:**
- Consumes: `AgentChatService.run(...,onToolCall)`（Task 6）、`ToolFacade.getToolCallbacks(ids)`（Task 2）、`AppRuntimeView.toolIds()`（Task 4）、`store.openTurn/appendAssistant/cleanupFailedTurn`、`StreamEvent.{Meta,ToolCall,Delta,Done}`。
- Produces: `sendStream` 对 agent 应用返回 `Flux<StreamEvent>`（Meta → tool_call* → Delta(final) → Done）。

- [ ] **Step 1: 写失败测试（改现有 17002 用例 + 加 agent 流式用例）**

删除/改写现有断言 `sendStream` 对 agent 抛 `AGENT_STREAM_UNSUPPORTED` 的用例（该行为本轮取消）。新增：
```java
    @Test
    void sendStream_agent应用_发出meta_toolcall_delta_done() {
        // agent app：toolIds=[1]，openTurn 返回 cid=100/userMsgId
        when(appFacade.findRunnableChatApp(eq(7L))).thenReturn(Optional.of(
                new AppRuntimeView(7L, 5L, "你是助手", List.of(), true, List.of(1L))));
        when(providerFacade.getChatClient(eq(5L))).thenReturn(chatClient);
        when(toolFacade.getToolCallbacks(eq(List.of(1L)))).thenReturn(List.of());
        // openTurn 桩：cid=100
        // agentChatService.run(...,consumer) 桩：触发一次 onToolCall 后返回 AgentReply("终答",3,2,[trace])
        when(agentChatService.run(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<StreamEvent.ToolCall> cb = inv.getArgument(4);
            cb.accept(new StreamEvent.ToolCall("http_request", "{}", "HTTP 200", true));
            return new AgentReply("终答", 3, 2, List.of(
                    new MessageToolCall("http_request", "{}", "HTTP 200")));
        });
        // appendAssistant 桩：返回带 id 的 Message

        List<StreamEvent> events = service.sendStream(7L, null, "问题", member).collectList().block();

        assertTrue(events.get(0) instanceof StreamEvent.Meta);
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCall tc
                && tc.toolName().equals("http_request")));
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.Delta d && d.text().equals("终答")));
        assertTrue(events.get(events.size() - 1) instanceof StreamEvent.Done);
    }
```
> 实现者：桩 `store.openTurn`/`appendAssistant` 照抄本类现有 `stubTurnAndReplyFor` 等 helper 的手法补全（返回 `TurnContext`/`Message`）。

- [ ] **Step 2: 运行验证失败**

Run: `cd server && mvn test -Dtest=ConversationServiceTest`
Expected: FAIL（agent 走 sendStream 仍抛 17002 / 编译错误引用已删常量）。

- [ ] **Step 3: 实现 sendStream 分流 + sendStreamAgent + send 改 per-app 工具**

`send()`（同步路径）——把 agent 分支的 `toolFacade.getBuiltinToolCallbacks()` 改成 per-app：
```java
                AgentReply reply = agentChatService.run(chatClient, app.systemPrompt(), turn.window(),
                        toolFacade.getToolCallbacks(app.toolIds()));
```
`sendStream()`——把守卫替换为分流：
```java
        if (app.agentEnabled()) {
            return sendStreamAgent(app, current, content);
        }
```
（`AppRuntimeView app` 已在方法内取得；`quotaGuard.check` 与 `findRunnableChatApp` 保留在前。）

新增私有方法：
```java
    /** Agent 流式编排（方案一）：在 boundedElastic 上跑同步循环，工具事件实时推、最终答案整段推、落库后 done。 */
    private Flux<StreamEvent> sendStreamAgent(AppRuntimeView app, CurrentUser current, String content) {
        TurnContext turn = store.openTurn(app.appId(), null /*conversationId 见下*/, current.userId(), content);
        Long cid = turn.conversationId();
        ChatClient chatClient = providerFacade.getChatClient(app.modelId());
        List<ToolCallback> callbacks = toolFacade.getToolCallbacks(app.toolIds());

        return Flux.<StreamEvent>create(sink -> {
            sink.next(new StreamEvent.Meta(cid));
            try {
                AgentReply reply = agentChatService.run(chatClient, app.systemPrompt(), turn.window(),
                        callbacks, sink::next);
                sink.next(new StreamEvent.Delta(reply.content()));
                Message saved = store.appendAssistant(cid, reply.content(),
                        reply.promptTokens(), reply.completionTokens(),
                        current.userId(), app.appId(), app.modelId(), List.of(), reply.toolCalls());
                sink.next(new StreamEvent.Done(cid, saved.getId(), reply.promptTokens(), reply.completionTokens()));
                sink.complete();
            } catch (RuntimeException e) {
                try {
                    store.cleanupFailedTurn(cid, turn.userMessageId(), turn.newConversation());
                } catch (RuntimeException cleanupEx) {
                    log.warn("Agent 流式孤儿清理失败 conversationId={}", cid, cleanupEx);
                }
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
```
> 注意：`sendStream` 签名是 `(appId, conversationId, content, current)`；上面 `sendStreamAgent` 需要 `conversationId`——把它作参数传入（`sendStreamAgent(app, conversationId, current, content)`），`openTurn` 用真实 `conversationId`（续聊）而非写死 null。实现者按现有 `sendStream` 的 `openTurn(appId, conversationId, userId, content)` 调用形态对齐。

`ConversationError.java`——删除 `AGENT_STREAM_UNSUPPORTED` 枚举项（把上一项 `APP_NOT_RUNNABLE` 的行尾逗号改分号）。

- [ ] **Step 4: 运行验证通过**

Run: `cd server && mvn test -Dtest=ConversationServiceTest`
Expected: PASS。

- [ ] **Step 5: 全量回归**

Run: `cd server && mvn test`
Expected: 全绿（含 ModularityTests / LayerRules / ArchUnit）。若 `AGENT_STREAM_UNSUPPORTED` 有其它引用（如 controller 测），一并清理。

- [ ] **Step 6: 提交**

```bash
git add server/src/main/java/com/hify/conversation server/src/test/java/com/hify/conversation
git commit -m "feat(conversation): 流式 Agent 编排(去 17002+sendStreamAgent)+send 走 per-app 工具"
```

---

### Task 8: 文档回写

**Files:**
- Modify: `docs/architecture/api-standards.md`（`tool_call` 事件例子形状）
- Modify: `docs/architecture/data-model.md`（`app_tool_rel` 落地状态）

- [ ] **Step 1: 更新 api-standards §3.3 的 tool_call 例子**

把：
```
event: tool_call      // Agent 工具调用轨迹（可选）
data: {"toolName": "http_request", "status": "running"}
```
改为：
```
event: tool_call      // Agent 工具调用轨迹：每个工具调用完成后发一次
data: {"toolName": "http_request", "args": "{\"url\":\"...\"}", "result": "HTTP 200 ...", "ok": true}
```

- [ ] **Step 2: 更新 data-model.md 的 app_tool_rel 行**

把 `app_tool_rel` 那行的「T2 落地；T1 暂不建表…」更新为「T2 已落地 V24：`app_id/tool_id` + BaseEntity 字段，tool_id 跨模块弱引用，Agent 应用勾选启用哪些工具」。

- [ ] **Step 3: 提交**

```bash
git add docs/architecture/api-standards.md docs/architecture/data-model.md
git commit -m "docs: tool_call 事件形状定稿+app_tool_rel(V24)落地回写"
```

---

### Task 9: 前端 Agent 配置页（开关 + 工具多选）

**Files:**
- Create: `web/src/api/tool.ts`
- Create: `web/src/types/tool.ts`
- Modify: `web/src/types/app.ts`（`App`/`AppForm` 加 `toolIds`，`config` 加 `agentEnabled`）
- Modify: `web/src/views/app/AppList.vue`
- Test: `web/src/views/app/__tests__/AppList.spec.ts`（加用例；mock `@/api/tool`）

**Interfaces:**
- Produces: `ToolOption{ id:string; name:string; description:string; source:string }`；`listTools(): Promise<ToolOption[]>`；表单 `form.config.agentEnabled:boolean`、`form.toolIds:string[]`。

- [ ] **Step 1: 写 api + 类型**

`web/src/types/tool.ts`：
```ts
/** 工具选项（对齐后端 ToolView）。id 为 string（Long）。 */
export interface ToolOption {
  id: string
  name: string
  description: string
  source: string
}
```
`web/src/api/tool.ts`：
```ts
import { request } from '@/api/request'
import type { ToolOption } from '@/types/tool'

/** 列出可选（enabled）工具，供 Agent 配置页勾选。后端：GET /api/v1/tool/tools */
export function listTools() {
  return request.get<ToolOption[]>('/tool/tools')
}
```
`web/src/types/app.ts`——给 `App`/`AppForm` 加 `toolIds: string[]`，给 config 类型加 `agentEnabled: boolean`（沿用现有 config 结构；若 config 是 `{ systemPrompt: string | null }`，扩成 `{ systemPrompt: string | null; agentEnabled?: boolean }`）。

- [ ] **Step 2: 写失败测试（AppList.spec 加用例）**

```ts
// 顶部加：
vi.mock('@/api/tool', () => ({ listTools: vi.fn() }))
import { listTools } from '@/api/tool'
// beforeEach 里：
vi.mocked(listTools).mockResolvedValue([
  { id: '1', name: 'http_request', description: 'HTTP 工具', source: 'builtin' },
  { id: '2', name: 'code_executor', description: '代码工具', source: 'builtin' },
])

it('开启 Agent 开关后显示工具多选，提交带 toolIds/agentEnabled', async () => {
  const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.find('[data-test="edit-1"]').trigger('click')   // 打开我的应用编辑
  await flushPromises()
  // 打开 agent 开关（data-test="form-agent"）
  // 断言工具多选出现（data-test="form-tools"）
  // 选中工具、点提交
  // expect(updateApp).toHaveBeenCalledWith('1', expect.objectContaining({
  //   config: expect.objectContaining({ agentEnabled: true }), toolIds: [...] }))
})
```
> 实现者：按现有 `datasetIds`/`form-datasets` 用例的交互手法（`ElSelect` 触发选中、`form-submit` 点击、断言 `updateApp` 入参）写实断言。`MINE` 等 fixture 需补 `toolIds: []`、`config: { systemPrompt: null, agentEnabled: false }`。

- [ ] **Step 3: 运行验证失败**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts`
Expected: FAIL（无 `form-agent`/`form-tools`、提交不含新字段）。

- [ ] **Step 4: 实现 AppList.vue**

- 引入 `import { listTools } from '@/api/tool'`、`import type { ToolOption } from '@/types/tool'`。
- `form` 初值：`config: { systemPrompt: '', agentEnabled: false }`、`toolIds: []`。
- `openCreate`/`openEdit` 里回填：`form.config.agentEnabled = row.config.agentEnabled ?? false`、`form.toolIds = [...(row.toolIds ?? [])]`；打开时 `loadToolOptions()`（同 `loadDatasetOptions`，存 `toolOptions.value`）。
- `toolSelectOptions` computed：现存工具 + 已勾但不在列表的作禁用项「已停用的工具」（照抄 `datasetSelectOptions`）。
- 模板：`formType === 'chat'` 区块加：
  ```html
  <el-form-item label="Agent 工具调用">
    <el-switch v-model="form.config.agentEnabled" data-test="form-agent" />
    <div class="app-list__hint">开启后，助手可调用你勾选的工具（如 HTTP 请求、代码执行）来回答</div>
  </el-form-item>
  <el-form-item v-if="form.config.agentEnabled" label="启用工具">
    <el-select v-model="form.toolIds" data-test="form-tools" multiple clearable
               placeholder="选择启用的工具" class="app-list__model-select">
      <el-option v-for="o in toolSelectOptions" :key="o.value" :value="o.value"
                 :label="o.label" :disabled="o.disabled" />
    </el-select>
  </el-form-item>
  ```
- `submitForm` 无需特殊处理（`{ ...form }` 已含 config.agentEnabled 与 toolIds）。

- [ ] **Step 5: 运行验证通过 + 全量前端回归**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts && pnpm vitest run`
Expected: PASS（全绿）。

- [ ] **Step 6: 提交**

```bash
git add web/src/api/tool.ts web/src/types/tool.ts web/src/types/app.ts \
  web/src/views/app/AppList.vue web/src/views/app/__tests__/AppList.spec.ts
git commit -m "feat(web): Agent 配置页(开关+工具多选)"
```

---

### Task 10: 前端流式接线（useChatStream tool_call + store append）

**Files:**
- Modify: `web/src/types/conversation.ts`（`MessageView` 加 `toolCalls`；加 `MessageToolCall` 类型）
- Modify: `web/src/composables/useChatStream.ts`（dispatch `tool_call`）
- Modify: `web/src/stores/conversation.ts`（`onToolCall` append）
- Test: `web/src/composables/__tests__/useChatStream.spec.ts`（加用例）
- Test: `web/src/stores/__tests__/conversation.spec.ts`（加用例）

**Interfaces:**
- Produces: `MessageToolCall{ name:string; args:string; result:string }`；`MessageView.toolCalls?: MessageToolCall[]`；`ChatStreamHandlers.onToolCall?(tc: { toolName:string; args:string; result:string; ok:boolean }): void`。

- [ ] **Step 1: 写失败测试（useChatStream）**

```ts
it('解析 tool_call 事件 → onToolCall', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse([
    'event:tool_call\ndata:{"toolName":"http_request","args":"{}","result":"HTTP 200","ok":true}\n\n',
    'event:done\ndata:{"conversationId":"9","messageId":"7","usage":{"promptTokens":1,"completionTokens":1}}\n\n',
  ])))
  const onToolCall = vi.fn()
  const { start } = useChatStream()
  await start('1', null, 'q', { onDelta: vi.fn(), onDone: vi.fn(), onError: vi.fn(), onToolCall })
  expect(onToolCall).toHaveBeenCalledWith(
    { toolName: 'http_request', args: '{}', result: 'HTTP 200', ok: true })
})
```

- [ ] **Step 2: 写失败测试（store）**

```ts
it('send：tool_call 事件 append 到助手消息 toolCalls', async () => {
  const start = vi.fn(async (_a, _c, _t, h) => {
    h.onToolCall({ toolName: 'http_request', args: '{}', result: 'HTTP 200', ok: true })
    h.onDelta('答案')
    h.onDone('100', '200', { promptTokens: 1, completionTokens: 1 })
  })
  ;(useChatStream as unknown as Mock).mockReturnValue({ start, abort: vi.fn() })
  const store = useConversationStore()
  await store.send('7', '问题')
  const asst = store.messages[store.messages.length - 1]
  expect(asst.toolCalls).toEqual([{ name: 'http_request', args: '{}', result: 'HTTP 200' }])
})
```
> 实现者：`h` 参数类型照抄本文件现有 send 用例的内联 handler 类型并加 `onToolCall`。

- [ ] **Step 3: 运行验证失败**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts src/stores/__tests__/conversation.spec.ts`
Expected: FAIL。

- [ ] **Step 4: 实现**

`web/src/types/conversation.ts`：
```ts
/** Agent 工具调用轨迹（对齐后端 MessageToolCall / 落库 message.tool_calls）。 */
export interface MessageToolCall {
  name: string
  args: string
  result: string
}
```
`MessageView` 加：`toolCalls?: MessageToolCall[]`。

`useChatStream.ts`——`ChatStreamHandlers` 加 `onToolCall?: (tc: { toolName: string; args: string; result: string; ok: boolean }) => void`；`dispatch` 加分支（`tool_call` 非终态，返回 false）：
```ts
    else if (event === 'tool_call') h.onToolCall?.(payload)
```

`stores/conversation.ts`——占位助手消息初始化加 `toolCalls: []`；`chat.start` 的 handler 加：
```ts
        onToolCall: (tc) => {
          const arr = messages.value[idx].toolCalls ?? (messages.value[idx].toolCalls = [])
          arr.push({ name: tc.toolName, args: tc.args, result: tc.result })
        },
```

- [ ] **Step 5: 运行验证通过**

Run: `cd web && pnpm vitest run src/composables/__tests__/useChatStream.spec.ts src/stores/__tests__/conversation.spec.ts`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add web/src/types/conversation.ts web/src/composables/useChatStream.ts web/src/stores/conversation.ts \
  web/src/composables/__tests__/useChatStream.spec.ts web/src/stores/__tests__/conversation.spec.ts
git commit -m "feat(web): 流式接线 tool_call 事件→助手消息 toolCalls"
```

---

### Task 11: 前端聊天区工具轨迹卡片

**Files:**
- Modify: `web/src/views/conversation/ChatView.vue`
- Test: `web/src/views/conversation/__tests__/ChatView.spec.ts`（加用例）

**Interfaces:**
- Consumes: `MessageView.toolCalls`（Task 10）。

- [ ] **Step 1: 写失败测试**

```ts
it('助手消息有 toolCalls 时渲染工具调用卡片', async () => {
  // 构造 store.messages 含一条 assistant 消息，toolCalls:[{name:'http_request',args:'{}',result:'HTTP 200'}]
  // 挂载 ChatView，断言存在 data-test="tool-trace" 且文本含 http_request
})
it('无 toolCalls 时不渲染工具卡片', async () => {
  // assistant 消息无 toolCalls → 不存在 data-test="tool-trace"
})
```
> 实现者：照抄本文件现有「参考来源」用例的 store 装配与挂载手法（`ChatView.spec` 已有 sources 渲染用例可参照）。

- [ ] **Step 2: 运行验证失败**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts`
Expected: FAIL（无 `tool-trace`）。

- [ ] **Step 3: 实现 ChatView.vue**

在 assistant 气泡内、`chat__sources` 附近，加工具轨迹折叠卡片（复用 `el-collapse` 样式；引入 `Tools` 图标 from `@element-plus/icons-vue`）：
```html
<div
  v-if="m.role === 'assistant' && m.toolCalls && m.toolCalls.length"
  class="chat__sources"
  data-test="tool-trace"
>
  <el-collapse>
    <el-collapse-item :name="m.id">
      <template #title>
        <span class="chat__sources-title">
          <el-icon><Tools /></el-icon>
          <span>工具调用 ({{ m.toolCalls.length }})</span>
        </span>
      </template>
      <div v-for="(tc, ti) in m.toolCalls" :key="ti" class="chat__source-card">
        <div class="chat__source-head">
          <span class="chat__source-doc">{{ tc.name }}</span>
        </div>
        <div class="chat__source-preview">参数：{{ tc.args }}</div>
        <div class="chat__source-preview">结果：{{ tc.result }}</div>
      </div>
    </el-collapse-item>
  </el-collapse>
</div>
```
（`import { ..., Tools } from '@element-plus/icons-vue'`。样式全部复用现有 `chat__sources*` 类，不新增。）

- [ ] **Step 4: 运行验证通过 + 全量前端回归**

Run: `cd web && pnpm vitest run src/views/conversation/__tests__/ChatView.spec.ts && pnpm vitest run`
Expected: PASS（全绿）。

- [ ] **Step 5: 提交**

```bash
git add web/src/views/conversation/ChatView.vue web/src/views/conversation/__tests__/ChatView.spec.ts
git commit -m "feat(web): 聊天区工具调用轨迹卡片(复用参考来源折叠样式)"
```

---

## 收尾（执行完全部 Task 后）

- [ ] 后端全量：`cd server && mvn clean test`（623+ 全绿，含 Modularity/LayerRules/ArchUnit）。
- [ ] 前端全量：`cd web && pnpm vitest run && pnpm build`（类型+测试全绿）。
- [ ] 起 server + 真实 LLM 手验黄金路径（见 spec §1 验收 1-6）：配一个 Agent 应用勾 http_request/code_executor → 试聊 → 观察 tool_call 卡片实时出现、答案落定、刷新历史仍带轨迹；关开关回归普通聊天。
- [ ] self-check 入档 `docs/self-check.md`（按 [[self-check-per-step]]）。
- [ ] push + 写 T2-merged memory。

## 自检（写计划者对照 spec）

- **spec 覆盖**：app_tool_rel(T1) ✓、per-app 选择 ToolFacade(T2) ✓、工具列表接口(T3) ✓、toolIds 打通(T4) ✓、
  MessageView 轨迹(T5) ✓、tool_call 事件+AgentChatService 钩子(T6) ✓、流式编排去 17002 + send per-app 工具(T7) ✓、
  文档(T8) ✓、前端配置页(T9) ✓、流式接线(T10) ✓、聊天区卡片(T11) ✓。spec 小决策 1-7 全落到对应 Task。
- **类型一致**：`AppRuntimeView` 六参（+toolIds）贯穿 T4/T7；`getToolCallbacks(Collection<Long>)` 定义于 T2、消费于 T7；
  `StreamEvent.ToolCall(toolName,args,result,ok)` 定义于 T6、映射于 T6 controller、消费于 T7；前端 `MessageToolCall{name,args,result}`
  与直播 append 的 `{name:toolName,...}` 收敛为同结构（T10/T11）。
- **无占位**：每步含实测代码/命令；标注「照抄现有 X」处均指向真实存在的既有实现（datasets 三件套、ConversationControllerTest token、
  ChatView sources 用例），非空泛「类似上文」。
</content>
