# Workflow W1：执行引擎地基 + LLM 节点 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把「开始 → LLM → 结束」的最小工作流跑通：草稿定义存取、图校验、拓扑执行、变量传递、run/node_run 落库、同步触发 API、僵尸自愈。

**Architecture:** 进程内顺序执行器（spec 拍板方案 A）：graph jsonb → GraphValidator 校验+拓扑排序 → WorkflowEngine 逐节点驱动（NodeExecutor 接口，W1 仅 LlmNodeExecutor；start/end 引擎内建）→ WorkflowRunStore 短事务落库。同步阻塞返回完整 run。

**Tech Stack:** Spring Boot 3.x / Java 21、MyBatis-Plus、PostgreSQL 16（jsonb + 月分区）、Spring AI ChatClient（经 ProviderFacade）、JUnit5 + Mockito + Testcontainers（PgIntegrationTest 基类）。

**Spec:** `docs/superpowers/specs/2026-07-09-workflow-w1-engine-design.md`（决策表 §0 是唯一事实源）。

## Global Constraints

- 模块依赖只许白名单（workflow/package-info.java 已声明）：`app::api`、`provider::api`、`usage::api`、`common`、`infra`（conversation/knowledge/tool 白名单内但 W1 不用）。ModularityTests 强制。
- DTO 禁 import entity（ArchUnit）；投影写在 service 私有方法。entity 可 import dto（App→AppConfig 有先例）。
- `@Transactional` 内禁 LLM/外部 IO；落库集中在 WorkflowRunStore 的短事务方法。
- Controller 禁 try-catch、禁手写失败 Result；失败一律抛 `BizException`。
- 错误码：本轮仅新增 `18001/400 GRAPH_INVALID`；其余复用 `CommonError`（10001/10004/10005/10006）与 usage 的 14001。
- Long 全局序列化为 JSON string（infra JacksonConfig 已配，DTO 直接用 Long）。
- 枚举值小写字符串，与 DB check 一致：status ∈ `running/succeeded/failed`；节点 type ∈ `start/llm/end`。
- 节点重试固定 0；W1 不做引擎级节点超时（ChatClient 三层超时兜底）。
- 配额检查只在触发入口调一次 `UsageFacade.checkQuota(userId, appId)`。
- 已合并的 Flyway 脚本禁改；本轮新增 `V21__create_workflow_tables.sql`。
- 集成测试（继承 `com.hify.support.PgIntegrationTest`）需要本机 Docker 已启动。
- 测试命令一律 `mvn -f server/pom.xml test -Dtest=类名`，**不加 `-q`**，以输出中 `Tests run: N, Failures: 0, Errors: 0` + `BUILD SUCCESS` 为准（记忆 mvn-quiet-verify-pitfall）。
- 测试方法名用中文场景名（仓库既有风格，如 `列表_成员可访问_返回PageResult且Long为string`）。
- 每个 Task 结束都 commit，消息用仓库惯用的 `feat/test/docs(scope): 中文摘要`。

## 文件结构总览

```
server/src/main/resources/db/migration/V21__create_workflow_tables.sql   [Task 1]
server/src/main/java/com/hify/app/api/WorkflowAppView.java               [Task 2]
server/src/main/java/com/hify/app/api/AppFacade.java                     [Task 2 改]
server/src/main/java/com/hify/app/service/AppFacadeImpl.java             [Task 2 改]
server/src/main/java/com/hify/app/service/AppService.java                [Task 2 改：解锁 workflow 创建]
server/src/main/java/com/hify/app/constant/AppError.java                 [Task 2 改：16001 文案]
server/src/main/java/com/hify/workflow/
├── constant/WorkflowError.java  constant/NodeType.java  constant/RunStatus.java   [Task 3]
├── dto/GraphDef.java  GraphNode.java  GraphEdge.java                              [Task 3]
├── config/GraphDefTypeHandler.java  JsonbMapTypeHandler.java                      [Task 3]
├── config/WorkflowProperties.java                                                 [Task 4]
├── entity/WorkflowDef.java  WorkflowRun.java  WorkflowNodeRun.java                [Task 3]
├── mapper/WorkflowDefMapper.java  WorkflowRunMapper.java  WorkflowNodeRunMapper.java [Task 3]
├── service/engine/GraphValidator.java                                             [Task 4]
├── service/engine/RunContext.java                                                 [Task 5]
├── service/engine/NodeExecutor.java  NodeResult.java                              [Task 6]
├── service/engine/LlmCaller.java  LlmCallResult.java  LlmNodeExecutor.java        [Task 6]
├── service/WorkflowRunStore.java  ZombieRunResetter.java  WorkflowPartitionMaintainer.java [Task 7]
├── service/engine/WorkflowEngine.java  EngineResult.java                          [Task 8]
├── service/WorkflowDraftService.java  dto/SaveDraftRequest.java  DraftResponse.java [Task 9]
├── controller/WorkflowController.java（Task 9 建，Task 10 扩）
├── service/WorkflowRunService.java  RunCursor.java                                [Task 10]
└── dto/RunRequest.java  RunResponse.java  NodeRunView.java  RunSummaryView.java   [Task 10]
server/src/main/resources/application.yml（hify.workflow.max-nodes）               [Task 4 改]
docs/architecture/api-standards.md（单例子资源条款）                                [Task 11 改]
docs/self-check.md（本轮自检）                                                      [Task 11 改]
```

**跨任务接口契约**（后续任务按此签名消费，改名即 bug）：

```java
// Task 2 产出
public record WorkflowAppView(Long appId, Long ownerId, boolean enabled) {}          // com.hify.app.api
Optional<WorkflowAppView> AppFacade.findWorkflowApp(Long appId);

// Task 3 产出
public record GraphDef(List<GraphNode> nodes, List<GraphEdge> edges) {}              // com.hify.workflow.dto
public record GraphNode(String id, String type, Map<String, Object> data) {}
public record GraphEdge(String source, String target) {}
enum NodeType { START("start"), LLM("llm"), END("end"); String value(); static boolean supported(String v); }
enum RunStatus { RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed"); String value(); }
enum WorkflowError implements ErrorCode { GRAPH_INVALID(18001, BAD_REQUEST, "工作流图结构非法") }

// Task 4 产出
List<GraphNode> GraphValidator.validateAndOrder(GraphDef graph);   // 不合法抛 BizException(GRAPH_INVALID, 具体原因)

// Task 5 产出
class RunContext { RunContext(Long userId, Long appId); Long userId(); Long appId();
                   void putOutput(String nodeId, Map<String,Object> out);
                   Map<String,Object> getOutput(String nodeId);
                   String render(String template); }               // 引用缺失抛 IllegalStateException

// Task 6 产出
interface NodeExecutor { String type(); NodeResult execute(GraphNode node, RunContext ctx); }
record NodeResult(Map<String,Object> inputs, Map<String,Object> outputs) {}
record LlmCallResult(String text, int promptTokens, int completionTokens) {}
LlmCallResult LlmCaller.call(ChatClient client, String systemPrompt, String userPrompt);

// Task 7 产出
WorkflowRun WorkflowRunStore.createRun(Long appId, Long defId, Long userId, Map<String,Object> inputs);
void  WorkflowRunStore.markRunSucceeded(Long runId, Map<String,Object> outputs, long elapsedMs);
void  WorkflowRunStore.markRunFailed(Long runId, String errorMessage, long elapsedMs);
Long  WorkflowRunStore.createNodeRun(Long runId, String nodeId, String nodeType);
void  WorkflowRunStore.finishNodeRun(Long nodeRunId, boolean succeeded, Map<String,Object> inputs,
                                     Map<String,Object> outputs, String errorMessage, long elapsedMs);
int   WorkflowRunStore.resetZombies();

// Task 8 产出
EngineResult WorkflowEngine.execute(Long runId, List<GraphNode> ordered,
                                    Map<String,Object> inputs, RunContext ctx);
record EngineResult(boolean succeeded, Map<String,Object> outputs, String failedNodeId, String errorMessage)

// Task 9/10 产出（Controller 消费）
DraftResponse WorkflowDraftService.getDraft(Long appId);            // 无草稿返回 null
DraftResponse WorkflowDraftService.saveDraft(Long appId, GraphDef graph, CurrentUser user);
WorkflowDef   WorkflowDraftService.requireDraft(Long appId);        // 无草稿抛 10005
RunResponse   WorkflowRunService.run(Long appId, Map<String,Object> inputs, CurrentUser user);
RunResponse   WorkflowRunService.getRun(Long runId);
CursorResult<RunSummaryView> WorkflowRunService.listRuns(Long appId, String cursor, int limit);
```

---

### Task 1: Flyway V21 三张 workflow 表

**Files:**
- Create: `server/src/main/resources/db/migration/V21__create_workflow_tables.sql`
- Test: `server/src/test/java/com/hify/workflow/mapper/WorkflowSchemaTest.java`

**Interfaces:** 产出三张表 `workflow_def` / `workflow_run` / `workflow_node_run`（列名即后续 entity 映射依据）。

- [x] **Step 1: 写失败测试（断言表存在与关键约束）**

```java
package com.hify.workflow.mapper;

import com.hify.support.PgIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V21 迁移的真库验证：三表存在、node_run 是分区表且当月分区可写、(app_id,version) 部分唯一、status check。 */
class WorkflowSchemaTest extends PgIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 三张表存在() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_name in "
                        + "('workflow_def','workflow_run','workflow_node_run') order by table_name",
                String.class);
        assertEquals(List.of("workflow_def", "workflow_node_run", "workflow_run"), tables);
    }

    @Test
    void node_run按月分区且当月分区可写并能返回自增id() {
        // 分区表：relkind = 'p'
        String relkind = jdbc.queryForObject(
                "select relkind from pg_class where relname = 'workflow_node_run'", String.class);
        assertEquals("p", relkind);
        Long id = jdbc.queryForObject(
                "insert into workflow_node_run (run_id, node_id, node_type, status) "
                        + "values (1, 'llm_1', 'llm', 'running') returning id",
                Long.class);
        assertNotNull(id);
    }

    @Test
    void def的app加version软删内唯一() {
        jdbc.update("insert into workflow_def (app_id, version, graph) values (99, 1, '{}')");
        assertThrows(Exception.class, () ->
                jdbc.update("insert into workflow_def (app_id, version, graph) values (99, 1, '{}')"));
    }

    @Test
    void run状态check约束拒绝非法值() {
        // 先合法后非法：PG 里失败语句会把当前事务打进 aborted 状态，之后的语句全报错，顺序不能反
        int ok = jdbc.update(
                "insert into workflow_run (app_id, def_id, user_id, status) values (1, 1, 1, 'running')");
        assertEquals(1, ok);
        assertThrows(Exception.class, () -> jdbc.update(
                "insert into workflow_run (app_id, def_id, user_id, status) values (1, 1, 1, 'bogus')"));
    }

    @Test
    void run表autovacuum已调密() {
        String opts = jdbc.queryForObject(
                "select array_to_string(reloptions, ',') from pg_class where relname = 'workflow_run'",
                String.class);
        assertNotNull(opts);
        assertTrue(opts.contains("autovacuum_vacuum_scale_factor=0.05"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowSchemaTest`
Expected: FAIL（`三张表存在` 断言空列表 ≠ 期望；其余表不存在报 SQL 异常）

- [x] **Step 3: 写迁移脚本**

```sql
-- V21：workflow 三表（data-model.md §1）。跨模块 app_id/user_id 只存 id 不建外键（§3 条1），引用列必建索引（db-standards §2.1）。

-- 画布定义（草稿）。W1 每 app 恒一行 version=1；version 字段预留给发布轮。graph 整存整取不建 GIN（db-standards §2.4）。
create table workflow_def (
    id          bigint      generated always as identity primary key,
    app_id      bigint      not null,
    version     int         not null default 1,
    graph       jsonb       not null,
    deleted     boolean     not null default false,
    create_time timestamptz not null default now(),
    update_time timestamptz not null default now()
);
comment on table workflow_def is '工作流画布定义（workflow 模块）：graph 为 jsonb 整存整取；(app_id,version) 软删内唯一，W1 只有 version=1 草稿';
create index workflow_def_app_id_idx on workflow_def (app_id);
create unique index workflow_def_app_version_uq on workflow_def (app_id, version) where deleted = false;

-- 运行实例（状态机）。同步执行无 pending；scaling-path 阶段 2 的任务表即本表，届时 check 加值即可。
create table workflow_run (
    id            bigint      generated always as identity primary key,
    app_id        bigint      not null,
    def_id        bigint      not null,
    user_id       bigint      not null,
    status        text        not null check (status in ('running', 'succeeded', 'failed')),
    inputs        jsonb,
    outputs       jsonb,
    error_message text,
    elapsed_ms    bigint,
    deleted       boolean     not null default false,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now()
);
comment on table workflow_run is '工作流运行实例（workflow 模块）：状态机 running→succeeded/failed；启动自愈把遗留 running 置 failed';
-- db-standards §2.2 点名标配（阶段 2 任务抢占 where status=? order by create_time）
create index workflow_run_status_create_time_idx on workflow_run (status, create_time);
-- 游标分页：where app_id=? and (create_time,id)<(?,?) order by create_time desc, id desc
create index workflow_run_app_ct_id_idx on workflow_run (app_id, create_time, id);
create index workflow_run_def_id_idx on workflow_run (def_id);
create index workflow_run_user_id_idx on workflow_run (user_id);
-- 状态机反复 UPDATE，autovacuum 调密（db-standards §8）
alter table workflow_run set (autovacuum_vacuum_scale_factor = 0.05, autovacuum_analyze_scale_factor = 0.02);

-- 节点执行日志：高水位只增表，第一天按月分区（db-standards §3）；照抄 V12 llm_call_log 模式，pk 必含分区键。
-- 日志无软删（清理=drop 分区），但有 update_time（running→终态一次 UPDATE 收尾）。
create table workflow_node_run (
    id            bigint      generated always as identity,
    run_id        bigint      not null,
    node_id       text        not null,
    node_type     text        not null,
    status        text        not null check (status in ('running', 'succeeded', 'failed')),
    inputs        jsonb,
    outputs       jsonb,
    error_message text,
    elapsed_ms    bigint,
    create_time   timestamptz not null default now(),
    update_time   timestamptz not null default now(),
    primary key (id, create_time)
) partition by range (create_time);
comment on table workflow_node_run is '节点执行日志（workflow 模块）：按 create_time 月分区；inputs=变量替换后的实际输入';
create index workflow_node_run_run_id_idx on workflow_node_run (run_id);

-- 初始建 6 个月分区（2026-07 ~ 2026-12）；下月分区由 WorkflowPartitionMaintainer 每月补建。
create table workflow_node_run_2026_07 partition of workflow_node_run for values from ('2026-07-01') to ('2026-08-01');
create table workflow_node_run_2026_08 partition of workflow_node_run for values from ('2026-08-01') to ('2026-09-01');
create table workflow_node_run_2026_09 partition of workflow_node_run for values from ('2026-09-01') to ('2026-10-01');
create table workflow_node_run_2026_10 partition of workflow_node_run for values from ('2026-10-01') to ('2026-11-01');
create table workflow_node_run_2026_11 partition of workflow_node_run for values from ('2026-11-01') to ('2026-12-01');
create table workflow_node_run_2026_12 partition of workflow_node_run for values from ('2026-12-01') to ('2027-01-01');
```

- [x] **Step 4: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowSchemaTest`
Expected: PASS（Tests run: 5, Failures: 0, Errors: 0）

- [x] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V21__create_workflow_tables.sql server/src/test/java/com/hify/workflow/mapper/WorkflowSchemaTest.java
git commit -m "feat(workflow): V21 三表迁移（def/run/node_run 月分区+标配索引+autovacuum 调密），真库 schema 测试"
```

