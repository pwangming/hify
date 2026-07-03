# knowledge K3 向量化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kb_chunk 补 1024 维向量列 + HNSW；embedding 模型做成 admin 可配的系统级设置（保存时探测验维度）；上传流水线整体异步化（提取→分段→分批嵌入，状态机 pending→processing→ready/failed）；failed 可重试（断点续嵌）；admin 全量重嵌入按钮（含存量补嵌）；顺路修 3 个 K2 跟进项。

**Architecture:** provider 模块新增 EmbeddingModel 工厂/批量池韧性装饰/system_setting 设置服务，经 `ProviderFacade.getEmbeddingModel()`（无参）暴露；knowledge 模块把 K2 的同步上传改为「落库 pending + AFTER_COMMIT 事件派发异步流水线」。Spec：`docs/superpowers/specs/2026-07-03-knowledge-k3-embedding-design.md`。

**Tech Stack:** Spring Boot 3 + Spring AI 1.0.1（`OpenAiEmbeddingModel`）+ Resilience4j + MyBatis-Plus（向量写库用注解 SQL `::vector` cast）+ Flyway；Vue 3 + Element Plus + vitest。

## Global Constraints

- 后端命令在 `/home/wang/playlab/hify/server` 下跑，前端在 `/home/wang/playlab/hify/web` 下跑；**mvn 不带 `-q`**（会静音汇总行，无法判断结果）。
- TDD 先红后绿；每步测试证据入报告。
- 错误码**仅新增四枚**：`12005 EMBEDDING_DIMENSION_MISMATCH(400)`、`12006 EMBEDDING_MODEL_NOT_CONFIGURED(409)`、`15002 DOCUMENT_STATE_CONFLICT(409)`、`15003 REEMBED_IN_PROGRESS(409)`；其余复用现有码（12001/12002/12003/12004/15001/15004/CommonError）。发布后只增不改。
- 只新增 V15、V16 两个迁移，禁改旧迁移。HNSW 建法：`using hnsw (embedding vector_cosine_ops)`，m/ef_construction 默认。
- `@Transactional` 内零外部 IO：嵌入 API 调用必须在事务外完成，拿到向量再开小事务写库。
- 手写 SQL（注解 @Update/@Select）**不享受 @TableLogic 自动过滤，必须手写 `deleted = false`**；写操作必须带 `update_time = now()`。
- 韧性参数不新增配置：批量池并发用 `model_provider.batch_concurrency`（V8 已有，默认 3），超时/重试/熔断复用该行其余字段；唯一新 yml 键 `hify.knowledge.embedding-batch-size`（默认 10，千问 v4 单次上限）。
- Long 序列化为 string；枚举/状态小写字符串；`Result` 信封；Controller 无业务逻辑无 try-catch。
- 前端：Element Plus 组件优先；类型放 `types/`；API 层一函数一测；`KbDocument` 命名不变（避免与 DOM Document 撞名）。
- 契约变化仅两条（spec 决策 12）：上传响应 status=pending/chunkCount=0；15001 不再出现在上传响应（异步 failed + errorMessage 呈现）。其余 K1/K2 端点签名不动。
- 分段预览等查询不 select embedding 列（entity 不映射该列，天然排除）。

---

### Task 1: V15/V16 迁移 + SystemSetting 实体/Mapper + KbDocument.errorMessage + yml

**Files:**
- Create: `server/src/main/resources/db/migration/V15__kb_chunk_embedding.sql`
- Create: `server/src/main/resources/db/migration/V16__create_system_setting.sql`
- Create: `server/src/main/java/com/hify/provider/entity/SystemSetting.java`
- Create: `server/src/main/java/com/hify/provider/mapper/SystemSettingMapper.java`
- Modify: `server/src/main/java/com/hify/knowledge/entity/KbDocument.java`（加 errorMessage 字段）
- Modify: `server/src/main/resources/application.yml`（hify.knowledge 加一键）

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`。
- Produces: `SystemSetting`（getSettingKey/getSettingValue + setter + 基类）、`SystemSettingMapper extends BaseMapper<SystemSetting>`、`KbDocument.getErrorMessage()/setErrorMessage(String)`、配置键 `hify.knowledge.embedding-batch-size`。

- [ ] **Step 1: 写 V15 迁移**

`server/src/main/resources/db/migration/V15__kb_chunk_embedding.sql`：

```sql
-- V15：kb_chunk 补 embedding 向量列 + HNSW 索引（兑现 K2 spec 决策 1）；kb_document 补失败原因列。
-- 向量列可空：分段落库时必然无向量（异步补嵌）；「embedding is null」同时是断点续嵌的选段依据。

alter table kb_chunk add column embedding vector(1024);
comment on column kb_chunk.embedding is '1024 维向量（database-standards §2.1：全库统一模型与维度，换模型=全量重嵌）；null=未嵌入';

-- HNSW 余弦索引，m/ef_construction 用默认（database-standards §2.1 原文建法）
create index kb_chunk_embedding_idx on kb_chunk using hnsw (embedding vector_cosine_ops);

alter table kb_document add column error_message text;
comment on column kb_document.error_message is 'status=failed 时的用户可读原因；其余状态为 null';
```

- [ ] **Step 2: 写 V16 迁移**

`server/src/main/resources/db/migration/V16__create_system_setting.sql`：

```sql
-- V16：系统设置 KV 表（data-model 预留席位兑现），当前归 provider 模块管理。
-- K3 仅一个键 embedding_model_id；后续系统设置复用本表，归属届时再议。
-- 照 database-standards §1.1 模板（四标准列强制）；业务唯一性用部分唯一索引（§2 条 3）。

create table system_setting (
    id            bigint      generated always as identity primary key,
    setting_key   text        not null check (char_length(setting_key) <= 100),
    setting_value text,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table system_setting is '系统设置 KV（admin 可改）；K3 起用，键：embedding_model_id';
create unique index system_setting_key_uq on system_setting (setting_key) where deleted = false;
```

- [ ] **Step 3: SystemSetting 实体与 Mapper**

`server/src/main/java/com/hify/provider/entity/SystemSetting.java`：

```java
package com.hify.provider.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;

/**
 * 系统设置 KV 表 {@code system_setting} 映射实体。当前归 provider 模块管理（K3 spec 决策 2）；
 * K3 仅一个键 embedding_model_id，值存字符串（Long 的十进制形态）。
 */
@TableName("system_setting")
public class SystemSetting extends BaseEntity {

    private String settingKey;
    private String settingValue;

    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }

    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
}
```

`server/src/main/java/com/hify/provider/mapper/SystemSettingMapper.java`：

```java
package com.hify.provider.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.provider.entity.SystemSetting;
import org.apache.ibatis.annotations.Mapper;

/** system_setting 表访问（KV，按 setting_key 查单行）。 */
@Mapper
public interface SystemSettingMapper extends BaseMapper<SystemSetting> {
}
```

- [ ] **Step 4: KbDocument 加 errorMessage**

`server/src/main/java/com/hify/knowledge/entity/KbDocument.java`：在 `chunkOverlap` 字段声明之后追加字段，并在类尾（最后一对 getter/setter 之后）追加访问器：

```java
    private String errorMessage; // status=failed 时的用户可读原因；其余状态为 null
```

```java
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
```

- [ ] **Step 5: application.yml 加配置键**

`server/src/main/resources/application.yml` 的 `hify.knowledge` 段（现有 chunk-size/chunk-overlap 之后）追加：

```yaml
    # 每次 embedding API 调用打包的分段数（千问 v4 单次上限 10；OpenAI 可更大但统一按最小公倍走）。
    embedding-batch-size: ${HIFY_KNOWLEDGE_EMBEDDING_BATCH_SIZE:10}
```

- [ ] **Step 6: 编译 + 既有测试回归**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: `BUILD SUCCESS`，`Tests run: 340, Failures: 0, Errors: 0`（数量与 K2 合并后基线一致）

- [ ] **Step 7: Commit**

```bash
git add server/src/main/resources/db/migration/V15__kb_chunk_embedding.sql server/src/main/resources/db/migration/V16__create_system_setting.sql server/src/main/java/com/hify/provider/entity/SystemSetting.java server/src/main/java/com/hify/provider/mapper/SystemSettingMapper.java server/src/main/java/com/hify/knowledge/entity/KbDocument.java server/src/main/resources/application.yml
git commit -m "feat(knowledge,provider): V15 向量列+HNSW / V16 system_setting 表 + 实体与配置"
```

---

### Task 2: provider 韧性与工厂 — buildEmbeddingModel + 批量池 + ResilientEmbeddingModel + Registry

**Files:**
- Modify: `server/src/main/java/com/hify/provider/constant/ProviderError.java`（加 12005/12006）
- Modify: `server/src/main/java/com/hify/provider/service/ChatClientFactory.java`（加 buildEmbeddingModel）
- Modify: `server/src/main/java/com/hify/provider/service/resilience/ResilienceBundle.java`（加 buildBatch）
- Create: `server/src/main/java/com/hify/provider/service/resilience/ResilientEmbeddingModel.java`
- Modify: `server/src/main/java/com/hify/provider/service/resilience/ResilienceRegistry.java`（加 getEmbeddingModel + 缓存失效）
- Test: `server/src/test/java/com/hify/provider/service/ChatClientFactoryTest.java`（增量）
- Test: `server/src/test/java/com/hify/provider/service/resilience/ResilienceRegistryTest.java`（增量）

**Interfaces:**
- Consumes: `ApiKeyCipher.decrypt(String)`、`ResilienceExceptions.toBizException/sneaky/isRetryable/isProviderFault`（Task 前已有）、`ModelType.EMBEDDING.value()`（constant/ModelType.java 已有 CHAT/EMBEDDING 两值）。
- Produces: `ChatClientFactory.EMBEDDING_DIMENSION`（public static final int = 1024）、`ChatClientFactory.buildEmbeddingModel(ModelProvider, AiModel)` → `org.springframework.ai.embedding.EmbeddingModel`、`ResilienceBundle.buildBatch(ModelProvider)`、`ResilienceRegistry.getEmbeddingModel(Long modelId)`（不可用抛 `BizException(MODEL_NOT_USABLE)`）。

- [ ] **Step 1: 写失败测试（工厂 + Registry 增量）**

`ChatClientFactoryTest.java` 追加（照文件既有风格，构造 factory 的方式复用文件里已有的 setup；若既有测试用 `new ChatClientFactory(cipher, retryTemplate)` 直接沿用）：

```java
    @Test
    void buildEmbeddingModel_openai协议_返回OpenAiEmbeddingModel() {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setProtocol("openai");
        p.setBaseUrl("https://api.example.com");
        p.setApiKeyCipher("cipher-text");
        AiModel m = new AiModel();
        m.setModelKey("text-embedding-v4");
        when(cipher.decrypt("cipher-text")).thenReturn("sk-plain");

        EmbeddingModel result = factory.buildEmbeddingModel(p, m);

        assertInstanceOf(OpenAiEmbeddingModel.class, result);
    }

    @Test
    void buildEmbeddingModel_anthropic协议_抛12001() {
        ModelProvider p = new ModelProvider();
        p.setProtocol("anthropic");
        BizException ex = assertThrows(BizException.class,
                () -> factory.buildEmbeddingModel(p, new AiModel()));
        assertEquals(ProviderError.EMBEDDING_NOT_SUPPORTED, ex.errorCode());
    }
