# ai_model 模型管理后端（Provider B 轮）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 provider 模块内新增 `ai_model`（供应商下的具体模型）CRUD + 启停，拦截 Anthropic 下建 embedding，并收紧 `ProviderService.delete` 为"有模型则拒删"。

**Architecture:** provider 模块内扩展，照 R1 的 controller→service→mapper→entity 分层。`AiModelService` 与 `ProviderService` 同模块、互读 mapper（合规）。混合路由：列表/创建挂供应商下、单条操作走顶级。纯后端，前端留后续轮次。

**Tech Stack:** Spring Boot 3.5 + MyBatis-Plus + PostgreSQL(Flyway) + JUnit5/Mockito。

## Global Constraints

- provider 段错误码：本轮**只新增 `12001`**（`EMBEDDING_NOT_SUPPORTED`/400）；其余复用 common（不存在 10005、冲突 10006、校验 10001）。12002 留 C 轮（doc 已示例熔断）。
- 路由 admin 族 `/api/v1/admin/provider/**`，admin 由 SecurityConfig `hasRole("ADMIN")` 拦截。不用 PATCH；启停用动作子资源 POST。
- 实体继承 `com.hify.common.BaseEntity`；DB 枚举用 `text + check`、布尔 `boolean`、时间 `timestamptz`、主键 `bigint generated always as identity`。
- Long → string（infra Jackson 全局）；时间 `OffsetDateTime`。
- DTO 禁 import entity；entity→DTO 投影写 service 私有方法。
- service 具体类无接口；`@Transactional` 仅 service 写方法、内无 LLM/外部 IO；Controller 不注 mapper/不写事务/不写业务分支。
- `provider_id`/`type` 创建定死、更新不可改；`update` 只改 name+modelKey；status 走启停。
- 测试 mock mapper，不连库；TDD 先红后绿；频繁提交。
- 每完成 Task 追加自检到 `docs/self-check.md` 再提交。
- 命令在 `server/` 执行：`cd /home/wang/playlab/hify/server`。

---

### Task 1: ai_model 数据层（迁移 + 枚举 + 实体 + Mapper）

**Files:**
- Create: `server/src/main/resources/db/migration/V6__create_ai_model.sql`
- Create: `server/src/main/java/com/hify/provider/constant/ModelType.java`
- Create: `server/src/main/java/com/hify/provider/constant/ProviderError.java`
- Create: `server/src/main/java/com/hify/provider/entity/AiModel.java`
- Create: `server/src/main/java/com/hify/provider/mapper/AiModelMapper.java`

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`、`com.hify.common.exception.ErrorCode`。
- Produces:
  - `AiModel`：字段 `providerId(Long)/type/name/modelKey/status(String)` + BaseEntity 四列。
  - `AiModelMapper extends BaseMapper<AiModel>`。
  - `ModelType.CHAT/EMBEDDING`，`value()` 返回 `chat`/`embedding`。
  - `ProviderError.EMBEDDING_NOT_SUPPORTED`（12001/400）。

- [ ] **Step 1: 写 Flyway 迁移**

`server/src/main/resources/db/migration/V6__create_ai_model.sql`
```sql
-- V6：具体模型表（provider 模块）。挂在 model_provider 下，区分 chat/embedding。
-- 建表模板见 V4/V5：text+check 枚举、boolean、timestamptz、bigint identity、公共四列由 BaseEntity 承载。

