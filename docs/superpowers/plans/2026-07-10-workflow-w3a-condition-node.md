# Workflow W3a 条件分支节点 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 新增 `condition` 节点（单条比较、二路 if/else），引擎支持分支执行与汇合，未选中路径节点落 `skipped` 记录。

**Architecture:** 保留 W1 拓扑序遍历骨架，执行前做「活边判定」（节点执行 ⇔ 存在活的入边）；被跳过节点的变量引用渲染为空串。V22 迁移放宽 node_run 状态 check；GraphEdge 加 `sourceHandle`（分支出口）、GraphNode 加 `position`（还 W1 画布留账）。

**Tech Stack:** Spring Boot 3.x / JUnit5 + Mockito / Testcontainers（PgIntegrationTest 基建已有）/ Flyway。

**Spec:** `docs/superpowers/specs/2026-07-10-workflow-w3a-condition-node-design.md`

## Global Constraints

- 与架构文档冲突的代码视为错误；禁改已合并的 V1–V21 迁移，只新增 V22
- 测试命令在 `server/` 下跑；**判定结果看退出码，禁止 grep "BUILD SUCCESS"**（`-q` 会静音）
- 运算符白名单 8 个：`==` `!=` `>` `>=` `<` `<=` `contains` `notContains`；数字比较用 BigDecimal，解析失败＝节点失败
- 引擎/服务类**禁止 @Transactional**（LLM IO 红线）；节点重试 0；失败即停
- skipped 记录：inputs/outputs/errorMessage 为 null，elapsedMs 为 0
- 提交信息用中文 conventional commits

---

### Task 1: V22 迁移 + RunStatus.SKIPPED + createSkippedNodeRun

**Files:**
- Create: `server/src/main/resources/db/migration/V22__node_run_status_add_skipped.sql`
- Modify: `server/src/main/java/com/hify/workflow/constant/RunStatus.java`
- Modify: `server/src/main/java/com/hify/workflow/service/WorkflowRunStore.java`（`finishNodeRun` 之后加方法）
- Test: `server/src/test/java/com/hify/workflow/service/WorkflowRunStoreTest.java`（追加用例）

**Interfaces:**
- Consumes: 既有 `WorkflowNodeRun` entity、`WorkflowNodeRunMapper`
- Produces: `RunStatus.SKIPPED`（value=`"skipped"`）；`void createSkippedNodeRun(Long runId, String nodeId, String nodeType)`——Task 6 引擎跳过时调用。

- [x] **Step 1: 写失败测试**

在 `WorkflowRunStoreTest.java` 追加（沿用该文件既有的建 run 方式；若测试里已有造 run 的辅助/常量则复用其 runId 来源）：

```java
@Test
void createSkippedNodeRun_落库status为skipped_无输入输出_耗时0() {
    WorkflowRun run = store.createRun(1L, 1L, 7L, Map.of());

    store.createSkippedNodeRun(run.getId(), "llm_b", "llm");

    var rows = store.listNodeRuns(run.getId());
    assertEquals(1, rows.size());
    assertEquals("skipped", rows.get(0).getStatus());
    assertEquals("llm_b", rows.get(0).getNodeId());
    assertNull(rows.get(0).getInputs());
    assertNull(rows.get(0).getOutputs());
    assertEquals(0L, rows.get(0).getElapsedMs());
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=WorkflowRunStoreTest test
```
Expected: 编译失败「找不到符号 createSkippedNodeRun」。退出码非 0。

- [x] **Step 3: 实现**

新建 `V22__node_run_status_add_skipped.sql`：

```sql
-- W3a：node_run 状态机加 skipped（未选中分支节点的落库状态，spec §3）。
-- 分区父表上 drop/add，子分区自动继承。约束名为 V21 内联 check 的 PG 默认命名。
alter table workflow_node_run drop constraint workflow_node_run_status_check;
alter table workflow_node_run add constraint workflow_node_run_status_check
    check (status in ('running', 'succeeded', 'failed', 'skipped'));
```

> 若 drop 报「约束不存在」（约束名与 PG 默认命名不符），先在 Testcontainers 日志/本地库执行
> `select conname from pg_constraint where conrelid = 'workflow_node_run'::regclass and contype = 'c'`
> 查真实名字后替换，并在最终报告偏差清单里说明。

`RunStatus.java` 加枚举值：

```java
public enum RunStatus {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    SKIPPED("skipped");
```

`WorkflowRunStore.java` 在 `finishNodeRun` 之后加：

```java
    /** 未选中分支上的节点：直接落终态 skipped（spec §3），无输入输出、耗时 0。 */
    @Transactional
    public void createSkippedNodeRun(Long runId, String nodeId, String nodeType) {
        WorkflowNodeRun nodeRun = new WorkflowNodeRun();
        nodeRun.setRunId(runId);
        nodeRun.setNodeId(nodeId);
        nodeRun.setNodeType(nodeType);
        nodeRun.setStatus(RunStatus.SKIPPED.value());
        nodeRun.setElapsedMs(0L);
        nodeRunMapper.insert(nodeRun);
    }
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=WorkflowRunStoreTest test
```
Expected: 全绿（V22 生效、check 放行 skipped），退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/resources/db/migration/V22__node_run_status_add_skipped.sql \
        server/src/main/java/com/hify/workflow/constant/RunStatus.java \
        server/src/main/java/com/hify/workflow/service/WorkflowRunStore.java \
        server/src/test/java/com/hify/workflow/service/WorkflowRunStoreTest.java