```

需要的新 import：`org.springframework.ai.embedding.EmbeddingModel`、`org.springframework.ai.openai.OpenAiEmbeddingModel`、`com.hify.common.exception.BizException`、`com.hify.provider.constant.ProviderError`、`static org.junit.jupiter.api.Assertions.assertInstanceOf`（其余按文件已有）。

`ResilienceRegistryTest.java` 追加（mock 风格照文件既有：mock 两个 Mapper + factory + executor）：

```java
    @Test
    void getEmbeddingModel_type非embedding_抛MODEL_NOT_USABLE() {
        AiModel chat = new AiModel();
        chat.setId(5L);
        chat.setType("chat");
        chat.setStatus("enabled");
        when(modelMapper.selectById(5L)).thenReturn(chat);

        BizException ex = assertThrows(BizException.class, () -> registry.getEmbeddingModel(5L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void getEmbeddingModel_可用_返回装饰实例且二次调用命中缓存() {
        AiModel emb = new AiModel();
        emb.setId(6L);
        emb.setProviderId(1L);
        emb.setType("embedding");
        emb.setStatus("enabled");
        ModelProvider p = enabledProvider(); // 复用文件里已有的「enabled 供应商」构造 helper；若无则新建 id=1/enabled/韧性字段全默认值的 helper
        when(modelMapper.selectById(6L)).thenReturn(emb);
        when(providerMapper.selectById(1L)).thenReturn(p);
        when(factory.buildEmbeddingModel(p, emb)).thenReturn(mock(EmbeddingModel.class));

        EmbeddingModel first = registry.getEmbeddingModel(6L);
        EmbeddingModel second = registry.getEmbeddingModel(6L);

        assertInstanceOf(ResilientEmbeddingModel.class, first);
        assertSame(first, second);
        verify(factory, times(1)).buildEmbeddingModel(p, emb);
    }

    @Test
    void invalidateModel_清embedding缓存() {
        AiModel emb = new AiModel();
        emb.setId(6L);
        emb.setProviderId(1L);
        emb.setType("embedding");
        emb.setStatus("enabled");
        ModelProvider p = enabledProvider();
        when(modelMapper.selectById(6L)).thenReturn(emb);
        when(providerMapper.selectById(1L)).thenReturn(p);
        when(factory.buildEmbeddingModel(p, emb)).thenReturn(mock(EmbeddingModel.class));

        registry.getEmbeddingModel(6L);
        registry.invalidateModel(6L);
        registry.getEmbeddingModel(6L);

        verify(factory, times(2)).buildEmbeddingModel(p, emb);
    }
```

注意：`enabledProvider()` helper 构造的 ModelProvider 必须给韧性字段赋值（`setBatchConcurrency(3)`、`setMaxConcurrency(10)`、`setResponseTimeoutSec(120)`、`setRetryMaxAttempts(3)`、`setCbFailureRate(50)`、`setCbWaitOpenSec(30)`、`setFirstTokenTimeoutSec(30)`、`setTokenGapTimeoutSec(60)`、`setStreamMaxDurationSec(600)`），否则 `ResilienceBundle.buildBatch` 拆箱 NPE。若文件已有 helper 但缺 batchConcurrency，补上。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ChatClientFactoryTest,ResilienceRegistryTest'`
Expected: 编译失败（`buildEmbeddingModel`/`getEmbeddingModel`/`ResilientEmbeddingModel` 不存在）

- [ ] **Step 3: 实现**

`ProviderError.java`：在 `PROVIDER_BUSY` 条目后追加两枚（注意前一条末尾改逗号）：

```java
    /** admin 保存 embedding 设置时探测返回维度 ≠ 1024。 */
    EMBEDDING_DIMENSION_MISMATCH(12005, HttpStatus.BAD_REQUEST, "该模型输出维度不符，需 1024 维"),
    /** 系统未配置 embedding 模型（getEmbeddingModel 无参入口 / 全量重嵌前置校验）。 */
    EMBEDDING_MODEL_NOT_CONFIGURED(12006, HttpStatus.CONFLICT, "系统未配置 embedding 模型，请联系管理员在系统设置中配置");
```

`ChatClientFactory.java`：类常量区加 `EMBEDDING_DIMENSION`，方法区加 `buildEmbeddingModel`：

```java
    /** 全库统一向量维度（kb_chunk.embedding vector(1024)，database-standards §2.1）。 */
    public static final int EMBEDDING_DIMENSION = 1024;
```

```java
    /**
     * 构建原始 EmbeddingModel（未包韧性，由 ResilientEmbeddingModel 装饰）。仅 openai 协议
     * （anthropic 无 embedding API，建模时已被 12001 拦，此处兜底）；options 固定 dimensions=1024，
     * 支持可变维度的模型（千问 v4 / OpenAI v3）自动输出 1024 维。
     */
    public EmbeddingModel buildEmbeddingModel(ModelProvider provider, AiModel model) {
        if (!"openai".equals(provider.getProtocol())) {
            throw new BizException(ProviderError.EMBEDDING_NOT_SUPPORTED);
        }
        String apiKey = cipher.decrypt(provider.getApiKeyCipher());
        OpenAiApi api = OpenAiApi.builder().baseUrl(provider.getBaseUrl()).apiKey(apiKey).build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model.getModelKey())
                        .dimensions(EMBEDDING_DIMENSION)
                        .build(),
                noRetryTemplate);
    }
```

新 import：`com.hify.common.exception.BizException`、`com.hify.provider.constant.ProviderError`、`org.springframework.ai.document.MetadataMode`、`org.springframework.ai.embedding.EmbeddingModel`、`org.springframework.ai.openai.OpenAiEmbeddingModel`、`org.springframework.ai.openai.OpenAiEmbeddingOptions`。

`ResilienceBundle.java`：在 `build` 方法后追加：

```java
    /**
     * 批量池（embedding/后台任务）：并发用 batch_concurrency，与交互池实例分离——各自独立的
     * 信号量与熔断器，文档批量向量化不吃对话并发额度、批量故障不熔断对话（llm-resilience.md §2）。
     * 信号量等待放宽到 300s：批量任务可排队（虚拟线程 park 零成本），不像交互请求要快速失败。
     */
    public static ResilienceBundle buildBatch(ModelProvider p) {
        String name = "llm-provider-batch-" + p.getId();

        TimeLimiter timeLimiter = TimeLimiter.of(name, TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(p.getResponseTimeoutSec()))
                .cancelRunningFuture(true)
                .build());

        Bulkhead bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(p.getBatchConcurrency())
                .maxWaitDuration(Duration.ofSeconds(300))
                .build());

        CircuitBreaker circuitBreaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .failureRateThreshold(p.getCbFailureRate())
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(30))
                .waitDurationInOpenState(Duration.ofSeconds(p.getCbWaitOpenSec()))
                .permittedNumberOfCallsInHalfOpenState(5)
                .recordException(ResilienceExceptions::isProviderFault)
                .build());

        Retry retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(p.getRetryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofSeconds(1), 2.0, 0.5))
                .retryOnException(ResilienceExceptions::isRetryable)
                .build());

        // 流式三超时字段 embedding 用不到，填交互池同值占位（record 结构复用，不另起类型）
        return new ResilienceBundle(timeLimiter, bulkhead, circuitBreaker, retry,
                Duration.ofSeconds(p.getFirstTokenTimeoutSec()),
                Duration.ofSeconds(p.getTokenGapTimeoutSec()),
                Duration.ofSeconds(p.getStreamMaxDurationSec()),
                p.getRetryMaxAttempts());
    }
```

`ResilientEmbeddingModel.java`（新建）：

```java
package com.hify.provider.service.resilience;

import io.github.resilience4j.decorators.Decorators;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Spring AI EmbeddingModel 装饰器：call 走批量池四件套
 * Retry(CircuitBreaker(Bulkhead(TimeLimiter(真实调用))))，结构与 ResilientChatModel.call 同款。
 * embedding 幂等无流式，不需要 stream 五件套。
 */
public class ResilientEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final ResilienceBundle bundle;
    private final ExecutorService executor;

    public ResilientEmbeddingModel(EmbeddingModel delegate, ResilienceBundle bundle, ExecutorService executor) {
        this.delegate = delegate;
        this.bundle = bundle;
        this.executor = executor;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        Supplier<EmbeddingResponse> timed = () -> callWithTimeout(request);
        Supplier<EmbeddingResponse> decorated = Decorators.ofSupplier(timed)
                .withBulkhead(bundle.bulkhead())
                .withCircuitBreaker(bundle.circuitBreaker())
                .withRetry(bundle.retry())
                .decorate();
        try {
            return decorated.get();
        } catch (Throwable t) {
            throw ResilienceExceptions.toBizException(t);
        }
    }

    private EmbeddingResponse callWithTimeout(EmbeddingRequest request) {
        try {
            return bundle.timeLimiter().executeFutureSupplier(
                    () -> executor.submit(() -> delegate.call(request)));
        } catch (Throwable t) {
            // 以原异常类型重抛，供 Retry/CircuitBreaker 谓词识别（同 ResilientChatModel）
            throw ResilienceExceptions.sneaky(t);
        }
    }

    @Override
    public float[] embed(Document document) {
        // 接口要求的 Document 版入口；转字符串走默认 embed(String) → call，复用韧性链
        return embed(document.getText());
    }
}
```

`ResilienceRegistry.java`：加字段、record、方法，并扩 invalidate：

```java
    private final Map<Long, ResilienceBundle> batchBundles = new ConcurrentHashMap<>();
    private final Map<Long, CachedEmbedding> embeddings = new ConcurrentHashMap<>();

    private record CachedEmbedding(Long providerId, EmbeddingModel model) {}
```

```java
    /** 取一个「可用」embedding 模型（批量池韧性装饰，命中缓存直接返回）；不可用抛 12002。 */
    public EmbeddingModel getEmbeddingModel(Long modelId) {
        CachedEmbedding cached = embeddings.get(modelId);
        if (cached != null) {
            return cached.model();
        }
        AiModel model = modelMapper.selectById(modelId);
        if (model == null || !ModelType.EMBEDDING.value().equals(model.getType())
                || !ProviderStatus.ENABLED.value().equals(model.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ResilienceBundle bundle = batchBundles.computeIfAbsent(provider.getId(),
                k -> ResilienceBundle.buildBatch(provider));
        EmbeddingModel resilient = new ResilientEmbeddingModel(
                factory.buildEmbeddingModel(provider, model), bundle, executor);
        embeddings.put(modelId, new CachedEmbedding(provider.getId(), resilient));
        return resilient;
    }
```

`invalidate(Long providerId)` 方法体追加两行；`invalidateModel(Long modelId)` 追加一行：

```java
        batchBundles.remove(providerId);
        embeddings.values().removeIf(c -> c.providerId().equals(providerId));
```

```java
        embeddings.remove(modelId);
```

新 import：`org.springframework.ai.embedding.EmbeddingModel`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ChatClientFactoryTest,ResilienceRegistryTest,ProviderErrorTest,ResilienceBundleTest'`
Expected: PASS（若 ProviderErrorTest 枚举计数断言失败，把预期数量 +2 并补 12005/12006 断言行——照文件既有断言风格）

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/provider/ server/src/test/java/com/hify/provider/
git commit -m "feat(provider): EmbeddingModel 工厂+批量池韧性装饰+Registry 缓存（TDD）"
```

---

### Task 3: provider 设置服务 + Facade.getEmbeddingModel + admin 接口

**Files:**
- Create: `server/src/main/java/com/hify/provider/service/EmbeddingSettingService.java`
- Create: `server/src/main/java/com/hify/provider/dto/EmbeddingSettingResponse.java`
- Create: `server/src/main/java/com/hify/provider/dto/UpdateEmbeddingSettingRequest.java`
- Create: `server/src/main/java/com/hify/provider/controller/AdminSettingController.java`
- Modify: `server/src/main/java/com/hify/provider/api/ProviderFacade.java`（加方法）
- Modify: `server/src/main/java/com/hify/provider/service/ProviderFacadeImpl.java`（实现）
- Test: `server/src/test/java/com/hify/provider/service/EmbeddingSettingServiceTest.java`（新建）
- Test: `server/src/test/java/com/hify/provider/controller/AdminSettingControllerTest.java`（新建）
- Test: `server/src/test/java/com/hify/provider/service/ProviderFacadeImplTest.java`（增量）

**Interfaces:**
- Consumes: Task 2 的 `ResilienceRegistry.getEmbeddingModel(Long)`、`ChatClientFactory.EMBEDDING_DIMENSION`、Task 1 的 `SystemSettingMapper`。
- Produces: `ProviderFacade.getEmbeddingModel()`（无参，未配置抛 12006）、`EmbeddingSettingService.get()/save(Long)/currentModelId()`、`GET/PUT /api/v1/admin/provider/settings/embedding-model`。knowledge（Task 4/5）只消费 `ProviderFacade.getEmbeddingModel()`。

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/provider/service/EmbeddingSettingServiceTest.java`（新建）：

```java
package com.hify.provider.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.entity.SystemSetting;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.mapper.SystemSettingMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingSettingServiceTest {

    private SystemSettingMapper settingMapper;
    private AiModelMapper modelMapper;
    private ModelProviderMapper providerMapper;
    private ResilienceRegistry registry;
    private EmbeddingModel embeddingModel;
    private EmbeddingSettingService service;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(SystemSetting.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SystemSetting.class);
        }
        settingMapper = mock(SystemSettingMapper.class);
        modelMapper = mock(AiModelMapper.class);
        providerMapper = mock(ModelProviderMapper.class);
        registry = mock(ResilienceRegistry.class);
        embeddingModel = mock(EmbeddingModel.class);
        service = new EmbeddingSettingService(settingMapper, modelMapper, providerMapper, registry);
    }

    private AiModel usableEmbeddingModel() {
        AiModel m = new AiModel();
        m.setId(6L);
        m.setProviderId(1L);
        m.setType("embedding");
        m.setName("千问 v4");
        m.setStatus("enabled");
        return m;
    }

    private ModelProvider enabledProvider() {
        ModelProvider p = new ModelProvider();
        p.setId(1L);
        p.setStatus("enabled");
        return p;
    }

    private SystemSetting settingRow(String value) {
        SystemSetting s = new SystemSetting();
        s.setId(1L);
        s.setSettingKey("embedding_model_id");
        s.setSettingValue(value);
        return s;
    }

    @Test
    void get_未配置_两字段均null() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        EmbeddingSettingResponse resp = service.get();
        assertNull(resp.modelId());
        assertNull(resp.modelName());
    }

    @Test
    void get_已配置_回显id与模型名() {
        when(settingMapper.selectOne(any())).thenReturn(settingRow("6"));
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        EmbeddingSettingResponse resp = service.get();
        assertEquals(6L, resp.modelId());
        assertEquals("千问 v4", resp.modelName());
    }

    @Test
    void save_模型不存在_NOT_FOUND() {
        when(modelMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.save(99L));
        assertEquals(CommonError.NOT_FOUND, ex.errorCode());
    }

    @Test
    void save_type是chat_MODEL_NOT_USABLE() {
        AiModel chat = usableEmbeddingModel();
        chat.setType("chat");
        when(modelMapper.selectById(6L)).thenReturn(chat);
        BizException ex = assertThrows(BizException.class, () -> service.save(6L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void save_供应商停用_MODEL_NOT_USABLE() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        ModelProvider disabled = enabledProvider();
        disabled.setStatus("disabled");
        when(providerMapper.selectById(1L)).thenReturn(disabled);
        BizException ex = assertThrows(BizException.class, () -> service.save(6L));
        assertEquals(ProviderError.MODEL_NOT_USABLE, ex.errorCode());
    }

    @Test
    void save_探测维度768_拒绝且message带维度() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[768]);

        BizException ex = assertThrows(BizException.class, () -> service.save(6L));

        assertEquals(ProviderError.EMBEDDING_DIMENSION_MISMATCH, ex.errorCode());
        assertTrue(ex.getMessage().contains("768"));
        verify(settingMapper, never()).insert(any(SystemSetting.class));
    }

    @Test
    void save_探测1024维_首次insert() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(settingMapper.selectOne(any())).thenReturn(null);
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);

        service.save(6L);

        verify(settingMapper).insert(captor.capture());
        assertEquals("embedding_model_id", captor.getValue().getSettingKey());
        assertEquals("6", captor.getValue().getSettingValue());
    }

    @Test
    void save_已有设置_update覆盖() {
        when(modelMapper.selectById(6L)).thenReturn(usableEmbeddingModel());
        when(providerMapper.selectById(1L)).thenReturn(enabledProvider());
        when(registry.getEmbeddingModel(6L)).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[1024]);
        when(settingMapper.selectOne(any())).thenReturn(settingRow("3"));
        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);

        service.save(6L);

        verify(settingMapper).updateById(captor.capture());
        assertEquals("6", captor.getValue().getSettingValue());
        verify(settingMapper, never()).insert(any(SystemSetting.class));
    }

    @Test
    void currentModelId_未配置返回null_已配置返回Long() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        assertNull(service.currentModelId());
        when(settingMapper.selectOne(any())).thenReturn(settingRow("6"));
        assertEquals(6L, service.currentModelId());
    }
}
```

`ProviderFacadeImplTest.java` 增量（构造器加 `EmbeddingSettingService` mock 后）：

```java
    @Test
    void getEmbeddingModel_未配置_抛12006() {
        when(embeddingSettingService.currentModelId()).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> facade.getEmbeddingModel());
        assertEquals(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED, ex.errorCode());
    }

    @Test
    void getEmbeddingModel_已配置_委托Registry() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(embeddingSettingService.currentModelId()).thenReturn(6L);
        when(resilienceRegistry.getEmbeddingModel(6L)).thenReturn(model);
        assertSame(model, facade.getEmbeddingModel());
    }
```

`server/src/test/java/com/hify/provider/controller/AdminSettingControllerTest.java`（新建，照 AdminModelControllerTest 的 @WebMvcTest 模板）：

```java
package com.hify.provider.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.service.EmbeddingSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSettingController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminSettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private EmbeddingSettingService embeddingSettingService;

    private String adminToken() {
        return jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
    }

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
    }

    @Test
    void 查设置_admin_200且Long为字符串() throws Exception {
        when(embeddingSettingService.get()).thenReturn(new EmbeddingSettingResponse(6L, "千问 v4"));
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("6"))
                .andExpect(jsonPath("$.data.modelName").value("千问 v4"));
    }

    @Test
    void 查设置_未配置_modelId为null仍200() throws Exception {
        when(embeddingSettingService.get()).thenReturn(new EmbeddingSettingResponse(null, null));
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").isEmpty());
    }

    @Test
    void 存设置_admin_200() throws Exception {
        when(embeddingSettingService.save(6L)).thenReturn(new EmbeddingSettingResponse(6L, "千问 v4"));
        mockMvc.perform(put("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\": 6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("6"));
    }

    @Test
    void 存设置_缺modelId_400参数校验() throws Exception {
        mockMvc.perform(put("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void member调admin接口_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/provider/settings/embedding-model")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='EmbeddingSettingServiceTest,AdminSettingControllerTest,ProviderFacadeImplTest'`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

`server/src/main/java/com/hify/provider/dto/EmbeddingSettingResponse.java`：

```java
package com.hify.provider.dto;

/** 系统 embedding 模型设置回显。未配置时两字段均为 null（空设置是成功不是错误）。 */
public record EmbeddingSettingResponse(Long modelId, String modelName) {
}
```

`server/src/main/java/com/hify/provider/dto/UpdateEmbeddingSettingRequest.java`：

```java
package com.hify.provider.dto;

import jakarta.validation.constraints.NotNull;

/** 设置系统 embedding 模型（PUT 全量，唯一字段）。 */
public record UpdateEmbeddingSettingRequest(@NotNull(message = "modelId 不能为空") Long modelId) {
}
```

`server/src/main/java/com/hify/provider/service/EmbeddingSettingService.java`：

```java
package com.hify.provider.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.constant.ModelType;
import com.hify.provider.constant.ProviderError;
import com.hify.provider.constant.ProviderStatus;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.entity.AiModel;
import com.hify.provider.entity.ModelProvider;
import com.hify.provider.entity.SystemSetting;
import com.hify.provider.mapper.AiModelMapper;
import com.hify.provider.mapper.ModelProviderMapper;
import com.hify.provider.mapper.SystemSettingMapper;
import com.hify.provider.service.resilience.ResilienceRegistry;
import org.springframework.stereotype.Service;

/**
 * 系统级 embedding 模型设置（system_setting 键 embedding_model_id）。
 * save 含一次真实外部调用（探测维度），因此**整个类不加 @Transactional**（事务内禁外部 IO）；
 * 落库是单行 UPSERT，无需事务。
 */
@Service
public class EmbeddingSettingService {

    public static final String KEY_EMBEDDING_MODEL_ID = "embedding_model_id";
    private static final String PROBE_TEXT = "hify embedding probe";

    private final SystemSettingMapper settingMapper;
    private final AiModelMapper modelMapper;
    private final ModelProviderMapper providerMapper;
    private final ResilienceRegistry registry;

    public EmbeddingSettingService(SystemSettingMapper settingMapper, AiModelMapper modelMapper,
                                   ModelProviderMapper providerMapper, ResilienceRegistry registry) {
        this.settingMapper = settingMapper;
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.registry = registry;
    }

    /** 回显当前设置；未配置时两字段均 null。模型名不管启停都回显（展示语义）。 */
    public EmbeddingSettingResponse get() {
        Long modelId = currentModelId();
        if (modelId == null) {
            return new EmbeddingSettingResponse(null, null);
        }
        AiModel model = modelMapper.selectById(modelId);
        return new EmbeddingSettingResponse(modelId, model == null ? null : model.getName());
    }

    /** 当前设置的 embedding 模型 id；未配置返回 null。供 ProviderFacade.getEmbeddingModel() 用。 */
    public Long currentModelId() {
        SystemSetting s = selectRow();
        return (s == null || s.getSettingValue() == null) ? null : Long.valueOf(s.getSettingValue());
    }

    /**
     * 保存设置：可用性校验（不存在 10005 / 非 embedding 或停用 12002）→ 事务外探测一次
     * （维度 ≠1024 → 12005；网络失败由韧性链映射 12003/12004）→ UPSERT。
     */
    public EmbeddingSettingResponse save(Long modelId) {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BizException(CommonError.NOT_FOUND, "模型不存在");
        }
        if (!ModelType.EMBEDDING.value().equals(model.getType())
                || !ProviderStatus.ENABLED.value().equals(model.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        ModelProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null || !ProviderStatus.ENABLED.value().equals(provider.getStatus())) {
            throw new BizException(ProviderError.MODEL_NOT_USABLE);
        }
        float[] vector = registry.getEmbeddingModel(modelId).embed(PROBE_TEXT); // 真实外部调用（批量池韧性链内）
        if (vector.length != ChatClientFactory.EMBEDDING_DIMENSION) {
            throw new BizException(ProviderError.EMBEDDING_DIMENSION_MISMATCH,
                    "该模型输出 " + vector.length + " 维，需 " + ChatClientFactory.EMBEDDING_DIMENSION
                            + " 维，请换支持 1024 维输出的模型");
        }
        upsert(modelId);
        return get();
    }

    private SystemSetting selectRow() {
        // @TableLogic 自动过滤 deleted（wrapper 查询享受）
        return settingMapper.selectOne(new LambdaQueryWrapper<SystemSetting>()
                .eq(SystemSetting::getSettingKey, KEY_EMBEDDING_MODEL_ID));
    }

    private void upsert(Long modelId) {
        SystemSetting existing = selectRow();
        if (existing == null) {
            SystemSetting s = new SystemSetting();
            s.setSettingKey(KEY_EMBEDDING_MODEL_ID);
            s.setSettingValue(String.valueOf(modelId));
            settingMapper.insert(s);
        } else {
            existing.setSettingValue(String.valueOf(modelId));
            settingMapper.updateById(existing);
        }
    }
}
```

`ProviderFacade.java`：接口追加方法（import `org.springframework.ai.embedding.EmbeddingModel`），并把类 Javadoc 里「C2 将新增 getEmbeddingModel」句改为「getEmbeddingModel 已于 K3 落地」：

```java
    /**
     * 取系统设置的 embedding 模型（批量池韧性装饰）。未配置抛
     * {@code BizException(EMBEDDING_MODEL_NOT_CONFIGURED)}；设置指向的模型已停用/被删抛
     * {@code BizException(MODEL_NOT_USABLE)}。供 knowledge 向量化流水线调用。
     */
    EmbeddingModel getEmbeddingModel();
```

`ProviderFacadeImpl.java`：构造器加 `EmbeddingSettingService embeddingSettingService` 依赖（字段+赋值），实现：

```java
    @Override
    public EmbeddingModel getEmbeddingModel() {
        Long modelId = embeddingSettingService.currentModelId();
        if (modelId == null) {
            throw new BizException(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED);
        }
        return resilienceRegistry.getEmbeddingModel(modelId);
    }
```

新 import：`com.hify.common.exception.BizException`、`com.hify.provider.constant.ProviderError`、`org.springframework.ai.embedding.EmbeddingModel`。

`server/src/main/java/com/hify/provider/controller/AdminSettingController.java`：

```java
package com.hify.provider.controller;

import com.hify.common.Result;
import com.hify.provider.dto.EmbeddingSettingResponse;
import com.hify.provider.dto.UpdateEmbeddingSettingRequest;
import com.hify.provider.service.EmbeddingSettingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin 系统设置接口（仅 Admin）。settings 集合 + 键名作标识，PUT 全量更新单键
 * （api-standards：不用 PATCH，单值资源 PUT 全量语义成立）。
 */
@RestController
@RequestMapping("/api/v1/admin/provider")
public class AdminSettingController {

    private final EmbeddingSettingService embeddingSettingService;

    public AdminSettingController(EmbeddingSettingService embeddingSettingService) {
        this.embeddingSettingService = embeddingSettingService;
    }

    @GetMapping("/settings/embedding-model")
    public Result<EmbeddingSettingResponse> getEmbeddingModel() {
        return Result.ok(embeddingSettingService.get());
    }

    /** 保存设置：service 内含一次真实探测调用（验 Key/网络/1024 维），失败按 12002/12003/12005 报。 */
    @PutMapping("/settings/embedding-model")
    public Result<EmbeddingSettingResponse> putEmbeddingModel(
            @Valid @RequestBody UpdateEmbeddingSettingRequest request) {
        return Result.ok(embeddingSettingService.save(request.modelId()));
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='EmbeddingSettingServiceTest,AdminSettingControllerTest,ProviderFacadeImplTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/provider/ server/src/test/java/com/hify/provider/
git commit -m "feat(provider): embedding 系统设置（保存探测验维度）+ Facade.getEmbeddingModel + admin 接口（TDD）"
```

---

### Task 4: knowledge 异步流水线 — 上传改造 + DocumentProcessJob + 重试 + 启动自愈

**Files:**
- Modify: `server/src/main/java/com/hify/knowledge/constant/KnowledgeError.java`（加 15002/15003）
- Modify: `server/src/main/java/com/hify/knowledge/mapper/KbDocumentMapper.java`（加 5 个注解 SQL）
- Modify: `server/src/main/java/com/hify/knowledge/mapper/KbChunkMapper.java`（加 3 个注解 SQL）
- Create: `server/src/main/java/com/hify/knowledge/service/DocumentUploadedEvent.java`
- Create: `server/src/main/java/com/hify/knowledge/service/DocumentProcessStore.java`
- Create: `server/src/main/java/com/hify/knowledge/service/DocumentProcessJob.java`
- Create: `server/src/main/java/com/hify/knowledge/service/DocumentStartupHealer.java`
- Modify: `server/src/main/java/com/hify/knowledge/service/DocumentService.java`（upload 异步化 + retryDocument）
- Modify: `server/src/main/java/com/hify/knowledge/dto/DocumentResponse.java`（加 errorMessage）
- Modify: `server/src/main/java/com/hify/knowledge/controller/DocumentController.java`（加 retry 路由）
- Test: `server/src/test/java/com/hify/knowledge/service/DocumentProcessJobTest.java`（新建）
- Test: `server/src/test/java/com/hify/knowledge/service/DocumentServiceTest.java`（改造）
- Test: `server/src/test/java/com/hify/knowledge/controller/DocumentControllerTest.java`（增量）

**Interfaces:**
- Consumes: Task 3 的 `ProviderFacade.getEmbeddingModel()`；K2 已有 `TextChunker.split(String,int,int)`、`DatasetService.assertCanModify(Dataset, CurrentUser)`。
- Produces: `DocumentProcessJob.runOnce(Long)`（同步流水线，Task 5 重嵌复用）、`DocumentProcessJob.processRetry(Long)`（@Async）、`KbDocumentMapper.claimStatus/claimForReembed/markReady/markFailed/failZombies/selectReembedTargetIds`、`KbChunkMapper.selectUnembedded/updateEmbedding/clearAllEmbeddings`、`DocumentService.retryDocument(Long, CurrentUser)`、`POST /api/v1/knowledge/documents/{id}/retry`、`DocumentResponse` 新签名（末位加 `String errorMessage`）。

- [ ] **Step 1: 写失败测试（DocumentProcessJobTest 新建）**

`server/src/test/java/com/hify/knowledge/service/DocumentProcessJobTest.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.hify.common.exception.BizException;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import com.hify.provider.api.ProviderFacade;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentProcessJobTest {

    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private DocumentProcessStore store;
    private ProviderFacade providerFacade;
    private EmbeddingModel embeddingModel;
    private ReembedGate gate;
    private DocumentProcessJob job;
    private MockedStatic<Db> dbMock;

    @BeforeEach
    void setUp() {
        initTableInfo(KbDocument.class);
        initTableInfo(KbChunk.class);
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        store = mock(DocumentProcessStore.class);
        providerFacade = mock(ProviderFacade.class);
        embeddingModel = mock(EmbeddingModel.class);
        gate = mock(ReembedGate.class);
        // batchSize=2：分批边界好断言
        job = new DocumentProcessJob(documentMapper, chunkMapper, store, providerFacade, gate, 2);
        dbMock = mockStatic(Db.class);
        dbMock.when(() -> Db.saveBatch(anyList(), anyInt())).thenReturn(true);
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), entityClass);
        }
    }

    @AfterEach
    void tearDown() {
        dbMock.close();
    }

    /** processing 态、内容 250 字符（chunkSize=100/overlap=10 → 3 段）的文档。 */
    private KbDocument processingDoc() {
        KbDocument doc = new KbDocument();
        doc.setId(20L);
        doc.setDatasetId(10L);
        doc.setName("faq.txt");
        doc.setStatus("processing");
        doc.setChunkSize(100);
        doc.setChunkOverlap(10);
        doc.setContent("x".repeat(250).getBytes(StandardCharsets.UTF_8));
        return doc;
    }

    private KbChunk chunk(long id) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setContent("段" + id);
        return c;
    }

    @Test
    void 上传事件_claim失败_直接返回不处理() {
        when(documentMapper.claimStatus(20L, "pending")).thenReturn(0);
        job.onDocumentUploaded(new DocumentUploadedEvent(20L));
        verify(documentMapper, never()).selectById(anyLong());
    }

    @Test
    void runOnce_分段不存在_提取分段后嵌入并ready() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(0L);
        // saveChunks 之后 selectUnembedded 返回 3 个待嵌分段
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList())).thenAnswer(inv ->
                ((List<String>) inv.getArgument(0)).stream().map(s -> new float[1024]).toList());

        job.runOnce(20L);

        verify(store).saveChunks(any(KbDocument.class), eq(List.of(
                "x".repeat(100), "x".repeat(100), "x".repeat(70))));
        // batchSize=2 → 3 段分两批：2 + 1
        verify(embeddingModel, times(2)).embed(anyList());
        verify(store, times(2)).writeEmbeddings(anyList(), anyList());
        verify(documentMapper).markReady(20L);
        verify(documentMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void runOnce_分段已存在_跳过提取只补空向量段() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(3))); // 只剩 1 段没向量
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));

        job.runOnce(20L);

        verify(store, never()).saveChunks(any(), anyList());
        verify(embeddingModel, times(1)).embed(anyList());
        verify(documentMapper).markReady(20L);
    }

    @Test
    void runOnce_全部已嵌_不调模型直接ready() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of());

        job.runOnce(20L);

        verify(providerFacade, never()).getEmbeddingModel();
        verify(documentMapper).markReady(20L);
    }

    @Test
    void runOnce_内容全空白_failed且原因是15001文案() {
        KbDocument blank = processingDoc();
        blank.setContent("   \n\t ".getBytes(StandardCharsets.UTF_8));
        when(documentMapper.selectById(20L)).thenReturn(blank);
        when(chunkMapper.selectCount(any())).thenReturn(0L);

        job.runOnce(20L);

        verify(documentMapper).markFailed(eq(20L), eq("文档内容为空或无法解析"));
        verify(documentMapper, never()).markReady(anyLong());
    }

    @Test
    void runOnce_未配置embedding模型_failed且原因可读() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1)));
        when(providerFacade.getEmbeddingModel()).thenThrow(new BizException(
                com.hify.provider.constant.ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED));

        job.runOnce(20L);

        verify(documentMapper).markFailed(eq(20L),
                eq("系统未配置 embedding 模型，请联系管理员在系统设置中配置"));
    }

    @Test
    void runOnce_批中途失败_已写批保留且failed() {
        when(documentMapper.selectById(20L)).thenReturn(processingDoc());
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(20L)).thenReturn(List.of(chunk(1), chunk(2), chunk(3)));
        when(providerFacade.getEmbeddingModel()).thenReturn(embeddingModel);
        when(embeddingModel.embed(anyList()))
                .thenReturn(List.of(new float[1024], new float[1024]))
                .thenThrow(new RuntimeException("boom"));

        job.runOnce(20L);

        verify(store, times(1)).writeEmbeddings(anyList(), anyList()); // 第一批已落
        verify(documentMapper).markFailed(eq(20L), eq("处理失败，请重试"));
    }

    @Test
    void runOnce_文档已删_静默退出() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        job.runOnce(99L);
        verify(documentMapper, never()).markReady(anyLong());
        verify(documentMapper, never()).markFailed(anyLong(), anyString());
    }

    @Test
    void reembedAll_清空向量后逐文档处理_单个失败不中断_收尾释放闸() {
        when(documentMapper.selectReembedTargetIds()).thenReturn(List.of(21L, 22L));
        when(documentMapper.claimForReembed(21L)).thenReturn(1);
        when(documentMapper.claimForReembed(22L)).thenReturn(1);
        // 21 号：selectById 抛异常（模拟意外失败被 runOnce 捕获置 failed）；22 号正常空文档流转
        when(documentMapper.selectById(21L)).thenThrow(new RuntimeException("boom"));
        KbDocument ok = processingDoc();
        ok.setId(22L);
        when(documentMapper.selectById(22L)).thenReturn(ok);
        when(chunkMapper.selectCount(any())).thenReturn(3L);
        when(chunkMapper.selectUnembedded(22L)).thenReturn(List.of());

        job.reembedAll();

        verify(chunkMapper).clearAllEmbeddings();
        verify(documentMapper).markFailed(eq(21L), anyString());
        verify(documentMapper).markReady(22L);
        verify(gate).finish();
    }

    @Test
    void reembedAll_claim为0的文档跳过() {
        when(documentMapper.selectReembedTargetIds()).thenReturn(List.of(21L));
        when(documentMapper.claimForReembed(21L)).thenReturn(0);

        job.reembedAll();

        verify(documentMapper, never()).selectById(21L);
        verify(gate).finish();
    }
}
```

注：`IntStream` import 如未用到删除；`ReembedGate` 在本 Task 一并创建（Task 5 的 ReembedService 才消费其闸语义，但 job.reembedAll 的 finally 需要它）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessJobTest'`
Expected: 编译失败（DocumentProcessJob/DocumentProcessStore/DocumentUploadedEvent/ReembedGate 不存在）

- [ ] **Step 3: 实现（错误码 / mapper / 事件 / store / job / healer）**

`KnowledgeError.java`：现有两枚后追加（前一条末尾改逗号）：

```java
    /** 文档当前状态不允许该操作（如对非 failed 文档点重试）。 */
    DOCUMENT_STATE_CONFLICT(15002, HttpStatus.CONFLICT, "文档当前状态不允许该操作"),
    /** 全量重嵌入任务已在进行中（进程内互斥闸）。 */
    REEMBED_IN_PROGRESS(15003, HttpStatus.CONFLICT, "重嵌入任务已在进行中，请稍后再试");
```

`KbDocumentMapper.java`（整文件替换）：

```java
package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_document 表访问。注解 SQL 不享受 @TableLogic 自动过滤，必须手写 deleted = false；
 * 状态流转全部用条件更新（where status=…）做乐观闸门，防并发重复处理。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /** 认领：from 态 → processing（顺带清 error_message）。返回 0 = 状态已被别人改走。 */
    @Update("update kb_document set status = 'processing', error_message = null, update_time = now() "
            + "where id = #{id} and status = #{from} and deleted = false")
    int claimStatus(@Param("id") Long id, @Param("from") String from);

    /** 重嵌认领：仅终态（ready/failed）可进；pending/processing 的自有流水线在跑，跳过。 */
    @Update("update kb_document set status = 'processing', error_message = null, update_time = now() "
            + "where id = #{id} and status in ('ready', 'failed') and deleted = false")
    int claimForReembed(@Param("id") Long id);

    @Update("update kb_document set status = 'ready', error_message = null, update_time = now() where id = #{id}")
    int markReady(@Param("id") Long id);

    @Update("update kb_document set status = 'failed', error_message = #{msg}, update_time = now() where id = #{id}")
    int markFailed(@Param("id") Long id, @Param("msg") String msg);

    /** 启动自愈：残留非终态僵尸置 failed（只重置状态，不自动重跑——花钱动作显式触发）。 */
    @Update("update kb_document set status = 'failed', error_message = '服务重启，处理中断，请重试', "
            + "update_time = now() where status in ('pending', 'processing') and deleted = false")
    int failZombies();

    /** 全量重嵌目标：终态未删文档 id，按 id 序（顺序处理）。 */
    @Select("select id from kb_document where status in ('ready', 'failed') and deleted = false order by id")
    List<Long> selectReembedTargetIds();
}
```

`KbChunkMapper.java`（整文件替换）：

```java
package com.hify.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * kb_chunk 表访问。批量写走 Db.saveBatch（database-standards §2.1）。
 * embedding 列**不映射进实体**（K4 检索前无读需求；float[] 映射需 TypeHandler，K3 不引），
 * 读写全走下方注解 SQL；注解 SQL 必须手写 deleted = false。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /** 待嵌分段（embedding 为空 = 断点续嵌选段依据）。只取 id+content，不拖全行。 */
    @Select("select id, content from kb_chunk where document_id = #{documentId} "
            + "and deleted = false and embedding is null order by position")
    List<KbChunk> selectUnembedded(@Param("documentId") Long documentId);

    /** 写单段向量：入参是 pgvector 字面量 '[0.1,0.2,…]'，cast 交给 PG。 */
    @Update("update kb_chunk set embedding = #{vector}::vector, update_time = now() where id = #{id}")
    int updateEmbedding(@Param("id") Long id, @Param("vector") String vector);

    /** 全量重嵌第一步：清空全部未删分段的向量。 */
    @Update("update kb_chunk set embedding = null, update_time = now() where deleted = false")
    int clearAllEmbeddings();
}
```

`server/src/main/java/com/hify/knowledge/service/DocumentUploadedEvent.java`：

```java
package com.hify.knowledge.service;

/**
 * 文档已上传（上传事务提交后由 DocumentProcessJob 接手异步处理）。
 * 模块内事件，不进 api/event（不跨模块，code-organization 的 api/event 只放跨模块通知）。
 */
public record DocumentUploadedEvent(Long documentId) {
}
```

`server/src/main/java/com/hify/knowledge/service/ReembedGate.java`（新建，Task 5 的 ReembedService 也消费）：

```java
package com.hify.knowledge.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全量重嵌入的进程内互斥闸（单机单实例部署，AtomicBoolean 足够）。
 * 独立成 bean 是为了断开 ReembedService ↔ DocumentProcessJob 的循环依赖：
 * service 开闸后派发 job，job 收尾关闸，两者都只依赖本类。
 */
@Component
public class ReembedGate {

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 尝试开闸；已在跑返回 false。 */
    public boolean tryStart() {
        return running.compareAndSet(false, true);
    }

    public void finish() {
        running.set(false);
    }
}
```

`server/src/main/java/com/hify/knowledge/service/DocumentProcessStore.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 流水线的事务写库操作。独立成类是因为 Spring 事务基于代理，DocumentProcessJob 内部自调用
 * 不过代理、@Transactional 会失效；拆出来经 bean 边界调用才生效。
 * 两个方法都只做本地写库——外部 IO（嵌入 API）在调用方事务外完成。
 */
@Service
public class DocumentProcessStore {

    private static final int BATCH_SIZE = 1000;

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;

    public DocumentProcessStore(KbDocumentMapper documentMapper, KbChunkMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    /** 提取分段落库：全部分段 + 文档 chunk_count 一个事务（要么都在要么都不在）。 */
    @Transactional
    public void saveChunks(KbDocument doc, List<String> pieces) {
        List<KbChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(doc.getId());
            chunk.setDatasetId(doc.getDatasetId());
            chunk.setPosition(i + 1);
            chunk.setContent(pieces.get(i));
            chunks.add(chunk);
        }
        Db.saveBatch(chunks, BATCH_SIZE);
        KbDocument patch = new KbDocument();
        patch.setId(doc.getId());
        patch.setChunkCount(pieces.size());
        documentMapper.updateById(patch); // MP 只更新非 null 字段 → 只动 chunk_count
    }

    /** 一批向量一个小事务写入（调用方已在事务外拿到向量，本方法零外部 IO）。 */
    @Transactional
    public void writeEmbeddings(List<KbChunk> batch, List<float[]> vectors) {
        for (int i = 0; i < batch.size(); i++) {
            chunkMapper.updateEmbedding(batch.get(i).getId(), vectorLiteral(vectors.get(i)));
        }
    }

    /** float[] → pgvector 字面量 '[0.1,0.2,…]'。 */
    static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
```

`server/src/main/java/com/hify/knowledge/service/DocumentProcessJob.java`：

```java
package com.hify.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.knowledge.entity.KbChunk;
import com.hify.knowledge.entity.KbDocument;
import com.hify.knowledge.mapper.KbChunkMapper;
import com.hify.knowledge.mapper.KbDocumentMapper;
import com.hify.provider.api.ProviderFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档处理流水线（提取→分段→分批嵌入→ready/failed）。跑在 infra AsyncConfig 的虚拟线程上。
 * 本类不加 @Transactional（嵌入是外部 IO）；事务写库收口在 DocumentProcessStore。
 * 状态约定：进入 runOnce 时文档必须已被条件更新置为 processing（三条路径的闸门各自负责：
 * 上传路径在 onDocumentUploaded 认领，重试路径在 DocumentService.retryDocument，重嵌路径在 reembedAll）。
 */
@Service
public class DocumentProcessJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessJob.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final DocumentProcessStore store;
    private final ProviderFacade providerFacade;
    private final ReembedGate gate;
    private final int batchSize;

    public DocumentProcessJob(KbDocumentMapper documentMapper,
                              KbChunkMapper chunkMapper,
                              DocumentProcessStore store,
                              ProviderFacade providerFacade,
                              ReembedGate gate,
                              @Value("${hify.knowledge.embedding-batch-size}") int batchSize) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.store = store;
        this.providerFacade = providerFacade;
        this.gate = gate;
        this.batchSize = batchSize;
    }

    /** 上传路径：事务提交后异步进入（AFTER_COMMIT 保证文档行对本线程可见）。 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        if (documentMapper.claimStatus(event.documentId(), "pending") == 0) {
            return; // 已被处理（并发兜底），直接退出
        }
        runOnce(event.documentId());
    }

    /** 重试路径：闸门（failed→processing）已由 DocumentService 完成，直接进流水线。 */
    @Async
    public void processRetry(Long documentId) {
        runOnce(documentId);
    }

    /**
     * 全量重嵌（含存量补嵌）：清空全部向量 → 逐文档顺序重跑（单文档失败已在 runOnce 内
     * 置 failed，不中断整体）。顺序单线程：批量池信号量之上再自限，防全库重嵌挤爆供应商 RPM。
     * 闸门 tryStart 由 ReembedService 完成，本方法只负责收尾 finish。
     */
    @Async
    public void reembedAll() {
        try {
            chunkMapper.clearAllEmbeddings();
            for (Long docId : documentMapper.selectReembedTargetIds()) {
                if (documentMapper.claimForReembed(docId) == 1) {
                    runOnce(docId);
                }
            }
        } finally {
            gate.finish();
        }
    }

    /** 流水线主体（同步执行；进入时文档已是 processing）。 */
    public void runOnce(Long documentId) {
        try {
            KbDocument doc = documentMapper.selectById(documentId);
            if (doc == null) {
                return; // 已删，静默退出
            }
            if (chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>()
                    .eq(KbChunk::getDocumentId, documentId)) == 0) {
                extractAndChunk(doc);
            }
            embedPending(documentId);
            documentMapper.markReady(documentId);
        } catch (BizException e) {
            documentMapper.markFailed(documentId, e.getMessage()); // BizException 的 message 面向用户可读
        } catch (Exception e) {
            log.error("文档处理失败 documentId={}", documentId, e);
            documentMapper.markFailed(documentId, "处理失败，请重试");
        }
    }

    private void extractAndChunk(KbDocument doc) {
        String text = new String(doc.getContent(), StandardCharsets.UTF_8);
        // 分段参数用文档行上记录的实际值（K2 落的底），重试/重嵌与首次口径一致
        List<String> pieces = TextChunker.split(text, doc.getChunkSize(), doc.getChunkOverlap());
        if (pieces.isEmpty()) {
            throw new BizException(KnowledgeError.DOCUMENT_CONTENT_EMPTY);
        }
        store.saveChunks(doc, pieces);
    }

    private void embedPending(Long documentId) {
        List<KbChunk> pending = chunkMapper.selectUnembedded(documentId);
        if (pending.isEmpty()) {
            return;
        }
        EmbeddingModel model = providerFacade.getEmbeddingModel(); // 未配置 → 12006 → 本文档 failed
        for (int from = 0; from < pending.size(); from += batchSize) {
            List<KbChunk> batch = pending.subList(from, Math.min(from + batchSize, pending.size()));
            // 先事务外调 API 拿向量，再开小事务写库（事务内零外部 IO 红线）
            List<float[]> vectors = model.embed(batch.stream().map(KbChunk::getContent).toList());
            store.writeEmbeddings(batch, vectors);
        }
    }
}
```

`server/src/main/java/com/hify/knowledge/service/DocumentStartupHealer.java`：

```java
package com.hify.knowledge.service;

import com.hify.knowledge.mapper.KbDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动自愈：把上次进程退出时残留的非终态（pending/processing）僵尸文档置为 failed。
 * 只重置状态不自动重跑（花钱动作显式触发，deployment.md 自愈定位）；用户在页面点重试恢复。
 */
@Component
public class DocumentStartupHealer {

    private static final Logger log = LoggerFactory.getLogger(DocumentStartupHealer.class);

    private final KbDocumentMapper documentMapper;

    public DocumentStartupHealer(KbDocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void healZombies() {
        int n = documentMapper.failZombies();
        if (n > 0) {
            log.warn("启动自愈：{} 个处理中断的文档已置为 failed，可在页面点重试恢复", n);
        }
    }
}
```

- [ ] **Step 4: 跑 DocumentProcessJobTest 确认通过**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='DocumentProcessJobTest'`
Expected: PASS（10 个测试）

- [ ] **Step 5: 改造 DocumentService / DocumentResponse / Controller（先改测试再改实现）**

`DocumentResponse.java` 记录末位加 `String errorMessage`：

```java
public record DocumentResponse(
        Long id, Long datasetId, String name, String fileType, Long fileSize,
        String status, Integer chunkCount, String errorMessage,
        OffsetDateTime createTime, OffsetDateTime updateTime) {}
```

（字段顺序：errorMessage 放 chunkCount 之后、时间之前；同步更新类上如有 Javadoc。）

`DocumentServiceTest.java` 改造（逐条）：
1. setUp：删除 `dbMock` 相关（`MockedStatic<Db>` 字段、`mockStatic`、`tearDown`、相关 import）——saveBatch 已移入 store；新增 `ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);` 与 `DocumentProcessJob processJob = mock(DocumentProcessJob.class);` 字段；构造 service 改为 `new DocumentService(datasetMapper, documentMapper, chunkMapper, publisher, processJob, 100, 10)`。
2. 删除测试 `上传_分段带document和dataset冗余id_position从1起`（分段逻辑已移到 job，DocumentProcessJobTest 覆盖）。
3. 删除测试 `上传_内容全空白_15001`（空白判定移入异步，job test 覆盖）。
4. `上传_成功_文档字段落库且分段批量写入` 改名 `上传_成功_落库pending并发布事件`，断言改为：

```java
    @Test
    void 上传_成功_落库pending并发布事件() {
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        when(documentMapper.insert(any(KbDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbDocument.class).setId(20L);
            return 1;
        });
        ArgumentCaptor<KbDocument> docCaptor = ArgumentCaptor.forClass(KbDocument.class);

        DocumentResponse resp = service.upload(10L, txt("faq.txt", "x".repeat(250)), owner);

        verify(documentMapper).insert(docCaptor.capture());
        KbDocument saved = docCaptor.getValue();
        assertEquals("pending", saved.getStatus());   // 异步流水线接手前的初始态
        assertEquals(0, saved.getChunkCount());
        assertEquals(100, saved.getChunkSize());      // 实际参数仍记录在行上（重试/重嵌用）
        assertEquals(10, saved.getChunkOverlap());
        assertEquals("pending", resp.status());
        verify(publisher).publishEvent(new DocumentUploadedEvent(20L));
    }
```

5. 追加重试三测：

```java
    @Test
    void 重试_failed文档_闸门通过并派发异步() {
        KbDocument failed = doc10();
        failed.setStatus("failed");
        when(documentMapper.selectById(20L)).thenReturn(failed);
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        when(documentMapper.claimStatus(20L, "failed")).thenReturn(1);

        service.retryDocument(20L, owner);

        verify(processJob).processRetry(20L);
    }

    @Test
    void 重试_状态不是failed_15002且不派发() {
        when(documentMapper.selectById(20L)).thenReturn(doc10()); // status=ready
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        when(documentMapper.claimStatus(20L, "failed")).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.retryDocument(20L, owner));

        assertEquals(KnowledgeError.DOCUMENT_STATE_CONFLICT, ex.errorCode());
        verify(processJob, never()).processRetry(anyLong());
    }

    @Test
    void 重试_非owner非admin_FORBIDDEN() {
        when(documentMapper.selectById(20L)).thenReturn(doc10());
        when(datasetMapper.selectById(10L)).thenReturn(ownedDataset());
        BizException ex = assertThrows(BizException.class, () -> service.retryDocument(20L, other));
        assertEquals(CommonError.FORBIDDEN, ex.errorCode());
        verify(documentMapper, never()).claimStatus(anyLong(), anyString());
    }
```

（新 import：`org.springframework.context.ApplicationEventPublisher`、`static org.mockito.ArgumentMatchers.anyLong`、`static org.mockito.ArgumentMatchers.anyString`。）

`DocumentService.java` 修改：
1. 构造器加两个依赖（字段+赋值+参数，插在 chunkMapper 之后）：`ApplicationEventPublisher eventPublisher`、`DocumentProcessJob processJob`。新 import：`org.springframework.context.ApplicationEventPublisher`。
2. `upload` 方法：删除「解码→TextChunker.split→空白判定→组装 chunks→Db.saveBatch」段落；`doc.setStatus("ready")` 改 `doc.setStatus("pending")`、`doc.setChunkCount(pieces.size())` 改 `doc.setChunkCount(0)`；`documentMapper.insert(doc)` 之后改为：

```java
        documentMapper.insert(doc);
        // 事务提交后 DocumentProcessJob 经 AFTER_COMMIT 监听接手（提取/分段/嵌入全在异步，
        // 事务内零外部 IO；50MB 大文件的解码也不再占 Web 请求与事务窗口）
        eventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId()));
        return toResponse(doc);