create table ai_model (
    id          bigint      generated always as identity primary key,
    provider_id bigint      not null references model_provider(id),
    type        text        not null check (type in ('chat', 'embedding')),
    name        text        not null check (char_length(name) <= 50),
    model_key   text        not null check (char_length(model_key) <= 100),
    status      text        not null default 'enabled' check (status in ('enabled', 'disabled')),
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table ai_model is '具体模型（provider 模块）：挂在 model_provider 下，区分 chat/embedding；model_key 为传给 LLM 的模型标识';

-- 同一供应商下同一模型标识不重复（配合 @TableLogic：软删后可同标识重建）。
create unique index ai_model_provider_key_uq on ai_model (provider_id, model_key) where deleted = false;
```

- [ ] **Step 2: 写两个枚举**

`server/src/main/java/com/hify/provider/constant/ModelType.java`
```java
package com.hify.provider.constant;

/** 模型用途。存库为小写字符串（与 ai_model.type 的 check 约束一致）。 */
public enum ModelType {

    CHAT("chat"),
    EMBEDDING("embedding");

    private final String value;

    ModelType(String value) {
        this.value = value;
    }

    /** 入库/比较用的字符串值。 */
    public String value() {
        return value;
    }
}
```

`server/src/main/java/com/hify/provider/constant/ProviderError.java`
```java
package com.hify.provider.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * provider 模块特有错误码（12xxx 段，api-standards.md 第 5 节）。
 * 模块段只放该模块特有的业务语义；通用语义（不存在/冲突/校验）一律复用 CommonError。
 */
public enum ProviderError implements ErrorCode {

    /** Anthropic 协议无 embedding 能力，禁止在其下建 embedding 模型。 */
    EMBEDDING_NOT_SUPPORTED(12001, HttpStatus.BAD_REQUEST, "该协议不支持 embedding 模型");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    ProviderError(int code, HttpStatus status, String defaultMessage) {
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

- [ ] **Step 3: 写实体与 Mapper**

`server/src/main/java/com/hify/provider/entity/AiModel.java`
```java
package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 具体模型表 {@code ai_model} 的映射实体。继承 {@link BaseEntity}，自动带
 * id / create_time / update_time / deleted 四列。挂在 model_provider 下（provider_id）。
 * type / status 存小写字符串（见 ai_model 的 check 约束 / ModelType / ProviderStatus）。
 */
@TableName("ai_model")
public class AiModel extends BaseEntity {

    private Long providerId;
    private String type;     // chat / embedding
    private String name;     // 显示名
    private String modelKey; // API 模型标识（传给 LLM，如 gpt-4o）
    private String status;   // enabled / disabled

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelKey() {
        return modelKey;
    }

    public void setModelKey(String modelKey) {
        this.modelKey = modelKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

`server/src/main/java/com/hify/provider/mapper/AiModelMapper.java`
```java
package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.AiModel;

/**
 * {@link AiModel} 的数据访问接口。继承 {@code BaseMapper} 即获得增删改查能力。
 * 由 {@code @MapperScan("com.hify.**.mapper")} 自动扫描注册，只允许被本模块 service 注入。
 */
public interface AiModelMapper extends BaseMapper<AiModel> {
}
```

- [ ] **Step 4: 编译 + 边界测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ModularityTests test`
Expected: BUILD SUCCESS（新增包不破坏模块边界）。若类名不同，改跑 `mvn -q test-compile` 应成功。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加：B-Task1 数据层完成，V6 建表（FK→model_provider、(provider_id,model_key) 部分唯一）、ModelType/ProviderError(12001)、AiModel、AiModelMapper 就位。
```bash
cd /home/wang/playlab/hify
git add server/src/main/resources/db/migration/V6__create_ai_model.sql \
        server/src/main/java/com/hify/provider/constant/ModelType.java \
        server/src/main/java/com/hify/provider/constant/ProviderError.java \
        server/src/main/java/com/hify/provider/entity/AiModel.java \
        server/src/main/java/com/hify/provider/mapper/AiModelMapper.java docs/self-check.md
git commit -m "feat(provider)：ai_model 建表迁移 + 实体/枚举/Mapper（B 轮）"
```

---

### Task 2: AiModelService + DTO（业务逻辑，TDD）

**Files:**
- Create: `server/src/main/java/com/hify/provider/dto/CreateModelRequest.java`
- Create: `server/src/main/java/com/hify/provider/dto/UpdateModelRequest.java`
- Create: `server/src/main/java/com/hify/provider/dto/ModelResponse.java`
- Create: `server/src/main/java/com/hify/provider/service/AiModelService.java`
- Test: `server/src/test/java/com/hify/provider/service/AiModelServiceTest.java`

**Interfaces:**
- Consumes: `AiModelMapper`、`ModelProviderMapper`、`ModelType`、`ProviderError`、`ProviderStatus`、`CommonError`、`ModelProvider`。
- Produces（供 Task 3 Controller）：
  - `AiModelService.create(Long providerId, CreateModelRequest) -> ModelResponse`
  - `AiModelService.update(Long id, UpdateModelRequest) -> ModelResponse`
  - `AiModelService.listByProvider(Long providerId) -> List<ModelResponse>`
  - `AiModelService.delete(Long id) -> void`
  - `AiModelService.enable(Long id) -> void`
  - `AiModelService.disable(Long id) -> void`
  - `CreateModelRequest(type, name, modelKey)`、`UpdateModelRequest(name, modelKey)`、`ModelResponse(id, providerId, type, name, modelKey, status, createTime)`

- [ ] **Step 1: 写 DTO**

`server/src/main/java/com/hify/provider/dto/CreateModelRequest.java`
```java
package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建模型请求。providerId 来自路径，不在请求体。
 * type 限 chat|embedding；name ≤50；modelKey（API 模型标识）≤100。
 */
public record CreateModelRequest(
        @NotBlank @Pattern(regexp = "chat|embedding", message = "type 仅支持 chat|embedding") String type,
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 100) String modelKey) {
}
```

`server/src/main/java/com/hify/provider/dto/UpdateModelRequest.java`
```java
package com.hify.provider.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新模型请求。仅改 name + modelKey；type/providerId 创建后不可改，不在请求体。
 */
public record UpdateModelRequest(
        @NotBlank @Size(max = 50) String name,
        @NotBlank @Size(max = 100) String modelKey) {
}
```

`server/src/main/java/com/hify/provider/dto/ModelResponse.java`
```java
package com.hify.provider.dto;

import java.time.OffsetDateTime;

/**
 * 模型出参。id/providerId 为 Long，经 Jackson 全局序列化为字符串；createTime 为 ISO-8601 带时区。
 * modelKey 非敏感，完整返回。本 record 不依赖 entity，投影在 AiModelService 完成。
 */
public record ModelResponse(
        Long id,
        Long providerId,
        String type,
        String name,
        String modelKey,
        String status,
        OffsetDateTime createTime) {
}
```

- [ ] **Step 2: 写失败测试**

`server/src/test/java/com/hify/provider/service/AiModelServiceTest.java`
```java
package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiModelService 单元测试：mock AiModelMapper + ModelProviderMapper，不连库。
 * 覆盖创建（含 embedding 协议守卫、重名、provider 不存在）/更新/启停幂等/删除/列表。
 */
class AiModelServiceTest {

    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private AiModelService service;

    @BeforeEach
    void setUp() {
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        service = new AiModelService(modelMapper, providerMapper);
    }

    private ModelProvider provider(long id, String protocol) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setProtocol(protocol);
        return p;
    }

    private AiModel model(long id, long providerId, String type, String status) {
        AiModel m = new AiModel();
        m.setId(id);
        m.setProviderId(providerId);
        m.setType(type);
        m.setName("旧名");
        m.setModelKey("old-key");
        m.setStatus(status);
        m.setCreateTime(OffsetDateTime.now());
        return m;
    }

    @Test
    void 创建chat_openai下_写库且状态默认启用() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        ModelResponse resp = service.create(1L, new CreateModelRequest("chat", "GPT-4o", "gpt-4o"));

        verify(modelMapper).insert(captor.capture());
        AiModel saved = captor.getValue();
        assertEquals(1L, saved.getProviderId());
        assertEquals("chat", saved.getType());
        assertEquals("gpt-4o", saved.getModelKey());
        assertEquals(ProviderStatus.ENABLED.value(), saved.getStatus());
        assertEquals("GPT-4o", resp.name());
    }

    @Test
    void 创建embedding_openai下_成功() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);

        ModelResponse resp = service.create(1L,
                new CreateModelRequest("embedding", "向量", "text-embedding-3-small"));

        assertEquals("embedding", resp.type());
    }

    @Test
    void 创建embedding_anthropic下_抛12001() {
        when(providerMapper.selectById(2L)).thenReturn(provider(2L, "anthropic"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(2L, new CreateModelRequest("embedding", "向量", "x")));
        assertEquals(ProviderError.EMBEDDING_NOT_SUPPORTED, ex.errorCode());
        verify(modelMapper, never()).insert(any());
    }

    @Test
    void 创建chat_anthropic下_成功() {
        when(providerMapper.selectById(2L)).thenReturn(provider(2L, "anthropic"));
        when(modelMapper.selectCount(any())).thenReturn(0L);

        ModelResponse resp = service.create(2L,
                new CreateModelRequest("chat", "Claude", "claude-sonnet-4-6"));

        assertEquals("chat", resp.type());
    }

    @Test
    void 创建_provider不存在_抛NOT_FOUND() {
        when(providerMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(99L, new CreateModelRequest("chat", "x", "x")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 创建_同供应商下modelKey重复_抛CONFLICT() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(1L, new CreateModelRequest("chat", "x", "gpt-4o")));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 创建_并发命中唯一索引_转CONFLICT() {
        when(providerMapper.selectById(1L)).thenReturn(provider(1L, "openai"));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        when(modelMapper.insert(any(AiModel.class))).thenThrow(new DuplicateKeyException("dup"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(1L, new CreateModelRequest("chat", "x", "gpt-4o")));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
    }

    @Test
    void 更新_改名与标识_成功() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        service.update(5L, new UpdateModelRequest("新名", "gpt-4o-mini"));

        verify(modelMapper).updateById(captor.capture());
        assertEquals("新名", captor.getValue().getName());
        assertEquals("gpt-4o-mini", captor.getValue().getModelKey());
    }

    @Test
    void 更新_不存在_抛NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(99L, new UpdateModelRequest("x", "x")));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 启用_已启用_幂等不写库() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));

        service.enable(5L);

        verify(modelMapper, never()).updateById(any());
    }

    @Test
    void 禁用_启用态_写库为disabled() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, 1L, "chat", ProviderStatus.ENABLED.value()));
        ArgumentCaptor<AiModel> captor = ArgumentCaptor.forClass(AiModel.class);

        service.disable(5L);

        verify(modelMapper).updateById(captor.capture());
        assertEquals(ProviderStatus.DISABLED.value(), captor.getValue().getStatus());
    }

    @Test
    void 启停_不存在_抛NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.enable(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void 删除_走软删() {
        service.delete(5L);
        verify(modelMapper).deleteById(5L);
    }

    @Test
    void 按供应商列表_投影() {
        when(modelMapper.selectList(any())).thenReturn(List.of(
                model(1L, 1L, "chat", ProviderStatus.ENABLED.value()),
                model(2L, 1L, "embedding", ProviderStatus.DISABLED.value())));

        List<ModelResponse> list = service.listByProvider(1L);

        assertEquals(2, list.size());
        assertEquals("chat", list.get(0).type());
        assertEquals(ProviderStatus.DISABLED.value(), list.get(1).status());
    }
}
```

- [ ] **Step 3: 跑测试，确认红**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AiModelServiceTest test`
Expected: 编译失败（`AiModelService` 不存在）。

- [ ] **Step 4: 实现 AiModelService**

`server/src/main/java/com/hify/provider/service/AiModelService.java`
```java
package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 具体模型业务逻辑（具体类 + @Service）。注入本模块 AiModelMapper 与 ModelProviderMapper
 * （读 provider 的 protocol 做 embedding 守卫，同模块内调用合规）。@Transactional 只在写方法。
 */
@Service
public class AiModelService {

    private static final String PROTOCOL_ANTHROPIC = "anthropic";

    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;

    public AiModelService(AiModelMapper modelMapper, ModelProviderMapper providerMapper) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
    }

    @Transactional
    public ModelResponse create(Long providerId, CreateModelRequest request) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        // 协议守卫：Anthropic 无 embedding 能力
        if (PROTOCOL_ANTHROPIC.equals(provider.getProtocol())
                && ModelType.EMBEDDING.value().equals(request.type())) {
            throw new BizException(ProviderError.EMBEDDING_NOT_SUPPORTED);
        }
        assertKeyAvailable(providerId, request.modelKey(), null);
        AiModel entity = new AiModel();
        entity.setProviderId(providerId);
        entity.setType(request.type());
        entity.setName(request.name());
        entity.setModelKey(request.modelKey());
        entity.setStatus(ProviderStatus.ENABLED.value());
        try {
            modelMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识", e);
        }
        return toResponse(entity);
    }

    @Transactional
    public ModelResponse update(Long id, UpdateModelRequest request) {
        AiModel entity = require(id);
        assertKeyAvailable(entity.getProviderId(), request.modelKey(), id);
        entity.setName(request.name());
        entity.setModelKey(request.modelKey());
        try {
            modelMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识", e);
        }
        return toResponse(entity);
    }

    /** 列某供应商下的模型（@TableLogic 自动加 where deleted=false），按创建时间倒序。 */
    public List<ModelResponse> listByProvider(Long providerId) {
        List<AiModel> rows = modelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getProviderId, providerId)
                        .orderByDesc(AiModel::getCreateTime));
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional
    public void enable(Long id) {
        AiModel entity = require(id);
        if (ProviderStatus.ENABLED.value().equals(entity.getStatus())) {
            return; // 幂等
        }
        entity.setStatus(ProviderStatus.ENABLED.value());
        modelMapper.updateById(entity);
    }

    @Transactional
    public void disable(Long id) {
        AiModel entity = require(id);
        if (ProviderStatus.DISABLED.value().equals(entity.getStatus())) {
            return; // 幂等
        }
        entity.setStatus(ProviderStatus.DISABLED.value());
        modelMapper.updateById(entity);
    }

    /** 逻辑删除：删不存在的也算成功（幂等）。 */
    @Transactional
    public void delete(Long id) {
        modelMapper.deleteById(id);
    }

    private AiModel require(Long id) {
        AiModel entity = modelMapper.selectById(id);
        if (entity == null) {
            throw new BizException(CommonError.NOT_FOUND, "模型不存在");
        }
        return entity;
    }

    /** 同一供应商下 model_key 唯一；excludeId 非 null 时排除自身（更新场景）。 */
    private void assertKeyAvailable(Long providerId, String modelKey, Long excludeId) {
        LambdaQueryWrapper<AiModel> q = new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getModelKey, modelKey);
        if (excludeId != null) {
            q.ne(AiModel::getId, excludeId);
        }
        if (modelMapper.selectCount(q) > 0) {
            throw new BizException(CommonError.CONFLICT, "该供应商下已存在同名模型标识");
        }
    }

    private ModelResponse toResponse(AiModel m) {
        return new ModelResponse(
                m.getId(), m.getProviderId(), m.getType(), m.getName(),
                m.getModelKey(), m.getStatus(), m.getCreateTime());
    }
}
```

- [ ] **Step 5: 跑测试，确认绿**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AiModelServiceTest test`
Expected: BUILD SUCCESS（14 个用例全绿；退出码 0）。