git commit -m "feat(workflow): V22 node_run 状态加 skipped + store 跳过落库方法"
```

---

### Task 2: GraphEdge.sourceHandle + GraphNode.position（还 W1 留账）

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/dto/GraphEdge.java`
- Modify: `server/src/main/java/com/hify/workflow/dto/GraphNode.java`
- Test: `server/src/test/java/com/hify/workflow/mapper/WorkflowMapperRoundtripTest.java`（追加用例）

**Interfaces:**
- Produces: `GraphEdge(String source, String target, String sourceHandle)` + 兼容旧构造 `GraphEdge(source, target)`（sourceHandle=null）；`GraphNode(String id, String type, Map<String,Object> data, Map<String,Object> position)` + 兼容旧构造（position=null）。**全库既有 `new GraphNode(id,type,data)` / `new GraphEdge(s,t)` 调用零改动**。Task 4/6 依赖 `sourceHandle()` 访问器。

- [x] **Step 1: 写失败测试**

在 `WorkflowMapperRoundtripTest.java` 追加：

```java
@Test
void position与sourceHandle_jsonb往返不丢() {
    GraphDef graph = new GraphDef(
            List.of(new GraphNode("start", "start", Map.of(), Map.of("x", 100, "y", 200)),
                    new GraphNode("end", "end", Map.of(), null)),
            List.of(new GraphEdge("start", "end", "true")));
    WorkflowDef def = new WorkflowDef();
    def.setAppId(1L);
    def.setVersion(1);
    def.setGraph(graph);
    defMapper.insert(def);

    GraphDef loaded = defMapper.selectById(def.getId()).getGraph();
    assertEquals(100, ((Number) loaded.nodes().get(0).position().get("x")).intValue());
    assertNull(loaded.nodes().get(1).position());
    assertEquals("true", loaded.edges().get(0).sourceHandle());
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=WorkflowMapperRoundtripTest test
```
Expected: 编译失败（record 无四参/三参构造）。退出码非 0。

- [x] **Step 3: 实现**

`GraphEdge.java` 整文件替换为：

```java
package com.hify.workflow.dto;

/**
 * 画布连线。sourceHandle：condition 节点出口标记（"true"/"false"），普通边为 null；
 * W3a 起引擎按它选路（spec §2），字段名对齐 Vue Flow。
 */
public record GraphEdge(String source, String target, String sourceHandle) {

    /** 普通边（无分支出口）。 */
    public GraphEdge(String source, String target) {
        this(source, target, null);
    }
}
```

`GraphNode.java` 整文件替换为：

```java
package com.hify.workflow.dto;

import java.util.Map;

/**
 * 画布节点：type ∈ NodeType；data 为节点私有配置（llm: modelId/systemPrompt/userPrompt；
 * condition: left/operator/right；start: inputs；end: outputs）。
 * position：画布坐标 {x,y}，引擎与校验不读，只保证 jsonb 往返不丢（W1 留账，spec §2）。
 */
public record GraphNode(String id, String type, Map<String, Object> data, Map<String, Object> position) {

    /** 无画布坐标的节点（API 直存草稿、测试构造用）。 */
    public GraphNode(String id, String type, Map<String, Object> data) {
        this(id, type, data, null);
    }
}
```

- [x] **Step 4: 跑测试 + 全模块编译（确认旧调用零破坏）**

```bash
cd server && mvn -Dtest=WorkflowMapperRoundtripTest test && mvn test-compile
```
Expected: 测试绿、`test-compile` 退出码 0（既有两参/三参调用走兼容构造）。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/dto/GraphEdge.java \
        server/src/main/java/com/hify/workflow/dto/GraphNode.java \
        server/src/test/java/com/hify/workflow/mapper/WorkflowMapperRoundtripTest.java
git commit -m "feat(workflow): GraphEdge 加 sourceHandle、GraphNode 加 position（还 W1 画布留账）"
```

---

### Task 3: ConditionEvaluator 求值器

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/ConditionEvaluator.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/ConditionEvaluatorTest.java`

**Interfaces:**
- Produces: `static boolean evaluate(String left, String operator, String right)`——非法运算符抛 `IllegalArgumentException`（validator 已拦，纯防御）；数字运算符遇非数字抛 `IllegalArgumentException`（消息含实际值）。Task 5 executor 调用。

- [x] **Step 1: 写失败测试**

新建 `ConditionEvaluatorTest.java`：

```java
package com.hify.workflow.service.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {

    @Test
    void 相等与不等_按字符串比较() {
        assertTrue(ConditionEvaluator.evaluate("退款类", "==", "退款类"));
        assertFalse(ConditionEvaluator.evaluate("退款类", "==", "咨询类"));
        assertTrue(ConditionEvaluator.evaluate("1.0", "!=", "1"));   // 刻意：字符串语义，不做数值归一
    }

    @Test
    void 数值比较_BigDecimal语义() {
        assertTrue(ConditionEvaluator.evaluate("2", ">", "0"));
        assertTrue(ConditionEvaluator.evaluate("2.5", ">=", "2.50"));   // BigDecimal compareTo 视为相等
        assertTrue(ConditionEvaluator.evaluate("-1", "<", "0"));
        assertTrue(ConditionEvaluator.evaluate("3", "<=", "3"));
        assertFalse(ConditionEvaluator.evaluate("0", ">", "0"));
    }

    @Test
    void 数值比较_两侧允许空白() {
        assertTrue(ConditionEvaluator.evaluate(" 2 ", ">", " 1 "));
    }

    @Test
    void 包含与不包含() {
        assertTrue(ConditionEvaluator.evaluate("我要退款", "contains", "退款"));
        assertFalse(ConditionEvaluator.evaluate("我要退款", "contains", "发票"));
        assertTrue(ConditionEvaluator.evaluate("我要退款", "notContains", "发票"));
    }

    @Test
    void 数字运算符遇非数字_抛IllegalArgument_消息含实际值() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("查到了", ">", "0"));
        assertTrue(ex.getMessage().contains("查到了"));
    }

    @Test
    void 空串参与数值比较_抛IllegalArgument() {   // 被跳过引用渲染为空串后误用数字比较的场景
        assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("", ">", "0"));
    }

    @Test
    void 非法运算符_抛IllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConditionEvaluator.evaluate("a", "=~", "b"));
        assertEquals(true, ex.getMessage().contains("=~"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=ConditionEvaluatorTest test
```
Expected: 编译失败「找不到符号 ConditionEvaluator」。退出码非 0。

