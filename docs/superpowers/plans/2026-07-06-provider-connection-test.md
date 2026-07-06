# 供应商试连接 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 供应商列表页可一键试连接并展示最近一次测试结果（落库），详情页每个模型（chat + embedding）可即时测试连通。

**Architecture:** 后端在 `model_provider` 加 3 个「最近一次测试」字段（V17），`ModelConnectionService` 扩展 embedding 分流并新增 `testProvider`（挑启用模型→真实调用→成败都落库），`AdminProviderController` 加动作子资源 `POST /{id}/test`。前端接入两个测试端点（带 130s 专用超时），列表页加「连接」三态列 + 「试连接」按钮，详情页模型行加「测试」按钮。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + Flyway + JUnit5/Mockito；Vue 3 + Element Plus + vitest。

**Spec:** `docs/superpowers/specs/2026-07-06-provider-connection-test-design.md`

## Global Constraints

- 已发布契约只增不改：`ModelTestResponse(sample)` 结构不变；`ProviderResponse` 只在**末尾追加**字段。
- 不发明新错误码：复用 10005（NOT_FOUND）、12002（MODEL_NOT_USABLE，可自定义 message）。
- `ModelConnectionService` 不加 `@Transactional`（真实外部 IO，llm-resilience.md §1）。
- 模型级测试**不回写**供应商状态；「无启用模型」时**不落库**。
- 后端测试判定看 **exit code**，不要 grep "BUILD SUCCESS"（`mvn -q` 会静音输出）。
- 前端遵循既有模式：API 失败由 request 拦截器统一 toast，调用方空 catch；`data-test` 属性命名 `试连接=test-{id}`、`模型测试=model-test-{id}`。
- 提交信息用中文，格式 `feat(provider): ...` / `feat(web): ...`，与 git log 风格一致。

## 关键坑（实现前必读）

1. **MyBatis-Plus `updateById` 忽略 null 字段**——成功时要把 `last_test_error` 清为 NULL，必须用 `LambdaUpdateWrapper.set(..., null)` 显式写 NULL，不能 `entity.setLastTestError(null); updateById(entity)`。
2. **单测里用 Lambda Wrapper 需要 TableInfo**：测试 `setUp` 里先 `TableInfoHelper.initTableInfo(..., ModelProvider.class)` 和 `AiModel.class`（模仿 `EmbeddingSettingServiceTest:43-45`）。
3. **`ChatClient` 链式调用**（`.prompt().user().call().content()`）单测用 `mock(ChatClient.class, RETURNS_DEEP_STUBS)`。
4. **前端 `Provider` 接口加必填字段后**，所有构造 `Provider` 字面量的测试（`ProviderList.spec.ts` 的 SAMPLE、`ProviderDetail.spec.ts` 的 provider 桩）会 typecheck 报错，必须同步补字段。
5. `ProviderResponse` 是 record，加字段后所有 `new ProviderResponse(...)` 调用点要同步：`ProviderService.toResponse`、`AdminProviderControllerTest:57`。

---

### Task 1: V17 迁移 + 实体/DTO/投影加「最近一次测试」字段

**Files:**
- Create: `server/src/main/resources/db/migration/V17__alter_model_provider_last_test.sql`
- Modify: `server/src/main/java/com/hify/provider/entity/ModelProvider.java`
- Modify: `server/src/main/java/com/hify/provider/dto/ProviderResponse.java`
- Modify: `server/src/main/java/com/hify/provider/service/ProviderService.java:152-156`（toResponse）
- Modify: `server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java:57`（补构造参数）
- Test: `server/src/test/java/com/hify/provider/service/ProviderServiceTest.java`

**Interfaces:**
- Produces: `ModelProvider` 新属性 `String lastTestStatus` / `OffsetDateTime lastTestAt` / `String lastTestError`（含 getter/setter）；`ProviderResponse` 末尾新增同名三字段。Task 3、4 依赖这些名字。

- [x] **Step 1: 写失败测试**——`ProviderServiceTest` 追加（沿用该文件既有 mock 风格）：

