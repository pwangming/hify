# Hify 代码组织规范

> 本文是代码组织的唯一权威规范。写代码前先读本文；与本文冲突的代码视为错误。
> 基础包名：`com.hify`。模块化单体，Spring Boot 3.x + MyBatis-Plus + Spring AI + Spring Modulith。

## 1. 模块清单与依赖白名单

`com.hify` 下只允许存在以下一级包（模块）。新增模块必须先修改本表。

| 模块 | 职责 | 允许依赖的业务模块（仅 api 包） |
|---|---|---|
| `common` | Result、错误码、异常、分页、BaseEntity、纯静态工具 | 无 |
| `infra` | 安全(JWT)、MyBatis/Caffeine缓存/Jackson 配置、全局异常处理、限流 | 无 |
| `identity` | 用户、角色、登录 | 无 |
| `provider` | 模型供应商、模型实例、ChatClient/EmbeddingModel 工厂 | 无 |
| `tool` | 工具注册表、内置工具、OpenAPI 工具、MCP 接入 | 无 |
| `usage` | 调用日志、Token 统计、配额 | 无 |
| `knowledge` | 知识库、文档分段、向量化、检索 | provider |
| `app` | 应用元数据、API Key | provider, knowledge, tool（仅校验引用） |
| `conversation` | 会话、消息、多轮记忆、Agent 编排 | app, provider, knowledge, tool, usage |
| `workflow` | 画布定义、节点执行引擎、运行日志 | conversation, app, provider, knowledge, tool, usage |

硬性规则：
- 所有模块可依赖 `common`、`infra`；`common` 不依赖任何人，`infra` 只依赖 `common`。
- 表中未列出的依赖方向一律禁止。需要新依赖时：先改本表 → 改 `package-info.java` 的 `allowedDependencies` → 再写代码。
- 禁止依赖 `identity`。"当前用户"从 `infra` 的 `CurrentUserHolder.current()`（JWT 解析结果 `CurrentUser`）获取。
  （该类内部封装 Spring Security 的 `SecurityContextHolder`，业务代码无需 import 任何 Spring Security 类。）
- 管理控制台没有独立模块：admin 接口写在各自模块的 `controller/` 下。

> **学习参考模块 `demo`（不属于业务模块集）**：`com.hify.demo` 是刻意长期保留的最小样板，
> 完整演示 `controller → service → mapper → entity → dto` 一条链路与各项基建（BaseEntity 自动填充、
> `@Valid` 校验、`Result`/`PageResult` 信封、逻辑删除）。它**不是**上表 10 个业务模块之一，不承载任何业务能力，
> 仅依赖 `common`、`infra`。硬性约束：**任何业务模块都禁止依赖 `demo`**（它是叶子，只能被读、不能被用），
> 由 `ModularityTests` 守护。新人读不懂复杂模块时，以本模块为对照基准。

## 2. 模块内部分层

每个业务模块的内部结构固定如下（不需要的目录可以不建，但不允许发明新目录名）：

```
com.hify.<module>/
├── api/                    # 模块对外的全部内容（其他模块只能 import 这里）
│   ├── <Module>Facade.java # 对外门面接口，一个模块最多一个
│   ├── dto/                # 跨模块传输对象（record）
│   └── event/              # 本模块发布的领域事件（record）
├── controller/             # REST 接口
├── dto/                    # 仅本模块 controller 用的 XxxRequest/XxxResponse，禁止被其他模块 import
├── service/                # 业务逻辑：具体类，不拆 service/impl，不写 IXxxService 接口
│   ├── XxxService.java
│   ├── <Module>FacadeImpl.java
│   └── <子域>/             # 复杂模块允许业务子包，如 workflow/service/node/
├── mapper/                 # MyBatis-Plus Mapper 接口 + XML（如有）
├── entity/                 # 数据库表映射对象
├── config/                 # 仅本模块用的 @Configuration（可选）
├── exception/              # 模块级自定义异常（可选；多数情况用 common 的 BizException + 错误码即可）
└── constant/               # 模块级常量与枚举（可选）
```

