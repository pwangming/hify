# app 模块（第一轮）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 app 模块第一轮——对话型应用的元数据 CRUD + 团队共享权限，前后端一体、一起验证。

**Architecture:** 后端按 code-organization 分层（controller→service→mapper→entity，跨模块 DTO 在 api/dto）。仅建 `app` 一张表；model_id 存而不校（②补）；只受理 `chat` 类型；团队共享权限（owner+Admin 才能改删启停）落在 service 层，当前用户由 controller 经 `CurrentUserHolder.current()` 传入。前端成员页（列表 + 服务端分页 + 创建/编辑弹窗 + canModify 门控），立起前端首个 `el-pagination` 范式。

**Tech Stack:** Spring Boot 3 / Java 21 / MyBatis-Plus / PostgreSQL 16(jsonb) / Flyway；Vue 3 + TS + Element Plus + Pinia + vitest。

## Global Constraints

- 后端连库测试（Testcontainers）本轮仍推迟，全部用 Mockito mock mapper；jsonb 正确性由 TypeHandler 单测（纯逻辑）+ §Task 9 端到端走查覆盖。
- 判定测试结果**不 grep `BUILD SUCCESS`**（`-q` 会静音）——看测试计数与进程退出码。
- Long/long 一律序列化为 JSON string（infra 全局 Jackson）：`id`、`modelId`、`ownerId`，以及 `PageResult.total/page/size` 在 JSON 里都是字符串。
- 错误码：复用 `CommonError`（10005/10006/10004/10001），app 段（16xxx）本轮只新增 `16001`。
- 不改 `app/package-info.java` 的 allowedDependencies（本轮不 import provider/knowledge/tool）。不改 `SecurityConfig`（`/api/v1/app/**` 已被 `anyRequest().authenticated()` 覆盖，任意登录用户可访问）。
- 每完成一个 Task，向 `docs/self-check.md` 追加一条自检（沿用文件既有格式：本步做了什么 / 怎么自证 / 已知遗留），与该 Task 的提交同批 commit。
- 提交粒度：每个 Task 末尾 commit 一次；分支为 `feat/app-module`（已创建）。

**关键文件落位（决策）：**
- `AppConfig` 放 `api/dto/`（不是 `dto/`）：被 entity、web dto 共同引用，且 ③ conversation 会消费它；唯一同时满足 ArchUnit「dto 不依赖 entity」「api 不依赖实现」且面向未来的位置。
- jsonb 写入用自定义 `AppConfigTypeHandler`（产出 `PGobject(type=jsonb)`），实体 `@TableName(autoResultMap=true)` 才能让 TypeHandler 在查询映射时生效。

---

### Task 1: jsonb 配置载体与 TypeHandler

**Files:**
- Create: `server/src/main/java/com/hify/app/api/dto/AppConfig.java`
- Create: `server/src/main/java/com/hify/app/config/AppConfigTypeHandler.java`
- Test: `server/src/test/java/com/hify/app/config/AppConfigTypeHandlerTest.java`

**Interfaces:**
- Produces: `record AppConfig(String systemPrompt)`；`class AppConfigTypeHandler extends BaseTypeHandler<AppConfig>`（写出 `PGobject` jsonb，读入从 JSON 反序列化；null/空白读成 `new AppConfig(null)`）。

- [ ] **Step 1: 写失败测试**

```java
package com.hify.app.config;

import com.hify.app.api.dto.AppConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class AppConfigTypeHandlerTest {

    private final AppConfigTypeHandler handler = new AppConfigTypeHandler();

    @Test
    void 写出_序列化为jsonb的PGobject() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, new AppConfig("你是客服助手"), null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(1), captor.capture());
        PGobject obj = (PGobject) captor.getValue();
        assertEquals("jsonb", obj.getType());
        assertEquals("{\"systemPrompt\":\"你是客服助手\"}", obj.getValue());
    }

    @Test
    void 读入_从json反序列化() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("config")).thenReturn("{\"systemPrompt\":\"hi\"}");
        AppConfig cfg = handler.getNullableResult(rs, "config");
        assertEquals("hi", cfg.systemPrompt());
    }

    @Test
    void 读入_空值兜底为空配置() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("config")).thenReturn(null);
        AppConfig cfg = handler.getNullableResult(rs, "config");
        assertNull(cfg.systemPrompt());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppConfigTypeHandlerTest test`
Expected: 编译失败 / FAIL（`AppConfig`、`AppConfigTypeHandler` 未定义）。

- [ ] **Step 3: 写最小实现**

`api/dto/AppConfig.java`：
```java
package com.hify.app.api.dto;

/**
 * 对话型应用的运行配置（jsonb 落库）。本轮仅 systemPrompt（系统提示词，可空）。
 * 跨模块 record：③ conversation 读 app 时消费它取人设。新增字段向后兼容，不算破坏性变更。
 */
public record AppConfig(String systemPrompt) {
}
```

`config/AppConfigTypeHandler.java`：
```java
package com.hify.app.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.app.api.dto.AppConfig;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * app.config（jsonb）↔ {@link AppConfig} 的类型处理器。
 * 写出包成 PGobject(type=jsonb)，否则 PG 报「column is of type jsonb but expression is of type varchar」。
 * 读入空值兜底为 new AppConfig(null)，保证字段不为 null。实体需 @TableName(autoResultMap=true) 才在查询时启用本处理器。
 */
public class AppConfigTypeHandler extends BaseTypeHandler<AppConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, AppConfig parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 app.config 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public AppConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public AppConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public AppConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private AppConfig parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return new AppConfig(null);
        }
        try {
            return MAPPER.readValue(json, AppConfig.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 app.config 失败", e);
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd server && mvn -q -Dtest=AppConfigTypeHandlerTest test`
Expected: PASS（3 个用例）。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加一条（本步：建 AppConfig + jsonb TypeHandler；自证：handler 单测 3 绿；遗留：jsonb 落库正确性待 Task 9 端到端走查）。