- [x] **Step 3: 实现**

新建 `ConditionEvaluator.java`：

```java
package com.hify.workflow.service.engine;

import java.math.BigDecimal;

/**
 * 条件求值（spec §2）：==/!= 按字符串；> >= < <= 按 BigDecimal（解析失败即错，
 * 明确报错优于静默字典序）；contains/notContains 按字符串包含。无状态纯函数。
 */
final class ConditionEvaluator {

    private ConditionEvaluator() { }

    static boolean evaluate(String left, String operator, String right) {
        return switch (operator) {
            case "==" -> left.equals(right);
            case "!=" -> !left.equals(right);
            case "contains" -> left.contains(right);
            case "notContains" -> !left.contains(right);
            case ">", ">=", "<", "<=" -> numeric(left, operator, right);
            default -> throw new IllegalArgumentException("不支持的运算符：" + operator);   // validator 已拦，纯防御
        };
    }

    private static boolean numeric(String left, String operator, String right) {
        int c = parse(left, "左值").compareTo(parse(right, "右值"));
        return switch (operator) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            default -> c <= 0;
        };
    }

    private static BigDecimal parse(String value, String side) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值比较要求" + side + "是数字，实际为：\"" + value + "\"");
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=ConditionEvaluatorTest test
```
Expected: 7 条全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/ConditionEvaluator.java \
        server/src/test/java/com/hify/workflow/service/engine/ConditionEvaluatorTest.java
git commit -m "feat(workflow): 条件求值器（8 运算符，数字比较 BigDecimal 语义）"
```

---

### Task 4: NodeType.CONDITION + GraphValidator 分支校验

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/constant/NodeType.java`
- Modify: `server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java`（追加用例）

**Interfaces:**
- Consumes: `GraphEdge.sourceHandle()`（Task 2）
- Produces: `NodeType.CONDITION`（value=`"condition"`）；validator 规则：condition 必填 left/operator/right 且 operator 在白名单；condition 恰好两条出边且 sourceHandle 为 "true"/"false" 各一；非 condition 出边不得带 sourceHandle。

- [x] **Step 1: 写失败测试**

在 `GraphValidatorTest.java` 追加：