---

### Task 2: app 模块配合改动——findWorkflowApp + 解锁 workflow 应用创建

**Files:**
- Create: `server/src/main/java/com/hify/app/api/WorkflowAppView.java`
- Modify: `server/src/main/java/com/hify/app/api/AppFacade.java`（加一个方法）
- Modify: `server/src/main/java/com/hify/app/service/AppFacadeImpl.java`（加实现）
- Modify: `server/src/main/java/com/hify/app/service/AppService.java:54`（create 的 type 守卫放行 workflow）
- Modify: `server/src/main/java/com/hify/app/constant/AppError.java`（16001 提示语随之更新）
- Modify: `server/src/main/java/com/hify/app/dto/CreateAppRequest.java`（javadoc 里「仅 chat 放行」的注释更新）
- Test: `server/src/test/java/com/hify/app/service/AppFacadeWorkflowViewTest.java`
- Test: `server/src/test/java/com/hify/app/service/AppServiceCreateWorkflowTest.java`

**背景**：`AppService.create` 现在只放行 type=chat（传 workflow 抛 16001）——这是 app 轮刻意留的闸门。
W1 没有这个解锁，验收连 workflow 应用都建不出来。错误码 16001 语义「不支持的应用类型」不变
（只增不改的是 code 与语义；提示文案可修），仍用于拦截 chat/workflow 之外的非法值。

**Interfaces:**
- Consumes: 既有 `AppMapper`（MP BaseMapper）、`AppType.WORKFLOW.value()`、`AppStatus.ENABLED.value()`。
- Produces: `Optional<WorkflowAppView> findWorkflowApp(Long appId)`；`WorkflowAppView(Long appId, Long ownerId, boolean enabled)`。Task 9/10 消费。

- [x] **Step 1: 写失败测试**

```java
package com.hify.app.service;

import com.hify.app.api.WorkflowAppView;
import com.hify.app.entity.App;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** findWorkflowApp：存在且 type=workflow 才有值；enabled 如实透传；chat 应用/不存在返回 empty。 */
class AppFacadeWorkflowViewTest {

    private AppMapper appMapper;
    private AppFacadeImpl facade;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        facade = new AppFacadeImpl(appMapper, mock(AppDatasetRelMapper.class));
    }

    private App app(String type, String status) {
        App a = new App();
        a.setId(42L);
        a.setType(type);
        a.setStatus(status);
        a.setOwnerId(7L);
        return a;
    }

    @Test
    void workflow应用_返回视图含owner与enabled() {
        when(appMapper.selectById(42L)).thenReturn(app("workflow", "enabled"));
        WorkflowAppView v = facade.findWorkflowApp(42L).orElseThrow();
        assertEquals(42L, v.appId());
        assertEquals(7L, v.ownerId());
        assertTrue(v.enabled());
    }

    @Test
    void 停用的workflow应用_enabled为false但仍返回() {
        when(appMapper.selectById(42L)).thenReturn(app("workflow", "disabled"));
        assertFalse(facade.findWorkflowApp(42L).orElseThrow().enabled());
    }

    @Test
    void chat应用_返回empty() {
        when(appMapper.selectById(42L)).thenReturn(app("chat", "enabled"));
        assertTrue(facade.findWorkflowApp(42L).isEmpty());
    }

    @Test
    void 不存在或入参null_返回empty() {
        when(appMapper.selectById(any())).thenReturn(null);
        assertTrue(facade.findWorkflowApp(42L).isEmpty());
        assertTrue(facade.findWorkflowApp(null).isEmpty());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=AppFacadeWorkflowViewTest`
Expected: 编译错误（`WorkflowAppView` / `findWorkflowApp` 不存在）

- [x] **Step 3: 实现**

新建 `server/src/main/java/com/hify/app/api/WorkflowAppView.java`：

```java
package com.hify.app.api;

/**
 * 工作流应用视图（跨模块）：workflow 模块取它做存在性/owner/启停判断。
 * 位于 api 顶层包（Modulith 只暴露顶层，见记忆 modulith-api-dto-not-consumable）。
 * enabled=false 仍返回（草稿可继续编辑，是否可运行由 workflow 侧判定）。
 */
public record WorkflowAppView(Long appId, Long ownerId, boolean enabled) {
}
```

`AppFacade.java` 接口末尾加方法（javadoc 一并写）：

```java
    /**
     * 取一个「工作流应用」视图：应用存在（未删）且 type=workflow 才有值，<b>不过滤启停</b>——
     * enabled 原样透传，草稿编辑不看启停、触发运行由 workflow 侧拒绝 disabled。
     */
    Optional<WorkflowAppView> findWorkflowApp(Long appId);
```

`AppFacadeImpl.java` 加实现（import `com.hify.app.api.WorkflowAppView`）：

```java
    @Override
    public Optional<WorkflowAppView> findWorkflowApp(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        App app = appMapper.selectById(appId);
        if (app == null || !AppType.WORKFLOW.value().equals(app.getType())) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowAppView(app.getId(), app.getOwnerId(),
                AppStatus.ENABLED.value().equals(app.getStatus())));
    }
```

- [x] **Step 4: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=AppFacadeWorkflowViewTest`
Expected: PASS（Tests run: 4）

- [x] **Step 5: 写失败测试（解锁 workflow 创建）**

```java
package com.hify.app.service;

import com.hify.app.dto.AppResponse;
import com.hify.app.dto.CreateAppRequest;
import com.hify.app.mapper.AppDatasetRelMapper;
import com.hify.app.mapper.AppMapper;
import com.hify.common.exception.BizException;
import com.hify.infra.security.CurrentUser;
import com.hify.knowledge.api.KnowledgeFacade;
import com.hify.provider.api.ProviderFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** W1 解锁：type=workflow 可创建（modelId/datasetIds 与其无关可为空）；非法 type 仍 16001。 */
class AppServiceCreateWorkflowTest {

    private AppMapper appMapper;
    private AppService service;
    private final CurrentUser member = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        service = new AppService(appMapper, mock(ProviderFacade.class),
                mock(AppDatasetRelMapper.class), mock(KnowledgeFacade.class));
    }

    @Test
    void 创建workflow应用_放行并落库() {
        AppResponse resp = service.create(
                new CreateAppRequest("工单分类器", null, "workflow", null, null, null), member);
        verify(appMapper).insert(any(com.hify.app.entity.App.class));
        assertEquals("workflow", resp.type());
    }

    @Test
    void 非法type_仍报16001() {
        BizException ex = assertThrows(BizException.class, () -> service.create(
                new CreateAppRequest("x", null, "bogus", null, null, null), member));
        assertEquals(16001, ex.errorCode().code());
    }
}
```

> 若 `AppResponse` 的 type 访问器不叫 `type()`，以实际 record 组件名为准微调断言（不改生产代码）。

- [x] **Step 6: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=AppServiceCreateWorkflowTest`
Expected: FAIL——`创建workflow应用_放行并落库` 抛 16001（守卫还在）

- [x] **Step 7: 解锁实现**

`AppService.java` create 里的守卫改为（原来只认 CHAT）：

```java
        if (!AppType.CHAT.value().equals(req.type()) && !AppType.WORKFLOW.value().equals(req.type())) {
            throw new BizException(AppError.APP_TYPE_NOT_SUPPORTED);
        }
```

`AppError.java` 的 16001 注释与文案更新（code/语义不变，仍是「类型不支持」）：

```java
    /** 应用类型不在支持范围（chat/workflow 之外的值）。W1 起 workflow 放行。 */
    APP_TYPE_NOT_SUPPORTED(16001, HttpStatus.BAD_REQUEST, "不支持的应用类型"),
```

`CreateAppRequest.java` javadoc 中「type 本轮仅 'chat' 放行（service 判，workflow→16001）」改为
「type 支持 'chat' / 'workflow'（W1 起）；其余值 service 判 16001」。

- [x] **Step 8: 运行确认通过（含既有 AppServiceTest 无回归）**

Run: `mvn -f server/pom.xml test -Dtest='AppServiceCreateWorkflowTest,AppServiceTest,AppFacadeWorkflowViewTest'`
Expected: PASS。若 `AppServiceTest` 里有断言「workflow 被拒」的旧用例，按新行为改该断言（16001 现在只拦 bogus 类型）。

- [x] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/app server/src/test/java/com/hify/app
git commit -m "feat(app): findWorkflowApp 门面 + 解锁 workflow 应用创建（16001 只拦未知类型）"
```

---

### Task 3: workflow 骨架——常量 / GraphDef / TypeHandler / 实体 / Mapper

**Files:**
- Create: `server/src/main/java/com/hify/workflow/constant/WorkflowError.java`、`constant/NodeType.java`、`constant/RunStatus.java`
- Create: `server/src/main/java/com/hify/workflow/dto/GraphDef.java`、`dto/GraphNode.java`、`dto/GraphEdge.java`
- Create: `server/src/main/java/com/hify/workflow/config/GraphDefTypeHandler.java`、`config/JsonbMapTypeHandler.java`
- Create: `server/src/main/java/com/hify/workflow/entity/WorkflowDef.java`、`entity/WorkflowRun.java`、`entity/WorkflowNodeRun.java`
- Create: `server/src/main/java/com/hify/workflow/mapper/WorkflowDefMapper.java`、`mapper/WorkflowRunMapper.java`、`mapper/WorkflowNodeRunMapper.java`
- Test: `server/src/test/java/com/hify/workflow/mapper/WorkflowMapperRoundtripTest.java`

**Interfaces:**
- Consumes: `com.hify.common.BaseEntity`、`com.hify.common.exception.ErrorCode`；jsonb 写出模式照抄 `AppConfigTypeHandler`（PGobject type=jsonb）。
- Produces: 总览「跨任务接口契约」中 Task 3 的全部类型；`WorkflowDefMapper/WorkflowRunMapper/WorkflowNodeRunMapper extends BaseMapper<...>`。

- [x] **Step 1: 写失败测试（jsonb 读写往返 + 分区表 MP 插入回填 id）**

```java
package com.hify.workflow.mapper;

import com.hify.support.PgIntegrationTest;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 三实体经 MP + TypeHandler 的真库读写往返：graph/inputs/outputs jsonb 不失真；分区表插入能回填自增 id。 */
class WorkflowMapperRoundtripTest extends PgIntegrationTest {

    @Autowired
    private WorkflowDefMapper defMapper;
    @Autowired
    private WorkflowRunMapper runMapper;
    @Autowired
    private WorkflowNodeRunMapper nodeRunMapper;

    @Test
    void def的graph往返不失真() {
        GraphDef graph = new GraphDef(
                List.of(new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                        new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                        new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        WorkflowDef def = new WorkflowDef();
        def.setAppId(1L);
        def.setVersion(1);
        def.setGraph(graph);
        defMapper.insert(def);
        assertNotNull(def.getId());

        GraphDef loaded = defMapper.selectById(def.getId()).getGraph();
        assertEquals(3, loaded.nodes().size());
        assertEquals("llm", loaded.nodes().get(1).type());
        assertEquals("{{start.query}}", loaded.nodes().get(1).data().get("userPrompt"));
        assertEquals("end", loaded.edges().get(1).target());
    }

    @Test
    void run的inputs_outputs往返不失真() {
        WorkflowRun run = new WorkflowRun();
        run.setAppId(1L);
        run.setDefId(1L);
        run.setUserId(7L);
        run.setStatus("running");
        run.setInputs(Map.of("query", "我要退货"));
        runMapper.insert(run);
        assertNotNull(run.getId());

        WorkflowRun loaded = runMapper.selectById(run.getId());
        assertEquals("我要退货", loaded.getInputs().get("query"));
        assertEquals("running", loaded.getStatus());
    }

    @Test
    void node_run分区表插入回填id_updateById可收尾() {
        WorkflowNodeRun nr = new WorkflowNodeRun();
        nr.setRunId(1L);
        nr.setNodeId("llm_1");
        nr.setNodeType("llm");
        nr.setStatus("running");
        nodeRunMapper.insert(nr);
        assertNotNull(nr.getId());

        WorkflowNodeRun patch = new WorkflowNodeRun();
        patch.setId(nr.getId());
        patch.setStatus("succeeded");
        patch.setOutputs(Map.of("text", "退款类"));
        patch.setElapsedMs(1200L);
        nodeRunMapper.updateById(patch);

        WorkflowNodeRun loaded = nodeRunMapper.selectById(nr.getId());
        assertEquals("succeeded", loaded.getStatus());
        assertEquals("退款类", loaded.getOutputs().get("text"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowMapperRoundtripTest`
Expected: 编译错误（实体/Mapper 不存在）

- [x] **Step 3: 写常量与 DTO**

`constant/WorkflowError.java`：

```java
package com.hify.workflow.constant;

import com.hify.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** workflow 模块错误码（18xxx 段，api-standards §5.2）。只放本模块特有语义；不存在/权限/配额复用通用段与 usage 段。 */
public enum WorkflowError implements ErrorCode {

    /** 图结构非法（环/断连/缺 start/end/未知类型/变量引用越界等，message 带具体原因）。 */
    GRAPH_INVALID(18001, HttpStatus.BAD_REQUEST, "工作流图结构非法");

    private final int code;
    private final HttpStatus status;
    private final String defaultMessage;

    WorkflowError(int code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() { return code; }

    @Override
    public HttpStatus status() { return status; }

    @Override
    public String defaultMessage() { return defaultMessage; }
}
```

`constant/NodeType.java`：

```java
package com.hify.workflow.constant;

import java.util.Arrays;

/** 节点类型，值与 graph jsonb 的 node.type 及 workflow_node_run.node_type check 一致。 */
public enum NodeType {
    START("start"),
    LLM("llm"),
    END("end");

    private final String value;

    NodeType(String value) { this.value = value; }

    public String value() { return value; }

    /** graph 校验用：type 字符串是否为已支持的节点类型。 */
    public static boolean supported(String v) {
        return Arrays.stream(values()).anyMatch(t -> t.value.equals(v));
    }
}
```

`constant/RunStatus.java`：

```java
package com.hify.workflow.constant;

/** 运行/节点状态机，值与 DB check 一致。同步执行无 pending（scaling-path 阶段 2 再加）。 */
public enum RunStatus {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    private final String value;

    RunStatus(String value) { this.value = value; }

    public String value() { return value; }
}
```

`dto/GraphDef.java` / `dto/GraphNode.java` / `dto/GraphEdge.java`：

```java
package com.hify.workflow.dto;

import java.util.List;

/**
 * 画布定义（workflow_def.graph jsonb 的 Java 形态），字段名对齐 Vue Flow（画布轮零转换）。
 * 未来画布轮 additive 加 position 等字段；未知字段全局忽略（Jackson 配置）。
 */
public record GraphDef(List<GraphNode> nodes, List<GraphEdge> edges) {
}
```

```java
package com.hify.workflow.dto;

import java.util.Map;

/** 画布节点：type ∈ NodeType；data 为节点私有配置（llm: modelId/systemPrompt/userPrompt；start: inputs；end: outputs）。 */
public record GraphNode(String id, String type, Map<String, Object> data) {
}
```

```java
package com.hify.workflow.dto;

/** 画布连线。预留 sourceHandle 给条件分支轮（W1 不解析）。 */
public record GraphEdge(String source, String target) {
}
```

- [x] **Step 4: 写 TypeHandler**

`config/GraphDefTypeHandler.java`（照抄 `AppConfigTypeHandler` 模式）：

```java
package com.hify.workflow.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hify.workflow.dto.GraphDef;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * workflow_def.graph（jsonb）↔ {@link GraphDef}。写出包成 PGobject(type=jsonb)；
 * 读入未知字段忽略（客户端可比服务端新）。实体需 @TableName(autoResultMap=true)。
 */
public class GraphDefTypeHandler extends BaseTypeHandler<GraphDef> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, GraphDef parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 workflow_def.graph 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public GraphDef getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public GraphDef getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public GraphDef getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private GraphDef parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, GraphDef.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 workflow_def.graph 失败", e);
        }
    }
}
```

`config/JsonbMapTypeHandler.java`：

```java
package com.hify.workflow.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** 通用 jsonb ↔ Map&lt;String,Object&gt;（run/node_run 的 inputs/outputs）。null 列返回 null（「无」≠ 空对象）。 */
public class JsonbMapTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, Object> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject obj = new PGobject();
        obj.setType("jsonb");
        try {
            obj.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("序列化 jsonb Map 失败", e);
        }
        ps.setObject(i, obj);
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Map<String, Object> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("反序列化 jsonb Map 失败", e);
        }
    }
}
```

- [x] **Step 5: 写实体与 Mapper**

`entity/WorkflowDef.java`：

```java
package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.workflow.config.GraphDefTypeHandler;
import com.hify.workflow.dto.GraphDef;

/** workflow_def 映射。graph 经 GraphDefTypeHandler 读写（autoResultMap=true 才在查询映射时生效）。 */
@TableName(value = "workflow_def", autoResultMap = true)
public class WorkflowDef extends BaseEntity {