```

同时删除不再使用的 import（`Db`、`TextChunker` 相关如有）与类 Javadoc 中「同步提取」表述（改为「上传落库 pending，处理全在 DocumentProcessJob 异步流水线」）。`file.getBytes()` IOException 分支保留。
3. 追加方法：

```java
    /**
     * 重试 failed 文档：条件更新做闸门（仅 failed 可进，0 行 → 15002），过闸后派发异步流水线。
     * 不加 @Transactional：闸门是单条语句，把异步派发包进事务反而拉长窗口。
     */
    public void retryDocument(Long id, CurrentUser current) {
        KbDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(CommonError.NOT_FOUND, "文档不存在");
        }
        Dataset dataset = datasetMapper.selectById(doc.getDatasetId());
        if (dataset == null) {
            throw new BizException(CommonError.NOT_FOUND, "知识库不存在");
        }
        DatasetService.assertCanModify(dataset, current);
        if (documentMapper.claimStatus(id, "failed") == 0) {
            throw new BizException(KnowledgeError.DOCUMENT_STATE_CONFLICT);
        }
        processJob.processRetry(id);
    }
```

4. `toResponse` 加 errorMessage：

```java
    private DocumentResponse toResponse(KbDocument d) {
        return new DocumentResponse(d.getId(), d.getDatasetId(), d.getName(), d.getFileType(),
                d.getFileSize(), d.getStatus(), d.getChunkCount(), d.getErrorMessage(),
                d.getCreateTime(), d.getUpdateTime());
    }