```bash
git add server/src/main/java/com/hify/app/api/dto/AppConfig.java \
        server/src/main/java/com/hify/app/config/AppConfigTypeHandler.java \
        server/src/test/java/com/hify/app/config/AppConfigTypeHandlerTest.java docs/self-check.md
git commit -m "feat(app): AppConfig + jsonb TypeHandler（首个 jsonb 列的读写处理）"
```

---

### Task 2: 建表迁移 + 常量 + 实体 + Mapper

**Files:**
- Create: `server/src/main/resources/db/migration/V7__create_app.sql`
- Create: `server/src/main/java/com/hify/app/constant/AppType.java`
- Create: `server/src/main/java/com/hify/app/constant/AppStatus.java`
- Create: `server/src/main/java/com/hify/app/constant/AppError.java`
- Create: `server/src/main/java/com/hify/app/entity/App.java`
- Create: `server/src/main/java/com/hify/app/mapper/AppMapper.java`
- Test: `server/src/test/java/com/hify/app/constant/AppEnumTest.java`

**Interfaces:**
- Consumes: `AppConfig`（Task 1）、`AppConfigTypeHandler`（Task 1）。
- Produces: `enum AppType{CHAT("chat"),WORKFLOW("workflow")}.value()`；`enum AppStatus{ENABLED("enabled"),DISABLED("disabled")}.value()`；`enum AppError implements ErrorCode`（`APP_TYPE_NOT_SUPPORTED=16001/400`）；`class App extends BaseEntity`（name/description/type/modelId/config(AppConfig)/ownerId/status）；`interface AppMapper extends BaseMapper<App>`。

- [ ] **Step 1: 写失败测试（常量自证）**

```java
package com.hify.app.constant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppEnumTest {
    @Test
    void 类型与状态枚举值与DB约束一致() {
        assertEquals("chat", AppType.CHAT.value());
        assertEquals("workflow", AppType.WORKFLOW.value());
        assertEquals("enabled", AppStatus.ENABLED.value());
        assertEquals("disabled", AppStatus.DISABLED.value());
    }

    @Test
    void 类型不支持错误码为16001且400() {
        assertEquals(16001, AppError.APP_TYPE_NOT_SUPPORTED.code());
        assertEquals(HttpStatus.BAD_REQUEST, AppError.APP_TYPE_NOT_SUPPORTED.status());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppEnumTest test`
Expected: 编译失败（AppType/AppStatus/AppError 未定义）。

- [ ] **Step 3: 写实现**

`db/migration/V7__create_app.sql`（照 V5/V6 风格）：
```sql
-- V7：应用表（app 模块）。type 分对话型/工作流型；对话型绑 model_id + config(jsonb)；团队共享制带 owner_id。
-- 跨模块 model_id/owner_id 只存 id、不建外键（data-model.md 第 3 条）。

create table app (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    type        text        not null check (type in ('chat', 'workflow')),
    model_id    bigint,
    config      jsonb       not null default '{}',
    owner_id    bigint      not null,
    status      text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table app is '应用（app 模块）：type 分对话型/工作流型；对话型绑 model_id + config(jsonb)；团队共享制带 owner_id';

-- 应用名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index app_name_uq on app (name) where deleted = false;
```

`constant/AppType.java`：
```java
package com.hify.app.constant;

/** 应用类型，值与 app.type 的 check 约束一致（api-standards 序列化：枚举存小写字符串）。 */
public enum AppType {
    CHAT("chat"),
    WORKFLOW("workflow");

    private final String value;

    AppType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
```

`constant/AppStatus.java`：
```java
package com.hify.app.constant;

/** 应用启停状态，值与 app.status 的 check 约束一致。 */
public enum AppStatus {
    ENABLED("enabled"),
    DISABLED("disabled");

    private final String value;

    AppStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
```

`constant/AppError.java`（照 `ProviderError` 范式）：
```java
package com.hify.app.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * app 模块特有错误码（16xxx 段）。通用语义（不存在/冲突/权限/校验）复用 CommonError。
 */
public enum AppError implements ErrorCode {

    /** 本轮仅支持创建对话型应用；传 type=workflow 时拒绝。 */
    APP_TYPE_NOT_SUPPORTED(16001, HttpStatus.BAD_REQUEST, "暂仅支持创建对话型应用");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    AppError(int code, HttpStatus status, String defaultMessage) {
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

`entity/App.java`：
```java
package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.app.api.dto.AppConfig;
import com.hify.app.config.AppConfigTypeHandler;
import com.hify.common.BaseEntity;

/**
 * 应用表 {@code app} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted）。
 * config 是 jsonb，经 {@link AppConfigTypeHandler} 读写；autoResultMap=true 才让该处理器在查询映射时生效。
 * type/status 存小写字符串（见 AppType/AppStatus 与 DB check）。
 */
@TableName(value = "app", autoResultMap = true)
public class App extends BaseEntity {

    private String name;
    private String description;
    private String type;
    private Long modelId;

    @TableField(typeHandler = AppConfigTypeHandler.class)
    private AppConfig config;