```java
@Test
void list_投影携带最近测试三字段() {
    ModelProvider e = new ModelProvider();
    e.setId(7L);
    e.setName("通义");
    e.setLastTestStatus("fail");
    e.setLastTestAt(OffsetDateTime.parse("2026-07-06T10:00:00+08:00"));
    e.setLastTestError("401 Unauthorized");
    when(mapper.selectList(any())).thenReturn(List.of(e));

    ProviderResponse resp = service.list().get(0);

    assertEquals("fail", resp.lastTestStatus());
    assertEquals(OffsetDateTime.parse("2026-07-06T10:00:00+08:00"), resp.lastTestAt());
    assertEquals("401 Unauthorized", resp.lastTestError());
}
```

- [x] **Step 2: 跑测试确认编译失败**（字段不存在）

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest=ProviderServiceTest; echo EXIT=$?`
Expected: EXIT 非 0（compilation error: cannot find symbol setLastTestStatus）

- [x] **Step 3: 实现**

`V17__alter_model_provider_last_test.sql`（风格对齐 V8）：

```sql
-- V17__alter_model_provider_last_test.sql
-- 供应商试连接：记录最近一次手动测试结果，NULL=从未测试（设计见 specs/2026-07-06）。
ALTER TABLE model_provider
    ADD COLUMN last_test_status varchar(8) CHECK (last_test_status IN ('ok', 'fail')),
    ADD COLUMN last_test_at     timestamptz,
    ADD COLUMN last_test_error  text;

COMMENT ON COLUMN model_provider.last_test_status IS '最近一次试连接结果 ok/fail，NULL=从未测试';
COMMENT ON COLUMN model_provider.last_test_at     IS '最近一次试连接时间';
COMMENT ON COLUMN model_provider.last_test_error  IS '最近一次试连接失败原因（成功置 NULL）';
```

`ModelProvider.java`：在 `streamMaxDurationSec` 字段后加（import `java.time.OffsetDateTime`）：

```java
    // 最近一次试连接结果（V17）；NULL=从未测试。成功/失败由 ModelConnectionService 落库。
    private String lastTestStatus;          // ok / fail
    private OffsetDateTime lastTestAt;
    private String lastTestError;
```

并按文件既有风格补 6 个 getter/setter。

`ProviderResponse.java`——**末尾**追加三字段：

```java
public record ProviderResponse(
        Long id,
        String name,
        String protocol,
        String baseUrl,
        String status,
        String apiKeyTail,
        OffsetDateTime createTime,
        String lastTestStatus,
        OffsetDateTime lastTestAt,
        String lastTestError) {
}
```

`ProviderService.toResponse`：

```java
    private ProviderResponse toResponse(ModelProvider e) {
        return new ProviderResponse(
                e.getId(), e.getName(), e.getProtocol(), e.getBaseUrl(),
                e.getStatus(), e.getApiKeyTail(), e.getCreateTime(),
                e.getLastTestStatus(), e.getLastTestAt(), e.getLastTestError());
    }
```

`AdminProviderControllerTest.java:57` 的 `new ProviderResponse(7L, "通义-生产", "openai", ...)` 结尾补 `null, null, null`。

- [x] **Step 4: 跑 provider 模块测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest='com.hify.provider.**'; echo EXIT=$?`
Expected: EXIT=0

- [x] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V17__alter_model_provider_last_test.sql \
  server/src/main/java/com/hify/provider/entity/ModelProvider.java \
  server/src/main/java/com/hify/provider/dto/ProviderResponse.java \
  server/src/main/java/com/hify/provider/service/ProviderService.java \
  server/src/test/java/com/hify/provider