    private Long appId;
    private Integer version;

    @TableField(typeHandler = GraphDefTypeHandler.class)
    private GraphDef graph;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public GraphDef getGraph() { return graph; }
    public void setGraph(GraphDef graph) { this.graph = graph; }
}
```

`entity/WorkflowRun.java`：

```java
package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.BaseEntity;
import com.hify.workflow.config.JsonbMapTypeHandler;

import java.util.Map;

/** workflow_run 映射。status 存小写字符串（RunStatus）。inputs/outputs jsonb 经 JsonbMapTypeHandler。 */
@TableName(value = "workflow_run", autoResultMap = true)
public class WorkflowRun extends BaseEntity {

    private Long appId;
    private Long defId;
    private Long userId;
    private String status;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> inputs;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> outputs;

    private String errorMessage;
    private Long elapsedMs;

    public Long getAppId() { return appId; }
    public void setAppId(Long appId) { this.appId = appId; }

    public Long getDefId() { return defId; }
    public void setDefId(Long defId) { this.defId = defId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) { this.inputs = inputs; }

    public Map<String, Object> getOutputs() { return outputs; }
    public void setOutputs(Map<String, Object> outputs) { this.outputs = outputs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
}
```

`entity/WorkflowNodeRun.java`（**不继承 BaseEntity**：日志表无 deleted 列，继承会让 MP 生成 `deleted=false` 过滤直接 SQL 报错；create/update_time 自带 fill 注解）：

```java
package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.workflow.config.JsonbMapTypeHandler;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * workflow_node_run 映射。分区日志表：无 deleted（清理=drop 分区），故不继承 BaseEntity。
 * DB 主键是 (id, create_time)（分区键必须入 pk）；MP 仍按 id 定位（updateById 跨分区扫 id 索引，W1 量级无碍）。
 */
@TableName(value = "workflow_node_run", autoResultMap = true)
public class WorkflowNodeRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String nodeId;
    private String nodeType;
    private String status;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> inputs;

    @TableField(typeHandler = JsonbMapTypeHandler.class)
    private Map<String, Object> outputs;

    private String errorMessage;
    private Long elapsedMs;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) { this.inputs = inputs; }

    public Map<String, Object> getOutputs() { return outputs; }
    public void setOutputs(Map<String, Object> outputs) { this.outputs = outputs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }

    public OffsetDateTime getCreateTime() { return createTime; }
    public void setCreateTime(OffsetDateTime createTime) { this.createTime = createTime; }

    public OffsetDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(OffsetDateTime updateTime) { this.updateTime = updateTime; }
}
```

三个 Mapper（`@MapperScan("com.hify.**.mapper")` 自动注册，无需注解）：

```java
package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowDef;

/** workflow_def 访问。graph 是大列：列表场景禁 select *（W1 只有按 app 单条读，不受影响）。 */
public interface WorkflowDefMapper extends BaseMapper<WorkflowDef> {
}
```

```java
package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowRun;

/** workflow_run 访问。游标分页/僵尸重置的手写 SQL 在 Task 7/10 补充。 */
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {
}
```

```java
package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowNodeRun;

/** workflow_node_run 访问（分区日志表）。分区补建/僵尸重置的手写 SQL 在 Task 7 补充。 */
public interface WorkflowNodeRunMapper extends BaseMapper<WorkflowNodeRun> {
}
```

- [x] **Step 6: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowMapperRoundtripTest`
Expected: PASS（Tests run: 3）。若 `node_run分区表插入回填id` 失败在「id 未回填」，改用手写 `@Insert ... returning`＋`@Options(useGeneratedKeys=true)` 兜底——但 PG JDBC 对分区表 RETURNING 原生支持，正常应直接过。

- [x] **Step 7: Commit**

```bash
git add server/src/main/java/com/hify/workflow server/src/test/java/com/hify/workflow/mapper/WorkflowMapperRoundtripTest.java
git commit -m "feat(workflow): 常量/GraphDef/jsonb TypeHandler/三实体三Mapper，真库读写往返测试"
```

---

### Task 4: GraphValidator（校验 + 拓扑排序）与 max-nodes 配置

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`
- Create: `server/src/main/java/com/hify/workflow/config/WorkflowProperties.java`
- Modify: `server/src/main/resources/application.yml`（`hify:` 段下加 `workflow.max-nodes`）
- Test: `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`

**Interfaces:**
- Consumes: Task 3 的 `GraphDef/GraphNode/GraphEdge/NodeType/WorkflowError`。
- Produces: `List<GraphNode> validateAndOrder(GraphDef graph)`——合法返回拓扑序节点列表，不合法抛 `BizException(GRAPH_INVALID, "工作流图结构非法：<原因>")`。

**校验规则（spec §4，逐条对应一个测试）**：
① graph/nodes 非空；② 节点数 ≤ maxNodes（默认 50）；③ 节点 id 非空且唯一；④ 恰好一个 start、一个 end；
⑤ 节点 type 已注册（NodeType.supported）；⑥ llm 节点 data 含非空 modelId（可解析为 long）与 userPrompt；
⑦ edges 的 source/target 都是存在的节点 id；⑧ 每个节点从 start 可达且可达 end（无孤儿/游离）；
⑨ 无环（Kahn 拓扑排序，排完数量 < 节点数即有环）；⑩ `{{x.y}}` 引用的 x 必须是拓扑序中位于本节点之前的节点
（执行是顺序的，"排在前面"即"输出已就绪"）——扫描 data 里所有字符串值（含嵌套 List/Map）。

- [x] **Step 1: 写失败测试**

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphValidatorTest {

    private GraphValidator validator;

    @BeforeEach
    void setUp() {
        WorkflowProperties props = new WorkflowProperties();
        props.setMaxNodes(50);
        validator = new GraphValidator(props);
    }

    private GraphNode start() {
        return new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true))));
    }

    private GraphNode llm(String id, String userPrompt) {
        return new GraphNode(id, "llm", Map.of("modelId", "3", "userPrompt", userPrompt));
    }

    private GraphNode end(String value) {
        return new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", value))));
    }

    private GraphDef legal() {
        return new GraphDef(List.of(start(), llm("llm_1", "{{start.query}}"), end("{{llm_1.text}}")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    private String failMessage(GraphDef graph) {
        BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(graph));
        assertEquals(18001, ex.errorCode().code());
        return ex.getMessage();
    }

    @Test
    void 合法线性图_返回拓扑序() {
        List<GraphNode> ordered = validator.validateAndOrder(legal());
        assertEquals(List.of("start", "llm_1", "end"), ordered.stream().map(GraphNode::id).toList());
    }

    @Test
    void 空图_报18001() {
        assertTrue(failMessage(new GraphDef(List.of(), List.of())).contains("节点"));
        assertTrue(failMessage(null).contains("节点"));
    }

    @Test
    void 超过节点数上限_报18001() {
        List<GraphNode> nodes = new ArrayList<>(List.of(start()));
        List<GraphEdge> edges = new ArrayList<>();
        String prev = "start";
        for (int i = 1; i <= 50; i++) {          // start + 50 个 llm + end = 52 > 50
            nodes.add(llm("llm_" + i, "hi"));
            edges.add(new GraphEdge(prev, "llm_" + i));
            prev = "llm_" + i;
        }
        nodes.add(end("{{llm_50.text}}"));
        edges.add(new GraphEdge(prev, "end"));
        assertTrue(failMessage(new GraphDef(nodes, edges)).contains("上限"));
    }

    @Test
    void 节点id重复_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("dup", "hi"), llm("dup", "hi"), end("x")),
                List.of(new GraphEdge("start", "dup"), new GraphEdge("dup", "end")));
        assertTrue(failMessage(g).contains("重复"));
    }

    @Test
    void 缺start或多个end_报18001() {
        GraphDef noStart = new GraphDef(List.of(llm("llm_1", "hi"), end("x")),
                List.of(new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(noStart).contains("start"));
        GraphDef twoEnd = new GraphDef(
                List.of(start(), new GraphNode("end", "end", Map.of()), new GraphNode("end2", "end", Map.of())),
                List.of(new GraphEdge("start", "end"), new GraphEdge("start", "end2")));
        assertTrue(failMessage(twoEnd).contains("end"));
    }

    @Test
    void 未知节点类型_报18001() {
        GraphDef g = new GraphDef(List.of(start(), new GraphNode("x", "magic", Map.of()), end("v")),
                List.of(new GraphEdge("start", "x"), new GraphEdge("x", "end")));
        assertTrue(failMessage(g).contains("magic"));
    }

    @Test
    void llm节点缺modelId或userPrompt_报18001() {
        GraphNode noModel = new GraphNode("llm_1", "llm", Map.of("userPrompt", "hi"));
        GraphDef g1 = new GraphDef(List.of(start(), noModel, end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g1).contains("modelId"));
        GraphNode noPrompt = new GraphNode("llm_1", "llm", Map.of("modelId", "3"));
        GraphDef g2 = new GraphDef(List.of(start(), noPrompt, end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g2).contains("userPrompt"));
    }

    @Test
    void 边引用不存在的节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), end("x")),
                List.of(new GraphEdge("start", "ghost"), new GraphEdge("ghost", "end")));
        assertTrue(failMessage(g).contains("ghost"));
    }

    @Test
    void 游离节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "hi"), llm("island", "hi"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"),
                        new GraphEdge("start", "island")));   // island 到不了 end
        assertTrue(failMessage(g).contains("island"));
    }

    @Test
    void 有环_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("a", "hi"), llm("b", "hi"), end("x")),
                List.of(new GraphEdge("start", "a"), new GraphEdge("a", "b"), new GraphEdge("b", "a"),
                        new GraphEdge("b", "end")));
        assertTrue(failMessage(g).contains("环"));
    }

    @Test
    void 变量引用下游节点_报18001() {
        // llm_1 引用了排在自己后面的 end 的输出
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "{{end.answer}}"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g).contains("end"));
    }

    @Test
    void 变量引用不存在的节点_报18001() {
        GraphDef g = new GraphDef(List.of(start(), llm("llm_1", "{{ghost.text}}"), end("x")),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
        assertTrue(failMessage(g).contains("ghost"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=GraphValidatorTest`
Expected: 编译错误（GraphValidator / WorkflowProperties 不存在）

- [x] **Step 3: 实现 WorkflowProperties + application.yml**

`config/WorkflowProperties.java`：

```java
package com.hify.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** workflow 模块配置外化（CLAUDE.md：不硬编码）。 */
@Component
@ConfigurationProperties(prefix = "hify.workflow")
public class WorkflowProperties {

    /** 单个画布的节点数上限（防巨型 graph 存库与失控执行）。 */
    private int maxNodes = 50;

    public int getMaxNodes() { return maxNodes; }
    public void setMaxNodes(int maxNodes) { this.maxNodes = maxNodes; }
}
```

`application.yml` 的 `hify:` 段下追加（保持既有缩进风格）：

```yaml
  workflow:
    # 单画布节点数上限：图校验用，超限报 18001
    max-nodes: 50
```

- [x] **Step 4: 实现 GraphValidator**

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.constant.WorkflowError;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画布图校验 + 拓扑排序（保存草稿与触发运行共用，spec §4）。
 * 变量引用规则按执行语义定义：引用的节点必须排在拓扑序中本节点之前（顺序执行=前面的输出必已就绪）。
 */
@Component
public class GraphValidator {

    /** {{nodeId.field}}，与 RunContext.render 同一语法。 */
    static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w-]+)\\.([\\w-]+)\\s*}}");

    private final WorkflowProperties props;

    public GraphValidator(WorkflowProperties props) {
        this.props = props;
    }

    public List<GraphNode> validateAndOrder(GraphDef graph) {
        if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
            throw invalid("至少需要一个节点");
        }
        List<GraphNode> nodes = graph.nodes();
        List<GraphEdge> edges = graph.edges() == null ? List.of() : graph.edges();
        if (nodes.size() > props.getMaxNodes()) {
            throw invalid("节点数超过上限 " + props.getMaxNodes());
        }

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            if (n.id() == null || n.id().isBlank()) {
                throw invalid("存在缺少 id 的节点");
            }
            if (byId.put(n.id(), n) != null) {
                throw invalid("节点 id 重复：" + n.id());
            }
            if (!NodeType.supported(n.type())) {
                throw invalid("未知节点类型：" + n.type());
            }
            if (NodeType.LLM.value().equals(n.type())) {
                requireLlmField(n, "modelId");
                requireLlmField(n, "userPrompt");
            }
        }
        requireExactlyOne(nodes, NodeType.START.value());
        requireExactlyOne(nodes, NodeType.END.value());

        Map<String, List<String>> next = new HashMap<>();
        Map<String, List<String>> prev = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        byId.keySet().forEach(id -> inDegree.put(id, 0));
        for (GraphEdge e : edges) {
            if (!byId.containsKey(e.source()) || !byId.containsKey(e.target())) {
                throw invalid("连线引用不存在的节点：" + e.source() + " → " + e.target());
            }
            next.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            prev.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e.source());
            inDegree.merge(e.target(), 1, Integer::sum);
        }

        // 连通性：从 start 正向可达 ∩ 到 end 反向可达，缺一即游离
        Set<String> fromStart = reach(NodeType.START.value(), next);
        Set<String> toEnd = reach(NodeType.END.value(), prev);
        for (String id : byId.keySet()) {
            if (!fromStart.contains(id) || !toEnd.contains(id)) {
                throw invalid("节点游离在 start→end 路径之外：" + id);
            }
        }

        // Kahn 拓扑排序（按 nodes 声明序出队，结果确定）
        List<GraphNode> ordered = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        byId.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(queue::add);
        Map<String, Integer> degree = new HashMap<>(inDegree);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ordered.add(byId.get(id));
            for (String t : next.getOrDefault(id, List.of())) {
                if (degree.merge(t, -1, Integer::sum) == 0) {
                    queue.add(t);
                }
            }
        }
        if (ordered.size() < byId.size()) {
            throw invalid("图中存在环");
        }

        // 变量引用只许指向拓扑序更早的节点
        Set<String> seen = new HashSet<>();
        for (GraphNode n : ordered) {
            for (String ref : referencedNodeIds(n.data())) {
                if (!seen.contains(ref)) {
                    throw invalid("节点 " + n.id() + " 引用了未就绪的节点输出：" + ref);
                }
            }
            seen.add(n.id());
        }
        return ordered;
    }

    private void requireLlmField(GraphNode n, String field) {
        Object v = n.data() == null ? null : n.data().get(field);
        if (v == null || String.valueOf(v).isBlank()) {
            throw invalid("llm 节点 " + n.id() + " 缺少 " + field);
        }
        if ("modelId".equals(field)) {
            try {
                Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException e) {
                throw invalid("llm 节点 " + n.id() + " 的 modelId 不是合法数字");
            }
        }
    }

    private void requireExactlyOne(List<GraphNode> nodes, String type) {
        long count = nodes.stream().filter(n -> type.equals(n.type())).count();
        if (count != 1) {
            throw invalid("必须恰好一个 " + type + " 节点，当前 " + count + " 个");
        }
    }

    private Set<String> reach(String from, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (visited.add(id)) {
                adjacency.getOrDefault(id, List.of()).forEach(stack::push);
            }
        }
        return visited;
    }

    /** 递归扫 data 中所有字符串值里的 {{x.y}}，收集被引用的节点 id。 */
    private Set<String> referencedNodeIds(Object value) {
        Set<String> refs = new HashSet<>();
        collectRefs(value, refs);
        return refs;
    }

    private void collectRefs(Object value, Set<String> refs) {
        switch (value) {
            case null -> { }
            case String s -> {
                Matcher m = VAR.matcher(s);
                while (m.find()) {
                    refs.add(m.group(1));
                }
            }
            case Map<?, ?> map -> map.values().forEach(v -> collectRefs(v, refs));
            case Collection<?> coll -> coll.forEach(v -> collectRefs(v, refs));
            default -> { }
        }
    }

    private BizException invalid(String reason) {
        return new BizException(WorkflowError.GRAPH_INVALID, "工作流图结构非法：" + reason);
    }
}
```

- [x] **Step 5: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=GraphValidatorTest`
Expected: PASS（Tests run: 12）