    private Long ownerId;
    private String status;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public AppConfig getConfig() { return config; }
    public void setConfig(AppConfig config) { this.config = config; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

`mapper/AppMapper.java`：
```java
package com.hify.app.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.app.entity.App;

/** app 表数据访问。只被本模块 service 注入（code-organization 第 2 节）。 */
public interface AppMapper extends BaseMapper<App> {
}
```

- [ ] **Step 4: 运行确认通过（含模块边界）**

Run: `cd server && mvn -q -Dtest=AppEnumTest,ModularityTests,LayerRulesTest test`
Expected: PASS。ModularityTests 校验 app 仍只依赖白名单内模块、api 暴露正确；ArchUnit 校验 entity/mapper/dto 分层无越界。

- [ ] **Step 5: 追加自检并提交**

```bash
git add server/src/main/resources/db/migration/V7__create_app.sql \
        server/src/main/java/com/hify/app/constant/ server/src/main/java/com/hify/app/entity/App.java \
        server/src/main/java/com/hify/app/mapper/AppMapper.java \
        server/src/test/java/com/hify/app/constant/AppEnumTest.java docs/self-check.md
git commit -m "feat(app): app 建表迁移 + 类型/状态/错误码枚举 + 实体 + Mapper"
```

---

### Task 3: AppService.create + DTO + toResponse

**Files:**
- Create: `server/src/main/java/com/hify/app/dto/CreateAppRequest.java`
- Create: `server/src/main/java/com/hify/app/dto/AppResponse.java`
- Create: `server/src/main/java/com/hify/app/service/AppService.java`
- Test: `server/src/test/java/com/hify/app/service/AppServiceTest.java`

**Interfaces:**
- Consumes: `App`、`AppMapper`、`AppConfig`、`AppType`、`AppStatus`、`AppError`、`CurrentUser`（`com.hify.infra.security.CurrentUser`，含 `userId()`/`isAdmin()`）。
- Produces:
  - `record CreateAppRequest(String name, String description, String type, Long modelId, AppConfig config)`
  - `record AppResponse(Long id, String name, String description, String type, Long modelId, AppConfig config, Long ownerId, String status, OffsetDateTime createTime, OffsetDateTime updateTime)`
  - `AppService.create(CreateAppRequest req, CurrentUser current) -> AppResponse`（type 非 chat→16001；owner=current.userId()；status 默认 enabled；唯一索引撞→CONFLICT）。
  - 私有 `toResponse(App) -> AppResponse`（后续任务复用）。

- [ ] **Step 1: 写失败测试**

```java
package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceTest {

    private AppMapper mapper;
    private AppService service;

    private final CurrentUser member = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        mapper = mock(AppMapper.class);
        service = new AppService(mapper);
    }

    private CreateAppRequest chatReq() {
        return new CreateAppRequest("客服助手", "答疑", "chat", 5L, new AppConfig("你是客服"));
    }

    @Test
    void 创建_owner取当前用户_状态默认启用_字段落库() {
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);

        AppResponse resp = service.create(chatReq(), member);

        verify(mapper).insert(captor.capture());
        App saved = captor.getValue();
        assertEquals(7L, saved.getOwnerId());
        assertEquals(AppStatus.ENABLED.value(), saved.getStatus());
        assertEquals("chat", saved.getType());
        assertEquals(5L, saved.getModelId());
        assertEquals("你是客服", saved.getConfig().systemPrompt());
        assertEquals("客服助手", resp.name());
    }

    @Test
    void 创建_工作流型_拒绝16001() {
        CreateAppRequest wf = new CreateAppRequest("流程", null, "workflow", null, null);
        BizException ex = assertThrows(BizException.class, () -> service.create(wf, member));
        assertEquals(AppError.APP_TYPE_NOT_SUPPORTED, ex.errorCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void 创建_config缺省兜底为空配置() {
        CreateAppRequest noCfg = new CreateAppRequest("无配置", null, "chat", null, null);
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.create(noCfg, member);
        verify(mapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getConfig().systemPrompt());
    }

    @Test
    void 创建_撞唯一索引_转CONFLICT() {
        when(mapper.insert(any(App.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class, () -> service.create(chatReq(), member));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }
}
```

> 注：`BizException` 需有 `errorCode()` 访问器；provider/identity 测试已这样用（见 ProviderServiceTest）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: 编译失败（CreateAppRequest/AppResponse/AppService 未定义）。

- [ ] **Step 3: 写实现**

`dto/CreateAppRequest.java`：
```java
package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建应用请求。type 本轮仅 'chat' 放行（service 判，workflow→16001）。modelId 可空、本轮存而不校。
 * config 可空（service 兜底为空配置）。校验注解只写在本层。
 */
public record CreateAppRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description,
        @NotBlank String type,
        Long modelId,
        AppConfig config) {
}
```

`dto/AppResponse.java`：
```java
package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;

import java.time.OffsetDateTime;

/** 应用视图。id/modelId/ownerId 为 Long（infra 全局序列化为 string）。 */
public record AppResponse(
        Long id,
        String name,
        String description,
        String type,
        Long modelId,
        AppConfig config,
        Long ownerId,
        String status,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
```

`service/AppService.java`（本任务先放 create + toResponse；后续任务在同类追加方法）：
```java
package com.hify.app.service;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.constant.AppError;
import com.hify.app.constant.AppStatus;
import com.hify.app.constant.AppType;
import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应用业务逻辑。具体类 + @Service（不拆接口）。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 */
@Service
public class AppService {

    private final AppMapper appMapper;

    public AppService(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Transactional
    public AppResponse create(CreateAppRequest req, CurrentUser current) {
        if (!AppType.CHAT.value().equals(req.type())) {
            throw new BizException(AppError.APP_TYPE_NOT_SUPPORTED);
        }
        App entity = new App();
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setType(req.type());
        entity.setModelId(req.modelId());
        entity.setConfig(req.config() == null ? new AppConfig(null) : req.config());
        entity.setOwnerId(current.userId());
        entity.setStatus(AppStatus.ENABLED.value());
        try {
            appMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        return toResponse(entity);
    }

    AppResponse toResponse(App e) {
        return new AppResponse(
                e.getId(), e.getName(), e.getDescription(), e.getType(),
                e.getModelId(), e.getConfig() == null ? new AppConfig(null) : e.getConfig(),
                e.getOwnerId(), e.getStatus(), e.getCreateTime(), e.getUpdateTime());
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 追加自检并提交**

```bash
git add server/src/main/java/com/hify/app/dto/CreateAppRequest.java \
        server/src/main/java/com/hify/app/dto/AppResponse.java \
        server/src/main/java/com/hify/app/service/AppService.java \
        server/src/test/java/com/hify/app/service/AppServiceTest.java docs/self-check.md
git commit -m "feat(app): AppService.create（owner 归属/类型限 chat/重名 409）+ Create/AppResponse DTO"
```

---

### Task 4: AppService 读取（get + page）

**Files:**
- Modify: `server/src/main/java/com/hify/app/service/AppService.java`
- Test: `server/src/test/java/com/hify/app/service/AppServiceTest.java`（追加用例）

**Interfaces:**
- Consumes: `PageResult`（`com.hify.common.page.PageResult`）、MyBatis-Plus `Page`/`LambdaQueryWrapper`、`StringUtils`（`org.springframework.util.StringUtils`）。
- Produces:
  - `AppService.get(Long id) -> AppResponse`（不存在→NOT_FOUND）。
  - `AppService.page(String keyword, String type, int page, int size) -> PageResult<AppResponse>`（`page*size>10000`→PARAM_INVALID；keyword→name like；type→等值；orderByDesc(id)）。

- [ ] **Step 1: 追加失败测试**

在 `AppServiceTest` 追加：
```java
    @org.junit.jupiter.api.Test
    void 详情_不存在抛NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 分页_映射total与列表_不按owner过滤() {
        App a = new App();
        a.setId(1L); a.setName("x"); a.setType("chat"); a.setOwnerId(999L);
        a.setStatus("enabled"); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<App> pg =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        pg.setRecords(java.util.List.of(a));
        pg.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(pg);

        com.hify.common.page.PageResult<AppResponse> result = service.page(null, null, 1, 20);
        assertEquals(1, result.total());
        assertEquals("x", result.list().get(0).name());
    }

    @org.junit.jupiter.api.Test
    void 分页_页深超限抛PARAM_INVALID() {
        BizException ex = assertThrows(BizException.class, () -> service.page(null, null, 1000, 20));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: 编译失败（get/page 未定义）。

- [ ] **Step 3: 写实现（在 AppService 追加）**

```java
    public AppResponse get(Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonError.NOT_FOUND, "应用不存在");
        }
        return toResponse(app);
    }

    public PageResult<AppResponse> page(String keyword, String type, int page, int size) {
        if ((long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页过深，请用筛选条件缩小范围");
        }
        Page<App> result = appMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<App>()
                        .like(StringUtils.hasText(keyword), App::getName, keyword)
                        .eq(StringUtils.hasText(type), App::getType, type)
                        .orderByDesc(App::getId)); // 以 id 结尾保证稳定排序；@TableLogic 自动加 deleted=false
        List<AppResponse> list = result.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, result.getTotal(), page, size);
    }
```
对应新增 import：
```java
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.page.PageResult;
import org.springframework.util.StringUtils;
import java.util.List;
```

- [ ] **Step 4: 运行确认通过**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: PASS（共 7 个用例）。

- [ ] **Step 5: 追加自检并提交**

```bash
git add server/src/main/java/com/hify/app/service/AppService.java \
        server/src/test/java/com/hify/app/service/AppServiceTest.java docs/self-check.md
git commit -m "feat(app): AppService 读取（详情 + 页码分页，团队全可见、页深护栏）"
```

---

### Task 5: AppService 改/删/启停 + 权限判定

**Files:**
- Create: `server/src/main/java/com/hify/app/dto/UpdateAppRequest.java`
- Modify: `server/src/main/java/com/hify/app/service/AppService.java`
- Test: `server/src/test/java/com/hify/app/service/AppServiceTest.java`（追加用例）

**Interfaces:**
- Produces:
  - `record UpdateAppRequest(String name, String description, Long modelId, AppConfig config)`（无 type，type 不可改）。
  - `AppService.update(Long id, UpdateAppRequest req, CurrentUser current) -> AppResponse`
  - `AppService.delete(Long id, CurrentUser current)`（幂等：不存在直接返回）
  - `AppService.enable(Long id, CurrentUser current)` / `disable(...)`
  - 私有 `assertCanModify(App, CurrentUser)`：非 owner 且非 admin→FORBIDDEN；`loadOrThrow(Long)`：不存在→NOT_FOUND。

- [ ] **Step 1: 追加失败测试**

```java
    private App stored(long id, long ownerId, String status) {
        App a = new App();
        a.setId(id); a.setName("app" + id); a.setType("chat"); a.setOwnerId(ownerId);
        a.setStatus(status); a.setConfig(new com.hify.app.api.dto.AppConfig(null));
        return a;
    }

    private final CurrentUser admin = new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN);
    private UpdateAppRequest upd() {
        return new UpdateAppRequest("新名", "新描述", 9L, new com.hify.app.api.dto.AppConfig("改了"));
    }

    @org.junit.jupiter.api.Test
    void 更新_owner放行() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled")); // owner=bob(7)
        AppResponse r = service.update(10L, upd(), member);
        assertEquals("新名", r.name());
        verify(mapper).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_admin放行他人应用() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        service.update(10L, upd(), admin);
        verify(mapper).updateById(any(App.class));
    }

    @org.junit.jupiter.api.Test
    void 更新_他人非admin_拒绝FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        BizException ex = assertThrows(BizException.class, () -> service.update(10L, upd(), member));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).updateById(any());
    }

    @org.junit.jupiter.api.Test
    void 更新_不存在_NOT_FOUND() {
        when(mapper.selectById(10L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.update(10L, upd(), member));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @org.junit.jupiter.api.Test
    void 删除_不存在_幂等返回不报错() {
        when(mapper.selectById(10L)).thenReturn(null);
        service.delete(10L, member);
        verify(mapper, never()).deleteById(any());
    }

    @org.junit.jupiter.api.Test
    void 删除_他人非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 999L, "enabled"));
        BizException ex = assertThrows(BizException.class, () -> service.delete(10L, member));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).deleteById(any());
    }

    @org.junit.jupiter.api.Test
    void 停用_owner放行_写disabled() {
        when(mapper.selectById(10L)).thenReturn(stored(10L, 7L, "enabled"));
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);
        service.disable(10L, member);
        verify(mapper).updateById(captor.capture());
        assertEquals("disabled", captor.getValue().getStatus());
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: 编译失败（UpdateAppRequest/update/delete/disable 未定义）。

- [ ] **Step 3: 写实现**

`dto/UpdateAppRequest.java`：
```java
package com.hify.app.dto;

import com.hify.app.api.dto.AppConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 全量更新应用请求（PUT 语义）。type 不可改，故无 type 字段。 */
public record UpdateAppRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description,
        Long modelId,
        AppConfig config) {
}
```

在 `AppService` 追加（import `UpdateAppRequest`、`AppStatus` 已在；`AppConfig` 已在）：
```java
    @Transactional
    public AppResponse update(Long id, UpdateAppRequest req, CurrentUser current) {
        App app = loadOrThrow(id);
        assertCanModify(app, current);
        app.setName(req.name());
        app.setDescription(req.description());
        app.setModelId(req.modelId());
        app.setConfig(req.config() == null ? new AppConfig(null) : req.config());
        try {
            appMapper.updateById(app);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "应用名已存在", e);
        }
        return toResponse(app);
    }