git commit -m "feat(provider): V17 供应商最近测试三字段+实体/DTO/投影（TDD）"
```

---

### Task 2: ModelConnectionService 模型级测试支持 embedding

**Files:**
- Modify: `server/src/main/java/com/hify/provider/service/ModelConnectionService.java`（整体重写，见下）
- Test: Create `server/src/test/java/com/hify/provider/service/ModelConnectionServiceTest.java`

**Interfaces:**
- Consumes: `ResilienceRegistry.getChatClient(Long)` / `getEmbeddingModel(Long)`（已有）。
- Produces: `ModelConnectionService` 构造函数变为 `(ResilienceRegistry, AiModelMapper, ModelProviderMapper)`；`test(Long modelId)` 签名不变；私有 `pingModel(AiModel)` 供 Task 3 的 `testProvider` 复用。

- [x] **Step 1: 写失败测试**——新建 `ModelConnectionServiceTest.java`：

```java
package com.hify.provider.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.exception.BizException;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelConnectionServiceTest {

    private ResilienceRegistry registry;
    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ModelConnectionService service;

    @BeforeEach
    void setUp() {
        // Lambda Wrapper 需要 TableInfo（模仿 EmbeddingSettingServiceTest）
        for (Class<?> c : new Class<?>[]{AiModel.class, ModelProvider.class}) {
            if (TableInfoHelper.getTableInfo(c) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), c);
            }
        }
        registry = mock(ResilienceRegistry.class);
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        service = new ModelConnectionService(registry, modelMapper, providerMapper);
    }

    private AiModel model(long id, String type) {
        AiModel m = new AiModel();
        m.setId(id);
        m.setProviderId(1L);
        m.setType(type);
        m.setName(type + "-模型");
        m.setStatus("enabled");
        return m;
    }

    @Test
    void test_chat模型_ping聊天返回样例() {
        when(modelMapper.selectById(5L)).thenReturn(model(5L, "chat"));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user("ping").call().content()).thenReturn("pong");
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        ModelTestResponse resp = service.test(5L);

        assertEquals("pong", resp.sample());
    }

    @Test
    void test_embedding模型_转向量返回维度样例() {
        when(modelMapper.selectById(6L)).thenReturn(model(6L, "embedding"));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);

        ModelTestResponse resp = service.test(6L);

        assertEquals("已返回 1024 维向量", resp.sample());
    }

    @Test
    void test_模型不存在_MODEL_NOT_USABLE() {
        when(modelMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.test(99L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest=ModelConnectionServiceTest; echo EXIT=$?`
Expected: EXIT 非 0（构造函数不匹配，编译失败）

- [x] **Step 3: 重写 `ModelConnectionService.java`**（testProvider 在 Task 3 才加）：

```java
package com.hify.provider.service;

import com.hify.common.exception.BizException;
import com.hify.provider.api.dto.ModelTestResponse;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.entity.AiModel;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

/**
 * admin 测试供应商/模型连通：chat 发一句最短 prompt，embedding 把它转向量，验证 Key/baseUrl/网络。
 * 不加 @Transactional——这是真实外部 IO（事务内禁外部调用，llm-resilience.md §1）。
 */
@Service
public class ModelConnectionService {

    private final ResilienceRegistry registry;
    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;

    public ModelConnectionService(ResilienceRegistry registry, AiModelMapper modelMapper,
                                  ModelProviderMapper providerMapper) {
        this.registry = registry;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
    }

    /** 模型级测试：不回写供应商状态（modelKey 配错不代表供应商连接坏了）。 */
    public ModelTestResponse test(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        return new ModelTestResponse(pingModel(model));
    }

    /** 按模型类型真实调用一次；启用/类型校验由 registry 内部完成。 */
    private String pingModel(AiModel model) {
        if (ModelType.EMBEDDING.value().equals(model.getType())) {
            float[] vector = registry.getEmbeddingModel(model.getId()).embed("ping");
            return "已返回 " + vector.length + " 维向量";
        }
        return registry.getChatClient(model.getId())
                .prompt().user("ping").call().content();
    }
}
```

（`providerMapper` 本 Task 未用属正常——Task 3 的 `testProvider` 落库要用，一次把构造函数改到位，避免两次动 Controller 测试。）

- [x] **Step 4: 跑测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest=ModelConnectionServiceTest; echo EXIT=$?`
Expected: EXIT=0

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/provider/service/ModelConnectionService.java \
  server/src/test/java/com/hify/provider/service/ModelConnectionServiceTest.java
git commit -m "feat(provider): 模型级测试支持 embedding 分流（TDD）"
```

---

### Task 3: testProvider（挑模型+落库）+ 供应商级端点

**Files:**
- Create: `server/src/main/java/com/hify/provider/dto/ProviderTestResponse.java`
- Modify: `server/src/main/java/com/hify/provider/service/ModelConnectionService.java`
- Modify: `server/src/main/java/com/hify/provider/controller/AdminProviderController.java`
- Test: `server/src/test/java/com/hify/provider/service/ModelConnectionServiceTest.java`（追加）
- Test: `server/src/test/java/com/hify/provider/controller/AdminProviderControllerTest.java`（追加 `@MockitoBean ModelConnectionService` + 端点用例）

**Interfaces:**
- Produces: `ProviderTestResponse(String modelName, String sample)`（record，`provider/dto`，仅本模块用，不进 api 包）；`ModelConnectionService.testProvider(Long providerId)`；`POST /api/v1/admin/provider/providers/{id}/test`。Task 4 前端按 `{ modelName, sample }` 对接。

- [x] **Step 1: 写失败测试**——`ModelConnectionServiceTest` 追加（import `CommonError`、`LambdaUpdateWrapper` 相关按需补）：

```java
    private ModelProvider provider(String status) {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setName("通义");
        p.setStatus(status);
        return p;
    }

    @Test
    void testProvider_优先挑chat_成功落库ok() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(
                java.util.List.of(model(6L, "embedding"), model(5L, "chat")));
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user("ping").call().content()).thenReturn("pong");
        when(registry.getChatClient(5L)).thenReturn(chatClient);

        ProviderTestResponse resp = service.testProvider(1L);

        assertEquals("chat-模型", resp.modelName());
        assertEquals("pong", resp.sample());
        ArgumentCaptor<LambdaUpdateWrapper<ModelProvider>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(providerMapper).update(isNull(), captor.capture());
        assertTrue(captor.getValue().getSqlSet().contains("last_test_status"));
    }

    @Test
    void testProvider_仅embedding_用embedding测() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of(model(6L, "embedding")));
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);

        ProviderTestResponse resp = service.testProvider(1L);

        assertEquals("已返回 1024 维向量", resp.sample());
    }

    @Test
    void testProvider_调用失败_落库fail后原异常继续抛() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of(model(5L, "chat")));
        when(registry.getChatClient(5L))
                .thenThrow(new BizException(ProviderError.PROVIDER_UNAVAILABLE));

        BizException ex = assertThrows(BizException.class, () -> service.testProvider(1L));

        assertEquals(ProviderError.PROVIDER_UNAVAILABLE, ex.errorCode());
        verify(providerMapper).update(isNull(), any()); // 失败也落库
    }

    @Test
    void testProvider_无启用模型_12002且不落库() {
        when(providerMapper.selectById(1L)).thenReturn(provider("enabled"));
        when(modelMapper.selectList(any())).thenReturn(java.util.List.of());

        BizException ex = assertThrows(BizException.class, () -> service.testProvider(1L));

        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
        verify(providerMapper, never()).update(any(), any());
    }

    @Test
    void testProvider_供应商不存在或禁用() {
        when(providerMapper.selectById(9L)).thenReturn(null);
        assertEquals(CommonError.NOT_FOUND,
                assertThrows(BizException.class, () -> service.testProvider(9L)).errorCode());

        when(providerMapper.selectById(1L)).thenReturn(provider("disabled"));
        assertEquals(ProviderError.MODEL_NOT_USABLE,
                assertThrows(BizException.class, () -> service.testProvider(1L)).errorCode());
    }