- [ ] **Step 6: 追加自检并提交**

向 `docs/self-check.md` 追加：B-Task2 AiModelService 完成，CRUD + 启停 + embedding 协议守卫(12001) + 重名/不存在/幂等，14 测全绿。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/dto/CreateModelRequest.java \
        server/src/main/java/com/hify/provider/dto/UpdateModelRequest.java \
        server/src/main/java/com/hify/provider/dto/ModelResponse.java \
        server/src/main/java/com/hify/provider/service/AiModelService.java \
        server/src/test/java/com/hify/provider/service/AiModelServiceTest.java docs/self-check.md
git commit -m "feat(provider)：AiModelService CRUD + 启停 + embedding 协议守卫（B 轮）"
```

---

### Task 3: AdminModelController（REST 接口，Web 层测试）

**Files:**
- Create: `server/src/main/java/com/hify/provider/controller/AdminModelController.java`
- Test: `server/src/test/java/com/hify/provider/controller/AdminModelControllerTest.java`

**Interfaces:**
- Consumes: `AiModelService`（Task 2）的 6 个方法。
- Produces: 6 端点（混合路由，见 Global Constraints / spec §2）。

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/provider/controller/AdminModelControllerTest.java`
```java
package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.service.AiModelService;
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

@WebMvcTest(AdminModelController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminModelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AiModelService aiModelService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private ModelResponse sample() {
        return new ModelResponse(7L, 1L, "chat", "GPT-4o", "gpt-4o", "enabled", OffsetDateTime.now());
    }

    @Test
    void 列某供应商模型_admin_200且id字符串() throws Exception {
        when(aiModelService.listByProvider(1L)).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("7"))
                .andExpect(jsonPath("$.data[0].providerId").value("1"))
                .andExpect(jsonPath("$.data[0].modelKey").value("gpt-4o"));
    }

    @Test
    void 创建模型_admin_200() throws Exception {
        when(aiModelService.create(eq(1L), any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"type\":\"chat\",\"name\":\"GPT-4o\",\"modelKey\":\"gpt-4o\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("chat"));
    }

    @Test
    void 更新模型_admin_200() throws Exception {
        when(aiModelService.update(eq(7L), any())).thenReturn(sample());

        mockMvc.perform(put("/api/v1/admin/provider/models/7")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"name\":\"GPT-4o 改\",\"modelKey\":\"gpt-4o\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 删除模型_admin_200且data不存在() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/provider/models/7")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 启用模型_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/models/7/enable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 禁用模型_admin_200() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/models/7/disable")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    @Test
    void 创建模型_member_403且10004() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType("application/json")
                        .content("{\"type\":\"chat\",\"name\":\"x\",\"modelKey\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void 创建模型_type非法_400且10001() throws Exception {
        mockMvc.perform(post("/api/v1/admin/provider/providers/1/models")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json")
                        .content("{\"type\":\"image\",\"name\":\"x\",\"modelKey\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data").isArray());
    }
}
```