```

`DocumentController.java` 追加：

```java
    /** 重试 failed 文档（断点续嵌：已嵌好的分段不重花钱）。owner/Admin 可点。 */
    @PostMapping("/documents/{id}/retry")
    public Result<Void> retryDocument(@PathVariable Long id) {
        documentService.retryDocument(id, CurrentUserHolder.current());
        return Result.ok(null);
    }
```

`DocumentControllerTest.java` 增量（照文件既有 @WebMvcTest 风格；同时修复既有测试中 `DocumentResponse` 构造调用——在 chunkCount 实参后补 `null`）：

```java
    @Test
    void 重试文档_登录用户_200() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents/20/retry")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk());
        verify(documentService).retryDocument(eq(20L), any());
    }

    @Test
    void 重试文档_未登录_401() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge/documents/20/retry"))
                .andExpect(status().isUnauthorized());
    }
```

（memberToken/verify/eq/any 等按文件既有 import；若文件用别的 token helper 名，照抄现名。）

- [ ] **Step 6: 全量回归**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: `BUILD SUCCESS`，0 Failures 0 Errors（总数 ≈ 340 + 新增；ModularityTests/LayerRulesTest 必须在列且过——knowledge→provider 走 Facade 白名单本就允许）

- [ ] **Step 7: Commit**

```bash
git add server/src/main/java/com/hify/knowledge/ server/src/test/java/com/hify/knowledge/
git commit -m "feat(knowledge): 上传流水线异步化+分批嵌入+重试+启动自愈（TDD）"
```

---

### Task 5: knowledge 全量重嵌入口 — ReembedService + AdminKnowledgeController

**Files:**
- Create: `server/src/main/java/com/hify/knowledge/service/ReembedService.java`
- Create: `server/src/main/java/com/hify/knowledge/controller/AdminKnowledgeController.java`
- Test: `server/src/test/java/com/hify/knowledge/service/ReembedServiceTest.java`（新建）
- Test: `server/src/test/java/com/hify/knowledge/controller/AdminKnowledgeControllerTest.java`（新建）

**Interfaces:**
- Consumes: Task 4 的 `ReembedGate.tryStart()/finish()`、`DocumentProcessJob.reembedAll()`；Task 3 的 `ProviderFacade.getEmbeddingModel()`。
- Produces: `ReembedService.start()`、`POST /api/v1/admin/knowledge/documents/reembed`。

- [ ] **Step 1: 写失败测试**

`server/src/test/java/com/hify/knowledge/service/ReembedServiceTest.java`：

```java
package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.provider.api.ProviderFacade;
import com.hify.provider.constant.ProviderError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReembedServiceTest {

    private ProviderFacade providerFacade;
    private ReembedGate gate;
    private DocumentProcessJob job;
    private ReembedService service;

    @BeforeEach
    void setUp() {
        providerFacade = mock(ProviderFacade.class);
        gate = mock(ReembedGate.class);
        job = mock(DocumentProcessJob.class);
        service = new ReembedService(providerFacade, gate, job);
    }

    @Test
    void 启动_未配置embedding模型_12006且不开闸() {
        when(providerFacade.getEmbeddingModel())
                .thenThrow(new BizException(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED));
        BizException ex = assertThrows(BizException.class, () -> service.start());
        assertEquals(ProviderError.EMBEDDING_MODEL_NOT_CONFIGURED, ex.errorCode());
        verify(gate, never()).tryStart();
    }

    @Test
    void 启动_已有任务在跑_15003() {
        when(gate.tryStart()).thenReturn(false);
        BizException ex = assertThrows(BizException.class, () -> service.start());
        assertEquals(KnowledgeError.REEMBED_IN_PROGRESS, ex.errorCode());
        verify(job, never()).reembedAll();
    }

    @Test
    void 启动_正常_开闸并派发() {
        when(gate.tryStart()).thenReturn(true);
        service.start();
        verify(job).reembedAll();
    }
}
```

`server/src/test/java/com/hify/knowledge/controller/AdminKnowledgeControllerTest.java`（照 AdminSettingControllerTest 模板）：

```java
package com.hify.knowledge.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.knowledge.service.ReembedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminKnowledgeController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class AdminKnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ReembedService reembedService;

    @Test
    void 全量重嵌_admin_200() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN));
        mockMvc.perform(post("/api/v1/admin/knowledge/documents/reembed")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        verify(reembedService).start();
    }

    @Test
    void 全量重嵌_member_403() throws Exception {
        String token = jwtService.generateToken(new CurrentUser(2L, "bob", CurrentUser.ROLE_MEMBER));
        mockMvc.perform(post("/api/v1/admin/knowledge/documents/reembed")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/server && mvn test -Dtest='ReembedServiceTest,AdminKnowledgeControllerTest'`
Expected: 编译失败（ReembedService/AdminKnowledgeController 不存在）

- [ ] **Step 3: 实现**

`server/src/main/java/com/hify/knowledge/service/ReembedService.java`：

```java
package com.hify.knowledge.service;

import com.hify.common.exception.BizException;
import com.hify.knowledge.constant.KnowledgeError;
import com.hify.provider.api.ProviderFacade;
import org.springframework.stereotype.Service;

/**
 * 全量重嵌入的启动入口：前置校验（模型已配置且可用）→ 互斥闸 → 派发异步任务。
 * getEmbeddingModel 只查库建对象不发网络请求，放前面 fail fast（未配置 12006 / 不可用 12002）。
 */
@Service
public class ReembedService {

    private final ProviderFacade providerFacade;
    private final ReembedGate gate;
    private final DocumentProcessJob job;

    public ReembedService(ProviderFacade providerFacade, ReembedGate gate, DocumentProcessJob job) {
        this.providerFacade = providerFacade;
        this.gate = gate;
        this.job = job;
    }

    public void start() {
        providerFacade.getEmbeddingModel();
        if (!gate.tryStart()) {
            throw new BizException(KnowledgeError.REEMBED_IN_PROGRESS);
        }
        job.reembedAll(); // @Async，立即返回；收尾 gate.finish() 在 job 的 finally 里
    }
}
```

`server/src/main/java/com/hify/knowledge/controller/AdminKnowledgeController.java`：

```java
package com.hify.knowledge.controller;

import com.hify.common.Result;
import com.hify.knowledge.service.ReembedService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin knowledge 接口（仅 Admin；admin 接口写在各模块 controller 下，无独立 admin 模块）。
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeController {

    private final ReembedService reembedService;

    public AdminKnowledgeController(ReembedService reembedService) {
        this.reembedService = reembedService;
    }

    /** 全量重嵌入（含存量补嵌）：清空全部向量后逐文档重嵌。花钱动作，admin 显式触发。 */
    @PostMapping("/documents/reembed")
    public Result<Void> reembed() {
        reembedService.start();
        return Result.ok(null);
    }
}
```

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: `BUILD SUCCESS`，0 Failures 0 Errors

- [ ] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/knowledge/ server/src/test/java/com/hify/knowledge/
git commit -m "feat(knowledge): admin 全量重嵌入接口（互斥闸+前置校验，TDD）"
```

---

### Task 6: 前端基座 — 类型 + API 层 + 拦截器 10001 修复 + 上传超时

**Files:**
- Modify: `web/src/types/knowledge.ts`（KbDocument 加 errorMessage）
- Modify: `web/src/types/model.ts`（加 EmbeddingSetting）
- Modify: `web/src/config.ts`（加 uploadTimeoutMs）
- Modify: `web/src/api/request.ts`（10001 兜底 toast）
- Modify: `web/src/api/knowledge.ts`（retryDocument + 上传超时）
- Modify: `web/src/api/provider.ts`（listEmbeddingModels）
- Modify: `web/src/api/admin/provider.ts`（getEmbeddingSetting/saveEmbeddingSetting）
- Create: `web/src/api/admin/knowledge.ts`（reembedAll）
- Test: `web/src/api/__tests__/knowledge.spec.ts`（增量）、`web/src/api/__tests__/provider.spec.ts`（增量）、`web/src/api/admin/__tests__/provider.spec.ts`（增量）、`web/src/api/admin/__tests__/knowledge.spec.ts`（新建）

**Interfaces:**
- Consumes: Task 3/4/5 的后端接口。
- Produces: `retryDocument(id)`、`listEmbeddingModels()`、`getEmbeddingSetting()`、`saveEmbeddingSetting(modelId)`、`reembedAll()`、`EmbeddingSetting` 类型、`KbDocument.errorMessage`、`config.uploadTimeoutMs`。Task 7/8 消费。

- [ ] **Step 1: 写失败测试**

`web/src/api/__tests__/knowledge.spec.ts`：`uploadDocument` 既有测试的期望改为三参（追加 `{ timeout: 120_000 }`），并追加：

```ts
  it('uploadDocument → 传大文件专用超时', () => {
    const file = new File(['hello'], 'a.txt', { type: 'text/plain' })
    uploadDocument('10', file)
    expect(request.post).toHaveBeenCalledWith(
      '/knowledge/datasets/10/documents',
      expect.any(FormData),
      { timeout: 120_000 },
    )
  })
  it('retryDocument → POST /knowledge/documents/{id}/retry', () => {
    retryDocument('20')
    expect(request.post).toHaveBeenCalledWith('/knowledge/documents/20/retry')
  })
```

（import 行补 `retryDocument`；原 uploadDocument 测试与本条合并，保留一条即可。）

`web/src/api/__tests__/provider.spec.ts` 追加：

```ts
  it('listEmbeddingModels → GET /provider/models?type=embedding', () => {
    listEmbeddingModels()
    expect(request.get).toHaveBeenCalledWith('/provider/models', { params: { type: 'embedding' } })
  })
```

`web/src/api/admin/__tests__/provider.spec.ts` 追加（import 补两函数）：

```ts
  it('getEmbeddingSetting → GET /admin/provider/settings/embedding-model', () => {
    getEmbeddingSetting()
    expect(request.get).toHaveBeenCalledWith('/admin/provider/settings/embedding-model')
  })
  it('saveEmbeddingSetting → PUT + body', () => {
    saveEmbeddingSetting('6')
    expect(request.put).toHaveBeenCalledWith('/admin/provider/settings/embedding-model', { modelId: '6' })
  })
```

`web/src/api/admin/__tests__/knowledge.spec.ts`（新建）：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { request } from '@/api/request'
import { reembedAll } from '@/api/admin/knowledge'

vi.mock('@/api/request', () => ({
  request: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}))

describe('admin knowledge api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reembedAll → POST /admin/knowledge/documents/reembed', () => {
    reembedAll()
    expect(request.post).toHaveBeenCalledWith('/admin/knowledge/documents/reembed')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run src/api`
Expected: FAIL（函数不存在 / uploadDocument 参数不匹配）

- [ ] **Step 3: 实现**

`web/src/types/knowledge.ts`：`KbDocument` 接口 `chunkCount: number` 之后加一行，并更新注释提及 K3 异步：

```ts
  /** status=failed 时的用户可读原因；其余状态为 null */
  errorMessage: string | null
```

`web/src/types/model.ts` 末尾追加：

```ts
/** 系统 embedding 模型设置（对齐后端 EmbeddingSettingResponse）；未配置时两字段均为 null。 */
export interface EmbeddingSetting {
  modelId: string | null
  modelName: string | null
}
```

`web/src/config.ts` 的 config 对象追加：

```ts
  // 上传大文件（≤50MB）专用超时：默认 apiTimeout 偏短，局域网 50MB 传输留足余量（K2 终审跟进项）。
  uploadTimeoutMs: 120_000,
```

`web/src/api/request.ts` 的 `case ERR_PARAM_INVALID` 分支替换为：

```ts
      case ERR_PARAM_INVALID:
        // 有字段错误数组 → 交表单逐项标红；没有（上传超限/文件名超长等非表单 10001）→ 兜底 toast，
        // 否则报错静默（K2 终审跟进项）。
        if (!apiError.fieldErrors?.length && !silent) {
          ElMessage.error(apiError.message)
        }
        break
```

`web/src/api/knowledge.ts`：顶部补 `import { config } from '@/config'`；`uploadDocument` 的 post 调用加第三参 `{ timeout: config.uploadTimeoutMs }` 并加注释；文件末尾追加：

```ts
/** 重试 failed 文档（断点续嵌）。后端：POST /api/v1/knowledge/documents/{id}/retry */
export function retryDocument(id: string) {
  return request.post<void>(`${DOC_BASE}/${id}/retry`)
}
```

`web/src/api/provider.ts` 追加：

```ts
/** 列出可用 embedding 模型（系统设置页选择器）。后端：GET /api/v1/provider/models?type=embedding */
export function listEmbeddingModels() {
  return request.get<ModelOption[]>('/provider/models', { params: { type: 'embedding' } })
}
```

`web/src/api/admin/provider.ts` 追加（import 行补 `EmbeddingSetting`，来自 `@/types/model`）：

```ts
/** 查系统 embedding 模型设置。后端：GET /api/v1/admin/provider/settings/embedding-model */
export function getEmbeddingSetting() {
  return request.get<EmbeddingSetting>('/admin/provider/settings/embedding-model')
}

/** 设系统 embedding 模型（后端保存时真实探测验 1024 维，可能秒级耗时）。后端：PUT 同路径 */
export function saveEmbeddingSetting(modelId: string) {
  return request.put<EmbeddingSetting>('/admin/provider/settings/embedding-model', { modelId })
}
```

`web/src/api/admin/knowledge.ts`（新建）：

```ts
import { request } from '@/api/request'

// admin knowledge 域接口。baseURL 已含 /api/v1。

/** 全量重嵌入（含存量补嵌，花钱动作）。后端：POST /api/v1/admin/knowledge/documents/reembed */
export function reembedAll() {
  return request.post<void>('/admin/knowledge/documents/reembed')
}
```

- [ ] **Step 4: 跑测试与类型检查确认通过**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run src/api && pnpm typecheck`
Expected: api 测试 PASS；typecheck 若报 KbDocument 缺 errorMessage 的 fixture（DatasetDetail.spec.ts 的 DOC 常量），在该 fixture 补 `errorMessage: null,` 后重跑至绿。

- [ ] **Step 5: Commit**

```bash
git add web/src/types/ web/src/config.ts web/src/api/
git commit -m "feat(web): K3 API 层+类型+拦截器 10001 兜底 toast+上传专用超时（TDD）"
```

---

### Task 7: 前端系统设置页（SystemSettings.vue + 路由）

**Files:**
- Create: `web/src/views/admin/system/SystemSettings.vue`
- Modify: `web/src/router/index.ts`（加路由，放 `/admin/identity` 路由之后）
- Test: `web/src/views/admin/system/__tests__/SystemSettings.spec.ts`（新建）

**Interfaces:**
- Consumes: Task 6 的 `getEmbeddingSetting/saveEmbeddingSetting/reembedAll/listEmbeddingModels`。
- Produces: 路由 `/admin/settings`（menu:true, group:'管理控制台' → 侧边栏自动出现，无需改布局组件）。

- [ ] **Step 1: 写失败测试**

`web/src/views/admin/system/__tests__/SystemSettings.spec.ts`：

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { getEmbeddingSetting, saveEmbeddingSetting } from '@/api/admin/provider'
import { reembedAll } from '@/api/admin/knowledge'
import { listEmbeddingModels } from '@/api/provider'
import type { EmbeddingSetting } from '@/types/model'
import SystemSettings from '@/views/admin/system/SystemSettings.vue'

vi.mock('@/api/admin/provider', () => ({
  getEmbeddingSetting: vi.fn(), saveEmbeddingSetting: vi.fn(),
  listProviders: vi.fn(), createProvider: vi.fn(), updateProvider: vi.fn(),
  enableProvider: vi.fn(), disableProvider: vi.fn(), deleteProvider: vi.fn(),
}))
vi.mock('@/api/admin/knowledge', () => ({ reembedAll: vi.fn() }))
vi.mock('@/api/provider', () => ({ listChatModels: vi.fn(), listEmbeddingModels: vi.fn() }))

globalThis.ResizeObserver = class {
  observe() {} unobserve() {} disconnect() {}
} as unknown as typeof ResizeObserver

const SETTING: EmbeddingSetting = { modelId: '6', modelName: '千问 v4' }
const EMPTY: EmbeddingSetting = { modelId: null, modelName: null }
const MODELS = [{ id: '6', name: '千问 v4', type: 'embedding', providerName: '阿里云' }]

async function mountPage() {
  const wrapper = mount(SystemSettings, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('SystemSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listEmbeddingModels).mockResolvedValue(MODELS)
    vi.mocked(getEmbeddingSetting).mockResolvedValue(SETTING)
  })

  it('挂载：拉取设置与可用 embedding 模型', async () => {
    const wrapper = await mountPage()
    expect(getEmbeddingSetting).toHaveBeenCalled()
    expect(listEmbeddingModels).toHaveBeenCalled()
    expect(wrapper.text()).toContain('embedding')
  })

  it('保存：调用 saveEmbeddingSetting 并提示成功', async () => {
    vi.mocked(saveEmbeddingSetting).mockResolvedValue(SETTING)
    const wrapper = await mountPage()
    await wrapper.find('[data-test="save-embedding"]').trigger('click')
    await flushPromises()
    expect(saveEmbeddingSetting).toHaveBeenCalledWith('6')
  })

  it('未配置且未选择：保存按钮禁用、重嵌按钮禁用', async () => {
    vi.mocked(getEmbeddingSetting).mockResolvedValue(EMPTY)
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="save-embedding"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="reembed-all"]').attributes('disabled')).toBeDefined()
  })

  it('全量重嵌：确认后调用并提示', async () => {
    vi.mocked(reembedAll).mockResolvedValue(undefined)
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="reembed-all"]').trigger('click')
    await flushPromises()
    expect(reembedAll).toHaveBeenCalled()
  })

  it('全量重嵌：取消确认则不调用', async () => {
    const { ElMessageBox } = await import('element-plus')
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountPage()
    await wrapper.find('[data-test="reembed-all"]').trigger('click')
    await flushPromises()
    expect(reembedAll).not.toHaveBeenCalled()
  })
})
```

（若 `@/api/admin/provider` 的 mock 工厂缺少页面未用到的导出报错，按报错补齐 vi.fn() 即可。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run src/views/admin/system`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 实现**

`web/src/views/admin/system/SystemSettings.vue`（新建）：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEmbeddingSetting, saveEmbeddingSetting } from '@/api/admin/provider'
import { reembedAll } from '@/api/admin/knowledge'
import { listEmbeddingModels } from '@/api/provider'
import type { ModelOption } from '@/types/model'
import PageHeader from '@/components/PageHeader.vue'
import ContentCard from '@/components/ContentCard.vue'