```java
// ==== W3a: condition 节点校验 ====

/** start → if_1 →(true) llm_a / (false) llm_b → end 的标准分支图，kbData 换成入参。 */
private GraphDef condGraph(Map<String, Object> condData, List<GraphEdge> edges) {
    return new GraphDef(List.of(
            new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "q", "required", true)))),
            new GraphNode("if_1", "condition", condData),
            new GraphNode("llm_a", "llm", Map.of("modelId", "3", "userPrompt", "A:{{start.q}}")),
            new GraphNode("llm_b", "llm", Map.of("modelId", "3", "userPrompt", "B:{{start.q}}")),
            new GraphNode("end", "end", Map.of("outputs", List.of(
                    Map.of("name", "r", "value", "{{llm_a.text}}{{llm_b.text}}"))))),
            edges);
}

private List<GraphEdge> goodCondEdges() {
    return List.of(new GraphEdge("start", "if_1"),
            new GraphEdge("if_1", "llm_a", "true"),
            new GraphEdge("if_1", "llm_b", "false"),
            new GraphEdge("llm_a", "end"), new GraphEdge("llm_b", "end"));
}

private Map<String, Object> goodCondData() {
    return Map.of("left", "{{start.q}}", "operator", "==", "right", "yes");
}

@Test
void condition_合法分支图_通过() {
    List<GraphNode> ordered = validator.validateAndOrder(condGraph(goodCondData(), goodCondEdges()));
    assertEquals(5, ordered.size());
}

@Test
void condition_缺operator_拒绝() {
    BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(
            condGraph(Map.of("left", "a", "right", "b"), goodCondEdges())));
    assertTrue(ex.getMessage().contains("operator"));
}

@Test
void condition_operator不在白名单_拒绝() {
    BizException ex = assertThrows(BizException.class, () -> validator.validateAndOrder(
            condGraph(Map.of("left", "a", "operator", "=~", "right", "b"), goodCondEdges())));
    assertTrue(ex.getMessage().contains("=~"));
}

@Test
void condition_出边不是两条_拒绝() {
    List<GraphEdge> edges = List.of(new GraphEdge("start", "if_1"),
            new GraphEdge("if_1", "llm_a", "true"),
            new GraphEdge("llm_a", "end"),
            new GraphEdge("if_1", "llm_b", "false"),
            new GraphEdge("if_1", "end", "true"),   // 第三条出边
            new GraphEdge("llm_b", "end"));
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(condGraph(goodCondData(), edges)));
    assertTrue(ex.getMessage().contains("两条出边"));
}

@Test
void condition_handle不是true_false各一_拒绝() {
    List<GraphEdge> edges = List.of(new GraphEdge("start", "if_1"),
            new GraphEdge("if_1", "llm_a", "true"),
            new GraphEdge("if_1", "llm_b", "true"),   // 两条都是 true
            new GraphEdge("llm_a", "end"), new GraphEdge("llm_b", "end"));
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(condGraph(goodCondData(), edges)));
    assertTrue(ex.getMessage().contains("sourceHandle"));
}

@Test
void 普通节点出边带handle_拒绝() {
    List<GraphEdge> edges = List.of(new GraphEdge("start", "if_1", "true"),   // start 出边带 handle
            new GraphEdge("if_1", "llm_a", "true"),
            new GraphEdge("if_1", "llm_b", "false"),
            new GraphEdge("llm_a", "end"), new GraphEdge("llm_b", "end"));
    BizException ex = assertThrows(BizException.class,
            () -> validator.validateAndOrder(condGraph(goodCondData(), edges)));
    assertTrue(ex.getMessage().contains("sourceHandle"));
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: `condition_合法分支图_通过` 报「未知节点类型：condition」，退出码非 0。

- [x] **Step 3: 实现**

`NodeType.java`：

```java
public enum NodeType {
    START("start"),
    LLM("llm"),
    KNOWLEDGE_RETRIEVAL("knowledge-retrieval"),
    CONDITION("condition"),
    END("end");
```

`GraphValidator.java` 三处修改。

(a) 类顶部（`VAR` 之后）加白名单常量：

```java
    /** condition 节点 operator 白名单（spec §2），与 ConditionEvaluator 支持的一致。 */
    static final Set<String> CONDITION_OPERATORS =
            Set.of("==", "!=", ">", ">=", "<", "<=", "contains", "notContains");
```

(b) 节点循环里（knowledge-retrieval 分支之后）加：

```java
            if (NodeType.CONDITION.value().equals(n.type())) {
                requireConditionFields(n);
            }
```

(c) 建完 `next`/`prev` 邻接表之后、连通性检查之前，加出边规则校验：

```java
        // 分支出边规则：condition 恰好两条出边且 handle 为 true/false 各一；普通节点出边不得带 handle
        for (GraphNode n : nodes) {
            List<GraphEdge> outs = edges.stream().filter(e -> n.id().equals(e.source())).toList();
            if (NodeType.CONDITION.value().equals(n.type())) {
                if (outs.size() != 2) {
                    throw invalid("condition 节点 " + n.id() + " 必须恰好两条出边，当前 " + outs.size() + " 条");
                }
                Set<String> handles = new HashSet<>();
                outs.forEach(e -> handles.add(e.sourceHandle()));
                if (!handles.equals(Set.of("true", "false"))) {
                    throw invalid("condition 节点 " + n.id() + " 的出边 sourceHandle 必须为 true/false 各一条");
                }
            } else {
                for (GraphEdge e : outs) {
                    if (e.sourceHandle() != null) {
                        throw invalid("非 condition 节点 " + n.id() + " 的出边不得带 sourceHandle");
                    }
                }
            }
        }
```

(d) 私有方法（`requireKnowledgeRetrievalFields` 之后）：

```java
    /** condition 节点字段校验；left/right 的变量引用合法性走既有引用拓扑序校验，此处只查必填与白名单。 */
    private void requireConditionFields(GraphNode n) {
        for (String field : List.of("left", "operator", "right")) {
            Object v = n.data() == null ? null : n.data().get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw invalid("condition 节点 " + n.id() + " 缺少 " + field);
            }
        }
        String op = String.valueOf(n.data().get("operator"));
        if (!CONDITION_OPERATORS.contains(op)) {
            throw invalid("condition 节点 " + n.id() + " 的 operator 非法：" + op);
        }
    }
```

（`java.util.Set`/`HashSet`/`List` 均已在该文件 import。）

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=GraphValidatorTest test
```
Expected: 全绿（含 W1/W2 既有用例不回归），退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/constant/NodeType.java \
        server/src/main/java/com/hify/workflow/service/engine/GraphValidator.java \
        server/src/test/java/com/hify/workflow/service/engine/GraphValidatorTest.java