- [ ] **Step 2: 跑测试，确认红**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AdminModelControllerTest test`
Expected: 编译失败（`AdminModelController` 不存在）。

- [ ] **Step 3: 实现 Controller**

`server/src/main/java/com/hify/provider/controller/AdminModelController.java`
```java
package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.dto.CreateModelRequest;
import com.hify.provider.dto.ModelResponse;
import com.hify.provider.dto.UpdateModelRequest;
import com.hify.provider.service.AiModelService;
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
 * admin 模型管理接口（仅 Admin）。混合路由：列表/创建挂供应商下、单条操作走顶级
 * （api-standards "子资源有了自己的 id 就升为顶级"）。类前缀取两族公共段 /api/v1/admin/provider。
 * 协议层：@Valid + 调 service + 包 Result；无业务/无 try-catch/无 @Transactional/不注入 Mapper。
 */
@RestController
@RequestMapping("/api/v1/admin/provider")
public class AdminModelController {

    private final AiModelService aiModelService;

    public AdminModelController(AiModelService aiModelService) {
        this.aiModelService = aiModelService;
    }

    @GetMapping("/providers/{providerId}/models")
    public Result<List<ModelResponse>> list(@PathVariable Long providerId) {
        return Result.ok(aiModelService.listByProvider(providerId));
    }