### 每层的职责边界

**`api/`** —— 模块的公开契约。
- 只能依赖：本包内类、`common`、JDK、其他模块的 `api`。禁止依赖本模块的 `service/mapper/entity/controller/dto`。
- DTO 和 Event 一律用 `record`，不可变，不携带行为。
- 例外：`provider` 与 `tool` 的 Facade 允许在签名中使用 Spring AI 类型（provider：`ChatClient`、`EmbeddingModel`；tool：`ToolCallback`）。

**`controller/` + `dto/`** —— 协议转换层，不是业务层。
- Controller 只做：参数校验（`@Valid` + jakarta validation 注解）、Request → Service 入参转换、调用**本模块** service、组装 `Result<T>` 返回。
- 禁止：业务逻辑（if/else 业务分支）、`@Transactional`、注入 Mapper、注入任何其他模块的类（包括 Facade——跨模块聚合写在 service）。
- `dto/` 只放本模块 controller 的 `XxxRequest`（含校验注解）和 `XxxResponse`，**禁止被其他模块 import**；要给其他模块用的数据结构放 `api/dto/`，二者不混用。
- 路由规则：成员接口 `/api/v1/<module>/**`；admin 接口 `/api/v1/admin/<module>/**`；应用对外 API `/v1/apps/{appKey}/**`（仅 conversation、workflow 有）。
- 统一返回 `com.hify.common.Result<T>`；异常直接抛 `BizException`，由 `infra` 的全局异常处理器转换，Controller 里不写 try-catch。

**`exception/` + `constant/`** —— 可选的辅助包。
- 默认用 `common` 的 `BizException` + 模块错误码；只有当异常需要携带模块特有的结构化信息时才新建异常类，且必须继承 `BizException`。
- 常量优先用枚举；魔法值（字符串/数字字面量）出现两次即提取到 `constant/`。两个包都禁止被其他模块 import。

**`service/`** —— 业务逻辑唯一所在地。
- 具体类 + `@Service`，不写 service 接口（`Facade` 是唯一的接口抽象）。
  - **为什么不拆 `IXxxService` + `XxxServiceImpl`**：拆接口的唯一真实收益是「同一接口多实现可切换」，而业务 service 几乎永远只有单一实现，那个接口就是个空壳，纯增维护成本（两份文件、签名两边同步、IDE 还要绕一跳才到逻辑）。传统拆接口的另外两个理由如今都已失效：Spring AOP（事务/缓存）现在默认走 CGLIB，能直接代理具体类，不再「无接口事务不生效」；Mockito 也早能直接 mock 具体类。遵循 CLAUDE.md「最简单直接、不过度抽象」——真出现第二实现那天，IDE 一键抽接口即可，不亏。
  - **判断「要不要接口」的真正标准是「对面有没有需要隔离的真实边界」**：模块**内部**调用没有边界 → 用具体类；模块**之间**调用要把「对外契约」和「内部实现」隔开 → 才用接口，那就是 `Facade`/`FacadeImpl`（且受 Modulith 强制校验）。所以「Service 不拆、Facade 拆」不是自相矛盾，而是同一条标准的两面。
- `@Transactional` 只允许出现在这一层。
- 注入对象限定为：本模块 mapper、本模块其他 service、其他模块的 Facade、`infra` 提供的技术组件。
- 不继承 MyBatis-Plus 的 `ServiceImpl`/`IService`；批量操作用 `com.baomidou.mybatisplus.extension.toolkit.Db`。
- Entity ↔ DTO 转换写在 service 层（复杂的抽成同包 `XxxConverter` 静态方法类），不引入 MapStruct。

**`mapper/`** —— 数据访问。
- 接口继承 `BaseMapper<Entity>`；复杂 SQL 用 `@Select` 或同目录 XML。
- 禁止业务逻辑。只能被本模块 `service/` 注入。