git commit -m "feat(workflow): condition 节点类型与分支图校验（字段白名单/出边 handle 规则）"
```

---

### Task 5: ConditionNodeExecutor

**Files:**
- Create: `server/src/main/java/com/hify/workflow/service/engine/ConditionNodeExecutor.java`
- Test: `server/src/test/java/com/hify/workflow/service/engine/ConditionNodeExecutorTest.java`

**Interfaces:**
- Consumes: `ConditionEvaluator.evaluate`（Task 3）、`NodeType.CONDITION`（Task 4）、`RunContext.render`
- Produces: Spring `@Component`，`type()="condition"`，outputs=`{result: Boolean}`，inputs=`{left/operator/right: 渲染后}`。Task 6 引擎读 `outputs.get("result")` 选路。

- [x] **Step 1: 写失败测试**

新建 `ConditionNodeExecutorTest.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConditionNodeExecutorTest {

    private ConditionNodeExecutor executor;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        executor = new ConditionNodeExecutor();
        ctx = new RunContext(7L, 42L);
        ctx.putOutput("kb", Map.of("count", 2));
    }

    private GraphNode node(String left, String op, String right) {
        return new GraphNode("if_1", "condition", Map.of("left", left, "operator", op, "right", right));
    }

    @Test
    void 渲染左值_数值比较为真_inputs落渲染后的值() {
        NodeResult result = executor.execute(node("{{kb.count}}", ">", "0"), ctx);

        assertEquals(Boolean.TRUE, result.outputs().get("result"));
        assertEquals("2", result.inputs().get("left"));
        assertEquals(">", result.inputs().get("operator"));
        assertEquals("0", result.inputs().get("right"));
    }

    @Test
    void 比较为假_result为false() {
        NodeResult result = executor.execute(node("{{kb.count}}", "==", "0"), ctx);
        assertEquals(Boolean.FALSE, result.outputs().get("result"));
    }

    @Test
    void 数字比较遇非数字_抛NodeExecutionException_携带渲染后inputs() {
        ctx.putOutput("llm_1", Map.of("text", "查到了"));
        NodeExecutionException ex = assertThrows(NodeExecutionException.class,
                () -> executor.execute(node("{{llm_1.text}}", ">", "0"), ctx));
        assertEquals("查到了", ex.inputs().get("left"));
        assertEquals(IllegalArgumentException.class, ex.getCause().getClass());
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=ConditionNodeExecutorTest test
```
Expected: 编译失败「找不到符号 ConditionNodeExecutor」。退出码非 0。

- [x] **Step 3: 实现**

新建 `ConditionNodeExecutor.java`：

```java
package com.hify.workflow.service.engine;

import com.hify.workflow.constant.NodeType;
import com.hify.workflow.dto.GraphNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 条件节点：渲染 left/right → ConditionEvaluator 求值 → 输出 {result}。
 * 引擎按 result 与出边 sourceHandle 匹配选路（spec §3）。求值失败＝节点失败（如数字比较遇非数字）。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return NodeType.CONDITION.value();
    }

    @Override
    public NodeResult execute(GraphNode node, RunContext ctx) {
        String left = ctx.render(String.valueOf(node.data().get("left")));
        String operator = String.valueOf(node.data().get("operator"));   // validator 已保证在白名单
        String right = ctx.render(String.valueOf(node.data().get("right")));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("left", left);
        inputs.put("operator", operator);
        inputs.put("right", right);

        try {
            boolean result = ConditionEvaluator.evaluate(left, operator, right);
            return new NodeResult(inputs, Map.of("result", result));
        } catch (Exception e) {
            // 渲染已成功、求值才失败：渲染后的实际比较值随异常落 node_run.inputs 供排障
            throw new NodeExecutionException(inputs, e);
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

```bash
cd server && mvn -Dtest=ConditionNodeExecutorTest test
```
Expected: 3 条全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/ConditionNodeExecutor.java \
        server/src/test/java/com/hify/workflow/service/engine/ConditionNodeExecutorTest.java
git commit -m "feat(workflow): 条件节点 executor（输出 result，求值失败带 inputs 落库）"
```

---

### Task 6: RunContext 跳过语义 + WorkflowEngine 活边判定

**Files:**
- Modify: `server/src/main/java/com/hify/workflow/service/engine/RunContext.java`
- Modify: `server/src/main/java/com/hify/workflow/service/engine/WorkflowEngine.java`
- Modify: `server/src/main/java/com/hify/workflow/service/WorkflowRunService.java:72`（execute 调用加 edges 参数）
- Modify: `server/src/test/java/com/hify/workflow/service/WorkflowRunServiceTest.java:116,135,153`（mock 打桩补一个 `anyList()`）
- Test: `server/src/test/java/com/hify/workflow/service/engine/WorkflowEngineBranchTest.java`（新建）

**Interfaces:**
- Consumes: `RunStatus.SKIPPED`/`createSkippedNodeRun`（Task 1）、`GraphEdge.sourceHandle()`（Task 2）、`ConditionNodeExecutor`（Task 5）
- Produces: `WorkflowEngine.execute(Long runId, List<GraphNode> ordered, List<GraphEdge> edges, Map<String,Object> inputs, RunContext ctx)`（签名变更）；`RunContext.markSkipped(String nodeId)`；render 遇被跳过节点引用 → 空串。

- [x] **Step 1: 写失败测试**

新建 `WorkflowEngineBranchTest.java`（纯单测：mock store，真 ConditionNodeExecutor + 假 llm 执行器）：

```java
package com.hify.workflow.service.engine;

import com.hify.workflow.dto.GraphEdge;
import com.hify.workflow.dto.GraphNode;
import com.hify.workflow.service.WorkflowRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEngineBranchTest {

    private WorkflowRunStore store;
    private WorkflowEngine engine;
    private final List<String> executedLlm = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = mock(WorkflowRunStore.class);
        when(store.createNodeRun(anyLong(), anyString(), anyString())).thenReturn(1L);
        NodeExecutor fakeLlm = new NodeExecutor() {
            @Override public String type() { return "llm"; }
            @Override public NodeResult execute(GraphNode node, RunContext ctx) {
                executedLlm.add(node.id());
                return new NodeResult(Map.of(), Map.of("text", "答案-" + node.id()));
            }
        };
        engine = new WorkflowEngine(List.of(new ConditionNodeExecutor(), fakeLlm), store);
    }

    /** start → if_1 →(true) llm_a →(直连) llm_a2 → end；if_1 →(false) llm_b → end。 */
    private List<GraphNode> nodes(String left, String op, String right) {
        return List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("if_1", "condition", Map.of("left", left, "operator", op, "right", right)),
                new GraphNode("llm_a", "llm", Map.of()),
                new GraphNode("llm_a2", "llm", Map.of()),
                new GraphNode("llm_b", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_a2.text}}{{llm_b.text}}")))));
    }

    private List<GraphEdge> edges() {
        return List.of(new GraphEdge("start", "if_1"),
                new GraphEdge("if_1", "llm_a", "true"),
                new GraphEdge("llm_a", "llm_a2"),
                new GraphEdge("if_1", "llm_b", "false"),
                new GraphEdge("llm_a2", "end"), new GraphEdge("llm_b", "end"));
    }

    @Test
    void 条件为真_走true路_false路skipped_汇合end渲染跳过侧为空() {
        EngineResult result = engine.execute(9L, nodes("1", "==", "1"), edges(),
                Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals(List.of("llm_a", "llm_a2"), executedLlm);
        assertEquals("答案-llm_a2", result.outputs().get("r"));   // {{llm_b.text}} 渲染为空串
        verify(store).createSkippedNodeRun(9L, "llm_b", "llm");
    }

    @Test
    void 条件为假_走false路_true路连锁skipped() {
        EngineResult result = engine.execute(9L, nodes("1", "==", "2"), edges(),
                Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals(List.of("llm_b"), executedLlm);
        assertEquals("答案-llm_b", result.outputs().get("r"));
        verify(store).createSkippedNodeRun(9L, "llm_a", "llm");
        verify(store).createSkippedNodeRun(9L, "llm_a2", "llm");   // 连锁跳过
    }

    @Test
    void 直线图无分支_全执行_零skipped回归() {
        List<GraphNode> line = List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_1.text}}")))));
        List<GraphEdge> lineEdges = List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"));

        EngineResult result = engine.execute(9L, line, lineEdges, Map.of(), new RunContext(7L, 42L));

        assertTrue(result.succeeded());
        assertEquals("答案-llm_1", result.outputs().get("r"));
        verify(store, never()).createSkippedNodeRun(anyLong(), anyString(), anyString());
    }

    @Test
    void 执行过的节点缺字段_仍报错不吞() {
        List<GraphNode> line = List.of(
                new GraphNode("start", "start", Map.of()),
                new GraphNode("llm_1", "llm", Map.of()),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "r", "value", "{{llm_1.noSuchField}}")))));
        List<GraphEdge> lineEdges = List.of(new GraphEdge("start", "llm_1"), new GraphEdge("llm_1", "end"));

        EngineResult result = engine.execute(9L, line, lineEdges, Map.of(), new RunContext(7L, 42L));

        assertTrue(!result.succeeded());
        assertTrue(result.errorMessage().contains("end"));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

