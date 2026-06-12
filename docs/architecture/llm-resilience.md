# LLM 外部调用韧性方案（线程 / 超时 / 重试 / 容错）

> 适用范围：所有外部 LLM API 调用（聊天 + Embedding）。全部实现收口在 `provider` 模块，
> conversation / workflow / knowledge 通过 `ProviderFacade` 拿到的 ChatClient **天然带全套韧性能力**，
> 业务模块不写任何超时/重试代码。

## 0. 总原则

1. **唯一出口**：外部调用只发生在 `provider` 模块的 `ResilientChatModel` 装饰器内（实现 Spring AI 的
   `ChatModel`/`StreamingChatModel` 接口，包住真实实现）。韧性逻辑只写一遍。
2. **协议归一**：OpenAI、Gemini、Ollama 都走 OpenAI 兼容协议，Claude 走 Anthropic 协议——
   与 CLAUDE.md 的两协议设计一致，Gemini/Ollama 不需要新协议代码。韧性层与协议无关。
3. **配置随供应商实例存 DB**（`provider` 实体字段），运行时按供应商构建 Resilience4j 实例
   （Registry 按 providerId 缓存），admin 改配置后重建实例即可热生效，不重启。
4. **每一层只做一件事**：并发隔离=信号量，快速失败=熔断，止损=超时，自愈=重试。装饰顺序固定：

```
Retry( CircuitBreaker( Bulkhead( TimeLimiter( 真实HTTP调用 ))))
└ 最外层重试：每次重试都重新过熔断计数与信号量；熔断打开时
  CallNotPermittedException 不在重试白名单内，重试立即终止，不空转。
```

## 1. 线程管理

**核心决策：Java 21 虚拟线程，不上 WebFlux。**

```properties
spring.threads.virtual.enabled=true
```

- 依据：LLM 调用阻塞 10~120 秒，Tomcat 默认 200 个平台线程，五十个用户同时等模型回复就可能
  打满线程池拖死整个应用。虚拟线程让"同步阻塞"的编程模型零成本扩展到数千并发，
  几千人规模也不需要改成响应式——WebFlux 的复杂度对一人项目不值。
- **并发隔离用信号量，不用线程池**（线程池隔离在虚拟线程下没有意义）：
  Resilience4j Bulkhead（SEMAPHORE 模式），按供应商实例一个；同一供应商再分两个池——
  **交互池（chat）和批量池（embedding/后台任务）分开**，避免文档批量向量化把对话的并发额度吃光。
- SSE 流式输出：`ChatClient.stream()` 得到 Flux，桥接到 `SseEmitter` 返回前端；订阅跑在虚拟线程上。
- 后台任务（向量化、workflow 异步执行）：`SimpleAsyncTaskExecutor`（虚拟线程模式）+ 有界队列，
  并发上限独立配置，队列满则拒绝并提示。
- 两个虚拟线程注意事项：长临界区用 `ReentrantLock` 不用 `synchronized`（JDK 21 有 pinning 问题）；
  HTTP 客户端统一用 `RestClient`（底层 JDK HttpClient，对虚拟线程友好）。
- **【硬规则】`@Transactional` 方法内禁止任何 LLM / 外部 HTTP 调用。**
  虚拟线程消除了"线程池耗尽"，但数据库连接池（HikariCP，默认 10 个连接）仍是有界资源：
  事务跨 LLM 调用 = 一个连接被占住 10~120 秒，十几个并发对话就能耗尽连接池，
  连累所有功能（包括管理页面）拿不到连接。正确写法：事务A落库 → 调 LLM（无事务）→ 事务B落库。
  ArchUnit/code review 按此检查。HikariCP 建议 `maximum-pool-size=20`、`connection-timeout=3000`，
  并监控 `hikaricp_connections_pending` 指标。

## 2. 超时

分层设置，**流式与非流式必须区分**——流式的合理总时长可能很长，但"卡住不吐字"应该快速判死。

| 参数 | 非流式 | 流式 | 说明 |
|---|---|---|---|
| 连接超时 | 5s | 5s | 建连失败快速暴露 |
| 响应/首 token 超时 | 120s（总） | 30s | 流式用 Reactor `timeout(首元素超时, 逐元素超时工厂)` |
| token 间隔超时 | — | 60s | 两个 token 之间最大静默 |
| 流式总时长上限 | — | 10min | 兜底，防节点级泄漏 |

