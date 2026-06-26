# provider C2 自检（getChatClient + 四层韧性）

> C2 轮的逐任务自检，独立于 `docs/self-check.md`。
> Plan：`docs/superpowers/plans/2026-06-25-provider-c2.md`；验证清单：`...-c2-verification.md`。
> 分支 `feat/provider-c2`，11 个 Task。判定测试结果看计数/退出码，不 grep BUILD SUCCESS。
>
> ⚠️ Task 1–5 原始自检写在 SDD 临时目录 `.superpowers/sdd/`，该目录未进 git、已被清理丢失；
> 以下 Task 1–5 为据 git 提交与 surefire 报告**重建的概要**，Task 6 起为实时记录。

## Task 1–5（重建概要，2026-06-25）

| Task | 提交 | 内容 | 测试 |
|---|---|---|---|
| C2-1 | d484263 | `model_provider` 加 10 韧性字段 + Flyway **V8** | 纯 DDL+实体，无单测（连库测试推迟，靠启动走查） |
| C2-2 | 416b07a | `ProviderError` +12002/12003/12004（400/503/429） | ProviderErrorTest 1 |
| C2-3 | 6479b34 | `ResilienceExceptions` 异常分类器（isRetryable/isProviderFault/toBizException + cause 链遍历含自环防护） | ResilienceExceptionsTest 7 |
| C2-4 | c60e23e | `ResilienceBundle` 按 DB 字段建四件套（Retry/CB/Bulkhead/TimeLimiter） | ResilienceBundleTest 1 |
| C2-5 | 6046f47 | `ResilientChatModel` 四层韧性装饰器（非流式，构造依赖 ExecutorService） | ResilientChatModelTest 4 |

> 续点核对：在 C2-5 HEAD 上 `mvn test` 全量 **209/0/0**、BUILD SUCCESS（2026-06-26 复核），确认 Task 1–5 干净。

## Task 6：ChatClientFactory（协议 → 原始 ChatModel）（2026-06-26）

- 对应改动：`provider/service/ChatClientFactory.java`（新）、`provider/config/ProviderConfig.java`（+`noRetryTemplate` +`llmCallExecutor` 两个 Bean）。
- 做了什么：`buildChatModel(provider, model)` 按 `protocol` 建 `OpenAiChatModel`/`AnthropicChatModel`，`options.model=modelKey`，经 `ApiKeyCipher.decrypt` 注入明文 Key，传入单次 RetryTemplate 关掉 Spring AI 自带重试（重试统一交 Resilience4j）；未知协议抛 IllegalArgumentException。`llmCallExecutor`（虚拟线程池）供 ResilientChatModel 的 TimeLimiter 使用。
- 怎么自证：`mvn -Dtest=ChatClientFactoryTest test` → `Tests run: 3, Failures: 0`。覆盖：openai 建 OpenAiChatModel 且 decrypt 被调、anthropic 建 AnthropicChatModel、未知协议抛异常。Spring AI 1.0.1 的 `*Api.builder()`/`*ChatModel.builder()` 与计划一致，无需调整。
- 反向验证：把 `default -> throw` 改成回退到 openai，「未知协议_抛异常」用例会失败。
- 已知遗留：`connect_timeout_sec` 暂未注入 RestClient（计划 §注：1.0.1 builder 若不暴露 restClientBuilder 则记为后续微调，不阻塞）；流式留后续。

## Task 7：ResilienceRegistry（双键缓存 + 失效）（2026-06-26）

- 改动：`provider/service/resilience/ResilienceRegistry.java`（新）。按 providerId 缓存 ResilienceBundle、按 modelId 缓存 ChatClient（双 ConcurrentHashMap）。
- 做了什么：`getChatClient(modelId)` 命中缓存直返；否则校验 model（存在/chat/enabled）+ provider（存在/enabled），不过抛 12002；过则 computeIfAbsent 取 bundle → factory 建 raw → 包 ResilientChatModel → ChatClient.create 缓存。`invalidate(providerId)` 清 bundle + 名下 client；`invalidateModel(modelId)` 只清该 client。getChatClient 本身只查库+建对象+decrypt，不发外部请求。
- 自证：`mvn -Dtest=ResilienceRegistryTest test` → `Tests run: 8`。覆盖：可用返回、缓存命中（工厂只建一次）、模型不存在/停用/非chat/供应商停用→12002、invalidateModel/invalidate 后重建。

## Task 8：ProviderFacade.getChatClient 委托（2026-06-26）

- 改动：`ProviderFacade`(api 加 `getChatClient(Long):ChatClient`)、`ProviderFacadeImpl`(构造加 ResilienceRegistry，委托)。
- 自证：`mvn -Dtest=ProviderFacadeImplTest test` → `Tests run: 5`（+1 委托用例）。api 用 Spring AI ChatClient 属 provider Facade 既有例外，全量回归含 Modulith 绿。

## Task 9：失效热生效钩子（2026-06-26）

- 改动：`ProviderService`(update/enable/disable/delete 写库成功后 `registry.invalidate(id)`)、`AiModelService`(update/enable/disable/delete 后 `registry.invalidateModel(id)`)。幂等早返回分支不调失效（状态没变）。失效是内存 map remove、非外部 IO，留在 @Transactional 内合规。
- 自证：`mvn -Dtest=ProviderServiceTest,AiModelServiceTest test` → 各 `Tests run: 15`（构造签名变更 + 新增失效验证用例）。

## Task 10：admin 测试连通端点（2026-06-26）

- 改动：`ModelTestResponse`(api/dto 新)、`ModelConnectionService`(新，**无 @Transactional**，真实外部 IO)、`AdminModelController` 加 `POST /api/v1/admin/provider/models/{id}/test`。
- 做了什么：经 Registry 取 ChatClient，发 "ping" 最短 prompt，回 `{sample}`。失败按 ResilienceExceptions 映射（12002/12003/12004），admin 限定（member→403/10004）。
- 自证：`mvn -Dtest=AdminModelControllerTest test` → `Tests run: 11`（+3：admin 200 返回 sample、member 403、供应商不可用 503/12003）。

## Task 11：全量回归 + 模块边界 + Postman（2026-06-26）

- 自证：`mvn test` 全量 **227/0/0、BUILD SUCCESS**（含 ModularityTests + LayerRulesTest，模块边界绿）。Postman：`docs/postman/hify-provider-c2.postman_collection.json`（admin 登录→建供应商+模型→测试连通 sample→不存在 12002→改错 Key 热生效→member 403），JSON 校验通过。
- 真实 LLM 走查（需用户参与）：见 `docs/superpowers/plans/2026-06-25-provider-c2-verification.md` §人工验证（起服务确认 Flyway V8、真实凭证测连通、改错 Key 热生效、熔断打开毫秒级失败）。
- C2 后端全部 11 Task 完成。待用户人工 e2e 后合并 main。