**`entity/`** —— 表映射。
- `@TableName` 注解；继承 `common.BaseEntity`（`id`、`createTime`、`updateTime`、`deleted`，自动填充在 `infra` 的 `MetaObjectHandler`）。
- Entity 不允许出现在 `api/`、`controller/`、`dto/` 的任何方法签名或字段中，不允许被其他模块 import。

### "这段代码放哪"速查

| 要写的东西 | 位置 |
|---|---|
| HTTP 接口 | `controller/XxxController` |
| 接口的入参/出参对象 | `dto/`（仅本模块用，对外的放 `api/dto/`） |
| 业务规则、流程编排、跨模块聚合 | `service/` |
| SQL / 查询条件 | `mapper/` |
| 新表 | `entity/` + Flyway 迁移脚本 `src/main/resources/db/migration/` |
| 给其他模块用的数据结构 | `api/dto/` |
| 通知别的模块"发生了什么" | `api/event/` + 发布事件 |
| 模块特有异常 | `exception/`（默认直接用 `BizException`） |
| 模块常量/枚举 | `constant/` |
| JWT、序列化、连接池等技术配置 | `infra/` |
| 与业务无关的纯工具方法 | `common/util/`（先确认 Hutool/Spring 没有现成的） |

## 3. 命名规范

| 类型 | 命名 | 示例 |
|---|---|---|
| 门面 | `<Module>Facade` / `<Module>FacadeImpl` | `KnowledgeFacade` |
| 服务 | `XxxService`（具体类） | `DocumentIngestService` |
| 控制器 | `XxxController` | `DatasetController` |
| 跨模块 DTO | `XxxDTO`（record） | `RetrievedChunkDTO` |
| 事件 | `XxxEvent`（record，过去时语义） | `TokenUsedEvent` |
| Web 入参/出参 | `XxxRequest` / `XxxResponse` | `CreateDatasetRequest` |
| 实体 | 表名驼峰，无后缀 | 表 `kb_document` → `KbDocument` |
| Mapper | `<Entity>Mapper` | `KbDocumentMapper` |

## 4. 跨模块调用规则

1. **唯一入口**：模块 X 引用模块 Y 时，只能 import `com.hify.y.api..*`。出现任何其他 import（如 `com.hify.y.service.*`）即违规，CI 会失败。
2. **同步调用走 Facade**：需要对方"做事并拿到结果"时调 Facade 方法。Facade 方法签名只能使用：`api/dto` 类型、`common` 类型、JDK 类型（provider/tool 的 Spring AI 类型例外：provider 暴露 `ChatClient`/`EmbeddingModel`，tool 暴露 `ToolCallback`）。
3. **通知走事件**：只需要告知"发生了什么"、不关心谁处理时，发布 Spring 事件。规则：
   - 事件 record 定义在**发布方**的 `api/event/`；
   - **例外——被 usage 消费的计量事件放 `common`**：如 `TokenUsedEvent`。发布方（conversation/workflow）
     同时是 usage 的下游（调 `UsageFacade.checkQuota`），若事件放发布方 `api/event/`，usage 监听会反向
     依赖发布方而成环（违反本节规则 7 的 DAG）。这类"喂给 usage 的计量事件"统一放 `common.event`，
     两侧都依赖 common、无环。`ToolInvokedEvent` 若也由 usage 消费同理。
   - 通过 `ApplicationEventPublisher` 发布，并**在发布方的事务方法内**发（否则 `AFTER_COMMIT` 监听不触发）；
   - 监听方用 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async`；
   - Token 计量、调用日志**必须**走事件（`TokenUsedEvent`、`ToolInvokedEvent`），禁止同步调用 usage 写入。
4. **配额检查**：只在两个运行时入口做——conversation 收到消息时、workflow 触发时，调用 `UsageFacade.checkQuota(...)`。禁止在 provider 内每次模型调用时检查。
5. **事务不跨模块写**：一个 `@Transactional` 方法内禁止出现两个不同模块的写操作。跨模块一致性用"本模块状态机 + 事件 + 失败补偿"。读操作不受限。
6. **事务不包外部调用**：`@Transactional` 方法内禁止 LLM 调用、HTTP 请求等任何外部 IO（会占住数据库连接 10~120 秒，耗尽连接池拖垮全站）。正确模式：事务A落库 → 外部调用（无事务）→ 事务B落库。详见 `llm-resilience.md` 第 1 节。
7. **禁止循环**：依赖图必须是 DAG（第 1 节白名单已保证）。特别地：若二期要"工作流作为 Agent 工具"，通过 `tool` 模块注册工作流应用的对外 API 实现，禁止 conversation → workflow 直接依赖。

## 5. 强制校验（CI 红灯）

边界不靠自觉，靠测试。以下文件必须存在且在 CI 中运行。

### 5.1 Spring Modulith 模块声明

每个模块的 `package-info.java` 声明依赖白名单（以 conversation 为例）：

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "app::api", "provider::api", "knowledge::api",
        "tool::api", "usage::api", "common", "infra"
    }
)
package com.hify.conversation;
```