```

- [x] **Step 2: 跑测试确认编译失败**（`testProvider` / `ProviderTestResponse` 不存在）

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest=ModelConnectionServiceTest; echo EXIT=$?`
Expected: EXIT 非 0

- [x] **Step 3: 实现**

`ProviderTestResponse.java`：

```java
package com.hify.provider.dto;

/** admin 供应商试连接响应：本次借用的模型显示名 + 样例文本（同 ModelTestResponse.sample 语义）。 */
public record ProviderTestResponse(String modelName, String sample) {
}
```

`ModelConnectionService` 追加（import `LambdaQueryWrapper`/`LambdaUpdateWrapper`/`CommonError`/`ProviderStatus`/`ModelProvider`/`ProviderTestResponse`/`OffsetDateTime`/`List`）：

```java
    /**
     * 供应商级试连接：优先挑启用的 chat 模型（ping 便宜），无 chat 用 embedding；
     * 真实调用后成败都落库（最近一次测试三字段）；「无启用模型」没发请求，不落库。
     */
    public ProviderTestResponse testProvider(Long providerId) {
        ModelProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BizException(CommonError.NOT_FOUND, "供应商不存在");
        }
        if (!ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE, "供应商已禁用，无法试连接");
        }
        List<AiModel> models = modelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderId, providerId)
                .eq(AiModel::getStatus, ProviderStatus.ENABLED.value()));
        AiModel candidate = models.stream()
                .filter(m -> ModelType.CHAT.value().equals(m.getType()))
                .findFirst()
                .orElseGet(() -> models.isEmpty() ? null : models.get(0));
        if (candidate == null) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE, "该供应商下暂无启用的模型，无法试连接");
        }
        try {
            String sample = pingModel(candidate);
            saveResult(providerId, "ok", null);
            return new ProviderTestResponse(candidate.getName(), sample);
        } catch (RuntimeException e) {
            saveResult(providerId, "fail", e.getMessage());
            throw e;
        }
    }

    /** updateById 会忽略 null 字段，清空 last_test_error 必须用 UpdateWrapper 显式 set NULL。 */
    private void saveResult(Long providerId, String status, String error) {
        providerMapper.update(null, new LambdaUpdateWrapper<ModelProvider>()
                .eq(ModelProvider::getId, providerId)
                .set(ModelProvider::getLastTestStatus, status)
                .set(ModelProvider::getLastTestAt, OffsetDateTime.now())
                .set(ModelProvider::getLastTestError, error));
    }
```