- [x] **Step 6: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java server/src/main/java/com/hify/workflow/config/WorkflowProperties.java server/src/main/resources/application.yml server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java
git commit -m "feat(workflow): GraphValidator 十条校验+Kahn拓扑排序，max-nodes 配置外化"
```

---

### Task 5: RunContext（运行上下文 + 变量替换）

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/RunContext.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/RunContextTest.java`

**Interfaces:**
- Produces: 见总览契约 Task 5 行。`render` 对引用不存在的节点/字段抛 `IllegalStateException`（运行期兜底；
  节点级存在性校验已由 GraphValidator 前置，字段级只能运行期发现）。

- [x] **Step 1: 写失败测试**

```java
package com.hify.workflow.service.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunContextTest {

    private RunContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("query", "我要退货"));
        ctx.putOutput("llm_1", Map.of("text", "退款类"));
    }

    @Test
    void 持有触发者与应用() {
        assertEquals(7L, ctx.userId());
        assertEquals(42L, ctx.appId());
    }

    @Test
    void 单变量替换() {
        assertEquals("请分类：我要退货", ctx.render("请分类：{{start.query}}"));
    }

    @Test
    void 同串多变量与空白容忍() {
        assertEquals("我要退货→退款类", ctx.render("{{ start.query }}→{{llm_1.text}}"));
    }

    @Test
    void 无变量原样返回_null入参返回null() {
        assertEquals("plain", ctx.render("plain"));
        assertNull(ctx.render(null));
    }

    @Test
    void 引用不存在的节点_抛IllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ctx.render("{{ghost.text}}"));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void 引用不存在的字段_抛IllegalState() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ctx.render("{{llm_1.score}}"));
        assertTrue(ex.getMessage().contains("score"));
    }

    @Test
    void 非字符串输出值转字符串拼入() {
        ctx.putOutput("n", Map.of("count", 3));
        assertEquals("共3条", ctx.render("共{{n.count}}条"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=RunContextTest`
Expected: 编译错误（RunContext 不存在）

- [x] **Step 3: 实现**

```java
package com.hify.workflow.service.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * 一次运行的内存上下文：节点输出按 nodeId 存放，{{nodeId.field}} 从这里做字符串替换。
 * 非线程安全——引擎单线程顺序执行，一次 run 一个实例，不共享。
 */
public class RunContext {

    private final Long userId;
    private final Long appId;
    private final Map<String, Map<String, Object>> outputs = new HashMap<>();

    public RunContext(Long userId, Long appId) {
        this.userId = userId;
        this.appId = appId;
    }

    public Long userId() { return userId; }

    public Long appId() { return appId; }

    public void putOutput(String nodeId, Map<String, Object> out) {
        outputs.put(nodeId, out == null ? Map.of() : out);
    }

    public Map<String, Object> getOutput(String nodeId) {
        return outputs.get(nodeId);
    }

    /** 模板替换；引用缺失抛 IllegalStateException（引擎捕获后按节点失败处理）。null 模板原样返回。 */
    public String render(String template) {
        if (template == null) {
            return null;
        }
        Matcher m = GraphValidator.VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String nodeId = m.group(1);
            String field = m.group(2);
            Map<String, Object> out = outputs.get(nodeId);
            if (out == null) {
                throw new IllegalStateException("变量引用的节点无输出：" + nodeId);
            }
            if (!out.containsKey(field)) {
                throw new IllegalStateException("变量引用的字段不存在：" + nodeId + "." + field);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(out.get(field))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

- [x] **Step 4: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=RunContextTest`
Expected: PASS（Tests run: 7）

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/RunContext.java server/src/test/java/com/hify/workflow/service/engine/RunContextTest.java
git commit -m "feat(workflow): RunContext 运行上下文与 {{node.field}} 变量替换"
```

---

### Task 6: NodeExecutor 接口 + LlmCaller + LlmNodeExecutor

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/NodeExecutor.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/NodeResult.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/LlmCallResult.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/LlmCaller.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/LlmNodeExecutor.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/LlmNodeExecutorTest.java`

**Interfaces:**
- Consumes: Task 3 的 `GraphNode/NodeType`、Task 5 的 `RunContext`；`ProviderFacade.getChatClient(Long)`（不可用抛 BizException(12xxx)）；`com.hify.common.event.TokenUsedEvent`。
- Produces: 见总览契约 Task 6 行。LLM 节点输出字段固定 `text`（spec §1.4）。

**分层理由**：`LlmCaller` 是 ChatClient 流式 API 的薄适配（仿 conversation 的 `ChatInvoker` 先例）——
真实外部 IO 不做单测；`LlmNodeExecutor` 的逻辑（渲染提示词、组装输入快照、发计量事件）全部可 mock 单测。

- [x] **Step 1: 写失败测试**

```java
package com.hify.workflow.service.engine;

import com.hify.common.event.TokenUsedEvent;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.provider.api.ProviderFacade;
import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmNodeExecutorTest {

    private ProviderFacade providerFacade;
    private LlmCaller llmCaller;
    private ApplicationEventPublisher events;
    private LlmNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        providerFacade = mock(ProviderFacade.class);
        llmCaller = mock(LlmCaller.class);
        events = mock(ApplicationEventPublisher.class);
        executor = new LlmNodeExecutor(providerFacade, llmCaller, events);
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("start", Map.of("query", "我要退货"));
    }

    private GraphNode node(Map<String, Object> data) {
        return new GraphNode("llm_1", "llm", data);
    }

    @Test
    void 渲染提示词_调用模型_输出text_发计量事件() {
        ChatClient client = mock(ChatClient.class);
        when(providerFacade.getChatClient(3L)).thenReturn(client);
        when(llmCaller.call(eq(client), eq("你是客服"), eq("分类：我要退货")))
                .thenReturn(new LlmCallResult("退款类", 10, 5));

        NodeResult result = executor.execute(node(Map.of(
                "modelId", "3", "systemPrompt", "你是客服", "userPrompt", "分类：{{start.query}}")), ctx);

        assertEquals("退款类", result.outputs().get("text"));
        assertEquals("分类：我要退货", result.inputs().get("userPrompt"));
        assertEquals("你是客服", result.inputs().get("systemPrompt"));
        assertEquals("3", result.inputs().get("modelId"));

        ArgumentCaptor<TokenUsedEvent> captor = ArgumentCaptor.forClass(TokenUsedEvent.class);
        verify(events).publishEvent(captor.capture());
        TokenUsedEvent evt = captor.getValue();
        assertEquals(7L, evt.userId());
        assertEquals(42L, evt.appId());
        assertEquals(3L, evt.modelId());
        assertEquals(10, evt.promptTokens());
        assertEquals(5, evt.completionTokens());
    }

    @Test
    void systemPrompt缺省_传null给LlmCaller() {
        ChatClient client = mock(ChatClient.class);
        when(providerFacade.getChatClient(3L)).thenReturn(client);
        when(llmCaller.call(eq(client), eq(null), eq("hi"))).thenReturn(new LlmCallResult("ok", 1, 1));

        NodeResult result = executor.execute(node(Map.of("modelId", "3", "userPrompt", "hi")), ctx);

        assertEquals("ok", result.outputs().get("text"));
        assertNull(result.inputs().get("systemPrompt"));
    }

    @Test
    void 模型不可用_BizException原样上抛且不发事件() {
        when(providerFacade.getChatClient(3L))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "模型不可用"));
        assertThrows(BizException.class,
                () -> executor.execute(node(Map.of("modelId", "3", "userPrompt", "hi")), ctx));
        verify(events, never()).publishEvent(any());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=LlmNodeExecutorTest`
Expected: 编译错误（NodeExecutor/LlmCaller 等不存在）

- [x] **Step 3: 实现接口与 record**

`NodeExecutor.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphNode;

/**
 * 节点执行器：每类节点一个实现，按 type() 注册进 WorkflowEngine（唯一抽象点，spec §2）。
 * start/end 是引擎内建逻辑，不实现本接口。执行失败直接抛异常（引擎统一按节点失败处理，不重试）。
 */
public interface NodeExecutor {

    /** 对应 graph 里的 node.type（NodeType 枚举值）。 */
    String type();

    /** 执行节点：从 ctx 取上游输出做变量替换，返回（渲染后的输入快照, 本节点输出）。 */
    NodeResult execute(GraphNode node, RunContext ctx);
}
```

`NodeResult.java`：

```java
package com.hify.workflow.service.engine;

import java.util.Map;

/**
 * 节点执行结果：inputs=变量替换后的实际输入（落 node_run.inputs 供排障），outputs=节点输出（进 RunContext）。
 * inputs 可为 null（如 end 节点无独立输入）。
 */
public record NodeResult(Map<String, Object> inputs, Map<String, Object> outputs) {
}
```

`LlmCallResult.java`：

```java
package com.hify.workflow.service.engine;

/** 一次 LLM 同步调用的收敛结果（模块内自用，不跨模块，与 conversation.LlmReply 同型异地）。 */
public record LlmCallResult(String text, int promptTokens, int completionTokens) {
}
```

- [x] **Step 4: 实现 LlmCaller 与 LlmNodeExecutor**

`LlmCaller.java`（仿 `ChatInvoker`：薄 IO 适配不做单测，由集成/手动验收覆盖）：

```java
package com.hify.workflow.service.engine;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ChatClient 的薄适配（仿 conversation 的 ChatInvoker）：同步 call 收敛成 LlmCallResult。
 * 不带 @Transactional——真实外部 IO。韧性（信号量/三层超时/熔断）已在 provider 的 ResilientChatModel 内。
 */
@Service
public class LlmCaller {

    public LlmCallResult call(ChatClient client, String systemPrompt, String userPrompt) {
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            spec = spec.system(systemPrompt);
        }
        ChatResponse resp = spec.user(userPrompt).call().chatResponse();
        String text = resp.getResult().getOutput().getText();
        Usage usage = resp.getMetadata().getUsage();
        int promptTokens = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
        int completionTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
        return new LlmCallResult(text, promptTokens, completionTokens);
    }
}
```

`LlmNodeExecutor.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.common.event.TokenUsedEvent;
import com.hify.provider.api.ProviderFacade;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 节点：渲染提示词 → ProviderFacade 拿带韧性的 ChatClient → 同步调用 → 输出 {text} → 发 TokenUsedEvent。
 * 节点重试固定 0（llm-resilience：防节点×provider 重试风暴）。模型不可用由 getChatClient 抛 BizException，
 * 引擎按节点失败处理（spec §4：模型可用性不在保存时校验）。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private final ProviderFacade providerFacade;
    private final LlmCaller llmCaller;
    private final ApplicationEventPublisher events;

    public LlmNodeExecutor(ProviderFacade providerFacade, LlmCaller llmCaller, ApplicationEventPublisher events) {
        this.providerFacade = providerFacade;
        this.llmCaller = llmCaller;
        this.events = events;
    }

    @Override
    public String type() {
        return NodeType.LLM.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        long modelId = Long.parseLong(String.valueOf(node.data().get("modelId")));   // validator 已保证合法
        Object rawSystem = node.data().get("systemPrompt");
        String systemPrompt = rawSystem == null ? null : ctx.render(String.valueOf(rawSystem));
        String userPrompt = ctx.render(String.valueOf(node.data().get("userPrompt")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("modelId", String.valueOf(modelId));
        inputs.put("systemPrompt", systemPrompt);
        inputs.put("userPrompt", userPrompt);

        ChatClient client = providerFacade.getChatClient(modelId);
        LlmCallResult result = llmCaller.call(client, systemPrompt, userPrompt);
        events.publishEvent(new TokenUsedEvent(ctx.userId(), ctx.appId(), modelId,
                result.promptTokens(), result.completionTokens()));
        return new NodeResult(inputs, Map.of("text", result.text()));
    }
}
```

> 注意 `inputs` 用 `LinkedHashMap` 而非 `Map.of`：systemPrompt 可为 null，`Map.of` 会 NPE。

- [x] **Step 5: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=LlmNodeExecutorTest`
Expected: PASS（Tests run: 3）

- [x] **Step 6: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine server/src/test/java/com/hify/workflow/service/engine
git commit -m "feat(workflow): NodeExecutor 接口 + LlmCaller/LlmNodeExecutor（渲染提示词/调模型/发计量事件）"
```

---

### Task 7: WorkflowRunStore 短事务落库 + 僵尸自愈 + 分区维护

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/mapper/WorkflowRunMapper.java`（加 resetZombieRuns）
- Modify: `server/src/main/java/com/hify/workflow/mapper/WorkflowNodeRunMapper.java`（加 createMonthlyPartition/resetZombieNodeRuns）
- Create: `server/src/main/java/com/hify/workflow/service/WorkflowRunStore.java`
- Create: `server/src/main/java/com/hify/workflow/service/ZombieRunResetter.java`
- Create: `server/src/main/java/com/hify/workflow/service/WorkflowPartitionMaintainer.java`
- Test: `server/src/test/java/com/hify/workflow/service/WorkflowRunStoreTest.java`

**Interfaces:**
- Consumes: Task 3 的实体与 Mapper、`RunStatus`。
- Produces: 见总览契约 Task 7 行。**引擎/服务的所有落库只走本类**——每个写方法一个独立短事务，
  保证「LLM IO 永远在事务外」的红线可被 code review 一眼验证。

- [x] **Step 1: 写失败测试（真库）**

```java
package com.hify.workflow.service;

