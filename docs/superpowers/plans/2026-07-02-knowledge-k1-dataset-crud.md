# knowledge K1 知识库管理（dataset CRUD）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** knowledge 模块首个功能落地——`dataset` 表 + CRUD 接口 + 前端知识库列表页（团队共享权限）。

**Architecture:** 照 app 模块的既有三层模式（Controller 协议层 → Service 业务层含权限判定 → MyBatis-Plus Mapper），前端照 AppList.vue 的列表页模式。Spec：`docs/superpowers/specs/2026-07-02-knowledge-k1-dataset-crud-design.md`。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + Flyway/PostgreSQL；Vue 3 `<script setup>` + Element Plus + vitest。

## Global Constraints

- 后端命令在 `/home/wang/playlab/hify/server` 下跑，前端命令在 `/home/wang/playlab/hify/web` 下跑；**禁止在仓库根跑构建**。
- **mvn 不带 `-q`**（会静音测试汇总行，无法判定结果）。
- TDD 先红后绿：每个测试步先跑出失败，再写实现。
- 错误码零新增：只用 `CommonError`（10001/10004/10005/10006）；不建 `knowledge/constant` 枚举、不建 `knowledge/api` Facade（K5 再说）。
- 不加任何新依赖；不改 SecurityConfig（`/api/v1/**` 已 `anyRequest().authenticated()`）。
- 不改已合并迁移脚本；本轮只新增 `V13__create_dataset.sql`。
- Long 由 infra Jackson 全局序列化为 string，前端类型一律 `string`。
- Java 无 Lombok，getter/setter 手写；record 用于 DTO。

---

### Task 1: V13 迁移 + Dataset 实体 + Mapper

**Files:**
- Create: `server/src/main/resources/db/migration/V13__create_dataset.sql`
- Create: `server/src/main/java/com/hify/knowledge/entity/Dataset.java`
- Create: `server/src/main/java/com/hify/knowledge/mapper/DatasetMapper.java`

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`（id/deleted/createTime/updateTime 与 `@TableLogic` 软删都在基类）。
- Produces: `Dataset`（getName/setName、getDescription/setDescription、getOwnerId/setOwnerId + 基类字段）、`DatasetMapper extends BaseMapper<Dataset>`——Task 2 的 Service 依赖两者。

- [ ] **Step 1: 写迁移脚本**

`server/src/main/resources/db/migration/V13__create_dataset.sql`：

```sql
-- V13：知识库表（knowledge 模块）。团队共享制带 owner_id；文档/分段表留 K2。
-- 跨模块 owner_id 只存 id、不建外键（data-model.md 第 3 条）。