`AdminProviderController`：构造注入 `ModelConnectionService modelConnectionService`，追加：

```java
    /** 试连接：自动挑一个启用模型真实调用，成败都记入最近测试字段。失败按韧性映射（12002/12003/12004）。 */
    @PostMapping("/{id}/test")
    public Result<ProviderTestResponse> test(@PathVariable Long id) {
        return Result.ok(modelConnectionService.testProvider(id));
    }
```

`AdminProviderControllerTest`：加 `@MockitoBean private ModelConnectionService modelConnectionService;`，追加用例（沿用该文件既有 JWT/MockMvc 写法）：

```java
    @Test
    void test_试连接_返回模型名与样例() throws Exception {
        when(modelConnectionService.testProvider(7L))
                .thenReturn(new ProviderTestResponse("通义-chat", "pong"));
        mockMvc.perform(post("/api/v1/admin/provider/providers/7/test")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelName").value("通义-chat"))
                .andExpect(jsonPath("$.data.sample").value("pong"));
    }
```

（`adminToken()` 为该测试类已有的取 token 辅助写法，按现文件实际方法名对齐。）

- [x] **Step 4: 跑 provider 模块测试通过**

Run: `cd /home/wang/playlab/hify/server && mvn -q test -Dtest='com.hify.provider.**'; echo EXIT=$?`
Expected: EXIT=0

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/provider server/src/test/java/com/hify/provider
git commit -m "feat(provider): 供应商试连接端点 testProvider 挑模型+成败落库（TDD）"
```

---

### Task 4: 前端类型 + 130s 专用超时 + 两个测试 API

**Files:**
- Modify: `web/src/types/provider.ts`
- Modify: `web/src/types/model.ts`
- Modify: `web/src/config/index.ts`
- Modify: `web/src/api/admin/provider.ts`
- Modify: `web/src/api/admin/model.ts`
- Modify: `web/src/views/admin/provider/__tests__/ProviderList.spec.ts`、`ProviderDetail.spec.ts`（Provider 桩补三字段，修 typecheck）
- Test: `web/src/api/admin/__tests__/provider.spec.ts`、`web/src/api/admin/__tests__/model.spec.ts`（追加）

**Interfaces:**
- Consumes: Task 3 的 `{ modelName, sample }` 响应与两个端点路径。
- Produces: `testProvider(id: string): Promise<ProviderTestResult>`、`testModel(id: string): Promise<ModelTestResult>`、`config.llmTestTimeoutMs`、`Provider` 新三字段。Task 5/6 依赖。

- [x] **Step 1: 写失败测试**——`api/admin/__tests__/provider.spec.ts` 追加（`model.spec.ts` 同式）：

```ts
import { config } from '@/config'