import com.hify.support.PgIntegrationTest;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRunStoreTest extends PgIntegrationTest {

    @Autowired
    private WorkflowRunStore store;
    @Autowired
    private WorkflowPartitionMaintainer maintainer;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createRun落库running并回填id() {
        WorkflowRun run = store.createRun(42L, 1L, 7L, Map.of("query", "hi"));
        assertNotNull(run.getId());
        WorkflowRun loaded = store.getRun(run.getId());
        assertEquals("running", loaded.getStatus());
        assertEquals("hi", loaded.getInputs().get("query"));
    }

    @Test
    void 节点日志_开工到收尾() {
        WorkflowRun run = store.createRun(42L, 1L, 7L, Map.of());
        Long nodeRunId = store.createNodeRun(run.getId(), "llm_1", "llm");
        assertNotNull(nodeRunId);
        store.finishNodeRun(nodeRunId, true, Map.of("userPrompt", "hi"), Map.of("text", "ok"), null, 120L);

        List<WorkflowNodeRun> nodeRuns = store.listNodeRuns(run.getId());
        assertEquals(1, nodeRuns.size());
        assertEquals("succeeded", nodeRuns.get(0).getStatus());
        assertEquals("ok", nodeRuns.get(0).getOutputs().get("text"));
        assertEquals(120L, nodeRuns.get(0).getElapsedMs());
    }

    @Test
    void run终态_成功带outputs_失败带原因() {
        WorkflowRun ok = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunSucceeded(ok.getId(), Map.of("answer", "退款类"), 800L);
        WorkflowRun loadedOk = store.getRun(ok.getId());
        assertEquals("succeeded", loadedOk.getStatus());
        assertEquals("退款类", loadedOk.getOutputs().get("answer"));
        assertNull(loadedOk.getErrorMessage());

        WorkflowRun bad = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunFailed(bad.getId(), "节点 llm_1 失败：模型不可用", 300L);
        WorkflowRun loadedBad = store.getRun(bad.getId());
        assertEquals("failed", loadedBad.getStatus());
        assertEquals("节点 llm_1 失败：模型不可用", loadedBad.getErrorMessage());
    }

    @Test
    void 僵尸重置_running的run与nodeRun全部置failed() {
        WorkflowRun zombie = store.createRun(42L, 1L, 7L, Map.of());
        store.createNodeRun(zombie.getId(), "llm_1", "llm");
        WorkflowRun done = store.createRun(42L, 1L, 7L, Map.of());
        store.markRunSucceeded(done.getId(), Map.of(), 10L);

        int reset = store.resetZombies();

        assertTrue(reset >= 2);   // 1 run + 1 node_run（≥ 防其他用例遗留）
        assertEquals("failed", store.getRun(zombie.getId()).getStatus());
        assertEquals("服务重启中断", store.getRun(zombie.getId()).getErrorMessage());
        assertEquals("failed", store.listNodeRuns(zombie.getId()).get(0).getStatus());
        assertEquals("succeeded", store.getRun(done.getId()).getStatus());   // 终态不受影响
    }

    @Test
    void 分区维护_确保未来月份分区存在() {
        maintainer.onStartup();
        YearMonth next = YearMonth.now().plusMonths(3);
        String name = String.format("workflow_node_run_%d_%02d", next.getYear(), next.getMonthValue());
        Integer count = jdbc.queryForObject(
                "select count(*) from pg_class where relname = ?", Integer.class, name);
        assertEquals(1, count);
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowRunStoreTest`
Expected: 编译错误（WorkflowRunStore 等不存在）

- [x] **Step 3: Mapper 加手写 SQL**

`WorkflowRunMapper.java` 加：

```java
    /** 启动自愈：同步执行下，启动时仍 running 的 run 必是上次重启遗留（spec §2）。 */
    @Update("update workflow_run set status = 'failed', error_message = '服务重启中断', update_time = now() "
            + "where status = 'running' and deleted = false")
    int resetZombieRuns();
```

（import `org.apache.ibatis.annotations.Update`）

`WorkflowNodeRunMapper.java` 加：

```java
    /**
     * 幂等补建月分区（DDL）。name/from/to 由 WorkflowPartitionMaintainer 从日期计算、非用户输入，
     * ${} 拼接安全（标识符/DDL 无法用 #{} 占位）。照抄 usage LlmCallLogMapper 先例。
     */
    @Update("create table if not exists ${name} partition of workflow_node_run "
            + "for values from ('${from}') to ('${to}')")
    void createMonthlyPartition(@Param("name") String name, @Param("from") String from, @Param("to") String to);

    /** 启动自愈：running 的节点日志随 run 一并置 failed。 */
    @Update("update workflow_node_run set status = 'failed', error_message = '服务重启中断', update_time = now() "
            + "where status = 'running'")
    int resetZombieNodeRuns();
```

（import `org.apache.ibatis.annotations.Param` / `org.apache.ibatis.annotations.Update`）

- [x] **Step 4: 实现 Store / Resetter / Maintainer**

`WorkflowRunStore.java`：

```java
package com.hify.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.workflow.constant.RunStatus;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowNodeRunMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * run/node_run 的落库出入口（仿 conversation 的 ConversationStore）：每个写方法一个独立短事务。
 * 引擎与服务层<b>不得</b>自带 @Transactional——LLM IO 必须发生在这些短事务之间（红线，spec §4）。
 */
@Service
public class WorkflowRunStore {

    private final WorkflowRunMapper runMapper;
    private final WorkflowNodeRunMapper nodeRunMapper;

    public WorkflowRunStore(WorkflowRunMapper runMapper, WorkflowNodeRunMapper nodeRunMapper) {
        this.runMapper = runMapper;
        this.nodeRunMapper = nodeRunMapper;
    }

    @Transactional
    public WorkflowRun createRun(Long appId, Long defId, Long userId, Map<String, Object> inputs) {
        WorkflowRun run = new WorkflowRun();
        run.setAppId(appId);
        run.setDefId(defId);
        run.setUserId(userId);
        run.setStatus(RunStatus.RUNNING.value());
        run.setInputs(inputs);
        runMapper.insert(run);
        return run;
    }

    @Transactional
    public void markRunSucceeded(Long runId, Map<String, Object> outputs, long elapsedMs) {
        WorkflowRun patch = new WorkflowRun();
        patch.setId(runId);
        patch.setStatus(RunStatus.SUCCEEDED.value());
        patch.setOutputs(outputs);
        patch.setElapsedMs(elapsedMs);
        runMapper.updateById(patch);
    }

    @Transactional
    public void markRunFailed(Long runId, String errorMessage, long elapsedMs) {
        WorkflowRun patch = new WorkflowRun();
        patch.setId(runId);
        patch.setStatus(RunStatus.FAILED.value());
        patch.setErrorMessage(errorMessage);
        patch.setElapsedMs(elapsedMs);
        runMapper.updateById(patch);
    }

    @Transactional
    public Long createNodeRun(Long runId, String nodeId, String nodeType) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setRunId(runId);
        nodeRun.setNodeId(nodeId);
        nodeRun.setNodeType(nodeType);
        nodeRun.setStatus(RunStatus.RUNNING.value());
        nodeRunMapper.insert(nodeRun);
        return nodeRun.getId();
    }

    @Transactional
    public void finishNodeRun(Long nodeRunId, boolean succeeded, Map<String, Object> inputs,
                              Map<String, Object> outputs, String errorMessage, long elapsedMs) {
        WorkflowNodeRun patch = new WorkflowNodeRun();
        patch.setId(nodeRunId);
        patch.setStatus(succeeded ? RunStatus.SUCCEEDED.value() : RunStatus.FAILED.value());
        patch.setInputs(inputs);
        patch.setOutputs(outputs);
        patch.setErrorMessage(errorMessage);
        patch.setElapsedMs(elapsedMs);
        nodeRunMapper.updateById(patch);
    }

    /** 启动自愈：遗留 running 全部置 failed。返回重置总条数（run + node_run）。 */
    @Transactional
    public int resetZombies() {
        return runMapper.resetZombieRuns() + nodeRunMapper.resetZombieNodeRuns();
    }

    public WorkflowRun getRun(Long runId) {
        return runMapper.selectById(runId);
    }

    /** 一次 run 的节点日志，按执行顺序（id 升序）。 */
    public List<WorkflowNodeRun> listNodeRuns(Long runId) {
        return nodeRunMapper.selectList(new LambdaQueryWrapper<WorkflowNodeRun>()
                .eq(WorkflowNodeRun::getRunId, runId).orderByAsc(WorkflowNodeRun::getId));
    }
}
```

`ZombieRunResetter.java`：

```java
package com.hify.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动自愈（deployment.md 预案）：同步执行下，启动时仍 running 的记录必是上次重启遗留，统一置 failed。幂等。 */
@Component
public class ZombieRunResetter {

    private static final Logger log = LoggerFactory.getLogger(ZombieRunResetter.class);

    private final WorkflowRunStore store;

    public ZombieRunResetter(WorkflowRunStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int reset = store.resetZombies();
        if (reset > 0) {
            log.warn("workflow 启动自愈：重置 {} 条遗留 running 记录为 failed", reset);
        }
    }
}
```

`WorkflowPartitionMaintainer.java`（照抄 usage `PartitionMaintainer` 模式，错峰 00:35）：

```java
package com.hify.workflow.service;

import com.hify.workflow.mapper.WorkflowNodeRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * workflow_node_run 月分区维护（db-standards §分区运维）。V21 初始建到 2026-12，本任务每月补建。
 * 模式照抄 usage.PartitionMaintainer（各模块自管分区，不跨模块共用类）。
 */
@Component
public class WorkflowPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPartitionMaintainer.class);
    /** 每次向前保证的月数（含当月），冗余覆盖调度漏跑/停机窗口。 */
    private static final int MONTHS_AHEAD = 3;

    private final WorkflowNodeRunMapper mapper;

    public WorkflowPartitionMaintainer(WorkflowNodeRunMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ensureFuturePartitions();
    }

    /** 每月 1 日 00:35（与 usage 的 00:30 错峰）补建未来分区。 */
    @Scheduled(cron = "0 35 0 1 * *")
    public void scheduled() {
        ensureFuturePartitions();
    }

    private void ensureFuturePartitions() {
        YearMonth base = YearMonth.now();
        for (int i = 0; i <= MONTHS_AHEAD; i++) {
            YearMonth ym = base.plusMonths(i);
            String name = String.format("workflow_node_run_%d_%02d", ym.getYear(), ym.getMonthValue());
            mapper.createMonthlyPartition(name, ym.atDay(1).toString(), ym.plusMonths(1).atDay(1).toString());
        }
        log.info("workflow_node_run 分区已确保至 {}", base.plusMonths(MONTHS_AHEAD));
    }
}
```

- [x] **Step 5: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowRunStoreTest`
Expected: PASS（Tests run: 5）

- [x] **Step 6: Commit**

```bash
git add server/src/main/java/com/hify/workflow server/src/test/java/com/hify/workflow/service/WorkflowRunStoreTest.java
git commit -m "feat(workflow): WorkflowRunStore 短事务落库 + 僵尸自愈 + node_run 分区维护"
```

---

### Task 8: WorkflowEngine（顺序驱动 + 失败即停）

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/WorkflowEngine.java`
- Create: `server/src/main/java/com/hify/workflow/service/engine/EngineResult.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/WorkflowEngineTest.java`

**Interfaces:**
- Consumes: Task 5/6/7 的 `RunContext`、`NodeExecutor/NodeResult`、`WorkflowRunStore`、`NodeType`。
- Produces: 见总览契约 Task 8 行。**本类无 @Transactional**（内部经 executor 发生 LLM IO）。

- [x] **Step 1: 写失败测试**

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineTest {

    private WorkflowRunStore store;
    private NodeExecutor llmExecutor;
    private WorkflowEngine engine;
    private RunContext ctx;

    private final GraphNode start = new GraphNode("start", "start",
            Map.of("inputs", List.of(Map.of("name", "query", "required", true))));
    private final GraphNode llm = new GraphNode("llm_1", "llm",
            Map.of("modelId", "3", "userPrompt", "{{start.query}}"));
    private final GraphNode end = new GraphNode("end", "end",
            Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))));

    @BeforeEach
    void setUp() {
        store = mock(WorkflowRunStore.class);
        AtomicLong seq = new AtomicLong();
        when(store.createNodeRun(anyLong(), anyString(), anyString()))
                .thenAnswer((Answer<Long>) inv -> seq.incrementAndGet());
        llmExecutor = mock(NodeExecutor.class);
        when(llmExecutor.type()).thenReturn("llm");
        engine = new WorkflowEngine(List.of(llmExecutor), store);
        ctx = new RunContext(7L, 42L);
    }

    @Test
    void 全链成功_end渲染最终输出_逐节点落日志() {
        when(llmExecutor.execute(eq(llm), any())).thenAnswer(inv -> {
            return new NodeResult(Map.of("userPrompt", "我要退货"), Map.of("text", "退款类"));
        });

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "我要退货"), ctx);

        assertTrue(result.succeeded());
        assertEquals("退款类", result.outputs().get("answer"));
        assertNull(result.errorMessage());
        // 三个节点各开工一次、成功收尾一次
        verify(store).createNodeRun(100L, "start", "start");
        verify(store).createNodeRun(100L, "llm_1", "llm");
        verify(store).createNodeRun(100L, "end", "end");
        verify(store).finishNodeRun(eq(1L), eq(true), any(), any(), isNull(), anyLong());
        verify(store).finishNodeRun(eq(2L), eq(true), any(), any(), isNull(), anyLong());
        verify(store).finishNodeRun(eq(3L), eq(true), isNull(), any(), isNull(), anyLong());
    }

    @Test
    void 中途节点失败_run失败_后续节点不执行() {
        when(llmExecutor.execute(eq(llm), any()))
                .thenThrow(new BizException(CommonError.DEPENDENCY_UNAVAILABLE, "模型不可用"));

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "hi"), ctx);

        assertFalse(result.succeeded());
        assertEquals("llm_1", result.failedNodeId());
        assertTrue(result.errorMessage().contains("模型不可用"));
        // llm 节点失败收尾；end 节点从未开工
        verify(store).finishNodeRun(eq(2L), eq(false), isNull(), isNull(), eq("模型不可用"), anyLong());
        verify(store, never()).createNodeRun(100L, "end", "end");
    }

    @Test
    void 非Biz异常_包一层节点执行异常前缀() {
        when(llmExecutor.execute(eq(llm), any()))
                .thenThrow(new IllegalStateException("变量引用的字段不存在：start.q"));

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "hi"), ctx);

        assertFalse(result.succeeded());
        assertTrue(result.errorMessage().contains("节点执行异常"));
        assertTrue(result.errorMessage().contains("变量引用的字段不存在"));
    }

    @Test
    void start输出即触发入参_可被下游引用() {
        when(llmExecutor.execute(eq(llm), any())).thenAnswer(inv -> {
            RunContext c = inv.getArgument(1);
            // 引擎必须先把 start 输出放进 ctx，llm 才能渲染 {{start.query}}
            return new NodeResult(Map.of(), Map.of("text", c.render("{{start.query}}")));
        });

        EngineResult result = engine.execute(100L, List.of(start, llm, end), Map.of("query", "我要退货"), ctx);

        assertTrue(result.succeeded());
        assertEquals("我要退货", result.outputs().get("answer"));
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowEngineTest`
Expected: 编译错误（WorkflowEngine/EngineResult 不存在）

- [x] **Step 3: 实现**

`EngineResult.java`：

```java
package com.hify.workflow.service.engine;

import java.util.Map;

/** 一次引擎执行的收敛结果：成功带最终输出；失败带失败节点与用户可读原因（写入 run.error_message）。 */
public record EngineResult(boolean succeeded, Map<String, Object> outputs,
                           String failedNodeId, String errorMessage) {

    public static EngineResult success(Map<String, Object> outputs) {
        return new EngineResult(true, outputs, null, null);
    }

    public static EngineResult failure(String failedNodeId, String errorMessage) {
        return new EngineResult(false, null, failedNodeId, errorMessage);
    }
}
```