- 所有值按**供应商实例**可配（DB 字段），Ollama 本机推理和云端 API 的合理值差一个量级。
- 上层预算必须大于下层：workflow 节点超时 > 单次模型调用超时；Agent 循环用
  "最大迭代次数 × 单次超时"做总预算，防失控循环（这同时是账单保护，配合 usage 配额）。
- 实现位置:非流式用 TimeLimiter / RestClient read-timeout；流式用 Flux 的 timeout 操作符，
  不能用整体 read-timeout（会杀死正常的长生成）。

## 3. 重试

**白名单制，只重试"重试有意义"的失败：**

| 重试 | 不重试 |
|---|---|
| 连接失败 / 建连超时 | 400 参数错误、401/403 鉴权失败 |
| 408、429（优先按 `Retry-After` 头等待） | 上下文超长、内容安全拦截 |
| 500 / 502 / 503 / 529（Anthropic overloaded） | **非流式读超时**（已等 120s，重试只会翻倍延迟） |
| | 熔断器拒绝（CallNotPermitted） |

- 策略：最多 3 次尝试（重试 2 次），指数退避 1s → 2s → 4s + 全抖动（full jitter）；
  429 的等待时间被 `Retry-After` 覆盖。
- **流式只在首 token 之前可重试**；已经向用户吐字的流中断不自动重试（避免内容重复/错乱），
  报错由前端给"重新生成"按钮。
- **全链路只允许这一处自动重试**：关掉 Spring AI 自带的 RetryTemplate（`spring.ai.retry.max-attempts=1`），
  workflow 节点重试默认 0——否则节点重试 3 × provider 重试 3 = 9 次真实调用，重试风暴。
- Embedding 调用幂等且离线，可放宽到 5 次尝试。

## 4. 容错

**熔断器**（Resilience4j CircuitBreaker，按供应商实例一个）：

| 参数 | 值 |
|---|---|
| 滑动窗口 | COUNT_BASED，20 次调用 |
| 打开条件 | 失败率 > 50%，或慢调用率 > 80%（慢调用阈值=非流式 30s） |
| 打开时长 | 30s 后进入半开 |
| 半开试探 | 5 次调用 |

熔断打开后，新请求**毫秒级失败**并返回明确错误（"模型供应商暂时不可用，请稍后重试"），
而不是让用户白等 120 秒超时——这是熔断的全部意义。

**信号量满载**：`maxWaitDuration` 设 2s，等不到立即失败提示"当前使用人数较多"。不做长排队，
排队会把延迟问题滚雪球。

**降级策略（一期刻意从简）**：不做自动跨供应商 failover——CLAUDE.md 已排除"模型负载均衡"，
且静默切换模型会让回答质量不可预期。对话场景给明确错误；workflow 的 LLM 节点失败按节点失败处理。
二期若需要，在 app 配置里加"备用模型"字段，仅对非流式且首 token 前的失败做一次切换。

**观测**：resilience4j-micrometer 指标接入 Actuator（熔断状态、信号量占用、重试次数）；
熔断状态变迁打 WARN 日志；每次调用结束发布 `LlmCallCompletedEvent`（成功/失败/耗时/token），
usage 模块监听落库——admin 用量看板直接能看到各供应商的失败率和延迟。

## 5. 落地清单

**依赖**：`resilience4j-spring-boot3`、`resilience4j-reactor`（流式超时/熔断桥接）、`spring-boot-starter-actuator`。

**代码位置**（遵循 code-organization.md）：

```
provider/
├── api/ProviderFacade.java            # getChatClient(modelId) / getEmbeddingModel(modelId)
├── service/
│   ├── ChatClientFactory.java         # 按协议构建原始 ChatModel
│   └── resilience/
│       ├── ResilientChatModel.java    # 装饰器：Retry→CB→Bulkhead→Timeout
│       └── ResilienceRegistry.java    # 按 providerId 构建/缓存/重建 R4j 实例
└── entity/ModelProvider.java          # 含下表字段
```

**`model_provider` 表新增字段（均有默认值，admin 界面可改）**：

| 字段 | 默认 | 字段 | 默认 |
|---|---|---|---|
| `max_concurrency`（交互池） | 10 | `connect_timeout_sec` | 5 |
| `batch_concurrency`（批量池） | 3 | `response_timeout_sec` | 120 |
| `retry_max_attempts` | 3 | `first_token_timeout_sec` | 30 |
| `cb_failure_rate` | 50 | `token_gap_timeout_sec` | 60 |
| `cb_wait_open_sec` | 30 | `stream_max_duration_sec` | 600 |