```bash
cd server && mvn -Dtest=WorkflowEngineBranchTest test
```
Expected: 编译失败（execute 无 5 参重载）。退出码非 0。

- [x] **Step 3: 实现**

`RunContext.java` 两处修改。字段区加：

```java
    private final Set<String> skipped = new HashSet<>();
```

（import 补 `java.util.Set`、`java.util.HashSet`。）`putOutput` 之后加方法：

```java
    /** 标记节点被分支跳过：其字段引用渲染为空串（spec §3 汇合语义）。 */
    public void markSkipped(String nodeId) {
        skipped.add(nodeId);
    }
```

`render` 的 while 循环体开头（取 `nodeId`/`field` 之后）插入：

```java
            if (skipped.contains(nodeId)) {
                m.appendReplacement(sb, "");
                continue;
            }
```

（其余分支不变：节点无输出且未标记跳过 → 仍抛 IllegalStateException；字段不存在 → 仍抛。）

`WorkflowEngine.java` 整类替换 execute 及新增 alive 方法（imports 补 `com.hify.workflow.dto.GraphEdge`、`java.util.ArrayList`、`java.util.HashMap`、`java.util.HashSet`、`java.util.Set`）：

```java
    public EngineResult execute(Long runId, List<GraphNode> ordered, List<GraphEdge> edges,
                                Map<String, Object> inputs, RunContext ctx) {
        Map<String, List<GraphEdge>> incoming = new HashMap<>();
        for (GraphEdge e : edges == null ? List.<GraphEdge>of() : edges) {
            incoming.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
        }
        Set<String> executed = new HashSet<>();
        Map<String, Boolean> conditionResults = new HashMap<>();

        Map<String, Object> finalOutputs = Map.of();
        for (GraphNode node : ordered) {
            if (!alive(node, incoming, executed, conditionResults)) {
                store.createSkippedNodeRun(runId, node.id(), node.type());
                ctx.markSkipped(node.id());
                continue;
            }
            Long nodeRunId = store.createNodeRun(runId, node.id(), node.type());
            long startAt = System.currentTimeMillis();
            try {
                NodeResult result = executeNode(node, inputs, ctx);
                ctx.putOutput(node.id(), result.outputs());
                store.finishNodeRun(nodeRunId, true, result.inputs(), result.outputs(),
                        null, System.currentTimeMillis() - startAt);
                executed.add(node.id());
                if (NodeType.CONDITION.value().equals(node.type())) {
                    conditionResults.put(node.id(), (Boolean) result.outputs().get("result"));
                }
                if (NodeType.END.value().equals(node.type())) {
                    finalOutputs = result.outputs();
                }
            } catch (Exception e) {
                // NodeExecutionException 只是运载壳：inputs 取快照、失败文案看真实 cause
                Map<String, Object> failedInputs = e instanceof NodeExecutionException nee ? nee.inputs() : null;
                Throwable actual = e instanceof NodeExecutionException ? e.getCause() : e;
                String reason = actual instanceof BizException
                        ? actual.getMessage()
                        : "节点执行异常：" + actual.getMessage();
                store.finishNodeRun(nodeRunId, false, failedInputs, null,
                        reason, System.currentTimeMillis() - startAt);
                return EngineResult.failure(node.id(), "节点 " + node.id() + " 失败：" + reason);
            }
        }
        return EngineResult.success(finalOutputs);
    }

    /**
     * 活边判定（spec §3）：节点执行 ⇔ 存在一条活的入边。
     * 前驱是 condition ⇒ 只有 sourceHandle 与求值结果一致的边被选中；普通前驱 ⇒ 恒选中。
     * 拓扑序遍历保证判定时前驱状态已定；失败即停保证 executed 不含失败节点。
     */
    private boolean alive(GraphNode node, Map<String, List<GraphEdge>> incoming,
                          Set<String> executed, Map<String, Boolean> conditionResults) {
        if (NodeType.START.value().equals(node.type())) {
            return true;
        }
        for (GraphEdge e : incoming.getOrDefault(node.id(), List.of())) {
            if (!executed.contains(e.source())) {
                continue;   // 前驱被跳过：这条边必然不活
            }
            Boolean cond = conditionResults.get(e.source());
            if (cond == null || String.valueOf(cond).equals(e.sourceHandle())) {
                return true;
            }
        }
        return false;
    }
```