`WorkflowEngine.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.common.exception.BizException;
import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 顺序执行器（spec 方案 A）：按拓扑序逐节点驱动，失败即停、不重试。
 * <b>本类禁止 @Transactional</b>——节点内发生真实 LLM IO；落库全走 store 的短事务。
 * start/end 为内建逻辑：start 把触发入参变成自己的输出；end 渲染 outputs 声明得到最终输出。
 */
@Component
public class WorkflowEngine {

    private final Map<String, NodeExecutor> executors;
    private final WorkflowRunStore store;

    public WorkflowEngine(List<NodeExecutor> executorList, WorkflowRunStore store) {
        this.executors = executorList.stream()
                .collect(Collectors.toUnmodifiableMap(NodeExecutor::type, e -> e));
        this.store = store;
    }

    public EngineResult execute(Long runId, List<GraphNode> ordered,
                                Map<String, Object> inputs, RunContext ctx) {
        Map<String, Object> finalOutputs = Map.of();
        for (GraphNode node : ordered) {
            Long nodeRunId = store.createNodeRun(runId, node.id(), node.type());
            long startAt = System.currentTimeMillis();
            try {
                NodeResult result = executeNode(node, inputs, ctx);
                ctx.putOutput(node.id(), result.outputs());
                store.finishNodeRun(nodeRunId, true, result.inputs(), result.outputs(),
                        null, System.currentTimeMillis() - startAt);
                if (NodeType.END.value().equals(node.type())) {
                    finalOutputs = result.outputs();
                }
            } catch (Exception e) {
                String reason = e instanceof BizException
                        ? e.getMessage()
                        : "节点执行异常：" + e.getMessage();
                store.finishNodeRun(nodeRunId, false, null, null,
                        reason, System.currentTimeMillis() - startAt);
                return EngineResult.failure(node.id(), "节点 " + node.id() + " 失败：" + reason);
            }
        }
        return EngineResult.success(finalOutputs);
    }

    private NodeResult executeNode(GraphNode node, Map<String, Object> inputs, RunContext ctx) {
        if (NodeType.START.value().equals(node.type())) {
            Map<String, Object> in = inputs == null ? Map.of() : inputs;
            return new NodeResult(in, in);
        }
        if (NodeType.END.value().equals(node.type())) {
            return new NodeResult(null, renderEndOutputs(node, ctx));
        }
        return executors.get(node.type()).execute(node, ctx);   // validator 已保证类型注册
    }

    /** end 节点：按 data.outputs 声明逐项渲染 {name, value 模板} → 最终输出 map。 */
    private Map<String, Object> renderEndOutputs(GraphNode node, RunContext ctx) {
        Object declared = node.data() == null ? null : node.data().get("outputs");
        Map<String, Object> out = new LinkedHashMap<>();
        if (declared instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.put(String.valueOf(m.get("name")), ctx.render(String.valueOf(m.get("value"))));
                }
            }
        }
        return out;
    }
}
```

> 测试里 `finishNodeRun(eq(2L), eq(false), …, eq("模型不可用"), …)` 验证的是 BizException 的 message
> 直接作为节点失败原因（不加前缀）；EngineResult 汇总时再补「节点 x 失败：」前缀写进 run。

- [x] **Step 4: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowEngineTest`
Expected: PASS（Tests run: 4）

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine server/src/test/java/com/hify/workflow/service/engine/WorkflowEngineTest.java
git commit -m "feat(workflow): WorkflowEngine 顺序驱动（start/end 内建、失败即停、节点日志逐一落库）"
```

---

### Task 9: WorkflowDraftService + 草稿 GET/PUT 接口

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/mapper/WorkflowDefMapper.java`（加 upsertDraft——db-standards §6.3：唯一约束下插或改一律 UPSERT）
- Create: `server/src/main/java/com/hify/workflow/dto/SaveDraftRequest.java`、`dto/DraftResponse.java`
- Create: `server/src/main/java/com/hify/workflow/service/WorkflowDraftService.java`
- Create: `server/src/main/java/com/hify/workflow/controller/WorkflowController.java`（本任务只有 draft 两端点，Task 10 扩 runs）
- Test: `server/src/test/java/com/hify/workflow/service/WorkflowDraftServiceTest.java`
- Test: `server/src/test/java/com/hify/workflow/controller/WorkflowControllerDraftTest.java`

**Interfaces:**
- Consumes: Task 2 的 `AppFacade.findWorkflowApp/WorkflowAppView`、Task 4 的 `GraphValidator`、Task 3 的 `WorkflowDefMapper/GraphDef`。
- Produces: 见总览契约 Task 9 行；路由 `GET/PUT /api/v1/workflow/apps/{appId}/draft`（spec 拍板 #6 单例子资源）。

- [x] **Step 1: 写失败测试（service）**

```java
package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.infra.security.CurrentUser;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.service.engine.GraphValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDraftServiceTest {

    private WorkflowDefMapper defMapper;
    private AppFacade appFacade;
    private WorkflowDraftService service;

    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);
    private final CurrentUser other = new CurrentUser(8L, "eve", CurrentUser.ROLE_MEMBER);
    private final CurrentUser admin = new CurrentUser(1L, "root", CurrentUser.ROLE_ADMIN);

    @BeforeEach
    void setUp() {
        defMapper = mock(WorkflowDefMapper.class);
        appFacade = mock(AppFacade.class);
        WorkflowProperties props = new WorkflowProperties();
        service = new WorkflowDraftService(defMapper, appFacade, new GraphValidator(props));
        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, true)));
    }

    private GraphDef legalGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void app不存在或非workflow_报10005() {
        when(appFacade.findWorkflowApp(99L)).thenReturn(Optional.empty());
        BizException ex = assertThrows(BizException.class, () -> service.getDraft(99L));
        assertEquals(10005, ex.errorCode().code());
    }

    @Test
    void 非owner非admin保存_报10004() {
        BizException ex = assertThrows(BizException.class,
                () -> service.saveDraft(42L, legalGraph(), other));
        assertEquals(10004, ex.errorCode().code());
        verify(defMapper, never()).upsertDraft(any(), any());
    }

    @Test
    void 非法图_报18001且不落库() {
        GraphDef illegal = new GraphDef(List.of(
                new GraphNode("start", "start", Map.of())), List.of());
        BizException ex = assertThrows(BizException.class,
                () -> service.saveDraft(42L, illegal, owner));
        assertEquals(18001, ex.errorCode().code());
        verify(defMapper, never()).upsertDraft(any(), any());
    }

    @Test
    void owner保存合法图_upsert并返回回读结果() {
        WorkflowDef stored = new WorkflowDef();
        stored.setAppId(42L);
        stored.setVersion(1);
        stored.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(stored);

        DraftResponse resp = service.saveDraft(42L, legalGraph(), owner);

        verify(defMapper).upsertDraft(eq(42L), any(GraphDef.class));
        assertEquals(3, resp.graph().nodes().size());
    }

    @Test
    void admin可保存他人应用的草稿() {
        when(defMapper.selectOne(any())).thenReturn(new WorkflowDef());
        service.saveDraft(42L, legalGraph(), admin);
        verify(defMapper).upsertDraft(eq(42L), any(GraphDef.class));
    }

    @Test
    void getDraft无草稿返回null_有草稿返回graph() {
        when(defMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getDraft(42L));

        WorkflowDef stored = new WorkflowDef();
        stored.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(stored);
        assertEquals(3, service.getDraft(42L).graph().nodes().size());
    }

    @Test
    void requireDraft无草稿_报10005() {
        when(defMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.requireDraft(42L));
        assertEquals(10005, ex.errorCode().code());
    }
}
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowDraftServiceTest`
Expected: 编译错误（WorkflowDraftService/DraftResponse/upsertDraft 不存在）

- [x] **Step 3: 实现 Mapper upsert + DTO + Service**

`WorkflowDefMapper.java` 加（import `org.apache.ibatis.annotations.Insert`/`Param`）：

```java
    /**
     * 草稿插或改一律 UPSERT（db-standards §6.3 禁先查后插）。冲突目标是部分唯一索引
     * workflow_def_app_version_uq，故 on conflict 需带 where 子句匹配索引谓词。
     */
    @Insert("insert into workflow_def (app_id, version, graph) values (#{appId}, 1, "
            + "#{graph,typeHandler=com.hify.workflow.config.GraphDefTypeHandler}) "
            + "on conflict (app_id, version) where deleted = false "
            + "do update set graph = excluded.graph, update_time = now()")
    void upsertDraft(@Param("appId") Long appId, @Param("graph") GraphDef graph);
```

`dto/SaveDraftRequest.java`：

```java
package com.hify.workflow.dto;

import jakarta.validation.constraints.NotNull;

/** 保存草稿请求（PUT 全量）：body 即整个画布。节点/连线的结构性校验在 GraphValidator，不在注解层。 */
public record SaveDraftRequest(@NotNull GraphDef graph) {
}
```

`dto/DraftResponse.java`：

```java
package com.hify.workflow.dto;

import java.time.OffsetDateTime;

/** 草稿视图。updateTime 供前端显示「上次保存时间」。 */
public record DraftResponse(GraphDef graph, OffsetDateTime updateTime) {
}
```

`service/WorkflowDraftService.java`：

```java
package com.hify.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.infra.security.CurrentUser;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.service.engine.GraphValidator;
import org.springframework.stereotype.Service;

/**
 * 草稿定义读写。W1 每 app 恒一条 version=1（spec 拍板 #3）；保存前过 GraphValidator，
 * 不合法的图拒绝入库。权限：读全员，写 owner 或 Admin（api-standards §6）。
 */
@Service
public class WorkflowDraftService {

    private final WorkflowDefMapper defMapper;
    private final AppFacade appFacade;
    private final GraphValidator validator;

    public WorkflowDraftService(WorkflowDefMapper defMapper, AppFacade appFacade, GraphValidator validator) {
        this.defMapper = defMapper;
        this.appFacade = appFacade;
        this.validator = validator;
    }

    /** 读草稿；应用必须存在且为 workflow 型。无草稿返回 null（api-standards §4：对象字段 null=无）。 */
    public DraftResponse getDraft(Long appId) {
        requireWorkflowApp(appId);
        WorkflowDef def = findDef(appId);
        return def == null ? null : new DraftResponse(def.getGraph(), def.getUpdateTime());
    }

    /** 全量保存草稿（UPSERT，无先查后插竞态）。校验是纯内存计算，UPSERT 是单条短 SQL，无需事务。 */
    public DraftResponse saveDraft(Long appId, GraphDef graph, CurrentUser user) {
        WorkflowAppView app = requireWorkflowApp(appId);
        if (!user.isAdmin() && !user.userId().equals(app.ownerId())) {
            throw new BizException(CommonError.FORBIDDEN, "仅创建者或管理员可编辑工作流");
        }
        validator.validateAndOrder(graph);
        defMapper.upsertDraft(appId, graph);
        WorkflowDef saved = findDef(appId);
        return new DraftResponse(saved.getGraph(), saved.getUpdateTime());
    }

    /** 触发运行前取草稿：无草稿=还没配置工作流，10005。 */
    public WorkflowDef requireDraft(Long appId) {
        WorkflowDef def = findDef(appId);
        if (def == null) {
            throw new BizException(CommonError.NOT_FOUND, "工作流尚未配置，请先保存草稿");
        }
        return def;
    }

    /** 应用存在性守卫（包内共享给 WorkflowRunService）。 */
    WorkflowAppView requireWorkflowApp(Long appId) {
        return appFacade.findWorkflowApp(appId)
                .orElseThrow(() -> new BizException(CommonError.NOT_FOUND, "应用不存在或不是工作流应用"));
    }

    private WorkflowDef findDef(Long appId) {
        return defMapper.selectOne(new LambdaQueryWrapper<WorkflowDef>()
                .eq(WorkflowDef::getAppId, appId)
                .eq(WorkflowDef::getVersion, 1));
    }
}
```

- [x] **Step 4: 实现 Controller（draft 部分）**

`controller/WorkflowController.java`：

```java
package com.hify.workflow.controller;