    @PostMapping("/providers/{providerId}/models")
    public Result<ModelResponse> create(@PathVariable Long providerId,
                                        @Valid @RequestBody CreateModelRequest request) {
        return Result.ok(aiModelService.create(providerId, request));
    }

    @PutMapping("/models/{id}")
    public Result<ModelResponse> update(@PathVariable Long id,
                                        @Valid @RequestBody UpdateModelRequest request) {
        return Result.ok(aiModelService.update(id, request));
    }

    @DeleteMapping("/models/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        aiModelService.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/models/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        aiModelService.enable(id);
        return Result.ok(null);
    }

    @PostMapping("/models/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        aiModelService.disable(id);
        return Result.ok(null);
    }
}
```

- [ ] **Step 4: 跑测试，确认绿**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=AdminModelControllerTest test`
Expected: BUILD SUCCESS（8 个用例全绿）。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加：B-Task3 AdminModelController 完成，6 端点混合路由通、admin/member/@Valid 校验，8 测全绿。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/controller/AdminModelController.java \
        server/src/test/java/com/hify/provider/controller/AdminModelControllerTest.java docs/self-check.md
git commit -m "feat(provider)：AdminModelController 6 端点（混合路由，B 轮）"
```

---

### Task 4: ProviderService.delete 守卫（收紧：有模型则拒删）

**Files:**
- Modify: `server/src/main/java/com/hify/provider/service/ProviderService.java`
- Test: `server/src/test/java/com/hify/provider/service/ProviderServiceTest.java`

**Interfaces:**
- Consumes: `AiModelMapper`（Task 1）。
- Produces: `ProviderService(ModelProviderMapper, AiModelMapper, ApiKeyCipher)`（**构造器新增一参**）；`delete(Long)` 行为收紧。

- [ ] **Step 1: 改测试（构造器 + 新增守卫用例）**

在 `server/src/test/java/com/hify/provider/service/ProviderServiceTest.java`：

(1) 顶部补两个 import：
```java
import com.hify.provider.mapper.AiModelMapper;
import static org.mockito.ArgumentMatchers.anyLong;
```

(2) 类内字段补一个，并整体替换 `setUp()`：
```java
    private ModelProviderMapper mapper;
    private AiModelMapper aiModelMapper;
    private ApiKeyCipher cipher;
    private ProviderService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ModelProviderMapper.class);
        aiModelMapper = mock(AiModelMapper.class);
        when(aiModelMapper.selectCount(any())).thenReturn(0L); // 默认无模型，既有删除测试照常通过
        ProviderCryptoProperties props = new ProviderCryptoProperties();
        props.setMasterKey("unit-test-master-key");
        cipher = new ApiKeyCipher(props);
        service = new ProviderService(mapper, aiModelMapper, cipher);
    }