（`executeNode`/`renderEndOutputs` 不变。）

`WorkflowRunService.java:72` 改为：

```java
            result = engine.execute(run.getId(), ordered, def.getGraph().edges(),
                    inputs, new RunContext(user.userId(), appId));
```

`WorkflowRunServiceTest.java` 116/135/153 三处打桩，把

```java
        when(engine.execute(eq(100L), anyList(), anyMap(), any()))
```

改为（只加一个 `anyList()`，断言不动）：

```java
        when(engine.execute(eq(100L), anyList(), anyList(), anyMap(), any()))
```

- [x] **Step 4: 跑引擎/服务/上下文相关测试**

```bash
cd server && mvn -Dtest='WorkflowEngineBranchTest,WorkflowRunServiceTest,GraphValidatorTest' test
```
Expected: 全绿，退出码 0。

- [x] **Step 5: Commit**

```bash
git add server/src/main/java/com/hify/workflow/service/engine/RunContext.java \
        server/src/main/java/com/hify/workflow/service/engine/WorkflowEngine.java \
        server/src/main/java/com/hify/workflow/service/WorkflowRunService.java \
        server/src/test/java/com/hify/workflow/service/WorkflowRunServiceTest.java \
        server/src/test/java/com/hify/workflow/service/engine/WorkflowEngineBranchTest.java
git commit -m "feat(workflow): 引擎活边判定支持分支执行，被跳过引用渲染空串"
```

---

### Task 7: 集成测试（分支两方向穿真库）

**Files:**
- Modify: `server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java`（追加图构造与两个测试方法）

**Interfaces:**
- Consumes: Task 1–6 全部产物；既有 `@MockitoBean knowledgeFacade`（W2 已加）、`llmCaller` 桩
- Produces: 无新接口，纯验证。

- [x] **Step 1: 写测试**

`WorkflowRunFlowTest` 追加（放 W2 用例之后）：

```java
    /** W3a：start → kb → if(count>0) → llm_hit / llm_miss → end 的分支图。 */
    private GraphDef branchGraph() {
        return new GraphDef(List.of(
                new GraphNode("start", "start", Map.of("inputs", List.of(Map.of("name", "query", "required", true)))),
                new GraphNode("kb", "knowledge-retrieval", Map.of("datasetIds", List.of(1), "query", "{{start.query}}")),
                new GraphNode("if_1", "condition", Map.of("left", "{{kb.count}}", "operator", ">", "right", "0")),
                new GraphNode("llm_hit", "llm", Map.of("modelId", "3", "userPrompt", "根据资料回答：{{kb.text}}")),
                new GraphNode("llm_miss", "llm", Map.of("modelId", "3", "userPrompt", "礼貌告知没查到：{{start.query}}")),
                new GraphNode("end", "end", Map.of("outputs", List.of(
                        Map.of("name", "answer", "value", "{{llm_hit.text}}{{llm_miss.text}}"))))),
                List.of(new GraphEdge("start", "kb"), new GraphEdge("kb", "if_1"),
                        new GraphEdge("if_1", "llm_hit", "true"),
                        new GraphEdge("if_1", "llm_miss", "false"),
                        new GraphEdge("llm_hit", "end"), new GraphEdge("llm_miss", "end")));
    }

    @Test
    void 分支_kb命中_走精答路_兜底路skipped() {
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of(
                new com.hify.knowledge.api.RetrievedChunk(11L, 1L, "手册", "七天无理由退货", 0.9)));
        when(llmCaller.call(any(), any(), eq("根据资料回答：[1] 七天无理由退货")))
                .thenReturn(new LlmCallResult("支持七天退货", 10, 5));

        draftService.saveDraft(appId, branchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("支持七天退货", resp.outputs().get("answer"));   // 跳过侧渲染空串
        assertEquals(6, resp.nodeRuns().size());   // 全节点都有记录（含 skipped）
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("succeeded", byId.get("llm_hit"));
        assertEquals("skipped", byId.get("llm_miss"));
        // 真库兜底：skipped 行确实过了 V22 check
        Integer skippedRows = jdbc.queryForObject(
                "select count(*) from workflow_node_run where run_id = ? and status = 'skipped'",
                Integer.class, resp.id());
        assertEquals(1, skippedRows);
    }

    @Test
    void 分支_kb未命中_走兜底路_精答路skipped() {
        when(knowledgeFacade.retrieve(any(), any())).thenReturn(List.of());
        when(llmCaller.call(any(), any(), eq("礼貌告知没查到：怎么退货")))
                .thenReturn(new LlmCallResult("抱歉没有找到相关资料", 8, 4));

        draftService.saveDraft(appId, branchGraph(), owner);
        RunResponse resp = runService.run(appId, Map.of("query", "怎么退货"), owner);

        assertEquals("succeeded", resp.status());
        assertEquals("抱歉没有找到相关资料", resp.outputs().get("answer"));
        var byId = resp.nodeRuns().stream()
                .collect(java.util.stream.Collectors.toMap(n -> n.nodeId(), n -> n.status()));
        assertEquals("skipped", byId.get("llm_hit"));
        assertEquals("succeeded", byId.get("llm_miss"));
        // 条件节点 inputs 落了实际比较值（排障可用性）
        var ifRun = resp.nodeRuns().stream().filter(n -> "if_1".equals(n.nodeId())).findFirst().orElseThrow();
        assertEquals("0", ifRun.inputs().get("left"));
    }
```