const models = ref<ModelOption[]>([])
const selectedModelId = ref<string | null>(null)
const savedModelId = ref<string | null>(null)
const savedModelName = ref<string | null>(null)
const saving = ref(false)
const reembedding = ref(false)

/** 有已保存的设置才允许全量重嵌（后端还有 12006 前置校验双保险）。 */
const configured = computed(() => savedModelId.value !== null)

async function load() {
  const [setting, list] = await Promise.all([getEmbeddingSetting(), listEmbeddingModels()])
  savedModelId.value = setting.modelId
  savedModelName.value = setting.modelName
  selectedModelId.value = setting.modelId
  models.value = list
}
onMounted(load)

async function onSave() {
  if (!selectedModelId.value) return
  saving.value = true
  try {
    const saved = await saveEmbeddingSetting(selectedModelId.value)
    savedModelId.value = saved.modelId
    savedModelName.value = saved.modelName
    ElMessage.success('已保存（模型探测通过，输出 1024 维）')
  } catch {
    /* 失败（12002/12003/12005）由 request 拦截器统一 toast */
  } finally {
    saving.value = false
  }
}

async function onReembed() {
  try {
    await ElMessageBox.confirm(
      '将清空全部分段向量并按当前模型重新嵌入，会调用外部 API 产生费用，且耗时随文档量增长。确定开始？',
      '全量重嵌入',
      { type: 'warning', confirmButtonText: '开始重嵌入' },
    )
  } catch {
    return
  }
  reembedding.value = true
  try {
    await reembedAll()
    ElMessage.success('已开始重嵌入，可到各知识库详情页查看文档状态')
  } catch {
    /* 15003（已在进行中）等由 request 拦截器统一 toast */
  } finally {
    reembedding.value = false
  }
}
</script>