it('testProvider → POST /{id}/test 且带 LLM 专用超时', () => {
  testProvider('7')
  expect(request.post).toHaveBeenCalledWith('/admin/provider/providers/7/test', undefined, {
    timeout: config.llmTestTimeoutMs,
  })
})
```

`model.spec.ts` 追加：

```ts
it('testModel → POST /models/{id}/test 且带 LLM 专用超时', () => {
  testModel('5')
  expect(request.post).toHaveBeenCalledWith('/admin/provider/models/5/test', undefined, {
    timeout: config.llmTestTimeoutMs,
  })
})
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/api/admin/__tests__/`
Expected: FAIL（testProvider/testModel 未导出）

- [x] **Step 3: 实现**

`types/provider.ts`——`Provider` 接口 `createTime` 后追加，并新增响应类型：

```ts
  /** 最近一次试连接：null=从未测试（对齐 V17 三字段） */
  lastTestStatus: 'ok' | 'fail' | null
  lastTestAt: string | null
  lastTestError: string | null
```

```ts
/** 供应商试连接响应（对齐后端 ProviderTestResponse）。 */
export interface ProviderTestResult {
  modelName: string
  sample: string
}
```

`types/model.ts` 新增：

```ts
/** 模型测试响应（对齐后端 ModelTestResponse）。 */
export interface ModelTestResult {
  sample: string
}
```

`config/index.ts`——`uploadTimeoutMs` 下加：

```ts
  // 试连接是真实 LLM 调用：后端非流式预算最长 120s，前端超时须 ≥ 后端预算（否则客户端先断）。
  llmTestTimeoutMs: 130_000,
```

`api/admin/provider.ts`（补 `import { config } from '@/config'` 与 `ProviderTestResult` 类型导入）：

```ts
/** 试连接：后端自动挑一个启用模型真实调用并落库。后端：POST .../{id}/test */
export function testProvider(id: string) {
  return request.post<ProviderTestResult>(`${BASE}/${id}/test`, undefined, {
    timeout: config.llmTestTimeoutMs,
  })
}
```

`api/admin/model.ts`（同样补 import）：

```ts
/** 测试模型连通（chat 发 ping / embedding 转向量）。后端：POST .../models/{id}/test */
export function testModel(id: string) {
  return request.post<ModelTestResult>(`${BASE}/models/${id}/test`, undefined, {
    timeout: config.llmTestTimeoutMs,
  })
}
```

两个视图测试文件里所有 `Provider` 字面量补：`lastTestStatus: null, lastTestAt: null, lastTestError: null`（本 Task 只为过 typecheck；三态渲染用例在 Task 5 写）。

- [x] **Step 4: 跑测试与 typecheck 通过**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add web/src/types web/src/config/index.ts web/src/api/admin web/src/views/admin/provider/__tests__
git commit -m "feat(web): 试连接 API 层+类型+LLM 专用超时（TDD）"
```

---

### Task 5: ProviderList 连接三态列 + 试连接按钮

**Files:**
- Modify: `web/src/views/admin/provider/ProviderList.vue`
- Test: `web/src/views/admin/provider/__tests__/ProviderList.spec.ts`（追加）

**Interfaces:**
- Consumes: `testProvider`、`Provider.lastTestStatus/lastTestAt/lastTestError`（Task 4）。