- [x] **Step 2: 跑集成测试**

```bash
cd server && mvn -Dtest=WorkflowRunFlowTest test
```
Expected: 若 Task 1–6 正确，两条新用例直接绿（端到端验证网）；W1/W2 既有 5 条不回归。退出码 0。

- [x] **Step 3: 全量回归**

```bash
cd server && mvn verify
```
Expected: 退出码 0（含 ModularityTests/ArchUnit/LayerRulesTest）。

- [x] **Step 4: Commit**

```bash
git add server/src/test/java/com/hify/workflow/WorkflowRunFlowTest.java
git commit -m "test(workflow): W3a 分支两方向集成测试（skipped 落库/汇合空串/条件 inputs 排障）"
```

---

### Task 8: Postman 验收集合 + 自检入档

**Files:**
- Create: `docs/postman/workflow-w3a.postman_collection.json`
- Modify: `docs/self-check.md`（追加 W3a 一节）

- [x] **Step 1: 写 Postman 集合**

以 `docs/postman/workflow-w2.postman_collection.json` 为骨架复制（保留登录/查模型/建应用与变量捕获脚本、`datasetId` 变量约定），改名 `Hify · Workflow W3a 条件分支`。**集合描述第一条写**：`【验收前必须重启 hify-server——旧进程不会加载新代码（W2 实测踩坑）】`，并保留 W2 的变量撞车说明。请求列表：

1. `0. 登录` / `1. 查可用 chat 模型` / `2. 创建 workflow 应用`——同 W2
2. `3. 保存分支草稿`——PUT `{{baseUrl}}/api/v1/workflow/apps/{{appId}}/draft`，body：

```json
{
  "graph": {
    "nodes": [
      {"id": "start", "type": "start", "data": {"inputs": [{"name": "query", "required": true}]}},
      {"id": "kb", "type": "knowledge-retrieval", "data": {"datasetIds": [{{datasetId}}], "query": "{{start.query}}"}},
      {"id": "if_1", "type": "condition", "data": {"left": "{{kb.count}}", "operator": ">", "right": "0"}},
      {"id": "llm_hit", "type": "llm", "data": {"modelId": "{{modelId}}", "systemPrompt": "只根据参考资料回答", "userPrompt": "参考资料：{{kb.text}}\n\n问题：{{start.query}}"}},
      {"id": "llm_miss", "type": "llm", "data": {"modelId": "{{modelId}}", "userPrompt": "知识库没有查到相关内容，请礼貌告知用户无法回答：{{start.query}}"}},
      {"id": "end", "type": "end", "data": {"outputs": [{"name": "answer", "value": "{{llm_hit.text}}{{llm_miss.text}}"}]}}
    ],
    "edges": [
      {"source": "start", "target": "kb"},
      {"source": "kb", "target": "if_1"},
      {"source": "if_1", "target": "llm_hit", "sourceHandle": "true"},
      {"source": "if_1", "target": "llm_miss", "sourceHandle": "false"},
      {"source": "llm_hit", "target": "end"},
      {"source": "llm_miss", "target": "end"}
    ]
  }
}
```

3. `4. 触发·命中方向（问知识库里有的问题）`——POST runs，body `{"inputs": {"query": "<改成知识库里真实内容的问题>"}}`；预期 succeeded、答案来自资料、详情里 llm_miss 为 skipped
4. `5. 触发·未命中方向（问无关问题）`——body `{"inputs": {"query": "今天天气怎么样"}}`；预期 succeeded、答案为礼貌兜底、llm_hit 为 skipped、if_1 的 inputs.left 为 "0"
5. `6.【失败路径准备】存数字比较遇非数字的草稿`——同请求 3 但 if_1 的 left 改为 `"{{kb.text}}"`（文本进数字比较）；预期保存 200（保存不求值）
6. `7.【失败路径】触发 → HTTP 200 但 run=failed`——预期 if_1 节点 failed，node_run.inputs 可见实际比较值，errorMessage 含「数值比较」
7. `8.【收尾】把好草稿存回去`——同请求 3

- [x] **Step 2: 校验 JSON 合法**

```bash
python3 -m json.tool docs/postman/workflow-w3a.postman_collection.json > /dev/null && echo OK
```
Expected: `OK`

- [x] **Step 3: self-check 入档**

按 `docs/self-check.md` 既有格式追加 W3a 一节：范围（V22/sourceHandle+position/求值器/validator/活边判定/集成测试/Postman）、五个拍板决策（单条比较、二路、skipped 落库、跳过引用空串、拓扑序+活边判定）、测试数据（各层条数、`mvn verify` 退出码）、DoD 待办（用户 Postman 实测三条路径，**先重启服务**）。

- [x] **Step 4: Commit**

```bash
git add docs/postman/workflow-w3a.postman_collection.json docs/self-check.md
git commit -m "docs(postman): W3a 验收集合（分支两方向 + 非数字比较失败路径）；自检入档"
```

---

## 验收 DoD（用户手动，反做假）

**先重启 hify-server**（W2 教训），Postman 跑 W3a 集合：
1. 命中方向：答案来自知识库、llm_miss 显示 skipped
2. 未命中方向：礼貌兜底答案、llm_hit 显示 skipped、if_1 的 inputs 可见 left="0"
3. 失败路径：文本进数字比较 → HTTP 200 + run failed + 可排障

全部通过后由用户确认，再走 finishing-a-development-branch 收尾。