<template>
  <div class="system-settings">
    <PageHeader title="系统设置" description="全局生效的系统级配置（仅管理员）" />

    <ContentCard>
      <h3 class="system-settings__section-title">embedding 模型</h3>
      <p class="system-settings__hint">
        知识库向量化使用的模型，全库统一（输出必须为 1024 维，如通义 text-embedding-v4）。
        保存时会真实调用一次该模型验证维度与连通性。切换模型后需点「全量重嵌入」重建全部向量。
      </p>
      <div class="system-settings__row">
        <el-select
          v-model="selectedModelId"
          placeholder="选择 embedding 模型"
          style="width: 320px"
          data-test="embedding-select"
        >
          <el-option
            v-for="m in models"
            :key="m.id"
            :label="`${m.name}（${m.providerName}）`"
            :value="m.id"
          />
        </el-select>
        <el-button
          type="primary"
          data-test="save-embedding"
          :loading="saving"
          :disabled="!selectedModelId"
          @click="onSave"
        >保存</el-button>
      </div>
      <p v-if="configured" class="system-settings__current">
        当前生效：{{ savedModelName ?? savedModelId }}
      </p>
      <p v-else class="system-settings__current system-settings__current--empty">
        尚未配置——配置前上传的文档会停在「失败」态，配置后可在文档列表点重试恢复。
      </p>

      <el-divider />

      <h3 class="system-settings__section-title">全量重嵌入</h3>
      <p class="system-settings__hint">
        对全部文档重新向量化：首次配好模型后执行一次可补齐存量文档；切换模型后必须执行（旧向量与新模型不在同一语义空间）。
      </p>
      <el-button
        type="warning"
        data-test="reembed-all"
        :loading="reembedding"
        :disabled="!configured"
        @click="onReembed"
      >全量重嵌入</el-button>
    </ContentCard>
  </div>
