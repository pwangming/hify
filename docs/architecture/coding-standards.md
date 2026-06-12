# Hify Java 编码规范（基于阿里巴巴 Java 开发手册提炼，20 条）

> 从手册中按"本项目真实会踩"的标准提炼，并按技术栈（Java 21 虚拟线程 / Spring Boot 3 /
> MyBatis-Plus / BizException 体系）改写为可直接执行的规则。与本文冲突的代码视为错误。
> 配套：领域命名表与分层规则见 `code-organization.md`；错误码与序列化见 `api-standards.md`。

## 命名

1. **大小写三件套**：类 `UpperCamelCase`，方法/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。
   禁止拼音命名、禁止单字母变量（循环下标除外）、缩写仅限通用词（id / url / dto / llm）。
   Facade/Service/Controller 等领域命名直接套用 code-organization.md 第 3 节的表。
2. **POJO 布尔字段禁止 `is` 前缀**：写 `deleted` 不写 `isDeleted`。Lombok 生成 `isDeleted()` 后，
   序列化与 MyBatis-Plus 映射会把字段名错判成 `deleted`/`isDeleted` 两套，必出事故。
3. **集合命名自带语义**：列表用复数（`datasets`），Map 命名写明 key 到 value（`chunkIdToScore`），
   禁止 `list1`、`map`、`data` 这类无信息量命名。
4. **枚举**：成员 `UPPER_SNAKE_CASE`；对外/入库的字符串值统一小写（api-standards.md 第 4 节），
   通过显式 value 字段绑定，禁止依赖 `name()`/`ordinal()` 隐式转换。
5. **方法名名实一致**：动词开头，`get/query` 绝不改状态、不做远程调用；写操作用
   `create/update/delete/save`；判断用 `check/validate`（失败抛 BizException）或 `is/has`（返回 boolean）。

## 异常处理

6. **异常不做流程控制**：能用 if 预判的情况（null、空集合、状态不对）必须先判断，
   禁止靠 catch 接住再走分支。
7. **业务失败只抛 `BizException` + 错误码**：禁止自创 RuntimeException 子类、禁止抛裸
   `RuntimeException("xxx")`。确需携带结构化信息时继承 BizException（code-organization.md 第 2 节）。
8. **catch 三选一**：处理并恢复、记日志后转译重抛（保留原异常为 cause）、原样上抛。
   禁止空 catch 块、禁止 `catch (Exception)` 宽口径兜底——全局唯一兜底在 infra 的异常处理器。
9. **事务内吞异常 = 事务失效**：`@Transactional` 方法里 catch 住异常又不重抛，事务会照常提交。
   要么抛出去，要么显式 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`，二选一。
10. **资源一律 try-with-resources**（流、HTTP response、Statement）；
    finally 块禁止 `return`、禁止抛异常（会吞掉 try 里的原始异常）。

## 日志

11. **只用 SLF4J**（`@Slf4j`）：禁止 `System.out.println`、禁止 `e.printStackTrace()`。
12. **占位符 `{}` 传参**，禁止字符串拼接进日志方法；参数需要昂贵计算（序列化大对象）时，
    先 `if (log.isDebugEnabled())` 再拼。
13. **记异常必须把异常对象作为最后一个参数**：`log.error("文档解析失败, docId={}", docId, e)`。
    只记 `e.getMessage()` 会丢堆栈，排障时等于没记。
14. **级别铁律**：ERROR = 需要人工介入；WARN = 异常但已自动恢复/降级（如 LLM 重试成功、熔断开启）；
    INFO = 关键业务节点（应用发布、工作流启停、文档入库完成）；DEBUG = 排障细节。
    循环体内禁止 INFO 及以上级别。
15. **谁处理谁打日志**：catch 后重抛就不要打（上层处理者会打），杜绝一个异常打两遍堆栈。
    日志禁止出现 API Key、密码、JWT；LLM 请求/响应原文截断到 500 字符再记。

## 并发（Java 21 虚拟线程环境，手册的线程池条目按此修正）

16. **禁止手动 `new Thread()` 和私建线程池**（`Executors.newFixedThreadPool` 等一律不用）：
    异步任务用 `@Async` 或注入项目统一配置的虚拟线程 executor，并发上限用 `Semaphore`
    控制（llm-resilience.md 的供应商信号量即此模式），不靠池大小控制。
17. **虚拟线程专项：禁止用 `synchronized` 包住阻塞调用**（LLM、HTTP、JDBC）——Java 21 会把
    载体线程钉死（pinning），虚拟线程优势归零。需要互斥用 `ReentrantLock`。
18. **单例 Bean 禁止可变实例字段**：`@Service`/`@Component` 是并发共享的，状态要么进方法局部变量，
    要么用 `ConcurrentHashMap`/`AtomicLong`/Caffeine。ConcurrentHashMap 的复合操作用
    `computeIfAbsent`，禁止 get 判空再 put（两步之间会被插队）。
19. **日期时间只用 `java.time`**（`OffsetDateTime`/`Instant`/`DateTimeFormatter`，均不可变线程安全）：
    禁止 `SimpleDateFormat` 和 `java.util.Date`，且 `OffsetDateTime` 才能与 `timestamptz`、
    ISO-8601 序列化（api-standards.md 第 4 节）正确对齐。
20. **ThreadLocal 用完必须在 finally 里 `remove()`**；跨线程传递 traceId 统一走 infra 提供的
    MDC 装饰 executor，禁止业务代码手工 `MDC.get/put` 搬运。