每个模块的 `api/package-info.java`：

```java
@org.springframework.modulith.NamedInterface("api")
package com.hify.conversation.api;
```

`common` 与 `infra` 声明为开放模块：

```java
@org.springframework.modulith.ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.hify.common;
```

### 5.2 模块边界测试

```java
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(HifyApplication.class);

    @Test
    void 模块依赖白名单与无循环() {
        modules.verify();   // 校验 allowedDependencies、api 暴露、循环依赖
    }
}
```

### 5.3 模块内分层测试（ArchUnit）

```java
@AnalyzeClasses(packages = "com.hify")
class LayerRulesTest {

    // 依赖「目标」包一律用 com.hify 前缀限定，避免第三方库里同名段（如 MyBatis 的
    // org.mybatis.spring.mapper）被宽模式 "..mapper.." 误判为越界。

    @ArchTest
    static final ArchRule 协议层不碰数据访问 = noClasses()
        .that().resideInAnyPackage("..controller..", "com.hify.*.dto..")
        .should().dependOnClassesThat().resideInAnyPackage("com.hify..mapper..", "com.hify..entity..");

    @ArchTest
    static final ArchRule mapper只被service使用 = noClasses()
        .that().resideOutsideOfPackage("..service..")
        .should().dependOnClassesThat().resideInAPackage("com.hify..mapper..");

    @ArchTest
    static final ArchRule api包不依赖实现 = noClasses()
        .that().resideInAPackage("..api..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("com.hify..service..", "com.hify..mapper..", "com.hify..entity..",
                            "com.hify..controller..", "com.hify.*.dto..");
        // 注意 "com.hify.*.dto.." 只匹配模块级 dto/（单层通配符），不会误伤 api/dto/

    // @Transactional 只允许在 service 层：ArchUnit 的 fluent API 对「类上注解」与「方法上注解」
    // 分开表达，故拆成下面两条（noClasses 查类、noMethods 查方法），意图等价于「事务只在 service 层」。
    @ArchTest
    static final ArchRule 事务注解不在service层之外的类 = noClasses()
        .that().resideOutsideOfPackage("..service..")
        .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule 事务注解不在service层之外的方法 = noMethods()
        .that().areDeclaredInClassesThat().resideOutsideOfPackage("..service..")
        .should().beAnnotatedWith(Transactional.class);
}
```

> 骨架阶段大多数分层包（controller/service/mapper…）还没有类，ArchUnit 默认「规则目标类集合为空 =
> 判失败」（用于抓写错的包名）。需在 `src/test/resources/archunit.properties` 设
> `archRule.failOnEmptyShould=false` 关闭该行为；各层填入代码后规则自动生效。

## 6. 新增一个模块的步骤

1. 在本文第 1 节的表里加一行（职责 + 依赖白名单）。
2. 建包 `com.hify.<module>`，写 `package-info.java`（`allowedDependencies`）。
3. 建 `api/` + `api/package-info.java`（`@NamedInterface("api")`）。
4. 按第 2 节的结构建其余目录。
5. 跑 `ModularityTests`，确认通过后再写业务代码。