- [x] **Step 1: 写失败测试**——`ProviderList.spec.ts`：把 SAMPLE 第 1 条改为 `lastTestStatus: 'ok', lastTestAt: '2026-07-06T10:00:00+08:00', lastTestError: null`，第 2 条保持三 null，另加第 3 条 `status: 'disabled'` 且 `lastTestStatus: 'fail', lastTestError: '401 Unauthorized'` 的供应商（id: '9'）。追加用例：

```ts
it('连接列渲染三态标签', async () => {
  const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  const text = wrapper.find('[data-test="provider-table"]').text()
  expect(text).toContain('通过')
  expect(text).toContain('未测试')
  expect(text).toContain('失败')
})

it('点击试连接调用 API 并刷新列表', async () => {
  vi.mocked(testProvider).mockResolvedValue({ modelName: '通义-chat', sample: 'pong' })
  const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  await wrapper.find('[data-test="test-1"]').trigger('click')
  await flushPromises()
  expect(testProvider).toHaveBeenCalledWith('1')
  expect(listProviders).toHaveBeenCalledTimes(2) // 挂载 1 次 + 测试后刷新 1 次
})

it('禁用供应商的试连接按钮置灰', async () => {
  const wrapper = mount(ProviderList, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  expect(wrapper.find('[data-test="test-9"]').attributes('disabled')).toBeDefined()
})
```

（`vi.mock('@/api/admin/provider', ...)` 工厂里补 `testProvider: vi.fn()` 并 import。）

- [x] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderList.spec.ts`
Expected: FAIL（无连接列/按钮）

- [x] **Step 3: 实现 `ProviderList.vue`**

script 部分：import 补 `testProvider`；新增：

```ts
const testingId = ref<string | null>(null)

/** tooltip 文案：时间 + 失败原因（成功只有时间）。 */
function connTooltip(row: Provider): string {
  const time = row.lastTestAt ? `测试时间：${formatDateTime(row.lastTestAt)}` : ''
  return row.lastTestStatus === 'fail' ? `${time}　${row.lastTestError ?? ''}` : time
}

async function onTest(row: Provider) {
  testingId.value = row.id
  try {
    const result = await testProvider(row.id)
    ElMessage.success(`连接正常（${result.modelName}）`)
  } catch {
    /* 失败原因已由 request 拦截器统一 toast */
  } finally {
    testingId.value = null
    await load() // 成败都已落库，刷新「连接」列
  }
}
```

template：「状态」列后加「连接」列：

```vue
<el-table-column label="连接">
  <template #default="{ row }">
    <el-tooltip
      v-if="(row as Provider).lastTestStatus !== null"
      :content="connTooltip(row as Provider)"
      placement="top"
    >
      <el-tag :type="(row as Provider).lastTestStatus === 'ok' ? 'success' : 'danger'">
        {{ (row as Provider).lastTestStatus === 'ok' ? '通过' : '失败' }}
      </el-tag>
    </el-tooltip>
    <el-tag v-else type="info">未测试</el-tag>
  </template>
</el-table-column>
```

操作列 `width="320"` 改 `width="400"`，「管理模型」按钮后加：

```vue
<el-button
  :data-test="`test-${(row as Provider).id}`"
  size="small"
  :loading="testingId === (row as Provider).id"
  :disabled="(row as Provider).status !== 'enabled'"
  @click="onTest(row as Provider)"
  >试连接</el-button
>
```

- [x] **Step 4: 跑测试通过**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderList.spec.ts && pnpm typecheck`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add web/src/views/admin/provider/ProviderList.vue \
  web/src/views/admin/provider/__tests__/ProviderList.spec.ts