import com.hify.common.Result;
import com.hify.infra.security.CurrentUserHolder;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.SaveDraftRequest;
import com.hify.workflow.service.WorkflowDraftService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * workflow 成员接口（/api/v1/workflow/**，api-standards 路由三族）。
 * draft 是单例子资源（每 app 一份草稿，spec 拍板 #6）：GET 读 / PUT 全量写。
 * 协议层：@Valid → 当前用户 → service → Result；无业务逻辑、无 try-catch、无 @Transactional。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowDraftService draftService;

    public WorkflowController(WorkflowDraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping("/apps/{appId}/draft")
    public Result<DraftResponse> getDraft(@PathVariable Long appId) {
        return Result.ok(draftService.getDraft(appId));
    }

    @PutMapping("/apps/{appId}/draft")
    public Result<DraftResponse> saveDraft(@PathVariable Long appId,
                                           @Valid @RequestBody SaveDraftRequest request) {
        return Result.ok(draftService.saveDraft(appId, request.graph(), CurrentUserHolder.current()));
    }
}
```

- [x] **Step 5: 写 Controller 测试（照抄 DatasetControllerTest 的 @WebMvcTest 模式）**

```java
package com.hify.workflow.controller;

import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.workflow.dto.DraftResponse;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowDraftService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class WorkflowControllerDraftTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private WorkflowDraftService draftService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private DraftResponse sample() {
        GraphDef graph = new GraphDef(
                List.of(new GraphNode("start", "start", Map.of()),
                        new GraphNode("end", "end", Map.of())),
                List.of(new GraphEdge("start", "end")));
        return new DraftResponse(graph, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"));
    }

    @Test
    void 读草稿_返回graph结构() throws Exception {
        when(draftService.getDraft(42L)).thenReturn(sample());
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.graph.nodes[0].id").value("start"))
                .andExpect(jsonPath("$.data.graph.edges[0].target").value("end"));
    }

    @Test
    void 读草稿_无草稿data为null仍200() throws Exception {
        when(draftService.getDraft(42L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 保存草稿_全量PUT() throws Exception {
        when(draftService.saveDraft(eq(42L), any(GraphDef.class), any())).thenReturn(sample());
        mockMvc.perform(put("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"graph": {"nodes": [{"id":"start","type":"start","data":{}},
                                                      {"id":"end","type":"end","data":{}}],
                                           "edges": [{"source":"start","target":"end"}]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 保存草稿_缺graph报10001() throws Exception {
        mockMvc.perform(put("/api/v1/workflow/apps/42/draft")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void 未登录_401() throws Exception {
        mockMvc.perform(get("/api/v1/workflow/apps/42/draft"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [x] **Step 6: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest='WorkflowDraftServiceTest,WorkflowControllerDraftTest'`
Expected: PASS（Tests run: 12）

- [x] **Step 7: Commit**

```bash
git add server/src/main/java/com/hify/workflow server/src/test/java/com/hify/workflow
git commit -m "feat(workflow): 草稿单例子资源 GET/PUT（UPSERT 落库、保存前图校验、owner/Admin 写权限）"
```

---

### Task 10: WorkflowRunService + 触发/详情/历史接口

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/mapper/WorkflowRunMapper.java`（加游标分页两条 @Select）
- Create: `server/src/main/java/com/hify/workflow/service/RunCursor.java`
- Create: `server/src/main/java/com/hify/workflow/service/WorkflowRunService.java`
- Create: `server/src/main/java/com/hify/workflow/dto/RunRequest.java`、`dto/RunResponse.java`、`dto/NodeRunView.java`、`dto/RunSummaryView.java`
- Modify: `server/src/main/java/com/hify/workflow/controller/WorkflowController.java`（加 3 个端点）
- Test: `server/src/test/java/com/hify/workflow/service/WorkflowRunServiceTest.java`
- Test: `server/src/test/java/com/hify/workflow/service/RunCursorTest.java`
- Test: `server/src/test/java/com/hify/workflow/controller/WorkflowControllerRunTest.java`

**Interfaces:**
- Consumes: Task 4/5/7/8/9 全部产出；`UsageFacade.checkQuota(Long,Long)`；`CursorResult`（common，首个消费方）。
- Produces: 见总览契约 Task 10 行。触发前置检查顺序（spec §3）：app 存在(10005) → enabled(10006) →
  草稿存在(10005) → 配额(14001) → 图校验(18001) → 必填入参(10001)。运行失败仍 HTTP 200（拍板 #7）。

- [x] **Step 1: 写失败测试（RunCursor）**

```java
package com.hify.workflow.service;

import com.hify.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCursorTest {

    @Test
    void 编解码往返() {
        OffsetDateTime t = OffsetDateTime.parse("2026-07-09T10:30:00.123456+08:00");
        String cursor = RunCursor.encode(t, 42L);
        RunCursor.Cursor decoded = RunCursor.decode(cursor);
        assertEquals(t, decoded.createTime());
        assertEquals(42L, decoded.id());
    }

    @Test
    void 非法游标_报10001() {
        BizException ex = assertThrows(BizException.class, () -> RunCursor.decode("garbage!!"));
        assertEquals(10001, ex.errorCode().code());
    }
}
```

- [x] **Step 2: 写失败测试（RunService）**

```java
package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.infra.security.CurrentUser;
import com.hify.usage.api.UsageFacade;
import com.hify.workflow.config.WorkflowProperties;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.common.page.CursorResult;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowDefMapper;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.service.engine.EngineResult;
import com.hify.workflow.service.engine.GraphValidator;
import com.hify.workflow.service.engine.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunServiceTest {

    private AppFacade appFacade;
    private UsageFacade usageFacade;
    private WorkflowDefMapper defMapper;
    private WorkflowRunMapper runMapper;
    private WorkflowEngine engine;
    private WorkflowRunStore store;
    private WorkflowRunService service;

    private final CurrentUser user = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void setUp() {
        appFacade = mock(AppFacade.class);
        usageFacade = mock(UsageFacade.class);
        defMapper = mock(WorkflowDefMapper.class);
        runMapper = mock(WorkflowRunMapper.class);
        engine = mock(WorkflowEngine.class);
        store = mock(WorkflowRunStore.class);
        WorkflowDraftService draftService =
                new WorkflowDraftService(defMapper, appFacade, new GraphValidator(new WorkflowProperties()));
        service = new WorkflowRunService(appFacade, usageFacade, draftService,
                new GraphValidator(new WorkflowProperties()), engine, store, runMapper);

        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, true)));
        WorkflowDef def = new WorkflowDef();
        def.setId(5L);
        def.setAppId(42L);
        def.setGraph(legalGraph());
        when(defMapper.selectOne(any())).thenReturn(def);
    }

    private GraphDef legalGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "userPrompt", "{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    private WorkflowRun runningRun(Long id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus("running");
        return run;
    }

    @Test
    void 应用已停用_报10006且不创建run() {
        when(appFacade.findWorkflowApp(42L))
                .thenReturn(Optional.of(new WorkflowAppView(42L, 7L, false)));
        BizException ex = assertThrows(BizException.class,
                () -> service.run(42L, Map.of("query", "hi"), user));
        assertEquals(10006, ex.errorCode().code());
        verify(store, never()).createRun(any(), any(), any(), any());
    }

    @Test
    void 缺必填输入_报10001且不创建run() {
        BizException ex = assertThrows(BizException.class, () -> service.run(42L, Map.of(), user));
        assertEquals(10001, ex.errorCode().code());
        assertTrue(ex.getMessage().contains("query"));
        verify(store, never()).createRun(any(), any(), any(), any());
    }

    @Test
    void 成功链路_配额先查_引擎执行_终态succeeded() {
        when(store.createRun(eq(42L), eq(5L), eq(7L), anyMap())).thenReturn(runningRun(100L));
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
                .thenReturn(EngineResult.success(Map.of("answer", "退款类")));
        WorkflowRun done = runningRun(100L);
        done.setStatus("succeeded");
        done.setOutputs(Map.of("answer", "退款类"));
        when(store.getRun(100L)).thenReturn(done);
        when(store.listNodeRuns(100L)).thenReturn(List.of());

        RunResponse resp = service.run(42L, Map.of("query", "我要退货"), user);

        verify(usageFacade).checkQuota(7L, 42L);
        verify(store).markRunSucceeded(eq(100L), eq(Map.of("answer", "退款类")), anyLong());
        assertEquals("succeeded", resp.status());
        assertEquals("退款类", resp.outputs().get("answer"));
    }

    @Test
    void 引擎失败_终态failed_正常返回不抛异常() {
        when(store.createRun(eq(42L), eq(5L), eq(7L), anyMap())).thenReturn(runningRun(100L));
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
                .thenReturn(EngineResult.failure("llm_1", "节点 llm_1 失败：模型不可用"));
        WorkflowRun failed = runningRun(100L);
        failed.setStatus("failed");
        failed.setErrorMessage("节点 llm_1 失败：模型不可用");
        when(store.getRun(100L)).thenReturn(failed);
        when(store.listNodeRuns(100L)).thenReturn(List.of());

        RunResponse resp = service.run(42L, Map.of("query", "hi"), user);

        verify(store).markRunFailed(eq(100L), eq("节点 llm_1 失败：模型不可用"), anyLong());
        assertEquals("failed", resp.status());
        assertEquals("节点 llm_1 失败：模型不可用", resp.errorMessage());
    }

    @Test
    void getRun不存在_报10005() {
        when(store.getRun(999L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.getRun(999L));
        assertEquals(10005, ex.errorCode().code());
    }

    @Test
    void listRuns_多一条探测hasMore并给nextCursor() {
        WorkflowRun r1 = runningRun(3L);
        r1.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:03+08:00"));
        WorkflowRun r2 = runningRun(2L);
        r2.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:02+08:00"));
        WorkflowRun r3 = runningRun(1L);
        r3.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:01+08:00"));
        when(runMapper.firstPage(42L, 3)).thenReturn(List.of(r1, r2, r3));   // limit=2 → 查 3 条

        CursorResult<RunSummaryView> page = service.listRuns(42L, null, 2);

        assertEquals(2, page.list().size());
        assertTrue(page.hasMore());
        RunCursor.Cursor next = RunCursor.decode(page.nextCursor());
        assertEquals(2L, next.id());   // 游标=页内最后一行
    }

    @Test
    void listRuns_不足一页hasMore为false游标null() {
        WorkflowRun only = runningRun(1L);
        only.setCreateTime(OffsetDateTime.parse("2026-07-09T10:00:01+08:00"));
        when(runMapper.firstPage(42L, 21)).thenReturn(List.of(only));

        CursorResult<RunSummaryView> page = service.listRuns(42L, null, 20);

        assertEquals(1, page.list().size());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
    }

    @Test
    void listRuns_limit越界报10001() {
        BizException ex = assertThrows(BizException.class, () -> service.listRuns(42L, null, 101));
        assertEquals(10001, ex.errorCode().code());
    }
}
```

- [x] **Step 3: 运行确认失败**

Run: `mvn -f server/pom.xml test -Dtest='RunCursorTest,WorkflowRunServiceTest'`
Expected: 编译错误（RunCursor/WorkflowRunService/RunResponse 等不存在）

- [x] **Step 4: 实现 Mapper 游标 SQL + RunCursor + DTO**

`WorkflowRunMapper.java` 加（import `org.apache.ibatis.annotations.Select` 与 `java.time.OffsetDateTime`、`java.util.List`）：

```java
    /** 运行历史首页（摘要列，禁大列 inputs/outputs——db-standards §4 通用禁令）。多查一条探测 hasMore。 */
    @Select("select id, app_id, def_id, user_id, status, error_message, elapsed_ms, create_time, update_time "
            + "from workflow_run where app_id = #{appId} and deleted = false "
            + "order by create_time desc, id desc limit #{limit}")
    List<WorkflowRun> firstPage(@Param("appId") Long appId, @Param("limit") int limit);

    /** 游标翻页：keyset (create_time,id) 严格递减（db-standards §4 消息流模板同款）。 */
    @Select("select id, app_id, def_id, user_id, status, error_message, elapsed_ms, create_time, update_time "
            + "from workflow_run where app_id = #{appId} and deleted = false "
            + "and (create_time, id) < (#{createTime}, #{id}) "
            + "order by create_time desc, id desc limit #{limit}")
    List<WorkflowRun> afterCursor(@Param("appId") Long appId, @Param("createTime") OffsetDateTime createTime,
                                  @Param("id") Long id, @Param("limit") int limit);
```

`service/RunCursor.java`：

```java
package com.hify.workflow.service;

import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * 运行历史的游标编解码：排序键 (create_time, id) → Base64Url（api-standards §3.1：对客户端不透明，
 * 只能原样回传）。解码失败＝客户端自行构造或篡改，报 10001。
 */
final class RunCursor {

    private RunCursor() {
    }

    record Cursor(OffsetDateTime createTime, Long id) {
    }

    static String encode(OffsetDateTime createTime, Long id) {
        String raw = createTime.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new Cursor(OffsetDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            throw new BizException(CommonError.PARAM_INVALID, "无效的分页游标", e);
        }
    }
}
```

DTO 四件（`dto/` 下，全部 record；Long 由全局 Jackson 转 string）：

```java
package com.hify.workflow.dto;

import java.util.Map;

/** 触发运行请求。inputs 可空（无输入的工作流）；必填项校验依据 start 节点声明在 service 做。 */
public record RunRequest(Map<String, Object> inputs) {
}
```

```java
package com.hify.workflow.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 一次运行的完整视图（触发响应与详情共用）。失败时 status=failed + errorMessage，HTTP 仍 200（spec 拍板 #7）。 */
public record RunResponse(Long id, String status, Map<String, Object> inputs, Map<String, Object> outputs,
                          String errorMessage, Long elapsedMs, OffsetDateTime createTime,
                          List<NodeRunView> nodeRuns) {
}
```

```java
package com.hify.workflow.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/** 节点日志视图（随 RunResponse 返回，按执行顺序）。 */
public record NodeRunView(Long id, String nodeId, String nodeType, String status,
                          Map<String, Object> inputs, Map<String, Object> outputs,
                          String errorMessage, Long elapsedMs, OffsetDateTime createTime) {
}
```

```java
package com.hify.workflow.dto;

import java.time.OffsetDateTime;

/** 运行历史列表行（摘要，不带 inputs/outputs 大列）。 */
public record RunSummaryView(Long id, String status, String errorMessage,
                             Long elapsedMs, OffsetDateTime createTime) {
}
```

- [x] **Step 5: 实现 WorkflowRunService**

```java
package com.hify.workflow.service;

import com.hify.app.api.AppFacade;
import com.hify.app.api.WorkflowAppView;
import com.hify.common.exception.BizException;
import com.hify.common.exception.CommonError;
import com.hify.common.page.CursorResult;
import com.hify.infra.security.CurrentUser;
import com.hify.usage.api.UsageFacade;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.NodeRunView;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.entity.WorkflowDef;
import com.hify.workflow.entity.WorkflowNodeRun;
import com.hify.workflow.entity.WorkflowRun;
import com.hify.workflow.mapper.WorkflowRunMapper;
import com.hify.workflow.service.engine.EngineResult;
import com.hify.workflow.service.engine.GraphValidator;
import com.hify.workflow.service.engine.RunContext;
import com.hify.workflow.service.engine.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 触发编排（spec §2 链路）。<b>本类禁止 @Transactional</b>——engine.execute 内有 LLM IO；
 * 落库全在 WorkflowRunStore 短事务。前置检查顺序（spec §3）：
 * app 存在(10005) → enabled(10006) → 草稿存在(10005) → 配额(14001) → 图校验(18001) → 必填入参(10001)。
 */
@Service
public class WorkflowRunService {

    private final AppFacade appFacade;
    private final UsageFacade usageFacade;
    private final WorkflowDraftService draftService;
    private final GraphValidator validator;
    private final WorkflowEngine engine;
    private final WorkflowRunStore store;
    private final WorkflowRunMapper runMapper;

    public WorkflowRunService(AppFacade appFacade, UsageFacade usageFacade,
                              WorkflowDraftService draftService, GraphValidator validator,
                              WorkflowEngine engine, WorkflowRunStore store, WorkflowRunMapper runMapper) {
        this.appFacade = appFacade;
        this.usageFacade = usageFacade;
        this.draftService = draftService;
        this.validator = validator;
        this.engine = engine;
        this.store = store;
        this.runMapper = runMapper;
    }

    /** 同步触发一次运行：跑完（或失败）后返回完整 run。运行失败不抛异常——failed 是合法终态（拍板 #7）。 */
    public RunResponse run(Long appId, Map<String, Object> inputs, CurrentUser user) {
        WorkflowAppView app = draftService.requireWorkflowApp(appId);
        if (!app.enabled()) {
            throw new BizException(CommonError.CONFLICT, "应用已停用，无法触发运行");
        }
        WorkflowDef def = draftService.requireDraft(appId);
        usageFacade.checkQuota(user.userId(), appId);
        List<GraphNode> ordered = validator.validateAndOrder(def.getGraph());
        checkRequiredInputs(ordered.get(0), inputs);   // 拓扑序首位必为 start（唯一入度 0 节点）

        WorkflowRun run = store.createRun(appId, def.getId(), user.userId(), inputs);
        long startAt = System.currentTimeMillis();
        EngineResult result = engine.execute(run.getId(), ordered, inputs, new RunContext(user.userId(), appId));
        long elapsed = System.currentTimeMillis() - startAt;
        if (result.succeeded()) {
            store.markRunSucceeded(run.getId(), result.outputs(), elapsed);
        } else {
            store.markRunFailed(run.getId(), result.errorMessage(), elapsed);
        }
        return getRun(run.getId());
    }

    /** 运行详情 + 逐节点日志（全员可查，团队共享制）。 */
    public RunResponse getRun(Long runId) {
        WorkflowRun run = store.getRun(runId);
        if (run == null) {
            throw new BizException(CommonError.NOT_FOUND, "运行记录不存在");
        }
        List<NodeRunView> nodeRuns = store.listNodeRuns(runId).stream().map(this::toNodeView).toList();
        return new RunResponse(run.getId(), run.getStatus(), run.getInputs(), run.getOutputs(),
                run.getErrorMessage(), run.getElapsedMs(), run.getCreateTime(), nodeRuns);
    }