create table dataset (
    id          bigint      generated always as identity primary key,
    name        text        not null check (char_length(name) <= 50),
    description text        check (char_length(description) <= 200),
    owner_id    bigint      not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table dataset is '知识库（knowledge 模块）：团队共享制带 owner_id；文档与分段见 kb_document/kb_chunk（K2）';

-- 知识库名团队内唯一（部分唯一索引，配合软删可同名重建）
create unique index dataset_name_uq on dataset (name) where deleted = false;
```

- [ ] **Step 2: 写实体**

`server/src/main/java/com/hify/knowledge/entity/Dataset.java`：

```java
package com.hify.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 知识库表 {@code dataset} 映射实体。继承 BaseEntity（id/createTime/updateTime/deleted，软删由基类 @TableLogic 生效）。
 */
@TableName("dataset")
public class Dataset extends BaseEntity {

    private String name;
    private String description;
    private Long ownerId;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}
```

- [ ] **Step 3: 写 Mapper**

`server/src/main/java/com/hify/knowledge/mapper/DatasetMapper.java`：

```java
package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.Dataset;
import org.apache.ibatis.annotations.Mapper;

/** dataset 表访问。K1 纯框架 CRUD，无手写 SQL。 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {
}
```

- [ ] **Step 4: 编译 + 既有测试回归（含 Modulith 边界）**

```bash
cd /home/wang/playlab/hify/server && mvn test
```

Expected: `BUILD SUCCESS`，Tests run 数量与 main 基线一致（287+）、0 failures。knowledge 空壳变实不改白名单（package-info 已声明 `provider::api, common, infra`），ModularityTests 应通过。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/resources/db/migration/V13__create_dataset.sql server/src/main/java/com/hify/knowledge/entity server/src/main/java/com/hify/knowledge/mapper && git commit -m "feat(knowledge): V13 dataset 表 + 实体 + Mapper（K1）"
```

---

### Task 2: DTO + DatasetService（TDD）

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/dto/CreateDatasetRequest.java`
- Create: `server/src/main/java/com/hify/knowledge/dto/UpdateDatasetRequest.java`
- Create: `server/src/main/java/com/hify/knowledge/dto/DatasetResponse.java`
- Create: `server/src/main/java/com/hify/knowledge/service/DatasetService.java`
- Test: `server/src/test/java/com/hify/knowledge/service/DatasetServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `Dataset` / `DatasetMapper`；`CurrentUser`（`userId()` / `isAdmin()`）；`BizException(CommonError, message)`；`PageResult.of(list, total, page, size)`。
- Produces（Task 3 的 Controller 依赖这些签名）:
  - `DatasetResponse page(String keyword, int page, int size)` → 实际返回 `PageResult<DatasetResponse>`
  - `DatasetResponse get(Long id)`
  - `DatasetResponse create(CreateDatasetRequest req, CurrentUser current)`
  - `DatasetResponse update(Long id, UpdateDatasetRequest req, CurrentUser current)`
  - `void delete(Long id, CurrentUser current)`
  - `record CreateDatasetRequest(String name, String description)` / `record UpdateDatasetRequest(String name, String description)` / `record DatasetResponse(Long id, String name, String description, Long ownerId, OffsetDateTime createTime, OffsetDateTime updateTime)`

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/service/DatasetServiceTest.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetServiceTest {

    private DatasetMapper mapper;
    private DatasetService service;

    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);
    private final CurrentUser other = new CurrentUser(8L, "carol", CurrentUser.ROLE_MEMBER);
    private final CurrentUser admin = new CurrentUser(1L, "admin", CurrentUser.ROLE_ADMIN);

    @BeforeEach
    void setUp() {
        mapper = mock(DatasetMapper.class);
        service = new DatasetService(mapper);
    }

    /** bob(7) 拥有的一条知识库记录。 */
    private Dataset owned() {
        Dataset d = new Dataset();
        d.setId(10L);
        d.setName("客服知识库");
        d.setDescription("售后答疑");
        d.setOwnerId(7L);
        return d;
    }

    @Test
    void 创建_owner取当前用户_字段落库() {
        ArgumentCaptor<Dataset> captor = ArgumentCaptor.forClass(Dataset.class);

        DatasetResponse resp = service.create(new CreateDatasetRequest("客服知识库", "售后答疑"), owner);

        verify(mapper).insert(captor.capture());
        Dataset saved = captor.getValue();
        assertEquals(7L, saved.getOwnerId());
        assertEquals("客服知识库", saved.getName());
        assertEquals("售后答疑", saved.getDescription());
        assertEquals("客服知识库", resp.name());
    }

    @Test
    void 创建_撞唯一索引_转CONFLICT() {
        when(mapper.insert(any(Dataset.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class,
                () -> service.create(new CreateDatasetRequest("客服知识库", null), owner));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 详情_不存在_NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 分页_参数非法_PARAM_INVALID() {
        BizException ex = assertThrows(BizException.class, () -> service.page(null, 0, 20));
        assertEquals(CommonError.PARAM_INVALID, ex.errorCode());
        BizException deep = assertThrows(BizException.class, () -> service.page(null, 101, 100));
        assertEquals(CommonError.PARAM_INVALID, deep.errorCode());
    }

    @Test
    void 分页_返回PageResult() {
        Page<Dataset> page = Page.of(1, 20);
        page.setRecords(List.of(owned()));
        page.setTotal(1);
        when(mapper.selectPage(any(), any())).thenReturn(page);

        var result = service.page("客服", 1, 20);

        assertEquals(1, result.total());
        assertEquals("客服知识库", result.list().get(0).name());
    }

    @Test
    void 更新_不存在_NOT_FOUND() {
        when(mapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.update(99L, new UpdateDatasetRequest("新名", null), owner));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 更新_非owner非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(owned());
        BizException ex = assertThrows(BizException.class,
                () -> service.update(10L, new UpdateDatasetRequest("新名", null), other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).updateById(any(Dataset.class));
    }

    @Test
    void 更新_owner_全量覆盖_description传null置空() {
        when(mapper.selectById(10L)).thenReturn(owned());
        ArgumentCaptor<Dataset> captor = ArgumentCaptor.forClass(Dataset.class);

        service.update(10L, new UpdateDatasetRequest("新名", null), owner);

        verify(mapper).updateById(captor.capture());
        assertEquals("新名", captor.getValue().getName());
        assertEquals(null, captor.getValue().getDescription()); // PUT 全量：未传视为置空
    }

    @Test
    void 更新_admin可改他人() {
        when(mapper.selectById(10L)).thenReturn(owned());
        service.update(10L, new UpdateDatasetRequest("管理员改名", null), admin);
        verify(mapper).updateById(any(Dataset.class));
    }

    @Test
    void 更新_撞唯一索引_转CONFLICT() {
        when(mapper.selectById(10L)).thenReturn(owned());
        when(mapper.updateById(any(Dataset.class))).thenThrow(new DuplicateKeyException("dup"));
        BizException ex = assertThrows(BizException.class,
                () -> service.update(10L, new UpdateDatasetRequest("重名", null), owner));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 删除_不存在_幂等不抛() {
        when(mapper.selectById(99L)).thenReturn(null);
        service.delete(99L, owner); // 不抛异常即通过
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 删除_非owner非admin_FORBIDDEN() {
        when(mapper.selectById(10L)).thenReturn(owned());
        BizException ex = assertThrows(BizException.class, () -> service.delete(10L, other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(mapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 删除_owner_软删() {
        when(mapper.selectById(10L)).thenReturn(owned());
        service.delete(10L, owner);
        verify(mapper).deleteById(10L); // @TableLogic 使 deleteById = update set deleted=true
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（红）**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DatasetServiceTest
```

Expected: `COMPILATION ERROR`（DatasetService / DTO 不存在）。

- [ ] **Step 3: 写 DTO**

`server/src/main/java/com/hify/knowledge/dto/CreateDatasetRequest.java`：

```java
package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建知识库入参。name 必填 ≤50；description 选填 ≤200（与 V13 check 同刻度）。 */
public record CreateDatasetRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {
}
```

`server/src/main/java/com/hify/knowledge/dto/UpdateDatasetRequest.java`：

```java
package com.hify.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 全量更新入参（PUT 语义：description 不传即置空，api-standards §2.2）。 */
public record UpdateDatasetRequest(
        @NotBlank @Size(max = 50) String name,
        @Size(max = 200) String description) {
}
```

`server/src/main/java/com/hify/knowledge/dto/DatasetResponse.java`：

```java
package com.hify.knowledge.dto;

import java.time.OffsetDateTime;

/** 知识库视图。id/ownerId 为 Long（infra 全局序列化为 string）。不做 ownerName 联查（前端对比 ownerId）。 */
public record DatasetResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {
}
```

- [ ] **Step 4: 写 Service**

`server/src/main/java/com/hify/knowledge/service/DatasetService.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.entity.Dataset;
import com.hify.knowledge.mapper.DatasetMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库业务逻辑。团队共享权限判定在本层（assertCanModify）。
 * 当前用户由 controller 经 CurrentUserHolder 传入，本层不直接读安全上下文（便于单测）。
 * 重名不做插入前预查：靠部分唯一索引 + DuplicateKeyException → CONFLICT（无并发窗口，照 AppService）。
 */
@Service
public class DatasetService {

    private final DatasetMapper datasetMapper;

    public DatasetService(DatasetMapper datasetMapper) {
        this.datasetMapper = datasetMapper;
    }

    @Transactional
    public DatasetResponse create(CreateDatasetRequest req, CurrentUser current) {
        Dataset entity = new Dataset();
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setOwnerId(current.userId());
        try {
            datasetMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "知识库名已存在", e);
        }
        return toResponse(entity);
    }

    public DatasetResponse get(Long id) {
        return toResponse(loadOrThrow(id));
    }

    public PageResult<DatasetResponse> page(String keyword, int page, int size) {
        if (page < 1 || size < 1 || (long) page * size > 10_000) {
            throw new BizException(CommonError.PARAM_INVALID, "分页参数非法或过深，请用筛选条件缩小范围");
        }
        Page<Dataset> result = datasetMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<Dataset>()
                        .like(StringUtils.hasText(keyword), Dataset::getName, keyword)
                        .orderByDesc(Dataset::getId)); // id 倒序=按创建先后稳定排序；@TableLogic 自动加 deleted=false
        return PageResult.of(result.getRecords().stream().map(this::toResponse).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public DatasetResponse update(Long id, UpdateDatasetRequest req, CurrentUser current) {
        Dataset dataset = loadOrThrow(id);
        assertCanModify(dataset, current);
        dataset.setName(req.name());
        dataset.setDescription(req.description());
        try {
            datasetMapper.updateById(dataset);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "知识库名已存在", e);
        }
        return toResponse(dataset);
    }

    @Transactional
    public void delete(Long id, CurrentUser current) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            return; // 幂等：删不存在的也算成功（api-standards §2.2）
        }
        assertCanModify(dataset, current);
        datasetMapper.deleteById(id);
    }

    private Dataset loadOrThrow(Long id) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        return dataset;
    }

    /** 团队共享制：仅 owner 或 Admin 可改/删（api-standards 第 6 节），否则 FORBIDDEN。 */
    private void assertCanModify(Dataset dataset, CurrentUser current) {
        if (!current.isAdmin() && !current.userId().equals(dataset.getOwnerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可操作该知识库");
        }
    }

    private DatasetResponse toResponse(Dataset e) {
        return new DatasetResponse(e.getId(), e.getName(), e.getDescription(),
                e.getOwnerId(), e.getCreateTime(), e.getUpdateTime());
    }
}
```

- [ ] **Step 5: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DatasetServiceTest
```

Expected: `Tests run: 13, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/dto server/src/main/java/com/hify/knowledge/service server/src/test/java/com/hify/knowledge && git commit -m "feat(knowledge): DatasetService CRUD + 团队共享权限（TDD）"
```

---

### Task 3: DatasetController（TDD）+ 后端全量回归

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/controller/DatasetController.java`
- Test: `server/src/test/java/com/hify/knowledge/controller/DatasetControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `DatasetService` 全部方法签名；`CurrentUserHolder.current()`；`Result.ok(...)`。
- Produces: HTTP 端点 `/api/v1/knowledge/datasets`（GET 列表 / GET {id} / POST / PUT {id} / DELETE {id}），前端 Task 5 对接。

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/controller/DatasetControllerTest.java`（安全栈 @Import 照 AppControllerTest）：

```java
package com.hify.knowledge.controller;

import com.hify.common.page.PageResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.service.DatasetService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class DatasetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private DatasetService datasetService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private DatasetResponse sample() {
        return new DatasetResponse(10L, "客服知识库", "售后答疑", 7L,
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"),
                OffsetDateTime.parse("2026-07-02T10:00:00+08:00"));
    }

    @Test
    void 列表_成员可访问_返回PageResult且Long为string() throws Exception {
        when(datasetService.page(any(), eq(1), eq(20)))
                .thenReturn(PageResult.of(List.of(sample()), 1, 1, 20));
        mockMvc.perform(get("/api/v1/knowledge/datasets?page=1&size=20")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].id").value("10"))   // Long→string
                .andExpect(jsonPath("$.data.list[0].ownerId").value("7"))
                .andExpect(jsonPath("$.data.total").value("1"));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge/datasets")).andExpect(status().isUnauthorized());
    }

    @Test
    void 详情_GET带id() throws Exception {
        when(datasetService.get(10L)).thenReturn(sample());
        mockMvc.perform(get("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("客服知识库"));
    }

    @Test
    void 创建_名称为空_400并带字段错误() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/datasets")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 创建_成功_返回完整资源() throws Exception {
        when(datasetService.create(any(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/knowledge/datasets")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"客服知识库\",\"description\":\"售后答疑\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("10"))
                .andExpect(jsonPath("$.data.name").value("客服知识库"));
    }

    @Test
    void 更新_PUT带id() throws Exception {
        when(datasetService.update(eq(10L), any(), any())).thenReturn(sample());
        mockMvc.perform(put("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"name\":\"新名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 删除_成功_data为null() throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge/datasets/10")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(datasetService).delete(eq(10L), any());
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（红）**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DatasetControllerTest
```

Expected: `COMPILATION ERROR`（DatasetController 不存在）。

- [ ] **Step 3: 写 Controller**

`server/src/main/java/com/hify/knowledge/controller/DatasetController.java`：

```java
package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.common.page.PageResult;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.knowledge.dto.CreateDatasetRequest;
import com.hify.knowledge.dto.DatasetResponse;
import com.hify.knowledge.dto.UpdateDatasetRequest;
import com.hify.knowledge.service.DatasetService;
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
 * 知识库管理接口（成员族 /api/v1/knowledge/**，任意登录用户可访问；团队共享权限在 service 判 owner+Admin）。
 * 协议层：@Valid 校验 → 取当前用户 → 调 service → 包 Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/knowledge/datasets")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @GetMapping
    public Result<PageResult<DatasetResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(datasetService.page(keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<DatasetResponse> get(@PathVariable Long id) {
        return Result.ok(datasetService.get(id));
    }

    @PostMapping
    public Result<DatasetResponse> create(@Valid @RequestBody CreateDatasetRequest request) {
        return Result.ok(datasetService.create(request, CurrentUserHolder.current()));
    }

    @PutMapping("/{id}")
    public Result<DatasetResponse> update(@PathVariable Long id,
                                          @Valid @RequestBody UpdateDatasetRequest request) {
        return Result.ok(datasetService.update(id, request, CurrentUserHolder.current()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasetService.delete(id, CurrentUserHolder.current());
        return Result.ok(null);
    }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/server && mvn test -Dtest=DatasetControllerTest
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 5: 后端全量回归（含 ModularityTests / ArchUnit）**

```bash
cd /home/wang/playlab/hify/server && mvn test
```

Expected: `BUILD SUCCESS`，0 failures（基线 287 + 新增 20 个）。若 ModularityTests 红，检查 knowledge 是否只 import 了 common/infra（K1 不应触碰 provider）。

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add server/src/main/java/com/hify/knowledge/controller server/src/test/java/com/hify/knowledge/controller && git commit -m "feat(knowledge): DatasetController /api/v1/knowledge/datasets（TDD）"
```

---

### Task 4: 前端类型 + API 层（TDD）

**Files:**
- Create: `web/src/types/knowledge.ts`
- Create: `web/src/api/knowledge.ts`
- Test: `web/src/api/__tests__/knowledge.spec.ts`

**Interfaces:**
- Consumes: `request`（`@/api/request`，baseURL 已含 `/api/v1`）；`PageResult<T>`（`@/types/app`，既有唯一定义处）。
- Produces（Task 5 的页面依赖）: `Dataset` / `DatasetForm` 类型；`listDatasets({keyword?, page, size})` / `getDataset(id)` / `createDataset(body)` / `updateDataset(id, body)` / `deleteDataset(id)`。

- [ ] **Step 1: 写失败测试**

`web/src/api/__tests__/knowledge.spec.ts`：

```typescript
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import {
  listDatasets, getDataset, createDataset, updateDataset, deleteDataset,
} from '@/api/knowledge'
import type { DatasetForm } from '@/types/knowledge'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

const FORM: DatasetForm = { name: '客服知识库', description: '售后答疑' }

describe('knowledge api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('listDatasets → GET /knowledge/datasets + 分页/搜索 params', () => {
    listDatasets({ keyword: '客服', page: 2, size: 20 })
    expect(request.get).toHaveBeenCalledWith('/knowledge/datasets', {
      params: { keyword: '客服', page: 2, size: 20 },
    })
  })
  it('getDataset → GET /knowledge/datasets/{id}', () => {
    getDataset('10')
    expect(request.get).toHaveBeenCalledWith('/knowledge/datasets/10')
  })
  it('createDataset → POST /knowledge/datasets + body', () => {
    createDataset(FORM)
    expect(request.post).toHaveBeenCalledWith('/knowledge/datasets', FORM)
  })
  it('updateDataset → PUT /knowledge/datasets/{id} + body', () => {
    updateDataset('10', FORM)
    expect(request.put).toHaveBeenCalledWith('/knowledge/datasets/10', FORM)
  })
  it('deleteDataset → DELETE /knowledge/datasets/{id}', () => {
    deleteDataset('10')
    expect(request.delete).toHaveBeenCalledWith('/knowledge/datasets/10')
  })
})
```

- [ ] **Step 2: 跑测试确认失败（红）**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/api/__tests__/knowledge.spec.ts
```

Expected: FAIL（`Cannot find module '@/api/knowledge'`）。

- [ ] **Step 3: 写类型与 API 模块**

`web/src/types/knowledge.ts`：

```typescript
/** 知识库视图（对齐后端 DatasetResponse）。id/ownerId 为 string（Long 序列化防精度丢失）。 */
export interface Dataset {
  id: string
  name: string
  description: string | null
  ownerId: string
  createTime: string
  updateTime: string
}

/** 创建/编辑共用表单。 */
export interface DatasetForm {
  name: string
  description: string
}
```

`web/src/api/knowledge.ts`：

```typescript
import { request } from '@/api/request'
import type { Dataset, DatasetForm } from '@/types/knowledge'
import type { PageResult } from '@/types/app'

// baseURL 已含 /api/v1（见 api/request.ts），此处只拼模块内路径。成员资源，放 api/ 根（不进 admin/）。
const BASE = '/knowledge/datasets'

/** 列表（页码分页）。后端：GET /api/v1/knowledge/datasets?keyword=&page=&size= */
export function listDatasets(params: { keyword?: string; page: number; size: number }) {
  return request.get<PageResult<Dataset>>(BASE, { params })
}

/** 详情。后端：GET .../{id} */
export function getDataset(id: string) {
  return request.get<Dataset>(`${BASE}/${id}`)
}

/** 新建。后端：POST /api/v1/knowledge/datasets */
export function createDataset(body: DatasetForm) {
  return request.post<Dataset>(BASE, body)
}

/** 全量更新。后端：PUT .../{id} */
export function updateDataset(id: string, body: DatasetForm) {
  return request.put<Dataset>(`${BASE}/${id}`, body)
}

/** 删除（逻辑删除）。后端：DELETE .../{id} */
export function deleteDataset(id: string) {
  return request.delete<void>(`${BASE}/${id}`)
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/api/__tests__/knowledge.spec.ts
```

Expected: `5 passed`。

- [ ] **Step 5: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src/types/knowledge.ts web/src/api/knowledge.ts web/src/api/__tests__/knowledge.spec.ts && git commit -m "feat(web): knowledge API 层 + 类型（TDD）"
```

---

### Task 5: KnowledgeList 页面（TDD）+ 前端全量回归

**Files:**
- Modify: `web/src/views/knowledge/KnowledgeList.vue`（现为占位空页；路由与侧边栏入口已存在，零路由改动）
- Test: `web/src/views/knowledge/__tests__/KnowledgeList.spec.ts`

**Interfaces:**
- Consumes: Task 4 的 API 函数与类型；`useUserStore`（`isAdmin` / `user?.id`）；`PageHeader` / `ContentCard` 公共组件；`formatDateTime`（`@/utils/datetime`）。
- Produces: 用户可见的知识库列表页（搜索/新建/编辑/删除/分页/权限门控）。

- [ ] **Step 1: 写失败测试**

`web/src/views/knowledge/__tests__/KnowledgeList.spec.ts`：

```typescript
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { listDatasets, createDataset, updateDataset, deleteDataset } from '@/api/knowledge'
import type { Dataset } from '@/types/knowledge'
import type { PageResult } from '@/types/app'
import { useUserStore } from '@/stores/user'
import KnowledgeList from '@/views/knowledge/KnowledgeList.vue'

vi.mock('@/api/knowledge', () => ({
  listDatasets: vi.fn(), getDataset: vi.fn(), createDataset: vi.fn(),
  updateDataset: vi.fn(), deleteDataset: vi.fn(),
}))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

function page(list: Dataset[]): PageResult<Dataset> {
  return { list, total: String(list.length), page: '1', size: '20' }
}
const MINE: Dataset = {
  id: '1', name: '客服知识库', description: '售后答疑', ownerId: '7',
  createTime: '2026-07-02T10:00:00+08:00', updateTime: '2026-07-02T10:00:00+08:00',
}
const OTHERS: Dataset = { ...MINE, id: '2', name: '他人知识库', ownerId: '999' }

describe('KnowledgeList', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(listDatasets).mockResolvedValue(page([MINE, OTHERS]))
    const store = useUserStore()
    store.user = { id: '7', username: 'bob', role: 'member' } // 当前用户=bob(7)
  })

  it('挂载拉取并渲染知识库名', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listDatasets).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('客服知识库')
    expect(wrapper.text()).toContain('他人知识库')
  })

  it('canModify 门控：自己的有编辑/删除按钮，他人没有', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="delete-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="delete-2"]').exists()).toBe(false)
  })

  it('admin 对他人知识库也有编辑按钮', async () => {
    const store = useUserStore()
    store.user = { id: '1', username: 'admin', role: 'admin' }
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="edit-2"]').exists()).toBe(true)
  })

  it('搜索回车触发重查且回到第一页', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="search"]').setValue('客服')
    await wrapper.find('[data-test="search"]').trigger('keyup.enter')
    await flushPromises()
    expect(listDatasets).toHaveBeenLastCalledWith({ keyword: '客服', page: 1, size: 20 })
  })

  it('创建：空名不提交', async () => {
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createDataset).not.toHaveBeenCalled()
  })

  it('创建：填名提交调用 createDataset', async () => {
    vi.mocked(createDataset).mockResolvedValue(MINE)
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="create-open"]').trigger('click')
    await wrapper.find('[data-test="form-name"]').setValue('新知识库')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(createDataset).toHaveBeenCalledWith({ name: '新知识库', description: '' })
  })

  it('编辑：回填并调用 updateDataset', async () => {
    vi.mocked(updateDataset).mockResolvedValue(MINE)
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="edit-1"]').trigger('click')
    const nameInput = wrapper.find('[data-test="form-name"]')
    expect((nameInput.element as HTMLInputElement).value).toBe('客服知识库')
    await nameInput.setValue('改名后')
    await wrapper.find('[data-test="form-submit"]').trigger('click')
    await flushPromises()
    expect(updateDataset).toHaveBeenCalledWith('1', { name: '改名后', description: '售后答疑' })
  })

  it('删除：确认后调用 deleteDataset', async () => {
    vi.mocked(deleteDataset).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = mount(KnowledgeList, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('[data-test="delete-1"]').trigger('click')
    await flushPromises()
    expect(deleteDataset).toHaveBeenCalledWith('1')
  })
})
```

- [ ] **Step 2: 跑测试确认失败（红）**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge/__tests__/KnowledgeList.spec.ts
```

Expected: FAIL（占位页无任何 data-test 元素，`listDatasets` 未被调用）。

- [ ] **Step 3: 实现页面**

`web/src/views/knowledge/KnowledgeList.vue`（整文件替换占位内容）：

```vue
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listDatasets, createDataset, updateDataset, deleteDataset } from '@/api/knowledge'
import type { Dataset, DatasetForm } from '@/types/knowledge'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/datetime'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const NAME_MAX = 50
const DESC_MAX = 200

const userStore = useUserStore()

const datasets = ref<Dataset[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 团队共享制：仅 owner 或 Admin 可改/删（与后端 10004 双保险）。 */
function canModify(dataset: Dataset): boolean {
  return userStore.isAdmin || dataset.ownerId === userStore.user?.id
}

async function load() {
  loading.value = true
  try {
    const res = await listDatasets({
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
    datasets.value = res.list
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

async function onDelete(row: Dataset) {
  try {
    await ElMessageBox.confirm(`确定删除知识库「${row.name}」？此操作不可恢复。`, '删除确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteDataset(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 已由 request 拦截器统一 toast */
  }
}

// —— 创建 / 编辑弹窗（共用）——
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<DatasetForm>({ name: '', description: '' })

const rules: FormRules<DatasetForm> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: NAME_MAX, message: `名称不超过 ${NAME_MAX} 个字符`, trigger: 'blur' },
  ],
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}
function openEdit(row: Dataset) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 兜底：happy-dom 下 el-form.validate 对空必填会误判通过（同 UserList/ProviderList/AppList）。
  if (!form.name || form.name.length > NAME_MAX) return
  if (form.description.length > DESC_MAX) return
  try {
    if (editingId.value === null) {
      await createDataset({ ...form })
      ElMessage.success('知识库已创建')
    } else {
      await updateDataset(editingId.value, { ...form })
      ElMessage.success('知识库已更新')
    }
    dialogVisible.value = false
    await load()
  } catch {
    /* 失败（如重名）由 request 拦截器统一 toast；弹窗保持打开 */
  }
}
</script>

<template>
  <div class="knowledge-list">
    <PageHeader title="知识库" description="团队共享：全员可见，编辑/删除仅创建者与管理员">
      <el-input
        v-model="keyword"
        data-test="search"
        placeholder="搜索知识库名"
        clearable
        class="knowledge-list__search"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <el-button type="primary" data-test="create-open" @click="openCreate">新建知识库</el-button>
    </PageHeader>

    <ContentCard>
      <el-table v-loading="loading" :data="datasets" data-test="dataset-table">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="归属">
          <template #default="{ row }">
            <el-tag :type="(row as Dataset).ownerId === userStore.user?.id ? 'success' : 'info'">
              {{ (row as Dataset).ownerId === userStore.user?.id ? '我创建' : '其他成员' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间">
          <template #default="{ row }">{{ formatDateTime((row as Dataset).createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="canModify(row as Dataset)">
              <el-button
                :data-test="`edit-${(row as Dataset).id}`"
                size="small"
                @click="openEdit(row as Dataset)"
                >编辑</el-button
              >
              <el-button
                :data-test="`delete-${(row as Dataset).id}`"
                size="small"
                type="danger"
                @click="onDelete(row as Dataset)"
                >删除</el-button
              >
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="knowledge-list__pager"
        layout="prev, pager, next, total"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </ContentCard>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建知识库' : '编辑知识库'"
      width="520"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="70px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" data-test="form-name" maxlength="50" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            data-test="form-desc"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="这个知识库装什么内容（可选）"
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
.knowledge-list__search {
  width: 220px;
}
.knowledge-list__pager {
  margin-top: $spacing-md;
  justify-content: flex-end;
}
</style>
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge/__tests__/KnowledgeList.spec.ts
```

Expected: `8 passed`。

- [ ] **Step 5: 前端全量回归 + 类型检查**

```bash
cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck
```

Expected: 全部测试通过（既有 + 新增 13 个），`vue-tsc` 零错误。

- [ ] **Step 6: Commit**

```bash
cd /home/wang/playlab/hify && git add web/src/views/knowledge && git commit -m "feat(web): 知识库列表页（搜索/建改删/权限门控，TDD）"
```

---

### Task 6: 自检 + 手动验收

**Files:**
- Modify: `docs/self-check.md`（文末追加本轮条目）

- [ ] **Step 1: 追加自检**

在 `docs/self-check.md` 文末追加（日期用当天）：

```markdown
## 2026-07-02 knowledge K1 知识库管理（dataset CRUD）

- [x] V13 迁移照 V7 约定（identity 主键 / text+check / 软删 / timestamptz / 部分唯一索引）
- [x] 路由 /api/v1/knowledge/datasets 逐条核对 api-standards（成员族带模块段、复数资源、PUT 全量、无 PATCH、软删幂等）
- [x] 错误码零新增，全部复用 CommonError；DTO 不 import entity（ArchUnit 绿）
- [x] 后端 mvn test 全绿（无 -q）；前端 pnpm test + typecheck 全绿
- [x] 手动验收：建库 → 重名 409 → 改名 → 他人账号禁用态 → 删除 → 同名重建
```

- [ ] **Step 2: 手动验收（用户执行或结对）**

启动后端（`server/` 下）与前端 dev（`web/` 下 `pnpm dev`），按上面清单走一遍。重点看：重名创建 toast「知识库名已存在」；用 admin 账号（本地库已有）看他人知识库出现编辑/删除按钮。

- [ ] **Step 3: Commit**

```bash
cd /home/wang/playlab/hify && git add docs/self-check.md && git commit -m "docs: knowledge K1 自检"
```
