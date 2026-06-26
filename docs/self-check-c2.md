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