    @Transactional
    public void delete(Long id, CurrentUser current) {
        App app = appMapper.selectById(id);
        if (app == null) {
            return; // 幂等：删不存在的也算成功（api-standards 2.2）
        }
        assertCanModify(app, current);
        appMapper.deleteById(id);
    }

    @Transactional
    public void enable(Long id, CurrentUser current) {
        setStatus(id, current, AppStatus.ENABLED);
    }

    @Transactional
    public void disable(Long id, CurrentUser current) {
        setStatus(id, current, AppStatus.DISABLED);
    }

    private void setStatus(Long id, CurrentUser current, AppStatus status) {
        App app = loadOrThrow(id);
        assertCanModify(app, current);
        app.setStatus(status.value());
        appMapper.updateById(app);
    }

    private App loadOrThrow(Long id) {
        App app = appMapper.selectById(id);
        if (app == null) {
            throw new BizException(CommonError.NOT_FOUND, "应用不存在");
        }
        return app;
    }

    /** 团队共享制：仅 owner 或 Admin 可改/删/启停（api-standards 第 6 节），否则 FORBIDDEN。 */
    private void assertCanModify(App app, CurrentUser current) {
        if (!current.isAdmin() && !current.userId().equals(app.getOwnerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可操作该应用");
        }
    }
```

- [ ] **Step 4: 运行确认通过**

Run: `cd server && mvn -q -Dtest=AppServiceTest test`
Expected: PASS（共 15 个用例）。

- [ ] **Step 5: 追加自检并提交**

```bash
git add server/src/main/java/com/hify/app/dto/UpdateAppRequest.java \
        server/src/main/java/com/hify/app/service/AppService.java \
        server/src/test/java/com/hify/app/service/AppServiceTest.java docs/self-check.md
git commit -m "feat(app): AppService 改/删/启停 + 团队共享权限判定（owner+Admin）"
```

---

### Task 6: AppController（7 端点）

**Files:**
- Create: `server/src/main/java/com/hify/app/controller/AppController.java`
- Test: `server/src/test/java/com/hify/app/controller/AppControllerTest.java`

**Interfaces:**
- Consumes: `AppService`（全部公有方法）、`CurrentUserHolder.current()`、`Result`、`PageResult`。
- Produces: REST 端点 `/api/v1/app/apps`（见 spec §3 表）。

- [ ] **Step 1: 写失败测试（照 AdminProviderControllerTest 范式）**

```java
package com.hify.app.controller;

import com.hify.app.api.dto.AppConfig;
import com.hify.app.dto.AppResponse;
import com.hify.app.service.AppService;
import com.hify.common.page.PageResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppService appService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private AppResponse sample() {
        return new AppResponse(10L, "客服助手", "答疑", "chat", 5L, new AppConfig("你是客服"),
                7L, "enabled", OffsetDateTime.parse("2026-06-24T10:00:00+08:00"),
                OffsetDateTime.parse("2026-06-24T10:00:00+08:00"));
    }

    @Test
    void 列表_成员可访问_返回PageResult且Long为string() throws Exception {
        when(appService.page(any(), any(), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sample()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/app/apps?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value("10"))   // Long→string
                .andExpect(jsonPath("$.data.total").value("1"));        // long→string
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/app/apps")).andExpect(status().isUnauthorized());
    }

    @Test
    void 创建_名称为空_400并带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/app/apps")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"\",\"type\":\"chat\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 创建_成功_返回完整资源() throws Exception {
        when(appService.create(any(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/app/apps")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"客服助手\",\"type\":\"chat\",\"config\":{\"systemPrompt\":\"你是客服\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("客服助手"))
                .andExpect(jsonPath("$.data.config.systemPrompt").value("你是客服"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && mvn -q -Dtest=AppControllerTest test`
Expected: 编译失败（AppController 未定义）。

- [ ] **Step 3: 写实现**

```java
package com.hify.app.controller;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.dto.UpdateAppRequest;
import com.hify.app.service.AppService;
import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用管理接口（成员族 /api/v1/app/**，任意登录用户可访问；团队共享权限在 service 判 owner+Admin）。
 * 协议层：@Valid 校验 → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/app/apps")
public class AppController {

    private final AppService appService;

    public AppController(AppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public Result<PageResult<AppResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(appService.page(keyword, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<AppResponse> get(@PathVariable Long id) {
        return Result.ok(appService.get(id));
    }

    @PostMapping
    public Result<AppResponse> create(@Valid @RequestBody CreateAppRequest request) {
        return Result.ok(appService.create(request, CurrentUserHolder.current()));
    }

    @PutMapping("/{id}")
    public Result<AppResponse> update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateAppRequest request) {
        return Result.ok(appService.update(id, request, CurrentUserHolder.current()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appService.delete(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        appService.enable(id, CurrentUserHolder.current());
        return Result.ok(null);
    }

    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        appService.disable(id, CurrentUserHolder.current());
        return Result.ok(null);
    }
}
```

- [ ] **Step 4: 运行确认通过 + 全量后端回归**

Run: `cd server && mvn -q -Dtest=AppControllerTest test && mvn -q test`
Expected: AppControllerTest PASS（4 用例）；全量 `mvn test`（含 ModularityTests/ArchUnit/既有模块）全绿。

- [ ] **Step 5: 追加自检并提交**

```bash
git add server/src/main/java/com/hify/app/controller/AppController.java \
        server/src/test/java/com/hify/app/controller/AppControllerTest.java docs/self-check.md
git commit -m "feat(app): AppController 7 端点（成员路由 + 分页 + 当前用户透传 service）"
```

---

### Task 7: 前端类型与 API 层

**Files:**
- Create: `web/src/types/app.ts`
- Create: `web/src/api/app.ts`
- Test: `web/src/api/__tests__/app.spec.ts`

**Interfaces:**
- Produces:
  - `types/app.ts`：`AppType='chat'|'workflow'`、`AppStatus='enabled'|'disabled'`、`AppConfig{ systemPrompt: string | null }`、`App`（响应，id/modelId/ownerId 为 string）、`AppForm`（创建/编辑共用）、`PageResult<T>{ list:T[]; total:string; page:string; size:string }`。
  - `api/app.ts`：`listApps(params) / getApp(id) / createApp(body) / updateApp(id,body) / deleteApp(id) / enableApp(id) / disableApp(id)`。

- [ ] **Step 1: 写失败测试（照 model.spec.ts 范式）**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listApps, getApp, createApp, updateApp, deleteApp, enableApp, disableApp,
} from '@/api/app'
import type { AppForm } from '@/types/app'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const FORM: AppForm = { name: '客服助手', description: '答疑', config: { systemPrompt: '你是客服' } }

describe('app api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listApps → GET /app/apps + 分页/筛选 params', () => {
    listApps({ keyword: '客服', page: 2, size: 20 })
    expect(request.get).toHaveBeenCalledWith('/app/apps', {
      params: { keyword: '客服', page: 2, size: 20 },
    })
  })
  it('getApp → GET /app/apps/{id}', () => {
    getApp('10')
    expect(request.get).toHaveBeenCalledWith('/app/apps/10')
  })
  it('createApp → POST /app/apps + body(type=chat)', () => {
    createApp(FORM)
    expect(request.post).toHaveBeenCalledWith('/app/apps', { ...FORM, type: 'chat' })
  })
  it('updateApp → PUT /app/apps/{id} + body', () => {
    updateApp('10', FORM)
    expect(request.put).toHaveBeenCalledWith('/app/apps/10', FORM)
  })
  it('deleteApp → DELETE /app/apps/{id}', () => {
    deleteApp('10')
    expect(request.delete).toHaveBeenCalledWith('/app/apps/10')
  })
  it('enableApp / disableApp → POST .../{id}/enable|disable', () => {
    enableApp('10'); disableApp('10')
    expect(request.post).toHaveBeenCalledWith('/app/apps/10/enable')
    expect(request.post).toHaveBeenCalledWith('/app/apps/10/disable')
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd web && pnpm vitest run src/api/__tests__/app.spec.ts`
Expected: FAIL（`@/api/app`、`@/types/app` 未定义）。

- [ ] **Step 3: 写实现**

`types/app.ts`：
```ts
/** 应用类型（对齐后端 app.type）。本轮仅 chat 可创建。 */
export type AppType = 'chat' | 'workflow'

/** 应用启停状态。 */
export type AppStatus = 'enabled' | 'disabled'

/** 对话型运行配置（对齐后端 AppConfig，jsonb）。本轮仅系统提示词。 */
export interface AppConfig {
  systemPrompt: string | null
}

/** 应用视图（对齐后端 AppResponse）。id/modelId/ownerId 为 string（Long 序列化防精度丢失）。 */
export interface App {
  id: string
  name: string
  description: string | null
  type: AppType
  modelId: string | null
  config: AppConfig
  ownerId: string
  status: AppStatus
  createTime: string
  updateTime: string
}

/** 创建/编辑共用表单。type 本轮固定 chat（api 层补；模型选择器推迟到②，故不含 modelId）。 */
export interface AppForm {
  name: string
  description: string
  config: AppConfig
}

/** 页码分页结果（对齐后端 PageResult）。total/page/size 后端以 string 下发（long 也序列化为 string）。 */
export interface PageResult<T> {
  list: T[]
  total: string
  page: string
  size: string
}
```

`api/app.ts`：
```ts
import { request } from '@/api/request'
import type { App, AppForm, PageResult } from '@/types/app'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根（不进 admin/）。
const BASE = '/app/apps'

/** 列表（页码分页）。后端：GET /api/v1/app/apps?keyword=&type=&page=&size= */
export function listApps(params: { keyword?: string; type?: AppType; page: number; size: number }) {
  return request.get<PageResult<App>>(BASE, { params })
}

/** 详情。后端：GET .../{id} */
export function getApp(id: string) {
  return request.get<App>(`${BASE}/${id}`)
}

/** 新建（本轮固定对话型）。后端：POST /api/v1/app/apps */
export function createApp(body: AppForm) {
  return request.post<App>(BASE, { ...body, type: 'chat' })
}

/** 全量更新。后端：PUT .../{id} */
export function updateApp(id: string, body: AppForm) {
  return request.put<App>(`${BASE}/${id}`, body)
}

/** 删除（逻辑删除）。后端：DELETE .../{id} */
export function deleteApp(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}

/** 启用。后端：POST .../{id}/enable */
export function enableApp(id: string) {
  return request.post<void>(`${BASE}/${id}/enable`)
}

/** 停用。后端：POST .../{id}/disable */
export function disableApp(id: string) {
  return request.post<void>(`${BASE}/${id}/disable`)
}
```
（`api/app.ts` 顶部补 `import type { AppType } from '@/types/app'` 供 `listApps` 的 params 类型用——与 `App` 合并到同一 import：`import type { App, AppForm, AppType, PageResult } from '@/types/app'`。）

- [ ] **Step 4: 运行确认通过**

Run: `cd web && pnpm vitest run src/api/__tests__/app.spec.ts`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 追加自检并提交**

```bash
git add web/src/types/app.ts web/src/api/app.ts web/src/api/__tests__/app.spec.ts docs/self-check.md
git commit -m "feat(web): app 模块类型与 API 层（成员路由 + 分页 PageResult）"
```

---

### Task 8: 前端应用列表页 AppList.vue

**Files:**
- Modify: `web/src/views/app/AppList.vue`（替换现占位）
- Test: `web/src/views/app/__tests__/AppList.spec.ts`

**Interfaces:**
- Consumes: `api/app.ts` 全部函数、`useUserStore`（`isAdmin`、`user.id`）、`PageHeader`、`ContentCard`、`formatDateTime`。

- [ ] **Step 1: 写失败测试（照 ProviderList.spec.ts 范式）**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listApps, createApp, deleteApp } from '@/api/app'
import type { App, PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import AppList from '@/views/app/AppList.vue'

vi.mock('@/api/app', () => ({
  listApps: vi.fn(), getApp: vi.fn(), createApp: vi.fn(), updateApp: vi.fn(),
  deleteApp: vi.fn(), enableApp: vi.fn(), disableApp: vi.fn(),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page(list: App[]): PageResult<App> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const MINE: App = {
  id: '1', name: '我的助手', description: null, type: 'chat', modelId: null,
  config: { systemPrompt: null }, ownerId: '7', status: 'enabled',
  createTime: '2026-06-24T10:00:00+08:00', updateTime: '2026-06-24T10:00:00+08:00',
}
const OTHERS: App = { ...MINE, id: '2', name: '他人应用', ownerId: '999' }

describe('AppList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listApps).mockResolvedValue(page([MINE, OTHERS]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户=bob(7)
  })

  it('挂载拉取并渲染应用名', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listApps).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('我的助手')
    expect(wrapper.text()).toContain('他人应用')
  })

  it('canModify 门控：自己的应用有编辑按钮，他人没有', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(true)   // 我的
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(false)  // 他人
  })

  it('删除自己的应用调用 deleteApp', async () => {
    vi.mocked(deleteApp).mockResolvedValue(undefined)
    // 二次确认弹窗放行
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteApp).toHaveBeenCalledWith('1')
  })

  it('创建：空名不提交', async () => {
    const wrapper = mount(AppList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createApp).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts`
Expected: FAIL（AppList 仍是占位，无表格/按钮/data-test）。

- [ ] **Step 3: 写实现**

替换 `web/src/views/app/AppList.vue` 全文：
```vue
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  listApps, createApp, updateApp, deleteApp, enableApp, disableApp,
} from '@/api/app'
import type { App, AppForm } from '@/types/app'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const DESC_MAX = 200

const userStore = useUserStore()

const apps = ref<App[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 团队共享制：仅 owner 或 Admin 可改/删/启停（与后端 10004 双保险）。 */
function canModify(app: App): boolean {
  return userStore.isAdmin || app.ownerId === userStore.user?.id
}

async function load() {
  loading.value = true
  try {
    const res = await listApps({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    apps.value = res.list
    total.value = Number(res.total) // 后端以 string 下发
  } finally {
    loading.value = false
  }
}
onMounted(load)

function onSearch() {
  page.value = 1
  load()
}
function onPageChange(p: number) {
  page.value = p
  load()
}

async function confirmDanger(message: string, title: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning' })
    return true
  } catch {
    return false
  }
}

async function runAction(action: () => Promise<unknown>, successMsg: string) {
  try {
    await action()
    ElMessage.success(successMsg)
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

async function onEnable(row: App) {
  await runAction(() => enableApp(row.id), '已启用')
}
async function onDisable(row: App) {
  if (!(await confirmDanger(`确定停用应用「${row.name}」？`, '停用确认'))) return
  await runAction(() => disableApp(row.id), '已停用')
}
async function onDelete(row: App) {
  if (!(await confirmDanger(`确定删除应用「${row.name}」？此操作不可恢复。`, '删除确认'))) return
  await runAction(() => deleteApp(row.id), '已删除')
}

// —— 创建 / 编辑弹窗（共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<AppForm>({ name: '', description: '', config: { systemPrompt: '' } })

const rules: FormRules<AppForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  form.config = { systemPrompt: '' }
  dialogVisible.value = true
}
function openEdit(row: App) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description ?? ''
  form.config = { systemPrompt: row.config.systemPrompt ?? '' }
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（同 UserList/ProviderList）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (form.description.length > DESC_MAX) return
  try {
    if (editingId.value === null) {
      await createApp({ ...form })
      ElMessage.success('应用已创建')
    } else {
      await updateApp(editingId.value, { ...form })
      ElMessage.success('应用已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开 */
  }
}
</script>

<template>
  <div class="app-list">
    <PageHeader title="应用管理" description="团队共享：全员可见，编辑/删除仅创建者与管理员">
      <el-input
        v-model="keyword"
        data-test="search"
        placeholder="搜索应用名"
        clearable
        class="app-list__search"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" data-test="create-open" @click="openCreate">新建应用</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="apps" data-test="app-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column label="类型">
          <template #default><el-tag>对话</el-tag></template>
        </el-table-column>
        <el-table-column label="归属">
          <template #default="{ row }">
            <el-tag :type="(row as App).ownerId === userStore.user?.id ? 'success' : 'info'">
              {{ (row as App).ownerId === userStore.user?.id ? '我创建' : '其他成员' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <el-tag :type="(row as App).status === 'enabled' ? 'success' : 'info'">
              {{ (row as App).status === 'enabled' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as App).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <div v-if="canModify(row as App)" class="app-list__ops">
              <el-button
                v-if="(row as App).status === 'enabled'"
                :data-test="`disable-${(row as App).id}`"
                size="small"
                @click="onDisable(row as App)"
                >停用</el-button
              >
              <el-button
                v-else
                :data-test="`enable-${(row as App).id}`"
                size="small"
                type="success"
                @click="onEnable(row as App)"
                >启用</el-button
              >
              <el-button
                :data-test="`edit-${(row as App).id}`"
                size="small"
                @click="openEdit(row as App)"
                >编辑</el-button
              >
              <el-button
                :data-test="`delete-${(row as App).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as App)"
                >删除</el-button
              >
            </div>
            <span v-else class="app-list__readonly">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="app-list__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建应用' : '编辑应用'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="类型">
          <el-tag>对话应用</el-tag>
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" data-test="form-desc" maxlength="200" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input
            v-model="form.config.systemPrompt"
            data-test="form-prompt"
            type="textarea"
            :rows="3"
            placeholder="给这个助手设定人设/职责（可选）"
          />
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
.app-list__search {
  width: 220px;
}
.app-list__ops {
  display: flex;
  align-items: center;
  gap: $spacing-sm;
}
.app-list__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
</style>
```

- [ ] **Step 4: 运行确认通过 + 前端全量回归**

Run: `cd web && pnpm vitest run src/views/app/__tests__/AppList.spec.ts && pnpm test && pnpm build`
Expected: AppList 测试 PASS（4 用例）；`pnpm test` 全量绿；`pnpm build` 通过（类型检查无误）。

- [ ] **Step 5: 追加自检并提交**

```bash
git add web/src/views/app/AppList.vue web/src/views/app/__tests__/AppList.spec.ts docs/self-check.md
git commit -m "feat(web): 应用列表页（服务端分页 + canModify 门控 + 创建/编辑弹窗）"
```

---

### Task 9: 端到端验证与收尾

**Files:**
- Modify: `docs/self-check.md`（追加端到端走查结论）

- [ ] **Step 1: 后端全量回归**

Run: `cd server && mvn -q test`
Expected: 全绿（app 模块新测试 + ModularityTests + LayerRulesTest + 既有模块）。失败则回到对应 Task 修复，不跳过。

- [ ] **Step 2: 前端全量回归 + 构建**

Run: `cd web && pnpm test && pnpm build`
Expected: 全绿、构建通过。

- [ ] **Step 3: 起服务做端到端走查（验证 jsonb 落库 + 权限）**

启动本地后端（连本地 PG，Flyway 自动跑 V7）与前端 dev：
1. 用 admin 账号登录 → 进「应用管理」→ 新建应用「客服助手」，填系统提示词 → 列表出现，归属显示「我创建」。
2. 详情/编辑该应用 → 系统提示词回显正确（**证明 jsonb 读写正确**）。
3. 用 member 账号（如无则 admin 先建一个）登录 → 看到 admin 建的「客服助手」，归属「其他成员」，**无操作按钮**；自己新建一个能编辑/删除。
4. admin 账号能编辑/删除任意成员的应用。
5. 翻页/搜索/启停/删除逐项点一遍。
将结论（含「jsonb 回显正确」「member 看不到他人操作按钮」两条关键证据）追加到 `docs/self-check.md` 并提交。

```bash
git add docs/self-check.md
git commit -m "docs(app): app 模块第一轮端到端走查自检"
```

- [ ] **Step 4: 收尾**

调用 superpowers:finishing-a-development-branch，按其指引决定合并/PR（分支 `feat/app-module`）。

---

## Self-Review（写完计划后对照 spec）

**1. Spec coverage：**
- §2.1 app 表 → Task 2（V7 迁移）✅；§2.2 jsonb 风险 → Task 1（TypeHandler）+ Task 9 Step 3 走查 ✅
- §3 API 7 端点 / PageResult / 16001 / 权限 → Task 6（controller）+ Task 3/4/5（service）✅
- §3.4 团队共享权限 → Task 5（assertCanModify）✅；§3.5 重名 409（catch DuplicateKey，不先查后插）→ Task 3/5 ✅
- §4 文件清单 → Task 1-6 覆盖（AppConfig 落位修正为 api/dto，已在 Global Constraints 记录）✅
- §5 前端 types/api/页面/分页/canModify/无模型选择器 → Task 7/8 ✅
- §6 测试策略（mock、TDD、ArchUnit 绿）→ 各 Task Step + Task 6 Step4/Task 9 ✅
- §7 已知缺口（model_id 不校、无模型选择器）→ 实现中体现（service create 不校 modelId；表单无模型字段）✅
- §8 端到端验证 → Task 9 ✅

**2. Placeholder 扫描：** 无 TBD/TODO；每个代码步骤含完整代码与确切命令。

**3. 类型一致性：** `AppConfig(systemPrompt)`、`AppService.create/get/page/update/delete/enable/disable(... CurrentUser)`、`AppResponse(10 字段)`、`PageResult{list,total,page,size}`、前端 `listApps(params)`/`createApp` 注入 `type:'chat'` —— 前后任务签名一致。

> 备注：后端命令用 `mvn`（仓库无 mvnw 包装器），从 `server/` 目录执行。前端命令从 `web/` 执行。