```

(3) 末尾追加一个守卫用例：
```java
    @Test
    void 删除_供应商下有模型_抛CONFLICT_且不删() {
        when(aiModelMapper.selectCount(any())).thenReturn(2L);

        BizException ex = assertThrows(BizException.class, () -> service.delete(5L));
        assertEquals(CommonError.CONFLICT, ex.errorCode());
        verify(mapper, never()).deleteById(anyLong());
    }
```

- [ ] **Step 2: 跑测试，确认红**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ProviderServiceTest test`
Expected: 编译失败（`ProviderService` 构造器仍是 2 参，与测试的 3 参不符）。

- [ ] **Step 3: 改 ProviderService（构造器注入 + delete 守卫）**

在 `server/src/main/java/com/hify/provider/service/ProviderService.java`：

(1) 顶部 import 区补两行：
```java
import com.hify.provider.entity.AiModel;
import com.hify.provider.mapper.AiModelMapper;
```

(2) 字段与构造器，将
```java
    private final ModelProviderMapper providerMapper;
    private final ApiKeyCipher apiKeyCipher;

    public ProviderService(ModelProviderMapper providerMapper, ApiKeyCipher apiKeyCipher) {
        this.providerMapper = providerMapper;
        this.apiKeyCipher = apiKeyCipher;
    }
```
替换为
```java
    private final ModelProviderMapper providerMapper;
    private final AiModelMapper aiModelMapper;
    private final ApiKeyCipher apiKeyCipher;

    public ProviderService(ModelProviderMapper providerMapper, AiModelMapper aiModelMapper,
                           ApiKeyCipher apiKeyCipher) {
        this.providerMapper = providerMapper;
        this.aiModelMapper = aiModelMapper;
        this.apiKeyCipher = apiKeyCipher;
    }
```