</template>

<style scoped lang="scss">
.system-settings__section-title {
  margin: 0 0 $spacing-sm;
}
.system-settings__hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0 0 $spacing-md;
  max-width: 720px;
}
.system-settings__row {
  display: flex;
  gap: $spacing-sm;
  align-items: center;
}
.system-settings__current {
  margin-top: $spacing-sm;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.system-settings__current--empty {
  color: var(--el-color-warning);
}
</style>
```

`web/src/router/index.ts`：在 `/admin/identity` 路由对象之后插入：

```ts
  {
    path: '/admin/settings',
    name: 'SystemSettings',
    component: () => import('@/views/admin/system/SystemSettings.vue'),
    meta: {
      requiresAuth: true,
      roles: ['admin'],
      title: '系统设置',
      menu: true,
      icon: 'Tools',
      group: '管理控制台',
    },
  },
```

（icon 用 Element Plus icons-vue 的 `Tools`；若布局的图标注册表需要登记新图标名，照 `Setting`/`User` 既有登记方式补一行。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run src/views/admin/system && pnpm typecheck`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add web/src/views/admin/system/ web/src/router/index.ts
git commit -m "feat(web): admin 系统设置页（embedding 模型选择+全量重嵌入，TDD）"
```

---

### Task 8: 前端 DatasetDetail 增强（轮询/重试/失败原因/文案）+ 全量回归

**Files:**
- Modify: `web/src/views/knowledge/DatasetDetail.vue`
- Test: `web/src/views/knowledge/__tests__/DatasetDetail.spec.ts`（增量+fixture 修）

**Interfaces:**
- Consumes: Task 6 的 `retryDocument`、`KbDocument.errorMessage`。

- [ ] **Step 1: 写失败测试**

`DatasetDetail.spec.ts` 修改：
1. `vi.mock('@/api/knowledge', …)` 工厂加 `retryDocument: vi.fn(),`；import 行补 `retryDocument`。
2. `DOC` fixture 补 `errorMessage: null,`（Task 6 typecheck 时可能已补，确保存在）。
3. 追加 fixture 与测试：

```ts
const FAILED_DOC: KbDocument = {
  ...DOC, id: '21', name: 'bad.txt', status: 'failed', chunkCount: 0,
  errorMessage: '系统未配置 embedding 模型，请联系管理员在系统设置中配置',
}
const PROCESSING_DOC: KbDocument = { ...DOC, id: '22', name: 'wip.txt', status: 'processing' }
```

```ts
  it('failed 文档：owner 可见重试按钮，点击后调用并刷新', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([FAILED_DOC]))
    vi.mocked(retryDocument).mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.find('[data-test="doc-retry-21"]').trigger('click')
    await flushPromises()
    expect(retryDocument).toHaveBeenCalledWith('21')
    expect(listDocuments).toHaveBeenCalledTimes(2) // 挂载 + 重试后刷新
  })

  it('ready 文档：无重试按钮', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-retry-20"]').exists()).toBe(false)
  })

  it('非 owner 非 admin：failed 文档也无重试按钮', async () => {
    vi.mocked(listDocuments).mockResolvedValue(page([FAILED_DOC]))
    const store = useUserStore()
    store.user = { id: '999', username: 'carol', role: 'member' }
    const wrapper = await mountPage()
    expect(wrapper.find('[data-test="doc-retry-21"]').exists()).toBe(false)
  })

  it('存在 processing 文档：启动轮询定时刷新，全部终态后停止', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(listDocuments)
        .mockResolvedValueOnce(page([PROCESSING_DOC])) // 挂载：处理中 → 开轮询
        .mockResolvedValueOnce(page([DOC]))            // 第一次轮询：已 ready → 停轮询
      const wrapper = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(2)

      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(2) // 已停止，不再增长
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('全部终态：不启动轮询', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await (async () => {
        const w = mount(DatasetDetail, { global: { plugins: [ElementPlus] } })
        await flushPromises()
        return w
      })()
      await vi.advanceTimersByTimeAsync(3000)
      await flushPromises()
      expect(listDocuments).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('文件名超 200 字符：前端拦截不调上传', async () => {
    const wrapper = await mountPage()
    await selectFile(wrapper, 'n'.repeat(201) + '.txt')
    expect(uploadDocument).not.toHaveBeenCalled()
  })
```

注意：轮询用例里 `mountPage()` 内部有 `flushPromises`，fake timers 下正常（flushPromises 基于微任务）；若既有 `mountPage` 与 fake timers 冲突，改为用例内手动 mount（如上示例已内联）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run src/views/knowledge`
Expected: FAIL（retry 按钮不存在、轮询无实现、文件名预检缺失）

- [ ] **Step 3: 实现（DatasetDetail.vue 修改）**

`<script setup>` 部分：
1. import 增：`onUnmounted`（vue）、`retryDocument`（@/api/knowledge）。
2. 常量区加 `const POLL_INTERVAL_MS = 3000`、`const NAME_MAX = 200`。
3. `loadDocs` 改为带背景参数（轮询不闪 loading）并在拉取后同步轮询开关；加轮询与重试逻辑；`onMounted` 之后补 `onUnmounted(stopPolling)`：

```ts
let pollTimer: number | null = null

async function loadDocs(background = false) {
  if (!background) loading.value = true
  try {
    const res = await listDocuments(datasetId, { page: page.value, size: size.value })
    docs.value = res.list
    total.value = Number(res.total)
  } finally {
    if (!background) loading.value = false
  }
  syncPolling()
}

/** 有非终态文档就开轮询、全部终态即停（K3 异步流水线的状态刷新）。 */
function syncPolling() {
  const active = docs.value.some((d) => d.status === 'pending' || d.status === 'processing')
  if (active && pollTimer === null) {
    pollTimer = window.setInterval(() => loadDocs(true), POLL_INTERVAL_MS)
  } else if (!active && pollTimer !== null) {
    stopPolling()
  }
}
function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}
onUnmounted(stopPolling)

async function onRetry(row: KbDocument) {
  try {
    await retryDocument(row.id)
    ElMessage.success('已重新开始处理')
    await loadDocs()
  } catch {
    /* 15002 等由 request 拦截器统一 toast */
  }
}
```

4. `beforeUpload` 在扩展名校验前加文件名预检：

```ts
  if (file.name.length > NAME_MAX) {
    ElMessage.error(`文件名不能超过 ${NAME_MAX} 个字符`)
    return false
  }
```

5. `doUpload` 成功提示 `'上传成功'` 改 `'已上传，正在处理'`（loadDocs 会自动开启轮询）。

`<template>` 部分：
1. 状态列模板替换（failed 且有原因时包 tooltip）：

```vue
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip
              v-if="(row as KbDocument).status === 'failed' && (row as KbDocument).errorMessage"
              :content="(row as KbDocument).errorMessage ?? ''"
            >
              <el-tag type="danger">{{ STATUS_LABEL.failed }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="STATUS_TAG[(row as KbDocument).status]">
              {{ STATUS_LABEL[(row as KbDocument).status] }}
            </el-tag>
          </template>
        </el-table-column>
```

2. 操作列 width 由 200 改 260，「删除」按钮前插入：

```vue
            <el-button
              v-if="canModify && (row as KbDocument).status === 'failed'"
              size="small"
              type="warning"
              :data-test="`doc-retry-${(row as KbDocument).id}`"
              @click="onRetry(row as KbDocument)"
              >重试</el-button
            >
```

3. 抽屉分页 `<el-pagination small` 改 `<el-pagination size="small"`（EP 2.8+ 写法，K2 终审跟进项）。

- [ ] **Step 4: 前后端全量回归**

Run: `cd /home/wang/playlab/hify/web && pnpm vitest run && pnpm typecheck`
Expected: 全绿（196 + 新增）
Run: `cd /home/wang/playlab/hify/server && mvn test`
Expected: `BUILD SUCCESS`，0 Failures 0 Errors

- [ ] **Step 5: Commit**

```bash
git add web/src/views/knowledge/ web/src/api/ web/src/types/
git commit -m "feat(web): 知识库详情页状态轮询+失败重试+错误原因展示+K2 跟进项修复（TDD）"
```

---

## 手动验收（合并前，照 spec §6）

1. 后端起服务（自动跑 V15/V16），前端 `pnpm dev`。
2. **未配模型**传 .txt → 文档变 failed，tooltip 显示「系统未配置 embedding 模型…」。
3. admin 进「系统设置」：选一个 chat 模型不可见（下拉只有 embedding 模型）；配 DeepSeek 供应商下建的 embedding 模型（若无跳过）→ 保存被拒；配千问 text-embedding-v4 → 保存成功提示探测通过。
4. 点「全量重嵌入」→ 确认弹窗 → 知识库详情页看存量文档 processing→ready；立刻再点一次 → 提示「已在进行中」。
5. 新传 .txt → 列表 pending→processing→ready 轮询可见，提示「已上传，正在处理」。
6. 传空文件 → failed + 原因；点「重试」→ 仍 failed（内容确实为空，合理）；对第 2 步的 failed 文档点「重试」→ ready。
7. member 账号：无「系统设置」菜单；他人库的 failed 文档无重试按钮。
8. psql 抽查：`select count(*) from kb_chunk where embedding is not null;` > 0；`select vector_dims(embedding) from kb_chunk where embedding is not null limit 1;` = 1024。
9. 重启后端（模拟部署）：若有 processing 中的文档 → 启动后变 failed，可重试。