    /** 运行历史（游标分页，api-standards §3.1）。多查一条探测 hasMore。 */
    public CursorResult<RunSummaryView> listRuns(Long appId, String cursor, int limit) {
        if (limit < 1 || limit > 100) {
            throw new BizException(CommonError.PARAM_INVALID, "limit 必须在 1~100 之间");
        }
        draftService.requireWorkflowApp(appId);
        List<WorkflowRun> rows;
        if (StringUtils.hasText(cursor)) {
            RunCursor.Cursor c = RunCursor.decode(cursor);
            rows = runMapper.afterCursor(appId, c.createTime(), c.id(), limit + 1);
        } else {
            rows = runMapper.firstPage(appId, limit + 1);
        }
        boolean hasMore = rows.size() > limit;
        List<WorkflowRun> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore) {
            WorkflowRun last = page.get(page.size() - 1);
            nextCursor = RunCursor.encode(last.getCreateTime(), last.getId());
        }
        List<RunSummaryView> list = new ArrayList<>(page.stream().map(this::toSummary).toList());
        return CursorResult.of(list, nextCursor, hasMore);
    }

    /** start 节点声明的 required 输入必须齐全且非空白（10001，复用通用码）。 */
    private void checkRequiredInputs(GraphNode startNode, Map<String, Object> inputs) {
        Object declared = startNode.data() == null ? null : startNode.data().get("inputs");
        if (!(declared instanceof List<?> list)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("required"))) {
                String name = String.valueOf(m.get("name"));
                Object value = inputs == null ? null : inputs.get(name);
                if (value == null || String.valueOf(value).isBlank()) {
                    missing.add(name);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(CommonError.PARAM_INVALID, "缺少必填输入：" + String.join("、", missing));
        }
    }

    private NodeRunView toNodeView(WorkflowNodeRun n) {
        return new NodeRunView(n.getId(), n.getNodeId(), n.getNodeType(), n.getStatus(),
                n.getInputs(), n.getOutputs(), n.getErrorMessage(), n.getElapsedMs(), n.getCreateTime());
    }

    private RunSummaryView toSummary(WorkflowRun r) {
        return new RunSummaryView(r.getId(), r.getStatus(), r.getErrorMessage(),
                r.getElapsedMs(), r.getCreateTime());
    }
}
```

- [x] **Step 6: Controller 加 3 个端点**

`WorkflowController.java`：构造器注入加 `WorkflowRunService runService`；新增：

```java
    @PostMapping("/apps/{appId}/runs")
    public Result<RunResponse> run(@PathVariable Long appId, @RequestBody(required = false) RunRequest request) {
        Map<String, Object> inputs = request == null ? null : request.inputs();
        return Result.ok(runService.run(appId, inputs, CurrentUserHolder.current()));
    }

    @GetMapping("/apps/{appId}/runs")
    public Result<CursorResult<RunSummaryView>> listRuns(@PathVariable Long appId,
                                                         @RequestParam(required = false) String cursor,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(runService.listRuns(appId, cursor, limit));
    }

    @GetMapping("/runs/{id}")
    public Result<RunResponse> getRun(@PathVariable Long id) {
        return Result.ok(runService.getRun(id));
    }
```

（补 import：`org.springframework.web.bind.annotation.PostMapping`/`RequestParam`、
`com.hify.common.page.CursorResult`、`com.hify.workflow.dto.RunRequest/RunResponse/RunSummaryView`、`java.util.Map`）

- [x] **Step 7: 写 Controller 测试**

```java
package com.hify.workflow.controller;

import com.hify.common.page.CursorResult;
import com.hify.infra.config.JacksonConfig;
import com.hify.infra.security.CurrentUser;
import com.hify.infra.security.JwtService;
import com.hify.infra.security.RestAccessDeniedHandler;
import com.hify.infra.security.RestAuthenticationEntryPoint;
import com.hify.infra.security.SecurityConfig;
import com.hify.infra.security.SecurityResponseWriter;
import com.hify.workflow.dto.NodeRunView;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.dto.RunSummaryView;
import com.hify.workflow.service.WorkflowDraftService;
import com.hify.workflow.service.WorkflowRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
@Import({SecurityConfig.class, JwtService.class, SecurityResponseWriter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JacksonConfig.class})
@TestPropertySource(properties = "hify.security.jwt.secret=test-secret-test-secret-test-secret-0123456789")
class WorkflowControllerRunTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private WorkflowDraftService draftService;
    @MockitoBean
    private WorkflowRunService runService;

    private String memberToken() {
        return jwtService.generateToken(new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER));
    }

    private RunResponse succeededRun() {
        return new RunResponse(100L, "succeeded", Map.of("query", "我要退货"), Map.of("answer", "退款类"),
                null, 1200L, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"),
                List.of(new NodeRunView(1L, "start", "start", "succeeded", Map.of(), Map.of(),
                        null, 1L, OffsetDateTime.parse("2026-07-09T10:00:00+08:00"))));
    }

    @Test
    void 触发运行_返回完整run且Long为string() throws Exception {
        when(runService.run(eq(42L), any(), any())).thenReturn(succeededRun());
        mockMvc.perform(post("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputs\": {\"query\": \"我要退货\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("100"))
                .andExpect(jsonPath("$.data.status").value("succeeded"))
                .andExpect(jsonPath("$.data.outputs.answer").value("退款类"))
                .andExpect(jsonPath("$.data.nodeRuns[0].nodeId").value("start"));
    }

    @Test
    void 触发运行_body可省略() throws Exception {
        when(runService.run(eq(42L), isNull(), any())).thenReturn(succeededRun());
        mockMvc.perform(post("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void 运行历史_游标分页结构() throws Exception {
        when(runService.listRuns(42L, null, 20)).thenReturn(CursorResult.of(
                List.of(new RunSummaryView(100L, "succeeded", null, 1200L,
                        OffsetDateTime.parse("2026-07-09T10:00:00+08:00"))),
                "abc", true));
        mockMvc.perform(get("/api/v1/workflow/apps/42/runs")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].id").value("100"))
                .andExpect(jsonPath("$.data.nextCursor").value("abc"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }

    @Test
    void 运行详情() throws Exception {
        when(runService.getRun(100L)).thenReturn(succeededRun());
        mockMvc.perform(get("/api/v1/workflow/runs/100")
                        .header("Authorization", "Bearer " + memberToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("succeeded"));
    }
}
```

- [x] **Step 8: 运行确认通过**

Run: `mvn -f server/pom.xml test -Dtest='RunCursorTest,WorkflowRunServiceTest,WorkflowControllerRunTest'`
Expected: PASS（Tests run: 15）

- [x] **Step 9: Commit**

```bash
git add server/src/main/java/com/hify/workflow server/src/test/java/com/hify/workflow
git commit -m "feat(workflow): 同步触发/详情/游标历史三接口 + WorkflowRunService 编排（六道前置检查）"
```

---

### Task 11: 端到端集成测试 + 文档收尾 + 全量验证

**Files:**
- Test: `server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java`
- Modify: `docs/architecture/api-standards.md`（§2.1 补单例子资源条款，spec 拍板 #6 的入档义务）
- Modify: `docs/self-check.md`（追加本轮自检，记忆 self-check-per-step）

- [x] **Step 1: 写全链路集成测试（真库 + mock LLM 边界）**

```java
package com.hify.workflow;

import com.hify.infra.security.CurrentUser;
import com.hify.provider.api.ProviderFacade;
import com.hify.support.PgIntegrationTest;
import com.hify.workflow.dto.GraphDef;
import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.dto.RunResponse;
import com.hify.workflow.service.WorkflowDraftService;
import com.hify.workflow.service.WorkflowRunService;
import com.hify.workflow.service.engine.LlmCallResult;
import com.hify.workflow.service.engine.LlmCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1 黄金链路（真库真服务，只 mock LLM 边界）：建 app → 存草稿 → 触发 → 断言 run/node_run 落库。
 * TokenUsedEvent→usage 计量是 AFTER_COMMIT 监听，测试事务回滚不触发，不在此断言（手动验收覆盖）。
 */
class WorkflowRunFlowTest extends PgIntegrationTest {

    @Autowired
    private WorkflowDraftService draftService;
    @Autowired
    private WorkflowRunService runService;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ProviderFacade providerFacade;
    @MockitoBean
    private LlmCaller llmCaller;

    private Long appId;
    private final CurrentUser owner = new CurrentUser(7L, "bob", CurrentUser.ROLE_MEMBER);

    @BeforeEach
    void seed() {
        appId = jdbc.queryForObject(
                "insert into app (name, type, owner_id) values ('W1工单分类器', 'workflow', 7) returning id",
                Long.class);
        when(providerFacade.getChatClient(anyLong())).thenReturn(mock(ChatClient.class));
    }

    private GraphDef graph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("llm_1", "llm", Map.of("modelId", "3", "systemPrompt", "你是客服",
                        "userPrompt", "分类：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(Map.of("name", "answer", "value", "{{llm_1.text}}"))))),
                List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end")));
    }

    @Test
    void 黄金链路_存草稿_触发_三节点日志齐全() {
        when(llmCaller.call(any(), eq("你是客服"), eq("分类：我要退货")))
                .thenReturn(new LlmCallResult("退款类", 10, 5));

        draftService.saveDraft(appId, graph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "我要退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("退款类", resp.outputs().get("answer"));
        assertNotNull(resp.id());
        assertEquals(3, resp.nodeRuns().size());
        assertEquals(List.of("start", "llm_1", "end"),
                resp.nodeRuns().stream().map(n -> n.nodeId()).toList());
        assertTrue(resp.nodeRuns().stream().allMatch(n -> "succeeded".equals(n.status())));
        // 变量替换后的实际输入已落 node_run（spec §1.3）
        assertEquals("分类：我要退货", resp.nodeRuns().get(1).inputs().get("userPrompt"));

        // 真库行数据兜底断言（不经 DTO）
        Integer nodeRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ?", Integer.class, resp.id());
        assertEquals(3, nodeRows);
        String runStatus = jdbc.queryForObject(
                "select status from workflow_run where id = ?", String.class, resp.id());
        assertEquals("succeeded", runStatus);
    }

    @Test
    void 节点失败_run置failed_end不执行_HTTP语义由status表达() {
        when(llmCaller.call(any(), any(), any()))
                .thenThrow(new IllegalStateException("连接被重置"));

        draftService.saveDraft(appId, graph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "我要退货"), owner);   // 不抛异常

        assertEquals("failed", resp.status());
        assertTrue(resp.errorMessage().contains("llm_1"));
        assertEquals(2, resp.nodeRuns().size());   // start + llm_1，end 未开工
        assertEquals("failed", resp.nodeRuns().get(1).status());
    }

    @Test
    void 运行历史_游标翻页穿真库() {
        when(llmCaller.call(any(), any(), any())).thenReturn(new LlmCallResult("ok", 1, 1));
        draftService.saveDraft(appId, graph(), owner);
        for (int i = 0; i < 3; i++) {
            runService.run(appId, Map.of("query", "q" + i), owner);
        }

        var page1 = runService.listRuns(appId, null, 2);
        assertEquals(2, page1.list().size());
        assertTrue(page1.hasMore());

        var page2 = runService.listRuns(appId, page1.nextCursor(), 2);
        assertEquals(1, page2.list().size());
        assertTrue(!page2.hasMore());
    }
}
```

- [x] **Step 2: 运行确认通过（新测试 + 全量回归）**

Run: `mvn -f server/pom.xml test -Dtest=WorkflowRunFlowTest`
Expected: PASS（Tests run: 3）

Run: `mvn -f server/pom.xml verify`
Expected: BUILD SUCCESS，且 ModularityTests / LayerRulesTest 全绿（workflow 依赖不越白名单、DTO 不 import entity）。
任何失败先修再进下一步。

- [x] **Step 3: api-standards.md 补单例子资源条款**

在 §2.1 URL 规则的列表末尾（`- 查询/过滤/分页一律 query 参数…` 之后）追加：

```markdown
- **单例子资源**：父资源下至多一个实例的从属资源（如每个工作流应用仅一份画布草稿），
  允许单数名词路径：`GET/PUT /api/v1/workflow/apps/{appId}/draft`。GET 读、PUT 全量写，
  不提供集合形态与独立 id。（W1 拍板，2026-07-09）
```

- [x] **Step 4: docs/self-check.md 追加本轮自检**

按文件既有格式追加一节「Workflow W1」，内容覆盖：五条 DoD 验证命令与结果、三张表与分区确认、
ModularityTests 结果、遗留事项（若有）。

- [x] **Step 5: 手动验收（DoD，真实环境）**（2026-07-10 用户以 `docs/postman/workflow-w1.postman_collection.json` 实测通过：黄金链路 + 图非法 18001 + 缺输入 10001 + 模型不可用 200/failed）

前提：本地 postgres 与 server 已起（dev 常规方式），库里已有可用 chat 模型（provider 轮配好的，记其 id 为 `<MODEL_ID>`；admin 账号已存在——记忆 admin-account-seeded）。依次执行并核对：

```bash
# 1. 登录拿 JWT（admin 账密按本地实际）
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/identity/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<本地密码>"}' | jq -r '.data.token')

# 2. 建 workflow 应用（W1 解锁后应成功；此前会报 16001）
APP_ID=$(curl -s -X POST http://localhost:8080/api/v1/app/apps \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"W1工单分类器","type":"workflow"}' | jq -r '.data.id')

# 3. 保存草稿（<MODEL_ID> 换成真实模型 id）
curl -s -X PUT "http://localhost:8080/api/v1/workflow/apps/$APP_ID/draft" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"graph":{"nodes":[
        {"id":"start","type":"start","data":{"inputs":[{"name":"query","required":true}]}},
        {"id":"llm_1","type":"llm","data":{"modelId":"<MODEL_ID>","systemPrompt":"你是工单分类助手，只回答类别一个词",
                                            "userPrompt":"把这段工单内容分类（退款/投诉/咨询）：{{start.query}}"}},
        {"id":"end","type":"end","data":{"outputs":[{"name":"answer","value":"{{llm_1.text}}"}]}}],
      "edges":[{"source":"start","target":"llm_1"},{"source":"llm_1","target":"end"}]}}' | jq

# 4. 触发运行（真打一次 LLM）：期待 status=succeeded、outputs.answer 是分类词、nodeRuns 三条
curl -s -X POST "http://localhost:8080/api/v1/workflow/apps/$APP_ID/runs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"inputs":{"query":"我买的东西坏了，我要退钱！"}}' | jq

# 5. 历史与详情
curl -s "http://localhost:8080/api/v1/workflow/apps/$APP_ID/runs?limit=10" \
  -H "Authorization: Bearer $TOKEN" | jq
# 用上一步返回的 run id：
curl -s "http://localhost:8080/api/v1/workflow/runs/<RUN_ID>" -H "Authorization: Bearer $TOKEN" | jq

# 6. 失败路径一：图非法（有环）→ 18001
curl -s -X PUT "http://localhost:8080/api/v1/workflow/apps/$APP_ID/draft" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"graph":{"nodes":[{"id":"start","type":"start","data":{}},
        {"id":"a","type":"llm","data":{"modelId":"1","userPrompt":"x"}},
        {"id":"b","type":"llm","data":{"modelId":"1","userPrompt":"y"}},
        {"id":"end","type":"end","data":{}}],
      "edges":[{"source":"start","target":"a"},{"source":"a","target":"b"},
               {"source":"b","target":"a"},{"source":"b","target":"end"}]}}' | jq '.code'   # 期待 18001

# 7. 失败路径二：modelId 指向不存在的模型（草稿能存，触发后 run.status=failed 且 HTTP 200）
#    把步骤 3 的 modelId 改成 "999999" 重存草稿，再触发一次，核对 status=failed + errorMessage + nodeRuns 里 llm_1 failed

# 8. 配额与计量：查 usage（admin 视角或直接查库 daily_usage 表）确认第 4 步的 token 已计入
```

另核对：登录日志里 ZombieRunResetter/WorkflowPartitionMaintainer 的启动日志各出现一次。

- [x] **Step 6: 最终 Commit**

```bash
git add server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java docs/architecture/api-standards.md docs/self-check.md
git commit -m "test(workflow): W1 黄金链路集成测试；docs: 单例子资源入档 api-standards + 本轮自检"
```

---

## 计划自审记录（writing-plans Self-Review）

- **Spec 覆盖**：spec §0 决策 1-7 → Task 1（表）/3（graph 格式）/4（校验）/6（LLM 节点）/7（僵尸自愈）/8（引擎）/9（draft 单例）/10（同步触发+failed 语义+游标）/11（DoD+入档）逐一落位；spec §5 三层测试 → 单测（T4/5/6/8/9/10）+ Testcontainers（T1/3/7/11）+ ModularityTests（T11 verify）。
- **计划外发现**：AppService.create 原本只放行 chat（16001 拦 workflow）——已并入 Task 2 解锁，否则 DoD 建不出应用。
- **类型一致性**：跨任务签名以「跨任务接口契约」块为准，各任务代码均按此引用。