(3) 将 `delete` 方法
```java
    /** 逻辑删除：@TableLogic 把 delete 变成 update set deleted=true；删不存在的也返回成功（幂等）。 */
    @Transactional
    public void delete(Long id) {
        providerMapper.deleteById(id);
    }
```
替换为
```java
    /**
     * 逻辑删除供应商。收紧（B 轮）：其下尚有未删模型时拒绝，防悬空引用（CONFLICT）。
     * 无模型时走 @TableLogic 软删；删不存在的也算成功（幂等）。
     */
    @Transactional
    public void delete(Long id) {
        long models = aiModelMapper.selectCount(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getProviderId, id));
        if (models > 0) {
            throw new BizException(CommonError.CONFLICT, "请先删除该供应商下的模型");
        }
        providerMapper.deleteById(id);
    }
```

- [ ] **Step 4: 跑测试，确认绿**

Run: `cd /home/wang/playlab/hify/server && mvn -q -Dtest=ProviderServiceTest test`
Expected: BUILD SUCCESS（原有 12 + 新增 1 = 13 个用例全绿）。

- [ ] **Step 5: 追加自检并提交**

向 `docs/self-check.md` 追加：B-Task4 ProviderService.delete 收紧为"有模型则拒删(10006)"，注入 AiModelMapper，13 测全绿。
```bash
cd /home/wang/playlab/hify
git add server/src/main/java/com/hify/provider/service/ProviderService.java \
        server/src/test/java/com/hify/provider/service/ProviderServiceTest.java docs/self-check.md
git commit -m "feat(provider)：删供应商拦有模型（ProviderService.delete 收紧，B 轮）"
```