git commit -m "feat(web): 供应商列表连接三态列+试连接按钮（TDD）"
```

---

### Task 6: ProviderDetail 模型行「测试」按钮

**Files:**
- Modify: `web/src/views/admin/provider/ProviderDetail.vue`
- Test: `web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts`（追加）

**Interfaces:**
- Consumes: `testModel`（Task 4）。

- [x] **Step 1: 写失败测试**——`ProviderDetail.spec.ts` 追加（`vi.mock('@/api/admin/model', ...)` 工厂补 `testModel: vi.fn()`；沿用该文件既有挂载/路由写法）：

```ts
it('点击模型测试按钮调用 API 并弹出样例', async () => {
  vi.mocked(testModel).mockResolvedValue({ sample: 'pong' })
  const wrapper = await mountDetail() // 该文件既有挂载辅助；无则按现有用例的 mount+flushPromises 写法展开
  await wrapper.find('[data-test="model-test-5"]').trigger('click')
  await flushPromises()
  expect(testModel).toHaveBeenCalledWith('5')
})

it('禁用模型的测试按钮置灰', async () => {
  const wrapper = await mountDetail()
  expect(wrapper.find('[data-test="model-test-6"]').attributes('disabled')).toBeDefined()
})
```

（前提：该文件模型桩里有 id '5' 启用、id '6' 禁用；若现桩不满足，按此调整桩数据。）

- [x] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts`
Expected: FAIL（无测试按钮）

- [x] **Step 3: 实现 `ProviderDetail.vue`**

script：import 补 `testModel`；新增：

```ts
const testingId = ref<string | null>(null)

async function onTest(row: AiModel) {
  testingId.value = row.id
  try {
    const result = await testModel(row.id)
    ElMessage.success(`测试通过：${result.sample}`)
  } catch {
    /* 已由 request 拦截器统一 toast */
  } finally {
    testingId.value = null
  }
}
```

template：操作列 `width="240"` 改 `width="300"`，「编辑」按钮前加：

```vue
<el-button
  :data-test="`model-test-${(row as AiModel).id}`"
  size="small"
  :loading="testingId === (row as AiModel).id"
  :disabled="(row as AiModel).status !== 'enabled'"
  @click="onTest(row as AiModel)"
  >测试</el-button
>
```

- [x] **Step 4: 跑测试通过**

Run: `cd /home/wang/playlab/hify/web && pnpm test src/views/admin/provider/__tests__/ProviderDetail.spec.ts && pnpm typecheck`
Expected: 全部通过

- [ ] **Step 5: Commit**

```bash
git add web/src/views/admin/provider/ProviderDetail.vue \
  web/src/views/admin/provider/__tests__/ProviderDetail.spec.ts
git commit -m "feat(web): 供应商详情页模型行测试按钮（TDD）"
```

---

### Task 7: 全量回归

**Files:** 无新改动（只跑回归；有失败则修）。

- [x] **Step 1: 后端全量（含 ModularityTests / ArchUnit）**

Run: `cd /home/wang/playlab/hify/server && mvn -q verify; echo EXIT=$?`
Expected: EXIT=0（**不要**用 grep BUILD SUCCESS 判定）

- [x] **Step 2: 前端全量**

Run: `cd /home/wang/playlab/hify/web && pnpm test && pnpm typecheck && pnpm lint`
Expected: 全部通过（lint 带 --fix，若有自动修复产生 diff 则一并提交）

- [x] **Step 3: 如有修复，提交**

```bash
git add -A && git commit -m "chore: 试连接功能全量回归修复"
```

（无 diff 则跳过。）

---

## 手动验收清单（执行完后给用户）

1. 列表页每行出现「连接」列，初始为灰色「未测试」；
2. 点某启用供应商「试连接」→ 按钮转 loading → 绿色成功提示（含借用的模型名），「连接」列变绿「通过」，悬停显示时间；
3. 把该供应商 baseUrl 改成错的再试 → 红色错误 toast，「连接」列变红「失败」，悬停显示时间+原因；刷新页面状态仍在；
4. 禁用的供应商「试连接」按钮置灰；
5. 详情页 chat 模型点「测试」→ 成功提示含模型回复样例；
6. 详情页 embedding 模型点「测试」→ 成功提示「已返回 1024 维向量」；
7. 详情页把某模型 modelKey 改错再测 → 报错，但列表页该供应商「连接」列**不变**（模型级不回写）。