---

### Task 5: 全量回归 + 边界校验

**Files:** 无（验证收尾）。

- [ ] **Step 1: 跑全量测试**

Run: `cd /home/wang/playlab/hify/server && mvn -q test`
Expected: BUILD SUCCESS；退出码 0。含既有 + B 轮新增（AiModelService 14、AdminModelController 8、ProviderService +1）+ ModularityTests/ArchUnit。

- [ ] **Step 2: 确认模块边界绿**

确认无 Spring Modulith / ArchUnit 违规（provider 模块内扩展，无跨模块依赖、DTO 不 import entity）。若违规按报告修正后重跑 Step 1。

- [ ] **Step 3: 追加自检并提交（如有改动）**

向 `docs/self-check.md` 追加：B-Task5 全量回归通过，模块边界无违规，ai_model 模型管理后端（B 轮）完成。
```bash
cd /home/wang/playlab/hify
git add -A && git commit -m "test(provider)：B 轮全量回归通过，ai_model 模型管理后端完成" || echo "无改动，跳过提交"
```

---

## 完成标准（Definition of Done）

- `mvn test` 全绿；B 轮新增约 23 个用例（service 14 + controller 8 + provider 守卫 1）通过。
- 6 个模型端点按 api-standards 返回 Result；`12001` 拦 Anthropic+embedding；删 provider 有模型时 `10006`。
- `ai_model` 表经 V6 迁移建立；模块边界无违规。

## 后续（不在本计划）

- 实现完成后为模型 6 端点扩充 Postman collection（新增「模型管理」目录，复用 R1 的登录取 token + providerId 链路）。
- 前端模型管理界面、C 轮 ProviderFacade 消费模型，见 spec §6。
